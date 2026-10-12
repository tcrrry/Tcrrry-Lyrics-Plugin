package com.tcrrry.lyrics.engine.hook

/** Recover a missed page notification through the native metadata listener, including duration and lyrics. */
internal class NativeLyricsMetadataRefresh(
    private val currentSong: CurrentSongIdentityCache,
    private val itemId: (Any) -> Long?,
    private val usable: (Any) -> Boolean,
    private val dispatch: ((() -> Unit) -> Unit),
    private val replay: (Any, Any) -> Unit,
    private val recovered: (Any, Long) -> Unit = { _, _ -> },
    private val authoritative: (Any, TargetCurrentSong) -> Boolean = { _, _ -> false },
    private val restoreAlias: (Any, TargetCurrentSong) -> Boolean = { _, _ -> false },
) {
    fun schedule(fragment: Any) {
        val snapshot = currentSong.current() ?: return
        val metadata = snapshot.nativeMetadata ?: return
        val page = java.lang.ref.WeakReference(fragment)
        dispatch {
            val live = page.get() ?: return@dispatch
            // A newer publish wins, even if it shares the same ID. Never replay an old queue event.
            if (currentSong.current() !== snapshot || !usable(live)) return@dispatch
            val id = snapshot.details.appleMusicId
            val oldId = itemId(live)
            if (oldId == id) return@dispatch
            val verified = authoritative(live, snapshot)
            if (!verified && !currentSong.canRebind(oldId, id)) return@dispatch
            // Same-persistent-ID aliases need no metadata replay: doing so would
            // repeat the library fetch which caused the ID overwrite in the first place.
            if (!verified || !restoreAlias(live, snapshot)) replay(live, metadata)
            if (currentSong.current() === snapshot && itemId(live) != id && verified) restoreAlias(live, snapshot)
            if (currentSong.current() === snapshot && itemId(live) == id) recovered(live, id)
        }
    }

    companion object {
        fun resolveReplay(fragment: Class<*>): ((Any, Any) -> Unit)? = runCatching {
            if (fragment.declaredMethods.none { it.name == "w2" }) return null
            val owner = generateSequence(fragment) { it.superclass }
                .single { it.name == "com.apple.android.music.player.fragment.e" }
            val listener = owner.lyricsDeclaredField("g0").apply { isAccessible = true }
            check(listener.type.name == "com.apple.android.music.player.fragment.e\$d")
            val publish = listener.type.getDeclaredMethod("onMediaMetadataChanged",
                lyricsClassForName("z3.w", false, fragment.classLoader)).apply { isAccessible = true }
            check(publish.returnType == Void.TYPE)
            val replay: (Any, Any) -> Unit = { page, metadata ->
                if (publish.parameterTypes.single().isInstance(metadata)) {
                    publish.invoke(listener.get(page), metadata)
                }
            }
            replay
        }.getOrNull()
    }
}
