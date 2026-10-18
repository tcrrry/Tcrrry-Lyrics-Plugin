package com.tcrrry.lyrics.engine.hook

import android.app.Activity
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.tcrrry.lyrics.plugin.TestPluginContext
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
class LyricPresentationWatchdogTest {
    @org.junit.Before fun attach() { TestPluginContext.attach() }
    @org.junit.After fun close() { TestPluginContext.current.close() }
    class Access(val list: FrameLayout) : LyricBlurTargetAccess {
        var stopped = false
        var repairs = 0
        var document: Any? = null
        override fun isRecyclerView(view: View) = view === list
        override fun isInstrumentalRow(view: View) = false
        override fun isCreditsRow(view: View) = false
        override fun adapterPosition(view: View) = 9
        override fun nativeFollowSuspended(owner: Any?, recycler: ViewGroup, document: Any?, active: Set<Int>) = stopped
        override fun recoverFollow(owner: Any?, recycler: ViewGroup, document: Any?, active: Set<Int>): Boolean {
            repairs++; this.document = document; stopped = false; return true
        }
    }
    private fun fixture(onTouch: (() -> Unit)? = null): Triple<Activity, OpenSourceLyricBlurPort, Access> {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        val list = FrameLayout(activity)
        if (onTouch != null) list.setOnTouchListener { _, _ -> onTouch(); true }
        list.addView(FrameLayout(activity), FrameLayout.LayoutParams(300, 100))
        activity.setContentView(list)
        list.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
        list.layout(0, 0, 300, 600)
        val access = Access(list)
        val runtime = OpenSourceLyricBlurPort(access)
        runtime.onLyricsViewCreated(Any(), list)
        runtime.onProcessPosition(Any(), 1000)
        runtime.onHighlightsChanged(setOf(9))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        return Triple(activity, runtime, access)
    }
    @Test fun currentRowCanRemainVisibleWhileNativeHighlightAndFollowHaveStopped() {
        val (activity, runtime, access) = fixture()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertEquals(0, access.repairs)
        access.stopped = true
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))
        assertEquals(1, access.repairs)
        assertNotNull(access.document)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertEquals(1, access.repairs) // Healthy playback does not continuously rebind.
        runtime.onLyricsViewDestroyed(Any()) // Unrelated page cannot release this one.
        activity.finish()
    }
    @Test fun touchObservationPreservesNativeListenersAndWaitsForTheUsersInactivity() {
        var touches = 0
        val (activity, runtime, access) = fixture { touches++ }
        access.stopped = true
        fun touch(action: Int) {
            val event = MotionEvent.obtain(0, SystemClock.uptimeMillis(), action, 10f, 10f, 0)
            runtime.onLyricsTouch(access.list, event)
            access.list.dispatchTouchEvent(event)
            event.recycle()
        }
        touch(MotionEvent.ACTION_DOWN)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))
        assertEquals(0, access.repairs)
        touch(MotionEvent.ACTION_UP)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertEquals(0, access.repairs)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(1, access.repairs)
        TestPluginContext.current.close()
        val before = touches
        touch(MotionEvent.ACTION_DOWN)
        assertEquals(before + 1, touches)
        activity.finish()
    }
    @Test fun clearingBlurDoesNotRewriteNativeRowOpacity() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val row = View(activity).apply { alpha = .42f }
        val renderer = LyricBlurRenderer()
        renderer.applyImmediately(mapOf(row to 2f))
        renderer.clearAll()
        assertEquals(.42f, row.alpha, .0001f)
        activity.finish()
    }
}
