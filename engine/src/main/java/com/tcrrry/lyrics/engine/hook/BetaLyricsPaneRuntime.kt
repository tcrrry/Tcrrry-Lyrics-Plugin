package com.tcrrry.lyrics.engine.hook

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.tcrrry.lyrics.engine.ModuleConstants
import java.lang.ref.WeakReference
import java.lang.reflect.Proxy
import java.util.WeakHashMap
import kotlin.math.abs

/** Scoped to the independent right fragment. Phone/fullscreen lyrics keep their native controls. */
internal class BetaLyricsPaneRuntime(private val lyricsClass: Class<*>, private val fields: LyricsLayoutFieldProfile,
    private val karaoke: FragmentKaraokeWidthContract) {
    private val sessions = WeakHashMap<Any, Session>()
    private val create = lyricsClass.getDeclaredMethod("onCreateView", LayoutInflater::class.java, ViewGroup::class.java, Bundle::class.java)
    private val resume = lyricsClass.getDeclaredMethod("onResume")
    private val destroy = lyricsClass.getDeclaredMethod("onDestroyView")
    private val metrics = lyricsClass.getDeclaredMethod("W1")
    private val chrome = checkNotNull(lyricsClass.superclass).getDeclaredMethod("N1", Int::class.javaPrimitiveType, IntArray::class.java)

    fun validate() {
        check(lyricsClass.name == "com.apple.android.music.player.fragment.PlayerLyricsViewFragment")
        check(metrics.returnType == Boolean::class.javaPrimitiveType)
        val binding = checkNotNull(dualPaneField(lyricsClass, fields.binding)).type
        check(binding.name == "q8.Y4")
        listOf(fields.container, fields.recycler, fields.gradients, "a0", "k0").forEach { checkNotNull(dualPaneField(binding, it)) }
        fields.synchronizedMetrics.forEach { field ->
            val metricClass = checkNotNull(dualPaneField(lyricsClass, field)).type
            listOf("a", "b", "c").forEach { check(dualPaneField(metricClass, it)?.type == Int::class.javaPrimitiveType) }
        }
        val recycler = checkNotNull(dualPaneField(binding, fields.recycler)).type
        val listener = Class.forName("androidx.recyclerview.widget.RecyclerView\$p", false, recycler.classLoader)
        recycler.getDeclaredMethod("h", listener)
        check(listener.isInterface)
        checkNotNull(dualPaneField(recycler, "k0"))
    }

    fun install(registration: HookRegistrationScope) {
        fun hook(method: java.lang.reflect.Method, callback: ModernMethodHook) {
            check(ModernXposedRuntime.hookMethod(method, callback, registration))
        }
        hook(create, object : ModernMethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.throwable != null) return
                val fragment = param.thisObject ?: return
                val root = param.result as? View ?: return
                if (!dedicated(fragment)) return
                guarded { sessions.remove(fragment)?.close(); sessions[fragment] = Session(fragment, root).also { it.start() } }
            }
        })
        hook(resume, object : ModernMethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                param.thisObject?.let { fragment -> guarded { sessions[fragment]?.schedule() } }
            }
        })
        hook(karaoke.bind, object : ModernMethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val adapter = param.thisObject ?: return
                if (karaoke.initialized(adapter)) return
                val row = param.args.firstOrNull() ?: return
                sessions.values.firstOrNull { it.owns(adapter) }?.let { session -> guarded { session.prepareRowWidth(adapter, row) } }
            }
        })
        hook(metrics, object : ModernMethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.throwable != null || param.result != true) return
                param.thisObject?.let { fragment -> guarded { sessions[fragment]?.correctMetrics() } }
            }
        })
        hook(chrome, object : ModernMethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val fragment = param.thisObject ?: return
                if (!lyricsClass.isInstance(fragment) || !dedicated(fragment)) return
                val root = runCatching { ModernXposedRuntime.callMethod(fragment, "getView") as? View }.getOrNull() ?: return
                if (!TabletModeQualifier.isEligible(root.context)) return
                hideChrome(root)
                param.result = null
            }
        })
        hook(destroy, object : ModernMethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                param.thisObject?.let { fragment -> sessions.remove(fragment)?.close() }
            }
        })
        registration.onClose { sessions.values.toList().forEach(Session::close); sessions.clear() }
    }

    private fun dedicated(fragment: Any): Boolean = runCatching {
        ModernXposedRuntime.callMethod(fragment, "getId") == FragmentDualPanePolicy.RIGHT_HOST_ID
    }.getOrDefault(false)

    private fun hideChrome(root: View) {
        listOf("current_player_item", "controls", "controls_tap_target").forEach { name ->
            val id = root.resources.getIdentifier(name, "id", ModuleConstants.TARGET_PACKAGE)
            if (id != 0) root.findViewById<View>(id)?.visibility = View.GONE
        }
    }

    private inner class Session(fragment: Any, root: View) : AutoCloseable {
        private val fragmentRef = WeakReference(fragment)
        private val rootRef = WeakReference(root)
        private val binding = checkNotNull(dualPaneField(fragment.javaClass, fields.binding)?.get(fragment))
        private val container = checkNotNull(dualPaneField(binding.javaClass, fields.container)?.get(binding) as? View)
        private val recycler = checkNotNull(dualPaneField(binding.javaClass, fields.recycler)?.get(binding) as? ViewGroup)
        private val gradients = checkNotNull(dualPaneField(binding.javaClass, fields.gradients)?.get(binding) as? View)
        private var closed = false
        private var posted = false
        private var listener: Any? = null
        private val layout = View.OnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) schedule()
        }
        private val rowLayout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyRows() }
        private val update = Runnable { posted = false; guarded { refresh() } }

        fun start() {
            val root = rootRef.get() ?: return
            if (TabletModeQualifier.isEligible(root.context)) RightLyricsPaneLayout.apply(root)
            container.addOnLayoutChangeListener(layout)
            recycler.addOnLayoutChangeListener(rowLayout)
            val listenerClass = Class.forName("androidx.recyclerview.widget.RecyclerView\$p", false, recycler.javaClass.classLoader)
            val proxy = Proxy.newProxyInstance(listenerClass.classLoader, arrayOf(listenerClass)) { proxy, method, args ->
                when (method.name) {
                    // 1606 p.d(View) is attach; p.b(View) is detach.
                    "d" -> { (args?.firstOrNull() as? View)?.let(::applyRow); null }
                    "equals" -> proxy === args?.firstOrNull()
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "AM++1606LyricRows"
                    else -> null
                }
            }
            ModernXposedRuntime.callMethod(recycler, "h", proxy)
            listener = proxy
            schedule()
        }

        fun schedule() {
            if (closed || posted) return
            posted = true
            if (!container.post(update)) posted = false
        }

        fun refresh() {
            if (closed || !TabletModeQualifier.isEligible(container.context)) return
            rootRef.get()?.let(::hideChrome)
            applyRows()
            fragmentRef.get()?.let { metrics.isAccessible = true; metrics.invoke(it) }
            RightLyricsPaneLayout.reapplyVerticalGradientEdges(gradients)
        }

        fun owns(adapter: Any): Boolean = !closed && fragmentRef.get()?.let { karaoke.owns(it, adapter) } == true
        fun prepareRowWidth(adapter: Any, row: Any) {
            // A saved right fragment can bind before portrait restoration removes it.
            // Its native width contract must hold until onDestroyView, in either orientation.
            if (closed) return
            karaoke.initialize(adapter, row, recycler.width)?.let { width ->
                ModernXposedRuntime.log("1606 right lyrics: initialized cached-row karaoke width=$width viewport=${recycler.width}")
            }
        }

        fun correctMetrics() {
            if (closed || !TabletModeQualifier.isEligible(container.context) || container.height <= 0) return
            val fragment = fragmentRef.get() ?: return
            val controls = runCatching { ModernXposedRuntime.callMethod(fragment, "S1") as? View }.getOrNull()
            val sync = checkNotNull(dualPaneField(fragment.javaClass, fields.synchronizedMetrics.first())?.get(fragment))
            val offset = checkNotNull(dualPaneField(sync.javaClass, "a"))
            offset.setInt(sync, TabletLyricAnchorPolicy.highlightOffset(offset.getInt(sync), container.height))
            fields.synchronizedMetrics.forEach { name ->
                val bounds = checkNotNull(dualPaneField(fragment.javaClass, name)?.get(fragment))
                val end = checkNotNull(dualPaneField(bounds.javaClass, "c"))
                end.setInt(bounds, (end.getInt(bounds) - (controls?.height ?: 0)).coerceAtLeast(0))
            }
            ModernXposedRuntime.callMethod(recycler, "S")
            RightLyricsPaneLayout.reapplyVerticalGradientEdges(gradients)
        }

        private fun applyRow(row: View) {
            if (closed || !TabletModeQualifier.isEligible(row.context)) return
            val primaryIds = listOf("song_lyrics_line", "song_lyrics_word").map {
                row.resources.getIdentifier(it, "id", ModuleConstants.TARGET_PACKAGE)
            }.filter { it != 0 }.toSet()
            val display = row.resources.displayMetrics
            val sizeSp = TabletLyricVisualPolicy.textSizeSp(display.widthPixels.toFloat(), display.heightPixels.toFloat(),
                display.density * row.resources.configuration.fontScale)
            val sizePx = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, sizeSp, display)
            fun visit(view: View) {
                if (view is TextView && view.id in primaryIds && abs(view.textSize - sizePx) > 0.5f) {
                    view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sizeSp)
                }
                (view as? ViewGroup)?.let { group -> repeat(group.childCount) { visit(group.getChildAt(it)) } }
            }
            visit(row)
            if (row.getTag(com.tcrrry.lyrics.engine.R.id.am_enhancer_lyric_spacing_applied) != true) {
                (row.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
                    it.bottomMargin += (TabletLyricVisualPolicy.ITEM_SPACING_EXTRA_DP * row.resources.displayMetrics.density + 0.5f).toInt()
                    row.layoutParams = it
                    row.setTag(com.tcrrry.lyrics.engine.R.id.am_enhancer_lyric_spacing_applied, true)
                }
            }
        }

        private fun applyRows() {
            if (closed) return
            repeat(recycler.childCount) { applyRow(recycler.getChildAt(it)) }
        }

        override fun close() {
            closed = true
            container.removeCallbacks(update)
            container.removeOnLayoutChangeListener(layout)
            recycler.removeOnLayoutChangeListener(rowLayout)
            // No public remove method survives R8 in 1606; remove only our identity from its native list.
            (dualPaneField(recycler.javaClass, "k0")?.get(recycler) as? MutableList<*>)?.remove(listener)
            listener = null
        }
    }

    private inline fun guarded(action: () -> Unit) {
        runCatching(action).onFailure { ModernXposedRuntime.log("1606 right lyrics layout failed", it) }
    }
}
