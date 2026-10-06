package com.tcrrry.lyrics.engine.config

import com.tcrrry.lyrics.engine.model.*
import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(manifest = org.robolectric.annotation.Config.NONE, sdk = [35])
class LyricGlowSettingsTest {
    @org.junit.Before fun attachPlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.attach() }
    @org.junit.After fun closePlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.current.close() }
    @Test fun oldOrInvalidSettingsKeepCurrentTriggerDefaults() {
        assertEquals(100, ModuleSettingsSchema.decode(emptyMap<String, Any>()).lyricGlowSensitivity)
        assertEquals(LyricGlowPosition.ALL, ModuleSettingsSchema.decode(mapOf("lyric_glow_position" to "unknown")).lyricGlowPosition)
        assertEquals(50, ModuleSettingsSchema.decode(mapOf("lyric_glow_sensitivity" to -10)).lyricGlowSensitivity)
        assertEquals(500, ModuleSettingsSchema.decode(mapOf("lyric_glow_sensitivity" to 900)).lyricGlowSensitivity)
    }
    @Test fun savedSensitivityAndPositionSurviveOrdinarySettingsRoundTrip() {
        for (position in LyricGlowPosition.entries) for (sensitivity in listOf(175, 200, 500)) {
            val saved = ModuleSettings(lyricGlowSensitivity = sensitivity, lyricGlowPosition = position)
            val restored = ModuleSettingsSchema.decode(ModuleSettingsSchema.encodeOrdinarySettings(saved))
            assertEquals(sensitivity, restored.lyricGlowSensitivity)
            assertEquals(position, restored.lyricGlowPosition)
        }
    }
}
