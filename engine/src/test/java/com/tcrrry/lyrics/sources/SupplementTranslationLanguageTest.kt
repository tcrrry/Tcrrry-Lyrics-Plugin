package com.tcrrry.lyrics.sources

import android.content.Context
import com.tcrrry.lyrics.plugin.PluginStorage.pluginPreferences
import com.tcrrry.lyrics.plugin.TestPluginContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class SupplementTranslationLanguageTest {
    @org.junit.Before fun attach() { TestPluginContext.attach() }
    @org.junit.After fun close() { TestPluginContext.current.close() }

    @Test fun legacyManualLanguageCannotOverrideDetectionOrTranslateChineseIntoChinese() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.pluginPreferences("supplement_translation", Context.MODE_PRIVATE)
        prefs.edit().putString("mode", "offline").putString("offline_source_language", "en").commit()
        var emitted = 0
        SupplementTranslation(context).translate("""[{"id":1,"text":"\u4f60\u597d\u4e16\u754c"}]""") { emitted++ }
        assertEquals("zh", prefs.getString("last_language", null))
        assertEquals(0, emitted)
        assertEquals("en", prefs.getString("offline_source_language", null))
    }
}
