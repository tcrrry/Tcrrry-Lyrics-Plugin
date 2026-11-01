package com.tcrrry.lyrics.engine.hook

import android.content.Context
import android.content.SharedPreferences
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.tcrrry.lyrics.plugin.PluginStorage.pluginPreferences

/** A popup may open before the native enrichment worker finishes. */
internal object NativeLyricsEmphasisControl {
    fun bind(row: LinearLayout, context: Context, id: Long, valid: () -> Boolean, changed: (Boolean) -> Unit) {
        val display = context.pluginPreferences("japanese_pronunciation", Context.MODE_PRIVATE)
        val status = context.pluginPreferences("ampp-current-lyrics-source", Context.MODE_PRIVATE)
        fun available() = CurrentLyricsSourceStatus.canEmphasizePronunciation(context, id) && NativeLyricsEmphasisVisibility.visible(context)
        fun update() {
            val enabled = available()
            val primary = enabled && display.getBoolean("primary", false)
            (row.getChildAt(0) as TextView).text = if (primary) "突出：发音" else "突出：歌词"
            row.isEnabled = enabled
            row.alpha = if (enabled) 1f else .35f
            row.contentDescription = "${if (primary) "突出发音" else "突出歌词"}，点击切换"
        }
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> row.post { update() } }
        row.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                display.registerOnSharedPreferenceChangeListener(listener)
                status.registerOnSharedPreferenceChangeListener(listener)
                update()
            }
            override fun onViewDetachedFromWindow(view: View) {
                display.unregisterOnSharedPreferenceChangeListener(listener)
                status.unregisterOnSharedPreferenceChangeListener(listener)
            }
        })
        row.isClickable = true
        row.isFocusable = true
        row.setOnClickListener {
            if (!available() || !valid()) return@setOnClickListener
            val primary = !display.getBoolean("primary", false)
            display.edit().putBoolean("primary", primary).apply()
            CurrentLyricsSourceStatus.refreshSilently(id)
            changed(primary)
        }
        update()
    }
}
