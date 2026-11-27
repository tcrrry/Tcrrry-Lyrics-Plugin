package dev.kifranei.ampp.media

import android.app.Application
import android.content.Context
import android.os.Build
import org.json.JSONObject
import org.luckypray.dexkit.DexKitBridge
import java.io.File
import java.lang.reflect.Modifier
import java.net.JarURLConnection
import java.util.zip.ZipFile

/**
 * Phase 1 DexKit pilot for card-sharing `observerClass` only.
 *
 * JSON profiles stay primary. When the profile observer fails a structural check
 * (the 1611 `commerce.activities.l` → `m` class: progress must instantiate the
 * observer, and the observer must hold/cast progress + result), DexKit resolves a
 * unique replacement. Results are cached by Apple Music versionCode+lastUpdateTime.
 * Ambiguous matches are refused — never silently hook the wrong class.
 *
 * Native `libdexkit.so` is not shipped in the plugin ZIP (SDK layout allows only
 * plugin.json / code.jar / assets/). The so is loaded from the AM++ module APK,
 * which already packages DexKit 2.2.0.
 */
internal object CardSharingDexResolver {
    private const val PREFS = "ampp_media_card_sharing_dex_v1"
    private const val LOG = "card_sharing:"
    private const val DEXKIT_SONAME = "dexkit"
    private const val DEXKIT_FILE = "libdexkit.so"
    private const val EXTRACT_DIR = "ampp-media-dexkit-native"

    private val loadLock = Any()
    @Volatile private var nativeReady = false

    data class Symbols(
        val observerClass: String,
        val observerProgressField: String,
        val source: String,
    )

    sealed class Outcome {
        data class Ok(val symbols: Symbols) : Outcome()
        data class Unsupported(val message: String) : Outcome()
    }

    /**
     * Fail-open: this never throws and never returns Unsupported while the JSON profile's
     * observer passes reflection checks. Buttons/hooks are installed from the profile
     * immediately; DexKit only runs (a) synchronously when the profile observer is unusable,
     * or (b) in the background as an advisory structural check whose result applies next launch.
     */
    fun resolve(application: Application, loader: ClassLoader, card: JSONObject): Outcome {
        val progressName = card.getString("progressClass")
        val resultName = card.getString("resultClass")
        val profileObserver = card.getString("observerClass")
        val profileField = card.getString("observerProgressField")
        val profileSymbols = Symbols(profileObserver, profileField, "profile")
        return try {
            resolveUnsafe(application, loader, progressName, resultName, profileSymbols)
        } catch (error: Throwable) {
            MediaRuntime.log("$LOG resolver failed; failing open to profile observer", error)
            if (shallowValidate(loader, progressName, profileObserver, profileField) == null) {
                Outcome.Ok(profileSymbols.copy(source = "profile-fail-open"))
            } else {
                Outcome.Unsupported("$LOG profile observer $profileObserver unusable and resolver failed: ${error.message}")
            }
        }
    }

    private fun resolveUnsafe(
        application: Application,
        loader: ClassLoader,
        progressName: String,
        resultName: String,
        profile: Symbols,
    ): Outcome {
        val versionKey = versionKey(application)
        val prefs = application.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val profileShallow = shallowValidate(loader, progressName, profile.observerClass, profile.observerProgressField)

        // A cached override exists only after DexKit proved the profile observer structurally wrong.
        if (prefs.getString("key", null) == versionKey) {
            val cachedObserver = prefs.getString("observerClass", null)
            val cachedField = prefs.getString("observerProgressField", null)
            if (cachedObserver != null && cachedField != null) {
                val shallow = shallowValidate(loader, progressName, cachedObserver, cachedField)
                if (shallow == null) {
                    MediaRuntime.log("$LOG using cached DexKit override observer=$cachedObserver field=$cachedField (profile was ${profile.observerClass})")
                    return Outcome.Ok(Symbols(cachedObserver, cachedField, "cache:dexkit"))
                }
                MediaRuntime.log("$LOG cached override failed validation: $shallow")
                prefs.edit().clear().apply()
            }
        }

        if (profileShallow == null) {
            // Profile usable: install now, verify structure off the main thread (advisory only).
            MediaRuntime.log("$LOG profile observer ${profile.observerClass} passes reflection checks; installing from profile")
            advisoryVerify(application, loader, progressName, resultName, profile, prefs, versionKey)
            return Outcome.Ok(profile)
        }

        MediaRuntime.log("$LOG profile observer unusable: $profileShallow; trying DexKit")
        val discovered = runCatching { discoverObserver(application, loader, progressName, resultName) }
            .onFailure { MediaRuntime.log("$LOG DexKit discovery failed", it) }.getOrNull()
        return when (discovered) {
            null -> Outcome.Unsupported("$LOG profile observer unusable ($profileShallow) and DexKit unavailable")
            is Discover.None -> Outcome.Unsupported("$LOG DexKit found no share-progress observer constructed by $progressName")
            is Discover.Ambiguous -> Outcome.Unsupported("$LOG ambiguous observers for $progressName: ${discovered.names.joinToString()}")
            is Discover.Unique -> {
                val field = pickProgressField(loader, discovered.name, progressName, profile.observerProgressField)
                    ?: return Outcome.Unsupported("$LOG resolved observer ${discovered.name} but no usable owner field")
                val symbols = Symbols(discovered.name, field, "dexkit")
                writeCache(prefs, versionKey, symbols)
                MediaRuntime.log("$LOG DexKit resolved observer=${discovered.name} field=$field")
                Outcome.Ok(symbols)
            }
        }
    }

    /** Background structural check; a confirmed, unique mismatch is cached and applies on next launch. */
    private fun advisoryVerify(
        application: Application,
        loader: ClassLoader,
        progressName: String,
        resultName: String,
        profile: Symbols,
        prefs: android.content.SharedPreferences,
        versionKey: String,
    ) {
        if (prefs.getString("verified", null) == versionKey) return
        Thread({
            try {
                val outcome = openBridgeOrNull(application, loader)?.use { bridge ->
                    val constructed = classesConstructedBy(bridge, progressName)
                    if (profile.observerClass in constructed) "ok" else {
                        val hit = discoverObserver(bridge, loader, progressName, resultName)
                        if (hit is Discover.Unique) "mismatch:${hit.name}" else "mismatch-unresolved:$hit"
                    }
                }
                when {
                    outcome == null -> MediaRuntime.log("$LOG advisory structural check skipped (DexKit unavailable); profile observer kept")
                    outcome == "ok" -> {
                        prefs.edit().putString("verified", versionKey).apply()
                        MediaRuntime.log("$LOG advisory structural check passed for ${profile.observerClass}")
                    }
                    outcome.startsWith("mismatch:") -> {
                        val name = outcome.removePrefix("mismatch:")
                        val field = pickProgressField(loader, name, progressName, "")
                        if (field != null && shallowValidate(loader, progressName, name, field) == null) {
                            writeCache(prefs, versionKey, Symbols(name, field, "dexkit"))
                            MediaRuntime.log("$LOG structural MISMATCH: profile ${profile.observerClass} is not constructed by progress; DexKit found $name — applies after Apple Music restart")
                        } else MediaRuntime.log("$LOG structural mismatch but candidate $name unusable; profile kept")
                    }
                    else -> MediaRuntime.log("$LOG structural check inconclusive ($outcome); profile observer kept")
                }
            } catch (error: Throwable) {
                MediaRuntime.log("$LOG advisory structural check failed; profile observer kept", error)
            }
        }, "AM++ card_sharing structure check").apply { isDaemon = true }.start()
    }

    private fun openBridgeOrNull(application: Application, loader: ClassLoader): DexKitBridge? {
        if (!ensureNative(application)) return null
        return runCatching { openBridge(application, loader) }
            .onFailure { MediaRuntime.log("$LOG DexKit bridge unavailable (native/loader mismatch?)", it) }
            .getOrNull()
    }

    private sealed class Discover {
        data class Unique(val name: String) : Discover()
        data class Ambiguous(val names: List<String>) : Discover()
        data object None : Discover()
    }

    /** Exposed for unit tests: keep only a single name. */
    internal fun uniqueOrNull(names: Collection<String>): String? =
        names.distinct().singleOrNull()

    /**
     * Prefer [preferred] when present; else a field typed as [progress]; else the sole
     * non-static Object capture field (Kotlin synthetic lambdas use `b: Object`).
     */
    internal fun pickProgressFieldName(
        fields: List<Pair<String, Class<*>>>,
        progress: Class<*>,
        preferred: String?,
    ): String? {
        if (preferred != null && fields.any { it.first == preferred }) return preferred
        val instance = fields.filter { (name, type) ->
            name != "serialVersionUID" && type != Integer.TYPE && type != java.lang.Integer.TYPE
        }
        instance.firstOrNull { (_, type) -> type == progress }?.let { return it.first }
        // Fragment/base-typed owner fields (e.g. 1607) — never treat raw Object as typed owner.
        instance.firstOrNull { (_, type) ->
            type != Any::class.java && type.name != "java.lang.Object" &&
                (type.isAssignableFrom(progress) || progress.isAssignableFrom(type))
        }?.let { return it.first }
        val objects = instance.filter { (_, type) ->
            type == Any::class.java || type.name == "java.lang.Object"
        }
        return objects.singleOrNull()?.first
    }

    private fun pickProgressField(
        loader: ClassLoader,
        observerName: String,
        progressName: String,
        preferred: String,
    ): String? {
        val observer = runCatching { loader.loadClass(observerName) }.getOrNull() ?: return null
        val progress = runCatching { loader.loadClass(progressName) }.getOrNull() ?: return null
        val fields = observer.declaredFields
            .filter { !Modifier.isStatic(it.modifiers) }
            .map { it.name to it.type }
        return pickProgressFieldName(fields, progress, preferred.takeIf { it.isNotEmpty() })
    }

    private fun shallowValidate(
        loader: ClassLoader,
        progressName: String,
        observerName: String,
        fieldName: String,
    ): String? {
        val progress = runCatching { loader.loadClass(progressName) }.getOrElse {
            return "progressClass missing: $progressName"
        }
        val observer = runCatching { loader.loadClass(observerName) }.getOrElse {
            return "observerClass missing: $observerName"
        }
        runCatching { observer.getDeclaredMethod("onChanged", Any::class.java) }.getOrElse {
            return "observer lacks onChanged(Object): $observerName"
        }
        val field = runCatching { observer.getDeclaredField(fieldName) }.getOrElse {
            return "observerProgressField missing: $observerName.$fieldName"
        }
        if (Modifier.isStatic(field.modifiers)) return "observerProgressField is static: $fieldName"
        val type = field.type
        if (type != progress && type != Any::class.java &&
            !type.isAssignableFrom(progress) && !progress.isAssignableFrom(type)
        ) {
            return "observerProgressField type ${type.name} incompatible with $progressName"
        }
        return null
    }

    private fun discoverObserver(
        application: Application,
        loader: ClassLoader,
        progressName: String,
        resultName: String,
    ): Discover {
        if (!ensureNative(application)) throw IllegalStateException("libdexkit unavailable")
        return openBridge(application, loader).use { discoverObserver(it, loader, progressName, resultName) }
    }

    private fun discoverObserver(
        bridge: DexKitBridge,
        loader: ClassLoader,
        progressName: String,
        resultName: String,
    ): Discover {
        val constructed = classesConstructedBy(bridge, progressName)
        val withOnChanged = constructed.filter { name ->
            hasOnChanged(bridge, name) || runCatching {
                loader.loadClass(name).getDeclaredMethod("onChanged", Any::class.java)
            }.isSuccess
        }.distinct()
        val withResult = withOnChanged.filter { observerUsesResult(bridge, it, resultName) }
        val confirmed = when {
            withResult.isNotEmpty() -> withResult
            else -> withOnChanged
        }
        MediaRuntime.log("$LOG DexKit candidates constructed-by=$progressName onChanged=$withOnChanged resultUse=$withResult -> $confirmed")
        return when (confirmed.size) {
            0 -> Discover.None
            1 -> Discover.Unique(confirmed[0])
            else -> Discover.Ambiguous(confirmed)
        }
    }

    private fun classesConstructedBy(bridge: DexKitBridge, progressName: String): Set<String> {
        val methods = bridge.findMethod {
            matcher {
                name("<init>")
                addCaller { declaredClass(progressName) }
            }
        }
        return methods.map { it.className }.filter {
            it != progressName && !it.startsWith("java.") && !it.startsWith("android.") &&
                !it.startsWith("androidx.lifecycle.MutableLiveData")
        }.toSet()
    }

    private fun hasOnChanged(bridge: DexKitBridge, className: String): Boolean =
        bridge.findMethod {
            matcher {
                declaredClass(className)
                name("onChanged")
                paramCount(1)
            }
        }.isNotEmpty()

    private fun observerUsesResult(bridge: DexKitBridge, observerName: String, resultName: String): Boolean {
        if (bridge.findMethod {
                matcher {
                    declaredClass(observerName)
                    addInvoke { declaredClass(resultName) }
                }
            }.isNotEmpty()
        ) return true
        val data = bridge.getClassData(observerName) ?: return false
        return data.methods.any { method ->
            method.usingFields.any { it.field.typeName == resultName } ||
                method.invokes.any { it.className == resultName }
        }
    }

    private fun openBridge(application: Application, loader: ClassLoader): DexKitBridge {
        runCatching { DexKitBridge.create(loader, true) }.getOrNull()?.let { return it }
        val apks = hostApkPaths(application)
        var last: Throwable? = null
        for (apk in apks) {
            try {
                return DexKitBridge.create(apk)
            } catch (error: Throwable) {
                last = error
            }
        }
        throw last ?: IllegalStateException("no host APK for DexKit")
    }

    private fun hostApkPaths(application: Application): List<String> {
        val info = application.applicationInfo
        return (listOf(info.sourceDir) + (info.splitSourceDirs?.toList() ?: emptyList()))
            .filter { File(it).isFile }
            .distinct()
    }

    private fun versionKey(application: Application): String {
        val info = application.packageManager.getPackageInfo(application.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else {
            @Suppress("DEPRECATION") info.versionCode.toLong()
        }
        return "$code|${info.lastUpdateTime}"
    }

    private fun writeCache(
        prefs: android.content.SharedPreferences,
        versionKey: String,
        symbols: Symbols,
    ) {
        prefs.edit()
            .putString("key", versionKey)
            .putString("observerClass", symbols.observerClass)
            .putString("observerProgressField", symbols.observerProgressField)
            .putString("source", symbols.source)
            .apply()
    }

    fun ensureNative(application: Application): Boolean {
        if (nativeReady) return true
        synchronized(loadLock) {
            if (nativeReady) return true
            val failures = ArrayList<String>()
            if (runCatching { System.loadLibrary(DEXKIT_SONAME) }.isSuccess) {
                nativeReady = true
                return true
            }
            failures += "loadLibrary failed"
            for (apk in moduleApkPaths(application)) {
                for (abi in Build.SUPPORTED_ABIS) {
                    val packed = "$apk!/lib/$abi/$DEXKIT_FILE"
                    if (runCatching { System.load(packed) }.isSuccess) {
                        nativeReady = true
                        MediaRuntime.log("$LOG loaded $DEXKIT_FILE from $packed")
                        return true
                    }
                    val extracted = runCatching {
                        extractAndLoad(application, apk, abi)
                    }.onFailure { failures += "extract $abi: ${it.message}" }.getOrDefault(false)
                    if (extracted) {
                        nativeReady = true
                        return true
                    }
                }
            }
            MediaRuntime.log("$LOG libdexkit.so unavailable: ${failures.joinToString("; ")}")
            return false
        }
    }

    private fun extractAndLoad(application: Application, apkPath: String, abi: String): Boolean {
        val entry = "lib/$abi/$DEXKIT_FILE"
        ZipFile(apkPath).use { zip ->
            val source = zip.getEntry(entry) ?: return false
            val dir = File(application.codeCacheDir, EXTRACT_DIR).also { it.mkdirs() }
            val target = File(dir, "$abi-$DEXKIT_FILE")
            zip.getInputStream(source).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            System.load(target.absolutePath)
            MediaRuntime.log("$LOG extracted $entry → ${target.absolutePath}")
            return true
        }
    }

    /** AM++ module APK paths (plugin ZIP cannot ship .so; host already packages DexKit). */
    private fun moduleApkPaths(application: Application): List<String> {
        val names = setOf("dev.amenhancer.module", "dev.amenhancer.module.debug")
        val fromPm = names.mapNotNull { name ->
            runCatching { application.packageManager.getApplicationInfo(name, 0).sourceDir }.getOrNull()
        }
        if (fromPm.isNotEmpty()) return fromPm.distinct()
        return runCatching { PluginContextPaths.moduleApksFromSdkLoader() }.getOrDefault(emptyList())
    }
}

/** Isolate ClassLoader resource walk so unit tests can skip Android PM. */
internal object PluginContextPaths {
    fun moduleApksFromSdkLoader(): List<String> {
        val packageNames = setOf("dev.amenhancer.module", "dev.amenhancer.module.debug")
        val loader = dev.amenhancer.plugin.api.PluginContext::class.java.classLoader ?: return emptyList()
        return loader.getResources("AndroidManifest.xml").asSequence().mapNotNull { resource ->
            runCatching {
                val connection = resource.openConnection() as? JarURLConnection ?: return@runCatching null
                val file = File(connection.jarFileURL.toURI())
                file.takeIf {
                    it.isFile && (it.name.endsWith(".apk") || packageNames.any { n -> file.path.contains(n) })
                }?.path
            }.getOrNull()
        }.distinct().toList()
    }
}
