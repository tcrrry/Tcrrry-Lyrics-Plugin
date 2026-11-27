package dev.kifranei.ampp.media

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class KifraneiConflictDetectorTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun prepareRoot(installedJson: String?, settingsJson: String?): File {
        val root = temp.newFolder()
        if (installedJson != null) {
            File(root, "installed.json").writeText(installedJson, Charsets.UTF_8)
        }
        if (settingsJson != null) {
            val dataDir = File(File(root, "data"), KifraneiConflictDetector.STANDALONE_PLUGIN_ID).apply { mkdirs() }
            File(dataDir, "settings.json").writeText(settingsJson, Charsets.UTF_8)
        }
        return root
    }

    @Test
    fun returnsNoYieldsWhenInstalledJsonMissingOrPluginAbsent() {
        val missingRoot = prepareRoot(null, null)
        val snapMissing = KifraneiConflictDetector.detectFromPluginsRoot(missingRoot, isOplusRom = false)
        assertFalse(snapMissing.standaloneEnabled)
        assertTrue(snapMissing.yielded.isEmpty())

        val otherOnly = prepareRoot("""{"com.tcrrry.lyrics":{"enabled":true,"deleted":false}}""", null)
        val snapAbsent = KifraneiConflictDetector.detectFromPluginsRoot(otherOnly, isOplusRom = false)
        assertFalse(snapAbsent.standaloneEnabled)
        assertTrue(snapAbsent.yielded.isEmpty())
    }

    @Test
    fun returnsNoYieldsWhenStandaloneDisabledOrDeleted() {
        val disabledRoot = prepareRoot(
            """{"dev.kifranei.ampp.media":{"enabled":false,"deleted":false,"version":"1.3.9"}}""",
            """{"player_glass":true,"sharing":true}""",
        )
        assertFalse(KifraneiConflictDetector.detectFromPluginsRoot(disabledRoot, false).standaloneEnabled)

        val deletedRoot = prepareRoot(
            """{"dev.kifranei.ampp.media":{"enabled":true,"deleted":true,"version":"1.3.9"}}""",
            """{"player_glass":true,"sharing":true}""",
        )
        assertFalse(KifraneiConflictDetector.detectFromPluginsRoot(deletedRoot, false).standaloneEnabled)
    }

    @Test
    fun yieldsOnlyUnconditionalRecoveryCapabilitiesWhenStandaloneHasNoSettingsFile() {
        val root = prepareRoot(
            """{"dev.kifranei.ampp.media":{"enabled":true,"deleted":false,"version":"1.3.9"}}""",
            null,
        )
        val snap = KifraneiConflictDetector.detectFromPluginsRoot(root, isOplusRom = false)
        assertTrue(snap.standaloneEnabled)
        assertFalse(snap.parseFallbackAll)
        assertEquals(
            setOf(KifraneiCapability.ARTWORK_RECOVERY, KifraneiCapability.BACKGROUND_RECOVERY),
            snap.yielded,
        )
        assertEquals(PopupGlassStyle.BOTTOM_BAR, snap.standalonePopupStyle)
    }

    @Test
    fun yieldsExactEnabledSubsetAndRespectsOplusAndPopupStyle() {
        val settings = JSONObject().apply {
            put("player_glass", true)
            put("popup_glass_style", "more_menu")
            put("player_handle", false)
            put("system_corners", true)
            put("volume_bar", true)
            put("ios_controls", false)
            put("sharing", true)
            put("content_downloads", false)
            put("auto_transition", true)
        }.toString()
        val root = prepareRoot(
            """{"dev.kifranei.ampp.media":{"enabled":true,"deleted":false}}""",
            settings,
        )

        val nonOplus = KifraneiConflictDetector.detectFromPluginsRoot(root, isOplusRom = false)
        assertEquals(PopupGlassStyle.MORE_MENU, nonOplus.standalonePopupStyle)
        assertEquals(
            setOf(
                KifraneiCapability.ARTWORK_RECOVERY,
                KifraneiCapability.BACKGROUND_RECOVERY,
                KifraneiCapability.PLAYER_GLASS,
                KifraneiCapability.PLAYER_CHROME,
                KifraneiCapability.VOLUME_BAR,
                KifraneiCapability.SHARING,
                KifraneiCapability.AUTO_TRANSITION,
            ),
            nonOplus.yielded,
        )
        assertFalse(nonOplus.isYielded(KifraneiCapability.IOS_CONTROLS))
        assertFalse(nonOplus.isYielded(KifraneiCapability.CONTENT_DOWNLOADS))

        val oplus = KifraneiConflictDetector.detectFromPluginsRoot(root, isOplusRom = true)
        assertFalse(oplus.isYielded(KifraneiCapability.VOLUME_BAR))
    }

    @Test
    fun failsSafeToYieldingAllCapabilitiesOnMalformedSettingsJson() {
        val root = prepareRoot(
            """{"dev.kifranei.ampp.media":{"enabled":true,"deleted":false}}""",
            "{not valid json",
        )
        val snap = KifraneiConflictDetector.detectFromPluginsRoot(root, isOplusRom = false)
        assertTrue(snap.standaloneEnabled)
        assertTrue(snap.parseFallbackAll)
        assertEquals(KifraneiCapability.values().toSet(), snap.yielded)
    }

    @Test
    fun decodesLegacyKifraneiKeysAndPopupStyle() {
        val decoded = KifraneiExternalSettings.decode(
            """{"player_chrome":true,"sleep_glass":true,"lyrics_sharing":true,"popup_glass_style":"no_frost"}""",
        )
        assertTrue(decoded.playerHandle)
        assertTrue(decoded.systemCorners)
        assertTrue(decoded.playerGlass)
        assertTrue(decoded.sharing)
        assertEquals(PopupGlassStyle.NO_FROST, decoded.popupGlassStyle)
    }

    @Test
    fun detectsViaPluginDataDirectoryPathHierarchy() {
        val root = prepareRoot(
            """{"dev.kifranei.ampp.media":{"enabled":true,"deleted":false}}""",
            """{"content_downloads":true}""",
        )
        val ownDataDir = File(File(root, "data"), "com.tcrrry.lyrics").apply { mkdirs() }
        val snap = KifraneiConflictDetector.detect(ownDataDir, isOplusRom = false)
        assertTrue(snap.standaloneEnabled)
        assertTrue(snap.isYielded(KifraneiCapability.CONTENT_DOWNLOADS))
        assertFalse(snap.isYielded(KifraneiCapability.SHARING))
    }
}
