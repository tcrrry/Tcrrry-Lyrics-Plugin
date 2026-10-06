package com.tcrrry.lyrics.engine.hook

/** Returns the current lyric row only when Apple's visible list has fallen behind playback. */
internal object LyricFollowRecoveryPolicy {
    fun offscreenTarget(active: Set<Int>, visible: List<Int>, gap: Boolean): Int? {
        if (gap || active.isEmpty()) return null
        val rows = visible.filter { it >= 0 }
        if (rows.isEmpty() || active.any(rows::contains)) return null
        val current = active.maxOrNull() ?: return null
        return current.takeIf { it < rows.min() || it > rows.max() }
    }
}
