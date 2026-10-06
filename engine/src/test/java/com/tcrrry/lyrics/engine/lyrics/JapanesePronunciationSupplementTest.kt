package com.tcrrry.lyrics.engine.lyrics

import com.tcrrry.lyrics.sources.DirectLyricsRepository.Result
import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(manifest = org.robolectric.annotation.Config.NONE, sdk = [35])
class JapanesePronunciationSupplementTest {
    @org.junit.Before fun attachPlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.attach() }
    @org.junit.After fun closePlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.current.close() }
    @Test fun dictionaryReadsKanjiAndParticles() {
        val value = requireNotNull(JapanesePronunciationSupplement.reading("君は東京へ行く"))
        assertTrue(value.contains("kimi wa"))
        assertTrue(value.contains("toukyou e"))
        assertTrue(value.contains("iku"))
    }
    @Test fun dictionaryPreservesGeminationAcrossConjugationTokens() {
        assertEquals("otona ni natta", JapanesePronunciationSupplement.reading("大人になった"))
        assertEquals("itta", JapanesePronunciationSupplement.reading("行った"))
    }
    @Test fun kanaHandlesGeminationLongVowelsAndContractedSounds() {
        assertEquals("gakkou", KanaRomaji.convert("がっこう"))
        assertEquals("koohii", KanaRomaji.convert("コーヒー"))
        assertEquals("matcha", KanaRomaji.convert("マッチャ"))
        assertEquals("shin'ya", KanaRomaji.convert("シンヤ"))
        assertEquals("fiirudo", KanaRomaji.convert("フィールド"))
    }
    @Test fun fillsOnlyMissingJapaneseLinesAndKeepsNativePronunciation() {
        val input = Result(lyrics = "[00:01.000]君は東京へ行く\n[00:04.000]好きだよ\n[00:07.000]Lovely\n[00:10.000]我的世界", romanizedLyrics = "[00:01.000]native reading", source = "网易云音乐")
        val result = JapanesePronunciationSupplement.fill(input)
        assertTrue(result.romanizedLyrics.contains("native reading"))
        assertEquals(setOf(4000L), result.supplementalPronunciationStarts)
        assertEquals(input.lyrics, result.lyrics)
        val ttml = requireNotNull(DesktopLyricsTtmlConverter.convert(result))
        val presentation = requireNotNull(DesktopLyricsPresentation.fromTtml(ttml))
        assertTrue(presentation.nativePronunciation)
        assertTrue(presentation.offlinePronunciation)
        assertTrue(ttml.contains("itunes:timing=\"Line\""))
        assertFalse(ttml.contains("<span"))
        assertTrue(ttml.contains("<text for=\"L3\"> </text>"))
    }
    @Test fun wordLyricsReadingUsesActualWordLineTimesAndKeepsOriginalWordTiming() {
        val input = Result(lyrics = "[00:01.000]君は東京へ行く", wordLyrics = "[5000,2000](5000,1000)君は(6000,1000)東京へ行く")
        val filled = JapanesePronunciationSupplement.fill(input)
        assertEquals(setOf(5000L), filled.supplementalPronunciationStarts)
        assertEquals(input.wordLyrics, filled.wordLyrics)
        val ttml = requireNotNull(DesktopLyricsTtmlConverter.convert(filled, offsetMs = 500))
        assertTrue(ttml.contains("itunes:timing=\"Word\""))
        assertTrue(ttml.contains("<span begin=\"0:04.500\" end=\"0:05.500\">君は</span>"))
        assertTrue(ttml.contains("<text for=\"L1\">kimi wa"))
    }
    @Test fun dummyOriginalPronunciationDoesNotHideGeneratedReading() {
        val result = JapanesePronunciationSupplement.fill(Result(lyrics = "[00:01.000]好きだよ", romanizedLyrics = "[00:01.000]好きだよ"))
        val ttml = requireNotNull(DesktopLyricsTtmlConverter.convert(result))
        assertTrue(ttml.contains("<text for=\"L1\">suki"))
        assertFalse(requireNotNull(DesktopLyricsPresentation.fromTtml(ttml)).nativePronunciation)
    }
    @Test fun changedPronunciationSettingRejectsStaleCacheWithoutDiscardingOtherSources() {
        val ttml = "<?xml version=\"1.0\"?>" + JapanesePronunciationSupplement.cacheMarker(true) +
            DesktopLyricsPresentation("QQ音乐", false, false, false, false, true).marker() + "<tt/>"
        assertTrue(JapanesePronunciationSupplement.cacheMatches(ttml, true))
        assertFalse(JapanesePronunciationSupplement.cacheMatches(ttml, true, primary = true))
        assertFalse(JapanesePronunciationSupplement.cacheMatches(ttml, false))
        assertTrue(JapanesePronunciationSupplement.cacheMatches("<tt>AMLL</tt>", false))
    }
    @Test fun oldMarkersRemainNativeAndUnrelatedLyricsDoNotLoadDictionary() {
        val old = "<!--tcrrry-lyrics-v1 source=QQ音乐 word=false platform=false api=false offline=false roma=true-->"
        assertTrue(requireNotNull(DesktopLyricsPresentation.fromTtml(old)).nativePronunciation)
        assertFalse(requireNotNull(DesktopLyricsPresentation.fromTtml(old)).offlinePronunciation)
        val input = Result(lyrics = "[00:01.000]Hello world")
        assertSame(input, JapanesePronunciationSupplement.fill(input))
    }
    @Test fun dictionaryAlternativeParticlesAndSpokenDigitsAlignWithoutReplacingProviderReading() {
        for ((original, reading) in listOf("自分を" to "ji bu n wo", "他の誰にも" to "ho ka no da re ni mo",
            "一人" to "hi to ri", "5畳半" to "go jo u ha n")) {
            val units = requireNotNull(JapanesePronunciationSupplement.alignedUnits(original, reading))
            assertEquals(reading.filter(Char::isLetterOrDigit), units.joinToString("") { it.text }.filter(Char::isLetterOrDigit))
            assertTrue(units.all { it.start >= 0 && it.end <= original.length && it.end > it.start })
            val ttml = requireNotNull(DesktopLyricsTtmlConverter.convert(Result(
                wordLyrics = "[1000,2000](1000,2000)$original", romanizedLyrics = "[00:01.000]$reading"), primaryPronunciation = true))
            assertTrue(ttml.contains("itunes:timing=\"Word\""))
            assertTrue(ttml.contains("begin=\"0:01.000\" end=\"0:03.000\""))
        }
    }
    @Test fun unrelatedOrIncompleteProviderReadingDoesNotAcquireInventedTiming() {
        assertNull(JapanesePronunciationSupplement.alignedUnits("他の誰にも", "completely unrelated"))
        assertNull(JapanesePronunciationSupplement.alignedUnits("一人", "hito"))
        assertNull(JapanesePronunciationSupplement.alignedUnits("5畳半", "go jou han extra"))
    }

}
