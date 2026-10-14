package com.tcrrry.lyrics.engine.hook

import android.content.Context
import com.tcrrry.lyrics.plugin.PluginStorage.pluginFilesDir
import com.tcrrry.lyrics.plugin.PluginStorage.pluginPreferences
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** Only generated lyric payloads/history belong to this quota, never imported files/models. */
internal object LyricsCacheStorage {
    const val DEFAULT_MB = 64
    const val MAX_MB = 1024
    private val lock = Any()
    private val revision = AtomicLong()
    val epoch: Long get() = revision.get()
    fun automaticDirectory(context: Context) = File(context.pluginFilesDir, "ampp-auto-lyrics-desktop-v14-japanese-r1-policy-v15-r8")
    fun limitMb(context: Context): Int = context.pluginPreferences("lyrics_cache", Context.MODE_PRIVATE)
        .getInt("limit_mb", DEFAULT_MB).coerceIn(0, MAX_MB)
    fun setLimitMb(context: Context, value: Int) = synchronized(lock) {
        context.pluginPreferences("lyrics_cache", Context.MODE_PRIVATE).edit()
            .putInt("limit_mb", value.coerceIn(0, MAX_MB)).apply()
        revision.incrementAndGet()
    }
    private fun files(context: Context): List<File> =
        automaticDirectory(context).listFiles()?.filter { it.isFile && it.name.matches(Regex("lyric_[1-9][0-9]*\\.ttml")) }.orEmpty() +
            File(context.pluginFilesDir, "tcrrry-lyrics-history").listFiles()
                ?.filter { it.isFile && it.name.matches(Regex("[1-9][0-9]*\\.json")) }.orEmpty()
    fun bytes(context: Context): Long = synchronized(lock) { files(context).sumOf(File::length) }
    fun store(context: Context, expectedEpoch: Long = epoch, write: () -> Boolean): Boolean = synchronized(lock) {
        if (expectedEpoch != epoch || limitMb(context) == 0) return false
        val written = write()
        if (written) trim(context)
        written
    }
    fun trim(context: Context): Long = synchronized(lock) {
        evict(files(context), limitMb(context) * 1024L * 1024L)
    }
    fun clear(context: Context): Long = synchronized(lock) {
        revision.incrementAndGet()
        evict(files(context), 0L)
    }
    internal fun evict(files: List<File>, budget: Long): Long {
        var size = files.sumOf(File::length)
        for (file in files.sortedBy(File::lastModified)) {
            if (size <= budget.coerceAtLeast(0)) break
            val bytes = file.length()
            if (file.delete()) size -= bytes
        }
        return size
    }
}
