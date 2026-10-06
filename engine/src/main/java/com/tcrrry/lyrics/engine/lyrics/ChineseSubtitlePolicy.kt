package com.tcrrry.lyrics.engine.lyrics

/** Do not make credits or Chinese-to-Chinese text into a subtitle track. */
internal object ChineseSubtitlePolicy {
    private val credits = Regex("^(?:作词|作曲|词曲|编曲|制作人|演唱|歌手|词|曲|混音|录音|母带|监制|出品|发行|版权|翻译|译者|词作者|曲作者|lyrics?\\s*(?:by)?|compos(?:er|ed)|arrang(?:er|ed)|producer|vocal|written\\s+by)\\s*[:：/／]", RegexOption.IGNORE_CASE)
    private fun han(c: Char) = c in '\u3400'..'\u9fff' || c in '\uf900'..'\ufaff'
    private fun foreign(c: Char) = c in '\u3040'..'\u30ff' || c in '\uac00'..'\ud7af' ||
        (c.isLetter() && !han(c))
    fun isCredit(text: String): Boolean = credits.containsMatchIn(text.trim())
    fun needsChineseTranslation(text: String): Boolean =
        !isCredit(text) && text.any(::foreign)
    fun isChineseSong(lines: List<String>): Boolean {
        val vocal = lines.filter { it.any(Char::isLetter) && !isCredit(it) }
        if (vocal.isEmpty()) return false
        // A kana/Hangul line must not be mistaken for Chinese just because it also uses Han.
        if (vocal.any { row -> row.any { it in '\u3040'..'\u30ff' || it in '\uac00'..'\ud7af' } }) return false
        return vocal.count { row -> row.count(::han) >= 2 &&
            row.count(::han) >= row.count(::foreign) } * 5 >= vocal.size * 4
    }
    fun usable(original: String, translated: String?): String? {
        val value = translated?.trim()?.takeIf(String::isNotBlank) ?: return null
        if (!needsChineseTranslation(original)) return null
        fun normalized(s: String) = s.filter(Char::isLetterOrDigit).lowercase()
        return value.takeIf { normalized(it) != normalized(original) && it.any(::han) }
    }
}
