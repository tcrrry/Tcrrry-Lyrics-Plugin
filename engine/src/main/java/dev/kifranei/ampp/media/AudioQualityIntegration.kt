package dev.kifranei.ampp.media

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.widget.ImageView
import android.widget.TextView

/** Match only the native quality disclosure's four sibling views. */
internal class AudioQualityIntegration(
    private val application: Application,
    private val build: TargetBuild,
) : AudioQualityDialogTarget {
    override fun install(present: (AudioQualityDialogSurface) -> Boolean): TargetCapabilityInstall {
        val names = PluginProfiles.find(build.packageName, build.versionName, build.versionCode)
            ?.document?.optJSONObject("audioQualityDialog")
            ?: return TargetCapabilityInstall.Unsupported("No verified audio-quality dialog contract for ${build.displayName}")
        fun id(name: String) = application.resources.getIdentifier(names.getString(name), "id", build.packageName)
        val badgeId = id("badgeId")
        val titleId = id("titleId")
        val encodingId = id("encodingId")
        val sourceId = id("sourceId")
        check(listOf(badgeId, titleId, encodingId, sourceId).all { it != 0 })
        val scope = PluginScope()
        val bypass = ThreadLocal<Dialog?>()
        val bindingType = application.classLoader.loadClass(names.getString("bindingClass"))
        val getBinding = bindingType.getDeclaredMethod(names.getString("bindingLookupMethod"), View::class.java)
        val executeBindings = bindingType.getDeclaredMethod(names.getString("bindingExecuteMethod"))
        fun presentNative(dialog: Dialog, show: () -> Unit): Boolean {
            val encoding = dialog.findViewById<TextView>(encodingId) ?: return false
            MediaRuntime.log("audio_quality_glass: native dialog identified (${dialog.javaClass.name})")
            val badge = dialog.findViewById<ImageView>(badgeId) ?: return false
            val title = dialog.findViewById<TextView>(titleId) ?: return false
            val source = dialog.findViewById<TextView>(sourceId) ?: return false
            if (badge.parent == null || listOf(title, encoding, source).any { it.parent !== badge.parent }) return false
            val activity = activity(dialog.context) ?: return false
            if (activity.packageName != build.packageName) return false
            val positive = dialog.findViewById<TextView>(android.R.id.button1)?.takeIf { it.visibility == View.VISIBLE } ?: return false
            val negative = dialog.findViewById<TextView>(android.R.id.button2)?.takeIf { it.visibility == View.VISIBLE } ?: return false
            val nativeIcon = badge.drawable ?: return false
            // SVG/PictureDrawable badges do not expose ConstantState. Snapshot those
            // without retaining or changing the native drawable's bounds/tint.
            val icon = nativeIcon.constantState?.newDrawable(application.resources)?.mutate() ?: run {
                val width = nativeIcon.intrinsicWidth.takeIf { it > 0 } ?: 256
                val height = nativeIcon.intrinsicHeight.takeIf { it > 0 } ?: 160
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val bounds = Rect(nativeIcon.bounds)
                try {
                    nativeIcon.setBounds(0, 0, width, height)
                    nativeIcon.draw(Canvas(bitmap))
                } finally { nativeIcon.bounds = bounds }
                BitmapDrawable(application.resources, bitmap)
            }
            return runCatching {
                if (present(AudioQualityDialogSurface(activity, dialog, icon, title.text.toString(),
                    encoding.text.toString(), if (source.visibility == View.VISIBLE) source.text.toString() else "", negative.text.toString(), positive.text.toString(),
                    { negative.performClick() }, { positive.performClick() }, show))) {
                    MediaRuntime.log("audio_quality_glass: native disclosure presentation replaced")
                    true
                } else false
            }.onFailure { MediaRuntime.log("audio quality dialog presentation failed", it) }.getOrDefault(false)
        }
        try {
            val dialogType = application.classLoader.loadClass(names.getString("dialogClass"))
            check(MediaRuntime.hookMethod(Dialog::class.java.getDeclaredMethod("show"), object : PluginMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val dialog = param.thisObject as? Dialog ?: return
                    if (bypass.get() === dialog || dialog.isShowing || !dialogType.isInstance(dialog) ||
                        activity(dialog.context)?.packageName != build.packageName) return
                    // Initialize and bind while detached. The native layout never enters
                    // WindowManager, which would otherwise capture it for the enter animation.
                    dialog.create()
                    val badge = dialog.findViewById<View>(badgeId) ?: return
                    if (dialog.findViewById<View>(encodingId) == null) return
                    val replaced = runCatching {
                        val binding = getBinding.invoke(null, badge.parent as? View ?: return@runCatching false)
                            ?: return@runCatching false
                        executeBindings.invoke(binding)
                        presentNative(dialog) {
                            bypass.set(dialog)
                            try { dialog.show() } finally { bypass.remove() }
                        }
                    }.onFailure { MediaRuntime.log("audio quality dialog preparation failed", it) }.getOrDefault(false)
                    if (replaced) {
                        param.result = null
                        MediaRuntime.log("audio_quality_glass: native show deferred until glass content is ready")
                    }
                }
            }, scope))
            scope.activate()
            return TargetCapabilityInstall.Active("Native audio-quality disclosure presentation hook installed")
        } catch (error: Throwable) { scope.close(); throw error }
    }

    private fun activity(context: Context): Activity? {
        var current = context
        repeat(16) {
            if (current is Activity) return current
            val next = (current as? ContextWrapper)?.baseContext ?: return null
            if (next === current) return null
            current = next
        }
        return null
    }
}
