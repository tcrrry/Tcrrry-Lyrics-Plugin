package com.tcrrry.lyrics.engine.lyrics

import com.tcrrry.lyrics.engine.model.CustomLyricsEntry
import com.tcrrry.lyrics.engine.model.CustomLyricsSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(manifest = org.robolectric.annotation.Config.NONE, sdk = [35])
class CustomLyricsFileReaderTest {
    @org.junit.Before fun attachPlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.attach() }
    @org.junit.After fun closePlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.current.close() }
    private val ttml = "<tt><body><p><span>word</span></p></body></tt>"
    private val bytes = ttml.toByteArray()
    private val entry = CustomLyricsEntry(
        appleMusicId = 42L,
        displayName = "Song",
        fileId = "lyrics_abc123",
        sizeBytes = bytes.size.toLong(),
        sha256 = CustomLyricsFilePolicy.sha256(bytes),
        source = CustomLyricsSources.MANUAL,
    )

    @Test
    fun `reader accepts only a file that matches its published size hash and ttml policy`() {
        assertEquals(ttml, CustomLyricsFileReader { bytes }.read(entry))
        assertNull(CustomLyricsFileReader { bytes + byteArrayOf(0) }.read(entry))
        assertNull(CustomLyricsFileReader { "not ttml".toByteArray() }.read(entry.copy(
            sizeBytes = "not ttml".toByteArray().size.toLong(),
            sha256 = CustomLyricsFilePolicy.sha256("not ttml".toByteArray()),
        )))
    }
}
