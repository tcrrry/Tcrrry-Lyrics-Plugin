package com.tcrrry.lyrics.engine.hook

internal object NativeLyricsPreferencePolicy {
    fun preferNative(qualityFirst: Boolean, selectedSource: String?, nativeTtml: String? = null): Boolean =
        selectedSource == LyricsSourceMenuPolicy.NATIVE || (selectedSource == null &&
            (qualityFirst || nativeTtml?.let(TtmlTimingPolicy::hasTimedWords) == true))
    private val paragraph = Regex("(?is)<p\\b[^>]*>(.*?)</p\\s*>")
    private val markup = Regex("<[^>]+>")
    fun hasContent(ttml: String): Boolean = paragraph.findAll(ttml).any {
        markup.replace(it.groupValues[1], "").any(Char::isLetterOrDigit)
    }
}
