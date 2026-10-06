package com.tcrrry.lyrics.engine.hook
import org.junit.Assert.*
import org.junit.Test
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(manifest = org.robolectric.annotation.Config.NONE, sdk = [35])
class LyricWordTailStateTest {
    @org.junit.Before fun attachPlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.attach() }
    @org.junit.After fun closePlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.current.close() }
    @Test fun earlyNextLineCannotCancelThePreviousRealWordUntilItsEnd() {
        val state = LyricWordTailState()
        val doc = Any()
        state.process(doc, 1000)
        state.update("word", mapOf(2 to 2500L))
        state.process(doc, 2200)
        assertTrue(state.protects(doc, 2))
        state.process(doc, 2499)
        assertTrue(state.protects(doc, 2))
        state.process(doc, 2500)
        assertFalse(state.protects(doc, 2))
    }
    @Test fun overlappingWordsAndGlowKeepTheirOwnEndTimesWithoutExtendingOtherRows() {
        val state = LyricWordTailState()
        val doc = Any()
        state.process(doc, 1000)
        state.update("word", mapOf(2 to 3000L))
        state.process(doc, 2200)
        state.update("word", mapOf(3 to 4000L))
        state.update("pronunciation", mapOf(2 to 3500L))
        assertTrue(state.protects(doc, 2))
        assertTrue(state.protects(doc, 3))
        state.process(doc, 3500)
        assertFalse(state.protects(doc, 2))
        assertTrue(state.protects(doc, 3))
        assertFalse(state.protects(doc, 4))
    }
    @Test fun replaySourceSwitchAndMissingTimingNeverProtectStaleAnimations() {
        val state = LyricWordTailState()
        val doc = Any()
        state.process(doc, null)
        state.update("word", mapOf(2 to 3000L))
        assertFalse(state.protects(doc, 2))
        state.process(doc, 2000)
        state.update("word", mapOf(2 to 3000L, -1 to 4000L, 3 to 1900L))
        assertFalse(state.protects(doc, -1))
        assertFalse(state.protects(doc, 3))
        assertFalse(state.protects(Any(), 2))
        state.process(doc, 0)
        assertFalse(state.protects(doc, 2))
        state.update("word", mapOf(2 to 3000L))
        state.process(Any(), 100)
        assertFalse(state.protects(doc, 2))
    }
}
