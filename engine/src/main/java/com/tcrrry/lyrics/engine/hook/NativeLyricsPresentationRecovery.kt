package com.tcrrry.lyrics.engine.hook

import android.view.View
import android.view.ViewGroup
import com.tcrrry.lyrics.plugin.PluginLifetime

/** Recover native presentation gates; never replace lyrics, clocks, indexes or timed words. */
internal class NativeLyricsPresentationRecovery private constructor(private val fragment: Class<*>) {
    private fun field(name: String) = fragment.lyricsDeclaredField(name).apply { isAccessible = true }
    private val binding = field("n0")
    private val list = binding.type.lyricsDeclaredField("f0").apply { isAccessible = true }
    private val pointer = field("h1")
    private val adapter = field("p0")
    private val follow = field("V0").also { require(it.type == Boolean::class.javaPrimitiveType) }
    private val callback = field("Z0")
    private val scroll = callback.type.getDeclaredMethod("b", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
    private val getView = fragment.getMethod("getView")
    private val hidden = fragment.getMethod("isHidden")
    private val scrollState = list.type.getMethod("getScrollState")
    private val adapterPointer = adapter.type.getMethod("y")
    private val browsing = adapter.type.getMethod("C")
    private val unlock = adapter.type.getMethod("N")
    private val enable = adapter.type.getMethod("P", Boolean::class.javaPrimitiveType)
    private val rebind = adapter.type.getMethod("g")
    private val browser = fragment.getMethod("getMediaBrowser")
    private val playing = browser.returnType.getMethod("isPlaying")
    // Only the verified karaoke adapter has this independent word-animation gate.
    private val karaoke = lyricsClassForName("com.apple.android.music.player.A", false, fragment.classLoader)
    private val wordAnimationEnabled = karaoke.lyricsDeclaredField("A").apply { isAccessible = true }

    private fun current(owner: Any?, recycler: ViewGroup, document: Any?, active: Set<Int>): Any? {
        if (!PluginLifetime.alive || owner == null || !fragment.isInstance(owner) || document == null ||
            active.isEmpty() || active.any { it < 0 } || pointer.get(owner) !== document) return null
        val root = getView.invoke(owner) as? View ?: return null
        if (!root.isAttachedToWindow || !root.isShown || !root.hasWindowFocus() || hidden.invoke(owner) == true ||
            !recycler.isAttachedToWindow || !recycler.isShown || list.get(binding.get(owner)) !== recycler ||
            scrollState.invoke(recycler) != 0) return null
        val media = browser.invoke(owner) ?: return null
        if (playing.invoke(media) != true) return null
        return adapter.get(owner)?.takeIf { adapterPointer.invoke(it) === document }
    }

    private fun suspendedAdapter(target: Any): Boolean = browsing.invoke(target) == true ||
        (karaoke.isInstance(target) && !wordAnimationEnabled.getBoolean(target))

    fun suspended(owner: Any?, recycler: ViewGroup, document: Any?, active: Set<Int>): Boolean =
        runCatching { current(owner, recycler, document, active)?.let(::suspendedAdapter) == true }
            .getOrDefault(false)

    fun recover(owner: Any?, recycler: ViewGroup, document: Any?, active: Set<Int>): Boolean = runCatching {
        val target = current(owner, recycler, document, active) ?: return false
        val needsRebind = suspendedAdapter(target)
        val listener = callback.get(owner) ?: return false
        unlock.invoke(target)
        enable.invoke(target, true)
        follow.setBoolean(owner, true)
        // Apple's own line callback restores browsing/highlight mode, scroll anchoring and VM focus.
        scroll.invoke(listener, active.min(), active.max())
        if (needsRebind) rebind.invoke(target)
        true
    }.onFailure { ModernXposedRuntime.log("Native lyric presentation recovery failed open", it) }.getOrDefault(false)

    companion object {
        fun resolve(fragment: Class<*>): NativeLyricsPresentationRecovery? = runCatching {
            NativeLyricsPresentationRecovery(fragment)
        }.onFailure { ModernXposedRuntime.log("Native lyric presentation contract unavailable", it) }.getOrNull()
    }
}
