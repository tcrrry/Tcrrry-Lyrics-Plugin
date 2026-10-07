package com.tcrrry.lyrics.engine.hook

import android.text.Html

/** Native Word parsing can discard metadata text; match intact TTML tracks safely. */
internal class NativeSubtitleLookup(ttml: String) {
    data class Tracks(val pronunciation: String?, val translation: String?)
    private fun normalize(value: String): String = Html.fromHtml(value, 0).toString()
        .replace(Regex("[\\s\\u00a0]+"), "")
    private val byOriginal: Map<String, Tracks>
    val hasPronunciation: Boolean
    val hasTranslation: Boolean
    init {
        val pronunciation = TtmlSubtitleTrack.subtitleValues(ttml, true)
        val translation = TtmlSubtitleTrack.subtitleValues(ttml)
        hasPronunciation = pronunciation.values.any { normalize(it).isNotBlank() }
        hasTranslation = translation.values.any { normalize(it).isNotBlank() }
        val paragraphs = Regex("(?is)<p\\b[^>]*>(.*?)</p\\s*>").findAll(ttml).take(1000).toList()
        byOriginal = paragraphs.mapIndexed { index, paragraph ->
            normalize(paragraph.groupValues[1]) to Tracks(pronunciation[index]?.let { Html.fromHtml(it, 0).toString() },
                translation[index]?.let { Html.fromHtml(it, 0).toString() })
        }.groupBy({ it.first }, { it.second }).mapValues { (_, tracks) ->
            // Repeated originals may have different readings or translations. Never guess.
            Tracks(tracks.map { it.pronunciation }.distinct().singleOrNull(),
                tracks.map { it.translation }.distinct().singleOrNull())
        }.filterKeys(String::isNotBlank)
    }
    fun forOriginal(original: String): Tracks? = byOriginal[normalize(original)]
}
