package com.tcrrry.lyrics.engine.hook

/** Keeps highlight continuity inside one native lyric document, never across songs. */
internal class LyricHighlightSession {
    private var token: Any? = null
    private val highlightedLineIds = mutableSetOf<Int>()
    private val completedOverlapLineIds = mutableSetOf<Int>()
    private var gap = false
    private var openingHighlight = false

    // Explicit "first non-empty highlight since enter" state; the highlighted set alone
    // would conflate "never highlighted" with "set empty at this moment".
    private var hasReceivedNonEmptyHighlight = false

    @Synchronized
    fun enter(newToken: Any): Boolean {
        if (token === newToken) return false
        token = newToken
        highlightedLineIds.clear()
        completedOverlapLineIds.clear()
        gap = false
        openingHighlight = false
        hasReceivedNonEmptyHighlight = false
        return true
    }

    @Synchronized
    fun resetForReplay() {
        highlightedLineIds.clear()
        completedOverlapLineIds.clear()
        gap = false
        openingHighlight = false
        hasReceivedNonEmptyHighlight = false
    }

    @Synchronized
    fun update(incoming: Set<Int>): Set<Int> {
        if (incoming.isEmpty()) {
            gap = true
            return snapshotLocked()
        }
        gap = false
        if (incoming == highlightedLineIds) return snapshotLocked()
        if (!hasReceivedNonEmptyHighlight) {
            hasReceivedNonEmptyHighlight = true
            openingHighlight = true
        } else {
            openingHighlight = false
        }
        val completedOverlap = if (
            highlightedLineIds.size > 1 && highlightedLineIds.containsAll(incoming)
        ) {
            val firstIncoming = incoming.min()
            (highlightedLineIds - incoming).filterTo(mutableSetOf()) { lineId ->
                lineId < firstIncoming
            }
        } else {
            emptySet()
        }
        completedOverlapLineIds.clear()
        completedOverlapLineIds.addAll(completedOverlap)
        highlightedLineIds.clear()
        highlightedLineIds.addAll(incoming)
        return snapshotLocked()
    }

    @Synchronized
    fun replace(lineId: Int) {
        gap = false
        val isRepeat = highlightedLineIds.size == 1 &&
            completedOverlapLineIds.isEmpty() &&
            highlightedLineIds.contains(lineId)
        if (!isRepeat) {
            if (!hasReceivedNonEmptyHighlight) {
                hasReceivedNonEmptyHighlight = true
                openingHighlight = true
            } else {
                openingHighlight = false
            }
        }
        completedOverlapLineIds.clear()
        highlightedLineIds.clear()
        highlightedLineIds.add(lineId)
    }

    @Synchronized
    fun current(document: Any): Set<Int>? = if (token !== document) null
        else if (gap) emptySet() else highlightedLineIds.toSet()

    @Synchronized
    fun snapshot(): Set<Int> = snapshotLocked()

    @Synchronized
    fun isGap(): Boolean = gap

    @Synchronized
    fun isOpeningHighlight(): Boolean = openingHighlight

    private fun snapshotLocked(): Set<Int> = highlightedLineIds + completedOverlapLineIds
}
