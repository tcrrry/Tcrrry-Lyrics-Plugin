package com.tcrrry.lyrics.engine.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.tcrrry.lyrics.plugin.PluginLifetime

/** A menu-only illustration; no playback screenshot or private floating window is bundled. */
internal class GuideSourceMenuIllustration(context: Context) : View(context) {
    private val bitmap = runCatching {
        PluginLifetime.context.openAsset("guide/source-menu.png").use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 2 })
        }
    }.getOrNull()
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = SettingsUiTheme.colors(context).primary
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * resources.displayMetrics.density
    }
    private val destination = RectF()
    internal val sourceHighlight = RectF()
    internal val imageAvailable get() = bitmap != null

    init { contentDescription = "歌词源菜单示意，红框标出 QQ音乐及下方的逐字、译文与发音信息" }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val image = bitmap ?: return
        val scale = minOf(w.toFloat() / image.width, h.toFloat() / image.height)
        val imageWidth = image.width * scale
        val imageHeight = image.height * scale
        destination.set((w - imageWidth) / 2f, (h - imageHeight) / 2f,
            (w + imageWidth) / 2f, (h + imageHeight) / 2f)
        sourceHighlight.set(destination.left + imageWidth * .052f, destination.top + imageHeight * .508f,
            destination.left + imageWidth * .948f, destination.top + imageHeight * .750f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        bitmap?.let { canvas.drawBitmap(it, null, destination, imagePaint) }
        if (!sourceHighlight.isEmpty) {
            val radius = 8f * resources.displayMetrics.density
            canvas.drawRoundRect(sourceHighlight, radius, radius, outline)
        }
    }
}
