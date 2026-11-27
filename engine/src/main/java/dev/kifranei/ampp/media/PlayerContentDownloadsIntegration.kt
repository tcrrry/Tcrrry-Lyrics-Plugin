package dev.kifranei.ampp.media

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import java.util.WeakHashMap

/** Long-press fallback remains available when the player uses its original More menu. */
internal class PlayerContentDownloadsIntegration(private val build: TargetBuild, private val downloads: ContentDownloadsIntegration) {
    fun install(): TargetCapabilityInstall {
        val profile = PluginProfiles.find(build.packageName, build.versionName, build.versionCode)?.document
            ?: return TargetCapabilityInstall.Unsupported("No verified player TTML entry")
        val names = profile.getJSONObject("audioOutput")
        val create = PluginProfiles.method("player-controller-create-view")
        val getView = PluginProfiles.method("player-fragment-view")
        val getId = PluginProfiles.method("content-download-item-id")
        val getTitle = PluginProfiles.method("content-download-item-title")
        val converter = PluginProfiles.method("metadata-to-playback-item-method")
        val buttons = WeakHashMap<View, Boolean>()
        val scope = PluginScope()
        val main = Handler(Looper.getMainLooper())
        var current: Pair<Long, String>? = null
        fun attach(root: View) {
            val id = root.resources.getIdentifier(names.getString("lyricsId"), "id", build.packageName)
            fun visit(view: View) {
                if (view.id == id && !buttons.containsKey(view) && !view.isLongClickable) {
                    buttons[view] = view.isLongClickable
                    view.setOnLongClickListener {
                        val activity = activity(view.context) ?: return@setOnLongClickListener false
                        val song = current
                        if (song == null) Toast.makeText(activity, ContentDownloadUi.pageNotReady(activity.resources.configuration.locales[0].language), Toast.LENGTH_LONG).show()
                        else downloads.downloadTtml(activity, song.first, song.second)
                        true
                    }
                }
                if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
            visit(root)
        }
        val pages = PlayerLifecycleWork<Any>({ main.post(it) }, { main.removeCallbacks(it) }, { scope.isActive }) { owner ->
            (getView.invoke(owner) as? View)?.let(::attach)
        }
        val bootstrap = PlayerViewBootstrap<Any, View> { _, root -> attach(root) }
        try {
            check(MediaRuntime.observeMethod(PluginProfiles.method("player-metadata-publish-method"), object : PluginMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null) return
                    val item = converter.invoke(null, param.args.firstOrNull()) ?: return
                    val id = (getId.invoke(item) as? String)?.toLongOrNull()
                    val title = getTitle.invoke(item) as? String
                    current = if (id != null && id > 0 && !title.isNullOrBlank()) id to title else null
                }
            }, scope))
            val page = object : PluginMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable == null && create.declaringClass.isInstance(param.thisObject)) param.thisObject?.let(pages::request)
                }
            }
            check(MediaRuntime.observeMethod(create, object : PluginMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null) return
                    val owner = param.thisObject ?: return
                    val root = param.result as? View ?: return
                    bootstrap.created(owner, root)
                    pages.created(owner)
                }
            }, scope))
            check(MediaRuntime.observeMethod(getView, object : PluginMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null || !create.declaringClass.isInstance(param.thisObject) || Looper.myLooper() != Looper.getMainLooper()) return
                    val root = param.result as? View ?: return
                    param.thisObject?.let { bootstrap.observed(it, root) }
                }
            }, scope))
            check(MediaRuntime.observeMethod(PluginProfiles.method("player-controller-select-pane"), page, scope))
            check(MediaRuntime.observeMethod(PluginProfiles.method("player-controller-resume"), page, scope))
            check(MediaRuntime.observeMethod(PluginProfiles.method("player-controller-destroy-view"), object : PluginMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) { param.thisObject?.let { pages.destroyed(it); bootstrap.destroyed(it) } }
            }, scope))
            scope.onClose {
                pages.close()
                bootstrap.close()
                main.removeCallbacksAndMessages(null)
                buttons.forEach { (view, wasLongClickable) -> view.setOnLongClickListener(null); view.isLongClickable = wasLongClickable }
                buttons.clear(); current = null
            }
            scope.activate()
            return TargetCapabilityInstall.Active("Long-press lyrics to download song-bound original TTML")
        } catch (error: Throwable) { scope.close(); throw error }
    }
    private fun activity(context: Context): Activity? {
        var current = context
        while (current is ContextWrapper) {
            if (current is Activity) return current
            val next = current.baseContext
            if (next === current) return null
            current = next
        }
        return null
    }
}
