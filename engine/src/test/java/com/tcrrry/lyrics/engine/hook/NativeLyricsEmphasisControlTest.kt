package com.tcrrry.lyrics.engine.hook

import android.app.Activity
import android.widget.TextView
import com.tcrrry.lyrics.engine.lyrics.DesktopLyricsPresentation
import com.tcrrry.lyrics.plugin.TestPluginContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class NativeLyricsEmphasisControlTest {
    @org.junit.Before fun attach() { TestPluginContext.attach() }
    @org.junit.After fun close() { TestPluginContext.current.close() }
    @Test fun openPopupUpdatesAfterEnrichmentAndDoesNotKeepPreviousSongCapability() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val id = 987123L
        val row = NativeLyricsMenuRowLayout(activity, "突出：歌词")
        var chosen: Boolean? = null
        NativeLyricsEmphasisControl.bind(row, activity, id, { true }) { chosen = it }
        activity.setContentView(row)
        assertFalse(row.isEnabled)
        val doc = DesktopLyricsPresentation("未知", true, false, false, false, true).marker() + "<tt/>"
        CurrentLyricsSourceStatus.rememberCandidate(activity, id, "APPLE_NATIVE", doc)
        CurrentLyricsSourceStatus.recordApplied(activity, id, false)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(row.isEnabled)
        row.performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(true, chosen)
        assertEquals("突出：发音", (row.getChildAt(0) as TextView).text.toString())
        CurrentLyricsSourceStatus.recordNativeApplied(activity, id + 1)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertFalse(row.isEnabled)
        activity.finish()
    }
}
