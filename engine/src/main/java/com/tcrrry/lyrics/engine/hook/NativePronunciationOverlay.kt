package com.tcrrry.lyrics.engine.hook

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Draw inside the native row, after its word masks, without adding a word-list index. */
internal object NativePronunciationOverlay {
    private class Header(val view: WeakReference<TextView>, val top: Int, val listener: View.OnLayoutChangeListener)
    private val rows = WeakHashMap<ViewGroup, Header>()

    internal fun view(container: ViewGroup): TextView? = rows[container]?.view?.get()

    fun attach(container: ViewGroup, view: TextView) {
        check(view.parent === container)
        clear(container)
        val top = container.paddingTop
        val reference = WeakReference(container)
        val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            reference.get()?.let(::layout)
        }
        container.removeView(view)
        rows[container] = Header(WeakReference(view), top, listener)
        try {
            container.overlay.add(view)
            container.addOnLayoutChangeListener(listener)
            layout(container)
        } catch (error: Exception) {
            clear(container)
            throw error
        }
    }

    private fun layout(container: ViewGroup) {
        val header = rows[container] ?: return
        val view = header.view.get() ?: return
        val outerWidth = container.width.takeIf { it > 0 }
            ?: container.measuredWidth.takeIf { it > 0 }
            ?: container.resources.displayMetrics.widthPixels
        val width = (outerWidth - container.paddingLeft - container.paddingRight).coerceAtLeast(1)
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val height = view.measuredHeight
        view.layout(container.paddingLeft, header.top, container.paddingLeft + width, header.top + height)
        val reserved = header.top + height
        if (container.paddingTop != reserved) {
            container.setPaddingRelative(container.paddingStart, reserved, container.paddingEnd, container.paddingBottom)
        }
        container.invalidate()
    }

    fun clear(container: ViewGroup) {
        val header = rows.remove(container) ?: return
        container.removeOnLayoutChangeListener(header.listener)
        header.view.get()?.let { view ->
            container.overlay.remove(view)
            NativeLyricsPronunciationSubtitle.releaseAuxiliary(view)
        }
        container.setPaddingRelative(container.paddingStart, header.top, container.paddingEnd, container.paddingBottom)
    }

    fun clearAll() = rows.keys.toList().forEach(::clear)
}

/** Normal playback refreshes keep native word maps; only changed auxiliary state rebuilds. */
internal class NativeAuxiliaryBindingState {
    internal data class Identity(val pointer: Any, val lineId: Int, val pronunciation: Boolean, val translation: Boolean)
    private val holders = WeakHashMap<Any, Identity>()
    fun changed(holder: Any, identity: Identity): Boolean = holders.put(holder, identity) != identity
    fun clear(holder: Any) { holders.remove(holder) }
}
