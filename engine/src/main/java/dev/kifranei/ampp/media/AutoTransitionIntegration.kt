package dev.kifranei.ampp.media

import android.animation.ValueAnimator
import android.graphics.drawable.BitmapDrawable
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.os.Handler
import android.os.Looper
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import java.lang.reflect.Method
import java.util.WeakHashMap

/**
 * AI DJ 自动过渡（Auto-Transition）主模块。
 *
 * 这是 iOS「自动过渡」功能的 Android 插件实现，不是简单 crossfade：
 *   1. 从 Apple Music 原生 AudioAnalysis 取当前/下一首的 BPM、调性、节拍轴、淡化点、能量；
 *   2. TransitionPlanner 用 Camelot 调性匹配 + BPM 匹配计算最佳淡化时长、速度比、曲线；
 *   3. hook ExoPlayer AudioFadeControl 的 prepareForCrossFade/doCrossFade，注入智能参数；
 *   4. hook setPlaybackParameters，在淡化期间对下一首做 beat match 速度匹配，结束恢复原速；
 *   5. 切歌时封面做淡入+缩放过渡动画。
 *
 * 实验性标注：beat match 的 PlaybackParameters 在双渲染期(fadeIn/fadeOut 同时渲染)的行为
 * 需要真机调试；若速度调整导致音频 glitch，可关闭 speed match（设置里只保留调性+曲线智能）。
 */
internal class AutoTransitionIntegration(
    private val build: TargetBuild,
    private val loader: ClassLoader,
    private val enabled: () -> Boolean,
    private val defaultDurationSec: () -> Int,
    private val speedMatch: () -> Boolean,
    private val animate: () -> Boolean,
    private val skipIntro: () -> Boolean = { false },
    private val useEqualizer: () -> Boolean = { false },
    private val preferAppleCurve: () -> Boolean = { false },
    private val allowAlbumSequential: () -> Boolean = { true },
    private val allowSingleRepeat: () -> Boolean = { true },
    private val allowSeekTrigger: () -> Boolean = { true },
    private val showQueueButton: () -> Boolean = { true },
    private val analysisCacheFile: java.io.File? = null,
    private val onRuntimeToggle: ((Boolean) -> Unit)? = null,
) {
    private val ownStateCall = ThreadLocal<Boolean>()
    @Volatile
    private var activeAnalysisProvider: AudioAnalysisProvider? = null
    private var lastProbeLogMs: Long = 0L
    private var lastVolumeLogMs: Long = 0L
    @Volatile
    private var activeFadeDurationMs: Long = 6_000L

    fun syncExternalEnabled(newState: Boolean) {
        AutoTransitionUi.syncEnabled(newState)
        if (newState == runtimeEnabled) {
            if (newState && !crossfadeStateApplied) armNativeCrossfadeEngineIfNeeded()
            return
        }
        runtimeEnabled = newState
        if (newState) {
            armNativeCrossfadeEngineIfNeeded()
        } else {
            flog("auto_transition soft-disabled (native engine kept armed, zero audio glitch)")
        }
    }

    fun syncQueueButtonVisible(visible: Boolean) {
        AutoTransitionUi.syncVisible(visible)
    }

    private fun effectiveFallbackSec(): Int =
        defaultDurationSec().let { if (it <= 0) 6 else it.coerceIn(2, 20) }

    fun analysisCacheStats(): Pair<Int, Long> =
        activeAnalysisProvider?.cacheStats() ?: AudioAnalysisProvider.readCacheStats(analysisCacheFile)

    fun clearAnalysisCache(): Boolean =
        activeAnalysisProvider?.clearCache() ?: AudioAnalysisProvider.deletePersistedCache(analysisCacheFile)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var activePlan: TransitionPlan? = null
    @Volatile private var crossfadeInProgress = false
    @Volatile private var introSkipApplied = false
    private var transitionFadeControl: Any? = null
    private var transitionStartedMs = 0L
    private var transitionModeName: String = "?"
    private var transitionPlannedSec: Int = 0
    private var transitionOutId: String? = null
    private var transitionInId: String? = null
    private var transitionInStartUs: Long? = null
    private var transitionSampler: Runnable? = null
    private var transitionFieldsDumped = false
    private var cachedArtwork: android.graphics.Bitmap? = null
    private var cachedArtworkAt = 0L
    private var lastCueLog = ""
    private var speedRenderer: Any? = null
    private var playbackParamsClass: Class<*>? = null
    private val artworkBySong = object : LinkedHashMap<String, android.graphics.Bitmap>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, android.graphics.Bitmap>?): Boolean = size > 8
    }
    private var gainController: Runnable? = null
    private var transitionCurve: FadeCurve = FadeCurve.GENTLE
    private var transitionDurationMs: Long = 0L
    @Volatile private var crossfadeStartTimeMs = 0L  // crossfade 开始时间（用于计算正确进度）
    @Volatile private var exoMediaPlayer: Any? = null
    @Volatile private var lastPositionMs: Long = 0L
    @Volatile private var lastDurationMs: Long = 0L
    @Volatile private var runtimeEnabled = false
    @Volatile private var crossfadeStateApplied = false
    private val stateLock = Any()
    private var setCrossFadeStateMethod: Method? = null
    private var setCrossFadeDurationMethod: Method? = null

    // ---- DJ Filter Sweep：V形低通扫频，让过渡听起来像 DJ 混音而非普通淡出淡入 ----
    // 原理：crossfade 期间低通截止频率从 18kHz→800Hz→18kHz（V形），
    // DJ Filter Sweep 已禁用：原地修改 ByteBuffer 会导致 Apple Music AudioTrack 嘶嘶声
    // 等功率音量曲线已通过 calculateFadeInLevel hook 实现，crossfade 期间不碰 PCM 数据

    // ---- 封面动画状态（提升为类成员，供 triggerCoverDissolve 访问）----
    private var coverView: android.widget.ImageView? = null
    private var currentCoverDrawable: android.graphics.drawable.Drawable? = null
    private var lastCoverDrawable: android.graphics.drawable.Drawable? = null
    private var pendingNewCover: android.graphics.drawable.Drawable? = null  // 被阻止的新封面，等 crossfade 开始时溶解
    private var coverDissolve: DissolveDrawable? = null
    private var coverOverlay: android.widget.ImageView? = null
    private var lastCoverBitmap: android.graphics.Bitmap? = null
    private var coverOverlayView: android.widget.ImageView? = null
    private var visualFinalizeRunnable: Runnable? = null
    private var coverRegional: RegionalDissolveDrawable? = null
    private var coverRegionalAnimator: android.animation.ValueAnimator? = null
    private var coverTransition: android.graphics.drawable.TransitionDrawable? = null
    private var coverTransitionTarget: android.graphics.drawable.Drawable? = null
    private var coverOverlayAnimator: android.animation.ValueAnimator? = null
    private var coverAnimator: android.animation.ValueAnimator? = null
    private var noiseBitmap: android.graphics.Bitmap? = null
    @Volatile private var waitingForCover = false
    private val isSettingCover = java.util.WeakHashMap<android.widget.ImageView, Boolean>()
    // 超时兜底：切歌后如果 crossfade 迟迟不开始，允许显示新封面（避免一直停在旧封面）
    private val coverHoldHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var coverHoldRunnable: Runnable? = null
    // 过渡总超时兜底：crossfade 开始后 durationSec+8s 内 maybeStart false 没来就强制结束
    // （否则进度条辉光/封面动画永远卡住——日志铁证：用户切页面后 maybeStart 停止被调用）
    private var transitionEndRunnable: Runnable? = null
    // 抑制 finalize：cancel 旧 animator 时防止其 onAnimationEnd 连带清理刚设置的新 dissolve
    @Volatile private var suppressFinalize = false

    // CrossFadeState 是 @IntDef 注解：AUTOMATIC=0, MANUAL=1, OFF=2
    private val STATE_AUTOMATIC = 0
    private val STATE_OFF = 2

    private fun flog(msg: String) {
        MediaRuntime.log("auto_transition: $msg")
        FileLogger.log(msg)
    }

    fun install(): TargetCapabilityInstall {
        val scope = PluginScope()
        try {
            var applied = 0
            runtimeEnabled = enabled()
            FileLogger.init(MediaRuntime.context.application)

            // 反射准备 setter 方法（参数都是 int）
            val exoCls = runCatching { loader.loadClass("com.apple.android.music.playback.player.ExoMediaPlayer") }.getOrNull()
            if (exoCls != null) {
                setCrossFadeStateMethod = runCatching {
                    exoCls.getDeclaredMethod("setCrossFadeState", Int::class.javaPrimitiveType).apply { isAccessible = true }
                }.getOrNull()
                setCrossFadeDurationMethod = runCatching {
                    exoCls.getDeclaredMethod("setCrossFadeDuration", Int::class.javaPrimitiveType).apply { isAccessible = true }
                }.getOrNull()
            }
            flog("setter state=${setCrossFadeStateMethod != null} duration=${setCrossFadeDurationMethod != null}")

            // ---- 0.5) 防止宿主关闭原生 CrossFadeState：runtimeEnabled 时兜底强制改回 AUTOMATIC(0) ----
            val cfStateHook = setCrossFadeStateMethod
            if (cfStateHook != null) {
                var lastGuardLogMs = 0L
                MediaRuntime.hookMethod(cfStateHook, object : PluginMethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (param.throwable != null) return
                        if (ownStateCall.get() == true) return
                        val requested = param.args.getOrNull(0) as? Int ?: return
                        if (!runtimeEnabled) {
                            if (requested != STATE_AUTOMATIC) crossfadeStateApplied = false
                            return
                        }
                        if (requested != STATE_AUTOMATIC) {
                            param.args[0] = STATE_AUTOMATIC
                            crossfadeStateApplied = true
                            val now = android.os.SystemClock.elapsedRealtime()
                            if (now - lastGuardLogMs > 10_000L) {
                                lastGuardLogMs = now
                                flog("拦截宿主 setCrossFadeState($requested) -> 强制维持 AUTOMATIC(0)")
                            }
                        }
                    }
                }, scope).let { if (it) applied++ }
            }

            // ---- 0) UI 注入：队列页 AutoMix 按钮 ----
            AutoTransitionUi.enabled = runtimeEnabled
            AutoTransitionUi.showButton = showQueueButton()
            AutoTransitionUi.onToggle = { newState ->
                runtimeEnabled = newState
                if (newState) {
                    armNativeCrossfadeEngineIfNeeded()
                } else {
                    flog("auto_transition soft-disabled via UI (zero audio glitch)")
                }
                onRuntimeToggle?.invoke(newState)
                flog("UI toggled -> $newState (persisted, zero-stutter soft gate)")
            }
            val uiInstalled = AutoTransitionUi.install(scope, MediaRuntime.context.application, build.packageName)
            if (uiInstalled) applied++
            val pbInstalled = AutoTransitionProgressBar.install(scope, build.packageName)
            if (pbInstalled) applied++

            // ---- 1) 音频分析数据提供者（hook getAudioAnalysis + 主动获取）----
            val analysis = AudioAnalysisProvider(loader, analysisCacheFile)
            activeAnalysisProvider = analysis
            if (analysis.install(scope)) applied++

            // ---- 1.5) 捕获 ExoMediaPlayer 实例（多途径：构造函数 + 多个方法）----
            val exoPlayerCls = runCatching { loader.loadClass("com.apple.android.music.playback.player.ExoMediaPlayer") }.getOrNull()
            if (exoPlayerCls != null) {
                // hook 构造函数
                for (ctor in exoPlayerCls.declaredConstructors) {
                    ctor.isAccessible = true
                    if (MediaRuntime.observeMethod(ctor, object : PluginMethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (param.throwable == null) {
                                exoMediaPlayer = param.thisObject
                                flog("ExoMediaPlayer instance captured via constructor")
                                if (runtimeEnabled) armNativeCrossfadeEngineIfNeeded()
                            }
                        }
                    }, scope)) applied++
                }
                // hook getCrossFadeDuration / getCrossFadeState 作为兜底
                for (methodName in listOf("getCrossFadeDuration", "getCrossFadeState", "getCrossFadeMode")) {
                    val m = runCatching { exoPlayerCls.getDeclaredMethod(methodName) }.getOrNull()
                    m?.isAccessible = true
                    if (m != null && MediaRuntime.observeMethod(m, object : PluginMethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (param.throwable == null && exoMediaPlayer == null) {
                                exoMediaPlayer = param.thisObject
                                flog("ExoMediaPlayer instance captured via $methodName")
                                if (runtimeEnabled) armNativeCrossfadeEngineIfNeeded()
                            }
                        }
                    }, scope)) applied++
                }
                // 记录播放位置/时长，用于区分“自然结束”和“手动切歌”，同时兜底热激活。
                for (methodName in listOf("getCurrentPosition", "getDuration")) {
                    val m = runCatching { exoPlayerCls.getDeclaredMethod(methodName) }.getOrNull()
                    m?.isAccessible = true
                    if (m != null && MediaRuntime.observeMethod(m, object : PluginMethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (param.throwable != null) return
                            exoMediaPlayer = param.thisObject
                            if (runtimeEnabled && !crossfadeStateApplied) {
                                armNativeCrossfadeEngineIfNeeded()
                            }
                            val value = (param.result as? Long) ?: return
                            if (methodName == "getCurrentPosition") lastPositionMs = value else lastDurationMs = value
                        }
                    }, scope)) applied++
                }
            } else {
                flog("ExoMediaPlayer class NOT FOUND!")
            }

            // ---- PCM 采集状态（hook AudioTrack.write，本地音频特征提取）----
            // 必须在所有 hook 之前定义，因为 prepareForCrossFade/maybeStart 里都引用 latestLocalAnalysis
            val pcmBuffer = java.util.ArrayList<Short>(44100 * 20 * 2)
            var pcmAnalyzed = false
            /** 最近一次本地分析结果，切歌时注入到上一首歌曲 ID 的 localCache。 */
            var latestLocalAnalysis: SongAudioAnalysis? = null
            var latestLocalSongId: String? = null
            var localInjectedForId: String? = null
            val analyzeExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
            /** 当前播放歌曲 ID（用于 PCM 采集关联）。 */
            var currentSongId: String? = null

            // ---- 2) 核心：hook PlayerAudioFadeControl（正确签名，带 MediaPeriodHolder 参数）----
            val holderCls = runCatching { loader.loadClass("com.google.android.exoplayer2.MediaPeriodHolder") }.getOrNull()
            val fadeCls = runCatching { loader.loadClass("com.apple.android.music.playback.player.PlayerAudioFadeControl") }.getOrNull()
            if (holderCls == null) flog("MediaPeriodHolder class NOT FOUND!")
            if (fadeCls == null) flog("PlayerAudioFadeControl class NOT FOUND!")

            fun methodSig(cls: Class<*>?, name: String, vararg params: Class<*>): Method? {
                val m = runCatching { cls?.getDeclaredMethod(name, *params) }.getOrNull()
                m?.isAccessible = true
                if (m == null) flog("HOOK MISS: $name(${params.joinToString { it.simpleName }})")
                else flog("HOOK OK: $name(${params.joinToString { it.simpleName }})")
                return m
            }

            // prepareForCrossFade(MediaPeriodHolder, MediaPeriodHolder) —— 计算 plan 的时机
            val applePrepare = if (fadeCls != null && holderCls != null)
                methodSig(fadeCls, "prepareForCrossFade", holderCls, holderCls) else null
            if (applePrepare != null) {
                MediaRuntime.hookMethod(applePrepare, object : PluginMethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (param.throwable != null || !runtimeEnabled) return
                        // 新的过渡开始：prepare 阶段就要允许跳前奏（此时 period 还没被引擎锁死）
                        introSkipApplied = false
                        // 从 MediaPeriodHolder 获取当前/下一首的音频分析数据（主动调用 getAudioAnalysis）
                        // prepareForCrossFade 触发时 holder 一定就绪（crossfade 已开始），这里能拿到 BPM/调性
                        analysis.updateFromHolders(param.args[0], param.args[1], param.thisObject)
                        // 兜底：current 为 null 时用最近一次本地分析结果
                        if (analysis.current == null && latestLocalAnalysis != null) {
                            analysis.current = latestLocalAnalysis
                            flog("prepareForCrossFade 兜底 current: bpm=${latestLocalAnalysis!!.bpm} key=${latestLocalAnalysis!!.key}")
                        }
                        var plan = TransitionPlanner.plan(analysis.current, analysis.next, defaultDurationSec())
                        plan = applyAppleCue(plan, analysis.current)
                        activePlan = plan
                        activeFadeDurationMs = (plan.durationSec * 1000L).coerceAtLeast(1_500L)
                        if (!crossfadeInProgress) {
                            crossfadeStartTimeMs = android.os.SystemClock.elapsedRealtime()
                        }
                        crossfadeInProgress = true
                        BassSwapProcessor.reset()  // 重置低频交换滤波器状态
                        // 播放条辉光和 Mixing 标签的呼吸节奏跟随当前歌曲 BPM
                        coverView?.rootView?.let(AutoTransitionProgressBar::setRootView)
                        AutoTransitionProgressBar.setBpm(analysis.current?.bpm)
                        AutoTransitionProgressBar.setTransitionActive(true)
                        // 过渡总超时兜底：durationSec+8s 内 maybeStart false 没来就强制结束
                        transitionEndRunnable?.let { coverHoldHandler.removeCallbacks(it) }
                        val endRunnable = Runnable { finalizeCoverTransition() }
                        transitionEndRunnable = endRunnable
                        coverHoldHandler.postDelayed(endRunnable, (plan.durationSec * 1000L) + 8000L)
                        flog("transitionEndTimeout 已注册: ${plan.durationSec + 8}s")
                        val curSilence = analysis.current?.let { "尾静${"%.1f".format(it.endSilenceSec)}s" } ?: ""
                        val nxtSilence = analysis.next?.let { "首静${"%.1f".format(it.startSilenceSec)}s intro${"%.1f".format(it.introEndSec)}s" } ?: ""
                        flog("plan=${plan.reason} duration=${plan.durationSec}s speed=${plan.speedRatio} cur=${analysis.current?.bpm} nxt=${analysis.next?.bpm} $curSilence $nxtSilence")
                        // 写入引擎字段：prepareForCrossFade 在 maybeStartCrossFading 内部被调用，
                        // 引擎随后读取 crossFadeDuration 字段——在这里写（09:53 验证 crossfade 正常不提前结束）
                        setCrossFadeDurationField(param.thisObject, plan.durationSec)
                        // 设置 crossingTimeUs（微秒，float）：doCrossFade 完成路径的触发条件。
                        // 反编译确认：crossingTimeUs > 0 且 < 下一首时长时，doCrossFade 走完成路径，
                        // fadeIn 歌曲播放到 crossingTimeUs 时 crossfade 结束。fallback 路径下此值默认为 0，
                        // 导致完成路径永不触发，maybeStart true 卡死（日志铁证：持续 6 分钟）。
                        runCatching {
                            val f = param.thisObject.javaClass.getDeclaredField("crossingTimeUs")
                            f.isAccessible = true
                            f.setFloat(param.thisObject, plan.durationSec * 1_000_000f)
                            flog("crossingTimeUs -> ${plan.durationSec}s (触发完成路径)")
                        }.onFailure { flog("set crossingTimeUs failed: ${it.message}") }
                        // 首静音/Intro 跳过：如果下一首开头有较长静音或 intro，
                        // 反射设置 fadeInPeriodHolder 的起始位置，跳过空白段。
                        // 取首静音和 intro 结束位置的较大值（至少 1 秒才跳过）。
                        val skipSec = activePlan?.introSkipSec ?: 4f
                        if (skipIntro() && skipSec > 1f && !introSkipApplied) {
                            introSkipApplied = true
                            skipNextSongIntro(param.args[1], skipSec)
                            flog("skip intro(prepare): ${"%.1f".format(skipSec)}s (plan ${activePlan?.mode?.name})")
                        }
                        // 只对 fadeIn 单个 renderer 设置速度，避免全局 PlaybackParameters 导致双渲染 AudioTrack glitch。
                        if (plan.needsSpeedMatch && speedMatch()) applyRendererSpeed(param.thisObject, param.args.getOrNull(1), plan.speedRatio)
                    }
                }, scope).let { if (it) applied++ }
            }

            // ---- 封面动画状态已提升为类成员变量（coverView/currentCoverDrawable/lastCoverDrawable/coverDissolve/coverAnimator/noiseBitmap/waitingForCover/isSettingCover）----

            // maybeStartCrossFading(MediaPeriodHolder, MediaPeriodHolder, long)Z —— 观察，true→false 作为 crossfade 结束信号
            val longCls = Long::class.javaPrimitiveType
            val maybeStart = if (fadeCls != null && holderCls != null && longCls != null)
                methodSig(fadeCls, "maybeStartCrossFading", holderCls, holderCls, longCls) else null
            if (maybeStart != null) {
                var lastResult: Boolean? = null
                var lastPlanSec = -1
                MediaRuntime.hookMethod(maybeStart, object : PluginMethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (param.throwable != null) return
                        if (!runtimeEnabled) {
                            if (crossfadeStateApplied && !crossfadeInProgress) {
                                param.result = false
                            }
                            return
                        }
                        val fadeControl = param.thisObject
                        // 先保存旧歌曲 ID，再更新当前 ID（切歌时注入需要旧 ID）
                        val prevSongId = currentSongId
                        val resolvedId = param.args.getOrNull(0)?.let { analysis.extractIdFromHolder(it) }
                        if (!resolvedId.isNullOrBlank()) {
                            currentSongId = resolvedId
                        }
                        // 把本地分析结果仅注入一次到对应歌曲 ID，避免每帧刷写与串歌。
                        val localNow = latestLocalAnalysis
                        if (localNow != null) {
                            val targetId = latestLocalSongId ?: currentSongId ?: prevSongId
                            if (!targetId.isNullOrBlank() && targetId != localInjectedForId) {
                                analysis.injectLocalAnalysis(targetId, localNow, fromApple = false)
                                latestLocalSongId = targetId
                                localInjectedForId = targetId
                            }
                        }
                        // maybeStartCrossFading 每帧被调用，只在 holder 变化时重算（切歌时）
                        val changed = analysis.updateFromHoldersIfChanged(param.args[0], param.args[1], fadeControl)
                        // 每次调用都兜底：current 为 null 时用最近分析结果（不只是 changed 时）
                        if (analysis.current == null && latestLocalAnalysis != null) {
                            analysis.current = latestLocalAnalysis
                            flog("用最近分析兜底 current: bpm=${latestLocalAnalysis!!.bpm} key=${latestLocalAnalysis!!.key}")
                        }
                        // 每次都用最新的 current/next 重算 plan（不只是 changed 时）
                        // 因为 current 可能在兜底后才变成非 null，需要立即重算并写入字段
                        if (analysis.current != null) {
                            var plan = TransitionPlanner.plan(analysis.current, analysis.next, defaultDurationSec())
                            plan = applyAppleCue(plan, analysis.current)
                            activePlan = plan
                            AutoTransitionProgressBar.setBpm(analysis.current?.bpm)
                            if (plan.durationSec != lastPlanSec) {
                                lastPlanSec = plan.durationSec
                                setCrossFadeDurationField(fadeControl, plan.durationSec)
                                flog("plan(pre)=${plan.reason} duration=${plan.durationSec}s speed=${plan.speedRatio} cur=${analysis.current?.bpm} nxt=${analysis.next?.bpm}")
                            }
                        }
                        if (changed) {
                            pcmBuffer.clear()
                            pcmAnalyzed = false
                            latestLocalAnalysis = null
                            latestLocalSongId = null
                            localInjectedForId = null
                            if (analysis.current == null && analysis.next == null) {
                                analysis.updateFromHolders(param.args[1], param.args[0], fadeControl)
                            }
                        }
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.throwable != null || !runtimeEnabled) return
                        if (param.result == false) {
                            val fc = param.thisObject
                            val rPos = (param.args.getOrNull(2) as? Long) ?: 0L
                            if (fc != null && tryRescueCrossFadeStart(fc, param.args.getOrNull(0), param.args.getOrNull(1), rPos)) {
                                param.result = true
                            }
                        }
                        val result = param.result as? Boolean
                        if (result != lastResult) {
                            lastResult = result
                            flog("maybeStartCrossFading -> $result")
                            if (result == true) {
                                val planNow = activePlan
                                val fallbackSec = effectiveFallbackSec()
                                coverView?.rootView?.let(AutoTransitionProgressBar::setRootView)
                                AutoTransitionProgressBar.setBpm(analysis.current?.bpm)
                                AutoTransitionProgressBar.setTransitionActive(true)
                                if (useEqualizer()) {
                                    AutoMixEqualizer.start(
                                        (planNow?.durationSec ?: fallbackSec) * 1000L,
                                        planNow?.eqDepthDb ?: 6f,
                                        planNow?.mode?.name ?: "FILTER_MIX",
                                    )
                                }
                                val skip = planNow?.introSkipSec ?: 0f
                                if (skipIntro() && skip > 1f && !introSkipApplied) {
                                    introSkipApplied = true
                                    skipNextSongIntro(param.args.getOrNull(1), skip)
                                    flog("skip intro(maybeStart): ${"%.1f".format(skip)}s (plan ${planNow?.mode?.name})")
                                }
                                startActualTransitionLog(param.thisObject, param.args.getOrNull(0), param.args.getOrNull(1), planNow)
                                // 音乐过渡开始：触发噪声溶解动画
                                // 封面切换可能发生在 crossfade 开始之后（日志证实差约1秒），
                                // 所以如果 lastCover/pendingNewCover 还没缓存，设置 waitingForCover，等封面切换后补触发
                                val v = coverView
                                val oldCover = lastCoverDrawable
                                val newCover = pendingNewCover
                                flog("cover: maybeStart true, coverView=${v != null} lastCover=${oldCover != null} pendingNew=${newCover != null}")
                                if (true) {
                                    if (v != null && oldCover != null && newCover != null) {
                                        waitingForCover = false
                                        triggerCoverDissolve(v, oldCover, newCover)
                                    } else {
                                        waitingForCover = true
                                        flog("cover: crossfade 已开始但封面未切换，等待 setImageDrawable 后补触发")
                                    }
                                }
                            }
                            // true→false 表示 crossfade 结束
                            if (result == false && crossfadeInProgress) {
                                endActualTransitionLog()
                                finalizeCoverTransition()
                                flog("crossfade finished (maybeStart false)")
                            }
                        }
                    }
                }, scope).let { if (it) applied++ }
            }

            // isCrossFadeEnabled()Z —— 确保引擎认为已启用
            val isEnabled = methodSig(fadeCls, "isCrossFadeEnabled")
            if (isEnabled != null) {
                MediaRuntime.hookMethod(isEnabled, object : PluginMethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (param.throwable != null) return
                        if (!runtimeEnabled) {
                            if (crossfadeStateApplied && !crossfadeInProgress) {
                                param.result = false
                            }
                            return
                        }
                        param.result = true
                    }
                }, scope).let { if (it) applied++ }
            }

            // isTransitionDataAvailable()Z —— 强制走 fallback 路径
            // 有 AudioAnalysis 数据的歌曲，引擎走 Automation 路径（Apple 自己的淡化曲线），
            // crossFadeDuration 完全不参与（日志为证：plan=12s/15s 但实际淡化由 automation 决定）。
            // hook 返回 false → 引擎走 fallback → 用 MediaPlayerContext.getCrossFadeDuration()（我们的智能时长）
            // → LevelComposer 线性淡化。代价：丢失 Apple 的 automation 音量曲线，但获得我们的乐句对齐动态时长。
            val isTransData = methodSig(fadeCls, "isTransitionDataAvailable")
            if (isTransData != null) {
                MediaRuntime.hookMethod(isTransData, object : PluginMethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (param.throwable != null || !runtimeEnabled) return
                        if (!preferAppleCurve()) {
                            param.result = false
                        }
                    }
                }, scope).let { if (it) applied++ }
            }

            // ---- 提升触发率 1) 解锁同专辑连续曲目：areSequentialItems(item1, item2) -> false ----
            // 宿主 canFadeBetweenPeriods 实际调用 PlayerAudioFadeControl$Companion.areSequentialItems，
            // 同时兼容直接挂在 fadeCls 上的签名。
            val companionCls = runCatching {
                loader.loadClass("com.apple.android.music.playback.player.PlayerAudioFadeControl\$Companion")
            }.getOrNull()
            val seqTargets = listOfNotNull(fadeCls, companionCls).flatMap { cls ->
                cls.declaredMethods.filter { it.name == "areSequentialItems" && it.parameterCount == 2 }
                    .onEach { it.isAccessible = true }
            }
            if (seqTargets.isNotEmpty()) {
                var lastBypassLogMs = 0L
                val seqHook = object : PluginMethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (param.throwable != null || !runtimeEnabled || !allowAlbumSequential()) return
                        param.result = false
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - lastBypassLogMs > 10_000L) {
                            lastBypassLogMs = now
                            flog("areSequentialItems -> false（已放行同专辑连续曲目过渡）")
                        }
                    }
                }
                var hookedCount = 0
                for (m in seqTargets) {
                    if (MediaRuntime.hookMethod(m, seqHook, scope)) {
                        applied++
                        hookedCount++
                    }
                }
                if (hookedCount > 0) flog("areSequentialItems hook OK ($hookedCount targets)")
            }

            // ---- 提升触发率 2) 适配单曲循环：areTheSameMediaPeriods(out, in) -> false ----
            val samePeriodMethod = if (fadeCls != null && holderCls != null)
                methodSig(fadeCls, "areTheSameMediaPeriods", holderCls, holderCls) else null
            if (samePeriodMethod != null) {
                var lastRepeatLogMs = 0L
                MediaRuntime.hookMethod(samePeriodMethod, object : PluginMethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (param.throwable != null || !runtimeEnabled || !allowSingleRepeat()) return
                        param.result = false
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - lastRepeatLogMs > 10_000L) {
                            lastRepeatLogMs = now
                            flog("areTheSameMediaPeriods -> false（已放行单曲循环）")
                        }
                    }
                }, scope).let { if (it) { applied++; flog("areTheSameMediaPeriods hook OK") } }
            }

            // ---- 提升触发率 3) canFadeBetweenPeriods 预兜底 & repeatMode==1 放行 ----
            val canFadeMethod = if (fadeCls != null && holderCls != null)
                methodSig(fadeCls, "canFadeBetweenPeriods", holderCls, holderCls) else null
            if (canFadeMethod != null) {
                var lastBlockLogMs = 0L
                var lastPreWarmMs = 0L
                MediaRuntime.hookMethod(canFadeMethod, object : PluginMethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (param.throwable != null) return
                        if (!runtimeEnabled) {
                            if (crossfadeStateApplied && !crossfadeInProgress) {
                                param.result = false
                            }
                            return
                        }
                        val fc = param.thisObject ?: return
                        val map = readFieldSafe(fc, "transitionsMap") as? Map<*, *>
                        if (map != null && map.isEmpty()) {
                            val now = android.os.SystemClock.elapsedRealtime()
                            if (now - lastPreWarmMs > 4_000L) {
                                lastPreWarmMs = now
                                applyCrossfadeDuration(effectiveFallbackSec())
                            }
                        }
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.throwable != null || !runtimeEnabled) return
                        if (param.result != false) return
                        val outHolder = param.args.getOrNull(0)
                        val inHolder = param.args.getOrNull(1)
                        val fc = param.thisObject ?: return
                        val map = readFieldSafe(fc, "transitionsMap") as? Map<*, *>
                        val mapSize = map?.size ?: -1
                        val mp = readFieldSafe(fc, "mediaPlayer")
                        val repeatMode = runCatching {
                            mp?.javaClass?.getMethod("getRepeatMode")?.invoke(mp) as? Int
                        }.getOrNull() ?: -1

                        if (outHolder != null && inHolder != null && mapSize >= 2 &&
                            repeatMode == 1 && allowSingleRepeat()
                        ) {
                            param.result = true
                            return
                        }

                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - lastBlockLogMs > 8_000L) {
                            lastBlockLogMs = now
                            val outPrep = outHolder?.let { readFieldSafe(it, "prepared") as? Boolean }
                            val inPrep = inHolder?.let { readFieldSafe(it, "prepared") as? Boolean }
                            flog("canFadeBetweenPeriods=false (out=${outHolder != null}, outPrep=$outPrep, in=${inHolder != null}, inPrep=$inPrep, transitionsMap=$mapSize, repeatMode=$repeatMode)")
                        }
                    }
                }, scope).let { if (it) { applied++; flog("canFadeBetweenPeriods hook OK") } }
            }

            // calculateFadeInLevel(F)F —— 等功率交叉淡化曲线（cos²/sin²）
            // 原生用线性或指数曲线，中间点会有音量凹陷。等功率曲线保证重叠区总能量恒定。
            // 只在 crossfade 期间替换，其他场景（播放/暂停淡入淡出）保持原生曲线。
            // 关键修复：fallback 路径（isTransitionDataAvailable=false）下原生进度公式错误
            // （(fadeOut.startPositionUs - fadeInCurrentPos) / duration，startPositionUs=0 → 恒负），
            // 导致 fadeInLevel 恒为 0。这里用 crossfade 已进行时间 / 总时长 计算正确进度。
            val floatCls = Float::class.javaPrimitiveType
            val calcFadeIn = if (floatCls != null) methodSig(fadeCls, "calculateFadeInLevel", floatCls) else null
            if (calcFadeIn != null) {
                MediaRuntime.hookMethod(calcFadeIn, object : PluginMethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (param.throwable != null || !runtimeEnabled) return
                        if (!crossfadeInProgress) return  // 非 crossfade 期间保持原生曲线
                        val durationMs = (activePlan?.durationSec ?: effectiveFallbackSec()) * 1000L
                        val elapsed = android.os.SystemClock.elapsedRealtime() - crossfadeStartTimeMs
                        val progress = (elapsed.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                        // 等功率淡入：sin²(progress * π/2)，从 0 平滑到 1
                        val equalPower = Math.sin(progress * Math.PI / 2.0).let { it * it }.toFloat()
                        param.result = equalPower
                    }
                }, scope).let { if (it) { applied++; flog("calculateFadeInLevel hook OK（等功率曲线+时间进度）") } }
            }

            // ---- 2.6) 本地音频特征提取（hook AudioTrack.write，不依赖 Apple AudioAnalysis）----
            // DefaultAudioSink hook 不生效（Apple 用 SVMediaCodecAudioRenderer/SVAudioRendererV2，不走 ExoPlayer sink）。
            // 换 AudioTrack.write——所有音频最终都写到这里，无法绕过。从 AudioTrack.getSampleRate/getChannelCount
            // 获取格式。采集当前播放歌曲的 PCM，后台分析，结果在切歌时注入 localCache。
            // 采集 10 秒 PCM：BPM 检测 8 秒以上即可（@120BPM = 16拍=1乐句），
            // 缩短采集时长让下一首在 crossfade 结束后更快被分析，减少 next=null 的窗口
            val TARGET_SECONDS = 10

            runCatching {
                val audioTrackCls = android.media.AudioTrack::class.java
                // write(ByteBuffer audioData, int sizeInBytes, int writeMode) —— 最底层 PCM 入口
                val writeMethod = audioTrackCls.getDeclaredMethod("write",
                    java.nio.ByteBuffer::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType
                ).apply { isAccessible = true }
                MediaRuntime.hookMethod(writeMethod, object : PluginMethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (param.throwable != null || !runtimeEnabled) return
                        val track = param.thisObject as? android.media.AudioTrack ?: return
                        val buf = param.args[0] as? java.nio.ByteBuffer ?: return
                        val sizeInBytes = param.args[1] as? Int ?: 0
                        if (sizeInBytes <= 0 || !buf.hasRemaining()) return
                        val sampleRate = runCatching { track.sampleRate }.getOrNull() ?: 44100
                        val channels = runCatching { track.channelCount }.getOrNull() ?: 2
                        // 检测音频格式：ENCODING_PCM_FLOAT=4 时 ByteBuffer 里是 float，不是 short
                        val audioFormat = runCatching { track.audioFormat }.getOrNull() ?: android.media.AudioFormat.ENCODING_PCM_16BIT
                        val isFloatPcm = audioFormat == android.media.AudioFormat.ENCODING_PCM_FLOAT

                        // crossfade 期间跳过 PCM 采集（不修改音频数据，避免原地修改 ByteBuffer 导致嘶嘶声）
                        // 等功率音量曲线已通过 calculateFadeInLevel hook 实现
                        if (crossfadeInProgress) return
                        if (pcmAnalyzed) return
                        if (analysis.hasAppleAnalysis(currentSongId)) return
                        val targetSamples = TARGET_SECONDS * sampleRate * channels
                        if (pcmBuffer.size >= targetSamples) {
                            pcmAnalyzed = true
                            val pcmCopy = pcmBuffer.toShortArray()
                            val sr = sampleRate
                            val ch = channels
                            val capturedId = currentSongId
                            analyzeExecutor.execute {
                                runCatching {
                                    val result = AudioFeatureExtractor.extract(pcmCopy, sr, ch)
                                    if (result.usable) {
                                        latestLocalAnalysis = result
                                        val injectId = capturedId ?: currentSongId
                                        latestLocalSongId = injectId
                                        localInjectedForId = injectId
                                        injectId?.let { id -> runCatching { analysis.injectLocalAnalysis(id, result, fromApple = false) } }
                                        FileLogger.log("本地分析完成 id=$injectId bpm=${result.bpm} key=${result.key} energy=${result.energy} dance=${result.danceability} beats=${result.beats?.size ?: 0} fmt=${if (isFloatPcm) "float" else "short"}")
                                    } else {
                                        FileLogger.log("本地分析不可用 bpm=${result.bpm} key=${result.key}")
                                    }
                                }.onFailure { FileLogger.log("PCM分析异常: ${it.message}") }
                            }
                            return
                        }
                        // 根据格式读取 PCM：float → 归一化到 short 范围；short → 直接读
                        if (isFloatPcm) {
                            val floatBuf = buf.asFloatBuffer()
                            val floatCount = sizeInBytes / 4  // 1 float = 4 bytes
                            val toRead = minOf(floatBuf.remaining(), floatCount, targetSamples - pcmBuffer.size)
                            if (toRead <= 0) return
                            val tmp = FloatArray(toRead)
                            floatBuf.get(tmp)
                            if (pcmBuffer.isEmpty()) {
                                var peak = 0
                                for (f in tmp) {
                                    val a = kotlin.math.abs((f * 32767f).toInt())
                                    if (a > peak) peak = a
                                }
                                if (peak < 400) return
                            }
                            for (f in tmp) {
                                // float [-1,1] → short [-32768,32767]
                                val s = (f * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                                pcmBuffer.add(s)
                            }
                        } else {
                            val shortBuf = buf.asShortBuffer()
                            val shortBytes = sizeInBytes / 2
                            val toRead = minOf(shortBuf.remaining(), shortBytes, targetSamples - pcmBuffer.size)
                            if (toRead <= 0) return
                            val tmp = ShortArray(toRead)
                            shortBuf.get(tmp)
                            if (pcmBuffer.isEmpty()) {
                                var peak = 0
                                for (s in tmp) {
                                    val a = kotlin.math.abs(s.toInt())
                                    if (a > peak) peak = a
                                }
                                if (peak < 400) return
                            }
                            for (s in tmp) pcmBuffer.add(s)
                        }
                    }
                }, scope).let { if (it) { applied++; flog("AudioTrack.write hook OK（本地PCM采集已启动）") } }
            }.onFailure { flog("hook AudioTrack.write 失败: ${it.message}") }

            // getCrossFadeDuration()I —— 返回智能时长（秒，int！不是微秒）
            // 注意：PlayerAudioFadeControl 自己的 getCrossFadeDuration() 无人调用（日志为证）；
            // 引擎真正消费的时长来自 MediaPlayerContext.getCrossFadeDuration()（见下方 2.5 hook）。
            // 这里保留仅为兜底防御。
            val appleDuration = methodSig(fadeCls, "getCrossFadeDuration")
            if (appleDuration != null) {
                var lastLoggedSec = -1
                MediaRuntime.hookMethod(appleDuration, object : PluginMethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (param.throwable != null || !runtimeEnabled) return
                        // 实时计算，不依赖 activePlan（调用时机可能早于 activePlan 设置）
                        val sec = activePlan?.durationSec
                            ?: TransitionPlanner.plan(analysis.current, analysis.next, defaultDurationSec()).durationSec
                        param.result = sec
                        if (sec != lastLoggedSec) {
                            lastLoggedSec = sec
                            flog("getCrossFadeDuration() -> $sec s (实时计算)")
                        }
                    }
                }, scope).let { if (it) applied++ }
            }

            // ---- 2.5) 真正生效的时长入口：hook BaseMediaPlayerContext.getCrossFadeDuration() ----
            // 反编译确认：BaseMediaPlayerContext 实现读 preferences.getCrossFadeDuration()（Apple 设置）。
            // 注意：prepareForCrossFade 本身不调用此方法，真正调用点在 computeTransitionJob/setCrossFadeState
            // 等预计算阶段，可能早于 activePlan 被设置——因此这里必须实时计算 plan，不能读 activePlan。
            runCatching {
                val baseCtxCls = loader.loadClass("com.apple.android.music.playback.player.BaseMediaPlayerContext")
                val ctxDuration = baseCtxCls.getDeclaredMethod("getCrossFadeDuration").apply { isAccessible = true }
                var lastCtxSec = -1
                MediaRuntime.hookMethod(ctxDuration, object : PluginMethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (param.throwable != null || !runtimeEnabled) return
                        // 实时计算 plan：从 analysis.current/next 算动态时长，不依赖 activePlan
                        val sec = activePlan?.durationSec
                            ?: TransitionPlanner.plan(analysis.current, analysis.next, defaultDurationSec()).durationSec
                        param.result = sec
                        if (sec != lastCtxSec) {
                            lastCtxSec = sec
                            flog("MediaPlayerContext.getCrossFadeDuration() -> $sec s (实时计算，引擎真实消费入口)")
                        }
                    }
                }, scope).let { if (it) applied++ }
            }.onFailure { flog("hook BaseMediaPlayerContext.getCrossFadeDuration failed: ${it}") }

            // doCrossFade(MediaPeriodHolder, MediaPeriodHolder, long)V —— 直接在公开接口出口以等功率曲线驱动双路渲染器音量
            val appleDo = if (fadeCls != null && holderCls != null && longCls != null)
                methodSig(fadeCls, "doCrossFade", holderCls, holderCls, longCls) else null
            if (appleDo != null) {
                MediaRuntime.hookMethod(appleDo, object : PluginMethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.throwable != null || !runtimeEnabled || !crossfadeInProgress || preferAppleCurve()) return
                        val fc = param.thisObject ?: return
                        val phaseName = (readFieldSafe(fc, "fadePhase") as? Enum<*>)?.name
                        if (phaseName != "FADE_OUT" && phaseName != "FADE_IN") return

                        val elapsedMs = (android.os.SystemClock.elapsedRealtime() - crossfadeStartTimeMs).coerceAtLeast(0L)
                        val durMs = activeFadeDurationMs.coerceAtLeast(1_500L)
                        val progress = (elapsedMs.toFloat() / durMs.toFloat()).coerceIn(0f, 1f)
                        val angle = progress * (Math.PI / 2.0)
                        val outVol = kotlin.math.cos(angle).let { it * it }.toFloat().coerceIn(0f, 1f)
                        val inVol = kotlin.math.sin(angle).let { it * it }.toFloat().coerceIn(0f, 1f)
                        driveRendererVolumes(fc, param.args.getOrNull(0), param.args.getOrNull(1), outVol, inVol)

                        val nowMs = android.os.SystemClock.elapsedRealtime()
                        if (nowMs - lastVolumeLogMs > 1_500L) {
                            lastVolumeLogMs = nowMs
                            flog("doCrossFade vol: p=${"%.2f".format(progress)} out=${"%.2f".format(outVol)} in=${"%.2f".format(inVol)} dur=${durMs / 1000}s phase=$phaseName")
                        }
                    }
                }, scope).let { if (it) { applied++; flog("doCrossFade equal-power volume drive OK") } }
            }

            // ---- 3) 切歌封面溶解式形变动画（LayerDrawable 交叉淡化+scale）----
            // 时机：setImageDrawable 时缓存新封面但保持旧封面显示；maybeStart true（音乐过渡开始）
            // 时触发溶解；maybeStart false（过渡结束）时确保完成。动画与音乐 crossfade 完全同步。
            val imageId = PluginProfiles.find(build.packageName, build.versionName, build.versionCode)
                ?.document?.optJSONObject("playerRecovery")?.optString("imageId")
            flog("cover hook: animate=${animate()} imageId=$imageId")
            val setImageDrawable = android.widget.ImageView::class.java.getDeclaredMethod("setImageDrawable", android.graphics.drawable.Drawable::class.java)
            if (animate() && !imageId.isNullOrBlank() && MediaRuntime.hookMethod(setImageDrawable, object : PluginMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null || !runtimeEnabled) return
                    try {
                        val view = param.thisObject as? android.widget.ImageView ?: return
                        val targetId = view.resources.getIdentifier(imageId, "id", build.packageName)
                        if (view.id != targetId) {
                            return
                        }
                        if (isSettingCover[view] == true) return  // 正在设置 transition，让原方法执行
                        // 捕获 rootView 给 ProgressBar 用于查找音质标签
                        AutoTransitionProgressBar.setRootView(view.rootView)

                        val newDrawable = param.args[0] as? android.graphics.drawable.Drawable
                        // 全屏播放页打开时，云同步歌曲的封面常常先被宿主清成白色占位，
                        // 再异步加载。底栏已经显示过同一首的封面，这里直接用缓存顶上。
                        if (coverDissolve == null && pendingNewCover == null) {
                            if (newDrawable != null && !isBlankArtwork(newDrawable)) {
                                val w = if (view.width > 1) view.width else 512
                                val h = if (view.height > 1) view.height else 512
                                runCatching {
                                    val bmp = drawableToBitmap(newDrawable, w, h)
                                    cachedArtwork = bmp
                                    cachedArtworkAt = android.os.SystemClock.elapsedRealtime()
                                    val id = currentSongId
                                    if (id != null && !crossfadeInProgress && pendingNewCover == null) {
                                        artworkBySong[id] = bmp
                                    }
                                    flog("cover: 缓存底栏封面 ${w}x$h song=$id")
                                }
                            } else {
                                val song = currentSongId
                                val bmp = song?.let { artworkBySong[it] }
                                if (bmp != null) {
                                isSettingCover[view] = true
                                view.setImageBitmap(bmp)
                                view.alpha = 1f
                                view.imageAlpha = 255
                                view.visibility = android.view.View.VISIBLE
                                isSettingCover[view] = false
                                param.result = null
                                flog("cover: 白屏占位，使用底栏封面缓存直接显示 ${bmp.width}x${bmp.height} song=$song alpha=${view.alpha}")
                                return
                                }
                            }
                        }

                        // 关键：封面溶解动画进行中（coverDissolve != null）时，阻止引擎的 setImageDrawable 调用
                        // 否则引擎会在 crossfade 期间再次设置封面，把我们的 DissolveDrawable 替换掉，动画被打断
                        if (coverDissolve != null) {
                            flog("cover: 动画进行中，阻止 setImageDrawable 调用")
                            param.result = null
                            return
                        }

                        // 核心逻辑：切歌时阻止新封面直接显示，保持旧封面，等 crossfade 开始时再做溶解
                        // 否则会"先闪一下新封面，再闪回旧封面开始动画"
                        if (newDrawable != null && newDrawable !is android.graphics.drawable.LayerDrawable) {
                            if (currentCoverDrawable != null && currentCoverDrawable !== newDrawable && pendingNewCover == null) {
                                // 手动切歌（未进入 crossfade、且当前曲离结束还很远）不要触发 AutoMix 动画。
                                if (!crossfadeInProgress && lastDurationMs > 0L &&
                                    lastDurationMs - lastPositionMs > 15_000L
                                ) {
                                    flog("cover: 手动切歌，跳过过渡动画")
                                    currentCoverDrawable = newDrawable.constantState?.newDrawable()?.mutate() ?: newDrawable
                                    return
                                }
                                // 这是一次封面切换：缓存旧封面和新封面，阻止引擎设置新封面
                                lastCoverDrawable = currentCoverDrawable
                                lastCoverDrawable?.let { old -> ensureCoverOverlay(view, old) }
                                pendingNewCover = newDrawable.constantState?.newDrawable()?.mutate() ?: newDrawable
                                coverView = view
                                flog("cover: 切歌，阻止新封面直接显示，保持旧封面，等待 crossfade 开始溶解")

                                // 取消之前的超时兜底
                                coverHoldRunnable?.let { coverHoldHandler.removeCallbacks(it) }
                                // 超时兜底：如果 8 秒内 crossfade 还没开始，就允许显示新封面（避免一直停在旧封面）
                                val holdView = view
                                val holdNew = pendingNewCover
                                val runnable = Runnable {
                                    runCatching {
                                        if (coverDissolve == null && !crossfadeInProgress && pendingNewCover === holdNew) {
                                            flog("cover: 超时兜底，允许显示新封面")
                                            isSettingCover[holdView] = true
                                            holdView.setImageDrawable(holdNew)
                                            currentCoverDrawable = holdNew
                                            isSettingCover[holdView] = false
                                            pendingNewCover = null
                                            lastCoverDrawable = null
                                        }
                                    }
                                }
                                coverHoldRunnable = runnable
                                coverHoldHandler.postDelayed(runnable, 8000)

                                // 封面已经切换：无论引擎是否走 maybeStartCrossFading，都立即做视觉过渡。
                                // 真机日志：部分曲目 maybeStartCrossFading 始终 false（引擎硬切），
                                // 只等它会导致“封面直接跳新图/白闪”，没有任何动画。
                                val v = view
                                val old = lastCoverDrawable
                                val new = pendingNewCover
                                if (old != null && new != null) {
                                    waitingForCover = false
                                    runCatching { AutoTransitionProgressBar.setTransitionActive(true) }
                                    view.post {
                                        runCatching { triggerCoverDissolve(v, old, new) }
                                    }
                                } else if (waitingForCover && crossfadeInProgress) {
                                    waitingForCover = false
                                    view.post {
                                        runCatching {
                                            if (old != null && new != null) triggerCoverDissolve(v, old, new)
                                        }
                                    }
                                }
                                // 阻止引擎设置新封面（保持旧封面显示）
                                param.result = null
                                return
                            }
                            // 非切换场景（首次设置封面、或同一张封面）：让原方法正常执行，
                            // 执行后更新 currentCoverDrawable（否则它永远是 null，后续切歌无法缓存 lastCover）
                            if (newDrawable != null && newDrawable !is android.graphics.drawable.LayerDrawable && currentCoverDrawable == null) {
                                val newRef = newDrawable
                                view.post {
                                    runCatching {
                                        val actual = view.drawable
                                        currentCoverDrawable = if (actual != null && actual !is android.graphics.drawable.LayerDrawable) {
                                            actual.constantState?.newDrawable()?.mutate() ?: actual
                                        } else {
                                            newRef.constantState?.newDrawable()?.mutate() ?: newRef
                                        }
                                        flog("cover: currentCoverDrawable 已更新（首次设置）")
                                    }
                                }
                            }
                        }
                        // 让原方法正常执行（不阻止封面替换）
                        return
                    } catch (e: Throwable) {
                        flog("cover: 缓存异常 ${e.message}")
                    }
                }
            }, scope)) applied++

            // 真机日志：切歌时 artwork_image 的 setImageDrawable 参数经常是 null（new=false），
            // 新封面实际通过 setImageBitmap 设置，导致上面的 hook 完全漏掉切歌。
            // 这里补一个 setImageBitmap hook，并把封面过渡解耦（不依赖 maybeStartCrossFading）。
            runCatching {
                // 底栏封面和播放页封面可能是不同 ImageView；全局缓存任意一个可见封面。
            MediaRuntime.observeMethod(setImageDrawable, object : PluginMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null) return
                    runCatching {
                        val view = param.thisObject as? android.widget.ImageView ?: return
                        val d = param.args[0] as? android.graphics.drawable.Drawable ?: return
                        if (isBlankArtwork(d)) return
                        val entry = runCatching { view.resources.getResourceEntryName(view.id) }.getOrDefault("")
                        if (!(entry.contains("artwork") || entry.contains("album") ||
                                entry.contains("cover") || entry.contains("song"))) return
                        val w = view.width; val h = view.height
                        if (w <= 1 || h <= 1) return
                        if (w > 700 || h > 700) return
                        if (android.os.SystemClock.elapsedRealtime() - cachedArtworkAt < 500L) return
                        cachedArtwork = drawableToBitmap(d, w, h)
                        cachedArtworkAt = android.os.SystemClock.elapsedRealtime()
                        flog("cover: global cache ${w}x$h id=${view.id}")
                    }
                }
            }, scope)

            val setImageBitmap = android.widget.ImageView::class.java.getDeclaredMethod(
                    "setImageBitmap", android.graphics.Bitmap::class.java,
                )
                if (animate() && !imageId.isNullOrBlank()) {
                    val hooked = MediaRuntime.hookMethod(setImageBitmap, object : PluginMethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (param.throwable != null || !runtimeEnabled) return
                            val view = param.thisObject as? android.widget.ImageView ?: return
                            val targetId = view.resources.getIdentifier(imageId, "id", build.packageName)
                            if (view.id != targetId) return
                            val bitmap = param.args[0] as? android.graphics.Bitmap ?: return
                            if (isSettingCover[view] == true || coverDissolve != null) return
                            val previous = view.drawable ?: return
                            val incoming = android.graphics.drawable.BitmapDrawable(view.resources, bitmap)
                            if (!crossfadeInProgress && lastDurationMs > 0L &&
                                lastDurationMs - lastPositionMs > 15_000L
                            ) {
                                currentCoverDrawable = incoming.constantState?.newDrawable()?.mutate() ?: incoming
                                flog("cover: 手动切歌(setImageBitmap)，跳过过渡动画")
                                return
                            }
                            val oldCopy = previous.constantState?.newDrawable()?.mutate() ?: previous
                            val newCopy = incoming.constantState?.newDrawable()?.mutate() ?: incoming
                            lastCoverDrawable = oldCopy
                            ensureCoverOverlay(view, oldCopy)
                            pendingNewCover = newCopy
                            currentCoverDrawable = oldCopy
                            coverView = view
                            waitingForCover = false
                            AutoTransitionProgressBar.setRootView(view.rootView)
                            AutoTransitionProgressBar.setTransitionActive(true)
                            param.result = null
                            view.post { runCatching { triggerCoverDissolve(view, oldCopy, newCopy) } }
                            val visualEndDelay = ((activePlan?.durationSec ?: effectiveFallbackSec()) * 1000L + 2_000L)
                                .coerceAtLeast(8_000L)
                            scheduleVisualFinalize(visualEndDelay)
                            flog("cover: setImageBitmap -> fallback dissolve (old=${previous.javaClass.simpleName})")
                        }
                    }, scope)
                    if (hooked) applied++
                }
            }.onFailure { flog("cover: setImageBitmap hook failed: ${it.message}") }

            scope.onClose {
                runtimeEnabled = false
                if (crossfadeStateApplied) {
                    val holder = exoMediaPlayer
                    val setter = setCrossFadeStateMethod
                    if (holder != null && setter != null) {
                        runCatching {
                            ownStateCall.set(true)
                            try { setter.invoke(holder, STATE_OFF) } finally { ownStateCall.remove() }
                        }
                    }
                    crossfadeStateApplied = false
                }
                AutoMixEqualizer.stop()
                activeAnalysisProvider = null
                analysis.close()
                analyzeExecutor.shutdownNow()
            }
            scope.activate()
            return TargetCapabilityInstall.Active(
                "AI DJ 自动过渡已启用：音频分析接入 + Camelot 调性匹配 + BPM 变速匹配 + 智能淡化曲线" +
                    if (applied > 0) "（$applied 处 hook）" else "（hook 未命中，可能版本不兼容）")
        } catch (error: Throwable) { scope.close(); throw error }
    }

    /** 在淡化期间对下一首设置播放速度（beat match）。通过 ExoMediaPlayer.player 字段拿 ExoPlayer 实例。 */
    private fun applySpeedMatch(ratio: Float) {
        val holder = exoMediaPlayer ?: return
        runCatching {
            val playerField = holder.javaClass.getDeclaredField("player").apply { isAccessible = true }
            val exoPlayer = playerField.get(holder) ?: return
            val paramsClass = loader.loadClass("com.google.android.exoplayer2.PlaybackParameters")
            // 优先 (speed, pitch) 构造，pitch=1 保持音调不变；失败回退单参数 (speed)
            val params = runCatching {
                paramsClass.getConstructor(Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
                    .newInstance(ratio, 1f)
            }.getOrElse {
                paramsClass.getConstructor(Float::class.javaPrimitiveType).newInstance(ratio)
            }
            exoPlayer.javaClass.getMethod("setPlaybackParameters", paramsClass).invoke(exoPlayer, params)
            flog("beat match applied speed=%.2f pitch=1.0".format(ratio))
        }.onFailure { flog("speed match failed: ${it}") }
    }

    /** 淡化结束后恢复原速。 */
    private fun restoreSpeed() {
        val holder = exoMediaPlayer ?: return
        runCatching {
            val playerField = holder.javaClass.getDeclaredField("player").apply { isAccessible = true }
            val exoPlayer = playerField.get(holder) ?: return
            val paramsClass = loader.loadClass("com.google.android.exoplayer2.PlaybackParameters")
            val params = runCatching {
                paramsClass.getConstructor(Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
                    .newInstance(1f, 1f)
            }.getOrElse {
                paramsClass.getConstructor(Float::class.javaPrimitiveType).newInstance(1f)
            }
            exoPlayer.javaClass.getMethod("setPlaybackParameters", paramsClass).invoke(exoPlayer, params)
            flog("playback speed restored to 1.0")
        }.onFailure { flog("speed restore failed: ${it}") }
    }

    private fun tryRescueCrossFadeStart(
        fc: Any,
        fadeOutHolder: Any?,
        fadeInHolder: Any?,
        rendererPosUs: Long,
    ): Boolean {
        if (fadeOutHolder == null || fadeInHolder == null) return false
        val phaseObj = readFieldSafe(fc, "fadePhase") ?: return false
        if ((phaseObj as? Enum<*>)?.name != "IDLE") return false

        val prepared = (readFieldSafe(fadeInHolder, "prepared") as? Boolean) == true
        if (!prepared) return false

        val periodPosUs = runCatching {
            fadeOutHolder.javaClass.getMethod("toPeriodTime", Long::class.javaPrimitiveType)
                .invoke(fadeOutHolder, rendererPosUs) as? Long
        }.getOrNull()
        val posUs = when {
            periodPosUs != null && periodPosUs >= 0L -> periodPosUs
            lastPositionMs > 0L -> lastPositionMs * 1000L
            else -> return false
        }

        val info = readFieldSafe(fadeOutHolder, "info")
        val infoDurUs = (info?.let { readFieldSafe(it, "durationUs") } as? Long)?.takeIf { it > 3_000_000L }
        val itemDurUs = if (infoDurUs == null && lastDurationMs <= 3_000L) {
            runCatching {
                val mp = readFieldSafe(fadeOutHolder, "mediaPeriod")
                val item = mp?.let { readFieldSafe(it, "mediaItem") }
                (item?.javaClass?.getMethod("getDuration")?.invoke(item) as? Long)?.takeIf { it > 3_000L }?.times(1000L)
            }.getOrNull()
        } else null
        val durationUs = infoDurUs ?: lastDurationMs.takeIf { it > 3_000L }?.times(1000L) ?: itemDurUs ?: return false
        val remUs = durationUs - posUs

        val plannedSec = activePlan?.durationSec ?: effectiveFallbackSec()
        val secondOffsetUs = (readFieldSafe(fc, "secondTrackOffsetUs") as? Long) ?: 0L
        val plannedTriggerUs = (durationUs - plannedSec * 1_000_000L).coerceAtLeast(1_000_000L)
        val seekEnabled = allowSeekTrigger()
        val triggerUs = if (seekEnabled) {
            if (secondOffsetUs in 1L until durationUs) minOf(secondOffsetUs, plannedTriggerUs) else plannedTriggerUs
        } else {
            if (secondOffsetUs in 1L until durationUs) secondOffsetUs else plannedTriggerUs
        }

        val inTriggerWindow = if (seekEnabled) {
            posUs >= triggerUs
        } else {
            posUs in triggerUs..(triggerUs + 2_000_000L)
        }
        if (!inTriggerWindow || remUs < 1_500_000L) return false

        val actualSec = (remUs / 1_000_000L).toInt().coerceIn(2, plannedSec)
        val fadeOutPhase = phaseObj.javaClass.enumConstants
            ?.firstOrNull { (it as? Enum<*>)?.name == "FADE_OUT" } ?: return false

        crossfadeInProgress = true
        crossfadeStartTimeMs = android.os.SystemClock.elapsedRealtime()
        activeFadeDurationMs = (actualSec * 1000L).coerceAtLeast(1_500L)

        writeFieldSafe(fc, "secondTrackOffsetUs", posUs)
        writeFieldSafe(fc, "crossingTimeUs", (actualSec * 1_000_000L).toFloat())
        syncTransitionsMap(fc, posUs, actualSec * 1_000_000L)
        setCrossFadeDurationField(fc, actualSec)
        val curPlan = activePlan
        if (curPlan != null && curPlan.durationSec > actualSec) {
            activePlan = curPlan.copy(durationSec = actualSec)
        }
        writeFieldSafe(fc, "fadePhase", fadeOutPhase)
        writeFieldSafe(fc, "lastMsgTs", System.currentTimeMillis())

        flog("maybeStart 兜底拉起 FADE_OUT: pos=${posUs / 1_000_000L}s/${durationUs / 1_000_000L}s rem=${actualSec}s (origOffset=${secondOffsetUs / 1_000_000L}s)")
        return true
    }

    private fun syncTransitionsMap(fc: Any, startUs: Long, durationUs: Long) {
        val map = readFieldSafe(fc, "transitionsMap") as? Map<*, *> ?: return
        for ((k, v) in map) {
            val keyName = (k as? Enum<*>)?.name ?: continue
            if (v != null && (keyName == "FADE_OUT" || keyName == "FADE_IN")) {
                writeFieldSafe(v, "startUs", startUs)
                writeFieldSafe(v, "durationUs", durationUs)
            }
        }
    }

    private fun driveRendererVolumes(fc: Any, outHolder: Any?, inHolder: Any?, outVol: Float, inVol: Float) {
        val renderers = readFieldSafe(fc, "renderers") as? Array<*> ?: return
        fun sendVol(holder: Any?, vol: Float) {
            if (holder == null) return
            runCatching {
                val idx = holder.javaClass.getMethod("getRendererIdx").invoke(holder) as? Int ?: return@runCatching
                val r = renderers.getOrNull(idx) ?: return@runCatching
                r.javaClass.getMethod("handleMessage", Int::class.javaPrimitiveType, Any::class.java)
                    .invoke(r, 2, java.lang.Float.valueOf(vol))
            }
        }
        sendVol(outHolder, outVol)
        sendVol(inHolder, inVol)
        setFloatField(fc, "fadeOutLevel", outVol)
        setFloatField(fc, "fadeInLevel", inVol)
    }

    private fun readFieldSafe(target: Any, name: String): Any? {
        var c: Class<*>? = target.javaClass
        while (c != null) {
            try {
                val f = c.getDeclaredField(name)
                f.isAccessible = true
                return f.get(target)
            } catch (_: Throwable) {
                c = c.superclass
            }
        }
        return null
    }

    private fun writeFieldSafe(target: Any, name: String, value: Any?): Boolean {
        var c: Class<*>? = target.javaClass
        while (c != null) {
            try {
                val f = c.getDeclaredField(name)
                f.isAccessible = true
                f.set(target, value)
                return true
            } catch (_: Throwable) {
                c = c.superclass
            }
        }
        return false
    }

    /** 仅在必要时一次性把原生引擎切到 AUTOMATIC(0)；后续开关走零卡顿软门控，不调用 setCrossFadeState(OFF) 以免冲洗 AudioTrack 引起爆音/卡顿。 */
    private fun armNativeCrossfadeEngineIfNeeded() {
        synchronized(stateLock) {
            if (crossfadeStateApplied) return
            val holder = exoMediaPlayer ?: return
            val setter = setCrossFadeStateMethod ?: return
            crossfadeStateApplied = true
            val armed = runCatching {
                ownStateCall.set(true)
                try {
                    setter.invoke(holder, STATE_AUTOMATIC)
                    flog("setCrossFadeState(0=AUTOMATIC) armed OK")
                } finally {
                    ownStateCall.remove()
                }
            }.onFailure {
                crossfadeStateApplied = false
                flog("setCrossFadeState failed: ${it}")
            }.isSuccess
            if (armed) {
                applyCrossfadeDuration(effectiveFallbackSec())
            }
        }
    }

    /**
     * 设置原生引擎的 crossfade 时长（秒）——仅启动时调用（此时无 crossfade 在进行）。
     */
    private fun applyCrossfadeDuration(sec: Int) {
        val holder = exoMediaPlayer ?: return
        runCatching {
            setCrossFadeDurationMethod?.invoke(holder, sec)
            flog("setCrossFadeDuration($sec s) OK (启动默认)")
        }.onFailure { flog("setCrossFadeDuration failed: ${it}") }
    }

    /**
     * 直接反射写 PlayerAudioFadeControl.crossFadeDuration 私有字段（秒）。
     *
     * 关键时序：maybeStartCrossFading 每帧被调用，引擎在内部读取 crossFadeDuration 字段决定淡化。
     * 在 maybeStartCrossFading 的 before hook 里（引擎读取字段前）写入智能时长，
     * 原生引擎本次 crossfade 就会用这个时长。
     */
    private fun setCrossFadeDurationField(fadeControl: Any?, sec: Int) {
        if (fadeControl == null) return
        runCatching {
            val f = fadeControl.javaClass.getDeclaredField("crossFadeDuration")
            f.isAccessible = true
            f.setInt(fadeControl, sec)
            flog("crossFadeDuration field -> ${sec}s (maybeStart before, 引擎读取前)")
        }.onFailure { flog("setCrossFadeDurationField failed: ${it}") }
    }

    /**
     * 触发封面噪声溶解动画。
     * @param oldCover 旧封面（上一首）
     * @param newCover 新封面（下一首，从 pendingNewCover 取，因为引擎的 setImageDrawable 被阻止了）
     */
    private fun isBlankBitmap(bmp: android.graphics.Bitmap): Boolean {
        var sum = 0L; var sum2 = 0L; var n = 0
        val stepX = (bmp.width / 12).coerceAtLeast(1)
        val stepY = (bmp.height / 12).coerceAtLeast(1)
        var y = 0
        while (y < bmp.height) {
            var x = 0
            while (x < bmp.width) {
                val c = bmp.getPixel(x, y)
                val v = ((android.graphics.Color.red(c) + android.graphics.Color.green(c) + android.graphics.Color.blue(c)) / 3)
                sum += v; sum2 += v.toLong() * v; n++
                x += stepX
            }
            y += stepY
        }
        if (n == 0) return true
        val mean = sum.toDouble() / n
        val varv = (sum2.toDouble() / n) - mean * mean
        return mean > 242 && varv < 40
    }

    private fun viewToBitmap(view: android.view.View): android.graphics.Bitmap {
        val w = view.width.coerceAtLeast(1)
        val h = view.height.coerceAtLeast(1)
        val bitmap = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        return bitmap
    }

    private fun drawableToBitmap(drawable: android.graphics.drawable.Drawable, w: Int, h: Int): android.graphics.Bitmap {
        val bitmap = android.graphics.Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        drawable.setBounds(0, 0, bitmap.width, bitmap.height)
        drawable.draw(canvas)
        return bitmap
    }

    /**
     * 触发封面“积雪融化”式分区溶解：旧封面→新封面不是整体 alpha，而是按 FBM 噪声阈值
     * 一块一块切换，区域之间用羽化过渡。
     */
    private fun scheduleVisualFinalize(delayMs: Long) {
        visualFinalizeRunnable?.let { coverHoldHandler.removeCallbacks(it) }
        val r = Runnable {
            if (crossfadeInProgress) return@Runnable  // 音频还在过渡，交给 maybeStart false 收尾
            finalizeCoverTransition()
        }
        visualFinalizeRunnable = r
        coverHoldHandler.postDelayed(r, delayMs)
    }

    private fun ensureCoverOverlay(view: android.widget.ImageView, oldCover: android.graphics.drawable.Drawable): android.widget.ImageView? {
        val existing = coverOverlayView
        if (existing != null && existing.parent != null) return existing
        val w = view.width
        val h = view.height
        if (w <= 0 || h <= 0) return null
        val parent = view.parent as? android.view.ViewGroup ?: return null
        val bmp = drawableToBitmap(oldCover, w, h)
        lastCoverBitmap = bmp
        val overlay = android.widget.ImageView(view.context).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            setImageBitmap(bmp)
            layoutParams = android.view.ViewGroup.LayoutParams(w, h)
        }
        parent.addView(overlay)
        overlay.x = view.left.toFloat()
        overlay.y = view.top.toFloat()
        overlay.bringToFront()
        coverOverlayView = overlay
        flog("cover: old-cover overlay preinstalled (防闪)")
        return overlay
    }

    private fun isBlankArtwork(d: android.graphics.drawable.Drawable): Boolean {
        if (d is android.graphics.drawable.ColorDrawable) {
            val c = d.color
            return android.graphics.Color.alpha(c) == 0 ||
                (android.graphics.Color.red(c) > 245 && android.graphics.Color.green(c) > 245 && android.graphics.Color.blue(c) > 245)
        }
        return d.intrinsicWidth <= 1 && d.intrinsicHeight <= 1
    }

    private fun triggerCoverDissolve(view: android.widget.ImageView, oldCover: android.graphics.drawable.Drawable, newCover: android.graphics.drawable.Drawable) {
        runCatching {
            if (coverRegional != null) return@runCatching
            val w = view.width
            val h = view.height
            if (w <= 0 || h <= 0) return@runCatching
            val oldBitmap = drawableToBitmap(oldCover, w, h)
            val newBitmap = drawableToBitmap(newCover, w, h)
            if (noiseBitmap == null || noiseBitmap!!.isRecycled) {
                noiseBitmap = generateFbmNoiseBitmap(512, 512, scale = 12f)
            }
            val dissolve = RegionalDissolveDrawable(oldBitmap, newBitmap, noiseBitmap!!)
            // 旧封面叠在封面 ImageView 的同一父容器里，盖住宿主的闪烁/空白占位；
            // 溶解只作用在这个叠层上，宿主的封面在动画结束前保持旧图。
            val overlay = ensureCoverOverlay(view, oldCover) ?: return@runCatching
            overlay.setImageDrawable(dissolve)
            isSettingCover[view] = true
            view.setImageDrawable(oldCover)
            isSettingCover[view] = false
            currentCoverDrawable = newCover.constantState?.newDrawable()?.mutate() ?: newCover
            coverRegional = dissolve
            lastCoverBitmap = newBitmap
            val scale = runCatching {
                android.provider.Settings.Global.getFloat(
                    MediaRuntime.context.application.contentResolver,
                    android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
                    1f,
                )
            }.getOrDefault(1f).coerceAtLeast(0.1f)
            val dur = (1_450L / scale).toInt().coerceIn(700, 1_800)
            overlay.scaleX = 0.982f
            overlay.scaleY = 0.982f
            overlay.animate()
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(dur.toLong())
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.8f))
                .start()
            val animator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                duration = dur.toLong()
                interpolator = android.view.animation.DecelerateInterpolator(1.35f)
                addUpdateListener { dissolve.progress = it.animatedValue as Float }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        runCatching {
                            coverOverlayView?.let { ov ->
                                ov.animate().cancel()
                                (ov.parent as? android.view.ViewGroup)?.removeView(ov)
                            }
                            coverOverlayView = null
                            isSettingCover[view] = true
                            if (isBlankBitmap(newBitmap)) {
                                flog("cover: 新封面仍是空白占位，先保留旧封面")
                            } else {
                                view.setImageDrawable(newCover)
                            }
                            isSettingCover[view] = false
                            currentCoverDrawable = newCover.constantState?.newDrawable()?.mutate() ?: newCover
                            if (coverRegional === dissolve) coverRegional = null
                        }
                    }
                })
            }
            coverRegionalAnimator = animator
            animator.start()
            flog("cover: 分区融化过渡启动 dur=${dur}ms")
            if (!crossfadeInProgress) {
                scheduleVisualFinalize(dur + 1_500L)
            }
        }.onFailure { flog("cover: triggerDissolve 异常 ${it.message}") }
    }

    private fun holderStartUs(holder: Any?): Long? = runCatching {
        if (holder == null) return null
        val info = holder.javaClass.getField("info").get(holder) ?: return null
        info.javaClass.getField("startPositionUs").getLong(info)
    }.getOrNull()

    private fun holderId(holder: Any?): String? = runCatching {
        if (holder == null) return null
        val mp = holder.javaClass.getField("mediaPeriod").get(holder)
        val item = mp.javaClass.getDeclaredField("mediaItem").apply { isAccessible = true }.get(mp)
        item.javaClass.getMethod("getId").invoke(item) as? String
            ?: item.javaClass.getMethod("getStoreId").invoke(item) as? String
    }.getOrNull()

    private fun fadeFloat(name: String): Float? = runCatching {
        val fc = transitionFadeControl ?: return null
        fc.javaClass.getDeclaredField(name).apply { isAccessible = true }.getFloat(fc)
    }.getOrNull()

    private fun curveValue(progress: Float, curve: FadeCurve): Float = when (curve) {
        FadeCurve.LINEAR -> progress
        FadeCurve.STEEP -> {
            val s = progress * progress * (3f - 2f * progress)
            s * s
        }
        else -> Math.sin(progress * Math.PI / 2.0).let { (it * it).toFloat() }
    }

    private fun setFloatField(target: Any?, name: String, value: Float) {
        if (target == null) return
        runCatching {
            target.javaClass.getDeclaredField(name).apply { isAccessible = true }.setFloat(target, value)
        }
    }

    /**
     * 柔和接歌点选择：只在末尾 3~12s 内寻找结构节拍点，并自动过滤尾部静音/弱音区，
     * 避免裁剪下一首前奏和人声；固定手动时长则完全尊重用户设定。
     */
    private fun applyAppleCue(plan: TransitionPlan, current: SongAudioAnalysis?): TransitionPlan {
        if (defaultDurationSec() > 0) return plan
        val total = lastDurationMs
        if (total <= 0L || current == null) return plan
        val durationSec = total / 1000f
        val bars = current.barTimesSec ?: current.beatTimesSec ?: return plan
        if (bars.size < 8) return plan
        val loudness = current.loudnessCurve
        val target = when (plan.mode) {
            TransitionMode.HARMONIC_BLEND -> 8f to 2.5f
            TransitionMode.BASS_SWAP -> 7f to 2f
            TransitionMode.FILTER_MIX -> 5f to 2f
            TransitionMode.QUICK_CUT -> 4f to 1.5f
        }
        var avg = 0f; var n = 0
        val from = bars.indexOfFirst { it > durationSec * 0.35f }.let { if (it < 0) 0 else it }
        for (i in from until bars.size) {
            val idx = ((bars[i] / durationSec) * (loudness?.size ?: 1)).toInt().coerceIn(0, (loudness?.size ?: 1) - 1)
            if (loudness != null) { avg += loudness[idx]; n++ }
        }
        if (n > 0) avg /= n
        var quietTailSec = 0f
        if (loudness != null && loudness.size >= 8 && n > 0) {
            val secPerSample = durationSec / loudness.size.toFloat()
            val quietThreshold = minOf(avg - 14f, -30f)
            var tailCount = 0
            for (k in loudness.indices.reversed()) {
                if (loudness[k] < quietThreshold) tailCount++ else break
            }
            quietTailSec = (tailCount * secPerSample).coerceIn(0f, 8f)
        }
        val desiredOverlap = (target.first + quietTailSec * 0.7f).coerceIn(4f, 12f)
        var best: Float? = null
        var bestScore = -Float.MAX_VALUE
        for (i in from until bars.size) {
            val cue = bars[i]
            if (cue >= durationSec - 2f) continue
            val overlap = durationSec - cue
            if (overlap < 3f || overlap > 12f) continue
            val durationScore = 1f - (kotlin.math.abs(overlap - desiredOverlap) / (target.second * 2f)).coerceIn(0f, 1f)
            val energyScore = if (loudness != null && n > 0) {
                val idx = ((cue / durationSec) * loudness.size).toInt().coerceIn(0, loudness.size - 1)
                ((avg - loudness[idx]) / (kotlin.math.abs(avg) + 1f)).coerceIn(0f, 1f)
            } else 0.4f
            val phrase = (60000f * 8f / (plan.targetBpm ?: 120f)) / 1000f
            val phraseScore = if (overlap >= phrase * 0.8f) 1f else 0.6f
            val mixScore = plan.mixability
            val score = durationScore * 0.45f + energyScore * 0.25f + phraseScore * 0.15f + mixScore * 0.15f
            if (score > bestScore) { bestScore = score; best = cue }
        }
        val cue = best ?: return plan
        val overlap = (durationSec - cue).toInt().coerceIn(3, 12)
        val signature = "${plan.mode}|${cue.toInt()}|${overlap}"
        if (signature != lastCueLog) {
            lastCueLog = signature
            flog("cue: bars=${bars.size} mode=${plan.mode} chosen=${"%.1f".format(cue)}s/${"%.1f".format(durationSec)}s overlap=${overlap}s quietTail=${"%.1f".format(quietTailSec)}s score=${"%.2f".format(bestScore)} (原 ${plan.durationSec}s)")
        }
        return plan.copy(durationSec = overlap,
            reason = plan.reason + "，接歌点=${"%.1f".format(cue)}s")
    }

    private fun applyRendererSpeed(fadeControl: Any?, inHolder: Any?, ratio: Float) {
        runCatching {
            val idx = fadeControl?.javaClass?.getDeclaredField("fadeInRendererIndex")
                ?.apply { isAccessible = true }?.getInt(fadeControl)
            if (idx == null) { flog("renderer speed: no fadeInRendererIndex"); return }
            val player = exoMediaPlayer?.javaClass?.getField("player")?.get(exoMediaPlayer) ?: return
            val renderers = runCatching {
                player.javaClass.getDeclaredField("renderers").apply { isAccessible = true }.get(player) as? Array<*>
            }.getOrNull() ?: runCatching {
                player.javaClass.getMethod("getRenderer", Int::class.javaPrimitiveType).invoke(player, idx)?.let { arrayOf(it) }
            }.getOrNull()
            val renderer = renderers?.getOrNull(idx) ?: return
            val ppCls = loader.loadClass("com.google.android.exoplayer2.PlaybackParameters")
            playbackParamsClass = ppCls
            val pp = ppCls.getConstructor(Float::class.javaPrimitiveType).newInstance(ratio)
            renderer.javaClass.getMethod("setPlaybackParameters", ppCls).invoke(renderer, pp)
            speedRenderer = renderer
            flog("renderer speed applied: renderer=$idx speed=$ratio (per-renderer, no global player)")
        }.onFailure { flog("renderer speed failed: ${it.message}") }
    }

    private fun restoreRendererSpeed() {
        val renderer = speedRenderer ?: return
        val ppCls = playbackParamsClass ?: return
        runCatching {
            val one = ppCls.getConstructor(Float::class.javaPrimitiveType).newInstance(1f)
            renderer.javaClass.getMethod("setPlaybackParameters", ppCls).invoke(renderer, one)
            flog("renderer speed restored 1.0")
        }.onFailure { flog("renderer speed restore failed: ${it.message}") }
        speedRenderer = null
    }

    private fun actualPositionMs(): Long? = runCatching {
        val p = exoMediaPlayer ?: return null
        (p.javaClass.getMethod("getCurrentPosition").invoke(p) as? Long)
    }.getOrNull()

    private fun startActualTransitionLog(fadeControl: Any?, outHolder: Any?, inHolder: Any?, plan: TransitionPlan?) {
        transitionFadeControl = fadeControl
        transitionStartedMs = android.os.SystemClock.elapsedRealtime()
        transitionModeName = plan?.mode?.name ?: "?"
        transitionPlannedSec = plan?.durationSec ?: 0
        setFloatField(fadeControl, "fadeInLevel", 0f)
        setFloatField(fadeControl, "fadeOutLevel", 1f)
        transitionOutId = holderId(outHolder)
        transitionInId = holderId(inHolder)
        val inStartUs = holderStartUs(inHolder)
        transitionInStartUs = inStartUs
        val eq = AutoMixEqualizer.snapshot()
        flog(
            "TRANSITION actual-start: outId=$transitionOutId inId=$transitionInId " +
                "outPos=${(actualPositionMs() ?: lastPositionMs) / 1000f}s/outDur=${lastDurationMs / 1000f}s " +
                "inActualStart=${inStartUs?.div(1_000_000f)}s " +
                "mode=${plan?.mode} mixability=${plan?.mixability} planned=${plan?.durationSec}s " +
                "curve=${plan?.curve} eqDepth=${plan?.eqDepthDb}dB inLevel=${fadeFloat("fadeInLevel")} outLevel=${fadeFloat("fadeOutLevel")} eq=[$eq]"
        )
        transitionCurve = plan?.curve ?: FadeCurve.GENTLE
        transitionDurationMs = activeFadeDurationMs.coerceAtLeast(1_500L)
        // 真实增益控制：宿主只可靠地 hook 到 fadeIn；fadeOut 用同一个进度强制写，
        // 每 40ms 写一次 fadeInLevel/fadeOutLevel，保证两路是互补曲线。
        val gain = object : Runnable {
            override fun run() {
                if (!crossfadeInProgress) return
                val elapsed = android.os.SystemClock.elapsedRealtime() - transitionStartedMs
                val progress = (elapsed.toFloat() / transitionDurationMs.toFloat()).coerceIn(0f, 1f)
                val inLevel = curveValue(progress, transitionCurve)
                val outLevel = Math.pow((1f - inLevel).toDouble(), (activePlan?.outgoingDuck ?: 1f).toDouble()).toFloat()
                setFloatField(transitionFadeControl, "fadeInLevel", inLevel)
                setFloatField(transitionFadeControl, "fadeOutLevel", outLevel)
                coverHoldHandler.postDelayed(this, 40L)
            }
        }
        gainController = gain
        coverHoldHandler.postDelayed(gain, 20L)
        val sampler = object : Runnable {
            override fun run() {
                if (!crossfadeInProgress) return
                val elapsed = android.os.SystemClock.elapsedRealtime() - transitionStartedMs
                val progress = (elapsed.toFloat() / transitionDurationMs.toFloat()).coerceIn(0f, 1f)
                flog(
                    "TRANSITION tick: +${elapsed}ms inLevel=${"%.3f".format(fadeFloat("fadeInLevel") ?: -1f)} " +
                        "outLevel=${"%.3f".format(fadeFloat("fadeOutLevel") ?: -1f)} " +
                        "phase=${fadeFloat("fadePhase")} pos=${(actualPositionMs() ?: lastPositionMs) / 1000f}s " +
                        "eq=[${AutoMixEqualizer.snapshot()}]"
                )
                coverHoldHandler.postDelayed(this, 1000L)
            }
        }
        transitionSampler = sampler
        coverHoldHandler.postDelayed(sampler, 1000L)
    }

    private fun endActualTransitionLog() {
        val elapsed = android.os.SystemClock.elapsedRealtime() - transitionStartedMs
        transitionSampler?.let { coverHoldHandler.removeCallbacks(it) }
        transitionSampler = null
        gainController?.let { coverHoldHandler.removeCallbacks(it) }
        gainController = null
        flog(
            "TRANSITION actual-end: elapsed=${elapsed}ms planned=${transitionPlannedSec}s mode=$transitionModeName " +
                "finalIn=${"%.3f".format(fadeFloat("fadeInLevel") ?: -1f)} " +
                "finalOut=${"%.3f".format(fadeFloat("fadeOutLevel") ?: -1f)} " +
                "incomingPos=${(actualPositionMs() ?: lastPositionMs) / 1000f}s inActualStart=${transitionInStartUs?.div(1_000_000f)}s eq=[${AutoMixEqualizer.snapshot()}]"
        )
        setFloatField(transitionFadeControl, "fadeInLevel", 1f)
        setFloatField(transitionFadeControl, "fadeOutLevel", 0f)
        transitionFadeControl = null
        transitionStartedMs = 0L
        introSkipApplied = false
    }

    private fun dumpFadeTransitionFields(holder: Any) {
        runCatching {
            for (name in listOf("fadeControl", "fadeInTransition", "fadeOutTransition")) {
                val f = holder.javaClass.getDeclaredField(name).apply { isAccessible = true }
                val v = f.get(holder) ?: continue
                val sb = StringBuilder()
                for (ff in v.javaClass.declaredFields) {
                    if (java.lang.reflect.Modifier.isStatic(ff.modifiers)) continue
                    ff.isAccessible = true
                    val vv = runCatching { ff.get(v) }.getOrNull()
                    sb.append("${ff.name}=$vv(${ff.type.simpleName}); ")
                }
                flog("fade-diag: $name ${v.javaClass.name} fields: $sb")
            }
        }.onFailure { flog("fade-diag failed: ${it.message}") }
    }

    private fun finalizeCoverTransition() {
        runCatching {
            val wasActive = crossfadeInProgress || coverDissolve != null || coverRegional != null || pendingNewCover != null
            if (!wasActive) return@runCatching
            flog("cover: finalizeCoverTransition（收尾）")
            crossfadeInProgress = false
            waitingForCover = false
            AutoTransitionProgressBar.setTransitionActive(false)
            AutoMixEqualizer.stop()
            if (speedMatch()) restoreSpeed()
            activePlan = null
            // 清理所有定时任务
            coverHoldRunnable?.let { coverHoldHandler.removeCallbacks(it) }
            coverHoldRunnable = null
            transitionEndRunnable?.let { coverHoldHandler.removeCallbacks(it) }
            transitionEndRunnable = null
            visualFinalizeRunnable?.let { coverHoldHandler.removeCallbacks(it) }
            visualFinalizeRunnable = null
            coverRegionalAnimator?.cancel()
            coverRegionalAnimator = null
            coverOverlayView?.let { ov ->
                ov.animate().cancel()
                (ov.parent as? android.view.ViewGroup)?.removeView(ov)
            }
            coverOverlayView = null
            coverRegional = null
            coverOverlayAnimator?.cancel()
            coverOverlayAnimator = null
            coverOverlay?.let { overlay -> (overlay.parent as? android.view.ViewGroup)?.removeView(overlay) }
            coverOverlay = null
            // 封面收尾：显示最终封面（新封面）
            val v = coverView
            val dissolve = coverDissolve
            if (v != null && dissolve != null) {
                isSettingCover[v] = true
                val finalCover = dissolve.newDrawable
                v.setImageDrawable(finalCover)
                currentCoverDrawable = finalCover.constantState?.newDrawable()?.mutate() ?: finalCover
                isSettingCover[v] = false
            } else {
                val newCover = pendingNewCover
                if (v != null && newCover != null) {
                    isSettingCover[v] = true
                    v.setImageDrawable(newCover)
                    currentCoverDrawable = newCover.constantState?.newDrawable()?.mutate() ?: newCover
                    isSettingCover[v] = false
                }
            }
            lastCoverDrawable = null
            pendingNewCover = null
            coverDissolve = null
            coverAnimator?.cancel()
            coverAnimator = null
        }.onFailure { flog("cover: finalizeCoverTransition 异常 ${it.message}") }
    }

    /**
     * 跳过下一首的首静音/Intro 段。
     *
     * 通过反射修改 fadeInPeriodHolder 的 MediaPeriodInfo.startPositionUs，
     * 让下一首从 skipSec 位置开始播放，跳过开头的静音或前奏。
     *
     * 尝试两种方式：
     *   1. 直接修改 holder.info.startPositionUs 字段
     *   2. 修改 holder.rendererPositionOffsetUs（渲染器位置偏移）
     * 两种方式都用 runCatching 包裹，失败不影响正常播放。
     */
    private fun skipNextSongIntro(fadeInHolder: Any?, skipSec: Float) {

        if (fadeInHolder == null || skipSec <= 1f) return
        val skipUs = (skipSec * 1_000_000L).toLong()
        runCatching {
            // 方式 1：修改 info.startPositionUs
            val infoField = fadeInHolder.javaClass.getField("info").apply { isAccessible = true }
            val info = infoField.get(fadeInHolder)
            if (info != null) {
                val startPosField = info.javaClass.getField("startPositionUs").apply { isAccessible = true }
                val oldStart = startPosField.getLong(info)
                startPosField.setLong(info, skipUs)
                flog("skip intro: info.startPositionUs ${oldStart/1_000_000}s -> ${skipUs/1_000_000}s (绝对位置 ${skipSec}s)")
                runCatching {
                    val mediaPeriod = fadeInHolder.javaClass.getField("mediaPeriod").get(fadeInHolder)
                    mediaPeriod?.javaClass?.getMethod("discardBuffer", Long::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                        ?.invoke(mediaPeriod, skipUs, false)
                    flog("skip intro: discardBuffer(${skipSec}s) OK")
                }.onFailure { flog("skip intro: discardBuffer failed: ${it.message}") }
                runCatching {
                    val streams = fadeInHolder.javaClass.getField("sampleStreams").get(fadeInHolder) as? Array<*> ?: return@runCatching
                    var n = 0
                    for (s in streams) {
                        if (s == null) continue
                        s.javaClass.getMethod("skipData", Long::class.javaPrimitiveType).invoke(s, skipUs)
                        n++
                    }
                    flog("skip intro: skipData(${skipSec}s) on $n streams")
                }.onFailure { flog("skip intro: skipData failed: ${it.message}") }
                return
            }
        }.onFailure { flog("skip intro (info.startPositionUs) failed: ${it.message}") }

        runCatching {
            // 方式 2：修改 rendererPositionOffsetUs
            val offsetField = fadeInHolder.javaClass.getField("rendererPositionOffsetUs").apply { isAccessible = true }
            val oldOffset = offsetField.getLong(fadeInHolder)
            offsetField.setLong(fadeInHolder, oldOffset + skipUs)
            flog("skip intro: rendererPositionOffsetUs ${oldOffset/1_000_000}s -> ${(oldOffset+skipUs)/1_000_000}s (跳过${skipSec}s)")
        }.onFailure { flog("skip intro (rendererPositionOffsetUs) failed: ${it.message}") }
    }
}

// ============================================================
// 噪声溶解 Drawable：用噪声阈值遮罩实现像素级溶解（非简单 alpha 交叉淡化）
// 原理：n = noise(uv)，mask = smoothstep(t-f, t+f, n)，result = mix(old, new, mask)
// 噪声值低的像素先切换、高的后切换，看起来是"从各处渗出来"而非整体淡化
// ============================================================
internal class DissolveDrawable(
    val oldDrawable: android.graphics.drawable.Drawable,
    val newDrawable: android.graphics.drawable.Drawable,
    @Suppress("unused") private val noiseBitmap: android.graphics.Bitmap
) : android.graphics.drawable.Drawable() {

    var progress: Float = 0f
        set(value) { field = value.coerceIn(0f, 1f); invalidateSelf() }

    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val oldBitmap: android.graphics.Bitmap = snapshot(oldDrawable)
    private val newBitmap: android.graphics.Bitmap = snapshot(newDrawable)

    private fun snapshot(source: android.graphics.drawable.Drawable): android.graphics.Bitmap {
        val width = source.intrinsicWidth.takeIf { it > 0 }?.coerceAtMost(1024) ?: 512
        val height = source.intrinsicHeight.takeIf { it > 0 }?.coerceAtMost(1024) ?: 512
        val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        source.setBounds(0, 0, width, height)
        source.draw(canvas)
        return bitmap
    }

    override fun draw(canvas: android.graphics.Canvas) {
        val b = bounds
        val p = progress.coerceIn(0f, 1f)
        // iOS AutoMix: both artworks keep their frame; soft cross-dissolve through a white bloom.
        paint.alpha = ((1f - p).coerceIn(0f, 1f) * 255f).toInt()
        canvas.drawBitmap(oldBitmap, null, b, paint)
        paint.alpha = (p.coerceIn(0f, 1f) * 255f).toInt()
        canvas.drawBitmap(newBitmap, null, b, paint)
        val bloom = (kotlin.math.sin((p * Math.PI).toFloat()) * 0.22f * 255f).toInt()
        if (bloom > 0) {
            paint.alpha = bloom
            paint.color = android.graphics.Color.WHITE
            canvas.drawRect(b, paint)
        }
        paint.alpha = 255
        paint.color = android.graphics.Color.WHITE
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth(): Int = newBitmap.width
    override fun getIntrinsicHeight(): Int = newBitmap.height
}
internal fun generateFbmNoiseBitmap(width: Int, height: Int, scale: Float = 12f): android.graphics.Bitmap {
    val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ALPHA_8)
    val random = java.util.Random(42)
    val gridSize = 16
    val grid = FloatArray(gridSize * gridSize) { random.nextFloat() }

    fun hash(x: Int, y: Int): Float {
        val ix = ((x % gridSize) + gridSize) % gridSize
        val iy = ((y % gridSize) + gridSize) % gridSize
        return grid[iy * gridSize + ix]
    }

    fun valueNoise(x: Float, y: Float): Float {
        val ix = x.toInt()
        val iy = y.toInt()
        val fx = x - ix
        val fy = y - iy
        val sx = fx * fx * (3f - 2f * fx)  // smoothstep
        val sy = fy * fy * (3f - 2f * fy)
        val a = hash(ix, iy)
        val b = hash(ix + 1, iy)
        val c = hash(ix, iy + 1)
        val d = hash(ix + 1, iy + 1)
        return (a * (1f - sx) + b * sx) * (1f - sy) + (c * (1f - sx) + d * sx) * sy
    }

    fun fbm(x: Float, y: Float): Float {
        var v = 0f
        var amp = 0.5f
        var px = x
        var py = y
        repeat(3) {
            v += amp * valueNoise(px, py)
            px *= 2f
            py *= 2f
            amp *= 0.5f
        }
        return v.coerceIn(0f, 1f)
    }

    val pixels = ByteArray(width * height)
    for (y in 0 until height) {
        for (x in 0 until width) {
            val n = fbm(x / width.toFloat() * scale, y / height.toFloat() * scale)
            pixels[y * width + x] = (n * 255f).toInt().coerceIn(0, 255).toByte()
        }
    }
    val buffer = java.nio.ByteBuffer.wrap(pixels)
    bitmap.copyPixelsFromBuffer(buffer)
    return bitmap
}

// ============================================================================
// DJ Low-Pass Sweep：V形低通扫频滤波器
//
// 原理：crossfade 期间低通截止频率呈 V 形变化——
//   progress=0   → cutoff=18kHz（全频）
//   progress=0.5 → cutoff=1500Hz（变闷，模拟 outgoing 低通 + incoming 尚未全频）
//   progress=1   → cutoff=18kHz（恢复全频）
//
// 注意：绝不修改 ByteBuffer 的 order！asShortBuffer/asFloatBuffer 自动使用
// buffer 原始字节序。Apple Music 有两个 AudioTrack（48k float LE + 44k short BE），
// 强制改 order 会导致 BIG_ENDIAN track 的数据字节错乱，产生嘶嘶声。
// ============================================================================
private class DjLowPassSweep {
    companion object {
        private const val MAX_CHANNELS = 8
        private const val MIN_CUTOFF = 1500f  // V 形底部（更温和，避免太闷）
        private const val MAX_CUTOFF = 18000f
        private const val Q = 0.707f
        private const val SLEW_PER_MS = 80f
    }

    /** 单个 AudioTrack 的滤波器状态 */
    private class TrackState {
        // 转置直接形式 II 状态：s1, s2（每声道）
        val s1 = FloatArray(MAX_CHANNELS)
        val s2 = FloatArray(MAX_CHANNELS)
        var b0 = 1f; var b1 = 0f; var b2 = 0f
        var a1 = 0f; var a2 = 0f
        var currentCutoff = MAX_CUTOFF
        var lastSampleRate = 44100
        var lastTimeMs = 0L

        fun reset() {
            s1.fill(0f); s2.fill(0f)
            b0 = 1f; b1 = 0f; b2 = 0f; a1 = 0f; a2 = 0f
            currentCutoff = MAX_CUTOFF
            lastTimeMs = 0L
        }
    }

    private val states = java.util.WeakHashMap<android.media.AudioTrack, TrackState>()

    fun reset() {
        // crossfade 开始时重置所有 track 状态
        for (s in states.values) s.reset()
    }

    private fun getState(track: android.media.AudioTrack): TrackState {
        return states.getOrPut(track) { TrackState() }
    }

    private fun targetCutoff(progress: Float): Float {
        val t = if (progress <= 0.5f) 1f - progress * 2f else (progress - 0.5f) * 2f
        val logMin = Math.log(MIN_CUTOFF.toDouble())
        val logMax = Math.log(MAX_CUTOFF.toDouble())
        return Math.exp(logMin + (logMax - logMin) * t).toFloat()
    }

    private fun updateCoeffs(st: TrackState, sampleRate: Int, cutoffHz: Float) {
        val sr = sampleRate.coerceIn(8000, 192000)
        val fc = cutoffHz.coerceIn(20f, sr * 0.45f)
        val w0 = 2.0 * Math.PI * fc / sr
        val cosw0 = Math.cos(w0)
        val sinw0 = Math.sin(w0)
        val alpha = sinw0 / (2.0 * Q)
        val a0 = 1.0 + alpha
        st.b0 = ((1.0 - cosw0) / 2.0 / a0).toFloat()
        st.b1 = ((1.0 - cosw0) / a0).toFloat()
        st.b2 = ((1.0 - cosw0) / 2.0 / a0).toFloat()
        st.a1 = (-2.0 * cosw0 / a0).toFloat()
        st.a2 = ((1.0 - alpha) / a0).toFloat()
    }

    /** 转置直接形式 II：数值更稳定，系数变化时瞬态更小 */
    private fun processSample(st: TrackState, input: Float, ch: Int): Float {
        val out = st.b0 * input + st.s1[ch]
        st.s1[ch] = st.b1 * input - st.a1 * out + st.s2[ch]
        st.s2[ch] = st.b2 * input - st.a2 * out
        return out
    }

    fun applyToBuffer(
        track: android.media.AudioTrack,
        buf: java.nio.ByteBuffer,
        sizeInBytes: Int,
        sampleRate: Int,
        channels: Int,
        isFloatPcm: Boolean,
        progress: Float
    ) {
        if (sizeInBytes <= 0 || channels <= 0) return
        val ch = channels.coerceAtMost(MAX_CHANNELS)
        val st = getState(track)

        // 截止频率平滑（slew limit）
        val target = targetCutoff(progress)
        val now = android.os.SystemClock.elapsedRealtime()
        if (st.lastTimeMs > 0) {
            val dt = (now - st.lastTimeMs).coerceAtLeast(1L)
            val maxDelta = SLEW_PER_MS * dt
            val delta = target - st.currentCutoff
            st.currentCutoff += delta.coerceIn(-maxDelta, maxDelta)
        } else {
            st.currentCutoff = target
        }
        st.lastTimeMs = now

        if (sampleRate != st.lastSampleRate && sampleRate in 8000..192000) {
            st.lastSampleRate = sampleRate
            st.reset()
            st.currentCutoff = target
        }
        updateCoeffs(st, st.lastSampleRate, st.currentCutoff)

        // 注意：绝不修改 buf.order()！asFloatBuffer/asShortBuffer 自动使用 buffer 原始字节序。
        // Apple Music 有两个 AudioTrack（48k float LE + 44k short BE），强制改 order 会
        // 导致 BIG_ENDIAN track 数据字节错乱产生嘶嘶声（日志铁证）。
        try {
            if (isFloatPcm) {
                val floatCount = sizeInBytes / 4
                if (floatCount <= 0) return
                val samples = FloatArray(floatCount)
                buf.asFloatBuffer().get(samples)
                var bad = 0
                var i = 0
                while (i < floatCount) {
                    val c = i % ch
                    val inp = samples[i]
                    if (inp.isNaN() || inp.isInfinite()) { bad++; i++; continue }
                    val out = processSample(st, inp, c)
                    if (out.isNaN() || out.isInfinite()) { bad++; i++; continue }
                    samples[i] = out.coerceIn(-1f, 1f)
                    i++
                }
                if (bad < floatCount / 10) {
                    buf.asFloatBuffer().put(samples)
                }
            } else {
                val shortCount = sizeInBytes / 2
                if (shortCount <= 0) return
                val samples = ShortArray(shortCount)
                buf.asShortBuffer().get(samples)
                var bad = 0
                var i = 0
                while (i < shortCount) {
                    val c = i % ch
                    val inf = samples[i] / 32768f
                    if (inf.isNaN() || inf.isInfinite()) { bad++; i++; continue }
                    val outf = processSample(st, inf, c)
                    if (outf.isNaN() || outf.isInfinite()) { bad++; i++; continue }
                    samples[i] = (outf * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                    i++
                }
                if (bad < shortCount / 10) {
                    buf.asShortBuffer().put(samples)
                }
            }
        } catch (_: Exception) {
            // 滤波异常时不写回，保持原始数据
        }
    }
}
























