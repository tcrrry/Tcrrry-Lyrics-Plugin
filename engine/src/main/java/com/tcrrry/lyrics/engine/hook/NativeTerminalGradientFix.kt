package com.tcrrry.lyrics.engine.hook

import java.lang.reflect.Modifier
import android.animation.ValueAnimator
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** The last glyph needs the trailing (fully lit) edge of the feather to pass it. */
internal fun terminalGradientMargin(original: Float, feather: Float): Float =
    if (original.isFinite() && feather.isFinite() && feather > 0f && feather <= 1f)
        maxOf(original, feather) else original

internal fun terminalGradientNeedsCompensation(text: CharSequence): Boolean = containsCjkKaraokeScript(text)

internal object NativeTerminalGradientFix {
    private val terminal = ThreadLocal<Boolean>()
    private data class Frame(val adapter: WeakReference<Any>, val row: Int, val word: Int,
        var fraction: Float = 0f, var stage: Int = -1)
    private val frames = WeakHashMap<ValueAnimator, Frame>()
    fun releasing(adapter: Any, row: Int) {
        val iterator = frames.entries.iterator()
        while (iterator.hasNext()) {
            val (animation, frame) = iterator.next()
            if (frame.adapter.get() === adapter && frame.row == row) {
                LyricsPlaybackDiagnostics.record("tail-mask-final", "row=$row word=${frame.word} fraction=${frame.fraction} duration=${animation.duration}")
                iterator.remove()
            }
        }
    }

    fun install(adapter: Class<*>): Boolean = runCatching {
        val build = adapter.declaredMethods.single { it.name == "d0" && it.parameterCount == 9 }
        val margin = adapter.declaredMethods.single { it.name == "c0" && Modifier.isStatic(it.modifiers) && it.parameterCount == 6 }
        val index = adapter.lyricsDeclaredField("p").apply { isAccessible = true }
        val entry = build.parameterTypes[2]
        val text = entry.lyricsDeclaredField("c").apply { isAccessible = true }
        val rowId = entry.lyricsDeclaredField("a").apply { isAccessible = true }
        val wordId = entry.lyricsDeclaredField("b").apply { isAccessible = true }
        val lastByTable = WeakHashMap<Any, MutableMap<Int, Set<Int>>>()
        fun isTerminal(target: Any, word: Any): Boolean {
            val table = index.get(target) ?: return false
            val row = rowId.getInt(word)
            val expected = wordId.getInt(word)
            lastByTable[table]?.get(row)?.let { return expected in it }
            val linePtr = table.javaClass.getMethod("a", Int::class.javaPrimitiveType).invoke(table, row) ?: return false
            val line = linePtr.javaClass.getMethod("get").invoke(linePtr) ?: return false
            val ids = mutableSetOf<Int>()
            // Original and native pronunciation vectors have independent word IDs.
            for (name in listOf("getWords", "getPronunciationWords")) {
                val vector = line.javaClass.getMethod(name).invoke(line) ?: continue
                val count = (vector.javaClass.getMethod("size").invoke(vector) as Number).toInt()
                if (count !in 2..4096) continue
                val get = vector.javaClass.getMethod("get", Long::class.javaPrimitiveType)
                var last: Int? = null
                var latest = -1L
                for (i in 0 until count) {
                    val ptr = get.invoke(vector, i.toLong()) ?: continue
                    val value = ptr.javaClass.getMethod("get").invoke(ptr) ?: continue
                    val begin = (value.javaClass.getMethod("getBegin").invoke(value) as Number).toLong()
                    val end = (value.javaClass.getMethod("getEnd").invoke(value) as Number).toLong()
                    if (begin >= 0 && end > begin && end >= latest) {
                        latest = end
                        last = (value.javaClass.getMethod("getWordId").invoke(value) as Number).toInt()
                    }
                }
                last?.let(ids::add)
            }
            lastByTable.getOrPut(table) { mutableMapOf() }[row] = ids
            return expected in ids
        }
        val listener = lyricsClassForName("com.apple.android.music.player.u", false, adapter.classLoader)
            .getMethod("onAnimationUpdate", ValueAnimator::class.java)
        ModernXposedRuntime.hookMethod(listener, object : ModernMethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val animation = param.args[0] as? ValueAnimator ?: return
                val frame = frames[animation] ?: return
                frame.fraction = animation.animatedFraction
                val stage = if (frame.fraction >= 0.98f) 2 else if (frame.fraction >= 0.5f) 1 else 0
                if (stage > frame.stage) {
                    frame.stage = stage
                    LyricsPlaybackDiagnostics.record("tail-mask-frame", "row=${frame.row} word=${frame.word} fraction=${frame.fraction} duration=${animation.duration}")
                }
            }
        })
        val marginInstalled = ModernXposedRuntime.hookMethod(margin, object : ModernMethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (terminal.get() != true || param.throwable != null) return
                val original = param.result as? Float ?: return
                val feather = param.args[0] as? Float ?: return
                val corrected = terminalGradientMargin(original, feather)
                if (corrected != original) {
                    param.result = corrected
                    LyricsPlaybackDiagnostics.record("tail-gradient", "margin=$original corrected=$corrected feather=$feather")
                }
            }
        })
        val buildInstalled = ModernXposedRuntime.hookMethod(build, object : ModernMethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                param.extras["terminal-gradient-previous"] = terminal.get()
                terminal.set(runCatching {
                    // Full-feather compensation is for the CJK zero-margin
                    // branch only. Latin words/readings keep their own margin
                    // and delayed sweep rather than borrowing a CJK tail rule.
                    param.args[7] != true && terminalGradientNeedsCompensation(text.get(param.args[2]) as CharSequence) &&
                        isTerminal(param.thisObject!!, param.args[2]!!)
                }.getOrDefault(false))
            }
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    if (terminal.get() == true && param.throwable == null) {
                        // Geometry now finishes in the timed sweep. A delayed 500 ms
                        // follow-up must not paint back over its completed mask.
                        (param.result as? MutableList<*>)?.removeAll { value ->
                            value != null && runCatching {
                                value.javaClass.getMethod("getTag").invoke(value) == "KARAOKE_SPLIT_ON_CHARS_LANGUAGES_RUSH_GRADIENT_TAG"
                            }.getOrDefault(false)
                        }
                        val word = param.args[2]!!
                        val scale = if (android.os.Build.VERSION.SDK_INT >= 33) ValueAnimator.getDurationScale() else null
                        (param.result as? List<*>)?.filterIsInstance<ValueAnimator>()?.forEach { animation ->
                            frames[animation] = Frame(WeakReference(param.thisObject!!), rowId.getInt(word), wordId.getInt(word))
                            LyricsPlaybackDiagnostics.record("tail-sweep", "row=${rowId.getInt(word)} word=${wordId.getInt(word)} duration=${animation.duration} delay=${animation.startDelay} scale=$scale")
                        }
                    }
                } finally {
                    val previous = param.extras.remove("terminal-gradient-previous") as? Boolean
                    if (previous == null) terminal.remove() else terminal.set(previous)
                }
            }
        })
        marginInstalled && buildInstalled
    }.onFailure { ModernXposedRuntime.log("Terminal gradient seam unavailable; kept native timing", it) }.getOrDefault(false)
}
