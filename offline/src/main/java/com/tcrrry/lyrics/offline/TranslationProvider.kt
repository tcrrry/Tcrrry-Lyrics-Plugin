package com.tcrrry.lyrics.offline

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.common.model.RemoteModelManager
import java.util.concurrent.TimeUnit
import java.util.concurrent.Semaphore

/** Only known Apple Music clients may use the model service; it never exposes files or secrets. */
class TranslationProvider : ContentProvider() {
    private val slots=Semaphore(2)
    override fun onCreate()=true
    override fun call(method:String,arg:String?,extras:Bundle?):Bundle {
        val result=Bundle()
        try {
            val app=requireNotNull(context)
            val uid=Binder.getCallingUid()
            val packages=app.packageManager.getPackagesForUid(uid).orEmpty().toSet()
            require(uid==android.os.Process.myUid() || packages.any { it in setOf("com.apple.android.music","com.tcrrry.ampplyrics.coexist") }) { "不支持的调用应用" }
            if(method=="status") return Bundle().apply { putInt("protocol",1) }
            require(method=="translate") { "不支持的请求" }
            val language=extras?.getString("language")?.takeIf { it.matches(Regex("[a-z]{2,3}")) } ?: error("缺少原语言")
            val text=extras.getString("text")?.takeIf { it.isNotBlank() && it.length<=4000 } ?: error("无效文本")
            check(slots.tryAcquire()) { "翻译正在忙，请稍后重试" }
            try {
                val installed=Tasks.await(RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel::class.java),15,TimeUnit.SECONDS).map { it.language }.toSet()
                require(installed.containsAll(setOf(language,"zh").filterNot { it=="en" })) { "请在离线翻译应用下载原语言和中文模型" }
                val translator=Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(language).setTargetLanguage("zh").build())
                try { result.putString("translation",Tasks.await(translator.translate(text),30,TimeUnit.SECONDS)) }
                finally { translator.close() }
            } finally { slots.release() }
        } catch(error:Exception) { result.putString("error",error.message.orEmpty().take(300)) }
        return result
    }
    override fun query(uri:Uri,projection:Array<out String>?,selection:String?,selectionArgs:Array<out String>?,sortOrder:String?):Cursor?=null
    override fun getType(uri:Uri):String?=null
    override fun insert(uri:Uri,values:ContentValues?):Uri?=null
    override fun delete(uri:Uri,selection:String?,selectionArgs:Array<out String>?)=0
    override fun update(uri:Uri,values:ContentValues?,selection:String?,selectionArgs:Array<out String>?)=0
}
