package com.tcrrry.lyrics.plugin

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.tcrrry.lyrics.engine.config.EmbeddedConfigurationSession
import com.tcrrry.lyrics.engine.config.HostPrivateEmbeddedStorage
import com.tcrrry.lyrics.engine.ui.SmoothSourceTicker
import com.tcrrry.lyrics.plugin.PluginStorage.pluginPreferences
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class SettingsHomeRefreshAndroidTest {
    @Before fun attach() { TestPluginContext.attach() }
    @After fun close() { TestPluginContext.current.close() }

    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()

    private fun open(root: View, name: String) {
        views(root).single { it.contentDescription?.toString()?.startsWith("$name，") == true }.performClick()
    }

    private fun session(activity: Activity): Pair<dev.amenhancer.plugin.api.PluginSettingsSession, EmbeddedConfigurationSession> {
        activity.pluginPreferences("lyrics_operation_guide", Context.MODE_PRIVATE).edit().putBoolean("completed", true).commit()
        val configuration = EmbeddedConfigurationSession(HostPrivateEmbeddedStorage(activity))
        val plugin = LyricsPlugin()
        LyricsPlugin::class.java.getDeclaredField("configuration").apply { isAccessible = true }.set(plugin, configuration)
        return plugin.createSettings(activity) to configuration
    }

    @Test fun preferenceChoicesKeepTheExistingHomeAndTickerAttached() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        val (session, _) = session(activity)
        activity.setContentView(session.view)
        val home = views(session.view).toList()
        val ticker = home.filterIsInstance<SmoothSourceTicker>().single()
        var detached = 0
        ticker.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) { detached++ }
        })
        open(session.view, "歌词偏好")
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        val controls = views(dialog.window!!.decorView).filterIsInstance<TextView>()
        for (choice in listOf("匹配优先级：质量优先", "匹配优先级：逐字优先", "发音排版：字词对应", "发音排版：整行发音")) {
            controls.single { it.contentDescription == choice }.performClick()
            assertEquals(home, views(session.view))
            assertSame(ticker, views(session.view).filterIsInstance<SmoothSourceTicker>().single())
            assertEquals(0, detached)
        }
        dialog.dismiss()
        session.close()
        activity.finish()
    }

    @Test fun reopeningGlowReadsCurrentSettingsWithoutRebuildingHome() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        val (session, configuration) = session(activity)
        activity.setContentView(session.view)
        val ticker = views(session.view).filterIsInstance<SmoothSourceTicker>().single()
        configuration.saveSettings(configuration.settings().copy(lyricGlowSensitivity = 420))
        open(session.view, "歌词效果")
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(views(dialog.window!!.decorView).filterIsInstance<TextView>().any { it.text == "辉光灵敏度：420%" })
        assertSame(ticker, views(session.view).filterIsInstance<SmoothSourceTicker>().single())
        dialog.dismiss()
        session.close()
        activity.finish()
    }
}
