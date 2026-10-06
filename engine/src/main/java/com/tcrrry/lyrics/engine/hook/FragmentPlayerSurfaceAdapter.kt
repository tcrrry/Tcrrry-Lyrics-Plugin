package com.tcrrry.lyrics.engine.hook

import android.view.View
import com.tcrrry.lyrics.engine.host.HostViewSessionController

/**
 * Fragment architecture seam. The future native adapter supplies resolved regions at view creation;
 * this class never loads a beta class or adds an overlay to a restricted Fragment container.
 */
internal class FragmentPlayerSurfaceAdapter(
    private val regionsOf: (View) -> PlayerRegions,
    private val onReleased: (View) -> Unit,
) : AutoCloseable {
    private val sessions = HostViewSessionController<View, Session> { root, _ -> Session(root, regionsOf(root)) }
    fun onViewCreated(root: View, configurationRevision: Long): PlayerSurfacePort = sessions.bind(root,configurationRevision)
    fun onDestroyView(root: View) = sessions.destroy(root)
    override fun close() = sessions.close()
    private inner class Session(private val root: View, private val resolved: PlayerRegions) : PlayerSurfacePort, AutoCloseable {
        override val pageFamily = HostPageFamily.FRAGMENT_VIEW
        override val viewSessionIdentity: Any get() = root
        override fun regions() = resolved
        override fun close() = onReleased(root)
    }
}
