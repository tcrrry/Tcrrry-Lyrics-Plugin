package com.tcrrry.lyrics.engine.ui

import android.animation.ValueAnimator
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.graphics.PointF
import android.graphics.Rect
import android.view.View
import android.view.ViewTreeObserver
import android.view.animation.PathInterpolator
import java.lang.ref.WeakReference

internal object SettingsDialogMotion {
    private var clickOrigin: Rect? = null
    private var closeTarget: ReturnTarget? = null

    internal class ReturnTarget(view: View) {
        private val view = WeakReference(view)
        private val saved = bounds(view)

        fun bounds(): Rect? {
            val current = view.get()
            return if (current != null && current.isAttachedToWindow) {
                if (!current.isShown) return null
                val location = IntArray(2)
                current.getLocationOnScreen(location)
                Rect().takeIf { current.getLocalVisibleRect(it) && !it.isEmpty }?.apply {
                    offset(location[0], location[1])
                }
            } else saved?.let(::Rect)
        }

        private fun bounds(view: View): Rect? {
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            return Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
                .takeUnless { it.isEmpty }
        }
    }

    fun from(view: View, action: () -> Unit) = from(view, null, action = action)

    fun from(view: View, touch: PointF?, returnTo: View = view, action: () -> Unit) {
        val previous = clickOrigin
        val previousTarget = closeTarget
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        clickOrigin = if (touch != null && touch.x.isFinite() && touch.y.isFinite()) {
            Rect(touch.x.toInt(), touch.y.toInt(), touch.x.toInt(), touch.y.toInt())
        } else Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
        closeTarget = ReturnTarget(returnTo)
        try { SettingsSurfaceContext.from(view, action) } finally { clickOrigin = previous; closeTarget = previousTarget }
    }

    fun origin(): Rect? = clickOrigin?.let(::Rect)
    fun returnTarget(): ReturnTarget? = closeTarget

    private fun anchor(panel: View, origin: Rect?) {
        val location = IntArray(2)
        layoutPositionOnScreen(panel, location)
        panel.pivotX = origin?.let { it.exactCenterX() - location[0] } ?: (panel.width / 2f)
        panel.pivotY = origin?.let { it.exactCenterY() - location[1] } ?: (panel.height / 2f)
    }

    /** Ignore the reveal transform while keeping real layout and scroll changes. */
    fun layoutPositionOnScreen(view: View, result: IntArray) {
        var node = view
        var x = 0
        var y = 0
        while (true) {
            val parent = node.parent as? View ?: break
            x += node.left - parent.scrollX
            y += node.top - parent.scrollY
            node = parent
        }
        node.getLocationOnScreen(result)
        result[0] += x
        result[1] += y
    }

    fun reveal(panel: View, scrim: View, origin: Rect?, background: View? = null,
        prepare: ((() -> Unit) -> Unit)): () -> Unit {
        var started = false
        var stopped = false
        fun animate() {
            if (started || stopped || !panel.isAttachedToWindow) return
            started = true
            if (!ValueAnimator.areAnimatorsEnabled()) {
                panel.alpha = 1f
                scrim.alpha = 1f
                return
            }
            val startScale = if (origin == null) .92f else .08f
            anchor(panel, origin)
            // Cache only the glass, so text and controls can animate independently.
            background?.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            background?.buildLayer()
            panel.scaleX = startScale
            panel.scaleY = startScale
            if (origin != null) panel.alpha = 1f
            panel.translationX = 0f
            panel.translationY = 0f
            panel.animate().alpha(1f).scaleX(1f).scaleY(1f).translationX(0f).translationY(0f)
                .setDuration(if (origin == null) 240L else 340L)
                .setInterpolator(PathInterpolator(.18f, .78f, .2f, 1f))
                .start()
            scrim.animate().alpha(1f).setDuration(240L).start()
        }
        val timeout = Runnable { animate() }
        panel.alpha = 0f
        scrim.alpha = 0f
        val preDraw = object : ViewTreeObserver.OnPreDrawListener {
            private var previous: Rect? = null
            private var rootWidth = 0
            private var rootHeight = 0
            override fun onPreDraw(): Boolean {
                if (stopped) return true
                val location = IntArray(2)
                layoutPositionOnScreen(panel, location)
                val bounds = Rect(location[0], location[1], location[0] + panel.width, location[1] + panel.height)
                val root = panel.rootView
                val stable = panel.width > 0 && panel.height > 0 && bounds == previous &&
                    root.width == rootWidth && root.height == rootHeight
                previous = bounds
                rootWidth = root.width
                rootHeight = root.height
                if (!stable) {
                    panel.postInvalidateOnAnimation()
                    return true
                }
                panel.viewTreeObserver.removeOnPreDrawListener(this)
                prepare {
                    if (!stopped) panel.postOnAnimation { panel.postOnAnimation {
                        panel.removeCallbacks(timeout)
                        animate()
                    } }
                }
                panel.postDelayed(timeout, 180L)
                return true
            }
        }
        panel.viewTreeObserver.addOnPreDrawListener(preDraw)
        fun stop() {
            stopped = true
            if (panel.viewTreeObserver.isAlive) panel.viewTreeObserver.removeOnPreDrawListener(preDraw)
            panel.removeCallbacks(timeout)
            panel.animate().withEndAction(null).cancel()
            scrim.animate().cancel()
        }
        panel.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                stop()
                background?.setLayerType(View.LAYER_TYPE_NONE, null)
            }
        })
        return ::stop
    }

    fun conceal(panel: View, scrim: View, origin: Rect?, background: View? = null, complete: () -> Unit) {
        if (!panel.isAttachedToWindow || panel.alpha <= 0f || !ValueAnimator.areAnimatorsEnabled()) {
            complete()
            return
        }
        val parent = panel.parent as? View
        val morph = if (parent != null && origin != null && !origin.isEmpty)
            runCatching { SettingsContainerMorph.capture(panel, parent, origin, background) }
                .onFailure { android.util.Log.w("TcrrrySettings", "Container capture unavailable; using fade", it) }
                .getOrNull() else null
        if (morph != null && parent != null) {
            parent.overlay.add(morph)
            panel.alpha = 0f
            val animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 340L
                interpolator = PathInterpolator(.2f, 0f, 0f, 1f)
                addUpdateListener { morph.progress = it.animatedValue as Float }
            }
            var finished = false
            lateinit var detach: View.OnAttachStateChangeListener
            fun finish() {
                if (finished) return
                finished = true
                panel.removeOnAttachStateChangeListener(detach)
                parent.overlay.remove(morph)
                morph.close()
                complete()
            }
            detach = object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = Unit
                override fun onViewDetachedFromWindow(v: View) { animator.cancel(); finish() }
            }
            panel.addOnAttachStateChangeListener(detach)
            animator.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { finish() }
            })
            animator.start()
            scrim.animate().alpha(0f).setDuration(300L).start()
            return
        }
        // If the source no longer exists, dissolve locally rather than fly to a stale point.
        panel.animate().alpha(0f).scaleX(panel.scaleX * .96f).scaleY(panel.scaleY * .96f)
            .setDuration(220L).setInterpolator(PathInterpolator(.4f, 0f, .2f, 1f))
            .withEndAction { panel.alpha = 0f; complete() }.start()
        scrim.animate().alpha(0f).setDuration(180L).start()
    }
}
