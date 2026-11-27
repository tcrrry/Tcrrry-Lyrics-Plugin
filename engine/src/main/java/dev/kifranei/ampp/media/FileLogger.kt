package dev.kifranei.ampp.media

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue

/**
 * 异步无阻塞文件日志工具，防止音频播放线程因同步写盘（FUSE I/O）发生卡顿。
 * 路径：Android/data/com.apple.android.music/files/automix_log.txt
 */
internal object FileLogger {
    private const val MAX_FILE_BYTES = 512L * 1024L
    private const val QUEUE_CAPACITY = 512
    private val queue = LinkedBlockingQueue<String>(QUEUE_CAPACITY)
    @Volatile private var logFile: File? = null
    @Volatile private var workerStarted = false
    private val workerLock = Any()

    private fun ensureWorkerStarted() {
        if (workerStarted) return
        synchronized(workerLock) {
            if (workerStarted) return
            workerStarted = true
            Thread({
                val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault())
                val pending = ArrayList<String>(32)
                while (true) {
                    val first = try {
                        queue.take()
                    } catch (_: InterruptedException) {
                        continue
                    }
                    pending.clear()
                    pending.add(first)
                    queue.drainTo(pending, 63)
                    val file = logFile ?: continue
                    runCatching {
                        val sb = StringBuilder()
                        var reset = false
                        for (entry in pending) {
                            if (entry.startsWith("__RESET__:")) {
                                sb.setLength(0)
                                sb.append(entry.removePrefix("__RESET__:"))
                                reset = true
                            } else {
                                sb.append(entry)
                            }
                        }
                        if (reset || (file.exists() && file.length() > MAX_FILE_BYTES)) {
                            file.writeText(sb.toString())
                        } else if (sb.isNotEmpty()) {
                            file.appendText(sb.toString())
                        }
                    }
                    @Suppress("UNUSED_EXPRESSION")
                    fmt
                }
            }, "automix-file-logger").apply { isDaemon = true }.start()
        }
    }

    private fun nowStamp(): String = synchronized(workerLock) {
        stampFormat.format(Date())
    }

    private val stampFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault())

    fun init(context: Context) {
        runCatching {
            val dir = context.getExternalFilesDir(null)
            if (dir != null) {
                logFile = File(dir, "automix_log.txt")
                ensureWorkerStarted()
                queue.clear()
                queue.offer("__RESET__:===== AutoMix session start ${nowStamp()} =====\n")
            }
        }
    }

    fun log(message: String) {
        if (logFile == null) return
        ensureWorkerStarted()
        queue.offer("${nowStamp()} $message\n")
    }

    fun log(message: String, error: Throwable?) {
        log("$message | error: ${error?.javaClass?.simpleName}: ${error?.message}")
    }
}
