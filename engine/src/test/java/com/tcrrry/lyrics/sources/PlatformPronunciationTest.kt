package com.tcrrry.lyrics.sources

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(manifest = org.robolectric.annotation.Config.NONE, sdk = [35])
class PlatformPronunciationTest {
    @org.junit.Before fun attachPlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.attach() }
    @org.junit.After fun closePlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.current.close() }
    private val repository = DirectLyricsRepository()

    @Test fun cloudsearchMetadataSurvivesTheExistingRecordingAndHistoryPaths() {
        val root = JSONObject("""{"code":200,"result":{"songs":[
            {"id":488388942,"name":"願い～あの頃のキミへ～",
             "ar":[{"name":"當山みれい"}],"al":{"name":"願い EP","picUrl":"https://example.test/cover"},"dt":338000}
        ]}}""")
        val song = repository.netEaseSongs(root).getJSONObject(0)
        assertEquals(488388942L, song.getLong("id"))
        assertEquals("當山みれい", song.getJSONArray("artists").getJSONObject(0).getString("name"))
        assertEquals("願い EP", song.getJSONObject("album").getString("name"))
        assertEquals(338000L, song.getLong("duration"))
        assertFalse(root.getJSONObject("result").getJSONArray("songs").getJSONObject(0).has("artists"))
    }

    @Test fun legacyMetadataAndEmptySearchRemainSupported() {
        val root = JSONObject("""{"result":{"songs":[{"id":1,"artists":[{"name":"artist"}],"album":{"name":"album"},"duration":1234}]}}""")
        val song = repository.netEaseSongs(root).getJSONObject(0)
        assertEquals("artist", song.getJSONArray("artists").getJSONObject(0).getString("name"))
        assertEquals(1234L, song.getLong("duration"))
        assertEquals(0, repository.netEaseSongs(JSONObject("""{"code":200,"result":{"songCount":0}}""")).length())
    }

    @Test(expected = IllegalArgumentException::class)
    fun encryptedResultIsAnInterfaceFailureRatherThanNoMatchingSongs() {
        repository.netEaseSongs(JSONObject("""{"code":200,"result":"35b1748964af8a7c"}"""))
    }

    @Test fun netEaseReadsRomanizationWithoutMistakingTranslationForPronunciation() {
        val response = JSONObject().put("romalrc", JSONObject().put("lyric", "[00:01]kimi"))
            .put("tlyric", JSONObject().put("lyric", "[00:01]你"))
        assertEquals("[00:01]kimi", repository.netEaseRomanizedLyrics(response))
        assertEquals("", repository.netEaseRomanizedLyrics(JSONObject().put("tlyric", response.get("tlyric"))))
    }

    @Test fun netEaseSupportsEmptyPrimaryTrackAndWordFormatFallback() {
        val response = JSONObject().put("romalrc", JSONObject().put("lyric", ""))
            .put("yromalrc", JSONObject().put("lyric", "[1000,1000](1000,500,0)ki(1500,500,0)mi"))
        assertEquals("[00:01.000]kimi", repository.netEaseRomanizedLyrics(response))
    }

    @Test fun netEaseUsesPronunciationSynchronizedWithTheSelectedOriginalTimeline() {
        val response = JSONObject().put("lrc", JSONObject().put("lyric", "[00:00.590]니가"))
            .put("romalrc", JSONObject().put("lyric", "[00:00.590]ni ga"))
            .put("yromalrc", JSONObject().put("lyric", "[00:01.100]ni ga"))
        assertEquals("[00:00.590]ni ga", repository.netEaseRomanizedLyrics(response))
        response.put("yrc", JSONObject().put("lyric", "[1100,1000](1100,500,0)니(1600,500,0)가"))
        assertEquals("[00:01.100]ni ga", repository.netEaseRomanizedLyrics(response))
        assertEquals("", repository.netEaseRomanizedWordLyrics(response))
        response.getJSONObject("yromalrc").put("lyric", "")
        assertEquals("[00:00.590]ni ga", repository.netEaseRomanizedLyrics(response))
    }

    @Test fun qqReadsItsSeparateRomanizationTrackAndRemovesQRCTimestamps() {
        val response = "<contentroma><![CDATA[[1000,1000]ki(1000,500)mi(1500,500)]]></contentroma>"
        assertEquals("[00:01.000]kimi", repository.qqRomanizedLyrics(response))
    }

    @Test fun qqPreservesNativePhoneticTimingInsteadOfOnlyFlatteningIt() {
        val response = "<contentroma><![CDATA[[15784,3906]ko (15784,168)u (15953,168)ka (16121,199)]]></contentroma>"
        assertEquals("[15784,3906](15784,168)ko (15953,168)u (16121,199)ka ", repository.qqRomanizedWordLyrics(response))
        assertEquals("", repository.qqRomanizedWordLyrics("<contentroma><![CDATA[[00:01]kimi]]></contentroma>"))
        val result = DirectLyricsRepository.Result(romanizedWordLyrics = "[1000,1000](1000,500)ki(1500,500)mi")
        assertEquals(result.romanizedWordLyrics, result.toJson().getString("romanizedWordLyrics"))
    }

    @Test fun netEasePreservesCanonicalWordPronunciationWhenLineTrackAlsoExists() {
        val track = "[1000,1000](1000,500,0)ki(1500,500,0)mi"
        val root = JSONObject().put("romalrc", JSONObject().put("lyric", "[00:01]kimi"))
            .put("yromalrc", JSONObject().put("lyric", track))
        assertEquals(track, repository.netEaseRomanizedWordLyrics(root))
        assertEquals("", repository.netEaseRomanizedWordLyrics(JSONObject()))
    }

    @Test fun absentOrCorruptOptionalQQTrackDoesNotInventPronunciation() {
        assertEquals("", repository.qqRomanizedLyrics("<contentts><![CDATA[[00:01]你]]></contentts>"))
        assertEquals("", repository.qqRomanizedLyrics("<contentroma><![CDATA[FFFFFFFFFFFFFFFF]]></contentroma>"))
    }

    @Test fun modernQQPlainPayloadUsesTheSameCanonicalParser() {
        assertEquals("[00:01]kimi", repository.decodeQqRomanizedTrack("[00:01]kimi"))
    }

    @Test fun savedProviderPayloadRetainsRomanization() {
        val result = DirectLyricsRepository.Result(lyrics = "[00:01]君", romanizedLyrics = "[00:01]kimi")
        assertEquals(result.romanizedLyrics, result.toJson().getString("romanizedLyrics"))
    }

    @Test fun lyricsAreDeliveredBeforeAnOptionalRequestAndSurviveItsFailure() {
        val original = DirectLyricsRepository.Result(lyrics = "[00:01]君だ", source = "QQ音乐")
        val delivered = mutableListOf<DirectLyricsRepository.Result>()
        val result = OptionalQqPronunciation.enrich(original, {
            assertEquals(listOf(original), delivered)
            throw java.io.IOException("optional endpoint unavailable")
        }, delivered::add)
        assertSame(original, result)
        assertEquals(listOf(original), delivered)
    }

    @Test fun aLatePronunciationTrackEnrichesTheSameRecording() {
        val original = DirectLyricsRepository.Result(lyrics = "[00:01]君だ", source = "QQ音乐", recordId = "id")
        val delivered = mutableListOf<DirectLyricsRepository.Result>()
        val result = OptionalQqPronunciation.enrich(original, { "[00:01]kimi da" }, delivered::add)
        assertEquals(original, delivered.first())
        assertEquals(result, delivered.last())
        assertEquals(original.recordId, result.recordId)
        assertEquals("[00:01]kimi da", result.romanizedLyrics)
    }

    @Test fun existingPronunciationAndChineseLyricsDoNotRequestAnotherTrack() {
        for (original in listOf(DirectLyricsRepository.Result(lyrics = "[00:01]中文歌曲"),
            DirectLyricsRepository.Result(lyrics = "[00:01]君だ", romanizedLyrics = "[00:01]kimi da"))) {
            val result = OptionalQqPronunciation.enrich(original, { fail("unnecessary request"); "" }, {})
            assertSame(original, result)
        }
    }
}
