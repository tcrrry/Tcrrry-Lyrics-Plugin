package com.tcrrry.lyrics.engine.hook
import com.tcrrry.lyrics.plugin.PluginStorage.pluginPreferences

import android.content.Context

/** Hiding phonetics must never hide the original by leaving it in the auxiliary track. */
internal object NativeLyricsEmphasisVisibility {
    fun synchronize(context: Context, visible: Boolean): Boolean {
        val prefs = context.pluginPreferences("japanese_pronunciation", Context.MODE_PRIVATE)
        val changedPrimary = !visible && prefs.getBoolean("primary", false)
        prefs.edit().putBoolean("visible", visible).apply {
            if (!visible) putBoolean("primary", false)
        }.apply()
        return changedPrimary
    }

    fun visible(context: Context): Boolean = context.pluginPreferences("japanese_pronunciation", Context.MODE_PRIVATE)
        .getBoolean("visible", true)
}
