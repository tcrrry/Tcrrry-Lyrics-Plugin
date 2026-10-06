package com.tcrrry.lyrics.engine.hook

import android.os.Handler
import java.lang.reflect.Field
import java.lang.reflect.Method

/** MediaBrowser is independent of SongInfoTimeProcessor's next-event scheduling. */
internal class NativeLyricsPlaybackClock private constructor(
    private val browser: Method, private val position: Method, private val playing: Method,
    private val state: Method, private val processLoop: Method, private val messages: Field?,
) {
    data class Snapshot(val position: Long, val playing: Boolean)
    fun sample(fragment: Any): Snapshot? {
        val media = browser.invoke(fragment) ?: return null
        return Snapshot((position.invoke(media) as Number).toLong().coerceAtLeast(0), playing.invoke(media) == true)
    }
    fun restart(fragment: Any, lyricMessageId: Int, nextEventDelay: Long = 100L) {
        val media = browser.invoke(fragment) ?: return
        val handler = messages?.get(fragment) as? Handler
        if (lyricMessageId != 0) handler?.removeMessages(lyricMessageId)
        if (playing.invoke(media) != true) return
        if (lyricMessageId != 0 && handler != null) {
            // Let the seek/loop update finish before the native callback samples its global clock.
            val delay = if (nextEventDelay > 0) nextEventDelay.coerceAtMost(500L) else 100L
            handler.sendEmptyMessageDelayed(lyricMessageId, delay)
        } else processLoop.invoke(fragment, state.invoke(media))
    }
    companion object {
        fun resolve(fragment: Class<*>): NativeLyricsPlaybackClock? = runCatching {
            // Apple's getMediaBrowser is a default method on ia.a$c, implemented by
            // a fragment superclass. Class.getMethod includes inherited interfaces;
            // walking declaredMethods on the class hierarchy alone silently misses it.
            fun method(type: Class<*>, name: String, vararg args: Class<*>): Method =
                runCatching { type.getMethod(name, *args).apply { isAccessible = true } }.getOrNull()
                    ?: generateSequence(type) { it.superclass }.mapNotNull {
                    runCatching { it.getDeclaredMethod(name, *args).apply { isAccessible = true } }.getOrNull()
                }.first()
            val browser = method(fragment, "getMediaBrowser")
            NativeLyricsPlaybackClock(browser, method(browser.returnType, "getCurrentPosition"),
                method(browser.returnType, "isPlaying"), method(browser.returnType, "getPlaybackState"),
                method(fragment, "y2", Int::class.javaPrimitiveType!!),
                generateSequence(fragment) { it.superclass }.mapNotNull {
                    runCatching { it.getDeclaredField("X").apply { isAccessible = true } }.getOrNull()
                }.firstOrNull()?.takeIf { Handler::class.java.isAssignableFrom(it.type) })
        }.onFailure { ModernXposedRuntime.log("Independent lyric playback clock unavailable", it) }.getOrNull()
    }
}
