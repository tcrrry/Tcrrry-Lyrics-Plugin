package com.tcrrry.lyrics.engine.lyrics
import com.tcrrry.lyrics.plugin.PluginStorage.pluginPreferences

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import android.icu.text.Transliterator
import com.tcrrry.lyrics.sources.DirectLyricsRepository
import java.text.Normalizer
import java.util.Locale

internal object LanguagePronunciationSupplement {
    internal data class Unit(val start: Int, val end: Int, val text: String)
    @RequiresApi(29)
    internal class Korean(xml: String) {
        private val rules = Regex("<tRule><!\\[CDATA\\[(.*?)]]></tRule>", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1) ?: error("缺少韩语规则")
        private val converter = Transliterator.createFromRules("AMPP-Korean", rules, Transliterator.REVERSE)
        @Synchronized fun units(text: String): List<Unit>? {
            if (Regex("[\\p{IsHan}ぁ-ゖァ-ヺ]").containsMatchIn(text)) return null
            if (text.codePoints().anyMatch { code -> Character.isLetter(code) &&
                    Character.UnicodeScript.of(code) !in setOf(Character.UnicodeScript.HANGUL, Character.UnicodeScript.LATIN) }) return null
            val words = Regex("[가-힣]+|[A-Za-z0-9]+(?:['’-][A-Za-z]+)*").findAll(text).toList()
            if (words.none { Regex("[가-힣]").containsMatchIn(it.value) }) return null
            return words.map { word ->
                val reading = if (Regex("[가-힣]").containsMatchIn(word.value)) converter.transliterate(Normalizer.normalize(word.value, Normalizer.Form.NFD)) else word.value
                if (Regex("[가-힣ᄀ-ᇿ]").containsMatchIn(reading)) return null
                Unit(word.range.first, word.range.last + 1, reading)
            }
        }
    }
    internal class Cantonese(chunks: List<String>, simplify: (String) -> String) {
        private val readings = HashMap<String, Pair<String, Float>>()
        private var maxLength = 1
        init {
            chunks.forEach { chunk -> chunk.lineSequence().forEach { line ->
                val fields = line.split('\t')
                if (fields.size < 2 || fields[0].isBlank() || !Regex("[a-z]+[1-6](?: [a-z]+[1-6])*").matches(fields[1])) return@forEach
                val original = fields[0]
                if (original.codePointCount(0, original.length) != fields[1].split(' ').size) return@forEach
                val weight = fields.getOrNull(2)?.removeSuffix("%")?.toFloatOrNull() ?: 100f
                for (key in setOf(original, simplify(original))) {
                    if ((readings[key]?.second ?: -1f) < weight) readings[key] = fields[1] to weight
                    maxLength = maxOf(maxLength, key.length.coerceAtMost(32))
                }
            } }
        }
        fun units(text: String): List<Unit>? {
            if (Regex("[가-힣ぁ-ゖァ-ヺ]").containsMatchIn(text) || !Regex("[\\p{IsHan}]").containsMatchIn(text)) return null
            val result = mutableListOf<Unit>()
            var cursor = 0
            while (cursor < text.length) {
                val code = text.codePointAt(cursor)
                if (Character.UnicodeScript.of(code) != Character.UnicodeScript.HAN) {
                    // Leave punctuation and whitespace out of phonetic spans. Mixed words are not guessed.
                    if (Character.isLetterOrDigit(code)) return null
                    cursor += Character.charCount(code); continue
                }
                val end = (minOf(text.length, cursor + maxLength) downTo cursor + 1)
                    .firstOrNull { readings.containsKey(text.substring(cursor, it)) } ?: return null
                val phonetics = readings.getValue(text.substring(cursor, end)).first.split(' ')
                var character = cursor
                phonetics.forEach { reading ->
                    val next = character + Character.charCount(text.codePointAt(character))
                    result.add(Unit(character, next, reading)); character = next
                }
                cursor = end
            }
            return result.takeIf { it.isNotEmpty() }
        }
    }
    private data class Engines(val fingerprint: String, val korean: Korean?, val cantonese: Cantonese?)
    private var engines: Engines? = null
    fun marker(context: Context, id: Long): String {
        val prefs = context.pluginPreferences("japanese_pronunciation", Context.MODE_PRIVATE)
        return "<!--bundled-pronunciation-r4 korean=${prefs.getBoolean("korean_enabled", true)} yueSong=${prefs.getBoolean("cantonese_$id", false)}-->"
    }
    @RequiresApi(29)
    @Synchronized private fun engines(context: Context, koreanNeeded: Boolean, cantoneseNeeded: Boolean): Engines {
        val stamp = context.packageName
        val previous = engines?.takeIf { it.fingerprint == stamp }
        if ((!koreanNeeded || previous?.korean != null) && (!cantoneseNeeded || previous?.cantonese != null)) return previous ?: Engines(stamp, null, null)
        fun files(language: String): List<String> = PronunciationModules.files(language)
        val korean = previous?.korean ?: if (koreanNeeded) runCatching { files("ko").firstOrNull()?.let(::Korean) }.onFailure { Log.w("AMPP-Lyrics", "Bundled Korean initialization failed", it) }.getOrNull() else null
        val cantonese = previous?.cantonese ?: if (cantoneseNeeded) runCatching {
            val data = files("yue")
            val simplify = Transliterator.getInstance("Traditional-Simplified")
            Cantonese(data.take(2), simplify::transliterate)
        }.onFailure { Log.w("AMPP-Lyrics", "Bundled Cantonese initialization failed", it) }.getOrNull() else null
        return Engines(stamp, korean, cantonese).also { engines = it }
    }
    fun fill(context: Context, id: Long, source: DirectLyricsRepository.Result): DirectLyricsRepository.Result {
        if (Build.VERSION.SDK_INT < 29) return source
        val prefs = context.pluginPreferences("japanese_pronunciation", Context.MODE_PRIVATE)
        val enabledYue = prefs.getBoolean("cantonese_$id", false)
        val enabledKorean = prefs.getBoolean("korean_enabled", true)
        val originalLines = DesktopLyricsTtmlConverter.pronunciationLines(source)
        val current = engines(context, enabledKorean && originalLines.any { Regex("[가-힣]").containsMatchIn(it.second) },
            enabledYue && originalLines.any { Regex("[\\p{IsHan}]").containsMatchIn(it.second) })
        return fill(source, enabledKorean, enabledYue, current.korean, current.cantonese)
    }
    @RequiresApi(29)
    internal fun fill(source: DirectLyricsRepository.Result, enabledKorean: Boolean, enabledYue: Boolean,
        korean: Korean?, cantonese: Cantonese?): DirectLyricsRepository.Result {
        val originalLines = DesktopLyricsTtmlConverter.pronunciationLines(source)
        if ((!enabledKorean || korean == null) && (!enabledYue || cantonese == null)) return source
        val native = DesktopLyricsTtmlConverter.pronunciationEntries(source)
        val added = originalLines.mapNotNull { (start, text) ->
            if (text.length > 500) return@mapNotNull null
            val existing = native.minByOrNull { kotlin.math.abs(it.first - start) }
                ?.takeIf { kotlin.math.abs(it.first - start) <= 1200L && it.second.isNotBlank() && !it.second.equals(text.trim(), true) }
            if (existing != null) return@mapNotNull null
            val language = if (Regex("[가-힣]").containsMatchIn(text)) "ko" else if (enabledYue) "yue" else return@mapNotNull null
            val units = if (language == "ko" && enabledKorean) korean?.units(text) else if (language == "ko") null else cantonese?.units(text)
            units?.let { Triple(start, language, it) }
        }
        if (added.isEmpty()) return source
        val replaced = added.mapNotNull { (start, _, _) -> native.minByOrNull { kotlin.math.abs(it.first - start) }?.takeIf { kotlin.math.abs(it.first - start) <= 1200L } }.toSet()
        return source.copy(
            romanizedLyrics = (native.filterNot { it in replaced } + added.map { it.first to it.third.joinToString(" ") { unit -> unit.text } })
                .sortedBy { it.first }.joinToString("\n") { (start, text) -> String.format(Locale.ROOT, "[%02d:%02d.%03d]%s", start / 60000, start / 1000 % 60, start % 1000, text) },
            supplementalPronunciationStarts = source.supplementalPronunciationStarts + added.map { it.first },
            supplementalPronunciationLanguages = source.supplementalPronunciationLanguages + added.associate { it.first to it.second },
            supplementalPronunciationUnits = source.supplementalPronunciationUnits + added.associate { row ->
                row.first to row.third.map { DirectLyricsRepository.PronunciationUnit(it.start, it.end, it.text) }
            },
        )
    }
}
