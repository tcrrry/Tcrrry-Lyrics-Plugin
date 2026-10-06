package com.tcrrry.lyrics.engine.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(manifest = org.robolectric.annotation.Config.NONE, sdk = [35])
class LyricFollowRecoveryPolicyTest {
    @org.junit.Before fun attachPlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.attach() }
    @org.junit.After fun closePlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.current.close() }
    @Test fun `only a current line outside visible rows needs recovery`() {
        assertEquals(47, LyricFollowRecoveryPolicy.offscreenTarget(setOf(47), listOf(41, 42, 43, 44, 45, 46), false))
        assertEquals(39, LyricFollowRecoveryPolicy.offscreenTarget(setOf(39), listOf(41, 42, 43), false))
        assertNull(LyricFollowRecoveryPolicy.offscreenTarget(setOf(43), listOf(41, 42, 43), false))
        assertNull(LyricFollowRecoveryPolicy.offscreenTarget(setOf(47), listOf(41, 42, 43), true))
        assertNull(LyricFollowRecoveryPolicy.offscreenTarget(emptySet(), listOf(41, 42, 43), false))
    }
}
