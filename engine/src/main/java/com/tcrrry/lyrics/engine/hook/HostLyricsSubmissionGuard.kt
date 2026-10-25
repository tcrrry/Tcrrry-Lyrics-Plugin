package com.tcrrry.lyrics.engine.hook

/** Let the SDK plugin submit its own prepared payload without a second bridge rewriting it. */
internal object HostLyricsSubmissionGuard {
    internal fun ownsArgument(value: Any?, owned: Any?): Boolean {
        if (owned == null) return false
        if (value === owned) return true
        return runCatching { value?.javaClass?.getMethod("component1")?.invoke(value) === owned }.getOrDefault(false)
    }

    fun install(scope: HookRegistrationScope, ownedPointer: () -> Any?) {
        // This optional bridge belongs to the embedded host module, not Apple
        // Music. All interception still uses the plugin SDK and is removed on close.
        val loader = com.tcrrry.lyrics.plugin.PluginLifetime.context.javaClass.classLoader ?: return
        for (name in listOf("setValueHook", "buildIndexHook")) {
            runCatching {
                val type = loader.loadClass("dev.amenhancer.module.hook.LyricsResultSubmissionBridge\$$name\$1")
                val method = type.declaredMethods.single { it.name == "beforeHookedMethod" && it.parameterCount == 1 }
                val arguments = method.parameterTypes.single().getMethod("getArgs")
                ModernXposedRuntime.hookMethod(method, object : ModernMethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val owned = ownedPointer() ?: return
                        val call = param.args.firstOrNull() ?: return
                        val values = arguments.invoke(call) as? Array<*> ?: return
                        // Keep native loads, empty results, other songs and every
                        // foreign document untouched. Only our exact ready pointer is owned.
                        if (ownsArgument(values.firstOrNull(), owned)) param.result = null
                    }
                }, scope)
                ModernXposedRuntime.log("embedded lyric bridge cooperation installed: $name")
            }.onFailure { ModernXposedRuntime.log("embedded lyric bridge cooperation unavailable: $name", it) }
        }
    }
}
