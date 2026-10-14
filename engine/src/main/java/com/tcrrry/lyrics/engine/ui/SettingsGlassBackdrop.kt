package com.tcrrry.lyrics.engine.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Density
import com.kyant.backdrop.Backdrop

internal interface SettingsPanelBackdrop : Backdrop, AutoCloseable {
    val washColor: Int
    val hasSnapshot: Boolean
    fun capture(ready: (Boolean) -> Unit)
    fun drawNative(canvas: Canvas, view: View, paint: Paint, destination: RectF): Boolean
}

/** A single pre-blurred texture serves the native plate and the small host selection controls. */
internal class SettingsGlassBackdrop(private val activity: Activity,
    private val consumer: View) : SettingsPanelBackdrop {
    private val source = activity.window.decorView
    private val width = source.width.also { check(it > 0) }
    private val height = source.height.also { check(it > 0) }
    private val scale = minOf(1f, 384f / maxOf(width, height))
    private val main = Handler(Looper.getMainLooper())
    private var input: Bitmap? = Bitmap.createBitmap((width * scale).toInt().coerceAtLeast(1),
        (height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    private var snapshot: Bitmap? = null
    private var image by mutableStateOf<ImageBitmap?>(null)
    @Volatile private var closed = false
    private var copying = false
    private var worker: Thread? = null
    private val sourcePosition = IntArray(2)
    private val targetPosition = IntArray(2)
    override var washColor = SettingsFrostedPalette.defaultWash(SettingsUiTheme.colors(activity).dark)
        private set
    override val hasSnapshot get() = snapshot != null
    override val isCoordinatesDependent = true

    override fun capture(ready: (Boolean) -> Unit) {
        val target = input ?: return ready(false)
        if (closed || copying || worker != null) return ready(false)
        copying = true
        try {
            PixelCopy.request(activity.window, target, { result ->
                copying = false
                if (closed) { recycleInput(); return@request }
                if (result != PixelCopy.SUCCESS) { recycleInput(); ready(false); return@request }
                input = null
                val dark = SettingsUiTheme.colors(activity).dark
                worker = Thread({
                    var output: Bitmap? = null
                    var wash = SettingsFrostedPalette.defaultWash(dark)
                    try {
                        if (!closed) {
                            output = runCatching {
                                if (android.os.Build.VERSION.SDK_INT < 31) error("Offscreen blur requires Android 12")
                                SettingsFrostedBitmap.blur(target, SettingsGlassPanelMaterial.BLUR_DP * activity.resources.displayMetrics.density * scale)
                            }.getOrElse {
                                android.util.Log.w("TcrrrySettings", "Offscreen blur unavailable; using a low-detail color snapshot", it)
                                Bitmap.createScaledBitmap(target, (target.width * 48f / target.height).toInt().coerceAtLeast(1), 48, true)
                            }
                            val bitmap = output
                            val samples = IntArray(144) { index -> bitmap.getPixel(
                                ((index % 12 + .5f) * bitmap.width / 12).toInt().coerceIn(0, bitmap.width - 1),
                                ((index / 12 + .5f) * bitmap.height / 12).toInt().coerceIn(0, bitmap.height - 1)) }
                            wash = SettingsFrostedPalette.wash(samples, dark)
                        }
                    } finally {
                        target.recycle()
                        val resultBitmap = output
                        val resultWash = wash
                        main.post {
                            worker = null
                            if (closed) { resultBitmap?.recycle(); return@post }
                            snapshot = resultBitmap
                            image = resultBitmap?.asImageBitmap()
                            washColor = resultWash
                            consumer.invalidate()
                            ready(resultBitmap != null)
                        }
                    }
                }, "lyrics-settings-frost").apply { isDaemon = true; start() }
            }, main)
        } catch (error: Throwable) {
            copying = false
            recycleInput()
            ready(false)
        }
    }

    override fun drawNative(canvas: Canvas, view: View, paint: Paint, destination: RectF): Boolean {
        val bitmap = snapshot ?: return false
        if (closed) return false
        source.getLocationOnScreen(sourcePosition)
        SettingsDialogMotion.layoutPositionOnScreen(view, targetPosition)
        val x = (targetPosition[0] - sourcePosition[0]).toFloat()
        val y = (targetPosition[1] - sourcePosition[1]).toFloat()
        destination.set(-x, -y, width - x, height - y)
        paint.color = android.graphics.Color.WHITE
        canvas.drawBitmap(bitmap, null, destination, paint)
        return true
    }

    override fun DrawScope.drawBackdrop(density: Density, coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?) {
        val captured = image ?: return
        if (closed || coordinates == null || !coordinates.isAttached) return
        source.getLocationOnScreen(sourcePosition)
        SettingsDialogMotion.layoutPositionOnScreen(consumer, targetPosition)
        val local = coordinates.localToRoot(Offset.Zero)
        val x = (targetPosition[0] - sourcePosition[0]).toFloat() + local.x
        val y = (targetPosition[1] - sourcePosition[1]).toFloat() + local.y
        withTransform({
            translate(-x, -y)
            scale(width.toFloat() / captured.width, height.toFloat() / captured.height, Offset.Zero)
        }) { drawImage(captured) }
    }

    override fun close() {
        if (closed) return
        closed = true
        image = null
        snapshot?.recycle(); snapshot = null
        worker?.interrupt()
        if (!copying) recycleInput()
    }

    private fun recycleInput() { input?.takeIf { !it.isRecycled }?.recycle(); input = null }
}

internal object SettingsGlassPanelMaterial {
    const val BLUR_DP = 28f
}
