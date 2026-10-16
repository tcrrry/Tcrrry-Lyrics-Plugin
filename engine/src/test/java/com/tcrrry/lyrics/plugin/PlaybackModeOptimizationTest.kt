package com.tcrrry.lyrics.plugin

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class PlaybackModeOptimizationTest {
    @org.junit.Before fun attach() { TestPluginContext.attach() }
    @org.junit.After fun close() { PlaybackModeOptimization.close(); TestPluginContext.current.close() }
    @Test fun enabledByDefaultAndUserChoiceSurvivesLifecycleRestart() {
        val app=RuntimeEnvironment.getApplication()
        assertTrue(PlaybackModeOptimization.enabled(app))
        PlaybackModeOptimization.setEnabled(app,false)
        PlaybackModeOptimization.attach(TestPluginContext.current)
        PlaybackModeOptimization.start()
        assertFalse(PlaybackModeOptimization.enabled(app))
        PlaybackModeOptimization.close()
        assertFalse(PlaybackModeOptimization.enabled(app))
        PlaybackModeOptimization.setEnabled(app,true)
        assertTrue(PlaybackModeOptimization.enabled(app))
    }
    @Test fun unsupportedHostDoesNotAcquirePlaybackHooksWhenToggled() {
        PlaybackModeOptimization.attach(TestPluginContext.current)
        PlaybackModeOptimization.start()
        val app=RuntimeEnvironment.getApplication()
        PlaybackModeOptimization.setEnabled(app,false)
        PlaybackModeOptimization.setEnabled(app,true)
        assertTrue(TestPluginContext.current.callbacks.isEmpty())
    }
}
