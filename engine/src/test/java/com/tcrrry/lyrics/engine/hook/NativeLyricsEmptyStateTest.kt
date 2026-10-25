package com.tcrrry.lyrics.engine.hook

import android.view.View
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class NativeLyricsEmptyStateTest {
    @Test fun completedEmptyLookupHidesSpinnerAndValidContentRestoresItsChildren() {
        val slot=FrameLayout(RuntimeEnvironment.getApplication())
        val spinner=ProgressBar(slot.context)
        slot.addView(spinner)
        slot.visibility=View.GONE // native o2 has already cancelled delayed loading
        val state=NativeLyricsEmptyState()
        state.showSlot(slot,"暂无歌词，请欣赏音乐")
        assertEquals(View.VISIBLE,slot.visibility)
        assertEquals(View.GONE,spinner.visibility)
        assertEquals(2,slot.childCount)
        state.showSlot(slot,"暂无歌词，请欣赏音乐")
        assertEquals(2,slot.childCount)
        assertEquals("暂无歌词，请欣赏音乐",(slot.getChildAt(1) as TextView).text.toString())
        state.clear(finished=true)
        assertEquals(1,slot.childCount)
        assertEquals(View.VISIBLE,spinner.visibility)
        assertEquals(View.GONE,slot.visibility)
    }
    @Test fun titleDoesNotGuessThatAnyMissingLyricsMeansInstrumental() {
        assertEquals("暂无歌词，请欣赏音乐",NativeLyricsEmptyState.message("China-P"))
        assertEquals("纯音乐，请欣赏",NativeLyricsEmptyState.message("Song (Instrumental)"))
    }
}
