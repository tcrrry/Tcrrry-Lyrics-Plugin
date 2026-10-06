package com.tcrrry.lyrics.engine.hook

import android.app.Activity
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class NativeLineLyricsPresentationTest {
    @org.junit.Before fun attachPlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.attach() }
    @org.junit.After fun closePlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.current.close() }
    private val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    private val root = object : ViewGroup(activity) {
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {}
        override fun checkLayoutParams(params: LayoutParams) = params is PronunciationHeaderLayoutTest.HostParams
        override fun generateDefaultLayoutParams(): LayoutParams = PronunciationHeaderLayoutTest.HostParams(-1, -2)
    }
    private fun text() = TextView(activity).apply {
        id = View.generateViewId()
        root.addView(this, PronunciationHeaderLayoutTest.HostParams(-1, -2))
    }
    private val original = text()
    private val pronunciation = text()
    private val translation = text()
    private fun params(view: View) = view.layoutParams as PronunciationHeaderLayoutTest.HostParams
    init {
        pronunciation.setTextColor(Color.WHITE); translation.setTextColor(Color.WHITE)
        pronunciation.alpha = 0.35f; translation.alpha = 0.35f
        params(original).apply { topToTop = 0; bottomToTop = pronunciation.id }
        params(pronunciation).apply { topToBottom = original.id; bottomToTop = translation.id }
        params(translation).apply { topToBottom = pronunciation.id; bottomToBottom = 0 }
    }
    private fun apply() = NativeLineLyricsPresentation.apply(root, original, pronunciation, translation)

    @Test fun emphasisPlacesSmallOriginalAboveLargePhoneticsAndTranslation() {
        NativeLineLyricsPresentation.apply(root, original, pronunciation, translation, primaryPronunciation = true)
        assertEquals(0, params(pronunciation).topToTop)
        assertEquals(pronunciation.id, params(original).topToBottom)
        assertEquals(original.id, params(translation).topToBottom)
        assertEquals(0x59ffffff, pronunciation.currentTextColor)
        assertEquals(Color.WHITE, translation.currentTextColor)
        NativeLineLyricsPresentation.apply(root, original, pronunciation, translation, primaryPronunciation = false)
        assertEquals(pronunciation.id, params(original).topToBottom)
        NativeLineLyricsPresentation.clear(root)
    }
    @Test fun lineOrderPreservesNativeBindingViewsAndOriginalText() {
        original.text = "フツフツと鳴り出す青春の音"
        pronunciation.text = "fu tsu fu tsu"
        translation.text = "青春音符"
        original.alpha = 0.7f
        apply()
        assertEquals(0, params(pronunciation).topToTop)
        assertEquals(original.id, params(pronunciation).bottomToTop)
        assertEquals(pronunciation.id, params(original).topToBottom)
        assertEquals(translation.id, params(original).bottomToTop)
        assertEquals(original.id, params(translation).topToBottom)
        assertEquals(0, params(translation).bottomToBottom)
        assertEquals(3, root.childCount)
        assertSame(root, pronunciation.parent)
        assertEquals("フツフツと鳴り出す青春の音", original.text.toString())
        assertEquals(0.7f, original.alpha, 0f)
        assertEquals(0x59ffffff, pronunciation.currentTextColor)
        assertEquals(Color.WHITE, translation.currentTextColor)
        assertEquals(89, Color.alpha(pronunciation.currentTextColor))
        NativeLineLyricsPresentation.clear(root)
    }

    @Test fun pronunciationStaysDimWhileOriginalAndTranslationKeepNativeHighlightAndFade() {
        apply()
        for (alpha in listOf(0.35f, 0.55f, 1f, 0.8f, 0.35f)) {
            assertEquals(1f, NativeLyricsPronunciationSubtitle.protectedAlpha(pronunciation, alpha), 0f)
            assertEquals(alpha, NativeLyricsPronunciationSubtitle.protectedAlpha(translation, alpha), 0f)
            assertEquals(alpha, NativeLyricsPronunciationSubtitle.protectedAlpha(original, alpha), 0f)
            assertEquals(0x59ffffff, NativeLyricsPronunciationSubtitle.protectedColor(pronunciation, Color.WHITE))
        }
        NativeLineLyricsPresentation.clear(root)
    }

    @Test fun sourceSwitchCleanupKeepsNativePrimaryAndTranslationStylesFromAfterBinding() {
        // Hooks prepare the row before the host's first binding; inflated alpha is 1.
        original.alpha = 1f
        translation.alpha = 1f
        apply()
        // Native binding owns these styles and only initializes translation alpha once.
        original.alpha = 0.94f
        translation.alpha = 0.18f
        original.setTextColor(0xffaaccff.toInt())
        translation.setTextColor(0xffabcdee.toInt())
        NativeLineLyricsPresentation.clear(root)
        assertEquals(0.18f, translation.alpha, 0f)
        assertEquals(0.94f, original.alpha, 0f)
        assertEquals(0xffabcdee.toInt(), translation.currentTextColor)
        assertEquals(0xffaaccff.toInt(), original.currentTextColor)
        assertEquals(pronunciation.id, params(translation).topToBottom)
        assertEquals(original.id, params(pronunciation).topToBottom)
        assertEquals(0.35f, NativeLyricsPronunciationSubtitle.protectedAlpha(pronunciation, 0.35f), 0f)
    }

    @Test fun repeatedManagedAndNativeReuseCannotPromoteTheTranslationToFullBrightness() {
        translation.alpha = 1f
        apply()
        translation.alpha = 0.18f
        repeat(3) {
            NativeLineLyricsPresentation.clear(root)
            assertEquals(0.18f, translation.alpha, 0f)
            NativeLineLyricsPresentation.apply(root, original, pronunciation, translation, primaryPronunciation = it % 2 == 0)
            assertEquals(0.18f, translation.alpha, 0f)
        }
        NativeLineLyricsPresentation.clear(root)
        assertEquals(0.18f, translation.alpha, 0f)
    }

    @Test fun hiddenPronunciationAndRepeatedBindingKeepSameChain() {
        pronunciation.visibility = View.GONE
        apply(); apply()
        assertEquals(View.GONE, pronunciation.visibility)
        assertEquals(pronunciation.id, params(original).topToBottom)
        pronunciation.visibility = View.VISIBLE
        apply()
        assertEquals(0, params(pronunciation).topToTop)
        NativeLineLyricsPresentation.clear(root)
    }

    @Test fun recycledNativeRowRestoresAnchorsAndReleasesBrightnessGuard() {
        apply(); apply()
        NativeLineLyricsPresentation.clear(root)
        NativeLineLyricsPresentation.clear(root)
        assertEquals(0, params(original).topToTop)
        assertEquals(-1, params(original).topToBottom)
        assertEquals(pronunciation.id, params(original).bottomToTop)
        assertEquals(original.id, params(pronunciation).topToBottom)
        assertEquals(pronunciation.id, params(translation).topToBottom)
        assertEquals(0.35f, NativeLyricsPronunciationSubtitle.protectedAlpha(pronunciation, 0.35f), 0f)
        assertEquals(Color.WHITE, NativeLyricsPronunciationSubtitle.protectedColor(translation, Color.WHITE))
        assertEquals(Color.WHITE, pronunciation.currentTextColor)
        assertEquals(Color.WHITE, translation.currentTextColor)
        assertEquals(0.35f, pronunciation.alpha, 0f)
        assertEquals(0.35f, translation.alpha, 0f)
    }
}
