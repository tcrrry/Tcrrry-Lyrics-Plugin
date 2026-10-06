package com.tcrrry.lyrics.offline
import android.app.Activity
import android.os.Bundle
import android.widget.*
import com.google.mlkit.nl.translate.*
import com.google.mlkit.common.model.*
import java.util.Locale

class ModelActivity:Activity() {
    private lateinit var body:LinearLayout
    private lateinit var status:TextView
    private var closed=false
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        ClientAccess.grant(this)
        val scroll=ScrollView(this);body=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(28,28,28,28) };scroll.addView(body);setContentView(scroll)
        body.addView(TextView(this).apply { text="Tcrrry 离线翻译";textSize=22f })
        body.addView(TextView(this).apply { text="仅为歌词插件提供 Google ML Kit 离线机翻。平台译文、API 翻译和发音不需要本应用。先下载原语言与中文模型，之后可离线翻译。" })
        status=TextView(this);body.addView(status)
        body.addView(Button(this).apply { text="验证英译中";setOnClickListener { test() } })
        refresh()
    }
    private fun refresh() {
        if(closed)return
        RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel::class.java).addOnSuccessListener { installed ->
            if(closed)return@addOnSuccessListener
            while(body.childCount>4)body.removeViewAt(4)
            val ready=installed.map { it.language }.toSet()
            val query=EditText(this).apply { hint="搜索语言：日语、韩语、法语…" };body.addView(query)
            val list=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL };body.addView(list)
            fun render(value:String) {
                list.removeAllViews()
                val codes=listOf("zh","ja","ko","en")+TranslateLanguage.getAllLanguages().filterNot { it in setOf("zh","ja","ko","en") }.sorted()
                codes.filter { value.isNotBlank() && (it.contains(value,true)||Locale.forLanguageTag(it).getDisplayLanguage(Locale.SIMPLIFIED_CHINESE).contains(value,true)) || value.isBlank() && it in setOf("zh","ja","ko","en") }.forEach { code ->
                    val name=Locale.forLanguageTag(code).getDisplayLanguage(Locale.SIMPLIFIED_CHINESE)
                    list.addView(Button(this).apply {
                        text="$name · $code · ${if(code=="en" || code in ready) "已就绪" else "下载"}"
                        setOnClickListener {
                            if(code=="en")test()
                            else if(code in ready)android.app.AlertDialog.Builder(this@ModelActivity).setMessage("删除${name}语言包？").setPositiveButton("删除") { _,_ ->
                                RemoteModelManager.getInstance().deleteDownloadedModel(TranslateRemoteModel.Builder(code).build()).addOnCompleteListener { refresh() }
                            }.setNegativeButton("取消",null).show()
                            else android.app.AlertDialog.Builder(this@ModelActivity).setMessage("下载${name}与中文模型，需访问 Google 服务，下载后可离线使用。")
                                .setPositiveButton("当前网络") { _,_ -> download(code,false) }.setNeutralButton("仅 Wi-Fi") { _,_ -> download(code,true) }.setNegativeButton("取消",null).show()
                        }
                    })
                }
            }
            query.addTextChangedListener(object:android.text.TextWatcher {
                override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
                override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){render(s.toString().trim())}
                override fun afterTextChanged(s:android.text.Editable?){}
            });render("")
        }.addOnFailureListener { if(!closed)status.text="读取语言包失败：${it.message}" }
    }
    private fun download(code:String,wifi:Boolean) {
        status.text="正在下载，完成后将验证模型…"
        val translator=Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(if(code=="zh")"en" else code).setTargetLanguage("zh").build())
        val conditions=DownloadConditions.Builder().apply { if(wifi) requireWifi() }.build()
        translator.downloadModelIfNeeded(conditions).continueWithTask { task ->
            if(!task.isSuccessful)throw task.exception?:IllegalStateException("下载失败")
            translator.translate(when(code){"ja"->"こんにちは";"ko"->"안녕하세요";else->"Hello, world"})
        }.addOnCompleteListener { task ->
            translator.close();if(!closed){status.text=if(task.isSuccessful)"模型已就绪：${task.result}" else "下载失败：${task.exception?.message}";refresh()}
        }
    }
    private fun test() {
        status.text="正在验证…"
        val translator=Translation.getClient(TranslatorOptions.Builder().setSourceLanguage("en").setTargetLanguage("zh").build())
        translator.translate("Hello, world").addOnCompleteListener { task ->translator.close();if(!closed)status.text=if(task.isSuccessful)"离线翻译可用：${task.result}" else "验证失败：${task.exception?.message}；请先下载中文模型" }
    }
    override fun onDestroy(){closed=true;super.onDestroy()}
}
