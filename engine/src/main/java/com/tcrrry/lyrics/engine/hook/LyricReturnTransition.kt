package com.tcrrry.lyrics.engine.hook

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Position once, then show a bounded visual return. No offscreen lyric rows
 * are laid out for a long RecyclerView journey back through the entire song.
 */
internal class LyricReturnTransition {
    private class Trip(view: View, val baseline: Float) {
        val view = WeakReference(view)
        var animator: ValueAnimator? = null
        var beforeDraw: ViewTreeObserver.OnPreDrawListener? = null
        var detach: View.OnAttachStateChangeListener? = null
    }
    private val active = WeakHashMap<View, Trip>()
    fun run(list: View, valid: () -> Boolean, position: () -> Unit): Boolean {
        cancel(list)
        if (!list.isAttachedToWindow || !valid()) return false
        val trip = Trip(list, list.translationY)
        active[list] = trip
        val distance = minOf(list.height * .22f, 120f * list.resources.displayMetrics.density)
        list.translationY = trip.baseline - distance
        try { position() } catch (error: Throwable) { cancel(list); throw error }
        val listener = ViewTreeObserver.OnPreDrawListener {
            val current = trip.view.get()
            if (current != null) {
                trip.beforeDraw?.let { if (current.viewTreeObserver.isAlive) current.viewTreeObserver.removeOnPreDrawListener(it) }
                trip.beforeDraw = null
                if (active[current] === trip && valid()) {
                    trip.animator = ValueAnimator.ofFloat(current.translationY, trip.baseline).apply {
                        duration = DURATION_MS
                        interpolator = DecelerateInterpolator()
                        addUpdateListener { frame -> trip.view.get()?.translationY = frame.animatedValue as Float }
                        addListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) { trip.view.get()?.let { if (active[it] === trip) cancel(it) } }
                        })
                        start()
                    }
                } else cancel(current)
            }
            true
        }
        trip.beforeDraw = listener
        list.viewTreeObserver.addOnPreDrawListener(listener)
        trip.detach = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) { cancel(v) }
        }.also(list::addOnAttachStateChangeListener)
        list.postInvalidateOnAnimation()
        return true
    }
    fun cancel(view: View) {
        val trip = active.remove(view) ?: return
        trip.beforeDraw?.let { if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnPreDrawListener(it) }
        trip.detach?.let(view::removeOnAttachStateChangeListener)
        trip.animator?.cancel()
        view.translationY = trip.baseline
    }
    fun close() { active.keys.toList().forEach(::cancel) }
    companion object { const val DURATION_MS = 280L }
}
