package com.tcrrry.lyrics.engine.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(manifest = org.robolectric.annotation.Config.NONE, sdk = [35])
class TtmlPronunciationTimingTest {
    @org.junit.Before fun attachPlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.attach() }
    @org.junit.After fun closePlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.current.close() }
    private val body = "<body><div><p begin=\"0:01.200\" end=\"0:03.400\" itunes:key=\"L1\" ttm:agent=\"v2\">" +
        "<span begin=\"0:01.200\" end=\"0:02.000\">君</span> <span begin=\"0:02.000\" end=\"0:03.400\">だ</span></p>" +
        "<p begin='0:03.400' end='0:05.000' itunes:key='L2'>空</p></div></body>"
    private val translation = "<translations><translation xml:lang=\"zh-Hans\"><text for=\"L1\">是你</text></translation></translations>"
    private fun document(entries: String, timing: String = "Word") =
        "<tt itunes:timing=\"$timing\"><head><metadata><iTunesMetadata>$translation" +
            "<transliterations><transliteration xml:lang=\"ko-Latn\">$entries</transliteration></transliterations>" +
            "</iTunesMetadata></metadata></head>$body</tt>"

    @Test fun `plain pronunciation becomes one span using exact line interval`() {
        val prepared = TtmlPronunciationTiming.prepare(document("<text for=\"L1\">kimi &amp; da</text>"))
        assertTrue(prepared.contains("<text for=\"L1\" begin=\"0:01.200\" end=\"0:03.400\"><span begin=\"0:01.200\" end=\"0:03.400\">kimi &amp; da</span></text>"))
        assertTrue(prepared.contains(body))
        assertTrue(prepared.contains(translation))
    }
    @Test fun `old cached plain track is repaired idempotently`() {
        val original = document("<text for=\"L1\">kimi</text><text for='L2'>sora</text>")
        val repaired = TtmlPronunciationTiming.prepare(original)
        assertEquals(repaired, TtmlPronunciationTiming.prepare(repaired))
        assertTrue(repaired.contains("<span begin='0:03.400' end='0:05.000'>sora</span>"))
    }
    @Test fun `existing word pronunciation is preserved`() {
        val original = document("<text for=\"L1\"><span begin=\"1.2s\" end=\"2s\">ki</span> <span begin=\"2s\" end=\"3.4s\">mi</span></text>")
        assertEquals(original, TtmlPronunciationTiming.prepare(original))
    }
    @Test fun `empty row keeps its place without borrowing another pronunciation`() {
        val repaired = TtmlPronunciationTiming.prepare(document("<text for=\"L1\">kimi</text><text for='L2'> </text>"))
        assertTrue(repaired.contains("<span begin='0:03.400' end='0:05.000'> </span>"))
    }
    @Test fun `missing key or interval is not guessed`() {
        val original = document("<text for=\"missing\">kimi</text><text>sora</text>")
        assertEquals(original, TtmlPronunciationTiming.prepare(original))
        val noEnd = document("<text for=\"L1\">kimi</text>").replace("end=\"0:03.400\" itunes:key", "itunes:key")
        assertEquals(noEnd, TtmlPronunciationTiming.prepare(noEnd))
    }
    @Test fun `line timing stays unchanged`() {
        val original = document("<text for=\"L1\">kimi</text>", "Line")
        assertEquals(original, TtmlPronunciationTiming.prepare(original))
    }
    @Test fun `entry timing attributes are not duplicated`() {
        val repaired = TtmlPronunciationTiming.prepare(document("<text for=\"L1\" begin=\"1s\" end=\"4s\">kimi</text>"))
        assertTrue(repaired.contains("<text for=\"L1\" begin=\"1s\" end=\"4s\"><span begin=\"0:01.200\" end=\"0:03.400\">kimi</span></text>"))
    }
}
