package com.tcrrry.lyrics.engine.hook

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.sqrt

/** A bounded snapshot covers native adapter replacement, without moving the page. */
internal class LyricsContentTransition {
    private class Swap(view: View, parent: ViewGroup, val image: ImageView, val bitmap: Bitmap, val alpha: Float) {
        val view = WeakReference(view)
        val parent = WeakReference(parent)
        var beforeDraw: ViewTreeObserver.OnPreDrawListener? = null
        var animator: ValueAnimator? = null
        var detach: View.OnAttachStateChangeListener? = null
        var timeout: Runnable? = null
    }
    private val active = WeakHashMap<View, Swap>()

    fun run(view: View, valid: () -> Boolean, replace: () -> Unit) {
        cancel(view)
        val parent = view.parent as? ViewGroup
        if (parent == null || !view.isAttachedToWindow || !view.isShown ||
            view.width <= 0 || view.height <= 0 || view.alpha <= 0f || !ValueAnimator.areAnimatorsEnabled() || !valid()) { replace(); return }
        val snapshot = runCatching {
            val scale = minOf(1f, sqrt(MAX_PIXELS.toDouble() / (view.width.toDouble() * view.height)).toFloat())
            val bitmap = Bitmap.createBitmap((view.width * scale).toInt().coerceAtLeast(1),
                (view.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            try { view.draw(Canvas(bitmap).apply { scale(scale, scale) }) }
            catch (error: Throwable) { bitmap.recycle(); throw error }
            bitmap
        }.getOrNull()
        if (snapshot == null) { replace(); return }
        val image = ImageView(view.context).apply {
            setImageBitmap(snapshot); scaleType = ImageView.ScaleType.FIT_XY
            isClickable = false; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            val x = (view.left + view.translationX - parent.scrollX).toInt()
            val y = (view.top + view.translationY - parent.scrollY).toInt()
            layout(x, y, x + view.width, y + view.height)
            alpha = view.alpha
        }
        val swap = Swap(view, parent, image, snapshot, view.alpha)
        active[view] = swap
        try { parent.overlay.add(image); view.alpha = 0f; replace() }
        catch (error: Throwable) { cancel(view); throw error }
        swap.beforeDraw = ViewTreeObserver.OnPreDrawListener {
            swap.beforeDraw?.let { if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnPreDrawListener(it) }
            swap.beforeDraw = null
            if (active[view] === swap && valid()) {
                swap.animator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = DURATION_MS; interpolator = DecelerateInterpolator()
                    addUpdateListener { frame ->
                        if (!valid()) { cancel(view); return@addUpdateListener }
                        val progress = frame.animatedValue as Float
                        view.alpha = swap.alpha * progress
                        image.alpha = swap.alpha * (1f - progress)
                        if (Build.VERSION.SDK_INT >= 31 && progress > 0f) {
                            val radius = progress * 6f * view.resources.displayMetrics.density
                            image.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
                        }
                    }
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) { if (active[view] === swap) cancel(view) }
                    })
                    start()
                }
            } else cancel(view)
            true
        }.also(view.viewTreeObserver::addOnPreDrawListener)
        swap.detach = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) { cancel(v) }
        }.also(view::addOnAttachStateChangeListener)
        swap.timeout = Runnable { if (active[view] === swap) cancel(view) }.also { view.postDelayed(it, 700L) }
        view.postInvalidateOnAnimation()
    }

    fun cancel(view: View) {
        val swap = active.remove(view) ?: return
        swap.beforeDraw?.let { if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnPreDrawListener(it) }
        swap.detach?.let(view::removeOnAttachStateChangeListener)
        swap.timeout?.let(view::removeCallbacks)
        swap.animator?.cancel()
        view.alpha = swap.alpha
        swap.parent.get()?.overlay?.remove(swap.image)
        swap.image.setImageDrawable(null)
        swap.bitmap.recycle()
    }
    fun close() { active.keys.toList().forEach(::cancel) }
    companion object { const val DURATION_MS = 240L; const val MAX_PIXELS = 524288 }
}
