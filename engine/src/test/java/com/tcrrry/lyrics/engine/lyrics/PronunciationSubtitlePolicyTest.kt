package com.tcrrry.lyrics.engine.lyrics

import com.tcrrry.lyrics.sources.DirectLyricsRepository
import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(manifest = org.robolectric.annotation.Config.NONE, sdk = [35])
class PronunciationSubtitlePolicyTest {
    @org.junit.Before fun attachPlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.attach() }
    @org.junit.After fun closePlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.current.close() }

    @Test fun redundantLatinPronunciationsAreRejected() {
        assertNull(PronunciationSubtitlePolicy.usable("Still remember when we met", "Still remember when we met"))
        assertNull(PronunciationSubtitlePolicy.usable("Still remember when we met", "still remember, when we met!"))
        assertNull(PronunciationSubtitlePolicy.usable("I'm gonna miss you", "Im gonna miss you"))
        assertNull(PronunciationSubtitlePolicy.usable("Café", "Cafe"))
        assertNull(PronunciationSubtitlePolicy.usable("We barely knew", "We barely new"))
    }

    @Test fun nonLatinAndMixedLinePronunciationsRemainIntact() {
        assertEquals("gaa soeng bat sei dik", PronunciationSubtitlePolicy.usable("加上 不死的", "gaa soeng bat sei dik"))
        assertEquals("kimi no koe", PronunciationSubtitlePolicy.usable("君の声", "kimi no koe"))
        assertEquals("se sang ui mo seo ri", PronunciationSubtitlePolicy.usable("세상의 모서리", "se sang ui mo seo ri"))
        assertEquals("privet mir", PronunciationSubtitlePolicy.usable("Привет мир", "privet mir"))
        assertEquals(
            "Where did you go sou sou do gau ba pung dou",
            PronunciationSubtitlePolicy.usable("Where did you go 數數多久不碰到", "Where did you go sou sou do gau ba pung dou"),
        )
        assertEquals("君よ", PronunciationSubtitlePolicy.usable("kimi yo", "君よ"))
    }

    @Test fun pureEnglishSongDoesNotEnableTransliterationsDespitePunctuationDifferences() {
        val result = DirectLyricsRepository.Result(
            source = "网易云音乐",
            lyrics = "[00:01.000]Still remember when we met\n[00:05.000]I'm gonna stay",
            romanizedLyrics = "[00:01.000]still remember, when we met\n[00:05.000]Im gonna stay",
        )
        val ttml = requireNotNull(DesktopLyricsTtmlConverter.convert(result))
        assertFalse(ttml.contains("<transliterations>"))
        assertFalse(ttml.contains("xml:lang=\"ko\""))
        assertFalse(requireNotNull(DesktopLyricsPresentation.fromTtml(ttml)).pronunciation)
    }

    @Test fun mixedCantoneseAndEnglishSongKeepsCantoneseReadingsAndBlanksEnglishLines() {
        val result = DirectLyricsRepository.Result(
            source = "网易云音乐",
            lyrics = "[00:01.000]加上 不死的\n[00:05.000]Still remember when we met",
            romanizedLyrics = "[00:01.000]gaa soeng bat sei dik\n[00:05.000]still remember when we met",
        )
        val ttml = requireNotNull(DesktopLyricsTtmlConverter.convert(result))
        assertTrue(ttml.contains("<transliterations>"))
        assertTrue(ttml.contains("<text for=\"L1\">gaa soeng bat sei dik</text>"))
        assertTrue(ttml.contains("<text for=\"L2\"> </text>"))
        assertTrue(requireNotNull(DesktopLyricsPresentation.fromTtml(ttml)).pronunciation)
    }
}
