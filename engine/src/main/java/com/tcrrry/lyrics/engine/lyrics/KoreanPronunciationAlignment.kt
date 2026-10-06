package com.tcrrry.lyrics.engine.lyrics

import java.util.Locale

/** Project synchronized provider syllables onto Hangul only when order and sounds agree. */
internal object KoreanPronunciationAlignment {
    data class Unit(val start: Int, val end: Int, val text: String)
    private val surface = Regex("[가-힣]|[A-Za-z0-9]+(?:['’-][A-Za-z]+)*")
    private val reading = Regex("[A-Za-z0-9]+(?:['’-][A-Za-z]+)*")
    private val syllable = Regex("(kk|gg|tt|dd|pp|bb|ss|jj|ch|sh|[gkdtnrlmbpsjchz])?(yeo|yae|wae|weo|ae|ya|eo|ye|wa|oe|yo|wo|we|wi|yu|eu|ui|a|e|o|u|i)(ng|kk|gg|tt|dd|pp|bb|ss|lk|lg|lm|lb|ls|lt|lp|lh|nh|nj|ks|gs|bs|[gkntdlrmbpsjh])?")
    private val initials = listOf(setOf("g","k","kk","gg"),setOf("kk","gg","k"),setOf("n","l","r"),
        setOf("d","t","tt","dd"),setOf("tt","dd","t"),setOf("r","l","n"),setOf("m"),
        setOf("b","p","pp","bb"),setOf("pp","bb","p"),setOf("s","ss","sh"),setOf("ss","s","sh"),
        setOf(""),setOf("j","ch","jj","z"),setOf("jj","j","z"),setOf("ch","c"),setOf("k","g"),setOf("t","d"),setOf("p","b"),setOf("h"))
    private val vowels = listOf(setOf("a"),setOf("ae"),setOf("ya"),setOf("yae"),setOf("eo"),setOf("e"),
        setOf("yeo"),setOf("ye"),setOf("o"),setOf("wa"),setOf("wae"),setOf("oe","we"),setOf("yo"),
        setOf("u"),setOf("wo","weo"),setOf("we"),setOf("wi"),setOf("yu"),setOf("eu"),setOf("ui","i","e"),setOf("i"))
    private val liaison = listOf(setOf(""),setOf("g","k"),setOf("kk","gg"),setOf("s","ss"),setOf("n"),
        setOf("j","ch"),setOf("h","n"),setOf("d","t"),setOf("r","l"),setOf("g","k"),setOf("m"),
        setOf("b","p"),setOf("s","ss"),setOf("t","d"),setOf("p","b"),setOf("h"),setOf("m"),
        setOf("b","p"),setOf("s","ss"),setOf("s","ss","sh"),setOf("ss","s","sh"),setOf(""),
        setOf("j","ch"),setOf("ch","c"),setOf("k","g"),setOf("t","d"),setOf("p","b"),setOf("h"))
    fun units(original: String, pronunciation: String): List<Unit>? {
        if (original.isBlank()) return null
        val originals = surface.findAll(original).toList()
        val phonetics = reading.findAll(pronunciation).map { it.value }.toList()
        // Reject omitted unsupported scripts, instead of silently shifting token indices.
        if (surface.replace(original, "").any(Char::isLetterOrDigit) ||
            reading.replace(pronunciation, "").any(Char::isLetterOrDigit)) return null
        val result = mutableListOf<Unit>()
        var cursor = 0
        originals.forEachIndexed { index, word ->
            val token = phonetics.getOrNull(cursor) ?: return null
            val c = word.value.singleOrNull()
            if (c != null && c in '가'..'힣') {
                val part = syllable.matchEntire(token.lowercase(Locale.ROOT)) ?: return null
                val value = c.code - 0xAC00
                val initial = value / 588
                val medial = value / 28 % 21
                val previous = originals.getOrNull(index - 1)?.value?.singleOrNull()?.takeIf { it in '가'..'힣' }
                val previousCoda = previous?.let { (it.code - 0xAC00) % 28 }
                // Final consonant liaison and aspiration/assimilation with following ㅎ.
                val shifted = if (previousCoda != null && initial in setOf(11, 18)) liaison[previousCoda] else emptySet()
                val allowed = initials[initial] + shifted
                if (part.groupValues[1] !in allowed || part.groupValues[2] !in vowels[medial]) return null
                cursor++
                result.add(Unit(word.range.first, word.range.last + 1, token))
            } else {
                // Provider may split English into several tokens; preserve its real word group.
                val normalize: (String) -> String = { it.filter(Char::isLetterOrDigit).lowercase(Locale.ROOT) }
                val target = normalize(word.value)
                val first = cursor
                var value = ""
                while (cursor < phonetics.size && value.length < target.length) value += normalize(phonetics[cursor++])
                if (value != target) return null
                result.add(Unit(word.range.first, word.range.last + 1, phonetics.subList(first,cursor).joinToString(" ")))
            }
        }
        return result.takeIf { cursor == phonetics.size && it.isNotEmpty() }
    }
}
