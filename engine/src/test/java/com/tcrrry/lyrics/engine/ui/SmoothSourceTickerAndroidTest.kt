package com.tcrrry.lyrics.engine.ui

import android.app.Activity
import android.view.View
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class SmoothSourceTickerAndroidTest {
    @Test fun longSourceKeepsAllTextAndStatusPollingDoesNotResetItsPosition() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val value = "网易云音乐 · 逐字 · 平台译文 · 离线注音 · 字词对应发音".repeat(8)
        val ticker = SmoothSourceTicker(activity, android.graphics.Color.BLACK)
        ticker.display(value)
        ticker.measure(View.MeasureSpec.makeMeasureSpec(160, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(40, View.MeasureSpec.AT_MOST))
        ticker.layout(0, 0, 160, ticker.measuredHeight)
        assertEquals(value, ticker.label.text.toString())
        assertEquals(ticker.width, ticker.label.width)
        assertEquals(android.text.TextUtils.TruncateAt.MARQUEE, ticker.label.ellipsize)
        assertEquals(-1, ticker.label.marqueeRepeatLimit)
        assertTrue(ticker.label.isHorizontalFadingEdgeEnabled)
        assertEquals((16 * activity.resources.displayMetrics.density).toInt(), ticker.label.horizontalFadingEdgeLength)
        var changes = 0
        ticker.label.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { changes++ }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        ticker.display(value)
        assertEquals(0, changes)
        ticker.display("QQ 音乐 · 逐字")
        assertEquals(1, changes)
    }

    @Test fun closingThePageReleasesTheCachedTextLayer() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        val root = android.widget.FrameLayout(activity)
        val ticker = SmoothSourceTicker(activity, android.graphics.Color.BLACK)
        root.addView(ticker)
        activity.setContentView(root)
        assertTrue(ticker.label.isSelected)
        ticker.label.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        root.removeView(ticker)
        assertEquals(View.LAYER_TYPE_NONE, ticker.label.layerType)
        assertFalse(ticker.label.isSelected)
    }
}
