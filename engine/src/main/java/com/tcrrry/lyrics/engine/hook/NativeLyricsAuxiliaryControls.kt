package com.tcrrry.lyrics.engine.hook

import android.view.View
import android.content.Context
import android.widget.LinearLayout
import android.widget.PopupWindow
import com.tcrrry.lyrics.plugin.PluginLifetime
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Availability means actual TTML text the plugin can render, not a language match alone. */
internal object NativeLyricsAuxiliaryControls {
    private val nativeRows = WeakHashMap<LinearLayout, List<View>>()
    private val PRONUNCIATION = "tcrrry_auxiliary_pronunciation"
    private val TRANSLATION = "tcrrry_auxiliary_translation"

    internal class State(private val model: Any, val lookup: NativeSubtitleLookup, private val context: Context? = null) {
        private fun live(pronunciation: Boolean, available: Boolean): Any {
            val name = "get${if (pronunciation) "Pronunciation" else "Translation"}${if (available) "Available" else "Selected"}LiveResult"
            return requireNotNull(model.javaClass.getMethod(name).invoke(model))
        }
        fun selected(pronunciation: Boolean): Boolean = live(pronunciation, false).let {
            val native = it.javaClass.getMethod("getValue").invoke(it) == true
            if (pronunciation && context != null) NativeLyricsEmphasisVisibility.effective(context, native) else native
        }
        private fun write(value: Any, selected: Boolean) {
            // The selected result is typed as LiveData, whose setter can be protected.
            val method = generateSequence(value.javaClass as Class<*>?) { it.superclass }
                .mapNotNull { runCatching { it.getDeclaredMethod("setValue", Any::class.java) }.getOrNull() }.first()
            method.isAccessible = true
            method.invoke(value, selected)
        }
        fun select(pronunciation: Boolean, selected: Boolean): Boolean {
            // Use the same native UI setter as Apple's own menu, so the choice survives
            // switching songs and restarting. Never read/write the host preference files.
            val setter = model.javaClass.classLoader!!.loadClass("ja.i0")
                .getDeclaredMethod(if (pronunciation) "m" else "n", Boolean::class.javaPrimitiveType)
            val applyNative = { setter.invoke(null, selected); Unit }
            return if (pronunciation && context != null) NativeLyricsEmphasisVisibility.choose(context, selected, applyNative)
                else { applyNative(); false }
        }
        fun available(pronunciation: Boolean): Boolean =
            (if (pronunciation) lookup.hasPronunciation else lookup.hasTranslation) ||
                live(pronunciation, true).let { it.javaClass.getMethod("getValue").invoke(it) == true }
        fun synchronize() {
            if (context != null) restorePronunciation(context, model)
            for (pronunciation in listOf(false, true)) {
                if (!available(pronunciation)) continue
                val availability = live(pronunciation, true)
                if (availability.javaClass.getMethod("getValue").invoke(availability) != true) write(availability, true)
                // F2 may have set adapter d/e to false even while the user's selected
                // LiveData remains true. Replaying that selection invokes native observers.
                write(live(pronunciation, false), selected(pronunciation))
            }
        }
    }

    internal fun state(page: Any, pointerOverride: Any? = null, nativeMenu: Boolean = false): State? = runCatching {
        val type = page.javaClass
        val pointer = pointerOverride ?: type.declaredFields.single { it.type.name == "com.apple.android.music.ttml.javanative.model.SongInfo\$SongInfoPtr" }
            .apply { isAccessible = true }.get(page)
        val lookup = NativeLyricsPronunciationSubtitle.subtitleLookup(pointer)
            ?: if (nativeMenu) NativeSubtitleLookup("<tt/>") else return null
        val model = type.declaredFields.single { it.type.name == "com.apple.android.music.player.viewmodel.PlayerLyricsViewModel" }
            .apply { isAccessible = true }.get(page) ?: return null
        State(model, lookup, PluginLifetime.context.application)
    }.getOrNull()

    internal fun restorePronunciation(context: Context, model: Any) {
        val desired = NativeLyricsEmphasisVisibility.saved(context) ?: return
        val live = model.javaClass.getMethod("getPronunciationSelectedLiveResult").invoke(model) ?: return
        if (live.javaClass.getMethod("getValue").invoke(live) == desired) return
        // Use the already verified native menu setter; never edit host files.
        model.javaClass.classLoader!!.loadClass("ja.i0")
            .getDeclaredMethod("m", Boolean::class.javaPrimitiveType).invoke(null, desired)
        if (live.javaClass.getMethod("getValue").invoke(live) != desired) {
            // Some native configuration notifications are asynchronous. Bring
            // this page's selection up to date before its first adapter binds.
            val setter = generateSequence(live.javaClass as Class<*>?) { it.superclass }
                .mapNotNull { runCatching { it.getDeclaredMethod("setValue", Any::class.java) }.getOrNull() }.first()
            setter.isAccessible = true
            setter.invoke(live, desired)
        }
    }

    fun synchronize(page: Any) {
        runCatching { state(page)?.synchronize() }.onFailure { ModernXposedRuntime.log("auxiliary availability synchronization failed", it) }
    }

    fun install(loader: ClassLoader) {
        runCatching {
            val fragment = loader.loadClass("com.apple.android.music.player.fragment.PlayerLyricsViewFragment")
            val pointer = loader.loadClass("com.apple.android.music.ttml.javanative.model.SongInfo\$SongInfoPtr")
            // I2 consults these Java predicates while creating an adapter. A replay
            // after F2 alone is too early when no adapter exists yet on first open.
            for ((name, pronunciation) in listOf("t2" to true, "u2" to false)) {
                ModernXposedRuntime.hookMethod(fragment.getDeclaredMethod(name, pointer), object : ModernMethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.throwable != null || param.args[0] == null) return
                        runCatching {
                            val page = param.thisObject ?: return@runCatching
                            val state = state(page, param.args[0]) ?: return@runCatching
                            val usable = if (pronunciation) state.lookup.hasPronunciation else state.lookup.hasTranslation
                            if (usable) param.result = state.selected(pronunciation)
                        }.onFailure { ModernXposedRuntime.log("auxiliary selection predicate failed", it) }
                    }
                })
            }
            ModernXposedRuntime.hookMethod(fragment.getDeclaredMethod("F2", pointer), object : ModernMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null || param.args[0] == null) return
                    runCatching {
                        val page = param.thisObject ?: return@runCatching
                        state(page, param.args[0])?.synchronize()
                    }.onFailure { ModernXposedRuntime.log("auxiliary availability synchronization failed", it) }
                }
            })
        }.onFailure { ModernXposedRuntime.log("auxiliary availability seam unavailable", it) }
    }

    internal fun restoreMenu(menu: LinearLayout) {
        (0 until menu.childCount).map(menu::getChildAt).filter { it.tag in setOf(PRONUNCIATION, TRANSLATION) }.forEach(menu::removeView)
        nativeRows.remove(menu)?.forEachIndexed { index, view ->
            if (view.parent == null) menu.addView(view, index.coerceAtMost(menu.childCount))
        }
    }

    fun extendMenu(page: Any, menu: LinearLayout, popup: PopupWindow, currentId: () -> Long?) {
        // Raw TTML may be unavailable on a native-only page. Still capture the
        // user's explicit native-menu choice; availability stays native-owned.
        val state = state(page, nativeMenu = true) ?: return
        restoreMenu(menu)
        if (menu.childCount < 2) return
        val originals = (0..1).map(menu::getChildAt)
        fun labelSizes(view: View): List<Float> = when (view) {
            is android.widget.TextView -> listOf(view.textSize)
            is android.view.ViewGroup -> (0 until view.childCount).flatMap { labelSizes(view.getChildAt(it)) }
            else -> emptyList()
        }
        val labelSize = originals.flatMap(::labelSizes).maxOrNull()
        nativeRows[menu] = originals
        originals.forEach(menu::removeView)
        val id = currentId()
        val popupRef = WeakReference(popup)
        for ((index, pronunciation) in listOf(true, false).withIndex()) {
            val noun = if (pronunciation) "发音" else "翻译"
            val available = state.available(pronunciation)
            val selected = available && state.selected(pronunciation)
            val row = NativeLyricsMenuRowLayout(menu.context, "${if (selected) "隐藏" else "显示"}$noun", autoSizeLabel = false, labelSizePx = labelSize, intrinsicWidth = true).apply {
                minimumWidth = (176 * resources.displayMetrics.density).toInt()
                tag = if (pronunciation) PRONUNCIATION else TRANSLATION
                isEnabled = available
                alpha = if (available) 1f else .35f
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (!PluginLifetime.alive || !available || id == null || id != currentId()) return@setOnClickListener
                    val refreshEmphasis = state.select(pronunciation, !state.selected(pronunciation))
                    if (refreshEmphasis) CurrentLyricsSourceStatus.refreshSilently(id)
                    popupRef.get()?.dismiss()
                }
            }
            menu.addView(row, index)
        }
    }
}
