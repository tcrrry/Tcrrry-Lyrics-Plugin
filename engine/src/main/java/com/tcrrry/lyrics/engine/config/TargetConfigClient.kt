package com.tcrrry.lyrics.engine.config

import android.content.SharedPreferences
import android.os.ParcelFileDescriptor
import com.tcrrry.lyrics.engine.model.CustomLyricsManifest
import com.tcrrry.lyrics.engine.model.FeatureHealth
import com.tcrrry.lyrics.engine.model.ModuleSettings
import java.io.InputStream

class TargetConfigClient private constructor(
    private val valuesProvider: () -> Map<String, *>,
    private val fileOpener: ((String) -> InputStream)?,
    private val remoteFileOpener: ((String) -> ParcelFileDescriptor)?,
    private val healthReporter: (FeatureHealth) -> Unit = {},
) {
    constructor(
        preferences: SharedPreferences,
        remoteFileOpener: ((String) -> ParcelFileDescriptor)? = null,
    ) : this(
        valuesProvider = preferences::getAll,
        fileOpener = remoteFileOpener?.let { opener ->
            { name -> ParcelFileDescriptor.AutoCloseInputStream(opener(name)) }
        },
        remoteFileOpener = remoteFileOpener,
    )

    constructor(reader: ConfigurationReader, healthReporter: (FeatureHealth) -> Unit = {}) : this(
        valuesProvider = reader::values,
        healthReporter = healthReporter,
        fileOpener = { name -> reader.openFile(name) ?: error("Configuration file is unavailable: $name") },
        remoteFileOpener = { name ->
            reader.openFileDescriptor(name)
                ?: error("Configuration file descriptor is unavailable: $name")
        },
    )

    @Volatile
    private var cachedIndex: CachedIndex? = null

    init {
        active = this
    }
    /** Ordinary feature settings only; never opens the potentially large lyrics index. */
    fun settings(): ModuleSettings = ModuleSettingsSchema.decode(valuesProvider())

    /** Background custom-lyrics index read. */
    fun customLyricsManifest(): CustomLyricsManifest {
        val values = valuesProvider()
        val pointer = ModuleSettingsSchema.decodeIndexPointer(values)
        val key = IndexCacheKey(
            pointer = pointer,
            legacyManifest = if (pointer == null) {
                ModuleSettingsSchema.legacyCustomLyricsManifestRaw(values)
            } else {
                null
            },
        )
        cachedIndex?.takeIf { it.key == key }?.let { return it.manifest }
        val state = CustomLyricsIndexRepository.state(values) { fileId ->
            runCatching { fileOpener?.invoke(fileId) }.getOrNull()
        }
        if (state.canCommit) cachedIndex = CachedIndex(key, state.manifest)
        return state.manifest
    }

    fun openRemoteFile(name: String): ParcelFileDescriptor? =
        runCatching { remoteFileOpener?.invoke(name) }.getOrNull()

    fun openFile(name: String): InputStream? =
        runCatching { fileOpener?.invoke(name) }.getOrNull()

    fun openFileDescriptor(name: String): ParcelFileDescriptor? =
        runCatching { remoteFileOpener?.invoke(name) }.getOrNull()

    fun reportHealth(health: FeatureHealth) {
        healthReporter(health)
    }

    companion object {
        @Volatile
        private var active: TargetConfigClient? = null

        fun currentSettings(): ModuleSettings = active?.settings()
            ?: ModuleSettings(phoneLiquidGlassEnabled = false)
    }

    private data class IndexCacheKey(
        val pointer: CustomLyricsIndexPointer?,
        val legacyManifest: String?,
    )

    private data class CachedIndex(
        val key: IndexCacheKey,
        val manifest: CustomLyricsManifest,
    )
}
