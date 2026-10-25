package dev.amenhancer.plugin.runtime

import dev.amenhancer.module.i18n.ModuleText

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.Adler32
import java.util.zip.ZipFile

data class PluginManifest(val id: String, val name: String, val author: String, val versionName: String,
    val versionCode: Long, val entryClass: String, val minAndroidApi: Int, val description: String) {
    companion object {
        fun parse(text: String): PluginManifest {
            val json = JSONObject(text)
            require(json.getInt("formatVersion") == 1) { ModuleText.PLUGIN_FORMAT_UNSUPPORTED.text() }
            require(json.getInt("apiVersion") == 1) { ModuleText.PLUGIN_API_UNSUPPORTED.text() }
            fun field(key: String) = json.getString(key).also { require(it.isNotBlank() && it.length <= 1024) { ModuleText.PLUGIN_FIELD_INVALID.text(key) } }
            val id = field("id")
            require(id.matches(Regex("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+")) && id.length <= 180) { ModuleText.PLUGIN_ID_INVALID.text() }
            val entry = field("entryClass")
            require(entry.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+"))) { ModuleText.PLUGIN_ENTRY_CLASS_INVALID.text() }
            val version = json.getLong("versionCode")
            val min = json.getInt("minAndroidApi")
            require(version > 0 && min >= 26) { ModuleText.PLUGIN_VERSION_INVALID.text() }
            return PluginManifest(id, field("name"), field("author"), field("versionName"), version, entry, min,
                json.optString("description").take(8192))
        }
    }
}

data class InstalledPlugin(val manifest: PluginManifest, val directory: File, val enabled: Boolean)
class PreparedPlugin internal constructor(val manifest: PluginManifest, internal val directory: File,
    internal val previousVersion: String?) : AutoCloseable {
    override fun close() { PluginStore.removeTree(directory) }
}

/** Import never executes code. Immutable version directories plus one atomic index commit. */
class PluginStore(val root: File) {
    private val index = File(root, "installed.json")
    private val versions = File(root, "versions")
    private val staging = File(root, "staging")
    init { require(root.mkdirs() || root.isDirectory) }

    @Synchronized private fun readIndex(): JSONObject = if (index.exists()) JSONObject(index.readText()) else JSONObject()
    private fun writeIndex(json: JSONObject) {
        val temp = File(root, "installed-${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temp).use { it.write(json.toString().toByteArray()); it.fd.sync() }
            Files.move(temp.toPath(), index.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temp.delete() }
    }
    @Synchronized fun installed(): List<InstalledPlugin> {
        val json = readIndex()
        return json.keys().asSequence().sorted().mapNotNull { id ->
            val entry = json.getJSONObject(id)
            if (entry.optBoolean("deleted")) null else {
                val dir = versionDirectory(id, entry.getString("version"))
                val manifest = PluginManifest.parse(File(dir, "plugin.json").readText())
                require(manifest.id == id)
                InstalledPlugin(manifest, dir, entry.optBoolean("enabled"))
            }
        }.toList()
    }
    @Synchronized fun setEnabled(id: String, enabled: Boolean) {
        val json = readIndex(); val entry = json.getJSONObject(id)
        require(!entry.optBoolean("deleted")) { ModuleText.PLUGIN_DELETED.text() }
        entry.put("enabled", enabled); writeIndex(json)
    }
    @Synchronized fun delete(id: String) {
        val json = readIndex(); json.getJSONObject(id).put("enabled", false).put("deleted", true); writeIndex(json)
    }
    fun dataDirectory(id: String): File = child(File(root, "data"), id).also { require(it.mkdirs() || it.isDirectory) }
    fun cacheDirectory(id: String): File = child(File(root, "cache"), id).also { require(it.mkdirs() || it.isDirectory) }

    fun prepare(input: InputStream, androidApi: Int): PreparedPlugin {
        val dir = File(staging, UUID.randomUUID().toString()); require(dir.mkdirs())
        val archive = File(dir, "input.zip")
        try {
            input.use { source -> FileOutputStream(archive).use { boundedCopy(source, it, MAX_INPUT) } }
            ZipFile(archive).use { zip ->
                val entries = zip.entries().asSequence().toList()
                require(entries.size <= MAX_ENTRIES) { ModuleText.PLUGIN_TOO_MANY_ENTRIES.text() }
                val names = hashSetOf<String>(); var expanded = 0L
                for (entry in entries) {
                    val name = entry.name
                    safeRelative(name.removeSuffix("/"))
                    require(names.add(name)) { ModuleText.PLUGIN_ZIP_DUPLICATE.text(name) }
                    require(name == "plugin.json" || name == "code.jar" || name.startsWith("assets/")) { ModuleText.PLUGIN_FILE_UNSUPPORTED.text(name) }
                    if (entry.isDirectory) continue
                    val file = child(dir, name); val parent = checkNotNull(file.parentFile); require(parent.mkdirs() || parent.isDirectory)
                    val limit = if (name == "plugin.json") MAX_MANIFEST else MAX_EXPANDED - expanded
                    zip.getInputStream(entry).use { source ->
                        FileOutputStream(file).use { output ->
                            if (name == "code.jar") require(file.setReadOnly()) { ModuleText.PLUGIN_CODE_READ_ONLY_FAILED.text() }
                            expanded += boundedCopy(source, output, limit)
                            output.fd.sync()
                        }
                    }
                    require(expanded <= MAX_EXPANDED) { ModuleText.PLUGIN_EXPANDED_TOO_LARGE.text() }
                }
            }
            val manifest = PluginManifest.parse(File(dir, "plugin.json").readText())
            require(androidApi >= manifest.minAndroidApi) { ModuleText.PLUGIN_ANDROID_REQUIRED.text(manifest.minAndroidApi) }
            validateCode(File(dir, "code.jar"))
            archive.delete()
            val previous = synchronized(this) { readIndex().optJSONObject(manifest.id)?.optString("version") }
            return PreparedPlugin(manifest, dir, previous)
        } catch (error: Throwable) { removeTree(dir); throw error }
    }
    @Synchronized fun commit(prepared: PreparedPlugin) {
        require(prepared.directory.parentFile?.canonicalFile == staging.canonicalFile && prepared.directory.isDirectory)
        val json = readIndex(); val old = json.optJSONObject(prepared.manifest.id)
        require(old?.optString("version") == prepared.previousVersion) { ModuleText.PLUGIN_CONCURRENT_UPDATE.text() }
        val version = UUID.randomUUID().toString()
        val destination = versionDirectory(prepared.manifest.id, version)
        val parent = checkNotNull(destination.parentFile); require(parent.mkdirs() || parent.isDirectory)
        Files.move(prepared.directory.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        try {
            json.put(prepared.manifest.id, JSONObject().put("version", version)
                .put("enabled", old?.optBoolean("enabled") == true && !old.optBoolean("deleted")))
            writeIndex(json)
        } catch (error: Throwable) { removeTree(destination); throw error }
    }
    /** Only at fresh process startup: active loaders may still use old files until then. */
    @Synchronized fun cleanupAtStartup() {
        removeTree(staging)
        val json = readIndex()
        for (id in json.keys().asSequence().toList()) {
            val entry = json.getJSONObject(id)
            val idRoot = child(versions, id)
            if (entry.optBoolean("deleted")) {
                removeTree(idRoot); removeTree(child(File(root, "data"), id)); removeTree(child(File(root, "cache"), id)); json.remove(id)
            } else idRoot.listFiles()?.filter { it.name != entry.getString("version") }?.forEach(::removeTree)
        }
        // A killed first install may have moved its package before committing the index.
        versions.listFiles()?.filter { !json.has(it.name) }?.forEach(::removeTree)
        writeIndex(json)
    }
    private fun versionDirectory(id: String, version: String) = child(child(versions, id), version)

    companion object {
        const val MAX_INPUT = 128L * 1024 * 1024
        const val MAX_EXPANDED = 256L * 1024 * 1024
        const val MAX_ENTRIES = 4096
        const val MAX_MANIFEST = 64L * 1024
        internal fun safeRelative(path: String) {
            require(path.isNotEmpty() && !path.contains('\\') && !path.contains(':') &&
                path.split('/').none { it.isEmpty() || it == "." || it == ".." }) { ModuleText.PLUGIN_PATH_INVALID.text(path) }
        }
        internal fun child(parent: File, path: String): File {
            safeRelative(path)
            return File(parent, path).also { require(it.canonicalPath.startsWith(parent.canonicalPath + File.separator)) }
        }
        internal fun removeTree(file: File) {
            if (!file.exists()) return
            if (file.isDirectory) file.listFiles()?.forEach(::removeTree)
            file.setWritable(true); check(file.delete()) { ModuleText.FILE_DELETE_FAILED.text(file.name) }
        }
        internal fun boundedCopy(input: InputStream, output: java.io.OutputStream, max: Long): Long {
            var size = 0L; val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer); if (read < 0) break
                size += read; require(size <= max) { ModuleText.PLUGIN_FILE_TOO_LARGE.text() }; output.write(buffer, 0, read)
            }
            return size
        }
        internal fun validateCode(file: File) {
            require(file.isFile) { ModuleText.PLUGIN_CODE_MISSING.text() }
            ZipFile(file).use { zip ->
                val entries = zip.entries().asSequence().toList()
                require(entries.size in 1..MAX_ENTRIES)
                val names = hashSetOf<String>(); var total = 0L; var dexCount = 0
                entries.forEach { entry ->
                    safeRelative(entry.name.removeSuffix("/")); require(names.add(entry.name)) { ModuleText.PLUGIN_CODE_DUPLICATE.text() }
                    require(entry.name.matches(Regex("classes(?:[2-9]|[1-9][0-9]+)?\\.dex")) || entry.name.startsWith("META-INF/")) { ModuleText.PLUGIN_CODE_DEX_ONLY.text(entry.name) }
                    if (!entry.isDirectory) {
                        val output = java.io.ByteArrayOutputStream()
                        zip.getInputStream(entry).use { total += boundedCopy(it, output, MAX_EXPANDED - total) }
                        if (entry.name.endsWith(".dex")) { validateDex(output.toByteArray()); dexCount++ }
                    }
                }
                require(dexCount > 0 && names.contains("classes.dex")) { ModuleText.PLUGIN_DEX_MISSING.text() }
            }
        }
        internal fun validateDex(bytes: ByteArray) {
            require(bytes.size >= 112 && bytes.copyOfRange(0, 8).toString(Charsets.ISO_8859_1).matches(Regex("dex\\n0(?:35|37|38|39|40)\\u0000"))) { ModuleText.PLUGIN_DEX_INVALID.text() }
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            require(buf.getInt(32) == bytes.size && buf.getInt(36) == 112 && buf.getInt(40) == 0x12345678) { ModuleText.PLUGIN_DEX_HEADER_INVALID.text() }
            require(MessageDigest.getInstance("SHA-1").digest(bytes.copyOfRange(32, bytes.size)).contentEquals(bytes.copyOfRange(12, 32))) { ModuleText.PLUGIN_DEX_SIGNATURE_INVALID.text() }
            require(Adler32().apply { update(bytes, 12, bytes.size - 12) }.value.toInt() == buf.getInt(8)) { ModuleText.PLUGIN_DEX_CHECKSUM_INVALID.text() }
            // References to SDK types are allowed; defining a private copy is not.
            val strings = buf.getInt(60); val types = buf.getInt(68); val count = buf.getInt(96); val defs = buf.getInt(100)
            require(count >= 0 && defs >= 0 && defs.toLong() + count.toLong() * 32 <= bytes.size)
            repeat(count) { index ->
                val type = buf.getInt(defs + index * 32)
                require(type >= 0 && type < buf.getInt(64))
                val string = buf.getInt(types + type * 4); require(string >= 0 && string < buf.getInt(56))
                var offset = buf.getInt(strings + string * 4)
                var n = 0; do { require(offset in bytes.indices && n++ < 5); val b = bytes[offset++].toInt(); if (b and 128 == 0) break } while (true)
                val start = offset; while (offset < bytes.size && bytes[offset] != 0.toByte()) offset++
                require(offset < bytes.size)
                val name = bytes.copyOfRange(start, offset).toString(Charsets.UTF_8)
                require(!name.startsWith("Ldev/amenhancer/plugin/api/")) { ModuleText.PLUGIN_SDK_BUNDLED.text() }
            }
        }
    }
}
