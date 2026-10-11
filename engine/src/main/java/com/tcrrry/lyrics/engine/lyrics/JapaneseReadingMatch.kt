package com.tcrrry.lyrics.engine.lyrics

/** Compare dictionary-backed Japanese readings with provider spellings.
 * Offsets always refer to the supplied letters, never to a rewritten reading.
 * Callers must keep Latin lyric text out of this matcher.
 */
internal object JapaneseReadingMatch {
    private val aliases = listOf(
        // Providers may expand contracted kana (じゃ -> ji a, しょ -> xi yo).
        // Match the complete known syllable; do not drop arbitrary letters.
        "sha" to listOf("sya", "shiya", "xiya"),
        "shu" to listOf("syu", "shiyu", "xiyu"),
        "sho" to listOf("syo", "shiyo", "xiyo"),
        "ja" to listOf("jya", "jia", "zya"),
        "ju" to listOf("jyu", "jiu", "zyu"),
        "jo" to listOf("jyo", "jio", "zyo"),
        "tsu" to listOf("cu", "tu"), "shi" to listOf("xi", "si"),
        "chi" to listOf("ci", "ti"), "ji" to listOf("zi"), "fu" to listOf("hu")
    )

    fun ends(canonical: String, supplied: String, offset: Int): List<Int> {
        if (canonical.isEmpty() || offset !in 0..supplied.length) return emptyList()
        val ends = linkedSetOf<Int>()
        if (supplied.startsWith(canonical, offset)) ends += offset + canonical.length
        val visited = HashSet<Pair<Int, Int>>()
        var budget = 4096
        fun match(index: Int, position: Int) {
            if (--budget < 0 || !visited.add(index to position)) return
            if (index == canonical.length) { ends += position; return }
            if (position >= supplied.length) return
            if (canonical[index] == supplied[position]) match(index + 1, position + 1)
            for ((from, alternatives) in aliases) {
                if (!canonical.startsWith(from, index)) continue
                for (to in alternatives) if (supplied.startsWith(to, position))
                    match(index + from.length, position + to.length)
            }
            if (canonical[index] == 'r' && canonical.getOrNull(index + 1)?.let { it in "aeiouy" } == true && supplied[position] == 'l')
                match(index + 1, position + 1)
            // Some providers write isolated vowels as yi/wu. Do not turn the
            // vowel inside ki/shi/etc. into an arbitrary extra consonant.
            if (index == 0 || canonical[index - 1] in "aeioun") {
                val extra = when (canonical[index]) { 'i' -> "yi"; 'u' -> "wu"; else -> null }
                if (extra != null && supplied.startsWith(extra, position)) match(index + 1, position + 2)
            }
        }
        match(0, offset)
        // Exhaustion must not accept an incompletely explored fuzzy match.
        return if (budget >= 0) ends.toList() else ends.filter { supplied.substring(offset, it) == canonical }
    }
}
