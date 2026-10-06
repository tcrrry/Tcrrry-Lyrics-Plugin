package dev.amenhancer.plugin.runtime

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.Adler32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PluginStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private fun store() = PluginStore(temp.newFolder())
    private fun manifest(version: Int = 1) = JSONObject().put("formatVersion", 1).put("apiVersion", 1)
        .put("id", "example.test.plugin").put("name", "Test").put("author", "Author")
        .put("versionName", "$version").put("versionCode", version).put("entryClass", "example.Entry").put("minAndroidApi", 26)
    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip -> entries.forEach { (name, value) -> zip.putNextEntry(ZipEntry(name)); zip.write(value); zip.closeEntry() } }
    }.toByteArray()
    private fun dex(): ByteArray = ByteArray(112).also { bytes ->
        "dex\n035\u0000".toByteArray().copyInto(bytes)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(32, bytes.size); buf.putInt(36, 112); buf.putInt(40, 0x12345678)
        MessageDigest.getInstance("SHA-1").digest(bytes.copyOfRange(32, bytes.size)).copyInto(bytes, 12)
        buf.putInt(8, Adler32().apply { update(bytes, 12, bytes.size - 12) }.value.toInt())
    }
    private fun archive(version: Int = 1, code: ByteArray = zip("classes.dex" to dex()), json: JSONObject = manifest(version)) = zip(
        "plugin.json" to json.toString().toByteArray(), "code.jar" to code, "assets/welcome.txt" to "hello".toByteArray())
    private fun prepare(store: PluginStore, version: Int = 1) = store.prepare(ByteArrayInputStream(archive(version)), 37)
    private fun reject(block: () -> Unit) { try { block(); fail("Expected rejection") } catch (_: Exception) {} }

    @Test fun importIsDisabledAndContainsAssetsWithoutExecutingEntry() {
        val store = store(); prepare(store).use { store.commit(it) }
        val installed = store.installed().single()
        assertFalse(installed.enabled)
        assertEquals("hello", File(installed.directory, "assets/welcome.txt").readText())
        assertTrue(File(installed.directory, "code.jar").exists())
    }
    @Test fun replacementPreservesDataEnableAndOldCodeUntilStartup() {
        val store = store(); prepare(store).use { store.commit(it) }
        val first = store.installed().single()
        store.setEnabled(first.manifest.id, true)
        File(store.dataDirectory(first.manifest.id), "config").writeText("keep")
        prepare(store, 2).use { store.commit(it) }
        assertEquals(2L, store.installed().single().manifest.versionCode)
        assertTrue(store.installed().single().enabled); assertTrue(first.directory.exists())
        assertEquals("keep", File(store.dataDirectory(first.manifest.id), "config").readText())
        store.cleanupAtStartup(); assertFalse(first.directory.exists())
        assertTrue(store.installed().single().directory.exists())
    }
    @Test fun blockedLoadingStillAllowsManagementAndStartupCleanupPrecedesImports() {
        val store = store(); prepare(store).use { store.commit(it) }
        val id = store.installed().single().manifest.id
        store.setEnabled(id, true)
        val stale = prepare(store, 2)
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val managed = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        PluginTasks().use { tasks ->
            try {
                tasks.prepareAndLoad(
                    prepare = { store.cleanupAtStartup(); store.installed() },
                    load = { snapshot ->
                        try {
                            assertTrue(snapshot.single().enabled)
                            entered.countDown()
                            release.await()
                        } catch (error: Throwable) { failure.set(error) }
                    },
                    failed = { failure.set(it); entered.countDown() }
                )
                tasks.execute {
                    try {
                        assertFalse(stale.directory.exists())
                        assertTrue(entered.await(5, TimeUnit.SECONDS))
                        assertEquals(id, store.installed().single().manifest.id)
                        prepare(store, 2).use { store.commit(it) }
                        store.setEnabled(id, false)
                        assertFalse(store.installed().single().enabled)
                        store.delete(id)
                        assertTrue(store.installed().isEmpty())
                    } catch (error: Throwable) { failure.set(error) }
                    finally { managed.countDown() }
                }
                assertTrue("Management queued behind blocked plugin loading", managed.await(10, TimeUnit.SECONDS))
                failure.get()?.let { throw AssertionError("Recovery failed", it) }
            } finally { release.countDown(); stale.close() }
        }
    }
    @Test fun startupRemovesInterruptedFirstInstallWithoutIndex() {
        val store = store(); val pending = prepare(store)
        val abandoned = File(store.root, "versions/${pending.manifest.id}/orphan-version")
        assertTrue(abandoned.parentFile!!.mkdirs())
        assertTrue(pending.directory.renameTo(abandoned))
        assertFalse(File(store.root, "installed.json").exists())
        store.cleanupAtStartup()
        assertFalse(abandoned.parentFile!!.exists())
        assertTrue(store.installed().isEmpty())
    }
    @Test fun startupRemovesUnindexedPluginIdsAndPreservesInstalledData() {
        val store = store(); prepare(store).use { store.commit(it) }
        val installed = store.installed().single()
        File(store.dataDirectory(installed.manifest.id), "config").writeText("keep")
        File(store.cacheDirectory(installed.manifest.id), "cache").writeText("keep-cache")
        val abandoned = File(store.root, "versions/example.abandoned.plugin/orphan-version")
        assertTrue(abandoned.mkdirs()); File(abandoned, "code.jar").writeText("orphan")
        store.cleanupAtStartup()
        assertFalse(abandoned.parentFile!!.exists())
        assertTrue(installed.directory.exists())
        assertEquals(installed.manifest.id, store.installed().single().manifest.id)
        assertEquals("keep", File(store.dataDirectory(installed.manifest.id), "config").readText())
        assertEquals("keep-cache", File(store.cacheDirectory(installed.manifest.id), "cache").readText())
    }
    @Test fun canceledImportAndInvalidUpdateKeepInstalledVersion() {
        val store = store(); prepare(store).use { store.commit(it) }
        prepare(store, 2).close()
        reject { store.prepare(ByteArrayInputStream(archive(3, byteArrayOf(1))), 37) }
        assertEquals(1L, store.installed().single().manifest.versionCode)
    }
    @Test fun staleReplacementCannotOverwriteNewerCommit() {
        val store = store(); prepare(store).use { store.commit(it) }
        val stale = prepare(store, 2)
        prepare(store, 3).use { store.commit(it) }
        stale.use { reject { store.commit(it) } }
        assertEquals(3L, store.installed().single().manifest.versionCode)
    }
    @Test fun deletionRetainsLoadedCodeUntilStartupThenRemovesData() {
        val store = store(); prepare(store).use { store.commit(it) }
        val installed = store.installed().single(); val id = installed.manifest.id
        val data = store.dataDirectory(id); File(data, "config").writeText("x")
        val cache = store.cacheDirectory(id); File(cache, "cache").writeText("x")
        store.delete(id); assertTrue(store.installed().isEmpty()); assertTrue(installed.directory.exists())
        store.cleanupAtStartup()
        assertFalse(installed.directory.exists()); assertFalse(data.exists()); assertFalse(cache.exists())
    }
    @Test fun rejectsTraversalUnexpectedFilesAndMissingCode() {
        val store = store()
        for (name in listOf("../escape", "/absolute", "assets/../escape", "assets\\escape", "assets/C:escape", "res/layout.xml")) {
            reject { store.prepare(ByteArrayInputStream(zip("plugin.json" to manifest().toString().toByteArray(), name to byteArrayOf(1))), 37) }
        }
        reject { store.prepare(ByteArrayInputStream(zip("plugin.json" to manifest().toString().toByteArray())), 37) }
        assertTrue(store.installed().isEmpty())
    }
    @Test fun rejectsDuplicateZipEntriesBeforeCommit() {
        val store = store()
        val bytes = zip("assets/one" to byteArrayOf(1), "assets/two" to byteArrayOf(2))
        val replaced = bytes.toString(Charsets.ISO_8859_1).replace("assets/two", "assets/one").toByteArray(Charsets.ISO_8859_1)
        reject { store.prepare(ByteArrayInputStream(replaced), 37) }
    }
    @Test fun rejectsManifestApiAndroidAndIdMismatch() {
        val store = store()
        for ((key, value) in listOf("apiVersion" to 2, "formatVersion" to 2, "minAndroidApi" to 38, "id" to "../bad", "entryClass" to "bad-entry")) {
            reject { store.prepare(ByteArrayInputStream(archive(json = manifest().put(key, value))), 37) }
        }
        reject { store.prepare(ByteArrayInputStream(archive(json = manifest().put("name", ""))), 37) }
    }
    @Test fun rejectsOversizedManifestTooManyEntriesAndBoundedReads() {
        val store = store()
        reject { store.prepare(ByteArrayInputStream(zip("plugin.json" to ByteArray(65537))), 37) }
        val entries = (0..4096).map { "assets/$it" to byteArrayOf() }.toTypedArray()
        reject { store.prepare(ByteArrayInputStream(zip(*entries)), 37) }
        reject { PluginStore.boundedCopy(ByteArrayInputStream(ByteArray(9)), ByteArrayOutputStream(), 8) }
    }
    @Test fun acceptsMultipleDexIncludingClassesTenButRejectsJvmJarAndCorruption() {
        val store = store()
        store.prepare(ByteArrayInputStream(archive(code = zip("classes.dex" to dex(), "classes2.dex" to dex(), "classes10.dex" to dex()))), 37).close()
        reject { store.prepare(ByteArrayInputStream(archive(code = zip("example/Entry.class" to byteArrayOf(1)))), 37) }
        val corrupt = dex().also { it[80] = 1 }
        reject { store.prepare(ByteArrayInputStream(archive(code = zip("classes.dex" to corrupt))), 37) }
    }
    @Test fun independentBuiltSampleCanBeImported() {
        val fixture = System.getProperty("pluginFixture")
        org.junit.Assume.assumeTrue("Standalone sample supplied by acceptance build", fixture != null)
        val store = store(); store.prepare(File(fixture!!).inputStream(), 37).use { store.commit(it) }
        assertEquals("dev.amenhancer.example.basic", store.installed().single().manifest.id)
    }
    @Test fun interruptedReadDoesNotPersistOrRetainStagingFiles() {
        val store = store()
        val broken = object : java.io.InputStream() { override fun read(): Int = throw java.io.IOException("Canceled") }
        reject { store.prepare(broken, 37) }
        assertTrue(store.installed().isEmpty())
        assertEquals(0, File(store.root, "staging").listFiles()!!.size)
    }
    @Test fun rejectsDefinitionsOfSdkClasses() {
        val descriptor = "Ldev/amenhancer/plugin/api/AmppPlugin;".toByteArray()
        val bytes = ByteArray(154 + descriptor.size)
        "dex\n035\u0000".toByteArray().copyInto(bytes)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(32, bytes.size); buf.putInt(36, 112); buf.putInt(40, 0x12345678)
        buf.putInt(56, 1); buf.putInt(60, 112); buf.putInt(64, 1); buf.putInt(68, 116)
        buf.putInt(96, 1); buf.putInt(100, 120); buf.putInt(112, 152)
        bytes[152] = descriptor.size.toByte(); descriptor.copyInto(bytes, 153)
        MessageDigest.getInstance("SHA-1").digest(bytes.copyOfRange(32, bytes.size)).copyInto(bytes, 12)
        buf.putInt(8, Adler32().apply { update(bytes, 12, bytes.size - 12) }.value.toInt())
        reject { PluginStore.validateDex(bytes) }
    }
}
