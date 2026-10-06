package com.tcrrry.lyrics.engine.hook

import com.tcrrry.lyrics.engine.lyrics.DesktopLyricsPresentation
import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(manifest = org.robolectric.annotation.Config.NONE, sdk = [35])
class NativeLyricsPhoneticPresentationTest {
    @org.junit.Before fun attachPlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.attach() }
    @org.junit.After fun closePlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.current.close() }
    private val word = """<tt xmlns:itunes="urn:itunes" itunes:timing="Word"><head><metadata><transliterations><transliteration xml:lang="ja-Latn"><text for="L1">ki mi</text></transliteration></transliterations><translations><translation xml:lang="zh"><text for="L1">你</text></translation></translations></metadata></head><body><p itunes:key="L1" begin="1s" end="3s"><span begin="1s" end="2s">君</span><span begin="2s" end="3s">よ</span></p></body></tt>"""
    @Test fun normalPresentationKeepsNativeWordTimingAndAllowsPhoneticEmphasis() {
        val rendered = NativeLyricsPhoneticPresentation.render(word, false)
        assertTrue(rendered.contains("<span begin=\"1s\" end=\"2s\">君</span>"))
        assertTrue(TtmlTimingPolicy.isWord(rendered))
        assertTrue(DesktopLyricsPresentation.fromTtml(rendered)!!.pronunciation)
        assertFalse(DesktopLyricsPresentation.fromTtml(rendered)!!.primaryPronunciation)
    }
    @Test fun oneUnalignedLineDoesNotDowngradeOtherNativeWordReadings() {
        val input = word.replace(">ki mi</text>", ">kimi yo</text><text for=\"L2\">unrelated reading</text>")
            .replace("</body>", "<p itunes:key=\"L2\" begin=\"4s\" end=\"6s\"><span begin=\"4s\" end=\"5s\">好き</span><span begin=\"5s\" end=\"6s\">だ</span></p></body>")
        val rendered = NativeLyricsPhoneticPresentation.render(input, true)
        assertTrue(TtmlTimingPolicy.isWord(rendered))
        assertTrue(rendered.contains("begin=\"0:01.000\" end=\"0:02.000\">kimi</span> "))
        assertTrue(rendered.contains("begin=\"4s\" end=\"6s\">unrelated reading</span>"))
        assertEquals("你", TtmlSubtitleTrack.subtitleValues(rendered)[0])
        assertEquals("好きだ", TtmlSubtitleTrack.subtitleValues(rendered, true)[1])
        assertEquals("发音逐字 1 句 · 逐行 1 句", NativeLyricsPhoneticPresentation.alignmentDetail(rendered))
    }
    @Test fun nativeStylingWrapperDoesNotHideInnerWordTimes() {
        val input = word.replace(">ki mi</text>", ">kimi yo</text>")
            .replace("><span begin=\"1s\"", "><span tts:style=\"s1\"><span begin=\"1s\"")
            .replace("</span></p>", "</span></span></p>")
        val rendered = NativeLyricsPhoneticPresentation.render(input, true)
        assertTrue(TtmlTimingPolicy.isWord(rendered))
        assertTrue(rendered.contains("begin=\"0:02.000\" end=\"0:03.000\">yo</span>"))
        assertEquals("君よ", TtmlSubtitleTrack.subtitleValues(rendered, true)[0])
    }
    @Test fun screenshotReadingWithSyllableSpacesAndWoMapsToNativeCharacters() {
        val original = "優しい心を持ちたいのだけれど"
        val spans = original.mapIndexed { index, char -> "<span begin=\"${1000 + index * 200}ms\" end=\"${1200 + index * 200}ms\">$char</span>" }.joinToString("")
        val input = word.replace(">ki mi</text>", ">ya sa shi i ko ko ro wo mo chi ta i no da ke re do</text>")
            .replace("<span begin=\"1s\" end=\"2s\">君</span><span begin=\"2s\" end=\"3s\">よ</span>", spans)
            .replace("end=\"3s\"", "end=\"10s\"")
        val rendered = NativeLyricsPhoneticPresentation.render(input, true)
        assertTrue(TtmlTimingPolicy.isWord(rendered))
        assertTrue(Regex("<span ").findAll(rendered).count() > 2)
        assertEquals(original, TtmlSubtitleTrack.subtitleValues(rendered, true)[0])
    }
    @Test fun alignedReadingKeepsNativeTokenTimesAndMachineTranslation() {
        val input = TtmlAuxiliaryOrigins(setOf("offline"), setOf("dictionary")).attach(word.replace(">ki mi</text>", ">kimi yo</text>"))
        val rendered = NativeLyricsPhoneticPresentation.render(input, true)
        assertTrue(TtmlTimingPolicy.isWord(rendered))
        assertTrue(rendered.contains("<span begin=\"0:01.000\" end=\"0:02.000\">kimi</span> "))
        assertTrue(rendered.contains("<span begin=\"0:02.000\" end=\"0:03.000\">yo</span>"))
        assertEquals("你", TtmlSubtitleTrack.subtitleValues(rendered)[0])
        assertEquals("君よ", TtmlSubtitleTrack.subtitleValues(rendered, true)[0])
        assertEquals(setOf("offline"), TtmlAuxiliaryOrigins.read(rendered)!!.translation)
    }
    @Test fun readingSpanningSeveralNativeCharactersUsesTheirActualEnvelope() {
        val input = word.replace(">ki mi</text>", ">hitori yo</text>")
            .replace("<span begin=\"1s\" end=\"2s\">君</span>",
                "<span begin=\"1s\" end=\"1.5s\">一</span><span begin=\"1.5s\" end=\"2s\">人</span>")
        val rendered = NativeLyricsPhoneticPresentation.render(input, true)
        assertTrue(TtmlTimingPolicy.isWord(rendered))
        assertTrue(rendered.contains("begin=\"0:01.000\" end=\"0:02.000\">hitori</span> "))
        assertFalse(rendered.contains(">hi</span>"))
    }
    @Test fun missingTokenTimesUseSafeLineFallbackButStylingCanInheritRealTimes() {
        val input = word.replace(">ki mi</text>", ">kimi yo</text>")
        assertFalse(TtmlTimingPolicy.isWord(NativeLyricsPhoneticPresentation.render(input.replace("end=\"2s\"", ""), true)))
        assertTrue(TtmlTimingPolicy.isWord(NativeLyricsPhoneticPresentation.render(input.replace(">君</span>", "><span>君</span></span>"), true)))
    }
    @Test fun shortPronunciationUnitsShareHighlightWithoutChangingTheirFullTimeRange() {
        val input = word.replace(">ki mi</text>", ">kimi yo</text>").replace("end=\"2s\"", "end=\"2.94s\"")
            .replace("begin=\"2s\"", "begin=\"2.94s\"")
        val plain = NativeLyricsPhoneticPresentation.render(input, true, false)
        val smooth = NativeLyricsPhoneticPresentation.render(input, true, true)
        assertEquals(2, Regex("<span ").findAll(plain).count())
        assertEquals(1, Regex("<span ").findAll(smooth).count())
        assertTrue(smooth.contains("begin=\"0:01.000\" end=\"0:03.000\">kimi yo</span>"))
        assertTrue(TtmlTimingPolicy.isWord(smooth))
        assertEquals("你", TtmlSubtitleTrack.subtitleValues(smooth)[0])
        assertEquals(NativeLyricsPhoneticPresentation.render(input, false, false), NativeLyricsPhoneticPresentation.render(input, false, true))
    }
    @Test fun reportedJapaneseLineUsesNativeWordRangesWhenPronunciationIsEmphasized() {
        val input = word.replace(">ki mi</text>", ">kokoro o ima toi te</text>").replace(
            "<span begin=\"1s\" end=\"2s\">君</span><span begin=\"2s\" end=\"3s\">よ</span>",
            "<span begin=\"1s\" end=\"1.4s\">心</span><span begin=\"1.4s\" end=\"1.6s\">を</span>" +
                "<span begin=\"1.6s\" end=\"2s\">今</span><span begin=\"2s\" end=\"2.6s\">解い</span><span begin=\"2.6s\" end=\"3s\">て</span>")
        val rendered = NativeLyricsPhoneticPresentation.render(input, true)
        assertTrue(TtmlTimingPolicy.isWord(rendered))
        assertTrue(rendered.contains("begin=\"0:01.000\" end=\"0:01.400\">kokoro</span> "))
        assertTrue(rendered.contains("begin=\"0:01.600\" end=\"0:02.000\">ima</span> "))
        assertEquals("心を今解いて", TtmlSubtitleTrack.subtitleValues(rendered, true)[0])
    }
    @Test fun emphasisUsesNativeLineTimesWithoutInventingSyllableTimesAndKeepsTranslation() {
        val rendered = NativeLyricsPhoneticPresentation.render(word, true)
        assertTrue(rendered.contains("<p itunes:key=\"L1\" begin=\"1s\" end=\"3s\">ki mi</p>"))
        assertEquals("君よ", TtmlSubtitleTrack.subtitleValues(rendered, true)[0])
        assertEquals("你", TtmlSubtitleTrack.subtitleValues(rendered)[0])
        assertFalse(TtmlTimingPolicy.isWord(rendered))
        assertTrue(DesktopLyricsPresentation.fromTtml(rendered)!!.primaryPronunciation)
        // The input remains the restoration source; switching back renders it afresh.
        assertEquals("ki mi", TtmlSubtitleTrack.subtitleValues(NativeLyricsPhoneticPresentation.render(word, false), true)[0])
    }
    @Test fun absentReadingsAndMultiAgentVocalsKeepTheirNativeDocument() {
        val empty = "<tt><body><p begin=\"1s\" end=\"2s\">君</p></body></tt>"
        assertEquals(empty, NativeLyricsPhoneticPresentation.render(empty, true))
        val background = word.replace("<p itunes:key", "<p ttm:role=\"x-bg\" itunes:key")
        assertEquals(background, NativeLyricsPhoneticPresentation.render(background, true))
        assertTrue(DesktopLyricsPresentation.fromTtml(NativeLyricsPhoneticPresentation.render(
            word.replace("<p itunes:key", "<p ttm:agent=\"v1\" itunes:key"), true))!!.primaryPronunciation)
    }
    @Test fun escapedReadingsAndOriginalsAreDecodedOnceWhenSwappingTracks() {
        val escaped = word.replace(">ki mi</text>", ">ki &amp; &#x6d;i</text>").replace(">君</span>", ">君&amp;</span>")
        val rendered = NativeLyricsPhoneticPresentation.render(escaped, true)
        assertTrue(rendered.contains(">ki &amp; mi</p>"))
        assertFalse(rendered.contains("&amp;amp;"))
        assertEquals("君&amp;よ", TtmlSubtitleTrack.subtitleValues(rendered, true)[0])
    }
    @Test fun originalAndGeneratedAuxiliaryFactsRemainSeparateThroughDisplaySwitch() {
        val origins = TtmlAuxiliaryOrigins(setOf("offline"), setOf("dictionary"))
        val rendered = NativeLyricsPhoneticPresentation.render(origins.attach(word), true)
        assertEquals(origins, TtmlAuxiliaryOrigins.read(rendered))
        assertEquals("机翻译文 · 离线注音", origins.detail())
        assertFalse(origins.detail().contains("原生译文"))
        assertEquals(setOf("native"), TtmlAuxiliaryOrigins.original("APPLE_NATIVE", word).translation)
        assertEquals(setOf("author"), TtmlAuxiliaryOrigins.original("am-lyrics", word).translation)
        assertEquals("来源自带译文 · 来源自带发音", TtmlAuxiliaryOrigins.original("amll", word).detail())
    }
}
