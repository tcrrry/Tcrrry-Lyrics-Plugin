package com.tcrrry.lyrics.engine.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.tcrrry.lyrics.plugin.TestPluginContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class SettingsHostSurfaceAndroidTest {
    @org.junit.Before fun attach() { TestPluginContext.attach() }
    @org.junit.After fun close() { TestPluginContext.current.close() }

    private fun layout(view: View, width: Int, height: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        view.viewTreeObserver.dispatchOnGlobalLayout()
    }

    @Test fun embeddedMainHasGlassControlsAndReleasesThemWhenItsSessionCloses() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        val original = ColorDrawable(Color.RED)
        val button = TextView(activity).apply { text = "Option"; background = original; isClickable = true }
        SettingsGlassMaterial.mark(button, SettingsGlassKind.Button)
        val body = LinearLayout(activity).apply { addView(button, LinearLayout.LayoutParams(-1, 80)) }
        val page = SettingsHostPage(activity, body)
        activity.setContentView(page)
        layout(page, 320, 600)
        assertFalse(page.scroll.isVerticalScrollBarEnabled)
        assertTrue(page.scroll.isVerticalFadingEdgeEnabled)
        assertNotSame(original, button.background)
        assertNotNull(button.stateListAnimator)
        page.close()
        page.close()
        assertSame(original, button.background)
        assertNull(button.stateListAnimator)
        activity.finish()
    }

    @Test fun nestedDialogsKeepTheHostSurfaceWithoutLeakingItToThePlayerEntrance() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        val root = FrameLayout(activity)
        val hostButton = View(activity)
        root.addView(hostButton)
        SettingsSurfaceContext.mark(root, Color.BLACK)
        SettingsDialogMotion.from(hostButton) {
            assertEquals(Color.BLACK, SettingsSurfaceContext.hostColor)
            assertTrue(SettingsUiTheme.colors(activity).dark)
            val body = LinearLayout(activity)
            val button = View(activity)
            body.addView(button)
            val dialog = TcrrryLyricsSettingsUi.bubbleDialog(activity, body)
            dialog.show()
            SettingsDialogMotion.from(button) { assertEquals(Color.BLACK, SettingsSurfaceContext.hostColor) }
            dialog.dismiss()
        }
        assertNull(SettingsSurfaceContext.hostColor)
        assertFalse(SettingsUiTheme.colors(activity).dark)
        SettingsDialogMotion.from(View(activity)) { assertNull(SettingsSurfaceContext.hostColor) }
        activity.finish()
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun hostBackdropStaysOpaqueBlackInsteadOfRevealingArtwork() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        val image = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        image.eraseColor(Color.MAGENTA)
        val backdrop = SettingsHostBackdrop(Color.BLACK)
        try {
            assertTrue(backdrop.drawNative(Canvas(image), View(activity), Paint(), RectF()))
            assertEquals(Color.BLACK, image.getPixel(100, 100))
            var ready = false
            backdrop.capture { ready = it }
            assertTrue(ready)
        } finally { backdrop.close(); image.recycle(); activity.finish() }
    }

    @Test @Config(qualifiers = "w320dp-h640dp-night")
    fun darkHostPageKeepsLongContentScrollableInsideTheAvailableHeight() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            repeat(20) { addView(TextView(activity).apply { text = "Setting $it" }, LinearLayout.LayoutParams(-1, 80)) }
        }
        val page = SettingsHostPage(activity, body)
        activity.setContentView(page)
        layout(page, 280, 480)
        assertTrue(page.height <= 480)
        assertTrue(body.height > page.scroll.height)
        assertTrue(page.scroll.canScrollVertically(1))
        assertTrue(Color.luminance(SettingsSurfaceContext.background(activity)) < .5f)
        page.close()
        activity.finish()
    }
}
