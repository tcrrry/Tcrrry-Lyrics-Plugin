package dev.kifranei.ampp.media

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.widget.ImageView
import java.util.WeakHashMap

/** Native detachment recycles its blur cache but leaves shaders referring to that cache. */
internal class PlayerBackgroundRecovery(private val build: TargetBuild) {
    fun install(): TargetCapabilityInstall {
        val profile = PluginProfiles.find(build.packageName, build.versionName, build.versionCode)
            ?: return TargetCapabilityInstall.Unsupported("No verified player background")
        val names = profile.document.getJSONObject("playerRecovery")
        val detach = PluginProfiles.method("player-background-detach")
        val attach = View::class.java.getDeclaredMethod("onAttachedToWindow").apply { isAccessible = true }
        val imageChanged = ImageView::class.java.getDeclaredMethod("setImageDrawable", android.graphics.drawable.Drawable::class.java)
        val setArtwork = PluginProfiles.method("player-background-artwork")
        val getView = PluginProfiles.method("player-fragment-view")
        val source = PluginProfiles.field("player-background-source")
        val queued = PluginProfiles.field("player-background-queued")
        val derived = PluginProfiles.field("player-background-derived")
        val shader = PluginProfiles.field("player-background-shader")
        val previousShader = PluginProfiles.field("player-background-previous-shader")
        val paint = PluginProfiles.field("player-background-paint")
        val previousPaint = PluginProfiles.field("player-background-previous-paint")
        val fade = PluginProfiles.field("player-background-fade")
        val pending = WeakHashMap<View, Boolean>()
        val scope = PluginScope()
        fun usable(bitmap: Bitmap?) = bitmap?.takeUnless(Bitmap::isRecycled)
        fun clear(view: View) {
            shader.set(view, null); previousShader.set(view, null); derived.set(view, null)
            (paint.get(view) as Paint).shader = null
            (previousPaint.get(view) as Paint).shader = null
        }
        fun restore(view: View, root: View?) {
            if (!view.isAttachedToWindow || view.width <= 0 || view.height <= 0) return
            // The foreground bitmap belongs to the current song even if an old loader's
            // cleared callback has emptied the background source after recreation.
            val image = root?.findViewById<ImageView>(root.resources.getIdentifier(names.getString("imageId"), "id", build.packageName))
            val foreground = (image?.drawable as? BitmapDrawable)?.bitmap
            val current = usable(source.get(view) as? Bitmap)
            fun picture(bitmap: Bitmap?) = usable(bitmap)?.takeIf { it.width > 1 && it.height > 1 }
            val bitmap = picture(queued.get(view) as? Bitmap) ?: picture(current) ?: picture(foreground) ?: return
            val invalidSource = current == null || current.width <= 1 || current.height <= 1
            val recycled = (derived.get(view) as? Bitmap)?.isRecycled == true
            if (pending[view] != true && !invalidSource && !recycled) return
            // An animator paused by the old lifecycle can otherwise queue the bitmap
            // indefinitely. Re-enter the native pipeline with no recycled crossfade shader.
            (fade.get(view) as ValueAnimator).cancel()
            clear(view)
            setArtwork.invoke(view, bitmap)
            pending.remove(view)
            MediaRuntime.log("player_background: rebuilt native blur after lifecycle change")
        }
        try {
            check(MediaRuntime.observeMethod(detach, object : PluginMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null) return
                    val view = param.thisObject as? View ?: return
                    clear(view)
                    pending[view] = true
                }
            }, scope))
            check(MediaRuntime.observeMethod(attach, object : PluginMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    if (param.throwable != null || !detach.declaringClass.isInstance(view) || pending[view] != true) return
                    view.post { if (scope.isActive) restore(view, view.rootView) }
                }
            }, scope))
            check(MediaRuntime.observeMethod(PluginProfiles.method("player-controller-resume"), object : PluginMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null) return
                    val root = getView.invoke(param.thisObject) as? View ?: return
                    val backgroundId = root.resources.getIdentifier(names.getString("backgroundId"), "id", build.packageName)
                    val view = root.findViewById<View>(backgroundId) ?: return
                    root.post { if (scope.isActive) restore(view, root) }
                }
            }, scope))
            check(MediaRuntime.observeMethod(imageChanged, object : PluginMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null) return
                    val image = param.thisObject as? ImageView ?: return
                    if (image.id != image.resources.getIdentifier(names.getString("imageId"), "id", build.packageName)) return
                    val root = image.rootView
                    val backgroundId = root.resources.getIdentifier(names.getString("backgroundId"), "id", build.packageName)
                    val view = root.findViewById<View>(backgroundId) ?: return
                    image.post { if (scope.isActive) restore(view, root) }
                }
            }, scope))
            scope.onClose { pending.clear() }
            scope.activate()
            return TargetCapabilityInstall.Active("Native player background lifecycle recovery installed")
        } catch (error: Throwable) { scope.close(); throw error }
    }
}
