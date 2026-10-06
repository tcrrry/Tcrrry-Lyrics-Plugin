package com.tcrrry.lyrics.engine.hook

import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(manifest = org.robolectric.annotation.Config.NONE, sdk = [35])
class NativeWordTailTimingTest {
    @org.junit.Before fun attachPlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.attach() }
    @org.junit.After fun closePlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.current.close() }
    class Word(private val begin: Int, private val end: Int) {
        fun getBegin() = begin
        fun getEnd() = end
    }
    class Pointer(private val word: Word?) { fun get() = word }
    class Vector(private vararg val words: Word?) {
        fun size() = words.size.toLong()
        fun get(index: Long) = Pointer(words[index.toInt()])
    }

    @Test fun letMeKnowTailUsesUpcomingWordsAndIgnoresTheZeroDurationMarker() {
        // NetEase records 1416359819 and 1453985009, user-reported row at 3:25.490.
        val vector = Vector(Word(208110, 208420), Word(208420, 208420),
            Word(208420, 208600), Word(208600, 209900))
        assertEquals(209900L, nativeLastWordEnd(vector))
        val state = LyricWordTailState()
        val doc = Any()
        state.process(doc, 208100)
        state.update("word", mapOf(1 to nativeLastWordEnd(vector)!!))
        state.process(doc, 208420)
        state.update("word", emptyMap())
        state.process(doc, 209420) // Native line exit can precede the final word end.
        assertTrue(state.protects(doc, 1))
        assertEquals(setOf(1), state.protectedRows())
        state.process(doc, 209899)
        assertTrue(state.protects(doc, 1))
        state.process(doc, 209900)
        assertFalse(state.protects(doc, 1))
        assertTrue(state.protectedRows().isEmpty())
    }

    @Test fun rapidTailWordsStayProtectedAcrossEmptyCallbacksWithoutExtendingAnotherRow() {
        val vector = Vector(Word(1000, 1130), Word(1130, 1250), Word(1250, 1390))
        val state = LyricWordTailState()
        val doc = Any()
        state.process(doc, 1000)
        state.update("word", mapOf(8 to nativeLastWordEnd(vector)!!))
        state.process(doc, 1150)
        state.update("word", emptyMap())
        state.process(doc, 1320)
        assertTrue(state.protects(doc, 8))
        assertFalse(state.protects(doc, 9))
        state.process(doc, 1390)
        assertFalse(state.protects(doc, 8))
    }

    @Test fun loveMeCrazyNextLineCanArriveBeforeTheLastWordEvenStarts() {
        // QQ record 107641673: consecutive rows end/start only seven milliseconds apart.
        val vector = Vector(Word(150219, 150400), Word(150400, 150659), Word(150659, 151043))
        val state = LyricWordTailState()
        val doc = Any()
        state.process(doc, 150219)
        state.update("word", mapOf(10 to nativeLastWordEnd(vector)!!))
        state.process(doc, 150570) // Next row at 151050 with native 480ms anticipation.
        state.update("word", mapOf(10 to 150659L))
        assertTrue(state.protects(doc, 10))
        state.process(doc, 150660)
        assertTrue(state.protects(doc, 10))
        state.process(doc, 151042)
        assertTrue(state.protects(doc, 10))
        state.process(doc, 151043)
        assertFalse(state.protects(doc, 10))
    }

    @Test fun missingOrUniformLineTimingCannotInventAWordTail() {
        assertNull(nativeLastWordEnd(Vector(Word(1000, 4000))))
        assertNull(nativeLastWordEnd(Vector(Word(1000, 4000), Word(1000, 4000))))
        assertNull(nativeLastWordEnd(Vector(null, Word(-1, 4000), Word(1000, 1000))))
        assertNull(nativeLastWordEnd(Any()))
    }

    @Test fun pausePreservesTheRealTailButReplayAndSourceReplacementInvalidateIt() {
        val state = LyricWordTailState()
        val doc = Any()
        state.process(doc, 2000)
        state.update("word", mapOf(3 to 2500L))
        repeat(3) { state.process(doc, 2000) }
        assertTrue(state.protects(doc, 3))
        state.process(doc, 0)
        assertFalse(state.protects(doc, 3))
        state.update("word", mapOf(3 to 2500L))
        val replacement = Any()
        state.process(replacement, 2000)
        assertFalse(state.owns(doc))
        assertTrue(state.owns(replacement))
        assertFalse(state.protects(replacement, 3))
        assertTrue(state.protectedRows().isEmpty())
    }
}
