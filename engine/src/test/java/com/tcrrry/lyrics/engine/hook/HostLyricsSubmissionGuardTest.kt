package com.tcrrry.lyrics.engine.hook

import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(manifest = org.robolectric.annotation.Config.NONE, sdk = [35])
class HostLyricsSubmissionGuardTest {
    @Test fun sdkGuardSkipsOnlyOwnedPayloadAndStopsWithItsScope() {
        com.tcrrry.lyrics.plugin.TestPluginContext.attach()
        val scope=HookRegistrationScope()
        try {
            val owned=Any();val foreign=Any()
            HostLyricsSubmissionGuard.install(scope) { owned };scope.activate()
            val callbacks=com.tcrrry.lyrics.plugin.TestPluginContext.current.callbacks
            val targets=callbacks.keys.filter { it.declaringClass.name.startsWith("dev.amenhancer.module.hook.LyricsResultSubmissionBridge$") }
            assertEquals(2,targets.size)
            fun blocked(method:java.lang.reflect.Executable,value:Any?):Boolean {
                val receiver=(method as java.lang.reflect.Method).declaringClass.getDeclaredConstructor().newInstance()
                val argument=dev.amenhancer.module.hook.ModernMethodHook.MethodHookParam(arrayOf(value))
                val call=dev.amenhancer.plugin.api.PluginCall(method,receiver,arrayOf(argument),null,null)
                callbacks.getValue(method).before(call)
                val result=call.isOutcomeChanged
                callbacks.getValue(method).after(call)
                return result
            }
            for (target in targets) {
                assertTrue(blocked(target,owned));assertTrue(blocked(target,owned to null))
                assertFalse(blocked(target,foreign));assertFalse(blocked(target,null to "error"))
            }
            scope.close()
            assertTrue(targets.none { callbacks.containsKey(it) })
        } finally { scope.close();com.tcrrry.lyrics.plugin.TestPluginContext.current.close() }
    }
    @Test fun onlyTheExactPreparedPointerOrItsResultPairIsOwned() {
        val owned=Any();val foreign=Any()
        assertTrue(HostLyricsSubmissionGuard.ownsArgument(owned,owned))
        assertTrue(HostLyricsSubmissionGuard.ownsArgument(owned to null,owned))
        assertFalse(HostLyricsSubmissionGuard.ownsArgument(foreign,owned))
        assertFalse(HostLyricsSubmissionGuard.ownsArgument(foreign to null,owned))
        assertFalse(HostLyricsSubmissionGuard.ownsArgument(null to "failure",owned))
        assertFalse(HostLyricsSubmissionGuard.ownsArgument(null,null))
    }
}
