package com.tcrrry.lyrics.engine.lyrics

import com.tcrrry.lyrics.plugin.TestPluginContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class JapaneseRubyAlignmentTest {
    @Test fun missingYouExpandedContractedKanaKeepsProviderTextAndOriginalRanges() {
        val original="一人じゃまだ何もできないよ"
        val reading="hito li ji a ma da nani mo de ki na i yo"
        val aligned=requireNotNull(JapaneseRubyAlignment.align(original,reading))
        assertEquals(reading.filter(Char::isLetterOrDigit),aligned.units.joinToString("") { it.reading }.filter(Char::isLetterOrDigit))
        assertEquals(original,aligned.units.joinToString("") { original.substring(it.start,it.end) })
        assertTrue(aligned.units.any { original.substring(it.start,it.end)=="じゃ" && it.reading.filter(Char::isLetterOrDigit)=="jia" })
    }
    @Test fun kissReadingMissingWholeOriginalWordsDoesNotInventAnnotations() {
        assertNull(JapaneseRubyAlignment.align("ずっと一緒にいたいと思った", "zu to yi xiyo ni yi ta yi ta"))
        assertNull(JapaneseRubyAlignment.align("一緒にいたいと思った", "yi xiyo ni yi ta yi"))
    }
    @Test fun expandedKanaRemainPhoneticPairsAndStrictTimingDoesNotOptIn() {
        for ((original, reading) in listOf("じゃ" to "ji a", "じゅ" to "ji u", "じょ" to "ji o",
            "しゃ" to "xi ya", "しゅ" to "xi yu", "しょ" to "xi yo")) {
            val aligned=requireNotNull(JapaneseRubyAlignment.align(original,reading))
            assertEquals(listOf(JapaneseRubyAlignment.Unit(0,2,reading)),aligned.units)
            assertNull(JapanesePronunciationSupplement.alignedUnits(original,reading))
        }
        assertNull(JapaneseRubyAlignment.align("じゃ", "ji"))
        assertNull(JapaneseRubyAlignment.align("しゃ", "sha ya"))
        assertNull(JapaneseRubyAlignment.align("君show", "kimi xiyow"))
    }
    @Test fun providerSpellingsFromKissAndMissingYouKeepTheirActualLetterRanges() {
        for ((original, reading) in listOf(
            "こんなにこんなに切なくする" to "ko n na ni ko n na ni secu na ku su lu",
            "逢いたいとか" to "a yi ta yi to ka",
            "深く刻まれているだけど" to "fuka ku kiza ma re te i lu da ke do",
            "冷たい毎日を耐えるほど" to "cume ta i mai nichi wo ta e lu ho do",
            "優しさを" to "yasa xi sa wo"
        )) {
            val aligned = requireNotNull(JapaneseRubyAlignment.align(original, reading)) { original }
            assertEquals(reading.filter(Char::isLetterOrDigit), aligned.units.joinToString("") { it.reading }.filter(Char::isLetterOrDigit))
            assertTrue(aligned.units.zipWithNext().all { (a, b) -> a.end <= b.start })
            assertEquals(original.filter(Char::isLetterOrDigit), aligned.units.joinToString("") { original.substring(it.start, it.end) })
        }
        val kana = requireNotNull(JapaneseRubyAlignment.align("する", "su lu"))
        assertEquals(listOf("su", "lu"), kana.units.map { it.reading.trim() })
        val variableLength = requireNotNull(JapaneseRubyAlignment.align("つめたい", "cu me ta yi"))
        assertEquals(listOf("cu", "me", "ta", "yi"), variableLength.units.map { it.reading.trim() })
    }
    @Test fun variantMatchingDoesNotChangeTimingAlignmentOrTreatEnglishAsJapanese() {
        assertNull(JapanesePronunciationSupplement.alignedUnits("する", "su lu"))
        assertNull(JapaneseRubyAlignment.align("する", "su lu extra"))
        assertNull(JapaneseRubyAlignment.align("する", "su"))
        assertNull(JapaneseRubyAlignment.align("light", "right"))
        assertNull(JapaneseRubyAlignment.align("君light", "kimi right"))
        assertNull(JapaneseRubyAlignment.align("日", "gi"))
        // A provider typo is not a spelling convention: keep the ordinary
        // reading rather than pretending to know which glyph should get it.
        assertNull(JapaneseRubyAlignment.align("冷たい毎日を耐えるほど", "cume ta i mai nigi wo ta e lu ho do"))
    }
    @Test fun kanjiVariantsKeepProviderOffsetsAndUnknownReadingsStayWhole() {
        val result=requireNotNull(JapaneseRubyAlignment.align("私", "wa ta xi"))
        assertEquals(listOf(JapaneseRubyAlignment.Unit(0,1,"wa ta xi")),result.units)
        val compound=requireNotNull(JapaneseRubyAlignment.align("力士", "li ki xi"))
        assertEquals("likixi",compound.units.joinToString("") { it.reading }.filter(Char::isLetterOrDigit))
        assertTrue(compound.units.all { it.start>=0 && it.end<=2 })
    }
    @Test fun r5ScreenshotQuotationAndCompoundReadingsAreMatchedWithoutReplacingProviderText() {
        for((original,reading) in listOf(
            "色違いのTシャツ" to "i ro chi ga i no T sha tsu",
            "「バス一つ見送ろうよ」" to "「 ba su hi to tsu mi o ku ro u yo 」",
            "大喧嘩になって" to "o o ge n ka ni na 't te",
            "歩幅合わせれば寒くない" to "ho haba a wa se re ba samu ku na i"
        )) {
            val result=requireNotNull(JapaneseRubyAlignment.align(original,reading)) { original }
            assertEquals(reading.filter(Char::isLetterOrDigit),result.units.joinToString("") { it.reading }.filter(Char::isLetterOrDigit))
        }
    }
    @org.junit.Before fun attach() { TestPluginContext.attach() }
    @org.junit.After fun close() { TestPluginContext.current.close() }
    @Test fun dictionaryRangesAndParticlesKeepTheirOriginalCharacters() {
        val original = "君は東京へ行く"
        val reading = requireNotNull(JapanesePronunciationSupplement.reading(original))
        val aligned = requireNotNull(JapaneseRubyAlignment.align(original, reading))
        assertTrue(aligned.dictionaryMatch)
        assertTrue(aligned.units.any { original.substring(it.start, it.end) == "は" && it.reading == "wa" })
        assertEquals(reading.filter(Char::isLetterOrDigit), aligned.units.joinToString("") { it.reading }.filter(Char::isLetterOrDigit))
    }
    @Test fun platformSpacedReadingAndDictionaryAlternativesArePreserved() {
        for ((original, reading) in listOf("自分を" to "ji bu n wo", "一人" to "hi to ri", "他の誰にも" to "ho ka no da re ni mo", "私の定位置" to "wa ta shi no te i i chi")) {
            val aligned = requireNotNull(JapaneseRubyAlignment.align(original, reading))
            assertEquals(reading.filter(Char::isLetterOrDigit), aligned.units.joinToString("") { it.reading }.filter(Char::isLetterOrDigit))
            assertTrue(aligned.units.all { it.start >= 0 && it.end <= original.length && it.start < it.end })
        }
    }
    @Test fun irregularWholeWordReadingIsNotArtificiallySplitAcrossKanji() {
        val aligned = requireNotNull(JapaneseRubyAlignment.align("今日は", "kyou wa"))
        assertTrue(aligned.units.any { it.start == 0 && it.end == 2 && it.reading == "kyou" })
    }
    @Test fun unrelatedOrPartialSourcePronunciationFallsBackInsteadOfGuessing() {
        assertNull(JapaneseRubyAlignment.align("君の声", "unrelated reading"))
        assertNull(JapaneseRubyAlignment.align("一人", "hito"))
        assertNull(JapaneseRubyAlignment.align("Hello", "hello"))
    }
    @Test fun kanaAndOkuriganaAreSplitInsteadOfCenteringTheWholeWord() {
        val original="強がりは好きじゃない"
        val aligned=requireNotNull(JapaneseRubyAlignment.align(original,"tsu yo ga ri ha su ki ja na i"))
        val parts=aligned.units.map { original.substring(it.start,it.end) to it.reading.filterNot(Char::isWhitespace) }
        assertTrue(parts.contains("強" to "tsuyo"))
        assertTrue(parts.contains("が" to "ga"))
        assertTrue(parts.contains("り" to "ri"))
        assertTrue(parts.contains("好" to "su"))
        assertTrue(parts.contains("き" to "ki"))
        assertTrue(parts.contains("じゃ" to "ja"))
        assertTrue(parts.contains("な" to "na"))
        assertTrue(parts.contains("い" to "i"))
    }
    @Test fun geminatedKanaRemainOnePhoneticPairAndDictionaryReadingIsPreserved() {
        val original="ずっとずっと"
        val aligned=requireNotNull(JapaneseRubyAlignment.align(original,"zu 't to zu 't to"))
        assertEquals(listOf("ず","っと","ず","っと"),aligned.units.map { original.substring(it.start,it.end) })
        assertEquals("zuttozutto",aligned.units.joinToString("") { it.reading }.filter(Char::isLetterOrDigit))
    }
    @Test fun screenshotBrandAndPresentReadingsKeepEveryPlatformSyllable() {
        for ((original, reading) in listOf(
            "ブランドのバッグ彼氏のスペック" to "bu ra n do no ba ggu ka re shi no su pe kku",
            "誕生日プレゼントいいねの数" to "tanjou bi pu re ze n to i i ne no kazu",
            "身に纏う全てものさしにして" to "mi ni mato u sube te mo no sa shi ni shi te"
        )) {
            val aligned = requireNotNull(JapaneseRubyAlignment.align(original, reading)) { original }
            assertEquals(reading.filter(Char::isLetterOrDigit), aligned.units.joinToString("") { it.reading }.filter(Char::isLetterOrDigit))
            println("Screenshot ruby $original: ${aligned.units}")
        }
    }
}
