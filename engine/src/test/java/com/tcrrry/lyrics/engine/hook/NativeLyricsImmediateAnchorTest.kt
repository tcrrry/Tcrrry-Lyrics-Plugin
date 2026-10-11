package com.tcrrry.lyrics.engine.hook

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import com.tcrrry.lyrics.plugin.TestPluginContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.TreeSet

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class NativeLyricsImmediateAnchorTest {
    class ListView(activity: Activity): FrameLayout(activity) { fun getScrollState()=0 }
    class Binding(@JvmField val f0: ListView)
    class Pointer { fun get()=Document() }
    class Document { fun getAdamId()=123L }
    class Adapter {
        fun w()=TreeSet<Int>().apply { add(65) }
        fun N()=Unit;fun P(enabled: Boolean)=Unit;fun g()=Unit
    }
    class Callback { var first=-1;fun b(start: Int,end: Int) { first=start } }
    class Processor {
        var repairs=0
        fun c(pointer: Pointer,position: Long,a: Runnable,b: Runnable,c: Runnable,d: Runnable,e: Runnable): Long { repairs++;return 100L }
        fun d(pointer: Pointer,a: Runnable,b: Runnable,c: Runnable,d: Runnable,e: Runnable)=Unit
    }
    class Browser { var position=210000L;var playing=false;fun getCurrentPosition()=position;fun isPlaying()=playing;fun getPlaybackState()=0 }
    class Fragment(activity: Activity) {
        @JvmField val n0=Binding(ListView(activity))
        @JvmField val h1=Pointer()
        @JvmField val p0=Adapter()
        @JvmField var V0=true
        @JvmField val Z0=Callback()
        @JvmField val b1=Processor()
        @JvmField val c1=Runnable {};@JvmField val d1=Runnable {};@JvmField val e1=Runnable {}
        @JvmField val f1=Runnable {};@JvmField val g1=Runnable {}
        @JvmField val X=Handler(Looper.getMainLooper())
        val browser=Browser()
        fun getView(): View=n0.f0
        fun isHidden()=false
        fun getMediaBrowser()=browser
        fun y2(state: Int)=Unit
    }
    @org.junit.Before fun attach() { TestPluginContext.attach() }
    @org.junit.After fun close() { TestPluginContext.current.close() }
    @Test fun missingClockCallbacksRecoverOnlyWhileOwnedVisibleAndPlaying() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        val fragment=Fragment(activity)
        activity.setContentView(fragment.getView())
        val anchor=NativeLyricsImmediateAnchor::class.java.getDeclaredConstructor(Class::class.java)
            .apply { isAccessible=true }.newInstance(Fragment::class.java)
        anchor.currentSongId={123L};anchor.isCurrentSong={true};anchor.ownsReplacement={_,_->true}
        anchor.watch(fragment)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(0,fragment.b1.repairs) // Paused playback must not start a repair loop.
        fragment.browser.playing=true
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertEquals(1,fragment.b1.repairs)
        anchor.ownsReplacement={_,_->false}
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertEquals(1,fragment.b1.repairs)
        TestPluginContext.current.close()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(1,fragment.b1.repairs)
    }
    @Test fun mediaClockCorrectionOnlyTouchesTheCurrentOwnedReplacement() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        val fragment=Fragment(activity)
        activity.setContentView(fragment.getView())
        val anchor=NativeLyricsImmediateAnchor::class.java.getDeclaredConstructor(Class::class.java)
            .apply { isAccessible=true }.newInstance(Fragment::class.java)
        anchor.currentSongId={123L}
        anchor.isCurrentSong={true}
        anchor.watch(fragment)
        assertEquals(1_000L,anchor.correctedPosition(fragment.h1,1_000))
        anchor.ownsReplacement={_,_->true}
        assertEquals(210_000L,anchor.correctedPosition(fragment.h1,1_000))
        assertEquals(209_000L,anchor.correctedPosition(fragment.h1,209_000))
        assertEquals(1_000L,anchor.correctedPosition(Pointer(),1_000))
        anchor.isCurrentSong={false}
        assertEquals(1_000L,anchor.correctedPosition(fragment.h1,1_000))
    }
    @Test fun watcherStartsOnAttachRestartsAfterReattachAndStopsWithPlugin() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        val fragment=Fragment(activity)
        val anchor=NativeLyricsImmediateAnchor::class.java.getDeclaredConstructor(Class::class.java)
            .apply { isAccessible=true }.newInstance(Fragment::class.java)
        anchor.currentSongId={123L}
        anchor.watch(fragment) // getView exists but is not attached yet.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        activity.setContentView(fragment.getView())
        fragment.browser.position=0
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        assertEquals(1,fragment.b1.repairs)
        // Drawing completes the native callback, overriding its stale tail ID.
        fragment.getView().viewTreeObserver.dispatchOnPreDraw()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0,fragment.Z0.first)
        activity.setContentView(FrameLayout(activity))
        fragment.browser.position=210000
        activity.setContentView(fragment.getView())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        fragment.browser.position=0
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        assertEquals(3,fragment.b1.repairs)
        val before=fragment.b1.repairs
        TestPluginContext.current.close()
        fragment.browser.position=210000
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(before,fragment.b1.repairs)
    }
}
