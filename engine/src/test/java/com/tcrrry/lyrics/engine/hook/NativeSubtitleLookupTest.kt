package com.tcrrry.lyrics.engine.hook

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class NativeSubtitleLookupTest {
    private fun document(body: String, pron: String, trans: String = "") =
        "<tt><head><metadata><transliterations><transliteration>$pron</transliteration></transliterations><translations><translation>$trans</translation></translations></metadata></head><body><div>$body</div></body></tt>"
    @Test fun generatedUntimedMetadataRemainsAvailableWhenNativePronunciationGetterIsBlank() {
        val lookup = NativeSubtitleLookup(document(
            "<p itunes:key=\"L1\"><span begin=\"0s\" end=\"1s\">僕にも</span><span begin=\"1s\" end=\"2s\">そんな過去</span></p>",
            "<text for=\"L1\">bo ku ni mo son na ka ko</text>", "<text for=\"L1\">我也有那样的过去</text>"))
        val tracks = requireNotNull(lookup.forOriginal("僕にも そんな過去"))
        assertEquals("bo ku ni mo son na ka ko", tracks.pronunciation)
        assertEquals("我也有那样的过去", tracks.translation)
        assertNull(lookup.forOriginal("另一句"))
    }
    @Test fun duplicateOriginalsWithDifferentSubtitlesAreNotMismatched() {
        val lookup = NativeSubtitleLookup(document(
            "<p itunes:key=\"a\">僕</p><p itunes:key=\"b\">僕</p>",
            "<text for=\"a\">boku</text><text for=\"b\">watashi</text>",
            "<text for=\"a\">我</text><text for=\"b\">我</text>"))
        assertNull(lookup.forOriginal("僕")?.pronunciation)
        assertEquals("我", lookup.forOriginal("僕")?.translation)
    }
    @Test fun entitiesAndTimedPronunciationAreDecodedWithoutArtificialWordSpaces() {
        val lookup = NativeSubtitleLookup(document(
            "<p itunes:key=\"a\"><span>A&amp;B</span></p>",
            "<text for=\"a\"><span>a &amp; </span><span>b</span></text>"))
        assertEquals("a & b", lookup.forOriginal("A&amp;B")?.pronunciation)
    }
    @Test fun simplifiedDisplayStillFindsCantoneseReadingsFromTraditionalSource() {
        val originals=listOf("難離難捨想抱緊些","茫茫人生好像荒野","常情如此不可推卸","任世間再冷酷想起這單車")
        val displayed=listOf("难离难舍想抱紧些","茫茫人生好像荒野","常情如此不可推卸","任世间再冷酷想起这单车")
        val input=document(originals.mapIndexed { i,text -> "<p itunes:key=\"L$i\">$text</p>" }.joinToString(""),
            originals.indices.joinToString("") { "<text for=\"L$it\">reading$it</text>" })
        val lookup=NativeSubtitleLookup(input)
        assertEquals(displayed.size,displayed.count { !lookup.forOriginal(it)?.pronunciation.isNullOrBlank() })
    }

    @Test fun traditionalSimplifiedCollisionDoesNotGuessDifferentReadings() {
        val lookup=NativeSubtitleLookup(document("<p itunes:key=\"a\">發</p><p itunes:key=\"b\">髮</p>",
            "<text for=\"a\">faat3</text><text for=\"b\">faat1</text>"))
        assertNull(lookup.forOriginal("发")?.pronunciation)
        assertEquals("漢字かな",com.tcrrry.lyrics.engine.lyrics.ChineseTextKey.of("漢字かな"))
    }

}
