package dev.kifranei.ampp.media

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35], qualifiers = "zh-rCN")
class IosPlayerMoreMenuViewTest {
    @Test fun omitsUnavailableDownloadShortcutSlotInsteadOfBlankDisabledButton() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val menu = IosPlayerMoreMenuView(activity, dark = false) { _, _ -> }
        val entries = listOf(
            NativePlayerMenuEntry(
                action = PlayerMenuAction("FAVORITE_ITEM", 0),
                title = "喜爱",
                subtitle = null,
                icon = ColorDrawable(Color.BLACK),
                enabled = true,
                checked = false,
                perform = {},
            ),
            NativePlayerMenuEntry(
                action = PlayerMenuAction("SHARE_SONG", 1),
                title = "分享",
                subtitle = null,
                icon = ColorDrawable(Color.BLACK),
                enabled = true,
                checked = false,
                perform = {},
            ),
            NativePlayerMenuEntry(
                action = PlayerMenuAction("ADD_TO_PLAYLIST", 2),
                title = "添加到播放列表…",
                subtitle = null,
                icon = ColorDrawable(Color.BLACK),
                enabled = true,
                checked = false,
                perform = {},
            ),
        )
        menu.update(entries)

        val content = menu.getChildAt(0) as LinearLayout
        val shortcutBar = content.getChildAt(0) as LinearLayout
        assertEquals(2, shortcutBar.childCount)

        val labels = mutableListOf<String>()
        for (i in 0 until shortcutBar.childCount) {
            val col = shortcutBar.getChildAt(i) as ViewGroup
            val iv = col.getChildAt(0) as ImageView
            val tv = col.getChildAt(1) as TextView
            assertNotNull("Shortcut icon must not be null", iv.drawable)
            assertEquals(View.VISIBLE, col.visibility)
            labels += tv.text.toString()
        }
        assertEquals(listOf("喜爱", "分享"), labels)
        assertFalse(labels.contains("下载"))
    }
}
