package dev.kifranei.ampp.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AudioAnalysisCacheTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun persistsAndReloadsLocalAnalysisAcrossProviderInstances() {
        val cacheFile = File(temp.newFolder(), "automix_analysis_cache.json")
        val writer = AudioAnalysisProvider(javaClass.classLoader!!, cacheFile)
        val sample = SongAudioAnalysis(
            bpm = 124.5f,
            key = "A minor",
            energy = 0.82f,
            danceability = 0.74f,
            acousticness = null,
        )
        writer.persistLocalCacheSync(cacheFile, mapOf("song_101" to sample))
        writer.close()

        val reader = AudioAnalysisProvider(javaClass.classLoader!!, cacheFile)
        // Verify load via inject + persist roundtrip using a subclass-free reflection check on localCache
        val field = AudioAnalysisProvider::class.java.getDeclaredField("localCache").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val reloaded = (field.get(reader) as Map<String, SongAudioAnalysis>)["song_101"]
        reader.close()

        assertNotNull(reloaded)
        assertEquals(124.5f, reloaded!!.bpm!!, 0.01f)
        assertEquals("A minor", reloaded.key)
        assertEquals(0.82f, reloaded.energy!!, 0.01f)
        assertEquals(0.74f, reloaded.danceability!!, 0.01f)
    }

    @Test
    fun supportsExpandedCapAndClearingCacheStats() {
        assertEquals(4000, AudioAnalysisProvider.MAX_PERSISTED_ENTRIES)
        val cacheFile = File(temp.newFolder(), "automix_analysis_cache.json")
        val provider = AudioAnalysisProvider(javaClass.classLoader!!, cacheFile)
        val sample = SongAudioAnalysis(bpm = 120f, key = "C major", energy = 0.7f, danceability = 0.7f, acousticness = null)
        provider.persistLocalCacheSync(cacheFile, mapOf("song_1" to sample, "song_2" to sample))
        val (diskCount, diskBytes) = AudioAnalysisProvider.readCacheStats(cacheFile)
        assertEquals(2, diskCount)
        assertTrue(diskBytes > 0L)
        assertTrue(AudioAnalysisProvider.deletePersistedCache(cacheFile))
        assertFalse(cacheFile.exists())
        assertEquals(0 to 0L, AudioAnalysisProvider.readCacheStats(cacheFile))
        provider.close()
    }

    @Test
    fun plannerAutoDurationStaysMusicalAndHonorsExplicitDuration() {
        val a = SongAudioAnalysis(bpm = 122f, key = "A minor", energy = 0.72f, danceability = 0.68f, acousticness = null)
        val b = SongAudioAnalysis(bpm = 124f, key = "E minor", energy = 0.70f, danceability = 0.66f, acousticness = null)
        val autoPlan = TransitionPlanner.plan(a, b, 0)
        assertTrue(autoPlan.enabled)
        assertTrue(autoPlan.durationSec in 4..10)
        val explicitPlan = TransitionPlanner.plan(a, b, 6)
        assertTrue(explicitPlan.enabled)
        assertEquals(6, explicitPlan.durationSec)
    }

    @Test
    fun appleOfficialAnalysisTakesPriorityAndBlocksLocalOverwrite() {
        val cacheFile = File(temp.newFolder(), "automix_analysis_cache.json")
        val provider = AudioAnalysisProvider(javaClass.classLoader!!, cacheFile)
        val songId = "Suisei|Lovely Summer Chan"
        val firstLocal = SongAudioAnalysis(bpm = 117.4f, key = "A minor", energy = 1.0f, danceability = 0.84f, acousticness = null)
        provider.injectLocalAnalysis(songId, firstLocal, fromApple = false)
        assertFalse(provider.hasAppleAnalysis(songId))

        val appleOfficial = SongAudioAnalysis(
            bpm = 97.0f,
            key = "C# minor",
            energy = 0.08f,
            danceability = 0.21f,
            acousticness = null,
            barTimesSec = floatArrayOf(1f, 3f, 5f),
        )
        provider.injectLocalAnalysis(songId, appleOfficial, fromApple = true)
        assertTrue(provider.hasAppleAnalysis(songId))

        val secondLocal = SongAudioAnalysis(bpm = 108.0f, key = "A minor", energy = 1.0f, danceability = 0.80f, acousticness = null)
        provider.injectLocalAnalysis(songId, secondLocal, fromApple = false)

        val localField = AudioAnalysisProvider::class.java.getDeclaredField("localCache").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val stored = (localField.get(provider) as Map<String, SongAudioAnalysis>)[songId]
        provider.close()

        assertNotNull(stored)
        assertEquals(97.0f, stored!!.bpm!!, 0.01f)
        assertEquals("C# minor", stored.key)
        assertEquals(0.08f, stored.energy!!, 0.01f)
    }
}
