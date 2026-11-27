package com.tcrrry.lyrics.engine.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(manifest = org.robolectric.annotation.Config.NONE, sdk = [35])
class TtmlSubtitleTrackTest {
    @org.junit.Before fun attachPlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.attach() }
    @org.junit.After fun closePlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.current.close() }
    @Test fun `old cached Chinese subtitle is removed without changing word body`() {
        val body = "<body><div><p itunes:key=\"L1\"><span begin=\"1s\" end=\"2s\">我的世界</span></p></div></body>"
        val raw = "<tt xml:lang=\"ko\"><head><metadata><iTunesMetadata>" +
            "<translations><translation type=\"subtitle\" xml:lang=\"zh-Hans\"><text for=\"L1\">我的世界</text></translation></translations>" +
            "</iTunesMetadata></metadata></head>$body</tt>"
        val cleaned = TtmlSubtitleTrack.cleanChineseSubtitles(raw)
        assertTrue(cleaned.contains(body))
        assertTrue(!cleaned.contains("<translations>"))
        assertTrue(cleaned.contains("xml:lang=\"zh\""))
    }
    @Test fun mixedLanguageChineseSubtitleRebuildDoesNotSerializeTimedSpansAsText() {
        val body = "<body><div>" +
            "<p itunes:key=\"L1\" begin=\"20s\" end=\"22s\"><span begin=\"20s\" end=\"22s\">寂寞寂寞不好</span></p>" +
            "<p itunes:key=\"L2\" begin=\"22s\" end=\"24s\"><span begin=\"22s\" end=\"24s\">with you</span></p>" +
            "<p itunes:key=\"L3\" begin=\"28.972\" end=\"30s\"><span begin=\"28.972\" end=\"30s\">I'm sorry 我不是壞男孩</span></p>" +
            "</div></body>"
        val raw = "<tt itunes:timing=\"Word\"><head><metadata><translations>" +
            "<translation type=\"subtitle\" xml:lang=\"zh-Hans\">" +
            "<text for=\"L1\">寂寞寂寞不好</text><text for=\"L2\">与你一起</text>" +
            "<text for=\"L3\"><span begin=\"28.972\" end=\"29.183\" xmlns=\"http://www.w3.org/ns/ttml\">I'm </span>" +
            "<span begin=\"29.183\" end=\"29.791\" xmlns=\"http://www.w3.org/ns/ttml\">sorry 我不是坏男孩</span></text>" +
            "</translation></translations></metadata></head>$body</tt>"
        val cleaned = TtmlSubtitleTrack.cleanChineseSubtitles(raw)
        assertTrue(cleaned.contains(body))
        assertTrue("timed structure must never become literal subtitle text", !cleaned.contains("&lt;span"))
        assertEquals("I'm sorry 我不是坏男孩", TtmlSubtitleTrack.subtitleValues(cleaned)[2])
    }
    @Test fun `fallback supplement preserves duet agents and alignment`() {
        val body = "<body><div><p ttm:agent=\"v1\" tts:textAlign=\"start\" itunes:key=\"L1\">hello</p>" +
            "<p ttm:agent=\"v2\" tts:textAlign=\"end\" itunes:key=\"L2\">goodbye</p></div></body>"
        val raw = "<tt xmlns:ttm=\"http://www.w3.org/ns/ttml#metadata\" xmlns:tts=\"http://www.w3.org/ns/ttml#styling\">$body</tt>"
        assertTrue(requireNotNull(TtmlSubtitleTrack.attach(raw, mapOf(0 to "你好", 1 to "再见"))).contains(body))
    }
    @Test fun `subtitle track preserves word timing and fills untranslated rows`() {
        val raw = "<tt xmlns=\"http://www.w3.org/ns/ttml\" " +
            "xmlns:itunes=\"http://music.apple.com/lyric-ttml-internal\" itunes:timing=\"Word\">" +
            "<body><div><p begin=\"1s\" end=\"2s\" itunes:key=\"L1\">" +
            "<span begin=\"1s\" end=\"2s\">hello</span></p>" +
            "<p begin=\"2s\" end=\"3s\"><span begin=\"2s\" end=\"3s\">world</span></p>" +
            "</div></body></tt>"
        assertEquals(listOf("hello", "world"), TtmlSubtitleTrack.lines(raw))
        val result = requireNotNull(TtmlSubtitleTrack.attach(raw, mapOf(0 to "你好")))
        assertTrue(result.contains("<span begin=\"1s\" end=\"2s\">hello</span>"))
        assertTrue(result.contains("<text for=\"L1\">你好</text>"))
        assertTrue(result.contains("<text for=\"TcrrryL2\"> </text>"))
        assertTrue(result.contains("itunes:key=\"TcrrryL2\""))
        assertTrue(TtmlTimingPolicy.metadataOf(result).hasTranslation)
    }
    @Test fun `cleanRedundantPronunciation strips English transliteration block and resets marker`() {
        val marker = com.tcrrry.lyrics.engine.lyrics.DesktopLyricsPresentation("网易云音乐", true, true, false, false, true).marker()
        val raw = "<?xml version=\"1.0\" encoding=\"utf-8\"?>$marker<!--tcrrry-source-language-v1 en-->" +
            "<tt xml:lang=\"ko\" xmlns:itunes=\"http://music.apple.com/lyric-ttml-internal\"><head><metadata><iTunesMetadata>" +
            "<transliterations><transliteration xml:lang=\"ko-Latn\"><text for=\"L1\">still remember, when we met!</text><text for=\"L2\">im gonna stay</text></transliteration></transliterations>" +
            "</iTunesMetadata></metadata></head>" +
            "<body><div><p itunes:key=\"L1\">Still remember when we met</p><p itunes:key=\"L2\">I'm gonna stay</p></div></body></tt>"
        val cleaned = TtmlSubtitleTrack.cleanRedundantPronunciation(raw)
        assertTrue(!cleaned.contains("<transliterations>"))
        assertTrue(!cleaned.contains("xml:lang=\"ko\""))
        assertTrue(cleaned.contains("xml:lang=\"en\""))
        assertEquals(false, com.tcrrry.lyrics.engine.lyrics.DesktopLyricsPresentation.fromTtml(cleaned)?.pronunciation)
    }
    @Test fun `cleanRedundantPronunciation keeps Cantonese timed spans while blanking English rows`() {
        val cantoneseSpan = "<span begin=\"1s\" end=\"2s\">gaa soeng </span><span begin=\"2s\" end=\"3s\">bat sei dik</span>"
        val body = "<body><div><p itunes:key=\"L1\"><span begin=\"1s\" end=\"2s\">加上</span><span begin=\"2s\" end=\"3s\">不死的</span></p>" +
            "<p itunes:key=\"L2\"><span begin=\"4s\" end=\"6s\">Still remember when we met</span></p></div></body>"
        val raw = "<tt xml:lang=\"ko\" xmlns:itunes=\"http://music.apple.com/lyric-ttml-internal\"><head><metadata><iTunesMetadata>" +
            "<transliterations><transliteration xml:lang=\"ko-Latn\"><text for=\"L1\">$cantoneseSpan</text>" +
            "<text for=\"L2\"><span begin=\"4s\" end=\"6s\">Still remember when we met</span></text></transliteration></transliterations>" +
            "<translations><translation type=\"subtitle\" xml:lang=\"zh-Hans\"><text for=\"L1\"> </text><text for=\"L2\">依然记得</text></translation></translations>" +
            "</iTunesMetadata></metadata></head>$body</tt>"
        val cleaned = TtmlSubtitleTrack.cleanRedundantPronunciation(raw)
        assertTrue(cleaned.contains(body))
        assertTrue(cleaned.contains("<text for=\"L1\">$cantoneseSpan</text>"))
        assertTrue(cleaned.contains("<text for=\"L2\"> </text>"))
        assertEquals(mapOf(0 to "gaa soeng bat sei dik"), TtmlSubtitleTrack.subtitleValues(cleaned, true))
    }
    @Test fun `native remember filters pure English transliterations while keeping mixed Cantonese`() {
        val englishPointer = Any()
        val englishTtml = "<tt xmlns:itunes=\"urn\"><head><metadata><transliterations><transliteration xml:lang=\"ko-Latn\">" +
            "<text for=\"L1\">still remember when we met</text></transliteration></transliterations></metadata></head>" +
            "<body><div><p itunes:key=\"L1\">Still remember when we met</p></div></body></tt>"
        NativeLyricsPronunciationSubtitle.remember(englishPointer, englishTtml)
        assertTrue(!NativeLyricsPronunciationSubtitle.hasManagedPronunciation(englishPointer))

        val mixedPointer = Any()
        val mixedTtml = "<tt xmlns:itunes=\"urn\"><head><metadata><transliterations><transliteration xml:lang=\"ko-Latn\">" +
            "<text for=\"L1\">gaa soeng bat sei dik</text><text for=\"L2\">Still remember</text></transliteration></transliterations></metadata></head>" +
            "<body><div><p itunes:key=\"L1\">加上不死的</p><p itunes:key=\"L2\">Still remember</p></div></body></tt>"
        NativeLyricsPronunciationSubtitle.remember(mixedPointer, mixedTtml)
        assertTrue(NativeLyricsPronunciationSubtitle.hasManagedPronunciation(mixedPointer))
        assertEquals("gaa soeng bat sei dik", NativeLyricsPronunciationSubtitle.lineSubtitles(mixedPointer, "加上不死的")?.pronunciation)
        assertEquals(null, NativeLyricsPronunciationSubtitle.lineSubtitles(mixedPointer, "Still remember")?.pronunciation)
    }
}
