package com.tcrrry.lyrics.engine.hook

import com.tcrrry.lyrics.engine.model.ModuleSettings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class LyricGlowScopeTest {
    @org.junit.Before fun attachPlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.attach() }
    @org.junit.After fun closePlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.current.close() }
    class Entry(@JvmField var c: CharSequence, @JvmField var f: Int, @JvmField var g: Int) {
        @JvmField val k = emptyList<Any>()
        @JvmField val p = mutableListOf<Any>()
    }
    class Holder(entry: Entry) { @JvmField val G = java.util.HashMap<Int, Entry>().apply { put(1, entry) } }
    private fun target(sensitivity: Int): AppleMusicCjkKaraokeAnimationTarget = AppleMusicCjkKaraokeAnimationTarget(
        object : TargetSymbolResolver {
            override fun <T : Any> resolve(symbol: TargetSymbolKey<T>): TargetResolution<T> = error("not used")
        }, glowSettings = { ModuleSettings(lyricGlowSensitivity = sensitivity) }).also {
        it.javaClass.getDeclaredField("hooksReady").apply { isAccessible = true }.setBoolean(it, true)
    }
    private fun call(target: Any, method: String, param: ModernMethodHook.MethodHookParam? = null) {
        val types = if (param == null) emptyArray() else arrayOf(ModernMethodHook.MethodHookParam::class.java)
        target.javaClass.getDeclaredMethod(method, *types).apply { isAccessible = true }
            .invoke(target, *if (param == null) emptyArray() else arrayOf(param))
    }
    private fun param(entry: Entry) = ModernMethodHook.MethodHookParam(javaClass.getMethod("dummy"), Any(),
        arrayOf(Holder(entry), 0, 1, entry.f, false))
    private fun beforeGradient(target: Any, entry: Entry) {
        target.javaClass.getDeclaredMethod("restoreGradientMetadata", Any::class.java)
            .apply { isAccessible = true }.invoke(target, entry)
    }
    fun dummy() = Unit
    @Test fun promotedCjkDurationRestoresAfterNativeAnimationFailure() {
        val target = target(200)
        val entry = Entry("あ", 600, 1)
        val param = param(entry)
        call(target, "enterA0Scope", param)
        assertEquals(1000, entry.f)
        assertEquals(600, param.args[3])
        param.throwable = IllegalStateException("native failure")
        call(target, "completeA0Scope", param)
        call(target, "leaveA0Scope")
        assertEquals(600, entry.f)
        assertEquals(1, entry.g)
    }
    @Test fun fiveHundredPercentTriggersShortSoundsWhileWordSweepKeepsRealDuration() {
        val entry = Entry("あ", 200, 1)
        val target = target(500)
        val param = param(entry)
        call(target, "enterA0Scope", param)
        assertEquals(1000, entry.f)
        assertEquals(200, param.args[3])
        // The verified native V method opens the special branch at f >= 1000.
        val glow = android.animation.ValueAnimator.ofFloat(0f, 1f).apply { duration = 1000L; startDelay = 500L }
        entry.p.add(glow)
        call(target, "completeA0Scope", param)
        assertEquals(200L, glow.duration)
        assertEquals(100L, glow.startDelay)
        beforeGradient(target, entry)
        assertEquals(200, entry.f)
        call(target, "leaveA0Scope")
        assertEquals(200, entry.f)
        assertEquals(1, entry.g)
    }
    @Test fun reducedSensitivitySuppressesOnlyTheGlowGateThenRestoresLength() {
        val target = target(50)
        val entry = Entry("あ", 1200, 1)
        val param = param(entry)
        call(target, "enterA0Scope", param)
        assertEquals(8, entry.g)
        assertEquals(1200, entry.f)
        call(target, "leaveA0Scope")
        assertEquals(1, entry.g)
        assertEquals(1200, entry.f)
    }
    @Test fun wordSweepSeesRealDurationBeforeTheGlowScopeEnds() {
        val target = target(200)
        val entry = Entry("あ", 600, 1)
        val param = param(entry)
        call(target, "enterA0Scope", param)
        assertEquals(1000, entry.f)
        beforeGradient(target, entry)
        assertEquals(600, entry.f)
        assertEquals(1, entry.g)
        assertEquals(600, param.args[3])
        call(target, "leaveA0Scope")
        assertEquals(600, entry.f)
    }
    @Test fun gradientRestoresShortWordFeatherMetadataWithoutTouchingAnotherBinding() {
        val target = target(50)
        val entry = Entry("あ", 1200, 1)
        val other = Entry("い", 1600, 1)
        call(target, "enterA0Scope", param(entry))
        assertEquals(8, entry.g)
        beforeGradient(target, other)
        assertEquals(8, entry.g)
        assertEquals(1, other.g)
        assertEquals(1600, other.f)
        beforeGradient(target, entry)
        assertEquals(1, entry.g)
        call(target, "leaveA0Scope")
        assertEquals(1, entry.g)
    }
    @Test fun backgroundWordsNeverBorrowTheForegroundGlowThreshold() {
        val target = target(50)
        val entry = Entry("あ", 1200, 1)
        val holder = object { @JvmField val H = java.util.HashMap<Int, Entry>().apply { put(1, entry) } }
        val param = ModernMethodHook.MethodHookParam(javaClass.getMethod("dummy"), Any(), arrayOf(holder, 0, 1, 1200, true))
        call(target, "enterA0Scope", param)
        assertEquals(1, entry.g)
        assertEquals(1200, entry.f)
        call(target, "leaveA0Scope")
        assertEquals(1, entry.g)
    }
}
