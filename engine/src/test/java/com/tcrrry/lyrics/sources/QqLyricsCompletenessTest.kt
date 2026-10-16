package com.tcrrry.lyrics.sources

import com.tcrrry.lyrics.engine.lyrics.DesktopLyricsTtmlConverter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class QqLyricsCompletenessTest {
    private val partial=DirectLyricsRepository.Result(source="QQ音乐",recordId="004Qs7J91mFVAR",
        title="あの子",artist="Soala",durationMs=181000,
        lyrics="[00:00.000]あの子 - Soala\n[00:11.610]词：Soala\n[00:53.173]少しの勇気がまだ足りない",
        wordLyrics="[53173,3331](53173,3331,0)少しの勇気がまだ足りない",
        translatedLyrics="[00:53.170]但还缺少一点点的勇气\n[02:48.170]现在就要去告诉你啦")
    private val complete=partial.lyrics + "\n[02:43.880]ちゃんと持っているから\n[02:45.900]大好きだって自信を持って\n[02:48.170]君に伝えに行くよ"

    @org.junit.Before fun attachPlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.attach() }
    @org.junit.After fun closePlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.current.close() }

    @Test fun sameRecordFullLrcRestoresAnokoTailWithoutInventingWordTimes() {
        var calls=0
        val restored=QqLyricsCompleteness.repair(partial) { calls++;complete }
        assertEquals(1,calls)
        assertEquals(complete,restored.lyrics)
        assertEquals("",restored.wordLyrics)
        assertEquals(partial.translatedLyrics,restored.translatedLyrics)
        assertEquals(partial.recordId,restored.recordId)
        val ttml=requireNotNull(DesktopLyricsTtmlConverter.convert(restored,181000))
        assertTrue(ttml.contains("itunes:timing=\"Line\""))
        assertTrue(ttml.contains("begin=\"2:48.170\""))
        assertTrue(ttml.contains("君に伝えに行くよ"))
    }
    @Test fun savedRealQqResponsesRestoreTheTailInTheProductionConverter() {
        val directory=java.io.File("../build/anoko-diagnosis")
        val qrc=java.io.File(directory,"content-decoded.txt")
        val lrc=java.io.File(directory,"qq-lrc.json")
        org.junit.Assume.assumeTrue("Local real-response evidence is optional",qrc.isFile && lrc.isFile)
        val repository=DirectLyricsRepository()
        fun converted(method: String) = DirectLyricsRepository::class.java.getDeclaredMethod(method,String::class.java)
            .apply { isAccessible=true }.invoke(repository,qrc.readText()) as String
        val old=partial.copy(lyrics=converted("qrcToLineLrc"),wordLyrics=converted("qrcToCanonicalWordLyrics"))
        val complete=org.json.JSONObject(lrc.readText()).getString("lyric")
        val restored=QqLyricsCompleteness.repair(old) { complete }
        assertNotSame(old,restored)
        assertEquals("",restored.wordLyrics)
        val ttml=requireNotNull(DesktopLyricsTtmlConverter.convert(restored,181000))
        assertTrue(ttml.contains("begin=\"2:48.170\""))
        assertTrue(ttml.contains("君に伝えに行くよ"))
    }
    @Test fun completeTracksDoNotRequestVerificationOrLoseTheirWords() {
        val full=partial.copy(lyrics=complete)
        assertSame(full,QqLyricsCompleteness.repair(full) { fail("No extra request for full tracks");"" })
    }
    @Test fun missingDurationCanStillUseTheLongerTranslationAsVerificationEvidence() {
        assertEquals(complete,QqLyricsCompleteness.repair(partial.copy(durationMs=0)) { complete }.lyrics)
    }
    @Test fun networkFailureOrEmptyResponsePreservesTheAvailableTrack() {
        assertSame(partial,QqLyricsCompleteness.repair(partial) { error("offline") })
        assertSame(partial,QqLyricsCompleteness.repair(partial) { "" })
    }
    @Test fun longInstrumentalTailAloneDoesNotReplaceLyrics() {
        val instrumental=partial.copy(translatedLyrics="")
        assertSame(instrumental,QqLyricsCompleteness.repair(instrumental) { partial.lyrics })
        assertSame(instrumental,QqLyricsCompleteness.repair(instrumental) { partial.lyrics+"\n[02:55]" })
    }
    @Test fun differentTextOrArrangementTimingCannotReplaceTheSameRecord() {
        assertSame(partial,QqLyricsCompleteness.repair(partial) {
            "[00:20]其他歌\n[01:30]第一句\n[01:40]第二句\n[02:00]第三句"
        })
        assertSame(partial,QqLyricsCompleteness.repair(partial) {
            complete.replace("00:00.000","00:25.000").replace("00:11.610","00:30.610").replace("00:53.173","01:03.173")
        })
    }
    @Test fun otherProvidersAndMissingRecordIdsNeverFetchQq() {
        for (input in listOf(partial.copy(source="网易云音乐"),partial.copy(recordId="")))
            assertSame(input,QqLyricsCompleteness.repair(input) { fail("Wrong source");"" })
    }
}
