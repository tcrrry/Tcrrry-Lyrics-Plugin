package dev.kifranei.ampp.media

import android.content.ClipData
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

internal class CardSharingFeature {
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val worker by lazy { Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "AM++ card image export").apply { isDaemon = true }
    } }

    fun install(target: CardSharingTarget): TargetCapabilityInstall {
        if (Build.VERSION.SDK_INT < 29) return TargetCapabilityInstall.Unsupported("卡片图片保存需要 Android 10+")
        MediaRuntime.context.onClose { worker.shutdownNow() }
        return target.install(::export)
    }

    private fun export(image: NativeShareImage, save: Boolean) {
        worker.execute {
            runCatching {
                val bitmap = NativeShareImageComposer.compose(image)
                try {
                    if (save) save(image, bitmap) else share(image, bitmap)
                } finally { bitmap.recycle() }
            }.onFailure { reportFailure(image, it) }
        }
    }

    private fun save(image: NativeShareImage, bitmap: Bitmap) {
        val resolver = image.activity.applicationContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "AMpp-${image.kind.filenameTag}-${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/AM++")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val destination = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        try {
            checkNotNull(resolver.openOutputStream(destination)).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
            check(resolver.update(destination, ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }, null, null) == 1)
        } catch (error: Throwable) { resolver.delete(destination, null, null); throw error }
        main.post { Toast.makeText(image.activity.applicationContext, image.kind.savedMessage(language(image)), Toast.LENGTH_SHORT).show() }
        MediaRuntime.log("card_sharing: native background and ${image.kind.filenameTag} card saved")
    }

    private fun share(image: NativeShareImage, bitmap: Bitmap) {
        val folder = File(checkNotNull(image.activity.getExternalFilesDir(Environment.DIRECTORY_PICTURES)), "ampp-card-share")
        check(folder.isDirectory || folder.mkdirs())
        val file = File(folder, "${image.kind.filenameTag}-${UUID.randomUUID()}.png")
        try {
            file.outputStream().use { output -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) }
        } catch (error: Throwable) { file.delete(); throw error }
        main.post {
            if (image.activity.isFinishing || image.activity.isDestroyed) return@post
            runCatching {
                val uri = image.shareUri(file)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, image.title)
                    clipData = ClipData.newRawUri("${image.kind.filenameTag} card", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                image.activity.startActivity(Intent.createChooser(send, image.kind.shareTitle(language(image)))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                MediaRuntime.log("card_sharing: native background and ${image.kind.filenameTag} card delivered to system sharing")
            }.onFailure { reportFailure(image, it) }
        }
    }

    private fun language(image: NativeShareImage) = image.activity.resources.configuration.locales[0].language

    private fun reportFailure(image: NativeShareImage, error: Throwable) {
        MediaRuntime.log("native ${image.kind.filenameTag} card export failed", error)
        main.post { Toast.makeText(image.activity.applicationContext, image.kind.failureMessage(language(image)), Toast.LENGTH_SHORT).show() }
    }
}
