package com.tcrrry.lyrics.engine.ui
import android.app.AlertDialog
import android.content.Context
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ScrollView
import java.lang.ref.WeakReference

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
        fun resolve(hostNight: Boolean) = if (hostNight) Dark else Light
    }
}

/** Settings follow the host's current day/night configuration. */
internal object SettingsUiTheme {
    private var host = WeakReference<Context>(null)
    fun bind(context: Context) { host = WeakReference(context) }
    private fun hostNight(context: Context) = context.resources.configuration.uiMode and
        Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    fun hostColors(context: Context) = SettingsColors.resolve(hostNight(context))
    fun colors(context: Context) = SettingsSurfaceContext.hostColor?.let {
        SettingsColors.resolve(android.graphics.Color.luminance(it) < .5f)
    } ?: hostColors(context)
    val currentColors get() = host.get()?.let(::colors) ?: SettingsColors.Light

    private fun owned(dialog: AlertDialog): AlertDialog = dialog.apply {
        val reference = WeakReference(this)
        com.tcrrry.lyrics.plugin.PluginLifetime.close {
            android.os.Handler(android.os.Looper.getMainLooper()).post { reference.get()?.dismiss() }
        }
    }

    fun contentDialog(hostContext: Context, content: View,
        animateClose: ((() -> Unit) -> Unit)? = null): AlertDialog = owned(
        object : AlertDialog(themedContext(hostContext, floating = false), themeStyle(hostContext, floating = false)) {
            private var closing = false
            private var completing = false

            override fun dismiss() {
                val activity = hostContext as? android.app.Activity
                if (completing || !isShowing || animateClose == null ||
                    !com.tcrrry.lyrics.plugin.PluginLifetime.alive || activity?.isFinishing == true ||
                    activity?.isDestroyed == true) {
                    super.dismiss()
                } else if (!closing) {
                    closing = true
                    animateClose {
                        completing = true
                        super.dismiss()
                    }
                }
            }

            override fun onCreate(savedInstanceState: android.os.Bundle?) {
                super.onCreate(savedInstanceState)
                window?.apply {
                    setBackgroundDrawableResource(android.R.color.transparent)
                    setWindowAnimations(0)
                    clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                    setGravity(android.view.Gravity.FILL)
                    setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                    setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    decorView.setPadding(0, 0, 0, 0)
                    @Suppress("DEPRECATION")
                    (hostContext as? android.app.Activity)?.window?.decorView?.systemUiVisibility?.let {
                        decorView.systemUiVisibility = it
                    }
                    @Suppress("DEPRECATION")
                    statusBarColor = android.graphics.Color.TRANSPARENT
                    @Suppress("DEPRECATION")
                    navigationBarColor = android.graphics.Color.TRANSPARENT
                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                        isStatusBarContrastEnforced = false
                        isNavigationBarContrastEnforced = false
                    }
                }
                setContentView(content)
            }
        })

    private fun themeStyle(context: Context, floating: Boolean): Int {
        val dark = colors(context).dark
        return if (floating) {
            if (dark) android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert
        } else {
            if (dark) android.R.style.Theme_Material_NoActionBar else android.R.style.Theme_Material_Light_NoActionBar
        }
    }

    private fun themedContext(context: Context, floating: Boolean): ContextThemeWrapper {
        val palette = colors(context)
        val themed = ContextThemeWrapper(context, themeStyle(context, floating))
        themed.applyOverrideConfiguration(Configuration().apply {
            uiMode = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (palette.dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        })
        return themed
    }

    fun dialogBuilder(context: Context): AlertDialog.Builder {
        val palette = colors(context)
        return object : AlertDialog.Builder(themedContext(context, floating = true)) {
            override fun create(): AlertDialog = owned(super.create()).apply {
                setOnShowListener {
                    listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL)
                        .forEach { getButton(it)?.setTextColor(palette.primary) }
                }
            }
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

    /** Rebuilds preserve unsaved API fields, selection, focus and scroll. */
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
