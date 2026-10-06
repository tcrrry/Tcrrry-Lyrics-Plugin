package com.tcrrry.lyrics.engine.hook

/** Uses the media clock even when the native lyric callback chain ends in the outro. */
internal class LyricReplayRecovery {
    private val epoch = LyricPlaybackEpoch()
    private var pending = false

    fun observe(document: Any, position: Long, dragging: Boolean): Long? {
        when (epoch.update(document, position)) {
            LyricPlaybackEpoch.Change.DOCUMENT -> { pending = false; return null }
            LyricPlaybackEpoch.Change.REWIND, LyricPlaybackEpoch.Change.SEEK_FORWARD -> pending = true
            LyricPlaybackEpoch.Change.CONTINUOUS -> Unit
        }
        if (!pending || dragging) return null
        pending = false
        return position
    }
}
