package dev.kifranei.ampp.media

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tester-facing diagnostics. Every line starts with `media-diag:` and goes to the AM++ plugin log
 * (LSPosed), logcat tag `AMPP-media` and `<plugin data>/media-diag.log` (bounded). A line names the
 * capability, the host member or profile contract and the host build, so a shared log pinpoints the
 * broken hook. Diagnostics never throw into the caller.
 */
internal object MediaDiag {
    const val TAG = "AMPP-media"
    const val FILE = "media-diag.log"
    private const val MAX_FILE_BYTES = 512 * 1024L
    private const val MAX_REPEATS = 3
    private val repeats = ConcurrentHashMap<String, AtomicInteger>()
    @Volatile var file: File? = null
    @Volatile var build: String = "?"
    /** Capability currently installing; hooks registered meanwhile are attributed to it. */
    @Volatile var capability: String? = null

    fun start(dataDirectory: File?, hostBuild: String, profile: String, plugin: String) {
        build = hostBuild
        file = runCatching { dataDirectory?.let { File(it, FILE) } }.getOrNull()
        runCatching { file?.takeIf { it.length() > MAX_FILE_BYTES }?.delete() }
        event("startup", "host=$hostBuild profile=$profile plugin=$plugin", key = null)
    }

    /** [key] deduplicates repeated failures (same key logs at most [MAX_REPEATS] times); null always logs. */
    fun event(area: String, message: String, error: Throwable? = null, key: String? = "$area|$message") {
        if (key != null && repeats.getOrPut(key) { AtomicInteger() }.incrementAndGet() > MAX_REPEATS) return
        val line = "media-diag: [$area] $message (host $build)" + (error?.let { " ! ${describe(it)}" } ?: "")
        runCatching { MediaRuntime.context.log(line, error) }
        runCatching { android.util.Log.w(TAG, line, error) }
        runCatching {
            val target = file ?: return@runCatching
            if (target.length() > MAX_FILE_BYTES) target.writeText("")
            val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            target.appendText("$stamp $line\n")
        }
    }

    fun member(member: java.lang.reflect.Executable): String =
        runCatching { "${member.declaringClass.name}.${if (member is java.lang.reflect.Constructor<*>) "<init>" else member.name}" +
            member.parameterTypes.joinToString(",", "(", ")") { it.simpleName } }.getOrDefault(member.toString())

    fun describe(error: Throwable): String {
        var root = error
        while (root.cause != null && root.cause !== root) root = root.cause!!
        val head = "${error.javaClass.simpleName}: ${error.message?.take(200)}"
        return if (root === error) head else "$head <- ${root.javaClass.simpleName}: ${root.message?.take(200)}"
    }

    internal fun resetForTest() { repeats.clear(); file = null; capability = null; build = "?" }
}
