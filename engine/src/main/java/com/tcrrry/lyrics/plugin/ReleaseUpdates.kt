package com.tcrrry.lyrics.plugin

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.tcrrry.lyrics.engine.ui.TcrrryLyricsSettingsUi
import com.tcrrry.lyrics.plugin.PluginStorage.pluginPreferences
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal data class UpdateChannel(val repository: String, val tagPrefix: String, val version: String, val label: String) {
    fun release(tag: String) = "https://github.com/$repository/releases/tag/$tag"
    fun versionOf(tag: String): String? = tag.takeIf { it.startsWith(tagPrefix) }?.removePrefix(tagPrefix)
        ?.takeIf { it.matches(Regex("[0-9]+(?:\\.[0-9]+){1,3}")) }
    companion object {
        fun newer(candidate: String, current: String): Boolean {
            fun parts(value: String): List<Long> = value.split('.').map { it.toLongOrNull() ?: return emptyList() }
            val a = parts(candidate); val b = parts(current)
            if (a.isEmpty() || b.isEmpty()) return false
            for (i in 0 until maxOf(a.size, b.size)) {
                val compared = (a.getOrElse(i) { 0 }).compareTo(b.getOrElse(i) { 0 })
                if (compared != 0) return compared > 0
            }
            return false
        }
    }
}

internal class UpdateReminderPolicy(private val prefs: SharedPreferences) {
    var automatic: Boolean
        get() = prefs.getBoolean("automatic", true)
        set(value) { prefs.edit().putBoolean("automatic", value).apply() }
    fun shouldNotify(tag: String, now: Long): Boolean = automatic && prefs.getString("ignored", null) != tag &&
        (prefs.getString("notified", null) != tag || prefs.getLong("snooze", 0).let { it > 0 && now >= it })
    fun presented(tag: String) { prefs.edit().putString("notified", tag).putLong("snooze", 0).apply() }
    fun ignore(tag: String) { prefs.edit().putString("ignored", tag).putLong("snooze", 0).apply() }
    fun later(tag: String, now: Long) {
        prefs.edit().putString("notified", tag).putLong("snooze", now + 24 * 60 * 60 * 1000L).apply()
    }
}

/** Public GitHub metadata only; checks are throttled and never download or install a package. */
internal object ReleaseUpdates {
    private val main = Handler(Looper.getMainLooper())
    private var executor: java.util.concurrent.ExecutorService? = null
    private var callbacks: Application.ActivityLifecycleCallbacks? = null
    private var foreground = WeakReference<Activity>(null)
    private var dialog = WeakReference<android.app.Dialog>(null)
    private val busy = AtomicBoolean()
    private var generation = 0
    private val fallback = UpdateChannel("tcrrry/Tcrrry-Lyrics-Plugin", "plugin-v", "1.0.6", "歌词插件")
    val channel: UpdateChannel by lazy {
        runCatching {
            val json = PluginLifetime.context.openAsset("update-channel.json").bufferedReader().use { JSONObject(it.readText()) }
            val repository = json.getString("repository")
            require(repository in setOf("tcrrry/Tcrrry-Lyrics-Plugin", "tcrrry/AMPP-Lyrics"))
            UpdateChannel(repository, json.getString("tagPrefix"), json.getString("version"), json.getString("label"))
        }.getOrDefault(fallback)
    }
    private fun preferences(context: Context) = context.pluginPreferences("release_updates_${channel.repository.substringAfter('/')}", Context.MODE_PRIVATE)
    fun automatic(context: Context) = UpdateReminderPolicy(preferences(context)).automatic
    fun automatic(context: Context, value: Boolean) { UpdateReminderPolicy(preferences(context)).automatic = value }

    fun start(application: Application) {
        if (callbacks != null) return
        executor = Executors.newSingleThreadExecutor { Thread(it, "lyrics-release-check").apply { isDaemon = true } }
        val observer = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                foreground = WeakReference(activity)
                main.postDelayed({ if (foreground.get() === activity && PluginLifetime.alive) check(activity, false) }, 4000)
            }
            override fun onActivityPaused(activity: Activity) { if (foreground.get() === activity) foreground.clear() }
            override fun onActivityCreated(a: Activity, b: Bundle?) = Unit
            override fun onActivityStarted(a: Activity) = Unit
            override fun onActivityStopped(a: Activity) = Unit
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) = Unit
            override fun onActivityDestroyed(a: Activity) = Unit
        }
        callbacks = observer
        application.registerActivityLifecycleCallbacks(observer)
        PluginLifetime.close { main.post { stop(application) } }
    }

    private fun stop(application: Application) {
        generation++
        callbacks?.let(application::unregisterActivityLifecycleCallbacks)
        callbacks = null
        main.removeCallbacksAndMessages(null)
        executor?.shutdownNow(); executor = null
        dialog.get()?.dismiss(); dialog.clear(); foreground.clear()
        busy.set(false)
    }

    fun check(activity: Activity, manual: Boolean = true) {
        if (!PluginLifetime.alive || activity.isFinishing || activity.isDestroyed) return
        val prefs = preferences(activity)
        val policy = UpdateReminderPolicy(prefs)
        val now = System.currentTimeMillis()
        if (!manual) {
            if (!policy.automatic) return
            val cached = prefs.getString("latest", null)
            if (cached != null && channel.versionOf(cached)?.let { UpdateChannel.newer(it, channel.version) } == true && policy.shouldNotify(cached, now)) {
                show(activity, cached, policy); return
            }
            if (now - prefs.getLong("checked", 0) < 6 * 60 * 60 * 1000L) return
        }
        if (!busy.compareAndSet(false, true)) {
            if (manual) toast(activity, "正在检查更新")
            return
        }
        if (manual) toast(activity, "正在检查${channel.label}更新")
        prefs.edit().putLong("checked", now).apply()
        val target = WeakReference(activity)
        val owner = generation
        val surface = com.tcrrry.lyrics.engine.ui.SettingsSurfaceContext.hostColor
        val service = executor
        if (service == null) { busy.set(false); if (manual) toast(activity, "更新检查暂不可用，请重启宿主后重试"); return }
        service.execute {
            val result = runCatching { latest() }
            main.post {
                busy.set(false)
                if (owner != generation || !PluginLifetime.alive) return@post
                result.getOrNull()?.let { prefs.edit().putString("latest", it).apply() }
                val current = target.get()?.takeIf { !it.isFinishing && !it.isDestroyed } ?: return@post
                if (!manual && foreground.get() !== current) return@post
                val tag = result.getOrNull()
                if (tag == null) { if (manual) toast(current, "检查失败，请稍后重试或访问 GitHub 发布页"); return@post }
                if (channel.versionOf(tag)?.let { UpdateChannel.newer(it, channel.version) } != true) {
                    if (manual) toast(current, "${channel.label}已是最新正式版（${channel.version}）")
                } else if (manual || policy.shouldNotify(tag, System.currentTimeMillis())) {
                    val anchor = android.view.View(current)
                    if (surface != null) com.tcrrry.lyrics.engine.ui.SettingsSurfaceContext.mark(anchor, surface)
                    com.tcrrry.lyrics.engine.ui.SettingsSurfaceContext.from(anchor) { show(current, tag, policy) }
                }
            }
        }
    }

    private fun latest(): String {
        val connection = URL("https://api.github.com/repos/${channel.repository}/releases/latest").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 5000; connection.readTimeout = 8000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "Tcrrry-Lyrics/${channel.version}")
            check(connection.responseCode == 200)
            val content = connection.inputStream.bufferedReader().use { it.readText().also { text -> check(text.length <= 256 * 1024) } }
            val release = JSONObject(content)
            check(!release.optBoolean("draft") && !release.optBoolean("prerelease"))
            return release.getString("tag_name").also { check(channel.versionOf(it) != null) }
        } finally { connection.disconnect() }
    }

    private fun show(activity: Activity, tag: String, policy: UpdateReminderPolicy) {
        if (dialog.get()?.isShowing == true) return
        lateinit var popup: android.app.AlertDialog
        fun option(caption: String, action: () -> Unit) = caption to { popup.dismiss(); action() }
        val body = TcrrryLyricsSettingsUi.updateReminderBody(activity,
            "${channel.label}有新版本 ${channel.versionOf(tag)}", "当前版本 ${channel.version}", listOf(
                option("查看并下载更新") {
                    runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(channel.release(tag)))) }
                        .onFailure { toast(activity, "无法打开浏览器") }
                },
                option("稍后提醒") { policy.later(tag, System.currentTimeMillis()) },
                option("忽略此版本") { policy.ignore(tag) },
                option("不再自动提醒") { policy.automatic = false },
            ))
        popup = TcrrryLyricsSettingsUi.bubbleDialog(activity, body, compact = true, outsideCancelable = false)
        popup.setOnCancelListener { policy.later(tag, System.currentTimeMillis()) }
        dialog = WeakReference(popup)
        popup.show()
        policy.presented(tag)
    }
    private fun toast(context: Context, message: String) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}
