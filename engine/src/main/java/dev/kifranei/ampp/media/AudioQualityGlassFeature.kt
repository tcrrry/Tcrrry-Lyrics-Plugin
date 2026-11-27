package dev.kifranei.ampp.media

import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import kotlin.math.roundToInt

internal class AudioQualityGlassFeature {

    fun install(target: AudioQualityDialogTarget): TargetCapabilityInstall {
        if (Build.VERSION.SDK_INT < 33) return TargetCapabilityInstall.Unsupported("完整玻璃折射需要 Android 13+")
        return target.install(::present)
    }

    private fun present(surface: AudioQualityDialogSurface): Boolean {
        val activity = surface.activity
        val dialog = surface.dialog
        val window = dialog.window ?: return false
        if (activity.isFinishing || activity.isDestroyed || dialog.isShowing) return false
        val moduleContext = MediaRuntime.glassContext(activity)
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).roundToInt()
        val width = minOf(dp(360), activity.window.decorView.width - dp(32))
        val height = minOf(dp(if (surface.source.isBlank()) 285 else 355), activity.window.decorView.height - dp(96))
        if (width <= 0 || height <= 0) return false
        val frame = FrameLayout(moduleContext).apply { setPadding(dp(10), dp(10), dp(10), dp(10)) }
        val glass = PluginGlassHostView(moduleContext, bleedDp = 0).apply {
            // Match the Compose RoundedCornerShape(32.dp) so host highlight/shadow cannot square the corners.
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    val r = 32f * density
                    outline.setRoundRect(0, 0, view.width, view.height, r)
                }
            }
            clipToOutline = true
        }
        frame.addView(glass, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // Same live PixelCopy path as the more menu so animated player chrome keeps sampling.
        val backdrop = DialogWindowBackdrop(activity, glass, live = true)
        var tree: ViewTreeObserver? = null
        val preDraw = ViewTreeObserver.OnPreDrawListener {
            backdrop.refreshPosition()
            true
        }
        glass.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                tree = view.viewTreeObserver.also { it.addOnPreDrawListener(preDraw) }
            }
            override fun onViewDetachedFromWindow(view: View) {
                tree?.takeIf { it.isAlive }?.removeOnPreDrawListener(preDraw)
                tree = null
                backdrop.close()
            }
        })
        val nativeContent = window.decorView.findViewById<ViewGroup>(android.R.id.content)
        val nativeChildren = (0 until nativeContent.childCount).map(nativeContent::getChildAt)
        val attributes = android.view.WindowManager.LayoutParams().apply { copyFrom(window.attributes) }
        val background = window.decorView.background
        val settingsLabel = surface.settingsLabel.toString()
        glass.content { GlassAudioQualityCard(backdrop, surface.badge, surface.title.toString(), surface.encoding.toString(),
            surface.source.toString(), settingsLabel, surface.doneLabel.toString(), surface.openSettings, surface.done) }
        fun restore() {
            nativeContent.removeAllViews()
            nativeChildren.forEach { child -> (child.parent as? ViewGroup)?.removeView(child); nativeContent.addView(child) }
            window.setBackgroundDrawable(background)
            window.attributes = attributes
            backdrop.close()
            if (!activity.isFinishing && !activity.isDestroyed && !dialog.isShowing) surface.show()
        }
        try {
            backdrop.capture { ready ->
                if (activity.isFinishing || activity.isDestroyed) { backdrop.close(); return@capture }
                if (!ready) { restore(); return@capture }
                runCatching {
                    window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                    window.setContentView(frame)
                    window.setLayout(width, height)
                    window.setDimAmount(.18f)
                    window.setWindowAnimations(0)
                    surface.show()
                    MediaRuntime.log("audio_quality_glass: first window shown with glass content")
                }.onFailure { restore(); MediaRuntime.log("audio quality glass popup failed", it) }
            }
        } catch (error: Throwable) { restore(); throw error }
        return true
    }
}
