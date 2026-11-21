package com.tcrrry.lyrics.plugin

import android.content.Context
import com.tcrrry.lyrics.engine.BuildConfig
import org.json.JSONObject
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
    @Test fun pluginVersionComesFromBuildEvenWithStaleChannelAsset() {
        val asset = JSONObject()
            .put("repository", "tcrrry/Tcrrry-Lyrics-Plugin")
            .put("tagPrefix", "plugin-v")
            .put("version", "1.2.1")
            .put("label", "歌词插件")
        val channel = UpdateChannel.fromAsset(asset)
        assertEquals(BuildConfig.PLUGIN_VERSION, channel.version)
        assertFalse(UpdateChannel.newer(BuildConfig.PLUGIN_VERSION, channel.version))
        asset.remove("version")
        assertEquals(channel, UpdateChannel.fromAsset(asset))
        assertEquals(channel, UpdateChannel.pluginDefault())
    }

    @Test fun embeddedHostUsesItsOwnVersion() {
        val asset = JSONObject()
            .put("repository", "tcrrry/AMPP-Lyrics")
            .put("tagPrefix", "v")
            .put("version", "1.23")
            .put("label", "完整安装包")
        assertEquals("1.23", UpdateChannel.fromAsset(asset).version)
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
