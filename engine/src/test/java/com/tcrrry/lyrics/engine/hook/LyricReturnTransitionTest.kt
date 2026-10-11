package com.tcrrry.lyrics.engine.hook

import android.app.Activity
import android.os.Looper
import android.view.View
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE, sdk=[35])
@LooperMode(LooperMode.Mode.PAUSED)
class LyricReturnTransitionTest {
    private fun list(): View {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        return View(activity).also { activity.setContentView(it); it.layout(0,0,500,1000) }
    }
    @Test fun returnPositionsOnceAndFinishesWithinOneShortAnimation() {
        val list=list().apply { translationY=7f }; val transition=LyricReturnTransition(); var jumps=0
        assertTrue(transition.run(list,{true}) { jumps++ })
        assertEquals(1,jumps)
        assertTrue(list.translationY<7f)
        assertTrue(7f-list.translationY<=120f*list.resources.displayMetrics.density)
        list.viewTreeObserver.dispatchOnPreDraw()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
        assertEquals(7f,list.translationY,.001f)
        assertEquals(1,jumps)
    }
    @Test fun dragCancellationAndSongReplacementRestoreTranslation() {
        val list=list().apply { translationY=13f }; val transition=LyricReturnTransition(); var valid=true
        assertTrue(transition.run(list,{valid}) {})
        valid=false
        list.viewTreeObserver.dispatchOnPreDraw()
        assertEquals(13f,list.translationY,.001f)
        assertTrue(transition.run(list,{true}) {})
        list.viewTreeObserver.dispatchOnPreDraw()
        transition.cancel(list)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
        assertEquals(13f,list.translationY,.001f)
        transition.close()
    }
}
