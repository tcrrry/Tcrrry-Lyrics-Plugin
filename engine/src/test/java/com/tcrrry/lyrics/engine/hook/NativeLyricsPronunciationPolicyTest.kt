package com.tcrrry.lyrics.engine.hook

import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(manifest = org.robolectric.annotation.Config.NONE, sdk = [35])
class NativeLyricsPronunciationPolicyTest {
    @org.junit.Before fun attachPlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.attach() }
    @org.junit.After fun closePlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.current.close() }
    @Test fun chineseScriptMismatchCanUseExistingGeneratedLatinTrack() {
        assertEquals("ko-Latn", NativeLyricsPronunciationPolicy.select(null, listOf("ko-Latn")))
    }
    @Test fun existingNativeMatchAlwaysWins() {
        assertEquals("ja-Hira", NativeLyricsPronunciationPolicy.select("ja-Hira", listOf("ja-Latn", "ja-Hira")))
    }
    @Test fun absentNativeTrackNeverEnablesPronunciation() {
        assertNull(NativeLyricsPronunciationPolicy.select(null, emptyList()))
    }
    @Test fun originalScriptsAreNotFalselyReportedAsLatinPronunciation() {
        assertNull(NativeLyricsPronunciationPolicy.select(null, listOf("ja", "ko", "zh-Hans", "ja-Hira")))
    }
    @Test fun firstSupportedRealTrackIsSelectedWithoutInventingLanguage() {
        val tracks = listOf("ja-Hira", "ja-Latn", "ko-Latn", "zh-Latn")
        val result = NativeLyricsPronunciationPolicy.select(null, tracks)
        assertEquals("ja-Latn", result)
        assertTrue(result in tracks)
    }
    @Test fun regionTagsWorkButMalformedOrUnrelatedTracksDoNot() {
        assertEquals("zh-Latn-CN", NativeLyricsPronunciationPolicy.select(null, listOf("zh-Latn-CN")))
        assertNull(NativeLyricsPronunciationPolicy.select(null, listOf("Latn", "en-Latn", "ko-Latn ", "ko-Latn-")))
    }
}
