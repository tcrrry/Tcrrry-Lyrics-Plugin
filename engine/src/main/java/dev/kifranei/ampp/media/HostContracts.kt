package dev.kifranei.ampp.media

import dev.amenhancer.plugin.api.PluginContext
import dev.amenhancer.plugin.api.PluginUnsupportedException
import org.json.JSONObject
import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal data class TargetBuild(val packageName: String, val versionName: String, val versionCode: Long) {
    val displayName get() = "$versionName ($versionCode)"
}
/** One shipped host profile: the exact host build it describes and the asset holding its contracts. */
internal data class ProfileEntry(val packageName: String, val versionName: String, val versionCode: Long, val asset: String) {
    val displayName get() = "$versionName / $versionCode"
}

/** assets/profiles.json lists every shipped profile; adding a host version is one JSON file plus one entry. */
internal object ProfileIndex {
    const val ASSET = "profiles.json"
    fun profilePackageName(packageName: String): String =
        if (packageName == "com.tcrrry.ampplyrics.coexist") "com.apple.android.music" else packageName

    fun parse(text: String): List<ProfileEntry> {
        val root = JSONObject(text)
        val packageName = root.getString("packageName")
        val array = root.getJSONArray("profiles")
        val entries = (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            ProfileEntry(packageName, item.getString("versionName"), item.getLong("versionCode"), item.getString("asset"))
        }
        check(entries.map { it.versionCode to it.versionName }.toSet().size == entries.size) { "Duplicate profile version" }
        return entries
    }
    /** Exact (normalized package, versionName, versionCode) match only; no range or nearest-version fallback. */
    fun select(entries: List<ProfileEntry>, packageName: String, versionName: String, versionCode: Long): ProfileEntry? {
        val targetPackage = profilePackageName(packageName)
        return entries.firstOrNull { it.packageName == targetPackage && it.versionName == versionName && it.versionCode == versionCode }
    }
    fun unsupportedMessage(entries: List<ProfileEntry>) =
        "仅适配 Apple Music " + entries.joinToString("、") { it.displayName }
}

internal object PluginProfiles {
    lateinit var document: JSONObject
    private lateinit var loader: ClassLoader
    private var selected: ProfileEntry? = null
    /** Choose the profile for the host build; unsupported builds are refused before any hook is installed. */
    fun load(context: PluginContext) {
        val entries = context.openAsset(ProfileIndex.ASSET).bufferedReader().use { ProfileIndex.parse(it.readText()) }
        val entry = ProfileIndex.select(entries, context.hostPackageName, context.hostVersionName, context.hostVersionCode)
            ?: throw PluginUnsupportedException(ProfileIndex.unsupportedMessage(entries) +
                "；当前为 ${context.hostVersionName} / ${context.hostVersionCode}")
        val loaded = context.openAsset(entry.asset).bufferedReader().use { JSONObject(it.readText()) }
        check(loaded.getString("packageName") == entry.packageName && loaded.getString("versionName") == entry.versionName &&
            loaded.getLong("versionCode") == entry.versionCode) { "Profile ${entry.asset} does not describe ${entry.displayName}" }
        loader = context.hostClassLoader
        document = loaded
        selected = entry
    }
    val selectedAsset: String? get() = selected?.asset
    fun find(packageName: String, versionName: String, versionCode: Long): PluginProfiles? =
        takeIf {
            val targetPackage = ProfileIndex.profilePackageName(packageName)
            selected?.let { it.packageName == targetPackage && it.versionName == versionName && it.versionCode == versionCode } == true
        }
    fun type(name: String): Class<*> = when (name) {
        "void" -> Void.TYPE
        "int" -> Integer.TYPE
        "long" -> java.lang.Long.TYPE
        "float" -> java.lang.Float.TYPE
        "boolean" -> java.lang.Boolean.TYPE
        // ClassLoader.loadClass cannot resolve array descriptors such as "[Lfoo.Bar;" (self-check reported
        // editorial-video-url-selector as ClassNotFoundException on every build); Class.forName can.
        else -> if (name.startsWith("[")) Class.forName(name, false, loader) else loader.loadClass(name)
    }
    fun method(key: String): Method = try { resolveMethod(key) } catch (error: Throwable) {
        MediaDiag.event("contract", "method '$key' unresolved: ${describeContract("methodContracts", key)} " +
            "(capability ${MediaDiag.capability ?: "late"})", error, key = "contract|m|$key")
        throw error
    }
    private fun resolveMethod(key: String): Method {
        val contract = document.getJSONObject("indexed").getJSONObject("methodContracts").getJSONObject(key)
        val parameters = contract.getJSONArray("parameters")
        return type(contract.getString("owner")).getDeclaredMethod(contract.getString("name"),
            *(0 until parameters.length()).map { type(parameters.getString(it)) }.toTypedArray()).apply {
            check(returnType == type(contract.getString("returns")) && Modifier.isStatic(modifiers) == contract.getBoolean("static")) {
                "return/static mismatch"
            }
            isAccessible = true
        }
    }
    private fun resolveField(key: String): java.lang.reflect.Field {
        val contract = document.getJSONObject("indexed").getJSONObject("fieldContracts").getJSONObject(key)
        return type(contract.getString("owner")).getDeclaredField(contract.getString("name")).apply {
            check(type == PluginProfiles.type(contract.getString("type")) && !Modifier.isStatic(modifiers)) { "type/static mismatch" }
            isAccessible = true
        }
    }
    /** Human-readable `owner.name(params)` of a contract for diagnostics; never throws. */
    fun describeContract(section: String, key: String): String = runCatching {
        val contract = document.getJSONObject("indexed").getJSONObject(section).optJSONObject(key) ?: return "missing in ${selected?.asset}"
        val params = contract.optJSONArray("parameters")?.let { array -> (0 until array.length()).joinToString(",") { array.getString(it) } }
        "${contract.optString("owner")}.${contract.optString("name")}" + (params?.let { "($it)" } ?: ":${contract.optString("type")}")
    }.getOrDefault("?")
    /** Resolves every method/field contract in the selected profile once; returns the unresolved keys. Never throws. */
    fun selfCheck(): List<String> {
        val broken = ArrayList<String>()
        runCatching {
            val indexed = document.getJSONObject("indexed")
            for ((section, resolve) in listOf<Pair<String, (String) -> Any>>("methodContracts" to ::resolveMethod, "fieldContracts" to ::resolveField)) {
                val keys = indexed.optJSONObject(section)?.keys()?.asSequence()?.toList() ?: continue
                keys.forEach { key ->
                    runCatching { resolve(key) }.onFailure { broken += "$section/$key=${describeContract(section, key)} (${it.javaClass.simpleName})" }
                }
            }
        }.onFailure { broken += "selfCheck:${it.javaClass.simpleName}" }
        return broken
    }
    fun field(key: String): java.lang.reflect.Field = try { resolveField(key) } catch (error: Throwable) {
        MediaDiag.event("contract", "field '$key' unresolved: ${describeContract("fieldContracts", key)} " +
            "(capability ${MediaDiag.capability ?: "late"})", error, key = "contract|f|$key")
        throw error
    }
}
internal sealed interface TargetCapabilityInstall {
    val message: String
    data class Active(override val message: String) : TargetCapabilityInstall
    data class Unsupported(override val message: String) : TargetCapabilityInstall
}
internal fun interface PlayerAudioOutputTarget { fun install(): TargetCapabilityInstall }
internal fun interface AudioQualityDialogTarget { fun install(present: (AudioQualityDialogSurface) -> Boolean): TargetCapabilityInstall }
internal fun interface CardSharingTarget { fun install(export: (NativeShareImage, Boolean) -> Unit): TargetCapabilityInstall }
