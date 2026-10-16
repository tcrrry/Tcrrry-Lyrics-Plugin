package com.tcrrry.lyrics.engine.hook

import android.view.View
import android.widget.ImageView
import com.tcrrry.lyrics.plugin.TestPluginContext
import dev.amenhancer.plugin.api.PluginCall
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class NativeLyricsSourceAnchorTest {
    class Binding(@JvmField val o0: ImageView)
    class Page(val button: ImageView) { fun F2(pointer: Any?) { button.visibility = View.GONE } }
    @org.junit.Before fun attach() { TestPluginContext.attach() }
    @org.junit.After fun close() { NativeLyricsSourceMenu.cleanupPluginRows(); TestPluginContext.current.close() }

    @Test fun coldChineseSongRegistersAtBindingCreationBeforeNativeAvailabilityChecks() {
        NativeLyricsSourceMenu.installBindingAnchorHooks(Binding::class.java)
        val constructor = Binding::class.java.getDeclaredConstructor(ImageView::class.java)
        val hook = requireNotNull(TestPluginContext.current.callbacks[constructor])
        val button = ImageView(RuntimeEnvironment.getApplication()).apply { visibility = View.GONE }
        val binding = Binding(button)
        val call = PluginCall(constructor, binding, arrayOf(button), null, null)
        hook.before(call); hook.after(call)
        assertEquals(View.VISIBLE, button.visibility)
        // F2 receives no translations/pronunciation on a Chinese song.
        assertEquals(View.VISIBLE, NativeLyricsSourceMenu.requestedVisibility(button, View.GONE))
        assertTrue(binding.o0 === button)
    }

    @Test fun nativeImmersiveVisibilityStillOwnsTheRegisteredButton() {
        val button = ImageView(RuntimeEnvironment.getApplication()).apply { visibility = View.GONE }
        NativeLyricsSourceMenu.registerAnchor(button)
        assertEquals(View.INVISIBLE, NativeLyricsSourceMenu.requestedVisibility(button, View.INVISIBLE))
        button.visibility = View.INVISIBLE
        NativeLyricsSourceMenu.registerAnchor(button)
        assertEquals(View.INVISIBLE, button.visibility)
        assertEquals(View.VISIBLE, NativeLyricsSourceMenu.requestedVisibility(button, View.VISIBLE))
    }

    @Test fun unrelatedControlsAndDestroyedAnchorsKeepNativeVisibility() {
        val button = ImageView(RuntimeEnvironment.getApplication())
        val other = ImageView(RuntimeEnvironment.getApplication())
        NativeLyricsSourceMenu.registerAnchor(button)
        assertEquals(View.GONE, NativeLyricsSourceMenu.requestedVisibility(other, View.GONE))
        NativeLyricsSourceMenu.unregisterAnchor(button)
        assertEquals(View.GONE, NativeLyricsSourceMenu.requestedVisibility(button, View.GONE))
    }

    @Test fun stoppingPluginRestoresOriginalGoneButtonAndReleasesOwnership() {
        val button = ImageView(RuntimeEnvironment.getApplication()).apply { visibility = View.GONE }
        NativeLyricsSourceMenu.registerAnchor(button)
        NativeLyricsSourceMenu.cleanupPluginRows()
        assertEquals(View.GONE, button.visibility)
        assertEquals(View.GONE, NativeLyricsSourceMenu.requestedVisibility(button, View.GONE))
    }

    private fun refresh(page: Page, pointer: Any? = null, nativeWrite: () -> Unit = { page.F2(pointer) }) {
        val method = Page::class.java.getDeclaredMethod("F2", Any::class.java)
        val hook = requireNotNull(TestPluginContext.current.callbacks[method])
        val call = PluginCall(method, page, arrayOf(pointer), null, null)
        hook.before(call)
        nativeWrite()
        hook.after(call)
    }

    @Test fun chineseAvailabilityRefreshCannotHideAnAlreadyRegisteredAnchor() {
        val page = Page(ImageView(RuntimeEnvironment.getApplication()).apply { visibility = View.GONE })
        NativeLyricsSourceMenu.registerAnchor(page.button)
        NativeLyricsSourceMenu.installAvailabilityAnchorHooks(Page::class.java, { (it as Page).button })
        // Simulate the native write without manually invoking the framework visibility hook.
        val subscription = CurrentLyricsSourceStatus.installIdentityPageHandler { fail("F2 must not republish/reapply lyrics") }
        try {
            repeat(3) {
                refresh(page, Any())
                assertEquals(View.VISIBLE, page.button.visibility)
            }
        } finally { subscription.close() }
    }

    @Test fun nullDocumentDuringColdLoadingAlsoKeepsSourceEntryAvailable() {
        val page = Page(ImageView(RuntimeEnvironment.getApplication()).apply { visibility = View.GONE })
        NativeLyricsSourceMenu.installAvailabilityAnchorHooks(Page::class.java, { (it as Page).button })
        refresh(page)
        assertEquals(View.VISIBLE, page.button.visibility)
    }

    @Test fun availabilityRefreshDoesNotRevealImmersiveControls() {
        val page = Page(ImageView(RuntimeEnvironment.getApplication()).apply { visibility = View.INVISIBLE })
        NativeLyricsSourceMenu.installAvailabilityAnchorHooks(Page::class.java, { (it as Page).button })
        refresh(page)
        assertEquals(View.INVISIBLE, page.button.visibility)
        page.button.visibility = View.VISIBLE
        refresh(page)
        assertEquals(View.VISIBLE, page.button.visibility)
    }

    @Test fun directBindingDoesNotRequireAResourceIdentifierAndOnlyRepairsItsOwnButton() {
        val page = Page(ImageView(RuntimeEnvironment.getApplication()).apply { id = View.NO_ID; visibility = View.GONE })
        val unrelated = ImageView(RuntimeEnvironment.getApplication()).apply { visibility = View.GONE }
        var rebound: View? = null
        NativeLyricsSourceMenu.installAvailabilityAnchorHooks(Page::class.java, { (it as Page).button }) { view, owner ->
            assertSame(page, owner)
            rebound = view
        }
        refresh(page)
        assertSame(page.button, rebound)
        assertEquals(View.VISIBLE, page.button.visibility)
        assertEquals(View.GONE, unrelated.visibility)
    }

    @Test fun stoppingDuringNativeRefreshPreventsLateVisibilityRepair() {
        val page = Page(ImageView(RuntimeEnvironment.getApplication()).apply { visibility = View.GONE })
        NativeLyricsSourceMenu.installAvailabilityAnchorHooks(Page::class.java, { (it as Page).button })
        refresh(page) {
            page.F2(null)
            TestPluginContext.current.close()
        }
        assertEquals(View.GONE, page.button.visibility)
    }
}
