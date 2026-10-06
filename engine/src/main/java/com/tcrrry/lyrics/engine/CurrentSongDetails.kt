package com.tcrrry.lyrics.engine

/** Verified current-item metadata shared by target hooks and embedded settings. */
data class CurrentSongDetails(
    val appleMusicId: Long,
    val title: String? = null,
    val artist: String? = null,
)
