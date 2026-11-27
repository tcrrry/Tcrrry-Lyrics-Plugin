package com.tcrrry.lyrics.engine.hook

import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import com.tcrrry.lyrics.plugin.PluginHandler
import com.tcrrry.lyrics.plugin.PluginLifetime
import java.lang.ref.WeakReference
import java.lang.reflect.Modifier

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
    private val unlock = adapter.type.lyricsMethod("N")
    private val enable = adapter.type.lyricsMethod("P", Boolean::class.javaPrimitiveType)
    private val follow = field("V0")
    private val shareSuppress = runCatching {
        val name = NativeLyricsHostContract.shareSuppressFieldName() ?: "W0"
        type.getDeclaredField(name).apply { isAccessible = true }
    }.getOrNull()?.takeIf { it.type == Boolean::class.javaPrimitiveType && it.name != follow.name }
    private val clearShareSuppress = runCatching { type.getDeclaredMethod("s2").apply { isAccessible = true } }.getOrNull()
    private val binding = field("n0")
    private val recycler = binding.type.lyricsDeclaredField("f0").apply { isAccessible = true }
    private val scrollState = recycler.type.getMethod("getScrollState")
    private val callback = field("Z0")
    private val scroll = callback.type.getDeclaredMethod("b", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
    private val processor = field("b1")
    private val callbacks = listOf("c1", "d1", "e1", "f1", "g1").map(::field)
    private val process = processor.type.getDeclaredMethod("c", pointer.type, Long::class.javaPrimitiveType,
        *callbacks.map { it.type }.toTypedArray())
    private val adapterPointer = runCatching { adapter.type.lyricsMethod("y") }.getOrNull()
    private val browsingGetter = runCatching { adapter.type.lyricsMethod("C") }.getOrNull()
    private val setBrowsing = runCatching {
        adapter.type.lyricsMethod("Q", Boolean::class.javaPrimitiveType)
    }.getOrNull()
    private val lineToAdapterPos = runCatching {
        adapter.type.lyricsMethod("E", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
    }.getOrNull()
    private val highlightedLines = runCatching {
        adapter.type.lyricsMethod("w").takeIf { it.returnType == java.util.TreeSet::class.java }
    }.getOrNull()
    private val rebindAdapter = runCatching { adapter.type.lyricsMethod("g") }.getOrNull()
    private val karaokeClass = runCatching {
        type.classLoader?.lyricsClass("com.apple.android.music.player.A")
    }.getOrNull()
    private val wordAnimationEnabled = runCatching {
        karaokeClass?.lyricsDeclaredField("A")?.apply { isAccessible = true }
    }.getOrNull()
    private val isLineVisibleMethod = runCatching {
        type.getDeclaredMethod("e2", type, Int::class.javaPrimitiveType).apply { isAccessible = true }
    }.getOrNull()
    private val childAdapterPosMethod = runCatching {
        recycler.type.declaredMethods.firstOrNull { method ->
            Modifier.isStatic(method.modifiers) &&
                method.parameterTypes.contentEquals(arrayOf(View::class.java)) &&
                method.returnType == Int::class.javaPrimitiveType
        }?.apply { isAccessible = true }
    }.getOrNull()
    private val timedStateField = runCatching { field("E0") }.getOrNull()
    private val observableStateField = runCatching { field("G0") }.getOrNull()
    private val observableStateValue = runCatching {
        observableStateField?.type?.lyricsDeclaredField("a")?.apply { isAccessible = true }
    }.getOrNull()
    private val layoutManagerField = runCatching { field("s0") }.getOrNull()
    private val layoutScrollEnabledField = runCatching {
        layoutManagerField?.type?.declaredFields?.singleOrNull {
            it.type == Boolean::class.javaPrimitiveType && !Modifier.isStatic(it.modifiers)
        }?.apply { isAccessible = true }
    }.getOrNull()
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
    private var userTouchActive = false
    private var lastUserTouchAt = Long.MIN_VALUE
    private var focusAnomalySince = 0L
    private var lastFocusRecoveryAt = Long.MIN_VALUE
    private var clockStallSince = 0L
    private var missingEventSince = 0L
    private var lastClockRecoveryAt = Long.MIN_VALUE
    private var lastObservedHighlighted: Set<Int> = emptySet()
    private var lastHighlightAdvanceAt = Long.MIN_VALUE
    private var lastClockRecoveryExpectedMax: Int? = null
    private var clockSkewSuppressed = false
    private val anchors = NativeLyricsSeekAnchor()
    private val presentation = NativeLyricsSeekPresentation.resolve(type)
    private var closed = false
    private var revision = 0L
    private val hooks = HookRegistrationScope()
    private val seekReady = Runnable {
        runCatching { sample() }.onFailure { ModernXposedRuntime.log("Native lyric seek event failed open", it) }
        schedule()
    }
    // The poll stops with the view. This deadline belongs to the seek itself.
    private val seekTimeout = Runnable { cancelPendingSeek() }
    private val attachment = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) { if (!closed) schedule() }
        override fun onViewDetachedFromWindow(v: View) {
            cancelPendingSeek()
            main.removeCallbacks(poll); main.removeCallbacks(seekReady); revision++
            transitioning = false; userTouchActive = false; clearFocusWatchdogTimers()
            presentation?.cancel()
        }
    }

    init {
        presentation?.mediaPosition = { owner.get()?.let { clock.sample(it)?.position } }
        // Observe RecyclerView touch events without consuming them so focus recovery yields to user scrolling.
        for (name in listOf("onInterceptTouchEvent", "onTouchEvent")) {
            runCatching {
                recycler.type.getDeclaredMethod(name, MotionEvent::class.java)
            }.getOrNull()?.let { touchMethod ->
                ModernXposedRuntime.hookMethod(touchMethod, object : ModernMethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val fragment = owner.get() ?: return
                        val boundList = runCatching { binding.get(fragment)?.let { recycler.get(it) } }.getOrNull() ?: return
                        if (param.thisObject !== boundList) return
                        val event = param.args.firstOrNull() as? MotionEvent ?: return
                        val now = SystemClock.uptimeMillis()
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN,
                            MotionEvent.ACTION_POINTER_DOWN,
                            MotionEvent.ACTION_MOVE -> {
                                userTouchActive = true
                                lastUserTouchAt = now
                                clearFocusWatchdogTimers()
                            }
                            MotionEvent.ACTION_UP,
                            MotionEvent.ACTION_CANCEL -> {
                                userTouchActive = false
                                lastUserTouchAt = now
                                clearFocusWatchdogTimers()
                            }
                        }
                    }
                }, hooks)
            }
        }
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
                clearFocusWatchdogTimers()
                transitionDocument = WeakReference(pointer.get(fragment))
                presentation?.block(fragment)
                main.removeCallbacks(seekReady)
                main.removeCallbacks(seekTimeout)
                main.postDelayed(seekTimeout, 2_000L)
            }
        }, after = { receiver, success ->
            val fragment = owner.get()
            if (!closed && fragment != null && clock.owns(fragment, receiver)) {
                if (!success) cancelPendingSeek()
                else { main.removeCallbacks(seekReady); main.postDelayed(seekReady, 60L) }
            }
        })
        runCatching {
            type.getDeclaredMethod("y2", Int::class.javaPrimitiveType)
        }.getOrNull()?.let { tickMethod ->
            ModernXposedRuntime.hookMethod(tickMethod, object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val fragment = param.thisObject ?: return
                    if (!closed && owner.get() !== fragment) {
                        runCatching { watch(fragment) }
                            .onFailure { ModernXposedRuntime.log("Native lyric auto-watch failed open", it) }
                    }
                }
            }, hooks)
        }
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
        cancelPendingSeek()
        root.get()?.removeOnAttachStateChangeListener(attachment)
        main.removeCallbacks(poll)
        revision++
        replay = LyricReplayRecovery()
        sampledSong = null; lastPosition = null; pendingPausedSeek = false
        requestedPosition = null; transitioning = false; presentation?.cancel()
        userTouchActive = false; lastUserTouchAt = Long.MIN_VALUE; clearFocusWatchdogTimers(); clearClockSkewTracking()
        owner = WeakReference(fragment); root = WeakReference(view)
        view.addOnAttachStateChangeListener(attachment)
        runCatching { sample(seed = true) }
        schedule()
    }

    private fun schedule() {
        main.removeCallbacks(poll)
        if (!closed && PluginLifetime.alive && root.get()?.isAttachedToWindow == true) main.postDelayed(poll, 500L)
    }

    private fun clearFocusWatchdogTimers() {
        focusAnomalySince = 0L
        clockStallSince = 0L
        missingEventSince = 0L
    }

    private fun clearClockSkewTracking() {
        lastObservedHighlighted = emptySet()
        lastHighlightAdvanceAt = Long.MIN_VALUE
        lastClockRecoveryExpectedMax = null
        clockSkewSuppressed = false
    }

    private fun isTimedState(fragment: Any): Boolean {
        val timedField = timedStateField ?: return true
        val obsField = observableStateField ?: return true
        val valueField = observableStateValue ?: return true
        return runCatching {
            val obs = obsField.get(fragment) ?: return@runCatching true
            val expected = timedField.get(fragment) ?: return@runCatching true
            valueField.get(obs) === expected
        }.getOrDefault(true)
    }

    private fun isShareSuppressed(fragment: Any): Boolean {
        val field = shareSuppress ?: return false
        return runCatching { field.getBoolean(fragment) }.getOrDefault(false)
    }

    private fun clearNativeSuppress(fragment: Any) {
        if (!isShareSuppressed(fragment)) return
        runCatching { clearShareSuppress?.invoke(fragment) }
        runCatching { shareSuppress?.setBoolean(fragment, false) }
    }

    private fun isScrollLocked(fragment: Any): Boolean {
        val lmField = layoutManagerField ?: return false
        val scrollField = layoutScrollEnabledField ?: return false
        return runCatching {
            val lm = lmField.get(fragment) ?: return@runCatching false
            !scrollField.getBoolean(lm)
        }.getOrDefault(false)
    }

    private fun unlockScrollManager(fragment: Any) {
        val lmField = layoutManagerField ?: return
        val scrollField = layoutScrollEnabledField ?: return
        runCatching {
            val lm = lmField.get(fragment) ?: return@runCatching
            if (!scrollField.getBoolean(lm)) scrollField.setBoolean(lm, true)
        }
    }

    private fun toAdapterPos(current: Any, lineId: Int): Int =
        runCatching { (lineToAdapterPos?.invoke(current, lineId, false) as? Number)?.toInt() }
            .getOrNull()?.takeIf { it >= 0 } ?: lineId

    private fun readHighlighted(current: Any): Set<Int>? = highlightedLines?.let { method ->
        runCatching {
            (method.invoke(current) as? Collection<*>)?.mapNotNull { (it as? Number)?.toInt() }?.toSet()
        }.getOrNull()
    }

    private fun isWordAnimationStopped(current: Any): Boolean {
        val karaoke = karaokeClass ?: return false
        val gate = wordAnimationEnabled ?: return false
        return karaoke.isInstance(current) && runCatching { !gate.getBoolean(current) }.getOrDefault(false)
    }

    private fun isTargetVisible(fragment: Any, list: Any, positions: Collection<Int>): Boolean? {
        if (positions.isEmpty()) return null
        val method = isLineVisibleMethod
        if (method != null) {
            return runCatching {
                positions.any { pos -> method.invoke(null, fragment, pos) == true }
            }.getOrNull()
        }
        val group = list as? ViewGroup ?: return null
        val accessor = childAdapterPosMethod ?: return null
        val visible = (0 until group.childCount).mapNotNull { index ->
            val child = group.getChildAt(index)
            if (child.bottom > group.paddingTop && child.top < group.height - group.paddingBottom) {
                (accessor.invoke(null, child) as? Int)?.takeIf { it >= 0 }
            } else null
        }
        if (visible.isEmpty()) return null
        return positions.any { it in visible }
    }

    private fun isTargetDriftedOutOfFocusZone(
        list: Any,
        positions: Collection<Int>,
        scrollLocked: Boolean,
    ): Boolean? {
        val firstTarget = positions.minOrNull() ?: return null
        val group = list as? ViewGroup ?: return null
        val accessor = childAdapterPosMethod ?: return null
        val contentTop = group.paddingTop
        val contentBottom = group.height - group.paddingBottom
        val contentHeight = contentBottom - contentTop
        if (contentHeight <= 0 || group.childCount == 0) return null
        var seenValidChild = false
        var targetView: View? = null
        for (index in 0 until group.childCount) {
            val child = group.getChildAt(index) ?: continue
            val pos = runCatching { (accessor.invoke(null, child) as? Number)?.toInt() }.getOrNull() ?: continue
            if (pos >= 0) {
                seenValidChild = true
                if (pos == firstTarget) {
                    targetView = child
                    break
                }
            }
        }
        if (!seenValidChild) return null
        if (targetView == null) return true
        if (targetView.bottom <= contentTop || targetView.top >= contentBottom) return true
        if (targetView.top < contentTop - (contentHeight * 0.05f).toInt() && group.canScrollVertically(-1)) return true
        if (targetView.top > contentTop + (contentHeight * 0.60f).toInt() &&
            (scrollLocked || group.canScrollVertically(1))) return true
        return false
    }

    private fun monitorFocusAndClock(fragment: Any, view: View, list: Any, token: Any, position: Long) {
        val now = SystemClock.uptimeMillis()
        if (userTouchActive && lastUserTouchAt != Long.MIN_VALUE && now - lastUserTouchAt > 6_000L) {
            userTouchActive = false
        }
        if (userTouchActive || (lastUserTouchAt != Long.MIN_VALUE && now - lastUserTouchAt < 3_500L)) {
            clearFocusWatchdogTimers()
            return
        }
        val current = adapter.get(fragment) ?: return
        val pointerGetter = adapterPointer
        if (pointerGetter != null && runCatching { pointerGetter.invoke(current) }.getOrNull() !== token) {
            clearFocusWatchdogTimers()
            return
        }
        if (!isTimedState(fragment)) {
            clearFocusWatchdogTimers()
            return
        }
        val message = view.resources.getIdentifier("message_lyrics_process_events", "id",
            com.tcrrry.lyrics.engine.ModuleConstants.RESOURCE_PACKAGE)
        val highlighted = readHighlighted(current)
        if (!highlighted.isNullOrEmpty() && highlighted != lastObservedHighlighted) {
            val prevMax = lastObservedHighlighted.maxOrNull()
            val curMax = highlighted.maxOrNull()
            if (prevMax != null && curMax != null && curMax > prevMax) {
                lastHighlightAdvanceAt = now
            }
            lastObservedHighlighted = highlighted
        }
        val expectedActive = anchors.activeLineIdsAt(token, position)
        val nearbyLines = anchors.lineIdsNear(token, position, toleranceMs = 1_200L)
        if (!highlighted.isNullOrEmpty() && highlighted.any { it in nearbyLines }) {
            clockSkewSuppressed = false
            lastClockRecoveryExpectedMax = null
        }
        val nativeAhead = !highlighted.isNullOrEmpty() && expectedActive.isNotEmpty() &&
            highlighted.min() > expectedActive.max()
        val nativeTimerAlive = clock.hasPendingEvents(fragment, message) == true
        val nativeRecentlyAdvanced = lastHighlightAdvanceAt != Long.MIN_VALUE && now - lastHighlightAdvanceAt < 10_000L
        if (nativeAhead && lastClockRecoveryExpectedMax != null &&
            lastClockRecoveryAt != Long.MIN_VALUE && now - lastClockRecoveryAt < 6_000L &&
            highlighted.min() > lastClockRecoveryExpectedMax!!) {
            if (!clockSkewSuppressed) {
                clockSkewSuppressed = true
                ModernXposedRuntime.log("Native lyric watchdog yielded to live ahead clock: position=$position expected=$expectedActive had=$highlighted")
            }
        }
        if (nativeAhead && (clockSkewSuppressed || nativeTimerAlive || nativeRecentlyAdvanced)) {
            clearFocusWatchdogTimers()
            return
        }
        val clockLagged = highlighted != null && expectedActive.isNotEmpty() && highlighted.none { it in nearbyLines }
        if (clockLagged) {
            if (clockStallSince == 0L) clockStallSince = now
            val clockReady = lastClockRecoveryAt == Long.MIN_VALUE || now - lastClockRecoveryAt >= 2_500L
            if (now - clockStallSince >= 1_500L && clockReady) {
                lastClockRecoveryAt = now
                lastClockRecoveryExpectedMax = expectedActive.maxOrNull()
                clearFocusWatchdogTimers()
                clearNativeSuppress(fragment)
                unlockScrollManager(fragment)
                runCatching { setBrowsing?.invoke(current, false) }
                unlock.invoke(current)
                enable.invoke(current, true)
                follow.setBoolean(fragment, true)
                val delay = (process.invoke(processor.get(fragment), token, position,
                    *callbacks.map { it.get(fragment) }.toTypedArray()) as? Number)?.toLong() ?: 100L
                clock.restart(fragment, message, delay)
                val afterIds = readHighlighted(current).orEmpty().intersect(expectedActive).ifEmpty { expectedActive }
                val afterMapped = afterIds.map { toAdapterPos(current, it) }
                if (afterMapped.isNotEmpty() && isTargetVisible(fragment, list, afterMapped) == false) {
                    follow.setBoolean(fragment, true)
                    callback.get(fragment)?.let { scroll.invoke(it, afterMapped.min(), afterMapped.max()) }
                }
                ModernXposedRuntime.log("Native lyric stalled clock recovered: position=$position expected=$expectedActive had=$highlighted")
            }
            return
        }
        clockStallSince = 0L
        if (clock.hasPendingEvents(fragment, message) == false && anchors.hasUpcomingLine(token, position)) {
            if (missingEventSince == 0L) missingEventSince = now
            val missingReady = lastClockRecoveryAt == Long.MIN_VALUE || now - lastClockRecoveryAt >= 3_000L
            if (now - missingEventSince >= 1_500L && missingReady) {
                lastClockRecoveryAt = now
                missingEventSince = 0L
                clock.restart(fragment, message, 100L)
                ModernXposedRuntime.log("Native lyric dropped timer restarted: position=$position")
            }
        } else {
            missingEventSince = 0L
        }

        // Ignore stale highlights in gaps. Restarting a missing timer above is
        // still useful when another vocal line is coming.
        if (expectedActive.isEmpty()) {
            focusAnomalySince = 0L
            return
        }
        val activeIds = highlighted.orEmpty().intersect(expectedActive).ifEmpty { expectedActive }
        val stuckShareSuppress = isShareSuppressed(fragment)
        val isBrowsing = runCatching { browsingGetter?.invoke(current) == true }.getOrDefault(false)
        val wordStopped = isWordAnimationStopped(current)
        val scrollLocked = isScrollLocked(fragment)
        val mappedTargets = activeIds.map { toAdapterPos(current, it) }
        val isOffscreen = isTargetVisible(fragment, list, mappedTargets) == false
        val isDrifted = isTargetDriftedOutOfFocusZone(list, mappedTargets, scrollLocked) == true
        val focusBroken = stuckShareSuppress || isBrowsing || wordStopped || scrollLocked || isOffscreen || isDrifted
        if (!focusBroken) {
            focusAnomalySince = 0L
            return
        }
        if (focusAnomalySince == 0L) focusAnomalySince = now
        if (now - focusAnomalySince < 1_500L || (lastFocusRecoveryAt != Long.MIN_VALUE && now - lastFocusRecoveryAt < 2_500L)) return
        lastFocusRecoveryAt = now
        focusAnomalySince = 0L
        clearNativeSuppress(fragment)
        unlockScrollManager(fragment)
        runCatching { setBrowsing?.invoke(current, false) }
        unlock.invoke(current)
        enable.invoke(current, true)
        follow.setBoolean(fragment, true)
        callback.get(fragment)?.let { scroll.invoke(it, mappedTargets.min(), mappedTargets.max()) }
        if (wordStopped || (isBrowsing && setBrowsing == null)) runCatching { rebindAdapter?.invoke(current) }
        if (clock.hasPendingEvents(fragment, message) == false && anchors.hasUpcomingLine(token, position)) {
            clock.restart(fragment, message, 100L)
        }
        ModernXposedRuntime.log("Native lyric focus recovered: pos=$position active=$activeIds browsing=$isBrowsing wordStopped=$wordStopped scrollLocked=$scrollLocked offscreen=$isOffscreen drifted=$isDrifted shareSuppress=$stuckShareSuppress")
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
            if (sampledSong != null) {
                cancelPendingSeek()
                revision++; transitioning = false; presentation?.cancel()
                clearFocusWatchdogTimers()
                clearClockSkewTracking()
            }
            sampledSong = id; lastPosition = null; pendingPausedSeek = false
        }
        val previousPosition = lastPosition
        // A paused clock cannot move continuously: even a small forward change is an explicit seek.
        if (!seed && !time.playing && lastPosition?.let { it != time.position } == true) pendingPausedSeek = true
        lastPosition = time.position
        val list = binding.get(fragment)?.let { recycler.get(it) } ?: return
        val state = (scrollState.invoke(list) as Number).toInt()
        if (state == 1) {
            lastUserTouchAt = SystemClock.uptimeMillis()
            clearFocusWatchdogTimers()
        }
        val canContinueTransition = view.isAttachedToWindow && view.isShown && view.hasWindowFocus() &&
            hidden.invoke(fragment) != true && state != 1
        val ready = canContinueTransition && state == 0
        // Losing focus/starting a gesture can invalidate a queued layout callback.
        // Release native processing and retry once visible, rather than leaving the gate shut.
        if (transitioning && requestedPosition == null &&
            (!canContinueTransition || transitionDocument.get() !== token || SystemClock.uptimeMillis() - transitionStartedAt > 3_000L)) {
            revision++; transitioning = false; presentation?.cancel(); pendingPausedSeek = true
            clearFocusWatchdogTimers()
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
            ?: observed ?: time.position.takeIf { ready && pendingPausedSeek }
        if (position == null) {
            if (!seed && ready && time.playing && !transitioning && request == null) {
                monitorFocusAndClock(fragment, view, list, token, time.position)
            } else {
                clearFocusWatchdogTimers()
            }
            return
        }
        if (!ready || seed) {
            clearFocusWatchdogTimers()
            return
        }
        clearPendingSeek()
        pendingPausedSeek = false
        clearFocusWatchdogTimers()
        clockSkewSuppressed = false
        lastClockRecoveryExpectedMax = null
        val opening = observed != null && replay.opening
        val stamp = ++revision
        val current = adapter.get(fragment) ?: return
        clearNativeSuppress(fragment)
        unlockScrollManager(fragment)
        runCatching { setBrowsing?.invoke(current, false) }
        unlock.invoke(current)
        enable.invoke(current, true)
        follow.setBoolean(fragment, true)
        val releaseValid = {
            !closed && PluginLifetime.alive && stamp == revision && owner.get() === fragment &&
                pointer.get(fragment) === token && songId(fragment) == id && view.hasWindowFocus() &&
                hidden.invoke(fragment) != true && (scrollState.invoke(list) as Number).toInt() != 1
        }
        val valid = {
            releaseValid() && (scrollState.invoke(list) as Number).toInt() == 0
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
                clearNativeSuppress(fragment)
                unlockScrollManager(fragment)
                runCatching { setBrowsing?.invoke(current, false) }
                follow.setBoolean(fragment, true)
                val restored = if (staged) presentation?.enter(fragment, token, at) else null
                if (restored.isNullOrEmpty()) {
                    val anchor = if (opening) 0 else anchors.at(token, at)?.let { toAdapterPos(current, it) }
                    if (anchor != null) scroll.invoke(callback.get(fragment), anchor, anchor)
                }
                // The native adapter has entered. Reopen native event processing only
                // after that layout, so active-word events cannot race old holders.
                NativeLyricsImmediateAnchor.afterLayout(view, releaseValid) {
                    transitioning = false
                    presentation?.release()
                    if (opening && !restored.isNullOrEmpty()) {
                        val mapped = restored.map { toAdapterPos(current, it) }
                        if (isTargetVisible(fragment, list, mapped) == false) {
                            clearNativeSuppress(fragment)
                            unlockScrollManager(fragment)
                            runCatching { setBrowsing?.invoke(current, false) }
                            follow.setBoolean(fragment, true)
                            scroll.invoke(callback.get(fragment), 0, 0)
                        }
                    }
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

    private fun clearPendingSeek() {
        requestedPosition = null
        main.removeCallbacks(seekReady)
        main.removeCallbacks(seekTimeout)
    }

    private fun cancelPendingSeek() {
        if (requestedPosition == null) return
        clearPendingSeek()
        revision++
        transitioning = false
        transitionDocument.clear()
        presentation?.cancel()
        val fragment = owner.get() ?: return
        val view = root.get()?.takeIf { it.isAttachedToWindow } ?: return
        runCatching {
            clock.restart(fragment, view.resources.getIdentifier("message_lyrics_process_events", "id",
                com.tcrrry.lyrics.engine.ModuleConstants.RESOURCE_PACKAGE))
        }.onFailure { ModernXposedRuntime.log("Native lyric seek cancellation failed open", it) }
    }

    private fun reset() {
        cancelPendingSeek()
        revision++; replay = LyricReplayRecovery(); sampledSong = null; lastPosition = null
        pendingPausedSeek = false; requestedPosition = null; transitioning = false; clearFocusWatchdogTimers(); clearClockSkewTracking()
        presentation?.cancel()
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
