package com.tcrrry.lyrics.plugin

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class ReleaseUpdatesTest {
    @Test fun versionsCompareNumericallyAndRejectNonReleaseVersions() {
        assertTrue(UpdateChannel.newer("1.0.10", "1.0.6"))
        assertFalse(UpdateChannel.newer("1.0.6", "1.0.6.0"))
        assertFalse(UpdateChannel.newer("1.0.5", "1.0.6"))
        assertFalse(UpdateChannel.newer("bad", "1.0.6"))
        val plugin = UpdateChannel("tcrrry/Tcrrry-Lyrics-Plugin", "plugin-v", "1.0.6", "plugin")
        assertEquals("1.0.7", plugin.versionOf("plugin-v1.0.7"))
        assertNull(plugin.versionOf("v1.0.7"))
        assertNull(plugin.versionOf("plugin-v1.0.7-beta"))
        assertEquals("https://github.com/tcrrry/Tcrrry-Lyrics-Plugin/releases/tag/plugin-v1.0.7", plugin.release("plugin-v1.0.7"))
    }
    private fun policy(): UpdateReminderPolicy = UpdateReminderPolicy(RuntimeEnvironment.getApplication()
        .getSharedPreferences("update-policy", Context.MODE_PRIVATE).apply { edit().clear().commit() })
    @Test fun eachReleaseAutomaticallyNotifiesOnlyOnceUnlessPostponed() {
        val policy = policy()
        assertTrue(policy.shouldNotify("v1.7", 100))
        policy.presented("v1.7")
        assertFalse(policy.shouldNotify("v1.7", 101))
        assertTrue(policy.shouldNotify("v1.8", 101))
        policy.later("v1.7", 100)
        assertFalse(policy.shouldNotify("v1.7", 101))
        assertTrue(policy.shouldNotify("v1.7", 100 + 24 * 60 * 60 * 1000L))
    }
    @Test fun ignoringOneVersionDoesNotHideFutureReleasesAndAutoCanBeRestored() {
        val policy = policy()
        policy.ignore("v1.7")
        assertFalse(policy.shouldNotify("v1.7", 100))
        assertTrue(policy.shouldNotify("v1.8", 100))
        policy.automatic = false
        assertFalse(policy.shouldNotify("v1.8", 100))
        policy.automatic = true
        assertTrue(policy.shouldNotify("v1.8", 100))
    }
}
