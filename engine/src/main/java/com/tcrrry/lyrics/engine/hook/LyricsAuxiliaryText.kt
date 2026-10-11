package com.tcrrry.lyrics.engine.hook

import android.text.Html

/** Display/translation text only. Never rewrite the timed TTML body.
 * A doubly escaped serialized timed span must not become visible XML after
 * ordinary HTML decoding. Recognize timing attributes, not arbitrary '<' text.
 */
internal object LyricsAuxiliaryText {
    private val serializedSpan = Regex("(?is)(?:<|&lt;|&amp;lt;)(?:[\\w.-]+:)?span\\b.{0,512}?\\b(?:begin|end)\\s*=")
    private val htmlStart = Regex("<\\s*/?\\s*[A-Za-z]")
    fun forNative(value: String): String = if (serializedSpan.containsMatchIn(value))
        android.text.TextUtils.htmlEncode(plain(value)) else value

    fun plain(value: String): String {
        if ('<' !in value && '&' !in value) return value
        if ('&' !in value && !htmlStart.containsMatchIn(value)) return value
        var result = Html.fromHtml(value, 0).toString()
        repeat(3) {
            if (!serializedSpan.containsMatchIn(result)) return result
            val parsed = NativeLyricsTimedWords.parse(result)?.text
            if (parsed != null && parsed != result) result = parsed
            else {
                val decoded = Html.fromHtml(result, 0).toString()
                if (decoded == result) return ""
                result = decoded
            }
        }
        return result.takeUnless { serializedSpan.containsMatchIn(it) }.orEmpty()
    }
}
