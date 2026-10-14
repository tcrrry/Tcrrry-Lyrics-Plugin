package com.tcrrry.lyrics.engine.hook
import com.tcrrry.lyrics.plugin.PluginStorage.pluginFilesDir
import com.tcrrry.lyrics.plugin.PluginStorage.pluginPreferences

import android.content.Context
import com.tcrrry.lyrics.sources.DirectLyricsRepository
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Keeps the original provider payload so a saved version can be restored offline. */
internal object TcrrryLyricsHistory {
    private fun file(context: Context, id: Long) = File(context.pluginFilesDir, "tcrrry-lyrics-history/$id.json")

    @Synchronized fun entries(context: Context, id: Long): List<DirectLyricsRepository.Result> = runCatching {
        val file = file(context, id)
        if (!file.isFile || file.length() > 8 * 1024 * 1024) return@runCatching emptyList()
        val array = JSONArray(file.readText())
        file.setLastModified(System.currentTimeMillis())
        (0 until array.length()).mapNotNull { index -> array.optJSONObject(index)?.let(::decode) }
    }.getOrDefault(emptyList())

    @Synchronized fun remember(context: Context, id: Long, result: DirectLyricsRepository.Result) {
        if (id <= 0 || result.recordId.isBlank() || result.lyrics.isBlank()) return
        val epoch = LyricsCacheStorage.epoch
        LyricsCacheStorage.store(context, epoch) { runCatching {
            val all = entries(context, id).filterNot { it.source == result.source && it.recordId == result.recordId } + result
            val destination = file(context, id)
            destination.parentFile?.mkdirs()
            val pending = File(destination.parentFile, "$id.tmp")
            pending.writeText(JSONArray().apply {
                all.forEach { put(it.toJson().apply { remove("alternatives") }) }
            }.toString())
            java.nio.file.Files.move(pending.toPath(), destination.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            true
        }.getOrDefault(false) }
    }

    fun selected(context: Context, id: Long, source: String?): DirectLyricsRepository.Result? {
        if (source == null) return null
        val record = context.pluginPreferences("ampp-current-lyrics-source", Context.MODE_PRIVATE)
            .getString("version_${id}_$source", null) ?: return null
        return entries(context, id).firstOrNull { it.source == source && it.recordId == record }
    }

    fun best(context: Context, id: Long, source: String, excluded: Set<String> = emptySet(),
             wordFirst: Boolean = false): DirectLyricsRepository.Result? =
        entries(context, id).filter {
            it.source == source && it.lyrics.isNotBlank() && it.recordId !in excluded
        }.maxByOrNull { DirectLyricsRepository.qualityRank(it, wordFirst) }

    fun select(context: Context, id: Long, result: DirectLyricsRepository.Result) {
        CurrentLyricsSourceStatus.selectSource(context, id, result.source)
        context.pluginPreferences("ampp-current-lyrics-source", Context.MODE_PRIVATE).edit()
            .putString("version_${id}_${result.source}", result.recordId).apply()
    }

    private fun decode(value: JSONObject) = DirectLyricsRepository.Result(
        lyrics = value.optString("lyrics"), translatedLyrics = value.optString("translatedLyrics"),
        wordLyrics = value.optString("wordLyrics"), durationMs = value.optLong("duration"),
        romanizedLyrics = value.optString("romanizedLyrics"),
        romanizedWordLyrics = value.optString("romanizedWordLyrics"),
        source = value.optString("source"), recordId = value.optString("recordId"),
        title = value.optString("title"), artist = value.optString("artist"), score = value.optInt("matchScore"),
        album = value.optString("album"),
    )
}
