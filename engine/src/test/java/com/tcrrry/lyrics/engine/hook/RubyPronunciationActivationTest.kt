package com.tcrrry.lyrics.engine.hook

import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import com.tcrrry.lyrics.plugin.TestPluginContext
import com.tcrrry.lyrics.plugin.PluginStorage.pluginPreferences
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class RubyPronunciationActivationTest {
    @org.junit.Before fun attach() { TestPluginContext.attach();RubyPronunciationRows.close() }
    @org.junit.After fun close() { RubyPronunciationRows.close();TestPluginContext.current.close() }
    private fun drain() {
        val worker=RubyPronunciationRows::class.java.getDeclaredField("worker").apply { isAccessible=true }
            .get(null) as? java.util.concurrent.ExecutorService
        worker?.submit {}?.get(10,java.util.concurrent.TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
    }
    @Test fun failedCantoneseMatchIsReactivatedWhenPlatformReadingEvidenceArrives() {
        val activity=Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        activity.pluginPreferences("tcrrry_lyrics_policy",0).edit().putBoolean("ruby_pronunciation",true).commit()
        val root=ConstraintLayout(activity);activity.setContentView(root)
        val original="讀音待識別";val reading="du yam doi si bi"
        val header=TextView(activity).apply { id=View.generateViewId();text=reading;textSize=18f }
        val word=TextView(activity).apply { id=View.generateViewId();text=original;textSize=32f }
        root.addView(header,ConstraintLayout.LayoutParams(0,-2).apply { topToTop=0;startToStart=0;endToEnd=0 })
        root.addView(word,ConstraintLayout.LayoutParams(0,-2).apply { topToBottom=header.id;startToStart=0;endToEnd=0 })
        RubyPronunciationRows.bindLine(word,header,original,reading,false);drain()
        assertFalse((0 until root.childCount).any { root.getChildAt(it) is RubyPronunciationRows.RubyView })
        val revision=com.tcrrry.lyrics.engine.lyrics.LanguagePronunciationSupplement.alignmentRevision
        com.tcrrry.lyrics.engine.lyrics.LanguagePronunciationSupplement.rememberChineseProviderRow(original,reading)
        assertTrue(com.tcrrry.lyrics.engine.lyrics.LanguagePronunciationSupplement.alignmentRevision>revision)
        RubyPronunciationRows.bindLine(word,header,original,reading,false);drain()
        val ruby=(0 until root.childCount).map(root::getChildAt).filterIsInstance<RubyPronunciationRows.RubyView>().single()
        repeat(12) {
            root.measure(View.MeasureSpec.makeMeasureSpec(280,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
            root.layout(0,0,280,root.measuredHeight);ruby.onPreDraw()
        }
        val runs=requireNotNull(ruby.placements())
        assertEquals(reading.split(' '),runs.map { it.text })
        assertTrue("header=${header.textSize} runs=${runs.map { it.size }}",runs.all { it.size==header.textSize })
        RubyPronunciationRows.clear(word)
        assertEquals(original,word.text.toString())
        assertTrue((word.text as? android.text.Spanned)?.getSpans(0,word.text.length,android.text.style.ReplacementSpan::class.java).orEmpty().isEmpty())
    }
}
