package com.tcrrry.lyrics.engine.ui

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView

/** Use the same one-way marquee and fading edges as the playback source menu. */
internal class SmoothSourceTicker(context: Context, color: Int) : FrameLayout(context) {
    val label = TextView(context).apply {
        textSize = 13f
        setTextColor(color)
        setSingleLine(true)
        setHorizontallyScrolling(true)
        ellipsize = TextUtils.TruncateAt.MARQUEE
        marqueeRepeatLimit = -1
        isHorizontalFadingEdgeEnabled = true
        setFadingEdgeLength((16 * resources.displayMetrics.density).toInt())
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    init {
        clipChildren = true
        clipToPadding = true
        addView(label, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL))
    }

    fun display(value: String) {
        if (label.text.toString() != value) label.text = value
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        label.isSelected = true
    }
    override fun onDetachedFromWindow() {
        label.isSelected = false
        label.setLayerType(View.LAYER_TYPE_NONE, null)
        super.onDetachedFromWindow()
    }
}
