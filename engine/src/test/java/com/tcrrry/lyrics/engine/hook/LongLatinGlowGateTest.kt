package com.tcrrry.lyrics.engine.hook

import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(manifest = org.robolectric.annotation.Config.NONE, sdk = [35])
class LongLatinGlowGateTest {
    @org.junit.Before fun attachPlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.attach() }
    @org.junit.After fun closePlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.current.close() }
    private fun timing(text: String = "beautiful", duration: Int = 2000) = CjkKaraokeWordTiming(text, duration, duration, text.length, 1, false)
    @Test fun onlySufficientlySustainedLongWholeWordsQualify() {
        assertTrue(isSingleUnmergedLongLatinWord(timing()))
        assertTrue(isSingleUnmergedLongLatinWord(timing("everlasting", 1800)))
        assertFalse(isSingleUnmergedLongLatinWord(timing("beautiful", 1200)))
        assertFalse(isSingleUnmergedLongLatinWord(timing("love", 3000)))
        assertFalse(isSingleUnmergedLongLatinWord(timing("uncharacteristically", 1700)))
    }
    @Test fun backgroundMergedSplitAndMultipleWordsKeepNativeBehavior() {
        val word = timing()
        assertFalse(isSingleUnmergedLongLatinWord(word.copy(isBackground = true)))
        assertFalse(isSingleUnmergedLongLatinWord(word.copy(cumulativeDurationMs = 2500)))
        assertFalse(isSingleUnmergedLongLatinWord(word.copy(splitBindingCount = 2)))
        assertFalse(isSingleUnmergedLongLatinWord(timing("two words", 3000)))
        assertFalse(isSingleUnmergedLongLatinWord(timing("你好你好你好你好", 3000)))
    }
}
