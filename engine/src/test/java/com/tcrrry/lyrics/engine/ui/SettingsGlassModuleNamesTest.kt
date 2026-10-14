package com.tcrrry.lyrics.engine.ui

import org.junit.Assert.*
import org.junit.Test

class SettingsGlassModuleNamesTest {
    @Test fun coexistUsesOnlyItsOwnModuleResources() {
        assertEquals(listOf("dev.amenhancer.module.coexist"),
            settingsGlassModuleNames("com.tcrrry.ampplyrics.coexist"))
    }

    @Test fun ordinaryRetainsReleaseAndEmbeddedModuleLookup() {
        assertEquals(listOf("dev.amenhancer.module", "dev.amenhancer.module.debug"),
            settingsGlassModuleNames("com.apple.android.music"))
    }
}
