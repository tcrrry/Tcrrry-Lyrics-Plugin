package com.tcrrry.lyrics.engine.hook

import android.view.View
import android.view.ViewTreeObserver
import android.os.SystemClock
import com.tcrrry.lyrics.engine.lyrics.LyricsPreference
import com.tcrrry.lyrics.plugin.PluginLifetime

/** Replay the native scroll callback after a new lyrics pointer's layout, without changing playback. */
internal class NativeLyricsImmediateAnchor private constructor(private val fragmentClass: Class<*>) {
    private fun field(name: String) = fragmentClass.getDeclaredField(name).apply { isAccessible = true }
    private val binding = field("n0")
    private val recycler = binding.type.getDeclaredField("f0").apply { isAccessible = true }
    private val scrollState = recycler.type.getMethod("getScrollState")
    private val pointer = field("h1")
    private val adapter = field("p0")
    private val follow = field("V0").also { require(it.type == Boolean::class.javaPrimitiveType) }
    private val callback = field("Z0")
    private val highlighted = adapter.type.getDeclaredMethod("w").also { require(it.returnType == java.util.TreeSet::class.java) }
    private val scroll = callback.type.getDeclaredMethod("b", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
    private val view = fragmentClass.getMethod("getView")
    private val hidden = fragmentClass.getMethod("isHidden")

    private val unlock = adapter.type.getDeclaredMethod("N")
    private val nativeFollow = adapter.type.getDeclaredMethod("P", Boolean::class.javaPrimitiveType)
    private val rebind = adapter.type.getMethod("g")
    private val processor = field("b1")
    private val callbacks = listOf("c1", "d1", "e1", "f1", "g1").map(::field)
    private val processAt = processor.type.getDeclaredMethod("c", pointer.type, Long::class.javaPrimitiveType, *callbacks.map { it.type }.toTypedArray())
    private val playbackClock = NativeLyricsPlaybackClock.resolve(fragmentClass)
    private var recoveringPlayback = false
    private val processNow = processor.type.getDeclaredMethod("d", pointer.type, *callbacks.map { it.type }.toTypedArray())
    private var active = java.lang.ref.WeakReference<Any>(null)
    private val epoch = LyricPlaybackEpoch()
    private var lastBoundaryPosition: Long? = null
    private var boundaryToken: Any? = null
    private var returning = java.lang.ref.WeakReference<Any>(null)
    private var returnStartedAt = 0L
    private var returnSongId: Long? = null
    private var lastReturnAt = Long.MIN_VALUE
    internal var isCurrentSong: ((Any) -> Boolean)? = null
    internal var currentSongId: ((Any) -> Long?)? = null
    internal var ownsReplacement: ((Any, Any) -> Boolean)? = null
    internal var smoothReturn: NativeLyricsSmoothReturn? = null

    private fun processBoundary(token: Any, position: Long?) {
        val fragment = active.get() ?: return
        if (pointer.get(fragment) !== token) return
        boundaryToken = token
        val oldPosition = lastBoundaryPosition
        if (position != null) lastBoundaryPosition = position
        val change = epoch.update(token, position)
        LyricsPlaybackDiagnostics.record("clock", "doc=${LyricsPlaybackDiagnostics.identity(token)} pos=$position change=$change", sample = change == LyricPlaybackEpoch.Change.CONTINUOUS)
        if (!recoveringPlayback && (change == LyricPlaybackEpoch.Change.REWIND || change == LyricPlaybackEpoch.Change.SEEK_FORWARD)) {
            adapter.get(fragment)?.let { unlock.invoke(it) }
            val list = binding.get(fragment)?.let { recycler.get(it) }
            if (list != null && scrollState.invoke(list) != 1) {
                val root = view.invoke(fragment) as? View
                if (change == LyricPlaybackEpoch.Change.REWIND && root != null &&
                    LyricsPreference.forceRepeatReset(root.context) && LyricReplayRecovery.isOpeningRewind(oldPosition, position)) {
                    requestLoopReturn(fragment)
                } else if (returning.get() !== fragment) {
                    follow.setBoolean(fragment, true)
                    schedule(fragment, refreshClock = false)
                }
            }
        }
        // Native time callbacks continue during the trip to the top. They may
        // update highlights, but must not replace that trip with an instant jump.
        if (returning.get() === fragment) follow.setBoolean(fragment, false)
    }

    private fun requestLoopReturn(fragment: Any) {
        val root = view.invoke(fragment) as? View ?: return
        val id = currentSongId?.invoke(fragment)
        val now = SystemClock.uptimeMillis()
        if (returnSongId == id && lastReturnAt != Long.MIN_VALUE && now - lastReturnAt < 3_000L) return
        returnSongId = id; lastReturnAt = now
        returning = java.lang.ref.WeakReference(fragment); returnStartedAt = now
        pointer.get(fragment)?.let { smoothReturn?.hold(fragment, it) }
        follow.setBoolean(fragment, false)
        schedule(fragment, refreshClock = false, smooth = smoothReturn != null, opening = true)
        // Polling is also active in the outro, independently of lyric callbacks.
        watch(fragment)
        root.postInvalidateOnAnimation()
    }

    private fun finishReturn(fragment: Any, state: Int) {
        if (returning.get() !== fragment) return
        val changedSong = currentSongId?.invoke(fragment) != returnSongId
        if (state == 1 || changedSong) { smoothReturn?.release(fragment); returning.clear(); return }
        if (state == 0 && SystemClock.uptimeMillis() - returnStartedAt >= 500L) {
            returning.clear()
            smoothReturn?.release(fragment)
            follow.setBoolean(fragment, true)
        }
    }

    internal fun nativeDocumentId(fragment: Any): Long? = runCatching {
        val token = pointer.get(fragment) ?: return null
        val document = token.javaClass.getMethod("get").invoke(token) ?: return null
        (document.javaClass.getMethod("getAdamId").invoke(document) as Number).toLong()
    }.getOrNull()

    private val watched = java.util.WeakHashMap<View, Boolean>()
    internal fun correctedPosition(token: Any, position: Long?): Long? {
        val fragment = active.get() ?: return position
        if (pointer.get(fragment) !== token || ownsReplacement?.invoke(fragment, token) != true ||
            isCurrentSong?.invoke(fragment) == false) return position
        val root = view.invoke(fragment) as? View ?: return position
        if (!root.isAttachedToWindow || !root.isShown || hidden.invoke(fragment) == true) return position
        val media = playbackClock?.sample(fragment)?.position ?: return position
        return correctStalledLyricPosition(position, media)
    }
    fun watch(fragment: Any) {
        active = java.lang.ref.WeakReference(fragment)
        installedAnchor = this
        val root = view.invoke(fragment) as? View ?: return
        if (watched.put(root, true) != null) return
        ModernXposedRuntime.log("Lyrics r4 loop watcher installed; mediaClock=${playbackClock != null}; smoothReturn=${smoothReturn != null}")
        val rootRef = java.lang.ref.WeakReference(root)
        val fragmentRef = java.lang.ref.WeakReference(fragment)
        val treeRef = java.lang.ref.WeakReference(root.viewTreeObserver)
        val replay = LyricReplayRecovery()
        var lastClockRepair = SystemClock.uptimeMillis()
        val poll = object : Runnable {
            override fun run() {
                val currentRoot = rootRef.get() ?: return
                val current = fragmentRef.get() ?: return
                if (!PluginLifetime.alive || !currentRoot.isAttachedToWindow) return
                runCatching {
                    if (currentRoot.isShown && hidden.invoke(current) != true && isCurrentSong?.invoke(current) != false) {
                        val token = pointer.get(current) ?: return@runCatching
                        val clock = playbackClock?.sample(current) ?: return@runCatching
                        val list = binding.get(current)?.let { recycler.get(it) } ?: return@runCatching
                        val state = (scrollState.invoke(list) as Number).toInt()
                        finishReturn(current, state)
                        val position = replay.observe(token, clock.position, state == 1, currentSongId?.invoke(current),
                            LyricsPreference.forceRepeatReset(currentRoot.context))
                        if (position != null) recoverPlayback(current, token, position, currentRoot, replay.opening)
                        else if (clock.playing && state != 1 && follow.getBoolean(current) && returning.get() !== current &&
                            ownsReplacement?.invoke(current, token) == true &&
                            (boundaryToken !== token || correctStalledLyricPosition(lastBoundaryPosition, clock.position) != lastBoundaryPosition) &&
                            SystemClock.uptimeMillis() - lastClockRepair >= 1_500L) {
                            lastClockRepair = SystemClock.uptimeMillis()
                            recoverPlayback(current, token, clock.position, currentRoot, opening = false)
                        }
                    }
                }.onFailure { ModernXposedRuntime.log("Playback lyric recovery failed open", it) }
                currentRoot.postDelayed(this, 500L)
            }
        }
        // Seed from the media clock now: an outro can stop native callbacks before the next poll.
        runCatching { playbackClock?.sample(fragment)?.let { clock -> pointer.get(fragment)?.let { replay.observe(it, clock.position, false, currentSongId?.invoke(fragment)) } } }
        if (playbackClock != null && root.isAttachedToWindow) root.postDelayed(poll, 500L)
        val listener = ViewTreeObserver.OnWindowFocusChangeListener { focused ->
            if (focused) fragmentRef.get()?.let { current ->
                runCatching { schedule(current) }.onFailure { ModernXposedRuntime.log("Lyrics focus recovery failed open", it) }
            }
        }
        root.viewTreeObserver.addOnWindowFocusChangeListener(listener)
        val attachment = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                v.viewTreeObserver.takeIf { it.isAlive }?.addOnWindowFocusChangeListener(listener)
                if (playbackClock != null && PluginLifetime.alive) { v.removeCallbacks(poll); v.postDelayed(poll, 500L) }
            }
            override fun onViewDetachedFromWindow(v: View) {
                v.removeCallbacks(poll)
                treeRef.get()?.takeIf { it.isAlive }?.removeOnWindowFocusChangeListener(listener)
                rootRef.get()?.let { detached ->
                    detached.viewTreeObserver.takeIf { it.isAlive }?.removeOnWindowFocusChangeListener(listener)
                }
                fragmentRef.get()?.let { if (returning.get() === it) { smoothReturn?.release(it); returning.clear() } }
            }
        }
        root.addOnAttachStateChangeListener(attachment)
        PluginLifetime.close {
            rootRef.get()?.let { owned ->
                owned.removeCallbacks(poll)
                owned.removeOnAttachStateChangeListener(attachment)
                owned.viewTreeObserver.takeIf { it.isAlive }?.removeOnWindowFocusChangeListener(listener)
                watched.remove(owned)
            }
            fragmentRef.get()?.let { if (returning.get() === it) { smoothReturn?.release(it); returning.clear() } }
        }
    }

    private fun recoverPlayback(fragment: Any, token: Any, position: Long, root: View, opening: Boolean) {
        if (pointer.get(fragment) !== token) return
        val current = adapter.get(fragment) ?: return
        unlock.invoke(current)
        nativeFollow.invoke(current, true)
        if (opening) requestLoopReturn(fragment)
        else if (returning.get() !== fragment) follow.setBoolean(fragment, true)
        recoveringPlayback = true
        try {
            // Explicit media position avoids re-reading the native global clock on the first seek.
            val delay = (processAt.invoke(processor.get(fragment), token, position,
                *callbacks.map { it.get(fragment) }.toTypedArray()) as? Number)?.toLong() ?: 100L
            playbackClock?.restart(fragment, root.resources.getIdentifier("message_lyrics_process_events", "id",
                com.tcrrry.lyrics.engine.ModuleConstants.RESOURCE_PACKAGE), delay)
        } finally { recoveringPlayback = false }
        LyricsPlaybackDiagnostics.record("playback-recover", "pos=$position")
        ModernXposedRuntime.log("Lyrics r4 clock repair: position=$position opening=$opening")
        if (!opening && returning.get() !== fragment) schedule(fragment, refreshClock = false)
    }

    fun schedule(fragment: Any, refreshClock: Boolean = true, smooth: Boolean = false, opening: Boolean = false) {
        LyricsPlaybackDiagnostics.record("anchor-request", "fragment=${LyricsPlaybackDiagnostics.identity(fragment)} refreshClock=$refreshClock")
        val root = view.invoke(fragment) as? View ?: return
        active = java.lang.ref.WeakReference(fragment)
        installedAnchor = this
        val installed = pointer.get(fragment) ?: return
        root.post {
            if (!PluginLifetime.alive || view.invoke(fragment) !== root || pointer.get(fragment) !== installed || !root.isAttachedToWindow || !root.isShown || hidden.invoke(fragment) == true || isCurrentSong?.invoke(fragment) == false) {
                LyricsPlaybackDiagnostics.record("anchor-skip", "stale-or-hidden")
                return@post
            }
            val list = binding.get(fragment)?.let { recycler.get(it) }
            if (list == null || scrollState.invoke(list) == 1) {
                LyricsPlaybackDiagnostics.record("anchor-skip", "missing-list-or-finger-drag")
                return@post
            }
            if (smooth || (!opening && returning.get() === fragment)) return@post // Never rebind/jump during an animated return.
            runCatching {
                val current = adapter.get(fragment) ?: return@runCatching
                unlock.invoke(current)
                nativeFollow.invoke(current, true)
                follow.setBoolean(fragment, true)
                // Evaluate the actual playback clock even when paused; never seek the audio.
                if (refreshClock) processor.get(fragment)?.let { owner ->
                    val clock = if (ownsReplacement?.invoke(fragment, installed) == true) playbackClock?.sample(fragment) else null
                    if (clock == null) processNow.invoke(owner, installed, *callbacks.map { it.get(fragment) }.toTypedArray())
                    else {
                        val delay = (processAt.invoke(owner, installed, clock.position,
                            *callbacks.map { it.get(fragment) }.toTypedArray()) as? Number)?.toLong() ?: 100L
                        playbackClock?.restart(fragment, root.resources.getIdentifier("message_lyrics_process_events", "id",
                            com.tcrrry.lyrics.engine.ModuleConstants.RESOURCE_PACKAGE), delay)
                    }
                }
                rebind.invoke(current)
                LyricsPlaybackDiagnostics.record("anchor-refresh", "doc=${LyricsPlaybackDiagnostics.identity(installed)} cached=${highlighted.invoke(current)}")
            }.onFailure { LyricsPlaybackDiagnostics.record("anchor-refresh-error", it.javaClass.simpleName); ModernXposedRuntime.log("Native lyric state refresh failed open", it) }
        }
        afterLayout(root, valid = {
            PluginLifetime.alive && view.invoke(fragment) === root && pointer.get(fragment) === installed && hidden.invoke(fragment) != true && isCurrentSong?.invoke(fragment) != false && (opening || returning.get() !== fragment)
        }) {
            val list = binding.get(fragment)?.let { recycler.get(it) } ?: return@afterLayout
            if (scrollState.invoke(list) == 1) return@afterLayout // Keep an active finger drag under user control.
            val current = adapter.get(fragment) ?: return@afterLayout
            @Suppress("UNCHECKED_CAST")
            val ids = currentPresentationHighlights?.invoke(installed)?.toSortedSet()
                ?: highlighted.invoke(current) as? java.util.SortedSet<Int> ?: sortedSetOf()
            presentationRecovered?.invoke(ids.toSet())
            LyricsPlaybackDiagnostics.record("anchor-layout", "ids=$ids cached=${highlighted.invoke(current)} follow=${follow.getBoolean(fragment)}")
            val scrollIds = LyricReplayRecovery.scrollIds(ids, opening)
            if (scrollIds.isEmpty()) return@afterLayout
            val listener = callback.get(fragment) ?: return@afterLayout
            // These are the native processor's current IDs, including offsets and overlapping lines.
            follow.setBoolean(fragment, true)
            val port = smoothReturn.takeIf { smooth }
            val animated = if (port != null) port.run(fragment, list, root, installed) {
                scroll.invoke(listener, scrollIds.first(), scrollIds.last())
            } else {
                scroll.invoke(listener, scrollIds.first(), scrollIds.last())
                false
            }
            // L.b can leave V0 set while the target is offscreen; that would allow the next
            // line callback to jump over the in-flight smooth scroll.
            if (animated) follow.setBoolean(fragment, false)
            LyricsPlaybackDiagnostics.record("anchor-scroll", "first=${scrollIds.first()} last=${scrollIds.last()} smooth=$animated")
        }
    }

    companion object {
        private var installedAnchor: NativeLyricsImmediateAnchor? = null
        internal var processObserved: ((Any, Long?) -> Unit)? = null
        internal var presentationRecovered: ((Set<Int>) -> Unit)? = null
        internal var currentPresentationHighlights: ((Any) -> Set<Int>?)? = null
        internal fun recoverFollow(owner: Any?): Boolean {
            val anchor = installedAnchor ?: return false
            if (owner == null || !anchor.fragmentClass.isInstance(owner)) return false
            val root = anchor.view.invoke(owner) as? View ?: return false
            if (!root.isAttachedToWindow || !root.isShown || anchor.hidden.invoke(owner) == true) return false
            val list = anchor.binding.get(owner)?.let { anchor.recycler.get(it) } ?: return false
            if (anchor.scrollState.invoke(list) == 1) return false
            anchor.watch(owner)
            anchor.schedule(owner, smooth = anchor.smoothReturn != null)
            return true
        }
        internal fun beforeProcess(token: Any, position: Long?) {
            runCatching { installedAnchor?.processBoundary(token, position) }
                .onFailure { ModernXposedRuntime.log("Lyric replay unlock failed open", it) }
        }

        fun resolve(type: Class<*>, installName: String): NativeLyricsImmediateAnchor? =
            if (installName == "w2" && type.name == "com.apple.android.music.player.fragment.PlayerLyricsViewFragment")
                runCatching {
                    NativeLyricsImmediateAnchor(type).also { anchor ->
                        anchor.smoothReturn = NativeLyricsSmoothReturn.resolve(type)
                        // A plugin enabled on an existing lyrics page may have
                        // missed onResume and the pointer installation entirely.
                        // Native processing is another verified entry point.
                        ModernXposedRuntime.hookMethod(type.getDeclaredMethod("y2", Int::class.javaPrimitiveType), object : ModernMethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                param.thisObject?.let { owner -> runCatching { anchor.watch(owner) }
                                    .onFailure { ModernXposedRuntime.log("Lyrics clock recovery registration failed open", it) } }
                            }
                        })
                        // Ordinary Java owner observes playback independently of the optional blur feature.
                        val method = anchor.processor.type.getDeclaredMethod("c", anchor.pointer.type,
                            Long::class.javaPrimitiveType, *anchor.callbacks.map { it.type }.toTypedArray())
                        val installed = ModernXposedRuntime.hookMethod(method, object : ModernMethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                val token = param.args.firstOrNull() ?: return
                                val position = anchor.correctedPosition(token, (param.args.getOrNull(1) as? Number)?.toLong())
                                if (position != null) param.args[1] = position
                                runCatching {
                                    processObserved?.invoke(token, position)
                                    beforeProcess(token, position)
                                }.onFailure { LyricsPlaybackDiagnostics.record("owner-error", it.javaClass.simpleName) }
                            }
                        })
                        LyricsPlaybackDiagnostics.record("owner-hook", "installed=$installed")
                    }
                }.onFailure {
                    LyricsPlaybackDiagnostics.record("anchor-resolve-error", it.javaClass.simpleName + ":" + it.message)
                    ModernXposedRuntime.log("Native lyrics anchor unavailable", it)
                }.getOrNull() else null

        internal fun afterLayout(root: View, valid: () -> Boolean, anchor: () -> Unit) {
            if (!root.isAttachedToWindow) return
            val tree = root.viewTreeObserver
            val listener = object : ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {
                fun remove() {
                    tree.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
                    root.viewTreeObserver.takeIf { it !== tree && it.isAlive }?.removeOnPreDrawListener(this)
                    root.removeOnAttachStateChangeListener(this)
                }
                override fun onPreDraw(): Boolean {
                    remove()
                    root.post {
                        runCatching { if (root.isAttachedToWindow && root.isShown && valid()) anchor() }
                            .onFailure { ModernXposedRuntime.log("Immediate lyrics anchor failed open", it) }
                    }
                    return true
                }
                override fun onViewDetachedFromWindow(v: View) { remove() }
                override fun onViewAttachedToWindow(v: View) = Unit
            }
            tree.addOnPreDrawListener(listener)
            root.addOnAttachStateChangeListener(listener)
            root.postInvalidateOnAnimation()
        }
    }
}

/** Preserve small native offsets; recover only a clearly divergent, valid media clock. */
internal fun correctStalledLyricPosition(native: Long?, media: Long): Long? =
    if (media >= 0L && (native == null || native < 0L || kotlin.math.abs(native - media) > 1_500L)) media else native
