package com.tcrrry.lyrics.engine.lyrics

import org.json.JSONArray
import java.security.MessageDigest

/** Read the module APK, not the host application's AssetManager in embedded mode. */
internal object PronunciationModules {
    data class Resource(val language: String, val name: String, val size: Long, val sha256: String)
    private fun read(loader: ClassLoader, name: String): ByteArray =
        com.tcrrry.lyrics.plugin.PluginLifetime.context.openAsset(name).use { it.readBytes() }
    fun resources(language: String, loader: ClassLoader = requireNotNull(PronunciationModules::class.java.classLoader)): List<Resource> {
        val array = JSONArray(read(loader, "pronunciation-modules.json").toString(Charsets.UTF_8))
        return (0 until array.length()).map { index -> array.getJSONObject(index).let {
            Resource(it.getString("language"), it.getString("name"), it.getLong("size"), it.getString("sha256"))
        } }.filter { it.language == language }
    }
    fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun files(language: String, loader: ClassLoader = requireNotNull(PronunciationModules::class.java.classLoader)): List<String> = resources(language, loader).map { resource ->
        val bytes = read(loader, "pronunciation/$language/${resource.name}")
        require(bytes.size.toLong() == resource.size && digest(bytes) == resource.sha256) { "注音资源校验失败" }
        bytes.toString(Charsets.UTF_8)
    }
}
