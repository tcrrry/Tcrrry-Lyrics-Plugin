package com.tcrrry.lyrics.host.applemusic

import org.json.JSONArray
import org.json.JSONObject
import java.util.zip.ZipFile
import java.util.concurrent.ConcurrentHashMap

/** Exact build knowledge shared by bootstrap, adapters and the offline verifier. */
object AppleMusicHostProfiles {
    @Volatile private var apkPaths: List<String> = emptyList()
    fun initializeModulePaths(paths: List<String>) { apkPaths = paths.distinct() }

    private val filenames: List<String> by lazy {
        val index = read("index.json")
        check(index.getInt("schemaVersion") == 1)
        index.getJSONArray("profiles").strings().also { require(it.distinct().size == it.size) }
    }
    private val loaded = ConcurrentHashMap<String, AppleMusicHostProfile>()
    private fun load(name: String): AppleMusicHostProfile = loaded.computeIfAbsent(name) {
        AppleMusicHostProfile(read(name)).also { profile ->
            require(name == "${profile.versionName}-${profile.versionCode}.json")
        }
    }
    val all: List<AppleMusicHostProfile> by lazy {
        filenames.map(::load).also { profiles ->
            check(profiles.map { Triple(it.packageName, it.versionName, it.versionCode) }.distinct().size == profiles.size)
        }
    }

    fun find(packageName: String, versionName: String, versionCode: Long): AppleMusicHostProfile? {
        val name = "$versionName-$versionCode.json"
        if (name !in filenames) return null
        return load(name).takeIf { it.packageName == profilePackage(packageName) }
    }

    fun profilePackage(packageName: String): String = if (
        packageName == com.tcrrry.lyrics.engine.ModuleConstants.TARGET_PACKAGE &&
        packageName == "com.tcrrry.ampplyrics.coexist"
    ) com.tcrrry.lyrics.engine.ModuleConstants.RESOURCE_PACKAGE else packageName

    fun isProductionBuild(packageName: String, versionName: String, versionCode: Long): Boolean =
        find(packageName, versionName, versionCode)?.productionEnabled == true

    fun supportsGlass(versionCode: Long, versionName: String): Boolean =
        find("com.apple.android.music", versionName, versionCode)?.let { it.productionEnabled && it.capability("glass") } == true

    fun supportsCellular(packageName: String, versionName: String, versionCode: Long): Boolean =
        find(packageName, versionName, versionCode)?.let { it.productionEnabled && it.capability("cellular") } == true

    fun catalogQueryRename(owner: String, preferredName: String): String? =
        all.firstNotNullOfOrNull { it.document.optJSONObject("catalogQueryRenames")?.optJSONObject(owner)
            ?.optString(preferredName)?.takeIf(String::isNotBlank) }

    private fun read(name: String): JSONObject {
        require(Regex("[A-Za-z0-9._-]+\\.json").matches(name))
        val path = "host-profiles/$name"
        return com.tcrrry.lyrics.plugin.PluginLifetime.context.openAsset(path).bufferedReader().use { JSONObject(it.readText()) }
    }
}

class AppleMusicHostProfile internal constructor(val document: JSONObject) {
    init { require(document.getInt("schemaVersion") == 1) }
    val id: String = document.getString("id")
    val packageName: String = document.getString("packageName")
    val versionName: String = document.getString("versionName")
    val versionCode: Long = document.getLong("versionCode")
    val productionEnabled: Boolean = document.getBoolean("productionEnabled")
    val family: String = document.getString("family")
    fun capability(key: String): Boolean = document.getJSONObject("capabilities").optBoolean(key, false)
}

fun JSONObject.stringMap(): Map<String, String> = keys().asSequence().associateWith(::getString)
fun JSONArray.strings(): List<String> = List(length()) { getString(it) }
