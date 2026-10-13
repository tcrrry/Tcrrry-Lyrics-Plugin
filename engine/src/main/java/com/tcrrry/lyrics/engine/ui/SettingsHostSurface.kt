package com.tcrrry.lyrics.engine.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Density
import java.util.WeakHashMap

/** Inherit the settings surface through view ancestry; scoped calls cannot leave a sticky mode. */
internal object SettingsSurfaceContext {
    private val hosts = WeakHashMap<View, Int>()
    var hostColor: Int? = null
        private set

    fun mark(view: View, color: Int) { hosts[view] = color }
    fun <T> from(view: View, action: () -> T): T {
        var node: View? = view
        var color: Int? = null
        while (node != null) {
            color = hosts[node]
            if (color != null) break
            node = node.parent as? View
        }
        val previous = hostColor
        hostColor = color
        return try { action() } finally { hostColor = previous }
    }

    fun background(context: Context): Int {
        val colors = SettingsUiTheme.colors(context)
        val attrs = context.obtainStyledAttributes(intArrayOf(android.R.attr.colorBackground))
        val candidate = try { attrs.getColor(0, colors.background) } finally { attrs.recycle() }
        val dark = Color.luminance(candidate) < .5f
        return if (Color.alpha(candidate) == 255 && dark == colors.dark) candidate else colors.background
    }
}

/** Opaque, host-colored base: glass controls must not reveal the player from this entrance. */
internal class SettingsHostBackdrop(private val color: Int) : SettingsPanelBackdrop {
    val dark = Color.luminance(color) < .5f
    override val washColor = Color.TRANSPARENT
    override val hasSnapshot = true
    override val isCoordinatesDependent = false
    override fun capture(ready: (Boolean) -> Unit) = ready(true)
    override fun drawNative(canvas: Canvas, view: View, paint: Paint, destination: RectF): Boolean {
        canvas.drawColor(color)
        return true
    }
    override fun DrawScope.drawBackdrop(density: Density, coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?) { drawRect(ComposeColor(color)) }
    override fun close() = Unit
}

internal class SettingsHostPage(context: Context, private val body: LinearLayout) : FrameLayout(context), AutoCloseable {
    private val color = SettingsSurfaceContext.background(context)
    private val backdrop = SettingsHostBackdrop(color)
    private var controls: SettingsGlassControls? = null
    private var closed = false
    internal val scroll = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        isVerticalFadingEdgeEnabled = true
        setFadingEdgeLength((18 * resources.displayMetrics.density).toInt())
        addView(body)
    }

    init {
        SettingsSurfaceContext.mark(this, color)
        background = GradientDrawable().apply {
            setColor(color)
            cornerRadius = 24 * resources.displayMetrics.density
        }
        clipToOutline = true
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val cap = (resources.displayMetrics.heightPixels * .78f).toInt()
        val size = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) cap
            else minOf(cap, MeasureSpec.getSize(heightMeasureSpec))
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(size,
            if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) MeasureSpec.EXACTLY else MeasureSpec.AT_MOST))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (closed) return
        controls = runCatching {
            SettingsGlassControls(this, body, null, backdrop, SettingsColors.resolve(backdrop.dark)).also { it.enable() }
        }.onFailure { android.util.Log.w("TcrrrySettings", "Host surface controls unavailable; keeping native controls", it) }.getOrNull()
    }

    override fun onDetachedFromWindow() {
        controls?.close()
        controls = null
        super.onDetachedFromWindow()
    }

    override fun close() {
        if (closed) return
        closed = true
        controls?.close()
        controls = null
        backdrop.close()
        (parent as? ViewGroup)?.removeView(this)
    }
}
