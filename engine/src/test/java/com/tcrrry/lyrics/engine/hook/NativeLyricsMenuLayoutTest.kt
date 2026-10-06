package com.tcrrry.lyrics.engine.hook

import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class NativeLyricsMenuLayoutTest {
    @org.junit.Before fun attachPlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.attach() }
    @org.junit.After fun closePlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.current.close() }
    private val context get() = RuntimeEnvironment.getApplication()
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private fun label(text: String, size: Float) = TextView(context).apply {
        this.text = text
        textSize = size
        setSingleLine(true)
        setPadding(dp(16), dp(8), dp(16), dp(8))
    }
    private fun measure(menu: LinearLayout, available: Int) {
        menu.measure(View.MeasureSpec.makeMeasureSpec(available, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(dp(700), View.MeasureSpec.AT_MOST))
        menu.layout(0, 0, menu.measuredWidth, menu.measuredHeight)
    }
    private fun nativeMenu(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        for (text in listOf("显示发音", "隐藏翻译")) {
            val label = label(text, 16f)
            val icon = ColorDrawable(-1).apply { setBounds(0, 0, dp(20), dp(20)) }
            label.setCompoundDrawables(null, null, icon, null)
            label.compoundDrawablePadding = dp(16)
            addView(label)
        }
    }
    private fun appendSource(menu: LinearLayout, source: String) {
        val row = NativeLyricsMenuRowLayout(context, source, labelSizePx = (menu.getChildAt(0) as TextView).textSize)
        menu.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    @Test fun sourceRowKeepsNativeWidthAndNearbyIcons() {
        for (source in listOf("QQ音乐", "网易云音乐", "LRCLIB", "缓存来源未记录", "Apple Music")) {
            val menu = nativeMenu()
            measure(menu, dp(360))
            val nativeWidth = menu.measuredWidth
            appendSource(menu, source)
            measure(menu, dp(360))
            assertEquals(source, nativeWidth, menu.measuredWidth)
            assertTrue(menu.measuredWidth < dp(240))
            assertEquals(menu.measuredWidth, menu.getChildAt(0).width)
            val text = (menu.getChildAt(2) as LinearLayout).getChildAt(0) as TextView
            assertNull(text.ellipsize)
            assertTrue(text.width >= text.paint.measureText(text.text.toString()))
        }
    }

    @Test fun nativeBindingAfterSourceInsertionStillMeasuresFullLabels() {
        val menu = nativeMenu()
        val labels = (0..1).map { menu.getChildAt(it) as TextView }
        labels.forEach { it.text = "" }
        appendSource(menu, "QQ音乐")
        measure(menu, dp(360))
        labels[0].text = "显示发音"
        labels[1].text = "隐藏翻译"
        measure(menu, dp(360))
        assertTrue(menu.measuredWidth < dp(240))
        for (text in labels) {
            assertTrue(text.width - text.compoundPaddingLeft - text.compoundPaddingRight >=
                text.paint.measureText(text.text.toString()))
        }
    }

    @Test fun emphasisLabelKeepsReadableSizeAcrossIntrinsicAndFinalPasses() {
        for (caption in listOf("突出：歌词", "突出：发音")) {
            val menu = nativeMenu()
            val row = NativeLyricsMenuRowLayout(context, caption, autoSizeLabel = false, labelSizePx = (menu.getChildAt(0) as TextView).textSize)
            menu.addView(row, LinearLayout.LayoutParams(-1, -2))
            repeat(3) { measure(menu, dp(360)) }
            val label = row.getChildAt(0) as TextView
            assertEquals(16f * context.resources.displayMetrics.scaledDensity, label.textSize, 0.1f)
            assertTrue(label.width >= label.paint.measureText(caption))
        }
    }

    @Test fun sourceDetailScrollsEntireTextWithoutShrinkingTheTitle() {
        val detail = "逐字 · 自带译文 · 机翻译文 · 自带发音 · 机翻发音 · 发音逐段高亮"
        val row = NativeLyricsMenuRowLayout(context, "QQ音乐", detail)
        val text = row.getChildAt(1) as TextView
        assertEquals(detail, text.text.toString())
        assertEquals(android.text.TextUtils.TruncateAt.MARQUEE, text.ellipsize)
        assertEquals(1, text.maxLines)
        assertEquals(-1, text.marqueeRepeatLimit)
        assertTrue(text.isSelected)
        assertTrue(text.isHorizontalFadingEdgeEnabled)
        assertEquals(dp(16), text.horizontalFadingEdgeLength)
    }

    @Test fun legacyPlainDividerReproducesExpandedPopup() {
        val menu = nativeMenu()
        appendSource(menu, "QQ音乐")
        menu.addView(View(context), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        measure(menu, dp(360))
        assertEquals(dp(360), menu.measuredWidth)
    }
}
