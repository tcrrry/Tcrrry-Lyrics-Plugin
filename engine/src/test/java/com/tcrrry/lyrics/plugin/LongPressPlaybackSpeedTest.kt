package com.tcrrry.lyrics.plugin

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class LongPressPlaybackSpeedTest {
    @Before fun attachPlugin() { TestPluginContext.attach() }
    @After fun closePlugin() { TestPluginContext.current.close() }

    @Test
    fun defaultsAndClampingWorkCorrectly() {
        val ctx: Context = RuntimeEnvironment.getApplication()
        val initial = LongPressPlaybackSpeed.config(ctx)
        assertFalse(initial.enabled)
        assertEquals(2.0f, initial.fastSpeed, 0.001f)
        assertEquals(0.5f, initial.slowSpeed, 0.001f)
        assertTrue(initial.holdMode)
        assertTrue(initial.keepPitch)

        val updated = LongPressPlaybackSpeed.updateConfig(ctx) {
            copy(enabled = true, fastSpeed = 9.9f, slowSpeed = 0.1f, holdMode = false, keepPitch = false)
        }
        assertTrue(updated.enabled)
        assertEquals(3.0f, updated.fastSpeed, 0.001f)
        assertEquals(0.50f, updated.slowSpeed, 0.001f)
        assertFalse(updated.holdMode)
        assertFalse(updated.keepPitch)

        val reloaded = LongPressPlaybackSpeed.config(ctx)
        assertEquals(updated, reloaded)
        assertTrue(LongPressPlaybackSpeed.menuSummary(ctx).contains("3.0x"))
        assertTrue(LongPressPlaybackSpeed.menuSummary(ctx).contains("长按锁定"))
    }
}
