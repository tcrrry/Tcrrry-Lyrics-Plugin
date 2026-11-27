package dev.kifranei.ampp.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class HostBackdropCompatTest {
    // Kotlin `$default` masks index declared parameters; the extension receiver is not counted.
    @Test fun newHostMaskKeepsHighlightShadowDefaultsAndExplicitSurface() {
        // 15 params: Modifier, backdrop, shape, effects, highlight, shadow, innerShadow, layerBlock, clip,
        // exported, minLayerSize, onDrawBehind, onDrawBackdrop, onDrawSurface(13), onDrawFront
        val mask = HostBackdropCompat.defaultMask(15, 13)
        assertEquals(0x2FF8, mask)
        assertEquals(8, mask and 8)            // highlight uses the host default
        assertEquals(0, mask and (1 shl 12))   // onDrawSurface is passed explicitly
        assertEquals(0, mask and 7)            // backdrop/shape/effects explicit
    }

    @Test fun noHighlightMaskOnlyClearsHighlightBit() {
        assertEquals(0x2FF8 and 8.inv(), HostBackdropCompat.defaultMask(15, 13, hostHighlight = false))
        assertEquals(0x17F8 and 8.inv(), HostBackdropCompat.defaultMask(14, 12, hostHighlight = false))
        assertEquals(0x2FF8, HostBackdropCompat.defaultMask(15, 13, hostHighlight = true))
    }

    @Test fun oldHostMask() = assertEquals(0x17F8, HostBackdropCompat.defaultMask(14, 12))

    @Test fun ampp171EighteenParamMaskKeepsOnDrawSurfaceExplicit() {
        val mask = HostBackdropCompat.defaultMask(16, 13, hostHighlight = true)
        assertEquals(0, mask and (1 shl 12))
        assertEquals(8, mask and 8)
    }

    @Test fun bundledHostJarSignatureMatchesMask() {
        val owner = Class.forName("com.kyant.backdrop.DrawBackdropModifierKt")
        val m = owner.declaredMethods.first { it.name.startsWith("drawBackdrop-") && it.name.endsWith("\$default") }
        assertNotNull(m)
        val size = m.parameterTypes.size - 2
        // The surface lambda is the second-to-last Function1 before onDrawFront.
        val surface = size - 2
        assertEquals(m.parameterTypes[surface], kotlin.jvm.functions.Function1::class.java)
        assertEquals(0, HostBackdropCompat.defaultMask(size, surface) and (1 shl (surface - 1)))
    }

    @Test fun playlistMenuLayoutKeepsExportRowAndPlaylistShareShortcut() {
        val actions = listOf(
            PlayerMenuAction("SORT_PLAYLIST", 1),
            PlayerMenuAction("EDIT_PLAYLIST", 2),
            PlayerMenuAction("SHARE_PLAYLIST", 3),
            PlayerMenuAction("ADD_TO_LIBRARY", 4),
            PlayerMenuAction("tcrrry_export_playlist_menu_row", 5),
            PlayerMenuAction("DELETE_FROM_LIBRARY", 6),
        )
        val grouped = PlayerMenuLayout.groups(actions, isPlaylist = true)
        assertEquals(3, grouped.shortcuts.size)
        assertEquals("ADD_TO_LIBRARY", grouped.shortcuts[0]?.key)
        assertEquals("SHARE_PLAYLIST", grouped.shortcuts[2]?.key)
        val flatSectionKeys = grouped.sections.flatten().map { it.key }
        assertEquals(
            listOf("SORT_PLAYLIST", "EDIT_PLAYLIST", "tcrrry_export_playlist_menu_row", "DELETE_FROM_LIBRARY"),
            flatSectionKeys,
        )
    }
}
