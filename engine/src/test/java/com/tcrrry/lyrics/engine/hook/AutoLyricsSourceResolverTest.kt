package com.tcrrry.lyrics.engine.hook

import com.tcrrry.lyrics.engine.lyrics.source.AutoLyricsSourceResolver
import com.tcrrry.lyrics.engine.lyrics.source.AutoLyricsSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(manifest = org.robolectric.annotation.Config.NONE, sdk = [35])
class AutoLyricsSourceResolverTest {
    @org.junit.Before fun attachPlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.attach() }
    @org.junit.After fun closePlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.current.close() }
    @Test
    fun `resolver accepts an AMLL line-timed lyric before later sources`() {
        val calls = mutableListOf<String>()
        val resolver = AutoLyricsSourceResolver(
            listOf(
                AutoLyricsSource("amll") {
                    calls += "amll"
                    LINE_TTML
                },
                AutoLyricsSource("lunabeat") {
                    calls += "lunabeat"
                    LINE_TTML
                },
                AutoLyricsSource("my-repository") {
                    calls += "my-repository"
                    WORD_TTML
                },
                AutoLyricsSource("later") {
                    calls += "later"
                    LINE_TTML
                },
            ),
        )

        assertEquals(AutoLyricsCandidate("amll", LINE_TTML), resolver.fetch(42L))
        assertEquals(listOf("amll"), calls)
    }

    @Test
    fun `resolver fails open for invalid ids`() {
        var called = false
        val resolver = AutoLyricsSourceResolver(
            listOf(AutoLyricsSource("source") {
                called = true
                WORD_TTML
            }),
        )

        assertNull(resolver.fetch(0L))
        assertEquals(false, called)
    }

    @Test fun qualityOrderIsAuthorThenThirdPartyAndManualSelectionWins() {
        val calls = mutableListOf<String>()
        val resolver = AutoLyricsSourceResolver(
            listOf(AutoLyricsSource(com.tcrrry.lyrics.engine.model.CustomLyricsSources.AM_LYRICS) { calls += "author"; LINE_TTML }),
            desktopLyrics = { calls += "third-party"; AutoLyricsCandidate("desktop", WORD_TTML) },
            qualityFirst = { true })
        val track = DesktopLyricsTrack("song", "artist", appleMusicId = 42L)
        assertEquals(com.tcrrry.lyrics.engine.model.CustomLyricsSources.AM_LYRICS, resolver.fetch(42L, track)?.source)
        assertEquals(listOf("author"), calls)
        calls.clear()
        assertEquals("desktop", resolver.fetch(42L, track.copy(explicitSource = true))?.source)
        assertEquals(listOf("third-party"), calls)
    }
    @Test fun qualityFallsBackWithoutDiscardingLineTimedThirdParty() {
        val resolver = AutoLyricsSourceResolver(listOf(AutoLyricsSource("author") { null }),
            desktopLyrics = { AutoLyricsCandidate("desktop", LINE_TTML) }, qualityFirst = { true })
        assertEquals("desktop", resolver.fetch(42L, DesktopLyricsTrack("song", "artist"))?.source)
    }
    @Test fun explicitlySelectedAuthorDoesNotSearchThirdPartyOrSilentlyFallBackToOtherLibraries() {
        var thirdParty = 0
        var other = 0
        var author = WORD_TTML as String?
        val resolver = AutoLyricsSourceResolver(listOf(
            AutoLyricsSource("other") { other++; WORD_TTML },
            AutoLyricsSource("am-lyrics") { author }),
            desktopLyrics = { thirdParty++; AutoLyricsCandidate("desktop", WORD_TTML) })
        val track = DesktopLyricsTrack("song", "artist", appleMusicId = 42L, explicitSource = true)
        assertEquals("am-lyrics", resolver.fetch(42L, track, "am-lyrics")?.source)
        author = null
        assertNull(resolver.fetch(42L, track, "am-lyrics"))
        assertEquals(0, thirdParty)
        assertEquals(0, other)
    }

    @Test fun wordPreferenceUsesAuthorWordLyricsBeforeRichThirdParty() {
        val calls = mutableListOf<String>()
        val resolver = AutoLyricsSourceResolver(listOf(AutoLyricsSource("am-lyrics") { calls += "author"; WORD_TTML }),
            desktopLyrics = { calls += "third-party"; AutoLyricsCandidate("desktop", WORD_TTML) })
        val track = DesktopLyricsTrack("song", "artist")
        assertEquals("am-lyrics", resolver.fetch(42L, track)?.source)
        assertEquals(listOf("author"), calls)
        calls.clear()
        assertEquals("desktop", resolver.fetch(42L, track.copy(explicitSource = true))?.source)
        assertEquals(listOf("third-party"), calls)
    }
    @Test fun wordPreferenceSkipsAuthorLineLyricsWithoutFetchingAuthorTwice() {
        var calls = 0
        var desktop = AutoLyricsCandidate("desktop", WORD_TTML) as AutoLyricsCandidate?
        val resolver = AutoLyricsSourceResolver(listOf(AutoLyricsSource("am-lyrics") { calls++; LINE_TTML }),
            desktopLyrics = { desktop })
        val track = DesktopLyricsTrack("song", "artist")
        assertEquals("desktop", resolver.fetch(42L, track)?.source)
        assertEquals(1, calls)
        desktop = null
        assertEquals("am-lyrics", resolver.fetch(42L, track)?.source)
        assertEquals(2, calls)
    }
    @Test fun unavailableAuthorStillAllowsThirdPartyWordLyrics() {
        val resolver = AutoLyricsSourceResolver(listOf(AutoLyricsSource("am-lyrics") { null }),
            desktopLyrics = { AutoLyricsCandidate("desktop", WORD_TTML) })
        assertEquals("desktop", resolver.fetch(42L, DesktopLyricsTrack("song", "artist"))?.source)
    }

    @Test fun noWordSourceFallsBackToAuthorBeforePlatformLinesAndFetchesEachOnce() {
        var authorCalls = 0
        var platformCalls = 0
        val resolver = AutoLyricsSourceResolver(listOf(AutoLyricsSource("am-lyrics") { authorCalls++; LINE_TTML }),
            desktopLyrics = { platformCalls++; AutoLyricsCandidate("desktop", LINE_TTML) })
        assertEquals("am-lyrics", resolver.fetch(42L, DesktopLyricsTrack("song", "artist"))?.source)
        assertEquals(1, authorCalls)
        assertEquals(1, platformCalls)
    }
    @Test fun wordLibraryIsNotHiddenByEarlierThirdPartyLineResult() {
        val resolver = AutoLyricsSourceResolver(listOf(
            AutoLyricsSource("am-lyrics") { LINE_TTML }, AutoLyricsSource("amll") { WORD_TTML }),
            desktopLyrics = { AutoLyricsCandidate("desktop", LINE_TTML) })
        assertEquals("amll", resolver.fetch(42L, DesktopLyricsTrack("song", "artist"))?.source)
    }
    @Test fun platformLineIsFallbackWhenNativeAndAuthorUnavailable() {
        val resolver = AutoLyricsSourceResolver(listOf(AutoLyricsSource("am-lyrics") { null }),
            desktopLyrics = { AutoLyricsCandidate("desktop", LINE_TTML) })
        assertEquals("desktop", resolver.fetch(42L, DesktopLyricsTrack("song", "artist"))?.source)
    }
    @Test fun explicitPlatformKeepsLineResultWithoutSwitchingToAuthorWord() {
        var authorCalls = 0
        val resolver = AutoLyricsSourceResolver(listOf(AutoLyricsSource("am-lyrics") { authorCalls++; WORD_TTML }),
            desktopLyrics = { AutoLyricsCandidate("desktop", LINE_TTML) })
        assertEquals("desktop", resolver.fetch(42L, DesktopLyricsTrack("song", "artist", explicitSource = true))?.source)
        assertEquals(0, authorCalls)
    }
    @Test fun fallbackAuthorStillReceivesTranslationEnrichment() {
        val resolver = AutoLyricsSourceResolver(listOf(AutoLyricsSource("am-lyrics") { LINE_TTML }),
            desktopLyrics = { AutoLyricsCandidate("desktop", LINE_TTML) },
            fallbackTranslation = { _, candidate -> candidate.copy(displayName = "enriched") })
        assertEquals("enriched", resolver.fetch(42L, DesktopLyricsTrack("song", "artist"))?.displayName)
    }

    private companion object {
        const val WORD_TTML =
            "<tt xmlns:itunes=\"urn\" itunes:timing=\"Word\"><body>" +
                "<p><span begin=\"0s\" end=\"1s\">hello</span></p>" +
                "</body></tt>"
        const val LINE_TTML =
            "<tt xmlns:itunes=\"urn\" itunes:timing=\"Line\"><body>" +
                "<p>hello</p></body></tt>"
    }
}
