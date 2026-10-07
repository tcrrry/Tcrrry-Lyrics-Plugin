package com.tcrrry.lyrics.engine.hook

import android.app.Activity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class NativePronunciationOverlayTest {
    private val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    private fun row() = FrameLayout(activity).apply {
        setPaddingRelative(12, 8, 14, 9)
        layout(0, 0, 400, 300)
    }
    @org.junit.After fun close() { NativePronunciationOverlay.clearAll() }

    @Test fun frameworkDrawsPronunciationAfterNativeWordGradientMask() {
        val events = mutableListOf<String>()
        val container = object : FrameLayout(activity) {
            override fun dispatchDraw(canvas: android.graphics.Canvas) {
                super.dispatchDraw(canvas)
                // FullWidthAlphaGradientFlexboxLayout applies its word masks here.
                events += "word-mask"
            }
        }.apply { layout(0, 0, 400, 300) }
        container.addView(TextView(activity).apply { text = "原文" })
        val header = object : TextView(activity) {
            override fun onDraw(canvas: android.graphics.Canvas) { events += "pronunciation" }
        }.apply { text = "tada kaze ga" }
        container.addView(header)
        NativePronunciationOverlay.attach(container, header)
        val bitmap = android.graphics.Bitmap.createBitmap(400, 300, android.graphics.Bitmap.Config.ARGB_8888)
        container.draw(android.graphics.Canvas(bitmap))
        assertEquals(listOf("word-mask", "pronunciation"), events)
    }

    @Test fun drawingInsideContainerPreservesEveryNativeWordIndexAndReservesSpace() {
        val container = row()
        val words = List(5) { TextView(activity).apply { text = "歌" }.also { container.addView(it) } }
        val header = TextView(activity).apply { text = "wa ka ra na i ko to ba ka ri na no ni" }
        container.addView(header)
        NativeLyricsPronunciationSubtitle.stylePronunciation(header)
        NativePronunciationOverlay.attach(container, header)
        assertEquals(words.size, container.childCount)
        words.forEachIndexed { i, word -> assertSame(word, container.getChildAt(i)) }
        assertEquals(12, header.left)
        assertEquals(386, header.right)
        assertEquals(8, header.top)
        assertTrue(header.height > 0)
        assertEquals(header.bottom, container.paddingTop)
        assertEquals(0x59ffffff, NativeLyricsPronunciationSubtitle.protectedColor(header, -1))
        assertEquals(1f, NativeLyricsPronunciationSubtitle.protectedAlpha(header, .35f), 0f)
        container.layout(0, 0, 220, 500)
        assertEquals(206, header.right)
        assertEquals(header.bottom, container.paddingTop)
        NativePronunciationOverlay.clear(container)
        assertEquals(8, container.paddingTop)
        assertEquals(9, container.paddingBottom)
        assertNull(NativePronunciationOverlay.view(container))
        assertEquals(-1, NativeLyricsPronunciationSubtitle.protectedColor(header, -1))
        assertEquals(words.size, container.childCount)
    }

    @Test fun ordinaryRefreshRetainsHeaderWhileRebuildOrRecyclingRemovesItWithoutPaddingGrowth() {
        val container = row()
        fun attach(text: String) = TextView(activity).apply { this.text = text; container.addView(this) }
            .also { NativePronunciationOverlay.attach(container, it) }
        val first = attach("kimi no koe")
        val padding = container.paddingTop
        container.layout(0, 0, 400, 301)
        assertSame(first, NativePronunciationOverlay.view(container))
        assertEquals(padding, container.paddingTop)
        NativePronunciationOverlay.clear(container)
        val second = attach("tada kaze ga fuku no wo matteta")
        assertNotSame(first, NativePronunciationOverlay.view(container))
        assertEquals(second.bottom, container.paddingTop)
        NativePronunciationOverlay.clear(container)
        NativePronunciationOverlay.clear(container)
        assertEquals(8, container.paddingTop)
        assertEquals(0, container.childCount)
    }

    @Test fun ordinaryBindingsKeepWordMapsButSongAndVisibilityChangesRebuild() {
        val state = NativeAuxiliaryBindingState()
        val holder = Any()
        val first = NativeAuxiliaryBindingState.Identity(Any(), 1, true, true)
        assertTrue(state.changed(holder, first))
        repeat(4) { assertFalse(state.changed(holder, first.copy())) }
        assertTrue(state.changed(holder, first.copy(pronunciation = false)))
        assertTrue(state.changed(holder, first.copy(translation = false)))
        assertTrue(state.changed(holder, first.copy(lineId = 2)))
        assertTrue(state.changed(holder, first.copy(pointer = Any())))
        state.clear(holder)
        assertTrue(state.changed(holder, first))
    }
}
