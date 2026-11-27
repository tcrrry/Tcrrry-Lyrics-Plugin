package dev.kifranei.ampp.media

/** 歌词页可见性：歌词页打开时暂停自定义 AutoMix 注入，避免原生歌词结果桥接被卡住。 */
internal object LyricsPageVisibility {
    @Volatile var visible: Boolean = false
        private set

    fun install(loader: ClassLoader, scope: PluginScope) {
        runCatching {
            val cls = loader.loadClass("com.apple.android.music.player.fragment.PlayerLyricsViewFragment")
            val onStart = cls.getMethod("onStart")
            val onStop = cls.getMethod("onStop")
            val onDestroy = cls.getMethod("onDestroy")
            MediaRuntime.hookMethod(onStart, object : PluginMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) { visible = true }
            }, scope)
            MediaRuntime.hookMethod(onStop, object : PluginMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) { visible = false }
            }, scope)
            MediaRuntime.hookMethod(onDestroy, object : PluginMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) { visible = false }
            }, scope)
        }
    }
}
