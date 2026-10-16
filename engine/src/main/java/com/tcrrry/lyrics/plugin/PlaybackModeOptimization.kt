package com.tcrrry.lyrics.plugin

import android.content.Context
import com.tcrrry.lyrics.plugin.PluginStorage.pluginPreferences
import com.tcrrry.playbackintent.PlaybackIntentPlugin
import dev.amenhancer.plugin.api.PluginContext

/** This switch owns only the merged playback registrations, not the lyrics lifetime. */
internal object PlaybackModeOptimization {
    private var runtime: PluginContext? = null
    private var controls: PlaybackIntentPlugin? = null
    private var started = false
    private var lastQueue = java.lang.ref.WeakReference<Any>(null)
    fun enabled(context: Context) = context.pluginPreferences("playback_mode_optimization", Context.MODE_PRIVATE)
        .getBoolean("enabled", true)

    @Synchronized fun attach(context: PluginContext) {
        runtime = context
        context.onClose { close() }
        if (enabled(context.application)) prepare()
    }
    @Synchronized fun start() { started = true; controls?.onStart() }
    @Synchronized fun setEnabled(context: Context, enabled: Boolean) {
        context.pluginPreferences("playback_mode_optimization", Context.MODE_PRIVATE).edit().putBoolean("enabled", enabled).apply()
        if (enabled) prepare() else {
            controls?.let { lastQueue = java.lang.ref.WeakReference(it.observedQueue()); it.onStop() }
            controls = null
        }
    }
    private fun prepare() {
        val context = runtime ?: return
        if (controls != null || context.hostVersionCode != 1609L) return
        val candidate = PlaybackIntentPlugin()
        try {
            candidate.onLoad(context)
            controls = candidate
            if (started) { candidate.onStart(); candidate.resumeObservedQueue(lastQueue.get()) }
        } catch (error: Exception) {
            if (controls === candidate) controls = null
            candidate.onStop()
            context.log("Playback controls unavailable for this host", error)
        }
    }
    @Synchronized fun close() {
        controls?.onStop(); controls = null; runtime = null; started = false; lastQueue.clear()
    }
}
