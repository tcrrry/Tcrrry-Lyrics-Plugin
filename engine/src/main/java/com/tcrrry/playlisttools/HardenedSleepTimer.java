package com.tcrrry.playlisttools;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.Application;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.text.InputType;
import android.view.KeyEvent;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import dev.amenhancer.plugin.api.PluginCall;
import dev.amenhancer.plugin.api.PluginContext;
import dev.amenhancer.plugin.api.PluginHook;
import dev.amenhancer.plugin.api.PluginHooks;
import dev.amenhancer.plugin.api.PluginRegistration;
import java.lang.reflect.Proxy;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntConsumer;

public final class HardenedSleepTimer {
    public interface CustomDurationDialogOpener {
        void open(Activity activity, int initialMinutes, long activeRemainingMs, IntConsumer onConfirmed);
    }

    private static final String KEY_HARDENED_ENABLED = "hardened_enabled";
    private static final String KEY_WAKELOCK_ENABLED = "wakelock_enabled";
    private static final String KEY_LAST_CUSTOM_MINUTES = "last_custom_minutes";
    private static final String ACTION_SLEEP_TIMER_FIRE = "com.tcrrry.lyrics.ACTION_SLEEP_TIMER_FIRE";
    private static final String EXTRA_GENERATION = "gen";
    private static final int ALARM_REQUEST_CODE = 9001;
    private static final int MAX_CUSTOM_MINUTES = 1440;
    private static final int MAX_DIAGNOSTICS = 20;

    private final PluginContext runtime;
    private final HostContract host;
    private final Application app;
    private final SharedPreferences prefs;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "tcrrry-sleep-timer");
        t.setDaemon(true);
        return t;
    });
    private final Object stateLock = new Object();
    private final AtomicLong generation = new AtomicLong(0L);
    private final Deque<String> diagnostics = new ArrayDeque<>(MAX_DIAGNOSTICS);
    private final List<PluginRegistration> registrations = new ArrayList<>();
    private final ThreadLocal<Boolean> inPluginCleanup = new ThreadLocal<>();
    private final ThreadLocal<Boolean> inPluginStop = new ThreadLocal<>();
    private final ThreadLocal<Object> buildingSleepController = new ThreadLocal<>();

    private BroadcastReceiver alarmReceiver;
    private volatile CustomDurationDialogOpener customDialogOpener;
    private volatile boolean active;
    private volatile long deadlineElapsedMs;
    private volatile int customMinutes;
    private volatile String activeLabel = "";

    private ScheduledFuture<?> tickFuture;
    private ScheduledFuture<?> fireFuture;
    private PowerManager.WakeLock timerWakeLock;
    private PowerManager.WakeLock stopWakeLock;
    private AudioFocusRequest activeAudioFocusRequest;

    public HardenedSleepTimer(PluginContext runtime, HostContract host, SharedPreferences prefs) {
        this.runtime = runtime;
        this.host = host;
        this.app = runtime.getApplication();
        this.prefs = prefs;
    }

    public void setCustomDurationDialogOpener(CustomDurationDialogOpener opener) {
        this.customDialogOpener = opener;
    }

    public static int parseCustomMinutes(String raw) {
        if (raw == null) {
            return -1;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return -1;
        }
        try {
            int value = Integer.parseInt(trimmed);
            if (value < 1 || value > MAX_CUSTOM_MINUTES) {
                return -1;
            }
            return value;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    public static long computeRemainingMs(long deadlineElapsedMs, long nowElapsedMs) {
        if (deadlineElapsedMs <= 0L) {
            return 0L;
        }
        return Math.max(0L, deadlineElapsedMs - nowElapsedMs);
    }

    public static boolean shouldTriggerStop(long deadlineElapsedMs, long nowElapsedMs) {
        return deadlineElapsedMs > 0L && nowElapsedMs >= deadlineElapsedMs;
    }

    public static String formatRemaining(long remainingMs) {
        long clamped = Math.max(0L, remainingMs);
        long totalSec = (clamped + 999L) / 1000L;
        if (clamped == 0L) {
            totalSec = 0L;
        }
        long hours = totalSec / 3600L;
        long minutes = (totalSec % 3600L) / 60L;
        long seconds = totalSec % 60L;
        if (hours > 0L) {
            return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }

    public boolean isHardenedEnabled() {
        return prefs.getBoolean(KEY_HARDENED_ENABLED, true);
    }

    public void setHardenedEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_HARDENED_ENABLED, enabled).apply();
        if (!enabled) {
            cancelHardenedOnly("关闭插件接管");
        }
        recordDiagnostic("插件接管原生睡眠定时: " + (enabled ? "开启" : "关闭"));
    }

    public boolean isWakeLockEnabled() {
        return prefs.getBoolean(KEY_WAKELOCK_ENABLED, true);
    }

    public void setWakeLockEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_WAKELOCK_ENABLED, enabled).apply();
        if (!enabled) {
            synchronized (stateLock) {
                releaseWakeLockLocked(timerWakeLock);
                timerWakeLock = null;
            }
        }
        recordDiagnostic("防休眠唤醒锁: " + (enabled ? "开启" : "关闭"));
    }

    public int getSavedRecentCustomMinutes() {
        int val = prefs.getInt(KEY_LAST_CUSTOM_MINUTES, 0);
        return (val >= 1 && val <= MAX_CUSTOM_MINUTES) ? val : 0;
    }

    public int getLastCustomMinutes() {
        int saved = getSavedRecentCustomMinutes();
        return saved > 0 ? saved : 20;
    }

    public static String formatRecentPinnedLabel(int recentMinutes, boolean isActiveThisDuration, long remainingMs) {
        if (isActiveThisDuration) {
            return "最近自定义：" + recentMinutes + " 分钟（剩余 " + formatRemaining(remainingMs) + "）";
        }
        return "最近自定义：" + recentMinutes + " 分钟后";
    }

    public boolean isActive() {
        return active;
    }

    public long getRemainingMs() {
        return active ? computeRemainingMs(deadlineElapsedMs, SystemClock.elapsedRealtime()) : 0L;
    }

    public String getActiveLabel() {
        return activeLabel;
    }

    public void install() {
        registerAlarmReceiver();
        PluginHooks hooks = runtime.getHooks();

        // 1. Replace SleepTimer.setTimer(J) with AlarmManager + ScheduledExecutor + WakeLock engine
        registrations.add(hooks.hook(host.sleepTimerSetTimerMethod, false, new PluginHook() {
            @Override public void before(PluginCall call) {
                if (!isHardenedEnabled()) {
                    return;
                }
                Object[] args = call.getArguments();
                if (args == null || args.length == 0 || !(args[0] instanceof Long)) {
                    return;
                }
                long durationMs = (Long) args[0];
                if (durationMs <= 0L) {
                    return;
                }
                call.returnResult(null);
                long mins = Math.max(1L, Math.round(durationMs / 60000.0));
                startTimerInternal(call.getReceiver(), durationMs, 0, "原生预设 " + mins + " 分钟");
            }
        }));

        // 2. When user switches to end-of-item / end-of-container, cancel any hardened countdown
        registrations.add(hooks.hook(host.sleepTimerSetEndOfEntityMethod, false, new PluginHook() {
            @Override public void before(PluginCall call) {
                if (active) {
                    cancelHardenedOnly("切换为当前曲目/歌单结束停止");
                }
            }
        }));

        // 3. Synchronize native SleepTimer.cleanup() with our timer cancellation
        registrations.add(hooks.hook(host.sleepTimerCleanupMethod, false, new PluginHook() {
            @Override public void before(PluginCall call) {
                if (Boolean.TRUE.equals(inPluginCleanup.get())) {
                    return;
                }
                if (active || customMinutes > 0) {
                    cancelHardenedOnly("原生清理/关闭定时");
                }
            }
        }));

        // 4. Prevent PredefinedTimerListener.onPlaybackStateChanged(STATE_IDLE=1) from wiping active timer
        registrations.add(hooks.hook(host.sleepTimerPredefinedStateChangedMethod, false, new PluginHook() {
            @Override public void before(PluginCall call) {
                if (!isHardenedEnabled() || !active) {
                    return;
                }
                Object[] args = call.getArguments();
                if (args != null && args.length > 0 && args[0] instanceof Integer && ((Integer) args[0]) == 1) {
                    call.returnResult(null);
                    recordDiagnostic("拦截播放器空闲(STATE_IDLE=1)误清除定时");
                }
            }
        }));

        // 5. Escalate SleepTimer.stopPlayback() to multi-layer forced stop
        registrations.add(hooks.hook(host.sleepTimerStopPlaybackMethod, false, new PluginHook() {
            @Override public void before(PluginCall call) {
                if (!isHardenedEnabled() || Boolean.TRUE.equals(inPluginStop.get())) {
                    return;
                }
                call.returnResult(null);
                Object instance = call.getReceiver();
                mainHandler.post(() -> executeForcedStop(instance, "原生 stopPlayback 触发"));
            }
        }));

        // 6. Avoid showing built-in option as selected when custom minute mode is active
        registrations.add(hooks.hook(host.sleepTimerGetCurrentOptionMethod, false, new PluginHook() {
            @Override public void after(PluginCall call) {
                if (isHardenedEnabled() && active && customMinutes > 0) {
                    call.returnResult(null);
                }
            }
        }));

        // 7. Pin recent custom duration below title & inject "自定义时间（分钟）..." row at bottom
        registrations.add(hooks.hook(host.sleepTimerEpoxyBuildModelsMethod, false, new PluginHook() {
            @Override public void before(PluginCall call) {
                if (isHardenedEnabled() && getSavedRecentCustomMinutes() > 0 && call.getReceiver() != null) {
                    buildingSleepController.set(call.getReceiver());
                } else {
                    buildingSleepController.remove();
                }
            }

            @Override public void after(PluginCall call) {
                buildingSleepController.remove();
                if (!isHardenedEnabled() || call.getThrowable() != null) {
                    return;
                }
                Object controller = call.getReceiver();
                if (controller == null) {
                    return;
                }
                try {
                    injectCustomRowIntoController(controller);
                } catch (Exception error) {
                    runtime.log("Failed to inject custom sleep timer row", error);
                }
            }
        }));

        // 8. Insert pinned recent custom duration immediately after SleepTimer's header title row
        registrations.add(hooks.hook(host.epoxyControllerAddMethod, false, new PluginHook() {
            @Override public void after(PluginCall call) {
                Object target = buildingSleepController.get();
                if (target == null || target != call.getReceiver() || call.getThrowable() != null) {
                    return;
                }
                Object[] args = call.getArguments();
                if (args == null || args.length == 0 || !host.sleepTimerTitleModelClass.isInstance(args[0])) {
                    return;
                }
                buildingSleepController.remove();
                int recent = getSavedRecentCustomMinutes();
                if (recent <= 0) {
                    return;
                }
                try {
                    injectRecentCustomRowIntoController(target, recent);
                } catch (Exception error) {
                    runtime.log("Failed to inject pinned recent sleep timer row", error);
                }
            }
        }));
    }

    public void startCustomMinutes(int minutes) {
        if (minutes < 1 || minutes > MAX_CUSTOM_MINUTES) {
            return;
        }
        prefs.edit().putInt(KEY_LAST_CUSTOM_MINUTES, minutes).apply();
        long durationMs = minutes * 60_000L;
        mainHandler.post(() -> {
            try {
                Object sleepTimer = host.sleepTimerGetInstanceMethod.invoke(null, app);
                if (sleepTimer != null && !isHardenedEnabled()) {
                    setHardenedEnabled(true);
                }
                if (sleepTimer != null) {
                    host.sleepTimerCurrentOptionField.set(sleepTimer, host.sleepTimerOffOption);
                }
                startTimerInternal(sleepTimer, durationMs, minutes, "自定义 " + minutes + " 分钟");
            } catch (Exception error) {
                runtime.log("Failed to start custom sleep timer", error);
                recordDiagnostic("启动自定义定时异常: " + error.getMessage());
            }
        });
    }

    public void cancelFromSettings() {
        mainHandler.post(() -> {
            cancelHardenedOnly("插件设置页手动取消");
            try {
                Object sleepTimer = host.sleepTimerGetInstanceMethod.invoke(null, app);
                if (sleepTimer != null) {
                    inPluginCleanup.set(Boolean.TRUE);
                    try {
                        host.sleepTimerCleanupMethod.invoke(sleepTimer);
                    } finally {
                        inPluginCleanup.remove();
                    }
                }
            } catch (Exception error) {
                runtime.log("Failed to cleanup native sleep timer on cancel", error);
            }
        });
    }

    public void stop() {
        for (PluginRegistration reg : registrations) {
            try {
                reg.close();
            } catch (Exception ignored) {}
        }
        registrations.clear();
        cancelHardenedOnly("插件卸载停止");
        synchronized (stateLock) {
            releaseWakeLockLocked(stopWakeLock);
            stopWakeLock = null;
        }
        abandonAudioFocus();
        unregisterAlarmReceiver();
        scheduler.shutdownNow();
    }

    String getDiagnosticsText() {
        synchronized (diagnostics) {
            if (diagnostics.isEmpty()) {
                return "（暂无诊断记录）";
            }
            StringBuilder sb = new StringBuilder();
            for (String line : diagnostics) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(line);
            }
            return sb.toString();
        }
    }

    void clearDiagnostics() {
        synchronized (diagnostics) {
            diagnostics.clear();
        }
    }

    private void startTimerInternal(Object sleepTimerInstance, long durationMs, int customMin, String label) {
        long nowElapsed = SystemClock.elapsedRealtime();
        long deadline = nowElapsed + durationMs;
        long gen;
        synchronized (stateLock) {
            gen = generation.incrementAndGet();
            cancelSchedulesLocked();
            cancelNativeCountDownLocked();
            active = true;
            deadlineElapsedMs = deadline;
            customMinutes = customMin;
            activeLabel = label;

            if (isWakeLockEnabled()) {
                timerWakeLock = acquirePartialWakeLock("tcrrry:sleep_timer", timerWakeLock, durationMs + 15_000L);
            } else {
                releaseWakeLockLocked(timerWakeLock);
                timerWakeLock = null;
            }
            fireFuture = scheduler.schedule(() -> onDeadlineReached(gen, "高精度调度器到期"), durationMs, TimeUnit.MILLISECONDS);
            tickFuture = scheduler.scheduleAtFixedRate(() -> {
                if (!active || generation.get() != gen) {
                    return;
                }
                long currentNow = SystemClock.elapsedRealtime();
                long remaining = computeRemainingMs(deadline, currentNow);
                if (shouldTriggerStop(deadline, currentNow)) {
                    onDeadlineReached(gen, "每秒校验到期");
                    return;
                }
                postTickLiveData(remaining);
            }, 1000L, 1000L, TimeUnit.MILLISECONDS);
        }

        scheduleAlarm(deadline, gen);
        ensureMediaBrowserConnected();
        updateHostTimerActiveState(durationMs);
        recordDiagnostic("启动定时: " + label + " (时长=" + (durationMs / 1000L) + "s, WakeLock=" + isWakeLockEnabled() + ")");
    }

    private void cancelHardenedOnly(String reason) {
        boolean wasActive;
        synchronized (stateLock) {
            wasActive = active;
            generation.incrementAndGet();
            active = false;
            deadlineElapsedMs = 0L;
            customMinutes = 0;
            activeLabel = "";
            cancelSchedulesLocked();
            releaseWakeLockLocked(timerWakeLock);
            timerWakeLock = null;
        }
        cancelAlarm();
        if (wasActive) {
            recordDiagnostic("已取消定时: " + reason);
        }
    }

    private void onDeadlineReached(long expectedGen, String source) {
        synchronized (stateLock) {
            if (!active || generation.get() != expectedGen) {
                return;
            }
            active = false;
            deadlineElapsedMs = 0L;
            customMinutes = 0;
            activeLabel = "";
            cancelSchedulesLocked();
            stopWakeLock = acquirePartialWakeLock("tcrrry:sleep_stop", stopWakeLock, 15_000L);
            releaseWakeLockLocked(timerWakeLock);
            timerWakeLock = null;
        }
        cancelAlarm();
        long stopGen = expectedGen;
        mainHandler.post(() -> {
            if (generation.get() != stopGen) {
                return;
            }
            Object sleepTimer = null;
            try {
                sleepTimer = host.sleepTimerGetInstanceMethod.invoke(null, app);
            } catch (Exception ignored) {}
            executeForcedStopWithGen(sleepTimer, source, stopGen);
        });
    }

    private void executeForcedStop(Object sleepTimerInstance, String reason) {
        long gen;
        synchronized (stateLock) {
            gen = generation.incrementAndGet();
            active = false;
            deadlineElapsedMs = 0L;
            customMinutes = 0;
            activeLabel = "";
            cancelSchedulesLocked();
            stopWakeLock = acquirePartialWakeLock("tcrrry:sleep_stop", stopWakeLock, 15_000L);
            releaseWakeLockLocked(timerWakeLock);
            timerWakeLock = null;
        }
        cancelAlarm();
        executeForcedStopWithGen(sleepTimerInstance, reason, gen);
    }

    private void executeForcedStopWithGen(Object sleepTimerInstance, String reason, long gen) {
        recordDiagnostic("执行强制停播 (" + reason + ")");
        Object browser = resolveMediaBrowser(sleepTimerInstance);
        AudioManager audioManager = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);

        // Attempt 0: primary pause + Media3 setPlayWhenReady(false) or media-key fallback
        if (browser != null) {
            invokeSafe(host.mediaControllerSetPlayWhenReadyMethod, browser, false);
            invokeSafe(host.mediaControllerPauseMethod, browser);
        } else {
            ensureMediaBrowserConnected();
            dispatchMediaKey(audioManager, KeyEvent.KEYCODE_MEDIA_PAUSE);
        }

        // Notify native UI & clean native state without re-entering cleanup hook
        postNativeFinishAndCleanup(sleepTimerInstance);
        Toast.makeText(app, "睡眠定时已到，已停止播放", Toast.LENGTH_SHORT).show();

        // Attempt 1..3: verify playback truly halted; escalate with stop(), media keys, and exclusive audio focus
        scheduleStopRetry(gen, sleepTimerInstance, 1, 600L);
        scheduleStopRetry(gen, sleepTimerInstance, 2, 1600L);
        scheduleStopRetry(gen, sleepTimerInstance, 3, 3200L);
    }

    private void scheduleStopRetry(long gen, Object sleepTimerInstance, int attempt, long delayMs) {
        mainHandler.postDelayed(() -> {
            if (generation.get() != gen) {
                return;
            }
            Object browser = resolveMediaBrowser(sleepTimerInstance);
            AudioManager audioManager = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
            boolean stillPlaying = isPlaybackStillActive(browser, audioManager);
            if (!stillPlaying) {
                recordDiagnostic("停播校验通过 (第" + attempt + "次/" + delayMs + "ms)");
                finishStopCycle();
                return;
            }
            if (attempt < 3) {
                recordDiagnostic("检测到仍在播放，升级强停 (第" + attempt + "次)");
                if (browser != null) {
                    invokeSafe(host.mediaControllerSetPlayWhenReadyMethod, browser, false);
                    invokeSafe(host.mediaControllerPauseMethod, browser);
                    invokeSafe(host.mediaControllerStopMethod, browser);
                }
                dispatchMediaKey(audioManager, KeyEvent.KEYCODE_MEDIA_PAUSE);
                if (attempt >= 2) {
                    dispatchMediaKey(audioManager, KeyEvent.KEYCODE_MEDIA_STOP);
                }
                requestExclusiveAudioFocusBriefly(audioManager);
            } else {
                recordDiagnostic("最终校验状态: " + (stillPlaying ? "AudioManager仍报告活跃，已发全部强停指令" : "已停止"));
                finishStopCycle();
            }
        }, delayMs);
    }

    private void finishStopCycle() {
        abandonAudioFocus();
        synchronized (stateLock) {
            releaseWakeLockLocked(stopWakeLock);
            stopWakeLock = null;
        }
    }

    private boolean isPlaybackStillActive(Object browser, AudioManager audioManager) {
        if (browser != null) {
            try {
                boolean isPlaying = Boolean.TRUE.equals(host.mediaControllerIsPlayingMethod.invoke(browser));
                boolean playWhenReady = Boolean.TRUE.equals(host.mediaControllerGetPlayWhenReadyMethod.invoke(browser));
                int state = (Integer) host.mediaControllerGetPlaybackStateMethod.invoke(browser);
                // Playback state 3 = STATE_READY, 2 = STATE_BUFFERING
                if (isPlaying || (playWhenReady && (state == 2 || state == 3))) {
                    return true;
                }
            } catch (Exception ignored) {}
        }
        return audioManager != null && audioManager.isMusicActive();
    }

    private Object resolveMediaBrowser(Object sleepTimerInstance) {
        if (sleepTimerInstance != null) {
            try {
                Object b = host.sleepTimerGetMediaBrowserMethod.invoke(sleepTimerInstance);
                if (b != null) {
                    return b;
                }
            } catch (Exception ignored) {}
        }
        try {
            return host.mediaBrowserHolderField.get(null);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void ensureMediaBrowserConnected() {
        mainHandler.post(() -> {
            try {
                if (host.mediaBrowserHolderField.get(null) == null) {
                    host.mediaBrowserConnectMethod.invoke(null);
                }
            } catch (Exception ignored) {}
        });
    }

    private static void dispatchMediaKey(AudioManager audioManager, int keyCode) {
        if (audioManager == null) {
            return;
        }
        try {
            long now = SystemClock.uptimeMillis();
            audioManager.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
            audioManager.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0));
        } catch (Exception ignored) {}
    }

    private void requestExclusiveAudioFocusBriefly(AudioManager audioManager) {
        if (audioManager == null) {
            return;
        }
        try {
            abandonAudioFocus();
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();
            AudioFocusRequest req = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attrs)
                    .setAcceptsDelayedFocusGain(false)
                    .build();
            int res = audioManager.requestAudioFocus(req);
            if (res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                activeAudioFocusRequest = req;
                mainHandler.postDelayed(this::abandonAudioFocus, 900L);
            }
        } catch (Exception ignored) {}
    }

    private void abandonAudioFocus() {
        AudioFocusRequest req = activeAudioFocusRequest;
        activeAudioFocusRequest = null;
        if (req == null) {
            return;
        }
        try {
            AudioManager audioManager = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
            if (audioManager != null) {
                audioManager.abandonAudioFocusRequest(req);
            }
        } catch (Exception ignored) {}
    }

    private void updateHostTimerActiveState(long durationMs) {
        try {
            host.sleepTimerIsSetField.setBoolean(null, true);
            Object onSet = host.sleepTimerOnSetField.get(null);
            if (onSet != null) {
                host.liveDataPostValueMethod.invoke(onSet, Boolean.TRUE);
            }
            Object onTick = host.sleepTimerOnTickField.get(null);
            if (onTick != null) {
                host.liveDataPostValueMethod.invoke(onTick, durationMs);
            }
        } catch (Exception error) {
            runtime.log("Failed updating SleepTimer active LiveData", error);
        }
    }

    private void postTickLiveData(long remainingMs) {
        try {
            Object onTick = host.sleepTimerOnTickField.get(null);
            if (onTick != null) {
                host.liveDataPostValueMethod.invoke(onTick, remainingMs);
            }
        } catch (Exception ignored) {}
    }

    private void postNativeFinishAndCleanup(Object sleepTimerInstance) {
        try {
            Object onFinish = host.sleepTimerOnFinishField.get(null);
            if (onFinish != null) {
                host.singleLiveEventPostEventMethod.invoke(onFinish, Boolean.TRUE);
            }
            Object onEnded = host.sleepTimerOnEndedField.get(null);
            if (onEnded != null) {
                host.singleLiveEventPostEventMethod.invoke(onEnded, Boolean.TRUE);
            }
        } catch (Exception error) {
            runtime.log("Failed posting SleepTimer finish events", error);
        }

        if (sleepTimerInstance != null) {
            inPluginCleanup.set(Boolean.TRUE);
            try {
                host.sleepTimerCleanupMethod.invoke(sleepTimerInstance);
            } catch (Exception error) {
                runtime.log("SleepTimer.cleanup() failed during forced stop", error);
            } finally {
                inPluginCleanup.remove();
            }
        }
    }

    private void cancelNativeCountDownLocked() {
        try {
            Object raw = host.sleepTimerCountDownField.get(null);
            if (raw instanceof CountDownTimer) {
                ((CountDownTimer) raw).cancel();
            }
            host.sleepTimerCountDownField.set(null, null);
        } catch (Exception ignored) {}
    }

    private void cancelSchedulesLocked() {
        if (tickFuture != null) {
            tickFuture.cancel(false);
            tickFuture = null;
        }
        if (fireFuture != null) {
            fireFuture.cancel(false);
            fireFuture = null;
        }
    }

    private void scheduleAlarm(long triggerAtElapsedMs, long gen) {
        AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        PendingIntent pi = buildAlarmPendingIntent(gen, true);
        if (pi == null) {
            return;
        }
        try {
            if (am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtElapsedMs, pi);
                return;
            }
        } catch (SecurityException ignored) {
        } catch (Exception ignored) {}
        try {
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtElapsedMs, pi);
        } catch (Exception error) {
            runtime.log("AlarmManager.setAndAllowWhileIdle failed", error);
        }
    }

    private void cancelAlarm() {
        AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = buildAlarmPendingIntent(0L, false);
        if (am != null && pi != null) {
            try {
                am.cancel(pi);
                pi.cancel();
            } catch (Exception ignored) {}
        }
    }

    private PendingIntent buildAlarmPendingIntent(long gen, boolean create) {
        Intent intent = new Intent(ACTION_SLEEP_TIMER_FIRE);
        intent.setPackage(app.getPackageName());
        intent.putExtra(EXTRA_GENERATION, gen);
        int flags = PendingIntent.FLAG_IMMUTABLE
                | (create ? PendingIntent.FLAG_UPDATE_CURRENT : PendingIntent.FLAG_NO_CREATE);
        return PendingIntent.getBroadcast(app, ALARM_REQUEST_CODE, intent, flags);
    }

    private void registerAlarmReceiver() {
        if (alarmReceiver != null) {
            return;
        }
        alarmReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (intent == null || !ACTION_SLEEP_TIMER_FIRE.equals(intent.getAction())) {
                    return;
                }
                long gen = intent.getLongExtra(EXTRA_GENERATION, -1L);
                onDeadlineReached(gen, "AlarmManager 唤醒触发");
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_SLEEP_TIMER_FIRE);
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(alarmReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            app.registerReceiver(alarmReceiver, filter);
        }
    }

    private void unregisterAlarmReceiver() {
        if (alarmReceiver != null) {
            try {
                app.unregisterReceiver(alarmReceiver);
            } catch (Exception ignored) {}
            alarmReceiver = null;
        }
    }

    private PowerManager.WakeLock acquirePartialWakeLock(
            String tag, PowerManager.WakeLock existing, long timeoutMs) {
        releaseWakeLockLocked(existing);
        try {
            PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
            if (pm == null) {
                return null;
            }
            PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag);
            wl.setReferenceCounted(false);
            wl.acquire(Math.max(1000L, timeoutMs));
            return wl;
        } catch (Exception error) {
            runtime.log("Failed acquiring WakeLock " + tag, error);
            return null;
        }
    }

    private static void releaseWakeLockLocked(PowerManager.WakeLock wl) {
        if (wl != null) {
            try {
                if (wl.isHeld()) {
                    wl.release();
                }
            } catch (Exception ignored) {}
        }
    }

    private void injectRecentCustomRowIntoController(Object controller, int recentMinutes) throws Exception {
        Object row = host.sleepTimerRowModelCtor.newInstance();
        boolean isThisActive = active && customMinutes == recentMinutes;
        String label = formatRecentPinnedLabel(recentMinutes, isThisActive, getRemainingMs());

        host.epoxyModelOnMutationMethod.invoke(row);
        host.sleepTimerRowLabelField.set(row, label);
        host.epoxyModelIdMethod.invoke(row, "tcrrry_recent_custom_sleep_timer");
        host.epoxyModelOnMutationMethod.invoke(row);
        host.sleepTimerRowSelectedField.setBoolean(row, isThisActive);
        host.epoxyModelOnMutationMethod.invoke(row);

        Object boundListener = Proxy.newProxyInstance(
                runtime.getHostClassLoader(),
                new Class<?>[] { host.epoxyOnModelBoundListenerClass },
                (proxy, method, args) -> {
                    if ("onModelBound".equals(method.getName()) && args != null && args.length >= 2) {
                        Object holder = args[1];
                        if (holder != null) {
                            Object binding = host.epoxyDataBindingHolderField.get(holder);
                            if (binding != null) {
                                android.view.View rootView =
                                        (android.view.View) host.viewDataBindingRootField.get(binding);
                                if (rootView != null) {
                                    rootView.setOnClickListener(v -> {
                                        startCustomMinutes(recentMinutes);
                                        try {
                                            Object vm = host.sleepTimerEpoxyGetViewModelMethod.invoke(controller);
                                            if (vm != null) {
                                                host.sleepTimerViewModelPostEventMethod.invoke(
                                                        vm, host.sleepTimerCloseDialogEvent);
                                            }
                                        } catch (Exception ignored) {}
                                        Toast.makeText(
                                                app,
                                                "睡眠定时已设为 " + recentMinutes + " 分钟（强力停止已开启）",
                                                Toast.LENGTH_SHORT).show();
                                    });
                                }
                            }
                        }
                    }
                    return null;
                });
        host.sleepTimerRowBoundListenerField.set(row, boundListener);
        host.epoxyControllerAddMethod.invoke(controller, row);
    }

    private void injectCustomRowIntoController(Object controller) throws Exception {
        Object row = host.sleepTimerRowModelCtor.newInstance();
        int recent = getSavedRecentCustomMinutes();
        boolean customActiveNotPinned = active && customMinutes > 0 && customMinutes != recent;
        String label = customActiveNotPinned
                ? ("自定义：" + customMinutes + " 分钟（剩余 " + formatRemaining(getRemainingMs()) + "）")
                : "自定义时间（分钟）...";

        host.epoxyModelOnMutationMethod.invoke(row);
        host.sleepTimerRowLabelField.set(row, label);
        host.epoxyModelIdMethod.invoke(row, "tcrrry_custom_sleep_timer");
        host.epoxyModelOnMutationMethod.invoke(row);
        host.sleepTimerRowSelectedField.setBoolean(row, customActiveNotPinned);
        host.epoxyModelOnMutationMethod.invoke(row);

        Object boundListener = Proxy.newProxyInstance(
                runtime.getHostClassLoader(),
                new Class<?>[] { host.epoxyOnModelBoundListenerClass },
                (proxy, method, args) -> {
                    if ("onModelBound".equals(method.getName()) && args != null && args.length >= 2) {
                        Object holder = args[1];
                        if (holder != null) {
                            Object binding = host.epoxyDataBindingHolderField.get(holder);
                            if (binding != null) {
                                android.view.View rootView =
                                        (android.view.View) host.viewDataBindingRootField.get(binding);
                                if (rootView != null) {
                                    rootView.setOnClickListener(v -> showCustomDurationDialog(controller));
                                }
                            }
                        }
                    }
                    return null;
                });
        host.sleepTimerRowBoundListenerField.set(row, boundListener);
        host.epoxyControllerAddMethod.invoke(controller, row);
    }

    private void showCustomDurationDialog(Object epoxyController) {
        Context uiContext = null;
        try {
            uiContext = (Context) host.sleepTimerEpoxyContextField.get(epoxyController);
        } catch (Exception ignored) {}
        if (uiContext == null) {
            uiContext = app;
        }
        Context dialogContext = uiContext;
        CustomDurationDialogOpener opener = this.customDialogOpener;
        if (opener != null && dialogContext instanceof Activity) {
            try {
                Object vm = host.sleepTimerEpoxyGetViewModelMethod.invoke(epoxyController);
                if (vm != null) {
                    host.sleepTimerViewModelPostEventMethod.invoke(vm, host.sleepTimerCloseDialogEvent);
                }
            } catch (Exception ignored) {}
            Activity act = (Activity) dialogContext;
            mainHandler.post(() -> opener.open(
                    act,
                    getLastCustomMinutes(),
                    getRemainingMs(),
                    this::startCustomMinutes));
            return;
        }
        float density = dialogContext.getResources().getDisplayMetrics().density;
        int dp8 = Math.round(8 * density);
        int dp16 = Math.round(16 * density);

        LinearLayout container = new LinearLayout(dialogContext);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp16, dp8, dp16, dp8);

        TextView hint = new TextView(dialogContext);
        hint.setText("请输入定时停止的分钟数（1–1440 分钟）：");
        hint.setPadding(0, 0, 0, dp8);
        container.addView(hint);

        EditText input = new EditText(dialogContext);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint("例如 25");
        String preset = String.valueOf(getLastCustomMinutes());
        input.setText(preset);
        input.setSelection(preset.length());
        container.addView(input);

        LinearLayout quickBar = new LinearLayout(dialogContext);
        quickBar.setOrientation(LinearLayout.HORIZONTAL);
        quickBar.setPadding(0, dp8, 0, 0);
        int[] presets = new int[] { 5, 10, 20, 90, 120 };
        for (int min : presets) {
            Button btn = new Button(dialogContext);
            btn.setText(min + "分");
            btn.setAllCaps(false);
            btn.setPadding(dp8 / 2, 0, dp8 / 2, 0);
            btn.setOnClickListener(v -> {
                String text = String.valueOf(min);
                input.setText(text);
                input.setSelection(text.length());
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            quickBar.addView(btn, lp);
        }
        container.addView(quickBar);

        new AlertDialog.Builder(dialogContext)
                .setTitle("自定义睡眠定时")
                .setView(container)
                .setPositiveButton("开始定时", (dialog, which) -> {
                    int minutes = parseCustomMinutes(input.getText().toString());
                    if (minutes <= 0) {
                        Toast.makeText(dialogContext, "请输入 1 到 1440 之间的整数分钟", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    startCustomMinutes(minutes);
                    try {
                        Object vm = host.sleepTimerEpoxyGetViewModelMethod.invoke(epoxyController);
                        if (vm != null) {
                            host.sleepTimerViewModelPostEventMethod.invoke(vm, host.sleepTimerCloseDialogEvent);
                        }
                    } catch (Exception ignored) {}
                    Toast.makeText(
                            dialogContext,
                            "睡眠定时已设为 " + minutes + " 分钟（强力停止已开启）",
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void invokeSafe(java.lang.reflect.Method method, Object target, Object... args) {
        if (method == null || target == null) {
            return;
        }
        try {
            method.invoke(target, args);
        } catch (Exception error) {
            runtime.log("Invoke failed: " + method.getName(), error);
        }
    }

    private void recordDiagnostic(String message) {
        String ts = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        synchronized (diagnostics) {
            if (diagnostics.size() >= MAX_DIAGNOSTICS) {
                diagnostics.removeFirst();
            }
            diagnostics.addLast("[" + ts + "] " + message);
        }
    }
}
