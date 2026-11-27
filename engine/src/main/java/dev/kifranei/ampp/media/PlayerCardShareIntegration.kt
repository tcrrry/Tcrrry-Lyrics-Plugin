package dev.kifranei.ampp.media

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.Toast
import java.util.IdentityHashMap

/** Independent share-card entry for Apple Music versions whose native card pipeline was removed. */
internal class PlayerCardShareIntegration(private val build: TargetBuild) : PlayerShareCardTarget {
    override fun install(): TargetCapabilityInstall {
        val method = PluginProfiles.find(build.packageName, build.versionName, build.versionCode)
            ?.let { PluginProfiles.method("player-controller-create-view") }
            ?: return TargetCapabilityInstall.Unsupported("No verified player-page contract for ${build.displayName}")
        val scope = PluginScope()
        val installed = IdentityHashMap<View, Boolean>()
        val app = MediaRuntime.context.application
        fun installOn(root: View) {
            if (installed.put(root, true) != null) return
            val artworkId = root.resources.getIdentifier("artwork_image", "id", build.packageName)
            val artwork = if (artworkId != 0) root.findViewById<View>(artworkId) else null
            val listener = View.OnLongClickListener {
                shareCard(root, app)
                true
            }
            artwork?.let { it.isLongClickable = true; it.setOnLongClickListener(listener) }
                ?: run { root.isLongClickable = true; root.setOnLongClickListener(listener) }
            MediaRuntime.log("player card share entry attached to ${artwork?.javaClass?.simpleName ?: root.javaClass.simpleName}")
        }
        try {
            check(MediaRuntime.observeMethod(method, object : PluginMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    (param.result as? View)?.let { root -> root.post { installOn(root) } }
                }
            }, scope))
            runCatching {
                val getView = PluginProfiles.method("player-fragment-view")
                check(MediaRuntime.observeMethod(getView, object : PluginMethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        (param.result as? View)?.let { root -> root.post { installOn(root) } }
                    }
                }, scope))
            }
            scope.onClose { installed.clear() }
            scope.activate()
            return TargetCapabilityInstall.Active("Long-press player artwork to share an AM++ card")
        } catch (error: Throwable) {
            scope.close()
            throw error
        }
    }

    private fun shareCard(root: View, context: android.content.Context) {
        runCatching {
            val width = root.width.coerceAtLeast(1)
            val height = root.height.coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            val name = "ampp-card-${System.currentTimeMillis()}.png"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                if (Build.VERSION.SDK_INT >= 29) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/AM++")
                }
            }
            val resolver = context.contentResolver
            val uri: Uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("unable to create shared card")
            resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                ?: error("unable to write shared card")
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(send, "分享卡片").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        }.onFailure {
            MediaRuntime.log("player card share failed", it)
            Toast.makeText(context, "分享卡片生成失败", Toast.LENGTH_SHORT).show()
        }
    }
}

internal interface PlayerShareCardTarget {
    fun install(): TargetCapabilityInstall
}
