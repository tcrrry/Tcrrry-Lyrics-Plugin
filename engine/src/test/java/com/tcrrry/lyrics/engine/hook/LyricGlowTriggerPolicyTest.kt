package com.tcrrry.lyrics.engine.hook

import com.tcrrry.lyrics.engine.model.LyricGlowPosition
import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(manifest = org.robolectric.annotation.Config.NONE, sdk = [35])
class LyricGlowTriggerPolicyTest {
    @org.junit.Before fun attachPlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.attach() }
    @org.junit.After fun closePlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.current.close() }
    @Test fun defaultAllowsLongSoundsInsideLineAsNativeDoes() {
        val policy = LyricGlowTriggerPolicy()
        assertFalse(policy.allows(999, false))
        assertTrue(policy.allows(1000, false))
        assertTrue(policy.allows(1000, true))
    }
    @Test fun sensitivityChangesThresholdWithoutChangingWordDuration() {
        assertEquals(2000, LyricGlowTriggerPolicy(50).threshold(1000, false))
        assertEquals(500, LyricGlowTriggerPolicy(200).threshold(1000, false))
        assertFalse(LyricGlowTriggerPolicy(200).allows(499, true))
        assertTrue(LyricGlowTriggerPolicy(200).allows(500, true))
        assertEquals(750, LyricGlowTriggerPolicy(200).threshold(1500, true))
        assertEquals(200, LyricGlowTriggerPolicy(500).threshold(1000, false))
        assertEquals(300, LyricGlowTriggerPolicy(500).threshold(1500, true))
        assertFalse(LyricGlowTriggerPolicy(500).allows(199, true))
        assertTrue(LyricGlowTriggerPolicy(500).allows(200, true))
        assertFalse(LyricGlowTriggerPolicy(500, LyricGlowPosition.TAIL_ONLY).allows(9000, false))
    }
    @Test fun tailOnlyRejectsEvenVeryLongSoundsInsideLine() {
        val policy = LyricGlowTriggerPolicy(200, LyricGlowPosition.TAIL_ONLY)
        assertFalse(policy.allows(9000, false))
        assertTrue(policy.allows(500, true))
    }
    @Test fun tailPreferredRaisesOnlyMidLineThreshold() {
        val policy = LyricGlowTriggerPolicy(100, LyricGlowPosition.TAIL_PREFERRED)
        assertFalse(policy.allows(1499, false))
        assertTrue(policy.allows(1500, false))
        assertTrue(policy.allows(1000, true))
    }
    @Test fun shortPromotedGlowUsesRealDurationAndOnlyScalesSpecialEnvelope() {
        assertEquals(600L, promotedGlowDuration(1000, 600))
        assertEquals(1200L, promotedGlowDuration(2000, 600))
        assertEquals(0L, promotedGlowDuration(0, 600))
        assertEquals(1000L, promotedGlowDuration(1000, 1000))
    }
    @Test fun punctuationAndHtmlTagsCannotClaimTailPosition() {
        assertFalse(isGlowTailText("<span>」!</span>"))
        assertFalse(isGlowTailText("&nbsp;"))
        assertTrue(isGlowTailText("<span>い</span>"))
        assertTrue(isGlowTailText("한"))
        assertTrue(isGlowTailText("night"))
    }
    class Word(private val id: Int, private val begin: Long, private val end: Long, private val text: String) {
        fun getWordId() = id; fun getBegin() = begin; fun getEnd() = end; fun getHtmlLineText() = text
    }
    class Ptr<T>(private val value: T) { fun get() = value }
    class Vector(private val words: List<Word>) {
        fun size() = words.size.toLong(); fun get(index: Long) = Ptr(words[index.toInt()])
    }
    class Line(private val words: Vector, private val pronunciation: Vector = Vector(emptyList())) {
        fun getWords() = words; fun getPronunciationWords() = pronunciation
    }
    class Table(private val line: Line) { fun a(row: Int) = Ptr(line) }
    class Adapter(@JvmField var p: Table)
    @Test fun nativeVectorsResolveOriginalAndPronunciationTailIgnoringZeroTokens() {
        val adapter = Adapter(Table(Line(Vector(listOf(Word(1, 10, 20, "あ"), Word(2, 20, 30, "い"),
            Word(3, 30, 40, "」"), Word(4, 40, 40, "う"))), Vector(listOf(Word(101, 10, 30, "a i"))))))
        assertEquals(setOf(2, 101), NativeGlowWordPosition().terminalIds(adapter, 0))
    }
    @Test fun changedNativeDocumentCannotReusePreviousTailIds() {
        val position = NativeGlowWordPosition()
        val adapter = Adapter(Table(Line(Vector(listOf(Word(1, 10, 20, "あ"))))))
        assertEquals(setOf(1), position.terminalIds(adapter, 0))
        adapter.p = Table(Line(Vector(listOf(Word(9, 10, 20, "い")))))
        assertEquals(setOf(9), position.terminalIds(adapter, 0))
    }
}
