package com.tcrrry.lyrics.engine.hook

import android.content.Context
import com.tcrrry.lyrics.sources.DirectLyricsRepository
import com.tcrrry.lyrics.sources.SupplementTranslation
import com.tcrrry.lyrics.engine.lyrics.ChineseSubtitlePolicy
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Adds machine translation only where the selected provider has no translation. */
internal class DesktopLyricsSupplement(private val context: Context) {
    data class Outcome(val result: DirectLyricsRepository.Result, val status: String, val retryable: Boolean = false)
    private data class TimedLine(val at: Long, val text: String)
    private val stamp = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")

    fun fill(result: DirectLyricsRepository.Result, mode: String): Outcome {
        if (mode !in setOf("offline", "api")) {
            return Outcome(result, "补充翻译已关闭")
        }
        val original = lines(result.lyrics)
        if (original.isEmpty()) return Outcome(result, "没有可补译的歌词")
        if (ChineseSubtitlePolicy.isChineseSong(original.map { it.text })) {
            return Outcome(result.copy(translatedLyrics = "", supplementalTranslationKind = "",
                supplementalTranslationStarts = emptySet()), "中文歌词无需补译")
        }
        val official = lines(result.translatedLyrics)
        val missing = original.withIndex().filter { (_, line) ->
            ChineseSubtitlePolicy.needsChineseTranslation(line.text) && official.none {
                kotlin.math.abs(it.at - line.at) <= 1_200L &&
                    ChineseSubtitlePolicy.usable(line.text, it.text) != null
            }
        }
        if (missing.isEmpty()) {
            return Outcome(result, "已使用平台译文，无需补译")
        }
        val payload = JSONArray().apply {
            missing.forEach { (index, line) ->
                put(JSONObject().put("id", index).put("text", line.text))
            }
        }
        val generated = mutableMapOf<Int, String>()
        val missingIds = missing.map { it.index }.toSet()
        val attempt = runCatching {
            if (mode == "offline") EmbeddedMlKit.initialize(context)
            else runCatching { EmbeddedMlKit.initialize(context) }
            runBlocking {
                SupplementTranslation(context).translate(payload.toString()) { row ->
                    val id = row.optInt("id", -1)
                    val text = row.optString("text").trim().replace(Regex("\\s+"), " ")
                    if (id in missingIds) ChineseSubtitlePolicy.usable(original[id].text, text)
                        ?.let { generated[id] = it }
                }
            }
        }
        val status = when {
            generated.isNotEmpty() -> "${if (mode == "api") "API 翻译" else "离线机翻"}已补译 ${generated.size} 句"
            attempt.isFailure -> attempt.exceptionOrNull()?.message?.take(150).orEmpty().ifBlank { "翻译暂不可用" }
            else -> "当前歌曲无需补译或源语言为中文"
        }
        if (generated.isEmpty()) return Outcome(result, status, retryable = true)
        val generatedStarts = mutableSetOf<Long>()
        val all = original.mapIndexedNotNull { index, line ->
            val officialLine = official.minByOrNull { kotlin.math.abs(it.at - line.at) }
                ?.takeIf { kotlin.math.abs(it.at - line.at) <= 1_200L }
            val text = ChineseSubtitlePolicy.usable(line.text, officialLine?.text)
                ?: generated[index]?.also { generatedStarts.add(line.at) }
            text?.let { TimedLine(line.at, it) }
        }
        return Outcome(result.copy(translatedLyrics = all.joinToString("\n") { line ->
            val minute = line.at / 60_000L
            val second = line.at / 1_000L % 60L
            val millis = line.at % 1_000L
            String.format(Locale.US, "[%02d:%02d.%03d]%s", minute, second, millis, line.text)
        }, supplementalTranslationKind = mode, supplementalTranslationStarts = generatedStarts), status, retryable = generated.size < missing.size)
    }

    private fun lines(raw: String): List<TimedLine> = raw.lineSequence().flatMap { line ->
        val text = stamp.replace(line, "").trim()
        if (text.isBlank()) return@flatMap emptySequence()
        stamp.findAll(line).map { match ->
            val fraction = match.groupValues[3].takeIf(String::isNotBlank)
                ?.let { ("0.$it".toDouble() * 1000).toLong() } ?: 0L
            TimedLine(match.groupValues[1].toLong() * 60_000L +
                match.groupValues[2].toLong() * 1_000L + fraction, text)
        }
    }.take(1000).toList()
}
