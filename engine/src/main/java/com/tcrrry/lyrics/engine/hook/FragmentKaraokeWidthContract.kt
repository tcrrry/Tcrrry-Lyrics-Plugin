package com.tcrrry.lyrics.engine.hook

import android.view.View
import org.json.JSONObject

/** All members are resolved once, before registration; the row callback performs no discovery. */
internal class FragmentKaraokeWidthContract(lyrics: Class<*>, names: JSONObject) {
    private val loader = lyrics.classLoader!!
    val adapter = loader.loadClass(names.getString("adapterClass"))
    private val holder = loader.loadClass(names.getString("holderClass"))
    private val binding = loader.loadClass(names.getString("bindingClass"))
    private val holderBase = loader.loadClass(names.getString("holderBindingClass"))
    val bind = FragmentChromeContract.method(adapter, names.getString("bindMethod"), holder, java.lang.Integer.TYPE)
    private val adapterField = FragmentChromeContract.field(lyrics, names.getString("adapterField"), adapter)
    private val widthField = FragmentChromeContract.field(adapter, names.getString("widthField"), java.lang.Integer.TYPE)
    private val bindingField = FragmentChromeContract.field(holderBase, names.getString("bindingField"), checkNotNull(binding.superclass))
    private val itemField = FragmentChromeContract.field(holder, names.getString("itemField"), View::class.java)
    private val flexbox = FragmentChromeContract.field(binding, names.getString("flexboxField"),
        loader.loadClass(names.getString("flexboxClass")))

    fun owns(fragment: Any, candidate: Any) = adapterField.get(fragment) === candidate
    fun initialized(candidate: Any) = widthField.getInt(candidate) > 0
    fun initialize(candidate: Any, row: Any, viewportWidth: Int): Int? {
        if (initialized(candidate)) return null
        val rowBinding = bindingField.get(row)?.takeIf(binding::isInstance) ?: return null
        val item = itemField.get(row) as? View ?: return null
        val leaf = flexbox.get(rowBinding) as? View ?: return null
        val insets = ArrayList<Int>()
        var view: View? = leaf
        var reachedItem = false
        while (view != null) {
            (view.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let {
                insets += it.marginStart; insets += it.marginEnd
            }
            insets += view.paddingStart; insets += view.paddingEnd
            if (view === item) { reachedItem = true; break }
            view = view.parent as? View
        }
        // Native creation sees a detached item. Cached items are attached: stop at itemView,
        // otherwise the pane/window offsets would be mistaken for internal row padding.
        if (!reachedItem) return null
        return FragmentKaraokeWidthPolicy.initialize(widthField.getInt(candidate), viewportWidth, insets)?.also {
            widthField.setInt(candidate, it)
        }
    }
}
