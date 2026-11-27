package dev.kifranei.ampp.media

import org.junit.Assert.*
import org.junit.Test

class AutoTransitionProgressBarMorphTest {
    @Test fun normalPlaybackFollowsSmoothlyWithoutFusionBoost() {
        val state = ProgressMorphState()
        val (f0, b0) = state.update(0.20f, 1000L, fusionActive = false, scrubbing = false)
        assertEquals(0.20f, f0, 0.001f)
        assertEquals(0f, b0, 0.001f)

        val (f1, b1) = state.update(0.24f, 1016L, fusionActive = false, scrubbing = false)
        assertTrue(f1 in 0.20f..0.24f)
        assertEquals(0f, b1, 0.001f)
    }

    @Test fun trackSwitchDropGlidesSmoothlyInsteadOfSnapping() {
        val state = ProgressMorphState()
        state.update(0.94f, 1000L, fusionActive = true, scrubbing = false)

        val (startF, startB) = state.update(0.04f, 1016L, fusionActive = true, scrubbing = false)
        assertEquals(0.94f, startF, 0.001f)
        assertEquals(0f, startB, 0.001f)

        val (midF, midB) = state.update(0.04f, 1441L, fusionActive = true, scrubbing = false)
        assertEquals(0.49f, midF, 0.02f)
        assertTrue(midB > 0.9f)

        val (endF, endB) = state.update(0.04f, 1900L, fusionActive = true, scrubbing = false)
        assertEquals(0.04f, endF, 0.001f)
        assertEquals(0f, endB, 0.001f)
    }

    @Test fun scrubbingCancelsMorphAndSnapsImmediately() {
        val state = ProgressMorphState()
        state.update(0.94f, 1000L, fusionActive = true, scrubbing = false)
        state.update(0.04f, 1016L, fusionActive = true, scrubbing = false)
        val (scrubF, scrubB) = state.update(0.55f, 1200L, fusionActive = true, scrubbing = true)
        assertEquals(0.55f, scrubF, 0.001f)
        assertEquals(0f, scrubB, 0.001f)
    }
}
