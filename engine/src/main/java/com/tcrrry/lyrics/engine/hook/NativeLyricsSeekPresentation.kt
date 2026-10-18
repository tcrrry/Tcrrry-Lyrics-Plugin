package com.tcrrry.lyrics.engine.hook

import android.util.Pair
import android.util.SparseArray
import java.util.TreeSet
import java.lang.reflect.Proxy
import java.lang.ref.WeakReference
import android.os.SystemClock

/** Submit a seek through Apple's adapter update path, including its ItemAnimator lifecycle.
 * No view alpha/translation or plugin animator: the native adapter owns every visual update.
 */
internal class NativeLyricsSeekPresentation private constructor(private val type: Class<*>) : AutoCloseable {
    private fun field(name: String) = type.lyricsDeclaredField(name).apply { isAccessible = true }
    private val adapter = field("p0")
    private val karaoke = type.classLoader!!.lyricsClass("com.apple.android.music.player.A")
    private val update = adapter.type.getMethod("O", List::class.java, Int::class.javaPrimitiveType,
        java.lang.reflect.Array.newInstance(Pair::class.java, 0).javaClass)
    private val keyType = type.classLoader!!.loadClass(adapter.type.name + "\$a")
    private fun key(name: String) = keyType.getField(name).get(null)
    private val words = key("WORD_HIGHLIGHT_IDS")
    private val background = key("BG_WORD_HIGHLIGHT_IDS")
    private val playback = key("PLAYBACK_POSITION")
    private val animator = runCatching { field("t0") }.getOrNull()
    private val finished = runCatching {
        type.classLoader!!.loadClass("androidx.recyclerview.widget.RecyclerView\$k\$a")
    }.getOrNull()
    private val whenFinished = runCatching { animator?.type?.getMethod("h", finished!!) }.getOrNull()
    private val endAnimations = runCatching { animator?.type?.getMethod("f") }.getOrNull()
    private val cancelGap = runCatching { karaoke.getMethod("v") }.getOrNull()
    private val scope = HookRegistrationScope()
    private var active = WeakReference<Any>(null)
    private var blocked = false
    private val owned = ThreadLocal.withInitial { 0 }
    private var committedAt = Long.MIN_VALUE
    internal var mediaPosition: (() -> Long?)? = null

    init {
        val implementation = runCatching { karaoke.getMethod("O", *update.parameterTypes) }.getOrDefault(update)
        ModernXposedRuntime.hookMethod(implementation, object : ModernMethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (param.thisObject !== active.get() || owned.get() != 0) return
                if (blocked) { param.result = null; return }
                // Old native line callbacks can be posted when an interrupted animation
                // finishes. They carry their original media position; reject a stale epoch.
                if (committedAt == Long.MIN_VALUE || SystemClock.uptimeMillis() - committedAt > 2_000L) return
                val payloads = param.args.getOrNull(2) as? Array<*> ?: return
                val at = payloads.filterIsInstance<Pair<*, *>>().firstOrNull { it.first == playback }?.second as? Number ?: return
                val now = runCatching { mediaPosition?.invoke() }.getOrNull() ?: return
                if (kotlin.math.abs(now - at.toLong()) > 1_500L) param.result = null
            }
        }, scope)
        scope.activate()
    }

    private fun submit(current: Any, ids: List<Int>, payloads: Array<Pair<*, *>> = emptyArray()) {
        owned.set((owned.get() ?: 0) + 1)
        try { update.invoke(current, ids, -1, payloads) } finally { owned.set((owned.get() ?: 1) - 1) }
    }
    fun block(fragment: Any) { active = WeakReference(adapter.get(fragment)); blocked = true }
    fun release() { blocked = false; committedAt = SystemClock.uptimeMillis() }
    fun cancel() { blocked = false; committedAt = Long.MIN_VALUE; active.clear() }

    fun exit(fragment: Any, pointer: Any, position: Long): Boolean {
        val current = adapter.get(fragment) ?: return false
        if (!karaoke.isInstance(current)) return false
        snapshot(pointer, position) // Validate all native data before touching the presentation.
        block(fragment)
        // Finish through Apple's lifecycle so recycled holders cannot keep old transforms.
        // Callbacks released here remain blocked until the new epoch has entered.
        animator?.get(fragment)?.let { endAnimations?.invoke(it) }
        cancelGap?.invoke(current)
        submit(current, emptyList())
        return true
    }

    /** Wait on Apple's actual move/fade animation, never a guessed plugin duration. */
    fun afterNativeTransition(fragment: Any, valid: () -> Boolean, action: () -> Unit) {
        val nativeAnimator = animator?.get(fragment)
        if (nativeAnimator == null || whenFinished == null || finished == null) {
            if (valid()) action()
            return
        }
        var delivered = false
        val listener = Proxy.newProxyInstance(finished.classLoader, arrayOf(finished)) { proxy, method, args ->
            when (method.name) {
                "a" -> {
                    if (!delivered) {
                        delivered = true
                        runCatching { if (valid()) action() }.onFailure {
                            ModernXposedRuntime.log("Native lyric seek completion failed open", it)
                        }
                    }
                    null
                }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "NativeLyricsSeekCompletion"
                else -> null
            }
        }
        whenFinished.invoke(nativeAnimator, listener)
    }

    fun enter(fragment: Any, pointer: Any, position: Long): List<Int>? {
        val current = adapter.get(fragment) ?: return null
        if (!karaoke.isInstance(current)) return null
        // Build completely before changing the adapter; unsupported documents fail open.
        val snapshot = snapshot(pointer, position)
        val foreground = SparseArray<TreeSet<Int>>()
        val backing = SparseArray<TreeSet<Int>>()
        snapshot.forEach {
            foreground.put(it.id, TreeSet(it.completed))
            backing.put(it.id, TreeSet(it.backgroundCompleted))
        }
        // Exit was laid out and animated in a separate frame. Sending both O updates
        // in one frame lets RecyclerView coalesce them and skip the native transition.
        val ids = snapshot.map { it.id }
        if (ids.isNotEmpty()) submit(current, ids, arrayOf(
            Pair(words, foreground), Pair(background, backing), Pair(playback, position)))
        return ids
    }

    override fun close() { cancel(); scope.close(); owned.remove(); mediaPosition = null }

    internal data class Row(val id: Int, val completed: List<Int>, val backgroundCompleted: List<Int>)

    companion object {
        fun resolve(type: Class<*>): NativeLyricsSeekPresentation? = runCatching {
            NativeLyricsSeekPresentation(type)
        }.onFailure { ModernXposedRuntime.log("Native lyric seek presentation unavailable", it) }.getOrNull()

        internal fun snapshot(pointer: Any, position: Long): List<Row> {
            fun entries(vector: Any, limit: Int, consume: (Any) -> Unit) {
                val count = (vector.javaClass.getMethod("size").invoke(vector) as Number).toLong()
                require(count in 0..limit.toLong())
                val get = vector.javaClass.getMethod("get", Long::class.javaPrimitiveType)
                for (index in 0 until count) {
                    val ptr = get.invoke(vector, index) ?: continue
                    ptr.javaClass.getMethod("get").invoke(ptr)?.let(consume)
                }
            }
            fun time(value: Any, method: String) =
                (value.javaClass.getMethod(method).invoke(value) as Number).toLong()
            fun completed(line: Any, getter: String): List<Int> {
                val result = mutableListOf<Int>()
                entries(requireNotNull(line.javaClass.getMethod(getter).invoke(line)), 4096) { word ->
                    if (time(word, "getEnd") <= position && word.javaClass.getMethod("isWhitespace").invoke(word) != true) {
                        val id = time(word, "getWordId").toInt()
                        if (id >= 0) result += id
                    }
                }
                return result.distinct().sorted()
            }
            val song = pointer.javaClass.getMethod("get").invoke(pointer) ?: return emptyList()
            val result = mutableListOf<Row>()
            entries(requireNotNull(song.javaClass.getMethod("getSections").invoke(song)), 512) { section ->
                entries(requireNotNull(section.javaClass.getMethod("getLines").invoke(section)), 4096) { line ->
                    if (time(line, "getBegin") <= position && position < time(line, "getEnd") &&
                        !(line.javaClass.getMethod("getHtmlLineText").invoke(line) as? String).isNullOrBlank()) {
                        result += Row(time(line, "getLineId").toInt(), completed(line, "getWords"),
                            completed(line, "getBackgroundWords"))
                    }
                }
            }
            return result.filter { it.id >= 0 }.distinctBy { it.id }.sortedBy { it.id }
        }
    }
}
