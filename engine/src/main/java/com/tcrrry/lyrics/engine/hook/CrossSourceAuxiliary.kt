package com.tcrrry.lyrics.engine.hook
import com.tcrrry.lyrics.plugin.PluginStorage.pluginPreferences

import com.tcrrry.lyrics.sources.DirectLyricsRepository
import com.tcrrry.lyrics.engine.lyrics.DesktopLyricsTtmlConverter
import java.text.Normalizer

/** Exact text and mutually unique nearby times; never replace the primary word-timed body. */
internal object CrossSourceAuxiliary {
    private data class Row(val start: Long, val text: String, val key: String, val raw: String)
    private val paragraphs = Regex("(?is)<p\\b([^>]*)>(.*?)</p\\s*>")
    private val tags = Regex("<[^>]+>")
    private fun normalized(text: String) = Normalizer.normalize(text, Normalizer.Form.NFKC).filterNot(Char::isWhitespace)
    private fun time(text: String): Long? = runCatching {
        if (text.endsWith("ms")) return@runCatching text.dropLast(2).toDouble().toLong()
        if (text.endsWith("s")) return@runCatching (text.dropLast(1).toDouble() * 1000).toLong()
        (text.split(':').fold(0.0) { value, part -> value * 60 + part.toDouble() } * 1000).toLong()
    }.getOrNull()
    private fun rows(ttml: String) = paragraphs.findAll(ttml).mapNotNull { match ->
        val begin = Regex("begin=[\"']([^\"']+)").find(match.groupValues[1])?.groupValues?.get(1) ?: return@mapNotNull null
        val key = Regex("itunes:key=[\"']([^\"']+)").find(match.groupValues[1])?.groupValues?.get(1).orEmpty()
        val text = tags.replace(match.groupValues[2], "").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        time(begin)?.let { Row(it, normalized(text), key, text) }
    }.toList()
    private fun track(ttml: String, name: String): Map<String, String> {
        val block = Regex("(?is)<$name\\b[^>]*>(.*?)</$name>").find(ttml)?.groupValues?.get(1) ?: return emptyMap()
        return Regex("(?is)<text for=[\"']([^\"']+)[\"']>(.*?)</text>").findAll(block).associate {
            it.groupValues[1] to it.groupValues[2].replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        }
    }
    fun dictionary(context: android.content.Context, id: Long, primary: String): String {
        val original = rows(primary)
        if (original.isEmpty()) return primary
        val lyrics = original.joinToString("\n") {
            val time = java.lang.String.format(java.util.Locale.US, "[%02d:%02d.%03d]", it.start / 60000, it.start / 1000 % 60, it.start % 1000)
            time + it.raw
        }
        var source = DirectLyricsRepository.Result(lyrics = lyrics)
        source = com.tcrrry.lyrics.engine.lyrics.LanguagePronunciationSupplement.fill(context, id, source)
        if (context.pluginPreferences("japanese_pronunciation", android.content.Context.MODE_PRIVATE).getBoolean("enabled", true))
            source = com.tcrrry.lyrics.engine.lyrics.JapanesePronunciationSupplement.fill(source)
        val converted = DesktopLyricsTtmlConverter.convert(source) ?: return primary
        val rendered = rows(converted); val readings = track(converted, "transliteration")
        val values = original.indices.mapNotNull { index ->
            rendered.getOrNull(index)?.takeIf { it.text == original[index].text && it.start == original[index].start }
                ?.let { readings[it.key] }?.let { index to it }
        }.toMap()
        return TtmlSubtitleTrack.attachPronunciation(primary, values) ?: primary
    }

    fun enrich(primary: String, source: DirectLyricsRepository.Result): String {
        val supplement = DesktopLyricsTtmlConverter.convert(source) ?: return primary
        val targetRows = rows(primary); val otherRows = rows(supplement)
        val translations = track(supplement, "translation"); val pronunciation = track(supplement, "transliteration")
        fun nearby(left: Row, right: Row) = left.text.isNotBlank() && left.text == right.text &&
            kotlin.math.abs(left.start - right.start) <= 1200L
        // A chorus can repeat minutes later without ambiguity. Reject only when
        // either occurrence could map to more than one nearby row, or order crosses.
        val pairs = targetRows.mapIndexedNotNull { index, row ->
            val other = otherRows.indices.singleOrNull { nearby(row, otherRows[it]) } ?: return@mapIndexedNotNull null
            if (targetRows.count { nearby(it, otherRows[other]) } != 1) return@mapIndexedNotNull null
            index to other
        }
        val matched = pairs.filter { (index, other) -> pairs.none { (i, o) ->
            (i < index && o > other) || (i > index && o < other)
        } }.associate { (index, other) -> index to otherRows[other].key }
        var result = TtmlSubtitleTrack.attach(primary, matched.mapNotNull { (index, key) -> translations[key]?.let { index to it } }.toMap()) ?: primary
        result = TtmlSubtitleTrack.attachPronunciation(result, matched.mapNotNull { (index, key) -> pronunciation[key]?.let { index to it } }.toMap()) ?: result
        return result
    }
}
