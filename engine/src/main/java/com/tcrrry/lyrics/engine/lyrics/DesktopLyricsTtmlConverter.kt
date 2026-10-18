package com.tcrrry.lyrics.engine.lyrics

import com.tcrrry.lyrics.sources.DirectLyricsRepository
import java.util.Locale

/** Converts Desktop Lyrics' LRC/YRC result into the format consumed by Apple's parser. */
internal object DesktopLyricsTtmlConverter {
    private data class Word(val start: Long, val end: Long, val text: String)
    private data class Line(val start: Long, val end: Long, val words: List<Word>,
        val sourceText: String = words.joinToString("") { it.text })
    private data class TimedText(val start: Long, val text: String)

    private val wordLine = Regex("^\\[(\\d+),(\\d+)\\](.*)$")
    private val wordToken = Regex("\\((\\d+),(\\d+)(?:,\\d+)?\\)")
    private val lrcStamp = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?\\]")

    internal fun pronunciationLines(result: DirectLyricsRepository.Result): List<Pair<Long, String>> {
        val words = parseWords(result.wordLyrics)
        return if (words.isNotEmpty()) words.map { it.start to it.words.joinToString("") { word -> word.text } }
        else parseLrc(result.lyrics).map { it.start to it.text }
    }

    internal fun pronunciationEntries(result: DirectLyricsRepository.Result): List<Pair<Long, String>> =
        parseLrc(result.romanizedLyrics).map { it.start to it.text }

    fun convert(
        result: DirectLyricsRepository.Result,
        expectedDurationMs: Long = 0L,
        offsetMs: Int = 0,
        primaryPronunciation: Boolean = false,
        smoothShortUnits: Boolean = false,
        hideOpeningCredits: Boolean = false,
    ): String? {
        val timed = parseLrc(result.lyrics)
        val ordinary = if (timed.isNotEmpty()) timed else {
            val plain = result.lyrics.lineSequence().map(String::trim)
                .filter { it.isNotBlank() && !it.matches(Regex("^\\[[^]]+]$")) }
                .take(200).toList()
            val duration = expectedDurationMs.takeIf { it > 0L } ?: result.durationMs
            val interval = if (plain.isNotEmpty() && duration > 0L)
                (duration / plain.size).coerceIn(1_500L, 10_000L) else 4_000L
            plain.mapIndexed { index, text -> TimedText(index * interval, text) }
        }
        val words = parseWords(result.wordLyrics)
        val lines = if (words.isNotEmpty()) words else ordinary.mapIndexed { index, line ->
            val next = ordinary.getOrNull(index + 1)?.start
                ?: expectedDurationMs.takeIf { it > line.start }
                ?: line.start + 4_000L
            val end = maxOf(line.start + 400L, minOf(next, line.start + 10_000L))
            Line(line.start, end, listOf(Word(line.start, end, line.text)))
        }
        if (lines.isEmpty()) return null
        val hidden = OpeningCreditsPolicy.hiddenIndices(result.source,
            lines.map { it.sourceText }, hideOpeningCredits,
            result.title, result.artist, lines.map { it.start })
        val visibleKeys = lines.indices.filterNot { it in hidden }.withIndex()
            .associate { it.value to "L${it.index + 1}" }
        val translations = parseLrc(result.translatedLyrics)
        val chineseSong = ChineseSubtitlePolicy.isChineseSong(lines.filterIndexed { index, _ -> index !in hidden }
            .map { it.words.joinToString("") { word -> word.text } })
        val selectedTranslations = lines.mapIndexed { index, line ->
            if (index in hidden) return@mapIndexed null
            val closest = translations.minByOrNull { kotlin.math.abs(it.start - line.start) }
                ?.takeIf { kotlin.math.abs(it.start - line.start) <= 1_200L }
            val text = if (chineseSong) null else ChineseSubtitlePolicy.usable(
                line.words.joinToString("") { it.text }, closest?.text)
            closest?.takeIf { text != null }?.copy(text = requireNotNull(text))
        }
        val translated = selectedTranslations.map { it?.text }
        val hasTranslation = translated.any { !it.isNullOrBlank() }
        val romanized = parseLrc(result.romanizedLyrics)
        val selectedPronunciation = lines.mapIndexed { index, line ->
            if (index in hidden) return@mapIndexed null
            romanized.minByOrNull { kotlin.math.abs(it.start - line.start) }
                ?.takeIf { candidate ->
                    val distance = kotlin.math.abs(candidate.start - line.start)
                    distance <= 1_200L && distance == lines.minOf { other -> kotlin.math.abs(candidate.start - other.start) }
                }
                ?.takeUnless { it.text.equals(line.words.joinToString("") { word -> word.text }.trim(), ignoreCase = true) }
        }
        val pronunciation = selectedPronunciation.map { it?.text }
        val hasPronunciation = pronunciation.any { !it.isNullOrBlank() }
        val emphasizePronunciation = primaryPronunciation && hasPronunciation
        val nativePronunciationWords = parseWords(result.romanizedWordLyrics)
        val mapped = if (emphasizePronunciation && words.isNotEmpty()) lines.mapIndexed { index, line ->
            pronunciation[index]?.takeIf(String::isNotBlank)?.let { text ->
                nativePronunciationWords.minByOrNull { kotlin.math.abs(it.start - line.start) }
                    ?.takeIf { kotlin.math.abs(it.start - line.start) <= 1200L &&
                        normalizedReading(it.words.joinToString("") { word -> word.text }) == normalizedReading(text) &&
                        it.words.all { word -> word.start >= line.start - 1200L && word.end <= line.end + 1200L } }
                    ?.words ?: alignPronunciation(line, text, selectedPronunciation[index]?.start?.let { start ->
                        result.supplementalPronunciationUnits[start]?.map { JapanesePronunciationSupplement.ReadingUnit(it.start, it.end, it.text) }
                    })
            }
        } else emptyList()
        // Never create phonetic sub-word timestamps. Incomplete alignment uses
        // native Line mode for the whole displayed track, avoiding fake precision.
        val displayedWordTimed = words.isNotEmpty() && (!emphasizePronunciation ||
            pronunciation.indices.all { pronunciation[it].isNullOrBlank() || mapped.getOrNull(it) != null })
        val displayedLines = if (emphasizePronunciation) lines.mapIndexed { index, line ->
            val phonetic = pronunciation[index]?.takeIf(String::isNotBlank)
            when {
                phonetic == null -> line
                displayedWordTimed -> line.copy(words = requireNotNull(mapped[index]))
                else -> line.copy(words = listOf(Word(line.start, line.end, phonetic)))
            }
        } else lines
        val auxiliaryPronunciation = if (emphasizePronunciation) lines.mapIndexed { index, line ->
            if (pronunciation[index].isNullOrBlank()) null else line.words.joinToString("") { it.text }
        } else pronunciation
        val usedSupplement = selectedTranslations.filterNotNull().any { it.start in result.supplementalTranslationStarts }
        val presentation = DesktopLyricsPresentation(
            source = result.source.takeIf { it in setOf("QQ音乐", "网易云音乐", "LRCLIB") } ?: "未知",
            wordTimed = displayedWordTimed,
            platformTranslation = selectedTranslations.filterNotNull().any { it.start !in result.supplementalTranslationStarts },
            apiTranslation = usedSupplement && result.supplementalTranslationKind == "api",
            offlineTranslation = usedSupplement && result.supplementalTranslationKind == "offline",
            pronunciation = hasPronunciation,
            primaryPronunciation = emphasizePronunciation,
            nativePronunciation = selectedPronunciation.filterNotNull().any { it.start !in result.supplementalPronunciationStarts },
            offlinePronunciation = selectedPronunciation.filterNotNull().any { it.start in result.supplementalPronunciationStarts },
            offlinePronunciationLanguages = selectedPronunciation.filterNotNull().mapNotNull { result.supplementalPronunciationLanguages[it.start] }.toSet(),
        )
        val ttml = buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
            append(presentation.marker())
            append("<tt xmlns=\"http://www.w3.org/ns/ttml\" ")
            append("xmlns:itunes=\"http://music.apple.com/lyric-ttml-internal\" ")
            append("itunes:timing=\"${if (displayedWordTimed) "Word" else "Line"}\"")
            // Match the existing AMLL converter's language workaround: Android
            // Apple Music only exposes both auxiliary tracks under this profile.
            if (hasTranslation || hasPronunciation) append(" xml:lang=\"ko\"")
            append('>')
            if (hasTranslation || hasPronunciation) {
                append("<head><metadata><iTunesMetadata xmlns=\"http://music.apple.com/lyric-ttml-internal\">")
                if (hasTranslation) {
                    append("<translations><translation type=\"subtitle\" xml:lang=\"zh-Hans\">")
                    translated.forEachIndexed { index, value ->
                        if (index in hidden) return@forEachIndexed
                        append("<text for=\"${visibleKeys.getValue(index)}\">${escape(value ?: " ")}</text>")
                    }
                    append("</translation></translations>")
                }
                if (hasPronunciation) {
                    append("<transliterations><transliteration xml:lang=\"ko-Latn\">")
                    auxiliaryPronunciation.forEachIndexed { index, value ->
                        if (index in hidden) return@forEachIndexed
                        append("<text for=\"${visibleKeys.getValue(index)}\">${escape(value ?: " ")}</text>")
                    }
                    append("</transliteration></transliterations>")
                }
                append("</iTunesMetadata></metadata></head>")
            }
            append("<body><div>")
            displayedLines.forEachIndexed { index, line ->
                if (index in hidden) return@forEachIndexed
                val lineBegin = (line.start - offsetMs).coerceAtLeast(0L)
                val lineEnd = (line.end - offsetMs).coerceAtLeast(lineBegin + 1L)
                append("<p begin=\"${stamp(lineBegin)}\" end=\"${stamp(lineEnd)}\" itunes:key=\"${visibleKeys.getValue(index)}\">")
                if (!displayedWordTimed) {
                    append(escape(line.words.joinToString("") { it.text }))
                } else (if (smoothShortUnits) smooth(line.words, emphasizePronunciation && !pronunciation[index].isNullOrBlank()) else line.words).forEach { word ->
                    val wordBegin = (word.start - offsetMs).coerceAtLeast(lineBegin)
                    val wordEnd = (word.end - offsetMs).coerceAtLeast(wordBegin + 1L)
                    // Spaces are lexical separators, not animated glyphs. Preserve even
                    // standalone timed spaces outside the native timed spans.
                    val leading = word.text.takeWhile(Char::isWhitespace)
                    val trailing = word.text.takeLastWhile(Char::isWhitespace)
                    val content = word.text.trim()
                    if (content.isEmpty()) append(escape(word.text)) else {
                        append(escape(leading))
                        append("<span begin=\"${stamp(wordBegin)}\" end=\"${stamp(wordEnd)}\">${escape(content)}</span>")
                        append(escape(trailing))
                    }
                }
                append("</p>")
            }
            append("</div></body></tt>")
        }
        return ttml.takeIf(TtmlInputPolicy::isAcceptable)
    }

    /** Original English word boundaries stay intact; highlighted readings may share a short-unit sweep. */
    private fun smooth(words: List<Word>, pronunciation: Boolean = false): List<Word> {
        val result = words.toMutableList()
        var index = 0
        fun joinable(a: Word, b: Word): Boolean =
            a.text.isNotBlank() && b.text.isNotBlank() &&
                (pronunciation || !(a.text + b.text).any { it.isWhitespace() || it in 'a'..'z' || it in 'A'..'Z' }) &&
                b.start >= a.start && b.start <= a.end + 20L
        while (index < result.size) {
            val word = result[index]
            if (word.end - word.start in 1L..99L) {
                val previous = result.getOrNull(index - 1)
                val next = result.getOrNull(index + 1)
                val target = when {
                    previous != null && joinable(previous, word) -> index - 1
                    next != null && joinable(word, next) -> index
                    else -> -1
                }
                if (target >= 0) {
                    val a = result[target]; val b = result[target + 1]
                    result[target] = Word(minOf(a.start, b.start), maxOf(a.end, b.end), a.text + b.text)
                    result.removeAt(target + 1)
                    index = target
                    continue
                }
            }
            index++
        }
        return result
    }

    private fun normalizedReading(text: String) = text.filter(Char::isLetterOrDigit).lowercase(Locale.ROOT)

    /** Reading groups borrow only actual source token ranges, never fabricated syllable times. */
    internal fun alignNativePronunciation(words: List<Triple<Long, Long, String>>, pronunciation: String,
        smoothShortUnits: Boolean = false): List<Triple<Long, Long, String>>? {
        if (words.isEmpty() || words.any { it.first < 0 || it.second <= it.first }) return null
        if (words.zipWithNext().any { (a, b) -> b.first < a.first }) return null
        val line = Line(words.first().first, words.maxOf { it.second }, words.map { Word(it.first, it.second, it.third) })
        val aligned = alignPronunciation(line, pronunciation) ?: return null
        return (if (smoothShortUnits) smooth(aligned, pronunciation = true) else aligned).map { Triple(it.start, it.end, it.text) }
    }

    private fun alignPronunciation(line: Line, pronunciation: String, suppliedUnits: List<JapanesePronunciationSupplement.ReadingUnit>? = null): List<Word>? {
        val original = line.words.joinToString("") { it.text }
        val korean = KoreanPronunciationAlignment.units(original, pronunciation)?.map {
            JapanesePronunciationSupplement.ReadingUnit(it.start, it.end, it.text)
        }
        if (suppliedUnits == null && korean == null && !Regex("[ぁ-ゖァ-ヺ\\p{IsHan}]").containsMatchIn(original)) return null
        val cantonese = if(suppliedUnits==null && korean==null) LanguagePronunciationSupplement.alignedCantoneseUnits(original,pronunciation)?.map {
            JapanesePronunciationSupplement.ReadingUnit(it.start,it.end,it.text)
        } else null
        val units = suppliedUnits ?: korean ?: cantonese ?: JapanesePronunciationSupplement.alignedUnits(original, pronunciation) ?: return null
        if (normalizedReading(units.joinToString(" ") { it.text }) != normalizedReading(pronunciation)) return null
        var cursor = 0
        val ranges = line.words.map { word ->
            val start = cursor
            cursor += word.text.length
            start until cursor
        }
        val parents = IntArray(units.size) { it }
        fun root(value: Int): Int {
            var index = value
            while (parents[index] != index) { parents[index] = parents[parents[index]]; index = parents[index] }
            return index
        }
        fun overlaps(unit: JapanesePronunciationSupplement.ReadingUnit, range: IntRange) =
            unit.start <= range.last && unit.end > range.first
        // If one original timestamp covers several reading tokens, those tokens
        // share a group. If a reading spans several words, their real times merge.
        ranges.forEach { range ->
            val matches = units.indices.filter { overlaps(units[it], range) }
            matches.drop(1).forEach { parents[root(it)] = root(matches.first()) }
        }
        val groups = units.indices.groupBy(::root).values.sortedBy { it.first() }
        return groups.map { group ->
            val touched = line.words.indices.filter { index -> group.any { overlaps(units[it], ranges[index]) } }
            if (touched.isEmpty()) return null
            Word(touched.minOf { line.words[it].start }, touched.maxOf { line.words[it].end },
                group.joinToString(" ") { units[it].text } + if (group.last() != units.lastIndex) " " else "")
        }
    }

    fun hasWordTiming(result: DirectLyricsRepository.Result): Boolean = parseWords(result.wordLyrics).isNotEmpty()

    fun preview(result: DirectLyricsRepository.Result, hideOpeningCredits: Boolean, maxLines: Int = 16): String {
        val words = parseWords(result.wordLyrics)
        val timed = if (words.isNotEmpty()) words.map { TimedText(it.start, it.sourceText) }
            else parseLrc(result.lyrics)
        val texts = if (timed.isNotEmpty()) timed.map { it.text } else result.lyrics.lineSequence()
            .map(String::trim).filter { it.isNotBlank() && !it.matches(Regex("^\\[[^]]+]$")) }.toList()
        val hidden = OpeningCreditsPolicy.hiddenIndices(result.source, texts, hideOpeningCredits,
            result.title, result.artist, timed.map { it.start })
        return texts.withIndex().filterNot { it.index in hidden }.take(maxLines).joinToString("\n") { (index, text) ->
            timed.getOrNull(index)?.let { "[${stamp(it.start).padStart(9, '0')}]$text" } ?: text
        }
    }

    private fun parseWords(raw: String): List<Line> = raw.lineSequence().mapNotNull { text ->
        val match = wordLine.matchEntire(text.trim()) ?: return@mapNotNull null
        val start = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
        val duration = match.groupValues[2].toLongOrNull() ?: return@mapNotNull null
        val body = match.groupValues[3]
        val markers = wordToken.findAll(body).toList()
        val words = markers.mapIndexedNotNull { index, marker ->
            val begin = marker.groupValues[1].toLongOrNull() ?: return@mapIndexedNotNull null
            val length = marker.groupValues[2].toLongOrNull() ?: return@mapIndexedNotNull null
            val value = body.substring(marker.range.last + 1,
                markers.getOrNull(index + 1)?.range?.first ?: body.length)
            if (value.isEmpty() || (length <= 0L && !value.isBlank())) null else Word(begin, begin + length.coerceAtLeast(0L), value)
        }
        // Zero-duration QQ punctuation is not a sung span, but is essential to detect credits.
        if (words.isEmpty()) null else Line(start, maxOf(start + duration, words.maxOf(Word::end)), words,
            body.replace(wordToken, ""))
    }.sortedBy(Line::start).take(4096).toList()

    private fun parseLrc(raw: String): List<TimedText> = raw.lineSequence().flatMap { line ->
        val stamps = lrcStamp.findAll(line).toList()
        val text = lrcStamp.replace(line, "").trim()
        if (text.isBlank() || stamps.isEmpty()) emptySequence() else stamps.asSequence().map { stamp ->
            val fraction = stamp.groupValues[3].takeIf(String::isNotEmpty)
                ?.let { ("0.$it".toDouble() * 1000).toLong() } ?: 0L
            TimedText(stamp.groupValues[1].toLong() * 60_000L +
                stamp.groupValues[2].toLong() * 1_000L + fraction, text)
        }
    }.sortedBy(TimedText::start).take(4096).toList()

    private fun stamp(ms: Long): String = String.format(Locale.US, "%d:%02d.%03d",
        ms.coerceAtLeast(0L) / 60_000L, ms.coerceAtLeast(0L) / 1_000L % 60L,
        ms.coerceAtLeast(0L) % 1_000L)

    private fun escape(value: String): String = value.replace("&", "&amp;")
        .replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
