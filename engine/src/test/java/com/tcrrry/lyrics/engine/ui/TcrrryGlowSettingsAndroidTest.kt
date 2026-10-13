package com.tcrrry.lyrics.engine.ui

import android.app.Activity
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.SeekBar
import android.widget.TextView
import com.tcrrry.lyrics.engine.model.LyricGlowPosition
import com.tcrrry.lyrics.engine.model.ModuleSettings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class TcrrryGlowSettingsAndroidTest {
    @org.junit.Before fun attachPlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.attach() }
    @org.junit.After fun closePlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.current.close() }
    @Test fun sliderAndPositionSaveTogetherWithoutOverwritingOtherSettings() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        var saved = ModuleSettings(lyricGlowSensitivity = 120, futureBlurEnabled = true)
        val card = TcrrryLyricsSettingsUi.glowSettingsCard(activity, saved, { saved = it }, {})
        activity.setContentView(card)
        val slider = (0 until card.childCount).map(card::getChildAt).filterIsInstance<SeekBar>().single()
        assertEquals(70, slider.progress)
        assertEquals(450, slider.max)
        assertTrue(slider.performAccessibilityAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
            Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 130f) }))
        assertEquals(180, saved.lyricGlowSensitivity)
        val picker = (0 until card.childCount).map(card::getChildAt).filterIsInstance<TextView>()
            .single { it.text.startsWith("辉光触发位置：") }
        picker.performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        fun texts(view: android.view.View): List<TextView> = if (view is android.view.ViewGroup)
            (0 until view.childCount).flatMap { texts(view.getChildAt(it)) } else listOfNotNull(view as? TextView)
        texts(dialog.window!!.decorView).single { it.text == LyricGlowPosition.TAIL_ONLY.displayName }.performClick()
        assertEquals(LyricGlowPosition.TAIL_ONLY, saved.lyricGlowPosition)
        assertEquals(180, saved.lyricGlowSensitivity)
        assertTrue(saved.futureBlurEnabled)
        slider.performAccessibilityAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
            Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 0f) })
        assertEquals(50, saved.lyricGlowSensitivity)
        assertEquals(LyricGlowPosition.TAIL_ONLY, saved.lyricGlowPosition)
        val rebuilt = TcrrryLyricsSettingsUi.glowSettingsCard(activity, saved, {}, {})
        assertEquals(0, (0 until rebuilt.childCount).map(rebuilt::getChildAt).filterIsInstance<SeekBar>().single().progress)
        slider.performAccessibilityAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
            Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 450f) })
        assertEquals(500, saved.lyricGlowSensitivity)
        assertEquals(LyricGlowPosition.TAIL_ONLY, saved.lyricGlowPosition)
        assertTrue(saved.futureBlurEnabled)
        val maximum = TcrrryLyricsSettingsUi.glowSettingsCard(activity, saved, {}, {})
        assertEquals(450, (0 until maximum.childCount).map(maximum::getChildAt).filterIsInstance<SeekBar>().single().progress)
        (0 until card.childCount).map(card::getChildAt).filterIsInstance<TextView>()
            .single { it.text == "恢复默认灵敏度" }.performClick()
        assertEquals(100, saved.lyricGlowSensitivity)
        assertEquals(50, slider.progress)
        assertEquals(LyricGlowPosition.TAIL_ONLY, saved.lyricGlowPosition)
    }

    @Test fun enhancementControlsStayAvailableWithoutAMasterSwitchAndSmoothDefaultsOn() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        val policy = com.tcrrry.lyrics.engine.lyrics.LyricsPreference
        var refreshed = false
        val card = TcrrryLyricsSettingsUi.glowSettingsCard(activity,
            ModuleSettings(cjkKaraokeAnimationEnabled = false), {}, { refreshed = true })
        val children = (0 until card.childCount).map(card::getChildAt)
        assertTrue(children.any { it is SeekBar })
        assertFalse(children.filterIsInstance<TextView>().any { it.text.contains("点击开启") || it.text.contains("点击关闭") })
        assertTrue(policy.smoothShortUnits(activity))
        val smooth = children.filterIsInstance<TextView>().single { it.text == "短单元平滑：开启" }
        assertEquals(SettingsUiTheme.colors(activity).primary, smooth.currentTextColor)
        smooth.performClick()
        assertFalse(policy.smoothShortUnits(activity))
        assertEquals(SettingsUiTheme.colors(activity).text, smooth.currentTextColor)
        smooth.performClick()
        assertTrue(policy.smoothShortUnits(activity))
        smooth.performClick()
        assertTrue(refreshed)
        assertFalse(policy.smoothShortUnits(activity)) // Explicit saved off remains off on later renders.
    }
}
