package com.tcrrry.lyrics.engine.hook
import org.junit.Assert.*
import org.junit.Test
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(manifest = org.robolectric.annotation.Config.NONE, sdk = [35])
class NativeLyricsPreferencePolicyTest {
    @org.junit.Before fun attachPlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.attach() }
    @org.junit.After fun closePlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.current.close() }
    @Test fun explicitNativeOverridesWordPriorityButExplicitAuthorOverridesQualityPriority() {
        assertTrue(NativeLyricsPreferencePolicy.preferNative(false, LyricsSourceMenuPolicy.NATIVE))
        assertTrue(NativeLyricsPreferencePolicy.preferNative(true, null))
        assertFalse(NativeLyricsPreferencePolicy.preferNative(true, LyricsSourceMenuPolicy.AUTHOR))
        assertFalse(NativeLyricsPreferencePolicy.preferNative(true, "QQ音乐"))
        assertFalse(NativeLyricsPreferencePolicy.preferNative(false, null))
    }
    @Test fun emptyTimedSpansAndMetadataAreNotNativeLyrics() {
        assertFalse(NativeLyricsPreferencePolicy.hasContent("<tt><head>title</head><body><p><span begin=\"1s\" end=\"2s\"> </span></p></body></tt>"))
        assertTrue(NativeLyricsPreferencePolicy.hasContent("<tt><body><p><span begin=\"1s\">君</span></p></body></tt>"))
    }
    @Test fun wordPreferenceUsesNativeTimedWordsButNotLineLyricsOrAnEmptyWordDeclaration() {
        val word = """<tt itunes:timing="Word"><body><p><span begin="1s" end="2s">君</span></p></body></tt>"""
        assertTrue(NativeLyricsPreferencePolicy.preferNative(false, null, word))
        assertFalse(NativeLyricsPreferencePolicy.preferNative(false, null, word.replace("Word", "Line")))
        assertFalse(NativeLyricsPreferencePolicy.preferNative(false, null, """<tt itunes:timing="Word"><body><p>君</p></body></tt>"""))
        assertFalse(NativeLyricsPreferencePolicy.preferNative(false, "QQ音乐", word))
        assertFalse(NativeLyricsPreferencePolicy.preferNative(false, LyricsSourceMenuPolicy.AUTHOR, word))
    }
    @Test fun headOnlyAndBlankWordSpansDoNotClaimNativeWordPriority() {
        assertFalse(TtmlTimingPolicy.hasTimedWords("""<tt itunes:timing="Word"><head><p><span begin="1s" end="2s">title</span></p></head><body><p>君</p></body></tt>"""))
        assertFalse(TtmlTimingPolicy.hasTimedWords("""<tt itunes:timing="Word"><body><p><span begin="1s" end="2s"> </span></p></body></tt>"""))
    }
    @Test fun automaticLineFallbackPrefersNativeToAnyLineSourceOrNoResult() {
        val line = "<tt><body><p>君</p></body></tt>"
        assertTrue(NativeLyricsPreferencePolicy.useNativeLineFallback(null, line, line))
        assertTrue(NativeLyricsPreferencePolicy.useNativeLineFallback(null, line, null))
        val word = """<tt itunes:timing="Word"><body><p><span begin="1s" end="2s">君</span></p></body></tt>"""
        assertFalse(NativeLyricsPreferencePolicy.useNativeLineFallback(null, line, word))
        assertFalse(NativeLyricsPreferencePolicy.useNativeLineFallback(null, "<tt><body/></tt>", line))
    }
    @Test fun nativeFallbackNeverOverridesExplicitlySelectedPlatformOrAuthor() {
        val line = "<tt><body><p>君</p></body></tt>"
        assertFalse(NativeLyricsPreferencePolicy.useNativeLineFallback("QQ音乐", line, null))
        assertFalse(NativeLyricsPreferencePolicy.useNativeLineFallback(LyricsSourceMenuPolicy.AUTHOR, line, line))
    }

}
