package com.tcrrry.lyrics.offline.runtime

import android.content.Context
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class RuntimeContextTest {
    @get:Rule val temp = TemporaryFolder()
    private fun context() = RuntimeContext(RuntimeEnvironment.getApplication(),temp.root,File(temp.root,"unused.apk"))
    @Test fun mlkitPreferencesAndModelsStayInPluginStorage() {
        val host=RuntimeEnvironment.getApplication()
        host.getSharedPreferences("com.google.mlkit.internal",0).edit().putString("sentinel","host").commit()
        val context=context()
        assertEquals(host.packageName,context.packageName)
        assertSame(context,context.applicationContext)
        assertSame(context,context.createDeviceProtectedStorageContext())
        context.getSharedPreferences("com.google.mlkit.internal",0).edit().putString("sentinel","plugin").commit()
        assertEquals("host",host.getSharedPreferences("com.google.mlkit.internal",0).getString("sentinel",null))
        assertEquals("plugin",context().getSharedPreferences("com.google.mlkit.internal",0).getString("sentinel",null))
        for (file in listOf(context.filesDir,context.noBackupFilesDir,context.cacheDir,context.getDir("model-files",0),context.getDatabasePath("telemetry.db")))
            assertTrue(file.canonicalPath.startsWith(temp.root.canonicalPath+File.separator))
    }
    @Test fun blocksPathsThatCouldEscapePluginData() {
        val context=context()
        for (name in listOf("../host","/host","..")) {
            assertThrows(IllegalArgumentException::class.java) { context.getSharedPreferences(name,0) }
            assertThrows(IllegalArgumentException::class.java) { context.getDatabasePath(name) }
            assertThrows(IllegalArgumentException::class.java) { context.getFileStreamPath(name) }
        }
        context.openFileOutput("sample",Context.MODE_PRIVATE).use { it.write(byteArrayOf(1,2)) }
        assertArrayEquals(byteArrayOf(1,2),context.openFileInput("sample").use { it.readBytes() })
    }
}
