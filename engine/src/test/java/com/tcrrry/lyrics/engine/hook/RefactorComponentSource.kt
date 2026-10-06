package com.tcrrry.lyrics.engine.hook
import java.io.File

/** Source contracts protect a responsibility, including all files extracted from its old owner. */
internal fun File.readRefactorComponent(): String {
    val root = generateSequence(absoluteFile.parentFile) { it.parentFile }
        .firstOrNull { File(it,"settings.gradle.kts").isFile } ?: return readText()
    val hook = "engine/src/main/java/com/tcrrry/lyrics/engine/hook/"
    val catalog = "app/src/main/java/io/github/proify/lyricon/amprovider/xposed/"
    val ui = "engine/src/main/java/com/tcrrry/lyrics/engine/ui/"
    val components = when (name) {
        "AppleMusicDualPaneTarget.kt" -> listOf("DualPaneResourceBindings.kt","LegacyLyricsLayoutProfiles.kt",
            "AppleMusicDualPaneTarget.kt","LegacyTabletQualification.kt","DualPaneViewMount.kt",
            "DualPaneFragmentStateBridge.kt","DualPaneReflectionAccess.kt").map { hook+it }
        "HleMetadataSurfaceBridge.kt" -> listOf(hook+"HleMetadataSurfaceBridge.kt",hook+"MetadataHostAdapters.kt")
        "AppleInternalCatalogResolver.kt" -> listOf("AppleInternalCatalogResolver.kt","CatalogPersistentAccess.kt",
            "CatalogRequestScheduling.kt","CatalogOriginalResolution.kt","NativeCatalogQueryAccess.kt",
            "CatalogResponseSnapshotAccess.kt").map { catalog+it }
        "EmbeddedSettingsHost.kt" -> listOf(ui+"EmbeddedSettingsHost.kt")
        else -> return readText()
    }
    return components.joinToString("\n") { File(root,it).readText() }
}
