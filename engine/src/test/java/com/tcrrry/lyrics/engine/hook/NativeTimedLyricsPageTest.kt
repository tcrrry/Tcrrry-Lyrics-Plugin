package com.tcrrry.lyrics.engine.hook

import android.app.Activity
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class NativeTimedLyricsPageTest {
    class Document(val id: Long, val timing: String) {
        fun getAdamId() = id
        fun getAvailableTiming() = timing
    }
    class Pointer(val document: Document) { fun get() = document }
    class Adapter {
        var document: Pointer? = null
        fun y() = document
    }
    class ListView(activity: Activity): FrameLayout(activity) {
        var currentAdapter: Any? = Any()
        fun getAdapter() = currentAdapter
    }
    class Binding(@JvmField val f0: ListView) {
        @JvmField val e0 = FrameLayout(f0.context).apply { visibility = View.GONE }
    }
    class Observable(@JvmField var a: Any?) { var notifications = 0; fun notifyChange() { notifications++ } }
    class Page(activity: Activity, id: Long, timing: String) {
        @JvmField val n0 = Binding(ListView(activity))
        @JvmField var h1 = Pointer(Document(id, timing))
        @JvmField val p0 = Adapter()
        @JvmField val E0 = Any()
        @JvmField val G0 = Observable(Any())
        var renders = 0
        var auxiliaries = 0
        var loadingFinishes = 0
        var onRender: (() -> Unit)? = null
        var hidden = false
        fun getView(): View = n0.f0
        fun isHidden() = hidden
        fun B2(pointer: Pointer, static: Boolean) {
            check(pointer === h1)
            check(!static)
            onRender?.invoke()
            renders++
            n0.f0.currentAdapter = p0
            p0.document = pointer
        }
        fun F2(pointer: Pointer) { check(pointer === h1); auxiliaries++ }
        fun o2() { loadingFinishes++; n0.e0.visibility = View.GONE }
    }
    private val id = 4256633673805039340L
    private fun page(timing: String = "Word", attached: Boolean = true): Page {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        return Page(activity, id, timing).also { if (attached) activity.setContentView(it.getView()) }
    }
    private fun recovery() = NativeTimedLyricsPage::class.java.getDeclaredConstructor(Class::class.java)
        .apply { isAccessible = true }.newInstance(Page::class.java)

    @Test fun installedTimedPointerCanRecoverFromStaticAdapterAndLayoutState() {
        val page = page()
        assertTrue(recovery().repair(page, id, page.h1))
        assertSame(page.E0, page.G0.a)
        assertSame(page.p0, page.n0.f0.currentAdapter)
        assertEquals(1, page.G0.notifications)
        assertEquals(1, page.auxiliaries)
        assertFalse(recovery().repair(page, id, page.h1))
        assertEquals(1, page.renders)
    }
    @Test fun sourcePointerIdentityAndFull64BitSongIdentityMustBothMatch() {
        val page = page()
        assertFalse(recovery().repair(page, id, Pointer(page.h1.document)))
        assertFalse(recovery().repair(page, id - 1, page.h1))
        assertFalse(recovery().repair(page, 0, page.h1))
        assertEquals(0, page.renders)
    }
    @Test fun staticUntimedLyricsAndUnavailableViewsRemainUntouched() {
        val static = page("None")
        val detached = page(attached = false)
        val hidden = page().apply { this.hidden = true }
        for (page in listOf(static, detached, hidden)) {
            assertFalse(recovery().repair(page, id, page.h1))
            assertEquals(0, page.G0.notifications)
            assertEquals(0, page.loadingFinishes)
        }
    }
    @Test fun readyTimedPageFinishesLoadingWithoutRebindingAndCancelsPendingShow() {
        val page = page().apply {
            G0.a = E0
            n0.f0.currentAdapter = p0
            p0.document = h1
            n0.e0.visibility = View.VISIBLE
        }
        assertTrue(recovery().repair(page, id, page.h1))
        assertEquals(View.GONE, page.n0.e0.visibility)
        assertEquals(0, page.renders)
        assertFalse(recovery().repair(page, id, page.h1))
        assertEquals(2, page.loadingFinishes)
        assertEquals(0, page.renders)
    }
    @Test fun lineTimingAlsoRecoversAndAlreadyCorrectTimedPagesDoNotRebind() {
        val page = page("Line")
        page.G0.a = page.E0
        assertTrue(recovery().repair(page, id, page.h1))
        assertFalse(recovery().repair(page, id, page.h1))
    }
    @Test fun sameAdapterAndLayoutWithStaleInternalDocumentMustStillRebind() {
        val page = page().apply {
            G0.a = E0
            n0.f0.currentAdapter = p0
            // Same song ID is insufficient: a new document was published.
            p0.document = Pointer(h1.document)
        }
        assertTrue(recovery().repair(page, id, page.h1))
        assertSame(page.h1, page.p0.y())
        assertEquals(1, page.renders)
        assertFalse(recovery().repair(page, id, page.h1))
    }
    @Test fun lateNativeBindingCanBeRepairedAndNestedBindingDoesNotLoop() {
        val page = page()
        val recovery = recovery()
        page.onRender = { assertTrue(recovery.isRepairing); assertFalse(recovery.repair(page, id, page.h1)) }
        assertTrue(recovery.repair(page, id, page.h1))
        assertFalse(recovery.isRepairing)
        // A later native callback replaces the adapter but leaves h1 unchanged.
        page.n0.f0.currentAdapter = Any()
        assertTrue(recovery.repair(page, id, page.h1))
        assertSame(page.h1, page.p0.y())
        assertEquals(2, page.renders)
        assertFalse(recovery.repair(page, id, page.h1))
    }
    @Test fun failedNativeBindingReleasesGuardSoLaterRecoveryCanProceed() {
        val page = page()
        val recovery = recovery()
        page.onRender = { error("Native binding failed") }
        assertThrows(java.lang.reflect.InvocationTargetException::class.java) {
            recovery.repair(page, id, page.h1)
        }
        assertFalse(recovery.isRepairing)
        page.onRender = null
        assertTrue(recovery.repair(page, id, page.h1))
        assertSame(page.h1, page.p0.y())
        assertFalse(recovery.repair(page, id, page.h1))
    }
    @Test fun staleClockRecoveryPreservesOffsetsAndRejectsInvalidMediaClock() {
        assertEquals(2_000L, correctStalledLyricPosition(2_000, 3_500))
        assertEquals(106_000L, correctStalledLyricPosition(14_000, 106_000))
        assertEquals(106_000L, correctStalledLyricPosition(null, 106_000))
        assertEquals(2_000L, correctStalledLyricPosition(2_000, -1))
    }
}
