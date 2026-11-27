package com.tcrrry.lyrics.plugin

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.Process
import android.provider.MediaStore
import dev.amenhancer.plugin.api.PluginContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Always-on rolling diagnostic log stored in app-external files so users can export logs without root. */
internal object PluginDiagnosticLog {
    const val PLUGIN_VERSION = "1.4"
    const val MAX_FILE_BYTES = 256L * 1024L
    const val TRIM_TARGET_BYTES = 128L * 1024L
    const val PREVIEW_MAX_BYTES = 24 * 1024
    const val MAX_QUEUE_SIZE = 512

    enum class Channel(val label: String, val fileName: String) {
        LYRICS("歌词", "tcrrry_lyrics_log.txt"),
        AUTOMIX("智能混音", "automix_log.txt"),
        USB("有线耳机", "usb_bitperfect_log.txt"),
    }

    data class ChannelInfo(
        val channel: Channel,
        val exists: Boolean,
        val sizeBytes: Long,
        val lastModifiedMs: Long,
        val path: String,
    )

    private val lock = Any()
    private val pendingCount = AtomicInteger(0)
    private var executor: ExecutorService? = null

    @Volatile private var attached = false
    @Volatile private var closed = false
    @Volatile private var appContext: Context? = null
    @Volatile private var hostPkg: String = ""
    @Volatile private var hostVerName: String = ""
    @Volatile private var hostVerCode: Long = 0L

    private var lastMessage = ""
    private var lastMessageAtMs = 0L

    private fun timeFormat() = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private fun fileStampFormat() = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    private fun humanStampFormat() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun attach(context: PluginContext) {
        val app = context.application
        val activeExecutor: ExecutorService
        synchronized(lock) {
            appContext = app
            hostPkg = context.hostPackageName
            hostVerName = context.hostVersionName
            hostVerCode = context.hostVersionCode
            attached = true
            closed = false
            lastMessage = ""
            lastMessageAtMs = 0L
            if (executor == null || executor?.isShutdown == true) {
                executor = Executors.newSingleThreadExecutor { runnable ->
                    Thread(runnable, "tcrrry-diag-log").apply {
                        isDaemon = true
                        priority = Thread.MIN_PRIORITY + 1
                    }
                }
            }
            activeExecutor = executor!!
        }
        context.onClose { close() }
        val banner = "===== Session start ${humanStampFormat().format(Date())} " +
            "plugin=$PLUGIN_VERSION host=$hostPkg $hostVerName ($hostVerCode) " +
            "sdk=${Build.VERSION.SDK_INT} pid=${Process.myPid()} ====="
        enqueue(activeExecutor, app, banner)
    }

    fun close() {
        val oldExecutor: ExecutorService?
        synchronized(lock) {
            closed = true
            attached = false
            oldExecutor = executor
            executor = null
        }
        oldExecutor?.shutdownNow()
        pendingCount.set(0)
    }

    fun record(message: String, error: Throwable? = null) {
        if (!attached || closed) return
        val clean = message.trim()
        if (clean.isEmpty() && error == null) return
        val now = System.currentTimeMillis()
        val app: Context
        val activeExecutor: ExecutorService
        synchronized(lock) {
            if (!attached || closed) return
            if (error == null && clean == lastMessage && now - lastMessageAtMs < 2_000L) return
            lastMessage = clean
            lastMessageAtMs = now
            app = appContext ?: return
            activeExecutor = executor ?: return
        }
        val formatted = buildString {
            append(timeFormat().format(Date(now)))
            append(" [").append(Process.myPid()).append("] ")
            append(clean)
            if (error != null) {
                append(" | ").append(error.javaClass.simpleName)
                error.message?.takeIf(String::isNotBlank)?.let { append(": ").append(it.take(240)) }
                val frames = error.stackTrace.take(5)
                if (frames.isNotEmpty()) {
                    append("\n  at ")
                    append(frames.joinToString("\n  at ") { it.toString() })
                }
            }
        }
        enqueue(activeExecutor, app, formatted)
    }

    private fun enqueue(targetExecutor: ExecutorService, app: Context, line: String) {
        if (pendingCount.get() >= MAX_QUEUE_SIZE) return
        pendingCount.incrementAndGet()
        runCatching {
            targetExecutor.execute {
                try {
                    val file = resolveWritableFile(app, Channel.LYRICS) ?: return@execute
                    file.parentFile?.mkdirs()
                    FileOutputStream(file, true).use { out ->
                        out.write((line + "\n").toByteArray(Charsets.UTF_8))
                    }
                    trimFileIfNeeded(file, MAX_FILE_BYTES, TRIM_TARGET_BYTES)
                } catch (_: Throwable) {
                } finally {
                    pendingCount.decrementAndGet()
                }
            }
        }.onFailure {
            pendingCount.decrementAndGet()
        }
    }

    internal fun trimFileIfNeeded(
        file: File,
        maxBytes: Long = MAX_FILE_BYTES,
        targetBytes: Long = TRIM_TARGET_BYTES,
    ): Boolean {
        if (!file.isFile) return false
        val length = file.length()
        if (length <= maxBytes) return false
        val keep = targetBytes.coerceIn(1L, length)
        val startOffset = (length - keep).coerceAtLeast(0L)
        val bytes = RandomAccessFile(file, "r").use { raf ->
            raf.seek(startOffset)
            val buffer = ByteArray((length - startOffset).toInt())
            raf.readFully(buffer)
            buffer
        }
        var alignedOffset = 0
        if (startOffset > 0L) {
            val newlineIndex = bytes.indexOf('\n'.code.toByte())
            if (newlineIndex >= 0 && newlineIndex + 1 < bytes.size) {
                alignedOffset = newlineIndex + 1
            } else {
                // Ensure UTF-8 leading byte alignment if there was no newline in slice.
                while (alignedOffset < bytes.size && (bytes[alignedOffset].toInt() and 0xC0) == 0x80) {
                    alignedOffset++
                }
            }
        }
        val parent = file.parentFile ?: return false
        val temp = File.createTempFile("diag_trim_", ".tmp", parent)
        return try {
            FileOutputStream(temp, false).use { out ->
                out.write(bytes, alignedOffset, bytes.size - alignedOffset)
                out.flush()
            }
            if (file.delete() && temp.renameTo(file)) {
                true
            } else {
                FileOutputStream(file, false).use { out ->
                    out.write(bytes, alignedOffset, bytes.size - alignedOffset)
                    out.flush()
                }
                temp.delete()
                true
            }
        } catch (_: Throwable) {
            temp.delete()
            false
        }
    }

    fun logDirectory(context: Context): File? {
        val ext = runCatching { context.getExternalFilesDir(null) }.getOrNull()
        return ext ?: runCatching { context.filesDir }.getOrNull()
    }

    fun logFile(context: Context, channel: Channel): File? {
        val dir = logDirectory(context) ?: return null
        return File(dir, channel.fileName)
    }

    private fun resolveWritableFile(context: Context, channel: Channel): File? = logFile(context, channel)

    fun channelInfo(context: Context, channel: Channel): ChannelInfo {
        val file = logFile(context, channel)
        val exists = file?.isFile == true && file.length() > 0L
        return ChannelInfo(
            channel = channel,
            exists = exists,
            sizeBytes = if (exists) file!!.length() else 0L,
            lastModifiedMs = if (exists) file!!.lastModified() else 0L,
            path = file?.absolutePath ?: channel.fileName,
        )
    }

    fun readTail(context: Context, channel: Channel, maxBytes: Int = PREVIEW_MAX_BYTES): String {
        val file = logFile(context, channel)?.takeIf { it.isFile && it.length() > 0L } ?: return ""
        return runCatching {
            val length = file.length()
            val window = maxBytes.coerceAtLeast(1024).toLong()
            val startOffset = if (length > window) length - window else 0L
            val bytes = RandomAccessFile(file, "r").use { raf ->
                raf.seek(startOffset)
                val out = ByteArrayOutputStream((length - startOffset).toInt())
                val buf = ByteArray(8192)
                var read: Int
                while (raf.read(buf).also { read = it } != -1) {
                    out.write(buf, 0, read)
                }
                out.toByteArray()
            }
            var offset = 0
            if (startOffset > 0L) {
                val nl = bytes.indexOf('\n'.code.toByte())
                if (nl >= 0 && nl + 1 < bytes.size) {
                    offset = nl + 1
                } else {
                    while (offset < bytes.size && (bytes[offset].toInt() and 0xC0) == 0x80) offset++
                }
            }
            String(bytes, offset, bytes.size - offset, Charsets.UTF_8).trimEnd()
        }.getOrDefault("")
    }

    fun menuSummary(context: Context): String {
        val total = Channel.entries.sumOf { channelInfo(context, it).sizeBytes }
        return if (total > 0L) "常驻记录 · 歌词 / 混音 / USB（共 ${formatSize(total)}）"
        else "常驻记录 · 歌词 / 混音 / USB"
    }

    fun buildDiagnosticHeader(context: Context): String {
        val pkg = hostPkg.ifBlank { context.packageName }
        val ver = if (hostVerName.isNotBlank() || hostVerCode > 0L) {
            "$hostVerName ($hostVerCode)".trim()
        } else {
            runCatching {
                val info = context.packageManager.getPackageInfo(pkg, 0)
                val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
                "${info.versionName.orEmpty()} ($code)".trim()
            }.getOrDefault("unknown")
        }
        val device = listOf(Build.MANUFACTURER, Build.MODEL)
            .map { it?.trim().orEmpty() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(" ")
            .ifBlank { "Android" }
        return buildString {
            appendLine("=== Tcrrry Lyrics Diagnostic Report ===")
            appendLine("Plugin Version: $PLUGIN_VERSION")
            appendLine("Host App: $pkg $ver")
            appendLine("Device: $device (Android ${Build.VERSION.RELEASE ?: "?"}, API ${Build.VERSION.SDK_INT})")
            appendLine("Process PID: ${Process.myPid()}")
            appendLine("Generated At: ${humanStampFormat().format(Date())}")
        }.trimEnd()
    }

    fun buildBundleText(context: Context, maxPerChannelBytes: Int = 128 * 1024): String = buildString {
        appendLine(buildDiagnosticHeader(context))
        for (channel in Channel.entries) {
            val info = channelInfo(context, channel)
            appendLine()
            appendLine("===== ${channel.label} (${channel.fileName}, size=${formatSize(info.sizeBytes)}) =====")
            val body = readTail(context, channel, maxPerChannelBytes)
            appendLine(if (body.isBlank()) "(暂无记录)" else body)
        }
    }

    fun clear(context: Context, channel: Channel? = null): Boolean {
        val targets = if (channel != null) listOf(channel) else Channel.entries
        var ok = true
        for (item in targets) {
            val file = logFile(context, item) ?: continue
            if (file.exists()) {
                val cleared = runCatching {
                    FileOutputStream(file, false).use { it.flush() }
                    true
                }.getOrDefault(false) || runCatching { file.delete() }.getOrDefault(false)
                if (!cleared) ok = false
            }
        }
        return ok
    }

    fun exportAllToDownloads(context: Context): Result<String> = runCatching {
        val fileName = "Tcrrry-Lyrics-Logs-${fileStampFormat().format(Date())}.txt"
        val bytes = buildBundleText(context).toByteArray(Charsets.UTF_8)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("无法创建下载文件：$fileName")
            try {
                resolver.openOutputStream(uri, "w")?.use { out ->
                    out.write(bytes)
                    out.flush()
                } ?: throw IOException("无法写入下载文件：$fileName")
                val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
                "${Environment.DIRECTORY_DOWNLOADS}/$fileName"
            } catch (error: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
                throw error
            }
        } else {
            val dir = logDirectory(context) ?: throw IOException("外部存储目录不可用")
            dir.mkdirs()
            val outFile = File(dir, fileName)
            FileOutputStream(outFile, false).use { out ->
                out.write(bytes)
                out.flush()
            }
            outFile.absolutePath
        }
    }

    fun formatSize(bytes: Long): String = when {
        bytes < 1024L -> "${bytes} B"
        bytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
    }

    fun formatTimestamp(ms: Long): String = if (ms <= 0L) "暂无" else humanStampFormat().format(Date(ms))
}
