package com.tcrrry.lyrics.engine.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.HardwareRenderer
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.view.View

/** Android's offscreen RenderEffect path: blur a small snapshot once, not a full window every frame. */
internal object SettingsFrostedBitmap {
    fun blur(source: Bitmap, radius: Float): Bitmap {
        val reader = ImageReader.newInstance(source.width, source.height, PixelFormat.RGBA_8888, 1,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
        val node = RenderNode("LyricsSettingsFrost")
        val renderer = HardwareRenderer()
        try {
            renderer.setSurface(reader.surface)
            renderer.setContentRoot(node)
            node.setPosition(0, 0, source.width, source.height)
            node.setRenderEffect(RenderEffect.createBlurEffect(radius.coerceAtLeast(.1f), radius.coerceAtLeast(.1f), Shader.TileMode.MIRROR))
            val canvas = node.beginRecording()
            canvas.drawBitmap(source, 0f, 0f, null)
            node.endRecording()
            renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            val image = reader.acquireNextImage() ?: error("Frosted snapshot was not produced")
            image.use {
                val buffer = image.hardwareBuffer ?: error("Frosted snapshot buffer unavailable")
                buffer.use {
                    val hardware = Bitmap.wrapHardwareBuffer(buffer, null) ?: error("Frosted bitmap unavailable")
                    try { return hardware.copy(Bitmap.Config.ARGB_8888, false) ?: error("Frosted bitmap copy failed") }
                    finally { hardware.recycle() }
                }
            }
        } finally {
            renderer.destroy()
            node.discardDisplayList()
            reader.close()
        }
    }
}

internal object SettingsFrostedPalette {
    fun defaultWash(dark: Boolean) = Color.argb(if (dark) 100 else 140,
        if (dark) 28 else 250, if (dark) 28 else 250, if (dark) 32 else 250)

    fun composite(background: Int, wash: Int): Int {
        val alpha = Color.alpha(wash) / 255f
        fun channel(bg: Int, fg: Int) = (bg * (1f - alpha) + fg * alpha + .5f).toInt()
        return Color.rgb(channel(Color.red(background), Color.red(wash)),
            channel(Color.green(background), Color.green(wash)), channel(Color.blue(background), Color.blue(wash)))
    }

    fun wash(samples: IntArray, dark: Boolean): Int {
        val foreground = Color.luminance(if (dark) SettingsColors.Dark.text else SettingsColors.Light.text)
        val base = if (dark) Color.rgb(28, 28, 32) else Color.rgb(250, 250, 250)
        val start = if (dark) .32f else .52f
        for (step in 0..18) {
            val alpha = (start + step * .02f).coerceAtMost(.72f)
            val color = (base and 0x00FFFFFF) or ((alpha * 255).toInt() shl 24)
            val readable = samples.count { sample ->
                val background = Color.luminance(composite(sample, color))
                (maxOf(background, foreground) + .05f) / (minOf(background, foreground) + .05f) >= 4.5f
            }
            if (samples.isEmpty() || readable >= samples.size * .9f || alpha == .72f) return color
        }
        return (base and 0x00FFFFFF) or (184 shl 24)
    }
}

/** Native display list shares one blurred texture with every control, without Compose or a live shader. */
internal class SettingsFrostedView(context: Context) : View(context) {
    var backdrop: SettingsPanelBackdrop? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val target = RectF()
    private val edge = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val material = backdrop
        if (material == null || !material.drawNative(canvas, this, paint, target)) {
            canvas.drawColor(SettingsUiTheme.colors(context).surface)
        }
        val dark = (material as? SettingsHostBackdrop)?.dark ?: SettingsUiTheme.colors(context).dark
        paint.shader = null
        paint.color = material?.washColor ?: SettingsFrostedPalette.defaultWash(dark)
        paint.style = Paint.Style.FILL
        if (material?.hasSnapshot == true) canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = resources.displayMetrics.density
        paint.color = if (dark) 0x40FFFFFF else 0x90FFFFFF.toInt()
        val inset = paint.strokeWidth / 2f
        edge.set(inset, inset, width - inset, height - inset)
        val radius = 24f * resources.displayMetrics.density
        canvas.drawRoundRect(edge, radius, radius, paint)
        paint.style = Paint.Style.FILL
    }
}
