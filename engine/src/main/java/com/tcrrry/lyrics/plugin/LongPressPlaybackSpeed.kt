package com.tcrrry.lyrics.plugin

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioTrack
import android.media.PlaybackParams
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import com.tcrrry.lyrics.engine.ui.SettingsUiTheme
import com.tcrrry.lyrics.plugin.PluginStorage.pluginPreferences
import dev.amenhancer.plugin.api.PluginCall
import dev.amenhancer.plugin.api.PluginContext
import dev.amenhancer.plugin.api.PluginHook
import dev.amenhancer.plugin.api.PluginRegistration
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * 独立倍速小工具：开启后在播放页长按「下一首」倍速播放、长按「上一首」慢速播放；
 * 短按依然保留 Apple Music 原生切歌行为。
 *
 * 使用系统原生 AudioTrack.setPlaybackParams 在 AudioFlinger 混音后执行高精度变速变调控制，
 * 避免触发宿主 ExoPlayer SonicAudioProcessor 对浮点/大端 PCM 的格式误读噪音。
 */
internal object LongPressPlaybackSpeed {
    private const val PREFS_NAME = "more_tools_playback_speed"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_FAST_SPEED = "fast_speed"
    private const val KEY_SLOW_SPEED = "slow_speed"
    private const val KEY_HOLD_MODE = "hold_mode"
    private const val KEY_KEEP_PITCH = "keep_pitch"

    private const val LONG_PRESS_DELAY_MS = 280L
    private const val PILL_TAG = "tcrrry_long_press_speed_pill"

    data class Config(
        val enabled: Boolean = false,
        val fastSpeed: Float = 2.0f,
        val slowSpeed: Float = 0.5f,
        val holdMode: Boolean = true,
        val keepPitch: Boolean = true,
    ) {
        fun clamped(): Config {
            val fast = ((fastSpeed / 0.25f).roundToInt() * 0.25f).coerceIn(1.25f, 3.0f)
            val slow = ((slowSpeed / 0.05f).roundToInt() * 0.05f).coerceIn(0.50f, 0.90f)
            return copy(fastSpeed = fast, slowSpeed = slow)
        }
    }

    private val lock = Any()
    private val tracksLock = Any()
    private val trackedTracks = WeakHashMap<AudioTrack, Boolean>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val hookRegistrations = ArrayList<PluginRegistration>()

    @Volatile private var runtimeContext: PluginContext? = null
    @Volatile private var runtimeEnabled = false
    @Volatile private var cachedConfig = Config()
    @Volatile private var nextBtnId = 0
    @Volatile private var prevBtnId = 0
    @Volatile private var primaryTrackRef: WeakReference<AudioTrack>? = null
    @Volatile private var currentAppliedSpeed = 1.0f
    @Volatile private var lockedModeSpeed: Float? = null

    private var sendingSyntheticCancel = false
    private var longPressTriggered = false
    private var activeIsFast = true
    private var downX = 0f
    private var downY = 0f
    private var downTimeMs = 0L
    private var activeViewRef: WeakReference<View>? = null
    private var activeRootRef: WeakReference<ViewGroup>? = null
    private var floatingPill: TextView? = null
    private var hidePillRunnable: Runnable? = null

    private val longPressRunnable = Runnable {
        val view = activeViewRef?.get() ?: return@Runnable
        if (!runtimeEnabled || !view.isAttachedToWindow) return@Runnable
        triggerLongPress(view)
    }

    fun formatSpeed(speed: Float): String {
        val rounded = (speed * 100f).roundToInt() / 100f
        return when {
            abs(rounded - rounded.toInt()) < 0.005f -> "${rounded.toInt()}.0x"
            abs(rounded * 10f - (rounded * 10f).roundToInt()) < 0.05f -> String.format(Locale.US, "%.1fx", rounded)
            else -> String.format(Locale.US, "%.2fx", rounded)
        }
    }

    fun config(context: Context): Config = synchronized(lock) {
        val prefs = context.applicationContext.pluginPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        Config(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            fastSpeed = prefs.getFloat(KEY_FAST_SPEED, 2.0f),
            slowSpeed = prefs.getFloat(KEY_SLOW_SPEED, 0.5f),
            holdMode = prefs.getBoolean(KEY_HOLD_MODE, true),
            keepPitch = prefs.getBoolean(KEY_KEEP_PITCH, true),
        ).clamped().also {
            cachedConfig = it
            runtimeEnabled = it.enabled
        }
    }

    @Synchronized
    fun updateConfig(context: Context, transform: Config.() -> Config): Config {
        val previous = config(context)
        val updated = previous.transform().clamped()
        context.applicationContext.pluginPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, updated.enabled)
            .putFloat(KEY_FAST_SPEED, updated.fastSpeed)
            .putFloat(KEY_SLOW_SPEED, updated.slowSpeed)
            .putBoolean(KEY_HOLD_MODE, updated.holdMode)
            .putBoolean(KEY_KEEP_PITCH, updated.keepPitch)
            .apply()
        cachedConfig = updated
        runtimeEnabled = updated.enabled
        if (!updated.enabled || (previous.holdMode != updated.holdMode && updated.holdMode)) {
            restoreNormalSpeed(context)
        } else if (updated.enabled && !updated.holdMode && lockedModeSpeed != null) {
            val newLocked = if (activeIsFast) updated.fastSpeed else updated.slowSpeed
            lockedModeSpeed = newLocked
            mainHandler.post { applySpeedToAudioTracks(newLocked, updated.keepPitch) }
        }
        return updated
    }

    fun menuSummary(context: Context): String {
        val cfg = config(context)
        if (!cfg.enabled) return "已关闭 · 长按下一首倍速 / 上一首慢放"
        val mode = if (cfg.holdMode) "松手恢复" else "长按锁定"
        return "快进 ${formatSpeed(cfg.fastSpeed)} / 慢放 ${formatSpeed(cfg.slowSpeed)} · $mode"
    }

    fun restoreNormalSpeed(context: Context? = null) {
        mainHandler.post {
            cancelPendingTrigger()
            lockedModeSpeed = null
            longPressTriggered = false
            applySpeedToAudioTracks(1.0f, keepPitch = true)
            dismissPillImmediate()
            if (context != null) cachedConfig = config(context)
        }
    }

    @Synchronized
    fun attach(context: PluginContext) {
        runtimeContext = context
        val app = context.application
        val cfg = config(app)
        runtimeEnabled = cfg.enabled
        cachedConfig = cfg

        resolveButtonIds(app)
        installHooksIfNeeded(context)
        context.onClose { close() }
    }

    private fun resolveButtonIds(context: Context) {
        val pkg = context.packageName
        if (nextBtnId == 0) {
            nextBtnId = runCatching { context.resources.getIdentifier("next_fast_forward", "id", pkg) }.getOrDefault(0)
        }
        if (prevBtnId == 0) {
            prevBtnId = runCatching { context.resources.getIdentifier("previous_rewind", "id", pkg) }.getOrDefault(0)
        }
    }

    private fun rememberTrack(track: AudioTrack?) {
        if (track == null) return
        if (primaryTrackRef?.get() === track) return
        synchronized(tracksLock) {
            trackedTracks[track] = java.lang.Boolean.TRUE
            primaryTrackRef = WeakReference(track)
        }
    }

    private fun installHooksIfNeeded(context: PluginContext) {
        synchronized(lock) {
            if (hookRegistrations.isNotEmpty()) return

            runCatching {
                val buildMethod = AudioTrack.Builder::class.java.getDeclaredMethod("build")
                hookRegistrations.add(context.hooks.hook(buildMethod, false, object : PluginHook {
                    override fun after(call: PluginCall) {
                        if (call.throwable != null) return
                        rememberTrack(call.result as? AudioTrack)
                    }
                }))
            }

            for (ctor in AudioTrack::class.java.declaredConstructors) {
                runCatching {
                    ctor.isAccessible = true
                    hookRegistrations.add(context.hooks.hook(ctor, false, object : PluginHook {
                        override fun after(call: PluginCall) {
                            if (call.throwable != null) return
                            rememberTrack(call.receiver as? AudioTrack)
                        }
                    }))
                }
            }

            runCatching {
                val playMethod = AudioTrack::class.java.getDeclaredMethod("play")
                hookRegistrations.add(context.hooks.hook(playMethod, false, object : PluginHook {
                    override fun after(call: PluginCall) {
                        if (call.throwable != null) return
                        val track = call.receiver as? AudioTrack ?: return
                        rememberTrack(track)
                        val locked = lockedModeSpeed
                        if (runtimeEnabled && locked != null && abs(locked - 1.0f) > 0.005f) {
                            applyParamsToSingleTrack(track, locked, cachedConfig.keepPitch)
                        }
                    }
                }))
            }

            runCatching {
                val dispatchMethod = View::class.java.getDeclaredMethod("dispatchTouchEvent", MotionEvent::class.java)
                hookRegistrations.add(context.hooks.hook(dispatchMethod, false, object : PluginHook {
                    override fun before(call: PluginCall) {
                        if (!runtimeEnabled || sendingSyntheticCancel) return
                        val view = call.receiver as? View ?: return
                        if (nextBtnId == 0 || prevBtnId == 0) resolveButtonIds(view.context)
                        val id = view.id
                        if (id != nextBtnId && id != prevBtnId) return
                        val event = call.arguments.firstOrNull() as? MotionEvent ?: return
                        handleButtonTouch(view, id == nextBtnId, event, call)
                    }
                }))
            }.onFailure { context.log("LongPressPlaybackSpeed: touch dispatch hook failed", it) }
        }
    }

    private fun handleButtonTouch(view: View, isFast: Boolean, event: MotionEvent, call: PluginCall) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelPendingTrigger()
                longPressTriggered = false
                activeIsFast = isFast
                downX = event.rawX
                downY = event.rawY
                downTimeMs = event.downTime
                activeViewRef = WeakReference(view)
                mainHandler.postDelayed(longPressRunnable, LONG_PRESS_DELAY_MS)
            }
            MotionEvent.ACTION_MOVE -> {
                if (longPressTriggered) {
                    call.returnResult(true)
                    return
                }
                val slop = ViewConfiguration.get(view.context).scaledTouchSlop * 1.5f
                if (hypot((event.rawX - downX).toDouble(), (event.rawY - downY).toDouble()) > slop) {
                    cancelPendingTrigger()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cancelPendingTrigger()
                if (longPressTriggered) {
                    longPressTriggered = false
                    if (cachedConfig.holdMode) {
                        applySpeedToAudioTracks(1.0f, keepPitch = true)
                        fadeOutPill(delayMs = 150L)
                    }
                    call.returnResult(true)
                }
            }
        }
    }

    private fun cancelPendingTrigger() {
        mainHandler.removeCallbacks(longPressRunnable)
    }

    private fun triggerLongPress(view: View) {
        longPressTriggered = true
        runCatching { view.parent?.requestDisallowInterceptTouchEvent(true) }
        runCatching { view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }

        val cancel = MotionEvent.obtain(
            downTimeMs,
            SystemClock.uptimeMillis(),
            MotionEvent.ACTION_CANCEL,
            0f,
            0f,
            0,
        )
        sendingSyntheticCancel = true
        try {
            view.dispatchTouchEvent(cancel)
        } finally {
            sendingSyntheticCancel = false
            cancel.recycle()
        }

        val cfg = cachedConfig
        val targetSpeed = if (activeIsFast) cfg.fastSpeed else cfg.slowSpeed
        if (cfg.holdMode) {
            lockedModeSpeed = null
            val applied = applySpeedToAudioTracks(targetSpeed, cfg.keepPitch)
            val label = if (applied == null) {
                "当前输出通道不支持倍速"
            } else if (activeIsFast) {
                "⏩ ${formatSpeed(applied)} 倍速中"
            } else {
                "⏪ ${formatSpeed(applied)} 慢放中"
            }
            showPill(view, label, autoHideMs = if (applied != null) null else 1500L)
        } else {
            val alreadyLocked = lockedModeSpeed?.let { abs(it - targetSpeed) < 0.01f } == true
            if (alreadyLocked) {
                lockedModeSpeed = null
                applySpeedToAudioTracks(1.0f, keepPitch = true)
                showPill(view, "已恢复 1.0x 原速", autoHideMs = 1400L)
            } else {
                val applied = applySpeedToAudioTracks(targetSpeed, cfg.keepPitch)
                if (applied != null) {
                    lockedModeSpeed = applied
                    val dir = if (activeIsFast) "⏩" else "⏪"
                    showPill(view, "$dir 已锁定 ${formatSpeed(applied)}（再次长按恢复）", autoHideMs = 1800L)
                } else {
                    showPill(view, "当前输出通道不支持倍速", autoHideMs = 1500L)
                }
            }
        }
    }

    private fun applyParamsToSingleTrack(track: AudioTrack, speed: Float, keepPitch: Boolean): Float? {
        if (track.state != AudioTrack.STATE_INITIALIZED) return null
        val requested = speed.coerceIn(0.25f, 3.0f)
        val candidates = if (requested > 2.0f) listOf(requested, 2.0f) else listOf(requested)
        for (candidate in candidates) {
            val pitch = if (keepPitch || abs(candidate - 1.0f) < 0.005f) 1.0f else candidate.coerceIn(0.5f, 2.0f)
            val ok = runCatching {
                val params = PlaybackParams()
                    .setSpeed(candidate)
                    .setPitch(pitch)
                    .setAudioFallbackMode(PlaybackParams.AUDIO_FALLBACK_MODE_DEFAULT)
                track.playbackParams = params
                true
            }.getOrDefault(false)
            if (ok) return candidate
        }
        return null
    }

    private fun applySpeedToAudioTracks(speed: Float, keepPitch: Boolean): Float? {
        val snapshot = synchronized(tracksLock) { trackedTracks.keys.toList() }
        val initialized = snapshot.filter { runCatching { it.state == AudioTrack.STATE_INITIALIZED }.getOrDefault(false) }
        if (initialized.isEmpty()) return null

        val targets = if (abs(speed - 1.0f) < 0.005f) {
            initialized
        } else {
            val playing = initialized.filter { runCatching { it.playState == AudioTrack.PLAYSTATE_PLAYING }.getOrDefault(false) }
            playing.ifEmpty { initialized }
        }

        var appliedSpeed: Float? = null
        for (track in targets) {
            val res = applyParamsToSingleTrack(track, speed, keepPitch)
            if (res != null) {
                appliedSpeed = res
            }
        }
        if (appliedSpeed != null) {
            currentAppliedSpeed = appliedSpeed
        }
        return appliedSpeed
    }

    private fun showPill(anchor: View, text: String, autoHideMs: Long?) {
        val root = anchor.rootView as? ViewGroup ?: return
        hidePillRunnable?.let { mainHandler.removeCallbacks(it) }
        hidePillRunnable = null

        val density = anchor.resources.displayMetrics.density
        val colors = SettingsUiTheme.colors(anchor.context)
        val existing = floatingPill?.takeIf { it.parent === root } ?: root.findViewWithTag<TextView>(PILL_TAG)
        val pill = existing ?: TextView(anchor.context).apply {
            tag = PILL_TAG
            textSize = 13.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setPadding((16f * density).roundToInt(), (8f * density).roundToInt(), (16f * density).roundToInt(), (8f * density).roundToInt())
            elevation = 10f * density
            isClickable = false
            isFocusable = false
            val lp = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            ).apply {
                topMargin = (56f * density).roundToInt()
            }
            root.addView(this, lp)
        }
        val bg = GradientDrawable().apply {
            setColor(if (colors.dark) 0xD8242428.toInt() else 0xEAFFFFFF.toInt())
            cornerRadius = 999f * density
            setStroke((1f * density).roundToInt().coerceAtLeast(1), if (colors.dark) 0x4CFFFFFF else 0x22000000)
        }
        pill.background = bg
        pill.setTextColor(if (colors.dark) Color.WHITE else colors.text)
        pill.text = text
        pill.visibility = View.VISIBLE
        pill.animate().cancel()
        if (pill.alpha < 0.99f) {
            pill.alpha = 0f
            pill.scaleX = 0.92f
            pill.scaleY = 0.92f
            pill.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(160L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
        activeRootRef = WeakReference(root)
        floatingPill = pill

        if (autoHideMs != null) {
            val r = Runnable { fadeOutPill(0L) }
            hidePillRunnable = r
            mainHandler.postDelayed(r, autoHideMs)
        }
    }

    private fun fadeOutPill(delayMs: Long) {
        val pill = floatingPill ?: return
        hidePillRunnable?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable {
            pill.animate().cancel()
            pill.animate()
                .alpha(0f)
                .scaleX(0.95f)
                .scaleY(0.95f)
                .setDuration(180L)
                .withEndAction {
                    pill.visibility = View.GONE
                }
                .start()
        }
        if (delayMs > 0L) {
            hidePillRunnable = r
            mainHandler.postDelayed(r, delayMs)
        } else {
            r.run()
        }
    }

    private fun dismissPillImmediate() {
        hidePillRunnable?.let { mainHandler.removeCallbacks(it) }
        hidePillRunnable = null
        val pill = floatingPill ?: return
        pill.animate().cancel()
        runCatching { (pill.parent as? ViewGroup)?.removeView(pill) }
        floatingPill = null
        activeRootRef = null
    }

    @Synchronized
    fun close() {
        runtimeEnabled = false
        cancelPendingTrigger()
        lockedModeSpeed = null
        longPressTriggered = false
        if (abs(currentAppliedSpeed - 1.0f) > 0.005f) {
            runCatching { applySpeedToAudioTracks(1.0f, keepPitch = true) }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            dismissPillImmediate()
        } else {
            mainHandler.post { dismissPillImmediate() }
        }
        hookRegistrations.forEach { runCatching { it.close() } }
        hookRegistrations.clear()
        synchronized(tracksLock) {
            trackedTracks.clear()
            primaryTrackRef = null
        }
        runtimeContext = null
    }
}
