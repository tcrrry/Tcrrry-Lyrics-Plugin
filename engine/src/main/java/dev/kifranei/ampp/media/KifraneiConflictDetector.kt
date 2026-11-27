package dev.kifranei.ampp.media

import org.json.JSONObject
import java.io.File

internal enum class KifraneiCapability {
    ARTWORK_RECOVERY,
    BACKGROUND_RECOVERY,
    PLAYER_GLASS,
    PLAYER_CHROME,
    VOLUME_BAR,
    IOS_CONTROLS,
    SHARING,
    CONTENT_DOWNLOADS,
    AUTO_TRANSITION,
}

internal data class ConflictSnapshot(
    val standaloneEnabled: Boolean,
    val yielded: Set<KifraneiCapability>,
    val standalonePopupStyle: PopupGlassStyle?,
    val parseFallbackAll: Boolean = false,
) {
    fun isYielded(cap: KifraneiCapability): Boolean = cap in yielded
}

/**
 * Inspects AM++ PluginStore's `installed.json` and `data/dev.kifranei.ampp.media/settings.json`
 * before claiming any exclusive `shared:applemusic-*` resources, yielding only the exact
 * capabilities that an active standalone Kifranei plugin (`dev.kifranei.ampp.media`) claims.
 */
internal object KifraneiConflictDetector {
    const val STANDALONE_PLUGIN_ID = "dev.kifranei.ampp.media"

    fun detect(pluginDataDirectory: File?, isOplusRom: Boolean = OplusRomDetector.current): ConflictSnapshot {
        val pluginsRoot = pluginDataDirectory?.parentFile?.parentFile
        return detectFromPluginsRoot(pluginsRoot, isOplusRom)
    }

    fun detectFromPluginsRoot(
        pluginsRoot: File?,
        isOplusRom: Boolean = OplusRomDetector.current,
    ): ConflictSnapshot {
        if (pluginsRoot == null) {
            return ConflictSnapshot(standaloneEnabled = false, yielded = emptySet(), standalonePopupStyle = null)
        }
        val installedFile = File(pluginsRoot, "installed.json")
        if (!installedFile.isFile) {
            return ConflictSnapshot(standaloneEnabled = false, yielded = emptySet(), standalonePopupStyle = null)
        }
        val entry = runCatching {
            val root = JSONObject(installedFile.readText(Charsets.UTF_8))
            root.optJSONObject(STANDALONE_PLUGIN_ID)
        }.getOrNull() ?: return ConflictSnapshot(standaloneEnabled = false, yielded = emptySet(), standalonePopupStyle = null)

        val deleted = entry.optBoolean("deleted", false)
        val enabled = entry.optBoolean("enabled", false)
        if (deleted || !enabled) {
            return ConflictSnapshot(standaloneEnabled = false, yielded = emptySet(), standalonePopupStyle = null)
        }

        val yielded = LinkedHashSet<KifraneiCapability>()
        // Kifranei installs these two capabilities unconditionally whenever enabled.
        yielded += KifraneiCapability.ARTWORK_RECOVERY
        yielded += KifraneiCapability.BACKGROUND_RECOVERY

        val settingsFile = File(File(File(pluginsRoot, "data"), STANDALONE_PLUGIN_ID), "settings.json")
        if (!settingsFile.isFile) {
            return ConflictSnapshot(
                standaloneEnabled = true,
                yielded = yielded.toSet(),
                standalonePopupStyle = PopupGlassStyle.default,
                parseFallbackAll = false,
            )
        }

        val parsed = runCatching {
            KifraneiExternalSettings.decode(settingsFile.readText(Charsets.UTF_8))
        }.getOrNull()

        if (parsed == null) {
            return ConflictSnapshot(
                standaloneEnabled = true,
                yielded = KifraneiCapability.values().toSet(),
                standalonePopupStyle = PopupGlassStyle.default,
                parseFallbackAll = true,
            )
        }

        if (parsed.playerGlass) yielded += KifraneiCapability.PLAYER_GLASS
        if (parsed.playerHandle || parsed.systemCorners) yielded += KifraneiCapability.PLAYER_CHROME
        if (parsed.volumeBar && !isOplusRom) yielded += KifraneiCapability.VOLUME_BAR
        if (parsed.iosControls) yielded += KifraneiCapability.IOS_CONTROLS
        if (parsed.sharing) yielded += KifraneiCapability.SHARING
        if (parsed.contentDownloads) yielded += KifraneiCapability.CONTENT_DOWNLOADS
        if (parsed.autoTransition) yielded += KifraneiCapability.AUTO_TRANSITION

        return ConflictSnapshot(
            standaloneEnabled = true,
            yielded = yielded.toSet(),
            standalonePopupStyle = parsed.popupGlassStyle,
            parseFallbackAll = false,
        )
    }
}
