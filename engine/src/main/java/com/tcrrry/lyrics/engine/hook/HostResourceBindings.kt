package com.tcrrry.lyrics.engine.hook

import com.tcrrry.lyrics.engine.config.TargetConfigClient

/** Early resource binding shared with the later typeface capability; no host reflection escapes. */
interface LyricsTypefaceResourceBinding {
    fun registerResources(config: TargetConfigClient)
}

/** Logical observation lifetime; closing detaches observers, not framework method hooks. */
fun interface HostSubscription : AutoCloseable {
    override fun close()
}

/** Native lyric payload identity, intentionally exposing no host object or member. */
interface NativeLyricsHandle
