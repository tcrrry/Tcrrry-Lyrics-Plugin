package com.tcrrry.lyrics.engine.hook

import com.tcrrry.lyrics.sources.DirectLyricsRepository
import com.tcrrry.lyrics.engine.lyrics.DesktopLyricsTtmlConverter

/** Keep the first credible display unless the completed search actually improves it. */
internal object DesktopLyricsUpdatePolicy {
    fun choose(first: DirectLyricsRepository.Result?, final: DirectLyricsRepository.Result,
               durationMs: Long, wordFirst: Boolean = false): DirectLyricsRepository.Result {
        if (first == null || first.lyrics.isBlank()) return final
        if (final.lyrics.isBlank()) return first
        val before = DesktopLyricsTtmlConverter.convert(first, durationMs)
        val after = DesktopLyricsTtmlConverter.convert(final, durationMs)
        if (before == after) return first
        if (first.score >= 95 && final.score < 95) return first
        if (wordFirst) return if (DirectLyricsRepository.qualityRank(final, true) >
            DirectLyricsRepository.qualityRank(first, true)) final else first
        val confidenceUpgrade = (final.score >= 95 && first.score < 95) || final.score >= first.score + 20
        val wordUpgrade = first.wordLyrics.isBlank() && final.wordLyrics.isNotBlank()
        val translationUpgrade = before?.contains("<translations>") != true &&
            after?.contains("<translations>") == true && final.score >= first.score
        val pronunciationUpgrade = after?.contains("<transliterations>") == true &&
            DirectLyricsRepository.coverage(final.lyrics, final.romanizedLyrics) >
                DirectLyricsRepository.coverage(first.lyrics, first.romanizedLyrics) &&
            DirectLyricsRepository.qualityRank(final) > DirectLyricsRepository.qualityRank(first)
        // Same recording enriched by its provider is a useful update; a similarly
        // ranked competing recording is kept in history for switching instead.
        val sameRecording = first.source == final.source && first.recordId.isNotBlank() && first.recordId == final.recordId
        return if (confidenceUpgrade || wordUpgrade || translationUpgrade || pronunciationUpgrade || sameRecording) final else first
    }
    fun changed(before: DirectLyricsRepository.Result?, after: DirectLyricsRepository.Result,
                durationMs: Long): Boolean = after.lyrics.isNotBlank() &&
        DesktopLyricsTtmlConverter.convert(before ?: DirectLyricsRepository.Result(), durationMs) !=
            DesktopLyricsTtmlConverter.convert(after, durationMs)
}
