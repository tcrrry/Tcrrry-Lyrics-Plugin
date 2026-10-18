package com.tcrrry.lyrics.engine.hook
import com.tcrrry.lyrics.plugin.PluginStorage.pluginFilesDir
import com.tcrrry.lyrics.plugin.PluginStorage.pluginPreferences

import android.app.Application
import android.content.Context
import com.tcrrry.lyrics.engine.config.EmbeddedConfigurationSession
import com.tcrrry.lyrics.engine.config.EmbeddedContentManager
import com.tcrrry.lyrics.engine.config.HostPrivateEmbeddedStorage
import com.tcrrry.lyrics.engine.lyrics.CustomLyricsDraft
import com.tcrrry.lyrics.engine.lyrics.CustomLyricsFilePolicy
import com.tcrrry.lyrics.engine.lyrics.CustomLyricsSaveResult
import com.tcrrry.lyrics.engine.lyrics.TtmlInputPolicy
import com.tcrrry.lyrics.engine.lyrics.source.AmLyricsClient
import com.tcrrry.lyrics.engine.lyrics.source.AmllTtmlClient
import com.tcrrry.lyrics.engine.lyrics.source.AutoLyricsSourceResolver
import com.tcrrry.lyrics.engine.lyrics.source.FileLunabeatCatalogCache
import com.tcrrry.lyrics.engine.lyrics.source.HttpLyricTransport
import com.tcrrry.lyrics.engine.lyrics.source.LunabeatClient
import com.tcrrry.lyrics.engine.model.CustomLyricsSources
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.LinkedHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.FutureTask
import org.json.JSONArray


internal fun createAutoLyricsRuntime(
    application: Application,
    suppressedIds: Set<Long> = emptySet(),
): AutoLyricsRuntime {
    val root = LyricsCacheStorage.automaticDirectory(application)
    // The previous cache may contain a prematurely selected or rejected candidate.
    runCatching { File(application.pluginFilesDir, "ampp-auto-lyrics-desktop-v6").deleteRecursively() }
    val lyricTransport = HttpLyricTransport(
        connectTimeoutMs = 4_000,
        readTimeoutMs = 8_000,
        maxResponseBytes = TtmlInputPolicy.MAX_TTML_BYTES,
    )
    val indexTransport = HttpLyricTransport(
        connectTimeoutMs = 4_000,
        readTimeoutMs = 8_000,
        maxResponseBytes = LunabeatClient.INDEX_MAX_BYTES,
    )
    val lunabeat = LunabeatClient(
        indexTransport = indexTransport,
        lyricsTransport = lyricTransport,
        cache = FileLunabeatCatalogCache(File(root, "lunabeat")),
    )
    val desktopLyrics = DesktopLyricsSource(application)
    val resolver = AutoLyricsSourceResolver.fixed(
        amll = AmllTtmlClient(lyricTransport),
        amLyrics = AmLyricsClient(lyricTransport),
        lunabeat = lunabeat,
        desktopLyrics = desktopLyrics::fetch,
        fallbackTranslation = FallbackLyricsTranslation(application)::enrich,
        qualityFirst = { com.tcrrry.lyrics.engine.lyrics.LyricsPreference.qualityFirst(application) },
    )
    val diskCache = FileAutoLyricsCache(root)
    fun cachePolicy(id: Long): AutoLyricsCachePolicy {
        val selected = CurrentLyricsSourceStatus.selectedSource(application, id)
        val sourcePrefs = application.pluginPreferences("ampp-current-lyrics-source", Context.MODE_PRIVATE)
        val options = org.json.JSONArray().put(selected)
        for (source in LyricsSourceMenuPolicy.sources) {
            options.put(sourcePrefs.getString("version_${id}_$source", null))
                .put(CurrentLyricsSourceStatus.excludedRecords(application, id, source).sorted().toString())
                .put(CurrentLyricsSourceStatus.offsetMs(application, id, source))
        }
        for (name in listOf("supplement_translation", "japanese_pronunciation")) {
            val values = application.pluginPreferences(name, Context.MODE_PRIVATE).all.toSortedMap()
            options.put(org.json.JSONObject().apply { values.forEach { (key, value) -> put(key, value) } })
        }
        options.put(NativeLyricsEmphasisVisibility.visible(application))
        options.put(com.tcrrry.lyrics.engine.lyrics.LyricsPreference.hideOpeningCredits(application))
            options.put("opening-credits-raw-text-2")
        options.put("qq-completeness-1")
        options.put("han-auxiliary-comparison-3")
        return AutoLyricsCachePolicy(
            com.tcrrry.lyrics.engine.lyrics.LyricsPreference.qualityFirst(application),
            com.tcrrry.lyrics.engine.lyrics.LyricsPreference.smoothShortUnits(application), options.toString())
    }
    val cache = object : AutoLyricsCache by diskCache {
        private val requests = ThreadLocal<Triple<Long, Long, AutoLyricsCachePolicy>>()
        override fun write(appleMusicId: Long, ttml: String): Boolean {
            val request = requests.get()?.takeIf { it.first == appleMusicId } ?: return false
            val stamped = request.third.stamp(ttml)
            if (!cachePolicy(appleMusicId).matches(stamped)) return false
            return LyricsCacheStorage.store(application, request.second) { diskCache.write(appleMusicId, stamped) }
        }
        override fun read(appleMusicId: Long): String? {
            val request = Triple(appleMusicId, LyricsCacheStorage.epoch, cachePolicy(appleMusicId))
            requests.set(request)
            if (LyricsCacheStorage.limitMb(application) == 0) return null
            return diskCache.read(appleMusicId)?.let { cached ->
                if (!cachePolicy(appleMusicId).matches(cached)) return null
                val translationMode = application.pluginPreferences("supplement_translation", Context.MODE_PRIVATE).getString("mode", "off")
                if (!TranslationOutcomeCache.canReuse(cached, translationMode in setOf("api", "offline"))) return null
                val pronunciationEnabled = application.pluginPreferences("japanese_pronunciation", Context.MODE_PRIVATE)
                    .getBoolean("enabled", true)
                if (!com.tcrrry.lyrics.engine.lyrics.JapanesePronunciationSupplement.cacheMatches(cached, pronunciationEnabled,
                    application.pluginPreferences("japanese_pronunciation", Context.MODE_PRIVATE).getBoolean("primary", false) &&
                        NativeLyricsEmphasisVisibility.visible(application))) return null
                if (cached.take(1024).contains("<!--tcrrry-lyrics-v1 ") &&
                    !cached.take(1024).contains(com.tcrrry.lyrics.engine.lyrics.LanguagePronunciationSupplement.marker(application, appleMusicId))) return null
                TtmlSubtitleTrack.cleanChineseSubtitles(cached).also { cleaned ->
                    if (cleaned != cached) LyricsCacheStorage.store(application, request.second) { diskCache.write(appleMusicId, cleaned) }
                }
            }
        }
    }
    val executor = com.tcrrry.lyrics.plugin.PluginExecutors.pool(
        2,
        2,
        0L,
        TimeUnit.MILLISECONDS,
        // Song changes must not wait for the previous song's fallback network requests.
        ArrayBlockingQueue(4),
        { runnable -> Thread(runnable, "ampp-auto-lyrics-desktop-v14-japanese-r1").apply { isDaemon = true } },
        // Rejection is handled by ensureRequested, which clears its pending marker.
        ThreadPoolExecutor.AbortPolicy(),
    )
    return AutoLyricsRuntime(
        resolver = resolver,
        cache = cache,
        executor = executor,
        publisher = null,
        suppressedIds = suppressedIds,
        invalidateSearch = desktopLyrics::invalidate,
    )
}




/** A validated candidate returned by one of the automatic lyric sources. */
data class AutoLyricsCandidate(
    val source: String,
    val ttml: String,
    val displayName: String? = null,
)

/** Persistent raw-TTML cache seam; all calls happen off the I2/main hot path. */
interface AutoLyricsCache {
    fun read(appleMusicId: Long): String?
    fun write(appleMusicId: Long, ttml: String): Boolean
    fun delete(appleMusicId: Long): Boolean = false
    fun cachedIds(): List<Long> = emptyList()
}

enum class AutoLyricsPublishResult {
    PUBLISHED,
    ALREADY_CONFIGURED,
    FAILED,
}

/** Publishes a validated automatic lyric into the normal configured index. */
fun interface AutoLyricsPublisher {
    fun publish(appleMusicId: Long, candidate: AutoLyricsCandidate): AutoLyricsPublishResult
}

/** No-op cache used when a target adapter cannot provide host-private storage. */
object DisabledAutoLyricsCache : AutoLyricsCache {
    override fun read(appleMusicId: Long): String? = null
    override fun write(appleMusicId: Long, ttml: String): Boolean = false
}

/** Target-process wiring for the opt-in automatic resolver. */
data class AutoLyricsRuntime(
    val resolver: AutoLyricsSourceResolver,
    val cache: AutoLyricsCache,
    val executor: Executor,
    val publisher: AutoLyricsPublisher? = null,
    val suppressedIds: Set<Long> = emptySet(),
    val invalidateSearch: (Long) -> Unit = {},
)

/**
 * Small atomic file cache kept outside the user-managed custom-lyrics index.
 * The raw TTML is useful across Apple Music process restarts; native pointers
 * are deliberately never persisted because their JavaCPP address is process
 * local. A compact ID index bounds disk growth and lets old files be removed.
 */
class FileAutoLyricsCache(
    private val directory: File,
    private val maxEntries: Int = MAX_ENTRIES,
    private val maxBytes: Long = MAX_CACHE_BYTES,
) : AutoLyricsCache {
    private val indexFile = File(directory, INDEX_FILE_NAME)

    @Synchronized override fun read(appleMusicId: Long): String? {
        if (appleMusicId <= 0L) return null
        val file = lyricFile(appleMusicId) ?: return null
        return runCatching {
            if (!file.isFile || file.length() !in 1L..MAX_TTML_BYTES) return@runCatching null
            val result = FileInputStream(file).use { input ->
                CustomLyricsFilePolicy.readBounded(input).toString(Charsets.UTF_8)
            }
            file.setLastModified(System.currentTimeMillis())
            val ids = readIds().toMutableList()
            if (ids.remove(appleMusicId)) {
                ids.add(appleMusicId)
                writeIndex(ids)
            }
            result
        }.getOrNull()
    }

    @Synchronized override fun delete(appleMusicId: Long): Boolean {
        if (appleMusicId <= 0L) return false
        val file = lyricFile(appleMusicId) ?: return false
        return runCatching {
            val deleted = file.isFile && file.delete()
            if (deleted) writeIndex(readIds().filterNot { it == appleMusicId })
            deleted
        }.getOrDefault(false)
    }

    override fun cachedIds(): List<Long> = readIds()

    @Synchronized override fun write(appleMusicId: Long, ttml: String): Boolean {
        if (
            appleMusicId <= 0L ||
            !TtmlInputPolicy.isAcceptable(ttml)
        ) return false
        val bytes = ttml.toByteArray(Charsets.UTF_8)
        val file = lyricFile(appleMusicId) ?: return false
        if (bytes.size > MAX_TTML_BYTES || bytes.size > maxBytes) return false
        return runCatching {
            if (!directory.exists() && !directory.mkdirs()) return@runCatching false
            atomicWrite(file, bytes)
            val ids = readIds().toMutableList().apply {
                remove(appleMusicId)
                add(appleMusicId)
            }
            var totalBytes = ids.sumOf { lyricFile(it)?.takeIf(File::isFile)?.length() ?: 0L }
            while (ids.size > maxEntries.coerceAtLeast(1) || totalBytes > maxBytes.coerceAtLeast(1L)) {
                val oldest = ids.removeAt(0)
                val oldFile = lyricFile(oldest)
                totalBytes -= oldFile?.takeIf(File::isFile)?.length() ?: 0L
                oldFile?.delete()
            }
            writeIndex(ids)
            true
        }.getOrDefault(false)
    }

    private fun readIds(): List<Long> = runCatching {
        if (!indexFile.isFile || indexFile.length() !in 1L..MAX_INDEX_BYTES) {
            return@runCatching emptyList()
        }
        val array = JSONArray(indexFile.readText(Charsets.UTF_8))
        buildList {
            for (index in 0 until array.length()) {
                array.optLong(index, -1L).takeIf { it > 0L }?.let(::add)
            }
        }.distinct()
    }.getOrDefault(emptyList())

    private fun writeIndex(ids: List<Long>) {
        val index = JSONArray().apply { ids.forEach(::put) }
        atomicWrite(indexFile, index.toString().toByteArray(Charsets.UTF_8))
    }

    private fun lyricFile(appleMusicId: Long): File? = appleMusicId
        .takeIf { it > 0L }
        ?.let { File(directory, "$FILE_PREFIX$it$FILE_SUFFIX") }

    private fun atomicWrite(destination: File, bytes: ByteArray) {
        val pending = File.createTempFile("pending_", ".tmp", directory)
        try {
            FileOutputStream(pending).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            try {
                Files.move(
                    pending.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    pending.toPath(),
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } finally {
            pending.delete()
        }
    }

    private companion object {
        const val FILE_PREFIX = "lyric_"
        const val FILE_SUFFIX = ".ttml"
        const val INDEX_FILE_NAME = "index.json"
        const val MAX_ENTRIES = 50_000
        const val MAX_CACHE_BYTES = 2L * 1024 * 1024 * 1024
        const val MAX_TTML_BYTES = 512 * 1024L
        const val MAX_INDEX_BYTES = 1024 * 1024L
    }
}

/**
 * Prepares automatic replacements off-hook. The I2 path only checks the
 * bounded native-pointer cache and queues this session when it is cold.
 */
class AutoLyricsReplacementSession(
    private val fetchCandidate: (Long) -> AutoLyricsCandidate?,
    private val cache: AutoLyricsCache,
    private val parseTtml: (String) -> Any?,
    private val isAlive: (Any?) -> Boolean,
    private val verifyPtr: (Any?) -> Boolean,
    private val readAdamId: (Any) -> Long?,
    private val bindAdamId: (Any, Long) -> Boolean,
    private val onReplacementPublished: ((Long) -> Unit)? = null,
    private val onCandidatePrepared: ((Long, AutoLyricsCandidate) -> Unit)? = null,
    private val onRefreshFinished: ((Long, Boolean) -> Unit)? = null,
    private val publisher: AutoLyricsPublisher? = null,
    private val isAllowed: (Long) -> Boolean = { true },
    private val executor: Executor,
    private val logger: (String) -> Unit,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val retryCooldownMs: Long = DEFAULT_RETRY_COOLDOWN_MS,
    private val onTaggedRefreshFinished: ((Long, Boolean, Long?) -> Unit)? = null,
    private val presentationKey: () -> String = { "" },
) {
    private val pointers = object : LinkedHashMap<Long, Any>(CACHE_CAPACITY, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Any>?): Boolean =
            size > CACHE_CAPACITY
    }
    private val lock = Any()
    private val pending = mutableMapOf<Long, Long>()
    private val jobs = mutableMapOf<Long, FutureTask<Unit>>()
    private val failedUntil = mutableMapOf<Long, Long>()
    private var generation = 0L
    private var activeSongKnown = false
    private var activeAppleMusicId: Long? = null
    private val activeTakeovers = mutableSetOf<Long>()
    private var refreshBackup: Pair<Long, Any>? = null
    private val documents = mutableMapOf<Long, String>()
    private var refreshDocument: String? = null
    private var refreshGeneration: Long? = null
    private var refreshTag: Long? = null

    /** Invalidates in-flight results and native pointers only when playback changes. */
    fun onSongChanged(appleMusicId: Long?) {
        val changed = synchronized(lock) {
            val sameSong = activeSongKnown == (appleMusicId != null) &&
                activeAppleMusicId == appleMusicId
            if (!sameSong) {
                generation += 1L
                activeSongKnown = appleMusicId != null
                activeAppleMusicId = appleMusicId
                pending.clear()
                jobs.values.forEach { it.cancel(true) }
                jobs.clear()
                failedUntil.clear()
                activeTakeovers.clear()
                refreshBackup = null
                refreshDocument = null
                documents.clear()
                refreshGeneration = null
                refreshTag = null
            }
            !sameSong
        }
        if (changed) {
            synchronized(pointers) {
                pointers.clear()
            }
        }
    }

    fun replacementFor(appleMusicId: Long): Any? {
        if (appleMusicId <= 0L || !isCurrentSong(appleMusicId) || !isAllowed(appleMusicId)) return null
        readyReplacementFor(appleMusicId)?.let { return it }
        request(appleMusicId)
        return readyReplacementFor(appleMusicId)
    }

    fun ensureRequested(appleMusicId: Long) {
        if (appleMusicId > 0L && isCurrentSong(appleMusicId) && isAllowed(appleMusicId)) {
            request(appleMusicId)
        }
    }

    fun refreshCurrent(appleMusicId: Long, completionTag: Long? = null) {
        if (!isCurrentSong(appleMusicId)) return
        val old = readyReplacementFor(appleMusicId) ?: refreshBackup?.takeIf { it.first == appleMusicId }?.second
        val oldDocument = synchronized(lock) { documents[appleMusicId] ?: refreshDocument }
        runCatching { cache.delete(appleMusicId) }
        onSongChanged(null)
        onSongChanged(appleMusicId)
        synchronized(lock) {
            refreshBackup = old?.let { appleMusicId to it }
            refreshDocument = oldDocument
            refreshGeneration = generation
            refreshTag = completionTag
        }
        ensureRequested(appleMusicId)
    }

    fun isTracking(appleMusicId: Long): Boolean = synchronized(lock) {
        appleMusicId > 0L && isCurrentSongLocked(appleMusicId) && isAllowed(appleMusicId) &&
            (appleMusicId in pending || synchronized(pointers) { appleMusicId in pointers })
    }

    fun readyReplacementFor(appleMusicId: Long): Any? {
        if (appleMusicId <= 0L || !isAllowed(appleMusicId)) return null
        synchronized(lock) {
            if (!isCurrentSongLocked(appleMusicId)) return null
            synchronized(pointers) {
                val pointer = pointers[appleMusicId]
                    ?: refreshBackup?.takeIf { it.first == appleMusicId }?.second ?: return null
                if (runCatching { isAlive(pointer) }.getOrDefault(false)) return pointer
                pointers.remove(appleMusicId)
            }
        }
        return null
    }

    fun replacementOrPrepareFor(appleMusicId: Long): Any? = replacementFor(appleMusicId)

    /** Records that an automatic pointer has actually been installed into I2. */
    fun markTakeoverApplied(appleMusicId: Long) {
        synchronized(lock) {
            if (isCurrentSongLocked(appleMusicId)) activeTakeovers += appleMusicId
        }
    }

    /**
     * Keeps an already-visible automatic pointer across an unrelated native
     * refresh for the same song. A newly observed higher-quality native
     * document is allowed to win; an unknown pointer fails open to the current
     * automatic display once takeover has already happened.
     */
    fun takeoverReplacementFor(
        appleMusicId: Long,
        original: Any?,
        metadata: TtmlDocumentMetadata?,
    ): Any? {
        val replacement = readyReplacementFor(appleMusicId) ?: return null
        if (replacement === original) return replacement
        synchronized(lock) {
            if (appleMusicId !in activeTakeovers) return null
        }
        if (metadata?.timingMode == TtmlTimingMode.NON_WORD ||
            metadata?.needsTranslationFallback == true ||
            metadata == null
        ) {
            return replacement
        }
        synchronized(lock) { activeTakeovers.remove(appleMusicId) }
        return null
    }

    private fun request(appleMusicId: Long) {
        synchronized(lock) {
            if (!isCurrentSongLocked(appleMusicId)) {
                logger("automatic lyrics skipped stale song id=$appleMusicId")
                return
            }
            val requestGeneration = generation
            if (pending[appleMusicId] == requestGeneration) return
            synchronized(pointers) {
                if (appleMusicId in pointers) return
            }
            val retryAt = failedUntil[appleMusicId] ?: 0L
            if (retryAt > nowMs()) return
            pending[appleMusicId] = requestGeneration
            logger("automatic lyrics queued id=$appleMusicId generation=$requestGeneration")
            try {
                val job = FutureTask<Unit> { prepare(appleMusicId, requestGeneration) }
                jobs[appleMusicId] = job
                executor.execute(job)
            } catch (_: RejectedExecutionException) {
                if (pending[appleMusicId] == requestGeneration) pending.remove(appleMusicId)
                jobs.remove(appleMusicId)
                logger("automatic lyrics prepare was rejected for $appleMusicId")
                if (refreshGeneration == requestGeneration) {
                    markFailedIfCurrent(appleMusicId, requestGeneration)
                    val tag = refreshTag
                    refreshGeneration = null
                    refreshTag = null
                    onRefreshFinished?.invoke(appleMusicId, false)
                    onTaggedRefreshFinished?.invoke(appleMusicId, false, tag)
                }
            }
        }
    }

    private fun prepare(appleMusicId: Long, requestGeneration: Long) {
        var published = false
        var preparedCandidate: AutoLyricsCandidate? = null
        var preparedPointer: Any? = null
        try {
            if (!isCurrentRequest(appleMusicId, requestGeneration)) {
                logger("automatic lyrics cancelled before fetch id=$appleMusicId")
                return
            }
            val cached = runCatching { cache.read(appleMusicId) }.getOrNull()
            logger("automatic lyrics cache id=$appleMusicId hit=${cached != null}")
            if (cached != null && isCurrentRequest(appleMusicId, requestGeneration)) {
                val candidate = AutoLyricsCandidate(CustomLyricsSources.AUTO_CACHE, cached)
                logger(
                    "automatic lyrics cache parse begin id=$appleMusicId bytes=${cached.length} " +
                        "translation=${"<translations>" in cached}",
                )
                preparedPointer = preparePointer(
                    candidate.ttml,
                    appleMusicId,
                    source = candidate.source,
                    requestGeneration = requestGeneration,
                )
                logger("automatic lyrics cache parse end id=$appleMusicId ready=${preparedPointer != null}")
                published = preparedPointer != null
                if (published) preparedCandidate = candidate
            }
            val upgradeCache = published && cached != null &&
                TtmlTimingPolicy.metadataOf(cached).timingMode == TtmlTimingMode.NON_WORD
            if (upgradeCache && isCurrentRequest(appleMusicId, requestGeneration)) {
                onCandidatePrepared?.invoke(appleMusicId, preparedCandidate!!)
                onReplacementPublished?.invoke(appleMusicId)
            }
            if ((!published || upgradeCache) && isCurrentRequest(appleMusicId, requestGeneration)) {
                logger("automatic lyrics fetching id=$appleMusicId")
                val candidate = runCatching { fetchCandidate(appleMusicId) }.getOrNull()
                if (candidate == null) logger("automatic lyrics no candidate id=$appleMusicId")
                if (candidate != null && isCurrentRequest(appleMusicId, requestGeneration) &&
                    (!upgradeCache || TtmlTimingPolicy.metadataOf(candidate.ttml).timingMode == TtmlTimingMode.WORD)) {
                    val freshPointer = preparePointer(
                        candidate.ttml,
                        appleMusicId,
                        candidate.source,
                        requestGeneration,
                    )
                    if (freshPointer != null) {
                        preparedPointer = freshPointer
                        published = true
                        preparedCandidate = candidate
                        logger("automatic lyrics prepared source=${candidate.source} id=$appleMusicId")
                        if (candidate.source.startsWith(CustomLyricsSources.DESKTOP_LYRICS) &&
                            isCurrentRequest(appleMusicId, requestGeneration)) {
                            runCatching { cache.write(appleMusicId, candidate.ttml) }
                        }
                    }
                }
            }
            if (!published || preparedCandidate == null ||
                !isCurrentRequest(appleMusicId, requestGeneration)
            ) {
                removePointerIf(appleMusicId, preparedPointer)
                if (!published || preparedCandidate == null) {
                    markFailedIfCurrent(appleMusicId, requestGeneration)
                }
                return
            }
            val publishResult = publisher?.let {
                runCatching { it.publish(appleMusicId, preparedCandidate!!) }
                    .getOrDefault(AutoLyricsPublishResult.FAILED)
            }
            when (publishResult) {
                null,
                AutoLyricsPublishResult.PUBLISHED -> {
                    if (publishResult == AutoLyricsPublishResult.PUBLISHED) {
                        runCatching { cache.delete(appleMusicId) }
                    }
                    if (isCurrentRequest(appleMusicId, requestGeneration)) {
                        onCandidatePrepared?.invoke(appleMusicId, preparedCandidate)
                        onReplacementPublished?.invoke(appleMusicId)
                        synchronized(lock) {
                            if (isCurrentRequestLocked(appleMusicId, requestGeneration)) {
                                failedUntil.remove(appleMusicId)
                            }
                        }
                    } else {
                        removePointerIf(appleMusicId, preparedPointer)
                    }
                }
                AutoLyricsPublishResult.ALREADY_CONFIGURED -> {
                    runCatching { cache.delete(appleMusicId) }
                    removePointerIf(appleMusicId, preparedPointer)
                }
                AutoLyricsPublishResult.FAILED -> {
                    removePointerIf(appleMusicId, preparedPointer)
                    markFailedIfCurrent(appleMusicId, requestGeneration)
                }
            }
        } finally {
            val completion = synchronized(lock) {
                if (refreshGeneration == requestGeneration && isCurrentRequestLocked(appleMusicId, requestGeneration)) {
                    val tag = refreshTag
                    refreshGeneration = null
                    refreshTag = null
                    if (published) { refreshBackup = null; refreshDocument = null }
                    true to tag
                } else false to null
            }
            if (completion.first) {
                onRefreshFinished?.invoke(appleMusicId, published)
                onTaggedRefreshFinished?.invoke(appleMusicId, published, completion.second)
            }
            synchronized(lock) {
                if (pending[appleMusicId] == requestGeneration) {
                    pending.remove(appleMusicId)
                    jobs.remove(appleMusicId)
                }
            }
        }
    }

    private fun preparePointer(
        ttml: String,
        appleMusicId: Long,
        source: String,
        requestGeneration: Long,
    ): Any? {
        if (!TtmlInputPolicy.isAcceptable(ttml)) {
            logger("automatic lyrics candidate rejected as invalid TTML source=$source id=$appleMusicId")
            return null
        }
        val key = presentationKey()
        val document = AutoLyricsCachePolicy.strip(ttml).let { if (key.isEmpty()) it else "$it\u0000$key" }
        synchronized(lock) {
            if (isCurrentRequestLocked(appleMusicId, requestGeneration)) {
                val previous = synchronized(pointers) { pointers[appleMusicId] }
                    ?: refreshBackup?.takeIf { it.first == appleMusicId }?.second
                if (previous != null && (documents[appleMusicId] ?: refreshDocument) == document &&
                    runCatching { isAlive(previous) && isPrepared(previous, appleMusicId) }.getOrDefault(false)) {
                    synchronized(pointers) { pointers[appleMusicId] = previous }
                    documents[appleMusicId] = document
                    logger("automatic lyrics unchanged document retained id=$appleMusicId")
                    return previous
                }
            }
        }
        logger("automatic lyrics native parse begin source=$source id=$appleMusicId")
        val pointer = runCatching { parseTtml(ttml) }.getOrNull() ?: run {
            logger("automatic lyrics native parse failed source=$source id=$appleMusicId")
            return null
        }
        logger("automatic lyrics native parse end source=$source id=$appleMusicId")
        if (!isPrepared(pointer, appleMusicId) && !runCatching {
                bindAdamId(pointer, appleMusicId)
            }.getOrDefault(false)
        ) {
            logger("automatic lyrics pointer binding failed source=$source id=$appleMusicId")
            return null
        }
        if (!isPrepared(pointer, appleMusicId)) {
            logger("automatic lyrics pointer was unusable source=$source id=$appleMusicId")
            return null
        }
        val accepted = synchronized(lock) {
            if (!isCurrentRequestLocked(appleMusicId, requestGeneration)) {
                false
            } else {
                synchronized(pointers) { pointers[appleMusicId] = pointer }
                documents[appleMusicId] = document
                true
            }
        }
        return pointer.takeIf { accepted }
    }

    private fun markFailedIfCurrent(appleMusicId: Long, requestGeneration: Long) {
        synchronized(lock) {
            if (isCurrentRequestLocked(appleMusicId, requestGeneration)) {
                refreshBackup?.takeIf { it.first == appleMusicId }?.let { (_, pointer) ->
                    synchronized(pointers) { pointers[appleMusicId] = pointer }
                    refreshDocument?.let { documents[appleMusicId] = it }
                }
                refreshBackup = null
                refreshDocument = null
                failedUntil[appleMusicId] = nowMs() + retryCooldownMs
            }
        }
    }

    private fun isPrepared(pointer: Any?, appleMusicId: Long): Boolean = runCatching {
        pointer != null && verifyPtr(pointer) && readAdamId(pointer) == appleMusicId
    }.getOrDefault(false)

    private fun isCurrentSong(appleMusicId: Long): Boolean = synchronized(lock) {
        isCurrentSongLocked(appleMusicId)
    }

    private fun isCurrentSongLocked(appleMusicId: Long): Boolean =
        !activeSongKnown || activeAppleMusicId == appleMusicId

    private fun isCurrentRequest(appleMusicId: Long, requestGeneration: Long): Boolean =
        synchronized(lock) { isCurrentRequestLocked(appleMusicId, requestGeneration) }

    private fun isCurrentRequestLocked(appleMusicId: Long, requestGeneration: Long): Boolean =
        generation == requestGeneration &&
            isCurrentSongLocked(appleMusicId) &&
            isAllowed(appleMusicId)

    private fun removePointerIf(appleMusicId: Long, pointer: Any?) {
        if (pointer == null) return
        synchronized(pointers) {
            if (pointers[appleMusicId] === pointer) pointers.remove(appleMusicId)
        }
    }

    private companion object {
        const val CACHE_CAPACITY = 32
        const val DEFAULT_RETRY_COOLDOWN_MS = 30_000L
    }
}
