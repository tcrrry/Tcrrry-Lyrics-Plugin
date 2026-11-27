package com.tcrrry.lyrics.plugin

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.widget.Toast
import com.tcrrry.lyrics.engine.ui.TcrrryLyricsSettingsUi
import com.tcrrry.lyrics.plugin.PluginStorage.pluginPreferences
import com.tcrrry.playlisttools.CoverStore
import com.tcrrry.playlisttools.HardenedSleepTimer
import com.tcrrry.playlisttools.HostContract
import com.tcrrry.playlisttools.LibraryPlaylistReader
import com.tcrrry.playlisttools.PlaylistActionSheetMenu
import com.tcrrry.playlisttools.PlaylistCoverFixer
import com.tcrrry.playlisttools.PlaylistExporter
import com.tcrrry.playlisttools.PlaylistModels
import dev.amenhancer.plugin.api.PluginCall
import dev.amenhancer.plugin.api.PluginContext
import dev.amenhancer.plugin.api.PluginHook
import dev.amenhancer.plugin.api.PluginRegistration
import dev.kifranei.ampp.media.ConflictSnapshot
import dev.kifranei.ampp.media.GlassCustomSleepTimerDialog
import dev.kifranei.ampp.media.KifraneiAppearanceBridge
import dev.kifranei.ampp.media.KifraneiPortableConfig
import dev.kifranei.ampp.media.OplusRomDetector
import dev.kifranei.ampp.media.PluginProfiles
import java.lang.ref.WeakReference
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Owns the integrated playlist tools, hardened sleep timer, cover fix, and Kifranei appearance toggle. */
internal object MoreToolsCoordinator {
    private const val PREFS_EXPORT = "more_tools_playlist_export"
    private const val PREFS_COVER = "more_tools_cover_fix"
    private const val PREFS_SLEEP = "more_tools_sleep_timer"
    private const val KEY_EXPORT_ENABLED = "export_enabled"
    private const val KEY_EXPORT_FORMAT = "export_format"
    private const val KEY_EXPORT_MERGED = "export_merged"
    private const val KEY_OVERWRITE_EXISTING = "export_overwrite_existing"
    private const val KEY_CUSTOM_TREE_URI = "export_custom_tree_uri"
    private const val KEY_CUSTOM_RELATIVE_DIR = "export_custom_relative_dir"
    private const val KEY_LAST_URI_PREFIX = "last_uri_"

    private const val REQ_PICK_SAF_TREE = 0x5452
    private const val REQ_PICK_OEM_DIR = 0x5453
    private const val ACTION_OEM_DIR_PICKER = "com.android.filemanager.DOWNLOAD_LOCATION"
    private const val ACTION_OEM_FILE_OPEN = "com.android.filemanager.FILE_OPEN"

    private var runtime: PluginContext? = null
    private var hostContract: HostContract? = null
    private var coverStore: CoverStore? = null
    private var reader: LibraryPlaylistReader? = null
    private var coverFixer: PlaylistCoverFixer? = null
    private var actionSheetMenu: PlaylistActionSheetMenu? = null
    private var sleepTimer: HardenedSleepTimer? = null
    private var toolsClaim: PluginRegistration? = null
    private var activityResultHook: PluginRegistration? = null
    private var executor: ExecutorService? = null
    private var host1626Supported = false

    @Volatile private var pendingLocationCallback: ((String?) -> Unit)? = null
    @Volatile private var pendingPickerActivity: WeakReference<Activity>? = null

    @Synchronized fun attach(context: PluginContext) {
        runtime = context
        KifraneiAppearanceBridge.attach(context)
        UsbBitPerfectAudio.attach(context)
        LongPressPlaybackSpeed.attach(context)
        installActivityResultHook(context)

        host1626Supported = context.hostPackageName in setOf("com.apple.android.music", "com.tcrrry.ampplyrics.coexist") &&
            context.hostVersionCode == 1626L && context.hostVersionName == "7.0.3"
        if (!host1626Supported) {
            context.log("Playlist/sleep tools skipped for host ${context.hostVersionName} (${context.hostVersionCode})", null)
            context.onClose { close() }
            return
        }

        val worker = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "tcrrry-more-tools").apply { isDaemon = true }
        }
        executor = worker

        runCatching {
            val contract = HostContract(context.hostClassLoader)
            hostContract = contract

            val coverPrefs = context.application.pluginPreferences(PREFS_COVER, Context.MODE_PRIVATE)
            val store = CoverStore(coverPrefs, context.dataDirectory, context.cacheDirectory)
            coverStore = store

            val libraryReader = LibraryPlaylistReader(contract)
            reader = libraryReader

            val fixer = PlaylistCoverFixer(context, contract, store)
            coverFixer = fixer

            val sheetMenu = PlaylistActionSheetMenu(
                context,
                contract,
                libraryReader,
                worker,
                { playlistExportEnabled(context.application) },
                { playlistExportFormat(context.application) },
                { key -> buildExportOptions(context.application, key) },
                { key, fileUri -> recordExportedUri(context.application, key, fileUri) },
            )
            actionSheetMenu = sheetMenu

            val sleepPrefs = context.application.pluginPreferences(PREFS_SLEEP, Context.MODE_PRIVATE)
            val timer = HardenedSleepTimer(context, contract, sleepPrefs)
            timer.setCustomDurationDialogOpener { activity, initialMinutes, activeRemainingMs, onConfirmed ->
                val showFallback = {
                    TcrrryLyricsSettingsUi.showCustomSleepTimerDialog(activity, initialMinutes, activeRemainingMs) { mins ->
                        onConfirmed.accept(mins)
                        Toast.makeText(activity, "睡眠定时已设为 $mins 分钟（强力停止已开启）", Toast.LENGTH_SHORT).show()
                    }
                }
                if (KifraneiAppearanceBridge.isGlassActive()) {
                    GlassCustomSleepTimerDialog.show(
                        activity = activity,
                        initialMinutes = initialMinutes,
                        activeRemainingMs = activeRemainingMs,
                        onConfirm = { mins -> onConfirmed.accept(mins) },
                        onFallback = showFallback,
                    )
                } else {
                    showFallback()
                }
            }
            sleepTimer = timer

            toolsClaim = runCatching { context.claimResource("com.tcrrry.playlisttools.core", true) }.getOrNull()
            fixer.install()
            sheetMenu.install()
            timer.install()
        }.onFailure { error ->
            context.log("MoreToolsCoordinator install failed (lyrics still active)", error)
        }
        context.onClose { close() }
    }

    @Synchronized fun start() {
        KifraneiAppearanceBridge.start()
    }

    @Synchronized fun close() {
        runCatching { sleepTimer?.stop() }
        runCatching { actionSheetMenu?.stop() }
        runCatching { coverFixer?.stop() }
        runCatching { toolsClaim?.close() }
        runCatching { activityResultHook?.close() }
        UsbBitPerfectAudio.close()
        KifraneiAppearanceBridge.close()
        executor?.shutdownNow()

        sleepTimer = null
        actionSheetMenu = null
        coverFixer = null
        reader = null
        coverStore = null
        hostContract = null
        toolsClaim = null
        activityResultHook = null
        executor = null
        runtime = null
        host1626Supported = false
        pendingLocationCallback = null
        pendingPickerActivity = null
    }

    private fun installActivityResultHook(context: PluginContext) {
        runCatching {
            val method = Activity::class.java.getDeclaredMethod(
                "onActivityResult",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Intent::class.java,
            )
            activityResultHook = context.hooks.hook(method, false, object : PluginHook {
                override fun before(call: PluginCall) {
                    val args = call.arguments ?: return
                    if (args.size < 3) return
                    val requestCode = args[0] as? Int ?: return
                    if (requestCode != REQ_PICK_SAF_TREE && requestCode != REQ_PICK_OEM_DIR) return
                    val callback = pendingLocationCallback ?: return
                    val resultCode = args[1] as? Int ?: Activity.RESULT_CANCELED
                    val data = args[2] as? Intent
                    val activity = (call.receiver as? Activity) ?: pendingPickerActivity?.get()
                    call.returnResult(null)
                    handleDirectoryPickerResult(activity, requestCode, resultCode, data, callback)
                }
            })
        }.onFailure {
            context.log("Failed to hook Activity.onActivityResult for directory picker", it)
        }
    }

    private fun handleDirectoryPickerResult(
        activity: Activity?,
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
        callback: (String?) -> Unit,
    ) {
        pendingLocationCallback = null
        pendingPickerActivity = null
        if (resultCode != Activity.RESULT_OK || data == null || activity == null) {
            return
        }
        val app = activity.applicationContext
        if (requestCode == REQ_PICK_SAF_TREE) {
            val treeUri = data.data ?: return
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { activity.contentResolver.takePersistableUriPermission(treeUri, flags) }
            saveCustomTreeUri(app, treeUri)
            val label = exportLocationDisplayLabel(app)
            notifyMain { callback(label) }
            Toast.makeText(activity, "已设置导出位置：$label", Toast.LENGTH_SHORT).show()
            return
        }
        if (requestCode == REQ_PICK_OEM_DIR) {
            val uri = data.data
            if (uri != null && "content" == uri.scheme && runCatching { DocumentsContract.isTreeUri(uri) }.getOrDefault(false)) {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                runCatching { activity.contentResolver.takePersistableUriPermission(uri, flags) }
                saveCustomTreeUri(app, uri)
                val label = exportLocationDisplayLabel(app)
                notifyMain { callback(label) }
                Toast.makeText(activity, "已设置导出位置：$label", Toast.LENGTH_SHORT).show()
                return
            }
            val rawPath = extractFolderPathFromIntent(data)
            val normalizedRel = PlaylistExporter.normalizeMediaStoreRelativeDir(rawPath)
            if (normalizedRel != null) {
                saveCustomRelativeDir(app, normalizedRel)
                val label = exportLocationDisplayLabel(app)
                notifyMain { callback(label) }
                Toast.makeText(activity, "已设置导出位置：$label", Toast.LENGTH_SHORT).show()
            } else if (!rawPath.isNullOrBlank()) {
                Toast.makeText(activity, "系统需要目录写入授权，请点击「使用此文件夹」", Toast.LENGTH_LONG).show()
                launchSafDirectoryPicker(activity, rawPath, callback)
            } else {
                Toast.makeText(activity, "未识别到所选文件夹路径，请改用系统目录授权选择", Toast.LENGTH_SHORT).show()
                launchSafDirectoryPicker(activity, null, callback)
            }
        }
    }

    private fun extractFolderPathFromIntent(data: Intent): String? {
        val uri = data.data
        if (uri != null && "file" == uri.scheme) {
            val p = uri.path
            if (!p.isNullOrBlank()) return p
        }
        val extras = data.extras
        if (extras != null) {
            val preferredKeys = listOf("path", "file_path", "select_path", "download_path", "dir_path", "folder_path")
            for (k in preferredKeys) {
                val v = extras.getString(k)
                if (!v.isNullOrBlank() && (v.startsWith("/") || v.contains("Download", true) || v.contains("Documents", true))) {
                    return v
                }
            }
            for (k in extras.keySet()) {
                @Suppress("DEPRECATION")
                val raw = extras.get(k)
                if (raw is String && (raw.startsWith("/storage/") || raw.startsWith("/sdcard/"))) {
                    return raw
                }
            }
        }
        return uri?.path?.takeIf { it.startsWith("/storage/") || it.startsWith("/sdcard/") }
    }

    private fun notifyMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else Handler(Looper.getMainLooper()).post(block)
    }

    fun is1626Supported(): Boolean = host1626Supported && reader != null

    fun isKifraneiSupported(): Boolean {
        val ctx = runtime ?: return false
        return PluginProfiles.find(ctx.hostPackageName, ctx.hostVersionName, ctx.hostVersionCode) != null
    }

    fun sleepTimerEnabled(context: Context): Boolean {
        val timer = sleepTimer
        return timer?.isHardenedEnabled ?: context.pluginPreferences(PREFS_SLEEP, Context.MODE_PRIVATE)
            .getBoolean("hardened_enabled", true)
    }

    fun setSleepTimerEnabled(context: Context, enabled: Boolean) {
        val timer = sleepTimer
        if (timer != null) {
            timer.isHardenedEnabled = enabled
        } else {
            context.pluginPreferences(PREFS_SLEEP, Context.MODE_PRIVATE)
                .edit().putBoolean("hardened_enabled", enabled).apply()
        }
    }

    fun coverFixEnabled(context: Context): Boolean {
        val store = coverStore
        return store?.isCoverFixEnabled ?: context.pluginPreferences(PREFS_COVER, Context.MODE_PRIVATE)
            .getBoolean("cover_fix_master_enabled", true)
    }

    fun setCoverFixEnabled(context: Context, enabled: Boolean) {
        val store = coverStore
        if (store != null) {
            store.isCoverFixEnabled = enabled
        } else {
            context.pluginPreferences(PREFS_COVER, Context.MODE_PRIVATE)
                .edit().putBoolean("cover_fix_master_enabled", enabled).apply()
        }
    }

    fun playlistExportEnabled(context: Context): Boolean =
        context.pluginPreferences(PREFS_EXPORT, Context.MODE_PRIVATE).getBoolean(KEY_EXPORT_ENABLED, true)

    fun setPlaylistExportEnabled(context: Context, enabled: Boolean) {
        context.pluginPreferences(PREFS_EXPORT, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_EXPORT_ENABLED, enabled).apply()
    }

    fun exportOverwriteEnabled(context: Context): Boolean =
        context.pluginPreferences(PREFS_EXPORT, Context.MODE_PRIVATE).getBoolean(KEY_OVERWRITE_EXISTING, false)

    fun setExportOverwriteEnabled(context: Context, enabled: Boolean) {
        context.pluginPreferences(PREFS_EXPORT, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_OVERWRITE_EXISTING, enabled).apply()
    }

    fun playlistExportFormat(context: Context): PlaylistModels.ExportFormat {
        val raw = context.pluginPreferences(PREFS_EXPORT, Context.MODE_PRIVATE)
            .getString(KEY_EXPORT_FORMAT, PlaylistModels.ExportFormat.CSV.name)
        return runCatching { PlaylistModels.ExportFormat.valueOf(raw ?: "CSV") }
            .getOrDefault(PlaylistModels.ExportFormat.CSV)
    }

    fun setPlaylistExportFormat(context: Context, format: PlaylistModels.ExportFormat) {
        context.pluginPreferences(PREFS_EXPORT, Context.MODE_PRIVATE)
            .edit().putString(KEY_EXPORT_FORMAT, format.name).apply()
    }

    fun playlistExportMerged(context: Context): Boolean =
        context.pluginPreferences(PREFS_EXPORT, Context.MODE_PRIVATE).getBoolean(KEY_EXPORT_MERGED, false)

    fun setPlaylistExportMerged(context: Context, merged: Boolean) {
        context.pluginPreferences(PREFS_EXPORT, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_EXPORT_MERGED, merged).apply()
    }

    fun hasCustomExportLocation(context: Context): Boolean {
        val prefs = context.pluginPreferences(PREFS_EXPORT, Context.MODE_PRIVATE)
        return !prefs.getString(KEY_CUSTOM_TREE_URI, null).isNullOrBlank() ||
            !prefs.getString(KEY_CUSTOM_RELATIVE_DIR, null).isNullOrBlank()
    }

    fun exportLocationDisplayLabel(context: Context): String {
        val prefs = context.pluginPreferences(PREFS_EXPORT, Context.MODE_PRIVATE)
        val treeStr = prefs.getString(KEY_CUSTOM_TREE_URI, null)?.takeIf { it.isNotBlank() }
        if (treeStr != null) {
            val docId = runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(treeStr)) }.getOrNull()
            return PlaylistExporter.formatTreeDocumentId(docId)
        }
        val relDir = prefs.getString(KEY_CUSTOM_RELATIVE_DIR, null)?.takeIf { it.isNotBlank() }
        if (relDir != null) {
            return "内部存储/$relDir"
        }
        return "Download/${PlaylistExporter.DOWNLOAD_SUBDIR}（默认）"
    }

    fun resetExportLocation(context: Context) {
        val prefs = context.pluginPreferences(PREFS_EXPORT, Context.MODE_PRIVATE)
        val oldTree = prefs.getString(KEY_CUSTOM_TREE_URI, null)?.takeIf { it.isNotBlank() }
        if (oldTree != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(oldTree), flags) }
        }
        prefs.edit()
            .remove(KEY_CUSTOM_TREE_URI)
            .remove(KEY_CUSTOM_RELATIVE_DIR)
            .apply()
    }

    private fun saveCustomTreeUri(context: Context, treeUri: Uri) {
        context.pluginPreferences(PREFS_EXPORT, Context.MODE_PRIVATE).edit()
            .putString(KEY_CUSTOM_TREE_URI, treeUri.toString())
            .remove(KEY_CUSTOM_RELATIVE_DIR)
            .apply()
    }

    private fun saveCustomRelativeDir(context: Context, relativeDir: String) {
        context.pluginPreferences(PREFS_EXPORT, Context.MODE_PRIVATE).edit()
            .putString(KEY_CUSTOM_RELATIVE_DIR, relativeDir)
            .remove(KEY_CUSTOM_TREE_URI)
            .apply()
    }

    fun hasOemFileManagerPicker(context: Context): Boolean =
        runCatching {
            context.packageManager.resolveActivity(Intent(ACTION_OEM_DIR_PICKER), 0) != null
        }.getOrDefault(false)

    fun launchOemDirectoryPicker(activity: Activity, onUpdated: (String?) -> Unit): Boolean {
        val intent = Intent(ACTION_OEM_DIR_PICKER)
        pendingPickerActivity = WeakReference(activity)
        pendingLocationCallback = onUpdated
        return runCatching {
            activity.startActivityForResult(intent, REQ_PICK_OEM_DIR)
            true
        }.onFailure {
            pendingPickerActivity = null
            pendingLocationCallback = null
        }.getOrDefault(false)
    }

    fun launchSafDirectoryPicker(activity: Activity, initialPath: String? = null, onUpdated: (String?) -> Unit): Boolean {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
            )
            val prefs = activity.pluginPreferences(PREFS_EXPORT, Context.MODE_PRIVATE)
            val existingTree = prefs.getString(KEY_CUSTOM_TREE_URI, null)?.takeIf { it.isNotBlank() }
            val relDir = prefs.getString(KEY_CUSTOM_RELATIVE_DIR, null)?.takeIf { it.isNotBlank() }
            val initUri = when {
                !initialPath.isNullOrBlank() -> DocumentsContract.buildDocumentUri(
                    "com.android.externalstorage.documents",
                    PlaylistExporter.buildInitialDocumentIdFromPath(initialPath),
                )
                existingTree != null -> Uri.parse(existingTree)
                relDir != null -> DocumentsContract.buildDocumentUri(
                    "com.android.externalstorage.documents",
                    PlaylistExporter.buildInitialDocumentIdFromPath(relDir),
                )
                else -> DocumentsContract.buildDocumentUri(
                    "com.android.externalstorage.documents",
                    PlaylistExporter.buildInitialDocumentIdFromPath(null),
                )
            }
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, initUri)
        }
        pendingPickerActivity = WeakReference(activity)
        pendingLocationCallback = onUpdated
        return runCatching {
            activity.startActivityForResult(intent, REQ_PICK_SAF_TREE)
            true
        }.onFailure {
            pendingPickerActivity = null
            pendingLocationCallback = null
        }.getOrDefault(false)
    }

    fun openExportLocationInFileManager(activity: Activity): Boolean {
        val prefs = activity.pluginPreferences(PREFS_EXPORT, Context.MODE_PRIVATE)
        val treeStr = prefs.getString(KEY_CUSTOM_TREE_URI, null)?.takeIf { it.isNotBlank() }
        val relDir = prefs.getString(KEY_CUSTOM_RELATIVE_DIR, null)?.takeIf { it.isNotBlank() }
            ?: "Download/${PlaylistExporter.DOWNLOAD_SUBDIR}"
        val folderDocId = if (treeStr != null) {
            runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(treeStr)) }.getOrNull()
        } else {
            PlaylistExporter.buildInitialDocumentIdFromPath(relDir)
        }

        // 1. Prefer OEM file manager view when present (e.g. vivo/iQOO FileManager)
        if (activity.packageManager.resolveActivity(Intent(ACTION_OEM_FILE_OPEN), 0) != null && treeStr == null) {
            val fsPath = "/storage/emulated/0/$relDir"
            val oemIntent = Intent(ACTION_OEM_FILE_OPEN).apply {
                putExtra("path", fsPath)
                putExtra("file_path", fsPath)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (runCatching { activity.startActivity(oemIntent); true }.getOrDefault(false)) {
                return true
            }
        }

        // 2. Standard document tree / directory view
        if (!folderDocId.isNullOrBlank()) {
            val viewDocUri = if (treeStr != null) {
                DocumentsContract.buildDocumentUriUsingTree(Uri.parse(treeStr), folderDocId)
            } else {
                DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", folderDocId)
            }
            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(viewDocUri, "vnd.android.document/directory")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (runCatching { activity.startActivity(viewIntent); true }.getOrDefault(false)) {
                return true
            }
        }

        // 3. Fallback to Downloads UI or OEM file manager launcher
        val downloadsIntent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (runCatching { activity.startActivity(downloadsIntent); true }.getOrDefault(false)) {
            return true
        }
        for (pkg in listOf("com.android.filemanager", "com.coloros.filemanager", "com.android.fileexplorer", "com.huawei.filemanager")) {
            val launch = activity.packageManager.getLaunchIntentForPackage(pkg) ?: continue
            if (runCatching { activity.startActivity(launch); true }.getOrDefault(false)) {
                return true
            }
        }
        return false
    }

    fun buildExportOptions(context: Context, playlistKey: String?): PlaylistExporter.ExportOptions {
        val prefs = context.pluginPreferences(PREFS_EXPORT, Context.MODE_PRIVATE)
        val treeStr = prefs.getString(KEY_CUSTOM_TREE_URI, null)?.takeIf { it.isNotBlank() }
        val treeUri = treeStr?.let { runCatching { Uri.parse(it) }.getOrNull() }
        val relDir = if (treeUri == null) prefs.getString(KEY_CUSTOM_RELATIVE_DIR, null)?.takeIf { it.isNotBlank() } else null
        val displayDir = if (treeUri != null) exportLocationDisplayLabel(context) else relDir
        val overwrite = prefs.getBoolean(KEY_OVERWRITE_EXISTING, false)
        val prevUriStr = if (overwrite && !playlistKey.isNullOrBlank()) {
            prefs.getString(KEY_LAST_URI_PREFIX + playlistKey, null)?.takeIf { it.isNotBlank() }
        } else null
        val prevUri = prevUriStr?.let { runCatching { Uri.parse(it) }.getOrNull() }
        return PlaylistExporter.ExportOptions(treeUri, relDir, displayDir, overwrite, prevUri)
    }

    fun recordExportedUri(context: Context, playlistKey: String?, fileUri: String?) {
        if (playlistKey.isNullOrBlank() || fileUri.isNullOrBlank()) return
        context.pluginPreferences(PREFS_EXPORT, Context.MODE_PRIVATE).edit()
            .putString(KEY_LAST_URI_PREFIX + playlistKey, fileUri)
            .apply()
    }

    fun kifraneiEnabled(context: Context): Boolean = KifraneiAppearanceBridge.enabled(context)

    fun setKifraneiEnabled(context: Context, enabled: Boolean) = KifraneiAppearanceBridge.setEnabled(context, enabled)

    fun kifraneiConfig(context: Context): KifraneiPortableConfig = KifraneiAppearanceBridge.config(context)

    fun updateKifraneiConfig(
        context: Context,
        transform: KifraneiPortableConfig.() -> KifraneiPortableConfig,
    ): KifraneiPortableConfig = KifraneiAppearanceBridge.updateConfig(context, transform)

    fun kifraneiMenuSummary(context: Context): String = KifraneiAppearanceBridge.menuSummary(context)

    fun autoTransitionMenuSummary(context: Context): String = KifraneiAppearanceBridge.autoTransitionMenuSummary(context)

    fun kifraneiConflictSnapshot(context: Context): ConflictSnapshot = KifraneiAppearanceBridge.conflictSnapshot(context)

    fun isOplusRom(): Boolean = OplusRomDetector.current

    fun isUsbBitPerfectSupported(): Boolean = UsbBitPerfectAudio.isApiSupported()

    fun usbBitPerfectEnabled(context: Context): Boolean = UsbBitPerfectAudio.isEnabled(context)

    fun setUsbBitPerfectEnabled(context: Context, enabled: Boolean) =
        UsbBitPerfectAudio.setEnabled(context, enabled)

    fun usbMenuSummary(context: Context): String = UsbBitPerfectAudio.menuSummary(context)

    fun probeUsbAudio(context: Context): UsbDiagnosticSnapshot = UsbBitPerfectAudio.probeNow(context)

    fun clearUsbPreferredMixer(context: Context): String = UsbBitPerfectAudio.clearPreferred(context)

    fun addUsbAudioListener(listener: () -> Unit): AutoCloseable =
        UsbBitPerfectAudio.addListener(listener)

    fun automixCacheStats(context: Context): Pair<Int, Long> =
        dev.kifranei.ampp.media.KifraneiAppearanceBridge.analysisCacheStats(context)

    fun clearAutomixCache(context: Context): Boolean =
        dev.kifranei.ampp.media.KifraneiAppearanceBridge.clearAnalysisCache(context)

    fun playbackSpeedConfig(context: Context): LongPressPlaybackSpeed.Config =
        LongPressPlaybackSpeed.config(context)

    fun updatePlaybackSpeedConfig(
        context: Context,
        transform: LongPressPlaybackSpeed.Config.() -> LongPressPlaybackSpeed.Config,
    ): LongPressPlaybackSpeed.Config = LongPressPlaybackSpeed.updateConfig(context, transform)

    fun playbackSpeedMenuSummary(context: Context): String =
        LongPressPlaybackSpeed.menuSummary(context)

    fun restoreNormalPlaybackSpeed(context: Context? = null) =
        LongPressPlaybackSpeed.restoreNormalSpeed(context)

    fun exportMenuSummary(context: Context): String {
        if (!playlistExportEnabled(context)) return "已关闭"
        val fmt = when (playlistExportFormat(context)) {
            PlaylistModels.ExportFormat.CSV -> "CSV"
            PlaylistModels.ExportFormat.TXT_TITLE_ARTIST -> "TXT 歌名-歌手"
            PlaylistModels.ExportFormat.TXT_ARTIST_TITLE -> "TXT 歌手-歌名"
        }
        val overwriteTag = if (exportOverwriteEnabled(context)) " · 覆盖旧文件" else ""
        return "默认 $fmt$overwriteTag（同步原生菜单）"
    }

    fun diagnosticLogMenuSummary(context: Context): String = PluginDiagnosticLog.menuSummary(context)

    fun diagnosticLogChannelInfo(
        context: Context,
        channel: PluginDiagnosticLog.Channel,
    ): PluginDiagnosticLog.ChannelInfo = PluginDiagnosticLog.channelInfo(context, channel)

    fun diagnosticLogTail(
        context: Context,
        channel: PluginDiagnosticLog.Channel,
    ): String = PluginDiagnosticLog.readTail(context, channel)

    fun diagnosticLogHeader(context: Context): String = PluginDiagnosticLog.buildDiagnosticHeader(context)

    fun clearDiagnosticLogs(context: Context, channel: PluginDiagnosticLog.Channel? = null): Boolean =
        PluginDiagnosticLog.clear(context, channel)

    fun exportDiagnosticLogsAsync(
        context: Context,
        onSuccess: (String) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        val app = context.applicationContext
        val task = Runnable {
            PluginDiagnosticLog.exportAllToDownloads(app)
                .onSuccess { path -> notifyMain { onSuccess(path) } }
                .onFailure { error -> notifyMain { onError(error) } }
        }
        val worker = executor
        if (worker != null && !worker.isShutdown) {
            runCatching { worker.execute(task) }.onFailure {
                Thread(task, "tcrrry-diag-export").apply { isDaemon = true }.start()
            }
        } else {
            Thread(task, "tcrrry-diag-export").apply { isDaemon = true }.start()
        }
    }

    fun listPlaylistsAsync(
        onSuccess: (List<PlaylistModels.PlaylistSummary>) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        val libraryReader = reader
        val worker = executor
        if (libraryReader == null || worker == null) {
            onError(IllegalStateException("当前版本或宿主未就绪"))
            return
        }
        worker.execute {
            runCatching { libraryReader.listPlaylists() }
                .onSuccess(onSuccess)
                .onFailure(onError)
        }
    }

    fun exportSelectedAsync(
        pids: List<Long>,
        format: PlaylistModels.ExportFormat,
        merged: Boolean,
        appContext: Context,
        onSuccess: (List<PlaylistModels.ExportFileResult>) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        val libraryReader = reader
        val worker = executor
        if (libraryReader == null || worker == null) {
            onError(IllegalStateException("当前版本或宿主未就绪"))
            return
        }
        worker.execute {
            runCatching {
                val bundles = libraryReader.readTracksForPlaylists(pids)
                if (bundles.isEmpty()) {
                    emptyList()
                } else if (merged) {
                    val key = "merged_" + pids.distinct().sorted().joinToString("_")
                    val options = buildExportOptions(appContext, key)
                    val res = PlaylistExporter.writeMergedToDownloads(appContext, bundles, format, options)
                    recordExportedUri(appContext, key, res.fileUri)
                    listOf(res)
                } else {
                    bundles.map { bundle ->
                        val key = if (bundle.summary != null && bundle.summary.persistentId != 0L) {
                            "pid_${bundle.summary.persistentId}"
                        } else {
                            "title_${bundle.summary?.title.orEmpty()}"
                        }
                        val options = buildExportOptions(appContext, key)
                        val res = PlaylistExporter.writeToDownloads(
                            appContext, bundle.summary, format, bundle.tracks, options,
                        )
                        recordExportedUri(appContext, key, res.fileUri)
                        res
                    }
                }
            }.onSuccess(onSuccess).onFailure(onError)
        }
    }

    fun copySelectedTxtAsync(
        pids: List<Long>,
        artistFirst: Boolean,
        onSuccess: (String, Int, Int) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        val libraryReader = reader
        val worker = executor
        if (libraryReader == null || worker == null) {
            onError(IllegalStateException("当前版本或宿主未就绪"))
            return
        }
        worker.execute {
            runCatching {
                val bundles = libraryReader.readTracksForPlaylists(pids)
                val text = PlaylistExporter.formatMultiTxtClipboard(bundles, artistFirst)
                val totalTracks = bundles.sumOf { it.tracks?.size ?: 0 }
                Triple(text, bundles.size, totalTracks)
            }.onSuccess { (text, playlistCount, trackCount) ->
                onSuccess(text, playlistCount, trackCount)
            }.onFailure(onError)
        }
    }
}
