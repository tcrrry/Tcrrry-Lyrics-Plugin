package dev.amenhancer.plugin.runtime
import java.io.File
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
class FullPluginImportTest {
    @get:Rule val temporary=TemporaryFolder()
    private fun fixture():File {
        val path=System.getProperty("fullPluginFixture")
        Assume.assumeTrue("Supply the built full plugin ZIP with -PfullPluginFixture",path!=null)
        return File(path!!)
    }
    @Test fun actualFullPluginCanBeImportedEnabledAndDeletedByAuthorsUnmodifiedStore() {
        val store=PluginStore(temporary.newFolder())
        store.prepare(fixture().inputStream(),35).use { prepared ->
            assertEquals("com.tcrrry.lyrics",prepared.manifest.id)
            assertEquals("com.tcrrry.lyrics.plugin.LyricsPlugin",prepared.manifest.entryClass)
            store.commit(prepared)
        }
        var installed=store.installed().single()
        assertFalse(installed.enabled)
        assertTrue(File(installed.directory,"code.jar").isFile)
        assertTrue(File(installed.directory,"assets/kuromoji/tokenInfoDictionary.bin").length()>1_000_000)
        assertTrue(File(installed.directory,"assets/pronunciation-modules.json").isFile)
        store.setEnabled(installed.manifest.id,true);installed=store.installed().single();assertTrue(installed.enabled)
        val ownSettings=File(store.dataDirectory(installed.manifest.id),"settings").apply { writeText("kept") }
        store.prepare(fixture().inputStream(),35).use(store::commit)
        assertEquals("kept",ownSettings.readText())
        store.delete(installed.manifest.id);assertTrue(store.installed().isEmpty())
    }
    @Test fun actualZipRejectsUnsupportedAndroidWithoutCommittingPartialPlugin() {
        val store=PluginStore(temporary.newFolder())
        try { store.prepare(fixture().inputStream(),30).close();fail("Expected API rejection") } catch(_:IllegalArgumentException){}
        assertTrue(store.installed().isEmpty())
    }
}
