package com.tcrrry.lyrics.engine.hook

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import com.tcrrry.lyrics.engine.hook.ModernMethodHook as XC_MethodHook
import com.tcrrry.lyrics.engine.ModuleConstants
import com.tcrrry.lyrics.engine.R
import com.tcrrry.lyrics.engine.config.TargetConfigClient
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

internal data class AlphaGradientEdgeFieldProfile(
    val vertical: List<String>,
    val horizontal: List<String>,
    val requiredIntegers: List<String>,
)

/** Resolves the verified Apple Music AlphaGradientFrameLayout obfuscation variants. */
internal object AlphaGradientEdgeFieldProfiles {
    private val profiles by lazy {
        com.tcrrry.lyrics.host.applemusic.AppleMusicHostProfiles.all.flatMap { profile ->
            val array = profile.document.getJSONObject("layoutVariants").getJSONArray("gradientEdges")
            List(array.length()) { index -> array.getJSONObject(index).let {
                AlphaGradientEdgeFieldProfile(it.getJSONArray("vertical").profileStrings(),
                    it.getJSONArray("horizontal").profileStrings(),it.getJSONArray("requiredIntegers").profileStrings())
            } }
        }.distinct()
    }

    fun resolve(type: Class<*>): AlphaGradientEdgeFieldProfile? = resolve(
        type.declaredFields.associate { field -> field.name to field.type },
    )

    fun resolve(type: Class<*>, build: TargetBuild): AlphaGradientEdgeFieldProfile? =
        resolve(type.declaredFields.associate { it.name to it.type }, build)

    internal fun resolve(fields: Map<String, Class<*>>, build: TargetBuild): AlphaGradientEdgeFieldProfile? {
        val profile = com.tcrrry.lyrics.host.applemusic.AppleMusicHostProfiles.find(
            build.packageName, build.versionName, build.versionCode)
        if (profile?.family != "fragment-content") return resolve(fields)
        val variants = profile.document.getJSONObject("layoutVariants").getJSONArray("gradientEdges")
        return List(variants.length()) { index -> variants.getJSONObject(index).let {
            AlphaGradientEdgeFieldProfile(it.getJSONArray("vertical").profileStrings(),
                it.getJSONArray("horizontal").profileStrings(), it.getJSONArray("requiredIntegers").profileStrings())
        } }.singleOrNull { candidate ->
            (candidate.vertical + candidate.horizontal).all { fields[it] == Boolean::class.javaPrimitiveType } &&
                candidate.requiredIntegers.all { fields[it] == Int::class.javaPrimitiveType }
        }
    }

    internal fun resolve(fields: Map<String, Class<*>>): AlphaGradientEdgeFieldProfile? =
        profiles.singleOrNull { profile ->
            (profile.vertical + profile.horizontal).all { fieldName ->
                fields[fieldName] == Boolean::class.javaPrimitiveType
            } && profile.requiredIntegers.all { fieldName ->
                fields[fieldName] == Int::class.javaPrimitiveType
            }
        }
}

internal data class LyricsLayoutFieldProfile(
    val binding: String,
    val container: String,
    val recycler: String,
    val gradients: String,
    val synchronizedMetrics: List<String>,
)

/** Resolves PlayerLyricsViewFragment fields in the verified Apple Music builds. */
internal object LyricsLayoutFieldProfiles {
    private val profiles by lazy {
        com.tcrrry.lyrics.host.applemusic.AppleMusicHostProfiles.all.flatMap { profile ->
            val array = profile.document.getJSONObject("layoutVariants").getJSONArray("lyricsFields")
            List(array.length()) { index -> array.getJSONObject(index).let {
                LyricsLayoutFieldProfile(it.getString("binding"),it.getString("container"),it.getString("recycler"),
                    it.getString("gradients"),it.getJSONArray("synchronizedMetrics").profileStrings())
            } }
        }.distinct()
    }

    fun resolve(fragmentType: Class<*>): LyricsLayoutFieldProfile? = resolve(fragmentType, profiles)

    fun resolve(fragmentType: Class<*>, build: TargetBuild): LyricsLayoutFieldProfile? {
        val profile = com.tcrrry.lyrics.host.applemusic.AppleMusicHostProfiles.find(
            build.packageName, build.versionName, build.versionCode)
        if (profile?.family != "fragment-content") return resolve(fragmentType)
        val variants = profile.document.getJSONObject("layoutVariants").getJSONArray("lyricsFields")
        return resolve(fragmentType, List(variants.length()) { index -> variants.getJSONObject(index).let {
            LyricsLayoutFieldProfile(it.getString("binding"), it.getString("container"), it.getString("recycler"),
                it.getString("gradients"), it.getJSONArray("synchronizedMetrics").profileStrings())
        } })
    }

    private fun resolve(fragmentType: Class<*>, candidates: List<LyricsLayoutFieldProfile>): LyricsLayoutFieldProfile? = candidates.singleOrNull { profile ->
        val bindingType = dualPaneField(fragmentType, profile.binding)?.type
            ?: return@singleOrNull false
        val bindingContractPresent = listOf(
            profile.container,
            profile.recycler,
            profile.gradients,
        ).all { fieldName -> dualPaneField(bindingType, fieldName) != null }
        bindingContractPresent && profile.synchronizedMetrics.all { fieldName ->
            val metricsType = dualPaneField(fragmentType, fieldName)?.type
                ?: return@all false
            listOf("a", "b", "c").all { metricName ->
                dualPaneField(metricsType, metricName)?.type == Int::class.javaPrimitiveType
            }
        }
    }
}


private fun org.json.JSONArray.profileStrings(): List<String> = List(length()) { getString(it) }
