package com.tcrrry.lyrics.engine.hook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(manifest = org.robolectric.annotation.Config.NONE, sdk = [35])
class AutoLyricsPolicyTest {
    @org.junit.Before fun attachPlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.attach() }
    @org.junit.After fun closePlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.current.close() }
    @Test
    fun `null native pointer is eligible for automatic lookup`() {
        assertTrue(shouldTryAutoLyrics(null, null))
    }

    @Test
    fun `only an observed non Word pointer is eligible`() {
        val pointer = Any()
        assertTrue(
            shouldTryAutoLyrics(
                pointer,
                TtmlDocumentMetadata(TtmlTimingMode.NON_WORD, language = "zh", hasTranslation = false),
            ),
        )
        assertFalse(
            shouldTryAutoLyrics(
                pointer,
                TtmlDocumentMetadata(TtmlTimingMode.WORD, language = "zh", hasTranslation = false),
            ),
        )
        assertFalse(shouldTryAutoLyrics(pointer, null))
    }

    @Test
    fun `foreign non Word lyric is eligible even without translation`() {
        assertTrue(
            shouldTryAutoLyrics(
                Any(),
                TtmlDocumentMetadata(TtmlTimingMode.NON_WORD, language = "de", hasTranslation = false),
            ),
        )
    }

    @Test
    fun `foreign Word lyric without translation is eligible`() {
        assertTrue(
            shouldTryAutoLyrics(
                Any(),
                TtmlDocumentMetadata(TtmlTimingMode.WORD, language = "en-US", hasTranslation = false),
            ),
        )
    }

    @Test
    fun `foreign Word lyric with translation is not eligible`() {
        assertFalse(
            shouldTryAutoLyrics(
                Any(),
                TtmlDocumentMetadata(TtmlTimingMode.WORD, language = "ja", hasTranslation = true),
            ),
        )
    }

    @Test
    fun `ready manual replacement suppresses automatic preparation`() {
        assertFalse(shouldPrepareAutomaticLyrics(Any(), autoEligible = true))
        assertTrue(shouldPrepareAutomaticLyrics(null, autoEligible = true))
        assertFalse(shouldPrepareAutomaticLyrics(null, autoEligible = false))
    }
}
