package com.tcrrry.lyrics.engine.hook

import android.widget.TextView
import com.tcrrry.lyrics.plugin.TestPluginContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class LyricsAuxiliaryTextTest {
    @org.junit.Before fun attach() { TestPluginContext.attach() }
    @org.junit.After fun close() { TestPluginContext.current.close() }
    private fun escaped(value:String)=value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")
    private val fragment="<span begin=\"57.946\" end=\"58.255\" xmlns=\"http://www.w3.org/ns/ttml\">Oh-</span>"+
        "<span begin=\"58.255\" end=\"58.817\">oh 我只能一直往前走</span>"
    @Test fun escapedTimedSpansNeverAppearAsVisibleXml() {
        for (input in listOf(fragment,escaped(fragment),escaped(escaped(fragment))))
            assertEquals("Oh-oh 我只能一直往前走",LyricsAuxiliaryText.plain(input))
    }
    @Test fun translationPayloadAndLookupCleanSerializedTextWithoutChangingTimedBody() {
        val body="<body><div><p itunes:key=\"a\" begin=\"57s\" end=\"60s\">${escaped(fragment)}</p></div></body>"
        val raw="<tt><head><metadata><translations><translation><text for=\"a\">${escaped(escaped(fragment))}</text></translation></translations></metadata></head>$body</tt>"
        assertEquals(listOf("Oh-oh 我只能一直往前走"),TtmlSubtitleTrack.lines(raw))
        val lookup=NativeSubtitleLookup(raw)
        assertEquals("Oh-oh 我只能一直往前走",lookup.forOriginal("Oh-oh 我只能一直往前走")?.translation)
        assertTrue(raw.contains(body))
    }
    @Test fun ordinaryLyricsEntitiesFormattingAndTraditionalCharactersStayIntact() {
        for (input in listOf("繁體中文與简体中文", "I <3 you", "begin=57.946", "1 > 0"))
            assertEquals(input,LyricsAuxiliaryText.plain(input))
        assertEquals("rock & roll",LyricsAuxiliaryText.plain("rock &amp; roll"))
        assertEquals("正确译文",LyricsAuxiliaryText.plain("<span>正确译文</span>"))
    }
    @Test fun lineBindingRecoversReadableSubtitleFromEncodedNativeText() {
        val view=TextView(RuntimeEnvironment.getApplication())
        NativeLineLyricsPresentation.repairText(view,escaped(fragment),null,true)
        assertEquals("Oh-oh 我只能一直往前走",view.text.toString())
        NativeLineLyricsPresentation.repairText(view,escaped(escaped(fragment)),null,true)
        assertEquals("Oh-oh 我只能一直往前走",view.text.toString())
    }
    @Test fun nativeInflationKeepsOrdinaryFormattingAndDecodesTimedFragmentsOnlyOnce() {
        for (input in listOf("<span>正确译文 &amp; 正文</span>","I &lt;b&gt;love&lt;/b&gt; you","I <3 you"))
            assertEquals(input,LyricsAuxiliaryText.forNative(input))
        assertEquals("Oh-oh 我只能一直往前走", LyricsAuxiliaryText.forNative(escaped(fragment)))
    }
    @Test fun nativeDelimiterLossIsRecoveredWithoutSongSpecificRules() {
        val broken = "span begin=28.972 end=29.183 xmlns=http://www.w3.org/ns/ttmlI'm/span " +
            "span begin=29.183 end=29.791 xmlns=http://www.w3.org/ns/ttmlsorry/span"
        assertEquals("I'm sorry", LyricsAuxiliaryText.plain(broken))
        assertEquals("I'm sorry", LyricsAuxiliaryText.forNative(broken))
        assertEquals("'cause", LyricsAuxiliaryText.plain("span begin=1 end=2 xmlns=http://www.w3.org/ns/ttml'cause/span"))
        for (ordinary in listOf("span begin=28.972", "span of my life", "begin=1 end=2", "I'm sorry"))
            assertEquals(ordinary, LyricsAuxiliaryText.forNative(ordinary))
    }
    @Test fun exactDeviceTextWithAdjacentStrippedTagsAndEntitiesIsCleaned() {
        val broken = "I&#39;m sorry 来/spanspan begin=51.786 end=52.138 xmlns=http://www.w3.org/ns/ttml不/spanspan begin=52.138 end=52.887 xmlns=http://www.w3.org/ns/ttml及/spanspan begin=52.887 end=53.423 xmlns=http://www.w3.org/ns/ttml把/spanspan begin=53.423 end=53.548 xmlns=http://www.w3.org/ns/ttml你/spanspan begin=53.548 end=53.903 xmlns=http://www.w3.org/ns/ttml藏/spanspan begin=54.199 end=54.559 xmlns=http://www.w3.org/ns/ttml起/spanspan begin=54.559 end=56.120 xmlns=http://www.w3.org/ns/ttml来"
        assertEquals("I'm sorry 来不及把你藏起来", LyricsAuxiliaryText.plain(broken))
        assertEquals("I'm sorry 来不及把你藏起来", LyricsAuxiliaryText.forNative(broken))
    }
    @Test fun timedNamespacesNestingAndEntityLayersDoNotLeakIntoAuxiliaries() {
        val raw = "<tt:span xmlns:tt=\"http://www.w3.org/ns/ttml\" end=\"31s\" begin=\"30s\">" +
            "<tt:span begin=\"30s\" end=\"30.5s\">A&amp;B </tt:span>不是坏男孩</tt:span>"
        var encoded = raw
        repeat(5) {
            assertEquals("A&B 不是坏男孩", LyricsAuxiliaryText.plain(encoded))
            val native = LyricsAuxiliaryText.forNative(encoded)
            assertEquals("A&B 不是坏男孩", native)
            encoded = escaped(encoded)
        }
        assertEquals("first\nsecond", LyricsAuxiliaryText.plain("first<br>second"))
    }
}
