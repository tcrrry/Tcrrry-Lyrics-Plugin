package com.tcrrry.lyrics.plugin

import android.app.Application
import dev.amenhancer.plugin.api.*
import java.io.File
import java.lang.reflect.Executable
import java.util.concurrent.atomic.AtomicBoolean
import org.robolectric.RuntimeEnvironment

class TestPluginContext : PluginContext, PluginHooks {
    val actions = mutableListOf<Runnable>()
    val callbacks = linkedMapOf<Executable,PluginHook>()
    private val directory = java.nio.file.Files.createTempDirectory("plugin-test-").toFile()
    override fun getApplication(): Application = RuntimeEnvironment.getApplication()
    override fun getHostClassLoader():ClassLoader=javaClass.classLoader!!
    override fun getHostPackageName()="com.apple.android.music"
    override fun getHostVersionName()="7.0.0-beta"
    override fun getHostVersionCode()=1606L
    override fun getDataDirectory()=File(directory,"data").apply { mkdirs() }
    override fun getCacheDirectory()=File(directory,"cache").apply { mkdirs() }
    override fun getHooks():PluginHooks=this
    override fun openAsset(path:String)=File("../assets",path).inputStream()
    override fun log(message:String,error:Throwable?) {}
    override fun onClose(action:Runnable) { actions+=action }
    override fun claimResource(key:String,exclusive:Boolean)=PluginRegistration {}
    override fun observe(target:Executable,callback:PluginObserver)=PluginRegistration {}
    override fun hook(target:Executable,exclusive:Boolean,callback:PluginHook):PluginRegistration {
        callbacks[target]=callback
        return PluginRegistration { callbacks.remove(target) }
    }
    fun close() {
        actions.toList().asReversed().forEach { it.run() };actions.clear();callbacks.clear()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        directory.deleteRecursively()
    }
    companion object {
        lateinit var current:TestPluginContext
        fun attach() {
            PluginLifetime::class.java.getDeclaredField("context").apply { isAccessible=true }.set(null,null)
            (PluginLifetime::class.java.getDeclaredField("stopped").apply { isAccessible=true }.get(null) as AtomicBoolean).set(false)
            (PluginStorage::class.java.getDeclaredField("preferences").apply { isAccessible=true }.get(null) as MutableMap<*,*>).clear()
            current=TestPluginContext();PluginLifetime.attach(current)
        }
    }
}
