package com.tcrrry.lyrics.engine.ui

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.StateListAnimator
import android.content.res.ColorStateList
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ScrollView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import java.util.WeakHashMap
import kotlin.math.abs

internal enum class SettingsGlassKind { Card, Button, Field, Segments }
internal data class SettingsGlassStyle(val kind: SettingsGlassKind, val accent: Boolean = false,
    val filled: Boolean = false)

internal object SettingsGlassMaterial {
    private val styles = WeakHashMap<View, SettingsGlassStyle>()
    fun mark(view: View, kind: SettingsGlassKind, accent: Boolean = false, filled: Boolean = false) {
        styles[view] = SettingsGlassStyle(kind, accent, filled)
    }
    fun style(view: View) = styles[view]
}

internal data class SettingsGlassBounds(val view: View, val style: SettingsGlassStyle,
    val left: Int, val top: Int, val width: Int, val height: Int,
    val clipLeft: Int, val clipTop: Int, val clipRight: Int, val clipBottom: Int,
    val opacity: Float, val focused: Boolean, val enabled: Boolean)

@Composable
internal fun SettingsLiquidSegments(group: SettingsSegmentedControl, backdrop: Backdrop,
    colors: SettingsColors, modifier: Modifier) {
    val density = LocalDensity.current
    HostLiquidBottomTabs(selectedTabIndex = { group.selectedIndex },
        onTabSelected = { if (group.isAttachedToWindow) group.select(it) },
        backdrop = backdrop, tabsCount = group.choices.size, modifier = modifier,
        accentOverride = Color(colors.primary), panelHeight = with(density) { group.height.toDp() }, panelBlur = 4.dp) {
        group.choices.forEachIndexed { index, caption ->
            HostLiquidBottomTab(onClick = { if (group.isAttachedToWindow) group.select(index) }) {
                BasicText(caption, Modifier.padding(horizontal = 8.dp), style = TextStyle(color = Color(colors.text),
                    fontSize = with(density) { group.labels[index].textSize.toSp() },
                    fontWeight = FontWeight.Medium, textAlign = TextAlign.Center))
            }
        }
    }
}

internal fun settingsGlassBounds(panel: ViewGroup, content: View): List<SettingsGlassBounds> {
    val origin = IntArray(2)
    SettingsDialogMotion.layoutPositionOnScreen(panel, origin)
    val location = IntArray(2)
    val result = mutableListOf<SettingsGlassBounds>()
    fun visit(view: View, clip: Rect) {
        if (view.visibility != View.VISIBLE) return
        SettingsDialogMotion.layoutPositionOnScreen(view, location)
        val x = location[0] - origin[0]
        val y = location[1] - origin[1]
        val bounds = Rect(x, y, x + view.width, y + view.height)
        val childClip = Rect(clip)
        if (view is ScrollView && !childClip.intersect(bounds)) return
        val style = SettingsGlassMaterial.style(view)
        if (style != null && view !== content && view.width > 0 && view.height > 0 && Rect.intersects(bounds, clip)) {
            result += SettingsGlassBounds(view, style, x, y, view.width, view.height,
                clip.left, clip.top, clip.right, clip.bottom, view.alpha, view.hasFocus(), view.isEnabled)
        }
        if (view is SettingsSegmentedControl) return
        if (view is ViewGroup && view !is SettingsGlassHostView)
            for (index in 0 until view.childCount) visit(view.getChildAt(index), childClip)
    }
    visit(panel, Rect(0, 0, panel.width, panel.height))
    return result
}

/** Shared frosted plate for ordinary controls; only real selection strips run live glass effects. */
internal class SettingsGlassControls(private val panel: ViewGroup, private val content: View,
    val host: SettingsGlassHostView?, backdrop: Backdrop, private val colors: SettingsColors) : AutoCloseable {
    private data class Binding(var original: Drawable?, var material: Drawable, var style: SettingsGlassStyle,
        val animator: StateListAnimator?)
    private val bindings = mutableMapOf<View, Binding>()
    private var groups by mutableStateOf(emptyList<SettingsGlassBounds>())
    private val inlineGroups = mutableSetOf<SettingsSegmentedControl>()
    private val frosted: Backdrop
    private var enabled = false
    private var closed = false
    private var tracking: SettingsSegmentedControl? = null
    private var dragging = false
    private var downX = 0f
    private var downY = 0f
    private val touchSlop = ViewConfiguration.get(panel.context).scaledTouchSlop
    private val inverse = Matrix()
    private val layout = ViewTreeObserver.OnGlobalLayoutListener { update() }
    private val scroll = ViewTreeObserver.OnScrollChangedListener { if (groups.isNotEmpty()) update() }

    init {
        host?.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        frosted = object : Backdrop {
            override val isCoordinatesDependent = backdrop.isCoordinatesDependent
            override fun DrawScope.drawBackdrop(density: Density, coordinates: LayoutCoordinates?,
                layerBlock: (GraphicsLayerScope.() -> Unit)?) {
                with(backdrop) { this@drawBackdrop.drawBackdrop(density, coordinates, layerBlock) }
                drawRect(Color((backdrop as? SettingsPanelBackdrop)?.washColor ?: SettingsFrostedPalette.defaultWash(colors.dark)))
            }
        }
        host?.content {
            val density = LocalDensity.current
            Box(Modifier.fillMaxSize()) {
                groups.forEach { surface -> key(surface.view) {
                    val group = surface.view as SettingsSegmentedControl
                    Box(Modifier.absoluteOffset { IntOffset(surface.clipLeft, surface.clipTop) }
                        .requiredSize(with(density) { (surface.clipRight - surface.clipLeft).toDp() },
                            with(density) { (surface.clipBottom - surface.clipTop).toDp() }).clipToBounds()) {
                        SettingsLiquidSegments(group, frosted, colors,
                            Modifier.absoluteOffset { IntOffset(surface.left - surface.clipLeft, surface.top - surface.clipTop) }
                                .requiredSize(with(density) { surface.width.toDp() }, with(density) { surface.height.toDp() }))
                    }
                } }
            }
        }
    }

    fun enable() {
        if (enabled || closed) return
        enabled = true
        panel.viewTreeObserver.addOnGlobalLayoutListener(layout)
        panel.viewTreeObserver.addOnScrollChangedListener(scroll)
        update()
    }

    private fun material(style: SettingsGlassStyle): Drawable {
        fun rounded(color: Int) = GradientDrawable().apply {
            setColor(color)
            cornerRadius = (if (style.kind == SettingsGlassKind.Card) 18f else 14f) * panel.resources.displayMetrics.density
            setStroke(panel.resources.displayMetrics.density.toInt().coerceAtLeast(1),
                if (this@SettingsGlassControls.colors.dark) 0x24FFFFFF else 0x1C000000)
        }
        val fill = when {
            style.filled -> colors.filledAccent
            style.accent -> if (colors.dark) 0x443E1722 else 0xCCFCE8EC.toInt()
            colors.dark -> if (style.kind == SettingsGlassKind.Card) 0x12FFFFFF else 0x16FFFFFF
            else -> 0xBFFFFFFF.toInt()
        }
        val shape = rounded(fill)
        return if (style.kind == SettingsGlassKind.Button) RippleDrawable(ColorStateList.valueOf(
            if (colors.dark) 0x24FFFFFF else 0x14000000), shape, rounded(android.graphics.Color.WHITE)) else shape
    }

    private fun pressAnimator(view: View) = StateListAnimator().apply {
        fun animation(scale: Float, time: Long) = ObjectAnimator.ofPropertyValuesHolder(view,
            PropertyValuesHolder.ofFloat(View.SCALE_X, scale), PropertyValuesHolder.ofFloat(View.SCALE_Y, scale)).apply {
                duration = time
                interpolator = android.view.animation.DecelerateInterpolator()
            }
        addState(intArrayOf(android.R.attr.state_pressed, android.R.attr.state_enabled), animation(.985f, 100L))
        addState(intArrayOf(), animation(1f, 160L))
    }

    private fun update() {
        if (!enabled || closed) return
        val next = settingsGlassBounds(panel, content)
        val local = mutableSetOf<SettingsSegmentedControl>()
        fun inline(view: View) {
            if (view is SettingsSegmentedControl) {
                if (view.inlineGlass) local += view
            } else if (view is ViewGroup && view !is SettingsGlassHostView)
                for (index in 0 until view.childCount) inline(view.getChildAt(index))
        }
        inline(panel)
        inlineGroups.filter { it !in local }.forEach { it.releaseInlineGlass(); inlineGroups.remove(it) }
        local.filter { it !in inlineGroups }.forEach { group ->
            inlineGroups += group
            runCatching { group.attachInlineGlass(frosted, colors) }
                .onFailure { android.util.Log.w("TcrrrySettings", "Inline liquid selection unavailable", it) }
        }
        // Native plates stay on their own views. Do not recreate them during each fling frame.
        val native = mutableListOf<Pair<View, SettingsGlassStyle>>()
        fun visit(view: View) {
            if (view.visibility != View.VISIBLE || view is SettingsGlassHostView) return
            SettingsGlassMaterial.style(view)?.takeIf { it.kind != SettingsGlassKind.Segments }
                ?.let { if (view !== content) native += view to it }
            if (view is SettingsSegmentedControl) return
            if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
        }
        visit(panel)
        val visible = native.mapTo(mutableSetOf()) { it.first }
        bindings.keys.filter { it !in visible }.forEach { restore(it) }
        native.forEach { (view, style) ->
            val binding = bindings[view]
            if (binding == null) {
                val draw = material(style)
                bindings[view] = Binding(view.background, draw, style, view.stateListAnimator)
                view.background = draw
                if (style.kind == SettingsGlassKind.Button) view.stateListAnimator = pressAnimator(view)
            } else if (binding.style != style || view.background !== binding.material) {
                if (view.background !== binding.material) binding.original = view.background
                binding.material = material(style); binding.style = style
                view.background = binding.material
            }
        }
        fun scrollable(view: View): Boolean {
            if (view is SettingsGlassHostView) return false
            if (view is ScrollView && (view.getChildAt(0)?.height ?: 0) > view.height - view.paddingTop - view.paddingBottom) return true
            return view is ViewGroup && (0 until view.childCount).any { scrollable(view.getChildAt(it)) }
        }
        // A scrolling page uses native segments, keeping their text/background in the same render node.
        val selectors = if (host == null || scrollable(panel)) emptyList() else next.filter {
            it.style.kind == SettingsGlassKind.Segments && !(it.view as SettingsSegmentedControl).inlineGlass }
        groups.filter { old -> selectors.none { it.view === old.view } }.forEach { (it.view as SettingsSegmentedControl).glassActive = false }
        selectors.forEach { (it.view as SettingsSegmentedControl).let { group -> if (!group.glassActive) group.glassActive = true } }
        if (groups != selectors) groups = selectors
    }

    private fun restore(view: View) {
        val binding = bindings.remove(view) ?: return
        if (view.background === binding.material) view.background = binding.original
        view.stateListAnimator = binding.animator
        view.scaleX = 1f; view.scaleY = 1f
    }

    /** Only selection strips own horizontal gestures; ordinary buttons keep native clicks. */
    fun touch(event: MotionEvent): Boolean {
        if (!enabled || closed) return false
        val local = MotionEvent.obtain(event)
        local.offsetLocation(-panel.left.toFloat(), -panel.top.toFloat())
        if (panel.matrix.invert(inverse)) local.transform(inverse)
        try {
            when (local.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    tracking = groups.lastOrNull { s -> local.x >= maxOf(s.left, s.clipLeft) &&
                        local.x < minOf(s.left + s.width, s.clipRight) && local.y >= maxOf(s.top, s.clipTop) &&
                        local.y < minOf(s.top + s.height, s.clipBottom) }?.view as? SettingsSegmentedControl
                    downX = local.x; downY = local.y; dragging = false
                }
                MotionEvent.ACTION_MOVE -> if (tracking != null) {
                    val dx = abs(local.x - downX); val dy = abs(local.y - downY)
                    if (!dragging && dy > touchSlop && dy > dx) local.action = MotionEvent.ACTION_CANCEL
                    else if (dx > touchSlop) dragging = true
                }
            }
            val active = tracking != null
            if (active) host?.compose?.dispatchTouchEvent(local)
            val consume = active && (dragging || local.actionMasked == MotionEvent.ACTION_UP)
            if (local.actionMasked == MotionEvent.ACTION_UP || local.actionMasked == MotionEvent.ACTION_CANCEL) {
                tracking = null; dragging = false
            }
            return consume
        } finally { local.recycle() }
    }

    override fun close() {
        if (closed) return
        closed = true
        if (panel.viewTreeObserver.isAlive) {
            panel.viewTreeObserver.removeOnGlobalLayoutListener(layout)
            panel.viewTreeObserver.removeOnScrollChangedListener(scroll)
        }
        bindings.keys.toList().forEach(::restore)
        groups.forEach { (it.view as SettingsSegmentedControl).glassActive = false }
        groups = emptyList()
        inlineGroups.forEach { it.releaseInlineGlass() }
        inlineGroups.clear()
    }
}
