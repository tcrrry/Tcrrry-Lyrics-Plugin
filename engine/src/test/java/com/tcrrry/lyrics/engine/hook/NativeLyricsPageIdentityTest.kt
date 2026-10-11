package com.tcrrry.lyrics.engine.hook

import com.tcrrry.lyrics.engine.CurrentSongDetails
import org.junit.Assert.*
import org.junit.Test

class NativeLyricsPageIdentityTest {
    private data class Item(var identity: NativeLyricsPageIdentity.Identity, val downloaded: Boolean = false)
    private class Page(var item: Item, var live: NativeLyricsPageIdentity.Identity?)
    private val expected = NativeLyricsPageIdentity.Identity(4_256_633_673_805_039_340L, 72L, 83L)
    private fun snapshot(identity: NativeLyricsPageIdentity.Identity = expected) = TargetCurrentSong(
        Item(identity), CurrentSongDetails(identity.id, "静悄悄", "旺仔小乔"), identity)
    private fun helper(factory: (Any, Any) -> Any? = { metadata, item ->
        Item(metadata as NativeLyricsPageIdentity.Identity, (item as Item).downloaded)
    }) = NativeLyricsPageIdentity(
        { (it as Page).live }, { it as? NativeLyricsPageIdentity.Identity },
        { (it as Page).item }, { (it as Item).identity }, factory,
        { page, item -> (page as Page).item = item as Item; true })

    @Test fun uploadedLibraryAliasRestoresMediaIdentityAndKeepsLibraryFlagsWithoutMutatingEitherItem() {
        val original = Item(expected.copy(id = 182871078), downloaded = true)
        val page = Page(original, expected)
        val published = snapshot()
        assertTrue(helper().restoreLibraryAlias(page, published))
        assertEquals(expected, page.item.identity)
        assertTrue(page.item.downloaded)
        assertEquals(182871078L, original.identity.id)
        assertEquals(expected, (published.item as Item).identity)
        assertNotSame(original, page.item)
        assertNotSame(published.item, page.item)
        assertFalse(helper().restoreLibraryAlias(page, published))
    }
    @Test fun mismatchingLiveTrackQueueOrPersistentIdentityCannotAuthorizeRecovery() {
        val h = helper()
        val page = Page(Item(expected.copy(id = 1)), expected)
        for (live in listOf(null, expected.copy(id = 9), expected.copy(queueId = 84), expected.copy(persistentId = 73))) {
            page.live = live
            assertFalse(h.isAuthoritative(page, snapshot()))
            assertFalse(h.restoreLibraryAlias(page, snapshot()))
        }
    }
    @Test fun unrelatedPageOrMissingPersistentIdentityCannotBeTreatedAsLibraryAlias() {
        val h = helper()
        for (item in listOf(expected.copy(id = 1, persistentId = 73), expected.copy(id = 1, queueId = 0))) {
            assertFalse(h.restoreLibraryAlias(Page(Item(item), expected), snapshot()))
        }
        val absent = expected.copy(persistentId = 0)
        assertFalse(h.restoreLibraryAlias(Page(Item(absent.copy(id = 1)), absent), snapshot(absent)))
    }
    @Test fun staleSnapshotIdAndSharedOrMalformedFactoryResultAreRejected() {
        val page = Page(Item(expected.copy(id = 1)), expected)
        val published = snapshot()
        assertFalse(helper().isAuthoritative(page, published.copy(details = published.details.copy(appleMusicId = 2))))
        assertFalse(helper { _, original -> original }.restoreLibraryAlias(page, published))
        assertFalse(helper { _, _ -> published.item }.restoreLibraryAlias(page, published))
        assertFalse(helper { _, _ -> Item(expected.copy(queueId = 0)) }.restoreLibraryAlias(page, published))
    }
    @Test fun trackChangeDuringNativeCopyRejectsBinding() {
        val page = Page(Item(expected.copy(id = 1)), expected)
        val original = page.item
        val h = helper { _, _ -> page.live = expected.copy(queueId = 99); Item(expected) }
        assertFalse(h.restoreLibraryAlias(page, snapshot()))
        assertSame(original, page.item)
    }
}
