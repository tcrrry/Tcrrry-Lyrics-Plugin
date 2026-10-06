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
) {
    private data class Request(val fragment: java.lang.ref.WeakReference<Any>, val root: java.lang.ref.WeakReference<View>, val pointer: Any, val list: java.lang.ref.WeakReference<Any>, val layout: java.lang.ref.WeakReference<Any>, var target: Int = -1, var offset: Int = 0, var started: Boolean = false)
    private var scoped: Request? = null
    private val arrivals = WeakHashMap<Any, Request>()

    fun run(fragment: Any, list: Any, root: View, pointer: Any, nativeCallback: () -> Unit): Boolean {
        val request = Request(java.lang.ref.WeakReference(fragment), java.lang.ref.WeakReference(root), pointer, java.lang.ref.WeakReference(list), java.lang.ref.WeakReference(manager(fragment)))
        scoped = request
        try { nativeCallback() } finally { scoped = null }
        return request.started
    }

    internal fun redirect(param: ModernMethodHook.MethodHookParam) {
        val request = scoped ?: return
        if (param.thisObject !== request.layout.get()) return
        request.target = (param.args[0] as Number).toInt()
        request.offset = (param.args[1] as Number).toInt()
        val list = request.list.get() ?: return
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
            fun type(name: String) = Class.forName(name, false, loader)
            fun field(type: Class<*>, name: String) = type.getDeclaredField(name).apply { isAccessible = true }
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
            val top = scroller.getDeclaredMethod("i", View::class.java, Int::class.javaPrimitiveType)
            val update = action.getDeclaredMethod("b", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, BaseInterpolator::class.java)
            val port = NativeLyricsSmoothReturn(
                manager = { requireNotNull(layoutField.get(it)) }, validPointer = { owner, token -> pointer.get(owner) === token },
                start = { list, target -> start.invoke(list, target) },
                listOfScroller = { scrollList.get(it) }, targetOfScroller = { scrollTarget.getInt(it) },
                topDistance = { owner, row -> (top.invoke(owner, row, -1) as Number).toInt() },
                animate = { owner, delta, command ->
                    val milliseconds = duration.getLong(itemAnimator.get(owner)).coerceAtLeast(1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    update.invoke(command, 0, delta, milliseconds, curve.get(null))
                },
            )
            fun hook(method: Method, call: (ModernMethodHook.MethodHookParam) -> Unit) = ModernXposedRuntime.hookMethod(method, object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    runCatching { call(param) }.onFailure { ModernXposedRuntime.log("Smooth lyrics return hook failed open", it) }
                }
            })
            // Install the arrival adjustment before enabling the redirect.
            check(hook(scroller.getDeclaredMethod("e", View::class.java, state, action), port::arrive))
            check(hook(layout.getDeclaredMethod("u1", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType), port::redirect))
            port
        }.onFailure { ModernXposedRuntime.log("Native smooth lyrics return unavailable", it) }.getOrNull()

    }
}
