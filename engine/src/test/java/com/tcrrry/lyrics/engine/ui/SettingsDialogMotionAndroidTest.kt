package com.tcrrry.lyrics.engine.ui

import android.app.Activity
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class SettingsDialogMotionAndroidTest {
    @Test fun glassSamplingIgnoresRevealTransformButTracksLayoutAndScrolling() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        val root = FrameLayout(activity)
        val panel = FrameLayout(activity)
        val glass = View(activity)
        root.addView(panel)
        panel.addView(glass)
        activity.setContentView(root)
        root.layout(0, 0, 400, 800)
        panel.layout(40, 240, 360, 560)
        glass.layout(0, 0, 320, 320)
        val expected = IntArray(2)
        glass.getLocationOnScreen(expected)
        panel.scaleX = .32f
        panel.scaleY = .32f
        panel.translationX = 80f
        panel.translationY = -170f
        val during = IntArray(2)
        SettingsDialogMotion.layoutPositionOnScreen(glass, during)
        assertArrayEquals(expected, during)
        panel.scaleX = 1f
        panel.scaleY = 1f
        panel.translationX = 0f
        panel.translationY = 0f
        val after = IntArray(2)
        SettingsDialogMotion.layoutPositionOnScreen(glass, after)
        assertArrayEquals(during, after)
        root.scrollTo(0, 20)
        SettingsDialogMotion.layoutPositionOnScreen(glass, after)
        assertEquals(expected[1] - 20, after[1])
        activity.finish()
    }

    @Test fun revealCachesOnlyTheBackgroundUntilTheDialogDetaches() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        val root = FrameLayout(activity)
        val scrim = View(activity)
        val panel = FrameLayout(activity)
        val glass = View(activity)
        panel.addView(glass)
        root.addView(scrim)
        root.addView(panel)
        activity.setContentView(root)
        root.layout(0, 0, 400, 800)
        panel.layout(40, 240, 360, 560)
        SettingsDialogMotion.reveal(panel, scrim, Rect(30, 100, 90, 150), background = glass) { it() }
        panel.viewTreeObserver.dispatchOnPreDraw()
        panel.viewTreeObserver.dispatchOnPreDraw()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(1f, panel.scaleX, .001f)
        assertEquals(0f, panel.translationY, .001f)
        assertEquals(View.LAYER_TYPE_NONE, panel.layerType)
        assertEquals(View.LAYER_TYPE_HARDWARE, glass.layerType)
        root.removeView(panel)
        assertEquals(View.LAYER_TYPE_NONE, glass.layerType)
        activity.finish()
    }

    @Test fun revealWaitsForTheWindowAndPanelToStopRelayingOut() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        val root = FrameLayout(activity)
        val panel = View(activity)
        val scrim = View(activity)
        root.addView(panel)
        activity.setContentView(root)
        root.layout(0, 0, 400, 800)
        panel.layout(60, 240, 360, 560)
        var preparations = 0
        SettingsDialogMotion.reveal(panel, scrim, null) { preparations++ }
        panel.viewTreeObserver.dispatchOnPreDraw()
        assertEquals(0, preparations)
        root.layout(0, 0, 360, 800)
        panel.layout(40, 240, 340, 560)
        panel.viewTreeObserver.dispatchOnPreDraw()
        assertEquals(0, preparations)
        panel.viewTreeObserver.dispatchOnPreDraw()
        assertEquals(1, preparations)
        activity.finish()
    }

    @Test fun closingDuringBackdropCapturePreventsTheLateReveal() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        val root = FrameLayout(activity)
        val panel = View(activity)
        val scrim = View(activity)
        root.addView(panel)
        root.addView(scrim)
        activity.setContentView(root)
        root.layout(0, 0, 400, 800)
        panel.layout(60, 240, 340, 560)
        var ready: (() -> Unit)? = null
        val stop = SettingsDialogMotion.reveal(panel, scrim, null) { ready = it }
        panel.viewTreeObserver.dispatchOnPreDraw()
        panel.viewTreeObserver.dispatchOnPreDraw()
        assertNotNull(ready)
        stop()
        var closed = false
        SettingsDialogMotion.conceal(panel, scrim, null) { closed = true }
        ready!!.invoke()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertTrue(closed)
        assertEquals(0f, panel.alpha, .001f)
        activity.finish()
    }

    @Test fun closingReturnsToTheOriginAndCompletesOnceAfterTheAnimation() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        val root = FrameLayout(activity)
        val panel = View(activity)
        val scrim = View(activity)
        root.addView(panel)
        root.addView(scrim)
        activity.setContentView(root)
        root.layout(0, 0, 400, 800)
        panel.layout(60, 240, 340, 560)
        val origin = Rect(20, 100, 80, 140)
        var completions = 0
        SettingsDialogMotion.conceal(panel, scrim, origin) { completions++ }
        assertEquals(0, completions)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(1, completions)
        assertEquals(1f, panel.scaleX, .001f)
        assertEquals(0f, panel.alpha, .001f)
        assertEquals(0f, panel.translationX, .001f)
        assertEquals(0f, panel.translationY, .001f)
        assertEquals(0f, scrim.alpha, .001f)
        activity.finish()
    }

    @Test fun returnTargetUsesThePlayerButtonInsteadOfTheLongPressPointAndTracksScrolling() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        val root = FrameLayout(activity)
        val row = View(activity)
        val button = View(activity)
        root.addView(row)
        root.addView(button)
        activity.setContentView(root)
        root.layout(0, 0, 400, 800)
        row.layout(20, 600, 320, 650)
        button.layout(20, 40, 70, 90)
        lateinit var target: SettingsDialogMotion.ReturnTarget
        SettingsDialogMotion.from(row, android.graphics.PointF(271f, 618f), returnTo = button) {
            target = SettingsDialogMotion.returnTarget()!!
            assertEquals(Rect(271, 618, 271, 618), SettingsDialogMotion.origin())
            val location = IntArray(2)
            button.getLocationOnScreen(location)
            assertEquals(Rect(location[0], location[1], location[0] + 50, location[1] + 50), target.bounds())
            SettingsDialogMotion.from(row) { assertNotSame(target, SettingsDialogMotion.returnTarget()) }
            assertSame(target, SettingsDialogMotion.returnTarget())
        }
        assertNull(SettingsDialogMotion.returnTarget())
        val before = target.bounds()!!
        root.scrollTo(0, 10)
        assertEquals(before.top - 10, target.bounds()!!.top)
        button.visibility = View.GONE
        assertNull(target.bounds())
        activity.finish()
    }

    @Test fun detachingDuringAMorphReleasesItAndCompletesOnlyOnce() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        val root = FrameLayout(activity)
        val panel = View(activity)
        val scrim = View(activity)
        root.addView(panel)
        activity.setContentView(root)
        root.layout(0, 0, 400, 800)
        panel.layout(60, 240, 340, 560)
        var completions = 0
        SettingsDialogMotion.conceal(panel, scrim, Rect(20, 40, 70, 90)) { completions++ }
        root.removeView(panel)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(1, completions)
        activity.finish()
    }

    @Test fun touchOriginSurvivesTheSourcePopupDetachingAndDoesNotLeakToTheNextOpen() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        val root = FrameLayout(activity)
        val row = View(activity)
        root.addView(row)
        activity.setContentView(root)
        root.layout(0, 0, 400, 800)
        row.layout(20, 600, 320, 650)
        SettingsDialogMotion.from(row, android.graphics.PointF(271f, 618f)) {
            root.removeView(row)
            assertEquals(Rect(271, 618, 271, 618), SettingsDialogMotion.origin())
        }
        assertNull(SettingsDialogMotion.origin())
        activity.finish()
    }

    @Test fun revealAndExitKeepTheSameScreenPointAsTheirScalePivot() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        val root = FrameLayout(activity)
        val panel = FrameLayout(activity)
        val scrim = View(activity)
        root.addView(panel, FrameLayout.LayoutParams(280, 320, android.view.Gravity.CENTER))
        root.addView(scrim)
        activity.setContentView(root)
        root.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 400, 800)
        val origin = Rect(270, 618, 270, 618)
        val stop = SettingsDialogMotion.reveal(panel, scrim, origin) { it() }
        panel.viewTreeObserver.dispatchOnPreDraw()
        panel.viewTreeObserver.dispatchOnPreDraw()
        val clock = shadowOf(Looper.getMainLooper())
        clock.idleFor(Duration.ofSeconds(1))
        val location = IntArray(2)
        SettingsDialogMotion.layoutPositionOnScreen(panel, location)
        assertEquals(origin.exactCenterX(), location[0] + panel.pivotX, .001f)
        assertEquals(origin.exactCenterY(), location[1] + panel.pivotY, .001f)
        stop()
        SettingsDialogMotion.conceal(panel, scrim, origin) {}
        clock.idleFor(Duration.ofMillis(100))
        SettingsDialogMotion.layoutPositionOnScreen(panel, location)
        assertEquals(origin.exactCenterX(), location[0] + panel.pivotX, .001f)
        assertEquals(origin.exactCenterY(), location[1] + panel.pivotY, .001f)
        assertEquals(0f, panel.translationX, .001f)
        assertEquals(0f, panel.translationY, .001f)
        activity.finish()
    }
}
