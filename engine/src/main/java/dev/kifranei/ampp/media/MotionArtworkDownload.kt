package dev.kifranei.ampp.media

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.util.Locale

/** Blocking implementation; ContentExportStorage calls it only on its bounded worker pool. */
internal class MotionArtworkDownload(
    private val network: ContentExportNetwork,
    private val options: ContentExportOptions = ContentExportOptions(),
    private val missingReason: ContentExportReason = ContentExportReason.NO_MOTION_ARTWORK,
    private val onStructure: (String) -> Unit = {},
) {
    /** Only a verified silent MP4 is returned. On any failure the staging file is removed. */
    fun download(
        sources: List<MotionArtworkSource>,
        staged: File,
        control: ContentExportControl,
        onProgress: (Long, Long?) -> Unit = { _, _ -> },
    ): ContentExportFileFormat {
        val candidates = sources.filter { it.url.isNotBlank() }.distinctBy { it.url }.sortedBy { source ->
            when {
                source.type == MotionArtworkSourceType.DIRECT_MP4 -> 0
                source.type == MotionArtworkSourceType.AUTO && source.url.substringBefore('?').substringBefore('#').endsWith(".mp4", true) -> 1
                source.type == MotionArtworkSourceType.AUTO -> 2
                else -> 3
            }
        }
        if (candidates.isEmpty()) {
            runCatching { staged.delete() }
            exportFailure(missingReason)
        }
        val budget = ContentExportByteBudget(options.maxBytes, control)
        var failure = ContentExportFailure(missingReason)
        try {
            for (source in candidates) {
                control.check()
                try {
                    if (source.type != MotionArtworkSourceType.HLS && source.width != null && source.height != null &&
                        source.width.toLong() * source.height > options.maxVideoPixels) exportFailure(ContentExportReason.PLAYLIST_LIMIT)
                    checkedContentExportUri(source.url, options)
                    withExportNetwork { network.open(source.url, control) }.use { response ->
                            checkArtworkResponse(response)
                            val input = BufferedInputStream(response.input)
                            if (isArtworkPlaylist(response, input)) {
                                val playlist = readPlaylist(response, input, budget)
                                downloadHls(response.finalUrl, playlist, staged, budget, control, onProgress)
                            } else {
                                FileOutputStream(staged).use { output ->
                                    copyContentExportBytes(input, output, budget, response.contentLength, onProgress)
                                    output.fd.sync()
                                }
                                checkDimensions(MotionArtworkMp4.validate(staged, control))
                            }
                    }
                    control.check()
                    return ContentExportFileFormat.MP4
                } catch (error: ContentExportException) {
                    failure = error.failure
                    if (failure.reason in setOf(ContentExportReason.CANCELLED, ContentExportReason.TIMEOUT, ContentExportReason.SIZE_LIMIT)) throw error
                    // A failed direct asset may have a real native HLS alternative. No URL is guessed.
                    if (staged.exists() && !staged.delete()) exportFailure(ContentExportReason.STORAGE_ERROR)
                }
            }
            throw ContentExportException(failure)
        } catch (error: Throwable) {
            runCatching { staged.delete() }
            throw error
        }
    }

    private fun downloadHls(
        initialUrl: String,
        initialText: String,
        staged: File,
        budget: ContentExportByteBudget,
        control: ContentExportControl,
        onProgress: (Long, Long?) -> Unit,
    ) {
        var url = initialUrl
        var text = initialText
        val visited = HashSet<String>()
        var depth = 0
        var media: HlsArtworkPlaylist.Media
        while (true) {
            control.check()
            if (!visited.add(url) || ++depth > options.maxPlaylistDepth) exportFailure(ContentExportReason.UNSUPPORTED_HLS)
            val structure = hlsArtworkStructure(text)
            val playlist = try { HlsArtworkParser.parse(url, text, options) } catch (error: ContentExportException) {
                runCatching { onStructure("stage=parse reason=${error.failure.reason} $structure") }
                throw error
            }
            runCatching { onStructure("stage=parse reason=OK $structure") }
            when (playlist) {
                is HlsArtworkPlaylist.Master -> {
                    if (depth >= options.maxPlaylistDepth) exportFailure(ContentExportReason.UNSUPPORTED_HLS)
                    val variant = try { HlsArtworkParser.selectVideo(playlist.variants, options) } catch (error: ContentExportException) {
                        runCatching { onStructure("stage=variant reason=${error.failure.reason} $structure") }
                        throw error
                    }
                    withExportNetwork { network.open(variant.url, control) }.use { response ->
                        checkArtworkResponse(response)
                        url = response.finalUrl
                        text = readPlaylist(response, response.input, budget)
                    }
                }
                is HlsArtworkPlaylist.Media -> { media = playlist; break }
            }
        }
        if (media.initializationRange != null || media.segmentRanges.any { it != null }) {
            // Native Apple cover playlists describe consecutive regions of one complete MP4.
            // Copy its bounded prefix once, preserving every original fragment-relative offset.
            val first = media.initializationRange
            var end = first?.endExclusive ?: 0L
            var contiguous = first?.offset == 0L && media.segmentRanges.size == media.segmentUrls.size
            media.segmentUrls.zip(media.segmentRanges).forEach { (address, range) ->
                if (address != media.initializationUrl || range == null || range.offset != end) contiguous = false
                if (range != null) end = range.endExclusive
            }
            runCatching { onStructure("stage=ranges contiguous=$contiguous segments=${media.segmentUrls.size}") }
            if (!contiguous || end <= 0) exportFailure(ContentExportReason.UNSUPPORTED_HLS)
            if (end > budget.remaining) exportFailure(ContentExportReason.SIZE_LIMIT)
            withExportNetwork { network.open(media.initializationUrl, control) }.use { response ->
                checkArtworkResponse(response)
                if (response.contentLength != null && response.contentLength < end) exportFailure(ContentExportReason.HTTP_ERROR)
                FileOutputStream(staged).use { output ->
                    copyContentExportBytes(ArtworkPrefixInputStream(response.input, end), output, budget, end, onProgress)
                    output.fd.sync()
                }
            }
            checkDimensions(MotionArtworkMp4.validate(staged, control, requireMonotonicFragments = true))
            return
        }
        var track: MotionArtworkMp4.VideoTrack
        var copied = 0L
        withExportNetwork { network.open(media.initializationUrl, control) }.use { response ->
            checkArtworkResponse(response)
            FileOutputStream(staged).use { output ->
                copied += copyContentExportBytes(response.input, output, budget, response.contentLength) { local, _ -> onProgress(local, null) }
                output.fd.sync()
            }
        }
        track = MotionArtworkMp4.validateInitialization(staged, control)
        checkDimensions(track)
        var previousDecodeTime = -1L
        for (segment in media.segmentUrls) {
            control.check()
            val start = staged.length()
            withExportNetwork { network.open(segment, control) }.use { response ->
                checkArtworkResponse(response)
                FileOutputStream(staged, true).use { output ->
                    val before = copied
                    copied += copyContentExportBytes(response.input, output, budget, response.contentLength) { local, _ -> onProgress(before + local, null) }
                    output.fd.sync()
                }
            }
            val times = MotionArtworkMp4.validateFragment(staged, start, staged.length(), track, control)
            for (time in times) {
                if (time < previousDecodeTime) exportFailure(ContentExportReason.UNSUPPORTED_HLS)
                previousDecodeTime = time
            }
        }
        // No text playlists, transport streams, audio tracks or initialization-only files can pass.
        MotionArtworkMp4.validate(staged, control)
        onProgress(staged.length(), staged.length())
    }

    private fun isArtworkPlaylist(response: ContentExportResponse, input: BufferedInputStream): Boolean {
        input.mark(64)
        val prefix = ByteArray(32)
        val count = input.read(prefix)
        input.reset()
        val startsWithHeader = count > 0 && String(prefix, 0, count, StandardCharsets.UTF_8).removePrefix("\uFEFF").trimStart().startsWith("#EXTM3U")
        return startsWithHeader || response.mimeType?.lowercase(Locale.ROOT) in setOf("application/vnd.apple.mpegurl", "application/x-mpegurl") ||
            response.finalUrl.substringBefore('?').substringBefore('#').endsWith(".m3u8", true)
    }

    private fun checkArtworkResponse(response: ContentExportResponse) {
        if (response.mimeType?.startsWith("audio/", true) == true) exportFailure(ContentExportReason.AUDIO_NOT_ALLOWED)
    }

    private fun checkDimensions(track: MotionArtworkMp4.VideoTrack) {
        if (track.width <= 0 || track.height <= 0) exportFailure(ContentExportReason.INVALID_MEDIA)
        if (track.width.toLong() * track.height > options.maxVideoPixels) exportFailure(ContentExportReason.PLAYLIST_LIMIT)
    }

    private fun readPlaylist(response: ContentExportResponse, input: InputStream, budget: ContentExportByteBudget): String {
        response.contentLength?.let { if (it > options.maxPlaylistBytes || it > budget.remaining) exportFailure(ContentExportReason.SIZE_LIMIT) }
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        var size = 0
        while (true) {
            budget.check()
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            if (count > options.maxPlaylistBytes - size) exportFailure(ContentExportReason.SIZE_LIMIT)
            budget.add(count); size += count
            output.write(buffer, 0, count)
        }
        if (response.contentLength != null && size.toLong() != response.contentLength) exportFailure(ContentExportReason.HTTP_ERROR)
        return output.toString(StandardCharsets.UTF_8.name())
    }
}

/** Never read/download trailing bytes beyond the finite playlist's last segment. */
private class ArtworkPrefixInputStream(input: InputStream, private var remaining: Long) : FilterInputStream(input) {
    override fun read(): Int {
        if (remaining == 0L) return -1
        return `in`.read().also { if (it >= 0) remaining-- }
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return -1
        return `in`.read(buffer, offset, minOf(remaining, length.toLong()).toInt()).also { if (it > 0) remaining -= it }
    }
}

/** Diagnostic values are whitelisted; arbitrary attributes, comments, hostnames and URI paths never leave this function. */
internal fun hlsArtworkStructure(text: String): String {
    val knownTags = setOf("#EXTM3U", "#EXTINF", "#EXT-X-STREAM-INF", "#EXT-X-I-FRAME-STREAM-INF", "#EXT-X-MEDIA",
        "#EXT-X-MAP", "#EXT-X-KEY", "#EXT-X-SESSION-KEY", "#EXT-X-ENDLIST", "#EXT-X-TARGETDURATION", "#EXT-X-MEDIA-SEQUENCE",
        "#EXT-X-DISCONTINUITY", "#EXT-X-DISCONTINUITY-SEQUENCE", "#EXT-X-BYTERANGE", "#EXT-X-PART", "#EXT-X-PART-INF",
        "#EXT-X-PRELOAD-HINT", "#EXT-X-SKIP", "#EXT-X-DEFINE", "#EXT-X-SESSION-DATA", "#EXT-X-INDEPENDENT-SEGMENTS",
        "#EXT-X-VERSION", "#EXT-X-PLAYLIST-TYPE", "#EXT-X-START", "#EXT-X-SERVER-CONTROL", "#EXT-X-DATERANGE",
        "#EXT-X-RENDITION-REPORT", "#EXT-X-ALLOW-CACHE")
    val formats = setOf("mp4", "m4s", "m3u8", "ts", "aac", "m4a", "mp3")
    fun format(uri: String): String = uri.substringBefore('?').substringBefore('#').substringAfterLast('/').substringAfterLast('.', "")
        .lowercase(Locale.ROOT).takeIf(formats::contains) ?: "opaque"
    val tags = LinkedHashSet<String>()
    val segmentFormats = LinkedHashSet<String>()
    val mapFormats = LinkedHashSet<String>()
    val videoCodecs = setOf("avc1", "avc3", "hev1", "hvc1", "dvh1", "dvhe", "av01", "vp09", "vp9", "mp4v")
    val audioCodecs = setOf("mp4a", "ac-3", "ec-3", "opus", "flac", "alac", "aac", "mp3")
    val codecKinds = LinkedHashSet<String>()
    val keyMethods = LinkedHashSet<String>()
    var variants = 0; var segments = 0; var unknownTags = 0; var malformedAttributes = 0
    var malformedVariants = 0; var audioGroups = 0; var mapByteRanges = false
    var pendingVariant = false; var header = false; var firstLine = true; var uriVariables = false; var examined = 0
    for (raw in text.removePrefix("\uFEFF").lineSequence().take(8192)) {
        examined++
        val line = raw.trim()
        if (line.isEmpty()) continue
        if (firstLine) { header = line == "#EXTM3U"; firstLine = false }
        uriVariables = uriVariables || line.contains("{$")
        if (line.startsWith('#')) {
            val tag = line.substringBefore(':')
            if (tag in knownTags) tags += tag else if (tag.startsWith("#EXT")) unknownTags++
            if (tag == "#EXT-X-STREAM-INF") {
                pendingVariant = true
                val attributes = runCatching { HlsArtworkParser.attributes(line.substringAfter(':', "")) }.getOrNull()
                if (attributes == null) malformedVariants++ else {
                    if (attributes.containsKey("AUDIO")) audioGroups++
                    attributes["CODECS"]?.split(',')?.forEach { codec ->
                        val family = codec.trim().lowercase(Locale.ROOT).substringBefore('.')
                        codecKinds += when (family) {
                            in videoCodecs -> "video:$family"
                            in audioCodecs -> "audio:$family"
                            else -> "unknown"
                        }
                    }
                }
            } else if (tag == "#EXT-X-KEY" || tag == "#EXT-X-SESSION-KEY") {
                val method = runCatching { HlsArtworkParser.attributes(line.substringAfter(':', ""))["METHOD"] }.getOrNull()
                keyMethods += method?.takeIf { it in setOf("NONE", "AES-128", "SAMPLE-AES", "SAMPLE-AES-CTR") } ?: "unknown"
            } else if (tag == "#EXT-X-MAP") {
                val attributes = runCatching { HlsArtworkParser.attributes(line.substringAfter(':', "")) }.getOrNull()
                if (attributes == null) malformedAttributes++ else {
                    attributes["URI"]?.let { mapFormats += format(it) }
                    mapByteRanges = mapByteRanges || attributes.containsKey("BYTERANGE")
                }
            }
        } else if (pendingVariant) { variants++; pendingVariant = false }
        else { segments++; segmentFormats += format(line) }
    }
    return "header=$header tags=${tags.sorted()} variants=$variants segments=$segments mapFormats=${mapFormats.sorted()} " +
        "segmentFormats=${segmentFormats.sorted()} hasEndList=${"#EXT-X-ENDLIST" in tags} hasMap=${"#EXT-X-MAP" in tags} " +
        "hasByteRanges=${mapByteRanges || "#EXT-X-BYTERANGE" in tags} codecKinds=${codecKinds.sorted()} audioGroups=$audioGroups keyMethods=${keyMethods.sorted()} " +
        "uriVariables=$uriVariables unknownTags=$unknownTags malformedMapAttributes=$malformedAttributes malformedVariantAttributes=$malformedVariants lineLimit=${examined == 8192}"
}

internal sealed class HlsArtworkPlaylist {
    data class Master(val variants: List<HlsArtworkVariant>) : HlsArtworkPlaylist()
    data class Media(val initializationUrl: String, val segmentUrls: List<String>,
        val initializationRange: HlsArtworkByteRange? = null, val segmentRanges: List<HlsArtworkByteRange?> = emptyList()) : HlsArtworkPlaylist() {
        override fun toString() = "HlsArtworkPlaylist.Media(urls=<redacted>, segments=${segmentUrls.size})"
    }
}

internal data class HlsArtworkByteRange(val offset: Long, val length: Long) {
    val endExclusive get() = offset + length
}

internal data class HlsArtworkVariant(
    val url: String,
    val bandwidth: Long,
    val pixels: Long?,
    val codecs: List<String>,
    val hasAudioGroup: Boolean,
) {
    override fun toString() = "HlsArtworkVariant(url=<redacted>, bandwidth=$bandwidth, pixels=$pixels)"
}

/** RFC 8216 subset: finite, unencrypted, one video variant, one immutable fMP4 initialization map. */
internal object HlsArtworkParser {
    private val videoCodecs = setOf("avc1", "avc3", "hev1", "hvc1", "dvh1", "dvhe", "av01", "vp09", "vp9", "mp4v")
    private val audioCodecs = setOf("mp4a", "ac-3", "ec-3", "opus", "flac", "alac", "aac", "mp3")

    fun parse(baseUrl: String, text: String, options: ContentExportOptions): HlsArtworkPlaylist {
        val base = checkedContentExportUri(baseUrl, options)
        val lines = text.removePrefix("\uFEFF").lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        if (lines.firstOrNull() != "#EXTM3U") exportFailure(ContentExportReason.UNSUPPORTED_HLS)
        val variants = ArrayList<HlsArtworkVariant>()
        val segments = ArrayList<String>()
        val ranges = ArrayList<HlsArtworkByteRange?>()
        var pendingVariant: Map<String, String>? = null
        var pendingDuration: Double? = null
        var initialization: String? = null
        var initializationRange: HlsArtworkByteRange? = null
        var pendingRange: String? = null
        var previousAddress: String? = null
        var previousEnd: Long? = null
        var ended = false
        var duration = 0.0
        fun resolve(relative: String): String {
            if (relative.contains("{$") || relative.isBlank()) exportFailure(ContentExportReason.UNSUPPORTED_HLS)
            val resolved = try { base.resolve(relative).toString() } catch (_: Exception) { exportFailure(ContentExportReason.UNSUPPORTED_URL) }
            return checkedContentExportUri(resolved, options).toString()
        }
        fun byteRange(raw: String, implicitOffset: Long?): HlsArtworkByteRange {
            val parts = raw.split('@')
            if (parts.size !in 1..2 || parts.any { it.isEmpty() || it.any { character -> character !in '0'..'9' } }) exportFailure(ContentExportReason.UNSUPPORTED_HLS)
            val length = parts[0].toLongOrNull()?.takeIf { it > 0 } ?: exportFailure(ContentExportReason.UNSUPPORTED_HLS)
            val offset = if (parts.size == 2) parts[1].toLongOrNull() else implicitOffset
            if (offset == null || offset < 0 || offset > Long.MAX_VALUE - length) exportFailure(ContentExportReason.UNSUPPORTED_HLS)
            return HlsArtworkByteRange(offset, length)
        }
        for (line in lines.drop(1)) {
            when {
                line.startsWith("#EXT-X-KEY:") || line.startsWith("#EXT-X-SESSION-KEY:") -> {
                    val attributes = attributes(line.substringAfter(':'))
                    if (attributes["METHOD"] != "NONE" || attributes.containsKey("KEYFORMAT") || attributes.containsKey("URI")) exportFailure(ContentExportReason.ENCRYPTED_MEDIA)
                }
                line.startsWith("#EXT-X-STREAM-INF:") -> {
                    if (pendingVariant != null || segments.isNotEmpty() || pendingDuration != null) exportFailure(ContentExportReason.UNSUPPORTED_HLS)
                    pendingVariant = attributes(line.substringAfter(':'))
                }
                line.startsWith("#EXT-X-I-FRAME-STREAM-INF:") -> {
                    // Apple's ordinary video masters also advertise seek-preview renditions.
                    // These URIs are attributes, not the following full-video variant's URI.
                    if (segments.isNotEmpty() || pendingDuration != null) exportFailure(ContentExportReason.UNSUPPORTED_HLS)
                    attributes(line.substringAfter(':'))
                }
                line.startsWith("#EXT-X-MAP:") -> {
                    if (segments.isNotEmpty() && initialization == null) exportFailure(ContentExportReason.UNSUPPORTED_HLS)
                    val attributes = attributes(line.substringAfter(':'))
                    val mapped = resolve(attributes["URI"] ?: exportFailure(ContentExportReason.UNSUPPORTED_HLS))
                    val range = attributes["BYTERANGE"]?.let { byteRange(it, null) }
                    if (initialization != null && (initialization != mapped || initializationRange != range)) exportFailure(ContentExportReason.UNSUPPORTED_HLS)
                    initialization = mapped
                    initializationRange = range
                }
                line.startsWith("#EXT-X-BYTERANGE:") -> {
                    if (pendingRange != null || ended || variants.isNotEmpty()) exportFailure(ContentExportReason.UNSUPPORTED_HLS)
                    pendingRange = line.substringAfter(':')
                }
                line.startsWith("#EXTINF:") -> {
                    if (pendingDuration != null || variants.isNotEmpty() || ended) exportFailure(ContentExportReason.UNSUPPORTED_HLS)
                    pendingDuration = line.substringAfter(':').substringBefore(',').toDoubleOrNull()
                    if (pendingDuration == null || !pendingDuration.isFinite() || pendingDuration <= 0) exportFailure(ContentExportReason.UNSUPPORTED_HLS)
                }
                line == "#EXT-X-ENDLIST" -> ended = true
                line == "#EXT-X-DISCONTINUITY" || line == "#EXT-X-GAP" || line == "#EXT-X-I-FRAMES-ONLY" ||
                    listOf("#EXT-X-PART:", "#EXT-X-PRELOAD-HINT:", "#EXT-X-SKIP:", "#EXT-X-DEFINE:")
                        .any { line.startsWith(it) } -> exportFailure(ContentExportReason.UNSUPPORTED_HLS)
                line.startsWith('#') -> Unit
                else -> {
                    val address = resolve(line)
                    val variant = pendingVariant
                    if (variant != null) {
                        val bandwidth = variant["BANDWIDTH"]?.toLongOrNull()?.takeIf { it > 0 } ?: exportFailure(ContentExportReason.UNSUPPORTED_HLS)
                        val pixels = variant["RESOLUTION"]?.let { value ->
                            val match = Regex("(\\d+)x(\\d+)").matchEntire(value) ?: exportFailure(ContentExportReason.UNSUPPORTED_HLS)
                            val width = match.groupValues[1].toIntOrNull()?.takeIf { it > 0 } ?: exportFailure(ContentExportReason.UNSUPPORTED_HLS)
                            val height = match.groupValues[2].toIntOrNull()?.takeIf { it > 0 } ?: exportFailure(ContentExportReason.UNSUPPORTED_HLS)
                            width.toLong() * height
                        }
                        val codecs = variant["CODECS"]?.split(',')?.map { it.trim().lowercase(Locale.ROOT) } ?: emptyList()
                        variants += HlsArtworkVariant(address, bandwidth, pixels, codecs, variant.containsKey("AUDIO"))
                        if (variants.size > options.maxVariants) exportFailure(ContentExportReason.PLAYLIST_LIMIT)
                        pendingVariant = null
                    } else {
                        if (pendingDuration == null || variants.isNotEmpty() || ended) exportFailure(ContentExportReason.UNSUPPORTED_HLS)
                        duration += pendingDuration
                        if (duration > options.maxHlsDurationSeconds) exportFailure(ContentExportReason.PLAYLIST_LIMIT)
                        segments += address
                        val range = pendingRange?.let { byteRange(it, previousEnd.takeIf { previousAddress == address }) }
                        ranges += range
                        previousAddress = address; previousEnd = range?.endExclusive; pendingRange = null
                        if (segments.size > options.maxSegments) exportFailure(ContentExportReason.PLAYLIST_LIMIT)
                        pendingDuration = null
                    }
                }
            }
        }
        if (pendingVariant != null || pendingDuration != null || pendingRange != null) exportFailure(ContentExportReason.UNSUPPORTED_HLS)
        if (variants.isNotEmpty()) {
            if (initialization != null || ended) exportFailure(ContentExportReason.UNSUPPORTED_HLS)
            return HlsArtworkPlaylist.Master(variants)
        }
        if (!ended) exportFailure(ContentExportReason.LIVE_PLAYLIST)
        if (segments.isEmpty()) exportFailure(ContentExportReason.UNSUPPORTED_HLS)
        if (segments.any { extension(it) in setOf("aac", "m4a", "mp3") }) exportFailure(ContentExportReason.AUDIO_NOT_ALLOWED)
        if (segments.any { extension(it) == "ts" } || initialization?.let { extension(it) == "ts" } == true) exportFailure(ContentExportReason.UNSUPPORTED_TS)
        return HlsArtworkPlaylist.Media(initialization ?: exportFailure(ContentExportReason.UNSUPPORTED_HLS), segments, initializationRange, ranges)
    }

    fun selectVideo(variants: List<HlsArtworkVariant>, options: ContentExportOptions): HlsArtworkVariant {
        val silent = variants.filter { variant ->
            !variant.hasAudioGroup && variant.codecs.none { it.substringBefore('.') in audioCodecs }
        }
        if (silent.isEmpty()) exportFailure(ContentExportReason.AUDIO_NOT_ALLOWED)
        val video = silent.filter { variant ->
            (variant.pixels != null || variant.codecs.any { it.substringBefore('.') in videoCodecs }) &&
                variant.codecs.all { it.substringBefore('.') in videoCodecs }
        }
        if (video.isEmpty()) exportFailure(ContentExportReason.UNSUPPORTED_HLS)
        return video.filter { it.bandwidth <= options.maxVariantBandwidth && (it.pixels == null || it.pixels <= options.maxVideoPixels) }
            .maxWithOrNull(compareBy<HlsArtworkVariant> { it.pixels ?: 0 }.thenBy { it.bandwidth })
            ?: exportFailure(ContentExportReason.PLAYLIST_LIMIT)
    }

    private fun extension(url: String) = url.substringBefore('?').substringBefore('#').substringAfterLast('.').lowercase(Locale.ROOT)

    internal fun attributes(raw: String): Map<String, String> {
        val parts = ArrayList<String>()
        var quoted = false
        var start = 0
        for (index in raw.indices) {
            if (raw[index] == '"') quoted = !quoted
            if (raw[index] == ',' && !quoted) { parts += raw.substring(start, index); start = index + 1 }
        }
        if (quoted) exportFailure(ContentExportReason.UNSUPPORTED_HLS)
        parts += raw.substring(start)
        val result = LinkedHashMap<String, String>()
        for (part in parts) {
            val key = part.substringBefore('=').trim()
            val value = part.substringAfter('=', "").trim()
            // Apple artwork masters include the private _AVG-BANDWIDTH hint alongside
            // AVERAGE-BANDWIDTH. Unknown attributes do not affect variant selection.
            if (!key.matches(Regex("[A-Z0-9_-]+")) || value.isEmpty() || result.containsKey(key)) exportFailure(ContentExportReason.UNSUPPORTED_HLS)
            result[key] = if (value.startsWith('"')) {
                if (value.length < 2 || !value.endsWith('"')) exportFailure(ContentExportReason.UNSUPPORTED_HLS)
                value.substring(1, value.length - 1)
            } else value
        }
        return result
    }
}

/** Inspect container structure, video handler, encryption and fragment-relative offsets without a decoder. */
internal object MotionArtworkMp4 {
    internal data class VideoTrack(val id: Long, val width: Int, val height: Int)
    private data class Box(val type: String, val start: Long, val payload: Long, val end: Long)
    private val containers = setOf("moov", "trak", "mdia", "minf", "stbl", "mvex", "moof", "traf")
    private val encrypted = setOf("pssh", "sinf", "tenc", "senc", "encv", "enca")
    private val videoEntries = setOf("avc1", "avc3", "hvc1", "hev1", "dvh1", "dvhe", "av01", "vp09", "mp4v")

    fun validate(file: File, control: ContentExportControl, requireMonotonicFragments: Boolean = false): VideoTrack = RandomAccessFile(file, "r").use { input ->
        rejectTransportStream(input, 0, input.length())
        val top = boxes(input, 0, input.length(), control)
        val track = videoTrack(input, top, control)
        if (top.none { it.type == "mdat" && it.end > it.payload }) exportFailure(ContentExportReason.INVALID_MEDIA)
        var previousTime = -1L
        for (fragment in top.filter { it.type == "moof" }) {
            val data = top.firstOrNull { it.start > fragment.start && it.type == "mdat" } ?: exportFailure(ContentExportReason.INVALID_MEDIA)
            val time = inspectFragment(input, fragment, data, track, control)
            if (requireMonotonicFragments && time < previousTime) exportFailure(ContentExportReason.UNSUPPORTED_HLS)
            previousTime = time
        }
        track
    }

    fun validateInitialization(file: File, control: ContentExportControl): VideoTrack = RandomAccessFile(file, "r").use { input ->
        rejectTransportStream(input, 0, input.length())
        val top = boxes(input, 0, input.length(), control, rejectZeroSize = true)
        val track = videoTrack(input, top, control)
        val movie = top.single { it.type == "moov" }
        if (children(input, movie, control).none { it.type == "mvex" } || top.any { it.type == "moof" || it.type == "mdat" && it.end > it.payload }) {
            exportFailure(ContentExportReason.INVALID_MEDIA)
        }
        track
    }

    fun validateFragment(file: File, start: Long, end: Long, track: VideoTrack, control: ContentExportControl): List<Long> =
        RandomAccessFile(file, "r").use { input ->
            rejectTransportStream(input, start, end)
            val top = boxes(input, start, end, control, rejectZeroSize = true)
            if (top.any { it.type in setOf("ftyp", "moov") }) exportFailure(ContentExportReason.UNSUPPORTED_HLS)
            top.forEach { inspectEncryption(input, it, control, 0, intArrayOf(0)) }
            val fragments = top.filter { it.type == "moof" }
            if (fragments.isEmpty()) exportFailure(ContentExportReason.INVALID_MEDIA)
            fragments.map { fragment ->
                val data = top.firstOrNull { it.start > fragment.start && it.type == "mdat" } ?: exportFailure(ContentExportReason.INVALID_MEDIA)
                inspectFragment(input, fragment, data, track, control)
            }
        }

    private fun videoTrack(input: RandomAccessFile, top: List<Box>, control: ContentExportControl): VideoTrack {
        val type = top.singleOrNull { it.type == "ftyp" } ?: exportFailure(ContentExportReason.INVALID_MEDIA)
        if (type.end - type.payload < 8) exportFailure(ContentExportReason.INVALID_MEDIA)
        val movie = top.singleOrNull { it.type == "moov" } ?: exportFailure(ContentExportReason.INVALID_MEDIA)
        val count = intArrayOf(0)
        top.forEach { inspectEncryption(input, it, control, 0, count) }
        val tracks = children(input, movie, control).filter { it.type == "trak" }
        val videoTracks = tracks.map { track ->
            val inner = children(input, track, control)
            val media = inner.singleOrNull { it.type == "mdia" } ?: exportFailure(ContentExportReason.INVALID_MEDIA)
            val handler = children(input, media, control).singleOrNull { it.type == "hdlr" } ?: exportFailure(ContentExportReason.INVALID_MEDIA)
            if (handler.end - handler.payload < 12) exportFailure(ContentExportReason.INVALID_MEDIA)
            input.seek(handler.payload + 8)
            val name = fourcc(input)
            if (name == "soun") exportFailure(ContentExportReason.AUDIO_NOT_ALLOWED)
            if (name != "vide") exportFailure(ContentExportReason.INVALID_MEDIA)
            val header = inner.singleOrNull { it.type == "tkhd" } ?: exportFailure(ContentExportReason.INVALID_MEDIA)
            input.seek(header.payload)
            val version = input.readUnsignedByte()
            val offset = when (version) { 0 -> 12L; 1 -> 20L; else -> exportFailure(ContentExportReason.INVALID_MEDIA) }
            if (header.end - header.payload < if (version == 0) 84 else 96) exportFailure(ContentExportReason.INVALID_MEDIA)
            input.seek(header.payload + offset)
            val id = input.readInt().toLong().and(0xffffffffL).takeIf { it > 0 } ?: exportFailure(ContentExportReason.INVALID_MEDIA)
            input.seek(header.end - 8)
            VideoTrack(id, input.readInt() ushr 16, input.readInt() ushr 16)
        }
        if (videoTracks.size != 1) exportFailure(ContentExportReason.UNSUPPORTED_HLS)
        return videoTracks.single()
    }

    private fun inspectFragment(input: RandomAccessFile, movie: Box, data: Box, track: VideoTrack, control: ContentExportControl): Long {
        if (data.end <= data.payload) exportFailure(ContentExportReason.INVALID_MEDIA)
        val fragments = children(input, movie, control).filter { it.type == "traf" }
        if (fragments.size != 1) exportFailure(ContentExportReason.UNSUPPORTED_HLS)
        val inner = children(input, fragments.single(), control)
        val header = inner.singleOrNull { it.type == "tfhd" } ?: exportFailure(ContentExportReason.INVALID_MEDIA)
        if (header.end - header.payload < 8) exportFailure(ContentExportReason.INVALID_MEDIA)
        input.seek(header.payload)
        val flags = input.readInt()
        if (flags ushr 24 != 0 || flags and 1 != 0) exportFailure(ContentExportReason.UNSUPPORTED_HLS)
        if (input.readInt().toLong().and(0xffffffffL) != track.id) exportFailure(ContentExportReason.INVALID_MEDIA)
        val runs = inner.filter { it.type == "trun" }
        if (runs.isEmpty()) exportFailure(ContentExportReason.INVALID_MEDIA)
        for (run in runs) {
            if (run.end - run.payload < 8) exportFailure(ContentExportReason.INVALID_MEDIA)
            input.seek(run.payload)
            val runFlags = input.readInt()
            val samples = input.readInt().toLong().and(0xffffffffL)
            if (samples == 0L) exportFailure(ContentExportReason.INVALID_MEDIA)
            var prefix = 8L
            if (runFlags and 1 != 0) {
                if (run.end - run.payload < 12) exportFailure(ContentExportReason.INVALID_MEDIA)
                val offset = input.readInt().toLong()
                val target = movie.start + offset
                if (target < data.payload || target >= data.end) exportFailure(ContentExportReason.UNSUPPORTED_HLS)
                prefix += 4
            }
            if (runFlags and 4 != 0) prefix += 4
            val fields = listOf(0x100, 0x200, 0x400, 0x800).count { runFlags and it != 0 }
            if (run.end - run.payload != prefix + samples * fields * 4L) exportFailure(ContentExportReason.INVALID_MEDIA)
        }
        val timing = inner.singleOrNull { it.type == "tfdt" } ?: exportFailure(ContentExportReason.INVALID_MEDIA)
        if (timing.end - timing.payload < 8) exportFailure(ContentExportReason.INVALID_MEDIA)
        input.seek(timing.payload)
        return when (input.readInt() ushr 24) {
            0 -> input.readInt().toLong().and(0xffffffffL)
            1 -> {
                if (timing.end - timing.payload < 12) exportFailure(ContentExportReason.INVALID_MEDIA)
                input.readLong().takeIf { it >= 0 } ?: exportFailure(ContentExportReason.INVALID_MEDIA)
            }
            else -> exportFailure(ContentExportReason.INVALID_MEDIA)
        }
    }

    private fun inspectEncryption(input: RandomAccessFile, box: Box, control: ContentExportControl, depth: Int, count: IntArray) {
        control.check()
        if (++count[0] > 50_000 || depth > 12) exportFailure(ContentExportReason.INVALID_MEDIA)
        if (box.type in encrypted) exportFailure(ContentExportReason.ENCRYPTED_MEDIA)
        val start = when {
            box.type in containers -> box.payload
            box.type == "stsd" -> {
                if (box.end - box.payload < 8) exportFailure(ContentExportReason.INVALID_MEDIA)
                box.payload + 8
            }
            box.type in videoEntries -> {
                if (box.end - box.payload < 78) exportFailure(ContentExportReason.INVALID_MEDIA)
                box.payload + 78
            }
            else -> return
        }
        boxes(input, start, box.end, control).forEach { inspectEncryption(input, it, control, depth + 1, count) }
    }

    private fun children(input: RandomAccessFile, box: Box, control: ContentExportControl) = boxes(input, box.payload, box.end, control)

    private fun boxes(input: RandomAccessFile, start: Long, end: Long, control: ContentExportControl, rejectZeroSize: Boolean = false): List<Box> {
        if (start < 0 || end < start || end > input.length()) exportFailure(ContentExportReason.INVALID_MEDIA)
        val result = ArrayList<Box>()
        var cursor = start
        while (cursor < end) {
            control.check()
            if (end - cursor < 8 || result.size >= 50_000) exportFailure(ContentExportReason.INVALID_MEDIA)
            input.seek(cursor)
            val shortSize = input.readInt().toLong().and(0xffffffffL)
            val type = fourcc(input)
            val header: Long
            val size: Long
            when (shortSize) {
                0L -> {
                    if (rejectZeroSize) exportFailure(ContentExportReason.UNSUPPORTED_HLS)
                    header = 8; size = end - cursor
                }
                1L -> {
                    if (end - cursor < 16) exportFailure(ContentExportReason.INVALID_MEDIA)
                    header = 16; size = input.readLong()
                }
                else -> { header = 8; size = shortSize }
            }
            if (size < header || size > end - cursor) exportFailure(ContentExportReason.INVALID_MEDIA)
            result += Box(type, cursor, cursor + header, cursor + size)
            cursor += size
        }
        return result
    }

    private fun fourcc(input: RandomAccessFile): String {
        val name = ByteArray(4)
        input.readFully(name)
        return String(name, StandardCharsets.US_ASCII)
    }

    private fun rejectTransportStream(input: RandomAccessFile, start: Long, end: Long) {
        if (end - start < 376) return
        input.seek(start)
        if (input.readUnsignedByte() != 0x47) return
        input.seek(start + 188)
        if (input.readUnsignedByte() == 0x47) exportFailure(ContentExportReason.UNSUPPORTED_TS)
    }
}
