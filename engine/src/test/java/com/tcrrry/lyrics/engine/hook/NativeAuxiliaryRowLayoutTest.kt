package com.tcrrry.lyrics.engine.hook

import android.app.Activity
import android.graphics.Canvas
import android.text.Html
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import com.google.android.flexbox.FlexboxLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class NativeAuxiliaryRowLayoutTest {
    private val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    @org.junit.After fun clear() { PronunciationHeaderLayout.clearAll() }
    private val drawEvents = mutableListOf<String>()
    private val root = ConstraintLayout(activity)
    private val words = object : FlexboxLayout(activity) {
        override fun dispatchDraw(canvas: Canvas) {
            super.dispatchDraw(canvas)
            drawEvents += "word-mask"
        }
    }.apply { id = View.generateViewId(); setPadding(12, 8, 14, 9); flexWrap = 1 }
    init {
        root.addView(words, ConstraintLayout.LayoutParams(0, -2).apply {
            topToTop = 0; bottomToBottom = 0; startToStart = 0; endToEnd = 0
        })
        repeat(8) { index -> words.addView(TextView(activity).apply { text = "歌詞$index"; textSize = 32f }) }
    }
    private fun render(pron: String?, trans: String?) = NativeLyricsPronunciationSubtitle.reconcileAuxiliaries(
        words, pron, trans, false, { 1 }) { text, _ ->
        words.addView(object : TextView(activity) {
            override fun onDraw(canvas: Canvas) { drawEvents += text; super.onDraw(canvas) }
        }.apply { this.text = Html.fromHtml(text, 0); textSize = 18f })
    }
    private fun layout(width: Int) {
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        root.layout(0, 0, width, root.measuredHeight)
    }
    @Test fun multilineAuxiliariesReserveMeasuredHeightWithoutPaddingOrWordIndexChanges() {
        val originalChildren = (0 until words.childCount).map(words::getChildAt)
        render("boku ni mo son na ka ko na ra a ru shi", "毕竟我也曾有那样的过去，一起走过的漫长时光")
        for (width in listOf(360, 190, 480)) {
            layout(width)
            val header = requireNotNull(PronunciationHeaderLayout.auxiliary(words, true))
            val footer = requireNotNull(PronunciationHeaderLayout.auxiliary(words, false))
            assertSame(root, header.parent); assertSame(root, footer.parent)
            assertTrue("header is measured", header.height > 0)
            assertTrue("header must finish above original", header.bottom <= words.top)
            assertTrue("translation must begin below original", words.bottom <= footer.top)
            assertEquals(8, words.paddingTop); assertEquals(9, words.paddingBottom)
            assertEquals(originalChildren, (0 until words.childCount).map(words::getChildAt))
            assertEquals(3, root.childCount)
        }
    }
    @Test fun ordinaryCachedBindingsRepairMissingHeaderWithoutRebuildingWordChildren() {
        render("kimi no koe", "你的声音")
        val first = PronunciationHeaderLayout.auxiliary(words, true)
        repeat(5) { render("kimi no koe", "你的声音") }
        assertSame(first, PronunciationHeaderLayout.auxiliary(words, true))
        assertEquals(3, root.childCount)
        // The outer adapter may skip its nested glyph rebuild; repair still runs.
        PronunciationHeaderLayout.clearAuxiliary(words, true)
        render("kimi no koe", "你的声音")
        assertNotNull(PronunciationHeaderLayout.auxiliary(words, true))
        assertEquals(8, words.childCount)
        render(null, "你的声音")
        layout(220)
        assertNull(PronunciationHeaderLayout.auxiliary(words, true))
        assertEquals(2, root.childCount)
        render("kimi no koe", null)
        layout(220)
        assertNull(PronunciationHeaderLayout.auxiliary(words, false))
        assertEquals(2, root.childCount)
        PronunciationHeaderLayout.clearRow(root)
        assertEquals(1, root.childCount)
        assertEquals(0, (words.layoutParams as ConstraintLayout.LayoutParams).topToTop)
        assertEquals(0, (words.layoutParams as ConstraintLayout.LayoutParams).bottomToBottom)
    }
    @Test fun hostAnchorResetIsRepairedWithoutAddingDuplicateAuxiliaryViews() {
        render("kimi no koe", "你的声音")
        val oldHeader = PronunciationHeaderLayout.auxiliary(words, true)
        (words.layoutParams as ConstraintLayout.LayoutParams).apply { topToTop = 0; topToBottom = -1 }
        render("kimi no koe", "你的声音")
        layout(190)
        val header = requireNotNull(PronunciationHeaderLayout.auxiliary(words, true))
        assertNotSame(oldHeader, header)
        assertNull(oldHeader?.parent)
        assertTrue(header.bottom <= words.top)
        assertEquals(3, root.childCount)
        assertEquals(8, words.childCount)
    }
    @Test fun neitherAuxiliaryIsDrawnInsideNativeWordMaskAndBothRemainDim() {
        render("kimi no koe", "你的声音")
        layout(360)
        words.draw(Canvas())
        assertEquals(listOf("word-mask"), drawEvents)
        for (above in listOf(true, false)) {
            val auxiliary = requireNotNull(PronunciationHeaderLayout.auxiliary(words, above)) as TextView
            assertEquals(0x59ffffff, auxiliary.currentTextColor)
            assertEquals(0x59ffffff, NativeLyricsPronunciationSubtitle.protectedColor(auxiliary, -1))
        }
        drawEvents.clear()
        root.draw(Canvas())
        assertTrue(drawEvents.contains("kimi no koe"))
        assertTrue(drawEvents.contains("你的声音"))
        assertEquals(1, drawEvents.count { it == "word-mask" })
    }
}
