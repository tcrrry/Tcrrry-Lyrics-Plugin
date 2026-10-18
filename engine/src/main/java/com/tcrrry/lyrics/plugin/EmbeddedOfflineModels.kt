package com.tcrrry.lyrics.plugin

import android.app.Activity
import android.content.Context
import java.util.function.BiConsumer
import java.util.function.Consumer

internal interface OfflineModelBackend {
    val languages: List<String>
    fun query(complete: (Set<String>?, String?) -> Unit)
    fun download(language: String, complete: (String?) -> Unit)
    fun download(language: String, progress: (android.os.Bundle) -> Unit, complete: (String?) -> Unit) = download(language,complete)
    fun delete(language: String, complete: (String?) -> Unit)
}

/** Optional parent-loader API. Older integrated hosts retain their existing manager. */
internal object EmbeddedOfflineModels {
    fun find(activity: Activity): OfflineModelBackend? = runCatching {
        val type = OfflineTranslationClient::class.java
        val query = type.getMethod("queryModels", Context::class.java, BiConsumer::class.java)
        val download = type.getMethod("downloadModels", Context::class.java, String::class.java, Consumer::class.java)
        val delete = type.getMethod("deleteModel", Context::class.java, String::class.java, Consumer::class.java)
        val receiver = OfflineTranslationClient
        object : OfflineModelBackend {
            override val languages = OfflineTranslationClient.languages
            override fun query(complete: (Set<String>?, String?) -> Unit) {
                query.invoke(receiver, activity, BiConsumer<Set<String>?, String?> { models, error -> complete(models, error) })
            }
            override fun download(language: String, complete: (String?) -> Unit) {
                download.invoke(receiver, activity, language, Consumer<String?> { complete(it) })
            }
            override fun download(language: String, progress: (android.os.Bundle) -> Unit, complete: (String?) -> Unit) {
                val richer=runCatching { type.getMethod("downloadModelsWithProgress",Context::class.java,String::class.java,Consumer::class.java,Consumer::class.java) }.getOrNull()
                if(richer==null) download(language,complete)
                else richer.invoke(receiver,activity,language,Consumer<android.os.Bundle> { progress(it) },Consumer<String?> { complete(it) })
            }
            override fun delete(language: String, complete: (String?) -> Unit) {
                delete.invoke(receiver, activity, language, Consumer<String?> { complete(it) })
            }
        }
    }.getOrNull()
}
