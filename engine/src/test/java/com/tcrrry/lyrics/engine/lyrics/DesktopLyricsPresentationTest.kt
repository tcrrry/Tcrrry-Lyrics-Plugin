package com.tcrrry.lyrics.engine.lyrics

import com.tcrrry.lyrics.sources.DirectLyricsRepository
import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(manifest = org.robolectric.annotation.Config.NONE, sdk = [35])
class DesktopLyricsPresentationTest {
    @org.junit.Before fun attachPlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.attach() }
    @org.junit.After fun closePlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.current.close() }
    private val platform = DirectLyricsRepository.Result(
        lyrics = "[00:01]Hello\n[00:03]Goodbye", translatedLyrics = "[00:01]你好\n[00:03]再见",
        source = "QQ音乐", recordId = "same-song",
    )
    private fun presentation(result: DirectLyricsRepository.Result) = requireNotNull(
        DesktopLyricsPresentation.fromTtml(requireNotNull(DesktopLyricsTtmlConverter.convert(result)))
    )

    @Test fun lineLyricsAreNotLabeledWordTimedJustBecauseAppleRequiresWordMode() {
        assertFalse(presentation(platform).wordTimed)
        assertTrue(presentation(platform).detail().startsWith("逐行"))
    }

    @Test fun realWordTimestampsEnableTheWordLabel() {
        val words = platform.copy(wordLyrics = "[1000,1000](1000,400)Hel(1400,600)lo")
        assertTrue(presentation(words).wordTimed)
        assertTrue(presentation(words).detail().startsWith("逐字"))
    }

    @Test fun malformedWordPayloadDoesNotClaimWordTiming() {
        assertFalse(presentation(platform.copy(wordLyrics = "not actually word lyrics")).wordTimed)
    }

    @Test fun platformTranslationNeverInheritsAPIMetadataFromEarlierLyrics() {
        val generated = platform.copy(supplementalTranslationKind = "api",
            supplementalTranslationStarts = setOf(1000L, 3000L))
        assertTrue(presentation(generated).apiTranslation)
        val actual = presentation(platform)
        assertTrue(actual.platformTranslation)
        assertFalse(actual.apiTranslation)
        assertFalse(actual.offlineTranslation)
        assertEquals("逐行 · 自带译文", actual.detail())
    }

    @Test fun mixedPlatformAndGeneratedLinesKeepBothOrigins() {
        val actual = presentation(platform.copy(supplementalTranslationKind = "offline",
            supplementalTranslationStarts = setOf(3000L)))
        assertTrue(actual.platformTranslation)
        assertTrue(actual.offlineTranslation)
        assertFalse(actual.apiTranslation)
    }

    @Test fun discardedAndUnalignedTranslationsCannotClaimAPISupplement() {
        val unaligned = platform.copy(translatedLyrics = "[01:00]晚到的译文",
            supplementalTranslationKind = "api", supplementalTranslationStarts = setOf(60_000L))
        assertEquals("逐行", presentation(unaligned).detail())
        val chinese = unaligned.copy(lyrics = "[00:01]西厢寻他", translatedLyrics = "[00:01]寻找他",
            supplementalTranslationStarts = setOf(1000L))
        assertFalse(presentation(chinese).apiTranslation)
    }

    @Test fun cachedPayloadKeepsSourceAndProvenanceWithoutRememberedUIStatus() {
        val original = presentation(platform)
        val cached = requireNotNull(DesktopLyricsTtmlConverter.convert(platform))
        assertEquals(original, DesktopLyricsPresentation.fromTtml(cached))
        assertEquals("QQ音乐", original.source)
        assertNull(DesktopLyricsPresentation.fromTtml("<tt><body/></tt>"))
    }
}
