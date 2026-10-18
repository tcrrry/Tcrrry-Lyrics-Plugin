package com.tcrrry.translation.plugin

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import dev.amenhancer.plugin.api.*
import java.io.File
import java.util.function.BiConsumer
import java.util.function.Consumer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class TranslationPluginTest {
    private class Sdk:PluginContext {
        private val dir=java.nio.file.Files.createTempDirectory("translation-test").toFile()
        val cleanup=mutableListOf<Runnable>()
        override fun getApplication():Application=RuntimeEnvironment.getApplication()
        override fun getHostClassLoader()=javaClass.classLoader!!
        override fun getHostPackageName()="com.apple.android.music"
        override fun getHostVersionName()="7.0.0-beta"
        override fun getHostVersionCode()=1609L
        override fun getHooks():PluginHooks=throw UnsupportedOperationException("No hook required")
        override fun getDataDirectory()=File(dir,"data").apply { mkdirs() }
        override fun getCacheDirectory()=File(dir,"cache").apply { mkdirs() }
        override fun openAsset(path:String)=File(dir,path).inputStream()
        override fun log(message:String,error:Throwable?) {}
        override fun onClose(action:Runnable) { cleanup+=action }
        override fun claimResource(key:String,exclusive:Boolean)=PluginRegistration {}
    }
    private lateinit var plugin:TranslationPlugin
    private val invoked=mutableListOf<String>()
    @org.junit.Before fun start() {
        com.tcrrry.lyrics.plugin.PluginLifetime::class.java.getDeclaredField("context").apply { isAccessible=true }.set(null,null)
        (com.tcrrry.lyrics.plugin.PluginLifetime::class.java.getDeclaredField("stopped").apply { isAccessible=true }.get(null) as java.util.concurrent.atomic.AtomicBoolean).set(false)
        plugin=TranslationPlugin()
        plugin.invokeRuntime={ name,_,args ->
            synchronized(invoked) { invoked+=name }
            @Suppress("UNCHECKED_CAST")
            val result:Any? = when(name) {
                "queryModels" -> (args[0] as BiConsumer<Set<String>?,String?>).accept(setOf("en","zh","ja"),null)
                "translate" -> "你好，世界"
                "downloadModelsWithProgress" -> {
                    (args[1] as Consumer<Bundle>).accept(Bundle().apply { putString("message","正在下载：42%");putInt("percent",42) })
                    (args[2] as Consumer<String?>).accept(null)
                }
                "deleteModel" -> (args[1] as Consumer<String?>).accept(null)
                else -> error("Unexpected runtime call")
            }
            result
        }
        // Each test uses a fresh sandbox class loader and SDK lifetime.
        plugin.onLoad(Sdk());plugin.onStart()
    }
    @org.junit.After fun stop() { plugin.onStop() }
    private fun request(operation:String,language:String?=null,text:String?=null):MutableList<Pair<Int,Bundle?>> {
        val replies=java.util.Collections.synchronizedList(mutableListOf<Pair<Int,Bundle?>>())
        val reply=object:ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(code:Int,data:Bundle?) { replies+=code to data }
        }
        val app=RuntimeEnvironment.getApplication()
        app.sendBroadcast(Intent(TranslationPlugin.ACTION).setPackage(app.packageName).apply {
            putExtra("protocol",1);putExtra("operation",operation);putExtra("language",language);putExtra("text",text);putExtra("reply",reply)
        })
        repeat(100) { shadowOf(Looper.getMainLooper()).idle();if(replies.any { it.first!=1 }) return replies;Thread.sleep(5) }
        return replies
    }
    @Test fun privateBridgeQueriesAndTranslatesAcrossFrameworkValues() {
        val query=request("query");assertTrue(query.any { it.first==0 && it.second!!.getStringArrayList("models")!!.containsAll(listOf("zh","en","ja")) })
        val result=request("translate","en","Hello, world")
        assertEquals("你好，世界",result.single { it.first==0 }.second!!.getString("translation"))
    }
    @Test fun progressIsForwardedAndChineseCannotBeDeleted() {
        val download=request("download","ko")
        assertTrue(download.any { it.first==1 && it.second?.getInt("percent")==42 })
        val deletion=request("delete","zh")
        assertTrue(deletion.any { it.first== -1 && it.second!!.getString("error")!!.contains("不可删除") })
        assertFalse(invoked.contains("deleteModel"))
    }
    @Test fun malformedReplyCannotCrashHostBroadcastDispatcher() {
        val app=RuntimeEnvironment.getApplication()
        app.sendBroadcast(Intent(TranslationPlugin.ACTION).setPackage(app.packageName).apply {
            putExtra("protocol",1);putExtra("operation","query");putExtra("reply",Bundle())
        })
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(request("query").any { it.first==0 })
    }
    @Test fun stoppingUnregistersBridgeAndStartingAgainIsSupported() {
        plugin.onStop();assertTrue(request("query").isEmpty())
        plugin.onStart();assertTrue(request("query").any { it.first==0 })
    }
}
