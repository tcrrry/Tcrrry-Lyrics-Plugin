package com.tcrrry.lyrics.engine.hook

import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class ChineseLyricsDisplayTest {
    private val raw = """<tt xml:lang="zh-Hant" xmlns:itunes="urn:itunes" xmlns:ttm="urn:ttm" itunes:timing="Word"><head><metadata><title>寂寞與愛</title><translations><translation xml:lang="zh-Hant"><text for="L1">與你一起</text></translation></translations></metadata></head><body><div><p itunes:key="L1" begin="28.972" end="30.500" ttm:agent="v1"><span begin="28.972" end="29.183">I'm sorry </span><span begin="29.183" end="29.791">我不是壞男孩</span><span begin="29.791" end="30.500"> 與愛</span></p></div></body></tt>"""
    private fun parse(value: String) = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        .parse(InputSource(StringReader(value)))
    private fun attributes(value: String): List<List<Pair<String, String>>> {
        val nodes = parse(value).getElementsByTagName("*")
        return (0 until nodes.length).map { index ->
            val attrs = nodes.item(index).attributes
            (0 until attrs.length).map { attrs.item(it).nodeName to attrs.item(it).nodeValue }.sortedBy { it.first }
        }
    }
    @Test fun chineseWordsAndSubtitlesChangeButAttributesTimingAndMetadataDoNot() {
        val result = ChineseLyricsDisplay.render(raw, true)
        assertNotEquals(raw, result)
        val document = parse(result)
        assertEquals("I'm sorry 我不是坏男孩 与爱", document.getElementsByTagName("p").item(0).textContent)
        assertEquals("与你一起", document.getElementsByTagName("text").item(0).textContent)
        assertEquals("寂寞與愛", document.getElementsByTagName("title").item(0).textContent)
        assertEquals(attributes(raw), attributes(result))
        assertFalse(result.contains("&lt;span"))
        assertTrue(TtmlTimingPolicy.hasTimedWords(result))
    }
    @Test fun disabledRenderingKeepsTheExactOriginalAndRepeatedConversionIsStable() {
        assertSame(raw, ChineseLyricsDisplay.render(raw, false))
        val result = ChineseLyricsDisplay.render(raw, true)
        assertEquals(result, ChineseLyricsDisplay.render(result, true))
        assertSame(raw, ChineseLyricsDisplay.render(raw, false))
    }
    @Test fun japaneseOriginalIsProtectedWhileChineseTranslationCanBeSimplified() {
        val input = raw.replace("zh-Hant\" xmlns", "ja\" xmlns")
        val document = parse(ChineseLyricsDisplay.render(input, true))
        assertEquals("I'm sorry 我不是壞男孩 與愛", document.getElementsByTagName("p").item(0).textContent)
        assertEquals("与你一起", document.getElementsByTagName("text").item(0).textContent)
    }
    @Test fun syntheticSubtitleLanguageAndNumericEntitiesRemainConvertible() {
        val input = """<tt xml:lang="ko"><body><div><p begin="1s" end="2s">&#x8207;你一起愛這個世界</p></div></body></tt>"""
        val result = parse(ChineseLyricsDisplay.render(input, true))
        assertEquals("与你一起爱这个世界", result.getElementsByTagName("p").item(0).textContent)
    }
    @Test fun malformedOrExternalEntityDocumentsFailOpenWithoutAnyReplacement() {
        for (input in listOf("<tt><body><p>與愛", "<!DOCTYPE tt [<!ENTITY x SYSTEM 'file:///not-read'>]><tt><body><p>&x;與愛</p></body></tt>"))
            assertSame(input, ChineseLyricsDisplay.render(input, true))
    }
    @Test fun mixedEnglishCantoneseBodyAndUntaggedChineseSubtitleStillSimplify() {
        val input="""<tt xml:lang="ko" xmlns:itunes="urn:itunes"><head><metadata><translations><translation><text for="L1">Where did you go 數數多久不碰到</text></translation></translations></metadata></head><body><div><p itunes:key="L1"><span begin="1s" end="2s">Where did you go </span><span begin="2s" end="3s">數數多久不碰到</span></p><p>Whoa, whoa</p><p>Where did you go</p></div></body></tt>"""
        val output=ChineseLyricsDisplay.render(input,true)
        assertEquals("Where did you go 数数多久不碰到",parse(output).getElementsByTagName("p").item(0).textContent)
        assertEquals("Where did you go 数数多久不碰到",parse(output).getElementsByTagName("text").item(0).textContent)
        assertEquals(attributes(input),attributes(output))
        assertEquals(input,ChineseLyricsDisplay.render(input,false))
        val japanese=input.replace("xml:lang=\"ko\"","xml:lang=\"ja\"")
        assertEquals("Where did you go 數數多久不碰到",parse(ChineseLyricsDisplay.render(japanese,true)).getElementsByTagName("p").item(0).textContent)
    }

}
