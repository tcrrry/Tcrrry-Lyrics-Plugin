package com.tcrrry.lyrics.engine.hook

import com.tcrrry.lyrics.sources.DirectLyricsRepository
import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(manifest = org.robolectric.annotation.Config.NONE, sdk = [35])
class CrossSourceAuxiliaryTest {
    @org.junit.Before fun attachPlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.attach() }
    @org.junit.After fun closePlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.current.close() }
    private val primary = "<tt xmlns:itunes=\"urn\" itunes:timing=\"Line\"><body><div><p begin=\"1s\" end=\"3s\" itunes:key=\"L1\">君の声</p></div></body></tt>"
    private val source = DirectLyricsRepository.Result(lyrics = "[00:01.000]君の声", translatedLyrics = "[00:01.000]你的声音",
        romanizedLyrics = "[00:01.000]kimi no koe", source = "QQ音乐")
    @Test fun exactLineGetsAuxiliaryButKeepsNativeBodyAndTiming() {
        val merged = CrossSourceAuxiliary.enrich(primary, source)
        assertTrue(merged.contains("你的声音")); assertTrue(merged.contains("kimi no koe"))
        assertTrue(merged.contains(primary.substringAfter("<body>")))
        assertTrue(merged.contains("itunes:timing=\"Line\""))
    }
    @Test fun wrongTextOrTimeNeverMerges() {
        assertEquals(primary, CrossSourceAuxiliary.enrich(primary, source.copy(lyrics = "[00:01.000]他の声")))
        assertEquals(primary, CrossSourceAuxiliary.enrich(primary, source.copy(lyrics = "[00:10.000]君の声")))
    }
    @Test fun repeatedRefrainIsAmbiguousAndExistingTrackWins() {
        val repeated = primary.replace("</div>", "<p begin=\"1.5s\" end=\"5s\">君の声</p></div>")
        assertEquals(repeated, CrossSourceAuxiliary.enrich(repeated, source))
        val originalSubtitle = requireNotNull(TtmlSubtitleTrack.attach(primary, mapOf(0 to "已有翻译")))
        val merged = CrossSourceAuxiliary.enrich(originalSubtitle, source)
        assertTrue(merged.contains("已有翻译")); assertFalse(merged.contains("你的声音"))
    }
    @Test fun separatedChorusesReceiveTheirOwnTranslationWithoutReplacingNativeWords() {
        val native = primary.replace("itunes:timing=\"Line\"", "itunes:timing=\"Word\"")
            .replace(">君の声</p>", "><span begin=\"1s\" end=\"2s\">君の</span><span begin=\"2s\" end=\"3s\">声</span></p>")
            .replace("</div>", "<p begin=\"20s\" end=\"22s\" itunes:key=\"L2\">君の声</p></div>")
        val merged = CrossSourceAuxiliary.enrich(native, source.copy(
            lyrics = "[00:01.000]君の声\n[00:20.000]君の声",
            translatedLyrics = "[00:01.000]你的声音\n[00:20.000]再次听见你的声音"))
        assertEquals(mapOf(0 to "你的声音", 1 to "再次听见你的声音"), TtmlSubtitleTrack.subtitleValues(merged))
        assertTrue(merged.contains(native.substringAfter("<body>")))
        assertTrue(TtmlTimingPolicy.isWord(merged))
    }
    @Test fun crossingLineOrderIsRejected() {
        val native = primary.replace("君の声", "星の光").replace("</div>", "<p begin=\"2s\" end=\"3s\">君の声</p></div>")
        val reversed = source.copy(lyrics = "[00:01.000]君の声\n[00:02.000]星の光",
            translatedLyrics = "[00:01.000]你的声音\n[00:02.000]星星的光", romanizedLyrics = "")
        assertEquals(native, CrossSourceAuxiliary.enrich(native, reversed))
    }
    @Test fun partialAuxiliaryTrackFillsOnlyItsMissingRows() {
        val two = primary.replace("</div>", "<p begin=\"4s\" end=\"6s\" itunes:key=\"L2\">星の光</p></div>")
        val existing = requireNotNull(TtmlSubtitleTrack.attach(two, mapOf(0 to "原生译文")))
        val filled = requireNotNull(TtmlSubtitleTrack.attach(existing, mapOf(0 to "不得替换", 1 to "星星的光")))
        assertTrue(filled.contains("原生译文")); assertTrue(filled.contains("星星的光")); assertFalse(filled.contains("不得替换"))
        assertEquals(mapOf(0 to "原生译文", 1 to "星星的光"), TtmlSubtitleTrack.subtitleValues(filled))
        val reading = requireNotNull(TtmlSubtitleTrack.attachPronunciation(filled, mapOf(0 to "kimi no koe")))
        val complete = requireNotNull(TtmlSubtitleTrack.attachPronunciation(reading, mapOf(0 to "wrong", 1 to "hoshi no hikari")))
        assertTrue(complete.contains("kimi no koe")); assertTrue(complete.contains("hoshi no hikari")); assertFalse(complete.contains("wrong"))
        assertTrue(complete.contains(two.substringAfter("<body>")))
    }
    @Test fun selfClosingEmptySubtitleIsFilledWithoutDuplicateKey() {
        val two = primary.replace("</div>", "<p begin=\"4s\" end=\"6s\" itunes:key=\"L2\">星の光</p></div>")
        val existing = requireNotNull(TtmlSubtitleTrack.attach(two, mapOf(0 to "原生译文")))
            .replace("<text for=\"L2\"> </text>", "<text for=\"L2\"/>")
        val filled = requireNotNull(TtmlSubtitleTrack.attach(existing, mapOf(1 to "星星的光")))
        assertEquals(1, Regex("<text for=\"L2\"").findAll(filled).count())
        assertEquals("星星的光", TtmlSubtitleTrack.subtitleValues(filled)[1])
    }
}
