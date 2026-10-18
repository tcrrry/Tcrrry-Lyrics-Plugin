package com.tcrrry.lyrics.engine.hook

import android.os.Looper
import android.os.SystemClock
import android.view.View
import com.tcrrry.lyrics.plugin.PluginHandler
import com.tcrrry.lyrics.plugin.PluginLifetime
import java.lang.ref.WeakReference

/** Observe media discontinuities even after the last lyric event; native code still owns rendering. */
internal class NativeLyricsPlaybackRecovery private constructor(
    private val type: Class<*>,
    private val clock: NativeLyricsPlaybackClock,
    private val songId: (Any) -> Long?,
) : AutoCloseable {
    private fun field(name: String) = type.lyricsDeclaredField(name).apply { isAccessible = true }
    private val getView = type.getMethod("getView")
    private val hidden = type.getMethod("isHidden")
    private val pointer = field("h1")
    private val adapter = field("p0")
    private val unlock = adapter.type.getMethod("N")
    private val enable = adapter.type.getMethod("P", Boolean::class.javaPrimitiveType)
    private val follow = field("V0")
    private val binding = field("n0")
    private val recycler = binding.type.lyricsDeclaredField("f0").apply { isAccessible = true }
    private val scrollState = recycler.type.getMethod("getScrollState")
    private val callback = field("Z0")
    private val scroll = callback.type.getDeclaredMethod("b", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
    private val processor = field("b1")
    private val callbacks = listOf("c1", "d1", "e1", "f1", "g1").map(::field)
    private val process = processor.type.getDeclaredMethod("c", pointer.type, Long::class.javaPrimitiveType,
        *callbacks.map { it.type }.toTypedArray())
    private val main = PluginHandler(Looper.getMainLooper())
    private var owner = WeakReference<Any>(null)
    private var root = WeakReference<View>(null)
    private var replay = LyricReplayRecovery()
    private var sampledSong: Long? = null
    private var lastPosition: Long? = null
    private var pendingPausedSeek = false
    private var requestedPosition: Long? = null
    private var requestedAt = 0L
    private var transitioning = false
    private var transitionStartedAt = 0L
    private var transitionDocument = WeakReference<Any>(null)
    private val anchors = NativeLyricsSeekAnchor()
    private val presentation = NativeLyricsSeekPresentation.resolve(type)
    private var closed = false
    private var revision = 0L
    private val hooks = HookRegistrationScope()
    private val seekReady = Runnable {
        runCatching { sample() }.onFailure { ModernXposedRuntime.log("Native lyric seek event failed open", it) }
        schedule()
    }
    private val attachment = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) { if (!closed) schedule() }
        override fun onViewDetachedFromWindow(v: View) {
            main.removeCallbacks(poll); main.removeCallbacks(seekReady); revision++
            transitioning = false; presentation?.cancel()
        }
    }

    init {
        presentation?.mediaPosition = { owner.get()?.let { clock.sample(it)?.position } }
        // During the exit/layout/entry transaction native timers must not repaint old
        // words or enqueue another line animation. Audio playback is never touched.
        ModernXposedRuntime.hookMethod(process, object : ModernMethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val fragment = owner.get() ?: return
                if (transitioning && param.thisObject === processor.get(fragment) &&
                    param.args.firstOrNull() === transitionDocument.get()) param.result = 100L
            }
        }, hooks)
        clock.observeSeeks(hooks, before = { receiver, position ->
            val fragment = owner.get()
            if (!closed && fragment != null && clock.owns(fragment, receiver)) {
                revision++
                requestedPosition = position; requestedAt = SystemClock.uptimeMillis()
                transitioning = true
                transitionDocument = WeakReference(pointer.get(fragment))
                presentation?.block(fragment)
                main.removeCallbacks(seekReady)
            }
        }, after = { receiver, success ->
            val fragment = owner.get()
            if (!closed && fragment != null && clock.owns(fragment, receiver)) {
                if (!success) { requestedPosition = null; transitioning = false; presentation?.cancel() }
                else { main.removeCallbacks(seekReady); main.postDelayed(seekReady, 60L) }
            }
        })
        hooks.activate()
    }
    private val poll = object : Runnable {
        override fun run() {
            if (closed || !PluginLifetime.alive || root.get()?.isAttachedToWindow != true) return
            runCatching { sample() }.onFailure { ModernXposedRuntime.log("Native lyric seek/loop recovery failed open", it) }
            schedule()
        }
    }

    fun watch(fragment: Any) {
        if (closed || !PluginLifetime.alive || !type.isInstance(fragment)) return
        val view = getView.invoke(fragment) as? View ?: return
        if (owner.get() === fragment && root.get() === view) return
        root.get()?.removeOnAttachStateChangeListener(attachment)
        main.removeCallbacks(poll)
        revision++
        replay = LyricReplayRecovery()
        sampledSong = null; lastPosition = null; pendingPausedSeek = false
        requestedPosition = null; transitioning = false; presentation?.cancel()
        owner = WeakReference(fragment); root = WeakReference(view)
        view.addOnAttachStateChangeListener(attachment)
        runCatching { sample(seed = true) }
        schedule()
    }

    private fun schedule() {
        main.removeCallbacks(poll)
        if (!closed && PluginLifetime.alive && root.get()?.isAttachedToWindow == true) main.postDelayed(poll, 500L)
    }

    private fun sample(seed: Boolean = false) {
        val fragment = owner.get() ?: return
        val view = root.get() ?: return
        val id = songId(fragment)?.takeIf { it > 0L } ?: run {
            reset(); return
        }
        val token = pointer.get(fragment) ?: return
        val native = token.javaClass.getMethod("get").invoke(token) ?: return
        val nativeId = (native.javaClass.getMethod("getAdamId").invoke(native) as Number).toLong()
        if (!LyricReplayRecovery.sameSong(id, id, nativeId)) {
            reset(); return
        }
        val time = clock.sample(fragment) ?: return
        if (sampledSong != id) {
            if (sampledSong != null) { revision++; requestedPosition = null; transitioning = false; presentation?.cancel() }
            sampledSong = id; lastPosition = null; pendingPausedSeek = false
        }
        val previousPosition = lastPosition
        // A paused clock cannot move continuously: even a small forward change is an explicit seek.
        if (!seed && !time.playing && lastPosition?.let { it != time.position } == true) pendingPausedSeek = true
        lastPosition = time.position
        val list = binding.get(fragment)?.let { recycler.get(it) } ?: return
        val ready = view.isAttachedToWindow && view.isShown && view.hasWindowFocus() &&
            hidden.invoke(fragment) != true && scrollState.invoke(list) == 0
        // Losing focus/starting a gesture can invalidate a queued layout callback.
        // Release native processing and retry once visible, rather than leaving the gate shut.
        if (transitioning && requestedPosition == null &&
            (!ready || transitionDocument.get() !== token || SystemClock.uptimeMillis() - transitionStartedAt > 3_000L)) {
            revision++; transitioning = false; presentation?.cancel(); pendingPausedSeek = true
            clock.restart(fragment, view.resources.getIdentifier("message_lyrics_process_events", "id",
                com.tcrrry.lyrics.engine.ModuleConstants.RESOURCE_PACKAGE))
        }
        val request = requestedPosition
        val observed = replay.observe(token, time.position, seed || !ready, id)?.takeUnless {
            // Explicit seeks, including tiny backwards seeks, are observed above. A
            // small backwards correction from the playing media clock alone is not
            // sufficient reason to cancel/restart the native presentation again.
            request == null && time.playing && previousPosition != null &&
                previousPosition - time.position in 1L..999L
        }
        if (request != null && ready) {
            val elapsed = (SystemClock.uptimeMillis() - requestedAt).coerceAtLeast(0L)
            val upper = request + (if (time.playing) elapsed else 0L) + 350L
            val settled = time.position >= request - 100L && time.position <= upper
            if (!settled && elapsed < 1_500L) {
                main.removeCallbacks(seekReady); main.postDelayed(seekReady, 60L); return
            }
        }
        val position = time.position.takeIf { ready && request != null }
            ?: observed ?: time.position.takeIf { ready && pendingPausedSeek } ?: return
        if (!ready || seed) return
        requestedPosition = null; pendingPausedSeek = false
        val opening = observed != null && replay.opening
        val stamp = ++revision
        val current = adapter.get(fragment) ?: return
        unlock.invoke(current)
        enable.invoke(current, true)
        follow.setBoolean(fragment, true)
        val valid = {
            !closed && PluginLifetime.alive && stamp == revision && owner.get() === fragment &&
                pointer.get(fragment) === token && songId(fragment) == id && view.hasWindowFocus() &&
                hidden.invoke(fragment) != true && scrollState.invoke(list) == 0
        }
        val message = view.resources.getIdentifier("message_lyrics_process_events", "id",
            com.tcrrry.lyrics.engine.ModuleConstants.RESOURCE_PACKAGE)
        transitioning = true
        transitionDocument = WeakReference(token)
        transitionStartedAt = SystemClock.uptimeMillis()
        clock.suspendEvents(fragment, message)
        val staged = runCatching { presentation?.exit(fragment, token, position) == true }
            .onFailure { ModernXposedRuntime.log("Native lyric seek exit failed open", it) }.getOrDefault(false)
        val enter = {
            if (valid()) {
                val at = clock.sample(fragment)?.position ?: position
                follow.setBoolean(fragment, true)
                val restored = if (staged) presentation?.enter(fragment, token, at) else null
                if (restored.isNullOrEmpty()) {
                    val anchor = anchors.at(token, at)
                    if (anchor != null) scroll.invoke(callback.get(fragment), anchor, anchor)
                }
                // The native adapter has entered. Reopen native event processing only
                // after that layout, so active-word events cannot race old holders.
                NativeLyricsImmediateAnchor.afterLayout(view, valid) {
                    transitioning = false
                    presentation?.release()
                    val now = clock.sample(fragment)?.position ?: at
                    val delay = (process.invoke(processor.get(fragment), token, now,
                        *callbacks.map { it.get(fragment) }.toTypedArray()) as? Number)?.toLong() ?: 100L
                    clock.restart(fragment, message, delay)
                }
            }
            Unit
        }
        // A real frame boundary is required between O(empty) and O(new IDs).
        // Native callbacks released on cancellation stay blocked while we cross it.
        NativeLyricsImmediateAnchor.afterLayout(view, valid) {
            if (staged) presentation?.afterNativeTransition(fragment, valid) {
                main.post { runCatching(enter).onFailure { abandon(fragment, message, stamp, it) } }
            } else runCatching(enter).onFailure { abandon(fragment, message, stamp, it) }
        }
        ModernXposedRuntime.log("Native lyric seek transaction started: position=$position loop=$opening native=$staged")
    }

    private fun reset() {
        revision++; replay = LyricReplayRecovery(); sampledSong = null; lastPosition = null
        pendingPausedSeek = false; requestedPosition = null; transitioning = false; presentation?.cancel()
    }
    private fun abandon(fragment: Any, message: Int, stamp: Long, error: Throwable) {
        if (stamp != revision) return
        transitioning = false; presentation?.cancel()
        runCatching { clock.restart(fragment, message) }
        ModernXposedRuntime.log("Native lyric seek transaction failed open", error)
    }

    override fun close() {
        closed = true; revision++
        val resume = transitioning
        transitioning = false; requestedPosition = null
        hooks.close(); presentation?.close()
        main.removeCallbacksAndMessages(null)
        root.get()?.removeOnAttachStateChangeListener(attachment)
        if (resume) runCatching {
            val fragment = owner.get() ?: return@runCatching
            val view = root.get()?.takeIf { it.isAttachedToWindow } ?: return@runCatching
            clock.restart(fragment, view.resources.getIdentifier("message_lyrics_process_events", "id",
                com.tcrrry.lyrics.engine.ModuleConstants.RESOURCE_PACKAGE))
        }
        owner.clear(); root.clear()
        transitionDocument.clear()
    }

    companion object {
        fun resolve(type: Class<*>, songId: (Any) -> Long?): NativeLyricsPlaybackRecovery? = runCatching {
            NativeLyricsPlaybackRecovery(type, requireNotNull(NativeLyricsPlaybackClock.resolve(type)), songId)
        }.onFailure { ModernXposedRuntime.log("Native lyric playback recovery unavailable", it) }.getOrNull()
    }
}
