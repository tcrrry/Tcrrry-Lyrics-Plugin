package com.tcrrry.lyrics.engine.hook

import android.view.View
import android.view.ViewGroup
import com.tcrrry.lyrics.plugin.PluginLifetime
import java.lang.reflect.Modifier

/** Recover native presentation gates; never replace lyrics, clocks, indexes or timed words. */
internal class NativeLyricsPresentationRecovery private constructor(private val fragment: Class<*>) {
    private fun field(name: String) = fragment.lyricsDeclaredField(name).apply { isAccessible = true }
    private val binding = field("n0")
    private val list = binding.type.lyricsDeclaredField("f0").apply { isAccessible = true }
    private val pointer = field("h1")
    private val adapter = field("p0")
    private val follow = field("V0").also { require(it.type == Boolean::class.javaPrimitiveType) }
    private val shareSuppress = runCatching {
        val name = NativeLyricsHostContract.shareSuppressFieldName() ?: "W0"
        fragment.getDeclaredField(name).apply { isAccessible = true }
    }.getOrNull()?.takeIf { it.type == Boolean::class.javaPrimitiveType && it.name != follow.name }
    private val clearShareSuppress = runCatching {
        fragment.getDeclaredMethod("s2").apply { isAccessible = true }
    }.getOrNull()
    private val callback = field("Z0")
    private val scroll = callback.type.getDeclaredMethod("b", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
    private val getView = fragment.getMethod("getView")
    private val hidden = fragment.getMethod("isHidden")
    private val scrollState = list.type.getMethod("getScrollState")
    private val adapterPointer = adapter.type.lyricsMethod("y")
    private val browsing = adapter.type.lyricsMethod("C")
    private val setBrowsing = runCatching {
        adapter.type.lyricsMethod("Q", Boolean::class.javaPrimitiveType)
    }.getOrNull()
    private val lineToAdapterPos = runCatching {
        adapter.type.lyricsMethod("E", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
    }.getOrNull()
    private val unlock = adapter.type.lyricsMethod("N")
    private val enable = adapter.type.lyricsMethod("P", Boolean::class.javaPrimitiveType)
    private val rebind = adapter.type.lyricsMethod("g")
    private val browser = fragment.getMethod("getMediaBrowser")
    private val playing = browser.returnType.getMethod("isPlaying")
    // Only the verified karaoke adapter has this independent word-animation gate.
    private val karaoke = lyricsClassForName("com.apple.android.music.player.A", false, fragment.classLoader)
    private val wordAnimationEnabled = karaoke.lyricsDeclaredField("A").apply { isAccessible = true }
    private val timedStateField = runCatching { field("E0") }.getOrNull()
    private val observableStateField = runCatching { field("G0") }.getOrNull()
    private val observableStateValue = runCatching {
        observableStateField?.type?.lyricsDeclaredField("a")?.apply { isAccessible = true }
    }.getOrNull()
    private val layoutManagerField = runCatching { field("s0") }.getOrNull()
    private val layoutScrollEnabledField = runCatching {
        layoutManagerField?.type?.declaredFields?.singleOrNull {
            it.type == Boolean::class.javaPrimitiveType && !Modifier.isStatic(it.modifiers)
        }?.apply { isAccessible = true }
    }.getOrNull()

    private fun isScrollLocked(owner: Any): Boolean {
        val lmField = layoutManagerField ?: return false
        val scrollField = layoutScrollEnabledField ?: return false
        return runCatching {
            val lm = lmField.get(owner) ?: return@runCatching false
            !scrollField.getBoolean(lm)
        }.getOrDefault(false)
    }

    private fun unlockScrollManager(owner: Any) {
        val lmField = layoutManagerField ?: return
        val scrollField = layoutScrollEnabledField ?: return
        runCatching {
            val lm = lmField.get(owner) ?: return@runCatching
            if (!scrollField.getBoolean(lm)) scrollField.setBoolean(lm, true)
        }
    }

    private fun isTimedState(owner: Any): Boolean {
        val timedField = timedStateField ?: return true
        val obsField = observableStateField ?: return true
        val valueField = observableStateValue ?: return true
        return runCatching {
            val obs = obsField.get(owner) ?: return@runCatching true
            val expected = timedField.get(owner) ?: return@runCatching true
            valueField.get(obs) === expected
        }.getOrDefault(true)
    }

    private fun isShareSuppressed(owner: Any): Boolean {
        val field = shareSuppress ?: return false
        return runCatching { field.getBoolean(owner) }.getOrDefault(false)
    }

    private fun clearNativeSuppress(owner: Any) {
        if (!isShareSuppressed(owner)) return
        runCatching { clearShareSuppress?.invoke(owner) }
        runCatching { shareSuppress?.setBoolean(owner, false) }
    }

    private fun toAdapterPos(target: Any, lineId: Int): Int =
        runCatching { (lineToAdapterPos?.invoke(target, lineId, false) as? Number)?.toInt() }
            .getOrNull()?.takeIf { it >= 0 } ?: lineId

    private fun current(owner: Any?, recycler: ViewGroup, document: Any?, active: Set<Int>): Any? {
        if (!PluginLifetime.alive || owner == null || !fragment.isInstance(owner) || document == null ||
            active.isEmpty() || active.any { it < 0 } || pointer.get(owner) !== document) return null
        if (!isTimedState(owner)) return null
        val root = getView.invoke(owner) as? View ?: return null
        if (!root.isAttachedToWindow || !root.isShown || !root.hasWindowFocus() || hidden.invoke(owner) == true ||
            !recycler.isAttachedToWindow || !recycler.isShown || list.get(binding.get(owner)) !== recycler ||
            scrollState.invoke(recycler) != 0) return null
        val media = browser.invoke(owner) ?: return null
        if (playing.invoke(media) != true) return null
        return adapter.get(owner)?.takeIf { adapterPointer.invoke(it) === document }
    }

    private fun wordAnimationStopped(target: Any): Boolean =
        karaoke.isInstance(target) && !wordAnimationEnabled.getBoolean(target)

    private fun suspendedAdapter(target: Any): Boolean = browsing.invoke(target) == true ||
        wordAnimationStopped(target)

    fun suspended(owner: Any?, recycler: ViewGroup, document: Any?, active: Set<Int>): Boolean =
        runCatching {
            val target = current(owner, recycler, document, active) ?: return@runCatching false
            suspendedAdapter(target) || (owner != null && (isShareSuppressed(owner) || isScrollLocked(owner)))
        }.getOrDefault(false)

    fun recover(owner: Any?, recycler: ViewGroup, document: Any?, active: Set<Int>): Boolean = runCatching {
        val target = current(owner, recycler, document, active) ?: return false
        val host = owner ?: return false
        val wasBrowsing = browsing.invoke(target) == true
        val wordStopped = wordAnimationStopped(target)
        val listener = callback.get(host) ?: return false
        clearNativeSuppress(host)
        unlockScrollManager(host)
        runCatching { setBrowsing?.invoke(target, false) }
        unlock.invoke(target)
        enable.invoke(target, true)
        follow.setBoolean(host, true)
        // Apple's own line callback restores browsing/highlight mode, scroll anchoring and VM focus.
        val mapped = active.map { toAdapterPos(target, it) }
        scroll.invoke(listener, mapped.min(), mapped.max())
        if (wordStopped || (wasBrowsing && setBrowsing == null)) rebind.invoke(target)
        true
    }.onFailure { ModernXposedRuntime.log("Native lyric presentation recovery failed open", it) }.getOrDefault(false)

    companion object {
        fun resolve(fragment: Class<*>): NativeLyricsPresentationRecovery? = runCatching {
            NativeLyricsPresentationRecovery(fragment)
        }.onFailure { ModernXposedRuntime.log("Native lyric presentation contract unavailable", it) }.getOrNull()
    }
}
