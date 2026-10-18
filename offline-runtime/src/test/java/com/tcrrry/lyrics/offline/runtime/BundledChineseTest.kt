package com.tcrrry.lyrics.offline.runtime

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BundledChineseTest {
    @get:Rule val temp=TemporaryFolder()
    @Test fun exactOfficialArchiveInstallsOfflineAndCanBeReused() {
        val zip=File("../translation-plugin/assets/bundled-models/zh.zip")
        assertTrue(zip.isFile)
        val target=File(temp.root,"en_zh")
        BundledChinese.installFiles(zip,target)
        assertTrue(File(target,"translate_enzh/processor_spec.pb").length()>0)
        assertTrue(File(target,"translate_zhen/processor_spec.pb").length()>0)
        assertFalse(File(target,"dict.en_zh_25").exists())
        val before=File(target,".bundled-sha256").lastModified()
        BundledChinese.installFiles(zip,target)
        assertEquals(before,File(target,".bundled-sha256").lastModified())
    }
    @Test fun corruptModelIsRejectedBeforeAnyInstall() {
        val bad=File(temp.root,"bad.zip").apply { writeBytes(byteArrayOf(1,2,3)) }
        val target=File(temp.root,"model")
        assertThrows(IllegalStateException::class.java) { BundledChinese.installFiles(bad,target) }
        assertFalse(target.exists())
    }
}
