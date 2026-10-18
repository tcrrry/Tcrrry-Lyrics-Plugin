package com.tcrrry.lyrics.offline.runtime

import android.content.Context
import android.os.Looper
import android.os.Bundle
import android.os.Handler
import android.app.DownloadManager
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.internal.CommonComponentRegistrar
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.common.sdkinternal.MlKitContext
import com.google.mlkit.nl.translate.NaturalLanguageTranslateRegistrar
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.function.BiConsumer
import java.util.function.Consumer

/** Loaded from the plugin payload. No Android component registration or companion IPC. */
class PluginTranslationRuntime(base: Context, data: File, payload: File, chinese: File) : AutoCloseable {
    private val slots = Semaphore(2)
    private val translators = ConcurrentHashMap.newKeySet<Translator>()
    @Volatile private var closed = false
    private val context = RuntimeContext(base, data, payload)
    private val main=Handler(Looper.getMainLooper())
    private val polls=ConcurrentHashMap.newKeySet<Runnable>()
    init {
        check(context.resources.getResourceEntryName(com.google.mlkit.nl.translate.R.raw.translate_models_metadata) == "translate_models_metadata")
        // Resolve the real JNI library through this payload's class loader before ML Kit starts.
        System.loadLibrary("translate_jni")
        MlKitContext.initializeIfNeeded(context, listOf(CommonComponentRegistrar(), NaturalLanguageTranslateRegistrar()))
        BundledChinese.install(chinese)
    }
    private fun active() = check(!closed) { "离线翻译已停止" }
    private fun valid(language: String) = require(language in TranslateLanguage.getAllLanguages()) { "不支持的语言" }
    private fun detail(error: Exception?): String {
        android.util.Log.e("PureOffline", "Model operation failed", error)
        var cause: Throwable? = error
        repeat(8) { cause?.cause?.let { cause = it } }
        return cause?.let { "${it.javaClass.simpleName}: ${it.message}" }?.take(240) ?: "离线引擎未响应"
    }
    fun translate(language: String, text: String): String {
        active(); valid(language)
        check(Looper.myLooper() != Looper.getMainLooper()) { "离线翻译必须在后台执行" }
        require(text.isNotBlank() && text.length <= 4000)
        check(slots.tryAcquire()) { "翻译正在忙，请稍后重试" }
        try {
            val ready = Tasks.await(RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel::class.java), 15, TimeUnit.SECONDS).map { it.language }.toSet()
            require(ready.containsAll(setOf(language, "zh") - "en")) { "请先下载所需的原语言模型；中文模型已内置" }
            val translator = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(language).setTargetLanguage("zh").build())
            try {
                synchronized(translators) { active(); translators.add(translator) }
                return Tasks.await(translator.translate(text), 30, TimeUnit.SECONDS).also { active() }
            }
            finally { translators.remove(translator); translator.close() }
        } finally { slots.release() }
    }
    fun queryModels(complete: BiConsumer<Set<String>?, String?>) {
        active()
        RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel::class.java).addOnCompleteListener {
            if (!closed) {
                if (it.isSuccessful) complete.accept(it.result.map { model -> model.language }.toSet(), null)
                else complete.accept(null, detail(it.exception))
            }
        }
    }
    fun downloadModels(language: String, complete: Consumer<String?>) {
        downloadModelsWithProgress(language,Consumer {},complete)
    }
    fun downloadModelsWithProgress(language: String, progress: Consumer<Bundle>, complete: Consumer<String?>) {
        active(); valid(language)
        if(language in setOf("en","zh")) { complete.accept(null);return }
        val manager = RemoteModelManager.getInstance()
        val model=TranslateRemoteModel.Builder(language).build()
        val task=manager.download(model,DownloadConditions.Builder().build())
        val poll=object:Runnable {
            override fun run() {
                if(closed || task.isComplete) { polls.remove(this); return }
                val state=Bundle().apply { putString("message","正在连接下载服务，请确认已开启代理…");putInt("percent",-1) }
                runCatching {
                    val id=com.google.mlkit.common.sdkinternal.SharedPrefManager.getInstance(MlKitContext.getInstance()).getDownloadingModelId(model)
                    val downloads=context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
                    if(id!=null) downloads?.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
                        if(cursor.moveToFirst()) {
                            val total=cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                            val done=cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                            val status=cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                            val percent=if(total>0) ((done*100)/total).coerceIn(0,100).toInt() else -1
                            state.putInt("percent",percent)
                            state.putString("message",when(status) {
                                DownloadManager.STATUS_PAUSED -> "等待网络恢复，请检查代理…"
                                DownloadManager.STATUS_SUCCESSFUL -> "下载完成，正在校验并安装语言包…"
                                DownloadManager.STATUS_FAILED -> "下载连接失败，正在尝试备用下载地址…"
                                else -> if(percent>=0) "正在下载：$percent%" else "正在下载语言包…"
                            })
                        }
                    }
                }
                progress.accept(state);main.postDelayed(this,800)
            }
        }
        polls.add(poll); main.post(poll)
        task.addOnCompleteListener {
            main.removeCallbacks(poll);polls.remove(poll)
            if(!closed) complete.accept(if(it.isSuccessful) null else "下载失败，请检查代理后重试。${detail(it.exception)}")
        }
    }
    fun deleteModel(language: String, complete: Consumer<String?>) {
        active(); valid(language); require(language !in setOf("en","zh")) { "中文内置必需模型不可删除" }
        RemoteModelManager.getInstance().deleteDownloadedModel(TranslateRemoteModel.Builder(language).build())
            .addOnCompleteListener { if (!closed) complete.accept(if (it.isSuccessful) null else detail(it.exception)) }
    }
    override fun close() {
        synchronized(translators) {
            if (closed) return
            closed = true
            polls.toList().forEach { main.removeCallbacks(it) };polls.clear()
            translators.toList().forEach { it.close() }
            translators.clear()
        }
    }
}
