package com.tcrrry.lyrics.engine.hook

import android.view.View

/** 1606 w2 assigns h1 before deciding between the static and timed adapters.
 * A prepared pointer therefore does not, on its own, prove the timed page is visible.
 * Callers must additionally establish that this is their exact current replacement.
 */
internal class NativeTimedLyricsPage private constructor(private val type: Class<*>) {
    private fun field(name: String) = type.lyricsDeclaredField(name).apply { isAccessible = true }
    private val pointer = field("h1")
    private val adapter = field("p0")
    private val adapterPointer = adapter.type.getMethod("y")
    private val binding = field("n0")
    private val recycler = binding.type.lyricsDeclaredField("f0").apply { isAccessible = true }
    private val loading = binding.type.lyricsDeclaredField("e0").apply { isAccessible = true }
    private val getAdapter = recycler.type.getMethod("getAdapter")
    private val getView = type.getMethod("getView")
    private val hidden = type.getMethod("isHidden")
    private val timed = field("E0")
    private val state = field("G0")
    private val value = state.type.lyricsDeclaredField("a").apply { isAccessible = true }
    private val notifyChange = state.type.getMethod("notifyChange")
    private val render = type.getDeclaredMethod("B2", pointer.type, Boolean::class.javaPrimitiveType)
    private val auxiliary = type.getDeclaredMethod("F2", pointer.type)
    // 1606 o2 removes the delayed loading message and hides binding.e0.
    private val finishLoading = type.getDeclaredMethod("o2")
    private val repairing = ThreadLocal.withInitial { false }
    internal val isRepairing: Boolean get() = repairing.get() == true
    internal val bindingMethod get() = render

    /** Never clear a pointer, replace a player item, or change playback/catalog identity. */
    fun repair(fragment: Any, id: Long, replacement: Any): Boolean {
        if (isRepairing) return false
        if (id <= 0L || pointer.get(fragment) !== replacement) return false
        val root = getView.invoke(fragment) as? View ?: return false
        if (!root.isAttachedToWindow || !root.isShown || hidden.invoke(fragment) == true) return false
        val native = replacement.javaClass.getMethod("get").invoke(replacement) ?: return false
        if ((native.javaClass.getMethod("getAdamId").invoke(native) as? Number)?.toLong() != id) return false
        val timing = native.javaClass.getMethod("getAvailableTiming").invoke(native)?.toString()
        if (timing !in setOf("Line", "Word")) return false
        val bound = binding.get(fragment) ?: return false
        val list = recycler.get(bound) ?: return false
        val observable = state.get(fragment) ?: return false
        val expected = timed.get(fragment) ?: return false
        val currentAdapter = adapter.get(fragment)
        val needsLayout = currentAdapter == null || value.get(observable) !== expected ||
            getAdapter.invoke(list) !== currentAdapter || adapterPointer.invoke(currentAdapter) !== replacement
        val wasLoading = (loading.get(bound) as? View)?.visibility == View.VISIBLE
        // Mirror w2's timed branch, using the already-installed, verified pointer.
        // B2's boolean selects the static adapter; no host preference/model is edited.
        repairing.set(true)
        try {
            if (needsLayout) {
                render.invoke(fragment, replacement, false)
                value.set(observable, expected)
                notifyChange.invoke(observable)
                auxiliary.invoke(fragment, replacement)
            }
            // Also cancel a pending show: the frame can still be hidden at this moment.
            // An already correct adapter must not be rebound merely to finish loading.
            finishLoading.invoke(fragment)
        } finally {
            repairing.remove()
        }
        return needsLayout || wasLoading
    }

    companion object {
        fun resolve(type: Class<*>, installName: String): NativeTimedLyricsPage? =
            if (installName == "w2" && type.name == "com.apple.android.music.player.fragment.PlayerLyricsViewFragment")
                runCatching { NativeTimedLyricsPage(type) }.onFailure {
                    ModernXposedRuntime.log("Timed lyrics page recovery unavailable", it)
                }.getOrNull() else null
    }
}
