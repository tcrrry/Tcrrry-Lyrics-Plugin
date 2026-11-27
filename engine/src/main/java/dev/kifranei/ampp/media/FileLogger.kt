package dev.kifranei.ampp.media

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 文件日志工具。把关键日志写到应用外部存储目录，用户用文件管理器就能查看。
 * 路径：Android/data/com.apple.android.music/files/automix_log.txt
 */
internal object FileLogger {
    private var logFile: File? = null
    private val dateFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault())

    fun init(context: Context) {
        runCatching {
            val dir = context.getExternalFilesDir(null)
            if (dir != null) {
                logFile = File(dir, "automix_log.txt")
                // 每次 session 清空旧日志，避免文件越来越大
                logFile?.writeText("===== AutoMix session start ${dateFormat.format(Date())} =====\n")
            }
        }
    }

    fun log(message: String) {
        runCatching {
            val line = "${dateFormat.format(Date())} $message\n"
            logFile?.appendText(line)
        }
    }

    fun log(message: String, error: Throwable?) {
        log("$message | error: ${error?.javaClass?.simpleName}: ${error?.message}")
    }
}
