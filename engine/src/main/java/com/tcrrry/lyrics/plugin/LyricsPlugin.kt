package com.tcrrry.lyrics.plugin

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Switch
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
        if(context.hostPackageName !in setOf("com.apple.android.music","com.tcrrry.ampplyrics.coexist") || context.hostVersionCode!=1606L || context.hostVersionName!="7.0.0-beta")
            throw PluginUnsupportedException("插件 1.0.0 仅适配 Apple Music 7.0.0-beta / 1606")
        runtime=context;PluginLifetime.attach(context)
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
        requireActive("辉光",AppleMusicCjkKaraokeAnimationTarget(resolver,"U",true,config::settings).install())
        requireActive("聚焦恢复",AppleMusicBidirectionalLyricBlurTarget(resolver).install())
        // Preserve English word boundaries and the real source timing.
        val parser=context.hostClassLoader.loadClass("com.apple.android.music.ttml.javanative.TTMLParser\$TTMLParserNative")
        context.hooks.hook(parser.getDeclaredMethod("songInfoFromTTML",String::class.java),false,object:PluginHook {
            override fun before(call:PluginCall) { (call.arguments[0] as? String)?.let { call.arguments[0]=TtmlNormalizer.spaces(it) } }
        })
        context.onClose { android.os.Handler(android.os.Looper.getMainLooper()).post { onStop() } }
    }
    override fun onStart() { runtime.log("Tcrrry Lyrics 1.0.0 started; baseline v1.5",null) }
    override fun onStop() {
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
    private fun render(activity:Activity):View {
        val scroll=ScrollView(activity);val body=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL };scroll.addView(body)
        fun refresh() {
            if(activity.isFinishing || !PluginLifetime.alive) return
            body.removeAllViews()
            val prefs=configuration.settings()
            body.addView(TextView(activity).apply { text="Tcrrry 歌词设置 · 插件 1.0.0";textSize=20f;setTextColor(SettingsUiTheme.colors(activity).text);setPadding(24,24,24,12) })
            body.addView(Switch(activity).apply {
                text="自动匹配歌词";isChecked=prefs.customLyricsEnabled && prefs.automaticLyricsEnabled
                setTextColor(SettingsUiTheme.colors(activity).text)
                setOnCheckedChangeListener { _,checked -> configuration.saveSettings(prefs.copy(customLyricsEnabled=checked,automaticLyricsEnabled=checked));CurrentLyricsSourceStatus.refresh(currentSong.current()?.details?.appleMusicId?:0L);refresh() }
            })
            TcrrryLyricsSettingsUi.render(activity,body,currentSong.current()?.details,::refresh,::refresh,prefs,{next -> configuration.saveSettings(next)},::refresh)
            body.addView(SettingsUiTheme.appearanceCard(activity,::refresh))
        }
        refresh();settingsViews.add(scroll);return scroll
    }
    private fun openSettings(activity:Activity) {
        val dialog=SettingsUiTheme.dialogBuilder(activity).setView(render(activity)).setNegativeButton("关闭",null).create()
        dialogs.add(dialog);dialog.setOnDismissListener { dialogs.remove(dialog) };dialog.show()
    }
    override fun createSettings(context:Context):PluginSettingsSession {
        val view=render(activity(context))
        return object:PluginSettingsSession {
            override fun getView()=view
            override fun close() { settingsViews.remove(view);(view.parent as? android.view.ViewGroup)?.removeView(view) }
        }
    }
}
