package dev.kifranei.ampp.media

import android.os.Looper
import android.animation.Animator
import android.view.View
import android.view.ViewGroup
import java.util.WeakHashMap
import java.lang.ref.WeakReference

/**
 * The native square container owns the big cover size; a transition-sized child does not.
 * The former hook on the artwork resize setter (player-artwork-resize, fragment.l.E1(boolean, Size)) was
 * removed: it collided with another registration on the same target. Only view lifecycle observers remain.
 */
internal class PlayerArtworkRecovery(private val build: TargetBuild) {
    fun install(): TargetCapabilityInstall {
        val profile = PluginProfiles.find(build.packageName, build.versionName, build.versionCode)
            ?: return TargetCapabilityInstall.Unsupported("No verified artwork layout")
        val names = profile.document.getJSONObject("playerRecovery")
        val getView = PluginProfiles.method("player-fragment-view")
        val mainType = PluginProfiles.method("player-controller-create-view").declaringClass
        val videoMode = PluginProfiles.field("player-artwork-video-mode")
        val sizeAnimation = PluginProfiles.field("player-artwork-size-animation")
        val originals = WeakHashMap<View, Pair<Int, Int>>()
        val owners = WeakHashMap<View, WeakReference<Any>>()
        val scope = PluginScope()
        fun card(root: View): View? {
            val view = root.findViewById<View>(root.resources.getIdentifier(names.getString("cardId"), "id", build.packageName)) ?: return null
            val parent = view.parent as? ViewGroup ?: return null
            return view.takeIf { parent.id == root.resources.getIdentifier(names.getString("containerId"), "id", build.packageName) }
        }
        fun nativeSize(view: View) {
            val original = originals[view] ?: return
            val params = view.layoutParams ?: return
            if (params.width == ViewGroup.LayoutParams.MATCH_PARENT && params.height == ViewGroup.LayoutParams.MATCH_PARENT) {
                params.width = original.first; params.height = original.second; view.layoutParams = params
            }
        }
        fun prepare(root: View, pane: Any? = null) {
            if (Looper.myLooper() != Looper.getMainLooper()) return
            val card = card(root) ?: return
            if (pane != null && videoMode.declaringClass.isInstance(pane)) owners[card] = WeakReference(pane)
            val owner = owners[card]?.get()
            if (owner != null && (videoMode.getBoolean(owner) || (sizeAnimation.get(owner) as? Animator)?.isRunning == true)) {
                nativeSize(card); return
            }
            val params = card.layoutParams ?: return
            if (params.width != ViewGroup.LayoutParams.WRAP_CONTENT || params.height != ViewGroup.LayoutParams.WRAP_CONTENT) return
            originals.putIfAbsent(card, params.width to params.height)
            // Preserve G0.P's absolute play/pause scale. Match the native artwork slot,
            // including its density, before shared elements are captured.
            params.width = ViewGroup.LayoutParams.MATCH_PARENT
            params.height = ViewGroup.LayoutParams.MATCH_PARENT
            card.layoutParams = params
            MediaRuntime.log("player_artwork: cover measurement follows native container; playback scale preserved")
        }
        try {
            check(MediaRuntime.observeMethod(PluginProfiles.method("player-pane-view-created"), object : PluginMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable == null) (param.args.firstOrNull() as? View)?.let { prepare(it, param.thisObject) }
                }
            }, scope))
            check(MediaRuntime.observeMethod(PluginProfiles.method("player-controller-create-view"), object : PluginMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable == null) (param.result as? View)?.let { root ->
                        prepare(root)
                    }
                }
            }, scope))
            check(MediaRuntime.observeMethod(PluginProfiles.method("player-controller-resume"), object : PluginMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable == null && mainType.isInstance(param.thisObject))
                        (getView.invoke(param.thisObject) as? View)?.let { root ->
                            prepare(root)
                        }
                }
            }, scope))
            scope.onClose {
                originals.keys.toList().forEach(::nativeSize)
                originals.clear(); owners.clear()
            }
            scope.activate()
            return TargetCapabilityInstall.Active("Native artwork slot measurement restored")
        } catch (error: Throwable) { scope.close(); throw error }
    }
}
