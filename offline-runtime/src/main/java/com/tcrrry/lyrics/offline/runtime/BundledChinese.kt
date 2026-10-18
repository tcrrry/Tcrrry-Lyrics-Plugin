package com.tcrrry.lyrics.offline.runtime

import android.content.Context
import com.google.mlkit.common.sdkinternal.MlKitContext
import com.google.mlkit.common.sdkinternal.ModelType
import com.google.mlkit.common.sdkinternal.SharedPrefManager
import com.google.mlkit.common.sdkinternal.model.ModelFileHelper
import com.google.mlkit.nl.translate.TranslateRemoteModel
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Exact 17.0.3/r29 model archive, pinned to ML Kit's own metadata SHA-256. */
internal object BundledChinese {
    const val SHA256 = "ab077c810b001d16667877d9fcda565a206718128a5163428ab5b6ace08936c4"
    internal fun installFiles(archive: File, target: File) {
        val marker = File(target,".bundled-sha256")
        if (marker.isFile && marker.readText() == SHA256 && File(target,"translate_enzh/processor_spec.pb").isFile) return
        val digest=MessageDigest.getInstance("SHA-256")
        archive.inputStream().use { input -> val b=ByteArray(65536); while(true) {
            val n=input.read(b); if(n<0) break; digest.update(b,0,n)
        } }
        check(digest.digest().joinToString("") { "%02x".format(it) } == SHA256) { "中文模型校验失败" }
        check(target.mkdirs() || target.isDirectory)
        var expanded=0L
        val paths=hashSetOf<String>()
        ZipFile(archive).use { zip ->
            val entries=zip.entries().asSequence().toList(); check(entries.size<=256)
            for(entry in entries) {
                check(entry.name.startsWith("dict.en_zh_25/"))
                val relative=entry.name.removePrefix("dict.en_zh_25/")
                check(relative.isNotBlank() && relative.split('/').none { it==".." || it=="." } && !relative.contains('\\'))
                check(paths.add(relative))
                val file=File(target,relative)
                check(file.canonicalPath.startsWith(target.canonicalPath+File.separator))
                if(entry.isDirectory) { file.mkdirs(); continue }
                check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
                zip.getInputStream(entry).use { source -> file.outputStream().use { output ->
                    val b=ByteArray(65536); while(true) {
                        val n=source.read(b); if(n<0) break; expanded+=n;check(expanded<=64L*1024*1024);output.write(b,0,n)
                    }
                } }
            }
        }
        check(File(target,"translate_enzh/processor_spec.pb").isFile && File(target,"translate_zhen/processor_spec.pb").isFile)
        marker.writeText(SHA256)
    }
    fun install(archive: File) {
        val mlkit=MlKitContext.getInstance()
        val model=TranslateRemoteModel.Builder("zh").build()
        val target=mlkit.get(ModelFileHelper::class.java).getModelDir(model.modelNameForBackend,ModelType.TRANSLATE)
        installFiles(archive,target)
        SharedPrefManager.getInstance(mlkit).setLatestModelHash(model,SHA256)
    }
}
