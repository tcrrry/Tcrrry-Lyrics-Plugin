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
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE, sdk=[35])
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LyricsContentTransitionTest {
    private fun view(): View {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        return View(activity).also { activity.setContentView(it); it.layout(0,0,500,1000) }
    }
    @Test fun replacementIsSynchronousAndFadeRestoresAlphaWithoutMovingThePage() {
        val view=view().apply { alpha=.8f; translationY=9f }
        val transition=LyricsContentTransition(); var replacements=0
        transition.run(view,{true}) { replacements++ }
        assertEquals(1,replacements); assertEquals(0f,view.alpha,.001f)
        view.viewTreeObserver.dispatchOnPreDraw()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
        assertEquals(.8f,view.alpha,.001f); assertEquals(9f,view.translationY,.001f)
        assertEquals(1,replacements)
    }
    @Test fun cancellationSongChangeAndMissingPredrawNeverLeaveLyricsInvisible() {
        val view=view(); val transition=LyricsContentTransition(); var valid=true
        transition.run(view,{valid}) {}
        valid=false; view.viewTreeObserver.dispatchOnPreDraw()
        assertEquals(1f,view.alpha,.001f)
        valid=true; transition.run(view,{valid}) {}
        transition.close(); assertEquals(1f,view.alpha,.001f)
        transition.run(view,{true}) {}
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800))
        assertEquals(1f,view.alpha,.001f)
    }
}
