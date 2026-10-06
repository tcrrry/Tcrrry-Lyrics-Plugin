package com.tcrrry.lyrics.engine.hook

import com.tcrrry.lyrics.sources.DirectLyricsRepository
import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(manifest = org.robolectric.annotation.Config.NONE, sdk = [35])
class LyricsSearchRecoveryTest {
    @org.junit.Before fun attachPlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.attach() }
    @org.junit.After fun closePlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.current.close() }
    private class NativePlaybackItem(private val seconds: Long) {
        fun getPlaybackDuration(): Long = seconds
    }
    @Test fun actualPlaybackItemGetterSuppliesDurationForMireiTracksAndLongSongs() {
        assertEquals(236000L, readPlaybackDurationMs(NativePlaybackItem(236)))
        assertEquals(329000L, readPlaybackDurationMs(NativePlaybackItem(329)))
        assertEquals(1500000L, readPlaybackDurationMs(NativePlaybackItem(1500)))
        assertNull(readPlaybackDurationMs(NativePlaybackItem(-1)))
        assertNull(readPlaybackDurationMs(Any()))
    }
    @Test fun failedSearchCanExpireButPendingAndSuccessCannot() {
        assertFalse(shouldRetryEmptyLyricsSearch(null, 0, 50000))
        assertFalse(shouldRetryEmptyLyricsSearch(DirectLyricsRepository.Result(), 1000, 10999))
        assertTrue(shouldRetryEmptyLyricsSearch(DirectLyricsRepository.Result(), 1000, 11000))
        assertFalse(shouldRetryEmptyLyricsSearch(DirectLyricsRepository.Result(lyrics = "hello"), 1000, 50000))
    }
    @Test fun queueDurationTakesPriorityAndMissingQueueDurationUsesItem() {
        assertEquals(236000L, preferredSongDurationMs(236000L, 300L))
        assertEquals(329000L, preferredSongDurationMs(0L, 329L))
        assertEquals(236000L, preferredSongDurationMs(236L, null))
        assertEquals(0L, preferredSongDurationMs(null, 0L))
    }
}
