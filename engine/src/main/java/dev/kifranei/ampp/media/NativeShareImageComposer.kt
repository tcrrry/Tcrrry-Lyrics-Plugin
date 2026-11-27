package dev.kifranei.ampp.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

/** Flatten AM's original background and translucent card without redrawing their contents. */
internal object NativeShareImageComposer {
    fun compose(image: NativeShareImage): Bitmap {
        val resolver = image.activity.applicationContext.contentResolver
        val background = checkNotNull(resolver.openInputStream(image.backgroundUri)).use { input ->
            checkNotNull(BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply {
                inMutable = true
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })) { "Cannot decode native share background" }
        }
        try {
            val card = checkNotNull(resolver.openInputStream(image.cardUri)).use { input ->
                checkNotNull(BitmapFactory.decodeStream(input)) { "Cannot decode native share card" }
            }
            try {
                // Story shares place the original sticker in the center at 75% width.
                // Limit its height for long selections while preserving its aspect ratio.
                val scale = minOf(background.width * .75f / card.width, background.height * .8f / card.height)
                val width = card.width * scale
                val height = card.height * scale
                val left = (background.width - width) / 2f
                val top = (background.height - height) / 2f
                Canvas(background).drawBitmap(card, null, RectF(left, top, left + width, top + height),
                    Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                return background
            } finally { card.recycle() }
        } catch (error: Throwable) { background.recycle(); throw error }
    }
}
