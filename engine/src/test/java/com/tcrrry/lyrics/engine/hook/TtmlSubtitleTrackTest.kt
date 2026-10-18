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
}
