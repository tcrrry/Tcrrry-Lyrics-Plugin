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
        private val characterReadings = HashMap<Int, MutableSet<String>>()
        private val entryReading = Regex("[a-z]+[1-6](?: [a-z]+[1-6])*")
        init {
            chunks.forEach { chunk -> chunk.lineSequence().forEach { line ->
                val fields = line.split('\t')
                if (fields.size < 2 || fields[0].isBlank() || !entryReading.matches(fields[1])) return@forEach
                val original = fields[0]
                if (original.codePointCount(0, original.length) != fields[1].split(' ').size) return@forEach
                val weight = fields.getOrNull(2)?.removeSuffix("%")?.toFloatOrNull() ?: 100f
                for (key in setOf(original, simplify(original))) {
                    if(key.codePointCount(0,key.length)!=fields[1].split(' ').size) continue
                    key.codePoints().toArray().zip(fields[1].split(' ')).forEach { (code,reading) ->
                        characterReadings.getOrPut(code) { HashSet() }.add(reading.dropLast(1))
                    }
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
                    // Keep literal Latin words; do not discard an otherwise Cantonese line.
                    if(code in 'A'.code..'Z'.code || code in 'a'.code..'z'.code || code in '0'.code..'9'.code) {
                        val start=cursor
                        while(cursor<text.length && (text[cursor].isLetterOrDigit() && text[cursor].code<128 || text[cursor] in "'’-")) cursor++
                        result.add(Unit(start,cursor,text.substring(start,cursor)));continue
                    }
                    if (Character.isLetterOrDigit(code)) return null
                    cursor += Character.charCount(code); continue
                }
                val end = (minOf(text.length, cursor + maxLength) downTo cursor + 1)
                    .firstOrNull { readings.containsKey(text.substring(cursor, it)) } ?: return null
                val phonetics = readings.getValue(text.substring(cursor, end)).first.split(' ')
                var character = cursor
                phonetics.forEach { reading ->
                    val next = character + Character.charCount(text.codePointAt(character))
                    result.add(Unit(character, next, CantoneseReadingDisplay.convert(reading))); character = next
                }
                cursor = end
            }
            return result.takeIf { it.isNotEmpty() }
        }
        /** Accept provider text only when every syllable matches a dictionary reading for its Han glyph. */
        fun alignedUnits(original:String, pronunciation:String):List<Unit>? = align(original,pronunciation,true)
        fun providerUnits(original:String,pronunciation:String):List<Unit>? = align(original,pronunciation,false)
        private fun align(original:String, pronunciation:String, verifySound:Boolean):List<Unit>? {
            if(original.length>500 || pronunciation.length>2000) return null
            val tokenPattern=Regex("[A-Za-z]+(?:['’-][A-Za-z]+)*[1-6]?|[0-9]+")
            val tokens=tokenPattern.findAll(pronunciation).toList()
            if(tokenPattern.replace(pronunciation,"").any(Char::isLetterOrDigit)) return null
            val result=mutableListOf<Unit>();var cursor=0;var tokenIndex=0;var han=false
            while(cursor<original.length) {
                val code=original.codePointAt(cursor);val end=cursor+Character.charCount(code)
                if(Character.UnicodeScript.of(code)==Character.UnicodeScript.HAN) {
                    han=true
                    val token=tokens.getOrNull(tokenIndex++)?.value ?: return null
                    val plain=token.lowercase(Locale.ROOT).trimEnd { it in '1'..'6' }
                    if(verifySound && plain !in characterReadings[code].orEmpty()) return null
                    result+=Unit(cursor,end,token);cursor=end
                } else if(code<128 && Character.isLetterOrDigit(code)) {
                    val start=cursor
                    while(cursor<original.length && (original[cursor].code<128 && original[cursor].isLetterOrDigit() || original[cursor] in "'’-")) cursor++
                    val literal=original.substring(start,cursor)
                    val token=tokens.getOrNull(tokenIndex++)?.value ?: return null
                    if(!literal.replace('’','\'').equals(token.replace('’','\''),true)) return null
                    result+=Unit(start,cursor,token)
                } else {
                    if(Character.isLetterOrDigit(code)) return null
                    cursor=end
                }
            }
            return result.takeIf { han && tokenIndex==tokens.size }

        }

    }
    // Per-song Cantonese opt-in or an identified Chinese platform track supplies reading identity.
    // The provider remains authoritative; this registry never changes its text or timing.
    private val providerRevision=java.util.concurrent.atomic.AtomicLong()
    internal val alignmentRevision:Long get()=providerRevision.get()
    private val providerRows=object:LinkedHashMap<Pair<String,String>,Boolean>(128,.75f,true) {
        override fun removeEldestEntry(eldest:MutableMap.MutableEntry<Pair<String,String>,Boolean>?)=size>512
    }
    private fun providerKey(original:String,reading:String)=ChineseTextKey.of(original.filterNot(Char::isWhitespace)) to
        reading.filter(Char::isLetterOrDigit).lowercase(Locale.ROOT)
    private val providerAlignment by lazy { Cantonese(emptyList()) { it } }
    internal fun rememberChineseProviderRows(originals:List<Pair<Long,String>>,native:List<Pair<Long,String>>) {
        rememberProviderRows(originals,native)
    }
    internal fun rememberChineseProviderRow(original:String,reading:String) {
        if(providerAlignment.providerUnits(original,reading)!=null)
            synchronized(providerRows) { if(providerRows.put(providerKey(original,reading),true)!=true) providerRevision.incrementAndGet() }
    }
    private fun rememberProviderRows(originals:List<Pair<Long,String>>,native:List<Pair<Long,String>>) {
        originals.forEach { (start,text) ->
            val nearest=native.minByOrNull { kotlin.math.abs(it.first-start) } ?: return@forEach
            if(kotlin.math.abs(nearest.first-start)>1200L ||
                kotlin.math.abs(nearest.first-start)!=originals.minOf { kotlin.math.abs(it.first-nearest.first) }) return@forEach
            rememberChineseProviderRow(text,nearest.second)
        }
    }
    private data class Engines(val fingerprint: String, val korean: Korean?, val cantonese: Cantonese?)
    private var engines: Engines? = null
    fun marker(context: Context, id: Long): String {
        val prefs = context.pluginPreferences("japanese_pronunciation", Context.MODE_PRIVATE)
        return "<!--bundled-pronunciation-r7 korean=${prefs.getBoolean("korean_enabled", true)} yueSong=${prefs.getBoolean("cantonese_$id", false)}-->"
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
        val native=DesktopLyricsTtmlConverter.pronunciationEntries(source)
        if(enabledYue) rememberProviderRows(originalLines,native)
        val missingLines=originalLines.filter { (start,text) -> !hasExisting(native,start,text) }
        val current = engines(context, enabledKorean && missingLines.any { Regex("[가-힣]").containsMatchIn(it.second) },
            enabledYue && missingLines.any { Regex("[\\p{IsHan}]").containsMatchIn(it.second) })
        return fill(source, enabledKorean, enabledYue, current.korean, current.cantonese)
    }
    private fun hasExisting(native:List<Pair<Long,String>>,start:Long,text:String):Boolean =
        native.minByOrNull { kotlin.math.abs(it.first-start) }?.let {
            kotlin.math.abs(it.first-start)<=1200L && it.second.isNotBlank() && !it.second.equals(text.trim(),true)
        } == true

    internal fun alignedCantoneseUnits(original:String,reading:String):List<Unit>? {
        if(Build.VERSION.SDK_INT<29) return null
        if(!Regex("[\\p{IsHan}]").containsMatchIn(original) || Regex("[ぁ-ゖァ-ヺ가-힣]").containsMatchIn(original)) return null
        if(synchronized(providerRows) { providerRows[providerKey(original,reading)]==true })
            return providerAlignment.providerUnits(original,reading)
        val engine=engines(com.tcrrry.lyrics.plugin.PluginLifetime.context.application,false,true).cantonese ?: return null
        return engine.alignedUnits(original,reading)
    }
    @RequiresApi(29)
    internal fun fill(source: DirectLyricsRepository.Result, enabledKorean: Boolean, enabledYue: Boolean,
        korean: Korean?, cantonese: Cantonese?): DirectLyricsRepository.Result {
        val originalLines = DesktopLyricsTtmlConverter.pronunciationLines(source)
        val native = DesktopLyricsTtmlConverter.pronunciationEntries(source)
        if(enabledYue) rememberProviderRows(originalLines,native)
        if ((!enabledKorean || korean == null) && (!enabledYue || cantonese == null)) return source
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
        if(enabledYue) rememberProviderRows(originalLines,added.filter { it.second=="yue" }.map {
            it.first to it.third.joinToString(" ") { unit -> unit.text }
        })
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
