package dev.kifranei.ampp.media

import org.json.JSONObject

/** Material of the liquid-glass popups (sleep timer, delete confirmation, audio quality); the More menu keeps its own look. */
internal enum class PopupGlassStyle(val key: String) {
    BOTTOM_BAR("bottom_bar"),
    MORE_MENU("more_menu"),
    NO_FROST("no_frost");

    companion object {
        val default = BOTTOM_BAR
        @Volatile var current: PopupGlassStyle = default
        fun fromKey(key: String?) = values().firstOrNull { it.key == key } ?: default
    }
}

/**
 * Mirrors standalone Kifranei (`dev.kifranei.ampp.media`) `settings.json` schema
 * (including legacy keys) so [KifraneiConflictDetector] can determine precisely
 * which exclusive capabilities the standalone plugin claims.
 */
internal data class KifraneiExternalSettings(
    val lyricon: Boolean = false,
    val iosControls: Boolean = false,
    val sharing: Boolean = false,
    val playerGlass: Boolean = false,
    val playerHandle: Boolean = false,
    val systemCorners: Boolean = false,
    val volumeBar: Boolean = false,
    val contentDownloads: Boolean = false,
    val popupGlassStyle: PopupGlassStyle = PopupGlassStyle.default,
    val autoTransition: Boolean = false,
    val autoTransitionDuration: Int = 12,
    val autoTransitionSpeedMatch: Boolean = false,
    val autoTransitionAnimate: Boolean = true,
    val meizuTicker: Boolean = false,
    val mediaNotificationLyrics: Boolean = false,
    val superLyric: Boolean = false,
    val lyricGetter: Boolean = false,
) {
    companion object {
        fun decode(text: String): KifraneiExternalSettings {
            val json = JSONObject(text)
            val legacyChrome = json.opt("player_chrome") as? Boolean ?: false
            val legacyGlass = listOf("quality_glass", "more_glass", "confirmation_glass", "sleep_glass")
                .any { json.opt(it) == true }
            return KifraneiExternalSettings(
                lyricon = json.opt("lyricon") as? Boolean ?: false,
                iosControls = json.opt("ios_controls") as? Boolean ?: false,
                sharing = json.opt("sharing") as? Boolean ?: (json.opt("lyrics_sharing") as? Boolean ?: false),
                playerGlass = json.opt("player_glass") as? Boolean ?: legacyGlass,
                playerHandle = json.opt("player_handle") as? Boolean ?: legacyChrome,
                systemCorners = json.opt("system_corners") as? Boolean ?: legacyChrome,
                volumeBar = json.opt("volume_bar") as? Boolean ?: false,
                contentDownloads = json.opt("content_downloads") as? Boolean ?: false,
                popupGlassStyle = PopupGlassStyle.fromKey(json.opt("popup_glass_style") as? String),
                autoTransition = json.opt("auto_transition") as? Boolean ?: false,
                autoTransitionDuration = (json.opt("auto_transition_duration") as? Int ?: 12).coerceIn(2, 20),
                autoTransitionSpeedMatch = json.opt("auto_transition_speed_match") as? Boolean ?: false,
                autoTransitionAnimate = json.opt("auto_transition_animate") as? Boolean ?: true,
                meizuTicker = json.opt("meizu_ticker") as? Boolean ?: false,
                mediaNotificationLyrics = json.opt("media_notification_lyrics") as? Boolean ?: false,
                superLyric = json.opt("super_lyric") as? Boolean ?: false,
                lyricGetter = json.opt("lyric_getter") as? Boolean ?: false,
            )
        }
    }
}

/**
 * Persisted configuration for the ported Kifranei module inside Tcrrry Lyrics Plugin.
 * Appearance, sharing, and downloads default to true once the master toggle is enabled;
 * experimental Auto-Transition defaults to false.
 */
internal data class KifraneiPortableConfig(
    val masterEnabled: Boolean = false,
    val playerGlass: Boolean = true,
    val popupGlassStyle: PopupGlassStyle = PopupGlassStyle.BOTTOM_BAR,
    val playerHandle: Boolean = true,
    val systemCorners: Boolean = true,
    val volumeBar: Boolean = true,
    val iosControls: Boolean = true,
    val sharing: Boolean = true,
    val contentDownloads: Boolean = true,
    val autoTransition: Boolean = false,
    val autoTransitionDuration: Int = 0,
    val autoTransitionSpeedMatch: Boolean = true,
    val autoTransitionAnimate: Boolean = true,
    val autoTransitionSkipIntro: Boolean = false,
    val autoTransitionEqualizer: Boolean = false,
    val autoTransitionPreferAppleCurve: Boolean = false,
    val autoTransitionAllowAlbumSequential: Boolean = true,
    val autoTransitionAllowSingleRepeat: Boolean = false,
    val autoTransitionAllowSeekTrigger: Boolean = true,
    val autoTransitionShowQueueButton: Boolean = true,
) {
    fun clamped() = copy(
        autoTransitionDuration = if (autoTransitionDuration <= 0) 0 else autoTransitionDuration.coerceIn(2, 20),
    )
}
