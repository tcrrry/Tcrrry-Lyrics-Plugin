package com.tcrrry.lyrics.plugin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Looper
import android.os.Parcel
import android.os.ResultReceiver
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
@Config(manifest=Config.NONE,sdk=[35],shadows=[OfflineTranslationClientBridgeTest.BinderResultReceiver::class])
class OfflineTranslationClientBridgeTest {
    // Robolectric's default shadow calls onReceiveResult directly and skips Binder.
    @org.robolectric.annotation.Implements(ResultReceiver::class)
    class BinderResultReceiver {
        @org.robolectric.annotation.RealObject lateinit var receiver:ResultReceiver
        @org.robolectric.annotation.Implementation
        fun send(code:Int,data:Bundle?) {
            org.robolectric.shadow.api.Shadow.directlyOn<Any,ResultReceiver>(receiver,ResultReceiver::class.java,"send",
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType,code),
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(Bundle::class.java,data))
        }
    }
    @org.junit.Before fun attach() { TestPluginContext.attach() }
    @org.junit.After fun close() { TestPluginContext.current.close() }
    @Test fun optionalPluginAnswersWithoutAnyInstalledCompanion() {
        val app=RuntimeEnvironment.getApplication()
        val receiver=object:BroadcastReceiver() {
            @Suppress("DEPRECATION") override fun onReceive(context:Context,intent:Intent) {
                // Recreate the OS parcel boundary using a loader that cannot see sender classes.
                val parcel=Parcel.obtain()
                val received=try {
                    intent.writeToParcel(parcel,0);parcel.setDataPosition(0)
                    Intent.CREATOR.createFromParcel(parcel).apply {
                        setExtrasClassLoader(object:ClassLoader(ResultReceiver::class.java.classLoader) {
                            override fun loadClass(name:String,resolve:Boolean):Class<*> {
                                if(name.startsWith("com.tcrrry.")) throw ClassNotFoundException(name)
                                return super.loadClass(name,resolve)
                            }
                        })
                    }
                } finally { parcel.recycle() }
                val callback=received.getParcelableExtra<ResultReceiver>("reply")!!
                assertEquals(ResultReceiver::class.java,callback.javaClass)
                callback.send(0,Bundle().apply {
                    putStringArrayList("models",arrayListOf("en","zh","ja"))
                })
            }
        }
        app.registerReceiver(receiver,IntentFilter(OfflineTranslationClient.ACTION),Context.RECEIVER_NOT_EXPORTED)
        try {
            var models:Set<String>?=null;var error:String?=null
            OfflineTranslationClient.queryModels(app,BiConsumer { value,failure -> models=value;error=failure })
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(setOf("zh","en","ja"),models);assertNull(error)
            assertTrue(OfflineTranslationClient.installed(app))
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(3))
            assertTrue(OfflineTranslationClient.installed(app))
        } finally { app.unregisterReceiver(receiver) }
    }
    @Test fun absentExtensionReportsActionableTimeout() {
        var error:String?=null
        OfflineTranslationClient.queryModels(RuntimeEnvironment.getApplication(),BiConsumer { _,failure -> error=failure })
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(3))
        assertTrue(error!!.contains("导入并启用"))
    }
    @Test fun chineseDeletionIsRejectedLocallyAndStoppedClientIgnoresCallbacks() {
        var error:String?=null
        OfflineTranslationClient.deleteModel(RuntimeEnvironment.getApplication(),"zh",Consumer { error=it })
        assertTrue(error!!.contains("不可删除"))
        var callbacks=0
        OfflineTranslationClient.queryModels(RuntimeEnvironment.getApplication(),BiConsumer { _,_ -> callbacks++ })
        TestPluginContext.current.close()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(3))
        assertEquals(0,callbacks)
    }
}
