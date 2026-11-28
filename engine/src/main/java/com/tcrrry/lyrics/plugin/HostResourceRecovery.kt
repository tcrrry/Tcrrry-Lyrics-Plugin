package com.tcrrry.lyrics.plugin

import android.app.Application
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.content.res.TypedArray
import android.util.DisplayMetrics
import dev.amenhancer.plugin.api.PluginCall
import dev.amenhancer.plugin.api.PluginContext
import dev.amenhancer.plugin.api.PluginHook
import dev.amenhancer.plugin.api.PluginRegistration
import java.util.Collections
import java.util.IdentityHashMap

/** Recover a type-corrupted host lookup without mutating global host resources. */
internal object HostResourceRecoveryPolicy {
    private val ALLOWED_PACKAGES = setOf(
        "com.apple.android.music",
        "com.tcrrry.ampplyrics.coexist",
    )
    private val TYPE_CORRUPTION_REGEX = Regex("(?i)type\\s*(?:=|#)\\s*0x[0-9a-f]+")

    fun supports(versionCode: Long, versionName: String): Boolean = when (versionCode) {
        1609L, 1613L -> versionName == "7.0.0-beta"
        1614L -> versionName == "7.0.0"
        1622L -> versionName == "7.0.1"
        1626L -> versionName == "7.0.3"
        else -> false
    }

    fun accepts(
        packageName: String?,
        declaredType: String?,
        expectedType: String,
        error: Throwable?,
    ): Boolean {
        if (packageName !in ALLOWED_PACKAGES || declaredType != expectedType || error == null) return false
        var cause: Throwable? = error
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        while (cause != null && seen.add(cause)) {
            val message = cause.message.orEmpty()
            if ((message.contains("Can't convert", ignoreCase = true) ||
                    message.contains("is not valid", ignoreCase = true)) &&
                TYPE_CORRUPTION_REGEX.containsMatchIn(message)
            ) {
                return true
            }
            cause = cause.cause
        }
        return false
    }
}

/** Private isolated AssetManager fallback when host Resources/Theme cache is type-corrupted. */
internal object HostResourceRecovery {
    private val ALLOWED_PACKAGES = setOf(
        "com.apple.android.music",
        "com.tcrrry.ampplyrics.coexist",
    )
    private val recovering = ThreadLocal<Boolean>()
    private val lock = Any()
    private val reported = mutableSetOf<String>()
    private val registrations = ArrayList<PluginRegistration>()

    private data class Snapshot(
        val configuration: Configuration,
        val resources: Resources,
        val theme: Resources.Theme,
    )

    private var installed = false
    private var snapshot: Snapshot? = null

    fun install(context: PluginContext) {
        if (context.hostPackageName !in ALLOWED_PACKAGES ||
            !HostResourceRecoveryPolicy.supports(context.hostVersionCode, context.hostVersionName)
        ) {
            return
        }
        val application = context.application
        synchronized(lock) {
            if (installed) return
            installed = true
        }

        val registered = ArrayList<PluginRegistration>()
        val specs = listOf(
            Triple("getColor", "color", arrayOf(Int::class.javaPrimitiveType!!, Resources.Theme::class.java)),
            Triple("getColorStateList", "color", arrayOf(Int::class.javaPrimitiveType!!, Resources.Theme::class.java)),
            Triple("getBoolean", "bool", arrayOf(Int::class.javaPrimitiveType!!)),
            Triple(
                "getDrawableForDensity",
                "drawable",
                arrayOf(Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Resources.Theme::class.java),
            ),
        )
        for ((name, kind, parameters) in specs) {
            runCatching {
                val method = Resources::class.java.getDeclaredMethod(name, *parameters)
                registered += context.hooks.hook(method, false, object : PluginHook {
                    @Suppress("DEPRECATION")
                    override fun after(call: PluginCall) {
                        if (recovering.get() == true || call.throwable == null) return
                        val resources = call.receiver as? Resources ?: return
                        val id = call.arguments.firstOrNull() as? Int ?: return
                        val owner = runCatching { resources.getResourcePackageName(id) }.getOrNull()
                        val type = runCatching { resources.getResourceTypeName(id) }.getOrNull()
                        if (!HostResourceRecoveryPolicy.accepts(owner, type, kind, call.throwable)) return
                        val originalError = call.throwable
                        recovering.set(true)
                        try {
                            synchronized(lock) {
                                val clean = resourcesFor(application, resources)
                                val value: Any? = when (name) {
                                    "getColor" -> clean.resources.getColor(id, clean.theme)
                                    "getColorStateList" -> clean.resources.getColorStateList(id, clean.theme)
                                    "getBoolean" -> clean.resources.getBoolean(id)
                                    else -> clean.resources.getDrawableForDensity(
                                        id,
                                        call.arguments[1] as Int,
                                        clean.theme,
                                    )
                                }
                                call.returnResult(requireNotNull(value) { "Clean resource lookup returned null" })
                                val key = "$name:${id.toString(16)}:${clean.configuration.uiMode}"
                                if (reported.size < 32 && reported.add(key)) {
                                    val msg = "host resource recovered: $name ${runCatching { resources.getResourceName(id) }.getOrDefault(id.toString(16))} " +
                                        "id=0x${id.toString(16)} uiMode=${clean.configuration.uiMode}"
                                    context.log(msg, originalError)
                                    PluginDiagnosticLog.record(msg)
                                }
                            }
                        } catch (error: Throwable) {
                            context.log("host resource recovery failed for 0x${id.toString(16)}", error)
                        } finally {
                            recovering.remove()
                        }
                    }
                })
            }
        }

        runCatching {
            val typedColor = TypedArray::class.java.getDeclaredMethod(
                "getColor",
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
            )
            registered += context.hooks.hook(typedColor, false, object : PluginHook {
                override fun after(call: PluginCall) {
                    recoverTypedArrayColor(
                        context = context,
                        application = application,
                        call = call,
                        label = "typedColor",
                        readValue = { clean, id -> clean.resources.getColor(id, clean.theme) },
                    )
                }
            })
        }

        runCatching {
            val typedCsl = TypedArray::class.java.getDeclaredMethod(
                "getColorStateList",
                Int::class.javaPrimitiveType!!,
            )
            registered += context.hooks.hook(typedCsl, false, object : PluginHook {
                override fun after(call: PluginCall) {
                    recoverTypedArrayColor(
                        context = context,
                        application = application,
                        call = call,
                        label = "typedColorStateList",
                        readValue = { clean, id -> clean.resources.getColorStateList(id, clean.theme) },
                    )
                }
            })
        }

        synchronized(lock) {
            registrations.addAll(registered)
        }
        context.onClose {
            val toClose: List<PluginRegistration>
            synchronized(lock) {
                toClose = registrations.toList()
                registrations.clear()
                reported.clear()
                snapshot = null
                installed = false
            }
            toClose.forEach { reg -> runCatching { reg.close() } }
        }
        val initMsg = "host resource type recovery installed (${registered.size} hooks)"
        context.log(initMsg, null)
        PluginDiagnosticLog.record(initMsg)
    }

    private fun recoverTypedArrayColor(
        context: PluginContext,
        application: Application,
        call: PluginCall,
        label: String,
        readValue: (Snapshot, Int) -> Any,
    ) {
        if (recovering.get() == true || call.throwable == null) return
        val array = call.receiver as? TypedArray ?: return
        val index = call.arguments.firstOrNull() as? Int ?: return
        val id = runCatching { array.getResourceId(index, 0) }.getOrDefault(0)
        if (id == 0) return
        val source = array.resources
        val owner = runCatching { source.getResourcePackageName(id) }.getOrNull()
        val type = runCatching { source.getResourceTypeName(id) }.getOrNull()
        if (!HostResourceRecoveryPolicy.accepts(owner, type, "color", call.throwable)) return
        val originalError = call.throwable
        recovering.set(true)
        try {
            synchronized(lock) {
                val clean = resourcesFor(application, source)
                call.returnResult(readValue(clean, id))
                val key = "$label:${id.toString(16)}:${clean.configuration.uiMode}"
                if (reported.size < 32 && reported.add(key)) {
                    val msg = "host $label recovered: ${runCatching { source.getResourceName(id) }.getOrDefault(id.toString(16))}"
                    context.log(msg, originalError)
                    PluginDiagnosticLog.record(msg)
                }
            }
        } catch (error: Throwable) {
            context.log("host $label recovery failed for 0x${id.toString(16)}", error)
        } finally {
            recovering.remove()
        }
    }

    @Suppress("DEPRECATION")
    private fun resourcesFor(application: Application, source: Resources): Snapshot {
        val config = Configuration(source.configuration)
        snapshot?.takeIf { it.configuration == config }?.let { return it }
        val info = application.applicationInfo
        val paths = listOfNotNull(info.sourceDir) + info.splitSourceDirs.orEmpty()
        require(paths.isNotEmpty()) { "Host APK paths unavailable" }
        val assets = AssetManager::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        val add = AssetManager::class.java.getDeclaredMethod("addAssetPath", String::class.java)
        try {
            paths.distinct().forEach { path ->
                require((add.invoke(assets, path) as Int) != 0) { "Host asset path unavailable" }
            }
            val metrics = DisplayMetrics().apply { setTo(source.displayMetrics) }
            val clean = Resources(assets, metrics, config)
            val theme = clean.newTheme().apply {
                val dark = config.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
                applyStyle(if (dark) android.R.style.Theme_Material else android.R.style.Theme_Material_Light, true)
                val style = info.theme
                if (style != 0 && runCatching {
                        clean.getResourcePackageName(style) == info.packageName &&
                            clean.getResourceTypeName(style) == "style"
                    }.getOrDefault(false)
                ) {
                    applyStyle(style, true)
                }
            }
            return Snapshot(config, clean, theme).also { snapshot = it }
        } catch (error: Throwable) {
            assets.close()
            throw error
        }
    }
}
