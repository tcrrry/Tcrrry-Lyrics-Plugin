package com.tcrrry.lyrics.engine.hook

import android.view.View
import android.view.animation.BaseInterpolator
import java.lang.reflect.Method
import java.util.WeakHashMap

/** Redirect only our idle-return call; ordinary Apple line transitions remain untouched. */
internal class NativeLyricsSmoothReturn constructor(
    private val manager: (Any) -> Any,
    private val validPointer: (Any, Any) -> Boolean,
    private val start: (Any, Int) -> Unit,
    private val listOfScroller: (Any) -> Any?,
    private val targetOfScroller: (Any) -> Int,
    private val topDistance: (Any, View) -> Int,
    private val animate: (Any, Int, Any) -> Unit,
    private val compactReturn: ((Any, Any, View, Any, Int, Int) -> Boolean)? = null,
    private val cancelCompact: (Any) -> Unit = {},
) {
    private data class Request(val fragment: java.lang.ref.WeakReference<Any>, val root: java.lang.ref.WeakReference<View>, val pointer: Any, val list: java.lang.ref.WeakReference<Any>, val layout: java.lang.ref.WeakReference<Any>, var target: Int = -1, var offset: Int = 0, var started: Boolean = false)
    private var scoped: Request? = null
    private val arrivals = WeakHashMap<Any, Request>()
    private val held = WeakHashMap<Any, Pair<java.lang.ref.WeakReference<Any>, Any>>()

    fun hold(fragment: Any, pointer: Any) { held[manager(fragment)] = java.lang.ref.WeakReference(fragment) to pointer }
    fun release(fragment: Any) { held.remove(manager(fragment)); cancelCompact(fragment) }

    fun run(fragment: Any, list: Any, root: View, pointer: Any, nativeCallback: () -> Unit): Boolean {
        val request = Request(java.lang.ref.WeakReference(fragment), java.lang.ref.WeakReference(root), pointer, java.lang.ref.WeakReference(list), java.lang.ref.WeakReference(manager(fragment)))
        scoped = request
        try { nativeCallback() } finally { scoped = null }
        return request.started
    }

    internal fun redirect(param: ModernMethodHook.MethodHookParam) {
        val request = scoped ?: run {
            val hold = held[param.thisObject] ?: return
            val owner = hold.first.get()
            if (owner != null && validPointer(owner, hold.second)) param.result = null
            else held.remove(param.thisObject)
            return
        }
        if (param.thisObject !== request.layout.get()) return
        request.target = (param.args[0] as Number).toInt()
        request.offset = (param.args[1] as Number).toInt()
        val list = request.list.get() ?: return
        compactReturn?.let { compact ->
            val owner = request.fragment.get() ?: return
            val root = request.root.get() ?: return
            if (compact(owner, list, root, request.pointer, request.target, request.offset)) {
                request.started = true
                param.result = null
                return
            }
        }
        arrivals[list] = request
        try {
            start(list, request.target)
            request.started = true
            param.result = null // Do not execute u1 first: it would teleport before the animation.
            LyricsPlaybackDiagnostics.record("follow-smooth-start", "target=${request.target} offset=${request.offset}")
        } catch (error: Throwable) {
            arrivals.remove(list)
            ModernXposedRuntime.log("Native smooth return failed open", error)
        }
    }

    internal fun arrive(param: ModernMethodHook.MethodHookParam) {
        val scroller = param.thisObject ?: return
        val list = listOfScroller(scroller) ?: return
        val request = arrivals[list] ?: return
        if (targetOfScroller(scroller) != request.target) { arrivals.remove(list); return }
        arrivals.remove(list)
        val fragment = request.fragment.get() ?: return
        val root = request.root.get() ?: return
        if (!root.isAttachedToWindow || !root.isShown || !validPointer(fragment, request.pointer)) return
        val target = param.args[0] as? View ?: return
        val action = param.args[2] ?: return
        // Native i(view, SNAP_TO_START) includes decoration, margins and list padding.
        val delta = -topDistance(scroller, target) - request.offset
        animate(fragment, delta, action)
        param.result = null
        LyricsPlaybackDiagnostics.record("follow-smooth-arrive", "target=${request.target} delta=$delta offset=${request.offset}")
    }

    companion object {
        fun resolve(fragment: Class<*>): NativeLyricsSmoothReturn? = runCatching {
            val loader = fragment.classLoader
            fun type(name: String) = lyricsClassForName(name, false, loader)
            fun field(type: Class<*>, name: String) = type.lyricsDeclaredField(name).apply { isAccessible = true }
            val recycler = type("androidx.recyclerview.widget.RecyclerView")
            val layout = type("androidx.recyclerview.widget.LinearLayoutManager")
            val scroller = type("androidx.recyclerview.widget.t")
            val base = scroller.superclass!!
            val action = type("androidx.recyclerview.widget.RecyclerView\$y\$a")
            val state = type("androidx.recyclerview.widget.RecyclerView\$z")
            val layoutField = field(fragment, "s0")
            val pointer = field(fragment, "h1")
            val itemAnimator = field(fragment, "t0")
            val duration = field(type("androidx.recyclerview.widget.RecyclerView\$k"), "e")
            val curve = field(type("lc.A"), "w")
            val scrollList = field(base, "b")
            val scrollTarget = field(base, "a")
            val start = recycler.getDeclaredMethod("u0", Int::class.javaPrimitiveType)
            val jump = layout.getDeclaredMethod("u1", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            val top = scroller.getDeclaredMethod("i", View::class.java, Int::class.javaPrimitiveType)
            val update = action.getDeclaredMethod("b", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, BaseInterpolator::class.java)
            val transition = LyricReturnTransition()
            val transitionLists = WeakHashMap<Any, java.lang.ref.WeakReference<View>>()
            var placing = false
            val port = NativeLyricsSmoothReturn(
                manager = { requireNotNull(layoutField.get(it)) }, validPointer = { owner, token -> pointer.get(owner) === token },
                start = { list, target -> start.invoke(list, target) },
                listOfScroller = { scrollList.get(it) }, targetOfScroller = { scrollTarget.getInt(it) },
                topDistance = { owner, row -> (top.invoke(owner, row, -1) as Number).toInt() },
                animate = { owner, delta, command ->
                    val milliseconds = duration.getLong(itemAnimator.get(owner)).coerceAtLeast(1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    update.invoke(command, 0, delta, milliseconds, curve.get(null))
                },
                compactReturn = { owner, list, root, token, target, offset ->
                    val visual = list as? View
                    if (visual == null) false else {
                        transitionLists[owner] = java.lang.ref.WeakReference(visual)
                        val ownerRef = java.lang.ref.WeakReference(owner)
                        val rootRef = java.lang.ref.WeakReference(root)
                        transition.run(visual, valid = {
                            val current = ownerRef.get()
                            com.tcrrry.lyrics.plugin.PluginLifetime.alive && current != null &&
                                pointer.get(current) === token && rootRef.get()?.isAttachedToWindow == true
                        }) {
                            placing = true
                            try { jump.invoke(layoutField.get(owner), target, offset) } finally { placing = false }
                        }
                    }
                },
                cancelCompact = { owner -> transitionLists.remove(owner)?.get()?.let(transition::cancel) },
            )
            com.tcrrry.lyrics.plugin.PluginLifetime.close { transition.close(); transitionLists.clear() }
            fun hook(method: Method, call: (ModernMethodHook.MethodHookParam) -> Unit) = ModernXposedRuntime.hookMethod(method, object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    runCatching { call(param) }.onFailure { ModernXposedRuntime.log("Smooth lyrics return hook failed open", it) }
                }
            })
            // Install the arrival adjustment before enabling the redirect.
            check(hook(scroller.getDeclaredMethod("e", View::class.java, state, action), port::arrive))
            check(hook(jump) { if (!placing) port.redirect(it) })
            port
        }.onFailure { ModernXposedRuntime.log("Native smooth lyrics return unavailable", it) }.getOrNull()

    }
}
