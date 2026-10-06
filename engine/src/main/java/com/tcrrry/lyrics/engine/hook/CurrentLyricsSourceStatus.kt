package com.tcrrry.lyrics.engine.hook
import com.tcrrry.lyrics.plugin.PluginStorage.pluginPreferences

import android.content.Context
import android.os.Process
import com.tcrrry.lyrics.engine.lyrics.DesktopLyricsPresentation

/** The source actually handed to Apple's lyric view for the current song. */
internal object CurrentLyricsSourceStatus {
    private const val PREFS = "ampp-current-lyrics-source"
    private val SOURCES = LyricsSourceMenuPolicy.sources.toSet()
    private data class SourceCycle(val id: Long, val ticket: Long, val source: String, val remaining: List<String>)
    private val cycleSequence = java.util.concurrent.atomic.AtomicLong()
    private var sourceCycle: SourceCycle? = null

    @Synchronized fun beginSourceCycle(context: Context, id: Long): String? {
        if (id <= 0L) return null
        val order = LyricsSourceMenuPolicy.cycleOrder(appliedSource(context, id), selectedSource(context, id))
        val first = order.first()
        selectSource(context, id, first)
        sourceCycle = SourceCycle(id, cycleSequence.incrementAndGet(), first, order.drop(1))
        return first
    }

    @Synchronized fun sourceCycleTicket(id: Long): Long? = sourceCycle?.takeIf { it.id == id }?.ticket

    @Synchronized fun cancelSourceCycle(id: Long) {
        if (sourceCycle?.id == id) sourceCycle = null
    }

    @Synchronized fun cancelSourceCycleUnless(id: Long?) {
        if (sourceCycle?.id != id) sourceCycle = null
    }

    @Synchronized fun continueSourceCycle(context: Context, id: Long, ticket: Long?, success: Boolean): String? {
        val cycle = sourceCycle?.takeIf { it.id == id && it.ticket == ticket } ?: return null
        if (success || selectedSource(context, id) != cycle.source || cycle.remaining.isEmpty()) {
            sourceCycle = null
            return null
        }
        val next = cycle.remaining.first()
        sourceCycle = SourceCycle(id, cycleSequence.incrementAndGet(), next, cycle.remaining.drop(1))
        context.pluginPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("selected_$id", next).apply()
        return next
    }

    @Volatile private var refreshHandler: ((Long, Boolean) -> Boolean)? = null

    @Volatile private var pageHandler: ((Any) -> Unit)? = null
    @Volatile private var identityPageHandler: ((Any) -> Unit)? = null
    fun installIdentityPageHandler(handler: (Any) -> Unit): HostSubscription {
        identityPageHandler = handler
        return HostSubscription { if (identityPageHandler === handler) identityPageHandler = null }
    }
    fun installPageHandler(handler: (Any) -> Unit) { pageHandler = handler }
    fun rememberVisiblePage(fragment: Any) {
        identityPageHandler?.invoke(fragment)
        pageHandler?.invoke(fragment)
    }

    fun installRefreshHandler(handler: (Long, Boolean) -> Boolean) { refreshHandler = handler }
    fun refresh(id: Long): Boolean = refreshHandler?.invoke(id, true) ?: false
    fun refreshSilently(id: Long): Boolean = refreshHandler?.invoke(id, false) ?: false

    fun rememberMatchInput(context: Context, track: DesktopLyricsTrack) {
        context.pluginPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("match_input_${track.appleMusicId}",
                "歌名：${track.title.take(120)}\n歌手：${track.artist.take(120)}\n" +
                    "专辑：${track.album.take(120).ifBlank { "未提供" }}\n时长：${track.durationMs / 1000.0} 秒")
            .putInt("match_input_pid_${track.appleMusicId}", Process.myPid()).apply()
    }

    fun matchInput(context: Context, id: Long): String {
        val prefs = context.pluginPreferences(PREFS, Context.MODE_PRIVATE)
        return if (prefs.getInt("match_input_pid_$id", 0) == Process.myPid())
            prefs.getString("match_input_$id", null) ?: "尚未收到匹配输入"
        else "尚未收到匹配输入"
    }

    fun rememberMatchResult(context: Context, id: Long, result: com.tcrrry.lyrics.sources.DirectLyricsRepository.Result) {
        if (id <= 0L || result.source !in LyricsSourceMenuPolicy.thirdPartySources) return
        val detail = "歌名：${result.title.ifBlank { "未提供" }}\n歌手：${result.artist.ifBlank { "未提供" }}\n" +
            "专辑：${result.album.ifBlank { "未提供" }}\n时长：${result.durationMs / 1000.0} 秒\n" +
            "平台 ID：${result.recordId.ifBlank { "未提供" }}\n匹配评分：${result.score}"
        context.pluginPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("match_result_${id}_${result.source}", detail).apply()
    }

    fun matchingInformation(context: Context, id: Long): String {
        val source = appliedSource(context, id)
        val prefs = context.pluginPreferences(PREFS, Context.MODE_PRIVATE)
        val matched = if (source?.startsWith("desktop-lyrics:") == true)
            prefs.getString("applied_match_result", null) ?: "当前已显示来源的匹配详情尚未记录，请重新匹配。"
        else if (source == "APPLE_NATIVE" || source == "Apple Music 原生" || source == "am-lyrics")
            "按 Apple Music 歌曲 ID 获取，无第三方平台匹配记录。"
        else "当前显示来源的匹配信息尚未确认。"
        return "播放歌曲\nApple Music ID：$id\n${matchInput(context, id)}\n\n当前显示来源\n" +
            "${description(context, id)}\n\n匹配到的歌曲\n$matched"
    }

    fun rememberMatchStatus(context: Context, id: Long, status: String) {
        if (id <= 0L) return
        context.pluginPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("match_status_$id", status.take(180)).putInt("match_pid_$id", Process.myPid()).apply()
    }

    fun selectedSource(context: Context, id: Long): String? =
        context.pluginPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("selected_$id", null)?.takeIf { it in SOURCES }

    fun resetMatching(context: Context, id: Long) {
        cancelSourceCycle(id)
        val edit = context.pluginPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("selected_$id")
        SOURCES.forEach { edit.remove("excluded_${id}_$it").remove("version_${id}_$it") }
        edit.apply()
    }

    @Synchronized fun selectSource(context: Context, id: Long, source: String?) {
        if (id <= 0L || (source != null && source !in SOURCES)) return
        cancelSourceCycle(id)
        context.pluginPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("selected_$id", source).apply()
    }

    fun offsetMs(context: Context, id: Long, source: String): Int =
        context.pluginPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt("offset_${id}_$source", 0)

    fun setOffsetMs(context: Context, id: Long, source: String, value: Int) {
        if (id <= 0L || source.isBlank()) return
        context.pluginPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("offset_${id}_$source", value.coerceIn(-5_000, 5_000)).apply()
    }

    fun excludedRecords(context: Context, id: Long, source: String): Set<String> =
        context.pluginPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet("excluded_${id}_$source", emptySet())?.toSet().orEmpty()

    /** Retry restores access to known records; only an explicit version change excludes one. */
    fun retryCurrentSource(context: Context, id: Long) {
        if (id <= 0L) return
        cancelSourceCycle(id)
        val source = selectedSource(context, id) ?: LyricsSourceMenuPolicy.provider(appliedSource(context, id)) ?: return
        context.pluginPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove("excluded_${id}_$source").remove("version_${id}_$source")
            .putString("selected_$id", source).apply()
    }

    fun excludeCurrentRecord(context: Context, id: Long): Boolean {
        val prefs = context.pluginPreferences(PREFS, Context.MODE_PRIVATE)
        val source = LyricsSourceMenuPolicy.provider(appliedSource(context, id)) ?: return false
        if (source !in LyricsSourceMenuPolicy.thirdPartySources) return false
        if (selectedSource(context, id)?.let { it != source } == true) return false
        val record = (prefs.getString("applied_record_id", null)
            ?: prefs.getString("record_${id}_$source", null))?.takeIf(String::isNotBlank)
            ?: return false
        prefs.edit().putStringSet(
            "excluded_${id}_$source", (excludedRecords(context, id, source) + record).toSet(),
        ).putString("selected_$id", source).remove("version_${id}_$source").apply()
        return true
    }

    fun rememberRecord(context: Context, id: Long, source: String, recordId: String) {
        if (id <= 0L || source !in SOURCES || recordId.isBlank()) return
        context.pluginPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("record_${id}_$source", recordId).apply()
    }

    fun rememberTranslationStatus(context: Context, id: Long, status: String) {
        context.pluginPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("translation_status_$id", status.take(180)).apply()
    }

    fun translationStatus(context: Context, id: Long): String =
        context.pluginPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("translation_status_$id", null).orEmpty()

    fun appliedSource(context: Context, id: Long): String? {
        val prefs = context.pluginPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString("applied_source", null)
            ?.takeIf { prefs.getLong("applied_id", 0L) == id && prefs.getInt("applied_pid", 0) == Process.myPid() }
    }

    fun candidateSource(context: Context, id: Long): String? =
        context.pluginPreferences(PREFS, Context.MODE_PRIVATE).getString("source_$id", null)

    fun rememberCandidate(context: Context, id: Long, source: String, ttml: String) {
        if (id <= 0L || source.isBlank()) return
        val presentation = DesktopLyricsPresentation.fromTtml(ttml)
        val effectiveSource = presentation?.source?.takeIf { it in LyricsSourceMenuPolicy.thirdPartySources }
            ?.let { "desktop-lyrics:$it" } ?: source
        val metadata = TtmlTimingPolicy.metadataOf(ttml)
        val origins = TtmlAuxiliaryOrigins.read(ttml)
        val detail = if (origins != null) buildList {
            add(if (metadata.timingMode == TtmlTimingMode.WORD) "逐字" else "逐行")
            origins.detail().takeIf(String::isNotBlank)?.let(::add)
            if (presentation?.primaryPronunciation == true) add(NativeLyricsPhoneticPresentation.alignmentDetail(ttml)
                ?: if (metadata.timingMode == TtmlTimingMode.WORD) "发音逐段高亮" else "发音逐行显示")
        }.joinToString(" · ") else presentation?.detail() ?: buildList {
            add(if (metadata.timingMode == TtmlTimingMode.WORD) "逐字" else "逐行")
            if (metadata.hasTranslation) add("含译文")
        }.joinToString(" · ")
        context.pluginPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("source_$id", effectiveSource)
            .putString("detail_$id", detail)
            .putBoolean("pronunciation_$id", presentation?.pronunciation == true)
            .apply()
    }

    fun recordNativeIfInstalled(context: Context, id: Long, displayedId: Long?, installed: Any?, native: Any?, ttml: String?): Boolean {
        if (!shouldReportAppliedLyrics(id, displayedId, installed, native)) return false
        recordNativeApplied(context, id, ttml)
        return true
    }

    fun recordNativeApplied(context: Context, id: Long, ttml: String? = null) {
        val detail = ttml?.let { document ->
            listOf(if (TtmlTimingPolicy.isWord(document)) "逐字" else "逐行",
                TtmlAuxiliaryOrigins.original("APPLE_NATIVE", document).detail()).filter(String::isNotBlank).joinToString(" · ")
        }.orEmpty()
        context.pluginPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("applied_id", id).putInt("applied_pid", Process.myPid())
            .putString("applied_source", "Apple Music 原生").remove("applied_match_result").remove("applied_record_id")
            .putString("applied_detail", detail).putBoolean("applied_pronunciation", ttml?.let {
                DesktopLyricsPresentation.fromTtml(NativeLyricsPhoneticPresentation.render(it, false))?.pronunciation
            } == true).apply()
    }

    fun recordApplied(context: Context, id: Long, manual: Boolean) {
        if (id <= 0L) return
        val prefs = context.pluginPreferences(PREFS, Context.MODE_PRIVATE)
        val source = if (manual) "manual" else prefs.getString("source_$id", null) ?: "automatic-cache"
        prefs.edit()
            .putLong("applied_id", id)
            .putInt("applied_pid", Process.myPid())
            .putString("applied_source", source)
            .putString("applied_record_id", if (source.startsWith("desktop-lyrics:"))
                prefs.getString("record_${id}_${source.substringAfter(':')}", null) else null)
            .putString("applied_match_result", if (source.startsWith("desktop-lyrics:"))
                prefs.getString("match_result_${id}_${source.substringAfter(':')}", null) else null)
            .putString("applied_detail", if (manual) "" else prefs.getString("detail_$id", ""))
            .putBoolean("applied_pronunciation", !manual && prefs.getBoolean("pronunciation_$id", false))
            .apply()
    }

    fun canEmphasizePronunciation(context: Context, id: Long): Boolean {
        val prefs = context.pluginPreferences(PREFS, Context.MODE_PRIVATE)
        return id > 0L && prefs.getLong("applied_id", 0L) == id &&
            prefs.getInt("applied_pid", 0) == Process.myPid() &&
            prefs.getBoolean("applied_pronunciation", false)
    }

    fun description(context: Context, currentId: Long?): String {
        if (currentId == null || currentId <= 0L) return "暂无正在播放的歌曲"
        val prefs = context.pluginPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getLong("applied_id", 0L) != currentId ||
            prefs.getInt("applied_pid", 0) != Process.myPid()
        ) return if (prefs.getInt("match_pid_$currentId", 0) == Process.myPid())
            prefs.getString("match_status_$currentId", null) ?: "当前歌曲尚未装入替换歌词"
        else "当前歌曲尚未装入替换歌词"
        val source = prefs.getString("applied_source", null).orEmpty()
        val name = when {
            source.startsWith("desktop-lyrics:") -> "我的歌词源 · ${source.substringAfter(':')}"
            source == "manual" -> "手动指定的歌词"
            source == "automatic-cache" -> "自动歌词缓存（来源未记录）"
            source.isBlank() -> "来源未记录"
            else -> LyricsSourceMenuPolicy.caption(source)
        }
        val detail = prefs.getString("applied_detail", "").orEmpty()
        return name + if (detail.isBlank()) "" else " · $detail"
    }
}
