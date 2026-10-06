package com.tcrrry.lyrics.engine.ui
import com.tcrrry.lyrics.plugin.PluginStorage.pluginPreferences

import android.app.AlertDialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.lang.ref.WeakReference

internal enum class SettingsAppearance(val label: String) {
    FOLLOW("跟随原软件"), LIGHT("日间"), DARK("夜间");
    companion object {
        fun decode(value: String?) = entries.firstOrNull { it.name == value } ?: FOLLOW
    }
}

internal data class SettingsColors(
    val dark: Boolean, val background: Int, val surface: Int, val raised: Int,
    val text: Int, val secondary: Int, val muted: Int, val outline: Int,
    val primary: Int, val selected: Int, val disabled: Int,
) {
    val filledAccent = 0xFFD62447.toInt()
    val onPrimary = 0xFFFFFFFF.toInt()
    companion object {
        val Light = SettingsColors(false, 0xFFF2F2F7.toInt(), 0xFFFFFFFF.toInt(), 0xFFF5F5F8.toInt(),
            0xFF1C1C1E.toInt(), 0xFF55555D.toInt(), 0xFF696971.toInt(), 0xFFD1D1D6.toInt(),
            0xFFC91D40.toInt(), 0xFFFCE8EC.toInt(), 0xFFE4E4E9.toInt())
        val Dark = SettingsColors(true, 0xFF0B0B0C.toInt(), 0xFF1C1C1E.toInt(), 0xFF2C2C2E.toInt(),
            0xFFF5F5F7.toInt(), 0xFFB7B7BF.toInt(), 0xFF9999A2.toInt(), 0xFF444448.toInt(),
            0xFFFF6B83.toInt(), 0xFF3B2029.toInt(), 0xFF353538.toInt())
        fun resolve(mode: SettingsAppearance, hostNight: Boolean) = when (mode) {
            SettingsAppearance.FOLLOW -> if (hostNight) Dark else Light
            SettingsAppearance.LIGHT -> Light
            SettingsAppearance.DARK -> Dark
        }
    }
}

/** Shared appearance for the two independent settings roots, stored in the host's private prefs. */
internal object SettingsUiTheme {
    private var host = WeakReference<Context>(null)
    private fun prefs(context: Context) = context.pluginPreferences("tcrrry_settings_appearance", Context.MODE_PRIVATE)
    fun bind(context: Context) { host = WeakReference(context) }
    fun mode(context: Context) = SettingsAppearance.decode(prefs(context).getString("mode", null))
    fun setMode(context: Context, mode: SettingsAppearance) { prefs(context).edit().putString("mode", mode.name).apply() }
    private fun hostNight(context: Context) = context.resources.configuration.uiMode and
        Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    fun hostColors(context: Context) = SettingsColors.resolve(SettingsAppearance.FOLLOW, hostNight(context))
    fun colors(context: Context) = SettingsColors.resolve(mode(context), hostNight(context))
    val currentColors get() = host.get()?.let(::colors) ?: SettingsColors.Light

    fun dialogBuilder(context: Context): AlertDialog.Builder {
        val palette = colors(context)
        val themed = ContextThemeWrapper(context, if (palette.dark)
            android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert)
        themed.applyOverrideConfiguration(Configuration().apply {
            uiMode = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (palette.dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        })
        return object : AlertDialog.Builder(themed) {
            override fun create(): AlertDialog = super.create().apply {
                val owned = java.lang.ref.WeakReference(this)
                com.tcrrry.lyrics.plugin.PluginLifetime.close { android.os.Handler(android.os.Looper.getMainLooper()).post { owned.get()?.dismiss() } }
                setOnShowListener {
                    listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL)
                        .forEach { getButton(it)?.setTextColor(palette.primary) }
                }
            }
        }
    }

    fun appearanceCard(context: Context, refresh: () -> Unit): View {
        fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()
        val palette = colors(context)
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply { setColor(palette.surface); cornerRadius = dp(18).toFloat() }
            addView(TextView(context).apply {
                text = "外观"; textSize = 17f; setTextColor(palette.text)
            })
            addView(TextView(context).apply {
                text = "两页共用 · 当前${if (palette.dark) "夜间" else "日间"}模式"
                textSize = 12f; setTextColor(palette.secondary)
                setPadding(0, dp(4), 0, dp(10))
            })
            addView(LinearLayout(context).apply {
                SettingsAppearance.entries.forEachIndexed { index, option ->
                    addView(TextView(context).apply {
                        text = option.label; textSize = 13f; gravity = Gravity.CENTER
                        isSelected = mode(context) == option
                        setTextColor(if (isSelected) palette.primary else palette.text)
                        background = GradientDrawable().apply {
                            setColor(if (isSelected) palette.selected else palette.raised)
                            cornerRadius = dp(12).toFloat()
                            setStroke(dp(1), if (isSelected) palette.primary else palette.outline)
                        }
                        isClickable = true; isFocusable = true
                        contentDescription = "外观：${option.label}"
                        setOnClickListener { if (mode(context) != option) { setMode(context, option); refresh() } }
                    }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { if (index > 0) marginStart = dp(6) })
                }
            })
        }
    }

    private fun descendants(root: View): List<View> = buildList {
        add(root)
        if (root is ViewGroup) for (i in 0 until root.childCount) addAll(descendants(root.getChildAt(i)))
    }

    /** Restore after layout, when a replacement ScrollView has its real scroll range. */
    fun refreshPreservingScroll(root: View, render: () -> Unit) {
        val positions = descendants(root).filterIsInstance<ScrollView>().map { it.scrollY }
        render()
        descendants(root).filterIsInstance<ScrollView>().zip(positions).forEach { (view, y) ->
            if (view.isLaidOut) view.scrollTo(0, y) else {
                view.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                    override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, oldL: Int, oldT: Int, oldR: Int, oldB: Int) {
                        view.removeOnLayoutChangeListener(this)
                        view.scrollTo(0, y)
                    }
                })
            }
        }
    }

    /** Appearance changes preserve unsaved API fields, selection, focus and scroll. */
    fun refreshPreservingInput(root: View, render: () -> Unit) {
        data class Field(val hint: String?, val value: String, val start: Int, val end: Int, val focused: Boolean)
        val fields = descendants(root).filterIsInstance<EditText>().map {
            Field(it.hint?.toString(), it.text.toString(), it.selectionStart, it.selectionEnd, it.hasFocus())
        }
        refreshPreservingScroll(root, render)
        descendants(root).filterIsInstance<EditText>().zip(fields).forEach { (input, old) ->
            if (input.hint?.toString() == old.hint) {
                input.setText(old.value)
                input.setSelection(old.start.coerceIn(0, old.value.length), old.end.coerceIn(0, old.value.length))
                if (old.focused) input.requestFocus()
            }
        }
    }

    /** Returns an acknowledgement for explicit refreshes, avoiding a second poll refresh. */
    fun observe(root: View, context: Context, changed: () -> Unit): () -> Unit {
        var previous = colors(context)
        val update = object : Runnable {
            override fun run() {
                if (!root.isAttachedToWindow) return
                val next = colors(context)
                if (next != previous) { previous = next; changed() }
                root.postDelayed(this, 1_000L)
            }
        }
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { root.post(update) }
            override fun onViewDetachedFromWindow(v: View) { root.removeCallbacks(update) }
        })
        return { previous = colors(context) }
    }
}
