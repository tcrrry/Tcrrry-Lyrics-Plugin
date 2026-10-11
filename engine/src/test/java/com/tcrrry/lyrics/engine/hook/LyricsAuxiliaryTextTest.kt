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
        assertEquals("Oh-oh 我只能一直往前走",android.text.Html.fromHtml(LyricsAuxiliaryText.forNative(escaped(fragment)),0).toString())
    }
}
