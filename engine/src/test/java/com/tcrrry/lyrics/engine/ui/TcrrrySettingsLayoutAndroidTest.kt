package com.tcrrry.lyrics.engine.ui

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.tcrrry.lyrics.engine.CurrentSongDetails
import com.tcrrry.lyrics.engine.hook.CurrentLyricsSourceStatus
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
class TcrrrySettingsLayoutAndroidTest {
    @org.junit.Before fun attachPlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.attach() }
    @org.junit.After fun closePlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.current.close() }
    private fun texts(view: View): List<TextView> = if (view is ViewGroup)
        (0 until view.childCount).flatMap { texts(view.getChildAt(it)) } else listOfNotNull(view as? TextView)
    private fun page(activity: Activity): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        CurrentLyricsSourceStatus.recordNativeApplied(activity, 321)
        TcrrryLyricsSettingsUi.render(activity, this, CurrentSongDetails(321, "一首很长的歌曲名称用于验证卡片自适应布局", "歌手"),
            {}, {}, ModuleSettings(), {}, {})
    }
    @Test fun combinedCardsAndWrappedSourceButtonsCannotOverlapFollowingControls() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        val parent = page(activity)
        val first = parent.getChildAt(0) as ViewGroup
        assertTrue(texts(first).any { it.text == "歌词偏好" })
        assertTrue(texts(first).any { it.text == "歌词源" })
        assertTrue(texts(first).any { it.text.toString().contains("双击不执行操作") })
        assertFalse(texts(first).any { it.text.startsWith("短单元平滑") })
        val status = texts(first).single { !it.isClickable && it.maxLines == 2 }
        status.text = "Apple Music 原生 · 逐字 · 机翻译文 · 离线注音 · 额外信息".repeat(4)
        val source = texts(first).single { it.text.startsWith("自动匹配") }
        source.text = "自动匹配 · Apple Music 原生 · 一个很长的来源名称"
        val width = (300 * activity.resources.displayMetrics.density).toInt()
        parent.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        parent.layout(0, 0, width, parent.measuredHeight)
        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, source.layoutParams.height)
        val row = source.parent as View
        val rowIndex = (0 until first.childCount).single { first.getChildAt(it) === row }
        assertTrue(row.bottom <= first.getChildAt(rowIndex + 1).top)
        for (i in 1 until parent.childCount) assertTrue(parent.getChildAt(i - 1).bottom <= parent.getChildAt(i).top)
        val glow = parent.getChildAt(1)
        assertTrue(texts(glow).any { it.text == "短单元平滑：开启" })
        val pronunciation = (0 until parent.childCount).map(parent::getChildAt).single {
            texts(it).any { t -> t.text == "离线发音与注音" }
        }
        assertTrue(texts(pronunciation).any { it.text == "日语注音：开启" })
        assertTrue(texts(pronunciation).any { it.text == "韩语注音：开启" })
        assertTrue(texts(pronunciation).any { it.text.startsWith("当前歌曲粤语注音") })
    }
    @Test fun matchingDetailsUseScrollableBubbleAndKeepSelectableFullInformation() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        TcrrryLyricsSettingsUi.showMatchingInformation(activity, 321)
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        val all = texts(dialog.window!!.decorView)
        assertTrue(all.any { it.text == "当前匹配信息" })
        assertTrue(all.any { it.isTextSelectable && it.text.toString().contains("321") })
        assertNull(dialog.listView)
        all.single { it.text == "关闭" }.performClick()
        assertFalse(dialog.isShowing)
    }
}
