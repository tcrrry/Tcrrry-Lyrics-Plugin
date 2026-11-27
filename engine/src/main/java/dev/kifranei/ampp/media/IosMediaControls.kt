package dev.kifranei.ampp.media

import android.app.Application
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import java.lang.reflect.Method
import android.os.Handler
import android.os.Looper
import java.util.IdentityHashMap
import java.util.WeakHashMap

/** Keep Apple's typed DataBinding view; replace its indicator and picker contracts. */
internal class IosMediaControls(
    private val application: Application,
    private val loader: ClassLoader,
    private val build: TargetBuild,
) : PlayerAudioOutputTarget {
    private var result: TargetCapabilityInstall? = null

    @Synchronized override fun install(): TargetCapabilityInstall {
        result?.let { return it }
        val names = PluginProfiles.find(build.packageName, build.versionName, build.versionCode)
            ?.document?.optJSONObject("audioOutput")
            ?: return TargetCapabilityInstall.Unsupported("No verified player audio-output contract for ${build.displayName}")
        val scope = PluginScope()
        val labels = IdentityHashMap<View, AudioOutputDeviceLabel>()
        val icons = IdentityHashMap<View, IosDeviceOutputDrawable>()
        val visibilityStates = WeakHashMap<View, AudioOutputButtonVisibility>()
        val reportedOverrides = WeakHashMap<View, Boolean>()
        val pendingCaptions = WeakHashMap<View, Boolean>()
        val discoveredRoots = WeakHashMap<View, Boolean>()
        val pendingRoots = WeakHashMap<View, Boolean>()
        val main = Handler(Looper.getMainLooper())
        scope.onClose {
            labels.values.toList().forEach(AudioOutputDeviceLabel::close); labels.clear(); icons.clear()
            visibilityStates.toMap().forEach { (view, state) -> state.restore { view.visibility = it } }
            visibilityStates.clear(); reportedOverrides.clear(); pendingCaptions.clear()
            discoveredRoots.clear(); pendingRoots.clear(); main.removeCallbacksAndMessages(null)
        }
        return try {
            val type = loader.loadClass(names.getString("buttonClass"))
            val indicator = method(type, "setRemoteIndicatorDrawableInternal", Drawable::class.java)
            val attached = method(type, "onAttachedToWindow")
            val dialog = method(type, "showDialog")
            val click = method(type, "performClick")
            val description = method(type, "updateContentDescription")
            val visibility = PluginProfiles.method("audio-output-button-visibility")
            check(visibility.declaringClass.isAssignableFrom(type))
            val id = application.resources.getIdentifier(names.getString("buttonId"), "id", build.packageName)
            val lyricsId = application.resources.getIdentifier(names.getString("lyricsId"), "id", build.packageName)
            val playerRootId = application.resources.getIdentifier(names.getString("playerRootId"), "id", build.packageName)
            val shareplayId = application.resources.getIdentifier(names.getString("shareplayId"), "id", build.packageName)
            check(id != 0 && lyricsId != 0 && playerRootId != 0 && shareplayId != 0) { "Player route control resources missing" }
            val factory = IosDeviceOutputDrawable.Factory(MediaRuntime.context, application.resources)
            fun newIcon(view: View) = icons.getOrPut(view, factory::create)
            fun owns(value: Any?): Boolean {
                val view = value as? View ?: return false
                return type.isInstance(view) && view.id == id &&
                    (view.parent as? ViewGroup)?.findViewById<View>(lyricsId) != null
            }
            fun hook(member: Method, callback: PluginMethodHook) {
                check(MediaRuntime.hookMethod(member, callback, scope))
            }
            fun sharedSessionVisible(view: View) =
                (view.parent as? ViewGroup)?.findViewById<View>(shareplayId)?.visibility == View.VISIBLE
            fun visibilityState(view: View) = visibilityStates.getOrPut(view) { AudioOutputButtonVisibility(view.visibility) }
            fun synchronizeVisibility(view: View) {
                if (scope.isActive && owns(view)) {
                    visibilityState(view).synchronize(view.visibility, sharedSessionVisible(view)) { view.visibility = it }
                }
            }
            fun ensureCaption(view: View) {
                if (labels.containsKey(view) || pendingCaptions.containsKey(view)) return
                pendingCaptions[view] = true
                // Queue outside attachment traversal, including indicator updates on old pages.
                main.post {
                    pendingCaptions.remove(view)
                    if (scope.isActive && view.isAttachedToWindow && owns(view) && !labels.containsKey(view)) {
                        runCatching {
                            labels[view] = AudioOutputDeviceLabel(view, playerRootId,
                                updateIcon = { kind -> newIcon(view).kind = kind },
                                updatePresentation = { synchronizeVisibility(view) },
                                onDetached = { labels.remove(view); icons.remove(view) })
                            MediaRuntime.log("player_audio_output: device caption attached to player root")
                        }.onFailure { MediaRuntime.log("player_audio_output: device caption unavailable", it) }
                    }
                }
            }
            fun prepare(view: View) {
                indicator.invoke(view, newIcon(view))
                synchronizeVisibility(view)
                view.contentDescription = labels[view]?.description ?: "投放 · 音频输出"
                ensureCaption(view)
            }
            hook(visibility, object : PluginMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!owns(param.thisObject)) return
                    val view = param.thisObject as View
                    val requested = param.args[0] as Int
                    val effective = visibilityState(view).requested(requested, sharedSessionVisible(view))
                    param.args[0] = effective
                    if (requested != effective && effective == View.VISIBLE && !reportedOverrides.containsKey(view)) {
                        reportedOverrides[view] = true
                        MediaRuntime.log("player_audio_output: Cast visibility $requested overridden for system output")
                    }
                    ensureCaption(view)
                }
            })
            hook(indicator, object : PluginMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (owns(param.thisObject)) (param.thisObject as View).let { view ->
                        param.args[0] = newIcon(view)
                        synchronizeVisibility(view)
                        ensureCaption(view)
                    }
                }
            })
            hook(attached, object : PluginMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null || !owns(param.thisObject)) return
                    runCatching {
                        val view = param.thisObject as View
                        prepare(view)
                        MediaRuntime.log("player_audio_output: native route indicator replaced; visibility=${view.visibility}")
                    }.onFailure { MediaRuntime.log("player audio-output indicator failed", it) }
                }
            })
            val open = object : PluginMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!owns(param.thisObject)) return
                    runCatching { PlatformAudioOutputSwitcher.open((param.thisObject as View).context) }
                        .onFailure { MediaRuntime.log("player audio-output picker failed", it) }
                    param.result = true
                }
            }
            hook(dialog, open)
            hook(click, open)
            hook(description, object : PluginMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (owns(param.thisObject)) (param.thisObject as View).let { it.contentDescription = labels[it]?.description ?: "投放 · 音频输出" }
                }
            })
            val create = PluginProfiles.method("player-controller-create-view")
            val getView = PluginProfiles.method("player-fragment-view")
            fun discover(root: View): Int {
                var found = 0
                if (owns(root)) {
                    prepare(root)
                    found++
                }
                if (root is ViewGroup) for (index in 0 until root.childCount) found += discover(root.getChildAt(index))
                return found
            }
            fun discoverLater(root: View) {
                if (discoveredRoots.containsKey(root) || pendingRoots.containsKey(root)) return
                pendingRoots[root] = true
                main.post {
                    pendingRoots.remove(root)
                    if (scope.isActive) runCatching { if (discover(root) > 0) discoveredRoots[root] = true }
                        .onFailure { MediaRuntime.log("player_audio_output: existing page discovery failed", it) }
                }
            }
            val pages = object : PluginMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null || !create.declaringClass.isInstance(param.thisObject)) return
                    val root = param.result as? View ?: return
                    discoverLater(root)
                }
            }
            check(MediaRuntime.observeMethod(create, pages, scope))
            check(MediaRuntime.observeMethod(getView, pages, scope))
            // The cover fragment initializes Cast before any lyrics/queue pane is opened.
            // Its layout can already exist when the main fragment is discovered.
            check(MediaRuntime.observeMethod(PluginProfiles.method("audio-output-player-initialize"), object : PluginMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null) return
                    runCatching {
                        (param.thisObject?.let { getView.invoke(it) } as? View)?.let(::discoverLater)
                    }.onFailure { MediaRuntime.log("player_audio_output: initial player discovery failed", it) }
                }
            }, scope))
            check(MediaRuntime.observeMethod(PluginProfiles.method("player-controller-resume"), object : PluginMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null) return
                    (param.thisObject?.let { getView.invoke(it) } as? View)?.let { root ->
                        discoverLater(root)
                    }
                }
            }, scope))
            scope.activate()
            TargetCapabilityInstall.Active("Native route indicator and system output picker hooks installed")
                .also { result = it }
        } catch (error: Throwable) {
            scope.close()
            throw error
        }
    }

    private fun method(type: Class<*>, name: String, vararg args: Class<*>): Method =
        generateSequence(type as Class<*>?) { it.superclass }.firstNotNullOfOrNull {
            runCatching { it.getDeclaredMethod(name, *args).apply { isAccessible = true } }.getOrNull()
        } ?: throw NoSuchMethodException("${type.name}#$name")
}
