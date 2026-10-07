package com.tcrrry.lyrics.engine.hook

import android.view.View
import android.view.ViewGroup
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Auxiliary text shares the row's parent, outside the native word gradient. */
internal object PronunciationHeaderLayout {
    private data class Edge(val view: WeakReference<View>, val first: Int, val second: Int, val margin: Int)
    private val headers = WeakHashMap<ViewGroup, Edge>()
    private val middles = WeakHashMap<ViewGroup, Edge>()
    private val nativeAuxiliaries = WeakHashMap<ViewGroup, MutableList<WeakReference<View>>>()
    private val footers = WeakHashMap<ViewGroup, Edge>()

    internal fun field(type: Class<*>, name: String) = type.getField(
        if (type.name == "androidx.constraintlayout.widget.ConstraintLayout\$b") {
            // Verified against the 6.5.3 and 1606 constraint resolvers.
            when (name) {
                "topToTop" -> "i"
                "topToBottom" -> "j"
                "bottomToTop" -> "k"
                "bottomToBottom" -> "l"
                "startToStart" -> "t"
                "endToEnd" -> "v"
                else -> name
            }
        } else name
    )

    fun supports(container: ViewGroup): Boolean = runCatching {
        val parent = container.parent as? ViewGroup ?: return@runCatching false
        val type = container.layoutParams.javaClass
        type.getConstructor(ViewGroup.LayoutParams::class.java)
        for (name in listOf("topToTop", "topToBottom", "bottomToTop", "bottomToBottom", "startToStart", "endToEnd")) field(type, name)
        parent != container && container.id != View.NO_ID
    }.getOrDefault(false)

    fun keepNativeTranslation(container: ViewGroup, view: View) {
        check(view.parent === container)
        // Native subtitle inflation already supplies its full-row flex layout.
        nativeAuxiliaries.getOrPut(container) { mutableListOf() }.add(WeakReference(view))
    }

    fun moveAbove(container: ViewGroup, view: View) = move(container, view, above = true)
    fun moveBelow(container: ViewGroup, view: View) = move(container, view, above = false)

    /** Insert the original subtitle between the emphasized phonetics and translation. */
    fun moveBetween(container: ViewGroup, view: View) {
        val nativeTranslation = nativeAuxiliaries[container]?.firstOrNull()?.get()
        if (nativeTranslation?.parent === container && view.parent === container) {
            // Only appended auxiliary views move; all native word indices stay fixed.
            container.removeView(view)
            container.addView(view, container.indexOfChild(nativeTranslation))
            nativeAuxiliaries.getOrPut(container) { mutableListOf() }.add(WeakReference(view))
            return
        }
        clearEdge(container, above = false, middle = true)
        val footer = footers[container]?.view?.get() ?: return moveBelow(container, view)
        check(view.parent === container && supports(container))
        val parent = container.parent as ViewGroup
        val parameters = container.layoutParams
        val type = parameters.javaClass
        val top = field(type, "topToTop")
        val topBottom = field(type, "topToBottom")
        val bottomTop = field(type, "bottomToTop")
        val bottom = field(type, "bottomToBottom")
        val saved = Edge(WeakReference(view), bottomTop.getInt(parameters), bottom.getInt(parameters), 0)
        val params = type.getConstructor(ViewGroup.LayoutParams::class.java)
            .newInstance(ViewGroup.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT)) as ViewGroup.MarginLayoutParams
        top.setInt(params, -1)
        topBottom.setInt(params, container.id)
        bottomTop.setInt(params, footer.id)
        bottom.setInt(params, -1)
        field(type, "startToStart").setInt(params, container.id)
        field(type, "endToEnd").setInt(params, container.id)
        params.marginStart = container.paddingStart
        params.marginEnd = container.paddingEnd
        params.topMargin = (view.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin ?: 0
        view.id = View.generateViewId()
        container.removeView(view)
        parent.addView(view, params)
        bottomTop.setInt(parameters, view.id)
        bottom.setInt(parameters, -1)
        topBottom.setInt(footer.layoutParams, view.id)
        footer.layoutParams = footer.layoutParams
        container.layoutParams = parameters
        middles[container] = saved
    }

    private fun move(container: ViewGroup, view: View, above: Boolean) {
        check(view.parent === container && supports(container))
        clearEdge(container, above)
        val parent = container.parent as ViewGroup
        val original = container.layoutParams
        val type = original.javaClass
        val first = field(type, if (above) "topToTop" else "bottomToTop")
        val second = field(type, if (above) "topToBottom" else "bottomToBottom")
        val neighborAnchor = field(type, if (above) "bottomToTop" else "topToBottom")
        val firstValue = first.getInt(original)
        val secondValue = second.getInt(original)
        val nativeParams = view.layoutParams
        val parameters = type.getConstructor(ViewGroup.LayoutParams::class.java)
            .newInstance(ViewGroup.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT)) as ViewGroup.MarginLayoutParams
        first.setInt(parameters, firstValue)
        second.setInt(parameters, secondValue)
        field(type, if (above) "bottomToTop" else "topToBottom").setInt(parameters, container.id)
        field(type, "startToStart").setInt(parameters, container.id)
        field(type, "endToEnd").setInt(parameters, container.id)
        parameters.marginStart = container.paddingStart
        parameters.marginEnd = container.paddingEnd
        val originalMargin = (original as? ViewGroup.MarginLayoutParams)?.let { if (above) it.topMargin else it.bottomMargin } ?: 0
        if (above) parameters.topMargin = originalMargin else {
            parameters.topMargin = (nativeParams as? ViewGroup.MarginLayoutParams)?.topMargin ?: 0
            parameters.bottomMargin = originalMargin
        }
        view.id = View.generateViewId()
        val neighbors = (0 until parent.childCount).map { parent.getChildAt(it) }.filter {
            it !== container && it.layoutParams.javaClass == type && neighborAnchor.getInt(it.layoutParams) == container.id
        }
        container.removeView(view)
        try {
            parent.addView(view, parameters)
            first.setInt(original, if (above) -1 else view.id)
            second.setInt(original, if (above) view.id else -1)
            for (neighbor in neighbors) {
                neighborAnchor.setInt(neighbor.layoutParams, view.id)
                neighbor.layoutParams = neighbor.layoutParams
            }
            (original as? ViewGroup.MarginLayoutParams)?.let { if (above) it.topMargin = 0 else it.bottomMargin = 0 }
            container.layoutParams = original
            (if (above) headers else footers)[container] = Edge(WeakReference(view), firstValue, secondValue, originalMargin)
        } catch (error: Exception) {
            parent.removeView(view)
            first.setInt(original, firstValue)
            second.setInt(original, secondValue)
            for (neighbor in neighbors) neighborAnchor.setInt(neighbor.layoutParams, container.id)
            (original as? ViewGroup.MarginLayoutParams)?.let { if (above) it.topMargin = originalMargin else it.bottomMargin = originalMargin }
            container.addView(view, nativeParams)
            throw error
        }
    }

    private fun clearEdge(container: ViewGroup, above: Boolean, middle: Boolean = false) {
        val edge = (if (middle) middles else if (above) headers else footers).remove(container) ?: return
        val view = edge.view.get()
        val parameters = container.layoutParams
        val type = parameters.javaClass
        val first = field(type, if (above) "topToTop" else "bottomToTop")
        val second = field(type, if (above) "topToBottom" else "bottomToBottom")
        // Read current anchors: another track's auxiliary row may have rewired them.
        first.setInt(parameters, view?.layoutParams?.let { first.getInt(it) } ?: edge.first)
        second.setInt(parameters, view?.layoutParams?.let { second.getInt(it) } ?: edge.second)
        val parent = container.parent as? ViewGroup
        if (view != null && parent != null) {
            val neighborAnchor = field(type, if (above) "bottomToTop" else "topToBottom")
            for (i in 0 until parent.childCount) {
                val sibling = parent.getChildAt(i)
                if (sibling !== container && sibling !== view && sibling.layoutParams.javaClass == type &&
                    neighborAnchor.getInt(sibling.layoutParams) == view.id) {
                    neighborAnchor.setInt(sibling.layoutParams, container.id)
                    sibling.layoutParams = sibling.layoutParams
                }
            }
        }
        val margin = (view?.layoutParams as? ViewGroup.MarginLayoutParams)?.let { if (above) it.topMargin else it.bottomMargin } ?: edge.margin
        (parameters as? ViewGroup.MarginLayoutParams)?.let { if (above) it.topMargin = margin else it.bottomMargin = margin }
        if (view != null) NativeLyricsPronunciationSubtitle.releaseAuxiliary(view)
        (view?.parent as? ViewGroup)?.removeView(view)
        container.layoutParams = parameters
    }

    fun auxiliary(container: ViewGroup, above: Boolean): View? =
        (if (above) headers else footers)[container]?.view?.get()?.takeIf { view ->
            view.parent === container.parent && runCatching {
                field(container.layoutParams.javaClass, if (above) "topToBottom" else "bottomToTop")
                    .getInt(container.layoutParams) == view.id
            }.getOrDefault(false)
        }

    fun clearAuxiliary(container: ViewGroup, above: Boolean) = clearEdge(container, above)

    fun clearAll() {
        (headers.keys.toList()+middles.keys.toList()+footers.keys.toList()+nativeAuxiliaries.keys.toList()).distinct().forEach(::clear)
    }

    fun clear(container: ViewGroup) {
        nativeAuxiliaries.remove(container)?.forEach { reference ->
            reference.get()?.takeIf { it.parent === container }?.let(container::removeView)
        }
        clearEdge(container, above = false, middle = true)
        clearEdge(container, above = false)
        clearEdge(container, above = true)
    }

    fun clearRow(root: ViewGroup) {
        val children = (0 until root.childCount).map { root.getChildAt(it) }.reversed()
        for (child in children) if (child is ViewGroup) {
            clear(child)
            clearRow(child)
        }
    }
}
