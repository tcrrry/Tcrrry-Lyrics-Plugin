package com.tcrrry.lyrics.plugin

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.Message
import dev.amenhancer.plugin.api.PluginContext
import java.util.concurrent.atomic.AtomicBoolean

/** All plugin-owned tasks and storage are tied to the SDK's logical lifetime. */
object PluginLifetime {
    lateinit var context: PluginContext
        private set
    private val stopped = AtomicBoolean(false)
    val alive get() = ::context.isInitialized && !stopped.get()
    fun attach(value: PluginContext) { check(!::context.isInitialized); context=value; value.onClose { stopped.set(true) } }
    fun close(action: () -> Unit) { context.onClose(Runnable(action)) }
}
object PluginStorage {
    private val preferences=java.util.concurrent.ConcurrentHashMap<String,FilePreferences>()
    fun Context.pluginPreferences(name: String, mode: Int): SharedPreferences =
        preferences.computeIfAbsent(name) {
            require(name.matches(Regex("[A-Za-z0-9_.-]{1,160}")))
            FilePreferences(java.io.File(PluginLifetime.context.dataDirectory,"preferences/$name.json"))
        }
    val Context.pluginFilesDir get() = PluginLifetime.context.dataDirectory
    val Context.pluginCacheDir get() = PluginLifetime.context.cacheDirectory
}
class PluginHandler(looper: Looper = Looper.getMainLooper()) : Handler(looper) {
    init { if (PluginLifetime.alive) PluginLifetime.close { removeCallbacksAndMessages(null) } }
    override fun dispatchMessage(message: Message) { if (PluginLifetime.alive) super.dispatchMessage(message) }
}
