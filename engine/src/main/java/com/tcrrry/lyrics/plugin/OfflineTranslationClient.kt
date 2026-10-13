package com.tcrrry.lyrics.plugin
import android.content.Context
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
object OfflineTranslationClient {
    val languages = "af ar be bg bn ca cs cy da de el en eo es et fa fi fr ga gl gu he hi hr ht hu id is it ja ka kn ko lt lv mk mr ms mt nl no pl pt ro ru sk sl sq sv sw ta te th tl tr uk ur vi zh".split(" ")

    const val PACKAGE="com.tcrrry.lyrics.offline"
    fun statusText(context: Context) = if (installed(context)) "伴随应用已安装" else "尚未安装伴随应用"
    fun installed(context: Context)=runCatching {
        val ready = context.contentResolver.call(Uri.parse("content://$PACKAGE.models"),"status",null,null)?.getInt("protocol")==1
        if (ready) runCatching { context.contentResolver.takePersistableUriPermission(Uri.parse("content://$PACKAGE.models/access"),Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        ready
    }.getOrDefault(false)
    fun requireInstalled(context: Context) { check(installed(context)) { "请安装并打开 Tcrrry 离线翻译伴随 APK，授权连接并下载语言包" } }
    fun translate(context: Context, language: String, text: String): String {
        requireInstalled(context)
        check(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper()) { "离线翻译必须在后台执行" }
        val result=context.contentResolver.call(Uri.parse("content://$PACKAGE.models"),"translate",null,Bundle().apply {
            putString("language",language);putString("text",text)
        }) ?: error("离线翻译未响应")
        result.getString("error")?.let { error(it) }
        return result.getString("translation")?.takeIf(String::isNotBlank) ?: error("离线翻译没有返回译文")
    }
    fun openManager(activity: Activity) {
        try { activity.startActivity(Intent().setClassName(PACKAGE,"com.tcrrry.lyrics.offline.ModelActivity")) } catch (_:android.content.ActivityNotFoundException) { android.widget.Toast.makeText(activity,"请先安装下载页中的 Tcrrry 离线翻译 APK",android.widget.Toast.LENGTH_LONG).show() }
    }
}
