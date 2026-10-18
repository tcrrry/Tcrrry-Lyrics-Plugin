package com.tcrrry.lyrics.engine.lyrics
import com.tcrrry.lyrics.plugin.PluginStorage.pluginPreferences

import android.icu.text.Transliterator
import android.content.Context
import org.robolectric.RuntimeEnvironment
import com.tcrrry.lyrics.sources.DirectLyricsRepository.Result
import java.io.File
import java.net.URLClassLoader
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class LanguagePronunciationSupplementTest {
    @org.junit.Before fun attachPlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.attach() }
    @org.junit.After fun closePlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.current.close() }
    private fun asset(name: String) = listOf(File("../assets/pronunciation/$name"), File("assets/pronunciation/$name"))
        .first { it.isFile }.readText()
    @Test fun modulesReadTheirOwnClassLoaderResourcesRatherThanHostAssets() {
        val source = listOf(File(".."), File(".")).first { File(it, "assets/pronunciation-modules.json").isFile }
        URLClassLoader(arrayOf(source.toURI().toURL()), null).use { loader ->
            assertEquals(2, PronunciationModules.resources("ko", loader).size)
            assertEquals(3, PronunciationModules.resources("yue", loader).size)
            assertEquals(asset("ko/Latin-ConjoiningJamo.xml"), PronunciationModules.files("ko", loader).first())
            assertEquals(asset("yue/jyut6ping3.chars.dict.yaml"), PronunciationModules.files("yue", loader).first())
        }
    }
    @Test fun togglingLanguageSettingsChangesCacheMarkerOnlyForSelectedSong() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.pluginPreferences("japanese_pronunciation", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val before = LanguagePronunciationSupplement.marker(context, 45L)
        assertTrue(before.contains("korean=true yueSong=false"))
        prefs.edit().putBoolean("cantonese_45", true).commit()
        assertNotEquals(before, LanguagePronunciationSupplement.marker(context, 45L))
        assertEquals(before, LanguagePronunciationSupplement.marker(context, 46L))
        prefs.edit().putBoolean("korean_enabled", false).commit()
        assertTrue(LanguagePronunciationSupplement.marker(context, 46L).contains("korean=false"))
        prefs.edit().clear().commit()
    }
    @Test fun bundledKoreanRulesProduceRomanizationWithoutNetwork() {
        val korean = LanguagePronunciationSupplement.Korean(asset("ko/Latin-ConjoiningJamo.xml"))
        assertEquals("annyeonghaseyo", korean.units("안녕하세요")!!.single().text.lowercase())
        assertEquals("hangug-eo", korean.units("한국어")!!.single().text.lowercase())
        assertNull(korean.units("你好"))
        assertNull(korean.units("Hello"))
        assertNull(korean.units("안녕 мир"))
        assertEquals(listOf("Hello", "annyeong"), korean.units("Hello 안녕")!!.map { it.text })
        val units = korean.units("너를 사랑해")!!
        assertEquals(2, units.size)
        assertEquals(0, units[0].start); assertEquals(2, units[0].end)
        assertEquals(3, units[1].start); assertEquals(6, units[1].end)
    }
    @Test fun bundledCantoneseWordsOverrideCharacterReadingsAndSupportSimplified() {
        val simplify = Transliterator.getInstance("Traditional-Simplified")
        val dictionary = LanguagePronunciationSupplement.Cantonese(listOf(asset("yue/jyut6ping3.chars.dict.yaml"), asset("yue/jyut6ping3.words.dict.yaml")), simplify::transliterate)
        assertEquals("nei hou", dictionary.units("你好")!!.joinToString(" ") { it.text })
        assertEquals(dictionary.units("愛情")!!.map { it.text }, dictionary.units("爱情")!!.map { it.text })
        assertEquals(2, dictionary.units("你好")!!.size)
        assertNull(dictionary.units("안녕")); assertNull(dictionary.units("unknown"))
    }
    @Test fun bundledModulesFillOnlyMissingRowsAndCantoneseRequiresSongOptIn() {
        val original = Result(lyrics = "[00:01]안녕\n[00:04]사랑해\n[00:07]你好",
            romanizedLyrics = "[00:01]native reading", source = "QQ音乐")
        val koreanEngine = LanguagePronunciationSupplement.Korean(asset("ko/Latin-ConjoiningJamo.xml"))
        val simplify = Transliterator.getInstance("Traditional-Simplified")
        val cantoneseEngine = LanguagePronunciationSupplement.Cantonese(listOf(asset("yue/jyut6ping3.chars.dict.yaml"), asset("yue/jyut6ping3.words.dict.yaml")), simplify::transliterate)
        val korean = LanguagePronunciationSupplement.fill(original, true, false, koreanEngine, cantoneseEngine)
        assertEquals(setOf(4000L), korean.supplementalPronunciationStarts)
        assertTrue(korean.romanizedLyrics.contains("native reading"))
        assertEquals(mapOf(4000L to "ko"), korean.supplementalPronunciationLanguages)
        val shown = requireNotNull(DesktopLyricsPresentation.fromTtml(requireNotNull(DesktopLyricsTtmlConverter.convert(korean))))
        assertTrue(shown.nativePronunciation)
        assertEquals(setOf("ko"), shown.offlinePronunciationLanguages)
        assertTrue(shown.detail().contains("机翻发音"))
        assertSame(original, LanguagePronunciationSupplement.fill(original, false, false, koreanEngine, cantoneseEngine))
        val cantonese = LanguagePronunciationSupplement.fill(original, false, true, koreanEngine, cantoneseEngine)
        assertEquals(setOf(7000L), cantonese.supplementalPronunciationStarts)
        assertEquals(mapOf(7000L to "yue"), cantonese.supplementalPronunciationLanguages)
        assertSame(original, LanguagePronunciationSupplement.fill(original, false, false, koreanEngine, cantoneseEngine))
    }
    @Test fun pronunciationUsesRealOriginalIntervalsWithoutInventingSyllableTimes() {
        val original = Result(lyrics = "[00:01]너를 사랑해", source = "QQ音乐",
            wordLyrics = "[1000,2000](1000,500)너(1500,500)를 (2000,400)사(2400,300)랑(2700,300)해")
        val engine = LanguagePronunciationSupplement.Korean(asset("ko/Latin-ConjoiningJamo.xml"))
        val filled = LanguagePronunciationSupplement.fill(original, true, false, engine, null)
        assertEquals(original.wordLyrics, filled.wordLyrics)
        val ttml = requireNotNull(DesktopLyricsTtmlConverter.convert(filled, primaryPronunciation = true))
        assertTrue(ttml.contains("itunes:timing=\"Word\""))
        assertTrue(ttml.contains("<span begin=\"0:01.000\" end=\"0:02.000\">"))
        assertTrue(ttml.contains("<span begin=\"0:02.000\" end=\"0:03.000\">"))
        assertEquals(2, Regex("<span ").findAll(ttml).count())
        val line = LanguagePronunciationSupplement.fill(original.copy(wordLyrics = ""), true, false, engine, null)
        val lineTtml = requireNotNull(DesktopLyricsTtmlConverter.convert(line, primaryPronunciation = true))
        assertTrue(lineTtml.contains("itunes:timing=\"Line\""))
        assertFalse(lineTtml.contains("<span "))
    }
    @Test fun cantoneseUsesWeightedReadingsAndLongestPhrasesWithoutMakingUpUnknownCharacters() {
        val dictionary = LanguagePronunciationSupplement.Cantonese(listOf("行\thong4\t1%\n行\thang4\t9%\n動\tdung6\n行動\thang4 dung6\n你\tnei5")) { it }
        assertEquals(listOf("hang", "dung"), dictionary.units("行動")!!.map { it.text })
        assertEquals(listOf("hang"), dictionary.units("行")!!.map { it.text })
        assertNull(dictionary.units("你𰻞"))
    }
    @Test fun cantoneseKeepsUtf16OffsetsForSupplementaryHanAndMixedLatinWords() {
        val dictionary=LanguagePronunciationSupplement.Cantonese(listOf("你\tnei5\n好\thou2\n𠀀\tjat1")) { it }
        val units=requireNotNull(dictionary.units("你，𠀀好 Hello!"))
        assertEquals(listOf("nei","ya","hou","Hello"),units.map { it.text })
        assertEquals(listOf("你","𠀀","好","Hello"),units.map { "你，𠀀好 Hello!".substring(it.start,it.end) })
        assertEquals(listOf(0 to 1,2 to 4,4 to 5,6 to 11),units.map { it.start to it.end })
        assertEquals(listOf("nei5","jat1","hou2"),dictionary.alignedUnits("你，𠀀好","nei5 jat1 hou2")!!.map { it.text })
        assertNull(dictionary.alignedUnits("你好","hou2 nei5"))
        assertNull(dictionary.alignedUnits("你好","nei5"))
    }
    @Test fun singleCycleFixtureHasOneTonelessReadingPerHanAndNativeReadingsSurviveToggles() {
        val simplify=Transliterator.getInstance("Traditional-Simplified")
        val dictionary=LanguagePronunciationSupplement.Cantonese(listOf(asset("yue/jyut6ping3.chars.dict.yaml"),asset("yue/jyut6ping3.words.dict.yaml")),simplify::transliterate)
        val text="不要不要假设我知道"
        val units=requireNotNull(dictionary.units(text))
        assertEquals(text.codePointCount(0,text.length),units.size)
        assertEquals(text,units.joinToString("") { text.substring(it.start,it.end) })
        assertTrue(units.all { it.text.none(Char::isDigit) })
        val platform="bat1 jiu3 bat1 jiu3 gaa2 cit3 ngo5 zi1 dou6"
        assertEquals(platform.split(' '),dictionary.alignedUnits(text,platform)!!.map { it.text })
        assertEquals(platform.split(' ').map { it.dropLast(1) },dictionary.alignedUnits(text,platform.replace(Regex("[1-6]"),""))!!.map { it.text })
        val source=Result(lyrics="[00:01]$text",romanizedLyrics="[00:01]$platform",source="QQ音乐")
        assertSame(source,LanguagePronunciationSupplement.fill(source,false,true,null,dictionary))
        assertSame(source,LanguagePronunciationSupplement.fill(source,false,false,null,dictionary))
        val generated=LanguagePronunciationSupplement.fill(source.copy(romanizedLyrics=""),false,true,null,dictionary)
        assertTrue(generated.romanizedLyrics.endsWith(units.joinToString(" ") { it.text }))
    }
    @Test fun cantoneseProviderRubyAndEmphasisUseActualWordIntervals() {
        val text="不要不要假设我知道"
        val reading="bat1 jiu3 bat1 jiu3 gaa2 cit3 ngo5 zi1 dou6"
        val ruby=requireNotNull(JapaneseRubyAlignment.align(text,reading))
        assertEquals(text.length,ruby.units.size)
        assertEquals(text,ruby.units.joinToString("") { text.substring(it.start,it.end) })
        val words=text.mapIndexed { i,c -> Triple(1000L+i*200,1200L+i*200,c.toString()) }
        val aligned=requireNotNull(DesktopLyricsTtmlConverter.alignNativePronunciation(words,reading))
        assertEquals(words.map { it.first to it.second },aligned.map { it.first to it.second })
        assertEquals(reading,aligned.joinToString("") { it.third })
    }

    @Test fun explicitCantoneseSongKeepsQqSpellingAndMapsEveryProviderSyllable() {
        val text="不要不要假设我知道"
        val qqReading="ba yiu ba yiu ga ci o zi dou"
        val source=Result(lyrics="[00:01]$text",romanizedLyrics="[00:01]$qqReading",source="QQ音乐")
        assertSame(source,LanguagePronunciationSupplement.fill(source,false,true,null,null))
        val ruby=requireNotNull(JapaneseRubyAlignment.align(text,qqReading))
        assertEquals(qqReading.split(' '),ruby.units.map { it.reading })
        assertEquals(text,ruby.units.joinToString("") { text.substring(it.start,it.end) })
        assertSame(source,LanguagePronunciationSupplement.fill(source,false,false,null,null))
        assertNotNull(JapaneseRubyAlignment.align(text,qqReading))
        val words=text.mapIndexed { i,c -> Triple(1000L+i*200,1200L+i*200,c.toString()) }
        assertEquals(words.map { it.first to it.second },DesktopLyricsTtmlConverter.alignNativePronunciation(words,qqReading)!!.map { it.first to it.second })
    }

    @Test fun offlineCantoneseUsesStableQqStyleDisplayAndStillAlignsEveryCharacter() {
        assertEquals("ba yiu ga ci o",listOf("bat1","jiu3","gaa2","cit3","ngo5").joinToString(" ") { CantoneseReadingDisplay.convert(it) })
        assertEquals("ng",CantoneseReadingDisplay.convert("ng5"))
        val text="不要不要假设我知道"
        val dictionary=LanguagePronunciationSupplement.Cantonese(listOf("不\tbat1\n要\tjiu3\n假\tgaa2\n设\tcit3\n我\tngo5\n知\tzi1\n道\tdou6")) { it }
        val filled=LanguagePronunciationSupplement.fill(Result(lyrics="[00:01]$text"),false,true,null,dictionary)
        val reading=filled.romanizedLyrics.substringAfter(']')
        assertEquals("ba yiu ba yiu ga ci o zi dou",reading)
        assertEquals(text.length,JapaneseRubyAlignment.align(text,reading)!!.units.size)
        assertEquals("bat1",dictionary.alignedUnits("不","bat1")!!.single().text)
        assertFalse(filled.supplementalPronunciationUnits.getValue(1000L).any { it.text.any(Char::isDigit) })
    }

    @Test fun traditionalProviderRowRetainsCharacterAlignmentAfterSimplifiedDisplay() {
        val source=Result(lyrics="[00:01]難離難捨想抱緊些",romanizedLyrics="[00:01]naan lei naan se soeng pou gan se")
        LanguagePronunciationSupplement.fill(source,false,true,null,null)
        val aligned=requireNotNull(JapaneseRubyAlignment.align("难离难舍想抱紧些","naan lei naan se soeng pou gan se"))
        assertEquals(8,aligned.units.size)
        assertEquals((0..7).toList(),aligned.units.map { it.start })
    }

    @Test fun cantoneseMixedLatinProviderUnitsPreserveLiteralWordsAndRejectMissingSounds() {
        val engine=LanguagePronunciationSupplement.Cantonese(emptyList()) { it }
        val original="Where did you go 數數多久不碰到"
        val reading="Where did you go sou sou do gau ba pung dou"
        val units=requireNotNull(engine.providerUnits(original,reading))
        assertEquals(listOf("Where","did","you","go","sou","sou","do","gau","ba","pung","dou"),units.map { it.text })
        assertEquals(listOf("Where","did","you","go","數","數","多","久","不","碰","到"),units.map { original.substring(it.start,it.end) })
        assertNull(engine.providerUnits(original,reading.replace("sou sou","sou")))
        assertNull(engine.providerUnits(original,reading.replace("Where","When")))
        assertNull(engine.providerUnits("僕の聲","boku no koe"))
    }

}
