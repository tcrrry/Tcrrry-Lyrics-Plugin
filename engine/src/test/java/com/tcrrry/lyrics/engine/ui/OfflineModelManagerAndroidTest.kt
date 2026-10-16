package com.tcrrry.lyrics.engine.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import com.tcrrry.lyrics.plugin.OfflineModelBackend
import com.tcrrry.lyrics.plugin.TestPluginContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlertDialog
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OfflineModelManagerAndroidTest {
    private class Models : OfflineModelBackend {
        override val languages = listOf("zh", "ja", "ko", "en", "de")
        var installed = setOf("zh")
        var downloaded: String? = null
        var deleted: String? = null
        var queryCallback: ((Set<String>?, String?) -> Unit)? = null
        var completion: ((String?) -> Unit)? = null
        var deferQuery = false
        override fun query(complete: (Set<String>?, String?) -> Unit) {
            if (deferQuery) queryCallback = complete else complete(installed, null)
        }
        override fun download(language: String, complete: (String?) -> Unit) { downloaded = language; completion = complete }
        override fun delete(language: String, complete: (String?) -> Unit) { deleted = language; completion = complete }
    }
    @org.junit.Before fun attach() { TestPluginContext.attach() }
    @org.junit.After fun close() { TestPluginContext.current.close() }
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun texts(view: View) = views(view).filterIsInstance<TextView>()
    private fun settle() { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1)) }
    private fun open(models: Models): android.app.AlertDialog {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        TcrrryLyricsSettingsUi.showOfflineModelManager(activity, models)
        settle()
        return ShadowAlertDialog.getLatestAlertDialog()
    }
    @Test fun searchesUsingSharedGlassFieldAndDownloadsWithoutLosingSearch() {
        val models = Models()
        val dialog = open(models)
        val root = dialog.window!!.decorView
        assertTrue(views(root).none { it is android.widget.Spinner })
        val input = views(root).filterIsInstance<EditText>().single()
        assertEquals(SettingsGlassKind.Field, SettingsGlassMaterial.style(input)?.kind)
        input.setText("Japanese")
        assertEquals(1, texts(root).count { it.contentDescription?.startsWith("选择") == true })
        assertTrue(texts(root).any { it.text.startsWith("日语 · ja") })
        val download = texts(root).single { it.text == "下载日语与中文模型" }
        assertEquals(SettingsGlassKind.Button, SettingsGlassMaterial.style(download)?.kind)
        download.performClick()
        assertEquals("ja", models.downloaded)
        assertFalse(download.isEnabled)
        models.installed = setOf("zh", "ja")
        models.completion!!(null)
        settle()
        assertEquals("Japanese", input.text.toString())
        assertTrue(download.isEnabled)
        assertTrue(texts(root).any { it.text == "日语 · ja\n已下载" })
        assertTrue(texts(root).single { it.text == "删除日语模型" }.isEnabled)
    }
    @Test fun downloadFailureRecoversButtonsAndEnglishCannotBeDeleted() {
        val models = Models()
        val root = open(models).window!!.decorView
        val download = texts(root).single { it.text == "下载日语与中文模型" }
        download.performClick(); models.completion!!("下载失败，请重试"); settle()
        assertTrue(download.isEnabled)
        assertTrue(texts(root).any { it.text == "下载失败，请重试" })
        views(root).filterIsInstance<EditText>().single().setText("en")
        texts(root).single { it.contentDescription == "选择英语语言包" }.performClick()
        assertFalse(texts(root).single { it.text == "删除英语模型" }.isEnabled)
        texts(root).single { it.text == "下载英语与中文模型" }.performClick()
        assertEquals("en", models.downloaded)
    }
    @Test fun asyncReplyAfterDismissalCannotUpdateTheClosedWindow() {
        val models = Models().apply { deferQuery = true }
        val dialog = open(models)
        val status = texts(dialog.window!!.decorView).single { it.text == "正在读取语言包…" }
        dialog.dismiss(); settle()
        models.queryCallback!!(setOf("zh", "ja"), null); settle()
        assertEquals("正在读取语言包…", status.text)
    }
    @Test fun standaloneOrOlderAdapterKeepsItsExistingManager() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        assertNull(com.tcrrry.lyrics.plugin.EmbeddedOfflineModels.find(activity))
    }
    @Test fun deletionRequiresConfirmationAndKeepsOtherModels() {
        val models = Models().apply { installed = setOf("ja", "zh") }
        val dialog = open(models)
        val root = dialog.window!!.decorView
        texts(root).single { it.text == "删除日语模型" }.performClick()
        val confirmation = ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(dialog !== confirmation)
        assertNull(models.deleted)
        texts(confirmation.window!!.decorView).single { it.text == "删除" }.performClick()
        assertEquals("ja", models.deleted)
        models.installed = setOf("zh"); models.completion!!(null); settle()
        assertFalse(texts(root).single { it.text == "删除日语模型" }.isEnabled)
        assertTrue(texts(root).any { it.text == "中文 · zh\n已下载" })
    }
    @Test fun compactPanelRendersWithoutClippedTextAndWithoutGlassOnEachLanguageRow() {
        val dialog = open(Models())
        val body = texts(dialog.window!!.decorView).single { it.text == "离线翻译语言包" }.parent as View
        for (widthDp in listOf(260, 320)) {
            val width = (widthDp * body.resources.displayMetrics.density).toInt()
            body.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            body.layout(0, 0, width, body.measuredHeight)
            texts(body).forEach { text ->
                if (text.contentDescription?.startsWith("选择") == true) assertNull(SettingsGlassMaterial.style(text))
                if (text.layout != null && text.layout.lineCount > 0)
                    assertEquals(0, text.layout.getEllipsisCount(text.layout.lineCount - 1))
            }
            val bitmap = Bitmap.createBitmap(width, body.height, Bitmap.Config.ARGB_8888)
            body.draw(Canvas(bitmap))
            val file = File("build/verification/offline-models-${widthDp}dp.png")
            file.parentFile!!.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
