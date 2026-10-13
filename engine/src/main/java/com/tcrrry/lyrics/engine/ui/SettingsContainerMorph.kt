package com.tcrrry.lyrics.engine.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View

/** Container-transform geometry with frozen layers: no layout or live blur during the exit. */
internal class SettingsContainerMorph private constructor(
    private val content: Bitmap,
    private val softened: Bitmap,
    private val plate: Bitmap,
    private val start: RectF,
    private val end: RectF,
    private val radius: Float,
    private val opacity: Float,
) : Drawable(), AutoCloseable {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val clip = Path()
    private val imageBounds = RectF()
    internal val containerBounds = RectF(start)
    internal var progress = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            containerBounds.set(lerp(start.left, end.left, field), lerp(start.top, end.top, field),
                lerp(start.right, end.right, field), lerp(start.bottom, end.bottom, field))
            invalidateSelf()
        }
    internal val contentOpacity: Float get() = 1f - smooth(progress / .72f)
    private var closed = false

    override fun draw(canvas: Canvas) {
        if (closed) return
        val bounds = containerBounds
        val corner = lerp(radius, minOf(radius / 2f, end.width() / 2f, end.height() / 2f), progress)
        clip.reset()
        clip.addRoundRect(bounds, corner, corner, Path.Direction.CW)
        val save = canvas.save()
        canvas.clipPath(clip)
        // Uniform fit + a changing mask preserves text proportions as the container changes shape.
        val scale = maxOf(bounds.width() / content.width, bounds.height() / content.height)
        imageBounds.set(bounds.centerX() - content.width * scale / 2f,
            bounds.centerY() - content.height * scale / 2f,
            bounds.centerX() + content.width * scale / 2f, bounds.centerY() + content.height * scale / 2f)
        val containerOpacity = opacity * (1f - smooth((progress - .62f) / .38f))
        fun layer(bitmap: Bitmap, alpha: Float) {
            paint.alpha = (255 * alpha).toInt().coerceIn(0, 255)
            if (paint.alpha > 0) canvas.drawBitmap(bitmap, null, imageBounds, paint)
        }
        layer(plate, containerOpacity)
        val softness = smooth(progress / .5f)
        layer(content, containerOpacity * contentOpacity * (1f - softness))
        layer(softened, containerOpacity * contentOpacity * softness)
        canvas.restoreToCount(save)
    }

    override fun close() {
        if (closed) return
        closed = true
        content.recycle()
        if (!softened.isRecycled) softened.recycle()
        plate.recycle()
    }
    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        private fun lerp(a: Float, b: Float, p: Float) = a + (b - a) * p
        private fun smooth(p: Float): Float = p.coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }

        fun capture(panel: View, parent: View, target: Rect, background: View?): SettingsContainerMorph {
            check(panel.width > 0 && panel.height > 0)
            val owned = mutableListOf<Bitmap>()
            try {
                val factor = minOf(1f, 768f / maxOf(panel.width, panel.height))
                fun snapshot(draw: (Canvas) -> Unit): Bitmap {
                    val bitmap = Bitmap.createBitmap((panel.width * factor).toInt().coerceAtLeast(1),
                        (panel.height * factor).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                    owned += bitmap
                    val canvas = Canvas(bitmap)
                    canvas.scale(bitmap.width.toFloat() / panel.width, bitmap.height.toFloat() / panel.height)
                    draw(canvas)
                    return bitmap
                }
                val content = snapshot(panel::draw)
                val plate = snapshot { canvas ->
                    if (background != null) background.draw(canvas) else panel.background?.draw(canvas)
                }
                // A small, bilinearly filtered copy supplies a cached soft layer, not a per-frame shader.
                val softScale = minOf(1f, 64f / maxOf(content.width, content.height))
                val softened = Bitmap.createScaledBitmap(content, (content.width * softScale).toInt().coerceAtLeast(1),
                    (content.height * softScale).toInt().coerceAtLeast(1), true)
                if (softened !== content) owned += softened
                val origin = IntArray(2)
                val parentOrigin = IntArray(2)
                panel.getLocationOnScreen(origin)
                parent.getLocationOnScreen(parentOrigin)
                val start = RectF((origin[0] - parentOrigin[0]).toFloat(), (origin[1] - parentOrigin[1]).toFloat(),
                    origin[0] - parentOrigin[0] + panel.width * panel.scaleX,
                    origin[1] - parentOrigin[1] + panel.height * panel.scaleY)
                val end = RectF(target).apply { offset(-parentOrigin[0].toFloat(), -parentOrigin[1].toFloat()) }
                return SettingsContainerMorph(content, softened, plate, start, end,
                    24f * panel.resources.displayMetrics.density * panel.scaleX, panel.alpha)
                    .apply { setBounds(0, 0, parent.width, parent.height) }
            } catch (error: Throwable) {
                owned.forEach { if (!it.isRecycled) it.recycle() }
                throw error
            }
        }
    }
}
