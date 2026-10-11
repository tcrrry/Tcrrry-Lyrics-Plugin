package com.tcrrry.lyrics.engine.hook

import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(manifest = org.robolectric.annotation.Config.NONE, sdk = [35])
class NativeTerminalGradientFixTest {
    @org.junit.Before fun attachPlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.attach() }
    @org.junit.After fun closePlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.current.close() }
    @Test fun shortFinalGlyphNeedsTheFullFeatherToFinishInsideItsOwnDuration() {
        val start = 0.6f
        val glyphEnd = 0.8f
        val feather = 0.12f
        val originalEnd = glyphEnd // native c0 returns zero for CJK
        assertTrue(originalEnd - feather < glyphEnd)
        val correctedEnd = glyphEnd + terminalGradientMargin(0f, feather)
        assertEquals(glyphEnd, correctedEnd - feather, 0.00001f)
        // The original linear sweep remains gradual, including a 183 ms final word.
        for (duration in listOf(183, 244, 356, 676)) {
            val midpoint = start + 0.5f * (correctedEnd - start)
            assertTrue(midpoint - feather < glyphEnd)
            assertEquals(glyphEnd, start + (duration.toFloat() / duration) * (correctedEnd - start) - feather, 0.00001f)
        }
    }
    @Test fun nativeOvershootAndInvalidGeometryAreLeftAlone() {
        assertEquals(0.2f, terminalGradientMargin(0.2f, 0.1f), 0f)
        for (feather in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, 2f)) {
            assertEquals(0.05f, terminalGradientMargin(0.05f, feather), 0f)
        }
    }
    @Test fun latinWordsAndEmphasizedReadingsCannotBorrowTheCjkTailMargin() {
        for(text in listOf("friends","childhood","ne","tto","watashi","shī","T")) {
            assertFalse(text,terminalGradientNeedsCompensation(text))
        }
        for(text in listOf("溶","け","で","光","你","寒くない")) {
            assertTrue(text,terminalGradientNeedsCompensation(text))
        }
    }
}
