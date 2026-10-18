package com.tcrrry.lyrics.engine.hook
import com.tcrrry.lyrics.plugin.PluginStorage.pluginPreferences

import android.content.Context
import com.tcrrry.lyrics.sources.SupplementTranslation
import com.tcrrry.lyrics.engine.lyrics.ChineseSubtitlePolicy
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/** Adds a Chinese subtitle track to AM++ fallback lyrics after the original is already visible. */
internal class FallbackLyricsTranslation(private val context: Context,
    private val platformLookup: ((DesktopLyricsTrack) -> List<com.tcrrry.lyrics.sources.DirectLyricsRepository.Result>)? = null,
    private val worker: java.util.concurrent.Executor? = null,
    private val now: () -> Long = { System.nanoTime() / 1_000_000L },
    private val networkWorker: java.util.concurrent.Executor? = null,
    private val translateRows: ((String, (JSONObject) -> Unit) -> Unit)? = null,
    private val isCurrent: (Long) -> Boolean = { true },
) {
    private data class Cached(val candidate: AutoLyricsCandidate, val retryAt: Long)
    private val completed = ConcurrentHashMap<String, Cached>()
    private val readings = ConcurrentHashMap<String, AutoLyricsCandidate>()
    private val platformRepository by lazy { com.tcrrry.lyrics.sources.DirectLyricsRepository() }
    private val working = ConcurrentHashMap.newKeySet<String>()
    private fun present(candidate: AutoLyricsCandidate): AutoLyricsCandidate {
        val primary = context.pluginPreferences("japanese_pronunciation", Context.MODE_PRIVATE)
            .getBoolean("primary", false) && NativeLyricsEmphasisVisibility.visible(context)
        val tracked = if (TtmlAuxiliaryOrigins.read(candidate.ttml) == null &&
            candidate.source in setOf("APPLE_NATIVE", com.tcrrry.lyrics.engine.model.CustomLyricsSources.AM_LYRICS))
            TtmlAuxiliaryOrigins.original(candidate.source, candidate.ttml).attach(candidate.ttml) else candidate.ttml
        val rendered = if (candidate.source in setOf("APPLE_NATIVE", com.tcrrry.lyrics.engine.model.CustomLyricsSources.AM_LYRICS))
            NativeLyricsPhoneticPresentation.render(tracked, primary,
                com.tcrrry.lyrics.engine.lyrics.LyricsPreference.smoothShortUnits(context)) else tracked
        return if (rendered == candidate.ttml) candidate else candidate.copy(ttml = rendered)
    }
    private val executor = com.tcrrry.lyrics.plugin.PluginExecutors.pool(2, 2, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(2), { task -> Thread(task, "ampp-fallback-translation").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy())
    private val platformExecutor = com.tcrrry.lyrics.plugin.PluginExecutors.pool(2, 2, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(2), { task -> Thread(task, "ampp-platform-auxiliary").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy())

    fun enrich(id: Long, rawCandidate: AutoLyricsCandidate): AutoLyricsCandidate = enrichWithTrack(id, rawCandidate, null)

    fun enrichWithTrack(id: Long, rawCandidate: AutoLyricsCandidate, playbackTrack: DesktopLyricsTrack?): AutoLyricsCandidate {
        val cleaned = TtmlSubtitleTrack.cleanChineseSubtitles(rawCandidate.ttml)
        val baseCandidate = if (cleaned == rawCandidate.ttml) rawCandidate else rawCandidate.copy(ttml = cleaned)
        val pronunciationPrefs = context.pluginPreferences("japanese_pronunciation", Context.MODE_PRIVATE)
        val readingKey = "$id|${baseCandidate.source}|${pronunciationPrefs.getBoolean("enabled", true)}|" +
            "${pronunciationPrefs.getBoolean("korean_enabled", true)}|${pronunciationPrefs.getBoolean("cantonese_$id", false)}|" +
            MessageDigest.getInstance("SHA-256").digest(cleaned.toByteArray()).joinToString("") { "%02x".format(it) }
        // enrich is called by the lyrics-fetch worker, never by the playback/UI hook.
        // Local readings must be available in the first published native document,
        // even when platform lookup or machine translation blocks/fails later.
        val candidate = readings[readingKey] ?: runCatching {
            val dictionary = CrossSourceAuxiliary.dictionary(context, id, baseCandidate.ttml)
            val result = if (dictionary == baseCandidate.ttml) baseCandidate else {
                val origins = TtmlAuxiliaryOrigins.read(baseCandidate.ttml)
                    ?: TtmlAuxiliaryOrigins.original(baseCandidate.source, baseCandidate.ttml)
                baseCandidate.copy(ttml = origins.copy(pronunciation = origins.pronunciation + "dictionary").attach(dictionary))
            }
            if (readings.size >= 64) readings.keys.firstOrNull()?.let(readings::remove)
            readings[readingKey] = result
            result
        }.getOrElse {
            ModernXposedRuntime.log("local pronunciation supplementation failed; retained source lyrics", it)
            baseCandidate
        }
        val prefs = context.pluginPreferences("supplement_translation", Context.MODE_PRIVATE)
        val mode = prefs.getString("mode", "off")
        val quality = com.tcrrry.lyrics.engine.lyrics.LyricsPreference.qualityFirst(context) ||
            candidate.source in setOf("APPLE_NATIVE", com.tcrrry.lyrics.engine.model.CustomLyricsSources.AM_LYRICS)
        val revision = com.tcrrry.lyrics.engine.lyrics.LyricsPreference.revision(context)
        if (!quality && (mode !in setOf("offline", "api") || TtmlTimingPolicy.metadataOf(candidate.ttml).hasTranslation)) {
            return present(candidate)
        }
        val lines = TtmlSubtitleTrack.lines(candidate.ttml)
        if (lines.isEmpty()) return present(candidate)
        val digest = MessageDigest.getInstance("SHA-256").digest(candidate.ttml.toByteArray())
            .take(8).joinToString("") { "%02x".format(it) }
        val metadata = com.tcrrry.lyrics.metadata.amprovider.xposed.MediaMetadataCache.getMetadataById(id.toString())
        val track = playbackTrack?.takeIf { (it.appleMusicId == 0L || it.appleMusicId == id) &&
            it.title.isNotBlank() && it.artist.isNotBlank() } ?: metadata?.let {
            DesktopLyricsTrack(it.title?.takeIf(String::isNotBlank) ?: it.originalTitle.orEmpty(),
                it.artist?.takeIf(String::isNotBlank) ?: it.originalArtist.orEmpty(), it.originalAlbum.orEmpty(),
                preferredSongDurationMs(it.duration, null), id)
        }?.takeIf { it.title.isNotBlank() && it.artist.isNotBlank() }
        val identity = track?.let { JSONObject().put("title", it.title).put("artist", it.artist)
            .put("album", it.album).put("duration", it.durationMs).toString() }.orEmpty()
        val key = "$id|${candidate.source}|$digest|$mode|$revision|" +
            "${pronunciationPrefs.getBoolean("enabled", true)}|${pronunciationPrefs.getBoolean("korean_enabled", true)}|${pronunciationPrefs.getBoolean("cantonese_$id", false)}|" +
            "${prefs.getString("active_api_profile", "")}|auto-v2|$identity"
        val cached = completed[key]
        if (cached != null && now() < cached.retryAt) return present(cached.candidate)
        if (working.add(key)) {
            val progress = AuxiliaryEnrichmentProgress(candidate, mode)
            val state = Any()
            var platformDone = !quality
            var machineDone = mode !in setOf("api", "offline")
            var machineFailed = false
            var lastDocument = candidate.ttml
            var lastEmission = Long.MIN_VALUE
            fun active() = com.tcrrry.lyrics.plugin.PluginLifetime.alive && isCurrent(id) &&
                revision == com.tcrrry.lyrics.engine.lyrics.LyricsPreference.revision(context)
            fun publish(force: Boolean) {
                if (!active()) return
                val changed = synchronized(state) {
                    val finished = platformDone && machineDone
                    val time = now()
                    if (!force && lastEmission != Long.MIN_VALUE && time - lastEmission < 350L) return
                    val result = progress.candidate()
                    val retry = when {
                        !finished -> Long.MAX_VALUE
                        machineFailed -> time + 5_000L
                        quality && progress.missingPlatform() -> time + 30_000L
                        else -> Long.MAX_VALUE
                    }
                    if (completed.size >= 64) completed.keys.firstOrNull()?.let(completed::remove)
                    completed[key] = Cached(result, retry)
                    val different = result.ttml != lastDocument
                    lastDocument = result.ttml
                    lastEmission = time
                    different
                }
                val selected = CurrentLyricsSourceStatus.selectedSource(context, id)
                // Refresh re-runs current-source arbitration; it does not force this
                // candidate into the host. A previous source's status may still be
                // recorded while this first result is being prepared.
                if (changed && (selected == null || selected == LyricsSourceMenuPolicy.provider(candidate.source)))
                    CurrentLyricsSourceStatus.refreshSilently(id)
            }
            fun finish(machine: Boolean, failed: Boolean = false) {
                val done = synchronized(state) {
                    if (machine) { machineDone = true; machineFailed = failed } else platformDone = true
                    platformDone && machineDone
                }
                try { publish(true) } finally { if (done) working.remove(key) }
            }
            fun machine() {
                if (mode !in setOf("api", "offline")) return
                runCatching { (worker ?: executor).execute {
                    var failed = false
                    try {
                        if (!active()) return@execute
                        val payload = progress.missingPayload()
                        if (JSONArray(payload).length() > 0) {
                            val accept: (JSONObject) -> Unit = { row ->
                                if (!active()) throw kotlinx.coroutines.CancellationException("歌曲或翻译设置已变更")
                                progress.machineRow(row); publish(false)
                            }
                            if (translateRows != null) translateRows.invoke(payload, accept)
                            else {
                                if (mode == "offline") EmbeddedMlKit.initialize(context)
                                runBlocking { SupplementTranslation(context).translate(payload, accept) }
                            }
                        }
                    } catch (error: Exception) {
                        failed = true
                        if (error !is kotlinx.coroutines.CancellationException)
                            ModernXposedRuntime.log("auxiliary translation incomplete; retained completed rows", error)
                    } finally { finish(true, failed) }
                } }.onFailure { finish(true, true) }
            }
            // Local work and platform IO have independent bounded queues. A slow
            // provider, including a previous song's search, cannot hold up local readings/translation.
            if (mode == "offline" || !quality) machine()
            if (quality) {
                runCatching { (networkWorker ?: worker ?: platformExecutor).execute {
                    try {
                        if (!active()) return@execute
                        if (track != null) {
                            val results = platformLookup?.invoke(track) ?: platformRepository.resolveLyrics(
                                track.title, track.artist, track.album, track.durationMs).let { listOf(it) + it.alternatives }
                            results.forEach(progress::platformResult)
                        }
                    } catch (error: Exception) {
                        ModernXposedRuntime.log("platform auxiliary lookup unavailable; retained local results", error)
                    } finally {
                        finish(false)
                        // Paid API translation keeps its provider-first policy.
                        if (mode == "api") machine()
                    }
                } }.onFailure { finish(false); if (mode == "api") machine() }
            } else if (mode !in setOf("api", "offline")) finish(false)
        }
        // An inline/fast completion may happen before the first candidate is published.
        // Re-read the cache instead of returning the snapshot taken before enqueueing.
        return present(completed[key]?.candidate ?: cached?.candidate ?: candidate)
    }
}

/** Textual edits preserve syllable timing and whitespace in Apple's original body. */
internal object TtmlSubtitleTrack {
    private val paragraph = Regex("(?is)<p\\b([^>]*)>(.*?)</p\\s*>")
    private val key = Regex("\\bitunes:key\\s*=\\s*([\"'])(.*?)\\1", RegexOption.IGNORE_CASE)
    private val markup = Regex("(?is)<[^>]+>")
    private val root = Regex("(?is)<tt\\b[^>]*>")

    fun cleanChineseSubtitles(ttml: String): String {
        val track = Regex("(?is)<translations\\b[^>]*>.*?</translations\\s*>").find(ttml) ?: return ttml
        // The embedded cache only supplies Chinese subtitle tracks. Preserve
        // other language tracks from the upstream TTML sources.
        if (!Regex("xml:lang\\s*=\\s*[\"']zh(?:-Hans|-CN)?[\"']", RegexOption.IGNORE_CASE)
                .containsMatchIn(track.value)) return ttml
        val rows = lines(ttml)
        val paragraphs = paragraph.findAll(ttml).toList()
        val ids = paragraphs.mapIndexed { index, match ->
            key.find(match.groupValues[1])?.groupValues?.get(2) ?: "TcrrryL${index + 1}"
        }
        val entries = Regex("(?is)<text\\b[^>]*\\bfor=[\"']([^\"']+)[\"'][^>]*>(.*?)</text>")
            .findAll(track.value).associate { it.groupValues[1] to LyricsAuxiliaryText.plain(it.groupValues[2]) }
        val useful = ids.mapIndexedNotNull { index, id ->
            ChineseSubtitlePolicy.usable(rows.getOrElse(index) { "" }, entries[id])?.let { index to it }
        }.toMap()
        if (!ChineseSubtitlePolicy.isChineseSong(rows) && useful.isNotEmpty() &&
            useful.size == entries.values.count { it.isNotBlank() }) return ttml
        val without = ttml.removeRange(track.range)
        val rebuilt = attach(without, useful)
        if (rebuilt != null) return rebuilt
        // Remove the forced foreign language used solely to expose the subtitle button.
        val tag = root.find(without) ?: return without
        return without.replaceRange(tag.range, tag.value.replace(
            Regex("xml:lang\\s*=\\s*[\"']ko[\"']"), "xml:lang=\"zh\""))
    }

    fun lines(ttml: String): List<String> = paragraph.findAll(ttml).map { match ->
        LyricsAuxiliaryText.plain(markup.replace(match.groupValues[2], " "))
            .replace(Regex("\\s+"), " ").trim()
    }.take(1000).toList()

    private val auxiliaryEntries = Regex("""(?is)<text\b([^>]*)>(.*?)</text\s*>""")
    private val emptyAuxiliary = Regex("""(?is)<text\b([^>]*)/\s*>""")
    private val auxiliaryFor = Regex("""\bfor\s*=\s*(["'])(.*?)\1""")
    private fun auxiliaryBlock(ttml: String, pronunciation: Boolean): MatchResult? =
        Regex(if (pronunciation) """(?is)<transliterations\b[^>]*>.*?</transliterations\s*>"""
              else """(?is)<translations\b[^>]*>.*?</translations\s*>""").find(ttml)

    fun subtitleValues(ttml: String, pronunciation: Boolean = false): Map<Int, String> {
        val block = auxiliaryBlock(ttml, pronunciation) ?: return emptyMap()
        val values = auxiliaryEntries.findAll(block.value).associate { entry ->
            auxiliaryFor.find(entry.groupValues[1])?.groupValues?.get(2).orEmpty() to
                markup.replace(entry.groupValues[2], "").trim()
        }
        return paragraph.findAll(ttml).take(1000).mapIndexedNotNull { index, match ->
            val id = key.find(match.groupValues[1])?.groupValues?.get(2) ?: "TcrrryL${index + 1}"
            values[id]?.takeIf(String::isNotBlank)?.let { index to it }
        }.toMap()
    }

    fun attach(ttml: String, translated: Map<Int, String>): String? = attachTrack(ttml, translated, false)
    fun attachPronunciation(ttml: String, values: Map<Int, String>): String? = attachTrack(ttml, values, true)
    private fun attachTrack(ttml: String, translated: Map<Int, String>, pronunciation: Boolean): String? {
        val original = lines(ttml)
        if (!pronunciation && ChineseSubtitlePolicy.isChineseSong(original)) return null
        val useful = translated.mapNotNull { (index, value) ->
            original.getOrNull(index)?.let { if (pronunciation) value.takeIf(String::isNotBlank) else ChineseSubtitlePolicy.usable(it, value) }
                ?.let { index to it }
        }.toMap()
        if (useful.isEmpty()) return null
        val matches = paragraph.findAll(ttml).take(1000).toList()
        if (matches.isEmpty()) return null
        val keys = matches.mapIndexed { index, match ->
            key.find(match.groupValues[1])?.groupValues?.get(2) ?: "TcrrryL${index + 1}"
        }
        val existing = auxiliaryBlock(ttml, pronunciation)
        if (existing != null) {
            // Keep every populated native entry byte-for-byte; replace blanks only.
            val desired = useful.mapKeys { keys[it.key] }
            val entries = auxiliaryEntries.findAll(existing.value).toList()
            val present = (entries.asSequence() + emptyAuxiliary.findAll(existing.value))
                .mapNotNull { auxiliaryFor.find(it.groupValues[1])?.groupValues?.get(2) }.toSet()
            var block = existing.value
            var changed = false
            entries.asReversed().forEach { entry ->
                val id = auxiliaryFor.find(entry.groupValues[1])?.groupValues?.get(2)
                val value = desired[id]
                if (value != null && markup.replace(entry.groupValues[2], "").isBlank()) {
                    val start = entry.range.first + entry.value.indexOf('>') + 1
                    val end = entry.range.first + entry.value.lastIndexOf('<')
                    block = block.substring(0, start) + escape(value) + block.substring(end)
                    changed = true
                }
            }
            emptyAuxiliary.findAll(block).toList().asReversed().forEach { entry ->
                val id = auxiliaryFor.find(entry.groupValues[1])?.groupValues?.get(2)
                desired[id]?.let { value ->
                    block = block.replaceRange(entry.range, "<text${entry.groupValues[1]}>${escape(value)}</text>")
                    changed = true
                }
            }
            val missing = desired.filterKeys { it !in present }
            if (missing.isNotEmpty()) {
                val closing = if (pronunciation) "</transliteration>" else "</translation>"
                val at = block.indexOf(closing)
                if (at < 0) return null
                val added = missing.entries.joinToString("") { "<text for=\"${escape(it.key)}\">${escape(it.value)}</text>" }
                block = block.substring(0, at) + added + block.substring(at)
                changed = true
            }
            return if (changed) ttml.replaceRange(existing.range, block) else null
        }
        var modified = ttml
        for (index in matches.indices.reversed()) {
            if (key.containsMatchIn(matches[index].groupValues[1])) continue
            val at = matches[index].range.first + matches[index].value.indexOf('>')
            modified = modified.substring(0, at) + " itunes:key=\"${keys[index]}\"" + modified.substring(at)
        }
        val entries = keys.mapIndexed { index, value ->
            "<text for=\"${escape(value)}\">${escape(useful[index].orEmpty().ifBlank { " " })}</text>"
        }.joinToString("")
        val track = if (pronunciation) "<transliterations><transliteration xml:lang=\"ko-Latn\">" +
            entries + "</transliteration></transliterations>" else
            "<translations><translation type=\"subtitle\" xml:lang=\"zh-Hans\">" + entries + "</translation></translations>"
        val metadata = Regex("(?is)<iTunesMetadata\\b[^>]*>").find(modified)
        modified = if (metadata != null) {
            val at = metadata.range.last + 1
            modified.substring(0, at) + track + modified.substring(at)
        } else {
            val container = "<iTunesMetadata xmlns=\"http://music.apple.com/lyric-ttml-internal\">" +
                track + "</iTunesMetadata>"
            val metadataTag = Regex("(?is)<metadata\\b[^>]*>").find(modified)
            val headTag = Regex("(?is)<head\\b[^>]*>").find(modified)
            val insertion = when {
                metadataTag != null -> metadataTag.range.last + 1 to container
                headTag != null -> headTag.range.last + 1 to "<metadata>$container</metadata>"
                else -> (Regex("(?is)<body\\b").find(modified)?.range?.first ?: return null) to
                    "<head><metadata>$container</metadata></head>"
            }
            modified.substring(0, insertion.first) + insertion.second + modified.substring(insertion.first)
        }
        val rootMatch = root.find(modified) ?: return null
        var tag = rootMatch.value
        if (!tag.contains("xmlns:itunes=")) {
            tag = tag.dropLast(1) + " xmlns:itunes=\"http://music.apple.com/lyric-ttml-internal\">"
        }
        tag = if (Regex("\\bxml:lang\\s*=").containsMatchIn(tag)) {
            tag.replace(Regex("\\bxml:lang\\s*=\\s*([\"']).*?\\1"), "xml:lang=\"ko\"")
        } else tag.dropLast(1) + " xml:lang=\"ko\">"
        return TtmlOriginalLanguage.preserve(ttml,modified.replaceRange(rootMatch.range, tag))
    }

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;")
}
