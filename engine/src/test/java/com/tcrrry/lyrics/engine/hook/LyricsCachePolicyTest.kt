package com.tcrrry.lyrics.engine.hook

import com.tcrrry.lyrics.engine.lyrics.LyricsPreference
import com.tcrrry.lyrics.plugin.PluginStorage.pluginFilesDir
import com.tcrrry.lyrics.plugin.PluginStorage.pluginPreferences
import com.tcrrry.lyrics.plugin.TestPluginContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class LyricsCachePolicyTest {
    @org.junit.Before fun attach() { TestPluginContext.attach() }
    @org.junit.After fun close() { TestPluginContext.current.close() }
    private val document = "<?xml version=\"1.0\"?><tt><body><p>hello</p></body></tt>"
    @Test fun creditVisibilitySwitchInvalidatesRenderedCacheInBothDirections() {
        val application = RuntimeEnvironment.getApplication()
        val cache = createAutoLyricsRuntime(application).cache
        assertFalse(LyricsPreference.hideOpeningCredits(application))
        assertNull(cache.read(42)); assertTrue(cache.write(42, document)); assertNotNull(cache.read(42))
        LyricsPreference.updateHideOpeningCredits(application, true)
        assertNull(cache.read(42)); assertTrue(cache.write(42, document)); assertNotNull(cache.read(42))
        LyricsPreference.updateHideOpeningCredits(application, false)
        assertNull(cache.read(42))
    }
    @Test fun stampsAreIdempotentAndChangingAnyPolicyInvalidatesThem() {
        val policy = AutoLyricsCachePolicy(true, true, "api|profile-a")
        val stamped = policy.stamp(document)
        assertTrue(stamped.startsWith("<?xml version=\"1.0\"?>"))
        assertTrue(policy.matches(stamped))
        assertEquals(stamped, policy.stamp(stamped))
        assertEquals(document, AutoLyricsCachePolicy.strip(stamped))
        assertFalse(policy.matches(document))
        assertFalse(AutoLyricsCachePolicy(false, true, "api|profile-a").matches(stamped))
        assertFalse(AutoLyricsCachePolicy(true, false, "api|profile-a").matches(stamped))
        assertFalse(AutoLyricsCachePolicy(true, true, "api|profile-b").matches(stamped))
    }
    @Test fun defaultSmoothingAndQualityModeCanBothReuseDiskLyrics() {
        val application = RuntimeEnvironment.getApplication()
        val cache = createAutoLyricsRuntime(application).cache
        assertTrue(LyricsPreference.smoothShortUnits(application))
        assertNull(cache.read(42))
        assertTrue(cache.write(42, document))
        assertEquals(document, AutoLyricsCachePolicy.strip(requireNotNull(cache.read(42))))
        LyricsPreference.update(application, quality = true)
        assertNull(cache.read(42))
        assertTrue(cache.write(42, document))
        assertNotNull(cache.read(42))
    }
    @Test fun clearAndDisabledStoragePreventPendingWritesWithoutTouchingImportsOrModels() {
        val application = RuntimeEnvironment.getApplication()
        val cache = createAutoLyricsRuntime(application).cache
        val imported = File(application.pluginFilesDir, "custom-lyrics/keep.ttml").apply { parentFile.mkdirs(); writeText("imported") }
        val model = File(application.pluginFilesDir, "pronunciation-model/keep.bin").apply { parentFile.mkdirs(); writeText("model") }
        cache.read(42)
        assertTrue(cache.write(42, document))
        cache.read(42)
        assertEquals(0L, LyricsCacheStorage.clear(application))
        assertFalse(cache.write(42, document))
        assertTrue(imported.exists()); assertTrue(model.exists())
        LyricsCacheStorage.setLimitMb(application, 0)
        assertNull(cache.read(42)); assertFalse(cache.write(42, document))
        LyricsCacheStorage.setLimitMb(application, 1024)
        assertEquals(1024, LyricsCacheStorage.limitMb(application))
        cache.read(42); assertTrue(cache.write(42, document))
    }
    @Test fun quotaEvictsOldestPayloadFirst() {
        val directory = java.nio.file.Files.createTempDirectory("lyric-quota-test").toFile()
        try {
            val oldest = File(directory, "old.ttml").apply { writeBytes(ByteArray(60)); setLastModified(1000) }
            val newest = File(directory, "new.ttml").apply { writeBytes(ByteArray(60)); setLastModified(2000) }
            assertEquals(60L, LyricsCacheStorage.evict(listOf(newest, oldest), 100))
            assertFalse(oldest.exists()); assertTrue(newest.exists())
        } finally { directory.deleteRecursively() }
    }

    @Test fun unrelatedSongCantoneseAndNonPrimaryVisibilityDoNotInvalidateCacheButSourceOrderDoes() {
        val application = RuntimeEnvironment.getApplication()
        val cache = createAutoLyricsRuntime(application).cache
        assertNull(cache.read(42L))
        assertTrue(cache.write(42L, AutoLyricsCachePolicy.stampEnrichmentComplete(document, true)))
        assertNotNull(cache.read(42L))

        val jpPrefs = application.pluginPreferences("japanese_pronunciation", android.content.Context.MODE_PRIVATE)
        jpPrefs.edit().putBoolean("cantonese_999", true).apply()
        assertNotNull(cache.read(42L))

        NativeLyricsEmphasisVisibility.choose(application, false) {}
        assertNotNull(cache.read(42L))

        val incomplete = AutoLyricsCachePolicy.stampEnrichmentComplete(document, false)
        assertTrue(AutoLyricsCachePolicy.isEnrichmentIncomplete(incomplete))
        assertNull(cache.read(43L))
        assertTrue(cache.write(43L, incomplete))
        assertNull(cache.read(43L))

        LyricsPreference.updateSourceOrder(application, listOf("网易云音乐", LyricsSourceMenuPolicy.NATIVE, LyricsSourceMenuPolicy.AUTHOR, "QQ音乐", "LRCLIB"))
        assertNull(cache.read(42L))
    }
}
