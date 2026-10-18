package com.tcrrry.lyrics.engine.hook

import android.text.Html

/** Display/translation text only. Never rewrite the timed TTML body.
 * A doubly escaped serialized timed span must not become visible XML after
 * ordinary HTML decoding. Recognize timing attributes, not arbitrary '<' text.
 */
internal object LyricsAuxiliaryText {
    private val serializedSpan = Regex("(?is)(?:<|&(?:amp;){0,8}(?:lt|#0*60|#x0*3c);)(?:[\\w.-]+:)?span\\b.{0,512}?\\b(?:begin|end)\\s*=")
    private val htmlStart = Regex("<\\s*/?\\s*[A-Za-z]")
    // Some native subtitle getters already removed XML delimiters/quotes. Require
    // both numeric timing attributes and the TTML namespace, never a bare "span".
    private val bareTimedSpan = Regex("""(?is)(?:[\w.-]+:)?span\s+begin\s*=\s*["']?[\d:.]+(?:ms|s)?["']?\s+end\s*=\s*["']?[\d:.]+(?:ms|s)?["']?\s+xmlns\s*=\s*(?<quote>["']?)https?://www\.w3\.org/ns/ttml\k<quote>""")
    private val bareTrailingCloses = Regex("""(?i)(?:/(?:[\w.-]+:)?span\s*)+$""")
    private val bareClose = Regex("""(?i)/(?:[\w.-]+:)?span""")
    fun hasTimedMarkup(value: String): Boolean = serializedSpan.containsMatchIn(value) || bareTimedSpan.containsMatchIn(value)
    // Verified A.U forwards a plain CharSequence to PrecomputedText, not Html.fromHtml.
    fun forNative(value: String): String = if (hasTimedMarkup(value)) plain(value) else value

    private fun cleanBare(value: String): String {
        fun text(part: String): String {
            val suffix = bareTrailingCloses.find(part) ?: return part
            return part.replaceRange(suffix.range, bareClose.replace(suffix.value, ""))
        }
        val result = StringBuilder()
        var at = 0
        for (header in bareTimedSpan.findAll(value)) {
            result.append(text(value.substring(at, header.range.first)))
            at = header.range.last + 1
        }
        result.append(text(value.substring(at)))
        return result.toString()
    }

    fun plain(value: String): String {
        if (!hasTimedMarkup(value) && '<' !in value && '&' !in value) return value
        if (!hasTimedMarkup(value) && '&' !in value && !htmlStart.containsMatchIn(value)) return value
        var result = value
        repeat(6) {
            if (bareTimedSpan.containsMatchIn(result) && '<' !in result && !serializedSpan.containsMatchIn(result)) {
                result = cleanBare(result)
                if ('&' in result) result = Html.fromHtml(result, 0).toString()
                if (!hasTimedMarkup(result)) return result
            }
            if (it > 0 && !hasTimedMarkup(result)) return result
            val parsed = if (hasTimedMarkup(result)) NativeLyricsTimedWords.parse(result)?.text else null
            if (parsed != null && parsed != result) result = parsed
            else {
                val decoded = Html.fromHtml(result, 0).toString()
                if (decoded == result) return if (hasTimedMarkup(result)) "" else result
                result = decoded
            }
        }
        return result.takeUnless(::hasTimedMarkup).orEmpty()
    }
}
