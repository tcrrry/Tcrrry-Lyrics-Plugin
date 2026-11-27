package dev.kifranei.ampp.media

import android.view.View

/** System output does not require a Cast route; SharePlay uses the same button slot. */
internal class AudioOutputButtonVisibility(initialNativeVisibility: Int) {
    private var nativeVisibility = initialNativeVisibility
    private var synchronizing = false

    fun requested(value: Int, sharedSessionVisible: Boolean): Int {
        if (!synchronizing) nativeVisibility = value
        return effective(sharedSessionVisible)
    }

    fun synchronize(current: Int, sharedSessionVisible: Boolean, apply: (Int) -> Unit) {
        val desired = effective(sharedSessionVisible)
        if (current == desired) return
        internalChange { apply(desired) }
    }

    fun restore(apply: (Int) -> Unit) = internalChange { apply(nativeVisibility) }

    private fun effective(sharedSessionVisible: Boolean) =
        if (sharedSessionVisible) View.INVISIBLE else View.VISIBLE

    private inline fun internalChange(apply: () -> Unit) {
        val previous = synchronizing
        synchronizing = true
        try { apply() } finally { synchronizing = previous }
    }
}
