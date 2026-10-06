package com.tcrrry.lyrics.sources

/** Publish usable lyrics before the optional pronunciation request can stall. */
internal object OptionalQqPronunciation {
    private val foreign = Regex("[\\u3040-\\u30ff\\uac00-\\ud7af]")

    fun enrich(
        result: DirectLyricsRepository.Result,
        fetch: () -> String,
        deliver: (DirectLyricsRepository.Result) -> Unit,
    ): DirectLyricsRepository.Result {
        deliver(result)
        if (result.romanizedLyrics.isNotBlank() || !foreign.containsMatchIn(result.lyrics)) return result
        val pronunciation = runCatching(fetch).getOrDefault("")
        return if (pronunciation.isBlank()) result
        else result.copy(romanizedLyrics = pronunciation).also(deliver)
    }
}
