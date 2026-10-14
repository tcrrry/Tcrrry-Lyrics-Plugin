package com.tcrrry.lyrics.engine.ui

import android.content.Context
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView

/** A one-way ticker whose clock belongs to the visible page, not window focus. */
internal class SmoothSourceTicker(context: Context, color: Int) : FrameLayout(context) {
    internal val motion = SourceTickerMotion(30f * resources.displayMetrics.density)
    val label = object : TextView(context) {
        override fun onDraw(canvas: Canvas) {
            val textLayout = layout ?: return
            paint.color = currentTextColor
            paint.drawableState = drawableState
            val saved = canvas.save()
            canvas.clipRect(compoundPaddingLeft, compoundPaddingTop,
                width - compoundPaddingRight, height - compoundPaddingBottom)
            val top = compoundPaddingTop +
                (height - compoundPaddingTop - compoundPaddingBottom - textLayout.height) / 2f
            canvas.translate(compoundPaddingLeft - motion.positionPx, top)
            textLayout.draw(canvas)
            if (motion.overflow) {
                canvas.translate(motion.periodPx, 0f)
                textLayout.draw(canvas)
            }
            canvas.restoreToCount(saved)
        }

        override fun getLeftFadingEdgeStrength(): Float =
            if (motion.overflow) (motion.positionPx / horizontalFadingEdgeLength.coerceAtLeast(1)).coerceIn(0f, 1f) else 0f
        override fun getRightFadingEdgeStrength(): Float = if (motion.overflow) 1f else 0f
    }.apply {
        textSize = 13f
        setTextColor(color)
        setSingleLine(true)
        setHorizontallyScrolling(true)
        ellipsize = null
        isHorizontalFadingEdgeEnabled = true
        setFadingEdgeLength((16 * resources.displayMetrics.density).toInt())
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private var framePosted = false
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            framePosted = false
            if (!canTick()) { motion.suspendClock(); return }
            motion.frame(frameTimeNanos)
            label.invalidate()
            schedule()
        }
    }

    init {
        clipChildren = true
        clipToPadding = true
        addView(label, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL))
    }

    fun display(value: String) {
        if (label.text.toString() != value) label.text = value
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val viewport = (label.width - label.compoundPaddingLeft - label.compoundPaddingRight).toFloat()
        motion.geometry(label.layout?.getLineWidth(0) ?: 0f, viewport,
            maxOf(48f * resources.displayMetrics.density, viewport / 3f))
        schedule()
    }

    private fun canTick() = isAttachedToWindow && isShown && windowVisibility == VISIBLE &&
        motion.overflow && ValueAnimator.areAnimatorsEnabled()

    private fun schedule() {
        if (!canTick()) { stopClock(); return }
        if (!framePosted) {
            framePosted = true
            Choreographer.getInstance().postFrameCallbackDelayed(frameCallback, 1L)
        }
    }

    private fun stopClock() {
        if (framePosted) Choreographer.getInstance().removeFrameCallback(frameCallback)
        framePosted = false
        motion.suspendClock()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        label.isSelected = true
        schedule()
    }
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) schedule() else stopClock()
    }
    override fun onDetachedFromWindow() {
        stopClock()
        label.isSelected = false
        label.setLayerType(View.LAYER_TYPE_NONE, null)
        super.onDetachedFromWindow()
    }
}

/** Fractional positions avoid integer scroll steps; resize/content updates retain the current phase. */
internal class SourceTickerMotion(private val pixelsPerSecond: Float) {
    var positionPx = 0f
        private set
    var periodPx = 0f
        private set
    val overflow get() = periodPx > 0f
    private var lastFrameNanos: Long? = null

    fun geometry(textWidth: Float, viewport: Float, gap: Float) {
        periodPx = if (viewport > 0f && textWidth > viewport) textWidth + gap.coerceAtLeast(0f) else 0f
        positionPx = if (overflow) positionPx % periodPx else 0f
        if (!overflow) suspendClock()
    }

    fun frame(nanos: Long) {
        lastFrameNanos?.let { previous ->
            if (overflow) {
                val seconds = (nanos - previous).coerceIn(0L, 50_000_000L) / 1_000_000_000f
                positionPx = (positionPx + seconds * pixelsPerSecond) % periodPx
            }
        }
        lastFrameNanos = nanos
    }

    fun suspendClock() { lastFrameNanos = null }
}
