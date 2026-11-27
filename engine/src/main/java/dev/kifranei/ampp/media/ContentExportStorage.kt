package dev.kifranei.ampp.media

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URI
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.nio.charset.Charset
import java.nio.charset.CharacterCodingException
import java.nio.CharBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Own this service with PluginContext.onClose. All network, conversion and file work runs on workers. */
internal class ContentExportStorage internal constructor(
    private val destination: ContentExportDestination,
    private val options: ContentExportOptions = ContentExportOptions(),
    private val network: ContentExportNetwork = UrlConnectionContentExportNetwork(options),
    private val dispatchCallback: (() -> Unit) -> Unit = { it() },
    private val onUnexpectedFailure: (Throwable) -> Unit = {},
) : AutoCloseable {
    constructor(context: Context, options: ContentExportOptions = ContentExportOptions(), onUnexpectedFailure: (Throwable) -> Unit = {}) : this(
        AndroidContentExportDestination(context.applicationContext), options,
        UrlConnectionContentExportNetwork(options), mainThreadContentExportDispatcher(), onUnexpectedFailure,
    )

    private val closed = AtomicBoolean()
    private val jobs = ConcurrentHashMap<String, ContentExportJob>()
    @Volatile private var motionStructureListener: (String) -> Unit = {}

    /** Receives only bounded tag names, counts and known file formats, never manifest URLs or values. */
    fun setMotionStructureListener(listener: (String) -> Unit) { motionStructureListener = listener }

    private fun reportMotionStructure(summary: String) { runCatching { motionStructureListener(summary) } }
    private val worker = ThreadPoolExecutor(
        options.maxConcurrentExports, options.maxConcurrentExports, 30, TimeUnit.SECONDS,
        ArrayBlockingQueue(options.maxQueuedExports),
        { runnable -> Thread(runnable, "AM++ content export").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    ).apply { allowCoreThreadTimeOut(true) }

    /** Android constructor callbacks always arrive on the main thread, including rejection/cancellation. */
    fun export(asset: ContentExportAsset, onState: (ContentExportStatus) -> Unit = {}): ContentExportJob {
        val snapshot = when (asset) {
            is ContentExportAsset.ArtistMotionArtwork -> asset.copy(sources = asset.sources.toList())
            is ContentExportAsset.AlbumMotionArtwork -> asset.copy(sources = asset.sources.toList())
            else -> asset
        }
        val job = ContentExportJob(UUID.randomUUID().toString(), snapshot.kind, options, dispatchCallback, onState)
        job.notifyInitial()
        if (closed.get()) {
            job.fail(ContentExportFailure(ContentExportReason.CLOSED))
            return job
        }
        jobs[job.id] = job
        val task = Runnable {
            try {
                if (closed.get()) job.cancel()
                if (job.start()) perform(snapshot, job)
            }
            finally { job.detachThread(); jobs.remove(job.id) }
        }
        job.onQueuedCancellation = { worker.remove(task); jobs.remove(job.id) }
        try {
            worker.execute(task)
            // Covers a close() racing between enrollment and executor submission/draining.
            if (closed.get()) job.cancel()
        }
        catch (_: RejectedExecutionException) {
            jobs.remove(job.id)
            job.fail(ContentExportFailure(if (closed.get()) ContentExportReason.CLOSED else ContentExportReason.QUEUE_FULL))
        }
        return job
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        motionStructureListener = {}
        jobs.values.forEach { it.cancel() }
        // cancel() interrupts active transfers and removes queued tasks; a publishing transaction may finish.
        worker.shutdown()
    }

    private fun perform(asset: ContentExportAsset, job: ContentExportJob) {
        var staged: File? = null
        var result: ContentExportFile? = null
        var failure: ContentExportFailure? = null
        try {
            asset.unavailableReason()?.let { exportFailure(it) }
            job.control.check()
            val directory = destination.stagingDirectory
            if (!directory.isDirectory && !directory.mkdirs()) exportFailure(ContentExportReason.STORAGE_ERROR)
            staged = File.createTempFile(".ampp-export-", ".part", directory)
            val format = when (asset) {
                is ContentExportAsset.ArtistArtwork, is ContentExportAsset.AlbumArtwork -> {
                    job.phase(ContentExportPhase.DOWNLOADING)
                    val art = (asset as? ContentExportAsset.ArtistArtwork)?.artwork ?: (asset as ContentExportAsset.AlbumArtwork).artwork
                    val source = checkNotNull(art).jpegUrl(options.useOriginalArtworkSize)
                    val budget = ContentExportByteBudget(options.maxBytes, job.control)
                    withExportNetwork { network.open(source, job.control) }.use { response ->
                            FileOutputStream(staged).use { output ->
                                copyContentExportBytes(response.input, output, budget, response.contentLength, job::progress)
                                output.fd.sync()
                            }
                    }
                    validateExportJpeg(staged)
                    ContentExportFileFormat.JPEG
                }
                is ContentExportAsset.ArtistWordmark -> {
                    job.phase(ContentExportPhase.DOWNLOADING)
                    val source = checkNotNull(asset.artwork).pngUrl(options.useOriginalArtworkSize)
                    val budget = ContentExportByteBudget(options.maxBytes, job.control)
                    withExportNetwork { network.open(source, job.control) }.use { response ->
                        FileOutputStream(staged).use { output ->
                            copyContentExportBytes(response.input, output, budget, response.contentLength, job::progress)
                            output.fd.sync()
                        }
                    }
                    detectExportWordmarkFormat(staged)
                }
                is ContentExportAsset.ArtistMotionArtwork -> {
                    job.phase(ContentExportPhase.DOWNLOADING)
                    MotionArtworkDownload(network, options, ContentExportReason.NO_ARTIST_MOTION_ARTWORK, ::reportMotionStructure)
                        .download(asset.sources, staged, job.control, job::progress)
                }
                is ContentExportAsset.AlbumMotionArtwork -> {
                    job.phase(ContentExportPhase.DOWNLOADING)
                    MotionArtworkDownload(network, options, onStructure = ::reportMotionStructure).download(asset.sources, staged, job.control, job::progress)
                }
                is ContentExportAsset.TtmlLyrics -> {
                    val raw = checkNotNull(asset.rawTtml)
                    checkExportTextSize(raw, options)
                    if (!isCompleteExportTtml(raw)) exportFailure(ContentExportReason.INVALID_TTML)
                    writeExportText(staged, raw, job, encodeExportTtml(raw))
                    ContentExportFileFormat.TTML
                }
                is ContentExportAsset.ArtistBiography -> {
                    writeEditorial(staged, checkNotNull(asset.text), ContentExportReason.NO_BIOGRAPHY, job)
                    ContentExportFileFormat.TEXT
                }
                is ContentExportAsset.AlbumEditorial -> {
                    writeEditorial(staged, checkNotNull(asset.text), ContentExportReason.NO_EDITORIAL, job)
                    ContentExportFileFormat.TEXT
                }
            }
            job.control.check()
            job.phase(ContentExportPhase.SAVING)
            if (format == ContentExportFileFormat.MP4) {
                // A valid HLS fragment chain is not a broadly playable standalone file.
                // Build the full sample table and duration before publishing to Downloads.
                MotionArtworkRemux.normalize(staged, options, job.control)
            }
            job.control.check()
            if (staged.length() <= 0) exportFailure(ContentExportReason.STORAGE_ERROR)
            val spec = ContentExportFileSpec(contentExportFileName(asset.title, asset.kind, format), format)
            result = destination.publish(staged, spec, job.control, job::commit)
        } catch (error: ContentExportException) {
            failure = error.failure
        } catch (error: Exception) {
            // File/provider exceptions can contain private paths or signed URLs. Never log their text.
            runCatching { onUnexpectedFailure(error) }
            failure = ContentExportFailure(ContentExportReason.STORAGE_ERROR)
        } finally {
            staged?.let { runCatching { it.delete() } }
        }
        // Terminal callbacks see the cleaned transaction, rather than racing staging-file removal.
        result?.let(job::succeed) ?: job.fail(failure ?: ContentExportFailure(ContentExportReason.STORAGE_ERROR))
    }

    private fun writeEditorial(file: File, text: ContentExportText, missing: ContentExportReason, job: ContentExportJob) {
        checkExportTextSize(text.content, options)
        val plain = text.plainText()
        if (plain.isBlank()) exportFailure(missing)
        writeExportText(file, plain, job)
    }

    private fun writeExportText(file: File, text: String, job: ContentExportJob, encoded: ByteArray? = null) {
        checkExportTextSize(text, options)
        val bytes = encoded ?: text.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size.toLong() > minOf(options.maxBytes, options.maxTextBytes)) exportFailure(ContentExportReason.SIZE_LIMIT)
        FileOutputStream(file).use { output ->
            val budget = ContentExportByteBudget(minOf(options.maxBytes, options.maxTextBytes), job.control)
            copyContentExportBytes(bytes.inputStream(), output, budget, bytes.size.toLong(), job::progress)
            output.fd.sync()
        }
    }
}

internal class ContentExportJob internal constructor(
    val id: String,
    kind: ContentExportKind,
    options: ContentExportOptions,
    private val dispatch: (() -> Unit) -> Unit,
    private val callback: (ContentExportStatus) -> Unit,
) {
    internal val control = ContentExportControl(options)
    @Volatile var status = ContentExportStatus(id, kind, ContentExportPhase.QUEUED)
        private set
    @Volatile private var runner: Thread? = null
    internal var onQueuedCancellation: () -> Unit = {}
    private var lastProgressNanos = 0L

    internal fun notifyInitial() = synchronized(this) { deliver(status) }

    /** Returns false after publication starts or a terminal result exists. Cancellation never performs I/O. */
    fun cancel(): Boolean {
        val wasQueued: Boolean
        synchronized(this) {
            if (status.phase.isTerminal || status.phase == ContentExportPhase.PUBLISHING || control.isCancelled) return false
            wasQueued = status.phase == ContentExportPhase.QUEUED
            control.cancel()
            update(status.copy(
                phase = if (wasQueued) ContentExportPhase.CANCELLED else ContentExportPhase.CANCELLING,
                failure = ContentExportFailure(ContentExportReason.CANCELLED),
            ))
            runner?.interrupt()
        }
        if (wasQueued) onQueuedCancellation()
        return true
    }

    internal fun start(): Boolean = synchronized(this) {
        if (status.phase.isTerminal) return false
        control.start()
        runner = Thread.currentThread()
        update(status.copy(phase = ContentExportPhase.PREPARING))
        true
    }

    internal fun detachThread() = synchronized(this) { runner = null }

    internal fun phase(value: ContentExportPhase) = synchronized(this) {
        control.check()
        if (!status.phase.isTerminal) update(status.copy(phase = value))
    }

    internal fun progress(bytes: Long, total: Long?) = synchronized(this) {
        control.check()
        val now = System.nanoTime()
        if (now - lastProgressNanos >= TimeUnit.MILLISECONDS.toNanos(100) || bytes == total) {
            lastProgressNanos = now
            update(status.copy(bytesCopied = maxOf(status.bytesCopied, bytes), totalBytes = total))
        }
    }

    /** The only uncancellable interval is the final MediaStore update or same-filesystem atomic move. */
    internal fun commit(action: () -> ContentExportFile): ContentExportFile {
        synchronized(this) {
            control.check()
            update(status.copy(phase = ContentExportPhase.PUBLISHING))
        }
        return action()
    }

    internal fun succeed(file: ContentExportFile) = synchronized(this) {
        if (!status.phase.isTerminal) update(status.copy(
            phase = ContentExportPhase.SUCCEEDED, file = file, bytesCopied = file.byteCount, totalBytes = file.byteCount, failure = null,
        ))
    }

    internal fun fail(failure: ContentExportFailure) = synchronized(this) {
        if (!status.phase.isTerminal) {
            val cancelled = control.isCancelled || failure.reason == ContentExportReason.CANCELLED
            update(status.copy(
                phase = if (cancelled) ContentExportPhase.CANCELLED else ContentExportPhase.FAILED,
                failure = if (cancelled) ContentExportFailure(ContentExportReason.CANCELLED) else failure, file = null,
            ))
        }
    }

    private fun update(value: ContentExportStatus) { status = value; deliver(value) }
    private fun deliver(value: ContentExportStatus) {
        runCatching { dispatch { runCatching { callback(value) } } }
    }
}

private fun mainThreadContentExportDispatcher(): (() -> Unit) -> Unit {
    val main = Handler(Looper.getMainLooper())
    return { action -> main.post { action() } }
}

internal data class ContentExportFileSpec(val displayName: String, val format: ContentExportFileFormat)

/** Testable transaction boundary. Implementations remove pending outputs on every failure/cancellation. */
internal interface ContentExportDestination {
    val stagingDirectory: File
    fun publish(
        staged: File,
        spec: ContentExportFileSpec,
        control: ContentExportControl,
        commit: (() -> ContentExportFile) -> ContentExportFile,
    ): ContentExportFile
}

/** API 28 fallback and JVM implementation: the hidden staging file and output share a filesystem. */
internal class AtomicFileContentExportDestination(private val directory: File) : ContentExportDestination {
    override val stagingDirectory: File get() = directory

    override fun publish(
        staged: File,
        spec: ContentExportFileSpec,
        control: ContentExportControl,
        commit: (() -> ContentExportFile) -> ContentExportFile,
    ): ContentExportFile {
        control.check()
        require(staged.parentFile?.canonicalFile == directory.canonicalFile)
        val target = File(directory, spec.displayName)
        require(target.parentFile?.canonicalFile == directory.canonicalFile && target.name == spec.displayName)
        if (target.exists() || staged.length() == 0L) exportFailure(ContentExportReason.STORAGE_ERROR)
        val length = staged.length()
        return commit {
            // No copy fallback: lack of atomic move support is an honest storage failure.
            Files.move(staged.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            ContentExportFile(target.toURI().toString(), target.name, spec.format.mimeType, length, ContentExportLocation.APP_DOWNLOADS)
        }
    }
}

private class AndroidContentExportDestination(private val context: Context) : ContentExportDestination {
    private val legacy by lazy {
        val directory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.filesDir, "Downloads")
        AtomicFileContentExportDestination(File(directory, "AM++"))
    }
    override val stagingDirectory: File
        get() = if (Build.VERSION.SDK_INT >= 29) File(context.cacheDir, "ampp-content-exports") else legacy.stagingDirectory

    override fun publish(
        staged: File,
        spec: ContentExportFileSpec,
        control: ContentExportControl,
        commit: (() -> ContentExportFile) -> ContentExportFile,
    ): ContentExportFile {
        if (Build.VERSION.SDK_INT < 29) return legacy.publish(staged, spec, control, commit)
        val resolver = context.contentResolver
        control.check()
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, spec.displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, spec.format.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/AM++")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: exportFailure(ContentExportReason.STORAGE_ERROR)
        try {
            val length = staged.length()
            if (length == 0L) exportFailure(ContentExportReason.STORAGE_ERROR)
            val output = resolver.openOutputStream(uri, "w") ?: exportFailure(ContentExportReason.STORAGE_ERROR)
            output.use { sink -> staged.inputStream().use { input ->
                copyContentExportBytes(input, sink, ContentExportByteBudget(length, control), length)
            } }
            return commit {
                val published = resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                if (published != 1) exportFailure(ContentExportReason.STORAGE_ERROR)
                ContentExportFile(uri.toString(), spec.displayName, spec.format.mimeType, length, ContentExportLocation.SHARED_DOWNLOADS)
            }
        } catch (error: Throwable) {
            // Cleanup is attempted even for interruption or VM errors; do not report the original exception text.
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }
}

internal class ContentExportControl(private val options: ContentExportOptions) {
    private val cancelled = AtomicBoolean()
    @Volatile private var deadlineNanos: Long = Long.MAX_VALUE
    val isCancelled: Boolean get() = cancelled.get()
    fun cancel() { cancelled.set(true) }
    fun start() {
        val duration = TimeUnit.MILLISECONDS.toNanos(options.maxExportDurationMillis)
        val now = System.nanoTime()
        deadlineNanos = if (duration > 0 && now <= Long.MAX_VALUE - duration) now + duration else Long.MAX_VALUE
    }
    fun check() {
        if (isCancelled || Thread.currentThread().isInterrupted) exportFailure(ContentExportReason.CANCELLED)
        if (System.nanoTime() >= deadlineNanos) exportFailure(ContentExportReason.TIMEOUT)
    }
    fun timeout(configuredMillis: Int): Int {
        check()
        if (deadlineNanos == Long.MAX_VALUE) return configuredMillis
        return minOf(configuredMillis.toLong(), maxOf(1, TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()))).toInt()
    }
}

internal class ContentExportByteBudget(val limit: Long, private val control: ContentExportControl) {
    var consumed: Long = 0
        private set
    val remaining: Long get() = limit - consumed
    fun add(count: Int) {
        control.check()
        if (count < 0 || count.toLong() > remaining) exportFailure(ContentExportReason.SIZE_LIMIT)
        consumed += count
    }
    fun check() = control.check()
}

internal fun copyContentExportBytes(
    input: InputStream,
    output: OutputStream,
    budget: ContentExportByteBudget,
    expectedLength: Long? = null,
    onProgress: (Long, Long?) -> Unit = { _, _ -> },
): Long {
    budget.check()
    if (expectedLength != null && (expectedLength < 0 || expectedLength > budget.remaining)) exportFailure(ContentExportReason.SIZE_LIMIT)
    val buffer = ByteArray(16 * 1024)
    var copied = 0L
    while (true) {
        budget.check()
        val read = input.read(buffer)
        if (read < 0) break
        if (read == 0) continue
        budget.add(read)
        output.write(buffer, 0, read)
        copied += read
        onProgress(copied, expectedLength)
    }
    budget.check()
    if (expectedLength != null && copied != expectedLength) exportFailure(ContentExportReason.HTTP_ERROR)
    output.flush()
    return copied
}

internal interface ContentExportNetwork {
    fun open(url: String, control: ContentExportControl): ContentExportResponse
}

internal class ContentExportResponse(
    val finalUrl: String,
    input: InputStream,
    val contentLength: Long? = null,
    val mimeType: String? = null,
    private val onClose: () -> Unit = {},
) : Closeable {
    val input: InputStream = object : FilterInputStream(input) {
        override fun read(): Int = withExportNetwork { super.read() }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int = withExportNetwork { super.read(bytes, offset, length) }
    }
    override fun close() { try { withExportNetwork { input.close() } } finally { withExportNetwork { onClose() } } }
    override fun toString() = "ContentExportResponse(url=<redacted>, contentLength=$contentLength, mimeType=$mimeType)"
}

internal class UrlConnectionContentExportNetwork(private val options: ContentExportOptions) : ContentExportNetwork {
    override fun open(url: String, control: ContentExportControl): ContentExportResponse = withExportNetwork {
        var address = checkedContentExportUri(url, options)
        var redirects = 0
        while (true) {
            control.check()
            val connection = address.toURL().openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.connectTimeout = control.timeout(options.connectTimeoutMillis)
                connection.readTimeout = control.timeout(options.readTimeoutMillis)
                connection.setRequestProperty("Accept-Encoding", "identity")
                val status = connection.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    if (redirects++ >= options.maxRedirects) exportFailure(ContentExportReason.HTTP_ERROR, status)
                    val location = connection.getHeaderField("Location") ?: exportFailure(ContentExportReason.HTTP_ERROR, status)
                    val redirected = checkedContentExportUri(address.resolve(location).toString(), options)
                    if (address.scheme.equals("https", true) && !redirected.scheme.equals("https", true)) exportFailure(ContentExportReason.UNSUPPORTED_URL)
                    address = redirected
                    connection.disconnect()
                    continue
                }
                if (status != HttpURLConnection.HTTP_OK) exportFailure(ContentExportReason.HTTP_ERROR, status)
                val encoding = connection.getHeaderField("Content-Encoding")
                if (!encoding.isNullOrBlank() && !encoding.equals("identity", true)) exportFailure(ContentExportReason.HTTP_ERROR, status)
                val length = connection.getHeaderField("Content-Length")?.let {
                    it.toLongOrNull()?.takeIf { size -> size >= 0 } ?: exportFailure(ContentExportReason.HTTP_ERROR, status)
                }
                val mime = connection.contentType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
                return@withExportNetwork ContentExportResponse(address.toString(), connection.inputStream, length, mime, connection::disconnect)
            } catch (error: Throwable) {
                connection.disconnect()
                throw error
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }
}

internal fun checkedContentExportUri(url: String, options: ContentExportOptions): URI {
    val uri = try { URI(url.trim()) } catch (_: Exception) { exportFailure(ContentExportReason.UNSUPPORTED_URL) }
    val allowed = uri.scheme.equals("https", true) || (options.allowCleartextHttp && uri.scheme.equals("http", true))
    if (!allowed || uri.host.isNullOrBlank() || uri.rawUserInfo != null || url.length > 16_384) exportFailure(ContentExportReason.UNSUPPORTED_URL)
    return uri
}

internal inline fun <T> withExportNetwork(action: () -> T): T = try { action() }
catch (error: ContentExportException) { throw error }
catch (_: SocketTimeoutException) { exportFailure(ContentExportReason.TIMEOUT) }
catch (_: IOException) { exportFailure(ContentExportReason.NETWORK_ERROR) }
catch (_: IllegalArgumentException) { exportFailure(ContentExportReason.UNSUPPORTED_URL) }

internal fun exportFailure(reason: ContentExportReason, httpStatus: Int? = null): Nothing =
    throw ContentExportException(ContentExportFailure(reason, httpStatus))

private fun validateExportJpeg(file: File) {
    RandomAccessFile(file, "r").use { input ->
        if (input.length() < 4 || input.readUnsignedShort() != 0xffd8 || input.readUnsignedByte() != 0xff) exportFailure(ContentExportReason.UNSUPPORTED_ARTWORK_FORMAT)
        input.seek(input.length() - 2)
        if (input.readUnsignedShort() != 0xffd9) exportFailure(ContentExportReason.UNSUPPORTED_ARTWORK_FORMAT)
    }
}

/** PNG is expected; a concrete non-template URL may still publish JPEG, which is kept unchanged. */
internal fun detectExportWordmarkFormat(file: File): ContentExportFileFormat {
    RandomAccessFile(file, "r").use { input ->
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        if (input.length() >= 20) {
            val header = ByteArray(8); input.readFully(header)
            if (header.contentEquals(png)) {
                // A complete PNG ends with the fixed IEND chunk (length 0, type, CRC).
                input.seek(input.length() - 12)
                val tail = ByteArray(12); input.readFully(tail)
                val iend = byteArrayOf(0, 0, 0, 0, 0x49, 0x45, 0x4e, 0x44, 0xae.toByte(), 0x42, 0x60, 0x82.toByte())
                if (tail.contentEquals(iend)) return ContentExportFileFormat.PNG
                exportFailure(ContentExportReason.UNSUPPORTED_WORDMARK_FORMAT)
            }
        }
    }
    try { validateExportJpeg(file) } catch (_: ContentExportException) { exportFailure(ContentExportReason.UNSUPPORTED_WORDMARK_FORMAT) }
    return ContentExportFileFormat.JPEG
}

private fun checkExportTextSize(text: String, options: ContentExportOptions) {
    if (text.length.toLong() > minOf(options.maxBytes, options.maxTextBytes)) exportFailure(ContentExportReason.SIZE_LIMIT)
}

/** Validate the envelope without parsing/rebuilding lyrics or following any XML external entities. */
internal fun isCompleteExportTtml(raw: String): Boolean {
    val start = Regex("(?s)^\uFEFF?\\s*(?:<\\?xml\\b.*?\\?>\\s*)?(?:<!--.*?-->\\s*)*<((?:[A-Za-z_][\\w.-]*:)?tt)(?=\\s|>)").find(raw)
        ?: return false
    val root = Regex.escape(start.groupValues[1])
    return Regex("(?s)</$root\\s*>\\s*(?:<!--.*?-->\\s*)*$").containsMatchIn(raw)
}

/** Honor an existing XML declaration; never rewrite the TTML string or silently replace characters. */
private fun encodeExportTtml(raw: String): ByteArray {
    val declared = Regex("^\uFEFF?\\s*<\\?xml\\b[^?]*?\\bencoding\\s*=\\s*(['\"])([^'\"]+)\\1").find(raw)?.groupValues?.get(2)
    val charset = try { Charset.forName(declared ?: "UTF-8") } catch (_: Exception) { exportFailure(ContentExportReason.INVALID_TTML) }
    val effective = if (charset == StandardCharsets.UTF_16 && raw.startsWith('\uFEFF')) StandardCharsets.UTF_16BE else charset
    return try {
        val bytes = effective.newEncoder().encode(CharBuffer.wrap(raw))
        ByteArray(bytes.remaining()).also { bytes.get(it) }
    } catch (_: CharacterCodingException) { exportFailure(ContentExportReason.INVALID_TTML) }
}
