package com.tcrrry.lyrics.engine.hook

import android.content.ContextWrapper
import android.content.res.Resources
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import com.apple.android.music.common.views.FullWidthAlphaGradientFlexboxLayout
import com.apple.android.music.player.A
import com.apple.android.music.player.n1
import com.tcrrry.lyrics.engine.ModuleConstants
import com.tcrrry.lyrics.engine.lyrics.DesktopLyricsPresentation
import com.tcrrry.lyrics.plugin.TestPluginContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class NativeAuxiliaryBindingIntegrationTest {
    @org.junit.Before fun attach() { TestPluginContext.attach() }
    @org.junit.After fun close() { PronunciationHeaderLayout.clearAll(); TestPluginContext.current.close() }
    private fun row(): n1.b {
        val app = RuntimeEnvironment.getApplication()
        val resources = object : Resources(app.assets, app.resources.displayMetrics, app.resources.configuration) {
            override fun getIdentifier(name: String?, type: String?, packageName: String?): Int =
                if (type == "layout") 1 else super.getIdentifier(name, type, packageName)
        }
        val context = object : ContextWrapper(app) { override fun getResources() = resources }
        val root = ConstraintLayout(context)
        fun words() = FullWidthAlphaGradientFlexboxLayout(context).apply {
            id = View.generateViewId()
            root.addView(this, ConstraintLayout.LayoutParams(0, -2).apply {
                topToTop = 0; startToStart = 0; endToEnd = 0
            })
        }
        return n1.b().apply { a = root; u = q8.p9().apply { a0 = words(); Z = words() } }
    }
    private fun adapter(): A {
        NativeLyricsPronunciationSubtitle.install(javaClass.classLoader!!,
            TargetBuild(ModuleConstants.TARGET_PACKAGE, "7.0.0-beta", 1606))
        val adapter = A()
        val marker = DesktopLyricsPresentation("QQ音乐", true, true, false, false, true).marker()
        NativeLyricsPronunciationSubtitle.remember(adapter.pointer,
            "$marker<tt timing=\"Word\"><head><metadata><transliterations><transliteration><text for=\"L1\">bo ku ni mo son na ka ko</text></transliteration></transliterations><translations><translation><text for=\"L1\">我也有那样的过去</text></translation></translations></metadata></head><body><div><p itunes:key=\"L1\"><span begin=\"0s\" end=\"1s\">僕にも</span><span begin=\"1s\" end=\"2s\">そんな過去</span></p></div></body></tt>")
        assertTrue("hook must install against verified host shape", TestPluginContext.current.callbacks.keys.any { it.name == "k" })
        return adapter
    }
    @Test fun outerBindingRepairsMissingNativePronunciationAndKeepsTranslationOutOfWordMap() {
        val adapter = adapter(); val row = row()
        adapter.k(row, 0)
        assertFalse(adapter.nativeTranslation); assertFalse(adapter.nativePronunciation)
        assertTrue(adapter.d); assertTrue(adapter.e)
        assertEquals(1, row.u.a0.childCount)
        val header = requireNotNull(PronunciationHeaderLayout.auxiliary(row.u.a0, true))
        val footer = requireNotNull(PronunciationHeaderLayout.auxiliary(row.u.a0, false))
        assertSame(row.a, header.parent); assertSame(row.a, footer.parent)
        val words = row.u.a0.getChildAt(0)
        repeat(5) { adapter.k(row, 0); adapter.l(row, 0, listOf("word-tick")) }
        assertEquals(1, adapter.glyphRebuilds)
        assertSame(words, row.u.a0.getChildAt(0))
        assertSame(header, PronunciationHeaderLayout.auxiliary(row.u.a0, true))
        PronunciationHeaderLayout.clearAuxiliary(row.u.a0, true)
        adapter.k(row, 0)
        assertEquals(1, adapter.glyphRebuilds)
        assertNotNull(PronunciationHeaderLayout.auxiliary(row.u.a0, true))
    }
    @Test fun failedNativeBindRestoresBothHostFlagsAndNextBindingRecovers() {
        val adapter = adapter(); val row = row()
        adapter.failBind = true
        try { adapter.k(row, 0); fail("Expected native failure") } catch (_: RuntimeException) { }
        assertTrue(adapter.d); assertTrue(adapter.e)
        adapter.failBind = false
        adapter.k(row, 0)
        assertTrue(adapter.d); assertTrue(adapter.e)
        assertNotNull(PronunciationHeaderLayout.auxiliary(row.u.a0, true))
        assertNotNull(PronunciationHeaderLayout.auxiliary(row.u.a0, false))
    }
    @Test fun togglePayloadUsesRealChoicesAndUnmanagedRecyclingRestoresNativeBinding() {
        val adapter = adapter(); val row = row()
        adapter.k(row, 0)
        adapter.e = false
        adapter.l(row, 0, listOf(n1.i))
        assertNull(PronunciationHeaderLayout.auxiliary(row.u.a0, true))
        assertNotNull(PronunciationHeaderLayout.auxiliary(row.u.a0, false))
        assertTrue(adapter.d); assertFalse(adapter.e)
        adapter.pointer = Any()
        adapter.k(row, 0)
        assertTrue(adapter.nativeTranslation)
        assertFalse(adapter.nativePronunciation)
        assertNull(PronunciationHeaderLayout.auxiliary(row.u.a0, false))
        assertEquals(2, (row.a as ConstraintLayout).childCount)
    }
}
