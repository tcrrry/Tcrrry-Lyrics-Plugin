package com.tcrrry.lyrics.engine.hook

import com.tcrrry.lyrics.engine.CurrentSongDetails
import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(manifest = org.robolectric.annotation.Config.NONE, sdk = [35])
class NativeLyricsMetadataRefreshTest {
    @org.junit.Before fun attachPlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.attach() }
    @org.junit.After fun closePlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.current.close() }
    private class Metadata(val id: Long, val title: String, val duration: Long, val lyrics: String)
    private class Page(var id: Long = 1, var added: Boolean = true) {
        var title = "Old song"
        var duration = 270000L
        var lyrics = "Old lyrics"
        var replays = 0
        fun nativeUpdate(metadata: Metadata) {
            id = metadata.id
            title = metadata.title
            duration = metadata.duration
            lyrics = metadata.lyrics
            replays++
        }
    }
    private class Harness {
        val cache = CurrentSongIdentityCache()
        val jobs = mutableListOf<() -> Unit>()
        val recovered = mutableListOf<Long>()
        val refresh = NativeLyricsMetadataRefresh(cache, { (it as Page).id }, { (it as Page).added },
            { jobs += it }, { page, metadata -> (page as Page).nativeUpdate(metadata as Metadata) },
            { _, id -> recovered += id })
        init { cache.bootstrap(Any(), CurrentSongDetails(1, "Old song", "MIREI")) }
        fun publish(id: Long, metadata: Metadata? = Metadata(id, "New song", 200000, "New lyrics")) {
            cache.publish(Any(), CurrentSongDetails(id, "New song", "AYANE"), metadata)
        }
        fun drain() { jobs.toList().also { jobs.clear() }.forEach { it() } }
    }

    @Test fun missedNativeNotificationRecoversWholePageFromExactPublishedMetadata() {
        val h = Harness()
        val page = Page()
        val metadata = Metadata(2, "泣きたい夜", 200000, "New lyric document")
        h.publish(2, metadata)
        assertSame(metadata, h.cache.current()!!.nativeMetadata)
        h.refresh.schedule(page)
        h.drain()
        assertEquals(2L, page.id)
        assertEquals("泣きたい夜", page.title)
        assertEquals(200000L, page.duration)
        assertEquals("New lyric document", page.lyrics)
        assertEquals(listOf(2L), h.recovered)
    }
    @Test fun nativeUpdateBeforeQueuedRecoveryAvoidsDuplicateReload() {
        val h = Harness()
        val page = Page()
        h.publish(2)
        h.refresh.schedule(page)
        page.nativeUpdate(h.cache.current()!!.nativeMetadata as Metadata)
        h.drain()
        assertEquals(1, page.replays)
        assertTrue(h.recovered.isEmpty())
    }
    @Test fun rapidSkipCannotReplayAnOlderSongOverTheLatestOne() {
        val h = Harness()
        val page = Page()
        h.publish(2)
        h.refresh.schedule(page)
        h.publish(3)
        h.refresh.schedule(page)
        h.drain()
        assertEquals(3L, page.id)
        assertEquals(1, page.replays)
        assertEquals(listOf(3L), h.recovered)
    }
    @Test fun detachedPageAndClearedPlayerRejectQueuedEvents() {
        val h = Harness()
        val page = Page()
        h.publish(2)
        h.refresh.schedule(page)
        page.added = false
        h.drain()
        page.added = true
        h.refresh.schedule(page)
        h.cache.publish(null, null)
        h.drain()
        assertEquals(0, page.replays)
    }
    @Test fun restoredPageWithoutNativeMetadataAndUnrelatedTrackFailOpen() {
        val h = Harness()
        val page = Page()
        h.refresh.schedule(page)
        h.publish(2, null)
        h.refresh.schedule(page)
        h.drain()
        assertEquals(0, page.replays)
        h.publish(2)
        page.id = 999
        h.refresh.schedule(page)
        h.drain()
        assertEquals(0, page.replays)
    }
    @Test fun returningToStalePageUsesLatestSnapshotAndRepeatedSchedulingIsIdempotent() {
        val h = Harness()
        val page = Page()
        h.publish(2)
        h.refresh.schedule(page)
        h.refresh.schedule(page)
        h.drain()
        assertEquals(2L, page.id)
        assertEquals(1, page.replays)
        h.refresh.schedule(page)
        h.drain()
        assertEquals(1, page.replays)
    }
}
