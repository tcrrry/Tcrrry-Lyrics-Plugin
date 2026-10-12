package com.tcrrry.lyrics.engine.hook

import java.lang.ref.WeakReference
import java.util.WeakHashMap

// Match the native word sweep's 100ms lead instead of the adaptive 480..750ms scroll lead.
internal const val WORD_LINE_ANTICIPATION_MS = -100

/** Bound word-timed anticipation while preserving native line-only preferences. */
internal class NativeLineTimingPolicy {
    var wordTimed = false
        private set
    private var requested = -500
    fun request(offset: Int): Int {
        requested = offset
        return if (wordTimed) maxOf(WORD_LINE_ANTICIPATION_MS, offset) else offset
    }
    fun document(hasWordTiming: Boolean, current: Int): Int {
        val previous = wordTimed
        wordTimed = hasWordTiming
        return when {
            wordTimed -> maxOf(WORD_LINE_ANTICIPATION_MS, current)
            previous -> requested
            else -> current
        }
    }
}

internal object NativeLyricsLineTiming {
    private data class State(val policy: NativeLineTimingPolicy = NativeLineTimingPolicy(),
        var document: WeakReference<Any> = WeakReference(null))
    private val states = WeakHashMap<Any, State>()
    private var installed = false
    private val applying = ThreadLocal<Boolean>()

    fun install(loader: ClassLoader) = runCatching {
        val native = lyricsClassForName("com.apple.android.music.ttml.javanative.SongInfoTimeProcessorJavaCpp\$SongInfoTimeProcessorNative", false, loader)
        val setter = native.getMethod("suggestLineOffset", Int::class.javaPrimitiveType)
        val setterInstalled = ModernXposedRuntime.hookMethod(setter, object : ModernMethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!installed || applying.get() == true) return
                val target = param.thisObject ?: return
                val requested = param.args[0] as? Int ?: return
                val effective = states.getOrPut(target) { State() }.policy.request(requested)
                if (effective != requested) {
                    param.args[0] = effective
                    LyricsPlaybackDiagnostics.record("line-timing", "requested=$requested effective=$effective timing=Word")
                }
            }
        })
        val fragment = lyricsClassForName("com.apple.android.music.player.fragment.PlayerLyricsViewFragment", false, loader)
        val owner = fragment.lyricsDeclaredField("b1").apply { isAccessible = true }
        val processor = owner.type.getMethod("a")
        val adjust = fragment.getDeclaredMethod("d2", fragment, Int::class.javaPrimitiveType)
        val adjustInstalled = ModernXposedRuntime.hookMethod(adjust, object : ModernMethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!installed) return
                runCatching {
                    val target = owner.get(param.args[0])?.let { processor.invoke(it) } ?: return
                    val state = states[target] ?: return
                    if (!state.policy.wordTimed) return
                    val requested = param.args[1] as Int
                    val effective = state.policy.request(requested)
                    val current = native.getMethod("getSuggestedLineOffset").invoke(target) as Int
                    if (effective == current) {
                        // f.invoke compares its adaptive 480..750ms lead with our 100ms cap.
                        // Avoid a redundant d2 -> y2 -> process re-entry on every line.
                        param.result = null
                        LyricsPlaybackDiagnostics.record("line-timing", "requested=$requested effective=$effective adaptiveSkipped=true")
                    }
                }.onFailure { LyricsPlaybackDiagnostics.record("line-timing-error", it.javaClass.simpleName) }
            }
        })
        installed = setterInstalled && adjustInstalled
    }.onFailure { ModernXposedRuntime.log("Word-timed line handoff unavailable; kept native anticipation", it) }

    /** Called before processing; apply to the first frame and to every seek/document change. */
    fun prepare(processor: Any, pointer: Any) {
        if (!installed) return
        runCatching {
            val state = states.getOrPut(processor) { State() }
            val getter = processor.javaClass.getMethod("getSuggestedLineOffset")
            val current = getter.invoke(processor) as Int
            val changed = state.document.get() !== pointer
            val effective = if (changed) {
                val song = pointer.javaClass.getMethod("get").invoke(pointer) ?: return
                // Exact 1606 enum contract: None=0, Line=1, Word=2.
                val timing = (song.javaClass.getMethod("getTiming").invoke(song) as Number).toLong()
                state.policy.document(timing == 2L, current).also {
                    state.document = WeakReference(pointer)
                }
            } else if (state.policy.wordTimed) maxOf(WORD_LINE_ANTICIPATION_MS, current) else current
            if (effective != current) {
                // Bypass our request observer: this is a policy application, not a new
                // native preference (needed to restore anticipation on a line-only song).
                applying.set(true)
                try {
                    processor.javaClass.getMethod("suggestLineOffset", Int::class.javaPrimitiveType)
                        .invoke(processor, effective)
                } finally { applying.remove() }
            }
            if (changed || effective != current) LyricsPlaybackDiagnostics.record("line-timing", "doc=${LyricsPlaybackDiagnostics.identity(pointer)} requested=$current effective=$effective wordTimed=${state.policy.wordTimed}")
        }.onFailure { LyricsPlaybackDiagnostics.record("line-timing-error", it.javaClass.simpleName) }
    }
}
