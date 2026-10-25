package com.tcrrry.lyrics.engine.lyrics

/** Spoken numeric spans for spatial ruby only; original offsets and timing stay intact. */
internal object JapaneseNumericReading {
    fun candidates(original: String, start: Int): List<Pair<Int, String>> {
        var end = start
        while (end < original.length && original[end].digitToIntOrNull() != null) end++
        if (end == start || end - start > 4) return emptyList()
        val digits = original.substring(start, end).map { it.digitToInt() }
        if (digits.size > 1 && digits.first() == 0) return emptyList()
        val value = digits.fold(0) { n, digit -> n * 10 + digit }
        val single = listOf("", "ichi", "ni", "san", "yon", "go", "roku", "nana", "hachi", "kyuu")
        val spoken = if (value == 0) "zero" else buildString {
            val thousands = value / 1000
            append(when (thousands) { 0 -> ""; 1 -> "sen"; 3 -> "sanzen"; 8 -> "hassen"; else -> single[thousands] + "sen" })
            val hundreds = value / 100 % 10
            append(when (hundreds) { 0 -> ""; 1 -> "hyaku"; 3 -> "sanbyaku"; 6 -> "roppyaku"; 8 -> "happyaku"; else -> single[hundreds] + "hyaku" })
            val tens = value / 10 % 10
            if (tens > 0) append((if (tens == 1) "" else single[tens]) + "juu")
            append(single[value % 10])
        }
        val result = mutableListOf(end to spoken)
        val counter = listOf("ヶ月", "か月", "カ月", "箇月").firstOrNull { original.startsWith(it, end) }
        if (counter != null && value > 0) {
            val stems = when {
                spoken.endsWith("ichi") -> listOf(spoken.dropLast(4) + "ik")
                spoken.endsWith("roku") -> listOf(spoken.dropLast(4) + "rok")
                spoken.endsWith("hachi") -> listOf(spoken.dropLast(5) + "hak", spoken)
                spoken.endsWith("juu") -> listOf(spoken.dropLast(3) + "juk", spoken.dropLast(3) + "jik")
                else -> listOf(spoken)
            }
            result.addAll(0, stems.map { end + counter.length to it + "kagetsu" })
        }
        return result
    }
}
