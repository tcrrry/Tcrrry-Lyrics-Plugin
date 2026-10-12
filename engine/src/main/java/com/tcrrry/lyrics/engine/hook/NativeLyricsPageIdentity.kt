package com.tcrrry.lyrics.engine.hook

import android.os.Bundle
import java.lang.reflect.Modifier

/** Library enrichment may replace an uploaded item's media ID with its library ID.
 * Validate against the page's live MediaBrowser before reconciling that alias.
 */
internal class NativeLyricsPageIdentity(
    private val liveIdentity: (Any) -> Identity?,
    private val metadataIdentity: (Any) -> Identity?,
    private val pageItem: (Any) -> Any?,
    private val itemIdentity: (Any) -> Identity?,
    private val freshItem: (Any, Any) -> Any?,
    private val bind: (Any, Any) -> Boolean,
    private val isCurrent: (TargetCurrentSong) -> Boolean = { true },
) {
    data class Identity(val id: Long, val persistentId: Long, val queueId: Long)

    fun isAuthoritative(page: Any, snapshot: TargetCurrentSong): Boolean {
        if (!isCurrent(snapshot)) return false
        val metadata = snapshot.nativeMetadata ?: return false
        val expected = metadataIdentity(metadata) ?: return false
        val live = liveIdentity(page) ?: return false
        // Require exact queue/persistent identities, including absent values. A
        // title match or a reused song ID is insufficient to authorize replay.
        return expected.id > 0 && expected.id == snapshot.details.appleMusicId && live == expected
    }

    fun restoreLibraryAlias(page: Any, snapshot: TargetCurrentSong): Boolean {
        if (!isAuthoritative(page, snapshot)) return false
        val metadata = snapshot.nativeMetadata ?: return false
        val expected = metadataIdentity(metadata) ?: return false
        val original = pageItem(page) ?: return false
        val actual = itemIdentity(original) ?: return false
        if (actual.id == expected.id || expected.persistentId == 0L ||
            actual.persistentId != expected.persistentId || actual.queueId != expected.queueId) return false
        // Use a new native item, retaining library flags. Never mutate the shared
        // player snapshot or an item held by the native library cache.
        val replacement = freshItem(metadata, original) ?: return false
        if (replacement === original || replacement === snapshot.item || itemIdentity(replacement) != expected ||
            !isAuthoritative(page, snapshot)) return false
        return bind(page, replacement)
    }

    companion object {
        private const val PREFIX = "com.apple.android.music.playback.metadata."

        fun resolve(fragment: Class<*>, seam: CurrentItemIdentitySeam, currentSong: CurrentSongIdentityCache): NativeLyricsPageIdentity? = runCatching {
            check(fragment.name == "com.apple.android.music.player.fragment.PlayerLyricsViewFragment")
            val browser = fragment.getMethod("getMediaBrowser").apply { isAccessible = true }
            val metadata = browser.returnType.getMethod("i0").apply { isAccessible = true }
            check(metadata.returnType.name == NativeLyricsHostContract.className("z3.w"))
            val bundle = metadata.returnType.lyricsDeclaredField("J").apply { isAccessible = true }
            check(bundle.type == Bundle::class.java)
            val converter = lyricsClassForName("com.apple.android.music.player.P", false, fragment.classLoader)
                .getDeclaredMethod("b", metadata.returnType).apply { isAccessible = true }
            check(Modifier.isStatic(converter.modifiers))
            val base = lyricsClassForName("com.apple.android.music.model.BaseContentItem", false, fragment.classLoader)
            val getId = base.getMethod("getId")
            val persistent = base.getMethod("getPersistentId")
            val queue = lyricsClassForName("com.apple.android.music.model.PlaybackItem", false, fragment.classLoader)
                .getMethod("getQueueId")
            val enrich = base.getMethod("overwriteLibraryDataFrom", base)
            val setId = base.getMethod("setId", String::class.java)
            fun read(value: Any): Identity? {
                val extras = bundle.get(value) as? Bundle ?: return null
                val id = parseCurrentItemAdamId(extras.getString(PREFIX + "METADATA_KEY_MEDIA_ID")) ?: return null
                return Identity(id, extras.getLong(PREFIX + "PERSISTENT_ID"), extras.getLong(PREFIX + "ITEM_QUEUE_ID"))
            }
            NativeLyricsPageIdentity(
                liveIdentity = { page -> browser.invoke(page)?.let { metadata.invoke(it) }?.let(::read) },
                metadataIdentity = ::read,
                pageItem = seam::currentItemOf,
                itemIdentity = { item -> parseCurrentItemAdamId(getId.invoke(item))?.let {
                    Identity(it, (persistent.invoke(item) as Number).toLong(), (queue.invoke(item) as Number).toLong())
                } },
                freshItem = { value, original -> converter.invoke(null, value)?.also {
                    enrich.invoke(it, original)
                    setId.invoke(it, read(value)?.id?.toString())
                } },
                bind = seam::bindCurrentItemOf,
                isCurrent = { currentSong.current() === it },
            )
        }.onFailure { ModernXposedRuntime.log("Live lyrics page identity unavailable", it) }.getOrNull()
    }
}
