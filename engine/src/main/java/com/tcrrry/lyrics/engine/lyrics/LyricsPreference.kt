package com.tcrrry.lyrics.engine.lyrics
import com.tcrrry.lyrics.plugin.PluginStorage.pluginPreferences

import android.content.Context
import com.tcrrry.lyrics.engine.hook.LyricsSourceMenuPolicy
import org.json.JSONArray

/** Independent Tcrrry settings; deliberately outside upstream ModuleSettings. */
internal object LyricsPreference {
    val defaultSourceOrder: List<String> = listOf(
        LyricsSourceMenuPolicy.NATIVE,
        LyricsSourceMenuPolicy.AUTHOR,
        "网易云音乐",
        "QQ音乐",
        "LRCLIB",
    )

    private fun prefs(context: Context) = context.pluginPreferences("tcrrry_lyrics_policy", Context.MODE_PRIVATE)
    fun qualityFirst(context: Context) = prefs(context).getBoolean("quality_first", false)
    fun lyricsReplacementEnabled(context: Context) = prefs(context).getBoolean("lyrics_replacement_enabled", true)
    fun updateLyricsReplacementEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("lyrics_replacement_enabled", enabled)
            .putInt("revision", revision(context) + 1).apply()
    }
    fun smoothShortUnits(context: Context) = prefs(context).getBoolean("smooth_short_units", true)
    fun rubyPronunciation(context: Context) = prefs(context).getBoolean("ruby_pronunciation", false)
    fun hideOpeningCredits(context: Context) = prefs(context).getBoolean("hide_opening_credits", false)
    fun simplifiedChinese(context: Context) = prefs(context).getBoolean("simplified_chinese", false)
    fun normalizeSourceOrder(raw: List<String>): List<String> {
        val valid = LyricsSourceMenuPolicy.sources.toSet()
        val seen = LinkedHashSet<String>()
        raw.forEach { if (it in valid) seen += it }
        defaultSourceOrder.forEach { seen += it }
        return seen.toList()
    }
    fun customSourceOrder(context: Context): List<String>? {
        val raw = prefs(context).getString("source_order", null)?.takeIf(String::isNotBlank) ?: return null
        val parsed = runCatching {
            val array = JSONArray(raw)
            buildList { for (i in 0 until array.length()) add(array.optString(i)) }
        }.getOrNull() ?: return null
        return normalizeSourceOrder(parsed)
    }
    fun sourceOrder(context: Context): List<String> = customSourceOrder(context) ?: defaultSourceOrder
    fun updateSourceOrder(context: Context, order: List<String>) {
        val normalized = normalizeSourceOrder(order)
        val json = JSONArray().apply { normalized.forEach(::put) }.toString()
        prefs(context).edit().putString("source_order", json)
            .putInt("revision", revision(context) + 1).apply()
    }
    fun resetSourceOrder(context: Context) {
        prefs(context).edit().remove("source_order")
            .putInt("revision", revision(context) + 1).apply()
    }
    fun updateSimplifiedChinese(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("simplified_chinese", enabled)
            .putInt("revision", revision(context) + 1).apply()
    }
    fun updateHideOpeningCredits(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("hide_opening_credits", enabled)
            .putInt("revision", revision(context) + 1).apply()
    }
    fun forceRepeatReset(context: Context) = true
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
