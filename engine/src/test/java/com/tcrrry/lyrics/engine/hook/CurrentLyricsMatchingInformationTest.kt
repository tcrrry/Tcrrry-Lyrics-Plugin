package com.tcrrry.lyrics.engine.hook
import com.tcrrry.lyrics.plugin.PluginStorage.pluginPreferences

import android.app.Activity
import com.tcrrry.lyrics.sources.DirectLyricsRepository
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class CurrentLyricsMatchingInformationTest {
    @org.junit.Before fun attachPlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.attach() }
    @org.junit.After fun closePlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.current.close() }
    @Test fun nativePhoneticWordStatusDoesNotHardCodeLineModeAndShowsLocalFallbackCounts() {
        val context = Robolectric.buildActivity(Activity::class.java).setup().get()
        val doc = com.tcrrry.lyrics.engine.lyrics.DesktopLyricsPresentation("未知", true, true, false, false, true,
            primaryPronunciation = true).marker() +
            "<!--native-phonetic-v2 words=12 lines=1--><tt itunes:timing=\"Word\"><body/></tt>"
        val enriched = TtmlAuxiliaryOrigins(setOf("QQ音乐"), setOf("dictionary")).attach(doc)
        CurrentLyricsSourceStatus.rememberCandidate(context, 81239L, "APPLE_NATIVE", enriched)
        CurrentLyricsSourceStatus.recordApplied(context, 81239L, false)
        val detail = CurrentLyricsSourceStatus.matchingInformation(context, 81239L)
        assertTrue(detail.contains("发音逐字 12 句 · 逐行 1 句"))
        assertFalse(detail.contains("突出发音 · 逐行显示"))
        assertTrue(detail.contains("QQ音乐"))
    }
    @Test fun detailsFollowInstalledResultAndDoNotExposePendingOrPreviousSongAsCurrent() {
        val context = Robolectric.buildActivity(Activity::class.java).setup().get()
        val id = 81234L
        val first = DirectLyricsRepository.Result(source = "QQ音乐", title = "Answer", artist = "幾田りら",
            album = "Sketch", durationMs = 246000, recordId = "0036wvFg4CJXrQ", score = 100)
        CurrentLyricsSourceStatus.rememberMatchInput(context, DesktopLyricsTrack("Answer", "Lilas Ikuta", "Sketch", 246000, id))
        CurrentLyricsSourceStatus.rememberMatchResult(context, id, first)
        CurrentLyricsSourceStatus.rememberCandidate(context, id, "desktop-lyrics:QQ音乐", "<tt/>")
        CurrentLyricsSourceStatus.recordApplied(context, id, false)
        val detail = CurrentLyricsSourceStatus.matchingInformation(context, id)
        assertTrue(detail.contains("Apple Music ID：81234"))
        assertTrue(detail.contains("歌手：幾田りら"))
        assertTrue(detail.contains("专辑：Sketch"))
        assertTrue(detail.contains("时长：246.0 秒"))
        assertTrue(detail.contains("平台 ID：0036wvFg4CJXrQ"))
        CurrentLyricsSourceStatus.rememberMatchResult(context, id, first.copy(title = "Pending version", recordId = "pending"))
        assertEquals(detail, CurrentLyricsSourceStatus.matchingInformation(context, id))
        assertFalse(CurrentLyricsSourceStatus.matchingInformation(context, id + 1).contains("0036wvFg4CJXrQ"))
        CurrentLyricsSourceStatus.recordApplied(context, id, false)
        assertTrue(CurrentLyricsSourceStatus.matchingInformation(context, id).contains("平台 ID：pending"))
        CurrentLyricsSourceStatus.recordNativeApplied(context, id)
        assertFalse(CurrentLyricsSourceStatus.matchingInformation(context, id).contains("平台 ID：pending"))
        assertTrue(CurrentLyricsSourceStatus.matchingInformation(context, id).contains("无第三方平台匹配记录"))
    }
    @Test fun matchedAlbumSurvivesHistoryAndAbsentAlbumIsExplicit() {
        val context = Robolectric.buildActivity(Activity::class.java).setup().get()
        val result = DirectLyricsRepository.Result(source = "网易云音乐", title = "song", album = "provider album",
            lyrics = "[00:01]body", recordId = "123", score = 100)
        TcrrryLyricsHistory.remember(context, 81235L, result)
        assertEquals("provider album", TcrrryLyricsHistory.entries(context, 81235L).single().album)
        CurrentLyricsSourceStatus.rememberMatchResult(context, 81235L, result.copy(album = ""))
        CurrentLyricsSourceStatus.rememberCandidate(context, 81235L, "desktop-lyrics:网易云音乐", "<tt/>")
        CurrentLyricsSourceStatus.recordApplied(context, 81235L, false)
        assertTrue(CurrentLyricsSourceStatus.matchingInformation(context, 81235L).contains("专辑：未提供"))
    }
    @Test fun retryRestoresOnlyRequestedProviderAndPendingSelectionCannotExcludeDisplayedProvider() {
        val context = Robolectric.buildActivity(Activity::class.java).setup().get()
        val id = 81236L
        CurrentLyricsSourceStatus.rememberRecord(context, id, "网易云音乐", "net-1")
        CurrentLyricsSourceStatus.rememberCandidate(context, id, "desktop-lyrics:网易云音乐", "<tt/>")
        CurrentLyricsSourceStatus.recordApplied(context, id, false)
        CurrentLyricsSourceStatus.selectSource(context, id, "QQ音乐")
        assertFalse(CurrentLyricsSourceStatus.excludeCurrentRecord(context, id))
        assertEquals("QQ音乐", CurrentLyricsSourceStatus.selectedSource(context, id))
        assertTrue(CurrentLyricsSourceStatus.excludedRecords(context, id, "网易云音乐").isEmpty())
        CurrentLyricsSourceStatus.selectSource(context, id, "网易云音乐")
        CurrentLyricsSourceStatus.rememberRecord(context, id, "网易云音乐", "not-yet-displayed")
        assertTrue(CurrentLyricsSourceStatus.excludeCurrentRecord(context, id))
        assertEquals(setOf("net-1"), CurrentLyricsSourceStatus.excludedRecords(context, id, "网易云音乐"))
        CurrentLyricsSourceStatus.retryCurrentSource(context, id)
        assertTrue(CurrentLyricsSourceStatus.excludedRecords(context, id, "网易云音乐").isEmpty())
        assertEquals("网易云音乐", CurrentLyricsSourceStatus.selectedSource(context, id))
    }
    @Test fun retryAndSwitchBackRecoverBestWordHistoryInsteadOfNewestPoorerResult() {
        val context = Robolectric.buildActivity(Activity::class.java).setup().get()
        val id = 81237L
        val word = DirectLyricsRepository.Result(source = "QQ音乐", recordId = "word", score = 95,
            lyrics = "[00:01]君", wordLyrics = "[1000,500](1000,500)君")
        val line = word.copy(recordId = "line", score = 100, wordLyrics = "",
            translatedLyrics = "[00:01]你", romanizedLyrics = "[00:01]kimi")
        TcrrryLyricsHistory.remember(context, id, word)
        TcrrryLyricsHistory.remember(context, id, line)
        assertEquals("word", TcrrryLyricsHistory.best(context, id, "QQ音乐", wordFirst = true)?.recordId)
        assertEquals("line", TcrrryLyricsHistory.best(context, id, "QQ音乐", wordFirst = false)?.recordId)
        context.pluginPreferences("ampp-current-lyrics-source", android.content.Context.MODE_PRIVATE).edit()
            .putStringSet("excluded_${id}_QQ音乐", setOf("word", "line")).apply()
        CurrentLyricsSourceStatus.selectSource(context, id, "QQ音乐")
        assertNull(TcrrryLyricsHistory.best(context, id, "QQ音乐",
            CurrentLyricsSourceStatus.excludedRecords(context, id, "QQ音乐"), true))
        CurrentLyricsSourceStatus.retryCurrentSource(context, id)
        assertEquals("word", TcrrryLyricsHistory.best(context, id, "QQ音乐",
            CurrentLyricsSourceStatus.excludedRecords(context, id, "QQ音乐"), true)?.recordId)
        TcrrryLyricsHistory.select(context, id, line)
        assertEquals("line", TcrrryLyricsHistory.selected(context, id, "QQ音乐")?.recordId)
        CurrentLyricsSourceStatus.resetMatching(context, id)
        CurrentLyricsSourceStatus.rememberCandidate(context, id, "desktop-lyrics:QQ音乐", "<tt/>")
        CurrentLyricsSourceStatus.recordApplied(context, id, false)
        CurrentLyricsSourceStatus.retryCurrentSource(context, id)
        assertEquals("QQ音乐", CurrentLyricsSourceStatus.selectedSource(context, id))
    }
    @Test fun failedQuickSwitchContinuesThroughEachSourceOnceAndNeverClaimsItWasApplied() {
        val context = Robolectric.buildActivity(Activity::class.java).setup().get()
        val id = 81238L
        CurrentLyricsSourceStatus.rememberCandidate(context, id, "desktop-lyrics:QQ音乐", "<tt/>")
        CurrentLyricsSourceStatus.recordApplied(context, id, false)
        assertEquals("网易云音乐", CurrentLyricsSourceStatus.beginSourceCycle(context, id))
        val attempted = mutableListOf<String>()
        repeat(5) {
            attempted += CurrentLyricsSourceStatus.selectedSource(context, id)!!
            val ticket = CurrentLyricsSourceStatus.sourceCycleTicket(id)
            CurrentLyricsSourceStatus.continueSourceCycle(context, id, ticket, success = false)
            assertEquals("desktop-lyrics:QQ音乐", CurrentLyricsSourceStatus.appliedSource(context, id))
        }
        assertEquals(listOf("网易云音乐", "LRCLIB", LyricsSourceMenuPolicy.NATIVE, LyricsSourceMenuPolicy.AUTHOR, "QQ音乐"), attempted)
        assertNull(CurrentLyricsSourceStatus.sourceCycleTicket(id))
        assertNull(CurrentLyricsSourceStatus.continueSourceCycle(context, id, null, false))
    }
    @Test fun quickSwitchStopsAtFirstSuccessfulSource() {
        val context = Robolectric.buildActivity(Activity::class.java).setup().get()
        val id = 81239L
        CurrentLyricsSourceStatus.selectSource(context, id, "QQ音乐")
        assertEquals("网易云音乐", CurrentLyricsSourceStatus.beginSourceCycle(context, id))
        assertEquals("LRCLIB", CurrentLyricsSourceStatus.continueSourceCycle(context, id,
            CurrentLyricsSourceStatus.sourceCycleTicket(id), false))
        assertNull(CurrentLyricsSourceStatus.continueSourceCycle(context, id,
            CurrentLyricsSourceStatus.sourceCycleTicket(id), true))
        assertEquals("LRCLIB", CurrentLyricsSourceStatus.selectedSource(context, id))
        assertNull(CurrentLyricsSourceStatus.sourceCycleTicket(id))
    }
    @Test fun newGestureExplicitSelectionOrSongChangeCannotBeAdvancedByOldFailure() {
        val context = Robolectric.buildActivity(Activity::class.java).setup().get()
        val id = 81240L
        CurrentLyricsSourceStatus.selectSource(context, id, "QQ音乐")
        assertEquals("网易云音乐", CurrentLyricsSourceStatus.beginSourceCycle(context, id))
        val oldTicket = CurrentLyricsSourceStatus.sourceCycleTicket(id)
        assertEquals("LRCLIB", CurrentLyricsSourceStatus.beginSourceCycle(context, id))
        val newTicket = CurrentLyricsSourceStatus.sourceCycleTicket(id)
        assertNull(CurrentLyricsSourceStatus.continueSourceCycle(context, id, oldTicket, false))
        assertEquals(newTicket, CurrentLyricsSourceStatus.sourceCycleTicket(id))
        assertEquals("LRCLIB", CurrentLyricsSourceStatus.selectedSource(context, id))
        CurrentLyricsSourceStatus.selectSource(context, id, "QQ音乐")
        assertNull(CurrentLyricsSourceStatus.sourceCycleTicket(id))
        assertNull(CurrentLyricsSourceStatus.continueSourceCycle(context, id, newTicket, false))
        CurrentLyricsSourceStatus.beginSourceCycle(context, id)
        CurrentLyricsSourceStatus.cancelSourceCycleUnless(id + 1)
        assertNull(CurrentLyricsSourceStatus.sourceCycleTicket(id))
        assertNull(CurrentLyricsSourceStatus.beginSourceCycle(context, 0L))
    }
}
