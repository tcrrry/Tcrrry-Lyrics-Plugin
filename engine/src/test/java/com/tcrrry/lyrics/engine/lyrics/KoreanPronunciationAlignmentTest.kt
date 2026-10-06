package com.tcrrry.lyrics.engine.lyrics

import com.tcrrry.lyrics.sources.DirectLyricsRepository.Result
import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(manifest = org.robolectric.annotation.Config.NONE, sdk = [35])
class KoreanPronunciationAlignmentTest {
    @org.junit.Before fun attachPlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.attach() }
    @org.junit.After fun closePlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.current.close() }
    @Test fun separatedProviderSyllablesKeepOriginalOffsetsAndLiaison() {
        val units = requireNotNull(KoreanPronunciationAlignment.units("니가 있는 곳에", "ni ga in neun go se"))
        assertEquals(listOf(0, 1, 3, 4, 6, 7), units.map { it.start })
        assertEquals(listOf("ni", "ga", "in", "neun", "go", "se"), units.map { it.text })
        assertEquals(listOf("neo", "rwi", "hae", "seo"),
            KoreanPronunciationAlignment.units("널 위해서", "neo rwi hae seo")!!.map { it.text })
    }
    @Test fun englishFragmentsRemainOneOriginalWord() {
        val units = requireNotNull(KoreanPronunciationAlignment.units("너 Heaven", "neo Hea ven"))
        assertEquals(2, units.size)
        assertEquals(2, units[1].start)
        assertEquals(8, units[1].end)
        assertEquals("Hea ven", units[1].text)
    }
    @Test fun omittedExtraOrIncompatibleSoundsNeverCreateTiming() {
        for ((original, reading) in listOf("니가" to "ni", "니가" to "ni ga ga", "니가" to "na ga",
            "니가" to "mi ga", "안녕 мир" to "an nyeong", "안녕" to "annyeong", "你好" to "ni hao")) {
            assertNull("$original / $reading", KoreanPronunciationAlignment.units(original, reading))
        }
    }
    @Test fun projectedPronunciationKeepsRealNativeIntervals() {
        val result = Result(lyrics = "[00:01.100]니가", wordLyrics = "[1100,800](1100,300,0)니(1500,400,0)가",
            romanizedLyrics = "[00:01.100]ni ga", source = "网易云音乐")
        val xml = requireNotNull(DesktopLyricsTtmlConverter.convert(result, primaryPronunciation = true))
        assertTrue(requireNotNull(DesktopLyricsPresentation.fromTtml(xml)).wordTimed)
        assertTrue(xml.contains("begin=\"0:01.100\" end=\"0:01.400\">ni</span> "))
        assertTrue(xml.contains("begin=\"0:01.500\" end=\"0:01.900\">ga"))
        assertFalse(requireNotNull(DesktopLyricsPresentation.fromTtml(requireNotNull(
            DesktopLyricsTtmlConverter.convert(result.copy(romanizedLyrics = "[00:01.100]na ga"), primaryPronunciation = true)))).wordTimed)
    }
}
