package com.tcrrry.lyrics.engine.hook

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal class DualPaneState(
    val root: ViewGroup,
    val playerHost: View,
    val lyricsHost: FrameLayout,
    val artworkLayoutReapply: (() -> Unit)? = null,
) {
    var lyricsAttachRequested: Boolean = false
    var lyricsAttached: Boolean = false
}

/**
 * The player controller's state enum hands out the pane fragment and its fragment tag through
 * two no-argument instance accessors.
 *
 * Verified from the host DEX: 6.5.2 (1586) declares `player.fragment.t0$n` with
 * `f()Lcom/apple/android/music/common/fragment/a;` and `g()Ljava/lang/String;`, while 6.5.3
 * (1599) declares `player.fragment.v0$n` with `e()Lcom/apple/android/music/common/fragment/a;`
 * and the unchanged `g()Ljava/lang/String;`. Each build declares exactly one method per role, so
 * both are matched by shape and an ambiguous or absent shape fails the attachment instead of
 * guessing a member name from another version.
 */
internal object DualPaneStateAccessors {
    fun fragment(stateClass: Class<*>): Method? = stateClass.declaredMethods.singleOrNull { method ->
        isAccessor(method) &&
            method.returnType != Void.TYPE &&
            method.returnType != String::class.java &&
            !method.returnType.isPrimitive &&
            !method.returnType.isArray
    }?.accessible()

    fun tag(stateClass: Class<*>): Method? = stateClass.declaredMethods.singleOrNull { method ->
        isAccessor(method) && method.returnType == String::class.java
    }?.accessible()

    private fun isAccessor(method: Method): Boolean =
        !Modifier.isStatic(method.modifiers) &&
            !method.isSynthetic &&
            method.parameterCount == 0

    private fun Method.accessible(): Method = apply { isAccessible = true }
}

