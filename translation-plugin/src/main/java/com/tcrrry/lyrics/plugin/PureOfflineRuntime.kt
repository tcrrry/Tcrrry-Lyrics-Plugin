package com.tcrrry.lyrics.plugin

import android.content.Context
import android.os.Build
import android.os.Process
import dalvik.system.DexClassLoader
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.lang.reflect.InvocationTargetException
import java.security.MessageDigest
import java.util.zip.ZipFile

internal data class StagedOfflineRuntime(val apk: File, val libraries: File, val optimized: File)

/** Bounded, hash-pinned staging exclusively below SDK cache. */
internal object OfflinePayloadStore {
    private const val MAX_PAYLOAD = 96L * 1024 * 1024
    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(65536); while (true) {
            val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count)
        } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    internal fun copyVerified(input: InputStream, target: File, bytes: Long, digest: String) {
        require(bytes in 1..MAX_PAYLOAD && digest.matches(Regex("[0-9a-f]{64}")))
        check(target.parentFile!!.mkdirs() || target.parentFile!!.isDirectory)
        if (target.isFile) {
            check(target.length() == bytes && hash(target) == digest) { "离线引擎缓存损坏，请清理插件缓存后重试" }
            check(target.setReadOnly()); return
        }
        val temp = File.createTempFile("payload-", ".tmp", target.parentFile)
        try {
            FileOutputStream(temp).use { output ->
                check(temp.setReadOnly()) // Android 14+ dynamic-code rule: mark before writing.
                val buffer = ByteArray(65536); var copied = 0L
                while (true) {
                    val count = input.read(buffer); if (count < 0) break
                    copied += count; check(copied <= bytes) { "Offline payload exceeds declared size" }
                    output.write(buffer, 0, count)
                }
                output.fd.sync(); check(copied == bytes)
            }
            check(hash(temp) == digest) { "Offline payload SHA-256 mismatch" }
            check(temp.renameTo(target))
        } finally { if (temp.exists()) { temp.setWritable(true); temp.delete() } }
    }
    @Synchronized fun stage(cache: File, metadata: JSONObject, abis: List<String>, asset: () -> InputStream): StagedOfflineRuntime {
        val digest = metadata.getString("sha256")
        require(digest.matches(Regex("[0-9a-f]{64}")))
        val root = File(cache, "pure-offline/$digest").apply { check(mkdirs() || isDirectory) }
        val apk = File(root, "runtime.apk")
        asset().use { copyVerified(it, apk, metadata.getLong("bytes"), digest) }
        val available = metadata.getJSONObject("native")
        val abi = abis.firstOrNull { it.matches(Regex("[A-Za-z0-9_-]+")) && available.has(it) }
            ?: error("离线引擎不支持当前处理器")
        val libraries = File(root, "lib/$abi").apply { check(mkdirs() || isDirectory) }
        val entry = available.getJSONObject(abi)
        ZipFile(apk).use { zip ->
            val native = checkNotNull(zip.getEntry("lib/$abi/libtranslate_jni.so"))
            zip.getInputStream(native).use { copyVerified(it, File(libraries, "libtranslate_jni.so"), entry.getLong("bytes"), entry.getString("sha256")) }
        }
        return StagedOfflineRuntime(apk, libraries, File(root, "odex").apply { check(mkdirs() || isDirectory) })
    }
}

/** Isolate ML Kit/Firebase from host dependencies, while sharing platform/Kotlin types. */
internal class OfflineRuntimeClassLoader(payload: StagedOfflineRuntime, parent: ClassLoader) :
    DexClassLoader(payload.apk.path, payload.optimized.path, payload.libraries.path, parent) {
    override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(this) {
        var type = findLoadedClass(name)
        if (type == null && (name.startsWith("com.google.") || name.startsWith("com.tcrrry.lyrics.offline.runtime."))) {
            type = try { findClass(name) } catch (_: ClassNotFoundException) { null }
        }
        if (type == null) type = super.loadClass(name, false)
        if (resolve) resolveClass(type)
        type!!
    }
}

internal object PureOfflineRuntime {
    private var instance: Any? = null
    private var stopped = false
    @Synchronized fun initialize(context: Context): Any {
        check(PluginLifetime.alive && !stopped) { "离线翻译插件已停止" }
        instance?.let { return it }
        val sdk = PluginLifetime.context
        val metadata = sdk.openAsset("pure-offline/runtime.json").bufferedReader().use { JSONObject(it.readText()) }
        val abis = if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS
        val payload = OfflinePayloadStore.stage(sdk.cacheDirectory, metadata, abis.toList()) { sdk.openAsset("pure-offline/runtime.apk") }
        val loader = OfflineRuntimeClassLoader(payload, PureOfflineRuntime::class.java.classLoader!!)
        val type = loader.loadClass("com.tcrrry.lyrics.offline.runtime.PluginTranslationRuntime")
        val models = File(sdk.dataDirectory, "pure-offline-models").apply { check(mkdirs() || isDirectory) }
        val chinesePin = sdk.openAsset("bundled-models/zh.json").bufferedReader().use { JSONObject(it.readText()) }
        val chinese = File(sdk.cacheDirectory, "pure-offline/zh-${chinesePin.getString("sha256")}.zip")
        sdk.openAsset("bundled-models/zh.zip").use {
            OfflinePayloadStore.copyVerified(it, chinese, chinesePin.getLong("bytes"), chinesePin.getString("sha256"))
        }
        val created = type.getConstructor(Context::class.java, File::class.java, File::class.java, File::class.java)
            .newInstance(context.applicationContext, models, payload.apk, chinese)
        instance = created
        sdk.onClose { synchronized(this) { stopped = true; (instance as? AutoCloseable)?.close(); instance = null } }
        check(PluginLifetime.alive && !stopped) { "离线翻译插件已停止" }
        return created
    }
    fun invoke(context: Context, name: String, types: Array<Class<*>>, vararg args: Any?): Any? {
        try {
            val runtime = initialize(context)
            return runtime.javaClass.getMethod(name, *types).invoke(runtime, *args)
        } catch (error: InvocationTargetException) {
            throw IllegalStateException(error.targetException.message ?: "离线引擎调用失败", error.targetException)
        } catch (error: LinkageError) { throw IllegalStateException("离线引擎加载失败：${error.message}", error) }
    }
}
