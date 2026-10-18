package com.tcrrry.lyrics.engine.hook

import com.tcrrry.lyrics.engine.lyrics.ChineseSubtitlePolicy
import com.tcrrry.lyrics.engine.lyrics.DesktopLyricsPresentation
import com.tcrrry.lyrics.sources.DirectLyricsRepository
import org.json.JSONArray
import org.json.JSONObject

/** Merge independent local/network completion without replacing source text or existing translations. */
internal class AuxiliaryEnrichmentProgress(private val base: AutoLyricsCandidate, private val mode: String?) {
    private var platform = base.ttml
    private var origins = TtmlAuxiliaryOrigins.read(base.ttml) ?: TtmlAuxiliaryOrigins.original(base.source, base.ttml)
    private val machine = mutableMapOf<Int, String>()
    private val lines = TtmlSubtitleTrack.lines(base.ttml)

    @Synchronized fun missingPayload(): String {
        val existing = TtmlSubtitleTrack.subtitleValues(platform)
        return JSONArray().apply {
            lines.forEachIndexed { index, text ->
                if (existing[index].isNullOrBlank() && ChineseSubtitlePolicy.needsChineseTranslation(text))
                    put(JSONObject().put("id", index).put("text", text))
            }
        }.toString()
    }

    @Synchronized fun machineRow(row: JSONObject) {
        val index = row.optInt("id", -1)
        val text = row.optString("text")
        if (index in lines.indices) ChineseSubtitlePolicy.usable(lines[index], text)?.let { machine[index] = it }
    }

    @Synchronized fun platformResult(source: DirectLyricsRepository.Result) {
        if (source.source !in setOf("QQ音乐", "网易云音乐") || source.score < 50) return
        val next = CrossSourceAuxiliary.enrich(platform, source)
        origins = origins.copy(
            translation = origins.translation + if (TtmlSubtitleTrack.subtitleValues(next).keys !=
                TtmlSubtitleTrack.subtitleValues(platform).keys) setOf(source.source) else emptySet(),
            pronunciation = origins.pronunciation + if (TtmlSubtitleTrack.subtitleValues(next, true).keys !=
                TtmlSubtitleTrack.subtitleValues(platform, true).keys) setOf(source.source) else emptySet())
        platform = next
    }

    @Synchronized fun missingPlatform(): Boolean {
        val existing = TtmlSubtitleTrack.subtitleValues(platform)
        return lines.indices.any { ChineseSubtitlePolicy.needsChineseTranslation(lines[it]) && existing[it].isNullOrBlank() }
    }

    @Synchronized fun candidate(): AutoLyricsCandidate {
        val existing = TtmlSubtitleTrack.subtitleValues(platform)
        // Native and accepted provider translations always win, regardless of completion order.
        val additions = machine.filterKeys { existing[it].isNullOrBlank() }
        var text = TtmlSubtitleTrack.attach(platform, additions) ?: platform
        val finalOrigins = if (additions.isNotEmpty()) origins.copy(
            translation = origins.translation + if (mode == "api") "api" else "offline") else origins
        if (text != base.ttml && "<transliterations" in text && DesktopLyricsPresentation.fromTtml(text) == null) {
            val marker = DesktopLyricsPresentation(source = "未知", wordTimed = TtmlTimingPolicy.isWord(text),
                platformTranslation = existing.isNotEmpty(), apiTranslation = additions.isNotEmpty() && mode == "api",
                offlineTranslation = additions.isNotEmpty() && mode == "offline", pronunciation = true).marker()
            text = if (text.startsWith("<?xml")) text.replaceFirst("?>", "?>$marker") else marker + text
        }
        return if (text != base.ttml) base.copy(ttml = finalOrigins.attach(text)) else base
    }
}
