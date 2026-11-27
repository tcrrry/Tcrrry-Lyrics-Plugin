package com.tcrrry.lyrics.plugin

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class PluginDiagnosticLogTest {
    @org.junit.Before fun attachPlugin() { TestPluginContext.attach() }
    @org.junit.After fun closePlugin() {
        PluginDiagnosticLog.close()
        TestPluginContext.current.close()
    }

    @Test fun trimFileIfNeededKeepsLatestCompleteLinesWithinBound() {
        val dir = java.nio.file.Files.createTempDirectory("diag-trim-").toFile()
        try {
            val file = File(dir, "tcrrry_lyrics_log.txt")
            val content = buildString {
                for (i in 1..400) {
                    append(String.format(java.util.Locale.US, "11-28 00:00:00.%03d [1000] 日志第 %03d 行 payload-abcdef\n", i % 1000, i))
                }
            }
            file.writeText(content, Charsets.UTF_8)
            assertTrue(file.length() > 4096L)

            val trimmed = PluginDiagnosticLog.trimFileIfNeeded(file, maxBytes = 4096L, targetBytes = 2048L)
            assertTrue(trimmed)
            assertTrue(file.length() <= 2048L)
            val lines = file.readLines(Charsets.UTF_8)
            assertTrue(lines.isNotEmpty())
            assertTrue(lines.first().contains("日志第"))
            assertTrue(lines.last().contains("日志第 400 行"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun buildBundleTextIncludesHeaderAndAllChannelsAndClearWorks() {
        val app = TestPluginContext.current.application
        val lyricsFile = requireNotNull(PluginDiagnosticLog.logFile(app, PluginDiagnosticLog.Channel.LYRICS))
        lyricsFile.parentFile?.mkdirs()
        lyricsFile.writeText("11-28 00:10:00.000 [123] Desktop Lyrics lookup id=42\n", Charsets.UTF_8)

        val bundle = PluginDiagnosticLog.buildBundleText(app)
        assertTrue(bundle.contains("Plugin Version: ${PluginDiagnosticLog.PLUGIN_VERSION}"))
        assertTrue(bundle.contains("===== 歌词 (tcrrry_lyrics_log.txt"))
        assertTrue(bundle.contains("===== 智能混音 (automix_log.txt"))
        assertFalse(bundle.contains("usb_bitperfect_log.txt"))
        assertTrue(bundle.contains("Desktop Lyrics lookup id=42"))

        assertTrue(PluginDiagnosticLog.clear(app, PluginDiagnosticLog.Channel.LYRICS))
        assertFalse(PluginDiagnosticLog.channelInfo(app, PluginDiagnosticLog.Channel.LYRICS).exists)
        assertEquals("", PluginDiagnosticLog.readTail(app, PluginDiagnosticLog.Channel.LYRICS))
    }
}
