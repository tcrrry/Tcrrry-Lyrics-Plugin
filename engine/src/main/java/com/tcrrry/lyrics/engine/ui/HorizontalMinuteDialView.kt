package com.tcrrry.lyrics.engine.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt

/** Horizontal gauge dial slider for picking sleep-timer minutes with 1-minute precision. */
internal class HorizontalMinuteDialView(
    context: Context,
    private val accentColor: Int = Color.WHITE,
    private val primaryTextColor: Int = Color.WHITE,
    private val secondaryTextColor: Int = 0xAAFFFFFF.toInt(),
) : View(context) {

    companion object {
        const val MIN_MINUTES = 1
        const val MAX_MINUTES = 240

        fun clampMinutes(value: Int): Int = value.coerceIn(MIN_MINUTES, MAX_MINUTES)

        fun formatDurationDetail(minutes: Int): String {
            val clamped = clampMinutes(minutes)
            if (clamped < 60) return ""
            val hours = clamped / 60
            val mins = clamped % 60
            return if (mins == 0) "$hours 小时" else "$hours 小时 $mins 分钟"
        }

        fun offsetToMinutes(offsetPx: Float, stepPx: Float): Int {
            if (stepPx <= 0f) return MIN_MINUTES
            return clampMinutes(MIN_MINUTES + (offsetPx / stepPx).roundToInt())
        }
    }

    var onMinutesChanged: ((Int) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val stepPx = 14f * density
    private val maxOffsetPx = (MAX_MINUTES - MIN_MINUTES) * stepPx
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val scroller = OverScroller(context)
    private var velocityTracker: VelocityTracker? = null

    private var scrollOffsetPx = (20 - MIN_MINUTES) * stepPx
    private var selectedMinutes = 20
    private var downX = 0f
    private var lastX = 0f
    private var dragging = false
    private var totalDragDx = 0f

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accentColor
        style = Paint.Style.STROKE
        strokeWidth = 2.6f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val pointerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accentColor
        style = Paint.Style.FILL
    }
    private val pointerPath = Path()
    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11.5f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    fun getMinutes(): Int = selectedMinutes

    fun setMinutes(value: Int, notify: Boolean = false) {
        val clamped = clampMinutes(value)
        scroller.abortAnimation()
        scrollOffsetPx = (clamped - MIN_MINUTES) * stepPx
        updateSelected(clamped, haptic = false, notify = notify)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredW = (280f * density).roundToInt()
        val desiredH = (90f * density).roundToInt()
        setMeasuredDimension(
            resolveSize(desiredW, widthMeasureSpec),
            resolveSize(desiredH, heightMeasureSpec),
        )
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollOffsetPx = scroller.currX.toFloat().coerceIn(0f, maxOffsetPx)
            val current = offsetToMinutes(scrollOffsetPx, stepPx)
            updateSelected(current, haptic = true, notify = true)
            if (scroller.isFinished) {
                snapIfNeeded()
            } else {
                postInvalidateOnAnimation()
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                scroller.abortAnimation()
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain().apply { addMovement(event) }
                downX = event.x
                lastX = event.x
                totalDragDx = 0f
                dragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                velocityTracker?.addMovement(event)
                val dx = event.x - lastX
                lastX = event.x
                totalDragDx += abs(dx)
                if (!dragging && totalDragDx > touchSlop) {
                    dragging = true
                }
                if (dragging) {
                    scrollOffsetPx = (scrollOffsetPx - dx).coerceIn(0f, maxOffsetPx)
                    val current = offsetToMinutes(scrollOffsetPx, stepPx)
                    updateSelected(current, haptic = true, notify = true)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val tracker = velocityTracker
                velocityTracker = null
                if (!dragging) {
                    val centerX = width * 0.5f
                    val delta = when {
                        downX < centerX - 18f * density -> -1
                        downX > centerX + 18f * density -> 1
                        else -> 0
                    }
                    if (delta != 0) {
                        val target = clampMinutes(selectedMinutes + delta)
                        animateToMinutes(target)
                    } else {
                        snapIfNeeded()
                    }
                } else if (tracker != null) {
                    tracker.addMovement(event)
                    tracker.computeCurrentVelocity(1000, 6000f * density)
                    val vx = tracker.xVelocity
                    if (abs(vx) > 220f * density) {
                        scroller.fling(
                            scrollOffsetPx.roundToInt(), 0,
                            (-vx).roundToInt(), 0,
                            0, maxOffsetPx.roundToInt(), 0, 0,
                        )
                        postInvalidateOnAnimation()
                    } else {
                        snapIfNeeded()
                    }
                } else {
                    snapIfNeeded()
                }
                tracker?.recycle()
                dragging = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                velocityTracker?.recycle()
                velocityTracker = null
                dragging = false
                snapIfNeeded()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun animateToMinutes(target: Int) {
        val targetOffset = ((clampMinutes(target) - MIN_MINUTES) * stepPx).roundToInt()
        val start = scrollOffsetPx.roundToInt()
        scroller.abortAnimation()
        scroller.startScroll(start, 0, targetOffset - start, 0, 180)
        postInvalidateOnAnimation()
    }

    private fun snapIfNeeded() {
        val snapped = offsetToMinutes(scrollOffsetPx, stepPx)
        val targetOffset = (snapped - MIN_MINUTES) * stepPx
        val deltaPx = (targetOffset - scrollOffsetPx).roundToInt()
        if (abs(deltaPx) >= 1) {
            scroller.startScroll(scrollOffsetPx.roundToInt(), 0, deltaPx, 0, 150)
            postInvalidateOnAnimation()
        } else {
            scrollOffsetPx = targetOffset
            updateSelected(snapped, haptic = true, notify = true)
            invalidate()
        }
    }

    private fun updateSelected(next: Int, haptic: Boolean, notify: Boolean) {
        if (next == selectedMinutes) return
        selectedMinutes = next
        if (haptic) {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        if (notify) {
            onMinutesChanged?.invoke(next)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        val cx = width * 0.5f
        val halfW = (width * 0.5f).coerceAtLeast(1f)
        val baselineY = height * 0.62f

        // Visible tick range
        val minVisible = (((scrollOffsetPx - halfW) / stepPx).toInt() + MIN_MINUTES - 1)
            .coerceAtLeast(MIN_MINUTES)
        val maxVisible = (((scrollOffsetPx + halfW) / stepPx).toInt() + MIN_MINUTES + 1)
            .coerceAtMost(MAX_MINUTES)

        for (minute in minVisible..maxVisible) {
            val dx = (minute - MIN_MINUTES) * stepPx - scrollOffsetPx
            val x = cx + dx
            val norm = (abs(dx) / halfW).coerceIn(0f, 1f)
            val curve = cos(norm * (PI * 0.5)).toFloat().coerceAtLeast(0f)
            val alphaCurve = curve * curve
            if (alphaCurve <= 0.02f) continue

            val isMajor = minute == 1 || minute % 10 == 0
            val isMedium = !isMajor && minute % 5 == 0
            val baseHeight = when {
                isMajor -> 26f * density
                isMedium -> 19f * density
                else -> 12f * density
            }
            val tickHeight = baseHeight * (0.72f + 0.28f * curve)
            val tickWidth = when {
                isMajor -> 2.1f * density
                isMedium -> 1.6f * density
                else -> 1.15f * density
            }

            val nearCenter = (1f - (abs(dx) / (stepPx * 1.1f))).coerceIn(0f, 1f)
            val baseColor = if (nearCenter > 0.35f) accentColor else if (isMajor) primaryTextColor else secondaryTextColor
            val baseAlpha = when {
                isMajor -> 0.88f
                isMedium -> 0.62f
                else -> 0.36f
            }
            tickPaint.strokeWidth = tickWidth
            tickPaint.color = withAlpha(baseColor, (baseAlpha * alphaCurve).coerceIn(0f, 1f))
            canvas.drawLine(x, baselineY - tickHeight, x, baselineY, tickPaint)

            if (minute == 1 || minute == 5 || minute % 10 == 0) {
                val labelAlpha = (0.78f * alphaCurve).coerceIn(0f, 1f)
                numberPaint.color = withAlpha(
                    if (nearCenter > 0.45f) accentColor else secondaryTextColor,
                    labelAlpha,
                )
                canvas.drawText(minute.toString(), x, baselineY + 17f * density, numberPaint)
            }
        }

        // Fixed center indicator needle + top chevron
        val needleTop = baselineY - 34f * density
        val needleBottom = baselineY + 2f * density
        canvas.drawLine(cx, needleTop, cx, needleBottom, needlePaint)

        val triY = needleTop - 5f * density
        val triHalf = 4.2f * density
        pointerPath.reset()
        pointerPath.moveTo(cx - triHalf, triY - 4.5f * density)
        pointerPath.lineTo(cx + triHalf, triY - 4.5f * density)
        pointerPath.lineTo(cx, triY + 0.5f * density)
        pointerPath.close()
        canvas.drawPath(pointerPath, pointerPaint)
    }

    private fun withAlpha(color: Int, factor: Float): Int {
        val origAlpha = Color.alpha(color)
        val scaled = (origAlpha * factor).roundToInt().coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (scaled shl 24)
    }
}
