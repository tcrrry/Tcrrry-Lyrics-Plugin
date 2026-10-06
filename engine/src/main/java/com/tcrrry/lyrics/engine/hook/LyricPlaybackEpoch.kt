package com.tcrrry.lyrics.engine.hook

/** Detect discontinuities before native callbacks, including a repeat with the same document. */
internal class LyricPlaybackEpoch {
    private var token: Any? = null
    private var position: Long? = null
    private var observedAt: Long? = null
    enum class Change { DOCUMENT, REWIND, SEEK_FORWARD, CONTINUOUS }
    @Synchronized fun update(document: Any, current: Long?, nowMs: Long = System.nanoTime() / 1_000_000): Change {
        val change = when {
            token !== document -> Change.DOCUMENT
            current != null && position != null && current < position!! -> Change.REWIND
            current != null && position != null && observedAt != null &&
                current - position!! > (nowMs - observedAt!!).coerceAtLeast(0) + 1500 -> Change.SEEK_FORWARD
            else -> Change.CONTINUOUS
        }
        token = document
        if (change == Change.DOCUMENT || current != null) { position = current; observedAt = nowMs }
        return change
    }
}
