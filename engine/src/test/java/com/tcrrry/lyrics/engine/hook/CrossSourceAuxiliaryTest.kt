package com.tcrrry.lyrics.engine.hook

import com.tcrrry.lyrics.sources.DirectLyricsRepository
import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(manifest = org.robolectric.annotation.Config.NONE, sdk = [35])
class CrossSourceAuxiliaryTest {
    @org.junit.Before fun attachPlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.attach() }
    @org.junit.After fun closePlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.current.close() }
    private val primary = "<tt xmlns:itunes=\"urn\" itunes:timing=\"Line\"><body><div><p begin=\"1s\" end=\"3s\" itunes:key=\"L1\">君の声</p></div></body></tt>"
    private val source = DirectLyricsRepository.Result(lyrics = "[00:01.000]君の声", translatedLyrics = "[00:01.000]你的声音",
        romanizedLyrics = "[00:01.000]kimi no koe", source = "QQ音乐")
    @Test fun exactLineGetsAuxiliaryButKeepsNativeBodyAndTiming() {
        val merged = CrossSourceAuxiliary.enrich(primary, source)
        assertTrue(merged.contains("你的声音")); assertTrue(merged.contains("kimi no koe"))
        assertTrue(merged.contains(primary.substringAfter("<body>")))
        assertTrue(merged.contains("itunes:timing=\"Line\""))
    }
    @Test fun wrongTextOrTimeNeverMerges() {
        assertEquals(primary, CrossSourceAuxiliary.enrich(primary, source.copy(lyrics = "[00:01.000]他の声")))
        assertEquals(primary, CrossSourceAuxiliary.enrich(primary, source.copy(lyrics = "[00:10.000]君の声")))
    }
    @Test fun repeatedRefrainIsAmbiguousAndExistingTrackWins() {
        val repeated = primary.replace("</div>", "<p begin=\"1.5s\" end=\"5s\">君の声</p></div>")
        assertEquals(repeated, CrossSourceAuxiliary.enrich(repeated, source))
        val originalSubtitle = requireNotNull(TtmlSubtitleTrack.attach(primary, mapOf(0 to "已有翻译")))
        val merged = CrossSourceAuxiliary.enrich(originalSubtitle, source)
        assertTrue(merged.contains("已有翻译")); assertFalse(merged.contains("你的声音"))
    }
    @Test fun separatedChorusesReceiveTheirOwnTranslationWithoutReplacingNativeWords() {
        val native = primary.replace("itunes:timing=\"Line\"", "itunes:timing=\"Word\"")
            .replace(">君の声</p>", "><span begin=\"1s\" end=\"2s\">君の</span><span begin=\"2s\" end=\"3s\">声</span></p>")
            .replace("</div>", "<p begin=\"20s\" end=\"22s\" itunes:key=\"L2\">君の声</p></div>")
        val merged = CrossSourceAuxiliary.enrich(native, source.copy(
            lyrics = "[00:01.000]君の声\n[00:20.000]君の声",
            translatedLyrics = "[00:01.000]你的声音\n[00:20.000]再次听见你的声音"))
        assertEquals(mapOf(0 to "你的声音", 1 to "再次听见你的声音"), TtmlSubtitleTrack.subtitleValues(merged))
        assertTrue(merged.contains(native.substringAfter("<body>")))
        assertTrue(TtmlTimingPolicy.isWord(merged))
    }
    @Test fun crossingLineOrderIsRejected() {
        val native = primary.replace("君の声", "星の光").replace("</div>", "<p begin=\"2s\" end=\"3s\">君の声</p></div>")
        val reversed = source.copy(lyrics = "[00:01.000]君の声\n[00:02.000]星の光",
            translatedLyrics = "[00:01.000]你的声音\n[00:02.000]星星的光", romanizedLyrics = "")
        assertEquals(native, CrossSourceAuxiliary.enrich(native, reversed))
    }
    @Test fun partialAuxiliaryTrackFillsOnlyItsMissingRows() {
        val two = primary.replace("</div>", "<p begin=\"4s\" end=\"6s\" itunes:key=\"L2\">星の光</p></div>")
        val existing = requireNotNull(TtmlSubtitleTrack.attach(two, mapOf(0 to "原生译文")))
        val filled = requireNotNull(TtmlSubtitleTrack.attach(existing, mapOf(0 to "不得替换", 1 to "星星的光")))
        assertTrue(filled.contains("原生译文")); assertTrue(filled.contains("星星的光")); assertFalse(filled.contains("不得替换"))
        assertEquals(mapOf(0 to "原生译文", 1 to "星星的光"), TtmlSubtitleTrack.subtitleValues(filled))
        val reading = requireNotNull(TtmlSubtitleTrack.attachPronunciation(filled, mapOf(0 to "kimi no koe")))
        val complete = requireNotNull(TtmlSubtitleTrack.attachPronunciation(reading, mapOf(0 to "wrong", 1 to "hoshi no hikari")))
        assertTrue(complete.contains("kimi no koe")); assertTrue(complete.contains("hoshi no hikari")); assertFalse(complete.contains("wrong"))
        assertTrue(complete.contains(two.substringAfter("<body>")))
    }
    @Test fun selfClosingEmptySubtitleIsFilledWithoutDuplicateKey() {
        val two = primary.replace("</div>", "<p begin=\"4s\" end=\"6s\" itunes:key=\"L2\">星の光</p></div>")
        val existing = requireNotNull(TtmlSubtitleTrack.attach(two, mapOf(0 to "原生译文")))
            .replace("<text for=\"L2\"> </text>", "<text for=\"L2\"/>")
        val filled = requireNotNull(TtmlSubtitleTrack.attach(existing, mapOf(1 to "星星的光")))
        assertEquals(1, Regex("<text for=\"L2\"").findAll(filled).count())
        assertEquals("星星的光", TtmlSubtitleTrack.subtitleValues(filled)[1])
    }
    @Test fun offlineCantoneseDoesNotReplaceExistingPlatformPronunciationOrTimedBody() {
        val context=org.robolectric.RuntimeEnvironment.getApplication()
        com.tcrrry.lyrics.plugin.PluginStorage.run {
            context.pluginPreferences("japanese_pronunciation",0).edit().putBoolean("cantonese_777",true).commit()
        }
        val native="<tt xmlns:itunes=\"urn\" itunes:timing=\"Word\"><body><div><p begin=\"1s\" end=\"3s\" itunes:key=\"L1\"><span begin=\"1s\" end=\"2s\">你</span><span begin=\"2s\" end=\"3s\">好</span></p></div></body></tt>"
        val existing=requireNotNull(TtmlSubtitleTrack.attachPronunciation(native,mapOf(0 to "nei5 hou2")))
        val filled=CrossSourceAuxiliary.dictionary(context,777,existing)
        assertEquals(mapOf(0 to "nei5 hou2"),TtmlSubtitleTrack.subtitleValues(filled,true))
        assertEquals(existing.substringAfter("<body>"),filled.substringAfter("<body>"))
    }

    @Test fun completeCantoneseTrackSurvivesDictionaryEnrichmentAndNativeLookup() {
        val fixture=System.getProperty("cantoneseFixture")
        org.junit.Assume.assumeNotNull(fixture)
        val entries=org.json.JSONArray(java.io.File(fixture!!).readText())
        val context=org.robolectric.RuntimeEnvironment.getApplication()
        com.tcrrry.lyrics.plugin.PluginStorage.run {
            context.pluginPreferences("japanese_pronunciation",0).edit().putBoolean("cantonese_777",true).commit()
        }
        fun stamp(ms:Long)=java.lang.String.format(java.util.Locale.US,"%d:%02d.%03d",ms/60000,ms/1000%60,ms%1000)
        val traditional=android.icu.text.Transliterator.getInstance("Simplified-Traditional")
        val text=(0 until entries.length()).map { traditional.transliterate(entries.getJSONObject(it).getString("text")) }
        val primary=buildString {
            append("<tt xmlns:itunes=\"urn\" itunes:timing=\"Line\"><body><div>")
            text.forEachIndexed { i,value ->
                val row=entries.getJSONObject(i);val start=row.getLong("start")
                append("<p begin=\"${stamp(start)}\" end=\"${stamp(start+row.getLong("duration"))}\" itunes:key=\"L$i\">$value</p>")
            }
            append("</div></body></tt>")
        }
        val filled=CrossSourceAuxiliary.dictionary(context,777,primary)
        val values=TtmlSubtitleTrack.subtitleValues(filled,true)
        val expected=text.indices.filter { text[it].any { c -> c in '\u4e00'..'\u9fff' } }
        assertEquals("pronunciation row coverage",expected.size,expected.count { !values[it].isNullOrBlank() })
        val lookup=NativeSubtitleLookup(filled)
        val displayed=text.map(com.tcrrry.lyrics.engine.lyrics.ChineseTextKey::of)
        assertEquals("native row lookup coverage after simplified display",expected.size,expected.count { !lookup.forOriginal(displayed[it])?.pronunciation.isNullOrBlank() })
        assertEquals(primary.substringAfter("<body>"),filled.substringAfter("<body>"))
        fun lrc(i:Int,value:String):String {
            val start=entries.getJSONObject(i).getLong("start")
            return java.lang.String.format(java.util.Locale.US,"[%02d:%02d.%03d]%s",start/60000,start/1000%60,start%1000,value)
        }
        val qq=DirectLyricsRepository.Result(source="QQ音乐",
            lyrics=displayed.mapIndexed { i,value -> lrc(i,value) }.joinToString("\n"),
            romanizedLyrics=displayed.indices.map { i -> lrc(i,entries.getJSONObject(i).optString("reading")) }.joinToString("\n"))
        val qqTrack=CrossSourceAuxiliary.enrich(primary,qq)
        val qqLookup=NativeSubtitleLookup(qqTrack)
        val qqRows=displayed.indices.filter { i -> entries.getJSONObject(i).optString("reading").matches(Regex("[A-Za-z ]+")) }
        assertEquals(40,qqRows.size)
        assertEquals("full QQ track native lookup coverage",40,qqRows.count { !qqLookup.forOriginal(displayed[it])?.pronunciation.isNullOrBlank() })
        assertEquals("full QQ track character alignment coverage",40,qqRows.count {
            com.tcrrry.lyrics.engine.lyrics.JapaneseRubyAlignment.align(displayed[it],qqLookup.forOriginal(displayed[it])!!.pronunciation!!)!=null
        })
    }

    @Test fun qqCantoneseMergesAcrossHanVariantsAndAlignsWithoutOfflineOptIn() {
        val context=org.robolectric.RuntimeEnvironment.getApplication()
        com.tcrrry.lyrics.plugin.PluginStorage.run {
            context.pluginPreferences("japanese_pronunciation",0).edit().putBoolean("cantonese_777",false).commit()
        }
        val originals=listOf("難離難捨想抱緊些","茫茫人生好像荒野","常情如此不可推卸","任世間再冷酷想起這單車")
        val displayed=listOf("难离难舍想抱紧些","茫茫人生好像荒野","常情如此不可推卸","任世间再冷酷想起这单车")
        val readings=listOf("naan lei naan se soeng pou gan se","mong mong yan sang hou zoeng fong ye","soeng cing yu ci ba ho tui se","yam sai gaan zoi laang huk soeng hei ze daan ce")
        val primary="<tt xmlns:itunes=\"urn\" itunes:timing=\"Line\"><body><div>"+originals.mapIndexed { i,text ->
            "<p begin=\"${i*5+1}s\" end=\"${i*5+4}s\" itunes:key=\"L$i\">$text</p>"
        }.joinToString("")+"</div></body></tt>"
        val source=DirectLyricsRepository.Result(source="QQ音乐",
            lyrics=displayed.mapIndexed { i,text -> "[00:${(i*5+1).toString().padStart(2,'0')}]$text" }.joinToString("\n"),
            romanizedLyrics=readings.mapIndexed { i,text -> "[00:${(i*5+1).toString().padStart(2,'0')}]$text" }.joinToString("\n"))
        val merged=CrossSourceAuxiliary.enrich(primary,source)
        assertEquals(4,TtmlSubtitleTrack.subtitleValues(merged,true).values.count { it.isNotBlank() })
        val lookup=NativeSubtitleLookup(merged)
        displayed.forEachIndexed { i,text ->
            assertEquals(readings[i],lookup.forOriginal(text)!!.pronunciation)
            assertEquals(text.length,com.tcrrry.lyrics.engine.lyrics.JapaneseRubyAlignment.align(text,readings[i])!!.units.size)
        }
        assertEquals(primary.substringAfter("<body>"),merged.substringAfter("<body>"))
    }

    @Test fun mixedEnglishCantoneseSourceRegistersReadingEvenWhenPrimaryIsAppleNative() {
        val original="Where did you go 數數多久不碰到"
        val reading="Where did you go sou sou do gau ba pung dou"
        val primary="<tt xmlns:itunes=\"urn\" itunes:timing=\"Word\"><body><div><p begin=\"1s\" end=\"4s\" itunes:key=\"L1\">$original</p><p begin=\"5s\" end=\"8s\" itunes:key=\"L2\">Whoa whoa</p></div></body></tt>"
        val source=DirectLyricsRepository.Result(source="QQ音乐",lyrics="[00:01]$original\n[00:05]Whoa whoa",
            romanizedLyrics="[00:01]$reading\n[00:05]Whoa whoa")
        val merged=CrossSourceAuxiliary.enrich(primary,source)
        assertEquals(primary.substringAfter("<body>"),merged.substringAfter("<body>"))
        val tracks=NativeSubtitleLookup(merged)
        val units=requireNotNull(com.tcrrry.lyrics.engine.lyrics.JapaneseRubyAlignment.align(original,tracks.forOriginal(original)!!.pronunciation!!))
        assertTrue(units.perCharacter);assertEquals(11,units.units.size)
        // Cache replay retains provider provenance even without a platform primary marker.
        val cached=TtmlAuxiliaryOrigins(pronunciation=setOf("QQ音乐")).attach(merged)
        val pointer=Any();NativeLyricsPronunciationSubtitle.remember(pointer,cached)
        assertTrue(NativeLyricsPronunciationSubtitle.isManaged(pointer))
        assertEquals(reading,NativeLyricsPronunciationSubtitle.lineSubtitles(pointer,original)!!.pronunciation)
    }

    @Test fun explicitJapaneseKanjiDocumentNeverUsesCantoneseProviderColumns() {
        val japanese="<tt xml:lang=\"ja\" xmlns:itunes=\"urn\"><body><div><p begin=\"1s\" end=\"3s\" itunes:key=\"L1\">桜</p></div></body></tt>"
        val platform=DirectLyricsRepository.Result(source="QQ音乐",lyrics="[00:01]桜",romanizedLyrics="[00:01]sakura")
        val merged=CrossSourceAuxiliary.enrich(japanese,platform)
        val pointer=Any()
        NativeLyricsPronunciationSubtitle.remember(pointer,TtmlAuxiliaryOrigins(pronunciation=setOf("QQ音乐")).attach(merged))
        val aligned=requireNotNull(com.tcrrry.lyrics.engine.lyrics.JapaneseRubyAlignment.align("桜","sakura"))
        assertFalse(aligned.perCharacter)
    }

}
