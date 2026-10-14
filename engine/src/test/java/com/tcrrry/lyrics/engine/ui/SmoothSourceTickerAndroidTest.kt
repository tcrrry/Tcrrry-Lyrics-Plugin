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
    @org.junit.Before fun useRealisticFrameIntervals() {
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(java.time.Duration.ofMillis(16))
    }

    @Test @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun childWindowFocusChangesDoNotStopOrResetTheTicker() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        val ticker = SmoothSourceTicker(activity, android.graphics.Color.BLACK)
        ticker.display("网易云音乐 · 逐字 · 平台译文 · 字词对应发音".repeat(8))
        val root = android.widget.FrameLayout(activity)
        root.addView(ticker, android.widget.FrameLayout.LayoutParams(180, 48))
        activity.setContentView(root)
        ticker.measure(View.MeasureSpec.makeMeasureSpec(180, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(48, View.MeasureSpec.EXACTLY))
        ticker.layout(0, 0, 180, 48)
        ticker.label.onWindowFocusChanged(true)
        val looper = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
        looper.idleFor(java.time.Duration.ofMillis(400))
        val before = ticker.motion.positionPx
        assertTrue(before > 0f)
        repeat(3) {
            val position = ticker.motion.positionPx
            ticker.label.onWindowFocusChanged(false)
            assertEquals(position, ticker.motion.positionPx)
            looper.idleFor(java.time.Duration.ofMillis(120))
            val after = ticker.motion.positionPx
            assertTrue("before=$position after=$after", after > position)
            ticker.label.onWindowFocusChanged(true)
            assertEquals(after, ticker.motion.positionPx)
        }
        ticker.visibility = View.GONE
        ticker.onVisibilityAggregated(false)
        val hiddenPosition = ticker.motion.positionPx
        looper.idleFor(java.time.Duration.ofMillis(120))
        assertEquals(hiddenPosition, ticker.motion.positionPx)
        activity.finish()
    }

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
        assertNull(ticker.label.ellipsize)
        assertTrue(ticker.motion.overflow)
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

    @Test @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun movingTickerDrawsVisibleTextWithoutChangingItsLayout() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        val ticker = SmoothSourceTicker(activity, android.graphics.Color.BLACK)
        ticker.display("网易云音乐 · 逐字 · 平台译文 · 离线注音".repeat(4))
        val root = android.widget.FrameLayout(activity)
        root.addView(ticker, android.widget.FrameLayout.LayoutParams(180, 48))
        activity.setContentView(root)
        ticker.measure(View.MeasureSpec.makeMeasureSpec(180, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(48, View.MeasureSpec.EXACTLY))
        ticker.layout(0, 0, 180, 48)
        val layout = ticker.label.layout
        fun pixels(): IntArray {
            val bitmap = android.graphics.Bitmap.createBitmap(180, 48, android.graphics.Bitmap.Config.ARGB_8888)
            ticker.draw(android.graphics.Canvas(bitmap))
            return IntArray(180 * 48).also { bitmap.getPixels(it, 0, 180, 0, 0, 180, 48); bitmap.recycle() }
        }
        val first = pixels()
        assertTrue(first.count { android.graphics.Color.alpha(it) > 0 } > 100)
        ticker.motion.frame(0)
        for (i in 1L..20) ticker.motion.frame(i * 50_000_000)
        val moved = pixels()
        assertTrue(moved.count { android.graphics.Color.alpha(it) > 0 } > 100)
        assertFalse(first.contentEquals(moved))
        assertSame(layout, ticker.label.layout)
        activity.setContentView(View(activity))
        activity.finish()
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
