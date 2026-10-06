package com.tcrrry.lyrics.engine.hook

import android.os.Handler
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class NativeLyricsPlaybackClockTest {
    @org.junit.Before fun attachPlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.attach() }
    @org.junit.After fun closePlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.current.close() }
    @Test fun defaultInterfaceGetterAndInheritedBrowserApiResolveLikeOriginalAppleMusic() {
        val host = InterfacePlaybackHost()
        assertFalse(InterfacePlaybackHost::class.java.declaredMethods.any { it.name == "getMediaBrowser" })
        val clock = requireNotNull(NativeLyricsPlaybackClock.resolve(InterfacePlaybackHost::class.java))
        assertEquals(210000L, clock.sample(host)!!.position)
        host.position = 0L
        assertEquals(0L, clock.sample(host)!!.position)
        host.handler().sendEmptyMessageDelayed(42, 60000L)
        host.handler().sendEmptyMessageDelayed(99, 60000L)
        clock.restart(host, 42, 200L)
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(199L))
        assertEquals(0, host.restarts)
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1L))
        assertEquals(1, host.restarts)
        assertTrue(host.handler().hasMessages(99))
        host.playing = false
        clock.restart(host, 42)
        assertFalse(host.handler().hasMessages(42))
        host.handler().removeCallbacksAndMessages(null)
    }
    class Media {
        var current = 5000L; var running = true
        fun getCurrentPosition() = current
        fun isPlaying() = running
        fun getPlaybackState() = if (running) 3 else 2
    }
    class Host {
        val media = Media()
        @JvmField val X = object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(message: android.os.Message) {
                if (message.what == 42) y2(media.getPlaybackState())
            }
        }
        var restarts = 0
        fun getMediaBrowser() = media
        fun y2(state: Int): Long { assertEquals(3, state); restarts++; return 100L }
    }
    @Test fun recoveryCancelsOnlyStaleLyricDeadlineAndRestartsPlayingLoop() {
        val host = Host(); val clock = requireNotNull(NativeLyricsPlaybackClock.resolve(Host::class.java))
        host.X.sendEmptyMessageDelayed(42, 60000)
        host.X.sendEmptyMessageDelayed(99, 60000)
        assertEquals(5000L, clock.sample(host)!!.position)
        clock.restart(host, 42)
        assertTrue(host.X.hasMessages(42))
        assertTrue(host.X.hasMessages(99))
        assertEquals(0, host.restarts)
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
        assertEquals(1, host.restarts)
        host.X.removeCallbacksAndMessages(null)
    }
    @Test fun pausedSeekDoesNotStartPlaybackOrAMessageLoop() {
        val host = Host(); host.media.running = false
        val clock = requireNotNull(NativeLyricsPlaybackClock.resolve(Host::class.java))
        clock.restart(host, 42)
        assertFalse(clock.sample(host)!!.playing)
        assertEquals(0, host.restarts)
    }
}
