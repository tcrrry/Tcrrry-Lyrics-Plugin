package dev.kifranei.ampp.media

import android.view.View
import android.view.ViewGroup
import java.lang.reflect.Field
import java.util.IdentityHashMap

/** A minimum is derived from child sizes, never from our previously expanded container. */
internal class PlayerVolumeMinimum(initial: Int) {
    private var native = initial
    private var applied = initial
    private fun observe(current: Int) { if (current != applied) native = current }
    fun update(current: Int, required: Int): Int {
        observe(current)
        applied = maxOf(native, required)
        return applied
    }
    fun restore(current: Int): Int {
        observe(current)
        applied = native
        return native
    }
}

/** Both the song pane and the lyrics/queue wrapper use native percent-height constraints. */
internal class PlayerVolumeSpace(private val root: ViewGroup, private val minimumField: Field,
    private val wrapperId: Int) : AutoCloseable {
    private data class Reservation(val params: ViewGroup.LayoutParams, val minimum: PlayerVolumeMinimum,
        var nativeHeight: Int, var appliedHeight: Int? = null)
    private val reservations = IdentityHashMap<View, Reservation>()

    private fun anchor(controls: ViewGroup): View? = PlayerVolumeSpaceTarget.find(controls as View,
        isConstraint = { view ->
            val params = view.layoutParams
            val reservation = reservations[view]
            params != null && minimumField.declaringClass.isInstance(params) &&
                (params.height == 0 || (reservation != null && reservation.params === params && reservation.appliedHeight == params.height))
        },
        parent = { it.parent as? View },
        isWrapper = { it is ViewGroup && wrapperId != 0 && it.id == wrapperId },
        fillsParent = { it.layoutParams?.height == ViewGroup.LayoutParams.MATCH_PARENT }
    )?.takeUnless { it === root }

    fun reserve(controls: ViewGroup, height: Int, compact: Boolean = false): Boolean {
        val anchor = anchor(controls) ?: return false
        val params = anchor.layoutParams
        val current = minimumField.getInt(params)
        // A replacement LayoutParams belongs to AM, even if its values match ours.
        val reservation = reservations[anchor]?.takeIf { it.params === params }
            ?: Reservation(params, PlayerVolumeMinimum(current), params.height).also { reservations[anchor] = it }
        val desired = reservation.minimum.update(current, height)
        var changed = desired != current
        if (changed) minimumField.setInt(params, desired)
        if (compact) {
            if (reservation.appliedHeight == null || params.height != reservation.appliedHeight) reservation.nativeHeight = params.height
            reservation.appliedHeight = desired
            if (params.height != desired) { params.height = desired; changed = true }
        } else if (reservation.appliedHeight != null) {
            if (params.height == reservation.appliedHeight) { params.height = reservation.nativeHeight; changed = true }
            reservation.appliedHeight = null
        }
        if (changed) anchor.layoutParams = params
        return changed
    }

    /** Retained phone panes keep their geometry; remove ownership only for departed Views. */
    fun retain(controls: List<ViewGroup>): Boolean {
        val active = controls.mapNotNull(::anchor)
        var changed = false
        PlayerVolumeLayoutState.inactive(reservations.keys, active).forEach {
            if (restore(it)) changed = true
        }
        return changed
    }

    private fun restore(view: View): Boolean {
        val reservation = reservations.remove(view) ?: return false
        val params = view.layoutParams?.takeIf { it === reservation.params } ?: return false
        val current = minimumField.getInt(params)
        val restored = reservation.minimum.restore(current)
        var changed = current != restored
        if (changed) minimumField.setInt(params, restored)
        if (reservation.appliedHeight != null && params.height == reservation.appliedHeight) {
            params.height = reservation.nativeHeight; changed = true
        }
        if (changed) view.layoutParams = params
        return changed
    }

    override fun close() { reservations.keys.toList().forEach(::restore) }
}
