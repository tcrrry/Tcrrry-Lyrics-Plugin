package com.tcrrry.lyrics.engine.lyrics

import java.util.Locale

/** Spatial reading ranges only. No lyric text or source timestamps are changed. */
internal object JapaneseRubyAlignment {
    data class Unit(val start: Int, val end: Int, val reading: String)
    data class Result(val units: List<Unit>, val dictionaryMatch: Boolean)
    private fun normalized(text: String) = text.filter(Char::isLetterOrDigit).lowercase(Locale.ROOT)
    fun displayReading(text:String)=text.filter { it.isLetterOrDigit() || it=='\'' || it=='’' || it=='-' }

    fun align(original: String, reading: String): Result? {
        if (original.length > 500 || reading.length > 2000 ||
            !Regex("[ぁ-ゖァ-ヺ\\p{IsHan}]").containsMatchIn(original)) return null
        val generated = JapanesePronunciationSupplement.units(original)
        val dictionaryMatch = generated != null && normalized(generated.joinToString("") { it.text }) == normalized(reading)
        // Platform spacing and pronunciation remain authoritative. The dictionary
        // checks alternative readings; it never replaces supplied platform text.
        val matched = if (dictionaryMatch) generated else JapanesePronunciationSupplement.alignedUnits(original, reading, spatialVariants = true)
        if (matched.isNullOrEmpty()) return null
        val ranges = joinContractedKana(original, matched.filter { normalized(it.text).isNotEmpty() })
            .flatMap { splitKanaAndKanji(original, it) }
        val positions = reading.indices.filter { reading[it].isLetterOrDigit() }
        var offset = 0
        val result = ranges.map { unit ->
            val length = normalized(unit.text).length
            if (length == 0 || offset + length > positions.size) return null
            val start = positions[offset]
            offset += length
            val end = if (offset == positions.size) reading.length else positions[offset]
            Unit(unit.start, unit.end, reading.substring(start, end).trim())
        }
        if (offset != positions.size || result.any { it.reading.isBlank() }) return null
        return Result(result, dictionaryMatch)
    }

    private fun joinContractedKana(original: String, units: List<JapanesePronunciationSupplement.ReadingUnit>):
        List<JapanesePronunciationSupplement.ReadingUnit> {
        val result = mutableListOf<JapanesePronunciationSupplement.ReadingUnit>()
        for (unit in units) {
            val previous = result.lastOrNull()
            if (previous != null && previous.end == unit.start && original[unit.start] in "ゃゅょャュョ" &&
                original[unit.start - 1] in "きぎしじちぢにひびぴみりキギシジチヂニヒビピミリ") {
                val surface = original.substring(previous.start, unit.end)
                val actual = normalized(previous.text + unit.text)
                if (surface.all { it in 'ぁ'..'ゖ' || it in 'ァ'..'ヺ' || it == 'ー' } &&
                    actual.length in JapaneseReadingMatch.ends(normalized(KanaRomaji.convert(surface)), actual, 0)) {
                    result[result.lastIndex] = JapanesePronunciationSupplement.ReadingUnit(previous.start, unit.end, previous.text + " " + unit.text)
                    continue
                }
            }
            result += unit
        }
        return result
    }

    private fun splitKanaAndKanji(original: String, unit: JapanesePronunciationSupplement.ReadingUnit):
        List<JapanesePronunciationSupplement.ReadingUnit> {
        val surface = original.substring(unit.start, unit.end)
        fun han(c: Char) = Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN
        fun kana(c: Char) = c in 'ぁ'..'ゖ' || c in 'ァ'..'ヺ' || c == 'ー'
        if (!surface.all { han(it) || kana(it) }) return listOf(unit)
        if (surface.all(::han)) return splitUnambiguousKanji(original, unit)
        data class Chunk(val start: Int, val end: Int, val kanji: Boolean)
        val chunks = mutableListOf<Chunk>()
        var index = 0
        while (index < surface.length) {
            val start = index
            if (han(surface[index])) {
                while (index < surface.length && han(surface[index])) index++
            } else {
                // A small kana or geminated consonant is a phonetic pair; it
                // cannot be annotated as an independently pronounced glyph.
                if (surface[index] in "っッ" && index + 1 < surface.length && kana(surface[index + 1])) index++
                index++
                while (index < surface.length && surface[index] in "ゃゅょぁぃぅぇぉャュョァィゥェォー") index++
            }
            chunks += Chunk(start, index, han(surface[start]))
        }
        if (chunks.count { it.kanji } > 1) return listOf(unit)
        val expected = normalized(unit.text)
        val known = chunks.map { chunk ->
            if (chunk.kanji) null else normalized(KanaRomaji.convert(surface.substring(chunk.start, chunk.end)))
        }
        val solutions = linkedSetOf<List<String>>()
        var budget = 8192
        fun partition(index: Int, offset: Int, parts: List<String>) {
            if (--budget < 0 || solutions.size > 1) return
            if (index == chunks.size) {
                if (offset == expected.length) solutions += parts
                return
            }
            val canonical = known[index]
            val ends = if (canonical == null) (offset + 1..expected.length).toList()
                else JapaneseReadingMatch.ends(canonical, expected, offset)
            for (end in ends) partition(index + 1, end, parts + expected.substring(offset, end))
        }
        partition(0, 0, emptyList())
        val values = solutions.singleOrNull()?.takeIf { budget >= 0 } ?: return listOf(unit)
        return chunks.flatMapIndexed { i, chunk ->
            val part = JapanesePronunciationSupplement.ReadingUnit(unit.start + chunk.start, unit.start + chunk.end, values[i])
            if (chunk.kanji) splitUnambiguousKanji(original, part) else listOf(part)
        }
    }

    private fun splitUnambiguousKanji(original: String, unit: JapanesePronunciationSupplement.ReadingUnit):
        List<JapanesePronunciationSupplement.ReadingUnit> {
        val surface = original.substring(unit.start, unit.end)
        if (surface.length < 2 || surface.length > 8 || !surface.all { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN })
            return listOf(unit)
        val expected = normalized(unit.text)
        val solutions = linkedSetOf<List<String>>()
        var budget = 128
        fun solve(index: Int, offset: Int, parts: List<String>) {
            if (--budget < 0 || solutions.size > 1) return
            if (index == surface.length) {
                if (offset == expected.length) solutions += parts
                return
            }
            JapanesePronunciationSupplement.characterReadings(surface[index].toString()).forEach { text ->
                val candidate = normalized(text)
                if (candidate.isNotBlank()) for (end in JapaneseReadingMatch.ends(candidate, expected, offset))
                    solve(index + 1, end, parts + expected.substring(offset, end))
            }
        }
        solve(0, 0, emptyList())
        val unique = solutions.singleOrNull()?.takeIf { budget >= 0 } ?: return listOf(unit)
        return unique.mapIndexed { index, text ->
            JapanesePronunciationSupplement.ReadingUnit(unit.start + index, unit.start + index + 1, text)
        }
    }
}
