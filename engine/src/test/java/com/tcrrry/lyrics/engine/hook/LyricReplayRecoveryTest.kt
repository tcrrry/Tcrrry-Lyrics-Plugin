package com.tcrrry.lyrics.engine.hook

import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(manifest = org.robolectric.annotation.Config.NONE, sdk = [35])
class LyricReplayRecoveryTest {
    @org.junit.Before fun attachPlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.attach() }
    @org.junit.After fun closePlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.current.close() }
    @Test fun outroSeekAndSameDocumentRepeatDoNotDependOnNativeHighlights() {
        val recovery = LyricReplayRecovery(); val doc = Any()
        assertNull(recovery.observe(doc, 210000, false))
        assertEquals(10000L, recovery.observe(doc, 10000, false))
        assertNull(recovery.observe(doc, 10010, false))
        recovery.observe(doc, 210000, false)
        assertEquals(0L, recovery.observe(doc, 0, false))
    }
    @Test fun dragDefersRecoveryUntilReleaseAndUsesLatestPosition() {
        val recovery = LyricReplayRecovery(); val doc = Any()
        recovery.observe(doc, 210000, false)
        assertNull(recovery.observe(doc, 5000, true))
        assertEquals(5200L, recovery.observe(doc, 5200, false))
        assertNull(recovery.observe(doc, 5300, false))
    }
    @Test fun songReplacementClearsPendingRecoveryWithoutScrollingAnUnrelatedDocument() {
        val recovery = LyricReplayRecovery(); val old = Any(); val next = Any()
        recovery.observe(old, 210000, false)
        recovery.observe(old, 0, true)
        assertNull(recovery.observe(next, 0, false))
        assertNull(recovery.observe(next, 200, false))
    }
    @Test fun repeatAcrossReplacementPointersKeepsVerifiedSongIdentityAndOverridesStaleTail() {
        val recovery = LyricReplayRecovery()
        recovery.observe(Any(), 210000, false, 123L)
        assertEquals(700L, recovery.observe(Any(), 700, false, 123L))
        assertTrue(recovery.opening)
        assertEquals(sortedSetOf(0), LyricReplayRecovery.scrollIds(setOf(65,66), recovery.opening))
        assertNull(recovery.observe(Any(), 1000, false, 123L))
        assertNull(recovery.observe(Any(), 0, false, 456L))
        assertFalse(recovery.opening)
    }
    @Test fun loopReturnWaitsForDragReleaseButDoesNotTurnAnOrdinarySeekIntoAFirstLineReset() {
        val recovery = LyricReplayRecovery(); val doc=Any()
        recovery.observe(doc, 210000, false)
        assertNull(recovery.observe(doc, 300, true))
        assertEquals(2800L, recovery.observe(doc, 2800, false))
        assertTrue(recovery.opening)
        recovery.observe(doc, 210000, false)
        assertEquals(5000L, recovery.observe(doc, 5000, false))
        assertFalse(recovery.opening)
        assertEquals(sortedSetOf(5), LyricReplayRecovery.scrollIds(setOf(5), recovery.opening))
    }
    @Test fun disablingForceResetKeepsClockRepairWithoutForcingTheFirstLine() {
        val recovery=LyricReplayRecovery(); val doc=Any()
        recovery.observe(doc,210000,false)
        assertEquals(0L,recovery.observe(doc,0,false,forceRepeat=false))
        assertFalse(recovery.opening)
    }
    @Test fun providerDocumentsWithoutCatalogIdDoNotSilentlyDisableRecoveryButOldPagesAreRejected() {
        assertTrue(LyricReplayRecovery.sameSong(123L,123L,0L))
        assertTrue(LyricReplayRecovery.sameSong(123L,123L,null))
        assertTrue(LyricReplayRecovery.sameSong(123L,123L,123L))
        assertFalse(LyricReplayRecovery.sameSong(123L,456L,0L))
        assertFalse(LyricReplayRecovery.sameSong(123L,123L,456L))
        assertFalse(LyricReplayRecovery.sameSong(null,123L,0L))
    }
}
