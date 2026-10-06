package com.tcrrry.lyrics.engine.hook

import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(manifest = org.robolectric.annotation.Config.NONE, sdk = [35])
class NativeLyricsLineTimingTest {
    @org.junit.Before fun attachPlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.attach() }
    @org.junit.After fun closePlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.current.close() }
    @Test fun loveMeCrazyFastTailCompletesBeforeNextRowTakesFocus() {
        val policy = NativeLineTimingPolicy()
        policy.request(-500)
        assertEquals(-100, policy.document(true, -500))
        // QQ 107641673: native adaptive anticipation used to cut into the last
        // three syllables; the next line begins only 7ms after the final end.
        val words = listOf(150219 to 150400, 150400 to 150659, 150659 to 151043)
        val next = 151050
        val originalHandoff = next - 480
        assertTrue(originalHandoff < words.last().first)
        val handoff = next + policy.request(-480)
        // Native suggestWordOffset(-100) starts each unchanged-duration sweep early.
        words.forEach { (_, end) -> assertTrue(end - 100 <= handoff) }
        // The tail guard still covers the source end, including a delayed render frame.
        val tails = LyricWordTailState()
        val document = Any()
        tails.process(document, handoff.toLong())
        tails.update("word", mapOf(46 to words.last().second.toLong()))
        assertTrue(tails.protects(document, 46))
        tails.process(document, words.last().second.toLong())
        assertFalse(tails.protects(document, 46))
        assertEquals(next - 100, handoff)
    }
    @Test fun nativeAdaptiveAnticipationCannotUndoWordTimingDuringCallbacks() {
        val policy = NativeLineTimingPolicy()
        policy.document(true, -500)
        for (native in listOf(-480, -500, -750)) assertEquals(-100, policy.request(native))
        assertEquals(-100, policy.document(true, -100)) // same-source reload/repeat
        assertEquals(250, policy.request(250)) // never discard a positive delay
    }
    @Test fun switchingToLineLyricsRestoresTheMostRecentNativePreference() {
        val policy = NativeLineTimingPolicy()
        assertEquals(-500, policy.request(-500))
        policy.document(true, -500)
        assertEquals(-100, policy.request(-750))
        assertEquals(-750, policy.document(false, -100))
        assertEquals(-480, policy.request(-480))
        assertEquals(-480, policy.document(false, -480))
    }
    @Test fun nextFirstWordAndFocusArriveTogetherWithoutRestoringHalfSecondAnticipation() {
        val policy = NativeLineTimingPolicy()
        policy.document(true, -500)
        for (nextStart in listOf(151050, 152790, 157830, 159490)) {
            val nativeFirstWordSweep = nextStart - 100
            assertEquals(nativeFirstWordSweep, nextStart + policy.request(-480))
            assertEquals(100, nextStart - (nextStart + policy.request(-750)))
        }
    }
    @Test fun lineOnlyAndUntimedDocumentsKeepTheirNativePresentation() {
        val policy = NativeLineTimingPolicy()
        assertEquals(-500, policy.document(false, -500))
        assertEquals(-480, policy.request(-480))
        assertEquals(-480, policy.document(false, -480))
    }
}
