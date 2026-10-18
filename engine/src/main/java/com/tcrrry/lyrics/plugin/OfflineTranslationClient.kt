package com.tcrrry.lyrics.plugin

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.os.ResultReceiver
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.BiConsumer
import java.util.function.Consumer

/** Optional SDK-plugin bridge. Only framework Bundles/ResultReceiver cross class loaders. */
object OfflineTranslationClient {
    const val ACTION = "com.tcrrry.translation.REQUEST_V1"
    val languages = "af ar be bg bn ca cs cy da de el en eo es et fa fi fr ga gl gu he hi hr ht hu id is it ja ka kn ko lt lv mk mr ms mt nl no pl pt ro ru sk sl sq sv sw ta te th tl tr uk ur vi zh".split(" ")
    private val main = Handler(Looper.getMainLooper())
    private val pending = ConcurrentHashMap<String, (String) -> Unit>()
    private var owner: dev.amenhancer.plugin.api.PluginContext? = null
    @Volatile private var connected = false
    @Volatile private var closing = false
    private val probeLock = Any()
    private var connectionProbe: java.util.concurrent.CompletableFuture<Unit>? = null
    private val translationSlots = java.util.concurrent.Semaphore(2, true)
    fun installed(context: Context) = connected
    fun statusText(context: Context) = if (connected) "离线翻译扩展已连接 · 中文已内置" else "离线机翻需要启用可选的「Tcrrry 离线翻译」插件"
    fun requireInstalled(context: Context) {
        check(PluginLifetime.alive) { "歌词插件已停止" }
        ownRequests()
        if (connected) return
        check(Looper.myLooper() != Looper.getMainLooper()) { "正在连接离线翻译，请稍后重试" }
        var start = false
        val probe = synchronized(probeLock) {
            connectionProbe ?: java.util.concurrent.CompletableFuture<Unit>().also { connectionProbe = it; start = true }
        }
        if (start) request(context, "query") { value, error ->
            if (value != null && error == null) probe.complete(Unit)
            else probe.completeExceptionally(IllegalStateException(error ?: "无法连接离线翻译扩展"))
            synchronized(probeLock) { if (connectionProbe === probe) connectionProbe = null }
        }
        try { probe.get(65, TimeUnit.SECONDS) }
        catch (error: java.util.concurrent.ExecutionException) { throw IllegalStateException(error.cause?.message, error.cause) }
    }
    @Synchronized private fun ownRequests() {
        val current=PluginLifetime.context
        if(owner===current) return
        owner=current;closing=false;connected=false
        synchronized(probeLock) { connectionProbe = null }
        current.onClose {
            closing=true
            pending.values.toList().forEach { it("歌词插件已停止") }; pending.clear(); connected=false
            synchronized(probeLock) {
                connectionProbe?.completeExceptionally(IllegalStateException("歌词插件已停止")); connectionProbe = null
            }
        }
    }
    internal fun request(context: Context, operation: String, language: String? = null, text: String? = null,
        progress: ((Bundle) -> Unit)? = null, complete: (Bundle?, String?) -> Unit) {
        if(!PluginLifetime.alive) { complete(null,"歌词插件已停止"); return }
        ownRequests()
        val id=UUID.randomUUID().toString();val done=AtomicBoolean()
        lateinit var timeout: Runnable
        fun finish(value: Bundle?, error: String?) {
            if(!done.compareAndSet(false,true)) return
            pending.remove(id); main.removeCallbacks(timeout)
            if(value!=null) connected=true
            complete(value,error)
        }
        timeout=Runnable { connected=false;finish(null,"离线翻译扩展未响应，请确认已导入并启用扩展，再重新打开宿主") }
        val receiver=object:ResultReceiver(main) {
            override fun onReceiveResult(code: Int, data: Bundle?) {
                if(done.get()) return
                if(code==1 && data!=null) {
                    connected=true;main.removeCallbacks(timeout)
                    main.postDelayed(timeout,if(operation=="download") 120_000L else 60_000L)
                    if(PluginLifetime.alive && !closing) progress?.invoke(data)
                } else finish(if(code==0) data ?: Bundle() else null,if(code==0) null else data?.getString("error") ?: "离线翻译失败")
            }
        }
        pending[id]={ finish(null,it) }
        main.postDelayed(timeout,2_500L)
        try { context.applicationContext.sendBroadcast(Intent(ACTION).setPackage(context.packageName).apply {
            putExtra("protocol",1);putExtra("operation",operation);putExtra("language",language);putExtra("text",text)
            putExtra("reply",frameworkReceiver(receiver))
        }) } catch(error:Exception) { finish(null,error.message ?: "无法连接翻译扩展") }
    }
    // Parcel records a Parcelable's concrete class. Export the framework receiver,
    // retaining its Binder endpoint, so another plugin never needs our anonymous class.
    internal fun frameworkReceiver(receiver: ResultReceiver): ResultReceiver {
        val parcel=Parcel.obtain()
        return try {
            receiver.writeToParcel(parcel,0)
            parcel.setDataPosition(0)
            ResultReceiver.CREATOR.createFromParcel(parcel)
        } finally { parcel.recycle() }
    }
    fun translate(context: Context, language: String, text: String): String {
        check(Looper.myLooper()!=Looper.getMainLooper()) { "离线翻译必须在后台执行" }
        require(language in languages && text.isNotBlank() && text.length<=4000)
        requireInstalled(context)
        check(translationSlots.tryAcquire(65, TimeUnit.SECONDS)) { "离线翻译队列繁忙" }
        try {
            val latch=CountDownLatch(1);var translated:String?=null;var failure:String?=null
            request(context,"translate",language,text) { value,error -> translated=value?.getString("translation");failure=error;latch.countDown() }
            check(latch.await(65,TimeUnit.SECONDS)) { "离线翻译超时" }
            check(failure==null) { failure.orEmpty() }
            return translated?.takeIf(String::isNotBlank) ?: error("离线翻译未返回译文")
        } finally { translationSlots.release() }
    }
    fun queryModels(context: Context, complete: BiConsumer<Set<String>?, String?>) {
        request(context,"query") { value,error -> if(PluginLifetime.alive && !closing)
            complete.accept(value?.getStringArrayList("models")?.toSet(),error) }
    }
    fun downloadModels(context: Context, language: String, complete: Consumer<String?>) =
        downloadModelsWithProgress(context,language,Consumer {},complete)
    fun downloadModelsWithProgress(context: Context, language: String, progress: Consumer<Bundle>, complete: Consumer<String?>) {
        request(context,"download",language,progress={ progress.accept(it) }) { _,error -> if(PluginLifetime.alive && !closing) complete.accept(error) }
    }
    fun deleteModel(context: Context, language: String, complete: Consumer<String?>) {
        if(language in setOf("en","zh")) { complete.accept("中文为内置必需模型，英语无需下载，均不可删除"); return }
        request(context,"delete",language) { _,error -> if(PluginLifetime.alive && !closing) complete.accept(error) }
    }
    fun openManager(activity: Activity) {
        EmbeddedOfflineModels.find(activity)?.let { com.tcrrry.lyrics.engine.ui.TcrrryLyricsSettingsUi.showOfflineModelManager(activity,it) }
    }
}
