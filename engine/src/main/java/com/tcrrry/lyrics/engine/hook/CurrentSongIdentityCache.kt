package com.tcrrry.lyrics.engine.hook

import com.tcrrry.lyrics.engine.CurrentSongDetails
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicReference

data class TargetCurrentSong(
    val item: Any,
    val details: CurrentSongDetails,
    val nativeMetadata: Any? = null,
)

/** Shared target-process state from Apple's player-level metadata funnel. */
class CurrentSongIdentityCache {
    private val current = AtomicReference<TargetCurrentSong?>(null)
    private val listeners = CopyOnWriteArraySet<(TargetCurrentSong?) -> Unit>()
    private val recentIds = ArrayDeque<Long>()
    private val recentIdsLock = Any()

    @Synchronized fun publish(item: Any?, details: CurrentSongDetails?, nativeMetadata: Any? = null) {
        val published = if (item != null && details != null && details.appleMusicId > 0L) {
            TargetCurrentSong(item, details, nativeMetadata)
        } else {
            null
        }
        current.set(published)
        notifyPublished(published)
    }

    /** A restored page can fill a missed startup event, but never replace player metadata. */
    @Synchronized internal fun bootstrap(item: Any?, details: CurrentSongDetails?): Boolean {
        if (item == null || details == null || details.appleMusicId <= 0L || current.get() != null) return false
        val restored = TargetCurrentSong(item, details)
        current.set(restored)
        notifyPublished(restored)
        return true
    }

    private fun notifyPublished(published: TargetCurrentSong?) {
        synchronized(recentIdsLock) {
            if (published == null) {
                recentIds.clear()
            } else {
                recentIds.remove(published.details.appleMusicId)
                recentIds.addLast(published.details.appleMusicId)
                while (recentIds.size > MAX_RECENT_IDS) recentIds.removeFirst()
            }
        }
        listeners.forEach { listener ->
            runCatching { listener(published) }
        }
    }

    fun addListener(listener: (TargetCurrentSong?) -> Unit): HostSubscription {
        listeners += listener
        current.get()?.let { published ->
            runCatching { listener(published) }
        }
        return HostSubscription { listeners -= listener }
    }

    fun current(): TargetCurrentSong? = current.get()

    /** Allows a stale fragment ID only when it was recently observed as current. */
    fun canRebind(fragmentAdamId: Long?, publishedAdamId: Long?): Boolean {
        if (publishedAdamId == null || publishedAdamId <= 0L) return false
        if (fragmentAdamId == null) return true
        return synchronized(recentIdsLock) { fragmentAdamId in recentIds }
    }

    private companion object {
        const val MAX_RECENT_IDS = 8
    }
}
