package com.tcrrry.lyrics.engine.hook

/** Uses the media clock even when the native lyric callback chain ends in the outro. */
internal class LyricReplayRecovery {
    private val epoch = LyricPlaybackEpoch()
    private var pending = false
    private var previousPosition: Long? = null
    private var songId: Long? = null
    private var songToken = Any()
    private var pendingOpening = false
    var opening = false
        private set

    fun observe(document: Any, position: Long, dragging: Boolean, currentSongId: Long? = null, forceRepeat: Boolean = true): Long? {
        // A replacement pointer can be re-created while the same song repeats.
        // Only a verified catalog ID may carry the clock across that replacement.
        val id = currentSongId?.takeIf { it > 0L }
        if (songId != id) { songId = id; songToken = Any() }
        val old = previousPosition
        previousPosition = position
        when (epoch.update(if (id != null) songToken else document, position)) {
            LyricPlaybackEpoch.Change.DOCUMENT -> { pending = false; pendingOpening = false; opening = false; return null }
            LyricPlaybackEpoch.Change.REWIND -> {
                pending = true
                pendingOpening = forceRepeat && isOpeningRewind(old, position)
            }
            LyricPlaybackEpoch.Change.SEEK_FORWARD -> { pending = true; pendingOpening = pendingOpening && position <= 5_000L }
            LyricPlaybackEpoch.Change.CONTINUOUS -> Unit
        }
        if (!pending || dragging) return null
        pending = false
        opening = pendingOpening && forceRepeat && position <= 5_000L
        pendingOpening = false
        return position
    }

    companion object {
        // Some provider documents leave the native catalog ID at zero. Require
        // the live player and page to agree; reject a conflicting known document.
        fun sameSong(player: Long?, page: Long?, document: Long?) = player != null && player > 0L && player == page &&
            (document == null || document <= 0L || document == player)
        fun isOpeningRewind(previous: Long?, position: Long?) = previous != null && position != null && previous >= 10_000L && position in 0L..2_500L
        fun scrollIds(ids: Set<Int>, opening: Boolean): java.util.SortedSet<Int> = if (opening) sortedSetOf(0) else ids.toSortedSet()
    }
}
