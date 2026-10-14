package com.tcrrry.lyrics.plugin

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import dev.amenhancer.plugin.api.*
import com.tcrrry.lyrics.engine.config.*
import com.tcrrry.lyrics.engine.hook.*
import com.tcrrry.lyrics.engine.ui.*
import com.tcrrry.lyrics.engine.model.ModuleSettings

/** Independently loaded v1.5 lyric engine; all hooks go through SDK v1. */
class LyricsPlugin : AmppPlugin() {
    private lateinit var runtime: PluginContext
    private lateinit var configuration: EmbeddedConfigurationSession
    private val currentSong=CurrentSongIdentityCache()
    private val settingsViews=java.util.Collections.newSetFromMap(java.util.WeakHashMap<View,Boolean>())
    private val dialogs=java.util.Collections.newSetFromMap(java.util.WeakHashMap<android.app.Dialog,Boolean>())
    override fun onLoad(context: PluginContext) {
        if(context.hostPackageName !in setOf("com.apple.android.music","com.tcrrry.ampplyrics.coexist") || context.hostVersionCode !in setOf(1606L,1609L) || context.hostVersionName!="7.0.0-beta")
            throw PluginUnsupportedException("插件 1.0.7 仅适配 Apple Music 7.0.0-beta / 1606、1609")
        runtime=context;PluginLifetime.attach(context)
        NativeLyricsHostContract.verify(context.hostClassLoader)
        context.claimResource("com.tcrrry.lyrics.settings",true)
        val storage=HostPrivateEmbeddedStorage(context.application)
        configuration=EmbeddedConfigurationSession(storage)
        val config=TargetConfigClient(configuration) { health -> context.log("${health.feature}: ${health.state} ${health.message}",null) }
        val build=TargetBuild(context.hostPackageName,context.hostVersionName,context.hostVersionCode)
        val resolver=IndexedTargetSymbolResolver(build,ApkTargetClassSource(context.application,context.hostClassLoader))
        val automatic=createAutoLyricsRuntime(context.application)
        (automatic.executor as? java.util.concurrent.ExecutorService)?.let { service -> context.onClose { service.shutdownNow() } }
        fun requireActive(label: String,result: TargetCapabilityInstall) {
            if(result !is TargetCapabilityInstall.Active) throw PluginUnsupportedException("$label：${result.message}")
            context.log("$label: ${result.message}",null)
        }
        requireActive("曲目信息",AppleMusicCurrentSongIdentityTarget(resolver,currentSong).install())
        NativeLyricsSourceMenu.install(context.hostClassLoader,{currentSong.current()?.details?.appleMusicId},::openSettings,build)
        requireActive("歌词匹配",AppleMusicCustomLyricsTarget(context.application,config,resolver,currentSong,automatic).install())
        requireActive("辉光",AppleMusicCjkKaraokeAnimationTarget(resolver,if(context.hostVersionCode==1609L) "X" else "U",true,config::settings).install())
        requireActive("聚焦恢复",AppleMusicBidirectionalLyricBlurTarget(resolver).install())
        // Preserve English word boundaries and the real source timing.
        val parser=context.hostClassLoader.loadClass("com.apple.android.music.ttml.javanative.TTMLParser\$TTMLParserNative")
        context.hooks.hook(parser.getDeclaredMethod("songInfoFromTTML",String::class.java),false,object:PluginHook {
            override fun before(call:PluginCall) { (call.arguments[0] as? String)?.let { call.arguments[0]=TtmlNormalizer.spaces(it) } }
        })
        context.onClose { android.os.Handler(android.os.Looper.getMainLooper()).post { onStop() } }
    }
    override fun onStart() { runtime.log("Tcrrry Lyrics 1.0.7 started; baseline v1.5",null); ReleaseUpdates.start(runtime.application) }
    override fun onStop() {
        RubyPronunciationRows.close()
        dialogs.toList().forEach { it.dismiss() };dialogs.clear()
        settingsViews.toList().forEach { (it.parent as? android.view.ViewGroup)?.removeView(it) };settingsViews.clear()
        PronunciationHeaderLayout.clearAll()
        NativeLyricsSourceMenu.cleanupPluginRows()
    }
    private fun activity(context:Context):Activity {
        var value=context
        repeat(12) { if(value is Activity) return value as Activity;value=(value as? ContextWrapper)?.baseContext ?: throw IllegalArgumentException("需要 Activity 设置上下文") }
        error("设置上下文嵌套过深")
    }
    private fun render(activity:Activity, hostSurface:Int?=null):LinearLayout {
        val body=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
        if(hostSurface!=null) SettingsSurfaceContext.mark(body,hostSurface)
        var renderedSong:com.tcrrry.lyrics.engine.CurrentSongDetails?=null
        var renderedColors:SettingsColors?=null
        fun refresh() {
            if(activity.isFinishing || !PluginLifetime.alive) return
            SettingsSurfaceContext.from(body) {
                val song=currentSong.current()?.details
                val colors=SettingsUiTheme.colors(activity)
                // Source status has its own content-aware poll; preference changes need no home rebuild.
                if(renderedColors==colors && renderedSong==song) return@from
                body.removeAllViews()
                val prefs=configuration.settings()
                body.addView(TextView(activity).apply { text="Tcrrry 歌词设置 · ${ReleaseUpdates.channel.label} ${ReleaseUpdates.channel.version}";textSize=20f;setTextColor(SettingsUiTheme.colors(activity).text);setPadding(24,24,24,12) })
                TcrrryLyricsSettingsUi.render(activity,body,song,::refresh,prefs,{next -> configuration.saveSettings(next)},configuration::settings)
                renderedSong=song
                renderedColors=colors
            }
        }
        refresh();settingsViews.add(body);return body
    }
    private fun openSettings(activity:Activity) {
        val dialog=TcrrryLyricsSettingsUi.mainDialog(activity,render(activity))
        dialogs.add(dialog);dialog.setOnDismissListener { dialogs.remove(dialog) };dialog.show()
    }
    override fun createSettings(context:Context):PluginSettingsSession {
        val body=render(activity(context),SettingsSurfaceContext.background(context))
        val view=SettingsHostPage(context,body)
        settingsViews.add(view)
        return object:PluginSettingsSession {
            override fun getView()=view
            override fun close() { settingsViews.remove(body);settingsViews.remove(view);view.close() }
        }
    }
}
