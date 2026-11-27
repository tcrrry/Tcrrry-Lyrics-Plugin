package dev.kifranei.ampp.media

import org.json.JSONObject
import java.io.File
import java.lang.reflect.Method
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 音频分析数据提供者。
 *
 * hook PlayerAudioFadeControl.getAudioAnalysis(PlayerMediaItem) 获取 Apple Music 原生的
 * 音频分析数据（BPM、调性、节拍、能量、舞曲性等），供 TransitionPlanner 做智能过渡规划。
 *
 * 注意：AudioAnalysis.getBPM() 是大写 BPM，不是 getBpm()。
 */
internal class AudioAnalysisProvider(
    private val loader: ClassLoader,
    private val cacheFile: File? = null,
) {
    private val fadeControlClass = runCatching { loader.loadClass("com.apple.android.music.playback.player.PlayerAudioFadeControl") }.getOrNull()
    private val playerMediaItemClass = runCatching { loader.loadClass("com.apple.android.music.playback.model.PlayerMediaItem") }.getOrNull()
    private val appleCache = object : LinkedHashMap<String, SongAudioAnalysis>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SongAudioAnalysis>?): Boolean = size > 64
    }
    /** 本地算法提取的分析结果（Apple AudioAnalysis 不可用时兜底，支持持久化）。 */
    private val localCache = LinkedHashMap<String, SongAudioAnalysis>(64, 0.75f, true)
    private val appleBackedIds = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val persistLock = Any()
    private val persistExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "automix-analysis-cache").apply { isDaemon = true }
    }

    init {
        loadPersistedLocalCache()
    }

    /** 缓存的当前/下一首分析，供 TransitionPlanner 读取。 */
    @Volatile var current: SongAudioAnalysis? = null
    @Volatile var next: SongAudioAnalysis? = null

    fun hasAppleAnalysis(itemId: String?): Boolean =
        !itemId.isNullOrBlank() && (appleBackedIds.contains(itemId) || synchronized(persistLock) { appleCache.containsKey(itemId) })

    private fun rememberAppleAnalysis(id: String, parsed: SongAudioAnalysis) {
        synchronized(persistLock) {
            appleCache[id] = parsed
        }
        injectLocalAnalysis(id, parsed, fromApple = true)
    }

    /** 注入本地/官方提取的分析结果并持久化（保护官方分析不被本地 PCM 覆盖，且相同值去重）。 */
    fun injectLocalAnalysis(itemId: String, analysis: SongAudioAnalysis, fromApple: Boolean = false) {
        if (!analysis.usable) return
        if (!fromApple && hasAppleAnalysis(itemId)) return
        val wasApple = appleBackedIds.contains(itemId)
        if (fromApple) appleBackedIds.add(itemId)
        val changed = synchronized(persistLock) {
            val prev = localCache[itemId]
            if (prev != null &&
                prev.bpm == analysis.bpm &&
                prev.key == analysis.key &&
                prev.energy == analysis.energy &&
                prev.danceability == analysis.danceability &&
                (!fromApple || wasApple)
            ) {
                false
            } else {
                localCache[itemId] = analysis
                trimLocalCacheLocked()
                true
            }
        }
        if (!changed) return
        persistLocalCacheAsync()
        FileLogger.log("AudioAnalysis: 写入分析缓存 id=$itemId src=${if (fromApple) "apple" else "local"} bpm=${analysis.bpm} key=${analysis.key} energy=${analysis.energy}")
    }

    fun close() {
        persistExecutor.shutdownNow()
    }

    private fun trimLocalCacheLocked() {
        while (localCache.size > MAX_PERSISTED_ENTRIES) {
            val oldest = localCache.entries.firstOrNull()?.key ?: break
            localCache.remove(oldest)
        }
    }

    private fun loadPersistedLocalCache() {
        val file = cacheFile ?: return
        if (!file.isFile) return
        runCatching {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            synchronized(persistLock) {
                val keys = root.keys()
                while (keys.hasNext()) {
                    val id = keys.next()
                    val item = root.optJSONObject(id) ?: continue
                    val parsed = SongAudioAnalysis(
                        bpm = if (item.has("bpm")) item.optDouble("bpm").toFloat().takeIf { !it.isNaN() && it > 0f } else null,
                        key = item.optString("key", "").takeIf { it.isNotBlank() },
                        energy = if (item.has("energy")) item.optDouble("energy").toFloat().takeIf { !it.isNaN() } else null,
                        danceability = if (item.has("danceability")) item.optDouble("danceability").toFloat().takeIf { !it.isNaN() } else null,
                        acousticness = null,
                    )
                    if (parsed.usable) localCache[id] = parsed
                }
                trimLocalCacheLocked()
            }
        }
    }

    private fun persistLocalCacheAsync() {
        val file = cacheFile ?: return
        val snapshot = synchronized(persistLock) { LinkedHashMap(localCache) }
        runCatching {
            persistExecutor.execute {
                persistLocalCacheSync(file, snapshot)
            }
        }
    }

    internal fun persistLocalCacheSync(
        file: File,
        entries: Map<String, SongAudioAnalysis>,
    ) {
        runCatching {
            val parent = file.parentFile
            if (parent != null && !parent.isDirectory) parent.mkdirs()
            val root = JSONObject()
            for ((id, item) in entries) {
                if (!item.usable) continue
                root.put(
                    id,
                    JSONObject().apply {
                        item.bpm?.let { put("bpm", it.toDouble()) }
                        item.key?.let { put("key", it) }
                        item.energy?.let { put("energy", it.toDouble()) }
                        item.danceability?.let { put("danceability", it.toDouble()) }
                    },
                )
            }
            val tmp = File(file.parentFile, "${file.name}.tmp")
            try {
                tmp.outputStream().use { out ->
                    out.write(root.toString().toByteArray(Charsets.UTF_8))
                    out.fd.sync()
                }
                try {
                    Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                tmp.delete()
            }
        }
    }

    fun clearCache(): Boolean {
        synchronized(persistLock) {
            appleCache.clear()
            appleBackedIds.clear()
            localCache.clear()
        }
        val file = cacheFile ?: return false
        return runCatching { !file.exists() || file.delete() }.getOrDefault(false)
    }

    fun cacheStats(): Pair<Int, Long> {
        val memCount = synchronized(persistLock) { localCache.size }
        val file = cacheFile
        val bytes = file?.let { if (it.exists()) it.length() else 0L } ?: 0L
        val count = if (memCount > 0 || file == null || !file.exists()) {
            memCount
        } else {
            readCacheStats(file).first
        }
        return count to bytes
    }

    companion object {
        const val MAX_PERSISTED_ENTRIES = 4000

        fun readCacheStats(file: File?): Pair<Int, Long> {
            if (file == null || !file.exists()) return 0 to 0L
            val bytes = file.length()
            val count = runCatching {
                JSONObject(file.readText(Charsets.UTF_8)).length()
            }.getOrDefault(0)
            return count to bytes
        }

        fun deletePersistedCache(file: File?): Boolean {
            if (file == null || !file.exists()) return false
            return runCatching { file.delete() }.getOrDefault(false)
        }
    }

    fun install(scope: PluginScope): Boolean {
        val fadeCls = fadeControlClass ?: run { FileLogger.log("AudioAnalysis: fadeControlClass null"); return false }
        val itemCls = playerMediaItemClass ?: run { FileLogger.log("AudioAnalysis: playerMediaItemClass null"); return false }
        val getAnalysis = runCatching {
            fadeCls.getDeclaredMethod("getAudioAnalysis", itemCls)
        }.getOrNull() ?: return false
        getAnalysis.isAccessible = true

        var installed = MediaRuntime.observeMethod(getAnalysis, object : PluginMethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.throwable != null) return
                val analysis = param.result ?: return
                val mediaItem = param.args[0] ?: return
                val id = extractItemId(mediaItem) ?: return
                val parsed = parse(analysis) ?: return
                rememberAppleAnalysis(id, parsed)
            }
        }, scope)

        // 预取：hook BaseAssetRequest.fetchAudioAnalysisIfAvailable 触发 Apple Music 后台分析，
        // 为后续过渡积累音频分析数据（本次拿不到数据没关系，下次切到这首歌时就有了）
        runCatching {
            val baseAssetCls = loader.loadClass("com.apple.android.music.playback.player.mediasource.BaseAssetRequest")
            val fetchMethod = baseAssetCls.getDeclaredMethod("fetchAudioAnalysisIfAvailable", itemCls)
            fetchMethod.isAccessible = true
            if (MediaRuntime.observeMethod(fetchMethod, object : PluginMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null) return
                    val mediaItem = param.args[0] ?: return
                    val id = extractItemId(mediaItem) ?: return
                    FileLogger.log("AudioAnalysis: fetchAudioAnalysisIfAvailable triggered for $id")
                }
            }, scope)) installed = true
        }
        return installed
    }

    /** 由 prepareForCrossFade 调用，从 MediaPeriodHolder 获取 PlayerMediaItem 并更新 current/next。 */
    fun updateFromHolders(fadeOutHolder: Any?, fadeInHolder: Any?, fadeControl: Any?) {
        val currentId = fadeOutHolder?.let { extractIdFromHolder(it) }
        val nextId = fadeInHolder?.let { extractIdFromHolder(it) }
        // 永远优先使用 Apple 官方完整分析（含 bars/响度曲线），仅当 Apple 未下发时才用本地 PCM 兜底。
        fun resolveForHolder(id: String?, holder: Any?): SongAudioAnalysis? {
            val liveApple = id?.let { synchronized(persistLock) { appleCache[it] } }
            if (liveApple != null) return liveApple
            val fetched = if (holder != null && fadeControl != null) fetchAnalysis(fadeControl, holder) else null
            if (fetched != null) return fetched
            return id?.let { synchronized(persistLock) { localCache[it] } }
        }
        current = resolveForHolder(currentId, fadeOutHolder)
        next = resolveForHolder(nextId, fadeInHolder)
        FileLogger.log("AudioAnalysis: holders current=${current?.bpm}/${current?.key}(bars=${current?.barTimesSec?.size ?: 0}) next=${next?.bpm}/${next?.key}(bars=${next?.barTimesSec?.size ?: 0})")
    }

    /**
     * 带变化检测的更新：maybeStartCrossFading 每帧被调用，但 holder 只在切歌时变化。
     * 返回 true 表示 holder 对发生了变化（需要重算 plan）。
     */
    private var lastOutHolderId = 0
    private var lastInHolderId = 0

    fun updateFromHoldersIfChanged(fadeOutHolder: Any?, fadeInHolder: Any?, fadeControl: Any?): Boolean {
        val outId = System.identityHashCode(fadeOutHolder)
        val inId = System.identityHashCode(fadeInHolder)
        if (outId == lastOutHolderId && inId == lastInHolderId) return false
        lastOutHolderId = outId
        lastInHolderId = inId
        updateFromHolders(fadeOutHolder, fadeInHolder, fadeControl)
        return true
    }

    private fun fetchAppleAnalysisById(id: String): SongAudioAnalysis? = runCatching {
        val resolver = loader.loadClass("n9.d")
        val method = resolver.getDeclaredMethod("e", String::class.java).apply { isAccessible = true }
        val analysis = method.invoke(null, id) ?: return null
        parse(analysis)?.also {
            rememberAppleAnalysis(id, it)
            FileLogger.log("AudioAnalysis: n9.d.e($id) 命中 Apple 分析 bpm=${it.bpm} key=${it.key}")
        }
    }.getOrElse {
        FileLogger.log("AudioAnalysis: n9.d.e($id) failed: ${it.message}")
        null
    }

    private fun fetchAnalysis(fadeControl: Any, holder: Any): SongAudioAnalysis? = runCatching {
        val mediaItem = extractMediaItemFromHolder(holder)
        if (mediaItem == null) {
            FileLogger.log("AudioAnalysis: extractMediaItemFromHolder failed, holder=${holder.javaClass.name}")
            return null
        }
        val method = fadeControl.javaClass.getDeclaredMethod("getAudioAnalysis", playerMediaItemClass!!)
        method.isAccessible = true
        val analysis = method.invoke(fadeControl, mediaItem)
        val id = extractItemId(mediaItem)
        if (analysis == null) {
            FileLogger.log("AudioAnalysis: getAudioAnalysis returned null for item=${mediaItem.javaClass.name}")
            // LibraryMediaItem 不走宿主分析；尝试 Apple 内部按 store id 取分析缓存（n9.d.e(String)）。
            val byId = id?.let { fetchAppleAnalysisById(it) }
            if (byId != null) return byId
            return null
        }
        id?.let { synchronized(persistLock) { appleCache[it] } }?.let { return it }
        val parsed = parse(analysis) ?: return null
        // 写回缓存并持久化，供下次过渡与缓存统计直接使用
        if (id != null) {
            rememberAppleAnalysis(id, parsed)
        }
        parsed
    }.getOrElse {
        FileLogger.log("AudioAnalysis: fetchAnalysis error: ${it.message}")
        null
    }

    internal fun extractIdFromHolder(holder: Any): String? = runCatching {
        val mediaItem = extractMediaItemFromHolder(holder) ?: return null
        extractItemId(mediaItem)
    }.getOrNull()

    private fun extractMediaItemFromHolder(holder: Any): Any? = runCatching {
        // PlaybackAssetMediaPeriod.mediaItem 字段（LibraryMediaItem / PlayerMediaItem）
        val mediaPeriod = holder.javaClass.getField("mediaPeriod").get(holder)
        val f = mediaPeriod.javaClass.getDeclaredField("mediaItem")
        f.isAccessible = true
        f.get(mediaPeriod)
    }.getOrNull()

    private fun extractItemId(mediaItem: Any): String? = runCatching {
        val direct = (runCatching { mediaItem.javaClass.getMethod("getId").invoke(mediaItem) as? String }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: runCatching { mediaItem.javaClass.getMethod("getStoreId").invoke(mediaItem) as? String }.getOrNull()
                ?.takeIf { it.isNotBlank() }
            ?: runCatching { mediaItem.javaClass.getMethod("getCatalogId").invoke(mediaItem) as? String }.getOrNull()
                ?.takeIf { it.isNotBlank() }
            ?: runCatching { mediaItem.javaClass.getMethod("getCloudId").invoke(mediaItem) as? String }.getOrNull()
                ?.takeIf { it.isNotBlank() })
        if (direct != null) return@runCatching direct
        val title = runCatching { mediaItem.javaClass.getMethod("getTitle").invoke(mediaItem) as? String }.getOrNull().orEmpty().trim()
        val artist = runCatching { mediaItem.javaClass.getMethod("getArtistName").invoke(mediaItem) as? String }.getOrNull().orEmpty().trim()
        if (title.isNotEmpty()) "$title|$artist" else null
    }.getOrNull()

    private fun readBeatArray(analysis: Any, method: String): FloatArray? {
        val beats = runCatching { callObject(analysis, "getBeats") }.getOrNull() ?: return null
        val values = runCatching {
            beats.javaClass.getMethod(method).invoke(beats) as? Array<*>
        }.getOrNull() ?: return null
        val result = FloatArray(values.size) {
            ((values[it] as? Number)?.toFloat() ?: 0f) / 1000f
        }
        return result.takeIf { it.isNotEmpty() }
    }

    private fun readLoudnessCurve(analysis: Any): FloatArray? = runCatching {
        val curve = callObject(analysis, "getLoudnessCurve") ?: return null
        val values = curve.javaClass.getMethod("getValue").invoke(curve) as? Array<*> ?: return null
        FloatArray(values.size) { (values[it] as? Number)?.toFloat() ?: 0f }
    }.getOrNull()

    private fun readFadeMs(analysis: Any, fadeMethod: String, valueMethod: String): Float? = runCatching {
        val fades = callObject(analysis, "getFades") ?: return null
        val fade = callObject(fades, fadeMethod) ?: return null
        val value = callObject(fade, valueMethod) as? Number ?: return null
        value.toFloat()
    }.getOrNull()

    private fun parse(analysis: Any): SongAudioAnalysis? = runCatching {
        // BPM 继承自 MainBegEnding，有 main/beginning/ending 字段（Double）
        val bpmObj = callObject(analysis, "getBPM")
        val bpm = bpmObj?.let { getDoubleField(it, "main") }?.toFloat()
        // Key 有 main/beginning/ending 字段（BaseKey），BaseKey 有 tonic + mode（String）
        val keyObj = callObject(analysis, "getKey")
        val mainKey = keyObj?.let { getObjectField(it, "main") }
        val tonic = mainKey?.let { getStringField(it, "tonic") }
        val mode = mainKey?.let { getStringField(it, "mode") }
        val key = if (tonic != null && mode != null) "$tonic $mode" else null
        // Energy/Danceability 是 MainBegEnding，取 main 值
        val energy = callObject(analysis, "getEnergy")?.let { getDoubleField(it, "main") }?.toFloat()
        val danceability = callObject(analysis, "getDanceability")?.let { getDoubleField(it, "main") }?.toFloat()
        FileLogger.log("AudioAnalysis: parsed bpm=$bpm key=$key energy=$energy dance=$danceability")
        SongAudioAnalysis(
            bpm = bpm,
            key = key,
            energy = energy,
            danceability = danceability,
            acousticness = callObject(analysis, "getAcousticness")?.let { getDoubleField(it, "main") }?.toFloat(),
            outroFadeStartMs = readFadeMs(analysis, "getFadeOut", "getStartInMilliseconds"),
            outroFadeEndMs = readFadeMs(analysis, "getFadeOut", "getEndInMilliseconds"),
            introFadeStartMs = readFadeMs(analysis, "getFadeIn", "getStartInMilliseconds"),
            introFadeEndMs = readFadeMs(analysis, "getFadeIn", "getEndInMilliseconds"),
            loudnessCurve = readLoudnessCurve(analysis),
            barTimesSec = readBeatArray(analysis, "getBarsInMilliseconds"),
            beatTimesSec = readBeatArray(analysis, "getBeatsInMilliseconds"),
        )
    }.getOrElse {
        FileLogger.log("AudioAnalysis: parse error: ${it.message}")
        null
    }

    /** 遍历父类链查找 private 字段（BPM 继承自 MainBegEnding，main 字段在父类里）。 */
    private fun findField(obj: Any, name: String): java.lang.reflect.Field? {
        var cls: Class<*>? = obj.javaClass
        while (cls != null) {
            runCatching { cls.getDeclaredField(name) }?.getOrNull()?.let { return it }
            cls = cls.superclass
        }
        return null
    }

    private fun getDoubleField(obj: Any, name: String): Double? = runCatching {
        val f = findField(obj, name) ?: return null
        f.isAccessible = true
        (f.get(obj) as? Double) ?: (f.get(obj) as? Number)?.toDouble()
    }.getOrNull()

    private fun getStringField(obj: Any, name: String): String? = runCatching {
        val f = findField(obj, name) ?: return null
        f.isAccessible = true
        f.get(obj) as? String
    }.getOrNull()

    private fun getObjectField(obj: Any, name: String): Any? = runCatching {
        val f = findField(obj, name) ?: return null
        f.isAccessible = true
        f.get(obj)
    }.getOrNull()

    private fun callFloat(obj: Any, name: String): Float? = runCatching {
        obj.javaClass.getMethod(name).invoke(obj) as? Float
    }.getOrNull()

    private fun callObject(obj: Any, name: String): Any? = runCatching {
        obj.javaClass.getMethod(name).invoke(obj)
    }.getOrNull()
}

/** 简化后的歌曲音频分析数据，供过渡规划使用。 */
internal data class SongAudioAnalysis(
    val bpm: Float?,
    val key: String?,
    val energy: Float?,
    val danceability: Float?,
    val acousticness: Float?,
    /** 节拍时间点（秒），从 onset 峰值检测得到，用于精确乐句对齐。 */
    val beats: FloatArray? = null,
    /** 开头静音时长（秒），低于 -45dB 的连续段。 */
    val startSilenceSec: Float = 0f,
    /** 结尾静音时长（秒）。 */
    val endSilenceSec: Float = 0f,
    /** Intro 结束位置（秒），能量首次显著上升的点，用于跳过前奏。 */
    val introEndSec: Float = 0f,
    /** Apple 原生分析给出的尾部淡出起止（毫秒），就是 AutoMix 的接歌点。 */
    val outroFadeStartMs: Float? = null,
    val outroFadeEndMs: Float? = null,
    /** Apple 原生分析给出的头部淡入起止（毫秒）。 */
    val introFadeStartMs: Float? = null,
    val introFadeEndMs: Float? = null,
    /** Apple 的响度曲线（按时间均匀采样），用于找 outro/能量下降点。 */
    val loudnessCurve: FloatArray? = null,
    /** Apple 小节线时间（秒），用于把切歌点对齐到小节。 */
    val barTimesSec: FloatArray? = null,
    /** Apple 节拍时间（秒）。 */
    val beatTimesSec: FloatArray? = null,
) {
    /** 是否有足够数据做智能过渡（BPM 或调性任一可用即可）。 */
    val usable: Boolean get() = (bpm != null && bpm > 0f) || !key.isNullOrBlank()

    /** 是否有 BPM 数据（决定能否变速匹配）。 */
    val hasBpm: Boolean get() = bpm != null && bpm > 0f
}


