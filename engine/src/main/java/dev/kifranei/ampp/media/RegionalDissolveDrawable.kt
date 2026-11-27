package dev.kifranei.ampp.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.drawable.Drawable

/**
 * iOS AutoMix 封面切换：不是整体 alpha 交叉淡化，而是像积雪融化一样分区域渐隐渐显。
 * 用 FBM 噪声作为阈值遮罩：噪声低的区域先切到新封面，噪声高的区域后切；羽化让区域之间平滑过渡。
 */
internal class RegionalDissolveDrawable(
    private val oldBitmap: Bitmap,
    private val newBitmap: Bitmap,
    private val noiseBitmap: Bitmap,
) : Drawable() {
    var progress: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidateSelf()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val feather = 0.16f

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        paint.alpha = 255
        canvas.drawBitmap(oldBitmap, null, b, paint)

        val save = canvas.saveLayer(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), null)
        canvas.drawBitmap(newBitmap, null, b, paint)

        val scale = 1f / (2f * feather)
        val offset = ((-1f + progress + feather) / (2f * feather)) * 255f
        val cm = ColorMatrix(floatArrayOf(
            0f, 0f, 0f, 0f, 0f,
            0f, 0f, 0f, 0f, 0f,
            0f, 0f, 0f, 0f, 0f,
            0f, 0f, 0f, scale, offset,
        ))
        paint.colorFilter = ColorMatrixColorFilter(cm)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        canvas.drawBitmap(noiseBitmap, null, b, paint)
        paint.xfermode = null
        paint.colorFilter = null
        canvas.restoreToCount(save)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth(): Int = newBitmap.width
    override fun getIntrinsicHeight(): Int = newBitmap.height
}
