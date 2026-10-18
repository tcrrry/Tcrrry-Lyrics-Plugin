package com.tcrrry.lyrics.engine.ui

import org.junit.Assert.*
import org.junit.Test

class HostLiquidControlsTest {
    @Test fun legacyCompileReferencesResolveExactly() {
        assertEquals(HostLiquidControls.tabsLegacy,
            HostLiquidControls.tabs.parameterTypes.map { it.name })
        assertEquals(HostLiquidControls.tabLegacy,
            HostLiquidControls.tab.parameterTypes.map { it.name })
    }

    @Test fun rejectsUnverifiedNameOrParameterOrder() {
        val method = HostLiquidControls.tab
        assertFalse(HostLiquidControls.matches(method, "UnknownTab", HostLiquidControls.tabLegacy))
        val reordered = HostLiquidControls.tabLegacy.toMutableList()
        java.util.Collections.swap(reordered, 0, 1)
        assertFalse(HostLiquidControls.matches(method, method.name, reordered))
        assertThrows(IllegalStateException::class.java) {
            HostLiquidControls.select(arrayOf(method), listOf(method.name to HostLiquidControls.tab1017))
        }
    }
}
