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
}
