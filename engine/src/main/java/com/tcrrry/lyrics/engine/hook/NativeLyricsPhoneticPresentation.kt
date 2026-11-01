package com.tcrrry.lyrics.engine.hook

import com.tcrrry.lyrics.engine.lyrics.DesktopLyricsPresentation
import com.tcrrry.lyrics.engine.lyrics.DesktopLyricsTtmlConverter

/** Switch only display text; native line ranges and auxiliary translation remain intact. */
internal object NativeLyricsPhoneticPresentation {
    private val paragraphs = Regex("(?is)<p\\b([^>]*)>(.*?)</p\\s*>")
    private val body = Regex("(?is)<body\\b[^>]*>.*?</body\\s*>")
    private val roles = Regex("(?is)\\b(?:ttm:role|itunes:role)\\s*=")
    private val begin = Regex("(?is)\\bbegin\\s*=\\s*([\"'])(.*?)\\1")
    private val end = Regex("(?is)\\bend\\s*=\\s*([\"'])(.*?)\\1")
    private val alignment = Regex("<!--native-phonetic-v2 words=(\\d+) lines=(\\d+)-->")
    fun alignmentDetail(ttml: String): String? = alignment.find(ttml.take(1024))?.let {
        val words = it.groupValues[1].toIntOrNull() ?: return@let null
        val lines = it.groupValues[2].toIntOrNull() ?: return@let null
        when {
            words == 0 -> "发音逐行显示"
            lines == 0 -> "发音逐段高亮"
            else -> "发音逐字 $words 句 · 逐行 $lines 句"
        }
    }
    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun timedReading(words: List<Triple<Long, Long, String>>, reading: String, smoothShortUnits: Boolean): String? {
        val aligned = DesktopLyricsTtmlConverter.alignNativePronunciation(words, reading, smoothShortUnits) ?: return null
        fun stamp(ms: Long) = java.lang.String.format(java.util.Locale.US, "%d:%02d.%03d", ms / 60000, ms / 1000 % 60, ms % 1000)
        return aligned.joinToString("") { (start, finish, text) ->
            escape(text.takeWhile(Char::isWhitespace)) +
                "<span begin=\"${stamp(start)}\" end=\"${stamp(finish)}\">${escape(text.trim())}</span>" +
                escape(text.takeLastWhile(Char::isWhitespace))
        }
    }

    private fun decode(text: String): String = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos);").replace(text) { match ->
        val token = match.groupValues[1]
        when (token) {
            "amp" -> "&"
            "lt" -> "<"
            "gt" -> ">"
            "quot" -> "\""
            "apos" -> "'"
            else -> {
                val code = if (token.startsWith("#x")) token.substring(2).toIntOrNull(16) else token.substring(1).toIntOrNull()
                if (code != null && Character.isValidCodePoint(code) && code !in 0xD800..0xDFFF)
                    String(Character.toChars(code)) else match.value
            }
        }
    }

    fun render(ttml: String, primary: Boolean, smoothShortUnits: Boolean = false): String {
        val readings = TtmlSubtitleTrack.subtitleValues(ttml, pronunciation = true)
        if (readings.isEmpty()) return ttml
        val original = body.find(ttml) ?: return ttml
        val rows = paragraphs.findAll(original.value).toList()
        // Preserve complex background rows locally; their presence must not
        // disable separate, fully timed main-vocal rows in the same document.
        val supported = readings.filterKeys { index ->
            rows.getOrNull(index)?.let {
                !roles.containsMatchIn(it.value) && begin.find(it.groupValues[1]) != null && end.find(it.groupValues[1]) != null
            } == true
        }
        if (supported.isEmpty()) return ttml
        var rendered = ttml
        var alignmentMarker = ""
        if (primary) {
            val originals = readings.mapValues { decode(it.value) }.toMutableMap()
            val parsed = supported.mapValues { (row, _) -> NativeLyricsTimedWords.parse(rows[row].groupValues[2]) }
            val mapped = if (TtmlTimingPolicy.isWord(ttml)) supported.mapValues { (row, reading) ->
                parsed[row]?.words?.let { timedReading(it, decode(reading), smoothShortUnits) }
            } else emptyMap()
            val wordTimed = mapped.values.any { it != null } || (TtmlTimingPolicy.isWord(ttml) &&
                rows.indices.any { it !in supported && NativeLyricsTimedWords.parse(rows[it].groupValues[2])?.words?.isNotEmpty() == true })
            val wordRows = mapped.values.count { it != null }
            alignmentMarker = "<!--native-phonetic-v2 words=$wordRows lines=${supported.size - wordRows}-->"
            var index = 0
            val displayed = paragraphs.replace(original.value) { match ->
                val row = index++
                val reading = supported[row] ?: return@replace match.value
                originals[row] = parsed[row]?.text ?: decode(Regex("<[^>]+>").replace(match.groupValues[2], ""))
                val value = mapped[row] ?: if (wordTimed) {
                    val a = begin.find(match.groupValues[1])!!.groupValues[2]
                    val b = end.find(match.groupValues[1])!!.groupValues[2]
                    // This one row has only real line timing; keep it local, not a track-wide downgrade.
                    "<span begin=\"$a\" end=\"$b\">${escape(decode(reading))}</span>"
                } else escape(decode(reading))
                "<p${match.groupValues[1]}>$value</p>"
            }
            if (originals.isEmpty()) return ttml
            rendered = ttml.replaceRange(original.range, displayed)
            // Unaligned rows get one line-timed span; other rows keep their native word ranges.
            if (!wordTimed) rendered = rendered.replace(Regex("(?i)itunes:timing\\s*=\\s*([\"'])Word\\1"), "itunes:timing=\"Line\"")
            rendered = rendered.replace(Regex("(?is)<transliterations\\b[^>]*>.*?</transliterations\\s*>"), "")
            rendered = TtmlSubtitleTrack.attachPronunciation(rendered, originals) ?: return ttml
        }
        val old = DesktopLyricsPresentation.fromTtml(rendered)
        val presentation = old?.copy(wordTimed = TtmlTimingPolicy.isWord(rendered), pronunciation = true, primaryPronunciation = primary)
            ?: DesktopLyricsPresentation("未知", TtmlTimingPolicy.isWord(rendered),
                TtmlTimingPolicy.metadataOf(rendered).hasTranslation, false, false, true, primaryPronunciation = primary)
        rendered = rendered.replace(Regex("<!--tcrrry-lyrics-v1 .*?-->"), "")
        rendered = rendered.replace(alignment, "")
        val marker = presentation.marker() + alignmentMarker
        return if (rendered.startsWith("<?xml")) rendered.replaceFirst("?>", "?>$marker") else marker + rendered
    }
}
