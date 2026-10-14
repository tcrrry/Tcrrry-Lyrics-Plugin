package com.tcrrry.lyrics.engine.ui

import org.junit.Assert.*
import org.junit.Test

class SourceTickerMotionTest {
    @Test fun contentAndRepeatedLayoutUpdatesDoNotResetThePosition() {
        val motion = SourceTickerMotion(30f)
        motion.geometry(600f, 160f, 48f)
        motion.frame(0)
        motion.frame(16_000_000)
        assertEquals(.48f, motion.positionPx, .0001f)
        motion.geometry(600f, 160f, 48f)
        motion.geometry(640f, 160f, 48f)
        assertEquals(.48f, motion.positionPx, .0001f)
        motion.frame(32_000_000)
        assertEquals(.96f, motion.positionPx, .0001f)
    }

    @Test fun hiddenPagesPreservePhaseWithoutJumpingOverElapsedBackgroundTime() {
        val motion = SourceTickerMotion(30f)
        motion.geometry(600f, 160f, 48f)
        motion.frame(0)
        motion.frame(16_000_000)
        motion.suspendClock()
        motion.frame(10_000_000_000)
        assertEquals(.48f, motion.positionPx, .0001f)
        motion.frame(10_016_000_000)
        assertEquals(.96f, motion.positionPx, .0001f)
    }

    @Test fun loopAlwaysMovesForwardAndFittingTextStaysStill() {
        val motion = SourceTickerMotion(30f)
        motion.geometry(200f, 160f, 48f)
        motion.frame(0)
        for (i in 1L..200) motion.frame(i * 50_000_000)
        assertEquals(52f, motion.positionPx, .001f)
        motion.geometry(100f, 160f, 48f)
        assertFalse(motion.overflow)
        assertEquals(0f, motion.positionPx, 0f)
    }
}
