package com.tcrrry.lyrics.engine.lyrics.source

import com.tcrrry.lyrics.engine.hook.AutoLyricsCandidate
import com.tcrrry.lyrics.engine.hook.DesktopLyricsTrack

import com.tcrrry.lyrics.engine.lyrics.TtmlInputPolicy
import com.tcrrry.lyrics.engine.lyrics.AmllTtmlFormatConverter
import com.tcrrry.lyrics.engine.model.CustomLyricsSources
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

/**
 * AMLL TTML DB client. Direct, fixed URL per Adam ID; failures return null.
 */
class AmllTtmlClient(private val transport: LyricHttpTransport) {
    fun fetch(adamId: Long): String? =
        transport.get("$AMLL_TTML_DB_BASE/am-lyrics/$adamId.ttml")

    companion object {
        const val AMLL_TTML_DB_BASE =
            "https://raw.githubusercontent.com/amll-dev/amll-ttml-db/refs/heads/main"
    }
}

/** One automatic source in the fixed playback lookup order. */
data class AutoLyricsSource(
    val name: String,
    val fetch: (Long) -> String?,
)

/**
 * Fetches the first structurally valid Word-TTML candidate. Source-specific
 * conversion stays here so the playback session only handles validation,
 * native parsing, caching, and publication.
 */
class AutoLyricsSourceResolver(
    private val sources: List<AutoLyricsSource>,
    private val desktopLyrics: ((DesktopLyricsTrack) -> AutoLyricsCandidate?)? = null,
    private val fallbackTranslation: ((Long, AutoLyricsCandidate) -> AutoLyricsCandidate)? = null,
    private val qualityFirst: () -> Boolean = { false },
) {
    fun fetch(appleMusicId: Long, track: DesktopLyricsTrack? = null, selectedRepository: String? = null): AutoLyricsCandidate? {
        if (appleMusicId <= 0L || Thread.currentThread().isInterrupted) return null
        if (selectedRepository != null) {
            val source = sources.firstOrNull { it.name == selectedRepository } ?: return null
            val ttml = runCatching { source.fetch(appleMusicId) }.getOrNull()
                ?.takeIf(TtmlInputPolicy::isAcceptable) ?: return null
            val candidate = AutoLyricsCandidate(source.name, ttml)
            return fallbackTranslation?.invoke(appleMusicId, candidate) ?: candidate
        }
        fun enrich(candidate: AutoLyricsCandidate): AutoLyricsCandidate =
            fallbackTranslation?.invoke(appleMusicId, candidate) ?: candidate
        val fetched = mutableMapOf<String, AutoLyricsCandidate?>()
        fun fetchSource(source: AutoLyricsSource): AutoLyricsCandidate? {
            if (source.name in fetched) return fetched[source.name]
            if (Thread.currentThread().isInterrupted) return null
            val ttml = runCatching { source.fetch(appleMusicId) }.onFailure {
                if (it is InterruptedException) Thread.currentThread().interrupt()
            }.getOrNull()?.takeIf(TtmlInputPolicy::isAcceptable)
            return ttml?.let { AutoLyricsCandidate(source.name, it) }.also { fetched[source.name] = it }
        }
        fun desktop(): AutoLyricsCandidate? = if (track == null || Thread.currentThread().isInterrupted) null
            else runCatching { desktopLyrics?.invoke(track) }.onFailure {
                if (it is InterruptedException) Thread.currentThread().interrupt()
            }.getOrNull()?.takeIf { TtmlInputPolicy.isAcceptable(it.ttml) }
        // An explicitly chosen platform must never silently select another library.
        if (track?.explicitSource == true) return desktop()
        val quality = qualityFirst()
        val author = sources.firstOrNull { it.name == CustomLyricsSources.AM_LYRICS }
        if (quality) {
            author?.let(::fetchSource)?.let { return enrich(it) }
            desktop()?.let { return it }
            return sources.asSequence().mapNotNull(::fetchSource).firstOrNull()?.let(::enrich)
        }
        // ID-only legacy callers retain their fixed repository lookup order.
        if (track == null) return sources.asSequence().mapNotNull(::fetchSource).firstOrNull()?.let(::enrich)
        val authorCandidate = author?.let(::fetchSource)
        if (Thread.currentThread().isInterrupted) return null
        if (authorCandidate?.ttml?.let(com.tcrrry.lyrics.engine.hook.TtmlTimingPolicy::hasTimedWords) == true)
            return enrich(authorCandidate)
        val platformCandidate = desktop()
        if (Thread.currentThread().isInterrupted) return null
        if (platformCandidate?.ttml?.let(com.tcrrry.lyrics.engine.hook.TtmlTimingPolicy::hasTimedWords) == true)
            return platformCandidate
        // Check remaining libraries for real word timings before accepting a line result.
        var otherLine: AutoLyricsCandidate? = null
        for (source in sources) {
            if (Thread.currentThread().isInterrupted) return null
            if (source === author) continue
            val candidate = fetchSource(source) ?: continue
            if (com.tcrrry.lyrics.engine.hook.TtmlTimingPolicy.hasTimedWords(candidate.ttml)) return enrich(candidate)
            if (otherLine == null) otherLine = candidate
        }
        // No word source: the install seam considers native AM first, then this quality order.
        return authorCandidate?.let(::enrich) ?: platformCandidate ?: otherLine?.let(::enrich)

    }

    companion object {
        /** Wires the fixed AMLL → Lunabeat → user's repository priority. */
        fun fixed(
            amll: AmllTtmlClient,
            amLyrics: AmLyricsClient,
            lunabeat: LunabeatClient,
            desktopLyrics: ((DesktopLyricsTrack) -> AutoLyricsCandidate?)? = null,
            fallbackTranslation: ((Long, AutoLyricsCandidate) -> AutoLyricsCandidate)? = null,
            qualityFirst: () -> Boolean = { false },
        ): AutoLyricsSourceResolver = AutoLyricsSourceResolver(
            listOf(
                AutoLyricsSource(CustomLyricsSources.AMLL) { raw ->
                    amll.fetch(raw)?.let { AmllTtmlFormatConverter.toAppleFormat(it).ttml }
                },
                AutoLyricsSource(CustomLyricsSources.LUNABEAT, lunabeat::fetch),
                AutoLyricsSource(CustomLyricsSources.AM_LYRICS, amLyrics::fetch),
            ),
            desktopLyrics,
            fallbackTranslation,
            qualityFirst,
        )
    }
}

/** User-owned TTML repository indexed by Apple Music Adam ID; settings process only. */
data class AmLyricsIndexEntry(
    val appleMusicId: Long,
    val alternateIds: List<Long>,
    val displayName: String,
    val path: String,
    val enabled: Boolean,
    val sizeBytes: Long,
    val sha256: String,
) {
    val allAppleMusicIds: List<Long>
        get() = listOf(appleMusicId) + alternateIds
}

data class AmLyricsIndex(
    val entries: List<AmLyricsIndexEntry>,
) {
    fun entryFor(appleMusicId: Long): AmLyricsIndexEntry? = entries.firstOrNull { entry ->
        appleMusicId in entry.allAppleMusicIds
    }
}

class AmLyricsClient(private val transport: LyricHttpTransport) {
    fun fetch(adamId: Long): String? {
        if (adamId <= 0L) return null
        val entry = fetchIndex()?.entryFor(adamId) ?: return null
        return fetchTtml(entry)
    }

    fun fetchIndex(): AmLyricsIndex? = runCatching {
        val bytes = transport.getBytes(AM_LYRICS_INDEX_URL) ?: return@runCatching null
        parseIndex(bytes.toString(Charsets.UTF_8))
    }.getOrNull()

    fun fetchTtml(entry: AmLyricsIndexEntry): String? {
        if (!entry.enabled) return null
        val path = encodePath(entry.path) ?: return null
        val bytes = transport.getBytes("$AM_LYRICS_BASE/$path") ?: return null
        if (bytes.size.toLong() != entry.sizeBytes) return null
        if (!sha256(bytes).equals(entry.sha256, ignoreCase = true)) return null
        val ttml = bytes.toString(Charsets.UTF_8)
        return ttml.takeIf(TtmlInputPolicy::isAcceptable)
    }

    internal companion object {
        const val AM_LYRICS_BASE = "https://raw.githubusercontent.com/Zennmn/am-lyrics/main"
        const val AM_LYRICS_INDEX_URL = "$AM_LYRICS_BASE/index.json"
        private const val AM_LYRICS_ROOT = "am-lyrics/"
        private const val INDEX_VERSION = 1
        private val SHA256_PATTERN = Regex("[0-9a-fA-F]{64}")

        fun parseIndex(indexJson: String): AmLyricsIndex? = runCatching {
            val root = JSONObject(indexJson)
            if (root.optInt("version", 0) != INDEX_VERSION) return@runCatching null
            if (root.optString("layout") != "artist-title-id") return@runCatching null
            val entries = root.optJSONArray("entries") ?: return@runCatching null
            val parsed = entries.parseEntries() ?: return@runCatching null
            val allIds = mutableSetOf<Long>()
            parsed.forEach { entry ->
                entry.allAppleMusicIds.forEach { id ->
                    if (!allIds.add(id)) return@runCatching null
                }
            }
            AmLyricsIndex(parsed)
        }.getOrNull()

        private fun JSONArray.parseEntries(): List<AmLyricsIndexEntry>? = buildList {
            for (index in 0 until length()) {
                val raw = optJSONObject(index) ?: return null
                val appleMusicId = parsePositiveLong(raw.opt("appleMusicId"))
                    ?: return null
                val alternateIds = parseAlternateIds(raw.optJSONArray("alternateIds"))
                    ?: return null
                val path = raw.optString("path").takeIf(String::isNotBlank)
                    ?.takeIf { isSafePath(it) } ?: return null
                val sizeBytes = raw.optLong("sizeBytes", 0L)
                    .takeIf { it in 1L..TtmlInputPolicy.MAX_TTML_BYTES.toLong() }
                    ?: return null
                val sha256 = raw.optString("sha256")
                    .takeIf(SHA256_PATTERN::matches) ?: return null
                val displayName = raw.optString("displayName").trim().ifBlank {
                    listOfNotNull(
                        raw.optString("title").takeIf(String::isNotBlank),
                        raw.optString("artist").takeIf(String::isNotBlank),
                    ).joinToString(" - ").ifBlank { "GitHub 自定义歌词" }
                }
                add(
                    AmLyricsIndexEntry(
                        appleMusicId = appleMusicId,
                        alternateIds = alternateIds,
                        displayName = displayName,
                        path = path,
                        enabled = raw.optBoolean("enabled", true),
                        sizeBytes = sizeBytes,
                        sha256 = sha256.lowercase(),
                    ),
                )
            }
        }

        private fun parseAlternateIds(array: JSONArray?): List<Long>? {
            if (array == null) return emptyList()
            val result = linkedSetOf<Long>()
            for (index in 0 until array.length()) {
                val id = parsePositiveLong(array.opt(index)) ?: return null
                result += id
            }
            return result.toList()
        }

        private fun parsePositiveLong(value: Any?): Long? = when (value) {
            is Long -> value
            is Int -> value.toLong()
            is Short -> value.toLong()
            is Byte -> value.toLong()
            is String -> value.trim().toLongOrNull()
            else -> null
        }?.takeIf { it > 0L }

        private fun isSafePath(path: String): Boolean =
            path.startsWith(AM_LYRICS_ROOT) &&
                !path.contains('\\') &&
                path.split('/').none { it.isEmpty() || it == "." || it == ".." }

        private fun sha256(bytes: ByteArray): String =
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun encodePath(path: String): String? {
        if (!isSafePath(path)) return null
        val segments = path.split('/')
        return segments.joinToString("/") { segment ->
            URLEncoder.encode(segment, Charsets.UTF_8.name()).replace("+", "%20")
        }
    }
}
