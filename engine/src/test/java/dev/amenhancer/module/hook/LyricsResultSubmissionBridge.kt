package dev.amenhancer.module.hook

// Test-only embedded-module callback surface. Never packaged in the plugin.
class ModernMethodHook {
    class MethodHookParam(val args: Array<Any?>)
}
class LyricsResultSubmissionBridge {
    class `setValueHook$1` {
        fun beforeHookedMethod(param: ModernMethodHook.MethodHookParam) = Unit
    }
    class `buildIndexHook$1` {
        fun beforeHookedMethod(param: ModernMethodHook.MethodHookParam) = Unit
    }
}
