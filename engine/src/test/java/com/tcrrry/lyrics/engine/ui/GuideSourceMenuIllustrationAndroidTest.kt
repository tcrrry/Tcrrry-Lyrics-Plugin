package com.tcrrry.lyrics.engine.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.ViewFlipper
import com.tcrrry.lyrics.plugin.TestPluginContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlertDialog
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GuideSourceMenuIllustrationAndroidTest {
    @org.junit.Before fun attachPlugin() { TestPluginContext.attach() }
    @org.junit.After fun closePlugin() { TestPluginContext.current.close() }

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    @Test fun menuImageAndSourceOutlineRenderAtCompactWidthsWithoutClipping() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        TcrrryLyricsSettingsUi.showOperationGuide(activity,required=false)
        val dialog=ShadowAlertDialog.getLatestAlertDialog()
        val page=descendants(dialog.window!!.decorView).filterIsInstance<ViewFlipper>().single().getChildAt(0)
        val illustration=descendants(page).filterIsInstance<GuideSourceMenuIllustration>().single()
        assertTrue(illustration.imageAvailable)
        try {
            for (widthDp in listOf(220,300)) {
                val width=(widthDp * activity.resources.displayMetrics.density).toInt()
                page.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
                page.layout(0,0,width,page.measuredHeight)
                for (view in descendants(page).filterIsInstance<android.widget.TextView>()) {
                    assertEquals(0,view.layout.getEllipsisCount(view.layout.lineCount-1))
                    assertTrue(view.bottom <= page.height)
                }
                val bitmap=Bitmap.createBitmap(width,page.height,Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(SettingsUiTheme.colors(activity).surface)
                page.draw(Canvas(bitmap))
                val bounds=illustration.sourceHighlight
                assertTrue(bounds.width() > 0)
                val frameColor=bitmap.getPixel(illustration.left + bounds.centerX().toInt(),
                    illustration.top + bounds.top.toInt())
                assertTrue(Color.alpha(frameColor) > 128)
                assertTrue("Source outline must be red", Color.red(frameColor) > Color.green(frameColor))
                val file=File("build/verification/guide-source-menu-${widthDp}dp.png")
                file.parentFile!!.mkdirs()
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                bitmap.recycle()
            }
        } finally { dialog.dismiss(); activity.finish() }
    }
}
