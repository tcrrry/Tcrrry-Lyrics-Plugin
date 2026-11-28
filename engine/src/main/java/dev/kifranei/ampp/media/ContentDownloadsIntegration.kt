package dev.kifranei.ampp.media

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal fun resolveFragmentCreateViewMethod(targetType: Class<*>, fallback: Method): Method {
    var cur: Class<*>? = targetType
    while (cur != null &&
        cur.name != "com.apple.android.music.common.BaseActivityFragment" &&
        cur.name != "androidx.fragment.app.Fragment" &&
        cur != Any::class.java
    ) {
        val declared = runCatching {
            cur.getDeclaredMethod(
                "onCreateView",
                android.view.LayoutInflater::class.java,
                ViewGroup::class.java,
                android.os.Bundle::class.java,
            ).apply { isAccessible = true }
        }.getOrNull()
        if (declared != null) return declared
        cur = cur.superclass
    }
    return fallback
}

/** Native page/loader adapter, including the Compose album pages used by the library. */
internal class ContentDownloadsIntegration(
    private val application: Application,
    private val loader: ClassLoader,
    private val build: TargetBuild,
    val capture: ContentDownloadCapture = ContentDownloadCapture(),
    private val exportOptions: ContentExportOptions = ContentExportOptions(),
) : AutoCloseable {
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val storage = lazy { ContentExportStorage(application, exportOptions) { error ->
        MediaRuntime.log("content_download: unexpected export failure", contentDownloadInstallError(error))
    }.apply { setMotionStructureListener { summary -> MediaRuntime.log("content_download_hls: $summary") } } }
    private val decoder = ContentDownloadPageDecoder(capture.options)
    private val readerWorker = lazy {
        ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(32),
            { task -> Thread(task, "AM++ native content snapshots").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
            .apply { allowCoreThreadTimeOut(true) }
    }
    private val surfaces = WeakHashMap<Any, PageSurface>() // Main thread only; destroy-view removes entries.
    private val jobs = ConcurrentHashMap<String, ContentExportJob>()
    private val dialogs = Collections.newSetFromMap(IdentityHashMap<AlertDialog, Boolean>()) // Main thread only.
    private val resourceLock = Any()
    private var scope: PluginScope? = null
    private var installation: TargetCapabilityInstall? = null
    @Volatile private var closed = false
    private lateinit var contracts: ContentDownloadContracts
    private lateinit var artistType: Class<*>
    private lateinit var albumType: Class<*>
    private lateinit var composeAlbumType: Class<*>
    private lateinit var composeAlbumModelType: Class<*>
    private lateinit var artistModelType: Class<*>
    private lateinit var continuationType: Class<*>
    private var albumTitleId = 0

    @Synchronized fun install(): TargetCapabilityInstall {
        installation?.let { return it }
        if (closed) return TargetCapabilityInstall.Unsupported("Content downloads are closed")
        val profile = PluginProfiles.find(build.packageName, build.versionName, build.versionCode)?.document
            ?.optJSONObject("contentDownloads")
            ?: return TargetCapabilityInstall.Unsupported("No verified content-download contract for ${build.displayName}")
        val lifetime = PluginScope()
        scope = lifetime
        lifetime.onClose(::dispose)
        var installStage = "method contracts"
        return try {
            contracts = ContentDownloadContracts(
                CONTENT_DOWNLOAD_METHODS.associateWith { key ->
                    installStage = "method $key"
                    profileMethod(key)
                },
                CONTENT_DOWNLOAD_FIELDS.associateWith { key ->
                    installStage = "field $key"
                    PluginProfiles.field(key)
                },
            )
            installStage = "class artistFragmentClass"
            artistType = loader.loadClass(profile.getString("artistFragmentClass"))
            installStage = "class albumFragmentClass"
            albumType = loader.loadClass(profile.getString("albumFragmentClass"))
            installStage = "class composeAlbumFragmentClass"
            composeAlbumType = loader.loadClass(profile.getString("composeAlbumFragmentClass"))
            composeAlbumModelType = loader.loadClass(profile.getString("composeAlbumModelClass"))
            installStage = "class artistModelClass"
            artistModelType = loader.loadClass(profile.getString("artistModelClass"))
            installStage = "class loaderContinuationClass"
            continuationType = loader.loadClass(profile.getString("loaderContinuationClass"))
            installStage = "resource collection_header_title"
            albumTitleId = application.resources.getIdentifier(profile.getString("albumTitleId"), "id", build.packageName)
            check(albumTitleId != 0 && albumTitleId == profile.getInt("albumTitleResourceId"))
            installLyricsCapture(lifetime) { key -> installStage = "hook $key" }
            installStage = "hook content-download-artist-set-data"
            observe("content-download-artist-set-data", lifetime) { call ->
                if (call.throwable == null && artistModelType.isInstance(call.thisObject)) enqueueEntity(call.args.firstOrNull())
            }
            installStage = "hook content-download-artist-create-header"
            observe("content-download-artist-create-header", lifetime) { call ->
                if (call.throwable == null && artistModelType.isInstance(call.thisObject)) enqueueEntity(call.args.firstOrNull())
            }

            installStage = "hook content-download-album-set-data"
            observe("content-download-album-set-data", lifetime) { call ->
                if (call.throwable == null) enqueueEntity(call.args.firstOrNull())
            }
            observe("content-download-compose-album-set-data", lifetime) { call ->
                if (call.throwable == null && composeAlbumModelType.isInstance(call.thisObject)) enqueueEntity(call.args.firstOrNull())
            }
            installStage = "hook content-download-artist-create-view"
            installPage("content-download-artist-create-view", ContentDownloadPageKind.ARTIST, lifetime)
            installStage = "hook content-download-album-create-view"
            installPage("content-download-album-create-view", ContentDownloadPageKind.ALBUM, lifetime)
            installStage = "hook content-download-compose-album-create-view"
            val composeAlbumCreateView = resolveFragmentCreateViewMethod(
                composeAlbumType,
                contracts.method("content-download-compose-album-create-view"),
            )
            installPage("content-download-compose-album-create-view", ContentDownloadPageKind.ALBUM, lifetime, composeAlbumCreateView)
            installStage = "hook content-download-base-destroy-view"
            check(MediaRuntime.observeMethod(contracts.method("content-download-base-destroy-view"), object : PluginMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (Looper.myLooper() == Looper.getMainLooper()) surfaces.remove(param.thisObject)?.close()
                }
            }, lifetime))
            installStage = "scope activation"
            lifetime.activate()
            TargetCapabilityInstall.Active("Native artist/album title downloads and song-ID-bound original TTML capture installed")
                .also { installation = it }
        } catch (error: Throwable) {
            // Reflection/SDK errors can embed arbitrary values in messages or causes.
            runCatching { MediaRuntime.log("Native content downloads install failed at $installStage", contentDownloadInstallError(error)) }
            lifetime.close()
            TargetCapabilityInstall.Unsupported("Content downloads need the complete verified method/field/resource contract")
                .also { installation = it }
        }
    }

    /** Playback menu seam: the caller supplies the song being acted on, never the current prefetch target. */
    fun ttmlAsset(songId: Long, title: String): ContentExportAsset.TtmlLyrics = capture.ttmlAsset(songId, title)

    fun downloadTtml(activity: Activity, songId: Long, title: String): ContentExportJob? =
        startDownload(activity, ttmlAsset(songId, title))

    fun exportTtml(songId: Long, title: String, onState: (ContentExportStatus) -> Unit = {}): ContentExportJob {
        return exportStorage().export(ttmlAsset(songId, title), onState)
    }

    fun showPageDownloads(activity: Activity, kind: ContentDownloadPageKind, id: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { showPageDownloads(activity, kind, id) }; return }
        if (closed || activity.isFinishing || activity.isDestroyed) return
        val page = capture.page(kind, id)
        if (page == null) { toast(activity, ContentDownloadUi.pageNotReady(language(activity))); return }
        val assets = page.assets()
        val lang = language(activity)
        val labels = assets.map { asset ->
            asset.kind.label(lang) + if (asset.unavailableReason() != null) ContentDownloadUi.unavailable(lang) else ""
        }
        val dialog = AlertDialog.Builder(activity).setTitle(page.title.ifBlank { ContentDownloadUi.download(lang) })
            .setItems(labels.toTypedArray()) { _, index -> startDownload(activity, assets[index]) }
            .setNegativeButton(ContentDownloadUi.close(lang), null).create()
        dialog.show()
        trackDialog(dialog)
        findSurface(kind, id)?.ownDialog(dialog)
    }

    override fun close() {
        val lifetime = synchronized(this) { scope }
        if (lifetime != null) lifetime.close() else dispose()
    }

    private fun dispose() {
        val resources = synchronized(resourceLock) {
            if (closed) return
            closed = true
            (if (readerWorker.isInitialized()) readerWorker.value else null) to
                (if (storage.isInitialized()) storage.value else null)
        }
        capture.close()
        resources.first?.shutdownNow()
        resources.second?.close()
        jobs.clear()
        main.removeCallbacksAndMessages(null)
        val cleanup = {
            surfaces.values.toList().forEach(PageSurface::close); surfaces.clear()
            dialogs.toList().forEach(AlertDialog::dismiss); dialogs.clear()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) cleanup() else main.post { cleanup() }
    }

    private fun exportStorage(): ContentExportStorage = synchronized(resourceLock) {
        storage.value.also { if (closed) it.close() }
    }

    private fun installLyricsCapture(lifetime: PluginScope, installing: (String) -> Unit) {
        fun loaderHook(key: String, songId: (PluginMethodHook.MethodHookParam) -> Long?) {
            installing(key)
            check(MediaRuntime.observeMethod(contracts.method(key), object : PluginMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) { capture.beginLoad(songId(param)) }
                override fun afterHookedMethod(param: MethodHookParam) { capture.endLoad() }
            }, lifetime))
        }
        loaderHook("content-download-lyrics-file-loader") { (it.args.getOrNull(1) as? Number)?.toLong() }
        loaderHook("content-download-lyrics-online-loader") { call ->
            val continuation = call.args.getOrNull(4)?.takeIf(continuationType::isInstance)
            contentDownloadLoaderSongId(call.args.firstOrNull(),
                (contracts.field("content-download-loader-resume-label", continuation) as? Number)?.toInt(),
                (contracts.field("content-download-loader-song-id", continuation) as? Number)?.toLong())
        }
        installing("content-download-lyrics-parser")
        check(MediaRuntime.observeMethod(contracts.method("content-download-lyrics-parser"), object : PluginMethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.throwable != null || param.result == null) return
                val id = capture.parserSongId() ?: return
                val raw = param.args.firstOrNull() as? String ?: return
                if (raw.length > capture.options.maxTtmlChars) return
                enqueue { capture.recordTtml(id, raw) }
            }
        }, lifetime))
    }

    /** Keep the exact profile contract while resolving JVM array descriptors through the host loader. */
    private fun profileMethod(key: String): Method {
        val definition = PluginProfiles.document.getJSONObject("indexed").getJSONObject("methodContracts").getJSONObject(key)
        val parameters = definition.getJSONArray("parameters")
        val names = (0 until parameters.length()).map(parameters::getString)
        if (names.none { it.startsWith('[') }) return PluginProfiles.method(key)
        return contentDownloadArrayMethod(definition, loader, PluginProfiles::type)
    }

    private fun observe(key: String, lifetime: PluginScope, action: (PluginMethodHook.MethodHookParam) -> Unit) {
        check(MediaRuntime.observeMethod(contracts.method(key), object : PluginMethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) { runCatching { action(param) } }
        }, lifetime))
    }

    private fun enqueue(action: () -> Unit) {
        synchronized(resourceLock) {
            if (closed) return
            runCatching { readerWorker.value.execute { if (!closed) runCatching(action) } }
        }
    }

    private fun enqueueEntity(entity: Any?) {
        if (entity == null) return
        // Native view models and their getters belong to the UI thread. Read them
        // after the setter callback releases native locks; retain only the immutable asset snapshot.
        main.post {
            if (closed) return@post
            val page = runCatching { decoder.fromNative(entity, contracts) }.getOrNull() ?: return@post
            if (capture.recordPage(page)) surfaces.values.toList().forEach { it.refreshMetadata() }
        }
    }

    private fun installPage(
        key: String,
        kind: ContentDownloadPageKind,
        lifetime: PluginScope,
        targetMethod: Method = contracts.method(key),
    ) {
        MediaRuntime.log("content_download: hook $kind on ${targetMethod.declaringClass.name}.${targetMethod.name}")
        check(MediaRuntime.hookMethod(targetMethod, object : PluginMethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (closed || param.throwable != null || Looper.myLooper() != Looper.getMainLooper()) return
                val owner = param.thisObject ?: return
                val nativeRoot = param.result as? View ?: return
                if (if (kind == ContentDownloadPageKind.ARTIST) !artistType.isInstance(owner)
                    else !albumType.isInstance(owner) && !composeAlbumType.isInstance(owner)) return
                val frame = if (nativeRoot is FrameLayout) nativeRoot else {
                    if (nativeRoot.parent != null) return
                    // Xb.g returns a ComposeView; its composition remains the existing child.
                    FrameLayout(nativeRoot.context).apply {
                        nativeRoot.layoutParams?.let { layoutParams = it }
                        addView(nativeRoot, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                    }.also { param.result = it }
                }
                MediaRuntime.log("content_download: page hooked kind=$kind owner=${owner.javaClass.name}")
                surfaces.remove(owner)?.close()
                val surface = PageSurface(owner, frame, kind).also { it.attach() }
                surfaces[owner] = surface
                if (kind == ContentDownloadPageKind.ARTIST) runCatching {
                    val model = contracts.call("content-download-artist-model", owner)
                    val query = runCatching { PluginProfiles.method("content-download-artist-query-entity") }.getOrNull()
                    val entity = query?.invoke(model)
                    surface.prime(entity)
                }
            }
        }, lifetime))
    }

    private fun findSurface(kind: ContentDownloadPageKind, id: String) = surfaces.values.firstOrNull { it.kind == kind && it.pageId == id }

    private fun startDownload(activity: Activity, asset: ContentExportAsset): ContentExportJob? {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { startDownload(activity, asset) }; return null }
        if (closed || activity.isFinishing || activity.isDestroyed) return null
        val lang = language(activity)
        asset.unavailableReason()?.let { toast(activity, it.message(lang)); return null }
        val reference = AtomicReference<ContentExportJob?>()
        val activityRef = WeakReference(activity)
        val progress = AlertDialog.Builder(activity).setTitle(asset.kind.label(lang)).setMessage(ContentDownloadUi.preparing(lang))
            .setNegativeButton(ContentDownloadUi.cancel(lang)) { _, _ -> reference.get()?.cancel() }.create()
        progress.setOnCancelListener { reference.get()?.cancel() }
        progress.show()
        trackDialog(progress)
        val job = exportStorage().export(asset) { status ->
            if (status.phase.isTerminal) jobs.remove(status.jobId)
            if (closed) return@export
            val active = activityRef.get()?.takeUnless { it.isFinishing || it.isDestroyed } ?: return@export
            if (status.phase.isTerminal) {
                MediaRuntime.log("content_download: ${asset.kind} ${status.phase} bytes=${status.bytesCopied} reason=${status.failure?.reason} http=${status.failure?.httpStatus}")
                progress.dismiss()
                val message = status.file?.let { ContentDownloadUi.saved(lang, it) } ?: status.failure?.message(lang).orEmpty()
                toast(active, message)
            } else if (progress.isShowing) {
                progress.setMessage(ContentDownloadUi.progress(lang, status))
                progress.getButton(AlertDialog.BUTTON_NEGATIVE)?.isEnabled = status.phase != ContentExportPhase.PUBLISHING && status.phase != ContentExportPhase.CANCELLING
            }
        }
        reference.set(job)
        if (!job.status.phase.isTerminal) jobs[job.id] = job
        return job
    }

    private fun language(activity: Activity) = activity.resources.configuration.locales[0].language
    private fun trackDialog(dialog: AlertDialog) { dialogs.add(dialog); dialog.setOnDismissListener { dialogs.remove(dialog) } }
    private fun toast(activity: Activity, message: String) { if (message.isNotEmpty()) Toast.makeText(activity.applicationContext, message, Toast.LENGTH_LONG).show() }

    private inner class PageSurface(owner: Any, private val root: FrameLayout, val kind: ContentDownloadPageKind) : AutoCloseable {
        private val ownerRef = WeakReference(owner)
        private val anchor = ContentDownloadComposeAnchor(contracts)
        private val artistAnchor = ContentDownloadArtistAnchor(contracts, build.packageName)
        private val artistEntry = ContentDownloadArtistEntryState()
        private var entityRef: WeakReference<Any>? = null
        private var lastLookup = 0L
        private var lastLayoutLookup = 0L
        private var nativeTitle: TextView? = null
        private var lastGlyphs: ContentDownloadBounds? = null
        private val hold = ContentDownloadPlacementHold(if (kind == ContentDownloadPageKind.ALBUM) CONTENT_DOWNLOAD_HOLD_WHILE_PAGE_LIVE else CONTENT_DOWNLOAD_ANCHOR_GRACE_MS)
        private var dialog: WeakReference<AlertDialog>? = null
        var pageId = ""
            private set
        private var pageTitle = ""
        private var firstMetadataAt = 0L
        private var removed = false
        private val position = IntArray(2)
        private val button = ImageButton(root.context).apply {
            val attributes = root.context.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
            val foreground = try { attributes.getColor(0, Color.GRAY) } finally { attributes.recycle() }
            setImageDrawable(ContentDownloadIcon(foreground))
            background = RippleDrawable(ColorStateList.valueOf(Color.argb(48, 128, 128, 128)), null,
                GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) })
            elevation = 0f; visibility = View.GONE
            contentDescription = ContentDownloadUi.download(application.resources.configuration.locales[0].language)
            setOnClickListener {
                val activity = contracts.call("content-download-fragment-activity", ownerRef.get()) as? Activity ?: return@setOnClickListener
                showPageDownloads(activity, kind, pageId)
            }
        }
        private val draw = ViewTreeObserver.OnPreDrawListener { updatePosition(); true }
        private val layout = View.OnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom) artistAnchor.invalidate()
        }

        fun attach() {
            root.addView(button, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.TOP or Gravity.LEFT))
            root.viewTreeObserver.addOnPreDrawListener(draw)
            root.addOnLayoutChangeListener(layout)
            refreshMetadata()
        }

        fun ownDialog(value: AlertDialog) { dialog?.get()?.dismiss(); dialog = WeakReference(value) }

        fun refreshMetadata() {
            val owner = ownerRef.get() ?: return
            val composeAlbum = kind == ContentDownloadPageKind.ALBUM && composeAlbumType.isInstance(owner)
            val modelKey = when {
                kind == ContentDownloadPageKind.ARTIST -> "content-download-artist-model"
                composeAlbum -> "content-download-compose-album-model"
                else -> "content-download-album-model"
            }
            val dataKey = when {
                kind == ContentDownloadPageKind.ARTIST -> "content-download-artist-data"
                composeAlbum -> "content-download-compose-album-data"
                else -> "content-download-album-data"
            }
            val model = contracts.call(modelKey, owner) ?: return
            val entity = contracts.call(dataKey, model) ?: return
            val id = contracts.get(entity, "content-download-entity-id") as? String ?: return
            if (!validContentDownloadId(id)) return
            val nativeKind = ContentDownloadPageKind.fromNativeType(contracts.get(entity, "content-download-entity-type") as? String)
            if (nativeKind != kind) return
            val title = (contracts.get(entity, "media-entity-get-title-method") as? String).orEmpty()
            if (id != pageId || title != pageTitle) {
                pageId = id; pageTitle = title; anchor.clear(); nativeTitle = null; lastGlyphs = null; lastLayoutLookup = 0L
                firstMetadataAt = SystemClock.uptimeMillis()
                hold.reset()?.let { logAnchor(it) }
                artistEntry.clear(); artistAnchor.clear()
            }
            if (firstMetadataAt == 0L && pageId.isNotEmpty()) {
                firstMetadataAt = SystemClock.uptimeMillis()
            }
            if (entityRef?.get() !== entity) { entityRef = WeakReference(entity); enqueueEntity(entity) }
        }

        private fun updatePosition() {
            if (removed || closed || root.width <= 0 || root.height <= 0) return
            val now = SystemClock.uptimeMillis()
            if (now - lastLookup >= 500) { lastLookup = now; refreshMetadata() }
            if (pageId.isEmpty() || kind == ContentDownloadPageKind.ALBUM && pageTitle.isBlank()) {
                hold.reset()?.let { logAnchor(it) }
                button.visibility = View.GONE; return
            }
            root.getLocationOnScreen(position)
            val nativeGlyphs = if (kind == ContentDownloadPageKind.ALBUM) {
                if (nativeTitle?.isAttachedToWindow != true) nativeTitle = root.findViewById<TextView>(albumTitleId)
                nativeTitle?.takeIf { contentDownloadTitleMatch(it.text.toString(), pageTitle) > 0 }
                    ?.let { textViewGlyphBounds(it, position) }
            } else null
            anchor.unadjusted = null
            val glyphs = nativeGlyphs ?: run {
                if (now - lastLayoutLookup >= 500 || lastLayoutLookup == 0L) {
                    lastLayoutLookup = now
                    anchor.find(root, pageTitle, now)
                }
                anchor.bounds(position)
            }
            lastGlyphs = glyphs
            val computed = if (kind == ContentDownloadPageKind.ARTIST) {
                artistEntry.placement(glyphs, anchor.hasNode, root.width, root.height, root.resources.displayMetrics.density,
                    root.layoutDirection == View.LAYOUT_DIRECTION_RTL) { artistAnchor.placement(root, position, now) }
            } else glyphs?.let { contentDownloadButtonPlacement(it, root.width, root.height, root.resources.displayMetrics.density,
                root.layoutDirection == View.LAYOUT_DIRECTION_RTL) }
                ?: anchor.unadjusted?.let { contentDownloadButtonPlacement(it, root.width, root.height, root.resources.displayMetrics.density,
                    root.layoutDirection == View.LAYOUT_DIRECTION_RTL) }
            val held = hold.update(now, computed, if (glyphs == null) (if (anchor.hasNode) "anchor_bounds_unavailable" else "anchor_node_missing") else "placement_rejected")
            held.transition?.let { logAnchor(it) }
            var shown = held.placement
            if (shown == null) {
                if (pageId.isNotEmpty() && kind == ContentDownloadPageKind.ARTIST) {
                    val size = dp(44)
                    shown = ContentDownloadButtonPlacement(dp(24), dp(150), size, dp(10), dp(10), dp(24))
                } else if (pageId.isNotEmpty() &&
                    kind == ContentDownloadPageKind.ALBUM &&
                    pageTitle.isNotBlank() &&
                    firstMetadataAt > 0L &&
                    now - firstMetadataAt >= CONTENT_DOWNLOAD_ANCHOR_GRACE_MS
                ) {
                    val topInset = if (Build.VERSION.SDK_INT >= 30) {
                        root.rootWindowInsets?.getInsets(WindowInsets.Type.statusBars())?.top ?: dp(32)
                    } else {
                        dp(32)
                    }
                    shown = ContentDownloadButtonPlacement(dp(20), topInset + dp(62), dp(40), dp(11), dp(11), dp(18))
                } else {
                    button.visibility = View.GONE; return
                }
            }
            button.visibility = View.VISIBLE
            val placement: ContentDownloadButtonPlacement = shown
            val params = button.layoutParams as FrameLayout.LayoutParams
            if (params.width != placement.size || params.height != placement.size) {
                params.width = placement.size; params.height = placement.size; button.layoutParams = params
            }
            button.translationX = placement.left.toFloat(); button.translationY = placement.top.toFloat()
            button.setPadding(placement.iconLeft, placement.iconTop, placement.size - placement.iconLeft - placement.iconSize,
                placement.size - placement.iconTop - placement.iconSize)
        }

        fun prime(entity: Any?) {
            if (entity == null) return
            runCatching {
                val id = contracts.get(entity, "content-download-entity-id") as? String ?: return
                if (!validContentDownloadId(id)) return
                val title = (contracts.get(entity, "media-entity-get-title-method") as? String).orEmpty()
                pageId = id; pageTitle = title
                enqueueEntity(entity)
                MediaRuntime.log("content_download: primed artist page $id")
            }
        }

        override fun close() {
            if (removed) return
            removed = true
            if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnPreDrawListener(draw)
            root.removeOnLayoutChangeListener(layout)
            button.setOnClickListener(null)
            root.removeView(button); dialog?.get()?.dismiss(); dialog = null
            nativeTitle = null; anchor.clear(); entityRef = null; lastGlyphs = null
            artistEntry.clear(); artistAnchor.clear()
        }
        private fun logAnchor(message: String) { MediaRuntime.log("content_download_anchor: kind=$kind page=$pageId $message") }
        private fun dp(value: Int) = (value * root.resources.displayMetrics.density).roundToInt()
    }
}

/** Preserve diagnostic types/frames without retaining original messages, causes or suppressed errors. */
internal fun contentDownloadInstallError(error: Throwable): Throwable {
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    val types = ArrayList<String>()
    var current: Throwable? = error
    val identifier = Regex("[A-Za-z0-9_.$<>-]{1,256}")
    fun safe(value: String): String = if (identifier.matches(value)) value else "<redacted>"
    while (current != null && types.size < 8 && seen.add(current)) {
        types += safe(current.javaClass.name)
        current = current.cause
    }
    return IllegalStateException("Exception types: ${types.joinToString(" <- ")}; original messages omitted").apply {
        stackTrace = error.stackTrace.take(24).map { frame ->
            StackTraceElement(safe(frame.className), safe(frame.methodName), frame.fileName?.let(::safe), frame.lineNumber)
        }.toTypedArray()
    }
}

internal class ContentDownloadContracts(private val methods: Map<String, Method>, private val fields: Map<String, Field>) : ContentDownloadNativeAccess {
    fun method(key: String): Method = checkNotNull(methods[key])
    override fun get(owner: Any, contract: String): Any? = call(contract, owner)
    fun call(key: String, owner: Any?, vararg arguments: Any?): Any? {
        val method = methods[key] ?: return null
        if (!java.lang.reflect.Modifier.isStatic(method.modifiers) && (owner == null || !method.declaringClass.isInstance(owner))) return null
        return runCatching { method.invoke(owner, *arguments) }.getOrNull()
    }
    fun field(key: String, owner: Any?): Any? {
        val field = fields[key] ?: return null
        if (owner == null || !field.declaringClass.isInstance(owner)) return null
        return runCatching { field.get(owner) }.getOrNull()
    }
}

internal fun contentDownloadArrayMethod(definition: org.json.JSONObject, loader: ClassLoader, type: (String) -> Class<*>): Method {
    val parameters = definition.getJSONArray("parameters")
    val names = (0 until parameters.length()).map(parameters::getString)
    return type(definition.getString("owner")).getDeclaredMethod(definition.getString("name"),
        *names.map { if (it.startsWith('[')) Class.forName(it, false, loader) else type(it) }.toTypedArray()).apply {
        check(returnType == type(definition.getString("returns")) &&
            java.lang.reflect.Modifier.isStatic(modifiers) == definition.getBoolean("static"))
        isAccessible = true
    }
}

internal data class ContentDownloadBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    fun valid() = listOf(left, top, right, bottom).all(Float::isFinite) && width > 0 && height > 0
    fun translated(x: Float, y: Float) = ContentDownloadBounds(left + x, top + y, right + x, bottom + y)
}

internal data class ContentDownloadGlyphGeometry(val text: ContentDownloadBounds, val lastLine: ContentDownloadBounds)

/** A semantic box may wrap the glyphs while the text result still uses a wider paragraph origin. */
internal fun contentDownloadComposeGlyphBounds(native: ContentDownloadBounds, glyphs: ContentDownloadGlyphGeometry): ContentDownloadBounds? {
    val text = glyphs.text
    val line = glyphs.lastLine
    if (!native.valid() || !text.valid() || !line.valid() || line.left < text.left || line.right > text.right ||
        line.top < text.top || line.bottom > text.bottom) return null
    fun axis(start: Float, end: Float, textStart: Float, textEnd: Float, lineStart: Float, lineEnd: Float): Pair<Float, Float>? {
        val size = end - start
        // Float character bounds and integer semantic sizes can differ by up to two pixels.
        val tolerance = 2f
        if (textStart >= -tolerance && textEnd <= size + tolerance) {
            return (start + lineStart).coerceIn(start, end) to (start + lineEnd).coerceIn(start, end)
        }
        if (kotlin.math.abs(size - (textEnd - textStart)) > tolerance) return null
        fun coordinate(value: Float): Float = when (value) {
            textStart -> start
            textEnd -> end
            else -> (start + value - textStart).coerceIn(start, end)
        }
        return coordinate(lineStart) to coordinate(lineEnd)
    }
    val x = axis(native.left, native.right, text.left, text.right, line.left, line.right) ?: return null
    val y = axis(native.top, native.bottom, text.top, text.bottom, line.top, line.bottom) ?: return null
    return ContentDownloadBounds(x.first, y.first, x.second, y.second).takeIf(ContentDownloadBounds::valid)
}

internal data class ContentDownloadButtonPlacement(val left: Int, val top: Int, val size: Int, val iconLeft: Int, val iconTop: Int, val iconSize: Int)

/** Keep the visible glyph beside the last title line; its transparent touch target may overlap whitespace. */
internal fun contentDownloadButtonPlacement(title: ContentDownloadBounds, width: Int, height: Int, density: Float, rtl: Boolean): ContentDownloadButtonPlacement? {
    if (!title.valid() || !density.isFinite() || density <= 0 || title.top < 0 || title.bottom > height) return null
    val size = max(1, (40 * density).roundToInt())
    if (width < size || height < size) return null
    for ((iconDp, gapDp) in listOf(18 to 4, 14 to 2)) {
        val icon = max(1, (iconDp * density).roundToInt())
        val gap = gapDp * density
        val candidates = if (rtl) listOf(title.left - gap - icon, title.right + gap) else listOf(title.right + gap, title.left - gap - icon)
        for (visualLeft in candidates) {
            if (visualLeft < 0 || visualLeft + icon > width) continue
            val visualTop = (title.top + title.bottom - icon) / 2
            if (visualTop < 0 || visualTop + icon > height) continue
            val left = (visualLeft + (icon - size) / 2f).roundToInt().coerceIn(0, width - size)
            val top = (visualTop + (icon - size) / 2f).roundToInt().coerceIn(0, height - size)
            return ContentDownloadButtonPlacement(left, top, size, (visualLeft - left).roundToInt().coerceIn(0, size - icon),
                (visualTop - top).roundToInt().coerceIn(0, size - icon), icon)
        }
    }
    return null
}

/**
 * 2 = exact title, 1 = title followed only by an explicit/clean badge (inline placeholder char, "E", "Explicit"...), 0 = no match.
 * Apple's title row renders the "E" badge inside the same text node/semantics, so strict equality used to reject the page title.
 */
internal fun contentDownloadTitleMatch(plain: String, title: String): Int {
    val t = normalizeContentDownloadTitle(title)
    if (t.isEmpty()) return 0
    val n = normalizeContentDownloadTitle(plain)
    if (n == t) return 2
    if (!n.startsWith(t)) return 0
    val rest = n.substring(t.length).filter { it.code != 0xFFFC && it.code != 0xFFFD && it.code !in 0x200b..0x200f && it.code != 0x2060 && it.code != 0xFEFF }
        .trim().trim('[', ']', '(', ')').trim().lowercase()
    return if (rest in CONTENT_DOWNLOAD_BADGE_TEXT) 1 else 0
}

private val CONTENT_DOWNLOAD_BADGE_TEXT = setOf("", "e", "c", "explicit", "clean", "explicit content", "\uD83C\uDD34", "\uD83C\uDD72", "\uD83C\uDD54", "\uD83C\uDD52",
    "露骨", "不适宜内容", "明确", "已净化")

internal fun normalizeContentDownloadTitle(value: String) = value.filter { it.code !in 0x202a..0x202e && it.code !in 0x2066..0x2069 }
    .trim().replace(Regex("\\s+"), " ")

/** Uses native semantic/layout objects only for positioning, without injecting foreign Compose code. */
internal class ContentDownloadComposeAnchor(private val contracts: ContentDownloadContracts) {
    private var owner: View? = null
    private var node: Any? = null
    private var glyphs: ContentDownloadGlyphGeometry? = null
    private var geometryReported = false
    val hasNode: Boolean get() = node != null
    fun clear() { owner = null; node = null; glyphs = null; lostSince = 0L; badgeNodes = emptyList(); unadjusted = null }

    private var lostSince = 0L
    private var missReported = false
    private var looseReported = false
    private var badgeReported = false
    private var badgeNodes: List<Any> = emptyList()
    /** Title bounds before badge adjustment, so the caller can fall back when the badge-adjusted spot does not fit. */
    var unadjusted: ContentDownloadBounds? = null

    /**
     * Re-scans for the title. A scan that finds nothing (page recomposing, text layout not ready, rows being loaded) keeps the
     * previous node for [CONTENT_DOWNLOAD_ANCHOR_GRACE_MS] instead of dropping it, so the button does not flicker or vanish.
     */
    fun find(root: View, title: String, now: Long = 0L) {
        if (title.isBlank()) { clear(); return }
        var foundOwner: View? = null
        var foundNode: Any? = null
        var foundGlyphs: ContentDownloadGlyphGeometry? = null
        var best: ContentDownloadAnchorCandidate? = null
        var looseOwner: View? = null; var looseNode: Any? = null; var looseGlyphs: ContentDownloadGlyphGeometry? = null
        var looseBest: ContentDownloadAnchorCandidate? = null; var looseText: String? = null
        val texts = ArrayList<String>(); var owners = 0; var nodeTotal = 0; var capped = false
        val views = ArrayDeque<View>(); views.add(root)
        val badgeCandidates = ArrayList<Pair<View, Any>>()
        var visited = 0
        while (views.isNotEmpty()) {
            if (visited++ >= 256) { capped = true; break }
            val view = views.removeFirst()
            if (view is ViewGroup) for (index in 0 until min(view.childCount, 256)) views.add(view.getChildAt(index))
            val semanticOwner = contracts.call("content-download-compose-semantics", view) ?: continue
            val semanticRoot = contracts.call("content-download-semantics-root", semanticOwner) ?: continue
            owners++
            val nodes = ArrayDeque<Any>(); nodes.add(semanticRoot)
            val seen = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
            var count = 0
            while (nodes.isNotEmpty()) {
                if (count++ >= 1024) { capped = true; break }
                nodeTotal++
                val current = nodes.removeFirst()
                if (!seen.add(current)) continue
                val properties = semanticsProperties(current)
                val text = properties["Text"] as? List<*>
                val plain = text?.joinToString("") { element -> contracts.call("content-download-annotated-text", element) as? String ?: (element as? String).orEmpty() }
                val match = if (plain != null) contentDownloadTitleMatch(plain, title) else 0
                if (badgeCandidates.size < 32 && (plain != null && contentDownloadIsBadgeText(plain) ||
                        semanticsDescriptions(properties).any(::contentDownloadIsBadgeText))) badgeCandidates.add(view to current)
                if (plain != null && match == 0 && texts.size < 200) texts.add(plain)
                if (plain != null && match == 0 && contentDownloadTitleLooseMatch(plain, title)) {
                    val local = glyphGeometry(properties["GetTextLayoutResult"], contentDownloadBadgeExtent(plain, title))
                    if (local != null) {
                        val candidate = ContentDownloadAnchorCandidate(0, properties.containsKey("Heading"), local.lastLine.height)
                        if (contentDownloadAnchorBetter(candidate, looseBest)) { looseBest = candidate; looseText = plain; looseOwner = view; looseNode = current; looseGlyphs = local }
                    }
                }
                if (plain != null && match > 0) {
                    val local = glyphGeometry(properties["GetTextLayoutResult"], plain.length)
                    if (local != null) {
                        val candidate = ContentDownloadAnchorCandidate(match, properties.containsKey("Heading"), local.lastLine.height)
                        if (contentDownloadAnchorBetter(candidate, best)) {
                            best = candidate; foundOwner = view; foundNode = current; foundGlyphs = local
                        }
                    }
                }
                (contracts.call("content-download-semantics-children", current, true, false) as? List<*>)?.take(512)?.filterNotNull()?.forEach(nodes::add)
            }
        }
        if (foundNode == null && looseNode != null) {
            foundOwner = looseOwner; foundNode = looseNode; foundGlyphs = looseGlyphs
            if (!looseReported) { looseReported = true; MediaRuntime.log("content_download_anchor: matched merged title node (loose)") }
            if (!badgeReported && contentDownloadBadgeExtent(looseText ?: "", title) > normalizeContentDownloadTitle(title).length) {
                badgeReported = true; MediaRuntime.log("content_download_anchor: badge-adjusted placement source=text extent")
            }
        }
        if (foundNode != null) {
            owner = foundOwner; node = foundNode; glyphs = foundGlyphs; lostSince = 0L; missReported = false
            badgeNodes = badgeCandidates.filter { it.first === foundOwner }.map { it.second }
        } else {
            if (!missReported) {
                missReported = true
                MediaRuntime.log("content_download_anchor: " + contentDownloadMissSummary(title, visited, owners, nodeTotal, capped, texts))
            }
            if (lostSince == 0L) lostSince = now
            if (node != null && now - lostSince >= CONTENT_DOWNLOAD_ANCHOR_GRACE_MS) clear()
        }
    }

    fun bounds(rootScreenPosition: IntArray): ContentDownloadBounds? {
        val view = owner?.takeIf { it.isAttachedToWindow } ?: return null
        val native = readRect(contracts.call("content-download-semantics-bounds", node)) ?: return null
        val local = glyphs ?: return null
        val anchored = contentDownloadComposeGlyphBounds(native, local) ?: return null
        val position = IntArray(2); view.getLocationOnScreen(position)
        val dx = (position[0] - rootScreenPosition[0]).toFloat(); val dy = (position[1] - rootScreenPosition[1]).toFloat()
        val titleBounds = anchored.translated(dx, dy)
        val badge = badgeNodes.mapNotNull { readRect(contracts.call("content-download-semantics-bounds", it)) }
            .let { contentDownloadBadgeAdjust(anchored, it) }
        val placed = badge?.translated(dx, dy) ?: titleBounds
        unadjusted = if (badge != null) titleBounds else null
        if (badge != null && !badgeReported) {
            badgeReported = true
            MediaRuntime.log("content_download_anchor: badge-adjusted placement source=node title=$titleBounds placed=$placed")
        }
        if (!geometryReported) {
            geometryReported = true
            MediaRuntime.log("content_download_anchor: native=$native glyph=$local placed=$placed")
        }
        return placed
    }

    internal fun semanticsProperties(node: Any): Map<String, Any?> {
        val config = contracts.call("content-download-semantics-config", node) as? Iterable<*> ?: return emptyMap()
        val result = LinkedHashMap<String, Any?>()
        config.take(64).forEach { item ->
            val entry = item as? Map.Entry<*, *> ?: return@forEach
            val name = contracts.field("content-download-semantics-key-name", entry.key) as? String ?: return@forEach
            if (name in setOf("Text", "Heading", "GetTextLayoutResult", "ContentDescription")) result[name] = entry.value
        }
        return result
    }

    private fun semanticsDescriptions(properties: Map<String, Any?>): List<String> =
        (properties["ContentDescription"] as? List<*>)?.filterIsInstance<String>().orEmpty()

    internal fun glyphBounds(action: Any?, textLength: Int): ContentDownloadBounds? = glyphGeometry(action, textLength)?.lastLine

    internal fun glyphGeometry(action: Any?, textLength: Int): ContentDownloadGlyphGeometry? {
        if (textLength !in 1..512) return null
        val function = contracts.field("content-download-semantics-action", action) ?: return null
        val layouts = ArrayList<Any>()
        if (contracts.call("content-download-function-invoke", function, layouts) != true) return null
        val layout = layouts.firstOrNull() ?: return null
        var result: ContentDownloadBounds? = null
        var full: ContentDownloadBounds? = null
        for (index in 0 until textLength) {
            val box = readRect(contracts.call("content-download-glyph-bounds", layout, index))?.takeIf(ContentDownloadBounds::valid) ?: continue
            full = full?.let { ContentDownloadBounds(min(it.left, box.left), min(it.top, box.top), max(it.right, box.right), max(it.bottom, box.bottom)) } ?: box
            val old = result
            result = when {
                old == null || box.top > old.top + 2 -> box
                kotlin.math.abs(box.top - old.top) <= 2 -> ContentDownloadBounds(min(old.left, box.left), min(old.top, box.top), max(old.right, box.right), max(old.bottom, box.bottom))
                else -> old
            }
        }
        return ContentDownloadGlyphGeometry(full ?: return null, result ?: return null)
    }

    private fun readRect(value: Any?): ContentDownloadBounds? {
        if (value == null) return null
        fun coordinate(name: String) = (contracts.field("content-download-rect-$name", value) as? Number)?.toFloat()
        return ContentDownloadBounds(coordinate("left") ?: return null, coordinate("top") ?: return null,
            coordinate("right") ?: return null, coordinate("bottom") ?: return null)
    }
}

private fun textViewGlyphBounds(title: TextView, rootPosition: IntArray): ContentDownloadBounds? {
    val layout = title.layout ?: return null
    if (layout.lineCount == 0 || !title.isShown) return null
    val position = IntArray(2); title.getLocationOnScreen(position)
    val line = layout.lineCount - 1
    val left = position[0] - rootPosition[0] + title.compoundPaddingLeft + layout.getLineLeft(line)
    val top = (position[1] - rootPosition[1] + title.extendedPaddingTop + layout.getLineTop(line)).toFloat()
    // A badge set as a compound drawable sits after the text; keep the button beyond it.
    val rtl = title.layoutDirection == View.LAYOUT_DIRECTION_RTL
    val badge = title.compoundDrawablesRelative.let { if (rtl) it[0] else it[2] }
    val badgeWidth = if (badge != null && !rtl) badge.intrinsicWidth.coerceAtLeast(0) + title.compoundDrawablePadding else 0
    val result = ContentDownloadBounds(left, top, position[0] - rootPosition[0] + title.compoundPaddingLeft + layout.getLineRight(line) + badgeWidth,
        position[1] - rootPosition[1] + title.extendedPaddingTop + layout.getLineBottom(line).toFloat())
    val visible = Rect()
    if (!title.getGlobalVisibleRect(visible) || result.top + rootPosition[1] < visible.top || result.bottom + rootPosition[1] > visible.bottom) return null
    return result.takeIf(ContentDownloadBounds::valid)
}

private class ContentDownloadIcon(color: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    override fun draw(canvas: Canvas) {
        val edge = min(bounds.width(), bounds.height()).toFloat()
        if (edge <= 0) return
        paint.strokeWidth = max(1f, edge / 10)
        val x = bounds.exactCenterX(); val top = bounds.top + edge * .12f; val bottom = bounds.top + edge * .62f
        canvas.drawLine(x, top, x, bottom, paint)
        canvas.drawLine(x - edge * .24f, bottom - edge * .24f, x, bottom, paint)
        canvas.drawLine(x + edge * .24f, bottom - edge * .24f, x, bottom, paint)
        canvas.drawLine(x - edge * .32f, bounds.top + edge * .84f, x + edge * .32f, bounds.top + edge * .84f, paint)
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(filter: android.graphics.ColorFilter?) { paint.colorFilter = filter; invalidateSelf() }
    @Deprecated("Deprecated in Android; retained for Drawable compatibility")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

internal object ContentDownloadUi {
    private fun zh(language: String) = language.startsWith("zh", true)
    fun download(language: String) = if (zh(language)) "下载内容" else "Download content"
    fun unavailable(language: String) = if (zh(language)) "（不可用）" else " (unavailable)"
    fun pageNotReady(language: String) = if (zh(language)) "此页面的内容尚未载入，请稍后重试" else "This page's content has not loaded yet; try again shortly"
    fun preparing(language: String) = if (zh(language)) "正在准备导出…" else "Preparing export…"
    fun cancel(language: String) = if (zh(language)) "取消" else "Cancel"
    fun close(language: String) = if (zh(language)) "关闭" else "Close"
    fun saved(language: String, file: ContentExportFile): String {
        val location = if (file.location == ContentExportLocation.SHARED_DOWNLOADS) "Download/AM++" else if (zh(language)) "应用下载目录" else "the app's downloads folder"
        return if (zh(language)) "已保存到 $location：${file.displayName}" else "Saved to $location: ${file.displayName}"
    }
    fun progress(language: String, state: ContentExportStatus): String {
        val verb = when (state.phase) {
            ContentExportPhase.QUEUED -> if (zh(language)) "等待导出…" else "Waiting to export…"
            ContentExportPhase.DOWNLOADING -> if (zh(language)) "正在下载…" else "Downloading…"
            ContentExportPhase.SAVING, ContentExportPhase.PUBLISHING -> if (zh(language)) "正在保存…" else "Saving…"
            ContentExportPhase.CANCELLING -> if (zh(language)) "正在取消…" else "Cancelling…"
            else -> preparing(language)
        }
        return if (state.bytesCopied > 0) "$verb\n${state.bytesCopied / 1024} KiB" else verb
    }
}

private val CONTENT_DOWNLOAD_METHODS = listOf(
    "media-entity-get-title-method", "media-entity-get-attributes-method",
    "entity-id", "entity-type", "attrs-artwork", "attrs-editorial-artwork", "attrs-biography", "attrs-notes", "attrs-videos",
    "artwork-url", "artwork-width", "artwork-height", "notes-standard", "notes-short", "video-files", "video-hls", "file-url", "file-width", "file-height",
    "artist-model", "artist-data", "artist-set-data", "artist-create-header", "album-model", "album-data", "album-set-data", "fragment-view", "fragment-activity",
    "base-destroy-view", "lyrics-parser", "artist-create-view", "album-create-view", "lyrics-online-loader", "lyrics-file-loader",
    "compose-album-model", "compose-album-data", "compose-album-set-data", "compose-album-create-view",
    "compose-semantics", "semantics-root", "semantics-config", "semantics-children", "semantics-bounds", "annotated-text", "function-invoke", "glyph-bounds",
).map { if (it.startsWith("media-entity-")) it else "content-download-$it" }

private val CONTENT_DOWNLOAD_FIELDS = listOf("loader-song-id", "loader-resume-label", "semantics-key-name", "semantics-action", "semantics-id", "rect-left", "rect-top", "rect-right", "rect-bottom")
    .map { "content-download-$it" }

internal const val CONTENT_DOWNLOAD_ANCHOR_GRACE_MS = 1500L
/** Album pages keep the last good placement for as long as the same page stays current (reset on page change / close). */
internal const val CONTENT_DOWNLOAD_HOLD_WHILE_PAGE_LIVE = Long.MAX_VALUE

/**
 * Tolerant title match for semantics nodes that merge the title with descriptors ("Title, Explicit, Artist", "Title\nArtist"):
 * the node text must start with the full title followed by a non-alphanumeric boundary. Weaker than [contentDownloadTitleMatch],
 * so it is only used when no strict candidate exists.
 */
internal fun contentDownloadTitleLooseMatch(plain: String, title: String): Boolean {
    val t = normalizeContentDownloadTitle(title)
    if (t.isEmpty()) return false
    val n = normalizeContentDownloadTitle(plain)
    if (n.length <= t.length || !n.startsWith(t)) return false
    val next = n[t.length]
    return !next.isLetterOrDigit() || !t.last().isLetterOrDigit()
}

/** One-line summary of why no anchor matched: scan counters plus the closest text candidates (truncated). */
internal fun contentDownloadMissSummary(title: String, views: Int, owners: Int, nodes: Int, capped: Boolean, texts: List<String>): String {
    fun cut(v: String) = normalizeContentDownloadTitle(v).replace('\n', ' ').let { if (it.length > 48) it.take(48) + "…" else it }
    val t = normalizeContentDownloadTitle(title)
    val ranked = texts.distinct().sortedByDescending { n ->
        val x = normalizeContentDownloadTitle(n)
        when { x.contains(t) -> 3; t.isNotEmpty() && x.take(4).equals(t.take(4), true) -> 2; else -> 0 }
    }.take(5)
    return "anchor_scan_miss title=\"${cut(title)}\" views=$views semanticsOwners=$owners nodes=$nodes capped=$capped texts=${ranked.map(::cut)}"
}

internal data class ContentDownloadAnchorCandidate(val match: Int, val heading: Boolean, val height: Float)

/**
 * Title-level ranking: a heading beats plain text, then the larger glyph height (page title is bigger than any track row),
 * and only then the exact-vs-badge match. A same-named track row (smaller, not a heading) can therefore not hijack the title.
 */
internal fun contentDownloadAnchorBetter(candidate: ContentDownloadAnchorCandidate, best: ContentDownloadAnchorCandidate?): Boolean {
    if (best == null) return true
    if (candidate.heading != best.heading) return candidate.heading
    if (kotlin.math.abs(candidate.height - best.height) > 1f) return candidate.height > best.height
    return candidate.match > best.match
}

internal data class ContentDownloadHoldResult(val placement: ContentDownloadButtonPlacement?, val transition: String?)

/** Keeps the last good button placement through short anchor gaps; hides only after sustained absence. */
internal class ContentDownloadPlacementHold(private val graceMs: Long = CONTENT_DOWNLOAD_ANCHOR_GRACE_MS) {
    private var last: ContentDownloadButtonPlacement? = null
    private var lastGoodAt = 0L
    private var holding = false
    private var visible = false

    fun reset(): String? {
        val was = visible
        last = null; holding = false; visible = false
        return if (was) "hide reason=page_changed" else null
    }

    fun update(now: Long, placement: ContentDownloadButtonPlacement?, missReason: String): ContentDownloadHoldResult {
        if (placement != null) {
            val transition = when {
                !visible -> "show reason=anchor_found"
                holding -> "recover reason=anchor_reappeared"
                else -> null
            }
            last = placement; lastGoodAt = now; holding = false; visible = true
            return ContentDownloadHoldResult(placement, transition)
        }
        val kept = last
        if (kept != null && visible && now - lastGoodAt < graceMs) {
            val transition = if (!holding) "hold reason=$missReason" else null
            holding = true
            return ContentDownloadHoldResult(kept, transition)
        }
        val transition = if (visible) "hide reason=${missReason}_sustained" else null
        last = null; holding = false; visible = false
        return ContentDownloadHoldResult(null, transition)
    }
}

/** True for node text / content description that is only an Explicit/Clean badge ("E", "Explicit", placeholder glyph...). */
internal fun contentDownloadIsBadgeText(value: String): Boolean {
    val n = normalizeContentDownloadTitle(value).filter { it.code != 0xFFFC && it.code != 0xFFFD && it.code !in 0x200b..0x200f && it.code != 0x2060 && it.code != 0xFEFF }
        .trim().trim('[', ']', '(', ')').trim().lowercase()
    return n.isNotEmpty() && n in CONTENT_DOWNLOAD_BADGE_TEXT
}

/**
 * Number of leading characters of [plain] to measure for the title line: the title plus trailing badge characters
 * (inline placeholder, whitespace, ", Explicit"-style suffix), so inline badge glyphs are inside the measured bounds.
 */
internal fun contentDownloadBadgeExtent(plain: String, title: String): Int {
    val t = normalizeContentDownloadTitle(title).length
    val n = normalizeContentDownloadTitle(plain)
    if (n.length <= t) return n.length.coerceAtLeast(1).coerceAtMost(plain.length.coerceAtLeast(1))
    val rest = n.substring(t)
    val suffix = rest.trimStart(',', ';', ' ', '\u00a0').trim()
    val firstLine = suffix.substringBefore('\n')
    val badgeOnly = rest.all { it.isWhitespace() || it.code == 0xFFFC || it.code in 0x200b..0x200f || it == ',' } || contentDownloadIsBadgeText(firstLine)
    return (if (badgeOnly) n.length else t).coerceIn(1, plain.length.coerceAtLeast(1))
}

/**
 * Extends [title] to the right edge of a separate badge node that sits right after it on the same line.
 * Returns null when no such node exists (placement stays on the title text).
 */
internal fun contentDownloadBadgeAdjust(title: ContentDownloadBounds, badges: List<ContentDownloadBounds>): ContentDownloadBounds? {
    val maxGap = title.height.coerceAtLeast(8f)
    val best = badges.filter { it.valid() }.filter { b ->
        val centerY = (b.top + b.bottom) / 2
        centerY >= title.top && centerY <= title.bottom && b.left >= title.right - 2f && b.left - title.right <= maxGap && b.right > title.right &&
            b.height <= title.height * 1.5f
    }.maxByOrNull { it.right } ?: return null
    return ContentDownloadBounds(title.left, title.top, best.right, title.bottom).takeIf(ContentDownloadBounds::valid)
}
