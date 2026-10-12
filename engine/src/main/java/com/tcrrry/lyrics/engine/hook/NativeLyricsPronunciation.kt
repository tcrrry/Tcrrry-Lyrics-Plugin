package com.tcrrry.lyrics.engine.hook

/** Prefer Apple's match; only fall back to a real Latin track reported by its native parser. */
internal object NativeLyricsPronunciationPolicy {
    private val supported = Regex("^(ja|ko|zh)-Latn(?:-[A-Za-z0-9]{2,8})*$", RegexOption.IGNORE_CASE)
    fun select(original: String?, languages: List<String>): String? =
        original ?: languages.firstOrNull { supported.matches(it) }
}

internal object NativeLyricsPronunciation {
    fun install(loader: ClassLoader) {
        runCatching {
            val vector = loader.lyricsClass("com.apple.android.mediaservices.javanative.common.StringVector\$StringVectorNative")
            val locale = loader.lyricsClass("com.apple.android.music.playback.util.LocaleUtil")
            val size = vector.getMethod("size").apply { isAccessible = true }
            val get = vector.getMethod("get", Long::class.javaPrimitiveType).apply { isAccessible = true }
            val match = locale.getDeclaredMethod("matchToSystemLyricsScript", vector).apply { isAccessible = true }
            val reported = java.util.Collections.synchronizedSet(mutableSetOf<String>())
            ModernXposedRuntime.hookMethod(match, object : ModernMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null || param.result != null) return
                    runCatching {
                        val value = param.args.firstOrNull() ?: return@runCatching
                        val count = (size.invoke(value) as Number).toLong()
                        if (count !in 0L..32L) return@runCatching
                        val languages = (0L until count).mapNotNull { get.invoke(value, it) as? String }
                        val selected = NativeLyricsPronunciationPolicy.select(null, languages)
                        if (selected != null) param.result = selected
                        // Bounded, credential-free diagnostics distinguish parser loss from script filtering.
                        val signature = languages.joinToString(",").take(160) + " -> " + (selected ?: "none")
                        if (reported.size < 32 && reported.add(signature)) {
                            ModernXposedRuntime.log("native pronunciation script fallback: $signature")
                        }
                    }.onFailure { ModernXposedRuntime.log("native pronunciation match failed open", it) }
                }
            })
            ModernXposedRuntime.log("native pronunciation Latin-script fallback installed")
        }.onFailure { ModernXposedRuntime.log("native pronunciation fallback unavailable; kept original behavior", it) }
    }
}
