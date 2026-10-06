package com.tcrrry.lyrics.engine.lyrics

import com.tcrrry.lyrics.dictionary.Tokenizer
import com.tcrrry.lyrics.sources.DirectLyricsRepository
import java.util.Locale

/** Reading dictionary, not translation. Called only by the background lyrics fetch. */
internal object JapanesePronunciationSupplement {
    private class ReadingTokenizer : Tokenizer() {
        private val trie by lazy { com.atilika.kuromoji.trie.DoubleArrayTrie.newInstance(
            com.atilika.kuromoji.util.ResourceResolver { name -> com.tcrrry.lyrics.plugin.PluginLifetime.context.openAsset("kuromoji/$name") }) }
        fun alternatives(surface: String): List<String> {
            val id = trie.lookup(surface)
            if (id <= 0) return emptyList()
            val dictionary = dictionaryMap[com.atilika.kuromoji.viterbi.ViterbiNode.Type.KNOWN]
                as com.atilika.kuromoji.dict.TokenInfoDictionary
            return dictionary.lookupWordIds(id).asSequence().mapNotNull { word ->
                dictionary.getAllFeaturesArray(word).getOrNull(7)?.takeUnless { it == "*" }
            }.distinct().toList()
        }
    }
    private val tokenizer by lazy { ReadingTokenizer() }
    internal data class ReadingUnit(val start: Int, val end: Int, val text: String)
    private val readings = object : LinkedHashMap<String, List<ReadingUnit>?>(128, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<ReadingUnit>?>?) = size > 128
    }
    private val kana = Regex("[ぁ-ゖァ-ヺ]")
    private val kanji = Regex("[\\p{IsHan}]")

    fun cacheMarker(enabled: Boolean, primary: Boolean = false) = "<!--japanese-pronunciation-r4 enabled=$enabled primary=$primary-->"

    fun cacheMatches(ttml: String, enabled: Boolean, primary: Boolean = false): Boolean =
        !ttml.take(1024).contains("<!--tcrrry-lyrics-v1 ") || ttml.take(1024).contains(cacheMarker(enabled, primary))

    fun fill(result: DirectLyricsRepository.Result): DirectLyricsRepository.Result {
        val originals = DesktopLyricsTtmlConverter.pronunciationLines(result)
        val native = DesktopLyricsTtmlConverter.pronunciationEntries(result)
        val added = originals.mapNotNull { (start, text) ->
            if (!kana.containsMatchIn(text) || text.length > 500) return@mapNotNull null
            val existing = native.minByOrNull { kotlin.math.abs(it.first - start) }
                ?.takeIf { kotlin.math.abs(it.first - start) <= 1200 && it.second.isNotBlank() && !it.second.equals(text.trim(), true) }
            if (existing != null) return@mapNotNull null
            reading(text)?.let { start to it }
        }
        if (added.isEmpty()) return result
        // A provider can send the original text as a dummy pronunciation. Remove
        // that matched entry so it cannot win a tie against our generated reading.
        val replaced = added.mapNotNull { (start, _) -> native.minByOrNull { kotlin.math.abs(it.first - start) }
            ?.takeIf { kotlin.math.abs(it.first - start) <= 1200 } }.toSet()
        return result.copy(
            romanizedLyrics = (native.filterNot { it in replaced } + added).sortedBy { it.first }.joinToString("\n") { (start, text) ->
                String.format(Locale.ROOT, "[%02d:%02d.%03d]%s", start / 60000, start / 1000 % 60, start % 1000, text)
            },
            supplementalPronunciationStarts = result.supplementalPronunciationStarts + added.map { it.first },
            supplementalPronunciationLanguages = result.supplementalPronunciationLanguages + added.associate { it.first to "ja" },
        )
    }

    /** Match the supplied reading against dictionary alternatives, never guess timestamp splits. */
    @Synchronized internal fun alignedUnits(original: String, pronunciation: String): List<ReadingUnit>? {
        if (original.length > 500 || pronunciation.length > 2000) return null
        fun normalize(value: String) = value.filter(Char::isLetterOrDigit).lowercase(Locale.ROOT)
        val expected = normalize(pronunciation)
        if (expected.isEmpty()) return null
        val positions = pronunciation.indices.filter { pronunciation[it].isLetterOrDigit() }
        val preferred = units(original).orEmpty().groupBy { it.start }
        val failed = HashSet<Pair<Int, Int>>()
        var budget = 8192
        val digits = listOf(listOf("ゼロ", "レイ"), listOf("イチ"), listOf("ニ"), listOf("サン"),
            listOf("ヨン", "シ"), listOf("ゴ"), listOf("ロク"), listOf("ナナ", "シチ"), listOf("ハチ"), listOf("キュウ", "ク"))
        fun solve(start: Int, offset: Int): List<ReadingUnit>? {
            if (--budget < 0) return null
            if (start == original.length) return emptyList<ReadingUnit>().takeIf { offset == expected.length }
            val state = start to offset
            if (state in failed) return null
            if (!original[start].isLetterOrDigit()) {
                solve(start + 1, offset)?.let { return it }
            }
            val candidates = linkedSetOf<Pair<Int, String>>()
            preferred[start].orEmpty().forEach { candidates += it.end to normalize(it.text) }
            for (end in start + 1..minOf(original.length, start + 24)) {
                val surface = original.substring(start, end)
                tokenizer.alternatives(surface).forEach { reading ->
                    candidates += end to normalize(KanaRomaji.convert(reading))
                }
                // ASCII words/digits and kana can legitimately be absent from the dictionary.
                if (surface.all { it in 'A'..'Z' || it in 'a'..'z' || it.isDigit() }) candidates += end to normalize(surface)
                if (surface.all { it in 'ぁ'..'ゖ' || it in 'ァ'..'ヺ' || it == 'ー' })
                    candidates += end to normalize(KanaRomaji.convert(surface))
            }
            when (original[start]) {
                'を' -> candidates += start + 1 to "wo"
                'は' -> candidates += start + 1 to "wa"
                'へ' -> candidates += start + 1 to "e"
                in '0'..'9' -> digits[original[start] - '0'].forEach { candidates += start + 1 to normalize(KanaRomaji.convert(it)) }
            }
            for ((end, reading) in candidates) {
                if (reading.isEmpty() || !expected.startsWith(reading, offset)) continue
                val next = offset + reading.length
                val tail = solve(end, next) ?: continue
                val textEnd = if (next == positions.size) pronunciation.length else positions[next]
                val actual = pronunciation.substring(positions[offset], textEnd).trim()
                return listOf(ReadingUnit(start, end, actual)) + tail
            }
            failed += state
            return null
        }
        return solve(0, 0)?.takeIf { it.isNotEmpty() }
    }

    internal fun reading(text: String): String? = units(text)?.joinToString(" ") { it.text }

    @Synchronized internal fun units(text: String): List<ReadingUnit>? {
        if (text.length > 500) return null
        if (readings.containsKey(text)) return readings[text]
        val tokens = tokenizer.tokenize(text)
        val raw = tokens.map { token ->
            val surface = token.surface
            val value = token.reading?.takeUnless { it == "*" || it.isBlank() } ?: surface
            if (kanji.containsMatchIn(value)) return null.also { readings[text] = null }
            when {
                token.partOfSpeechLevel1 == "助詞" && surface == "は" -> "ワ"
                token.partOfSpeechLevel1 == "助詞" && surface == "へ" -> "エ"
                token.partOfSpeechLevel1 == "助詞" && surface == "を" -> "オ"
                else -> value
            }
        }
        val parts = mutableListOf<ReadingUnit>()
        raw.forEachIndexed { index, value ->
            val roman = KanaRomaji.convert(value, raw.getOrNull(index + 1).orEmpty()).trim()
            if (roman.isNotBlank()) {
                val token = tokens[index]
                val part = ReadingUnit(token.position, token.position + token.surface.length, roman)
                if (index > 0 && raw[index - 1].endsWith("ッ") && parts.isNotEmpty()) {
                    val previous = parts.removeAt(parts.lastIndex)
                    parts.add(ReadingUnit(previous.start, part.end, previous.text + part.text))
                } else parts.add(part)
            }
        }
        val result = parts.takeIf { it.isNotEmpty() }
        readings[text] = result
        return result
    }

}

internal object KanaRomaji {
    private val syllables = buildMap<String, String> {
        val rows = listOf(
            "アイウエオ" to "a i u e o", "カキクケコ" to "ka ki ku ke ko",
            "ガギグゲゴ" to "ga gi gu ge go", "サシスセソ" to "sa shi su se so",
            "ザジズゼゾ" to "za ji zu ze zo", "タチツテト" to "ta chi tsu te to",
            "ダヂヅデド" to "da ji zu de do", "ナニヌネノ" to "na ni nu ne no",
            "ハヒフヘホ" to "ha hi fu he ho", "バビブベボ" to "ba bi bu be bo",
            "パピプペポ" to "pa pi pu pe po", "マミムメモ" to "ma mi mu me mo",
            "ヤユヨ" to "ya yu yo", "ラリルレロ" to "ra ri ru re ro", "ワヰヱヲンヴ" to "wa i e o n vu",
            "ァィゥェォャュョヮ" to "a i u e o ya yu yo wa",
        )
        rows.forEach { (characters, latin) -> characters.toList().zip(latin.split(' ')).forEach { (c, r) -> put(c.toString(), r) } }
        listOf("キ" to "ky", "ギ" to "gy", "シ" to "sh", "ジ" to "j", "チ" to "ch", "ヂ" to "j",
            "ニ" to "ny", "ヒ" to "hy", "ビ" to "by", "ピ" to "py", "ミ" to "my", "リ" to "ry").forEach { (c, r) ->
            listOf("ャ" to "a", "ュ" to "u", "ョ" to "o").forEach { (small, vowel) -> put(c + small, r + vowel) }
        }
        listOf("シェ" to "she", "ジェ" to "je", "チェ" to "che", "ティ" to "ti", "ディ" to "di",
            "トゥ" to "tu", "ドゥ" to "du", "ツァ" to "tsa", "ツィ" to "tsi", "ツェ" to "tse", "ツォ" to "tso",
            "ファ" to "fa", "フィ" to "fi", "フェ" to "fe", "フォ" to "fo", "フュ" to "fyu",
            "ウィ" to "wi", "ウェ" to "we", "ウォ" to "wo", "ヴァ" to "va", "ヴィ" to "vi", "ヴェ" to "ve", "ヴォ" to "vo").forEach { put(it.first, it.second) }
    }

    fun convert(input: String, following: String = ""): String {
        val text = input.map { if (it in 'ぁ'..'ゖ') (it.code + 0x60).toChar() else it }.joinToString("")
        return buildString {
            var index = 0
            while (index < text.length) {
                val c = text[index]
                if (c == 'ッ') {
                    val next = if (index + 1 < text.length) text.substring(index + 1, minOf(index + 3, text.length))
                        else following.take(2).map { if (it in 'ぁ'..'ゖ') (it.code + 0x60).toChar() else it }.joinToString("")
                    val nextRoma = syllables[next] ?: syllables[next.take(1)]
                    if (nextRoma != null && nextRoma.first() !in "aeioun") append(if (nextRoma.startsWith("ch")) 't' else nextRoma.first())
                    index++
                } else if (c == 'ー') {
                    lastOrNull()?.takeIf { it in "aeiou" }?.let { append(it) }
                    index++
                } else {
                    val pair = text.substring(index, minOf(index + 2, text.length))
                    val roma = syllables[pair]
                    if (roma != null) { append(roma); index += 2 }
                    else {
                        append(syllables[c.toString()] ?: c.toString())
                        if (c == 'ン' && index + 1 < text.length && (syllables[text[index + 1].toString()]?.first()?.let { it in "aeiouy" } == true)) append('\'')
                        index++
                    }
                }
            }
        }
    }
}
