package com.tcrrry.lyrics.engine.lyrics

import com.tcrrry.lyrics.sources.DirectLyricsRepository.Result
import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(manifest = org.robolectric.annotation.Config.NONE, sdk = [35])
class KoreanPronunciationAlignmentTest {
    @org.junit.Before fun attachPlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.attach() }
    @org.junit.After fun closePlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.current.close() }
    @Test fun separatedProviderSyllablesKeepOriginalOffsetsAndLiaison() {
        val units = requireNotNull(KoreanPronunciationAlignment.units("니가 있는 곳에", "ni ga in neun go se"))
        assertEquals(listOf(0, 1, 3, 4, 6, 7), units.map { it.start })
        assertEquals(listOf("ni", "ga", "in", "neun", "go", "se"), units.map { it.text })
        assertEquals(listOf("neo", "rwi", "hae", "seo"),
            KoreanPronunciationAlignment.units("널 위해서", "neo rwi hae seo")!!.map { it.text })
    }
    @Test fun englishFragmentsRemainOneOriginalWord() {
        val units = requireNotNull(KoreanPronunciationAlignment.units("너 Heaven", "neo Hea ven"))
        assertEquals(2, units.size)
        assertEquals(2, units[1].start)
        assertEquals(8, units[1].end)
        assertEquals("Hea ven", units[1].text)
    }
    @Test fun omittedExtraOrIncompatibleSoundsNeverCreateTiming() {
        for ((original, reading) in listOf("니가" to "ni", "니가" to "ni ga ga", "니가" to "na ga",
            "니가" to "mi ga", "안녕 мир" to "an nyeong", "你好" to "ni hao")) {
            assertNull("$original / $reading", KoreanPronunciationAlignment.units(original, reading))
        }
    }
    @Test fun projectedPronunciationKeepsRealNativeIntervals() {
        val result = Result(lyrics = "[00:01.100]니가", wordLyrics = "[1100,800](1100,300,0)니(1500,400,0)가",
            romanizedLyrics = "[00:01.100]ni ga", source = "网易云音乐")
        val xml = requireNotNull(DesktopLyricsTtmlConverter.convert(result, primaryPronunciation = true))
        assertTrue(requireNotNull(DesktopLyricsPresentation.fromTtml(xml)).wordTimed)
        assertTrue(xml.contains("begin=\"0:01.100\" end=\"0:01.400\">ni</span> "))
        assertTrue(xml.contains("begin=\"0:01.500\" end=\"0:01.900\">ga"))
        assertFalse(requireNotNull(DesktopLyricsPresentation.fromTtml(requireNotNull(
            DesktopLyricsTtmlConverter.convert(result.copy(romanizedLyrics = "[00:01.100]na ga"), primaryPronunciation = true)))).wordTimed)
    }
    @Test fun celebrityScreenshotJoinedWordsAndHyphensMapToEveryHangulBlock() {
        val units=requireNotNull(KoreanPronunciationAlignment.units("환한 빛 조명이","hwanhan bich jomyeong-i"))
        assertEquals(listOf("hwan","han","bich","jo","myeong","i"),units.map { it.text })
        assertEquals(listOf(0,1,3,5,6,7),units.map { it.start })
        assertEquals(listOf("eo","dil","bi","chu","neun","ji"),KoreanPronunciationAlignment.units("어딜 비추는지","eodil bichuneunji")!!.map { it.text })
        assertEquals(listOf("an","nyeong"),KoreanPronunciationAlignment.units("안녕","annyeong")!!.map { it.text })
        val ruby=requireNotNull(JapaneseRubyAlignment.align("환한 빛 조명이","hwanhan bich jomyeong-i"))
        assertTrue(ruby.perCharacter);assertEquals(6,ruby.units.size)
    }
    @Test fun connectedPronunciationStillUsesOnlyNativeTimeIntervals() {
        val words=listOf(Triple(1000L,1200L,"환"),Triple(1200L,1400L,"한 "),Triple(1500L,1800L,"빛 "),
            Triple(1800L,2000L,"조"),Triple(2000L,2300L,"명"),Triple(2300L,2500L,"이"))
        val aligned=requireNotNull(DesktopLyricsTtmlConverter.alignNativePronunciation(words,"hwanhan bich jomyeong-i"))
        assertEquals(words.map { it.first to it.second },aligned.map { it.first to it.second })
    }

    @Test fun celebrityReportedRemainingLinesSplitEveryHangulBlock() {
        for((original,reading,expected) in listOf(
            Triple("보이니 그 유일함이 얼마나","boini geu yuilham-i eolmana","bo|i|ni|geu|yu|il|ham|i|eol|ma|na"),
            Triple("아름다운지 말야","aleumdaunji mal-ya","a|leum|da|un|ji|mal|ya"),
            Triple("왼손으로 그린 별 하나","oen son eu lo geu lin byeol ha na","oen|son|eu|lo|geu|lin|byeol|ha|na"))) {
            val units=requireNotNull(KoreanPronunciationAlignment.units(original,reading)) { original }
            assertEquals(expected.split('|'),units.map { it.text })
        }
    }

    @Test fun externalWholeSongProviderCoverage() {
        val directory=System.getProperty("koreanFixtureDir")
        org.junit.Assume.assumeTrue(!directory.isNullOrBlank())
        val reports=mutableListOf<Map<String,Any>>()
        for(name in listOf("celebrity-netease.json","badboy-netease.json")) {
            val json=org.json.JSONObject(java.io.File(directory!!,name).readText())
            val source=Result(lyrics=json.getJSONObject("lrc").getString("lyric"),
                romanizedLyrics=json.getJSONObject("romalrc").getString("lyric"),source="网易云音乐")
            val readings=DesktopLyricsTtmlConverter.pronunciationEntries(source)
            var checked=0;val missing=mutableListOf<String>()
            for((time,text) in DesktopLyricsTtmlConverter.pronunciationLines(source)) {
                if(!Regex("[가-힣]").containsMatchIn(text)) continue
                val reading=readings.minByOrNull { kotlin.math.abs(it.first-time) }
                    ?.takeIf { kotlin.math.abs(it.first-time)<300 && it.second.isNotBlank() }?.second ?: continue
                checked++
                if(KoreanPronunciationAlignment.units(text,reading)==null) missing+="$text / $reading"
            }
            reports+=mapOf("song" to name,"checked" to checked,"missing" to missing)
        }
        java.io.File(directory!!,"korean-coverage.json").writeText(org.json.JSONArray(reports).toString(2))
        assertTrue(reports.toString(),reports.all { (it["missing"] as List<*>).isEmpty() })
    }

}
