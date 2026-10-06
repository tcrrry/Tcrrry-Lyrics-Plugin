package com.tcrrry.lyrics.engine.hook

import android.content.res.ColorStateList
import android.view.View
import android.widget.TextView
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Line lyrics have three bound views. Preserve binding identities and change only anchors. */
internal object NativeLineLyricsPresentation {
    private val names = listOf("topToTop", "topToBottom", "bottomToTop", "bottomToBottom")
    private data class Style(val colors: ColorStateList, val alpha: Float)
    private data class Saved(val view: WeakReference<TextView>, val anchors: List<Int>, val style: Style?)
    private val rows = WeakHashMap<View, List<Saved>>()

    internal fun clear(root: View) {
        for (saved in rows.remove(root).orEmpty()) {
            val view = saved.view.get() ?: continue
            // Primary and translation styles belong to native binding/animation.
            // Restoring their pre-bind alpha (often 1) makes translation turn white.
            saved.style?.let { style ->
                NativeLyricsPronunciationSubtitle.releaseAuxiliary(view)
                view.setTextColor(style.colors)
                view.alpha = style.alpha
            }
            val params = view.layoutParams
            names.zip(saved.anchors).forEach { (name, value) -> PronunciationHeaderLayout.field(params.javaClass, name).setInt(params, value) }
            view.layoutParams = params
        }
    }

    internal fun apply(root: View, original: TextView, pronunciation: TextView, translation: TextView, primaryPronunciation: Boolean = false) {
        check(original.parent === pronunciation.parent && original.parent === translation.parent)
        if (!rows.containsKey(root)) {
            rows[root] = listOf(original, pronunciation, translation).map { view ->
                Saved(WeakReference(view), names.map { PronunciationHeaderLayout.field(view.layoutParams.javaClass, it).getInt(view.layoutParams) },
                    if (view === pronunciation) Style(view.textColors, view.alpha) else null)
            }
        }
        fun anchors(view: TextView, values: List<Int>) {
            val params = view.layoutParams
            names.zip(values).forEach { (name, value) -> PronunciationHeaderLayout.field(params.javaClass, name).setInt(params, value) }
            view.layoutParams = params
        }
        val oldTop = rows.getValue(root).first().anchors
        val oldBottom = rows.getValue(root).last().anchors
        // The auxiliary binding contains the original in phonetic emphasis mode.
        // Keep it above the primary binding in either mode, then translation below.
        anchors(pronunciation, listOf(oldTop[0], oldTop[1], original.id, -1))
        anchors(original, listOf(-1, pronunciation.id, translation.id, -1))
        anchors(translation, listOf(-1, original.id, oldBottom[2], oldBottom[3]))
        NativeLyricsPronunciationSubtitle.stylePronunciation(pronunciation)
    }

    fun install(loader: ClassLoader) {
        runCatching {
            val adapter = loader.loadClass("com.apple.android.music.player.Y0")
            val base = loader.loadClass("com.apple.android.music.player.n1")
            val holder = loader.loadClass("androidx.recyclerview.widget.RecyclerView\$D")
            val nativeHolder = loader.loadClass("com.apple.android.music.player.n1\$b")
            val row = holder.getDeclaredField("a")
            val binding = nativeHolder.getDeclaredField("u").apply { isAccessible = true }
            val nativeBinding = loader.loadClass("q8.l9")
            val original = nativeBinding.getField("b0")
            val pronunciation = nativeBinding.getField("Z")
            val translation = nativeBinding.getField("a0")
            val pointer = adapter.getMethod("y")
            val type = adapter.getMethod("f", Int::class.javaPrimitiveType)
            val full = adapter.getDeclaredMethod("k", holder, Int::class.javaPrimitiveType)
            val partial = adapter.getDeclaredMethod("l", holder, Int::class.javaPrimitiveType, List::class.java)
            var enabled = false
            fun owns(owner: Any?): Boolean = enabled && owner != null && NativeLyricsPronunciationSubtitle.isManagedLine(pointer.invoke(owner))
            fun update(param: ModernMethodHook.MethodHookParam) {
                val item = param.args[0] ?: return
                val root = row.get(item) as View
                if (!owns(param.thisObject) || type.invoke(param.thisObject, param.args[1]) != 0) { clear(root); return }
                val views = binding.get(item) ?: return
                if (!nativeBinding.isInstance(views)) { clear(root); return }
                apply(root, original.get(views) as TextView, pronunciation.get(views) as TextView, translation.get(views) as TextView,
                    NativeLyricsPronunciationSubtitle.isPronunciationPrimary(pointer.invoke(param.thisObject)))
            }
            ModernXposedRuntime.hookMethod(base.getDeclaredMethod("B"), object : ModernMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable == null && adapter.isInstance(param.thisObject) && owns(param.thisObject)) param.result = true
                }
            })
            val hook = object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    runCatching { update(param) }.onFailure { ModernXposedRuntime.log("line auxiliary preparation failed open", it) }
                }
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable == null) runCatching { update(param) }
                        .onFailure { ModernXposedRuntime.log("line auxiliary presentation failed open", it) }
                }
            }
            ModernXposedRuntime.hookMethod(full, hook)
            ModernXposedRuntime.hookMethod(partial, hook)
            enabled = true
            ModernXposedRuntime.log("managed line pronunciation order and brightness installed")
        }.onFailure { ModernXposedRuntime.log("line auxiliary presentation unavailable; kept native layout", it) }
    }
}
