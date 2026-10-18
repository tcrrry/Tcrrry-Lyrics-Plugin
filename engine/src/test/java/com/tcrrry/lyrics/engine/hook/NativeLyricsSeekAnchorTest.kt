package com.tcrrry.lyrics.engine.hook

import org.junit.Assert.*
import org.junit.Test

class NativeLyricsSeekAnchorTest {
    @Test fun introInterludeActiveLineAndOutroUseNativeLineTimes() {
        val rows = listOf(NativeLyricsSeekAnchor.Line(0, 1000, 5000), NativeLyricsSeekAnchor.Line(1, 10000, 15000))
        assertEquals(0, NativeLyricsSeekAnchor.select(rows, 0))
        assertEquals(0, NativeLyricsSeekAnchor.select(rows, 2000))
        assertEquals(1, NativeLyricsSeekAnchor.select(rows, 7000))
        assertEquals(1, NativeLyricsSeekAnchor.select(rows, 10000))
        assertEquals(1, NativeLyricsSeekAnchor.select(rows, 20000))
        assertNull(NativeLyricsSeekAnchor.select(emptyList(), 2000))
    }
}
