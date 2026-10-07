package com.tcrrry.lyrics.engine.hook

import android.view.View
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import com.apple.android.music.player.Y0
import com.apple.android.music.player.n1
import com.tcrrry.lyrics.plugin.TestPluginContext
import dev.amenhancer.plugin.api.PluginCall
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class NativeLineSubtitleRepairTest {
    @org.junit.Before fun attach() { TestPluginContext.attach() }
    @org.junit.After fun close() { TestPluginContext.current.close() }
    private class FutureTextView : TextView(RuntimeEnvironment.getApplication()) {
        var pending: CharSequence? = null
        override fun getText(): CharSequence = pending?.let { pending = null; super.setText(it); it } ?: super.getText()
    }
    @Test fun emptyNativeFutureDoesNotOverwriteFilledTranslationOnNextLayout() {
        val view = FutureTextView().apply { text = "上一首的译文"; pending = ""; visibility = View.GONE; alpha = 0f }
        NativeLineLyricsPresentation.repairText(view, "", "原生歌词搭配QQ音乐译文", true)
        assertEquals("原生歌词搭配QQ音乐译文", view.text.toString())
        assertEquals("原生歌词搭配QQ音乐译文", view.text.toString())
        assertEquals(View.VISIBLE, view.visibility)
        assertTrue(view.alpha >= .35f)
    }
    @Test fun cachedRowNeverRetainsAnotherLinesTranslationAndExplicitHideWins() {
        val view = TextView(RuntimeEnvironment.getApplication()).apply { text = "旧的译文" }
        NativeLineLyricsPresentation.repairText(view, "", "新的译文", true)
        assertEquals("新的译文", view.text.toString())
        NativeLineLyricsPresentation.repairText(view, "", "新的译文", false)
        assertEquals(View.GONE, view.visibility)
        NativeLineLyricsPresentation.repairText(view, "", null, true)
        assertEquals(View.GONE, view.visibility)
    }
    @Test fun nativeTextWinsAndDuplicatedOriginalIsNotShownAsTranslation() {
        val view = TextView(RuntimeEnvironment.getApplication())
        NativeLineLyricsPresentation.repairText(view, "<span>原生译文 &amp; 正文</span>", "QQ译文", true)
        assertEquals("原生译文 & 正文", view.text.toString())
        NativeLineLyricsPresentation.repairText(view, "<span>same</span>", "QQ译文", true, "<b>same</b>")
        assertEquals(View.GONE, view.visibility)
    }
    @Test fun wholeLineAdapterBindingRecoversBothAuxiliariesFromOriginalTtml() {
        val app = RuntimeEnvironment.getApplication()
        val root = ConstraintLayout(app)
        fun text() = TextView(app).apply {
            id = View.generateViewId()
            root.addView(this, ConstraintLayout.LayoutParams(0, -2).apply { topToTop = 0; startToStart = 0; endToEnd = 0 })
        }
        val binding = q8.l9().apply { b0 = text(); Z = text(); a0 = text() }
        val row = n1.b().apply { a = root; u = binding }
        val adapter = Y0()
        NativeLyricsPronunciationSubtitle.remember(adapter.pointer,
            "<tt><head><metadata><transliterations><transliteration><text for=\"a\">bo ku ni mo son na ka ko</text></transliteration></transliterations><translations><translation><text for=\"a\">我也有那样的过去</text></translation></translations></metadata></head><body><div><p itunes:key=\"a\">僕にもそんな過去</p></div></body></tt>")
        NativeLineLyricsPresentation.install(javaClass.classLoader!!)
        val method = adapter.javaClass.getDeclaredMethod("k", androidx.recyclerview.widget.`RecyclerView$D`::class.java, Int::class.javaPrimitiveType)
        val callback = requireNotNull(TestPluginContext.current.callbacks[method])
        val call = PluginCall(method, adapter, arrayOf(row, 0), null, null)
        callback.before(call)
        // Model Apple's getter loss and stale cached views during its own binding.
        binding.Z.text = ""; binding.Z.visibility = View.GONE
        binding.a0.text = "上一句译文"; binding.a0.visibility = View.GONE
        callback.after(call)
        assertEquals("bo ku ni mo son na ka ko", binding.Z.text.toString())
        assertEquals("我也有那样的过去", binding.a0.text.toString())
        assertEquals(View.VISIBLE, binding.Z.visibility); assertEquals(View.VISIBLE, binding.a0.visibility)
        adapter.d = false
        val hidden = PluginCall(method, adapter, arrayOf(row, 0), null, null)
        callback.before(hidden); callback.after(hidden)
        assertEquals(View.GONE, binding.a0.visibility)
        assertEquals(View.VISIBLE, binding.Z.visibility)
        NativeLineLyricsPresentation.clear(root)
    }
}
