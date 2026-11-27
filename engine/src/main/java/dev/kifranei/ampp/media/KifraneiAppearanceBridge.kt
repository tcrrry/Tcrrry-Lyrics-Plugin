package dev.kifranei.ampp.media

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.tcrrry.lyrics.plugin.PluginStorage.pluginPreferences
import dev.amenhancer.plugin.api.PluginContext
import dev.amenhancer.plugin.api.PluginRegistration
import java.util.EnumMap

/**
 * Coordinates the ported Kifranei v1.3.9 features (Liquid Glass menus/dialogs & popup styles,
 * player handle & system corners, volume bar, iOS audio output controls, song/lyrics card sharing,
 * cover/motion/TTML content downloads, and opt-in Auto-Transition) with per-capability
 * coexistence detection so only actually conflicting slots yield to an active standalone
 * `dev.kifranei.ampp.media` plugin.
 */
internal object KifraneiAppearanceBridge {
    private const val PREFS_NAME = "kifranei_appearance_bridge"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PLAYER_GLASS = "player_glass"
    private const val KEY_POPUP_GLASS_STYLE = "popup_glass_style"
    private const val KEY_PLAYER_HANDLE = "player_handle"
    private const val KEY_SYSTEM_CORNERS = "system_corners"
    private const val KEY_VOLUME_BAR = "volume_bar"
    private const val KEY_IOS_CONTROLS = "ios_controls"
    private const val KEY_SHARING = "sharing"
    private const val KEY_CONTENT_DOWNLOADS = "content_downloads"
    private const val KEY_AUTO_TRANSITION = "auto_transition"
    private const val KEY_AUTO_TRANSITION_DURATION = "auto_transition_duration"
    private const val KEY_AUTO_TRANSITION_SPEED_MATCH = "auto_transition_speed_match"
    private const val KEY_AUTO_TRANSITION_ANIMATE = "auto_transition_animate"
    private const val KEY_AUTO_TRANSITION_SKIP_INTRO = "auto_transition_skip_intro"
    private const val KEY_AUTO_TRANSITION_EQUALIZER = "auto_transition_equalizer"
    private const val KEY_AUTO_TRANSITION_PREFER_APPLE_CURVE = "auto_transition_prefer_apple_curve"
    private const val KEY_AUTO_TRANSITION_ALLOW_ALBUM_SEQ = "auto_transition_allow_album_seq"
    private const val KEY_AUTO_TRANSITION_ALLOW_SINGLE_REPEAT = "auto_transition_allow_single_repeat"
    private const val KEY_AUTO_TRANSITION_ALLOW_SEEK_TRIGGER = "auto_transition_allow_seek_trigger"
    private const val KEY_AUTO_TRANSITION_SHOW_QUEUE_BUTTON = "auto_transition_show_queue_button"

    @Volatile
    private var activeAutoTransition: AutoTransitionIntegration? = null

    private enum class SlotKey {
        ARTWORK_RECOVERY,
        BACKGROUND_RECOVERY,
        CONTENT_DOWNLOADS,
        SHARING,
        PLAYER_GLASS_FULL,
        PLAYLIST_GLASS_ONLY,
        PLAYER_CHROME,
        VOLUME_BAR,
        IOS_CONTROLS,
        AUTO_TRANSITION,
    }

    private class CapabilitySlot(
        val signature: Any,
        val claims: List<PluginRegistration>,
        val scopes: List<PluginScope>,
        val onClose: (() -> Unit)? = null,
    ) : AutoCloseable {
        override fun close() {
            onClose?.let { runCatching(it) }
            scopes.asReversed().forEach { runCatching { it.close() } }
            claims.asReversed().forEach { runCatching { it.close() } }
        }
    }

    private var runtime: PluginContext? = null
    private var profileLoaded = false
    private var started = false
    @Volatile private var activeDownloads: ContentDownloadsIntegration? = null
    @Volatile private var lastConflictSnapshot: ConflictSnapshot =
        ConflictSnapshot(standaloneEnabled = false, yielded = emptySet(), standalonePopupStyle = null)
    private val activeSlots = EnumMap<SlotKey, CapabilitySlot>(SlotKey::class.java)

    fun config(context: Context): KifraneiPortableConfig {
        val prefs = context.pluginPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return KifraneiPortableConfig(
            masterEnabled = prefs.getBoolean(KEY_ENABLED, false),
            playerGlass = prefs.getBoolean(KEY_PLAYER_GLASS, true),
            popupGlassStyle = PopupGlassStyle.fromKey(prefs.getString(KEY_POPUP_GLASS_STYLE, null)),
            playerHandle = prefs.getBoolean(KEY_PLAYER_HANDLE, true),
            systemCorners = prefs.getBoolean(KEY_SYSTEM_CORNERS, true),
            volumeBar = prefs.getBoolean(KEY_VOLUME_BAR, true),
            iosControls = prefs.getBoolean(KEY_IOS_CONTROLS, true),
            sharing = prefs.getBoolean(KEY_SHARING, true),
            contentDownloads = prefs.getBoolean(KEY_CONTENT_DOWNLOADS, true),
            autoTransition = prefs.getBoolean(KEY_AUTO_TRANSITION, false),
            autoTransitionDuration = prefs.getInt(KEY_AUTO_TRANSITION_DURATION, 0).let {
                if (it <= 0) 0 else it.coerceIn(2, 20)
            },
            autoTransitionSpeedMatch = prefs.getBoolean(KEY_AUTO_TRANSITION_SPEED_MATCH, true),
            autoTransitionAnimate = prefs.getBoolean(KEY_AUTO_TRANSITION_ANIMATE, true),
            autoTransitionSkipIntro = prefs.getBoolean(KEY_AUTO_TRANSITION_SKIP_INTRO, false),
            autoTransitionEqualizer = prefs.getBoolean(KEY_AUTO_TRANSITION_EQUALIZER, false),
            autoTransitionPreferAppleCurve = prefs.getBoolean(KEY_AUTO_TRANSITION_PREFER_APPLE_CURVE, false),
            autoTransitionAllowAlbumSequential = prefs.getBoolean(KEY_AUTO_TRANSITION_ALLOW_ALBUM_SEQ, true),
            autoTransitionAllowSingleRepeat = prefs.getBoolean(KEY_AUTO_TRANSITION_ALLOW_SINGLE_REPEAT, false),
            autoTransitionAllowSeekTrigger = prefs.getBoolean(KEY_AUTO_TRANSITION_ALLOW_SEEK_TRIGGER, true),
            autoTransitionShowQueueButton = prefs.getBoolean(KEY_AUTO_TRANSITION_SHOW_QUEUE_BUTTON, true),
        ).clamped()
    }

    fun enabled(context: Context): Boolean = config(context).masterEnabled

    fun setEnabled(context: Context, enable: Boolean) {
        updateConfig(context) { copy(masterEnabled = enable) }
    }

    @Synchronized fun updateConfig(
        context: Context,
        transform: KifraneiPortableConfig.() -> KifraneiPortableConfig,
    ): KifraneiPortableConfig {
        val previous = config(context)
        val updated = previous.transform().clamped()
        context.pluginPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, updated.masterEnabled)
            .putBoolean(KEY_PLAYER_GLASS, updated.playerGlass)
            .putString(KEY_POPUP_GLASS_STYLE, updated.popupGlassStyle.key)
            .putBoolean(KEY_PLAYER_HANDLE, updated.playerHandle)
            .putBoolean(KEY_SYSTEM_CORNERS, updated.systemCorners)
            .putBoolean(KEY_VOLUME_BAR, updated.volumeBar)
            .putBoolean(KEY_IOS_CONTROLS, updated.iosControls)
            .putBoolean(KEY_SHARING, updated.sharing)
            .putBoolean(KEY_CONTENT_DOWNLOADS, updated.contentDownloads)
            .putBoolean(KEY_AUTO_TRANSITION, updated.autoTransition)
            .putInt(KEY_AUTO_TRANSITION_DURATION, updated.autoTransitionDuration)
            .putBoolean(KEY_AUTO_TRANSITION_SPEED_MATCH, updated.autoTransitionSpeedMatch)
            .putBoolean(KEY_AUTO_TRANSITION_ANIMATE, updated.autoTransitionAnimate)
            .putBoolean(KEY_AUTO_TRANSITION_SKIP_INTRO, updated.autoTransitionSkipIntro)
            .putBoolean(KEY_AUTO_TRANSITION_EQUALIZER, updated.autoTransitionEqualizer)
            .putBoolean(KEY_AUTO_TRANSITION_PREFER_APPLE_CURVE, updated.autoTransitionPreferAppleCurve)
            .putBoolean(KEY_AUTO_TRANSITION_ALLOW_ALBUM_SEQ, updated.autoTransitionAllowAlbumSequential)
            .putBoolean(KEY_AUTO_TRANSITION_ALLOW_SINGLE_REPEAT, updated.autoTransitionAllowSingleRepeat)
            .putBoolean(KEY_AUTO_TRANSITION_ALLOW_SEEK_TRIGGER, updated.autoTransitionAllowSeekTrigger)
            .putBoolean(KEY_AUTO_TRANSITION_SHOW_QUEUE_BUTTON, updated.autoTransitionShowQueueButton)
            .apply()
        val liveAuto = activeAutoTransition
        if (liveAuto != null) {
            if (updated.autoTransition != previous.autoTransition) {
                liveAuto.syncExternalEnabled(updated.autoTransition)
            }
            if (updated.autoTransitionShowQueueButton != previous.autoTransitionShowQueueButton) {
                liveAuto.syncQueueButtonVisible(updated.autoTransitionShowQueueButton)
            }
        }
        val mainAction = Runnable { reconcile() }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            mainAction.run()
        } else {
            Handler(Looper.getMainLooper()).post(mainAction)
        }
        return updated
    }

    fun conflictSnapshot(context: Context): ConflictSnapshot {
        val dataDir = runCatching { runtime?.dataDirectory }.getOrNull()
        val snapshot = if (dataDir != null) {
            KifraneiConflictDetector.detect(dataDir)
        } else {
            lastConflictSnapshot
        }
        lastConflictSnapshot = snapshot
        return snapshot
    }

    fun menuSummary(context: Context): String {
        val cfg = config(context)
        val snap = conflictSnapshot(context)
        if (!cfg.masterEnabled) {
            return if (snap.standaloneEnabled) {
                "已关闭 · 已检测到独立版 K 插件"
            } else {
                "已关闭 · 液态玻璃、分享与资源下载"
            }
        }
        val activeTags = buildList {
            if (cfg.playerGlass) add(if (snap.isYielded(KifraneiCapability.PLAYER_GLASS)) "歌单玻璃(协同)" else "液态玻璃")
            if (cfg.sharing && !snap.isYielded(KifraneiCapability.SHARING)) add("分享解限")
            if (cfg.contentDownloads && !snap.isYielded(KifraneiCapability.CONTENT_DOWNLOADS)) add("资源下载")
        }
        val core = if (activeTags.isEmpty()) "已启用" else activeTags.joinToString(" · ")
        return if (snap.standaloneEnabled) "$core · 已避让 ${snap.yielded.size} 项" else core
    }

    fun autoTransitionMenuSummary(context: Context): String {
        val cfg = config(context)
        val snap = conflictSnapshot(context)
        return when {
            snap.isYielded(KifraneiCapability.AUTO_TRANSITION) -> "独立版 K 插件已开启自动过渡，已自动让位"
            cfg.autoTransition -> "已开启 · ${if (cfg.autoTransitionDuration <= 0) "自动时长" else "${cfg.autoTransitionDuration}秒"}"
            else -> "已关闭 · 智能混音与无缝过渡"
        }
    }

    @Synchronized fun attach(context: PluginContext) {
        runtime = context
        MediaRuntime.context = context
        val cfg = config(context.application)
        PopupGlassStyle.current = cfg.popupGlassStyle
        val hostBuild = "${context.hostVersionName}/${context.hostVersionCode}"
        profileLoaded = runCatching {
            PluginProfiles.load(context)
            val dataDir = runCatching { context.dataDirectory }.getOrNull()
            MediaDiag.start(dataDir, hostBuild, PluginProfiles.selectedAsset ?: "?", "1.3.9-ported")
            lastConflictSnapshot = KifraneiConflictDetector.detect(dataDir)
            if (lastConflictSnapshot.standaloneEnabled && lastConflictSnapshot.isYielded(KifraneiCapability.PLAYER_GLASS)) {
                lastConflictSnapshot.standalonePopupStyle?.let { PopupGlassStyle.current = it }
            }
            true
        }.getOrElse { error ->
            profileLoaded = false
            context.log("Kifranei appearance profile unavailable for $hostBuild (lyrics still active)", error)
            false
        }
        runCatching { FileLogger.init(context.application) }
        context.onClose { close() }
    }

    @Synchronized fun start() {
        started = true
        reconcile()
    }

    fun isGlassActive(): Boolean = Build.VERSION.SDK_INT >= 33 && synchronized(this) {
        activeSlots.containsKey(SlotKey.PLAYER_GLASS_FULL) ||
            activeSlots.containsKey(SlotKey.PLAYLIST_GLASS_ONLY) ||
            lastConflictSnapshot.isYielded(KifraneiCapability.PLAYER_GLASS)
    }

    @Synchronized private fun reconcile() {
        val context = runtime ?: return
        val cfg = config(context.application)
        val snap = KifraneiConflictDetector.detect(runCatching { context.dataDirectory }.getOrNull())
        lastConflictSnapshot = snap

        PopupGlassStyle.current = if (snap.isYielded(KifraneiCapability.PLAYER_GLASS)) {
            snap.standalonePopupStyle ?: cfg.popupGlassStyle
        } else {
            cfg.popupGlassStyle
        }

        if (!profileLoaded) {
            context.log("Kifranei integration skipped: host build not supported", null)
            return
        }
        if (!started && Looper.myLooper() != Looper.getMainLooper()) return

        val app = context.application
        val loader = context.hostClassLoader
        val build = TargetBuild(context.hostPackageName, context.hostVersionName, context.hostVersionCode)
        val kEnabled = cfg.masterEnabled

        fun reconcileSlot(
            key: SlotKey,
            shouldRun: Boolean,
            signature: Any = true,
            onClose: (() -> Unit)? = null,
            installer: () -> List<PluginRegistration>,
        ) {
            val existing = activeSlots[key]
            if (!shouldRun) {
                if (existing != null) {
                    activeSlots.remove(key)
                    runCatching { existing.close() }
                }
                return
            }
            if (existing != null && existing.signature == signature) {
                return
            }
            if (existing != null) {
                activeSlots.remove(key)
                runCatching { existing.close() }
            }
            MediaDiag.capability = key.name.lowercase()
            try {
                runCatching {
                    val (claims, scopes) = MediaRuntime.collectScopes { installer() }
                    if (claims.isEmpty() && scopes.isEmpty()) {
                        onClose?.let { runCatching(it) }
                    } else {
                        activeSlots[key] = CapabilitySlot(signature, claims, scopes, onClose)
                    }
                }.onFailure { error ->
                    onClose?.let { runCatching(it) }
                    context.log("Kifranei[${key.name}] install failed (skipped)", error)
                }
            } finally {
                MediaDiag.capability = null
            }
        }

        fun installClaimed(claimKey: String, action: () -> TargetCapabilityInstall): List<PluginRegistration> {
            val reg = context.claimResource(claimKey, true)
            val result = try {
                action()
            } catch (error: Throwable) {
                reg.close()
                throw error
            }
            if (result is TargetCapabilityInstall.Unsupported) {
                reg.close()
                return emptyList()
            }
            context.log("Kifranei[$claimKey]: ${result.message}", null)
            return listOf(reg)
        }

        reconcileSlot(
            SlotKey.ARTWORK_RECOVERY,
            shouldRun = kEnabled && !snap.isYielded(KifraneiCapability.ARTWORK_RECOVERY),
        ) {
            installClaimed("shared:applemusic-player-artwork-measurement") {
                PlayerArtworkRecovery(build).install()
            }
        }

        reconcileSlot(
            SlotKey.BACKGROUND_RECOVERY,
            shouldRun = kEnabled && !snap.isYielded(KifraneiCapability.BACKGROUND_RECOVERY),
        ) {
            installClaimed("shared:applemusic-player-background-recovery") {
                PlayerBackgroundRecovery(build).install()
            }
        }

        var installedDownloads: ContentDownloadsIntegration? = null
        reconcileSlot(
            SlotKey.CONTENT_DOWNLOADS,
            shouldRun = kEnabled && cfg.contentDownloads && !snap.isYielded(KifraneiCapability.CONTENT_DOWNLOADS),
            onClose = {
                installedDownloads?.let { dl ->
                    if (activeDownloads === dl) activeDownloads = null
                    runCatching { dl.close() }
                }
            },
        ) {
            val dl = ContentDownloadsIntegration(app, loader, build)
            installedDownloads = dl
            val claims = ArrayList<PluginRegistration>()
            val dlReg = context.claimResource("shared:applemusic-content-downloads", true)
            val dlResult = try {
                dl.install()
            } catch (error: Throwable) {
                dlReg.close()
                throw error
            }
            if (dlResult is TargetCapabilityInstall.Unsupported) {
                dlReg.close()
                context.log("Kifranei[content-downloads]: ${dlResult.message}", null)
                return@reconcileSlot emptyList()
            }
            claims += dlReg
            activeDownloads = dl
            context.log("Kifranei[content-downloads]: ${dlResult.message}", null)
            runCatching {
                val ttmlReg = context.claimResource("shared:applemusic-player-ttml-download", true)
                val ttmlResult = try {
                    PlayerContentDownloadsIntegration(build, dl).install()
                } catch (error: Throwable) {
                    ttmlReg.close()
                    throw error
                }
                if (ttmlResult is TargetCapabilityInstall.Unsupported) {
                    ttmlReg.close()
                } else {
                    claims += ttmlReg
                }
                context.log("Kifranei[player-ttml-download]: ${ttmlResult.message}", null)
            }.onFailure { error ->
                context.log("Kifranei[player-ttml-download] install failed (skipped)", error)
            }
            claims
        }

        reconcileSlot(
            SlotKey.SHARING,
            shouldRun = kEnabled && cfg.sharing && !snap.isYielded(KifraneiCapability.SHARING),
        ) {
            val claims = ArrayList<PluginRegistration>()
            claims += installClaimed("shared:applemusic-lyric-share-image") {
                CardSharingFeature().install(CardSharingIntegration(app, loader, build))
            }
            runCatching {
                claims += installClaimed("shared:applemusic-share-card") {
                    LyricsMoreShareIntegration(build, loader).install()
                }
            }.onFailure { error ->
                context.log("Kifranei[share-card] install failed (skipped)", error)
            }
            claims
        }

        val fullGlassWanted = kEnabled && cfg.playerGlass && !snap.isYielded(KifraneiCapability.PLAYER_GLASS)
        val playlistGlassOnlyWanted = kEnabled && cfg.playerGlass && snap.isYielded(KifraneiCapability.PLAYER_GLASS)

        reconcileSlot(
            SlotKey.PLAYER_GLASS_FULL,
            shouldRun = fullGlassWanted,
        ) {
            val claims = ArrayList<PluginRegistration>()
            fun addSafe(claimKey: String, action: () -> TargetCapabilityInstall) {
                runCatching { claims += installClaimed(claimKey, action) }
                    .onFailure { error -> context.log("Kifranei[$claimKey] install failed (skipped)", error) }
            }
            addSafe("shared:applemusic-quality-disclosure") {
                AudioQualityGlassFeature().install(AudioQualityIntegration(app, build))
            }
            addSafe("shared:applemusic-player-more-menu") {
                PlayerMoreMenuIntegration(build, { activeDownloads }, PlayerMoreMenuIntegration.Mode.BOTH).install()
            }
            addSafe("shared:applemusic-delete-confirmation") {
                PlayerDialogsIntegration(build, confirmation = true, sleep = false).install()
            }
            addSafe("shared:applemusic-sleep-timer") {
                PlayerDialogsIntegration(build, confirmation = false, sleep = true, playlistSort = true).install()
            }
            claims
        }

        // Coexistence path: standalone Kifranei handles player popups/more-menu; keep playlist ActionSheets & sort
        // in Liquid Glass without claiming any exclusive `shared:applemusic-*` keys.
        reconcileSlot(
            SlotKey.PLAYLIST_GLASS_ONLY,
            shouldRun = playlistGlassOnlyWanted,
        ) {
            runCatching {
                val menuRes = PlayerMoreMenuIntegration(
                    build,
                    { activeDownloads },
                    PlayerMoreMenuIntegration.Mode.PLAYLIST_ONLY,
                ).install()
                context.log("Kifranei[playlist-glass-menu]: ${menuRes.message}", null)
            }.onFailure { error ->
                context.log("Kifranei[playlist-glass-menu] failed", error)
            }
            runCatching {
                val sortRes = PlayerDialogsIntegration(
                    build,
                    confirmation = false,
                    sleep = false,
                    playlistSort = true,
                ).install()
                context.log("Kifranei[playlist-glass-sort]: ${sortRes.message}", null)
            }.onFailure { error ->
                context.log("Kifranei[playlist-glass-sort] failed", error)
            }
            emptyList()
        }

        val chromeWanted = kEnabled && (cfg.playerHandle || cfg.systemCorners) && !snap.isYielded(KifraneiCapability.PLAYER_CHROME)
        reconcileSlot(
            SlotKey.PLAYER_CHROME,
            shouldRun = chromeWanted,
            signature = cfg.playerHandle to cfg.systemCorners,
        ) {
            installClaimed("shared:applemusic-player-page-chrome") {
                PlayerPageChromeIntegration(build, showHandle = cfg.playerHandle, systemCorners = cfg.systemCorners).install()
            }
        }

        reconcileSlot(
            SlotKey.VOLUME_BAR,
            shouldRun = kEnabled && cfg.volumeBar && !OplusRomDetector.current && !snap.isYielded(KifraneiCapability.VOLUME_BAR),
        ) {
            installClaimed("shared:applemusic-player-volume") {
                PlayerVolumeIntegration(build).install()
            }
        }

        reconcileSlot(
            SlotKey.IOS_CONTROLS,
            shouldRun = kEnabled && cfg.iosControls && !snap.isYielded(KifraneiCapability.IOS_CONTROLS),
        ) {
            installClaimed("shared:applemusic-player-output-button") {
                IosMediaControls(app, loader, build).install()
            }
        }

        var installedAuto: AutoTransitionIntegration? = null
        reconcileSlot(
            SlotKey.AUTO_TRANSITION,
            shouldRun = (cfg.autoTransition || activeSlots.containsKey(SlotKey.AUTO_TRANSITION)) &&
                !snap.isYielded(KifraneiCapability.AUTO_TRANSITION),
            onClose = {
                if (activeAutoTransition === installedAuto) {
                    activeAutoTransition = null
                }
            },
        ) {
            installClaimed("shared:applemusic-auto-transition") {
                val analysisCacheFile = runCatching { java.io.File(context.dataDirectory, "automix_analysis_cache.json") }.getOrNull()
                analysisCacheFileRef = analysisCacheFile
                val integration = AutoTransitionIntegration(
                    build = build,
                    loader = loader,
                    enabled = { config(app).autoTransition },
                    defaultDurationSec = { config(app).autoTransitionDuration },
                    speedMatch = { false },
                    animate = { config(app).autoTransitionAnimate },
                    skipIntro = { false },
                    useEqualizer = { false },
                    preferAppleCurve = { config(app).autoTransitionPreferAppleCurve },
                    allowAlbumSequential = { config(app).autoTransitionAllowAlbumSequential },
                    allowSingleRepeat = { config(app).autoTransitionAllowSingleRepeat },
                    allowSeekTrigger = { config(app).autoTransitionAllowSeekTrigger },
                    showQueueButton = { config(app).autoTransitionShowQueueButton },
                    analysisCacheFile = analysisCacheFile,
                    onRuntimeToggle = { newState -> persistAutoTransitionFromUi(app, newState) },
                )
                installedAuto = integration
                activeAutoTransition = integration
                integration.install()
            }
        }
    }

    private fun persistAutoTransitionFromUi(context: Context, enabled: Boolean) {
        context.pluginPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_TRANSITION, enabled)
            .apply()
    }

    @Volatile
    private var analysisCacheFileRef: java.io.File? = null

    fun analysisCacheStats(context: Context): Pair<Int, Long> {
        val active = activeAutoTransition
        if (active != null) {
            return active.analysisCacheStats()
        }
        val file = analysisCacheFileRef ?: resolveFallbackCacheFile(context)
        return AudioAnalysisProvider.readCacheStats(file)
    }

    fun clearAnalysisCache(context: Context): Boolean {
        val active = activeAutoTransition
        val clearedInMemory = active?.clearAnalysisCache() ?: false
        val file = analysisCacheFileRef ?: resolveFallbackCacheFile(context)
        val deleted = AudioAnalysisProvider.deletePersistedCache(file)
        return clearedInMemory || deleted
    }

    private fun resolveFallbackCacheFile(context: Context): java.io.File {
        val host = runtime
        if (host != null) {
            return java.io.File(host.dataDirectory, "automix_analysis_cache.json")
        }
        return java.io.File(context.filesDir, "automix_analysis_cache.json")
    }

    @Synchronized private fun closeAllSlots() {
        val entries = activeSlots.values.toList()
        activeSlots.clear()
        entries.asReversed().forEach { runCatching { it.close() } }
        activeDownloads = null
        activeAutoTransition = null
        MediaRuntime.closeTrackedScopes()
    }

    @Synchronized fun close() {
        started = false
        closeAllSlots()
        profileLoaded = false
        runtime = null
    }
}
