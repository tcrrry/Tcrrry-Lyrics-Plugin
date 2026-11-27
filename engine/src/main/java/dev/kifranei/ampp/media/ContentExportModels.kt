package dev.kifranei.ampp.media

import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID

/** Native page snapshots only. Artwork video sources must come from editorial artwork, never music. */
internal sealed class ContentExportAsset(val kind: ContentExportKind, open val title: String) {
    data class ArtistArtwork(override val title: String, val artwork: ArtworkImageSource?) :
        ContentExportAsset(ContentExportKind.ARTIST_ARTWORK, title)

    /** The stylized artist-name logo (editorialArtwork["musicContentColorLogoTrimmed"]); transparent PNG when the template allows. */
    data class ArtistWordmark(override val title: String, val artwork: ArtworkImageSource?) :
        ContentExportAsset(ContentExportKind.ARTIST_WORDMARK, title)

    /** A silent video is a separate asset from the artist's still JPEG. */
    data class ArtistMotionArtwork(override val title: String, val sources: List<MotionArtworkSource>) :
        ContentExportAsset(ContentExportKind.ARTIST_MOTION_ARTWORK, title)

    /** The plain album cover (static JPEG at native maximum size); separate from the animated cover. */
    data class AlbumArtwork(override val title: String, val artwork: ArtworkImageSource?) :
        ContentExportAsset(ContentExportKind.ALBUM_ARTWORK, title)

    data class AlbumMotionArtwork(override val title: String, val sources: List<MotionArtworkSource>) :
        ContentExportAsset(ContentExportKind.ALBUM_MOTION_ARTWORK, title)

    /** The complete parser input, including namespaces, timing, translations and metadata. */
    data class TtmlLyrics(override val title: String, val rawTtml: String?) :
        ContentExportAsset(ContentExportKind.TTML_LYRICS, title) {
        override fun toString() = "TtmlLyrics(title=$title, captured=${rawTtml != null})"
    }

    data class ArtistBiography(override val title: String, val text: ContentExportText?) :
        ContentExportAsset(ContentExportKind.ARTIST_BIOGRAPHY, title)

    data class AlbumEditorial(override val title: String, val text: ContentExportText?) :
        ContentExportAsset(ContentExportKind.ALBUM_EDITORIAL, title)

    fun unavailableReason(): ContentExportReason? = when (this) {
        is ArtistArtwork -> ContentExportReason.NO_ARTIST_ARTWORK.takeIf { artwork?.url.isNullOrBlank() }
        is ArtistWordmark -> ContentExportReason.NO_ARTIST_WORDMARK.takeIf { artwork?.url.isNullOrBlank() }
        is ArtistMotionArtwork -> ContentExportReason.NO_ARTIST_MOTION_ARTWORK.takeIf { sources.none { it.url.isNotBlank() } }
        is AlbumArtwork -> ContentExportReason.NO_ALBUM_ARTWORK.takeIf { artwork?.url.isNullOrBlank() }
        is AlbumMotionArtwork -> ContentExportReason.NO_MOTION_ARTWORK.takeIf { sources.none { it.url.isNotBlank() } }
        is TtmlLyrics -> ContentExportReason.NO_TTML.takeIf { rawTtml.isNullOrBlank() }
        is ArtistBiography -> ContentExportReason.NO_BIOGRAPHY.takeIf { text?.content.isNullOrBlank() }
        is AlbumEditorial -> ContentExportReason.NO_EDITORIAL.takeIf { text?.content.isNullOrBlank() }
    }
}

internal enum class ContentExportKind(val filenameTag: String, private val zh: String, private val en: String) {
    ARTIST_ARTWORK("artist-artwork", "艺术家封面（静态 JPEG）", "Artist artwork (still JPEG)"),
    ARTIST_WORDMARK("artist-wordmark", "艺术家字标（透明 PNG）", "Artist wordmark (transparent PNG)"),
    ARTIST_MOTION_ARTWORK("artist-motion", "艺术家动态封面（无声 MP4）", "Artist motion artwork (silent MP4)"),
    ALBUM_ARTWORK("album-artwork", "专辑封面（静态 JPEG，原始最大尺寸）", "Album cover (still JPEG, full resolution)"),
    ALBUM_MOTION_ARTWORK("album-motion", "专辑动态封面（无声 MP4）", "Album motion artwork (silent MP4)"),
    TTML_LYRICS("lyrics", "原始 TTML 歌词", "Original TTML lyrics"),
    ARTIST_BIOGRAPHY("artist-biography", "艺术家传记（文本）", "Artist biography (text)"),
    ALBUM_EDITORIAL("album-editorial", "专辑介绍（文本）", "Album introduction (text)");

    fun label(language: String): String = if (language.startsWith("zh", true)) zh else en
}

/** Use native maximum dimensions when supplied. A concrete URL without dimensions stays intact. */
internal data class ArtworkImageSource(
    val url: String,
    val originalWidth: Int? = null,
    val originalHeight: Int? = null,
    val crop: String = "bb",
) {
    fun jpegUrl(useOriginalSize: Boolean = true): String = resolvedUrl("jpg", useOriginalSize)

    /** Template URLs ask the image service for a lossless PNG (alpha kept); concrete URLs stay as published. */
    fun pngUrl(useOriginalSize: Boolean = true): String = resolvedUrl("png", useOriginalSize)

    private fun resolvedUrl(format: String, useOriginalSize: Boolean): String {
        val address = url.trim()
        val suffixAt = address.indexOfAny(charArrayOf('?', '#')).takeIf { it >= 0 } ?: address.length
        var resolved = address.substring(0, suffixAt)
        val suffix = address.substring(suffixAt)
        // Android ICU rejects the unescaped closing brace that the JVM regex engine accepted.
        val needsDimensions = resolved.contains("{w}", true) || resolved.contains("{h}", true)
        if (needsDimensions && (originalWidth == null || originalHeight == null || originalWidth <= 0 || originalHeight <= 0)) {
            throw ContentExportException(ContentExportFailure(ContentExportReason.ARTWORK_DIMENSIONS_UNKNOWN))
        }
        if (originalWidth != null && originalHeight != null && originalWidth > 0 && originalHeight > 0) {
            resolved = resolved.replace("{w}", originalWidth.toString(), true).replace("{h}", originalHeight.toString(), true)
            // Only native-sized last path components are rewritten; signed query parameters are untouched.
            if (useOriginalSize) {
                resolved = resolved.replace(Regex("/\\d+x\\d+(?=[a-zA-Z-]*\\.(?:jpg|jpeg|png|webp)$)")) {
                    "/${originalWidth}x${originalHeight}"
                }
            }
        }
        resolved = resolved.replace("{f}", format, true).replace("{c}", crop, true)
        // Some editorial logo templates hard-code ".../{w}x{h}bb.jpg"; the image service renders the
        // same size segment as PNG (with alpha) when asked, so only templated size segments are rewritten.
        if (format == "png" && needsDimensions) {
            resolved = resolved.replace(Regex("(/\\d+x\\d+[a-zA-Z-]*)\\.(?:jpg|jpeg|webp)$")) { "${it.groupValues[1]}.png" }
        }
        return resolved + suffix
    }

    override fun toString() = "ArtworkImageSource(url=<redacted>, originalWidth=$originalWidth, originalHeight=$originalHeight)"
}

internal enum class MotionArtworkSourceType { AUTO, DIRECT_MP4, HLS }

internal data class MotionArtworkSource(
    val url: String,
    val type: MotionArtworkSourceType = MotionArtworkSourceType.AUTO,
    val width: Int? = null,
    val height: Int? = null,
) {
    override fun toString() = "MotionArtworkSource(url=<redacted>, type=$type, width=$width, height=$height)"
}

internal enum class ContentExportTextFormat { PLAIN_TEXT, HTML }

internal data class ContentExportText(val content: String, val format: ContentExportTextFormat = ContentExportTextFormat.PLAIN_TEXT) {
    fun plainText(): String = if (format == ContentExportTextFormat.HTML) ContentExportHtml.toPlainText(content) else content
    override fun toString() = "ContentExportText(format=$format, characters=${content.length})"
}

/** A bounded subset sufficient for editorial paragraphs, lists, line breaks and common entities. */
internal object ContentExportHtml {
    private val entities = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "ndash" to "–", "mdash" to "—", "hellip" to "…", "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”",
        "copy" to "©", "reg" to "®", "trade" to "™", "bull" to "•", "middot" to "·", "eacute" to "é", "Eacute" to "É",
    )

    fun toPlainText(html: String): String {
        val withoutHidden = html.replace(Regex("<(script|style|head)\\b[^>]*>[\\s\\S]*?</\\1\\s*>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<!--[\\s\\S]*?-->"), "")
        val spaced = withoutHidden.replace(Regex("<[^>]*>")) { match ->
            val tag = match.value.lowercase(Locale.ROOT)
            when {
                Regex("<br\\b").containsMatchIn(tag) -> "\n"
                Regex("<li\\b").containsMatchIn(tag) -> "\n- "
                Regex("</li\\s*>").matches(tag) -> ""
                Regex("</?(?:p|div|h[1-6]|section|article|ul|ol|blockquote)\\b").containsMatchIn(tag) -> "\n\n"
                Regex("</(?:td|th)\\s*>").matches(tag) -> " "
                Regex("</tr\\s*>").matches(tag) -> "\n"
                else -> ""
            }
        }
        val decoded = spaced.replace(Regex("&(#x[0-9a-fA-F]+|#\\d+|[a-zA-Z]+);")) { match ->
            val entity = match.groupValues[1]
            if (!entity.startsWith('#')) entities[entity] ?: match.value else {
                val value = if (entity.startsWith("#x", true)) entity.substring(2).toIntOrNull(16) else entity.substring(1).toIntOrNull()
                if (value != null && value in 1..0x10ffff && value !in 0xd800..0xdfff) String(Character.toChars(value)) else match.value
            }
        }
        return decoded.replace("\r\n", "\n").replace('\r', '\n')
            .split('\n').joinToString("\n") { it.replace(Regex("[\\t\\x0B\\x0C \u00a0]+"), " ").trim() }
            .replace(Regex("\n{3,}"), "\n\n").trim()
    }
}

internal enum class ContentExportFileFormat(val mimeType: String, val extension: String) {
    JPEG("image/jpeg", "jpg"), PNG("image/png", "png"), MP4("video/mp4", "mp4"), TTML("application/ttml+xml", "ttml"), TEXT("text/plain", "txt")
}

/** No exception messages or URLs are exposed to UI or logging. */
internal enum class ContentExportReason(private val zh: String, private val en: String) {
    NO_ARTIST_ARTWORK("此艺术家没有可用的封面", "No artist artwork is available"),
    NO_ARTIST_WORDMARK("此艺术家没有特色字标", "This artist has no wordmark"),
    NO_ARTIST_MOTION_ARTWORK("此艺术家没有可用的动态封面", "No motion artwork is available for this artist"),
    NO_ALBUM_ARTWORK("此专辑没有可用的静态封面", "No static cover is available for this album"),
    NO_MOTION_ARTWORK("此专辑没有可用的动态封面", "No motion artwork is available for this album"),
    NO_TTML("尚未捕获此歌曲的原始 TTML 歌词", "Original TTML lyrics have not been captured for this song"),
    NO_BIOGRAPHY("此艺术家没有可用的传记", "No artist biography is available"),
    NO_EDITORIAL("此专辑没有可用的介绍", "No album introduction is available"),
    ARTWORK_DIMENSIONS_UNKNOWN("封面地址模板缺少原始尺寸", "The artwork URL template has no original dimensions"),
    UNSUPPORTED_URL("资产地址不可用或协议不受支持", "The asset URL is unavailable or uses an unsupported scheme"),
    UNSUPPORTED_ARTWORK_FORMAT("封面不是可用的 JPEG 图片", "The artwork is not a valid JPEG image"),
    UNSUPPORTED_WORDMARK_FORMAT("字标不是可用的 PNG/JPEG 图片", "The wordmark is not a valid PNG or JPEG image"),
    INVALID_TTML("捕获内容不是完整的 TTML 文档", "The captured content is not a complete TTML document"),
    ENCRYPTED_MEDIA("不支持下载加密或 DRM 动态封面", "Encrypted or DRM motion artwork cannot be exported"),
    AUDIO_NOT_ALLOWED("仅支持无声封面视频，不下载音乐或音轨", "Only silent artwork video is supported; music and audio tracks are excluded"),
    UNSUPPORTED_HLS("此 HLS 封面使用了不受支持的播放列表结构", "This HLS artwork uses an unsupported playlist structure"),
    UNSUPPORTED_TS("此动态封面使用 MPEG-TS，目前不支持导出", "MPEG-TS motion artwork export is not supported"),
    LIVE_PLAYLIST("动态封面播放列表尚未结束，无法完整保存", "The motion artwork playlist is not finite and cannot be saved completely"),
    INVALID_MEDIA("动态封面视频无效或不完整", "The motion artwork video is invalid or incomplete"),
    NETWORK_ERROR("下载失败，请检查网络后重试", "Download failed; check the network and try again"),
    TIMEOUT("导出超过配置的时间限制", "The export exceeded the configured time limit"),
    HTTP_ERROR("资产服务器未返回完整文件", "The asset server did not return a complete file"),
    SIZE_LIMIT("资产超过配置的大小限制", "The asset exceeds the configured size limit"),
    PLAYLIST_LIMIT("动态封面超过配置的片段或画质限制", "The motion artwork exceeds the configured segment or quality limits"),
    STORAGE_ERROR("文件保存失败", "The file could not be saved"),
    QUEUE_FULL("导出队列已满，请稍后重试", "The export queue is full; try again later"),
    CLOSED("导出功能已关闭", "The export service is closed"),
    CANCELLED("导出已取消", "The export was cancelled");

    fun message(language: String): String = if (language.startsWith("zh", true)) zh else en
}

internal data class ContentExportFailure(val reason: ContentExportReason, val httpStatus: Int? = null) {
    fun message(language: String): String = reason.message(language)
}

internal class ContentExportException(val failure: ContentExportFailure) : java.io.IOException(failure.reason.name)

internal data class ContentExportOptions(
    val connectTimeoutMillis: Int = 15_000,
    val readTimeoutMillis: Int = 15_000,
    val maxExportDurationMillis: Long = 180_000,
    val maxBytes: Long = 256L * 1024 * 1024,
    val maxTextBytes: Long = 8L * 1024 * 1024,
    val maxPlaylistBytes: Int = 1024 * 1024,
    val maxSegments: Int = 256,
    val maxPlaylistDepth: Int = 3,
    val maxVariants: Int = 32,
    val maxVideoPixels: Long = 4096L * 4096,
    val maxVariantBandwidth: Long = 30_000_000,
    val maxHlsDurationSeconds: Double = 300.0,
    val maxRedirects: Int = 5,
    val maxConcurrentExports: Int = 2,
    val maxQueuedExports: Int = 6,
    val allowCleartextHttp: Boolean = false,
    val useOriginalArtworkSize: Boolean = true,
) {
    init {
        require(connectTimeoutMillis > 0 && readTimeoutMillis > 0 && maxExportDurationMillis > 0)
        require(maxBytes > 0 && maxTextBytes > 0 && maxPlaylistBytes > 0)
        require(maxSegments > 0 && maxPlaylistDepth > 0 && maxVariants > 0)
        require(maxVideoPixels > 0 && maxVariantBandwidth > 0 && maxHlsDurationSeconds.isFinite() && maxHlsDurationSeconds > 0)
        require(maxRedirects >= 0 && maxConcurrentExports in 1..8 && maxQueuedExports in 1..64)
    }
}

internal enum class ContentExportLocation { SHARED_DOWNLOADS, APP_DOWNLOADS }

internal data class ContentExportFile(
    val uri: String,
    val displayName: String,
    val mimeType: String,
    val byteCount: Long,
    /** API 28 uses an app-owned folder without requesting a new storage permission. */
    val location: ContentExportLocation,
)

internal enum class ContentExportPhase {
    QUEUED, PREPARING, DOWNLOADING, SAVING, PUBLISHING, CANCELLING, SUCCEEDED, FAILED, CANCELLED;
    val isTerminal: Boolean get() = this == SUCCEEDED || this == FAILED || this == CANCELLED
}

internal data class ContentExportStatus(
    val jobId: String,
    val kind: ContentExportKind,
    val phase: ContentExportPhase,
    val bytesCopied: Long = 0,
    val totalBytes: Long? = null,
    val file: ContentExportFile? = null,
    val failure: ContentExportFailure? = null,
)

internal fun contentExportFileName(
    title: String,
    kind: ContentExportKind,
    format: ContentExportFileFormat,
    uniqueSuffix: String = UUID.randomUUID().toString(),
): String {
    val clean = title.map { char ->
        if (char.code < 32 || char.code in 0x7f..0x9f || char in "/\\:*?\"<>|" || char.code in 0x202a..0x202e || char.code in 0x2066..0x2069) '_' else char
    }.joinToString("").trim(' ', '.').ifBlank { "AMpp" }
    val stem = StringBuilder()
    var bytes = 0
    var position = 0
    while (position < clean.length) {
        val point = clean.codePointAt(position)
        val part = String(Character.toChars(point))
        val count = part.toByteArray(StandardCharsets.UTF_8).size
        if (bytes + count > 120) break
        stem.append(part); bytes += count; position += Character.charCount(point)
    }
    val safeStem = stem.toString().trimEnd(' ', '.').ifBlank { "AMpp" }
    val reserved = Regex("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?").matches(safeStem)
    val safeSuffix = uniqueSuffix.filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' }.take(40).ifBlank { UUID.randomUUID().toString() }
    return "${if (reserved) "_$safeStem" else safeStem}-${kind.filenameTag}-$safeSuffix.${format.extension}"
}
