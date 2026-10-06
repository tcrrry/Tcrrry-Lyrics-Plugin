package com.tcrrry.lyrics.engine.hook

import android.view.View

enum class HostPageFamily { LEGACY_ACTIVITY, FRAGMENT_VIEW }
enum class NavigationPlacement { TOP, BOTTOM }
enum class PlayerPane { SONG, QUEUE }

data class PlayerRegions(
    val navigation: View?,
    val navigationPlacement: NavigationPlacement,
    val miniPlayer: View?,
    val player: View?,
    /** A FragmentContainerView may accept only native Fragment roots. */
    val restrictedPlayerContainer: Boolean,
)

/** Regions are independent: a top navigation must never imply a mini-player position. */
interface PlayerSurfacePort {
    val pageFamily: HostPageFamily
    val viewSessionIdentity: Any
    fun regions(): PlayerRegions
}
