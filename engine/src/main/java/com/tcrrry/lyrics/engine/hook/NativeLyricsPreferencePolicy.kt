package com.tcrrry.lyrics.engine.hook

import com.tcrrry.lyrics.engine.model.CustomLyricsSources

internal object NativeLyricsPreferencePolicy {
    fun refreshForNativeDocument(preferNative: Boolean, selectedSource: String?, preparedTiming: TtmlTimingMode?, preparing: Boolean): Boolean =
        preferNative || (selectedSource == null && !preparing && preparedTiming != TtmlTimingMode.WORD)
    fun preferNative(qualityFirst: Boolean, selectedSource: String?, nativeTtml: String? = null,
        hasCustomLyrics: Boolean = false): Boolean =
        selectedSource == LyricsSourceMenuPolicy.NATIVE || (selectedSource == null && !hasCustomLyrics &&
            (qualityFirst || nativeTtml?.let(TtmlTimingPolicy::hasTimedWords) == true))
    /** Only automatic word-first matching can fall back to the original line document. */
    fun useNativeLineFallback(selectedSource: String?, nativeTtml: String?, candidateTtml: String?,
        hasCustomLyrics: Boolean = false): Boolean =
        selectedSource == null && !hasCustomLyrics && nativeTtml != null && hasContent(nativeTtml) &&
            (candidateTtml == null || !TtmlTimingPolicy.hasTimedWords(candidateTtml))

    fun sourceRank(source: String?, sourceOrder: List<String>): Int {
        if (sourceOrder.isEmpty()) {
            return if (source == "APPLE_NATIVE" || source == LyricsSourceMenuPolicy.NATIVE) 0 else 1
        }
        val mapped = when (source) {
            "APPLE_NATIVE", LyricsSourceMenuPolicy.NATIVE -> LyricsSourceMenuPolicy.NATIVE
            CustomLyricsSources.AM_LYRICS, LyricsSourceMenuPolicy.AUTHOR -> LyricsSourceMenuPolicy.AUTHOR
            else -> LyricsSourceMenuPolicy.provider(source)
        } ?: return Int.MAX_VALUE
        val index = sourceOrder.indexOf(mapped)
        return if (index >= 0) index else Int.MAX_VALUE
    }

    fun preferNativeBeforeSearch(
        qualityFirst: Boolean,
        selectedSource: String?,
        nativeTtml: String?,
        hasCustomLyrics: Boolean,
        sourceOrder: List<String>,
    ): Boolean {
        if (sourceOrder.isEmpty()) return preferNative(qualityFirst, selectedSource, nativeTtml, hasCustomLyrics)
        if (selectedSource == LyricsSourceMenuPolicy.NATIVE) return true
        if (selectedSource != null || hasCustomLyrics) return false
        if (sourceRank("APPLE_NATIVE", sourceOrder) != 0) return false
        return qualityFirst || nativeTtml?.let(TtmlTimingPolicy::hasTimedWords) == true
    }

    fun nativeBeatsCandidate(
        qualityFirst: Boolean,
        selectedSource: String?,
        nativeTtml: String?,
        candidateSource: String?,
        candidateTtml: String?,
        hasCustomLyrics: Boolean,
        sourceOrder: List<String>,
    ): Boolean {
        if (selectedSource != null || hasCustomLyrics || nativeTtml == null || !hasContent(nativeTtml)) return false
        if (candidateTtml == null) return true
        val nativeRank = sourceRank("APPLE_NATIVE", sourceOrder)
        val candidateRank = sourceRank(candidateSource, sourceOrder)
        if (qualityFirst) return nativeRank < candidateRank
        val nativeWord = TtmlTimingPolicy.hasTimedWords(nativeTtml)
        val candidateWord = TtmlTimingPolicy.hasTimedWords(candidateTtml)
        return if (nativeWord != candidateWord) nativeWord else nativeRank < candidateRank
    }

    fun refreshForNativeDocument(
        qualityFirst: Boolean,
        selectedSource: String?,
        nativeTtml: String?,
        hasCustomLyrics: Boolean,
        preparedSource: String?,
        preparedTiming: TtmlTimingMode?,
        preparing: Boolean,
        sourceOrder: List<String>,
    ): Boolean {
        if (selectedSource != null && selectedSource != LyricsSourceMenuPolicy.NATIVE) return false
        if (hasCustomLyrics && selectedSource != LyricsSourceMenuPolicy.NATIVE) return false
        if (preparedTiming != null && preparedSource == "APPLE_NATIVE") return false
        if (selectedSource == LyricsSourceMenuPolicy.NATIVE) return true
        if (nativeTtml == null || !hasContent(nativeTtml)) return false
        if (preparedTiming != null) {
            val nativeRank = sourceRank("APPLE_NATIVE", sourceOrder)
            val preparedRank = sourceRank(preparedSource, sourceOrder)
            if (qualityFirst) return nativeRank < preparedRank
            val nativeWord = TtmlTimingPolicy.hasTimedWords(nativeTtml)
            val preparedWord = preparedTiming == TtmlTimingMode.WORD
            return if (nativeWord != preparedWord) nativeWord else nativeRank < preparedRank
        }
        return if (preparing) preferNativeBeforeSearch(qualityFirst, selectedSource, nativeTtml, hasCustomLyrics, sourceOrder) else true
    }

    private val paragraph = Regex("(?is)<p\\b[^>]*>(.*?)</p\\s*>")
    private val markup = Regex("<[^>]+>")
    fun hasContent(ttml: String): Boolean = paragraph.findAll(ttml).any {
        markup.replace(it.groupValues[1], "").any(Char::isLetterOrDigit)
    }
}
