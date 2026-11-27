package dev.kifranei.ampp.media

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Container-only, blocking remux. Call on the export worker, before publishing the staging file. */
internal object MotionArtworkRemux {
    // Independent of attacker-controlled sample_count and export duration/timescale.
    private const val MAX_SAMPLES = 200_000
    private const val MAX_METADATA = 16 * 1024 * 1024
    private const val UINT = 0xffffffffL
    private val videoEntries = setOf("avc1", "avc3", "hvc1", "hev1", "dvh1", "dvhe", "av01", "vp09", "mp4v")
    private val containers = setOf("moov", "trak", "mdia", "minf", "stbl", "mvex", "moof", "traf")
    private val encrypted = setOf("pssh", "sinf", "tenc", "senc", "encv", "enca")

    /**
     * Preserves compressed samples and stsd verbatim. Fragment DTS must be contiguous; gaps,
     * overlaps, encryption, additional tracks and unbounded tables are rejected, never guessed.
     * Absolute tfdt origins and obsolete edits are removed. A constant CTS translation makes
     * the earliest presentation zero, retaining every relative PTS and B-frame offset.
     * On failure/cancellation the original is untouched and the sibling temporary file is deleted.
     */
    fun normalize(file: File, options: ContentExportOptions, control: ContentExportControl) {
        control.check()
        if (file.length() > options.maxBytes) exportFailure(ContentExportReason.SIZE_LIMIT)
        var temporary: File? = null
        try {
            RandomAccessFile(file, "r").use { input ->
                val reader = Reader(input, options, control)
                val top = reader.boxes(0, input.length())
                val movie = top.one("moov")
                val ftyp = top.one("ftyp")
                if (movie.size > MAX_METADATA || ftyp.size > MAX_METADATA) invalid()
                val movieChildren = reader.children(movie)
                val tracks = movieChildren.filter { it.type == "trak" }
                if (tracks.size != 1) exportFailure(ContentExportReason.UNSUPPORTED_HLS)
                val track = tracks.single()
                val trackChildren = reader.children(track)
                val media = trackChildren.one("mdia")
                val mediaChildren = reader.children(media)
                val handler = reader.bytes(mediaChildren.one("hdlr"))
                if (handler.size < 12) invalid()
                when (String(handler, 8, 4, StandardCharsets.US_ASCII)) {
                    "soun" -> exportFailure(ContentExportReason.AUDIO_NOT_ALLOWED)
                    "vide" -> Unit
                    else -> invalid()
                }
                val trackHeader = reader.bytes(trackChildren.one("tkhd"))
                val trackVersion = headerVersion(trackHeader, 84, 96)
                val trackId = uint(trackHeader, if (trackVersion == 0) 12 else 20)
                if (trackId == 0L) invalid()
                val mdhd = reader.bytes(mediaChildren.one("mdhd"))
                val mediaScale = timescale(mdhd, 24, 36)
                val mvhd = reader.bytes(movieChildren.one("mvhd"))
                val movieScale = timescale(mvhd, 100, 112)
                val minf = mediaChildren.one("minf")
                val stbl = reader.children(minf).one("stbl")
                val stsd = reader.children(stbl).one("stsd")
                val descriptions = reader.descriptions(stsd)
                val fragments = top.filter { it.type == "moof" }
                if (fragments.isEmpty()) {
                    if (movieChildren.any { it.type == "mvex" }) invalid()
                    MotionArtworkMp4.validate(file, control)
                    reader.checkOrdinary(stbl, mediaScale)
                    control.check()
                    return
                }
                // Existing validate() rejects absolute tfhd offsets. Inspect input encryption here,
                // and use that validator on the completed ordinary MP4 before the atomic replace.
                top.forEach { reader.inspectEncryption(it) }
                val defaults = reader.defaults(movieChildren.one("mvex"), trackId, descriptions)
                val mdats = top.filter { it.type == "mdat" && it.end > it.payload }
                val samples = reader.samples(fragments, mdats, trackId, defaults, descriptions, mediaScale)
                val timeline = timeline(samples, mediaScale, options, control)
                if (!samples.first().sync) invalid()
                val fileType = reader.raw(ftyp)
                fun moov(dataStart: Long): ByteArray {
                    val tables = sampleTables(reader.raw(stsd), samples, timeline.shift, dataStart, control)
                    val newMinf = reader.rebuild(minf) { child -> if (child == stbl) tables else reader.raw(child) }
                    val newMedia = reader.rebuild(media) { child -> when (child.type) {
                        "mdhd" -> box("mdhd", durationHeader(mdhd, "mdhd", timeline.mediaDuration))
                        "minf" -> newMinf
                        else -> reader.raw(child)
                    } }
                    val movieDuration = scaleCeil(timeline.presentationDuration, mediaScale, movieScale)
                    val newTrack = reader.rebuild(track) { child -> when (child.type) {
                        "edts" -> null
                        "tkhd" -> box("tkhd", durationHeader(trackHeader, "tkhd", movieDuration))
                        "mdia" -> newMedia
                        else -> reader.raw(child)
                    } }
                    return reader.rebuild(movie) { child -> when (child.type) {
                        "mvex" -> null
                        "mvhd" -> box("mvhd", durationHeader(mvhd, "mvhd", movieDuration))
                        "trak" -> newTrack
                        else -> reader.raw(child)
                    } }
                }
                val provisional = moov(0)
                val dataStart = fileType.size.toLong() + provisional.size + 8
                val movieBytes = moov(dataStart)
                if (movieBytes.size != provisional.size) invalid()
                val dataSize = samples.fold(0L) { size, sample -> add(size, sample.size) }
                val outputSize = add(dataStart, dataSize)
                if (outputSize > options.maxBytes || outputSize > UINT || dataSize + 8 > UINT) {
                    exportFailure(ContentExportReason.SIZE_LIMIT)
                }
                control.check()
                temporary = File.createTempFile(".motion-remux-", ".mp4", file.absoluteFile.parentFile)
                RandomAccessFile(temporary, "rw").use { output ->
                    output.write(fileType)
                    output.write(movieBytes)
                    output.writeInt((dataSize + 8).toInt())
                    output.write("mdat".toByteArray(StandardCharsets.US_ASCII))
                    val buffer = ByteArray(64 * 1024)
                    for (sample in samples) {
                        control.check()
                        input.seek(sample.offset)
                        var remaining = sample.size
                        while (remaining > 0) {
                            control.check()
                            val count = minOf(remaining, buffer.size.toLong()).toInt()
                            input.readFully(buffer, 0, count)
                            output.write(buffer, 0, count)
                            remaining -= count
                        }
                    }
                    if (output.length() != outputSize) invalid()
                    control.check()
                    output.fd.sync()
                }
            }
            val completed = temporary ?: invalid()
            MotionArtworkMp4.validate(completed, control)
            control.check()
            // Same-directory atomic rename: never truncate the source or remove it first.
            Files.move(completed.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            temporary = null
        } catch (error: ContentExportException) {
            throw error
        } catch (_: EOFException) {
            invalid()
        } catch (_: IOException) {
            exportFailure(ContentExportReason.STORAGE_ERROR)
        } finally {
            temporary?.let { runCatching { Files.deleteIfExists(it.toPath()) } }
        }
    }

    private data class Box(val type: String, val start: Long, val payload: Long, val end: Long) {
        val size: Long get() = end - start
    }
    private data class Defaults(val description: Long, val duration: Long, val size: Long, val flags: Long)
    private data class Sample(val offset: Long, val size: Long, val duration: Long, val flags: Long,
                              val cts: Long, val dts: Long, val description: Long) {
        val sync: Boolean get() = flags and 0x10000L == 0L && (flags ushr 24) and 3L != 1L
    }
    private data class Timeline(val shift: Long, val mediaDuration: Long, val presentationDuration: Long)

    private class Reader(val input: RandomAccessFile, val options: ContentExportOptions, val control: ContentExportControl) {
        private var boxCount = 0
        fun boxes(start: Long, end: Long): List<Box> {
            if (start < 0 || end < start || end > input.length()) invalid()
            val result = ArrayList<Box>()
            var cursor = start
            while (cursor < end) {
                control.check()
                if (end - cursor < 8 || ++boxCount > 50_000) invalid()
                input.seek(cursor)
                val shortSize = u32()
                val type = ByteArray(4).also(input::readFully).toString(StandardCharsets.US_ASCII)
                val header = if (shortSize == 1L) 16L else 8L
                if (end - cursor < header) invalid()
                val size = when (shortSize) { 0L -> end - cursor; 1L -> input.readLong(); else -> shortSize }
                if (size < header || size > end - cursor) invalid()
                result += Box(type, cursor, cursor + header, cursor + size)
                cursor += size
            }
            return result
        }
        fun children(box: Box) = boxes(box.payload, box.end)
        fun bytes(box: Box) = read(box.payload, box.end)
        fun raw(box: Box) = read(box.start, box.end)
        private fun read(start: Long, end: Long): ByteArray {
            control.check()
            if (end - start > MAX_METADATA) invalid()
            input.seek(start)
            return ByteArray((end - start).toInt()).also(input::readFully)
        }
        fun rebuild(parent: Box, transform: (Box) -> ByteArray?): ByteArray = box(parent.type, payload {
            children(parent).forEach { child -> control.check(); transform(child)?.let { write(it) } }
        })
        fun descriptions(stsd: Box): Long {
            val cursor = Cursor(input, stsd)
            if (cursor.full(0).second != 0) invalid()
            val count = cursor.u32()
            val entries = boxes(input.filePointer, stsd.end)
            if (count == 0L || count != entries.size.toLong()) invalid()
            for (entry in entries) {
                if (entry.type in encrypted) exportFailure(ContentExportReason.ENCRYPTED_MEDIA)
                if (entry.type !in videoEntries || entry.end - entry.payload < 78) invalid()
                input.seek(entry.payload + 6)
                if (input.readUnsignedShort() != 1) invalid()
            }
            return count
        }
        fun inspectEncryption(box: Box, depth: Int = 0) {
            control.check()
            if (depth > 12) invalid()
            if (box.type in encrypted) exportFailure(ContentExportReason.ENCRYPTED_MEDIA)
            val start = when {
                box.type in containers -> box.payload
                box.type == "stsd" -> box.payload + 8
                box.type in videoEntries -> box.payload + 78
                else -> return
            }
            boxes(start, box.end).forEach { inspectEncryption(it, depth + 1) }
        }
        fun defaults(mvex: Box, trackId: Long, descriptions: Long): Defaults {
            val trex = children(mvex).filter { it.type == "trex" }.singleOrNull() ?: invalid()
            val cursor = Cursor(input, trex)
            if (cursor.full(0).second != 0 || cursor.u32() != trackId) invalid()
            val result = Defaults(cursor.u32(), cursor.u32(), cursor.u32(), cursor.u32())
            cursor.end()
            if (result.description !in 1..descriptions) invalid()
            return result
        }
        fun samples(fragments: List<Box>, mdats: List<Box>, trackId: Long, defaults: Defaults,
                    descriptions: Long, timescale: Long): List<Sample> {
            val samples = ArrayList<Sample>()
            var origin: Long? = null
            var decodeEnd = 0L
            for (fragment in fragments) {
                control.check()
                val trafs = children(fragment).filter { it.type == "traf" }
                if (trafs.size != 1) exportFailure(ContentExportReason.UNSUPPORTED_HLS)
                val inner = children(trafs.single())
                val header = Cursor(input, inner.one("tfhd"))
                val flags = header.full(0).second
                if (flags and 0x03003b.inv() != 0 || flags and 0x010000 != 0 || header.u32() != trackId) invalid()
                val base = if (flags and 1 != 0) header.u64() else fragment.start
                if (flags and 1 != 0 && flags and 0x020000 != 0) invalid()
                val description = if (flags and 2 != 0) header.u32() else defaults.description
                val duration = if (flags and 8 != 0) header.u32() else defaults.duration
                val size = if (flags and 0x10 != 0) header.u32() else defaults.size
                val sampleFlags = if (flags and 0x20 != 0) header.u32() else defaults.flags
                header.end()
                if (description !in 1..descriptions) invalid()
                val times = inner.filter { it.type == "tfdt" }
                if (times.size > 1) invalid()
                var dts = times.singleOrNull()?.let { timing ->
                    val cursor = Cursor(input, timing)
                    val (version, timeFlags) = cursor.full(0, 1)
                    if (timeFlags != 0) invalid()
                    (if (version == 1) cursor.u64() else cursor.u32()).also { cursor.end() }
                } ?: decodeEnd
                if (origin == null) origin = dts else if (dts != decodeEnd) invalid()
                var dataEnd: Long? = null
                val runs = inner.filter { it.type == "trun" }
                if (runs.isEmpty()) invalid()
                for (run in runs) {
                    val cursor = Cursor(input, run)
                    val (version, runFlags) = cursor.full(0, 1)
                    if (runFlags and 0xf05.inv() != 0 || runFlags and 4 != 0 && runFlags and 0x400 != 0) invalid()
                    val count = cursor.u32()
                    if (count == 0L) invalid()
                    if (count > MAX_SAMPLES - samples.size) exportFailure(ContentExportReason.PLAYLIST_LIMIT)
                    var offset = if (runFlags and 1 != 0) add(base, cursor.i32()) else dataEnd ?: base
                    val firstFlags = if (runFlags and 4 != 0) cursor.u32() else sampleFlags
                    val fields = Integer.bitCount(runFlags and 0xf00)
                    if (run.end - input.filePointer != count * fields * 4L) invalid()
                    repeat(count.toInt()) { index ->
                        control.check()
                        val delta = if (runFlags and 0x100 != 0) cursor.u32() else duration
                        val length = if (runFlags and 0x200 != 0) cursor.u32() else size
                        val actualFlags = if (runFlags and 0x400 != 0) cursor.u32() else if (index == 0) firstFlags else sampleFlags
                        val cts = if (runFlags and 0x800 != 0) { if (version == 1) cursor.i32() else cursor.u32() } else 0L
                        if (delta == 0L || length == 0L) invalid()
                        val end = add(offset, length)
                        if (!inMedia(mdats, offset, end)) invalid()
                        val relativeDts = dts - origin
                        samples += Sample(offset, length, delta, actualFlags, cts, relativeDts, description)
                        dts = add(dts, delta)
                        checkDuration(dts - origin, timescale, options)
                        offset = end
                    }
                    cursor.end()
                    dataEnd = offset
                }
                decodeEnd = dts
            }
            // An explicit offset may target any mdat, but never reuse/overlap compressed bytes.
            var previousEnd = -1L
            for (sample in samples.sortedBy { it.offset }) {
                control.check()
                if (sample.offset < previousEnd) invalid()
                previousEnd = add(sample.offset, sample.size)
            }
            return samples
        }
        private fun inMedia(mdats: List<Box>, start: Long, end: Long): Boolean {
            var low = 0
            var high = mdats.lastIndex
            while (low <= high) {
                val middle = (low + high) ushr 1
                val box = mdats[middle]
                when { start < box.payload -> high = middle - 1; start >= box.end -> low = middle + 1
                    else -> return end <= box.end }
            }
            return false
        }
        fun checkOrdinary(stbl: Box, timescale: Long) {
            val tables = children(stbl)
            val size = Cursor(input, tables.one("stsz"))
            if (size.full(0).second != 0) invalid()
            val constantSize = size.u32()
            val count = size.u32()
            if (count < 2) invalid()
            if (count > MAX_SAMPLES) exportFailure(ContentExportReason.PLAYLIST_LIMIT)
            if (constantSize == 0L) repeat(count.toInt()) { control.check(); if (size.u32() == 0L) invalid() }
            size.end()
            val durations = LongArray(count.toInt())
            val timing = Cursor(input, tables.one("stts"))
            if (timing.full(0).second != 0) invalid()
            val entries = timing.u32()
            if (entries > count) invalid()
            var index = 0
            repeat(entries.toInt()) {
                control.check()
                val run = timing.u32()
                val delta = timing.u32()
                if (run == 0L || run > count - index || delta == 0L) invalid()
                repeat(run.toInt()) { durations[index++] = delta }
            }
            timing.end()
            if (index.toLong() != count) invalid()
            val offsets = LongArray(count.toInt())
            val composition = tables.filter { it.type == "ctts" }
            if (composition.size > 1) invalid()
            composition.singleOrNull()?.let { box ->
                val cursor = Cursor(input, box)
                val (version, flags) = cursor.full(0, 1)
                if (flags != 0) invalid()
                val runs = cursor.u32()
                if (runs > count) invalid()
                index = 0
                repeat(runs.toInt()) {
                    control.check()
                    val run = cursor.u32()
                    val offset = if (version == 1) cursor.i32() else cursor.u32()
                    if (run == 0L || run > count - index) invalid()
                    repeat(run.toInt()) { offsets[index++] = offset }
                }
                cursor.end()
                if (index.toLong() != count) invalid()
            }
            var dts = 0L
            val samples = durations.indices.map { i ->
                Sample(0, 1, durations[i], 0, offsets[i], dts, 1).also { dts = add(dts, durations[i]) }
            }
            timeline(samples, timescale, options, control)
        }
        private fun u32() = input.readInt().toLong() and UINT
    }

    private class Cursor(val input: RandomAccessFile, val box: Box) {
        init { input.seek(box.payload) }
        private fun requireBytes(count: Long) { if (box.end - input.filePointer < count) invalid() }
        fun u32(): Long { requireBytes(4); return input.readInt().toLong() and UINT }
        fun i32(): Long { requireBytes(4); return input.readInt().toLong() }
        fun u64(): Long { requireBytes(8); return input.readLong().also { if (it < 0) invalid() } }
        fun full(vararg allowedVersions: Int): Pair<Int, Int> {
            val value = u32().toInt()
            val version = value ushr 24
            if (version !in allowedVersions) invalid()
            return version to (value and 0xffffff)
        }
        fun end() { if (input.filePointer != box.end) invalid() }
    }

    private fun timeline(samples: List<Sample>, timescale: Long, options: ContentExportOptions,
                         control: ContentExportControl): Timeline {
        if (samples.size < 2) invalid()
        var minimum = Long.MAX_VALUE
        var maximum = Long.MIN_VALUE
        var distinct = false
        val firstPts = add(samples.first().dts, samples.first().cts)
        for (sample in samples) {
            control.check()
            val pts = add(sample.dts, sample.cts)
            if (pts != firstPts) distinct = true
            minimum = minOf(minimum, pts)
            maximum = maxOf(maximum, add(pts, sample.duration))
        }
        if (!distinct) invalid()
        val decodeDuration = add(samples.last().dts, samples.last().duration)
        val presentationDuration = add(maximum, -minimum)
        if (presentationDuration <= 0 || decodeDuration <= 0) invalid()
        val mediaDuration = maxOf(decodeDuration, presentationDuration)
        checkDuration(mediaDuration, timescale, options)
        return Timeline(-minimum, mediaDuration, presentationDuration)
    }

    private fun sampleTables(stsd: ByteArray, samples: List<Sample>, shift: Long, dataStart: Long,
                             control: ContentExportControl): ByteArray = box("stbl", payload {
        write(stsd)
        fun runs(type: String, version: Int = 0, value: (Sample) -> Long) {
            val entries = ArrayList<Pair<Int, Long>>()
            for (sample in samples) {
                control.check()
                val current = value(sample)
                val last = entries.lastOrNull()
                if (last?.second == current) entries[entries.lastIndex] = last.first + 1 to current
                else entries += 1 to current
            }
            write(box(type, payload {
                writeInt(version shl 24); writeInt(entries.size)
                entries.forEach { (count, current) -> writeInt(count); writeInt(current.toInt()) }
            }))
        }
        runs("stts") { it.duration }
        val offsets = samples.map { add(it.cts, shift) }
        val signed = offsets.any { it < 0 }
        if (offsets.any { if (signed) it !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() else it !in 0..UINT }) invalid()
        if (offsets.any { it != 0L }) runs("ctts", if (signed) 1 else 0) { add(it.cts, shift) }
        // Write stss even for all-sync streams, so every sync decision is explicit.
        write(box("stss", payload {
            writeInt(0); writeInt(samples.count { it.sync })
            samples.forEachIndexed { i, sample -> if (sample.sync) writeInt(i + 1) }
        }))
        val chunks = samples.indices.filter { it == 0 || samples[it].description != samples[it - 1].description }
        write(box("stsc", payload {
            writeInt(0); writeInt(chunks.size)
            chunks.forEach { i -> writeInt(i + 1); writeInt(1); writeInt(samples[i].description.toInt()) }
        }))
        write(box("stsz", payload {
            writeInt(0); writeInt(0); writeInt(samples.size)
            samples.forEach { control.check(); writeInt(it.size.toInt()) }
        }))
        write(box("stco", payload {
            writeInt(0); writeInt(samples.size)
            var offset = dataStart
            samples.forEach { control.check(); if (offset > UINT) exportFailure(ContentExportReason.SIZE_LIMIT)
                writeInt(offset.toInt()); offset = add(offset, it.size) }
        }))
    })

    private fun durationHeader(source: ByteArray, type: String, duration: Long): ByteArray {
        val track = type == "tkhd"
        val version = headerVersion(source, if (track) 84 else if (type == "mdhd") 24 else 100,
            if (track) 96 else if (type == "mdhd") 36 else 112)
        if (duration < 0) invalid()
        val oldOffset = if (track) 20 else 16
        if (version == 0 && duration > UINT) return payload {
            writeInt((ByteBuffer.wrap(source).getInt(0) and 0xffffff) or 0x01000000)
            writeLong(uint(source, 4)); writeLong(uint(source, 8))
            write(source, 12, oldOffset - 12)
            writeLong(duration)
            write(source, oldOffset + 4, source.size - oldOffset - 4)
        }
        return source.copyOf().also { bytes ->
            if (version == 0) ByteBuffer.wrap(bytes).putInt(oldOffset, duration.toInt())
            else ByteBuffer.wrap(bytes).putLong(oldOffset + 8, duration)
        }
    }
    private fun headerVersion(bytes: ByteArray, v0Size: Int, v1Size: Int): Int {
        if (bytes.isEmpty()) invalid()
        val version = bytes[0].toInt() and 255
        if (version !in 0..1 || bytes.size < if (version == 0) v0Size else v1Size) invalid()
        return version
    }
    private fun timescale(bytes: ByteArray, v0Size: Int, v1Size: Int): Long {
        val version = headerVersion(bytes, v0Size, v1Size)
        return uint(bytes, if (version == 0) 12 else 20).also { if (it == 0L) invalid() }
    }
    private fun uint(bytes: ByteArray, offset: Int) = ByteBuffer.wrap(bytes).getInt(offset).toLong() and UINT
    private fun checkDuration(duration: Long, timescale: Long, options: ContentExportOptions) {
        if (duration < 0) invalid()
        if (duration.toDouble() / timescale > options.maxHlsDurationSeconds) exportFailure(ContentExportReason.PLAYLIST_LIMIT)
    }
    private fun scaleCeil(value: Long, from: Long, to: Long): Long {
        val quotient = value / from
        if (quotient > Long.MAX_VALUE / to) invalid()
        val remainder = value % from
        // Remainder and timescales are u32, so use exact arithmetic without signed multiplication overflow.
        val fraction = java.math.BigInteger.valueOf(remainder).multiply(java.math.BigInteger.valueOf(to))
            // The result is at most to (u32), hence longValue is exact and works on API 28.
            .add(java.math.BigInteger.valueOf(from - 1)).divide(java.math.BigInteger.valueOf(from)).toLong()
        return add(quotient * to, fraction)
    }
    private fun add(a: Long, b: Long): Long {
        if (b > 0 && a > Long.MAX_VALUE - b || b < 0 && a < Long.MIN_VALUE - b) invalid()
        return a + b
    }
    private fun List<Box>.one(type: String) = singleOrNull { it.type == type } ?: invalid()
    private fun payload(write: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { it.write() }
        if (bytes.size() > MAX_METADATA) invalid()
    }.toByteArray()
    private fun box(type: String, payload: ByteArray): ByteArray {
        if (payload.size > MAX_METADATA - 8) invalid()
        return payload { writeInt(payload.size + 8); write(type.toByteArray(StandardCharsets.US_ASCII)); write(payload) }
    }
    private fun invalid(): Nothing = exportFailure(ContentExportReason.INVALID_MEDIA)
}
