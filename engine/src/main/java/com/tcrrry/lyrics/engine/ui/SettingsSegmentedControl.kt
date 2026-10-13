package com.tcrrry.lyrics.engine.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlin.math.abs

/** Native measurement and accessibility remain available when host glass cannot load. */
internal class SettingsSegmentedControl(context: Context, val heading: String,
    val choices: List<String>, selected: Int, private val choose: (Int) -> Unit) : LinearLayout(context) {
    var selectedIndex by mutableIntStateOf(selected.coerceIn(choices.indices))
        private set
    var glassActive = false
        set(value) { field = value; paintSelection() }
    val labels = choices.map { caption -> TextView(context).apply {
        text = caption
        textSize = 13f
        gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        minimumHeight = dp(48)
        setPadding(dp(8), dp(10), dp(8), dp(10))
        isClickable = true; isFocusable = true
    } }
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    init {
        require(choices.size >= 2)
        orientation = HORIZONTAL
        setPadding(dp(4), dp(4), dp(4), dp(4))
        SettingsGlassMaterial.mark(this, SettingsGlassKind.Segments)
        labels.forEachIndexed { index, label ->
            SettingsGlassMaterial.mark(label, SettingsGlassKind.Button)
            label.setOnClickListener { select(index) }
            addView(label, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }
        paintSelection()
    }

    fun select(index: Int) {
        if (index !in choices.indices || index == selectedIndex) return
        selectedIndex = index
        paintSelection()
        choose(index)
    }

    private fun paintSelection() {
        val colors = SettingsUiTheme.colors(context)
        background = if (glassActive) null else rounded(colors.raised, 24)
        labels.forEachIndexed { index, label ->
            label.isSelected = index == selectedIndex
            label.contentDescription = "$heading：${choices[index]}"
            if (android.os.Build.VERSION.SDK_INT >= 30)
                label.stateDescription = if (label.isSelected) "已选择" else "未选择"
            label.setTextColor(if (glassActive) Color.TRANSPARENT else if (label.isSelected) colors.primary else colors.text)
            label.background = if (glassActive || !label.isSelected) null else rounded(colors.selected, 20)
        }
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (glassActive) return super.dispatchTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y; dragging = false }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && abs(event.y - downY) > slop && abs(event.y - downY) > abs(event.x - downX))
                    return super.dispatchTouchEvent(event)
                if (abs(event.x - downX) > slop) {
                    if (!dragging) {
                        val cancel = MotionEvent.obtain(event)
                        try { cancel.action = MotionEvent.ACTION_CANCEL; super.dispatchTouchEvent(cancel) }
                        finally { cancel.recycle() }
                    }
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
            MotionEvent.ACTION_UP -> if (dragging) {
                val usable = (width - paddingLeft - paddingRight).coerceAtLeast(1)
                val visual = (((event.x - paddingLeft) / usable) * choices.size).toInt().coerceIn(choices.indices)
                select(if (layoutDirection == View.LAYOUT_DIRECTION_RTL) choices.lastIndex - visual else visual)
                dragging = false; parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
            MotionEvent.ACTION_CANCEL -> { dragging = false; parent?.requestDisallowInterceptTouchEvent(false) }
        }
        return super.dispatchTouchEvent(event)
    }
}
