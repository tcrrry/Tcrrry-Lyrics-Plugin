package com.tcrrry.lyrics.engine.hook

import android.app.Activity
import android.view.View
import android.widget.FrameLayout
import com.tcrrry.lyrics.plugin.TestPluginContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class NativeLyricsPresentationRecoveryTest {
    class ListView(activity: Activity) : FrameLayout(activity) {
        var state = 0
        var windowFocused = true
        fun getScrollState() = state
        override fun hasWindowFocus() = windowFocused
    }
    class Binding(@JvmField val f0: ListView)
    class Adapter : com.apple.android.music.player.A() {
        var browsing = false
        var unlocks = 0
        var rebinds = 0
        fun C() = browsing
        fun N() { unlocks++ }
        fun P(enabled: Boolean) { A = enabled }
        fun g() { rebinds++ }
    }
    class Callback(private val owner: Fragment) {
        var first = -1; var last = -1; var calls = 0
        fun b(first: Int, last: Int) {
            assertTrue(owner.p0.A)
            assertTrue(owner.V0)
            this.first = first; this.last = last; calls++
            owner.p0.browsing = false
            owner.V0 = false // Native callback consumes the forced-anchor request.
        }
    }
    class Browser { var playing = true; fun isPlaying() = playing }
    class Fragment(activity: Activity) {
        @JvmField val h1 = Any()
        @JvmField val n0 = Binding(ListView(activity))
        @JvmField val p0 = Adapter().apply { pointer = h1 }
        @JvmField var V0 = false
        @JvmField val Z0 = Callback(this)
        var hidden = false
        val media = Browser()
        fun getView(): View = n0.f0
        fun isHidden() = hidden
        fun getMediaBrowser() = media
    }
    @org.junit.Before fun attach() { TestPluginContext.attach() }
    @org.junit.After fun close() { TestPluginContext.current.close() }
    private fun fixture(): Triple<Activity, Fragment, NativeLyricsPresentationRecovery> {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        val owner = Fragment(activity)
        activity.setContentView(owner.getView())
        val port = requireNotNull(NativeLyricsPresentationRecovery.resolve(Fragment::class.java))
        return Triple(activity, owner, port)
    }
    @Test fun stoppedWordGateRecoversHighlightAndNativeAnchorWithoutReplacingTheDocument() {
        val (activity, owner, port) = fixture()
        owner.p0.A = false
        assertTrue(port.suspended(owner, owner.n0.f0, owner.h1, setOf(8, 9)))
        assertTrue(port.recover(owner, owner.n0.f0, owner.h1, setOf(8, 9)))
        assertEquals(8, owner.Z0.first); assertEquals(9, owner.Z0.last)
        assertTrue(owner.p0.A); assertEquals(1, owner.p0.rebinds)
        assertSame(owner.h1, owner.p0.pointer)
        assertFalse(port.suspended(owner, owner.n0.f0, owner.h1, setOf(8, 9)))
        activity.finish()
    }
    @Test fun aHealthyOffscreenReturnUsesNativeScrollWithoutRebindingWordViews() {
        val (activity, owner, port) = fixture()
        assertFalse(port.suspended(owner, owner.n0.f0, owner.h1, setOf(9)))
        assertTrue(port.recover(owner, owner.n0.f0, owner.h1, setOf(9)))
        assertEquals(1, owner.Z0.calls); assertEquals(0, owner.p0.rebinds)
        activity.finish()
    }
    @Test fun pausedDraggingSettlingHiddenStaleAndClosedPagesAreUntouched() {
        val (activity, owner, port) = fixture()
        val rv = owner.n0.f0
        owner.p0.A = false
        owner.media.playing = false
        assertFalse(port.recover(owner, rv, owner.h1, setOf(9)))
        owner.media.playing = true
        for (state in listOf(1, 2)) { rv.state = state; assertFalse(port.recover(owner, rv, owner.h1, setOf(9))) }
        rv.state = 0
        rv.windowFocused = false; assertFalse(port.recover(owner, rv, owner.h1, setOf(9))); rv.windowFocused = true
        owner.hidden = true; assertFalse(port.recover(owner, rv, owner.h1, setOf(9))); owner.hidden = false
        assertFalse(port.recover(owner, rv, Any(), setOf(9)))
        owner.p0.pointer = Any(); assertFalse(port.recover(owner, rv, owner.h1, setOf(9))); owner.p0.pointer = owner.h1
        assertFalse(port.recover(owner, rv, owner.h1, emptySet()))
        TestPluginContext.current.close()
        assertFalse(port.recover(owner, rv, owner.h1, setOf(9)))
        assertEquals(0, owner.Z0.calls); assertEquals(0, owner.p0.rebinds)
        activity.finish()
    }
}
