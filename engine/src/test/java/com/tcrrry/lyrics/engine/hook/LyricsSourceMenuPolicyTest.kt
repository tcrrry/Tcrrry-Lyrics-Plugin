package com.tcrrry.lyrics.engine.hook

import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(manifest = org.robolectric.annotation.Config.NONE, sdk = [35])
class LyricsSourceMenuPolicyTest {
    @org.junit.Before fun attachPlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.attach() }
    @org.junit.After fun closePlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.current.close() }
    @Test fun cyclesFromTheAppliedAutomaticProviderWhenThereIsNoExplicitSelection() {
        assertEquals("网易云音乐", LyricsSourceMenuPolicy.next("desktop-lyrics:QQ音乐", null))
        assertEquals("LRCLIB", LyricsSourceMenuPolicy.next("desktop-lyrics:网易云音乐", null))
        assertEquals(LyricsSourceMenuPolicy.NATIVE, LyricsSourceMenuPolicy.next("desktop-lyrics:LRCLIB", null))
        assertEquals(LyricsSourceMenuPolicy.AUTHOR, LyricsSourceMenuPolicy.next("APPLE_NATIVE", null))
        assertEquals("QQ音乐", LyricsSourceMenuPolicy.next("am-lyrics", null))
    }
    @Test fun aFailedSwitchCanAdvanceAgainWithoutClaimingTheFailedSourceWasApplied() {
        assertEquals("LRCLIB", LyricsSourceMenuPolicy.next("desktop-lyrics:QQ音乐", "网易云音乐"))
        assertEquals("QQ音乐", LyricsSourceMenuPolicy.caption("desktop-lyrics:QQ音乐"))
    }
    @Test fun nativeAndManualLyricsStartWithTheFirstProvider() {
        for (source in listOf(null, "manual", "automatic-cache")) {
            assertEquals("QQ音乐", LyricsSourceMenuPolicy.next(source, null))
            assertNull(LyricsSourceMenuPolicy.provider(source))
        }
        assertEquals("手动歌词", LyricsSourceMenuPolicy.caption("manual"))
        assertEquals("缓存来源未记录", LyricsSourceMenuPolicy.caption("automatic-cache"))
    }
    @Test fun unrecognizedMetadataDoesNotBecomeAProviderSelection() {
        assertNull(LyricsSourceMenuPolicy.provider("desktop-lyrics:unexpected"))
        assertEquals("QQ音乐", LyricsSourceMenuPolicy.next("desktop-lyrics:unexpected", "unexpected"))
    }
    @Test fun menuGesturesCannotModifyTheNextSong() {
        assertFalse(LyricsSourceMenuPolicy.canAct(100L, 101L))
        assertTrue(LyricsSourceMenuPolicy.canAct(100L, 100L))
    }
    @Test fun missingSongNeverStartsAMatch() {
        assertFalse(LyricsSourceMenuPolicy.canAct(0L, 0L))
        assertFalse(LyricsSourceMenuPolicy.canAct(100L, null))
        assertFalse(LyricsSourceMenuPolicy.canAct(-1L, -1L))
    }
    @Test fun nativeAndAuthorSourcesHaveDifferentCaptionsAndNeverClaimAnUnknownSourceIsNative() {
        assertEquals("Apple Music 原生", LyricsSourceMenuPolicy.caption("APPLE_NATIVE"))
        assertEquals("AM++ 作者整理库", LyricsSourceMenuPolicy.caption("am-lyrics"))
        assertEquals("来源尚未确认", LyricsSourceMenuPolicy.caption(null))
        assertEquals("AMLL TTML 库", LyricsSourceMenuPolicy.caption("amll-ttml-db"))
    }
}
