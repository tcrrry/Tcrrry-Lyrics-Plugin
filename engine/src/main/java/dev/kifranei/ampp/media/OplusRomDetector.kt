package dev.kifranei.ampp.media

import android.os.Build
import java.util.Locale

/**
 * Detects OPPO / realme / OnePlus devices (ColorOS, realme UI, OxygenOS). Their larger default
 * DPI breaks the player volume bar, so that feature is force-disabled there.
 */
internal object OplusRomDetector {
    private val vendors = setOf("oppo", "realme", "oneplus")
    /** Properties whose mere non-blank presence identifies an OPlus ROM. */
    private val presenceKeys = listOf(
        "ro.build.version.opporom", "ro.build.version.oplusrom", "ro.build.version.oplusrom.display",
        "ro.build.version.realmeui", "ro.build.version.realme", "ro.oxygen.version",
    )
    /** Properties checked for an OPlus ROM name in their value. */
    private val valueKeys = listOf("ro.build.ota.versionname", "ro.rom.version", "ro.build.display.id", "ro.build.version.ota")
    private val romMarkers = listOf("coloros", "realme ui", "realmeui", "oxygen", "hydrogen")
    val propertyKeys: List<String> = presenceKeys + valueKeys

    fun isOplus(manufacturer: String?, brand: String?, properties: Map<String, String?>): Boolean {
        fun vendor(value: String?) = value?.trim()?.lowercase(Locale.ROOT)?.replace(" ", "") in vendors
        if (vendor(manufacturer) || vendor(brand)) return true
        if (presenceKeys.any { !properties[it].isNullOrBlank() }) return true
        return valueKeys.any { key ->
            val value = properties[key]?.lowercase(Locale.ROOT) ?: return@any false
            romMarkers.any(value::contains)
        }
    }

    /** Cached result for the running device; every failure falls back to "not OPlus". */
    val current: Boolean by lazy {
        runCatching {
            isOplus(runCatching { Build.MANUFACTURER }.getOrNull(), runCatching { Build.BRAND }.getOrNull(),
                propertyKeys.associateWith(::systemProperty))
        }.getOrDefault(false)
    }

    private fun systemProperty(key: String): String? = runCatching {
        val type = Class.forName("android.os.SystemProperties")
        type.getMethod("get", String::class.java, String::class.java).invoke(null, key, "") as? String
    }.getOrNull()
}
