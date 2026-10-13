package com.tcrrry.lyrics.engine.ui

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class SettingsGlassControlsAndroidTest {
    private val backdrop = object : com.kyant.backdrop.Backdrop {
        override val isCoordinatesDependent = false
        override fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBackdrop(
            density: androidx.compose.ui.unit.Density,
            coordinates: androidx.compose.ui.layout.LayoutCoordinates?,
            layerBlock: (androidx.compose.ui.graphics.GraphicsLayerScope.() -> Unit)?) = Unit
    }
    private fun activity() = Robolectric.buildActivity(Activity::class.java).setup().get()
    private fun layout(root: View, width: Int = 320, height: Int = 600) {
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, height)
    }

    @Test fun geometryTracksNativeScrollingAndClipsOnlyTheScrollableRegion() {
        val context = activity()
        val panel = FrameLayout(context)
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        SettingsGlassMaterial.mark(body, SettingsGlassKind.Card)
        val scroll = ScrollView(context).apply { addView(body) }
        panel.addView(scroll, FrameLayout.LayoutParams(-1, 300))
        val button = TextView(context).apply { text = "Glass action" }
        SettingsGlassMaterial.mark(button, SettingsGlassKind.Button)
        body.addView(View(context), LinearLayout.LayoutParams(-1, 240))
        body.addView(button, LinearLayout.LayoutParams(-1, 80))
        body.addView(View(context), LinearLayout.LayoutParams(-1, 600))
        val footer = TextView(context).apply { text = "Close" }
        SettingsGlassMaterial.mark(footer, SettingsGlassKind.Button)
        panel.addView(footer, FrameLayout.LayoutParams(-1, 60).apply { topMargin = 300 })
        layout(panel)
        val first = settingsGlassBounds(panel, body)
        assertFalse(first.any { it.view === body })
        val action = first.single { it.view === button }
        assertEquals(240, action.top)
        assertEquals(300, action.clipBottom)
        panel.scaleX = .2f; panel.translationY = 77f
        scroll.scrollTo(0, 180)
        val after = settingsGlassBounds(panel, body)
        assertEquals(60, after.single { it.view === button }.top)
        assertEquals(300, after.single { it.view === footer }.top)
        assertEquals(600, after.single { it.view === footer }.clipBottom)
    }

    @Test fun materialsRemoveOpaqueFillsOnlyWhileEnabledAndRestoreThemOnClose() {
        val context = activity()
        val panel = FrameLayout(context)
        val body = LinearLayout(context)
        panel.addView(body)
        val original = ColorDrawable(android.graphics.Color.RED)
        val button = TextView(context).apply { text = "Native command"; background = original; isClickable = true }
        SettingsGlassMaterial.mark(button, SettingsGlassKind.Button)
        body.addView(button, LinearLayout.LayoutParams(250, 60))
        layout(panel)
        val originalTextColor = button.currentTextColor
        val host = SettingsGlassHostView(context)
        val controls = SettingsGlassControls(panel, body, host, backdrop,
            SettingsColors.Light)
        assertSame(original, button.background)
        controls.enable()
        assertNotSame(original, button.background)
        assertTrue(button.background is android.graphics.drawable.RippleDrawable)
        assertNotNull(button.stateListAnimator)
        assertEquals(originalTextColor, button.currentTextColor)
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, host.importantForAccessibility)
        var commands = 0
        button.setOnClickListener { commands++ }
        button.performClick()
        assertEquals(1, commands)
        body.removeView(button)
        panel.viewTreeObserver.dispatchOnGlobalLayout()
        assertSame(original, button.background)
        body.addView(button, LinearLayout.LayoutParams(250, 60))
        layout(panel)
        panel.viewTreeObserver.dispatchOnGlobalLayout()
        assertNotSame(original, button.background)
        controls.close()
        assertSame(original, button.background)
    }

    @Test fun onlySelectionStripsConsumeHorizontalDragAndOrdinaryButtonsKeepTheirNativeInput() {
        val context = activity()
        val panel = FrameLayout(context)
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(body)
        val group = SettingsSegmentedControl(context, "Priority", listOf("Quality", "Words"), 0) {}
        body.addView(group, LinearLayout.LayoutParams(250, 80))
        body.addView(android.widget.SeekBar(context), LinearLayout.LayoutParams(250, 80))
        val ordinary = TextView(context).apply { text = "Action"; isClickable = true }
        SettingsGlassMaterial.mark(ordinary, SettingsGlassKind.Button)
        body.addView(ordinary, LinearLayout.LayoutParams(250, 80))
        layout(panel)
        val controls = SettingsGlassControls(panel, body, SettingsGlassHostView(context),
            backdrop, SettingsColors.Light)
        controls.enable()
        fun event(action: Int, x: Float, y: Float): Boolean {
            val e = MotionEvent.obtain(0, 0, action, x, y, 0)
            return try { controls.touch(e) } finally { e.recycle() }
        }
        assertFalse(event(MotionEvent.ACTION_DOWN, 100f, 40f))
        assertTrue(event(MotionEvent.ACTION_MOVE, 170f, 42f))
        assertTrue(event(MotionEvent.ACTION_UP, 170f, 42f))
        assertFalse(event(MotionEvent.ACTION_CANCEL, 170f, 42f))
        assertFalse(event(MotionEvent.ACTION_DOWN, 100f, 40f))
        assertFalse(event(MotionEvent.ACTION_MOVE, 102f, 110f))
        assertFalse(event(MotionEvent.ACTION_UP, 102f, 110f))
        assertFalse(event(MotionEvent.ACTION_DOWN, 100f, 120f))
        assertFalse(event(MotionEvent.ACTION_MOVE, 170f, 122f))
        assertFalse(event(MotionEvent.ACTION_UP, 170f, 122f))
        assertFalse(event(MotionEvent.ACTION_DOWN, 100f, 200f))
        assertFalse(event(MotionEvent.ACTION_MOVE, 170f, 202f))
        assertFalse(event(MotionEvent.ACTION_UP, 170f, 202f))
        assertTrue(group.glassActive)
        assertTrue(group.labels.all { it.currentTextColor == android.graphics.Color.TRANSPARENT })
        controls.close()
        assertFalse(group.glassActive)
        assertTrue(group.labels.none { it.currentTextColor == android.graphics.Color.TRANSPARENT })
    }

    @Test fun nativePagesNeedNoComposeHostAndKeepFallbackSelectionLabelsVisible() {
        val context = activity()
        val panel = FrameLayout(context)
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(body)
        val group = SettingsSegmentedControl(context, "Priority", listOf("Quality", "Words"), 0) {}
        val button = TextView(context).apply { text = "Close"; isClickable = true }
        SettingsGlassMaterial.mark(button, SettingsGlassKind.Button)
        body.addView(group, LinearLayout.LayoutParams(250, 80))
        body.addView(button, LinearLayout.LayoutParams(250, 60))
        layout(panel)
        val controls = SettingsGlassControls(panel, body, null, backdrop, SettingsColors.Light)
        controls.enable()
        assertNull(controls.host)
        assertFalse(group.glassActive)
        assertTrue(group.labels.none { it.currentTextColor == android.graphics.Color.TRANSPARENT })
        assertTrue(button.background is android.graphics.drawable.RippleDrawable)
        controls.close()
    }
}
