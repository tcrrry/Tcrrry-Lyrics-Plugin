package com.tcrrry.lyrics.engine.hook
import org.junit.Assert.*
import org.junit.Test
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(manifest = org.robolectric.annotation.Config.NONE, sdk = [35])
class LyricPlaybackEpochTest {
    @org.junit.Before fun attachPlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.attach() }
    @org.junit.After fun closePlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.current.close() }
    @Test fun replayIsDetectedBeforeAnyLineCallbackEvenWhenDocumentIsUnchanged() {
        val epoch = LyricPlaybackEpoch()
        val document = Any()
        assertEquals(LyricPlaybackEpoch.Change.DOCUMENT, epoch.update(document, 180000))
        assertEquals(LyricPlaybackEpoch.Change.CONTINUOUS, epoch.update(document, 181000))
        assertEquals(LyricPlaybackEpoch.Change.REWIND, epoch.update(document, 0))
        assertEquals(LyricPlaybackEpoch.Change.CONTINUOUS, epoch.update(document, 0))
        assertEquals(LyricPlaybackEpoch.Change.CONTINUOUS, epoch.update(document, 100))
    }
    @Test fun sourceSwitchAndUnknownClockDoNotCarryThePreviousDocumentPosition() {
        val epoch = LyricPlaybackEpoch()
        val old = Any()
        val fresh = Any()
        epoch.update(old, 180000)
        assertEquals(LyricPlaybackEpoch.Change.DOCUMENT, epoch.update(fresh, null))
        assertEquals(LyricPlaybackEpoch.Change.CONTINUOUS, epoch.update(fresh, 5000))
        assertEquals(LyricPlaybackEpoch.Change.CONTINUOUS, epoch.update(fresh, null))
        assertEquals(LyricPlaybackEpoch.Change.REWIND, epoch.update(fresh, 1000))
    }
    @Test fun forwardSeekIsDetectedButDelayedPlaybackTicksAreNotSeeks() {
        val epoch = LyricPlaybackEpoch()
        val doc = Any()
        epoch.update(doc, 1000, 0)
        assertEquals(LyricPlaybackEpoch.Change.CONTINUOUS, epoch.update(doc, 1100, 100))
        assertEquals(LyricPlaybackEpoch.Change.SEEK_FORWARD, epoch.update(doc, 30000, 200))
        assertEquals(LyricPlaybackEpoch.Change.CONTINUOUS, epoch.update(doc, 30100, 300))
        assertEquals(LyricPlaybackEpoch.Change.CONTINUOUS, epoch.update(doc, 60100, 30300))
        assertEquals(LyricPlaybackEpoch.Change.REWIND, epoch.update(doc, 0, 30400))
    }
}
