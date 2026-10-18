package com.tcrrry.lyrics.engine.hook
import com.tcrrry.lyrics.plugin.PluginStorage.pluginPreferences

import com.tcrrry.lyrics.sources.DirectLyricsRepository
import android.content.Context
import com.tcrrry.lyrics.engine.lyrics.DesktopLyricsTtmlConverter
import com.tcrrry.lyrics.engine.model.CustomLyricsSources
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.FutureTask
import java.util.concurrent.Executors

data class DesktopLyricsTrack(
    val title: String,
    val artist: String,
    val album: String = "",
    val durationMs: Long = 0L,
    val appleMusicId: Long = 0L,
    val explicitSource: Boolean = false,
)

/** Runs the original Desktop Lyrics provider search off the Apple Music hook thread. */
internal class DesktopLyricsSource(
    private val context: Context,
    private val repository: DirectLyricsRepository = DirectLyricsRepository(),
) {
    private val wordRepository by lazy { DirectLyricsRepository(wordFirst = true) }
    private val supplement = DesktopLyricsSupplement(context)
    private val completedTranslations = TranslationOutcomeCache()
    private val translationRevisions = ConcurrentHashMap<Long, Int>()
    private val translating = ConcurrentHashMap.newKeySet<String>()
    private val activeTranslationKeys = ConcurrentHashMap<Long, String>()
    private val translationExecutor = com.tcrrry.lyrics.plugin.PluginExecutors.pool(2, 2, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(2), { task -> Thread(task, "tcrrry-lyrics-translation").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy())
    private class SearchState {
        val first = CompletableFuture<DirectLyricsRepository.Result>()
        @Volatile var final: DirectLyricsRepository.Result? = null
        @Volatile var deliveredBeforeFinal = false
        var delivered: DirectLyricsRepository.Result? = null
        @Volatile var finishedAtMs: Long = 0L
        var job: FutureTask<Unit>? = null
    }
    private val searches = ConcurrentHashMap<String, SearchState>()
    private val retryCounts = ConcurrentHashMap<String, Int>()
    private val retryExecutor = com.tcrrry.lyrics.plugin.PluginExecutors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "tcrrry-lyrics-retry").apply { isDaemon = true }
    }
    private val searchExecutor = com.tcrrry.lyrics.plugin.PluginExecutors.pool(2, 2, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(2), { task -> Thread(task, "tcrrry-lyrics-search").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy())

    fun invalidate(appleMusicId: Long) {
        activeTranslationKeys.remove(appleMusicId)
        translationRevisions.compute(appleMusicId) { _, previous -> (previous ?: 0) + 1 }
        completedTranslations.invalidate(appleMusicId)
        val prefix = "$appleMusicId|"
        searches.entries.filter { it.key.startsWith(prefix) }.forEach {
            if (searches.remove(it.key, it.value)) {
                it.value.job?.cancel(true)
                it.value.first.complete(DirectLyricsRepository.Result())
            }
        }
        retryCounts.keys.filter { it.startsWith(prefix) }.forEach(retryCounts::remove)
    }

    fun fetch(track: DesktopLyricsTrack): AutoLyricsCandidate? {
        CurrentLyricsSourceStatus.rememberMatchInput(context, track)
        if (track.title.isBlank() || track.artist.isBlank()) return null
        val selected = CurrentLyricsSourceStatus.selectedSource(context, track.appleMusicId)
        val excluded = selected?.let { CurrentLyricsSourceStatus.excludedRecords(context, track.appleMusicId, it) }.orEmpty()
        val key = listOf(track.appleMusicId, track.title, track.artist, track.album, track.durationMs, selected, excluded.sorted(), com.tcrrry.lyrics.engine.lyrics.LyricsPreference.qualityFirst(context)).joinToString("|")
        val result = if (selected == null) findAuto(track, key) else {
            val saved = TcrrryLyricsHistory.selected(context, track.appleMusicId, selected)
                ?: TcrrryLyricsHistory.best(context, track.appleMusicId, selected, excluded,
                    wordFirst = !com.tcrrry.lyrics.engine.lyrics.LyricsPreference.qualityFirst(context))
            saved?.let { found ->
                repository.completeQqLyrics(found).also { repaired ->
                    if (repaired !== found) TcrrryLyricsHistory.remember(context, track.appleMusicId, repaired)
                }
            }
                ?: repository.rematch(selected, track.title, track.artist, track.album,
                    track.durationMs, excluded)?.also {
                    TcrrryLyricsHistory.remember(context, track.appleMusicId, it)
                }
        } ?: return null
        ModernXposedRuntime.log(
            "Desktop Lyrics provider result source=${result.source.ifBlank { "none" }} " +
                "score=${result.score} lines=${result.lyrics.lineSequence().count()} " +
                "word=${result.wordLyrics.isNotBlank()} translation=${result.translatedLyrics.isNotBlank()}",
        )
        CurrentLyricsSourceStatus.rememberRecord(
            context, track.appleMusicId, result.source, result.recordId,
        )
        CurrentLyricsSourceStatus.rememberMatchResult(context, track.appleMusicId, result)
        val offset = CurrentLyricsSourceStatus.offsetMs(context, track.appleMusicId, result.source)
        val translationPrefs = context.pluginPreferences("supplement_translation", Context.MODE_PRIVATE)
        val mode = translationPrefs.getString("mode", "off").orEmpty()
        val translationKey = "$key|${result.source}|${result.recordId}|$mode|${translationRevisions[track.appleMusicId] ?: 0}|" +
            "${translationPrefs.getString("active_api_profile", "")}|" +
            "auto-v2|" +
            "${result.lyrics.hashCode()}|${result.wordLyrics.hashCode()}|${result.translatedLyrics.hashCode()}|${result.romanizedLyrics.hashCode()}|${result.romanizedWordLyrics.hashCode()}"
        activeTranslationKeys[track.appleMusicId] = translationKey
        val translationOutcome = completedTranslations[translationKey]
        val complete = if (mode in setOf("api", "offline")) {
            translationOutcome?.also {
                CurrentLyricsSourceStatus.rememberTranslationStatus(context, track.appleMusicId, it.status)
            }?.result ?: result.also {
                if (translating.add(translationKey)) {
                    CurrentLyricsSourceStatus.rememberTranslationStatus(context, track.appleMusicId, "正在检查平台译文并补充缺失句子")
                    runCatching { translationExecutor.execute {
                        try {
                            val filled = supplement.fill(result, mode)
                            if (activeTranslationKeys[track.appleMusicId] == translationKey) {
                                completedTranslations[translationKey] = filled
                                CurrentLyricsSourceStatus.rememberTranslationStatus(context, track.appleMusicId, filled.status)
                                if (DesktopLyricsUpdatePolicy.changed(result, filled.result, track.durationMs))
                                    CurrentLyricsSourceStatus.refreshSilently(track.appleMusicId)
                            }
                        } finally { translating.remove(translationKey) }
                    } }.onFailure {
                        translating.remove(translationKey)
                        if (activeTranslationKeys[track.appleMusicId] == translationKey)
                            CurrentLyricsSourceStatus.rememberTranslationStatus(context, track.appleMusicId, "补译队列繁忙，请稍后重试")
                    }
                }
            }
        } else result.also {
            CurrentLyricsSourceStatus.rememberTranslationStatus(context, track.appleMusicId, "补充翻译已关闭")
        }
        val pronunciationEnabled = context.pluginPreferences("japanese_pronunciation", Context.MODE_PRIVATE)
            .getBoolean("enabled", true)
        val primaryPronunciation = context.pluginPreferences("japanese_pronunciation", Context.MODE_PRIVATE)
            .getBoolean("primary", false) && NativeLyricsEmphasisVisibility.visible(context)
        val withJapanesePronunciation = if (pronunciationEnabled) runCatching {
            com.tcrrry.lyrics.engine.lyrics.JapanesePronunciationSupplement.fill(complete)
        }.getOrElse {
            ModernXposedRuntime.log("Japanese pronunciation unavailable: ${it.javaClass.simpleName}")
            complete
        } else complete
        val withPronunciation = runCatching {
            com.tcrrry.lyrics.engine.lyrics.LanguagePronunciationSupplement.fill(context, track.appleMusicId, withJapanesePronunciation)
        }.getOrElse {
            ModernXposedRuntime.log("Language pronunciation unavailable: ${it.javaClass.simpleName}")
            withJapanesePronunciation
        }
        val ttml = DesktopLyricsTtmlConverter.convert(withPronunciation, track.durationMs, offset, primaryPronunciation,
            com.tcrrry.lyrics.engine.lyrics.LyricsPreference.smoothShortUnits(context),
            hideOpeningCredits = com.tcrrry.lyrics.engine.lyrics.LyricsPreference.hideOpeningCredits(context)) ?: run {
            CurrentLyricsSourceStatus.rememberMatchStatus(context, track.appleMusicId, "已找到 ${result.source}，歌词格式转换失败")
            return null
        }
        CurrentLyricsSourceStatus.rememberMatchStatus(context, track.appleMusicId, "已找到 ${result.source}，正在装载歌词")
        return AutoLyricsCandidate(
            source = "${CustomLyricsSources.DESKTOP_LYRICS}:${result.source}",
            ttml = ttml.replaceFirst("?>", "?>" + com.tcrrry.lyrics.engine.lyrics.JapanesePronunciationSupplement.cacheMarker(pronunciationEnabled, primaryPronunciation) +
                com.tcrrry.lyrics.engine.lyrics.LanguagePronunciationSupplement.marker(context, track.appleMusicId) +
                TranslationOutcomeCache.marker(mode !in setOf("api", "offline") || translationOutcome?.retryable == false)),
            displayName = "${track.title} - ${track.artist}",
        )
    }

    private fun findAuto(track: DesktopLyricsTrack, key: String): DirectLyricsRepository.Result? {
        searches[key]?.let { old ->
            if (shouldRetryEmptyLyricsSearch(old.final, old.finishedAtMs, System.currentTimeMillis()))
                searches.remove(key, old)
        }
        val wordFirst = !com.tcrrry.lyrics.engine.lyrics.LyricsPreference.qualityFirst(context)
        val autoRepository = if (wordFirst) wordRepository else repository
        val fresh = SearchState()
        val previous = searches.putIfAbsent(key, fresh)
        val state = previous ?: fresh.also {
                CurrentLyricsSourceStatus.rememberMatchStatus(context, track.appleMusicId,
                    "三源并行搜索中 · 时长 ${track.durationMs / 1000} 秒")
                val job = FutureTask<Unit> {
                    val found = runCatching {
                        autoRepository.resolveLyrics(track.title, track.artist, track.album, track.durationMs) { partial ->
                            if (partial.lyrics.isNotBlank()) {
                                TcrrryLyricsHistory.remember(context, track.appleMusicId, partial)
                                val refresh = synchronized(fresh) {
                                    fresh.first.complete(partial) && fresh.deliveredBeforeFinal && fresh.delivered == null
                                }
                                if (refresh && searches[key] === fresh) CurrentLyricsSourceStatus.refreshSilently(track.appleMusicId)
                            }
                        }
                    }.onFailure { error ->
                        ModernXposedRuntime.log("Desktop Lyrics search failed id=${track.appleMusicId}: ${error.javaClass.simpleName}: ${error.message?.take(120)}")
                    }.getOrDefault(DirectLyricsRepository.Result())
                    (listOf(found) + found.alternatives).filter { it.lyrics.isNotBlank() }
                        .forEach { TcrrryLyricsHistory.remember(context, track.appleMusicId, it) }
                    val refresh = synchronized(fresh) {
                        val previous = fresh.delivered ?: fresh.first.getNow(null)
                        fresh.final = DesktopLyricsUpdatePolicy.choose(previous, found, track.durationMs, wordFirst)
                        fresh.finishedAtMs = System.currentTimeMillis()
                        fresh.deliveredBeforeFinal &&
                            DesktopLyricsUpdatePolicy.changed(fresh.delivered, fresh.final!!, track.durationMs)
                    }
                    fresh.first.complete(found)
                    if (searches.size > 128) searches.entries.firstOrNull {
                        it.key != key && it.value.final != null
                    }?.let { searches.remove(it.key, it.value) }
                    if (searches[key] === fresh) {
                        if (refresh) CurrentLyricsSourceStatus.refreshSilently(track.appleMusicId)
                        if (fresh.final?.lyrics.isNullOrBlank()) {
                            val attempt = retryCounts.merge(key, 1, Int::plus) ?: 1
                            CurrentLyricsSourceStatus.rememberMatchStatus(context, track.appleMusicId,
                                if (attempt <= 2) "本轮三源未返回可用歌词，将自动重试"
                                else "三源暂未找到可用歌词，请重新匹配")
                            if (attempt <= 2) retryExecutor.schedule({
                                if (searches.remove(key, fresh)) CurrentLyricsSourceStatus.refreshSilently(track.appleMusicId)
                            }, if (attempt == 1) 12L else 30L, TimeUnit.SECONDS)
                        } else retryCounts.remove(key)
                    }
                }
                fresh.job = job
                runCatching { searchExecutor.execute(job) }.onFailure {
                    fresh.final = DirectLyricsRepository.Result()
                    fresh.finishedAtMs = System.currentTimeMillis()
                    fresh.first.complete(DirectLyricsRepository.Result())
                    searches.remove(key, fresh)
                    CurrentLyricsSourceStatus.rememberMatchStatus(context, track.appleMusicId, "搜索队列繁忙，请重新匹配")
                }
        }
        val first = if (state.deliveredBeforeFinal) state.first.getNow(null) else
            runCatching { state.first.get(4, TimeUnit.SECONDS) }.onFailure {
                if (it is InterruptedException) Thread.currentThread().interrupt()
            }.getOrNull()
        return synchronized(state) {
            (state.final ?: first).also {
                state.delivered = it
                state.deliveredBeforeFinal = state.final == null
            }
        }?.takeIf { it.lyrics.isNotBlank() }
    }
}

internal fun shouldRetryEmptyLyricsSearch(result: DirectLyricsRepository.Result?, finishedAtMs: Long, nowMs: Long): Boolean =
    result != null && result.lyrics.isBlank() && finishedAtMs > 0L && nowMs - finishedAtMs >= 10_000L
