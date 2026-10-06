package com.tcrrry.lyrics.engine.hook

internal object NativeLyricsPreferencePolicy {
    fun preferNative(qualityFirst: Boolean, selectedSource: String?, nativeTtml: String? = null): Boolean =
        selectedSource == LyricsSourceMenuPolicy.NATIVE || (selectedSource == null &&
            (qualityFirst || nativeTtml?.let(TtmlTimingPolicy::hasTimedWords) == true))
    /** Only automatic word-first matching can fall back to the original line document. */
    fun useNativeLineFallback(selectedSource: String?, nativeTtml: String?, candidateTtml: String?): Boolean =
        selectedSource == null && nativeTtml != null && hasContent(nativeTtml) &&
            (candidateTtml == null || !TtmlTimingPolicy.hasTimedWords(candidateTtml))
    private val paragraph = Regex("(?is)<p\\b[^>]*>(.*?)</p\\s*>")
    private val markup = Regex("<[^>]+>")
    fun hasContent(ttml: String): Boolean = paragraph.findAll(ttml).any {
        markup.replace(it.groupValues[1], "").any(Char::isLetterOrDigit)
    }
}
