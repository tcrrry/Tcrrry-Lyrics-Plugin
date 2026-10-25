package com.tcrrry.lyrics.engine.hook

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import java.lang.ref.WeakReference

/** Reuse the native loading slot only after lyric lookup has completed without content. */
internal class NativeLyricsEmptyState {
    private data class State(
        val slot: WeakReference<FrameLayout>,
        val label: WeakReference<TextView>,
        val visibility: Int,
        val children: List<Pair<WeakReference<View>, Int>>,
    )
    private var state: State? = null

    fun show(root: View, message: String) {
        val id = root.resources.getIdentifier("loading_progress", "id", "com.apple.android.music")
        if (id == 0) return
        val slot = root.findViewById<View>(id) as? FrameLayout ?: return
        showSlot(slot, message)
    }

    internal fun showSlot(slot: FrameLayout, message: String) {
        val current = state
        if (current?.slot?.get() === slot && current.label.get()?.parent === slot) {
            current.label.get()?.text = message
            slot.visibility = View.VISIBLE
            return
        }
        clear()
        val children = (0 until slot.childCount).map { slot.getChildAt(it) }
        val label = TextView(slot.context).apply {
            text = message
            gravity = Gravity.CENTER
            setTextAppearance(android.R.style.TextAppearance_Material_Body1)
            val color = children.filterIsInstance<android.widget.ProgressBar>().firstOrNull()
                ?.indeterminateTintList?.defaultColor
            if (color != null) setTextColor(color)
            isClickable = false
        }
        state = State(WeakReference(slot), WeakReference(label), slot.visibility,
            children.map { WeakReference(it) to it.visibility })
        children.forEach { it.visibility = View.GONE }
        slot.addView(label, FrameLayout.LayoutParams(-1, -1))
        slot.visibility = View.VISIBLE
    }

    fun clear(finished: Boolean = false) {
        val old = state ?: return
        state = null
        old.slot.get()?.let { slot ->
            old.label.get()?.takeIf { it.parent === slot }?.let(slot::removeView)
            old.children.forEach { (view, visibility) ->
                view.get()?.takeIf { it.parent === slot }?.visibility = visibility
            }
            slot.visibility = if (finished) View.GONE else old.visibility
        }
    }

    companion object {
        fun message(title: String): String = if (Regex(
            "(?i)(?:\\b(?:instrumental|off[ -]?vocal)\\b|纯音乐|純音樂|純音楽|伴奏)"
        ).containsMatchIn(title)) "纯音乐，请欣赏" else "暂无歌词，请欣赏音乐"
    }
}
