package com.tcrrry.lyrics.engine.hook

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import org.xmlpull.v1.XmlPullParser
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

internal object LayoutInflationRegistry {
    private val callbacks = ConcurrentHashMap<String, MutableList<(View) -> Unit>>()
    private val dispatched = Collections.synchronizedMap(WeakHashMap<View, MutableSet<String>>())

    fun register(layoutName: String, callback: (View) -> Unit) {
        callbacks.computeIfAbsent(layoutName) { mutableListOf() }.add(callback)
    }

    fun install() {
        installHook(LayoutInflater::class.java.getDeclaredMethod(
            "inflate",
            Int::class.javaPrimitiveType,
            ViewGroup::class.java,
            Boolean::class.javaPrimitiveType,
        ), resourceIdArgument = true)
        installHook(LayoutInflater::class.java.getDeclaredMethod(
            "inflate",
            XmlPullParser::class.java,
            ViewGroup::class.java,
            Boolean::class.javaPrimitiveType,
        ), resourceIdArgument = false)
    }

    private fun installHook(method: Method, resourceIdArgument: Boolean) {
        ModernXposedRuntime.hookMethod(method, object : ModernMethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val root = param.args[1] as? ViewGroup
                param.extras[ROOT_SNAPSHOT] = RootSnapshot(root, root?.childCount ?: 0)
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                val inflater = param.thisObject as? LayoutInflater ?: return
                val snapshot = param.extras[ROOT_SNAPSHOT] as? RootSnapshot ?: return
                val attachToRoot = param.args[2] as? Boolean ?: false
                val inflated = when {
                    !attachToRoot -> param.result as? View
                    snapshot.root != null && snapshot.root.childCount > snapshot.childCount ->
                        snapshot.root.getChildAt(snapshot.childCount)
                    else -> param.result as? View
                } ?: return
                val name = if (resourceIdArgument) {
                    val resourceId = param.args[0] as? Int ?: return
                    runCatching {
                        inflater.context.resources.getResourceEntryName(resourceId)
                    }.getOrNull()
                } else {
                    inferLayoutName(inflated)
                } ?: return
                dispatch(name, inflated)
            }
        })
    }

    private fun dispatch(name: String, view: View) {
        val handlers = callbacks[name]?.toList().orEmpty()
        if (handlers.isEmpty()) return
        val firstDispatch = synchronized(dispatched) {
            dispatched.getOrPut(view) { mutableSetOf() }.add(name)
        }
        if (!firstDispatch) return
        handlers.forEach { handler ->
            runCatching { handler(view) }
                .onFailure { ModernXposedRuntime.log("layout/$name callback failed", it) }
        }
    }

    private fun inferLayoutName(root: View): String? {
        val rootName = resourceName(root)
        val exact = when (rootName) {
            "bottom_navigation_root_stacked", "bottom_navigation_root_flat" -> "bottom_navigation"
            "mini_player", "mini_player_touch_panel" -> "mini_player"
            "player_root" -> "fragment_player_main"
            "song_lyrics_line" -> "lyrics_line"
            "song_lyrics_word" -> "lyrics_word_karaoke"
            else -> null
        }
        if (exact != null && callbacks.containsKey(exact)) return exact
        return inferLayoutNameByResourceNames { expected -> hasResourceName(root, expected) }
            ?.takeIf(callbacks::containsKey)
    }

    internal fun inferLayoutNameByResourceNames(isPresent: (String) -> Boolean): String? = when {
        isPresent("lyrics_main_content") &&
            isPresent("recycler_view_gradients") &&
            isPresent("current_player_item") -> "fragment_player_lyrics_sheet"
        isPresent("mini_player_content") -> "mini_player"
        isPresent("bottom_navigation") -> "bottom_navigation"
        isPresent("song_lyrics_word") -> "lyrics_word_karaoke"
        isPresent("song_lyrics_line") -> "lyrics_line"
        else -> null
    }

    private fun hasResourceName(view: View, expected: String): Boolean {
        if (resourceName(view) == expected) return true
        val group = view as? ViewGroup ?: return false
        for (index in 0 until group.childCount) {
            if (hasResourceName(group.getChildAt(index), expected)) return true
        }
        return false
    }

    private fun resourceName(view: View): String? {
        if (view.id == View.NO_ID) return null
        return runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull()
    }

    private data class RootSnapshot(val root: ViewGroup?, val childCount: Int)
    private const val ROOT_SNAPSHOT = "root_snapshot"
}
