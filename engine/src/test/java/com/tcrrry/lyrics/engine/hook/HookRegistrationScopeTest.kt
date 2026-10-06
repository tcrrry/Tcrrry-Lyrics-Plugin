package com.tcrrry.lyrics.engine.hook

import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(manifest = org.robolectric.annotation.Config.NONE, sdk = [35])
class HookRegistrationScopeTest {
    @org.junit.Before fun attachPlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.attach() }
    @org.junit.After fun closePlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.current.close() }
    @Test fun `partial registration and close leave callbacks inactive`() {
        val scope = HookRegistrationScope()
        assertFalse(scope.isActive)
        scope.close()
        assertFalse(scope.isActive)
        assertThrows(IllegalStateException::class.java) { scope.activate() }
    }
    @Test fun `activation publishes once and close cleans in reverse even after failure`() {
        val events = mutableListOf<Int>()
        val scope = HookRegistrationScope()
        scope.onClose { events += 1 }
        scope.onClose { events += 2; error("cleanup failed") }
        scope.onClose { events += 3 }
        scope.activate()
        assertTrue(scope.isActive)
        assertThrows(IllegalStateException::class.java) { scope.activate() }
        scope.close(); scope.close()
        assertFalse(scope.isActive)
        assertEquals(listOf(3, 2, 1), events)
        scope.onClose { events += 4 }
        assertEquals(listOf(3, 2, 1, 4), events)
    }
}
