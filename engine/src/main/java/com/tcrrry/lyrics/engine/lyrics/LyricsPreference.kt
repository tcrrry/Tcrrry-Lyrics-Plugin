package com.tcrrry.lyrics.engine.lyrics
import com.tcrrry.lyrics.plugin.PluginStorage.pluginPreferences

import android.content.Context

/** Independent Tcrrry settings; deliberately outside upstream ModuleSettings. */
internal object LyricsPreference {
    private fun prefs(context: Context) = context.pluginPreferences("tcrrry_lyrics_policy", Context.MODE_PRIVATE)
    fun qualityFirst(context: Context) = prefs(context).getBoolean("quality_first", false)
    fun smoothShortUnits(context: Context) = prefs(context).getBoolean("smooth_short_units", true)
    fun rubyPronunciation(context: Context) = prefs(context).getBoolean("ruby_pronunciation", false)
    fun forceRepeatReset(context: Context) = prefs(context).getBoolean("force_repeat_reset", true)
    fun updateForceRepeatReset(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("force_repeat_reset", enabled).apply()
    }
    fun updateRubyPronunciation(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("ruby_pronunciation", enabled)
            .putInt("revision", revision(context) + 1).apply()
    }
    fun revision(context: Context) = prefs(context).getInt("revision", 0)
    fun update(context: Context, quality: Boolean = qualityFirst(context), smooth: Boolean = smoothShortUnits(context)) {
        prefs(context).edit().putBoolean("quality_first", quality).putBoolean("smooth_short_units", smooth)
            .putInt("revision", revision(context) + 1).apply()
    }
}
