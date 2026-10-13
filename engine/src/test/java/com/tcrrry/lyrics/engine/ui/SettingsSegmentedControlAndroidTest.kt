package com.tcrrry.lyrics.engine.ui

import android.app.Activity
import android.view.MotionEvent
import android.view.View
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class SettingsSegmentedControlAndroidTest {
    @Test fun draggingCommitsTheReleasedChoiceOnceAndCancellationKeepsTheOldChoice() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val selected = mutableListOf<Int>()
        val group = SettingsSegmentedControl(activity, "匹配优先级", listOf("质量优先", "逐字优先"), 0) { selected += it }
        group.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(80, View.MeasureSpec.EXACTLY))
        group.layout(0, 0, 320, 80)
        fun touch(action: Int, x: Float) {
            val event = MotionEvent.obtain(0, 100, action, x, 40f, 0)
            try { group.dispatchTouchEvent(event) } finally { event.recycle() }
        }
        touch(MotionEvent.ACTION_DOWN, 70f)
        touch(MotionEvent.ACTION_MOVE, 250f)
        assertEquals(0, group.selectedIndex)
        touch(MotionEvent.ACTION_UP, 250f)
        assertEquals(1, group.selectedIndex)
        assertEquals(listOf(1), selected)
        assertTrue(group.labels[1].isSelected)
        touch(MotionEvent.ACTION_DOWN, 250f)
        touch(MotionEvent.ACTION_MOVE, 70f)
        touch(MotionEvent.ACTION_CANCEL, 70f)
        assertEquals(1, group.selectedIndex)
        assertEquals(listOf(1), selected)
        touch(MotionEvent.ACTION_DOWN, 250f)
        touch(MotionEvent.ACTION_MOVE, 70f)
        touch(MotionEvent.ACTION_UP, 70f)
        assertEquals(0, group.selectedIndex)
        assertEquals(listOf(1, 0), selected)
        group.labels[0].performClick()
        assertEquals(listOf(1, 0), selected)
    }
}
