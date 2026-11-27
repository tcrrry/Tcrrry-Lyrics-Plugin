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

/** Replace Apple Music's native song/lyrics share pages with the Android system share sheet. */
internal class NativeShareRedirectIntegration(private val build: TargetBuild, private val loader: ClassLoader) : PlayerShareCardTarget {
    override fun install(): TargetCapabilityInstall {
        val scope = PluginScope()
        try {
            val type = runCatching {
                loader.loadClass("com.apple.android.music.common.actionsheet.lyrics.ShareLyricsActionSheetFragment\$e")
            }.getOrNull() ?: return TargetCapabilityInstall.Unsupported("No lyrics share handler for ${build.displayName}")
            val method = runCatching {
                type.getDeclaredMethod("z", Int::class.javaPrimitiveType, View::class.java,
                    loader.loadClass("com.apple.android.music.model.CollectionItemView"))
            }.getOrNull() ?: return TargetCapabilityInstall.Unsupported("No lyrics share action for ${build.displayName}")
            check(MediaRuntime.observeMethod(method, object : PluginMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val clicked = param.args.getOrNull(1) as? View ?: return
                    redirect(clicked.rootView)
                    param.result = null
                }
            }, scope))
            scope.activate()
            return TargetCapabilityInstall.Active("Lyrics share uses the Android system share sheet")
        } catch (error: Throwable) {
            scope.close()
            throw error
        }
    }

    private fun redirect(view: View) {
        if (view.getTag(0x0A11CE01) == true) return
        view.setTag(0x0A11CE01, true)
        runCatching {
            val artworkId = view.resources.getIdentifier("artwork_image", "id", build.packageName)
            val artwork = if (artworkId != 0) view.rootView.findViewById<View>(artworkId) else null
            val source = artwork ?: view
            val bitmap = Bitmap.createBitmap(source.width.coerceAtLeast(1), source.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            source.draw(Canvas(bitmap))
            val context = view.context
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "ampp-share-${System.currentTimeMillis()}.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                if (Build.VERSION.SDK_INT >= 29) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/AM++")
                }
            }
            val uri: Uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("unable to create share image")
            resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                ?: error("unable to write share image")
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, "分享").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure {
            MediaRuntime.log("native share redirect failed", it)
            Toast.makeText(view.context, "分享页打开失败", Toast.LENGTH_SHORT).show()
        }
    }
}
