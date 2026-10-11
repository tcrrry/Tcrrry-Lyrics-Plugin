package com.tcrrry.lyrics.engine.hook
import com.tcrrry.lyrics.plugin.PluginStorage.pluginPreferences

import android.content.Context

/** Hiding phonetics must never hide the original by leaving it in the auxiliary track. */
internal object NativeLyricsEmphasisVisibility {
    fun saved(context: Context): Boolean? {
        val prefs = context.pluginPreferences("japanese_pronunciation", Context.MODE_PRIVATE)
        return if (prefs.contains("visible")) prefs.getBoolean("visible", true) else null
    }

    fun effective(context: Context, nativeVisible: Boolean): Boolean = saved(context) ?: nativeVisible

    /** Only an explicit menu action may persist a choice or clear emphasis.
     * Native startup observers are state notifications, not user actions.
     */
    fun choose(context: Context, visible: Boolean, applyNative: () -> Unit): Boolean {
        val prefs = context.pluginPreferences("japanese_pronunciation", Context.MODE_PRIVATE)
        val oldVisible = saved(context)
        val oldPrimary = if (prefs.contains("primary")) prefs.getBoolean("primary", false) else null
        val changedPrimary = !visible && prefs.getBoolean("primary", false)
        prefs.edit().putBoolean("visible", visible).apply {
            if (!visible) putBoolean("primary", false)
        }.apply()
        try { applyNative() } catch (error: Throwable) {
            prefs.edit().apply {
                if (oldVisible == null) remove("visible") else putBoolean("visible", oldVisible)
                if (oldPrimary == null) remove("primary") else putBoolean("primary", oldPrimary)
            }.apply()
            throw error
        }
        return changedPrimary
    }

    fun visible(context: Context): Boolean = context.pluginPreferences("japanese_pronunciation", Context.MODE_PRIVATE)
        .getBoolean("visible", true)
}
