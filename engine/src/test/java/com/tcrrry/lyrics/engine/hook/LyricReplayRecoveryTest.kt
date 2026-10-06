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
}
