package dev.kifranei.ampp.media

import android.app.Activity
import android.graphics.Bitmap
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

/** Sample the actual activity window at the consumer's screen position across windows. */
class DialogWindowBackdrop(
    private val activity: Activity,
    private val consumer: View,
    private val live: Boolean = false,
) : Backdrop, AutoCloseable {
    private val source = activity.window.decorView
    private val main = Handler(Looper.getMainLooper())
    private val sourceWidth = source.width.also { check(it > 0) }
    private val sourceHeight = source.height.also { check(it > 0) }
    // Blur does not need a full-resolution background. Two buffers keep PixelCopy
    // from writing into the bitmap currently presented by Compose.
    private val scale = if (live) minOf(1f, 1440f / maxOf(sourceWidth, sourceHeight)) else 1f
    private val bitmaps = Array(if (live) 2 else 1) {
        Bitmap.createBitmap((sourceWidth * scale).toInt().coerceAtLeast(1),
            (sourceHeight * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    }
    private var front = -1
    private var image by mutableStateOf<ImageBitmap?>(null)
    private var closed = false
    private var copying = false
    private var frames = 0
    private var failures = 0
    private val sourcePosition = IntArray(2)
    private val targetPosition = IntArray(2)
    private val position = WindowBackdropPosition()
    private val frame = Runnable {
        if (!closed && consumer.isAttachedToWindow && source.isAttachedToWindow &&
            !activity.isFinishing && !activity.isDestroyed) {
            requestCopy { success ->
                if (!success && ++failures == 3) MediaRuntime.log("dialog_backdrop: live window copy temporarily unavailable")
                schedule()
            }
        }
    }
    private val attached = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) { schedule() }
        override fun onViewDetachedFromWindow(view: View) { close() }
    }
    override val isCoordinatesDependent = true

    init { if (live) consumer.addOnAttachStateChangeListener(attached) }

    fun capture(ready: (Boolean) -> Unit) {
        check(!closed && !copying)
        requestCopy { success ->
            if (!success) close()
            ready(success)
            schedule()
        }
    }

    /** Called after the native sheet has moved, before its window draws. */
    fun refreshPosition() {
        if (closed || !live || !consumer.isAttachedToWindow) return
        position.update(screenOrigin())
    }

    private fun screenOrigin(): Pair<Float, Float> {
        source.getLocationOnScreen(sourcePosition)
        // CoordinatorLayout can move the material without changing Compose-local
        // coordinates or invalidating its cached draw layer.
        val composeRoot = (consumer as? PluginGlassHostView)?.compose ?: consumer
        composeRoot.getLocationOnScreen(targetPosition)
        return WindowBackdropGeometry.origin(sourcePosition[0], sourcePosition[1],
            targetPosition[0], targetPosition[1], 0f, 0f)
    }

    private fun schedule() {
        if (!live || closed || copying || !consumer.isAttachedToWindow) return
        main.removeCallbacks(frame)
        main.postDelayed(frame, 66L)
    }

    private fun requestCopy(ready: (Boolean) -> Unit) {
        if (closed || copying) return
        copying = true
        val next = if (live && front == 0) 1 else 0
        val bitmap = bitmaps[next]
        try {
            PixelCopy.request(activity.window, bitmap, { result ->
                copying = false
                if (closed) { recycle(); return@request }
                val success = result == PixelCopy.SUCCESS
                if (success) {
                    front = next
                    image = bitmap.asImageBitmap()
                    frames++
                    failures = 0
                }
                ready(success)
            }, main)
        } catch (error: Throwable) {
            copying = false
            if (!live || frames == 0) { close(); throw error }
            ready(false)
        }
    }

    override fun DrawScope.drawBackdrop(density: Density, coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?) {
        val captured = image ?: return
        if (closed || coordinates == null || !coordinates.isAttached) return
        // Reading observed position dirties only the draw layer as the sheet moves;
        // neither image capture nor recomposition is required for each drag frame.
        val origin = if (live) position.origin else screenOrigin()
        val local = coordinates.localToRoot(Offset.Zero)
        withTransform({
            translate(-origin.first - local.x, -origin.second - local.y)
            scale(sourceWidth.toFloat() / captured.width, sourceHeight.toFloat() / captured.height, Offset.Zero)
        }) { drawImage(captured) }
    }

    override fun close() {
        if (closed) return
        closed = true
        main.removeCallbacks(frame)
        if (live) consumer.removeOnAttachStateChangeListener(attached)
        image = null
        if (!copying) recycle()
        if (live) MediaRuntime.log("dialog_backdrop: released live playback window after $frames frames")
    }

    private fun recycle() { bitmaps.forEach { if (!it.isRecycled) it.recycle() } }
}
