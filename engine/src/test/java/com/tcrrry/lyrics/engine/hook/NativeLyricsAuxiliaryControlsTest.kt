package com.tcrrry.lyrics.engine.hook

import android.content.ContextWrapper
import android.content.res.Resources
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import com.apple.android.music.player.fragment.PlayerLyricsViewFragment
import com.apple.android.music.player.viewmodel.PlayerLyricsViewModel
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
class NativeLyricsAuxiliaryControlsTest {
    @org.junit.Before fun attach() {
        TestPluginContext.attach(); ja.i0.pronunciationWrites = 0; ja.i0.translationWrites = 0
    }
    @org.junit.After fun close() { NativeLyricsSourceMenu.cleanupPluginRows(); TestPluginContext.current.close(); ja.i0.model = null }
    private fun ttml(pron: String = "boku", trans: String = "我") =
        "<tt><head><metadata><transliterations><transliteration><text for=\"a\">$pron</text></transliteration></transliterations><translations><translation><text for=\"a\">$trans</text></translation></translations></metadata></head><body><div><p itunes:key=\"a\">僕</p></div></body></tt>"
    @Test fun actualTtmlAuxiliariesEnableAvailabilityAndReplaySelectedValuesWithoutChangingPreferences() {
        val model = PlayerLyricsViewModel()
        var pronShown = false; var transShown = false
        model.pronunciation.observer = java.util.function.Consumer { pronShown = it }
        model.translation.observer = java.util.function.Consumer { transShown = it }
        val state = NativeLyricsAuxiliaryControls.State(model, NativeSubtitleLookup(ttml()))
        state.synchronize()
        assertTrue(state.available(true)); assertTrue(state.available(false))
        assertTrue(pronShown); assertTrue(transShown)
        assertEquals(0, ja.i0.pronunciationWrites); assertEquals(0, ja.i0.translationWrites)
    }
    @Test fun explicitHideSurvivesAuxiliaryAvailabilityRepair() {
        val model = PlayerLyricsViewModel(); model.choose(true, false); model.choose(false, false)
        val state = NativeLyricsAuxiliaryControls.State(model, NativeSubtitleLookup(ttml()))
        state.synchronize()
        assertTrue(state.available(true)); assertTrue(state.available(false))
        assertFalse(state.selected(true)); assertFalse(state.selected(false))
    }
    @Test fun emptyAndWhitespaceTracksNeverInventAvailableSubtitles() {
        val model = PlayerLyricsViewModel()
        val state = NativeLyricsAuxiliaryControls.State(model, NativeSubtitleLookup(ttml("&#x20;", "&nbsp;")))
        state.synchronize()
        assertFalse(state.available(true)); assertFalse(state.available(false))
        assertEquals(0, model.pronunciation.events); assertEquals(0, model.translation.events)
    }
    @Test fun nativeRecognizedTrackRemainsAvailableWhenRawLookupDoesNotDescribeIt() {
        val model = PlayerLyricsViewModel(); model.pronunciationAvailable.setValue(true)
        val state = NativeLyricsAuxiliaryControls.State(model, NativeSubtitleLookup(ttml("", "")))
        state.synchronize()
        assertTrue(state.available(true)); assertFalse(state.available(false))
    }
    @Test fun f2AfterHookRepairsNativeAvailabilityOnAnOriginalUnmarkedDocument() {
        val page = PlayerLyricsViewFragment()
        NativeLyricsPronunciationSubtitle.remember(page.h1, ttml())
        assertTrue(NativeLyricsPronunciationSubtitle.isManagedLine(page.h1))
        NativeLyricsAuxiliaryControls.install(javaClass.classLoader!!)
        val method = page.javaClass.getDeclaredMethod("F2", page.h1.javaClass)
        val hook = requireNotNull(TestPluginContext.current.callbacks[method])
        val call = PluginCall(method, page, arrayOf(page.h1), null, null)
        hook.before(call); page.F2(page.h1); hook.after(call)
        assertEquals(true, page.o1.translationAvailable.getValue())
        assertEquals(true, page.o1.pronunciationAvailable.getValue())
    }
    @Test fun adapterCreationPredicatesUseActualTrackAndSelectionBeforeF2Replay() {
        val page = PlayerLyricsViewFragment()
        val pointer = com.apple.android.music.ttml.javanative.model.SongInfo.SongInfoPtr()
        NativeLyricsPronunciationSubtitle.remember(pointer, ttml())
        NativeLyricsAuxiliaryControls.install(javaClass.classLoader!!)
        fun selected(name: String): Boolean {
            val method = page.javaClass.getDeclaredMethod(name, pointer.javaClass)
            val callback = requireNotNull(TestPluginContext.current.callbacks[method])
            val call = PluginCall(method, page, arrayOf(pointer), false, null)
            callback.before(call); callback.after(call)
            return call.result == true
        }
        assertTrue(selected("t2")); assertTrue(selected("u2"))
        page.o1.choose(false, false)
        assertFalse(selected("u2")); assertTrue(selected("t2"))
        NativeLyricsPronunciationSubtitle.remember(pointer, ttml("", ""))
        assertFalse(selected("t2")); assertFalse(selected("u2"))
    }
    @Test fun clearedSongNeverReplaysAvailabilityFromPreviousDocument() {
        val page = PlayerLyricsViewFragment()
        NativeLyricsPronunciationSubtitle.remember(page.h1, ttml())
        NativeLyricsAuxiliaryControls.install(javaClass.classLoader!!)
        for (name in listOf("F2", "t2", "u2")) {
            val method = page.javaClass.getDeclaredMethod(name, page.h1.javaClass)
            val callback = requireNotNull(TestPluginContext.current.callbacks[method])
            val call = PluginCall(method, page, arrayOf(null), false, null)
            callback.before(call); callback.after(call)
            assertEquals(false, call.result)
        }
        assertEquals(false, page.o1.pronunciationAvailable.getValue())
        assertEquals(false, page.o1.translationAvailable.getValue())
        assertEquals(0, page.o1.pronunciation.events)
        assertEquals(0, page.o1.translation.events)
    }
    @Test fun firstPopupGetsAllFourRowsWithoutEarlierAnchorRegistrationAndAllOverloadsAreCovered() {
        val app = RuntimeEnvironment.getApplication()
        val resources = object : Resources(app.assets, app.resources.displayMetrics, app.resources.configuration) {
            override fun getIdentifier(name: String?, type: String?, pkg: String?): Int = when (name) {
                "translations_button" -> 100
                "translations_popup_menu" -> 101
                else -> 0
            }
        }
        val context = object : ContextWrapper(app) { override fun getResources() = resources }
        val menu = LinearLayout(context).apply { id = 101; orientation = LinearLayout.VERTICAL }
        repeat(2) { menu.addView(TextView(context).apply { text = if (it == 0) "显示发音" else "显示翻译" }) }
        val popup = PopupWindow(menu)
        val button = View(context).apply { id = 100 }
        NativeLyricsSourceMenu.installPopupHooks({ 123L }, {})
        val methods = TestPluginContext.current.callbacks.keys.filter { it.name == "showAsDropDown" }
        assertEquals(setOf(1, 3, 4), methods.map { it.parameterCount }.toSet())
        for (method in methods) {
            val args: Array<Any?> = when (method.parameterCount) {
                1 -> arrayOf(button)
                3 -> arrayOf(button, 0, 0)
                else -> arrayOf(button, 0, 0, 0)
            }
            val hook = requireNotNull(TestPluginContext.current.callbacks[method])
            val call = PluginCall(method, popup, args, null, null)
            hook.before(call); hook.after(call)
            assertEquals(4, menu.childCount)
        }
    }
    @Test fun ownedControlsToggleNativePersistedSelectionAndKeepPopupWidthAndCleanup() {
        val app = RuntimeEnvironment.getApplication(); val page = PlayerLyricsViewFragment()
        ja.i0.model = page.o1
        NativeLyricsPronunciationSubtitle.remember(page.h1, ttml())
        val menu = LinearLayout(app).apply { orientation = LinearLayout.VERTICAL }
        val originals = List(2) { TextView(app).apply { text = "native$it" }.also(menu::addView) }
        repeat(2) { menu.addView(NativeLyricsMenuRowLayout(app, "source$it")) }
        val popup = PopupWindow(menu)
        NativeLyricsAuxiliaryControls.synchronize(page)
        repeat(3) { NativeLyricsAuxiliaryControls.extendMenu(page, menu, popup, { 123L }) }
        assertEquals(4, menu.childCount)
        menu.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        assertTrue("replacing native rows must not collapse intrinsic popup width", menu.measuredWidth > 0)
        menu.getChildAt(0).performClick()
        assertEquals(1, ja.i0.pronunciationWrites)
        assertEquals(false, page.o1.pronunciation.getValue())
        menu.getChildAt(1).performClick()
        assertEquals(1, ja.i0.translationWrites)
        assertEquals(false, page.o1.translation.getValue())
        NativeLyricsAuxiliaryControls.restoreMenu(menu)
        assertEquals(4, menu.childCount)
        originals.forEachIndexed { index, view -> assertSame(view, menu.getChildAt(index)) }
    }
    @Test fun nativeOnlyPopupStillSavesExplicitHideWithoutInventingAvailableTracks() {
        val app=RuntimeEnvironment.getApplication();val page=PlayerLyricsViewFragment()
        ja.i0.model=page.o1
        page.o1.pronunciationAvailable.setValue(true)
        val menu=LinearLayout(app)
        repeat(2) { menu.addView(TextView(app)) }
        NativeLyricsAuxiliaryControls.extendMenu(page,menu,PopupWindow(menu),{123L})
        assertTrue(menu.getChildAt(0).isEnabled)
        assertFalse(menu.getChildAt(1).isEnabled)
        menu.getChildAt(0).performClick()
        assertFalse(page.o1.pronunciation.getValue() as Boolean)
        assertEquals(false,NativeLyricsEmphasisVisibility.saved(app))
        NativeLyricsAuxiliaryControls.restoreMenu(menu)
    }
}
