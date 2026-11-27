package dev.kifranei.ampp.media

import org.json.JSONObject
import java.util.LinkedHashMap

internal enum class ContentDownloadPageKind {
    ARTIST, ALBUM;
    companion object {
        fun fromNativeType(type: String?): ContentDownloadPageKind? = when (type) {
            "artists", "library-artists" -> ARTIST
            "albums", "library-albums" -> ALBUM
            else -> null
        }
    }
}

/** Only exportable metadata is retained; no response objects, headers, credentials or native pointers. */
internal data class ContentDownloadPage(
    val kind: ContentDownloadPageKind,
    val id: String,
    val title: String,
    val artwork: ArtworkImageSource? = null,
    val motion: List<MotionArtworkSource> = emptyList(),
    val biography: ContentExportText? = null,
    val editorial: ContentExportText? = null,
    /** Only present when the native artist exposes a stylized name logo. */
    val wordmark: ArtworkImageSource? = null,
) {
    fun assets(): List<ContentExportAsset> = when (kind) {
        ContentDownloadPageKind.ARTIST -> listOfNotNull(ContentExportAsset.ArtistArtwork(title, artwork),
            wordmark?.let { ContentExportAsset.ArtistWordmark(title, it) },
            ContentExportAsset.ArtistMotionArtwork(title, motion), ContentExportAsset.ArtistBiography(title, biography))
        ContentDownloadPageKind.ALBUM -> listOf(ContentExportAsset.AlbumArtwork(title, artwork), ContentExportAsset.AlbumMotionArtwork(title, motion), ContentExportAsset.AlbumEditorial(title, editorial))
    }
}

internal data class ContentDownloadCaptureOptions(
    val maxSongs: Int = 24,
    val maxPages: Int = 24,
    val maxTtmlChars: Int = 2 * 1024 * 1024,
    val maxTotalTtmlChars: Int = 8 * 1024 * 1024,
    val maxTextChars: Int = 2 * 1024 * 1024,
    val maxSources: Int = 16,
    val maxLoaderContexts: Int = 64,
    val maxLoaderDepth: Int = 8,
) {
    init {
        require(maxSongs > 0 && maxPages > 0 && maxTtmlChars > 0 && maxTotalTtmlChars >= maxTtmlChars)
        require(maxTextChars > 0 && maxSources > 0 && maxLoaderContexts > 0 && maxLoaderDepth > 0)
    }
}

/** UTF-16 character budgets bound retained String memory without re-encoding lyrics on a hook thread. */
internal class ContentDownloadCapture(val options: ContentDownloadCaptureOptions = ContentDownloadCaptureOptions()) : AutoCloseable {
    private data class PageKey(val kind: ContentDownloadPageKind, val id: String)
    private data class LoadStack(val ids: ArrayList<Long?> = ArrayList(), var overflow: Int = 0)
    private val lyrics = LinkedHashMap<Long, String>(16, .75f, true)
    private val pages = LinkedHashMap<PageKey, ContentDownloadPage>(16, .75f, true)
    private val loads = HashMap<Any, LoadStack>()
    private var lyricChars = 0L
    private var closed = false

    @Synchronized fun recordTtml(songId: Long, rawTtml: String): Boolean {
        if (closed || songId <= 0 || rawTtml.length > options.maxTtmlChars || !isCompleteExportTtml(rawTtml)) return false
        lyrics.remove(songId)?.let { lyricChars -= it.length }
        lyrics[songId] = rawTtml
        lyricChars += rawTtml.length
        while (lyrics.size > options.maxSongs || lyricChars > options.maxTotalTtmlChars) {
            val oldest = lyrics.entries.iterator()
            val entry = oldest.next(); lyricChars -= entry.value.length; oldest.remove()
        }
        return true
    }

    @Synchronized fun ttmlAsset(songId: Long, title: String): ContentExportAsset.TtmlLyrics =
        ContentExportAsset.TtmlLyrics(title, if (!closed && songId > 0) lyrics[songId] else null)

    @Synchronized fun recordPage(page: ContentDownloadPage): Boolean {
        if (closed || !validContentDownloadId(page.id)) return false
        pages[PageKey(page.kind, page.id)] = page.copy(motion = page.motion.take(options.maxSources).toList())
        while (pages.size > options.maxPages) pages.entries.iterator().apply { next(); remove() }
        return true
    }

    @Synchronized fun page(kind: ContentDownloadPageKind, id: String): ContentDownloadPage? =
        if (closed) null else pages[PageKey(kind, id)]

    /** Explicit loader IDs only: unknown nested loads suppress the outer ID instead of guessing a song. */
    @Synchronized fun beginLoad(songId: Long?, execution: Any = Thread.currentThread()) {
        if (closed) return
        var stack = loads[execution]
        if (stack == null) {
            if (loads.size >= options.maxLoaderContexts) return
            stack = LoadStack(); loads[execution] = stack
        }
        if (stack.overflow > 0 || stack.ids.size >= options.maxLoaderDepth) stack.overflow++
        else stack.ids += songId?.takeIf { it > 0 }
    }

    @Synchronized fun endLoad(execution: Any = Thread.currentThread()) {
        val stack = loads[execution] ?: return
        if (stack.overflow > 0) stack.overflow-- else if (stack.ids.isNotEmpty()) stack.ids.removeAt(stack.ids.lastIndex)
        if (stack.ids.isEmpty() && stack.overflow == 0) loads.remove(execution)
    }

    @Synchronized fun parserSongId(execution: Any = Thread.currentThread()): Long? {
        val stack = loads[execution] ?: return null
        return if (!closed && stack.overflow == 0) stack.ids.lastOrNull() else null
    }

    @Synchronized override fun close() {
        closed = true
        lyrics.clear(); pages.clear(); loads.clear(); lyricChars = 0
    }
}

internal fun validContentDownloadId(id: String): Boolean = id.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,255}"))

/** Reads only version-verified getters. A failed optional getter degrades that asset, not the page. */
internal fun interface ContentDownloadNativeAccess {
    fun get(owner: Any, contract: String): Any?
}

internal class ContentDownloadPageDecoder(private val options: ContentDownloadCaptureOptions = ContentDownloadCaptureOptions()) {
    fun fromNative(entity: Any, access: ContentDownloadNativeAccess): ContentDownloadPage? {
        fun get(owner: Any?, key: String): Any? = owner?.let { runCatching { access.get(it, key) }.getOrNull() }
        val id = get(entity, "content-download-entity-id") as? String ?: return null
        if (!validContentDownloadId(id)) return null
        val kind = ContentDownloadPageKind.fromNativeType(get(entity, "content-download-entity-type") as? String) ?: return null
        val title = (get(entity, "media-entity-get-title-method") as? String).orEmpty().take(512)
        val attributes = get(entity, "media-entity-get-attributes-method")
        fun image(artwork: Any?): ArtworkImageSource? {
            val url = sourceUrl(get(artwork, "content-download-artwork-url")) ?: return null
            return ArtworkImageSource(url, dimension(get(artwork, "content-download-artwork-width")), dimension(get(artwork, "content-download-artwork-height")))
        }
        val primary = image(get(attributes, "content-download-attrs-artwork"))
        val editorialEntries = (get(attributes, "content-download-attrs-editorial-artwork") as? Map<*, *>)?.entries
            ?.take(options.maxSources).orEmpty()
        val wordmark = if (kind == ContentDownloadPageKind.ARTIST)
            editorialEntries.firstOrNull { it.key == ARTIST_WORDMARK_ARTWORK_KEY }?.let { image(it.value) } else null
        // The logo is a wide transparent wordmark, never a usable artist portrait fallback.
        val editorialArt = editorialEntries.filter { it.key != ARTIST_WORDMARK_ARTWORK_KEY }.mapNotNull { image(it.value) }
            .maxByOrNull { (it.originalWidth ?: 0).toLong() * (it.originalHeight ?: 0) }
        val motion = ArrayList<MotionArtworkSource>()
        val videos = get(attributes, "content-download-attrs-videos") as? Map<*, *>
        videos?.values?.take(options.maxSources)?.forEach { video ->
            (get(video, "content-download-video-files") as? List<*>)?.take(options.maxSources)?.forEach { file ->
                sourceUrl(get(file, "content-download-file-url"))?.let { url ->
                    motion += MotionArtworkSource(url, MotionArtworkSourceType.DIRECT_MP4,
                        dimension(get(file, "content-download-file-width")), dimension(get(file, "content-download-file-height")))
                }
            }
            sourceUrl(get(video, "content-download-video-hls"))?.let { motion += MotionArtworkSource(it, MotionArtworkSourceType.HLS) }
        }
        val notes = get(attributes, "content-download-attrs-notes")
        val editorial = text(get(notes, "content-download-notes-standard")) ?: text(get(notes, "content-download-notes-short"))
        return ContentDownloadPage(kind, id, title, primary ?: editorialArt,
            boundedMotion(motion), if (kind == ContentDownloadPageKind.ARTIST) text(get(attributes, "content-download-attrs-biography")) else null,
            if (kind == ContentDownloadPageKind.ALBUM) editorial else null, wordmark)
    }

    /** Optional seam for an already requested JSON entity. The complete response is never retained. */
    fun fromJson(response: JSONObject): ContentDownloadPage? {
        val data = response.opt("data")
        val entity = if (data is org.json.JSONArray) data.optJSONObject(0) ?: return null
            else if (data is JSONObject) data else response
        val id = entity.opt("id") as? String ?: return null
        if (!validContentDownloadId(id)) return null
        val kind = ContentDownloadPageKind.fromNativeType(entity.opt("type") as? String) ?: return null
        val attributes = entity.optJSONObject("attributes") ?: JSONObject()
        val title = (attributes.opt("name") as? String).orEmpty().take(512)
        fun image(art: JSONObject?): ArtworkImageSource? {
            val url = sourceUrl(art?.opt("url")) ?: return null
            return ArtworkImageSource(url, dimension(art?.opt("width")), dimension(art?.opt("height")))
        }
        val primary = image(attributes.optJSONObject("artwork"))
        val editorialMap = attributes.optJSONObject("editorialArtwork")
        val wordmark = if (kind == ContentDownloadPageKind.ARTIST) image(editorialMap?.optJSONObject(ARTIST_WORDMARK_ARTWORK_KEY)) else null
        val editorialArt = editorialMap?.let { map ->
            map.keys().asSequence().take(options.maxSources).filter { it != ARTIST_WORDMARK_ARTWORK_KEY }.mapNotNull { image(map.optJSONObject(it)) }
                .maxByOrNull { (it.originalWidth ?: 0).toLong() * (it.originalHeight ?: 0) }
        }
        val motion = ArrayList<MotionArtworkSource>()
        attributes.optJSONObject("editorialVideo")?.let { videos ->
            videos.keys().asSequence().take(options.maxSources).forEach { key ->
                val video = videos.optJSONObject(key) ?: return@forEach
                video.optJSONArray("videoFile")?.let { files ->
                    for (index in 0 until minOf(files.length(), options.maxSources)) {
                        val file = files.optJSONObject(index) ?: continue
                        sourceUrl(file.opt("assetUrl"))?.let { url -> motion += MotionArtworkSource(url, MotionArtworkSourceType.DIRECT_MP4,
                            dimension(file.opt("width")), dimension(file.opt("height"))) }
                    }
                }
                sourceUrl(video.opt("video"))?.let { motion += MotionArtworkSource(it, MotionArtworkSourceType.HLS) }
            }
        }
        val notes = attributes.optJSONObject("editorialNotes")
        return ContentDownloadPage(kind, id, title, primary ?: editorialArt, boundedMotion(motion),
            if (kind == ContentDownloadPageKind.ARTIST) text(attributes.opt("artistBio")) else null,
            if (kind == ContentDownloadPageKind.ALBUM) text(notes?.opt("standard")) ?: text(notes?.opt("short")) else null, wordmark)
    }

    private fun text(value: Any?): ContentExportText? = (value as? String)?.takeIf { it.isNotBlank() && it.length <= options.maxTextChars }
        ?.let { ContentExportText(it, ContentExportTextFormat.HTML) }
    private fun sourceUrl(value: Any?): String? = (value as? String)?.trim()?.takeIf { it.isNotEmpty() && it.length <= 16_384 }
    private fun dimension(value: Any?): Int? = (value as? Number)?.toLong()?.takeIf { it in 1..Int.MAX_VALUE }?.toInt()
    private fun boundedMotion(sources: List<MotionArtworkSource>): List<MotionArtworkSource> {
        val unique = sources.distinctBy { it.url }
        // Retain a real HLS alternative even when a page exposes many direct video renditions.
        val direct = unique.filter { it.type == MotionArtworkSourceType.DIRECT_MP4 }
        val hls = unique.filter { it.type == MotionArtworkSourceType.HLS }
        return if (hls.isNotEmpty()) direct.take(maxOf(0, options.maxSources - 1)) + hls.take(1) else direct.take(options.maxSources)
    }
}

/** 1609 EditorialImageType.ARTIST_LOGO, read by profiles2.a via ka.d.a.b(entity, key) for the artist header. */
internal const val ARTIST_WORDMARK_ARTWORK_KEY = "musicContentColorLogoTrimmed"

/** Coroutine resumes pass zero arguments; only a verified saved ID from the loader's continuation is used. */
internal fun contentDownloadLoaderSongId(argument: Any?, resumeLabel: Int?, savedId: Long?): Long? {
    if (resumeLabel != null && resumeLabel and Int.MIN_VALUE != 0) return savedId?.takeIf { it > 0 }
    return (argument as? Number)?.toLong()?.takeIf { it > 0 }
}
