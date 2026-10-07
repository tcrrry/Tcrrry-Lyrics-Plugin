package com.tcrrry.lyrics.engine.hook

import org.junit.Assert.*
import org.junit.Test

class NativeAuxiliaryBindingStateTest {
    @Test fun ordinaryBindingsKeepWordMapsButSongAndVisibilityChangesRebuild() {
        val state = NativeAuxiliaryBindingState()
        val holder = Any()
        val first = NativeAuxiliaryBindingState.Identity(Any(), 1, true, true)
        assertTrue(state.changed(holder, first))
        repeat(4) { assertFalse(state.changed(holder, first.copy())) }
        assertTrue(state.changed(holder, first.copy(pronunciation = false)))
        assertTrue(state.changed(holder, first.copy(translation = false)))
        assertTrue(state.changed(holder, first.copy(lineId = 2)))
        assertTrue(state.changed(holder, first.copy(pointer = Any())))
        state.clear(holder)
        assertTrue(state.changed(holder, first))
    }
}
