package com.tcrrry.lyrics.engine.hook

/** The 1606 page can return before binding its header while native lyrics are unavailable. */
internal class NativeLyricsHeaderRefresh(
    private val bindingOf: (Any) -> Any?,
    private val itemOf: (Any) -> Any?,
    private val track: java.lang.reflect.Method,
    private val context: java.lang.reflect.Method,
    private val execute: java.lang.reflect.Method,
) {
    fun refresh(fragment: Any, contextItem: Any?, itemChanged: Boolean): Boolean {
        if (!itemChanged) return false
        return runCatching {
            val binding = bindingOf(fragment) ?: return false
            val item = itemOf(fragment) ?: return false
            if (!track.parameterTypes.single().isInstance(item)) return false
            track.invoke(binding, item)
            if (contextItem != null && context.parameterTypes.single().isInstance(contextItem)) {
                context.invoke(binding, contextItem)
            }
            execute.invoke(binding)
            true
        }.getOrDefault(false)
    }

    companion object {
        fun resolve(fragment: Class<*>, seam: CurrentItemIdentitySeam): NativeLyricsHeaderRefresh? = runCatching {
            // This contract is verified against the fixed 7.0.0/1606 input only.
            if (fragment.declaredMethods.none { it.name == "w2" }) return null
            val binding = fragment.lyricsDeclaredField("n0").apply { isAccessible = true }
            val methods = binding.type.methods
            val track = methods.single { it.name == "q0" && it.parameterTypes.singleOrNull()?.name == "com.apple.android.music.model.PlaybackItem" }
            val context = methods.single { it.name == "p0" && it.parameterTypes.singleOrNull()?.name == "com.apple.android.music.model.CollectionItemView" }
            val execute = binding.type.getMethod("n")
            NativeLyricsHeaderRefresh(binding::get, seam::currentItemOf, track, context, execute)
        }.getOrNull()
    }
}
