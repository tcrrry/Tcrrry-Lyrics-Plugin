package com.tcrrry.lyrics.engine.hook

import java.util.WeakHashMap

/** Normal playback refreshes keep native word maps; only changed auxiliary state rebuilds. */
internal class NativeAuxiliaryBindingState {
    internal data class Identity(val pointer: Any, val lineId: Int, val pronunciation: Boolean, val translation: Boolean)
    private val holders = WeakHashMap<Any, Identity>()
    fun changed(holder: Any, identity: Identity): Boolean = holders.put(holder, identity) != identity
    fun clear(holder: Any) { holders.remove(holder) }
}
