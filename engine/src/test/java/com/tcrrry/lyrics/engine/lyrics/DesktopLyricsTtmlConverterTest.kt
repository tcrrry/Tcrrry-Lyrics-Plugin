package com.tcrrry.lyrics.engine.lyrics

import com.tcrrry.lyrics.sources.DirectLyricsRepository
import com.tcrrry.lyrics.engine.hook.TtmlTimingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertFalse
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(manifest = org.robolectric.annotation.Config.NONE, sdk = [35])
class DesktopLyricsTtmlConverterTest {
    @org.junit.Before fun attachPlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.attach() }
    @org.junit.After fun closePlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.current.close() }
    @Test fun standaloneTimedEnglishSpaceSurvivesOutsideSpans() {
        val source = DirectLyricsRepository.Result(lyrics = "[00:01.000]stay forever",
            wordLyrics = "[1000,2200](1000,1000)stay(2000,50) (2050,1150)forever", source = "QQ音乐")
        val ttml = requireNotNull(DesktopLyricsTtmlConverter.convert(source))
        assertTrue(ttml.contains("stay</span> <span begin=\"0:02.050\""))
        assertFalse(ttml.contains("> </span>"))
    }
    @Test fun shortSmoothingPreservesEnvelopeAndWordBoundary() {
        val source = DirectLyricsRepository.Result(lyrics = "[00:01.000]好喜欢",
            wordLyrics = "[1000,700](1000,80)好(1080,120)喜(1200,500)欢", source = "QQ音乐")
        val original = requireNotNull(DesktopLyricsTtmlConverter.convert(source))
        val smoothed = requireNotNull(DesktopLyricsTtmlConverter.convert(source, smoothShortUnits = true))
        assertTrue(original.contains(">好</span>"))
        assertTrue(smoothed.contains("begin=\"0:01.000\" end=\"0:01.200\">好喜</span>"))
        assertTrue(smoothed.contains("begin=\"0:01.200\" end=\"0:01.700\">欢</span>"))
        val english = source.copy(wordLyrics = "[1000,700](1000,80)a (1080,620)word")
        assertTrue(requireNotNull(DesktopLyricsTtmlConverter.convert(english, smoothShortUnits = true)).contains(">a</span> <span"))
    }
    @Test fun phoneticShortUnitsCanShareSweepAcrossReadingSpacesWhileOriginalWordsStaySeparate() {
        val source = DirectLyricsRepository.Result(lyrics = "[00:01.000]君よ",
            wordLyrics = "[1000,2000](1000,1940)君(2940,60)よ", romanizedLyrics = "[00:01.000]kimi yo", source = "QQ音乐")
        val plain = requireNotNull(DesktopLyricsTtmlConverter.convert(source, primaryPronunciation = true))
        val smooth = requireNotNull(DesktopLyricsTtmlConverter.convert(source, primaryPronunciation = true, smoothShortUnits = true))
        assertTrue(plain.contains(">kimi</span> <span"))
        assertTrue(smooth.contains("begin=\"0:01.000\" end=\"0:03.000\">kimi yo</span>"))
        assertTrue(smooth.contains("itunes:timing=\"Word\""))
        val gap = source.copy(wordLyrics = "[1000,2000](1000,1500)君(2940,60)よ")
        assertTrue(requireNotNull(DesktopLyricsTtmlConverter.convert(gap, primaryPronunciation = true, smoothShortUnits = true)).contains(">kimi</span> <span"))
    }
    @Test fun celebrityCreditsCannotBorrowNextSungLinesPronunciationAndDisableWholeSongTiming() {
        val repository = DirectLyricsRepository()
        // Actual QQ recording 321160072: the 1130 ms credits are followed by
        // singing at 1510 ms. A nearest-only match borrowed the following row.
        val roma = "[1510,2340]se sang ui  (1510,1220)mo seo ri (2730,1120)"
        val response = "<contentroma><![CDATA[$roma]]></contentroma>"
        val source = DirectLyricsRepository.Result(source = "QQ音乐",
            lyrics = "[00:01.130]编曲：Jeppe London Bilsby\n[00:01.510]세상의 모서리",
            wordLyrics = "[1130,370](1130,100)编曲：(1230,270)Jeppe London Bilsby\n[1510,2340](1510,1220)세상의 (2730,1120)모서리",
            romanizedLyrics = repository.qqRomanizedLyrics(response),
            romanizedWordLyrics = repository.qqRomanizedWordLyrics(response))
        val ttml = requireNotNull(DesktopLyricsTtmlConverter.convert(source, primaryPronunciation = true))
        assertTrue(ttml.contains("itunes:timing=\"Word\""))
        assertTrue(ttml.contains("<span begin=\"0:01.510\" end=\"0:02.730\">se sang ui</span>  "))
        assertTrue(ttml.contains("<span begin=\"0:02.730\" end=\"0:03.850\">mo seo ri</span>"))
        assertTrue(ttml.contains("编曲："))
        assertTrue(ttml.contains("<text for=\"L1\"> </text>"))
    }
    @Test fun emphasizedReadingMergesKanjiWithoutInventingSubWordTimes() {
        val source = DirectLyricsRepository.Result(lyrics = "[00:01.000]今日の音", wordLyrics = "[1000,2000](1000,400)今(1400,400)日(1800,400)の(2200,800)音", translatedLyrics = "[00:01.000]今天的声音", source = "QQ音乐")
        val filled = JapanesePronunciationSupplement.fill(source)
        val ttml = requireNotNull(DesktopLyricsTtmlConverter.convert(filled, offsetMs = 100, primaryPronunciation = true))
        assertTrue(ttml.contains("itunes:timing=\"Word\""))
        assertTrue(ttml.contains("<span begin=\"0:00.900\" end=\"0:01.700\">kyou</span> "))
        assertTrue(ttml.contains("<span begin=\"0:01.700\" end=\"0:02.100\">no</span> "))
        assertTrue(ttml.contains("<text for=\"L1\">今日の音</text>"))
        assertTrue(ttml.contains("今天的声音"))
        assertTrue(requireNotNull(DesktopLyricsPresentation.fromTtml(ttml)).primaryPronunciation)
        assertEquals(source.wordLyrics, filled.wordLyrics)
    }
    @Test fun springThiefQQSampleKeepsProviderSyllableTimesAndHandlesOneMillisecondDrift() {
        val roma = "[15784,3906]ko (15784,168)u (15953,168)ka (16121,199)kyo (16320,133)u (16587,133)wo (16721,113)nu (16834,256)ke (17090,280)ta (17371,447)ra (17819,312)"
        val response = "<contentroma><![CDATA[$roma]]></contentroma>"
        val repository = DirectLyricsRepository()
        val source = DirectLyricsRepository.Result(lyrics = "[00:15.785]高架橋を抜けたら",
            wordLyrics = "[15785,3906](15785,336)高(16121,200)架(16321,400)橋(16721,114)を(16835,256)抜(17091,280)け(17371,448)た(17819,312)ら",
            romanizedLyrics = repository.qqRomanizedLyrics(response),
            romanizedWordLyrics = repository.qqRomanizedWordLyrics(response), source = "QQ音乐")
        val ttml = requireNotNull(DesktopLyricsTtmlConverter.convert(source, primaryPronunciation = true))
        assertTrue(ttml.contains("itunes:timing=\"Word\""))
        assertTrue(ttml.contains("<span begin=\"0:15.785\" end=\"0:15.952\">ko</span> "))
        assertTrue(ttml.contains("<span begin=\"0:15.953\" end=\"0:16.121\">u</span> "))
        assertEquals(10, Regex("<span ").findAll(ttml).count())
        assertTrue(requireNotNull(DesktopLyricsPresentation.fromTtml(ttml)).wordTimed)
    }

    @Test fun nativePhoneticTimingWorksEvenWithADifferentDictionaryReading() {
        val source = DirectLyricsRepository.Result(lyrics = "[00:01]今日の音",
            wordLyrics = "[1000,2000](1000,800)今日(1800,400)の(2200,800)音",
            romanizedLyrics = "[00:01]kon nichi no oto",
            romanizedWordLyrics = "[1000,2000](1000,400)kon (1400,400)nichi (1800,400)no (2200,800)oto")
        val ttml = requireNotNull(DesktopLyricsTtmlConverter.convert(source, primaryPronunciation = true))
        assertTrue(ttml.contains("itunes:timing=\"Word\""))
        assertTrue(ttml.contains("<span begin=\"0:01.000\" end=\"0:01.400\">kon</span> "))
        assertTrue(ttml.contains("<span begin=\"0:01.400\" end=\"0:01.800\">nichi</span> "))
        assertTrue(ttml.contains("<text for=\"L1\">今日の音</text>"))
    }

    @Test fun mismatchedNativePronunciationTrackDoesNotBorrowAnotherLinesTimes() {
        val source = DirectLyricsRepository.Result(lyrics = "[00:01]今日の音",
            wordLyrics = "[1000,2000](1000,800)今日(1800,400)の(2200,800)音",
            romanizedLyrics = "[00:01]special reading",
            romanizedWordLyrics = "[1000,2000](1000,800)different (1800,1200)reading")
        assertTrue(requireNotNull(DesktopLyricsTtmlConverter.convert(source, primaryPronunciation = true))
            .contains("itunes:timing=\"Line\""))
    }

    @Test fun nativeReadingMustAgreeWithDictionaryBeforeReusingWordTimes() {
        val source = DirectLyricsRepository.Result(lyrics = "[00:01.000]今日の音", wordLyrics = "[1000,2000](1000,800)今日(1800,400)の(2200,800)音", romanizedLyrics = "[00:01.000]special platform reading")
        val ttml = requireNotNull(DesktopLyricsTtmlConverter.convert(source, primaryPronunciation = true))
        assertTrue(ttml.contains("itunes:timing=\"Line\""))
        assertTrue(ttml.contains(">special platform reading</p>"))
        assertFalse(ttml.contains("<span"))
        assertTrue(ttml.contains("<text for=\"L1\">今日の音</text>"))
    }
    @Test fun emphasizeDoesNotGenerateWordTimesForLineLyricsOrAlterSourceResult() {
        val source = DirectLyricsRepository.Result(lyrics = "[00:01.000]好きだよ", romanizedLyrics = "[00:01.000]suki da yo")
        val emphasized = requireNotNull(DesktopLyricsTtmlConverter.convert(source, primaryPronunciation = true))
        assertTrue(emphasized.contains(">suki da yo</p>"))
        assertTrue(emphasized.contains("<text for=\"L1\">好きだよ</text>"))
        assertFalse(emphasized.contains("<span"))
        val original = requireNotNull(DesktopLyricsTtmlConverter.convert(source))
        assertTrue(original.contains(">好きだよ</p>"))
        assertTrue(original.contains("<text for=\"L1\">suki da yo</text>"))
    }
    @Test fun missingPronunciationKeepsOriginalEvenWhenEmphasisSelected() {
        val source = DirectLyricsRepository.Result(lyrics = "[00:01.000]Hello", wordLyrics = "[1000,1000](1000,1000)Hello")
        assertEquals(DesktopLyricsTtmlConverter.convert(source), DesktopLyricsTtmlConverter.convert(source, primaryPronunciation = true))
    }
    @Test fun positiveOffsetAdvancesLineTimesWithoutSynthesizingWordTimes() {
        val result = DirectLyricsRepository.Result(lyrics = "[00:01.000]one")
        val ttml = requireNotNull(DesktopLyricsTtmlConverter.convert(result, 3_000L, 100))
        assertTrue(ttml.contains("<p begin=\"0:00.900\" end=\"0:02.900\""))
        assertTrue(ttml.contains(">one</p>"))
        assertFalse(ttml.contains("<span"))
    }
    @Test fun convertsWordTimingAndOfficialTranslation() {
        val result = DirectLyricsRepository.Result(
            lyrics = "[00:01.000]A & B",
            wordLyrics = "[1000,900](1000,300)A &(1300,600) B",
            translatedLyrics = "[00:01.000]甲 < 乙",
        )
        val ttml = requireNotNull(DesktopLyricsTtmlConverter.convert(result))
        assertTrue(ttml.contains("itunes:timing=\"Word\""))
        assertTrue(ttml.contains("<span begin=\"0:01.000\" end=\"0:01.300\">A &amp;</span>"))
        assertTrue(ttml.contains("<text for=\"L1\">甲 &lt; 乙</text>"))
        assertEquals(TtmlTimingMode.WORD, com.tcrrry.lyrics.engine.hook.TtmlTimingPolicy.modeOf(ttml))
    }

    @Test fun lineTimingUsesNativeLineModeWithoutTimedSpans() {
        val result = DirectLyricsRepository.Result(lyrics = "[00:01.000]one\n[00:03.000]two")
        val ttml = requireNotNull(DesktopLyricsTtmlConverter.convert(result, 5_000L))
        assertTrue(ttml.contains("<p begin=\"0:01.000\" end=\"0:03.000\""))
        assertTrue(ttml.contains("<p begin=\"0:03.000\" end=\"0:05.000\" itunes:key=\"L2\">two</p>"))
        assertEquals(TtmlTimingMode.NON_WORD, com.tcrrry.lyrics.engine.hook.TtmlTimingPolicy.modeOf(ttml))
        assertFalse(ttml.contains("<span"))
    }

    @Test fun plainLyricsRemainAvailableWhenNoTimestampsExist() {
        val result = DirectLyricsRepository.Result(lyrics = "first line\nsecond line", durationMs = 6_000L)
        val ttml = requireNotNull(DesktopLyricsTtmlConverter.convert(result))
        assertTrue(ttml.contains(">first line</p>"))
        assertTrue(ttml.contains("<p begin=\"0:03.000\""))
        assertTrue(ttml.contains(">second line</p>"))
        assertEquals(TtmlTimingMode.NON_WORD, com.tcrrry.lyrics.engine.hook.TtmlTimingPolicy.modeOf(ttml))
    }

    @Test fun malformedWordTrackFallsBackToLineTiming() {
        val result = DirectLyricsRepository.Result(lyrics = "[00:01]君", wordLyrics = "not timed words")
        val ttml = requireNotNull(DesktopLyricsTtmlConverter.convert(result, 3000))
        assertEquals(TtmlTimingMode.NON_WORD, com.tcrrry.lyrics.engine.hook.TtmlTimingPolicy.modeOf(ttml))
        assertFalse(DesktopLyricsTtmlConverter.hasWordTiming(result))
        assertFalse(ttml.contains("<span"))
    }

    @Test fun missingTranslationKeepsItsKeyedPlaceholder() {
        val result = DirectLyricsRepository.Result(
            lyrics = "[00:01.000]one\n[00:03.000]two\n[00:05.000]three",
            translatedLyrics = "[00:01.000]一\n[00:05.000]三",
        )
        val ttml = requireNotNull(DesktopLyricsTtmlConverter.convert(result, 7_000L))
        assertTrue(ttml.contains("<text for=\"L1\">一</text><text for=\"L2\"> </text><text for=\"L3\">三</text>"))
    }

    @Test fun translationAndPronunciationAreSeparateKeyedTracks() {
        val result = DirectLyricsRepository.Result(
            lyrics = "[00:01]君だ\n[00:03]僕だ\n[00:05]空だ",
            translatedLyrics = "[00:01]你\n[00:03]我\n[00:05]天空",
            romanizedLyrics = "[00:01]kimi\n[00:05]sora & a",
            source = "网易云音乐",
        )
        val ttml = requireNotNull(DesktopLyricsTtmlConverter.convert(result, 7000, 100))
        assertEquals(TtmlTimingMode.NON_WORD, com.tcrrry.lyrics.engine.hook.TtmlTimingPolicy.modeOf(ttml))
        assertTrue(ttml.contains("<transliterations><transliteration xml:lang=\"ko-Latn\">"))
        assertTrue(ttml.contains("<text for=\"L1\">kimi</text><text for=\"L2\"> </text><text for=\"L3\">sora &amp; a</text>"))
        assertTrue(ttml.contains("<translations>"))
        assertTrue(ttml.indexOf("<translations>") < ttml.indexOf("<transliterations>"))
        assertTrue(ttml.contains("<p begin=\"0:00.900\""))
        val parsed = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(InputSource(StringReader(ttml)))
        assertEquals(1, parsed.getElementsByTagName("transliteration").length)
        assertTrue(requireNotNull(DesktopLyricsPresentation.fromTtml(ttml)).pronunciation)
    }

    @Test fun pronunciationDoesNotRequireTranslation() {
        val ttml = requireNotNull(DesktopLyricsTtmlConverter.convert(DirectLyricsRepository.Result(
            lyrics = "[00:01]君", romanizedLyrics = "[00:01]kimi")))
        assertTrue(ttml.contains("<transliterations>"))
        assertTrue(ttml.contains("xml:lang=\"ko\""))
        assertFalse(ttml.contains("<translations>"))
    }

    @Test fun missingUnalignedAndIdenticalPronunciationDoNotEnableATrack() {
        val original = DirectLyricsRepository.Result(lyrics = "[00:01]君")
        for (payload in listOf("", "[01:00]kimi", "[00:01]君")) {
            val ttml = requireNotNull(DesktopLyricsTtmlConverter.convert(original.copy(romanizedLyrics = payload)))
            assertFalse(ttml.contains("<transliterations>"))
            assertFalse(requireNotNull(DesktopLyricsPresentation.fromTtml(ttml)).pronunciation)
        }
    }
    @Test fun offsetShiftsLineTimingInBothDirectionsAndKeepsTranslationKeys() {
        val result = DirectLyricsRepository.Result(lyrics = "[00:10]hello\n[00:15]world", translatedLyrics = "[00:10]你好\n[00:15]世界")
        val early = requireNotNull(DesktopLyricsTtmlConverter.convert(result, 20000L, 1500))
        val late = requireNotNull(DesktopLyricsTtmlConverter.convert(result, 20000L, -1500))
        assertTrue(early.contains("<p begin=\"0:08.500\""))
        assertTrue(late.contains("<p begin=\"0:11.500\""))
        assertTrue(early.contains("<text for=\"L1\">你好</text>"))
        assertTrue(late.contains("<text for=\"L1\">你好</text>"))
    }

    @Test fun offsetShiftsActualWordSpansRatherThanOnlyLineStart() {
        val result = DirectLyricsRepository.Result(lyrics = "[00:10]hello", wordLyrics = "[10000,2000](10000,1000,0)hel(11000,1000,0)lo")
        val early = requireNotNull(DesktopLyricsTtmlConverter.convert(result, 20000L, 1500))
        val late = requireNotNull(DesktopLyricsTtmlConverter.convert(result, 20000L, -1500))
        assertTrue(early.contains("<span begin=\"0:08.500\" end=\"0:09.500\">hel</span>"))
        assertTrue(late.contains("<span begin=\"0:11.500\" end=\"0:12.500\">hel</span>"))
    }

}
