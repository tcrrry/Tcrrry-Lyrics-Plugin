package com.tcrrry.lyrics.engine.hook

import com.tcrrry.lyrics.engine.model.CustomLyricsSources
import java.util.ArrayDeque
import java.nio.file.Files
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(manifest = org.robolectric.annotation.Config.NONE, sdk = [35])
class AutoLyricsReplacementSessionTest {
    @org.junit.Before fun attachPlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.attach() }
    @org.junit.After fun closePlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.current.close() }
    @Test fun scriptDisplayToggleReparsesTheSameSourceAndKeepsOriginalCacheReversible() {
        var simplified = false
        val seen = mutableListOf<String>()
        val raw = WORD_TTML.replace("<tt ", "<tt xml:lang=\"zh-Hant\" ").replace("hello", "與愛")
        val cache = MemoryCache()
        val queue = QueuedExecutor()
        val session = AutoLyricsReplacementSession(
            fetchCandidate = { AutoLyricsCandidate(CustomLyricsSources.DESKTOP_LYRICS, raw) }, cache = cache,
            parseTtml = { seen += ChineseLyricsDisplay.render(it, simplified); Pointer() },
            isAlive = { it is Pointer && it.live }, verifyPtr = { it is Pointer && it.live },
            readAdamId = { (it as Pointer).adamId },
            bindAdamId = { pointer, id -> (pointer as Pointer).adamId = id; true },
            executor = queue, logger = {}, presentationKey = { "simplified=$simplified" },
        )
        session.onSongChanged(42L); session.ensureRequested(42L); queue.runAll()
        assertEquals(1, seen.size); assertTrue(seen.last().contains("與愛"))
        simplified = true
        session.refreshCurrent(42L); queue.runAll()
        assertEquals(2, seen.size); assertTrue(seen.last().contains("与爱"))
        assertTrue(cache.values.getValue(42L).contains("與愛"))
        session.refreshCurrent(42L); queue.runAll()
        assertEquals(2, seen.size)
        simplified = false
        session.refreshCurrent(42L); queue.runAll()
        assertEquals(3, seen.size); assertTrue(seen.last().contains("與愛"))
        assertTrue(cache.values.getValue(42L).contains("與愛"))
    }
    @Test fun runningLookupIsInterruptedOnRefreshSoReadyResultDoesNotWaitBehindFallback() {
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        val started = java.util.concurrent.CountDownLatch(1)
        val interrupted = java.util.concurrent.CountDownLatch(1)
        val published = java.util.concurrent.CountDownLatch(1)
        val attempts = java.util.concurrent.atomic.AtomicInteger()
        val pointer = Pointer()
        val session = AutoLyricsReplacementSession(
            fetchCandidate = {
                if (attempts.incrementAndGet() == 1) {
                    started.countDown()
                    try { java.util.concurrent.CountDownLatch(1).await(5, java.util.concurrent.TimeUnit.SECONDS) }
                    catch (error: InterruptedException) { interrupted.countDown(); Thread.currentThread().interrupt() }
                    null
                } else AutoLyricsCandidate("desktop-lyrics:test", WORD_TTML)
            },
            cache = MemoryCache(), parseTtml = { pointer },
            isAlive = { it is Pointer }, verifyPtr = { it is Pointer },
            readAdamId = { (it as Pointer).adamId },
            bindAdamId = { value, id -> (value as Pointer).adamId = id; true },
            onReplacementPublished = { published.countDown() }, executor = worker, logger = {},
        )
        try {
            session.onSongChanged(42L)
            session.ensureRequested(42L)
            assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS))
            session.refreshCurrent(42L)
            assertTrue(interrupted.await(2, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(published.await(2, java.util.concurrent.TimeUnit.SECONDS))
            assertSame(pointer, session.readyReplacementFor(42L))
            assertEquals(2, attempts.get())
        } finally { worker.shutdownNow() }
    }
    @Test fun failedRefreshRetainsPreviousPointer() {
        val queued = QueuedExecutor()
        val pointer = Pointer()
        var available = true
        val session = session(queued, fetch = {
            if (available) AutoLyricsCandidate("desktop-lyrics:test", WORD_TTML) else null
        }, parse = { pointer })
        session.onSongChanged(42L)
        session.ensureRequested(42L)
        queued.runAll()
        available = false
        session.refreshCurrent(42L)
        queued.runAll()
        assertSame(pointer, session.readyReplacementFor(42L))
    }
    @Test fun pendingRefreshKeepsVisiblePointerAndIdenticalContentDoesNotReparse() {
        val queued = QueuedExecutor()
        var parses = 0
        val lyrics = session(queued, fetch = { AutoLyricsCandidate("desktop-lyrics:test", WORD_TTML) },
            parse = { parses++; Pointer() })
        lyrics.onSongChanged(42)
        lyrics.ensureRequested(42); queued.runAll()
        val original = lyrics.readyReplacementFor(42)
        lyrics.refreshCurrent(42)
        assertSame(original, lyrics.readyReplacementFor(42))
        queued.runAll()
        assertSame(original, lyrics.readyReplacementFor(42))
        assertEquals(1, parses)
        lyrics.onSongChanged(99)
        assertNull(lyrics.readyReplacementFor(42))
    }
    @Test fun changedTranslationReplacesOnlyAfterTheNewDocumentIsReady() {
        val queued = QueuedExecutor()
        var document = WORD_TTML
        val lyrics = session(queued, fetch = { AutoLyricsCandidate("desktop-lyrics:test", document) }, parse = { Pointer() })
        lyrics.onSongChanged(42); lyrics.ensureRequested(42); queued.runAll()
        val original = lyrics.readyReplacementFor(42)
        document = WORD_TTML.replace("hello", "translated")
        lyrics.refreshCurrent(42)
        assertSame(original, lyrics.readyReplacementFor(42))
        queued.runAll()
        org.junit.Assert.assertNotSame(original, lyrics.readyReplacementFor(42))
    }
    @Test
    fun `cold lookup keeps original path until a validated candidate publishes without caching fallback`() {
        val queued = QueuedExecutor()
        val cache = MemoryCache()
        val pointer = Pointer()
        var fetches = 0
        var published = 0
        val session = session(
            queued = queued,
            cache = cache,
            fetch = {
                fetches += 1
                AutoLyricsCandidate("amll", WORD_TTML)
            },
            parse = { pointer },
            onPublished = { published += 1 },
        )

        assertNull(session.replacementFor(42L))
        assertEquals(0, fetches)

        queued.runAll()

        assertSame(pointer, session.replacementFor(42L))
        assertEquals(1, fetches)
        assertEquals(1, published)
        assertEquals(42L, pointer.adamId)
        assertNull(cache.values[42L])
    }

    @Test
    fun `a persisted Word cache is prepared before any network source`() {
        val queued = QueuedExecutor()
        val cache = MemoryCache(mapOf(42L to WORD_TTML))
        val pointer = Pointer()
        var fetches = 0
        val session = session(
            queued = queued,
            cache = cache,
            fetch = { fetches += 1; AutoLyricsCandidate("network", WORD_TTML) },
            parse = { pointer },
        )

        assertNull(session.replacementFor(42L))
        queued.runAll()

        assertSame(pointer, session.readyReplacementFor(42L))
        assertEquals(0, fetches)
    }

    @Test
    fun `line cache displays immediately then upgrades to searched word lyrics`() {
        val queued = QueuedExecutor()
        val cache = MemoryCache(mapOf(42L to LINE_TTML))
        val line = Pointer()
        val word = Pointer()
        val displayed = mutableListOf<Any?>()
        lateinit var lyrics: AutoLyricsReplacementSession
        lyrics = session(queued = queued, cache = cache,
            fetch = { AutoLyricsCandidate("desktop-lyrics:QQ音乐", WORD_TTML) },
            parse = { if (it == LINE_TTML) line else word },
            onPublished = { displayed += lyrics.readyReplacementFor(42L) })
        lyrics.onSongChanged(42L)
        lyrics.ensureRequested(42L)
        queued.runAll()
        assertEquals(listOf(line, word), displayed)
        assertSame(word, lyrics.readyReplacementFor(42L))
        assertEquals(WORD_TTML, cache.values[42L])
    }

    @Test
    fun `line cache is retained when fresh lookup fails or has no timing upgrade`() {
        for (candidate in listOf(null, AutoLyricsCandidate("desktop-lyrics:QQ音乐", LINE_TTML))) {
            val queued = QueuedExecutor()
            val pointer = Pointer()
            var fetches = 0
            val lyrics = session(queued = queued, cache = MemoryCache(mapOf(42L to LINE_TTML)),
                fetch = { fetches++; candidate }, parse = { pointer })
            lyrics.onSongChanged(42L)
            lyrics.ensureRequested(42L)
            queued.runAll()
            assertEquals(1, fetches)
            assertSame(pointer, lyrics.readyReplacementFor(42L))
        }
    }

    @Test
    fun `unparseable word upgrade keeps displayed line cache`() {
        val queued = QueuedExecutor()
        val line = Pointer()
        val lyrics = session(queued = queued, cache = MemoryCache(mapOf(42L to LINE_TTML)),
            fetch = { AutoLyricsCandidate("desktop-lyrics:QQ音乐", WORD_TTML) },
            parse = { if (it == LINE_TTML) line else null })
        lyrics.onSongChanged(42L)
        lyrics.ensureRequested(42L)
        queued.runAll()
        assertSame(line, lyrics.readyReplacementFor(42L))
    }

    @Test
    fun `Desktop Lyrics result is cached for the next playback`() {
        val queued = QueuedExecutor()
        val cache = MemoryCache()
        val session = session(
            queued = queued,
            cache = cache,
            fetch = { AutoLyricsCandidate("desktop-lyrics:QQ音乐", WORD_TTML) },
            parse = { Pointer() },
        )

        assertNull(session.replacementFor(42L))
        queued.runAll()

        assertEquals(WORD_TTML, cache.values[42L])
    }

    @Test
    fun `validated automatic result is published to configured storage and cache is retired`() {
        val queued = QueuedExecutor()
        val cache = MemoryCache(mapOf(42L to WORD_TTML))
        var published = 0
        val session = AutoLyricsReplacementSession(
            fetchCandidate = { null },
            cache = cache,
            parseTtml = { Pointer() },
            isAlive = { it is Pointer && it.live },
            verifyPtr = { it is Pointer && it.live },
            readAdamId = { (it as Pointer).adamId },
            bindAdamId = { value, id -> (value as Pointer).adamId = id; true },
            publisher = { id, candidate ->
                assertEquals(42L, id)
                assertEquals(CustomLyricsSources.AUTO_CACHE, candidate.source)
                published += 1
                AutoLyricsPublishResult.PUBLISHED
            },
            executor = queued,
            logger = {},
        )

        session.replacementFor(42L)
        queued.runAll()

        assertEquals(1, published)
        assertEquals(null, cache.read(42L))
    }

    @Test
    fun `stale generation cannot publish after the current song changes`() {
        val queued = QueuedExecutor()
        var fetches = 0
        var published = 0
        val session = session(
            queued = queued,
            fetch = {
                fetches += 1
                AutoLyricsCandidate("amll", WORD_TTML)
            },
            parse = { Pointer() },
            onPublished = { published += 1 },
        )

        session.onSongChanged(42L)
        assertNull(session.replacementFor(42L))
        session.onSongChanged(43L)
        queued.runAll()

        assertNull(session.readyReplacementFor(42L))
        assertEquals(0, fetches)
        assertEquals(0, published)
    }

    @Test
    fun `repeated metadata for the same song keeps the pending lookup and pointer`() {
        val queued = QueuedExecutor()
        val pointer = Pointer()
        var fetches = 0
        val session = session(
            queued = queued,
            fetch = {
                fetches += 1
                AutoLyricsCandidate("amll", WORD_TTML)
            },
            parse = { pointer },
        )

        session.onSongChanged(42L)
        session.ensureRequested(42L)
        session.onSongChanged(42L)
        queued.runAll()

        assertEquals(1, fetches)
        assertSame(pointer, session.readyReplacementFor(42L))
    }

    @Test
    fun `already configured publisher never publishes an automatic ready late replacement`() {
        val queued = QueuedExecutor()
        var published = 0
        val session = AutoLyricsReplacementSession(
            fetchCandidate = { AutoLyricsCandidate("amll", WORD_TTML) },
            cache = MemoryCache(),
            parseTtml = { Pointer() },
            isAlive = { it is Pointer && it.live },
            verifyPtr = { it is Pointer && it.live },
            readAdamId = { (it as Pointer).adamId },
            bindAdamId = { value, id -> (value as Pointer).adamId = id; true },
            onReplacementPublished = { published += 1 },
            publisher = { _, _ -> AutoLyricsPublishResult.ALREADY_CONFIGURED },
            executor = queued,
            logger = {},
        )

        session.onSongChanged(42L)
        session.ensureRequested(42L)
        queued.runAll()

        assertEquals(0, published)
        assertEquals(null, session.readyReplacementFor(42L))
    }

    @Test
    fun `a manual replacement becoming ready cancels the queued automatic lookup`() {
        val queued = QueuedExecutor()
        var allowed = true
        var fetches = 0
        val session = session(
            queued = queued,
            fetch = {
                fetches += 1
                AutoLyricsCandidate("amll", WORD_TTML)
            },
            parse = { Pointer() },
            isAllowed = { allowed },
        )

        session.onSongChanged(42L)
        session.ensureRequested(42L)
        allowed = false
        queued.runAll()

        assertEquals(0, fetches)
        assertTrue(session.isTracking(42L).not())
    }

    @Test
    fun `already applied takeover survives unknown refresh but yields to better native Word lyrics`() {
        val queued = QueuedExecutor()
        val pointer = Pointer()
        val session = session(
            queued = queued,
            fetch = { AutoLyricsCandidate("amll", WORD_TTML) },
            parse = { pointer },
        )

        session.onSongChanged(42L)
        session.replacementFor(42L)
        queued.runAll()
        assertNull(session.takeoverReplacementFor(42L, Any(), metadata = null))

        session.markTakeoverApplied(42L)
        assertSame(pointer, session.takeoverReplacementFor(42L, Any(), metadata = null))
        assertNull(
            session.takeoverReplacementFor(
                42L,
                Any(),
                TtmlDocumentMetadata(TtmlTimingMode.WORD, language = "zh", hasTranslation = false),
            ),
        )
    }

    @Test
    fun `line timed candidates reach the native parser`() {
        val queued = QueuedExecutor()
        var parses = 0
        val session = session(
            queued = queued,
            fetch = { AutoLyricsCandidate("line-source", LINE_TTML) },
            parse = { parses += 1; Pointer() },
        )

        assertNull(session.replacementFor(42L))
        queued.runAll()

        assertTrue(session.readyReplacementFor(42L) != null)
        assertEquals(1, parses)
        assertTrue(session.isTracking(42L))
    }

    @Test
    fun `file cache persists Word and Line TTML by Adam ID`() {
        val directory = Files.createTempDirectory("ampp-auto-lyrics-test").toFile()
        try {
            val cache = FileAutoLyricsCache(directory, maxEntries = 2)
            assertTrue(cache.write(42L, WORD_TTML))
            assertEquals(WORD_TTML, FileAutoLyricsCache(directory).read(42L))
            assertTrue(cache.write(43L, LINE_TTML))
            assertEquals(LINE_TTML, cache.read(43L))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `file cache evicts least recently used lyric when byte budget is full`() {
        val directory = Files.createTempDirectory("ampp-auto-lyrics-lru-test").toFile()
        try {
            val cache = FileAutoLyricsCache(directory, maxBytes = WORD_TTML.toByteArray().size.toLong() * 2)
            assertTrue(cache.write(1L, WORD_TTML))
            assertTrue(cache.write(2L, WORD_TTML))
            assertEquals(WORD_TTML, cache.read(1L))
            assertTrue(cache.write(3L, WORD_TTML))
            assertEquals(null, cache.read(2L))
            assertEquals(WORD_TTML, cache.read(1L))
            assertEquals(WORD_TTML, cache.read(3L))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun refreshCompletionCarriesOnlyItsOwnAttemptTagAndCancelledRequestsCannotAdvanceCycles() {
        val queued = QueuedExecutor()
        val pointer = Pointer()
        var available = true
        val completed = mutableListOf<Pair<Long?, Boolean>>()
        val session = AutoLyricsReplacementSession(
            fetchCandidate = { if (available) AutoLyricsCandidate("desktop-lyrics:test", WORD_TTML) else null },
            cache = MemoryCache(), parseTtml = { pointer },
            isAlive = { it is Pointer }, verifyPtr = { it is Pointer },
            readAdamId = { (it as Pointer).adamId },
            bindAdamId = { value, id -> (value as Pointer).adamId = id; true },
            onTaggedRefreshFinished = { _, success, tag -> completed += tag to success },
            executor = queued, logger = {},
        )
        session.onSongChanged(42L)
        session.refreshCurrent(42L, 11L)
        session.refreshCurrent(42L, 22L)
        queued.runAll()
        assertEquals(listOf(22L to true), completed)
        available = false
        session.refreshCurrent(42L, 33L)
        queued.runAll()
        assertEquals(listOf(22L to true, 33L to false), completed)
        assertSame(pointer, session.readyReplacementFor(42L))
        session.refreshCurrent(42L, 44L)
        session.onSongChanged(99L)
        queued.runAll()
        assertEquals(2, completed.size)
    }

    @Test fun queueRejectionCompletesTheTaggedAttemptAndRetainsVisibleLyrics() {
        val pointer = Pointer()
        var reject = false
        val finished = mutableListOf<Pair<Long?, Boolean>>()
        val executor = Executor {
            if (reject) throw java.util.concurrent.RejectedExecutionException()
            it.run()
        }
        val session = AutoLyricsReplacementSession(
            fetchCandidate = { AutoLyricsCandidate("desktop-lyrics:test", WORD_TTML) },
            cache = MemoryCache(), parseTtml = { pointer },
            isAlive = { it is Pointer }, verifyPtr = { it is Pointer },
            readAdamId = { (it as Pointer).adamId },
            bindAdamId = { value, id -> (value as Pointer).adamId = id; true },
            onTaggedRefreshFinished = { _, success, tag -> finished += tag to success },
            executor = executor, logger = {},
        )
        session.onSongChanged(42L)
        session.ensureRequested(42L)
        reject = true
        session.refreshCurrent(42L, 66L)
        assertEquals(listOf(66L to false), finished)
        assertSame(pointer, session.readyReplacementFor(42L))
    }

    private fun session(
        queued: QueuedExecutor,
        cache: AutoLyricsCache = MemoryCache(),
        fetch: (Long) -> AutoLyricsCandidate?,
        parse: (String) -> Any?,
        onPublished: (Long) -> Unit = {},
        isAllowed: (Long) -> Boolean = { true },
    ): AutoLyricsReplacementSession {
        return AutoLyricsReplacementSession(
            fetchCandidate = fetch,
            cache = cache,
            parseTtml = parse,
            isAlive = { it is Pointer && it.live },
            verifyPtr = { it is Pointer && it.live },
            readAdamId = { (it as Pointer).adamId },
            bindAdamId = { value, id ->
                (value as Pointer).adamId = id
                true
            },
            onReplacementPublished = onPublished,
            isAllowed = isAllowed,
            executor = queued,
            logger = {},
        )
    }

    private class Pointer(
        var adamId: Long = 0L,
        var live: Boolean = true,
    )

    private class MemoryCache(initial: Map<Long, String> = emptyMap()) : AutoLyricsCache {
        val values = initial.toMutableMap()
        override fun read(appleMusicId: Long): String? = values[appleMusicId]
        override fun write(appleMusicId: Long, ttml: String): Boolean {
            values[appleMusicId] = ttml
            return true
        }
        override fun delete(appleMusicId: Long): Boolean = values.remove(appleMusicId) != null
    }

    private class QueuedExecutor : Executor {
        private val tasks = ArrayDeque<() -> Unit>()
        override fun execute(command: Runnable) {
            tasks.addLast { command.run() }
        }
        fun runAll() {
            while (tasks.isNotEmpty()) tasks.removeFirst().invoke()
        }
    }

    private companion object {
        const val WORD_TTML =
            "<tt xmlns:itunes=\"urn\" itunes:timing=\"Word\"><body>" +
                "<p><span begin=\"0s\" end=\"1s\">hello</span></p>" +
                "</body></tt>"
        const val LINE_TTML =
            "<tt xmlns:itunes=\"urn\" itunes:timing=\"Line\"><body>" +
                "<p>hello</p></body></tt>"
    }
}
