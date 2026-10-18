package com.tcrrry.translation.plugin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.ResultReceiver
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.tcrrry.lyrics.plugin.PluginLifetime
import com.tcrrry.lyrics.plugin.PureOfflineRuntime
import dev.amenhancer.plugin.api.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.function.BiConsumer
import java.util.function.Consumer

/** Same-host private broadcasts provide a versioned bridge without installing Android components. */
class TranslationPlugin : AmppPlugin() {
    private lateinit var sdk: PluginContext
    private val waiting = ConcurrentHashMap<String, ResultReceiver>()
    private fun pool()=ThreadPoolExecutor(2,2,30,TimeUnit.SECONDS,LinkedBlockingQueue(16))
    private var worker = pool()
    internal var invokeRuntime: (String,Array<Class<*>>,Array<out Any?>) -> Any? = { name,types,args ->
        PureOfflineRuntime.invoke(sdk.application,name,types,*args)
    }
    @Volatile private var active=false
    private var registered=false
    private val receiver=object:BroadcastReceiver() {
        @Suppress("DEPRECATION")
        override fun onReceive(context: Context, intent: Intent) {
            try { receive(intent) }
            catch(error:RuntimeException) { sdk.log("Rejected malformed translation request",error) }
        }
        @Suppress("DEPRECATION")
        private fun receive(intent: Intent) {
            if(!active || intent.action!=ACTION || intent.getIntExtra("protocol",0)!=1) return
            val reply=intent.getParcelableExtra<ResultReceiver>("reply") ?: return
            val operation=intent.getStringExtra("operation") ?: return
            if(operation !in setOf("query","download","delete","translate")) return
            val language=intent.getStringExtra("language")
            val text=intent.getStringExtra("text")
            val id=UUID.randomUUID().toString();waiting[id]=reply
            reply.send(1,Bundle().apply { putString("message","正在准备离线翻译");putInt("percent",-1) })
            fun finish(value: Bundle?=null,error: String?=null) {
                waiting.remove(id)?.send(if(error==null) 0 else -1,value ?: Bundle().apply { putString("error",error) })
            }
            try { worker.execute {
                if(!active) { finish(error="翻译扩展已停止");return@execute }
                try {
                    when(operation) {
                        "query" -> invokeRuntime("queryModels",arrayOf(BiConsumer::class.java),arrayOf(
                            BiConsumer<Set<String>?,String?> { models,error -> if(active) finish(models?.let { Bundle().apply { putStringArrayList("models",ArrayList(it)) } },error) }))
                        "translate" -> {
                            require(language!=null && text!=null && text.isNotBlank() && text.length<=4000)
                            val result=invokeRuntime("translate",arrayOf(String::class.java,String::class.java),arrayOf(language,text)) as String
                            if(active) finish(Bundle().apply { putString("translation",result) })
                        }
                        "download" -> {
                            require(language!=null && language.matches(Regex("[a-z]{2,3}")))
                            invokeRuntime("downloadModelsWithProgress",arrayOf(String::class.java,Consumer::class.java,Consumer::class.java),arrayOf(
                                language,Consumer<Bundle> { if(active && waiting.containsKey(id)) reply.send(1,it) },Consumer<String?> { if(active) finish(error=it) }))
                        }
                        "delete" -> {
                            require(language!=null && language !in setOf("en","zh")) { "中文内置模型不可删除" }
                            invokeRuntime("deleteModel",arrayOf(String::class.java,Consumer::class.java),arrayOf(language,Consumer<String?> { if(active) finish(error=it) }))
                        }
                    }
                } catch(error:Exception) { sdk.log("Translation request failed",error);finish(error=error.message?.take(240) ?: "离线翻译失败") }
            } } catch(_:java.util.concurrent.RejectedExecutionException) { finish(error="翻译扩展正在忙，请稍后重试") }
        }
    }
    override fun onLoad(context: PluginContext) {
        require(context.hostPackageName in setOf("com.apple.android.music","com.tcrrry.ampplyrics.coexist"))
        sdk=context;PluginLifetime.attach(context)
        context.claimResource("com.tcrrry.translation.service.v1",true)
        context.onClose { onStop() }
    }
    override fun onStart() {
        if(registered) return
        if(worker.isShutdown) worker=pool()
        active=true
        if(Build.VERSION.SDK_INT>=33) sdk.application.registerReceiver(receiver,IntentFilter(ACTION),Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") sdk.application.registerReceiver(receiver,IntentFilter(ACTION))
        registered=true
    }
    override fun onStop() {
        active=false
        if(registered) { sdk.application.unregisterReceiver(receiver);registered=false }
        waiting.values.toList().forEach { it.send(-1,Bundle().apply { putString("error","离线翻译扩展已停用") }) }
        waiting.clear();worker.shutdownNow()
    }
    override fun createSettings(context: Context): PluginSettingsSession {
        val dark=context.resources.configuration.uiMode and 48 == 32
        val body=LinearLayout(context).apply {
            orientation=LinearLayout.VERTICAL;setPadding(32,28,32,28)
            setBackgroundColor(if(dark) 0xff1c1c1e.toInt() else 0xfff2f2f7.toInt())
            addView(TextView(context).apply { text="Tcrrry 离线翻译";textSize=21f;setTextColor(if(dark) -1 else 0xff1c1c1e.toInt()) })
            addView(TextView(context).apply {
                text="在「歌词增强 → 翻译与注音 → 离线机翻」中查看已下载的语言、下载其他语言或管理语言包。\n\n下载其他语言包需要开启代理；下载完成后即可离线使用。"
                textSize=15f;setTextColor(if(dark) 0xffb7b7bf.toInt() else 0xff55555d.toInt());setPadding(0,20,0,0)
            })
        }
        return object:PluginSettingsSession { override fun getView(): View=body;override fun close() {} }
    }
    companion object { const val ACTION="com.tcrrry.translation.REQUEST_V1" }
}
