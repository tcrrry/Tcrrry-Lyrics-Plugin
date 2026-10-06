package com.tcrrry.lyrics.engine.hook

import java.lang.reflect.Field
import java.lang.reflect.Modifier

internal const val DUAL_PANE_SONG_STATE = "SONG"
internal const val DUAL_PANE_LYRICS_STATE = "LYRICS"
internal const val DUAL_PANE_DEBUG_PREFIX = "[AMENH-2]"
internal const val DUAL_PANE_MUTABLE_LIVE_DATA = "androidx.lifecycle.MutableLiveData"

internal fun dualPaneDebug(message: String) {
    val line = DUAL_PANE_DEBUG_PREFIX + " " + message
    ModernXposedRuntime.log(line)
    android.util.Log.e("AMENH-LIVE", line)
}

internal fun dualPaneField(type: Class<*>, name: String): Field? {
    var current: Class<*>? = type
    while (true) {
        val candidate = current ?: return null
        runCatching { candidate.getDeclaredField(name) }.getOrNull()?.let {
            it.isAccessible = true
            return it
        }
        current = candidate.superclass
    }
}

/** Walks the hierarchy for the first non-static field the predicate accepts. */
internal fun dualPaneFieldByType(type: Class<*>, predicate: (Field) -> Boolean): Field? {
    var current: Class<*>? = type
    while (true) {
        val candidate = current ?: return null
        candidate.declaredFields
            .firstOrNull { field -> !Modifier.isStatic(field.modifiers) && predicate(field) }
            ?.let {
                it.isAccessible = true
                return it
            }
        current = candidate.superclass
    }
}

internal fun setDualPaneField(field: Field, receiver: Any, value: Any) {
    runCatching {
        field.isAccessible = true
        field.set(receiver, value)
    }
}
