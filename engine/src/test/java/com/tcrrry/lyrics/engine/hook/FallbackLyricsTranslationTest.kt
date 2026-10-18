package com.tcrrry.lyrics.engine.hook
import com.tcrrry.lyrics.plugin.PluginStorage.pluginPreferences

import android.app.Activity
import android.content.Context
import com.tcrrry.lyrics.sources.DirectLyricsRepository
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class FallbackLyricsTranslationTest {
    @org.junit.Before fun attachPlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.attach() }
    @org.junit.After fun closePlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.current.close() }
    private fun context(): Context = Robolectric.buildActivity(Activity::class.java).setup().get().also {
        it.pluginPreferences("japanese_pronunciation", Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", false).putBoolean("korean_enabled", false).putBoolean("primary", false).commit()
        it.pluginPreferences("supplement_translation", Context.MODE_PRIVATE).edit().putString("mode", "off").commit()
    }
    private val track = DesktopLyricsTrack("song", "artist", "album", 180000L, 983452L)
    private val candidate = AutoLyricsCandidate("APPLE_NATIVE", """<tt xmlns:itunes="urn:itunes" itunes:timing="Word"><body><div><p begin="1s" end="3s" itunes:key="L1"><span begin="1s" end="2s">君の</span><span begin="2s" end="3s">声</span></p></div></body></tt>""")
    private val source = DirectLyricsRepository.Result(lyrics = "[00:01.000]君の声", translatedLyrics = "[00:01.000]你的声音", source = "QQ音乐", score = 100)
    private val direct = Executor { it.run() }

    @Test fun firstMachineRowIsVisibleAndRefreshesBeforeTheRestOfTheSongFinishes() {
        val context=context()
        context.pluginPreferences("supplement_translation",0).edit().putString("mode","offline").commit()
        val local=mutableListOf<Runnable>();val network=mutableListOf<Runnable>()
        var refreshes=0;var sawPartial=false
        CurrentLyricsSourceStatus.rememberCandidate(context,track.appleMusicId,"desktop-lyrics:QQ音乐",candidate.ttml)
        CurrentLyricsSourceStatus.installRefreshHandler { id,_ -> assertEquals(track.appleMusicId,id);refreshes++;true }
        lateinit var enrichment:FallbackLyricsTranslation
        enrichment=FallbackLyricsTranslation(context,{ emptyList() },Executor { local+=it },
            networkWorker=Executor { network+=it },translateRows={ _,emit ->
                emit(org.json.JSONObject().put("id",0).put("text","第一句机翻"))
                sawPartial=TtmlSubtitleTrack.subtitleValues(enrichment.enrichWithTrack(track.appleMusicId,candidate,track).ttml)[0]=="第一句机翻"
                throw IllegalStateException("later rows failed")
            })
        try {
            enrichment.enrichWithTrack(track.appleMusicId,candidate,track)
            local.single().run()
            assertTrue(sawPartial);assertEquals(1,refreshes)
            assertEquals("第一句机翻",TtmlSubtitleTrack.subtitleValues(enrichment.enrichWithTrack(track.appleMusicId,candidate,track).ttml)[0])
        } finally { CurrentLyricsSourceStatus.installRefreshHandler { _,_->false } }
    }

    @Test fun queuedOldSongDoesNotStartTranslationOrPlatformLookup() {
        val context=context()
        context.pluginPreferences("supplement_translation",0).edit().putString("mode","offline").commit()
        var current=true;val pending=mutableListOf<Runnable>()
        val enrichment=FallbackLyricsTranslation(context,{ error("old provider must not start") },Executor { pending+=it },
            translateRows={ _,_->error("old translator must not start") },isCurrent={current})
        enrichment.enrichWithTrack(track.appleMusicId,candidate,track)
        current=false;pending.forEach(Runnable::run)
        assertEquals(2,pending.size)
    }

    @Test fun offlineRowsPublishBeforeAnyPlatformRequestRunsAndProviderLaterWins() {
        val context=context()
        context.pluginPreferences("supplement_translation",0).edit().putString("mode","offline").commit()
        val local=mutableListOf<Runnable>();val network=mutableListOf<Runnable>();var calls=0
        val enrichment=FallbackLyricsTranslation(context,{ calls++;listOf(source) },Executor { local+=it },
            networkWorker=Executor { network+=it },translateRows={ _,emit -> emit(org.json.JSONObject().put("id",0).put("text","本地机翻")) })
        assertTrue(TtmlSubtitleTrack.subtitleValues(enrichment.enrichWithTrack(track.appleMusicId,candidate,track).ttml).isEmpty())
        local.single().run()
        assertEquals(0,calls)
        assertEquals("本地机翻",TtmlSubtitleTrack.subtitleValues(enrichment.enrichWithTrack(track.appleMusicId,candidate,track).ttml)[0])
        network.single().run()
        assertEquals(1,calls)
        assertEquals("你的声音",TtmlSubtitleTrack.subtitleValues(enrichment.enrichWithTrack(track.appleMusicId,candidate,track).ttml)[0])
    }

    @Test fun earlyCompletionReturnsItsResultEvenBeforeFirstSourcePublication() {
        val enrichment=FallbackLyricsTranslation(context(),{ listOf(source) },direct)
        val first=enrichment.enrichWithTrack(track.appleMusicId,candidate,track)
        assertEquals("你的声音",TtmlSubtitleTrack.subtitleValues(first.ttml)[0])
    }

    @Test fun incompleteMachineRunRetainsRowsAndCanRetryWithoutWaitingForPlatformCooldown() {
        val context=context()
        context.pluginPreferences("supplement_translation",0).edit().putString("mode","offline").commit()
        var clock=0L;var attempts=0
        val enrichment=FallbackLyricsTranslation(context,{ emptyList() },direct,{clock},translateRows={ _,emit ->
            attempts++;if(attempts==1) throw IllegalStateException("temporary connection failure")
            emit(org.json.JSONObject().put("id",0).put("text","本地机翻"))
        })
        enrichment.enrichWithTrack(track.appleMusicId,candidate,track)
        clock=4999;enrichment.enrichWithTrack(track.appleMusicId,candidate,track);assertEquals(1,attempts)
        clock=5000
        assertEquals("本地机翻",TtmlSubtitleTrack.subtitleValues(enrichment.enrichWithTrack(track.appleMusicId,candidate,track).ttml)[0])
        assertEquals(2,attempts)
    }

    @Test fun firstNativeDocumentHasDictionaryReadingBeforeNetworkWorkerRuns() {
        val context = context()
        context.pluginPreferences("japanese_pronunciation", Context.MODE_PRIVATE).edit().putBoolean("enabled", true).commit()
        val pending = mutableListOf<Runnable>()
        var calls = 0
        val enrichment = FallbackLyricsTranslation(context, { calls++; throw java.io.IOException("offline") }, Executor { pending += it })
        val japanese = candidate.copy(ttml = candidate.ttml.replace("君の", "ただ風が").replace(">声<", ">吹くのを待ってた<"))
        val first = enrichment.enrichWithTrack(track.appleMusicId, japanese, track)
        assertEquals(0, calls)
        assertEquals(1, pending.size)
        assertTrue(TtmlSubtitleTrack.subtitleValues(first.ttml, true).values.first().contains("tada"))
        assertTrue(TtmlTimingPolicy.isWord(first.ttml))
        val pointer = Any()
        NativeLyricsPronunciationSubtitle.remember(pointer, first.ttml)
        assertTrue(NativeLyricsPronunciationSubtitle.isManagedWord(pointer))
        assertTrue(NativeLyricsPronunciationSubtitle.hasManagedPronunciation(pointer))
        assertEquals(setOf("dictionary"), TtmlAuxiliaryOrigins.read(first.ttml)!!.pronunciation)
        pending.single().run()
        assertEquals(1, calls)
        assertEquals(TtmlSubtitleTrack.subtitleValues(first.ttml, true),
            TtmlSubtitleTrack.subtitleValues(enrichment.enrichWithTrack(track.appleMusicId, japanese, track).ttml, true))
    }

    @Test fun sourcePronunciationSurvivesEarlyDictionarySupplementAndTranslationDisabled() {
        val context = context()
        context.pluginPreferences("japanese_pronunciation", Context.MODE_PRIVATE).edit().putBoolean("enabled", true).commit()
        val primary = requireNotNull(TtmlSubtitleTrack.attachPronunciation(candidate.ttml, mapOf(0 to "kimi no koe")))
        val result = FallbackLyricsTranslation(context, { error("network must not run") }, Executor { error("no worker needed") })
            .enrich(track.appleMusicId, AutoLyricsCandidate("QQ音乐", primary))
        assertEquals("kimi no koe", TtmlSubtitleTrack.subtitleValues(result.ttml, true)[0])
        assertTrue(TtmlTimingPolicy.isWord(result.ttml))
    }

    @Test fun liveTrackFillsNativeWordsFromPlatformAndCachesResult() {
        var calls = 0
        val enrichment = FallbackLyricsTranslation(context(), { input ->
            calls++; assertEquals(track, input); listOf(source)
        }, direct)
        enrichment.enrichWithTrack(track.appleMusicId, candidate, track)
        val actual = enrichment.enrichWithTrack(track.appleMusicId, candidate, track)
        assertEquals(1, calls)
        assertEquals("APPLE_NATIVE", actual.source)
        assertEquals("你的声音", TtmlSubtitleTrack.subtitleValues(actual.ttml)[0])
        assertEquals(setOf("QQ音乐"), TtmlAuxiliaryOrigins.read(actual.ttml)!!.translation)
        assertTrue(actual.ttml.contains(candidate.ttml.substringAfter("<body>")))
        assertTrue(TtmlTimingPolicy.isWord(actual.ttml))
    }
    @Test fun metadataBecomingAvailableDoesNotReuseEmptyEnrichmentForever() {
        var calls = 0
        val enrichment = FallbackLyricsTranslation(context(), { calls++; listOf(source) }, direct)
        enrichment.enrich(track.appleMusicId, candidate)
        assertEquals(0, calls)
        enrichment.enrichWithTrack(track.appleMusicId, candidate, track)
        assertEquals(1, calls)
        assertEquals("你的声音", TtmlSubtitleTrack.subtitleValues(enrichment.enrichWithTrack(track.appleMusicId, candidate, track).ttml)[0])
    }
    @Test fun failedPlatformAttemptRetriesAfterCooldownInsteadOfCachingNoTranslationForever() {
        var calls = 0; var clock = 0L
        val enrichment = FallbackLyricsTranslation(context(), {
            if (++calls == 1) throw java.io.IOException("temporary")
            listOf(source.copy(source = "网易云音乐"))
        }, direct, { clock })
        enrichment.enrichWithTrack(track.appleMusicId, candidate, track)
        clock = 29999
        assertTrue(TtmlSubtitleTrack.subtitleValues(enrichment.enrichWithTrack(track.appleMusicId, candidate, track).ttml).isEmpty())
        assertEquals(1, calls)
        clock = 30000
        enrichment.enrichWithTrack(track.appleMusicId, candidate, track)
        val actual = enrichment.enrichWithTrack(track.appleMusicId, candidate, track)
        assertEquals(2, calls)
        assertEquals(setOf("网易云音乐"), TtmlAuxiliaryOrigins.read(actual.ttml)!!.translation)
    }
    @Test fun lowConfidenceOrUnrelatedPlatformLyricsDoNotBecomeNativeTranslation() {
        val enrichment = FallbackLyricsTranslation(context(), { listOf(source.copy(score = 49),
            source.copy(lyrics = "[00:01.000]別の曲", source = "网易云音乐")) }, direct)
        enrichment.enrichWithTrack(track.appleMusicId, candidate, track)
        val actual = enrichment.enrichWithTrack(track.appleMusicId, candidate, track)
        assertTrue(TtmlSubtitleTrack.subtitleValues(actual.ttml).isEmpty())
        assertTrue(actual.ttml.contains(candidate.ttml.substringAfter("<body>")))
    }
    @Test fun generatedOfflineReadingUsesNativeWordTimesWithoutPlatformPronunciation() {
        val context = context()
        context.pluginPreferences("japanese_pronunciation", Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", true).putBoolean("primary", true).putBoolean("visible", true).commit()
        val styled = candidate.ttml.replaceFirst("><span begin", "><span tts:style=\"nativeStyle\"><span begin")
            .replace("</span></p>", "</span></span></p>")
        val native = candidate.copy(ttml = TtmlAuxiliaryOrigins(setOf("offline"), emptySet()).attach(
            requireNotNull(TtmlSubtitleTrack.attach(styled, mapOf(0 to "你的声音")))))
        val enrichment = FallbackLyricsTranslation(context, { emptyList() }, direct)
        enrichment.enrichWithTrack(track.appleMusicId, native, track)
        val rendered = enrichment.enrichWithTrack(track.appleMusicId, native, track)
        assertTrue(TtmlTimingPolicy.isWord(rendered.ttml))
        assertTrue(rendered.ttml.contains("kimi"))
        assertEquals("君の声", TtmlSubtitleTrack.subtitleValues(rendered.ttml, true)[0])
        assertEquals("你的声音", TtmlSubtitleTrack.subtitleValues(rendered.ttml)[0])
        assertEquals(setOf("dictionary"), TtmlAuxiliaryOrigins.read(rendered.ttml)!!.pronunciation)
        assertEquals(setOf("offline"), TtmlAuxiliaryOrigins.read(rendered.ttml)!!.translation)
    }
}
