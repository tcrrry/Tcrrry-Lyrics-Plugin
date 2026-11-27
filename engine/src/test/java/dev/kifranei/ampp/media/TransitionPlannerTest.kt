package dev.kifranei.ampp.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransitionPlannerTest {
    private fun song(
        bpm: Float = 120f,
        key: String = "C major",
        energy: Float = 0.6f,
        danceability: Float = 0.6f,
        endSilenceSec: Float = 0f,
        outroFadeStartMs: Float? = null,
        loudnessCurve: FloatArray? = null,
        barTimesSec: FloatArray? = null,
    ) = SongAudioAnalysis(
        bpm = bpm,
        key = key,
        energy = energy,
        danceability = danceability,
        acousticness = 0.2f,
        endSilenceSec = endSilenceSec,
        outroFadeStartMs = outroFadeStartMs,
        loudnessCurve = loudnessCurve,
        barTimesSec = barTimesSec,
    )

    @Test
    fun autoModeSkipsWhenMixabilityIsTooLow() {
        val current = song(bpm = 80f, key = "C major", energy = 0.1f, danceability = 0.2f)
        val next = song(bpm = 165f, key = "F# minor", energy = 0.95f, danceability = 0.9f)
        val plan = TransitionPlanner.plan(current, next, requestedDurationSec = 0)
        assertFalse(plan.enabled)
        assertEquals(0, plan.durationSec)
    }

    @Test
    fun autoModeSkipsOrShortensWhenLoudVocalsRunToEnd() {
        val loudness = FloatArray(100) { -10f }
        val bars = FloatArray(80) { i -> i * 2.5f }
        // Marginal/medium mixability (<0.55) with full-volume vocal ending -> 0s auto-skip.
        val currentMid = song(
            bpm = 96f,
            key = "C major",
            energy = 0.34f,
            danceability = 0.48f,
            loudnessCurve = loudness,
            barTimesSec = bars,
        )
        val nextMid = song(bpm = 89f, key = "G minor", energy = 0.45f, danceability = 0.63f)
        val baseMid = TransitionPlanner.plan(currentMid, nextMid, requestedDurationSec = 0)
        assertTrue(baseMid.mixability < 0.55f)
        val refinedMid = TransitionPlanner.refineAutoDurationWithTail(baseMid, currentMid, 200_000L, 0)
        assertFalse(refinedMid.enabled)
        assertEquals(0, refinedMid.durationSec)

        // High mixability (>=0.55) with full-volume ending -> capped at 1..2s kiss.
        val currentHigh = song(
            bpm = 120f,
            key = "C major",
            energy = 0.7f,
            danceability = 0.7f,
            loudnessCurve = loudness,
            barTimesSec = bars,
        )
        val nextHigh = song(bpm = 120f, key = "C major", energy = 0.7f, danceability = 0.7f)
        val baseHigh = TransitionPlanner.plan(currentHigh, nextHigh, requestedDurationSec = 0)
        assertTrue(baseHigh.mixability >= 0.72f)
        val refinedHigh = TransitionPlanner.refineAutoDurationWithTail(baseHigh, currentHigh, 200_000L, 0)
        assertTrue("Expected 1..2s kiss, got ${refinedHigh.durationSec}", refinedHigh.durationSec in 1..2)
    }

    @Test
    fun autoModeAllowsShortOverlapForMarginalMatch() {
        val current = song(bpm = 100f, key = "C major", energy = 0.4f, danceability = 0.5f)
        val next = song(bpm = 122f, key = "D minor", energy = 0.5f, danceability = 0.5f)
        val plan = TransitionPlanner.plan(current, next, requestedDurationSec = 0)
        assertTrue("Expected mixability in 0.28..0.40, got ${plan.mixability}", plan.mixability in 0.28f..0.40f)
        assertTrue("Expected short 1..3s auto overlap, got ${plan.durationSec}", plan.durationSec in 1..3)
    }

    @Test
    fun autoModeKeepsSmoothMediumOverlapForQuietTailCompatibleTracks() {
        val loudness = FloatArray(100) { idx -> if (idx >= 95) -45f else -10f }
        val bars = FloatArray(80) { i -> i * 2.5f }
        val current = song(
            bpm = 120f,
            key = "C major",
            energy = 0.65f,
            danceability = 0.75f,
            endSilenceSec = 4f,
            loudnessCurve = loudness,
            barTimesSec = bars,
        )
        val next = song(bpm = 122f, key = "G major", energy = 0.65f, danceability = 0.72f)
        val base = TransitionPlanner.plan(current, next, requestedDurationSec = 0)
        val refined = TransitionPlanner.refineAutoDurationWithTail(base, current, 200_000L, 0)
        assertTrue(refined.enabled)
        assertTrue("Expected 4..10s overlap, got ${refined.durationSec}", refined.durationSec in 4..10)
    }

    @Test
    fun manualFixedDurationIgnoresAutoSkipAndTailShortening() {
        val loudness = FloatArray(100) { -10f }
        val current = song(bpm = 80f, key = "C major", energy = 0.1f, danceability = 0.2f, loudnessCurve = loudness)
        val next = song(bpm = 165f, key = "F# minor", energy = 0.95f, danceability = 0.9f)
        val plan = TransitionPlanner.plan(current, next, requestedDurationSec = 6)
        val refined = TransitionPlanner.refineAutoDurationWithTail(plan, current, 200_000L, requestedDurationSec = 6)
        assertTrue(refined.enabled)
        assertEquals(6, refined.durationSec)
    }

    @Test
    fun fadeCurveHoldsCompletionGateUntilRendererReachesFullSilenceAndUnity() {
        val mid = AutoMixFadeCurve.computeStep(3_500L, 7_000L)
        assertEquals(0.5f, mid.inVol, 0.01f)
        assertEquals(0.5f, mid.outVol, 0.01f)
        assertEquals(0.5f, mid.reportedIn, 0.01f)
        assertEquals(0.5f, mid.reportedOut, 0.01f)

        // At 86% (~6020ms of 7000ms), cos²/sin² has passed 0.95 / 0.05; reported levels must still hold COMPLETED gate.
        val late = AutoMixFadeCurve.computeStep(6_020L, 7_000L)
        assertTrue(late.inVol > 0.95f)
        assertTrue(late.outVol < 0.05f)
        assertTrue(late.reportedIn <= 0.945f)
        assertTrue(late.reportedOut >= 0.055f)

        // At 99%+, both real and reported values reach exact endpoints 1.0 / 0.0 so COMPLETED & reset happen smoothly.
        val done = AutoMixFadeCurve.computeStep(6_930L, 7_000L)
        assertEquals(1f, done.inVol, 0f)
        assertEquals(0f, done.outVol, 0f)
        assertEquals(1f, done.reportedIn, 0f)
        assertEquals(0f, done.reportedOut, 0f)
    }
}
