package com.tcrrry.lyrics.engine.hook

import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(manifest = org.robolectric.annotation.Config.NONE, sdk = [35])
class AppliedLyricsStatusPolicyTest {
    @org.junit.Before fun attachPlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.attach() }
    @org.junit.After fun closePlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.current.close() }
    @Test fun metadataArrivingAfterInstallationCanReconcileTheSamePointer() {
        val ready = Any()
        assertTrue(shouldReportAppliedLyrics(100L, 100L, ready, ready))
    }
    @Test fun aPreparedCandidateCannotMisreportTheStillNativePage() {
        assertFalse(shouldReportAppliedLyrics(100L, 100L, Any(), Any()))
    }
    @Test fun aPageFromThePreviousSongCannotReplaceTheCurrentStatus() {
        val ready = Any()
        assertFalse(shouldReportAppliedLyrics(101L, 100L, ready, ready))
    }
    @Test fun absentAndInvalidPointersNeverCountAsApplied() {
        assertFalse(shouldReportAppliedLyrics(100L, 100L, null, null))
        assertFalse(shouldReportAppliedLyrics(0L, 0L, Any(), Any()))
        assertFalse(shouldReportAppliedLyrics(100L, null, Any(), Any()))
    }
}
