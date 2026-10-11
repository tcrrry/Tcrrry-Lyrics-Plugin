package com.tcrrry.lyrics.engine.hook

import android.content.Context
import com.apple.android.music.player.viewmodel.PlayerLyricsViewModel
import com.tcrrry.lyrics.plugin.PluginStorage
import com.tcrrry.lyrics.plugin.PluginStorage.pluginPreferences
import com.tcrrry.lyrics.plugin.TestPluginContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class NativeLyricsVisibilityPersistenceTest {
    @org.junit.Before fun attach() { TestPluginContext.attach();ja.i0.pronunciationWrites=0;ja.i0.translationWrites=0 }
    @org.junit.After fun close() { ja.i0.model=null;TestPluginContext.current.close() }
    private val app get()=RuntimeEnvironment.getApplication()
    private val prefs get()=app.pluginPreferences("japanese_pronunciation",Context.MODE_PRIVATE)
    private fun state(model: PlayerLyricsViewModel)=NativeLyricsAuxiliaryControls.State(model,
        NativeSubtitleLookup("<tt><head><metadata><transliterations><transliteration><text for=\"a\">boku</text></transliteration></transliterations></metadata></head><body><div><p itunes:key=\"a\">僕</p></div></body></tt>"),app)
    @Test fun explicitHideSurvivesReloadedPluginPreferencesAndNewNativeDefault() {
        val first=PlayerLyricsViewModel();ja.i0.model=first
        prefs.edit().putBoolean("primary",true).apply()
        assertTrue(state(first).select(true,false))
        assertFalse(first.pronunciation.getValue() as Boolean)
        assertFalse(prefs.getBoolean("primary",true))
        // Reload from the plugin's real JSON preference file, as on restart.
        (PluginStorage::class.java.getDeclaredField("preferences").apply { isAccessible=true }.get(null) as MutableMap<*,*>).clear()
        val restarted=PlayerLyricsViewModel();ja.i0.model=restarted
        assertFalse(NativeLyricsEmphasisVisibility.effective(app,true))
        assertFalse(state(restarted).selected(true))
        state(restarted).synchronize()
        assertFalse(restarted.pronunciation.getValue() as Boolean)
        assertTrue(restarted.pronunciationAvailable.getValue() as Boolean)
        val writes=ja.i0.pronunciationWrites
        state(restarted).synchronize()
        assertEquals(writes,ja.i0.pronunciationWrites)
    }
    @Test fun startupStateWithoutExplicitChoiceDoesNotPersistOrClearEmphasis() {
        val model=PlayerLyricsViewModel();ja.i0.model=model;model.choose(true,false)
        prefs.edit().putBoolean("primary",true).apply()
        assertFalse(NativeLyricsEmphasisVisibility.effective(app,false))
        assertTrue(NativeLyricsEmphasisVisibility.effective(app,true))
        state(model).synchronize()
        assertFalse(prefs.contains("visible"))
        assertTrue(prefs.getBoolean("primary",false))
        assertEquals(0,ja.i0.pronunciationWrites)
    }
    @Test fun explicitShowOverridesSavedHideAndTranslationDoesNotChangePronunciationChoice() {
        val model=PlayerLyricsViewModel();ja.i0.model=model
        val state=state(model)
        state.select(true,false);state.select(true,true);state.select(false,false)
        assertTrue(NativeLyricsEmphasisVisibility.effective(app,false))
        assertTrue(model.pronunciation.getValue() as Boolean)
        assertFalse(model.translation.getValue() as Boolean)
    }
    @Test fun nativeSetterFailureRollsBackPluginChoiceAndEmphasis() {
        prefs.edit().putBoolean("primary",true).apply()
        try {
            NativeLyricsEmphasisVisibility.choose(app,false) {
                assertFalse(NativeLyricsEmphasisVisibility.effective(app,true))
                throw IllegalStateException("native setter unavailable")
            }
            fail("expected setter failure")
        } catch (_: IllegalStateException) { }
        assertFalse(prefs.contains("visible"))
        assertTrue(prefs.getBoolean("primary",false))
    }
    @Test fun legacySavedHideIsRestoredBeforeFirstAuxiliaryBinding() {
        prefs.edit().putBoolean("visible",false).apply()
        val model=PlayerLyricsViewModel();ja.i0.model=model
        NativeLyricsAuxiliaryControls.restorePronunciation(app,model)
        assertFalse(model.pronunciation.getValue() as Boolean)
        assertFalse(prefs.getBoolean("visible",true))
    }
}
