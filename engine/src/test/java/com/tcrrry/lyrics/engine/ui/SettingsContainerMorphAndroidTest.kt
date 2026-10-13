package com.tcrrry.lyrics.engine.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsContainerMorphAndroidTest {
    @Test fun containerKeepsTheTargetSizeAndFadesInformationBeforeItsFinalBlend() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        val root = FrameLayout(activity)
        val panel = FrameLayout(activity).apply { setBackgroundColor(Color.MAGENTA) }
        val label = TextView(activity).apply { text = "Settings"; setTextColor(Color.WHITE); textSize = 24f }
        panel.addView(label, FrameLayout.LayoutParams(240, 80))
        root.addView(panel)
        activity.setContentView(root)
        root.layout(0, 0, 400, 800)
        panel.layout(60, 200, 340, 600)
        label.layout(0, 0, 240, 80)
        val parentOrigin = IntArray(2)
        root.getLocationOnScreen(parentOrigin)
        val target = Rect(20 + parentOrigin[0], 60 + parentOrigin[1], 100 + parentOrigin[0], 110 + parentOrigin[1])
        val morph = SettingsContainerMorph.capture(panel, root, target, null)
        val output = Bitmap.createBitmap(400, 800, Bitmap.Config.ARGB_8888)
        try {
            assertEquals(RectF(60f, 200f, 340f, 600f), morph.containerBounds)
            assertEquals(1f, morph.contentOpacity, .001f)
            morph.draw(Canvas(output))
            assertEquals(Color.MAGENTA, output.getPixel(200, 400))
            assertEquals(Color.TRANSPARENT, output.getPixel(10, 400))
            morph.progress = .5f
            assertEquals(RectF(40f, 130f, 220f, 355f), morph.containerBounds)
            assertTrue(morph.contentOpacity in 0f.. .3f)
            output.eraseColor(Color.TRANSPARENT)
            morph.draw(Canvas(output))
            assertTrue(Color.alpha(output.getPixel(100, 230)) > 0)
            assertEquals(Color.TRANSPARENT, output.getPixel(300, 400))
            morph.progress = 1f
            assertEquals(RectF(20f, 60f, 100f, 110f), morph.containerBounds)
            assertEquals(0f, morph.contentOpacity, .001f)
            output.eraseColor(Color.TRANSPARENT)
            morph.draw(Canvas(output))
            assertEquals(Color.TRANSPARENT, output.getPixel(60, 85))
            assertEquals(280, panel.width)
            assertEquals(400, panel.height)
        } finally {
            morph.close()
            morph.close()
            output.recycle()
            activity.finish()
        }
    }
}
