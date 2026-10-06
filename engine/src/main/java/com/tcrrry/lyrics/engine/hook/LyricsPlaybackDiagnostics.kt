package com.tcrrry.lyrics.engine.hook

import com.tcrrry.lyrics.engine.BuildConfig

/** Bounded, local test-build trace. Never records lyric text or credentials. */
internal object LyricsPlaybackDiagnostics {
    private val buffer = PlaybackTraceBuffer()
    const val enabled: Boolean = false
    fun record(event: String, details: String = "", sample: Boolean = false) {
        if (enabled) buffer.record(event, details, System.nanoTime() / 1_000_000, sample)
    }
    fun identity(value: Any?): String = value?.let { Integer.toHexString(System.identityHashCode(it)) } ?: "null"
    fun export(): String = "AMPP ${BuildConfig.VERSION_NAME} SDK=${android.os.Build.VERSION.SDK_INT}\n" + buffer.export()
}

internal class PlaybackTraceBuffer(private val capacity: Int = 768) {
    init { require(capacity > 0) }
    private val rows = ArrayDeque<String>()
    private val lastSamples = mutableMapOf<String, Long>()
    @Synchronized fun record(event: String, details: String, now: Long, sample: Boolean = false) {
        if (sample) {
            val last = lastSamples[event]
            if (last != null && now - last < 500) return
            // Bound sampling keys as well as messages.
            if (lastSamples.size >= 32 && event !in lastSamples) lastSamples.clear()
            lastSamples[event] = now
        }
        if (rows.size == capacity) rows.removeFirst()
        rows.addLast("$now ${event.take(80)} ${details.take(800)}")
    }
    @Synchronized fun export(): String = rows.joinToString("\n")
}
