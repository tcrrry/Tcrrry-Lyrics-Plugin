package com.tcrrry.lyrics.engine.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import android.widget.ProgressBar
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
@Config(manifest=Config.NONE,sdk=[35],qualifiers="w400dp-h840dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OfflineModelManagerAndroidTest {
    private class Models:OfflineModelBackend {
        override val languages=listOf("zh","ja","ko","en","de")
        var installed=setOf("zh","en","ja")
        var downloaded:String?=null;var deleted:String?=null
        var deferQuery=false;var queryCallback:((Set<String>?,String?)->Unit)?=null
        var completion:((String?)->Unit)?=null;var progress:((Bundle)->Unit)?=null
        override fun query(complete:(Set<String>?,String?)->Unit) { if(deferQuery) queryCallback=complete else complete(installed,null) }
        override fun download(language:String,complete:(String?)->Unit) { downloaded=language;completion=complete }
        override fun download(language:String,progress:(Bundle)->Unit,complete:(String?)->Unit) { this.progress=progress;download(language,complete) }
        override fun delete(language:String,complete:(String?)->Unit) { deleted=language;completion=complete }
    }
    @org.junit.Before fun attach() { TestPluginContext.attach() }
    @org.junit.After fun close() { TestPluginContext.current.close() }
    private fun views(view:View):List<View> = listOf(view)+if(view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun texts(view:View)=views(view).filterIsInstance<TextView>()
    private fun settle() { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1)) }
    private fun activity()=Robolectric.buildActivity(Activity::class.java).setup().visible().get()
    private fun latest()=ShadowAlertDialog.getLatestAlertDialog()
    private fun click(dialog:android.app.Dialog,text:String) { assertTrue(texts(dialog.window!!.decorView).single { it.text==text }.performClick());settle() }
    private fun screenshot(dialog:android.app.Dialog,name:String) {
        val root=dialog.window!!.decorView
        root.measure(View.MeasureSpec.makeMeasureSpec(400,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(840,View.MeasureSpec.EXACTLY))
        root.layout(0,0,400,840);root.viewTreeObserver.dispatchOnPreDraw();settle()
        root.measure(View.MeasureSpec.makeMeasureSpec(400,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(840,View.MeasureSpec.EXACTLY));root.layout(0,0,400,840)
        val bitmap=Bitmap.createBitmap(400,840,Bitmap.Config.ARGB_8888);root.draw(Canvas(bitmap))
        val file=File("../../release-audit/translation-split-20261018/$name.png");file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
    }
    @Test fun embeddedPanelIgnoresResultsAfterModeRemovesIt() {
        val activity=activity();val parent=android.widget.LinearLayout(activity)
        activity.setContentView(parent)
        val panel=android.widget.LinearLayout(activity).apply { orientation=1 };parent.addView(panel)
        val models=Models().apply { deferQuery=true }
        TcrrryLyricsSettingsUi.renderOfflinePanel(activity,panel,panel,models);settle()
        val status=texts(panel).single { it.text=="正在读取语言状态…" }
        assertNotNull(models.queryCallback)
        parent.removeView(panel);models.queryCallback!!(setOf("ko"),null);settle()
        assertEquals("正在读取语言状态…",status.text.toString())
    }
    @Test fun translationPageShowsInlinePanelAndCompactMissingExtensionHint() {
        val activity=activity()
        com.tcrrry.lyrics.plugin.PluginStorage.run {
            activity.pluginPreferences("lyrics_operation_guide",0).edit().putBoolean("completed",true).commit()
            activity.pluginPreferences("supplement_translation",0).edit().putString("mode","offline").commit()
        }
        val page=android.widget.LinearLayout(activity)
        TcrrryLyricsSettingsUi.render(activity,page,null,{},com.tcrrry.lyrics.engine.model.ModuleSettings(),{})
        views(page).single { it.contentDescription?.toString()?.startsWith("翻译与注音，")==true }.performClick()
        settle();shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(3))
        val dialog=latest();val root=dialog.window!!.decorView
        assertTrue(texts(root).any { it.text=="已下载语言包" })
        assertFalse(texts(root).any { it.text.toString().startsWith("打开离线翻译") })
        assertTrue(texts(root).any { it.text.toString().startsWith("请导入并启用") })
        screenshot(dialog,"ui-inline-missing-extension")
    }
    @Test fun panelOpensDirectlyWithSearchManagementAndVerification() {
        val models=Models();TcrrryLyricsSettingsUi.showOfflineModelManager(activity(),models);settle()
        val dialog=latest();val labels=texts(dialog.window!!.decorView).map { it.text.toString() }
        assertFalse(labels.any { it.contains("日语") || it.contains("韩语") || it.contains("德语") })
        assertTrue("已下载语言包" in labels);assertTrue("检测离线翻译" in labels)
        assertEquals(1,views(dialog.window!!.decorView).filterIsInstance<EditText>().size)
        assertTrue(labels.any { it.contains("需要开启代理") })
        assertFalse(labels.any { it.contains("已就绪") || it.contains("不可删除") || it.contains("内置必需") })
        screenshot(dialog,"ui-panel")
    }
    @Test fun searchClickAllowsImeAndDoesNotUseGlassPerLanguage() {
        val activity=activity();TcrrryLyricsSettingsUi.showOfflineDownloads(activity,Models());settle()
        val dialog=latest();val root=dialog.window!!.decorView;val input=views(root).filterIsInstance<EditText>().single()
        assertEquals(0,dialog.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        input.performClick();settle();assertTrue(input.hasFocus());assertTrue(input.showSoftInputOnFocus)
        assertTrue(shadowOf(activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager).isSoftInputVisible)
        assertNotNull(SettingsGlassMaterial.style(input))
        input.setText("Korean");settle()
        assertTrue(texts(root).any { it.text=="韩语 · 下载" })
        assertFalse(texts(root).any { it.text=="德语 · 下载" })
        screenshot(dialog,"ui-download")
    }
    @Test fun downloadReportsRealProgressAndPreservesSearch() {
        val models=Models();TcrrryLyricsSettingsUi.showOfflineDownloads(activity(),models);settle()
        val dialog=latest();val root=dialog.window!!.decorView;val input=views(root).filterIsInstance<EditText>().single()
        input.setText("ko");settle();click(dialog,"韩语 · 下载");assertEquals("ko",models.downloaded)
        models.progress!!(Bundle().apply { putInt("percent",37);putString("message","正在下载：37%") });settle()
        val bar=views(root).filterIsInstance<ProgressBar>().single();assertFalse(bar.isIndeterminate);assertEquals(37,bar.progress)
        screenshot(dialog,"ui-progress")
        models.installed+= "ko";models.completion!!(null);settle()
        assertEquals("ko",input.text.toString());assertTrue(texts(root).any { it.text=="韩语 · 已下载" })
        assertFalse(texts(root).any { it.text=="删除韩语" })
    }
    @Test fun deletionLivesOnlyInInstalledManagerAndRequiresConfirmation() {
        val models=Models();TcrrryLyricsSettingsUi.showDownloadedModels(activity(),models);settle()
        val dialog=latest();val labels=texts(dialog.window!!.decorView).map { it.text.toString() }
        assertFalse(labels.any { it.contains("中文") || it.contains("不可删除") })
        assertFalse("删除中文" in labels);assertFalse("删除英语" in labels);assertFalse("删除德语" in labels)
        screenshot(dialog,"ui-installed")
        click(dialog,"删除日语");assertNull(models.deleted)
        click(latest(),"删除");assertEquals("ja",models.deleted)
        models.installed-= "ja";models.completion!!(null);settle()
        assertTrue(models.installed.containsAll(setOf("zh","en")))
    }
    @Test fun failedDownloadRecoversControlsAndDisplaysProxyHint() {
        val models=Models();TcrrryLyricsSettingsUi.showOfflineDownloads(activity(),models);settle()
        val dialog=latest();views(dialog.window!!.decorView).filterIsInstance<EditText>().single().setText("ko");settle();click(dialog,"韩语 · 下载")
        models.completion!!("下载失败，请检查代理后重试");settle()
        assertTrue(texts(dialog.window!!.decorView).single { it.text=="韩语 · 下载" }.isEnabled)
        assertTrue(texts(dialog.window!!.decorView).any { it.text=="下载失败，请检查代理后重试" })
    }
    @Test fun closedPanelIgnoresLateCallbacks() {
        val models=Models().apply { deferQuery=true };TcrrryLyricsSettingsUi.showOfflineDownloads(activity(),models);settle()
        val dialog=latest();val input=views(dialog.window!!.decorView).filterIsInstance<EditText>().single()
        input.setText("ko");dialog.dismiss();settle();models.queryCallback!!(setOf("ko"),null);settle()
        assertFalse(dialog.isShowing);assertEquals("ko",input.text.toString())
    }
    @Test fun splitClientHasModelManagerApiWithoutLoadingAnyEngine() {
        assertNotNull(com.tcrrry.lyrics.plugin.EmbeddedOfflineModels.find(activity()))
    }
}
