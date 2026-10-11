package com.tcrrry.lyrics.engine.hook

import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/** A native timed flex item can be a TextView or a group of character views.
 * Keep the group intact: gradient indices and playback refer to its root.
 */
internal data class RubyNativeWord(val view: View, val glyphs: List<TextView>) {
    val text: String get() = glyphs.joinToString("") { it.text.toString() }
    companion object {
        fun collect(container: ViewGroup): List<RubyNativeWord> {
            fun leaves(view: View): List<TextView> = when {
                view.visibility == View.GONE -> emptyList()
                view is TextView -> listOf(view).filter { it.text.isNotEmpty() }
                view is ViewGroup -> (0 until view.childCount).flatMap { leaves(view.getChildAt(it)) }
                else -> emptyList()
            }
            return (0 until container.childCount).mapNotNull {
                val root=container.getChildAt(it)
                leaves(root).takeIf { texts -> texts.isNotEmpty() }?.let { texts -> RubyNativeWord(root,texts) }
            }
        }
    }
}
