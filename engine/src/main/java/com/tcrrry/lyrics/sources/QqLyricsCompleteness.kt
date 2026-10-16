package com.tcrrry.lyrics.sources

/** A short QRC response is only a reason to verify, not proof that a song is truncated. */
internal object QqLyricsCompleteness {
    private data class Row(val start: Long, val text: String)
    private val stamp = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")
    private fun rows(raw: String): List<Row> = raw.lineSequence().flatMap { line ->
        val text=stamp.replace(line, "").trim()
        if (text.isBlank()) emptySequence() else stamp.findAll(line).map {
            Row(it.groupValues[1].toLong()*60_000 + it.groupValues[2].toLong()*1000 +
                it.groupValues[3].padEnd(3,'0').take(3).toLong(), text)
        }
    }.sortedBy { it.start }.toList()
    private fun key(text: String) = text.filter(Char::isLetterOrDigit).lowercase(java.util.Locale.ROOT)

    fun repair(result: DirectLyricsRepository.Result, fetchSameRecord: () -> String): DirectLyricsRepository.Result {
        if (result.source != "QQ音乐" || result.recordId.isBlank()) return result
        val current=rows(result.lyrics)
        val last=current.lastOrNull()?.start ?: return result
        val translatedLast=rows(result.translatedLyrics).lastOrNull()?.start ?: 0L
        val shortTrack=result.durationMs > 0 && last < result.durationMs * 65 / 100 && result.durationMs-last > 30_000
        if (!shortTrack && translatedLast-last < 15_000) return result
        val complete=runCatching(fetchSameRecord).getOrDefault("")
        val candidate=rows(complete)
        val later=candidate.count { it.start >= last+15_000 }
        // Shared timed lines protect long instrumental outros and differing arrangements.
        val common=current.count { old -> key(old.text).isNotEmpty() && candidate.any {
            kotlin.math.abs(it.start-old.start) <= 1500 && key(it.text)==key(old.text)
        } }
        if (later < 3 || common < 2) return result
        return result.copy(lyrics=complete, wordLyrics="", romanizedWordLyrics="")
    }
}
