package dev.amenhancer.plugin.runtime

import java.io.File
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder

class TranslationPluginImportTest {
    @get:Rule val temp=TemporaryFolder()
    private fun fixture():File {
        val path=System.getProperty("translationPluginFixture")
        Assume.assumeTrue("Supply translation ZIP with -PtranslationPluginFixture",path!=null)
        return File(path!!)
    }
    @Test fun optionalTranslationZipImportsWithDistinctIdAndOwnData() {
        val store=PluginStore(temp.newFolder())
        store.prepare(fixture().inputStream(),35).use {
            assertEquals("com.tcrrry.translation",it.manifest.id)
            assertEquals("com.tcrrry.translation.plugin.TranslationPlugin",it.manifest.entryClass)
            store.commit(it)
        }
        val installed=store.installed().single()
        assertTrue(File(installed.directory,"assets/bundled-models/zh.zip").length()>30_000_000)
        assertTrue(File(installed.directory,"assets/pure-offline/runtime.apk").isFile)
        store.setEnabled(installed.manifest.id,true)
        assertTrue(store.installed().single().enabled)
        assertTrue(store.dataDirectory(installed.manifest.id).canonicalPath.endsWith("com.tcrrry.translation"))
    }
}
