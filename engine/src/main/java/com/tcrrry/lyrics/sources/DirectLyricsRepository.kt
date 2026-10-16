package com.tcrrry.lyrics.sources

import android.icu.text.Transliterator
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.Charset
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Resolves lyrics directly from public music services. No request is routed through
 * a Lobsta/Tcrrry-owned server. MediaSession artwork remains the preferred cover;
 * QQ Music and NetEase artwork are only used when the player did not publish one.
 */
class DirectLyricsRepository(private val wordFirst: Boolean = false) {
    private fun rank(result: Result): Int = qualityRank(result, wordFirst)
    private val latinTransliterator by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Transliterator.getInstance("Any-Latin; NFD; [:Nonspacing Mark:] Remove; NFC")
        } else null
    }
    data class PronunciationUnit(val start: Int, val end: Int, val text: String)
    data class Result(
        val lyrics: String = "",
        val translatedLyrics: String = "",
        val wordLyrics: String = "",
        val durationMs: Long = 0L,
        val cover: String = "",
        val source: String = "",
        val recordId: String = "",
        val title: String = "",
        val artist: String = "",
        val score: Int = 0,
        val alternatives: List<Result> = emptyList(),
        val romanizedLyrics: String = "",
        val romanizedWordLyrics: String = "",
        val supplementalTranslationKind: String = "",
        val supplementalTranslationStarts: Set<Long> = emptySet(),
        val supplementalPronunciationStarts: Set<Long> = emptySet(),
        val supplementalPronunciationLanguages: Map<Long, String> = emptyMap(),
        val supplementalPronunciationUnits: Map<Long, List<PronunciationUnit>> = emptyMap(),
        val album: String = "",
    ) {
        private fun candidateJson(): JSONObject = JSONObject()
            .put("lyrics", lyrics)
            .put("translatedLyrics", translatedLyrics)
            .put("wordLyrics", wordLyrics)
            .put("romanizedLyrics", romanizedLyrics)
            .put("romanizedWordLyrics", romanizedWordLyrics)
            .put("duration", durationMs)
            .put("cover", cover)
            .put("source", source)
            .put("recordId", recordId)
            .put("title", title)
            .put("artist", artist)
            .put("album", album)
            .put("matchScore", score)

        fun toJson(): JSONObject = candidateJson().put(
            "alternatives",
            JSONArray().apply {
                put(candidateJson())
                alternatives.forEach { put(it.candidateJson()) }
            }
        )
    }

    private data class JsonMatch(val value: JSONObject, val score: Int)
    private data class SearchPlan(val track: String, val query: String, val includesArtist: Boolean)
    private data class ResolvedIdentity(val track: String, val artist: String, val album: String)
    private data class QqRichLyrics(
        val lineLyrics: String,
        val translatedLyrics: String,
        val wordLyrics: String,
        val romanizedLyrics: String,
        val romanizedWordLyrics: String,
    )
    private val identityCache = ConcurrentHashMap<String, ResolvedIdentity>()
    private val artistAliasCache = ConcurrentHashMap<String, Set<String>>()
    private val providerCooldown = ConcurrentHashMap<String, Long>()
    private val excludedRecords = ThreadLocal<Set<String>>()

    fun rematch(source: String, track: String, artist: String, album: String,
                durationMs: Long, excluded: Set<String>): Result? {
        excludedRecords.set(excluded.toSet())
        try {
            fun query(title: String, singer: String): Result? = when (source) {
                "QQ音乐" -> queryQqMusic(title, singer, true, durationMs)
                "网易云音乐" -> queryNetEase(title, singer, true, durationMs)
                "LRCLIB" -> queryLrcLib(title, singer, durationMs)
                else -> null
            }
            val direct = query(track, artist)
            if (direct != null && direct.score >= MIN_ACCEPTABLE_SCORE) return direct
            val identity = resolveLocalizedIdentity(track, artist, album, durationMs) ?: return null
            return query(identity.track, identity.artist)?.takeIf { it.score >= MIN_ACCEPTABLE_SCORE }
        } finally { excludedRecords.remove() }
    }

    private fun providerKey(url: String): String = when {
        URL(url).host.endsWith("music.163.com") -> "netease"
        URL(url).host.endsWith("qq.com") -> "qq"
        URL(url).host == "lrclib.net" -> "lrclib"
        else -> URL(url).host
    }

    private fun checkProvider(url: String) {
        if ((providerCooldown[providerKey(url)] ?: 0L) > System.nanoTime()) {
            throw IllegalStateException("Provider cooling down after request failure")
        }
    }

    private fun checkStatus(url: String, status: Int) {
        if (status == 405 || status == 429 || status == 503) {
            val seconds = if (status == 503) 15L else 60L
            providerCooldown[providerKey(url)] = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
            throw IllegalStateException("Provider status=$status; cooldown=${seconds}s")
        }
    }

    private val executor = com.tcrrry.lyrics.plugin.PluginExecutors.newFixedThreadPool(6) { runnable ->
        Thread(runnable, "direct-lyrics").apply { isDaemon = true }
    }

    fun resolveLyrics(
        track: String,
        artist: String,
        album: String = "",
        expectedDurationMs: Long = 0L,
        onPartial: (Result) -> Unit = {}
    ): Result {
        val direct = resolveFromProviders(track, artist, expectedDurationMs, onCandidate = onPartial)
        val current = (listOf(direct) + direct.alternatives).filter { isUsableLyrics(it.lyrics) }
        // A usable lyric is not proof that all providers found the correct identity.
        val incomplete = current.size < 3 || current.any(::needsEnrichment)
        if (current.isNotEmpty() && !incomplete) return direct
        if (current.isNotEmpty()) onPartial(direct)

        val identity = runCatching {
            resolveLocalizedIdentity(track, artist, album, expectedDurationMs)
        }.onFailure { error ->
            Log.w(
                LOG_TAG,
                "Lyrics identity bridge failed for ${track.take(48)}: " +
                    "${error.javaClass.simpleName}: ${error.message.orEmpty().take(80)}"
            )
        }.getOrNull() ?: return direct
        if (normalize(identity.track) == normalize(track) &&
            normalize(identity.artist) == normalize(artist)
        ) return direct

        Log.i(
            LOG_TAG,
            "Lyrics identity bridge ${track.take(40)} / ${artist.take(32)} -> " +
                "${identity.track.take(40)} / ${identity.artist.take(32)}"
        )
        val retrySources = setOf("网易云音乐", "QQ音乐", "LRCLIB").filter { source ->
            current.none { it.source == source } || current.any { it.source == source && needsEnrichment(it) }
        }.toSet()
        val retried = resolveFromProviders(identity.track, identity.artist, expectedDurationMs,
            retrySources, onCandidate = onPartial)
        val combined = (current + retried + retried.alternatives)
            .filter { isUsableLyrics(it.lyrics) }
            .sortedByDescending(::rank).distinctBy { it.source }
        val best = combined.firstOrNull() ?: return direct
        return best.copy(alternatives = combined.drop(1).map { it.copy(alternatives = emptyList()) })
    }

    private fun resolveFromProviders(track: String, artist: String, expectedDurationMs: Long,
                                     sources: Set<String> = setOf("网易云音乐", "QQ音乐", "LRCLIB"),
                                     onCandidate: (Result) -> Unit = {}): Result {
        val available = ConcurrentHashMap<String, Result>()
        val remember: (Result) -> Unit = { result ->
            available.compute(result.source) { _, previous ->
                if (previous == null || rank(result) > rank(previous) ||
                    (previous.recordId.isNotBlank() && previous.recordId == result.recordId &&
                        previous.romanizedLyrics.isBlank() && result.romanizedLyrics.isNotBlank())) result else previous
            }
            if (isUsableLyrics(result.lyrics) && result.score >= MIN_ACCEPTABLE_SCORE) {
                runCatching { onCandidate(result.copy(alternatives = emptyList())) }
            }
        }
        val completion = ExecutorCompletionService<Result?>(executor)
        val futures = listOfNotNull(
            runCatching { completion.submit(Callable {
                if ("LRCLIB" in sources) querySource("LRCLIB", track) { queryLrcLib(track, artist, expectedDurationMs) } else null
            }) }.getOrNull(),
            runCatching { completion.submit(Callable {
                if ("QQ音乐" in sources) querySource("QQ", track) {
                    queryQqMusic(track, artist, includeLyrics = true, expectedDurationMs, remember)
                } else null
            }) }.getOrNull(),
            runCatching { completion.submit(Callable {
                if ("网易云音乐" in sources) querySource("NetEase", track) {
                    queryNetEase(track, artist, includeLyrics = true, expectedDurationMs, remember)
                } else null
            }) }.getOrNull()
        )
        if (futures.isEmpty()) return Result()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(LYRICS_DEADLINE_MS)
        val candidates = mutableListOf<Result>()
        var completed = 0
        var firstCandidateAt = 0L

        try {
            while (completed < futures.size) {
                val candidateDeadline = if (firstCandidateAt == 0L) {
                    deadline
                } else {
                    minOf(deadline, firstCandidateAt + TimeUnit.MILLISECONDS.toNanos(SOURCE_GRACE_MS))
                }
                val remaining = candidateDeadline - System.nanoTime()
                if (remaining <= 0L) break
                val future = completion.poll(remaining, TimeUnit.NANOSECONDS) ?: break
                completed++
                val candidate = runCatching { future.get() }.getOrNull()
                    ?.takeIf { isUsableLyrics(it.lyrics) && it.score >= MIN_ACCEPTABLE_SCORE }
                if (candidate != null) {
                    candidates += candidate
                    runCatching { onCandidate(candidate.copy(alternatives = emptyList())) }
                    if (firstCandidateAt == 0L) firstCandidateAt = System.nanoTime()
                }
            }
        } finally {
            futures.forEach { it.cancel(true) }
        }
        val ranked = (candidates + available.values.toList())
            .sortedByDescending(::rank)
            .distinctBy { it.source }
            .distinctBy { "${it.source}\u0000${it.lyrics}" }
            .sortedByDescending(::rank)
        val primary = ranked.firstOrNull() ?: return Result()
        return primary.copy(alternatives = ranked.drop(1))
    }

    fun resolveCover(track: String, artist: String): String {
        val completion = ExecutorCompletionService<Result?>(executor)
        val futures = listOfNotNull(
            runCatching {
                completion.submit(Callable { queryQqMusic(track, artist, includeLyrics = false) })
            }.getOrNull(),
            runCatching {
                completion.submit(Callable { queryNetEase(track, artist, includeLyrics = false) })
            }.getOrNull()
        )
        if (futures.isEmpty()) return ""
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(COVER_DEADLINE_MS)
        var best: Result? = null

        try {
            repeat(futures.size) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0L) return@repeat
                val future = completion.poll(remaining, TimeUnit.NANOSECONDS) ?: return@repeat
                val candidate = runCatching { future.get() }.getOrNull()
                    ?.takeIf { it.cover.isNotBlank() && it.score >= MIN_ACCEPTABLE_SCORE }
                if (candidate != null && (best == null || candidate.score > best!!.score)) {
                    best = candidate
                }
                if (candidate != null && candidate.score >= EXACT_MATCH_SCORE) {
                    return candidate.cover
                }
            }
        } finally {
            futures.forEach { it.cancel(true) }
        }
        return best?.cover.orEmpty()
    }

    fun close() {
        executor.shutdownNow()
    }

    private fun querySource(source: String, track: String, block: () -> Result?): Result? {
        return try {
            val result = block()
            Log.d(
                LOG_TAG,
                "Lyrics provider=$source track=${track.take(48)} " +
                    "candidate=${result != null} score=${result?.score ?: 0}"
            )
            result
        } catch (error: Throwable) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            Log.w(
                LOG_TAG,
                "Lyrics provider=$source track=${track.take(48)} failed: " +
                    "${error.javaClass.simpleName}: ${error.message.orEmpty().take(80)}"
            )
            null
        }
    }

    private fun providerArtistAliases(artist: String): Set<String> {
        val key = normalize(artist)
        artistAliasCache[key]?.let { return it }
        val aliases = mutableSetOf(key)
        return runCatching {
            val root = searchNetEase(artist, type = 100, limit = 3)
            val artists = root.optJSONObject("result")?.optJSONArray("artists") ?: return@runCatching aliases
            for (index in 0 until artists.length()) {
                val entry = artists.optJSONObject(index) ?: continue
                // Search ranking alone must never create a new artist identity.
                if (normalize(entry.optString("name")) != key) continue
                val names = entry.optJSONArray("alias") ?: entry.optJSONArray("alia") ?: continue
                for (i in 0 until names.length()) normalize(names.optString(i)).takeIf { it.isNotBlank() }?.let(aliases::add)
            }
            if (artistAliasCache.size >= 128) artistAliasCache.keys.firstOrNull()?.let(artistAliasCache::remove)
            artistAliasCache[key] = aliases.toSet()
            aliases.toSet()
        }.getOrDefault(aliases)
    }

    private fun queryLrcLib(track: String, artist: String, expectedDurationMs: Long): Result? {
        val url = "https://lrclib.net/api/search?track_name=${encode(track)}&artist_name=${encode(artist)}"
        var best: Result? = null
        for (broad in listOf(false, true)) {
        val requestUrl = if (broad) "https://lrclib.net/api/search?track_name=${encode(track)}" else url
        val list = JSONArray(getText(requestUrl, mapOf("Accept" to "application/json", "User-Agent" to "DesktopLyrics/1.06")))
        val aliases = if (broad && list.length() > 0) providerArtistAliases(artist) else setOf(normalize(artist))
        for (index in 0 until list.length()) {
            val item = list.optJSONObject(index) ?: continue
            if (item.optLong("id").toString() in excludedRecords.get().orEmpty()) continue
            val syncedLyrics = item.optString("syncedLyrics")
            val plainLyrics = item.optString("plainLyrics")
            val lyrics = if (isUsableLyrics(syncedLyrics)) syncedLyrics else plainLyrics
            if (!isUsableLyrics(lyrics)) continue
            val synced = isUsableLyrics(syncedLyrics)
            val durationMs = (item.optDouble("duration", 0.0) * 1000.0).toLong()
            val candidateArtist = item.optString("artistName")
            val aliasMatch = normalize(candidateArtist) in aliases
            if (broad && (!aliasMatch || titleIdentityKey(track) != titleIdentityKey(item.optString("trackName")) ||
                    expectedDurationMs <= 0 || kotlin.math.abs(expectedDurationMs - durationMs) > 8000L ||
                    versionTags(track) != versionTags(item.optString("trackName")))) continue
            val score = platformMatchScore(
                track,
                artist,
                item.optString("trackName"),
                if (aliasMatch) artist else candidateArtist,
                expectedDurationMs,
                durationMs
            ) + if (synced) 5 else 0
            val result = Result(
                lyrics = lyrics,
                durationMs = durationMs,
                source = "LRCLIB",
                recordId = item.optLong("id").toString(),
                title = item.optString("trackName"),
                album = item.optString("albumName"),
                artist = item.optString("artistName"),
                score = score
            )
            if (best == null || result.score > best.score) best = result
        }
        if (best != null && best!!.score >= MIN_ACCEPTABLE_SCORE) return best
        }
        return best
    }

    private fun queryQqMusic(
        track: String,
        artist: String,
        includeLyrics: Boolean,
        expectedDurationMs: Long = 0L,
        onCandidate: (Result) -> Unit = {}
    ): Result? {
        val headers = mapOf(
            "Accept" to "application/json",
            "Referer" to "https://y.qq.com/",
            "User-Agent" to USER_AGENT
        )
        planLoop@ for (plan in searchPlans(track, artist)) {
            val searchTrack = plan.track
            val searchUrl = "https://c.y.qq.com/soso/fcgi-bin/search_for_qq_cp" +
                "?format=json&p=1&n=${searchResultLimit(searchTrack)}&w=${encode(plan.query)}"
            val root = JSONObject(getText(searchUrl, headers))
            val songs = root.optJSONObject("data")
                ?.optJSONObject("song")
                ?.optJSONArray("list") ?: continue
            val rejectedSongMids = mutableSetOf<String>()
            var best: Result? = null
            var firstSong: JSONObject? = null
            var extraTried = false
            while (rejectedSongMids.size < MAX_LYRIC_CANDIDATES_PER_QUERY) {
                val match = firstJsonMatch(
                    songs,
                    track,
                    searchTrack,
                    artist,
                    allowRankFallback = plan.includesArtist,
                    expectedDurationMs = expectedDurationMs,
                    candidateDurationMs = { it.optLong("interval", 0L) * 1000L },
                    isUsable = { item ->
                        val mid = item.optString("songmid")
                        mid !in rejectedSongMids && mid !in excludedRecords.get().orEmpty() &&
                            (!includeLyrics || (mid.isNotBlank() && mid != "0"))
                    }
                ) { item ->
                    val singers = item.optJSONArray("singer").joinNames("name")
                    Triple(item.optString("songname").ifBlank { item.optString("songorig") }, singers, item)
                } ?: run { if (best != null) return best; null } ?: continue@planLoop
                val song = match.value
                if (firstSong != null) {
                    if (extraTried || !sameRecording(
                        firstSong!!.optString("songname"), song.optString("songname"),
                        firstSong!!.optJSONArray("singer").joinNames("name"), song.optJSONArray("singer").joinNames("name"),
                        firstSong!!.optLong("interval", 0L) * 1000L, song.optLong("interval", 0L) * 1000L
                    )) return best
                    extraTried = true
                }
                val score = match.score
                val albumMid = song.optString("albummid")
                val albumId = song.optLong("albumid", 0L)
                val cover = when {
                    albumMid.isNotBlank() && !albumMid.all(Char::isDigit) ->
                        "https://y.gtimg.cn/music/photo_new/T002R800x800M000$albumMid.jpg"
                    albumId > 0L ->
                        "https://y.gtimg.cn/music/photo/album_500/${albumId % 100}/500_albumpic_${albumId}_0.jpg"
                    else -> ""
                }
                if (!includeLyrics) return Result(cover = cover, source = "QQ音乐", score = score)

                val songMid = song.optString("songmid")
                val richLyrics = song.optLong("songid", 0L).takeIf { it > 0L }?.let { songId ->
                    runCatching { queryQqRichLyrics(songId, headers, track) }
                        .onFailure { error ->
                            Log.w(
                                LOG_TAG,
                                "QQ QRC failed for $songId: ${error.javaClass.simpleName}: " +
                                    error.message.orEmpty().take(80)
                            )
                        }
                        .getOrNull()
                }
                if (richLyrics != null && isUsableLyrics(richLyrics.lineLyrics)) {
                    val result = OptionalQqPronunciation.enrich(completeQqLyrics(Result(
                        lyrics = richLyrics.lineLyrics,
                        translatedLyrics = richLyrics.translatedLyrics,
                        wordLyrics = richLyrics.wordLyrics,
                        romanizedLyrics = richLyrics.romanizedLyrics,
                        romanizedWordLyrics = richLyrics.romanizedWordLyrics,
                        durationMs = song.optLong("interval", 0L) * 1000L,
                        cover = cover,
                        source = "QQ音乐",
                        recordId = songMid,
                        title = song.optString("songname"),
                        album = song.optString("albumname"),
                        artist = song.optJSONArray("singer").joinNames("name"),
                        score = score + 5
                    )), { queryQqPronunciation(song.optLong("songid"), headers, song) }, onCandidate)
                if (best == null || rank(result) > rank(best!!)) best = result
                if (firstSong != null || !needsEnrichment(result)) return best
                firstSong = song
                rejectedSongMids += songMid
                continue
                }

                val lyricUrl = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg" +
                    "?songmid=${encode(songMid)}&format=json&nobase64=1&trans=1"
                val lyricRoot = parseJsonFlexible(getBytes(lyricUrl, headers))
                val lyrics = lyricRoot?.optString("lyric")?.let(::unescapeHtml).orEmpty()
                if (!isUsableLyrics(lyrics) || isTitleOnlyLyrics(lyrics, track)) {
                    rejectedSongMids += songMid
                    continue
                }
                val result = OptionalQqPronunciation.enrich(Result(
                    lyrics = lyrics,
                    translatedLyrics = unescapeHtml(lyricRoot?.optString("trans").orEmpty()),
                    romanizedLyrics = runCatching {
                        decodeQqRomanizedTrack(lyricRoot?.optString("roma").orEmpty())
                    }.getOrDefault(""),
                    durationMs = song.optLong("interval", 0L) * 1000L,
                    cover = cover,
                    source = "QQ音乐",
                    recordId = songMid,
                    title = song.optString("songname"),
                        album = song.optString("albumname"),
                    artist = song.optJSONArray("singer").joinNames("name"),
                    score = score + 5
                ), { queryQqPronunciation(song.optLong("songid"), headers, song) }, onCandidate)
                if (best == null || rank(result) > rank(best!!)) best = result
                if (firstSong != null || !needsEnrichment(result)) return best
                firstSong = song
                rejectedSongMids += songMid
                continue
            }
            if (best != null) return best
        }
        return null
    }

    private fun queryQqRichLyrics(songId: Long, headers: Map<String, String>, track: String): QqRichLyrics? {
        val response = postFormText(
            "https://c.y.qq.com/qqmusic/fcgi-bin/lyric_download.fcg",
            headers + ("Referer" to "https://c.y.qq.com/"),
            mapOf(
                "version" to "15",
                "miniversion" to "82",
                "lrctype" to "4",
                "roma" to "1",
                "musicid" to songId.toString()
            )
        )
        if (!Regex("<result>\\s*0\\s*</result>").containsMatchIn(response)) return null

        val encryptedOriginal = extractQqTrack(response, "content")
        if (encryptedOriginal.isBlank()) return null
        val originalPayload = decodeQqTrack(encryptedOriginal)
        val original = extractQqLyricContent(originalPayload)
        val lineLyrics = qrcToLineLrc(original)
        if (!isUsableLyrics(lineLyrics) || isTitleOnlyLyrics(lineLyrics, track)) return null

        val translatedPayload = extractQqTrack(response, "contentts")
            .takeIf { it.isNotBlank() }
            ?.let(::decodeQqTrack)
            ?.let(::extractQqLyricContent)
            .orEmpty()
        val translated = cleanQqTranslation(qrcToLineLrc(translatedPayload))
        val romanized = qqRomanizedLyrics(response)
        val hasWordTiming = Regex("^\\[\\d+,\\d+]", RegexOption.MULTILINE).containsMatchIn(original) &&
            Regex("\\(\\d+,\\d+(?:,\\d+)?\\)").containsMatchIn(original)

        return QqRichLyrics(
            lineLyrics = lineLyrics,
            translatedLyrics = translated,
            wordLyrics = if (hasWordTiming) qrcToCanonicalWordLyrics(original) else "",
            romanizedLyrics = romanized,
            romanizedWordLyrics = qqRomanizedWordLyrics(response),
        )
    }

    internal fun completeQqLyrics(result: Result): Result = QqLyricsCompleteness.repair(result) {
        val url="https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg" +
            "?songmid=${encode(result.recordId)}&format=json&nobase64=1&trans=1"
        val response=parseJsonFlexible(getBytes(url, mapOf("Referer" to "https://y.qq.com/", "User-Agent" to USER_AGENT)))
        response?.optString("lyric")?.let(::unescapeHtml).orEmpty()
    }

    internal fun qqRomanizedLyrics(response: String): String = runCatching {
        decodeQqRomanizedTrack(extractQqTrack(response, "contentroma"))
    }.getOrDefault("")

    internal fun qqRomanizedWordLyrics(response: String): String = runCatching {
        extractQqTrack(response, "contentroma").takeIf(String::isNotBlank)
            ?.let(::decodeQqTrack)?.let(::extractQqLyricContent)
            ?.let(::qrcToCanonicalWordLyrics).orEmpty()
    }.getOrDefault("")

    internal fun decodeQqRomanizedTrack(payload: String): String =
        payload.takeIf(String::isNotBlank)?.let(::decodeQqTrack)
            ?.let(::extractQqLyricContent)?.let(::qrcToLineLrc).orEmpty()

    private fun queryQqPronunciation(songId: Long, headers: Map<String, String>, song: JSONObject): String {
        fun b64(text: String): String = java.util.Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))
        val request = JSONObject().put("comm", JSONObject().put("ct", 19).put("cv", 2111))
            .put("request", JSONObject().put("module", "music.musichallSong.PlayLyricInfo")
                .put("method", "GetPlayLyricInfo").put("param", JSONObject()
                    .put("songID", songId).put("songName", b64(song.optString("songname")))
                    .put("singerName", b64(song.optJSONArray("singer").joinNames("name")))
                    .put("albumName", b64(song.optString("albumname")))
                    .put("interval", song.optLong("interval")).put("crypt", 1)
                    .put("ct", 19).put("cv", 2111).put("type", 0)
                    .put("qrc", 0).put("qrc_t", 0).put("lrc_t", 0)
                    .put("trans", 0).put("trans_t", 0).put("roma", 1).put("roma_t", 0)))
        val response = JSONObject(postText("https://u.y.qq.com/cgi-bin/musicu.fcg", headers,
            request.toString().toByteArray(Charsets.UTF_8), "application/json; charset=UTF-8"))
        val result = response.optJSONObject("request") ?: return ""
        if (response.optInt("code", -1) != 0 || result.optInt("code", -1) != 0) return ""
        return decodeQqRomanizedTrack(result.optJSONObject("data")?.optString("roma").orEmpty())
    }

    private fun extractQqTrack(response: String, tag: String): String {
        val safeTag = Regex.escape(tag)
        return Regex(
            "<$safeTag(?:\\s[^>]*)?><!\\[CDATA\\[(.*?)]]></$safeTag>",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        ).find(response)?.groupValues?.getOrNull(1).orEmpty().trim()
    }

    private fun decodeQqTrack(payload: String): String {
        val normalized = payload.trim()
        return if (normalized.length >= 16 && normalized.length % 2 == 0 &&
            normalized.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
        ) {
            QqQrcDecoder.decode(normalized)
        } else {
            normalized
        }
    }

    private fun extractQqLyricContent(payload: String): String {
        val content = Regex(
            "LyricContent=\\\"(.*?)\\\"",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        ).find(payload)?.groupValues?.getOrNull(1) ?: payload
        return unescapeHtml(content).replace("&#10;", "\n").replace("&#13;", "")
    }

    // QQ places each timestamp after its text; the renderer expects it before.
    private fun qrcToCanonicalWordLyrics(payload: String): String {
        val linePattern = Regex("^\\[(\\d+),(\\d+)\\](.*)$")
        val markerPattern = Regex("\\((\\d+),(\\d+)(?:,\\d+)?\\)")
        return payload.replace("\r", "").lineSequence().mapNotNull { raw ->
            val line = linePattern.matchEntire(raw.trim()) ?: return@mapNotNull null
            val body = line.groupValues[3]
            val markers = markerPattern.findAll(body).toList()
            if (markers.isEmpty()) return@mapNotNull null
            var cursor = 0
            val converted = buildString {
                for (marker in markers) {
                    val text = body.substring(cursor, marker.range.first)
                    append("(${marker.groupValues[1]},${marker.groupValues[2]})")
                    append(text)
                    cursor = marker.range.last + 1
                }
                append(body.substring(cursor))
            }
            "[${line.groupValues[1]},${line.groupValues[2]}]$converted"
        }.joinToString("\n")
    }

    private fun qrcToLineLrc(payload: String): String {
        if (payload.isBlank()) return ""
        val qrcLine = Regex("^\\[(\\d+),(\\d+)](.*)$")
        val wordMarker = Regex("\\(\\d+,\\d+(?:,\\d+)?\\)")
        return payload.replace("\r", "").lineSequence().mapNotNull { raw ->
            val line = raw.trim()
            val match = qrcLine.matchEntire(line)
            if (match == null) {
                line.takeIf { Regex("^\\[\\d{1,3}:\\d{2}(?:[.:]\\d+)?]").containsMatchIn(it) }
            } else {
                val start = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
                val text = match.groupValues[3].replace(wordMarker, "").trim()
                if (text.isBlank()) null else "${formatLrcTime(start)}$text"
            }
        }.joinToString("\n")
    }

    private fun formatLrcTime(milliseconds: Long): String {
        val safe = milliseconds.coerceAtLeast(0L)
        val minutes = safe / 60_000L
        val seconds = (safe / 1_000L) % 60L
        val millis = safe % 1_000L
        return "[%02d:%02d.%03d]".format(Locale.US, minutes, seconds, millis)
    }

    private fun cleanQqTranslation(lyrics: String): String = lyrics.lineSequence()
        .filterNot { line ->
            val text = line.replace(Regex("^(\\[[^]]+])+"), "").trim()
            text == "//" || text.contains("QQ音乐享有本翻译作品的著作权") ||
                text.contains("TME享有本翻译作品的著作权")
        }
        .joinToString("\n")

    private fun queryNetEase(
        track: String,
        artist: String,
        includeLyrics: Boolean,
        expectedDurationMs: Long = 0L,
        onCandidate: (Result) -> Unit = {}
    ): Result? {
        val headers = mapOf(
            "Accept" to "application/json",
            "Referer" to "https://music.163.com/",
            "User-Agent" to USER_AGENT
        )
        planLoop@ for (plan in searchPlans(track, artist)) {
            val searchTrack = plan.track
            val root = searchNetEase(plan.query, type = 1, limit = searchResultLimit(searchTrack))
            val songs = netEaseSongs(root)
            val rejectedSongIds = mutableSetOf<Long>()
            var best: Result? = null
            var firstSong: JSONObject? = null
            var extraTried = false
            while (rejectedSongIds.size < MAX_LYRIC_CANDIDATES_PER_QUERY) {
                val match = firstJsonMatch(
                    songs,
                    track,
                    searchTrack,
                    artist,
                    allowRankFallback = plan.includesArtist,
                    expectedDurationMs = expectedDurationMs,
                    candidateDurationMs = { it.optLong("duration", it.optLong("dt", 0L)) },
                    isUsable = { item ->
                        val id = item.optLong("id", 0L)
                        id > 0L && id !in rejectedSongIds && id.toString() !in excludedRecords.get().orEmpty()
                    }
                ) { item ->
                    val artists = (item.optJSONArray("artists") ?: item.optJSONArray("ar")).joinNames("name")
                    Triple(item.optString("name"), artists, item)
                } ?: run { if (best != null) return best; null } ?: continue@planLoop
                val song = match.value
                if (firstSong != null) {
                    if (extraTried || !sameRecording(
                        firstSong!!.optString("name"), song.optString("name"),
                        firstSong!!.optJSONArray("artists").joinNames("name"), song.optJSONArray("artists").joinNames("name"),
                        firstSong!!.optLong("duration", 0L), song.optLong("duration", 0L)
                    )) return best
                    extraTried = true
                }
                val score = match.score
                val album = song.optJSONObject("album") ?: song.optJSONObject("al")
                var cover = album?.optString("picUrl").orEmpty()
                val songId = song.optLong("id", 0L)

                if (cover.isBlank()) {
                    val detailUrl = "https://music.163.com/api/song/detail/?id=$songId&ids=[$songId]"
                    val detail = runCatching { JSONObject(getText(detailUrl, headers)) }.getOrNull()
                    val detailSong = detail?.optJSONArray("songs")?.optJSONObject(0)
                    cover = (detailSong?.optJSONObject("album") ?: detailSong?.optJSONObject("al"))
                        ?.optString("picUrl").orEmpty()
                }
                if (!includeLyrics) return Result(cover = cover, source = "网易云音乐", score = score)

                val lyricUrl = "https://music.163.com/api/song/lyric?os=pc&id=$songId" +
                    "&lv=-1&kv=-1&tv=-1&yv=-1&rv=-1"
                val lyricRoot = JSONObject(getText(lyricUrl, headers))
                val lyrics = lyricRoot.optJSONObject("lrc")?.optString("lyric").orEmpty()
                if (!isUsableLyrics(lyrics)) {
                    rejectedSongIds += songId
                    continue
                }
                val result = Result(
                    lyrics = lyrics,
                    translatedLyrics = lyricRoot.optJSONObject("tlyric")?.optString("lyric").orEmpty(),
                    romanizedLyrics = netEaseRomanizedLyrics(lyricRoot),
                    romanizedWordLyrics = netEaseRomanizedWordLyrics(lyricRoot),
                    wordLyrics = lyricRoot.optJSONObject("yrc")?.optString("lyric")
                        .orEmpty()
                        .ifBlank { lyricRoot.optJSONObject("klyric")?.optString("lyric").orEmpty() },
                    durationMs = song.optLong("duration", song.optLong("dt", 0L)),
                    cover = cover,
                    source = "网易云音乐",
                    recordId = songId.toString(),
                    title = song.optString("name"),
                    album = album?.optString("name").orEmpty(),
                    artist = song.optJSONArray("artists").joinNames("name"),
                    score = score + 5
                )
                onCandidate(result)
                if (best == null || rank(result) > rank(best!!)) best = result
                if (firstSong != null || !needsEnrichment(result)) return best
                firstSong = song
                rejectedSongIds += songId
                continue
            }
            if (best != null) return best
        }
        return null
    }

    private fun searchNetEase(query: String, type: Int, limit: Int): JSONObject {
        // The old /api/search/get/web now returns an encrypted result string.
        // Public cloudsearch accepts an ordinary form without login or cookies.
        val url = "https://music.163.com/api/cloudsearch/pc"
        val root = JSONObject(postFormText(url, mapOf(
            "Accept" to "application/json",
            "Referer" to "https://music.163.com/",
            "User-Agent" to USER_AGENT
        ), mapOf("s" to query, "type" to type.toString(), "limit" to limit.toString(), "offset" to "0")))
        checkStatus(url, root.optInt("code", 0))
        return root
    }

    internal fun netEaseSongs(root: JSONObject): JSONArray {
        val result = root.opt("result")
        require(result == null || result == JSONObject.NULL || result is JSONObject) {
            "Unsupported NetEase search result format"
        }
        val songs = (result as? JSONObject)?.optJSONArray("songs") ?: return JSONArray()
        return JSONArray().apply {
            for (i in 0 until songs.length()) {
                val song = songs.optJSONObject(i) ?: continue
                // Normalize cloudsearch's ar/al/dt for all downstream paths,
                // including recording comparisons and persisted artist metadata.
                put(JSONObject(song.toString()).apply {
                    if (optJSONArray("artists") == null) put("artists", song.optJSONArray("ar") ?: JSONArray())
                    if (optJSONObject("album") == null) song.optJSONObject("al")?.let { put("album", it) }
                    if (!has("duration")) put("duration", song.optLong("dt", 0L))
                })
            }
        }
    }

    internal fun netEaseRomanizedLyrics(root: JSONObject): String =
        (if (root.optJSONObject("yrc")?.optString("lyric").orEmpty().isNotBlank())
            sequenceOf("yromalrc", "romalrc", "rromalrc") else sequenceOf("romalrc", "yromalrc", "rromalrc"))
            .map { root.optJSONObject(it)?.optString("lyric").orEmpty() }
            .firstOrNull(String::isNotBlank)?.let(::qrcToLineLrc).orEmpty()

    internal fun netEaseRomanizedWordLyrics(root: JSONObject): String =
        sequenceOf("yromalrc", "rromalrc", "romalrc")
            .map { root.optJSONObject(it)?.optString("lyric").orEmpty() }
            .firstOrNull { Regex("^\\[\\d+,\\d+](?:\\(\\d+,\\d+(?:,\\d+)?\\))", RegexOption.MULTILINE).containsMatchIn(it) }
            .orEmpty()

    private fun firstJsonMatch(
        array: JSONArray,
        track: String,
        searchTrack: String,
        artist: String,
        allowRankFallback: Boolean = true,
        expectedDurationMs: Long = 0L,
        candidateDurationMs: (JSONObject) -> Long = { 0L },
        isUsable: (JSONObject) -> Boolean = { true },
        fields: (JSONObject) -> Triple<String, String, JSONObject>
    ): JsonMatch? {
        var best: JSONObject? = null
        var bestScore = Int.MIN_VALUE
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            if (!isUsable(item)) continue
            val (title, singer, value) = fields(item)
            if (versionTags(title) != versionTags(track)) continue
            val originalScore = platformMatchScore(
                track, artist, title, singer, expectedDurationMs, candidateDurationMs(item)
            )
            val variantScore = if (normalize(searchTrack) != normalize(track)) {
                platformMatchScore(
                    searchTrack, artist, title, singer, expectedDurationMs, candidateDurationMs(item)
                )
            } else {
                originalScore
            }
            val rankScore = if (allowRankFallback) rankedSearchFallbackScore(
                searchTrack,
                artist,
                title,
                singer,
                expectedDurationMs,
                candidateDurationMs(item),
                index
            ) else 0
            val score = maxOf(
                originalScore,
                variantScore,
                rankScore
            )
            if (score >= MIN_ACCEPTABLE_SCORE && score > bestScore) {
                best = value
                bestScore = score
            }
        }
        return best?.let { JsonMatch(it, bestScore) }
    }

    private fun searchTrackVariants(track: String): List<String> {
        val simplified = track
            .replace(Regex("\\s*[（(][^）)]*[）)]\\s*"), " ")
            .replace(Regex("\\s*[【\\[].*?[】\\]]\\s*"), " ")
            .trim()
        return listOf(track.trim(), simplified)
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun searchPlans(track: String, artist: String): List<SearchPlan> {
        val variants = searchTrackVariants(track)
        return buildList {
            variants.forEach { variant ->
                add(SearchPlan(variant, "$variant $artist".trim(), includesArtist = artist.isNotBlank()))
            }
            if (artist.isNotBlank()) {
                variants.forEach { variant ->
                    add(SearchPlan(variant, variant, includesArtist = false))
                }
            }
        }.distinctBy { it.query }
    }

    /**
     * The iTunes catalogue is used only as an identity bridge after all lyric
     * providers fail. It is especially useful when a player exposes localized
     * English or Chinese metadata while lyric providers index Japanese names.
     * No preview audio or artwork is downloaded.
     */
    private fun resolveLocalizedIdentity(
        track: String,
        artist: String,
        album: String,
        expectedDurationMs: Long
    ): ResolvedIdentity? {
        if (expectedDurationMs <= 0L) return null
        val cacheKey = listOf(track, artist, album, (expectedDurationMs / 2_000L).toString())
            .joinToString("\u0000") { normalize(it) }
        identityCache[cacheKey]?.let { return it }

        val query = "$track $artist".trim()
        val url = "https://itunes.apple.com/search?term=${encode(query)}" +
            "&media=music&entity=song&limit=20&country=jp&lang=ja_jp"
        val root = JSONObject(
            getText(
                url,
                mapOf("Accept" to "application/json", "User-Agent" to USER_AGENT)
            )
        )
        val results = root.optJSONArray("results") ?: return null
        val ranked = buildList {
            for (index in 0 until results.length()) {
                val item = results.optJSONObject(index) ?: continue
                if (item.optString("kind") != "song") continue
                val candidateTrack = item.optString("trackName")
                val candidateArtist = item.optString("artistName")
                val candidateAlbum = item.optString("collectionName")
                val candidateDuration = item.optLong("trackTimeMillis", 0L)
                val durationDelta = kotlin.math.abs(expectedDurationMs - candidateDuration)
                if (candidateTrack.isBlank() || candidateArtist.isBlank() || durationDelta > 15_000L) continue

                val inputVersions = versionTags(track)
                val candidateVersions = versionTags(candidateTrack)
                if (candidateVersions.any { it !in inputVersions }) continue

                val durationPoints = when (durationDelta) {
                    in 0L..3_000L -> 40
                    in 3_001L..8_000L -> 32
                    else -> 20
                }
                val titlePoints = (textSimilarity(coreTitle(track), coreTitle(candidateTrack)) * 35).toInt()
                val artistPoints = (textSimilarity(artist, candidateArtist) * 20).toInt()
                val albumPoints = (textSimilarity(album, candidateAlbum) * 20).toInt()
                val rankPoints = (10 - index).coerceAtLeast(0)
                add(
                    Pair(
                        ResolvedIdentity(candidateTrack, candidateArtist, candidateAlbum),
                        durationPoints + titlePoints + artistPoints + albumPoints + rankPoints
                    )
                )
            }
        }.groupBy { "${normalize(it.first.track)}\u0000${normalize(it.first.artist)}" }
            .values
            .mapNotNull { group -> group.maxByOrNull { it.second } }
            .sortedByDescending { it.second }

        val best = ranked.firstOrNull() ?: return null
        val secondScore = ranked.getOrNull(1)?.second ?: Int.MIN_VALUE
        if (best.second < IDENTITY_MIN_SCORE) return null
        if (secondScore != Int.MIN_VALUE && best.second < IDENTITY_STRONG_SCORE &&
            best.second - secondScore < IDENTITY_MIN_MARGIN
        ) return null
        if (identityCache.size >= 128) identityCache.keys.firstOrNull()?.let(identityCache::remove)
        identityCache[cacheKey] = best.first
        return best.first
    }

    private fun coreTitle(value: String): String = value
        .replace(Regex("\\s*[（(][^）)]*[）)]\\s*"), " ")
        .replace(Regex("\\s*[【\\[].*?[】\\]]\\s*"), " ")
        .replace(Regex("\\s*(?:-|/)?\\s*(?:feat\\.?|ft\\.?|with)\\s+.+$", RegexOption.IGNORE_CASE), " ")
        .trim()

    private fun textSimilarity(first: String, second: String): Double {
        val left = normalize(first)
        val right = normalize(second)
        if (left.isBlank() || right.isBlank()) return 0.0
        if (left == right) return 1.0
        if (left in right || right in left) {
            return minOf(left.length, right.length).toDouble() / maxOf(left.length, right.length)
        }
        return latinSimilarity(first, second).coerceAtLeast(
            1.0 - editDistance(left, right).toDouble() / maxOf(left.length, right.length).toDouble()
        )
    }

    private fun versionTags(value: String): Set<String> {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        return buildSet {
            if (Regex("\\blive\\b|现场|ライヴ|ライブ").containsMatchIn(normalized)) add("live")
            if (Regex("instrumental|伴奏|纯音乐|純音楽|off\\s*vocal").containsMatchIn(normalized)) add("instrumental")
            if (Regex("remix|リミックス").containsMatchIn(normalized)) add("remix")
            if (Regex("acoustic|アコースティック").containsMatchIn(normalized)) add("acoustic")
            if (Regex("karaoke|カラオケ").containsMatchIn(normalized)) add("karaoke")
        }
    }

    private fun searchResultLimit(track: String): Int {
        val searchableLength = normalize(track).length
        return if (searchableLength <= 3) 30 else 12
    }

    /**
     * Provider rank is useful evidence only when duration is very close. It is
     * deliberately combined with either the queried title or the artist, so a
     * random first result can never pass on rank alone.
     */
    private fun rankedSearchFallbackScore(
        searchTrack: String,
        artist: String,
        candidateTrack: String,
        candidateArtist: String,
        expectedDurationMs: Long,
        candidateDurationMs: Long,
        resultIndex: Int
    ): Int {
        if (expectedDurationMs <= 0L || candidateDurationMs <= 0L) return 0
        val durationDelta = kotlin.math.abs(expectedDurationMs - candidateDurationMs)
        if (durationDelta > 8_000L) return 0

        val wantedTitle = titleIdentityKey(searchTrack)
        val foundTitle = titleIdentityKey(candidateTrack)
        val titleExact = wantedTitle.isNotBlank() && wantedTitle == foundTitle
        val wantedArtist = normalize(artist)
        val foundArtist = normalize(candidateArtist)
        val artistStrong = wantedArtist.isNotBlank() && foundArtist.isNotBlank() &&
            (wantedArtist == foundArtist || wantedArtist in foundArtist || foundArtist in wantedArtist ||
                latinSimilarity(artist, candidateArtist) >= 0.58)
        val durationBonus = durationScore(expectedDurationMs, candidateDurationMs)

        // Handles provider-localized stage names and symbolic titles such as
        // "MIREI" -> "當山みれい" and "^^", without accepting arbitrary hits.
        if (titleExact && resultIndex <= 8 &&
            (artistStrong || !(containsCjk(artist) && containsCjk(candidateArtist)))) {
            return 82 + durationBonus - resultIndex.coerceAtMost(8)
        }
        // Handles localized titles such as "Till I Know What Love Is" ->
        // "愛を知るまでは" when the artist, duration and top search rank agree.
        if (artistStrong && resultIndex <= 2 &&
            !(containsCjk(searchTrack) && containsCjk(candidateTrack)) &&
            excludedRecords.get() == null) {
            return 84 + durationBonus - resultIndex
        }
        return 0
    }

    private fun matchScore(track: String, artist: String, candidateTrack: String, candidateArtist: String): Int {
        val wantedTrack = normalize(track)
        val foundTrack = normalize(candidateTrack)
        val wantedArtist = normalize(artist)
        val foundArtist = normalize(candidateArtist)
        if (wantedTrack.isBlank() || foundTrack.isBlank()) return 0
        if (
            wantedArtist.isNotBlank() &&
            foundArtist.isNotBlank() &&
            wantedArtist !in foundArtist &&
            foundArtist !in wantedArtist
        ) return 0

        val titleScore = when {
            wantedTrack == foundTrack -> 80
            wantedTrack.length >= 4 && (wantedTrack in foundTrack || foundTrack in wantedTrack) -> 62
            commonPrefixRatio(wantedTrack, foundTrack) >= 0.72 -> 50
            else -> 0
        }
        val artistScore = when {
            wantedArtist.isBlank() -> 12
            wantedArtist == foundArtist -> 20
            wantedArtist.length >= 2 && (wantedArtist in foundArtist || foundArtist in wantedArtist) -> 17
            else -> 0
        }
        return titleScore + artistScore
    }

    private fun platformMatchScore(
        track: String,
        artist: String,
        candidateTrack: String,
        candidateArtist: String,
        expectedDurationMs: Long = 0L,
        candidateDurationMs: Long = 0L
    ): Int {
        val strict = matchScore(track, artist, candidateTrack, candidateArtist)
        val durationBonus = durationScore(expectedDurationMs, candidateDurationMs)
        if (strict >= MIN_ACCEPTABLE_SCORE) return strict + durationBonus

        val wantedTitle = titleIdentityKey(track)
        val foundTitle = titleIdentityKey(candidateTrack)
        val titleExact = wantedTitle.isNotBlank() && wantedTitle == foundTitle
        val titleLatinSimilarity = latinSimilarity(track, candidateTrack)
        val artistLatinSimilarity = latinSimilarity(artist, candidateArtist)
        val durationReliable = expectedDurationMs > 0L && candidateDurationMs > 0L &&
            kotlin.math.abs(expectedDurationMs - candidateDurationMs) <= 12_000L

        if (!durationReliable) return strict
        if (titleExact && artistLatinSimilarity >= 0.58) return 88 + durationBonus
        if (titleLatinSimilarity >= 0.56 &&
            (normalize(artist) == normalize(candidateArtist) || artistLatinSimilarity >= 0.58)
        ) return 84 + durationBonus
        return strict
    }

    private fun containsCjk(value: String): Boolean =
        Regex("[\\u3040-\\u30FF\\u3400-\\u9FFF\\uAC00-\\uD7AF]").containsMatchIn(value)

    private fun durationScore(expectedMs: Long, candidateMs: Long): Int {
        if (expectedMs <= 0L || candidateMs <= 0L) return 0
        return when (kotlin.math.abs(expectedMs - candidateMs)) {
            in 0L..3_000L -> 12
            in 3_001L..8_000L -> 8
            in 8_001L..15_000L -> 4
            in 15_001L..35_000L -> -5
            else -> -25
        }
    }

    private fun latinSimilarity(first: String, second: String): Double {
        val left = normalize(latinizeIfAvailable(first))
        val right = normalize(latinizeIfAvailable(second))
        if (left.isBlank() || right.isBlank()) return 0.0
        if (left == right) return 1.0
        val distance = editDistance(left, right)
        return 1.0 - distance.toDouble() / maxOf(left.length, right.length).toDouble()
    }

    private fun latinizeIfAvailable(value: String): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            latinTransliterator?.transliterate(value) ?: value
        } else value

    private fun editDistance(first: String, second: String): Int {
        var previous = IntArray(second.length + 1) { it }
        for (i in first.indices) {
            val current = IntArray(second.length + 1)
            current[0] = i + 1
            for (j in second.indices) {
                current[j + 1] = minOf(
                    current[j] + 1,
                    previous[j + 1] + 1,
                    previous[j] + if (first[i] == second[j]) 0 else 1
                )
            }
            previous = current
        }
        return previous[second.length]
    }

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .replace(Regex("[（(\\[].*?(live|remaster|版|伴奏|纯音乐|翻唱).*?[）)\\]]", RegexOption.IGNORE_CASE), "")
        .replace(Regex("[^\\p{L}\\p{N}]"), "")

    private fun titleIdentityKey(value: String): String {
        val alphanumeric = normalize(value)
        if (alphanumeric.isNotBlank()) return alphanumeric
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), "")
    }

    private fun commonPrefixRatio(first: String, second: String): Double {
        val limit = minOf(first.length, second.length)
        var same = 0
        while (same < limit && first[same] == second[same]) same++
        return same.toDouble() / maxOf(first.length, second.length).toDouble()
    }

    private fun sameRecording(title: String, otherTitle: String, artist: String, otherArtist: String,
                              duration: Long, otherDuration: Long): Boolean =
        titleIdentityKey(title) == titleIdentityKey(otherTitle) &&
            normalize(artist).isNotBlank() && normalize(artist) == normalize(otherArtist) &&
            versionTags(title) == versionTags(otherTitle) && duration > 0 && otherDuration > 0 &&
            kotlin.math.abs(duration - otherDuration) <= 3000L

    private fun needsEnrichment(result: Result): Boolean = result.score >= EXACT_MATCH_SCORE &&
        (coverage(result.lyrics, result.wordLyrics) < 80 ||
            (Regex("[a-zA-Z\\u3040-\\u30ff\\uac00-\\ud7af]").containsMatchIn(result.lyrics) &&
                coverage(result.lyrics, result.translatedLyrics) < 80))

    private fun isTitleOnlyLyrics(value: String, track: String): Boolean {
        val lines = value.lineSequence().map { it.replace(Regex("^(\\[[^]]+])+"), "").trim() }
            .filter { it.isNotBlank() }.toList()
        if (lines.size != 1) return false
        val title = lines[0].split(Regex("\\s+[-–—]\\s+"), limit = 2)[0]
        return titleIdentityKey(title) == titleIdentityKey(track)
    }

    private fun JSONArray?.joinNames(key: String): String {
        if (this == null) return ""
        return buildList {
            for (index in 0 until length()) {
                optJSONObject(index)?.optString(key)?.takeIf { it.isNotBlank() }?.let(::add)
            }
        }.joinToString("/")
    }

    private fun getText(url: String, headers: Map<String, String>): String {
        val text = getBytes(url, headers).toString(Charsets.UTF_8)
        if (text.trimStart().startsWith("{")) {
            runCatching { JSONObject(text) }.getOrNull()?.let { checkStatus(url, it.optInt("code", 0)) }
        }
        return text
    }

    private fun postFormText(
        url: String,
        headers: Map<String, String>,
        fields: Map<String, String>
    ): String {
        val body = fields.entries.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }.toByteArray(Charsets.UTF_8)
        return postText(url, headers, body, "application/x-www-form-urlencoded; charset=UTF-8")
    }

    private fun postText(url: String, headers: Map<String, String>, body: ByteArray, contentType: String): String {
        checkProvider(url)
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.useCaches = false
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", contentType)
            connection.setFixedLengthStreamingMode(body.size)
            headers.forEach(connection::setRequestProperty)
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            checkStatus(url, status)
            if (status !in 200..299) throw IllegalStateException("HTTP $status")
            connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_RESPONSE_BYTES) throw IllegalStateException("Response too large")
                    output.write(buffer, 0, read)
                }
                return output.toString(Charsets.UTF_8.name())
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun getBytes(url: String, headers: Map<String, String>): ByteArray {
        checkProvider(url)
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.useCaches = true
            headers.forEach(connection::setRequestProperty)
            val status = connection.responseCode
            checkStatus(url, status)
            if (status !in 200..299) throw IllegalStateException("HTTP $status")
            connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_RESPONSE_BYTES) throw IllegalStateException("Response too large")
                    output.write(buffer, 0, read)
                }
                return output.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun parseJsonFlexible(bytes: ByteArray): JSONObject? {
        return runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }.getOrElse {
            runCatching { JSONObject(bytes.toString(Charset.forName("GBK"))) }.getOrNull()
        }
    }

    private fun unescapeHtml(value: String): String = value
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    companion object {
        internal fun coverage(original: String, extra: String): Int {
            if (!isUsableLyrics(extra)) return 0
            fun timedLines(text: String): Int = text.lineSequence().count {
                Regex("^\\[(?:\\d+:\\d+|\\d+,\\d+)").containsMatchIn(it.trim())
            }
            val base = timedLines(original).coerceAtLeast(1)
            return (timedLines(extra) * 100 / base).coerceIn(0, 100)
        }

        internal fun qualityRank(result: Result, wordFirst: Boolean = false): Int {
            val confidenceBand = if (result.score >= EXACT_MATCH_SCORE) 2 else 1
            if (wordFirst) {
                val timed = com.tcrrry.lyrics.engine.lyrics.DesktopLyricsTtmlConverter.hasWordTiming(result)
                return confidenceBand * 1_000_000 + (if (timed) 200_000 else 0) +
                    (if (timed) coverage(result.lyrics, result.wordLyrics) * 1_000 else 0) +
                    coverage(result.lyrics, result.translatedLyrics) * 200 +
                    coverage(result.lyrics, result.romanizedLyrics) * 50 +
                    result.score * 100 + lyricBodyScore(result.lyrics) +
                    (if (result.source == "网易云音乐") 4 else if (result.source == "QQ音乐") 2 else 0)
            }
            return confidenceBand * 100_000 +
            coverage(result.lyrics, result.translatedLyrics) * 200 +
            coverage(result.lyrics, result.wordLyrics) * 100 +
            coverage(result.lyrics, result.romanizedLyrics) * 50 +
            result.score * 100 +
            lyricBodyScore(result.lyrics) +
            when (result.source) {
                "网易云音乐" -> 4
                "QQ音乐" -> 2
                else -> 0
            }
        }

        private fun isUsableLyrics(value: String): Boolean {
            val normalized = value.trim()
            if (normalized.isEmpty() || normalized.equals("null", true) ||
                normalized.equals("undefined", true)
            ) return false

            val meaningful = normalized.lineSequence()
                .map { it.replace(Regex("^(\\[[^]]+])+"), "").trim() }
                .filter { it.isNotBlank() }
                .filterNot { Regex("^(?:作词|作詞|作曲|编曲|編曲|词|詞|曲|制作人|製作人|混音|母带|录音|演唱|歌手|composer|lyricist|arranger|producer)\\s*[:：]", RegexOption.IGNORE_CASE).containsMatchIn(it) }
                .toList()
            if (meaningful.isEmpty()) return false
            val body = meaningful.asSequence()
                .joinToString("")
                .replace(Regex("[\\s,，。.!！?？、]"), "")
            if (body.length <= 48 && PLACEHOLDER_LYRICS.any { it.matches(body) }) return false
            return true
        }

        private fun lyricBodyScore(lyrics: String): Int {
            val credit = Regex("^(作词|作詞|作曲|编曲|編曲|词|詞|曲|composer|lyricist|arranger)\\s*[:：]", RegexOption.IGNORE_CASE)
            val count = lyrics.lineSequence().count { raw ->
                val text = raw.replace(Regex("^(\\[[^]]+])+"), "").trim()
                text.isNotBlank() && !credit.containsMatchIn(text) &&
                    !text.contains("纯音乐，请欣赏")
            }
            return count.coerceAtMost(40) * 3
        }


        private const val LOG_TAG = "DesktopLyrics"
        private const val CONNECT_TIMEOUT_MS = 3_000
        private const val READ_TIMEOUT_MS = 6_000
        private const val LYRICS_DEADLINE_MS = 10_000L
        private const val SOURCE_GRACE_MS = 9_000L
        private const val COVER_DEADLINE_MS = 6_000L
        private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
        private const val MIN_ACCEPTABLE_SCORE = 50
        private const val EXACT_MATCH_SCORE = 95
        private const val IDENTITY_MIN_SCORE = 50
        private const val IDENTITY_STRONG_SCORE = 75
        private const val IDENTITY_MIN_MARGIN = 5
        private const val MAX_LYRIC_CANDIDATES_PER_QUERY = 4
        private val PLACEHOLDER_LYRICS = listOf(
            Regex("^(?:此|该)?歌曲(?:为)?(?:一首)?(?:没有填词的|无歌词的)?纯音乐请您?欣赏$"),
            Regex("^纯音乐请您?欣赏$"),
            Regex("^(?:暂无|暂未匹配到|没有|无)歌词$"),
            Regex("^歌词暂无$")
        )
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36"
    }
}
