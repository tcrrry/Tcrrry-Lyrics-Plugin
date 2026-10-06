package com.tcrrry.lyrics.engine.hook

import org.json.JSONObject
import java.lang.reflect.Field
import java.lang.reflect.Method

/** Exact APK contracts are profile-owned, never inferred from a host version string. */
internal class FragmentChromeContract(loader: ClassLoader, val names: JSONObject) {
    val content = loader.loadClass(names.getString("contentClass"))
    val player = loader.loadClass(names.getString("playerClass"))
    val model = loader.loadClass(names.getString("navigationModelClass"))
    val kind = loader.loadClass(names.getString("navigationKindClass"))
    val callback = loader.loadClass(names.getString("navigationCallbackClass"))
    val composer = loader.loadClass(names.getString("composerClass"))
    val renderTab = method(content, names.getString("renderTab"), model, java.lang.Long.TYPE,
        callback, composer, java.lang.Integer.TYPE)
    val viewModel = method(content, names.getString("navigationViewModel"))
    val modelKind = field(model, names.getString("modelKind"), kind)
    val kindId = method(kind, names.getString("kindId"))
    val kindLabel = method(kind, names.getString("kindLabel"))
    val kindIcon = method(kind, names.getString("kindIcon"))
    val invokeCallback = method(callback, "invoke", Any::class.java)
    val slide = loader.loadClass(names.getString("slideCallbackClass")).let {
        method(it, names.getString("slideMethod"), android.view.View::class.java, java.lang.Float.TYPE)
    }
    val slidePlayer = field(slide.declaringClass, names.getString("slidePlayerField"), player)
    val progress = method(slide.declaringClass, "d", java.lang.Float.TYPE)
    val playerBehavior = field(player, "c", loader.loadClass("com.google.android.material.bottomsheet.BottomSheetBehavior"))
    val behaviorState = field(playerBehavior.type, "p0", java.lang.Integer.TYPE)
    val playerSlide = field(player, names.getString("playerSlideCallbackField"), slide.declaringClass)
    val slideProgress = field(slide.declaringClass, names.getString("slideProgressField"), java.lang.Float.TYPE)
    val playerOf = method(content, names.getString("playerOf"))
    val libraryModel = method(content, names.getString("libraryViewModel"))
    val libraryTitle = method(libraryModel.returnType, "getLibraryTabName")
    val drawerOf = method(loader.loadClass(names.getString("activityClass")), names.getString("drawerOf"))
    val drawerOpen = method(drawerOf.returnType, names.getString("drawerOpen"))
    val nativeMenuListener = field(content, names.getString("menuListenerField"), loader.loadClass(names.getString("menuListenerClass")))
    val selectMenuItem = method(nativeMenuListener.type, names.getString("menuSelectMethod"), android.view.MenuItem::class.java)
    val activityTouch = android.app.Activity::class.java.getDeclaredMethod("dispatchTouchEvent", android.view.MotionEvent::class.java)
    val resources = names.getJSONObject("resources")
    val blurDraw = method(loader.loadClass(names.getString("blurClass")), "draw", android.graphics.Canvas::class.java)

    companion object {
        fun method(type: Class<*>, name: String, vararg parameters: Class<*>): Method {
            var current: Class<*>? = type
            while (current != null) {
                val found = runCatching { current!!.getDeclaredMethod(name, *parameters) }.getOrNull()
                if (found != null) return found.apply { isAccessible = true }
                current = current.superclass
            }
            throw NoSuchMethodException("${type.name}#$name(${parameters.joinToString { it.name }})")
        }
        fun field(type: Class<*>, name: String, expected: Class<*>): Field =
            type.getDeclaredField(name).apply {
                check(this.type == expected) { "${type.name}#$name descriptor mismatch" }
                isAccessible = true
            }
    }
}
