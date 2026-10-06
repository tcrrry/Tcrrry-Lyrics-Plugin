package com.tcrrry.lyrics.engine.ui

import android.app.Activity
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class SettingsUiThemeAndroidTest {
    @org.junit.Before fun attachPlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.attach() }
    @org.junit.After fun closePlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.current.close() }
    private lateinit var activity: Activity
    @Before fun setup() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        SettingsUiTheme.setMode(activity, SettingsAppearance.FOLLOW)
        SettingsUiTheme.bind(activity)
    }
    @Test fun hostConfigurationAndSharedOverrideReachBothPages() {
        val night = activity.createConfigurationContext(Configuration().apply { uiMode = Configuration.UI_MODE_NIGHT_YES })
        val day = activity.createConfigurationContext(Configuration().apply { uiMode = Configuration.UI_MODE_NIGHT_NO })
        assertTrue(SettingsUiTheme.colors(night).dark)
        assertFalse(SettingsUiTheme.colors(day).dark)
        SettingsUiTheme.setMode(activity, SettingsAppearance.DARK)
        assertTrue(SettingsUiTheme.colors(day).dark)
        assertFalse(SettingsUiTheme.hostColors(day).dark)
        assertSame(SettingsUiTheme.colors(activity), SettingsUiTheme.currentColors)
        SettingsUiTheme.setMode(activity, SettingsAppearance.LIGHT)
        assertFalse(SettingsUiTheme.colors(night).dark)
    }
    @Test fun manualNightButtonPersistsChoiceAndRequestsImmediateRefresh() {
        var refreshes = 0
        val card = SettingsUiTheme.appearanceCard(activity) { refreshes++ }
        fun find(root: View): TextView? {
            if (root is TextView && root.text.toString() == "夜间") return root
            if (root is ViewGroup) for (i in 0 until root.childCount) find(root.getChildAt(i))?.let { return it }
            return null
        }
        assertTrue(requireNotNull(find(card)).performClick())
        assertEquals(SettingsAppearance.DARK, SettingsUiTheme.mode(activity))
        assertEquals(1, refreshes)
        assertTrue(SettingsUiTheme.colors(activity).dark)
    }
    @Test fun dialogUsesManualAppearanceWithoutChangingHostConfiguration() {
        val original = activity.resources.configuration.uiMode
        for (mode in listOf(SettingsAppearance.LIGHT, SettingsAppearance.DARK)) {
            SettingsUiTheme.setMode(activity, mode)
            val dialog = SettingsUiTheme.dialogBuilder(activity).create()
            val expected = if (mode == SettingsAppearance.DARK) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            assertEquals(expected, dialog.context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
            assertEquals(original, activity.resources.configuration.uiMode)
        }
    }
    @Test fun appearanceRefreshKeepsUnsavedApiTextSelectionAndFocus() {
        val root = FrameLayout(activity)
        fun field(value: String) = EditText(activity).apply { hint = "HTTPS 服务地址"; setText(value) }
        val input = field("未保存的地址")
        root.addView(input)
        activity.setContentView(root)
        input.requestFocus(); input.setSelection(2, 4)
        lateinit var replacement: EditText
        SettingsUiTheme.refreshPreservingInput(root) {
            root.removeAllViews()
            replacement = field("以前保存的地址")
            root.addView(replacement)
        }
        assertEquals("未保存的地址", replacement.text.toString())
        assertEquals(2, replacement.selectionStart)
        assertEquals(4, replacement.selectionEnd)
        assertTrue(replacement.hasFocus())
    }
    @Test fun ordinaryRefreshRestoresScrollAfterLayoutWithoutRestoringOldApiProfileText() {
        val root = FrameLayout(activity)
        fun scroll(value: String): android.widget.ScrollView = android.widget.ScrollView(activity).apply {
            addView(android.widget.LinearLayout(activity).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                addView(EditText(activity).apply { hint = "HTTPS 服务地址"; setText(value) })
                addView(View(activity), android.widget.LinearLayout.LayoutParams(300, 2500))
            })
        }
        val old = scroll("旧API配置")
        root.addView(old)
        activity.setContentView(root)
        fun layout() {
            root.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 400, 600)
        }
        layout(); old.scrollTo(0, 450)
        lateinit var replacement: android.widget.ScrollView
        SettingsUiTheme.refreshPreservingScroll(root) {
            root.removeAllViews(); replacement = scroll("新API配置"); root.addView(replacement)
        }
        layout()
        assertEquals(450, replacement.scrollY)
        assertEquals("新API配置", ((replacement.getChildAt(0) as ViewGroup).getChildAt(0) as EditText).text.toString())
        replacement.scrollTo(0, 800)
        layout()
        assertEquals(800, replacement.scrollY)
    }

}
