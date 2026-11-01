package com.tcrrry.lyrics.engine.hook

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/** Detached subtitles must follow the native singer row, including recycled rows. */
internal object NativeAuxiliaryAlignment {
    fun sync(container: ViewGroup, subtitle: TextView) {
        // These public Flexbox accessors are present on the verified native builds.
        val justify = runCatching { container.javaClass.getMethod("getJustifyContent").invoke(container) as Int }.getOrNull()
            ?: return
        val direction = runCatching { container.javaClass.getMethod("getFlexDirection").invoke(container) as Int }.getOrNull()
            ?: return
        if (direction !in 0..1) return
        val horizontal = when (justify) {
            0 -> if (direction == 1) Gravity.END else Gravity.START
            1 -> if (direction == 1) Gravity.START else Gravity.END
            2 -> Gravity.CENTER_HORIZONTAL
            else -> return
        }
        subtitle.layoutDirection = container.layoutDirection
        subtitle.textAlignment = View.TEXT_ALIGNMENT_GRAVITY
        subtitle.gravity = (subtitle.gravity and Gravity.VERTICAL_GRAVITY_MASK) or horizontal
    }
}
