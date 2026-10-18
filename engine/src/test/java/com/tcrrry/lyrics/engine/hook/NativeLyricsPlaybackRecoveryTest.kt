package com.tcrrry.lyrics.engine.hook

import android.app.Activity
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.util.Pair
import android.util.SparseArray
import androidx.recyclerview.widget.`RecyclerView$k$a` as NativeFinishedListener
import com.tcrrry.lyrics.plugin.PluginLifetime
import com.tcrrry.lyrics.plugin.TestPluginContext
import dev.amenhancer.plugin.api.PluginCall
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

private fun nativePlaybackCall(receiver: Any, name: String, parameters: Array<Class<*>>, args: Array<Any?>,
    body: () -> Any?): Any? {
    val method = receiver.javaClass.getMethod(name, *parameters)
    val hook = TestPluginContext.current.callbacks[method]
    val before = PluginCall(method, receiver, args, null, null)
    hook?.before(before)
    val result = if (before.isOutcomeChanged) before.result else body()
    val after = PluginCall(method, receiver, args, result, before.throwable)
    hook?.after(after)
    return after.result
}

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class NativeLyricsPlaybackRecoveryTest {
    class Ptr<T>(private val value: T) { fun get() = value }
    class Vector<T>(private val values: List<T>) {
        fun size() = values.size.toLong()
        fun get(index: Long) = Ptr(values[index.toInt()])
    }
    class Line(private val id: Int, private val begin: Long, private val end: Long) {
        fun getLineId() = id; fun getBegin() = begin; fun getEnd() = end
        fun getHtmlLineText() = "line$id"
        fun getWords() = Vector(listOf(Word(0, begin, begin + 500), Word(1, begin + 500, begin + 1000),
            Word(2, begin + 1000, begin + 2000), Word(3, begin + 2000, end)))
        fun getBackgroundWords() = Vector(emptyList<Word>())
    }
    class Word(private val id: Int, private val begin: Long, private val end: Long) {
        fun getWordId() = id; fun getBegin() = begin; fun getEnd() = end
        fun isWhitespace() = false
    }
    class Section { fun getLines() = Vector(listOf(Line(0, 1000, 5000), Line(1, 30000, 35000), Line(2, 90000, 95000))) }
    class Song(val id: Long = 123) { fun getAdamId() = id; fun getSections() = Vector(listOf(Section())) }
    class ListView(activity: Activity) : FrameLayout(activity) {
        var state = 0; var focused = true
        fun getScrollState() = state
        override fun hasWindowFocus() = focused
    }
    class Binding(@JvmField val f0: ListView)
    class Adapter(private val callback: (Int, Int) -> Unit) : com.apple.android.music.player.A() {
        enum class a { WORD_HIGHLIGHT_IDS, BG_WORD_HIGHLIGHT_IDS, PLAYBACK_POSITION }
        var unlocks = 0; var enabled = false
        var completed = emptyList<Int>()
        var lastIds = emptyList<Int>()
        var entries = 0
        var onExit: (() -> Unit)? = null
        fun N() { unlocks++ }; fun P(value: Boolean) { enabled = value }
        fun O(ids: List<Int>, delay: Int, payloads: Array<Pair<*, *>>) {
            nativePlaybackCall(this, "O", arrayOf(List::class.java, Int::class.javaPrimitiveType!!,
                java.lang.reflect.Array.newInstance(Pair::class.java, 0).javaClass), arrayOf(ids, delay, payloads)) {
            // Native O ignores duplicate sets. Explicit seek must re-enter, including
            // when the target is another wrapped row of the same underlying line.
            if (ids.isNotEmpty() && ids == lastIds) return@nativePlaybackCall null
            lastIds = ids.toList()
            if (ids.isEmpty()) onExit?.invoke()
            if (ids.isNotEmpty()) {
                val words = payloads.first { it.first == a.WORD_HIGHLIGHT_IDS }.second as SparseArray<*>
                completed = (words.get(ids.first()) as Collection<Int>).toList()
                entries++
                callback(ids.first(), ids.last())
            }
            null
            }
        }
    }
    class Callback { var first = -1; var calls = 0; fun b(first: Int, last: Int) { this.first = first; calls++ } }
    class NativeAnimator {
        var running = false
        private val listeners = mutableListOf<NativeFinishedListener>()
        fun h(listener: NativeFinishedListener): Boolean {
            if (running) listeners += listener else listener.a()
            return running
        }
        fun finish() { running = false; listeners.toList().also { listeners.clear() }.forEach { it.a() } }
        fun f() = finish()
    }
    class Processor {
        val positions = mutableListOf<Long>()
        fun c(pointer: Ptr<Song>, position: Long, a: Runnable, b: Runnable, c: Runnable, d: Runnable, e: Runnable): Long {
            return (nativePlaybackCall(this, "c", arrayOf(Ptr::class.java, Long::class.javaPrimitiveType!!,
                Runnable::class.java, Runnable::class.java, Runnable::class.java, Runnable::class.java, Runnable::class.java),
                arrayOf(pointer, position, a, b, c, d, e)) { positions += position; 100L } as Number).toLong()
        }
    }
    class Browser {
        var position = 210000L; var playing = true
        fun getCurrentPosition() = position; fun isPlaying() = playing; fun getPlaybackState() = 3
        fun seekTo(target: Long) {
            nativePlaybackCall(this, "seekTo", arrayOf(Long::class.javaPrimitiveType!!), arrayOf(target)) {
                position = target; null
            }
        }
    }
    class Fragment(activity: Activity) {
        @JvmField var h1 = Ptr(Song())
        @JvmField val n0 = Binding(ListView(activity))
        @JvmField val p0 = Adapter { first, last -> Z0.b(first, last) }
        @JvmField var V0 = false
        @JvmField val Z0 = Callback()
        @JvmField val t0 = NativeAnimator()
        @JvmField val b1 = Processor()
        @JvmField val c1 = Runnable {}; @JvmField val d1 = Runnable {}; @JvmField val e1 = Runnable {}
        @JvmField val f1 = Runnable {}; @JvmField val g1 = Runnable {}
        val browser = Browser()
        var loops = 0; var hidden = false
        fun getView(): View = n0.f0; fun isHidden() = hidden
        fun getMediaBrowser() = browser; fun y2(state: Int) { loops++ }
    }
    @org.junit.Before fun attach() { TestPluginContext.attach() }
    @org.junit.After fun close() { TestPluginContext.current.close() }
    private fun fixture(position: Long = 210000): Triple<Activity, Fragment, NativeLyricsPlaybackRecovery> {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        val owner = Fragment(activity)
        owner.browser.position = position
        activity.setContentView(owner.getView())
        val recovery = requireNotNull(NativeLyricsPlaybackRecovery.resolve(Fragment::class.java) { 123L })
        PluginLifetime.close(recovery::close)
        recovery.watch(owner)
        return Triple(activity, owner, recovery)
    }
    private fun tick() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
    private fun draw(owner: Fragment) {
        owner.getView().viewTreeObserver.dispatchOnPreDraw()
        shadowOf(Looper.getMainLooper()).idle()
        owner.getView().viewTreeObserver.dispatchOnPreDraw()
        shadowOf(Looper.getMainLooper()).idle()
    }
    @Test fun repeatRestartsAfterOutroEvenWithNoFurtherHighlightCallbacks() {
        val (activity, owner, recovery) = fixture()
        tick(); assertTrue(owner.b1.positions.isEmpty())
        owner.browser.position = 0
        tick(); draw(owner)
        assertEquals(listOf(0L), owner.b1.positions)
        assertEquals(0, owner.Z0.first); assertTrue(owner.p0.enabled)
        assertEquals(1, owner.loops)
        owner.browser.position = 500; tick(); draw(owner)
        assertEquals(1, owner.b1.positions.size)
        recovery.close(); activity.finish()
    }
    @Test fun seekingFromAGapCanFollowAnActiveLineOrTheNextLineWithoutExistingHighlights() {
        val (activity, owner, recovery) = fixture(10000)
        owner.browser.position = 50000; tick(); draw(owner)
        assertEquals(2, owner.Z0.first) // Gap at 50s: next vocal starts at 90s.
        owner.browser.position = 32000; tick(); draw(owner)
        assertEquals(1, owner.Z0.first)
        assertEquals(listOf(50000L, 32000L), owner.b1.positions)
        recovery.close(); activity.finish()
    }
    @Test fun pausedSeekingUpdatesLyricsWithoutStartingPlaybackAndRecreatedPointersStillRepeat() {
        val (activity, owner, recovery) = fixture()
        owner.browser.playing = false
        owner.browser.position = 50000; tick(); draw(owner)
        assertEquals(2, owner.Z0.first); assertEquals(0, owner.loops)
        owner.browser.playing = true
        owner.h1 = Ptr(Song())
        owner.browser.position = 0; recovery.watch(owner); tick(); draw(owner)
        assertEquals(0, owner.Z0.first)
        assertEquals(listOf(50000L, 0L), owner.b1.positions)
        recovery.close(); activity.finish()
    }
    @Test fun draggingDefersRecoveryAndClosingRejectsQueuedLayoutWork() {
        val (activity, owner, recovery) = fixture()
        owner.n0.f0.state = 1
        owner.browser.position = 0; tick()
        assertTrue(owner.b1.positions.isEmpty())
        owner.n0.f0.state = 0; owner.browser.position = 500; tick()
        assertTrue(owner.b1.positions.isEmpty())
        recovery.close(); draw(owner)
        assertEquals(0, owner.Z0.calls)
        owner.browser.position = 70000; tick()
        assertEquals(0, owner.b1.positions.size)
        activity.finish()
    }
    @Test fun anotherSongsDocumentCannotBeRepairedUsingTheOldSongClock() {
        val (activity, owner, recovery) = fixture()
        owner.h1 = Ptr(Song(999)); owner.browser.position = 0; tick(); draw(owner)
        assertTrue(owner.b1.positions.isEmpty()); assertEquals(0, owner.Z0.calls)
        recovery.close(); activity.finish()
    }
    @Test fun aSmallPausedForwardSeekIsNotMistakenForNormalPlayback() {
        val (activity, owner, recovery) = fixture(10000)
        owner.browser.playing = false
        owner.browser.position = 10500; tick(); draw(owner)
        assertEquals(listOf(10500L), owner.b1.positions)
        assertEquals(1, owner.Z0.first); assertEquals(0, owner.loops)
        recovery.close(); activity.finish()
    }

    @Test fun seekingIntoASecondWrappedRowRestoresTheFirstRowsCompletedWordsThroughNativeUpdates() {
        val (activity, owner, recovery) = fixture(10000)
        owner.browser.position = 33000; tick(); draw(owner)
        assertEquals(1, owner.Z0.first)
        assertEquals(listOf(0, 1, 2), owner.p0.completed)
        val calls = owner.Z0.calls
        draw(owner)
        assertEquals("No second forced anchor may interrupt the native transition", calls, owner.Z0.calls)
        // A second seek within this same line must bypass native duplicate-line filtering.
        owner.browser.playing = false
        owner.browser.position = 30750; tick(); draw(owner)
        assertEquals(listOf(0), owner.p0.completed)
        assertEquals(2, owner.p0.entries)
        assertFalse(owner.browser.playing)
        recovery.close(); activity.finish()
    }

    @Test fun gapLocationWaitsForNativeAnimationCompletionInsteadOfUsingAPluginTimer() {
        val (activity, owner, recovery) = fixture(10000)
        owner.p0.onExit = { owner.t0.running = true }
        owner.browser.playing = false
        owner.browser.position = 50000; tick(); draw(owner)
        assertEquals(0, owner.Z0.calls)
        owner.t0.finish()
        draw(owner)
        assertEquals(2, owner.Z0.first)
        assertFalse(owner.browser.playing)
        recovery.close(); activity.finish()
    }

    @Test fun anotherSeekInvalidatesTheOldNativeAnimationCompletion() {
        val (activity, owner, recovery) = fixture(10000)
        owner.p0.onExit = { owner.t0.running = true }
        owner.browser.position = 50000; tick(); draw(owner)
        owner.browser.position = 33000; tick(); draw(owner)
        owner.t0.finish(); draw(owner)
        assertEquals(1, owner.Z0.first)
        val calls = owner.Z0.calls
        owner.t0.finish()
        assertEquals(1, owner.Z0.first)
        assertEquals(calls, owner.Z0.calls)
        recovery.close(); activity.finish()
    }

    @Test fun outgoingAndIncomingUpdatesCannotBeCoalescedIntoOneLayout() {
        val (activity, owner, recovery) = fixture(10000)
        owner.p0.lastIds = listOf(0)
        owner.browser.position = 33000; tick()
        assertTrue(owner.p0.lastIds.isEmpty())
        assertEquals(0, owner.p0.entries)
        assertTrue(owner.b1.positions.isEmpty())
        owner.getView().viewTreeObserver.dispatchOnPreDraw()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(1), owner.p0.lastIds)
        assertTrue("Word events wait for the incoming holder layout", owner.b1.positions.isEmpty())
        draw(owner)
        assertEquals(listOf(33000L), owner.b1.positions)
        recovery.close(); activity.finish()
    }

    @Test fun aSmallPlayingSeekUsesTheNativeSeekEventBeforeThePollingThreshold() {
        val (activity, owner, recovery) = fixture(31000)
        owner.browser.seekTo(31500)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(60))
        draw(owner)
        assertEquals(listOf(31500L), owner.b1.positions)
        assertEquals(listOf(0, 1), owner.p0.completed)
        assertTrue(owner.browser.playing)
        recovery.close(); activity.finish()
    }

    @Test fun nativeTimersAndLateLineCallbacksCannotPaintDuringAnOutgoingAnimation() {
        val (activity, owner, recovery) = fixture(10000)
        owner.p0.onExit = { owner.t0.running = true }
        owner.browser.seekTo(33000)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(60)); draw(owner)
        val noop = Runnable {}
        owner.b1.c(owner.h1, 33000, noop, noop, noop, noop, noop)
        owner.p0.O(listOf(0), -1, emptyArray())
        assertTrue(owner.b1.positions.isEmpty())
        assertTrue(owner.p0.lastIds.isEmpty())
        owner.t0.finish(); draw(owner)
        assertEquals(listOf(1), owner.p0.lastIds)
        assertEquals(listOf(33000L), owner.b1.positions)
        recovery.close(); activity.finish()
    }

    @Test fun rapidSeeksEnterOnlyTheLatestTargetAndRejectAnOlderPostedNativeCallback() {
        val (activity, owner, recovery) = fixture(10000)
        owner.p0.onExit = { owner.t0.running = true }
        owner.browser.seekTo(33000)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(60)); draw(owner)
        owner.browser.seekTo(92000)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(60)); draw(owner)
        assertEquals(0, owner.p0.entries)
        owner.t0.finish(); draw(owner)
        assertEquals(listOf(2), owner.p0.lastIds)
        assertEquals(1, owner.p0.entries)
        assertEquals(listOf(92000L), owner.b1.positions)
        owner.p0.O(listOf(1), -1, arrayOf(Pair(Adapter.a.PLAYBACK_POSITION, 33000L)))
        assertEquals(listOf(2), owner.p0.lastIds)
        assertEquals(1, owner.p0.entries)
        recovery.close(); activity.finish()
    }

    @Test fun losingWindowFocusDuringASeekReleasesNativeProcessingAndRetriesWhenVisible() {
        val (activity, owner, recovery) = fixture(10000)
        owner.browser.position = 33000; tick()
        owner.n0.f0.focused = false; draw(owner); tick()
        val noop = Runnable {}
        owner.b1.c(owner.h1, 33000, noop, noop, noop, noop, noop)
        assertEquals("An invalidated layout must not leave native processing blocked", listOf(33000L), owner.b1.positions)
        owner.n0.f0.focused = true; tick(); draw(owner)
        assertEquals(1, owner.Z0.first)
        recovery.close(); activity.finish()
    }

    @Test fun anotherBrowserCannotTriggerASeekAndClosingRemovesTheNewHooks() {
        val (activity, owner, recovery) = fixture(10000)
        Browser().seekTo(33000)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(60)); draw(owner)
        assertTrue(owner.b1.positions.isEmpty())
        assertTrue(TestPluginContext.current.callbacks.isNotEmpty())
        recovery.close()
        assertTrue(TestPluginContext.current.callbacks.isEmpty())
        owner.browser.seekTo(33000); draw(owner)
        assertTrue(owner.b1.positions.isEmpty())
        activity.finish()
    }

    @Test fun aSmallPlayingClockCorrectionDoesNotRestartAnimationsButAnExplicitSeekDoes() {
        val (activity, owner, recovery) = fixture(33000)
        owner.browser.position = 32750; tick(); draw(owner)
        assertTrue(owner.b1.positions.isEmpty())
        assertEquals(0, owner.p0.entries)
        owner.browser.seekTo(32500)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(60)); draw(owner)
        assertEquals(listOf(32500L), owner.b1.positions)
        assertEquals(1, owner.p0.entries)
        recovery.close(); activity.finish()
    }

    @Test fun replacingTheSourceDuringAnAnimationDoesNotBlockTheNewDocumentsProcessor() {
        val (activity, owner, recovery) = fixture(10000)
        owner.p0.onExit = { owner.t0.running = true }
        owner.browser.position = 33000; tick(); draw(owner)
        owner.h1 = Ptr(Song())
        val noop = Runnable {}
        owner.b1.c(owner.h1, 33000, noop, noop, noop, noop, noop)
        assertEquals(listOf(33000L), owner.b1.positions)
        tick(); draw(owner)
        owner.t0.finish(); draw(owner)
        assertEquals(listOf(1), owner.p0.lastIds)
        assertEquals(1, owner.p0.entries)
        recovery.close(); activity.finish()
    }
}
