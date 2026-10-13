package com.tcrrry.lyrics.engine.ui

import android.app.Activity
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
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
        SettingsUiTheme.bind(activity)
    }
    @Test fun colorsAlwaysFollowHostConfiguration() {
        val night = activity.createConfigurationContext(Configuration().apply { uiMode = Configuration.UI_MODE_NIGHT_YES })
        val day = activity.createConfigurationContext(Configuration().apply { uiMode = Configuration.UI_MODE_NIGHT_NO })
        assertTrue(SettingsUiTheme.colors(night).dark)
        assertFalse(SettingsUiTheme.colors(day).dark)
        assertEquals(SettingsUiTheme.hostColors(day), SettingsUiTheme.colors(day))
        assertSame(SettingsUiTheme.colors(activity), SettingsUiTheme.currentColors)
    }
    @Test fun dialogUsesHostAppearanceWithoutChangingHostConfiguration() {
        val original = activity.resources.configuration.uiMode
        val dialog = SettingsUiTheme.dialogBuilder(activity).create()
        assertEquals(original and Configuration.UI_MODE_NIGHT_MASK,
            dialog.context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
        assertEquals(original, activity.resources.configuration.uiMode)
    }
    @Test fun contentDialogHasItsFinalWindowSizeBeforeShowing() {
        val dialog = SettingsUiTheme.contentDialog(activity, FrameLayout(activity))
        dialog.create()
        val window = requireNotNull(dialog.window)
        val before = window.attributes
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, before.width)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, before.height)
        assertEquals(0, before.windowAnimations)
        assertEquals(android.view.Gravity.FILL, before.gravity)
        val floating = android.util.TypedValue()
        assertTrue(dialog.context.theme.resolveAttribute(android.R.attr.windowIsFloating, floating, true))
        assertEquals(0, floating.data)
        dialog.show()
        assertEquals(before.width, window.attributes.width)
        assertEquals(before.height, window.attributes.height)
        dialog.dismiss()
    }

    @Test fun dismissAndCancelShareOneExitAndNotifyOnlyWhenItFinishes() {
        var exits = 0
        var dismissals = 0
        var cancellations = 0
        var finish: (() -> Unit)? = null
        val dialog = SettingsUiTheme.contentDialog(activity, FrameLayout(activity)) {
            exits++
            finish = it
        }
        dialog.setOnDismissListener { dismissals++ }
        dialog.setOnCancelListener { cancellations++ }
        dialog.show()
        dialog.cancel()
        dialog.dismiss()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(1, exits)
        assertEquals(1, cancellations)
        assertEquals(0, dismissals)
        assertTrue(dialog.isShowing)
        finish!!.invoke()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertFalse(dialog.isShowing)
        assertEquals(1, dismissals)
    }

    @Test fun hostFinishingSkipsExitAnimationForImmediateCleanup() {
        var exits = 0
        val dialog = SettingsUiTheme.contentDialog(activity, FrameLayout(activity)) { exits++ }
        dialog.show()
        activity.finish()
        dialog.dismiss()
        assertFalse(dialog.isShowing)
        assertEquals(0, exits)
    }
    @Test fun refreshKeepsUnsavedApiTextSelectionAndFocus() {
        val root = FrameLayout(activity)
        fun field(value: String) = EditText(activity).apply { hint = "HTTPS 服务地址"; setText(value) }
        val input = field("未保存的地址")
        root.addView(input)
        activity.setContentView(root)
        input.requestFocus(); input.setSelection(2, 4)
        lateinit var replacement: EditText
        SettingsUiTheme.refreshPreservingInput(root) {
            root.removeAllViews(); replacement = field("以前保存的地址"); root.addView(replacement)
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
