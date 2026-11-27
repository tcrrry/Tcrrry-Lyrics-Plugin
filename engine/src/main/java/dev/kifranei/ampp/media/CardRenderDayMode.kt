package dev.kifranei.ampp.media

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup

/**
 * Apple Music renders its story share cards off-screen: `ShareSheetViewModel.subscribeToSharePlatformIntent()`
 * builds the sticker views (`common.views.m0` on 1613 and its lyrics/background subclasses) from the
 * Application context, so the card inherits the system night mode instead of being a fixed design.
 * The plugin pins only those sticker views to the day (NIGHT_NO) configuration so that the exported image
 * is the light-mode card in every system theme. Nothing outside the card renderer is touched.
 */
internal object CardRenderDayMode {
    /** [uiMode] with the night bits replaced by NIGHT_NO; every other uiMode bit is kept. */
    fun dayUiMode(uiMode: Int): Int =
        (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_NO

    fun isNight(uiMode: Int) = (uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /**
     * A day-configuration wrapper of [base]: resources, theme and the LayoutInflater all resolve with
     * NIGHT_NO, while application context, class loader, files and package identity stay [base]'s.
     */
    fun context(base: Context): Context {
        val configuration = Configuration(base.resources.configuration)
        configuration.uiMode = dayUiMode(configuration.uiMode)
        return DayContext(base).apply { applyOverrideConfiguration(configuration) }
    }

    /**
     * The theme is an exact copy of the base context's theme re-resolved against the day resources; no
     * default theme is layered on top, so the card keeps the host theme, only without night values.
     */
    private class DayContext(base: Context) : ContextThemeWrapper(base, 0) {
        override fun onApplyThemeResource(theme: android.content.res.Resources.Theme?, resid: Int, first: Boolean) = Unit
    }

    /** Opt the off-screen card out of any framework force-dark transform; API 29+. */
    fun disallowForceDark(view: View) {
        if (Build.VERSION.SDK_INT < 29) return
        view.isForceDarkAllowed = false
        if (view is ViewGroup) for (index in 0 until view.childCount) disallowForceDark(view.getChildAt(index))
    }
}

/**
 * Tracks, per thread, whether the native renderer is currently producing a card for one of the plugin's
 * own actions. Calls nest like the host's renderer calls: [enter] records whether that call is ours and
 * [exit] pops it, so a foreign render nested in (or following) ours is never pinned by mistake.
 */
internal class CardRenderWindow {
    private val stack = ThreadLocal<ArrayList<Boolean>>()
    val isActive: Boolean get() = stack.get()?.lastOrNull() == true
    fun enter(ours: Boolean) { (stack.get() ?: ArrayList<Boolean>().also(stack::set)).add(ours) }
    fun exit() {
        val calls = stack.get() ?: return
        if (calls.isNotEmpty()) calls.removeAt(calls.lastIndex)
        if (calls.isEmpty()) stack.remove()
    }
    fun clear() = stack.remove()
}
