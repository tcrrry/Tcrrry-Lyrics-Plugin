package com.tcrrry.lyrics.engine.hook

/*
 * Ported from a23bc/amlyricblur, commit 3417e217d7692ae742bbae80d2bd51aadffcd59e.
 * Copyright (c) 2026 a23bc. Licensed under the MIT License.
 */

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView

/**
 * Target-independent AMLyricBlur runtime.
 *
 * Apple Music discovery and hooks live in [AppleMusicBidirectionalLyricBlurTarget]. This class
 * only consumes semantic lyric events and the two view accessors required by the renderer.
 */
internal class OpenSourceLyricBlurPort(
    private val targetAccess: LyricBlurTargetAccess,
    private val blurRadiusOffsetPx: Int = 0,
    private val probe: LyricHighlightProbe = LyricHighlightProbe(),
) : LyricBlurRuntime {
    companion object {
        private const val TAG = "AMLyricBlur"
        private const val SCROLL_RESTORE_DELAY_MS = 3_500L
        private const val FOLLOW_RECOVERY_DELAY_MS = 2_000L
        private const val FOLLOW_RECOVERY_COOLDOWN_MS = 2_000L
        private const val USER_BROWSING_GRACE_MS = 3_500L
        private const val MAX_RECYCLER_DISCOVERY_ATTEMPTS = 10
    }

    private val highlightSession = LyricHighlightSession()
    private val wordHighlightState = LyricWordHighlightState()
    private val blurRenderer = LyricBlurRenderer()

    private var recyclerView: Any? = null
    private var lyricsRootView: View? = null
    private var lyricsFragmentOwner: Any? = null
    private var recyclerDiscoveryRunnable: Runnable? = null
    private var recyclerDiscoveryAttempts = 0
    private var observedScrollView: View? = null
    private var scrollChangedListener: ViewTreeObserver.OnScrollChangedListener? = null
    private var isUserScrolling = false
    private var lastNativePosition: Long? = null
    private var currentDocument: Any? = null
    private val playbackEpoch = LyricPlaybackEpoch()
    private var lastUserTouchAt = Long.MIN_VALUE
    private var offscreenSince = 0L
    private var lastFollowRecoveryAt = 0L
    private var lastHighlightAt = 0L
    private val scrollHandler by lazy { com.tcrrry.lyrics.plugin.PluginHandler(Looper.getMainLooper()) }
    // Check independently of callbacks; a stopped callback stream must not stop recovery.
    private val followWatchdog = object : Runnable {
        override fun run() {
            val root = lyricsRootView ?: return
            runCatching { if (root.isShown && root.isAttachedToWindow) {
                recoverVisibleFollow()
            } }.onFailure { Log.w(TAG, "Lyric follow check failed", it) }
            scrollHandler.postDelayed(this, 750L)
        }
    }
    private var blurFrameScheduled = false
    private val blurFrameCallback = Choreographer.FrameCallback {
        blurFrameScheduled = false
        runCatching(::applyBlur)
            .onFailure { error -> Log.e(TAG, "Blur failed", error) }
    }
    private val restoreBlurRunnable = Runnable {
        isUserScrolling = false
        // The same inactivity timer ends clear browsing and restores follow. Do not add another wait.
        offscreenSince = SystemClock.uptimeMillis() - FOLLOW_RECOVERY_DELAY_MS
        recoverVisibleFollow()
        scheduleBlurUpdate()
    }
    init { com.tcrrry.lyrics.plugin.PluginLifetime.close {
        Handler(Looper.getMainLooper()).post { lyricsFragmentOwner?.let(::onLyricsViewDestroyed); blurRenderer.clearAll(); detachScrollListener() }
    } }

    override fun onSessionChanged(songInfo: Any) = onProcessPosition(songInfo, null)

    override fun onProcessPosition(songInfo: Any, position: Long?) {
        currentDocument = songInfo
        val change = playbackEpoch.update(songInfo, position)
        if (change != LyricPlaybackEpoch.Change.CONTINUOUS) LyricsPlaybackDiagnostics.record("blur-reset", "change=$change pos=$position")
        if (change != LyricPlaybackEpoch.Change.CONTINUOUS) {
            highlightSession.enter(songInfo)
            highlightSession.resetForReplay()
            wordHighlightState.clear()
            lastNativePosition = null
            lastHighlightAt = 0L
            offscreenSince = 0L
            lastFollowRecoveryAt = 0L
            blurRenderer.clearAll()
            scheduleBlurUpdate()
        }
    }

    override fun currentLineHighlights(songInfo: Any): Set<Int>? = highlightSession.current(songInfo)

    override fun onPresentationRecovered(lineIds: Set<Int>) {
        // A native rebind can overwrite RenderEffects without changing our cached targets.
        // Keep the latest processor callbacks: adapter animation can still contain older IDs.
        LyricsPlaybackDiagnostics.record("blur-recovered", "requested=$lineIds current=${followHighlights()}")
        blurRenderer.clearAll()
        applyBlur(immediate = true)
    }

    override fun onNativeHighlightsChanged(lineIds: Set<Int>, nativePosition: Long?) {
        val previousPosition = lastNativePosition
        if (nativePosition != null && previousPosition != null && nativePosition < previousPosition) {
            wordHighlightState.clear()
            highlightSession.resetForReplay()
            offscreenSince = 0L
            lastFollowRecoveryAt = 0L
        }
        if (nativePosition != null) lastNativePosition = nativePosition
        onHighlightsChanged(lineIds)
    }

    override fun onHighlightsChanged(lineIds: Set<Int>) {
        lastHighlightAt = SystemClock.uptimeMillis()
        wordHighlightState.onLineHighlightsChanged(lineIds)
        val activeIds = highlightSession.update(lineIds)
        probe.recordSessionUpdate(
            incomingIds = lineIds,
            activeIds = activeIds,
            gap = highlightSession.isGap(),
            opening = highlightSession.isOpeningHighlight(),
        )
        scheduleBlurUpdate()
    }

    override fun onFallbackHighlightChanged(lineId: Int) {
        lastHighlightAt = SystemClock.uptimeMillis()
        wordHighlightState.clear()
        highlightSession.replace(lineId)
        probe.recordSessionUpdate(
            incomingIds = setOf(lineId),
            activeIds = highlightSession.snapshot(),
            gap = highlightSession.isGap(),
            opening = highlightSession.isOpeningHighlight(),
        )
        scheduleBlurUpdate()
    }

    override fun onWordHighlightsChanged(source: String, lineIds: Set<Int>) {
        if (lineIds.isNotEmpty()) lastHighlightAt = SystemClock.uptimeMillis()
        wordHighlightState.update(source, lineIds)
        scheduleBlurUpdate()
    }

    override fun onLyricsViewCreated(owner: Any, root: View) {
        lyricsFragmentOwner?.let(::releaseLyricsView)
        lyricsFragmentOwner = owner
        lyricsRootView = root
        scrollHandler.removeCallbacks(followWatchdog)
        scrollHandler.postDelayed(followWatchdog, 750L)
        recyclerDiscoveryAttempts = 0
        scheduleRecyclerViewDiscovery(root, delayMs = 500L)
    }

    override fun onLyricsViewDestroyed(owner: Any) {
        releaseLyricsView(owner)
    }

    private fun releaseLyricsView(owner: Any) {
        if (owner !== lyricsFragmentOwner) return
        recyclerDiscoveryRunnable?.let { discovery ->
            scrollHandler.removeCallbacks(discovery)
        }
        recyclerDiscoveryRunnable = null
        recyclerDiscoveryAttempts = 0
        scrollHandler.removeCallbacks(restoreBlurRunnable)
        scrollHandler.removeCallbacks(followWatchdog)
        if (blurFrameScheduled) {
            Choreographer.getInstance().removeFrameCallback(blurFrameCallback)
            blurFrameScheduled = false
        }
        detachScrollListener()
        blurRenderer.clearAll()
        wordHighlightState.clear()
        lastNativePosition = null
        offscreenSince = 0L
        currentDocument = null
        recyclerView = null
        lyricsRootView = null
        lyricsFragmentOwner = null
        isUserScrolling = false
    }

    private fun scheduleRecyclerViewDiscovery(root: View, delayMs: Long) {
        if (root !== lyricsRootView) return
        if (recyclerDiscoveryAttempts >= MAX_RECYCLER_DISCOVERY_ATTEMPTS) {
            recyclerDiscoveryRunnable = null
            Log.w(TAG, "RV discovery stopped after $recyclerDiscoveryAttempts attempts")
            return
        }
        recyclerDiscoveryAttempts += 1
        recyclerDiscoveryRunnable?.let(scrollHandler::removeCallbacks)
        val discovery = Runnable {
            recyclerDiscoveryRunnable = null
            if (root === lyricsRootView) findRecyclerView(root)
        }
        recyclerDiscoveryRunnable = discovery
        scrollHandler.postDelayed(discovery, delayMs)
    }

    private fun findRecyclerView(view: View) {
        if (recyclerView != null) return
        try {
            val rv = findRVInHierarchy(view)
            if (rv != null) {
                recyclerView = rv
                recyclerDiscoveryAttempts = 0
                Log.i(TAG, "RV FOUND")
                attachScrollListener(rv)
            } else {
                scheduleRecyclerViewDiscovery(view, delayMs = 1_000L)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "findRV error", t)
        }
    }

    private fun findRVInHierarchy(view: View): Any? {
        if (targetAccess.isRecyclerView(view)) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val result = findRVInHierarchy(view.getChildAt(i))
                if (result != null) return result
            }
        }
        return null
    }

    private fun scheduleBlurUpdate() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            scrollHandler.post(::scheduleBlurUpdate)
            return
        }
        if (blurFrameScheduled) return
        blurFrameScheduled = true
        Choreographer.getInstance().postFrameCallback(blurFrameCallback)
    }

    private fun attachScrollListener(rv: Any) {
        try {
            val view = rv as View
            detachScrollListener()
            val listener = ViewTreeObserver.OnScrollChangedListener(::onScrollDetected)
            view.viewTreeObserver.addOnScrollChangedListener(listener)
            observedScrollView = view
            scrollChangedListener = listener
            Log.i(TAG, "Scroll listener attached")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to attach scroll listener", t)
        }
    }

    override fun onLyricsTouch(view: View, event: MotionEvent) {
        if (view !== observedScrollView) return
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                isUserScrolling = true
                lastUserTouchAt = SystemClock.uptimeMillis()
                offscreenSince = 0L
                scrollHandler.removeCallbacks(restoreBlurRunnable)
                if (event.actionMasked == MotionEvent.ACTION_DOWN) applyBlur(includeFocus = false, immediate = true)
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> {
                lastUserTouchAt = SystemClock.uptimeMillis()
                scheduleScrollRestore()
            }
        }
    }

    private fun detachScrollListener() {
        val view = observedScrollView
        val listener = scrollChangedListener
        if (view != null && listener != null) {
            val observer = view.viewTreeObserver
            if (observer.isAlive) observer.removeOnScrollChangedListener(listener)
        }
        observedScrollView = null
        scrollChangedListener = null
    }

    private fun onScrollDetected() {
        if (!isUserScrolling) {
            scheduleBlurUpdate()
            return
        }
        // Native layout/scroll notifications must not restart the user inactivity timer.
        applyBlur(includeFocus = false, immediate = true)
    }

    private fun scheduleScrollRestore() {
        scrollHandler.removeCallbacks(restoreBlurRunnable)
        scrollHandler.postDelayed(restoreBlurRunnable, SCROLL_RESTORE_DELAY_MS)
    }

    private fun getRv(): Any? {
        val rv = recyclerView ?: return null
        if ((rv as? ViewGroup)?.childCount?.let { it > 0 } == true) return rv
        val root = lyricsRootView ?: return null
        val fresh = findRVInHierarchy(root)
        if (fresh != null) {
            recyclerView = fresh
            return fresh
        }
        return null
    }

    private fun applyBlur(
        includeFocus: Boolean = true,
        immediate: Boolean = false,
    ) {
        val focusEnabled = includeFocus && !isUserScrolling
        val rv = getRv() as? ViewGroup ?: return
        val visibleRows = ArrayList<Pair<View, Int>>(rv.childCount)
        val instrumentalRows = ArrayList<Pair<View, Int>>(1)
        val creditsRows = ArrayList<Pair<View, Int>>(2)

        for (i in 0 until rv.childCount) {
            val child = rv.getChildAt(i) ?: continue
            val adapterPos = targetAccess.adapterPosition(child)
            if (targetAccess.isCreditsRow(child)) {
                creditsRows += child to adapterPos
                continue
            }
            if (targetAccess.isInstrumentalRow(child)) {
                instrumentalRows += child to adapterPos
                continue
            }
            if (!isLyricsLine(child)) continue
            visibleRows += child to adapterPos
        }
        val wordActiveIds = wordHighlightState.snapshot()
        val liveWordActiveIds = wordHighlightState.liveSnapshot()
        val activeIds = highlightSession.snapshot() + wordActiveIds
        val gapAnchorPosition = BidirectionalBlurPolicy.selectInstrumentalGapAnchor(
            active = activeIds,
            isGap = highlightSession.isGap() && liveWordActiveIds.isEmpty(),
            isOpeningHighlight = highlightSession.isOpeningHighlight(),
            instrumentalPositions = instrumentalRows.map { (_, position) -> position },
            visiblePositions = visibleRows.map { (_, position) -> position },
        )
        val effectiveIds = BidirectionalBlurPolicy.resolveDisplayHighlights(
            active = activeIds,
            visiblePositions = visibleRows.map { (_, position) -> position },
            gapAnchorPosition = gapAnchorPosition,
        )
        // One bounded diagnostic observation per coalesced frame; individual
        // renderer setters remain intentionally silent.
        LyricsPlaybackDiagnostics.record("blur-frame", "active=$activeIds effective=$effectiveIds visible=${visibleRows.map { it.second }} focus=$focusEnabled immediate=$immediate", sample = true)
        probe.recordBlurFrame(
            activeIds = activeIds,
            effectiveIds = effectiveIds,
            visibleIds = visibleRows.map { (_, position) -> position },
            includeFocus = focusEnabled,
            immediate = immediate,
        )
        val useTabletEdges = TabletModeQualifier.isEligible(rv.context)
        val targets = LinkedHashMap<View, Float>(visibleRows.size + creditsRows.size)
        var lastLyricFocusBlur = if (focusEnabled) {
            BidirectionalBlurPolicy.applyRadiusOffset(
                radius = BidirectionalBlurPolicy.MAX_BLUR_RADIUS,
                offsetPx = blurRadiusOffsetPx,
            )
        } else {
            0f
        }
        visibleRows.forEach { (child, adapterPos) ->
            val focusBlur = if (focusEnabled) {
                BidirectionalBlurPolicy.applyRadiusOffset(
                    radius = BidirectionalBlurPolicy.targetRadius(adapterPos, effectiveIds),
                    offsetPx = blurRadiusOffsetPx,
                )
            } else {
                0f
            }
            lastLyricFocusBlur = focusBlur
            val edgeBlur = if (useTabletEdges && focusEnabled) {
                TabletLyricVisualPolicy.edgeBlurRadius(
                    rowCenterPx = (child.top + child.bottom) / 2f,
                    viewportHeightPx = rv.height.toFloat(),
                )
            } else {
                0f
            }
            targets[child] = TabletLyricVisualPolicy.mergeBlurRadius(
                focusBlurRadius = focusBlur,
                edgeBlurRadius = edgeBlur,
                isHighlighted = focusEnabled && adapterPos in effectiveIds,
            )
        }
        creditsRows.forEach { (child, _) ->
            val focusBlur = if (focusEnabled) lastLyricFocusBlur else 0f
            val edgeBlur = if (useTabletEdges && focusEnabled) {
                TabletLyricVisualPolicy.edgeBlurRadius(
                    rowCenterPx = (child.top + child.bottom) / 2f,
                    viewportHeightPx = rv.height.toFloat(),
                )
            } else {
                0f
            }
            targets[child] = TabletLyricVisualPolicy.mergeBlurRadius(
                focusBlurRadius = focusBlur,
                edgeBlurRadius = edgeBlur,
                isHighlighted = false,
            )
        }
        instrumentalRows.forEach { (view, _) -> blurRenderer.clear(view) }
        if (immediate || isUserScrolling) {
            blurRenderer.applyImmediately(targets)
        } else {
            blurRenderer.animateTo(targets)
        }
    }

    private fun isLyricsLine(view: View): Boolean {
        if (view !is ViewGroup) return false
        if (hasImageDescendant(view)) return false
        return true
    }

    private fun followHighlights(): Set<Int> = if (!highlightSession.isGap()) {
        highlightSession.snapshot()
    } else wordHighlightState.liveSnapshot()

    private fun recoverVisibleFollow() {
        val rv = getRv() as? ViewGroup ?: return
        val visible = (0 until rv.childCount).mapNotNull { index ->
            val child = rv.getChildAt(index)
            if (isLyricsLine(child) && child.bottom > rv.paddingTop &&
                child.top < rv.height - rv.paddingBottom) targetAccess.adapterPosition(child) else null
        }
        recoverLostFollow(rv, followHighlights(), visible)
    }

    private fun recoverLostFollow(rv: ViewGroup, activeIds: Set<Int>, visiblePositions: List<Int>) {
        // Do not scroll to old evidence when playback callbacks have stopped entirely.
        if (!rv.isShown || SystemClock.uptimeMillis() - lastHighlightAt > 15_000L) {
            offscreenSince = 0L
            return
        }
        LyricsPlaybackDiagnostics.record("follow-state", "active=$activeIds visible=$visiblePositions user=$isUserScrolling gap=${highlightSession.isGap()}", sample = true)
        val offscreen = LyricFollowRecoveryPolicy.offscreenTarget(
            activeIds, visiblePositions, highlightSession.isGap() && wordHighlightState.liveSnapshot().isEmpty(),
        )
        // A suspended native renderer can lose highlight/follow while its row is still visible.
        val suspended = !isUserScrolling && activeIds.isNotEmpty() &&
            (!highlightSession.isGap() || wordHighlightState.liveSnapshot().isNotEmpty()) &&
            targetAccess.nativeFollowSuspended(lyricsFragmentOwner, rv, currentDocument, activeIds)
        val target = offscreen ?: activeIds.minOrNull().takeIf { suspended }
        if (target == null || isUserScrolling) {
            offscreenSince = 0L
            return
        }
        val now = SystemClock.uptimeMillis()
        LyricsPlaybackDiagnostics.record("follow-check", "active=$activeIds visible=$visiblePositions target=$target touch=$lastUserTouchAt now=$now", sample = true)
        if (offscreenSince == 0L) offscreenSince = now
        if (now - offscreenSince < FOLLOW_RECOVERY_DELAY_MS ||
            (lastUserTouchAt != Long.MIN_VALUE && now - lastUserTouchAt < USER_BROWSING_GRACE_MS) ||
            now - lastFollowRecoveryAt < FOLLOW_RECOVERY_COOLDOWN_MS
        ) return
        lastFollowRecoveryAt = now
        offscreenSince = 0L
        val document = currentDocument
        val owner = lyricsFragmentOwner
        rv.post {
            if (document !== currentDocument || owner !== lyricsFragmentOwner || recyclerView !== rv || target !in followHighlights() || isUserScrolling ||
                (lastUserTouchAt != Long.MIN_VALUE &&
                    SystemClock.uptimeMillis() - lastUserTouchAt < USER_BROWSING_GRACE_MS)) return@post
            runCatching {
                if (targetAccess.recoverFollow(owner, rv, document, followHighlights())) {
                    LyricsPlaybackDiagnostics.record("follow-native", "target=$target")
                    Log.i(TAG, "Recovered native lyric presentation at row $target")
                }

            }.onFailure { error -> LyricsPlaybackDiagnostics.record("follow-error", error.javaClass.simpleName); Log.w(TAG, "Lyric follow recovery unavailable", error) }
        }
    }

    private fun hasImageDescendant(view: View): Boolean {
        if (view is ImageView) return true
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                if (hasImageDescendant(view.getChildAt(i))) return true
            }
        }
        return false
    }

}

internal interface LyricBlurRuntime {
    fun onSessionChanged(songInfo: Any)
    fun onProcessPosition(songInfo: Any, position: Long?) = onSessionChanged(songInfo)
    fun onLyricsTouch(view: View, event: MotionEvent) = Unit
    fun onPresentationRecovered(lineIds: Set<Int>) = Unit
    fun currentLineHighlights(songInfo: Any): Set<Int>? = null
    fun onHighlightsChanged(lineIds: Set<Int>)
    fun onNativeHighlightsChanged(lineIds: Set<Int>, nativePosition: Long?) {
        onHighlightsChanged(lineIds)
    }
    fun onFallbackHighlightChanged(lineId: Int)
    fun onWordHighlightsChanged(source: String, lineIds: Set<Int>) = Unit
    fun onLyricsViewCreated(owner: Any, root: View)
    fun onLyricsViewDestroyed(owner: Any)
}

internal interface LyricBlurTargetAccess {
    fun isRecyclerView(view: View): Boolean
    fun isInstrumentalRow(view: View): Boolean
    fun isCreditsRow(view: View): Boolean
    fun adapterPosition(view: View): Int
    fun nativeFollowSuspended(owner: Any?, recycler: ViewGroup, document: Any?, active: Set<Int>): Boolean = false
    fun recoverFollow(owner: Any?, recycler: ViewGroup, document: Any?, active: Set<Int>): Boolean = false
}
