package dev.kifranei.ampp.media

import android.animation.ValueAnimator
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import java.util.WeakHashMap
import kotlin.math.roundToInt

internal class ProgressMorphState(
    private val morphDurationMs: Long = 850L,
) {
    private var displayedFrac: Float = -1f
    private var morphing: Boolean = false
    private var morphStartFrac: Float = 0f
    private var morphStartMs: Long = 0L

    fun update(rawFrac: Float, nowMs: Long, fusionActive: Boolean, scrubbing: Boolean): Pair<Float, Float> {
        val raw = rawFrac.coerceIn(0f, 1f)
        if (scrubbing || displayedFrac < 0f) {
            morphing = false
            displayedFrac = raw
            return raw to 0f
        }
        if (fusionActive && !morphing && raw < displayedFrac - 0.18f) {
            morphing = true
            morphStartFrac = displayedFrac
            morphStartMs = nowMs
        }
        if (morphing) {
            val t = ((nowMs - morphStartMs).toFloat() / morphDurationMs.toFloat()).coerceIn(0f, 1f)
            val e = if (t < 0.5f) 4f * t * t * t else 1f - (-2f * t + 2f).let { it * it * it } / 2f
            displayedFrac = morphStartFrac + (raw - morphStartFrac) * e
            val bell = if (t >= 1f) 0f else kotlin.math.sin(t * Math.PI).toFloat().coerceIn(0f, 1f)
            if (t >= 1f) {
                morphing = false
            }
            return displayedFrac to bell
        }
        displayedFrac += (raw - displayedFrac) * 0.35f
        if (kotlin.math.abs(raw - displayedFrac) < 0.002f) {
            displayedFrac = raw
        }
        return displayedFrac to 0f
    }

    fun reset() {
        displayedFrac = -1f
        morphing = false
    }
}

/**
 * 原地修改音质 TextView 并通过 GPU AGSL 着色器为进度条提供紧致、自然的「整条微亮 + 专辑色暖白曙光」，
 * 并在 AutoMix 切换瞬间同步处理进度条融合滑回、迷你封面交叉淡化及标题微动过渡。
 */
internal object AutoTransitionProgressBar {
    private val main = Handler(Looper.getMainLooper())
    private val badgeViews = WeakHashMap<TextView, String>()
    private val badgeColors = WeakHashMap<TextView, Int>()
    private val badgeBackgrounds = WeakHashMap<TextView, Drawable?>()
    private val badgeSpans = WeakHashMap<TextView, SpannableStringBuilder>()
    private val pendingVisibility = WeakHashMap<View, Int>()
    private val seekBars = WeakHashMap<View, Float>()
    private val glowOverlays = WeakHashMap<View, GlowOverlayView>()
    private val glowRoots = WeakHashMap<View, View>()
    private val hostBarAlpha = WeakHashMap<View, Float>()
    private val appliedBarAlpha = WeakHashMap<View, Float>()
    private val miniCoverAnimators = WeakHashMap<ImageView, ValueAnimator>()
    private val titleFadeTimes = WeakHashMap<TextView, Long>()
    private val badgeAnimators = WeakHashMap<TextView, ValueAnimator>()

    @Volatile private var active = false
    @Volatile private var visualActive = false
    @Volatile private var ownText = false
    @Volatile private var activationStartedMs = 0L
    @Volatile private var albumAccentColor: Int? = null
    @Volatile private var glowIntensity = 0f

    private var lastWindowScanMs = 0L
    private var cachedRootView: View? = null
    private var clock: ValueAnimator? = null
    private var glowFadeAnimator: ValueAnimator? = null
    private var watchdogRunnable: Runnable? = null
    private val labelText = "智能混音"

    private const val WARM_DAWN_DEFAULT = 0xFFFFE4C4.toInt()
    private const val WARM_DAWN_HAZE = 0xFFFFEFE0.toInt()
    private const val WARM_DAWN_MID = 0xFFFFF6EB.toInt()

    private const val DAWN_SCRUBBER_AGSL = """
        uniform float uLeft;
        uniform float uRight;
        uniform float uPlayedRight;
        uniform float uCenterY;
        uniform float uHalfHeight;
        uniform float uDensity;
        uniform float uIntensity;
        uniform float uTime;
        uniform float uFusion;
        uniform float3 uAlbumColor;

        float distToCapsuleDp(float2 p, float ax, float bx, float cy, float halfH, float density) {
            float cx = clamp(p.x, ax, bx);
            float distPx = length(float2(p.x - cx, p.y - cy));
            return max(0.0, distPx - halfH) / density;
        }

        half4 main(float2 fragCoord) {
            float fullSpan = max(1.0, uRight - uLeft);
            float tx = clamp((fragCoord.x - uLeft) / fullSpan, 0.0, 1.0);

            float dFull = distToCapsuleDp(fragCoord, uLeft, uRight, uCenterY, uHalfHeight, uDensity);
            float dPlay = distToCapsuleDp(fragCoord, uLeft, max(uLeft + 1.0, uPlayedRight), uCenterY, uHalfHeight, uDensity);

            // 沿整条轨道缓慢移动的柔和高光波，边缘平滑消散
            float waveCenter = -0.25 + fract(uTime * 0.22) * 1.50;
            float waveDist = (tx - waveCenter) / 0.24;
            float wave = exp(-(waveDist * waveDist));
            float breath = 0.90 + 0.10 * sin(uTime * 1.65);

            float splitWeight = 1.0 - uFusion * 0.72;
            float fullBoost = 1.0 + uFusion * 0.32;

            // 仿歌词高亮的紧致真高斯衰减，过渡融合态时强化整条统一曙光并弱化断点
            float mistFull = exp(-(dFull * dFull) / (2.0 * 8.2 * 8.2)) * (0.10 + 0.06 * wave) * fullBoost;
            float haloFull = exp(-(dFull * dFull) / (2.0 * 3.8 * 3.8)) * (0.22 + 0.12 * wave) * fullBoost;
            float haloPlay = exp(-(dPlay * dPlay) / (2.0 * 3.0 * 3.0)) * 0.18 * splitWeight;

            // 轨道本体（含未播放段）轻柔提亮
            float insideFull = smoothstep(0.85, 0.0, dFull);
            float insidePlay = smoothstep(0.85, 0.0, dPlay);
            float trackLift = insideFull * (0.22 + 0.14 * wave + 0.14 * uFusion) + insidePlay * (0.30 * splitWeight);

            // 播放头极轻的暖白微光焦点
            float headDp = length(fragCoord - float2(uPlayedRight, uCenterY)) / uDensity;
            float headGlow = exp(-(headDp * headDp) / (2.0 * 4.6 * 4.6)) * (0.18 * (1.0 - uFusion * 0.55));

            float3 warmWhite = float3(1.0, 0.985, 0.96);
            float3 outerColor = mix(uAlbumColor, warmWhite, 0.38);
            float3 nearColor = mix(uAlbumColor, warmWhite, 0.70);

            float3 col = outerColor * mistFull
                       + nearColor * (haloFull + haloPlay)
                       + warmWhite * (trackLift + headGlow);

            float3 light = clamp(col * (uIntensity * breath), 0.0, 1.0);
            float a = clamp(max(light.r, max(light.g, light.b)), 0.0, 1.0);
            return half4(light, a);
        }
    """

    private val BADGE_KEYWORDS = listOf(
        "无损", "杜比", "Hi-Res", "Lossless", "Dolby", "Atmos", "空间音频", "Spatial",
        "母带", "高解析", "Digital Master", "Apple Digital",
    )

    private fun isAudioBadge(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        return BADGE_KEYWORDS.any { text.contains(it, ignoreCase = true) }
    }

    fun install(scope: PluginScope, packageName: String): Boolean {
        runCatching {
            val onResume = android.app.Activity::class.java.getDeclaredMethod("onResume")
            MediaRuntime.observeMethod(onResume, object : PluginMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null) return
                    val activity = param.thisObject as? android.app.Activity ?: return
                    main.post { cachedRootView = activity.window?.decorView }
                }
            }, scope)
        }
        runCatching {
            val setText = TextView::class.java.getDeclaredMethod(
                "setText", CharSequence::class.java, TextView.BufferType::class.java,
            )
            MediaRuntime.hookMethod(setText, object : PluginMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null) return
                    val tv = param.thisObject as? TextView ?: return
                    val text = param.args[0]?.toString() ?: return
                    if (text.isBlank()) return
                    if (isAudioBadge(text)) {
                        if (!badgeViews.containsKey(tv)) badgeViews[tv] = text
                        if (!active || ownText) return
                        param.result = null
                        main.post {
                            runCatching {
                                if (!active || !visualActive) return@runCatching
                                if (!badgeSpans.containsKey(tv)) {
                                    val sb = SpannableStringBuilder(labelText)
                                    updateBadgeSpan(sb, 0L)
                                    badgeSpans[tv] = sb
                                }
                                val sb = badgeSpans[tv]
                                if (sb != null) {
                                    ownText = true
                                    tv.text = sb
                                    ownText = false
                                }
                            }
                        }
                        return
                    }
                    if (active && !ownText) {
                        maybeAnimateHeaderTextChange(tv, text)
                    }
                }
            }, scope)
        }
        installMiniCoverCrossfadeObserver(scope)
        scope.onClose {
            active = false
            main.post {
                watchdogRunnable?.let { main.removeCallbacks(it) }
                watchdogRunnable = null
                visualActive = false
                for (anim in badgeAnimators.values.toList()) runCatching { anim.cancel() }
                badgeAnimators.clear()
                restoreBadges()
                detachAllGlowOverlays()
            }
        }
        runCatching {
            val onAttach = View::class.java.getDeclaredMethod("onAttachedToWindow")
            MediaRuntime.observeMethod(onAttach, object : PluginMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null) return
                    val view = param.thisObject as? View ?: return
                    main.post {
                        if (view is TextView && isAudioBadge(view.text?.toString())) {
                            if (!badgeViews.containsKey(view)) badgeViews[view] = view.text?.toString() ?: ""
                        }
                        if (view.javaClass.name == "com.apple.android.music.common.MinimalSeekBar") {
                            seekBars[view] = view.elevation
                        }
                    }
                }
            }, scope)
        }
        runCatching {
            val setVis = View::class.java.getDeclaredMethod("setVisibility", Int::class.javaPrimitiveType)
            MediaRuntime.hookMethod(setVis, object : PluginMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!active) return
                    val v = param.thisObject as? View ?: return
                    if (!isBadgeTarget(v)) return
                    val target = param.args[0] as? Int ?: return
                    if (target != View.VISIBLE) {
                        pendingVisibility[v] = target
                        param.result = null
                    }
                }
            }, scope)
        }
        return true
    }

    private fun isBadgeTarget(v: View): Boolean {
        for (badge in badgeViews.keys) {
            if (v === badge || v === badge.parent) return true
        }
        return false
    }

    fun setBpm(bpm: Float?) = Unit
    fun setRootView(root: View?) { if (root != null) cachedRootView = root }
    fun setAlbumAccentColor(color: Int?) { albumAccentColor = color }

    fun isTransitionActive(): Boolean =
        active || visualActive || glowOverlays.isNotEmpty() || badgeSpans.isNotEmpty()

    fun setTransitionActive(active: Boolean, maxDurationMs: Long = 13_000L) {
        this.active = active
        main.post {
            watchdogRunnable?.let { main.removeCallbacks(it) }
            watchdogRunnable = null
            if (active) {
                val safeTimeoutMs = maxDurationMs.coerceIn(2_000L, 15_000L)
                val watchdog = Runnable {
                    if (this.active || visualActive || glowOverlays.isNotEmpty() || badgeSpans.isNotEmpty()) {
                        setTransitionActive(false)
                    }
                }
                watchdogRunnable = watchdog
                main.postDelayed(watchdog, safeTimeoutMs)
                scanActiveWindowsForViews()
                val root = cachedRootView
                if (root != null) findViewsInTree(root)
                val targetBar = seekBars.keys
                    .filter { it.isAttachedToWindow && it.width > 0 && effectiveAlpha(it, it.rootView) > 0.05f }
                    .maxByOrNull { it.width }
                    ?: seekBars.keys.filter { it.isShown && it.width > 0 }.maxByOrNull { it.width }
                extractAlbumAccent(targetBar?.rootView ?: root)?.let { albumAccentColor = it }
                targetBar?.let { bar ->
                    if (!glowOverlays.containsKey(bar)) applyBarGlow(bar)
                }
                if (!visualActive) {
                    visualActive = true
                    val targets = badgeViews.keys.filter { it.isShown }
                    val list = if (targets.isEmpty()) badgeViews.keys.toList() else targets
                    list.forEach { applyBadgeTransition(it) }
                    activationStartedMs = SystemClock.elapsedRealtime()
                    startClock()
                }
                animateGlowIntensity(target = 1f, durationMs = 450L, detachOnEnd = false)
            } else {
                visualActive = false
                restoreBadges()
                if (glowOverlays.isEmpty()) {
                    glowFadeAnimator?.cancel()
                    glowIntensity = 0f
                    stopClock()
                } else {
                    animateGlowIntensity(target = 0f, durationMs = 550L, detachOnEnd = true)
                }
            }
        }
    }

    private fun scanActiveWindowsForViews() {
        runCatching {
            val wmgCls = Class.forName("android.view.WindowManagerGlobal")
            val wmg = wmgCls.getMethod("getInstance").invoke(null) ?: return
            val field = wmgCls.getDeclaredField("mViews").apply { isAccessible = true }
            val views = (field.get(wmg) as? List<*>)?.toList().orEmpty()
            for (v in views) {
                val root = v as? View ?: continue
                if (root.isShown) {
                    findViewsInTree(root)
                    if (cachedRootView == null) cachedRootView = root
                }
            }
        }
    }

    private fun animateGlowIntensity(target: Float, durationMs: Long, detachOnEnd: Boolean) {
        glowFadeAnimator?.cancel()
        val startValue = glowIntensity
        if (kotlin.math.abs(startValue - target) < 0.01f) {
            glowIntensity = target
            if (detachOnEnd && !visualActive) detachAllGlowOverlays()
            return
        }
        glowFadeAnimator = ValueAnimator.ofFloat(startValue, target).apply {
            duration = durationMs
            interpolator = if (target > startValue) DecelerateInterpolator(1.6f) else AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                glowIntensity = (anim.animatedValue as Float).coerceIn(0f, 1f)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    glowIntensity = target
                    if (detachOnEnd && !visualActive) {
                        detachAllGlowOverlays()
                    }
                }
            })
            start()
        }
    }

    private fun detachAllGlowOverlays() {
        stopClock()
        for ((bar, base) in hostBarAlpha) runCatching { bar.alpha = base }
        hostBarAlpha.clear()
        appliedBarAlpha.clear()
        for (anim in miniCoverAnimators.values.toList()) runCatching { anim.cancel() }
        miniCoverAnimators.clear()
        titleFadeTimes.clear()
        for ((_, ov) in glowOverlays) runCatching { (ov.parent as? ViewGroup)?.removeView(ov) }
        glowOverlays.clear()
        glowRoots.clear()
        albumAccentColor = null
    }

    private fun installMiniCoverCrossfadeObserver(scope: PluginScope) {
        val onBeforeImageUpdate: (ImageView, Any?) -> Unit = handler@{ view, incoming ->
            if (!active) return@handler
            if (!view.isAttachedToWindow || !view.isShown) return@handler
            if (view.width !in 40..360 || view.height !in 40..360) return@handler
            val prev = view.drawable ?: return@handler
            if (incoming == null || incoming === prev || miniCoverAnimators[view] != null) return@handler
            val entry = runCatching { view.resources.getResourceEntryName(view.id) }.getOrDefault("")
            if (entry == "artwork_image") return@handler
            if (!(entry.contains("artwork", ignoreCase = true) ||
                    entry.contains("album", ignoreCase = true) ||
                    entry.contains("cover", ignoreCase = true))) return@handler
            val w = view.width
            val h = view.height
            val oldBmp = runCatching {
                android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888).also { bmp ->
                    val c = Canvas(bmp)
                    val prevBounds = android.graphics.Rect(prev.bounds)
                    prev.setBounds(0, 0, w, h)
                    prev.draw(c)
                    prev.bounds = prevBounds
                }
            }.getOrNull() ?: return@handler
            val oldOverlay = BitmapDrawable(view.resources, oldBmp).apply {
                setBounds(0, 0, w, h)
                alpha = 255
            }
            view.overlay.add(oldOverlay)
            var cleaned = false
            fun cleanup() {
                if (cleaned) return
                cleaned = true
                runCatching { view.overlay.remove(oldOverlay) }
                runCatching { if (!oldBmp.isRecycled) oldBmp.recycle() }
                miniCoverAnimators.remove(view)
            }
            val anim = ValueAnimator.ofInt(255, 0).apply {
                duration = 550L
                interpolator = DecelerateInterpolator(1.5f)
                addUpdateListener { a -> oldOverlay.alpha = a.animatedValue as Int }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) = cleanup()
                    override fun onAnimationCancel(animation: android.animation.Animator) = cleanup()
                })
            }
            miniCoverAnimators[view] = anim
            view.post { if (active) anim.start() else anim.cancel() }
        }
        runCatching {
            val setDrawable = ImageView::class.java.getDeclaredMethod("setImageDrawable", Drawable::class.java)
            MediaRuntime.observeMethod(setDrawable, object : PluginMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null || !active) return
                    val view = param.thisObject as? ImageView ?: return
                    onBeforeImageUpdate(view, param.args.firstOrNull())
                }
            }, scope)
        }
        runCatching {
            val setBitmap = ImageView::class.java.getDeclaredMethod("setImageBitmap", android.graphics.Bitmap::class.java)
            MediaRuntime.observeMethod(setBitmap, object : PluginMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null || !active) return
                    val view = param.thisObject as? ImageView ?: return
                    onBeforeImageUpdate(view, param.args.firstOrNull())
                }
            }, scope)
        }
    }

    private val TIME_TEXT_PATTERN = Regex("^-?\\d+:\\d+$")

    private fun maybeAnimateHeaderTextChange(tv: TextView, newText: String) {
        if (!tv.isShown || !tv.isAttachedToWindow || tv.id == View.NO_ID) return
        val maxHeightPx = (100f * tv.resources.displayMetrics.density).roundToInt()
        if (tv.height !in 1..maxHeightPx) return
        val entry = runCatching { tv.resources.getResourceEntryName(tv.id) }.getOrDefault("")
        val looksLikeHeader = entry.contains("title", ignoreCase = true) ||
            entry.contains("subtitle", ignoreCase = true) ||
            entry.contains("artist", ignoreCase = true) ||
            entry.contains("song", ignoreCase = true)
        if (!looksLikeHeader) return
        if (entry.contains("time", ignoreCase = true) ||
            entry.contains("duration", ignoreCase = true) ||
            entry.contains("lyrics", ignoreCase = true) ||
            entry.contains("badge", ignoreCase = true)) return
        val oldTrim = tv.text?.toString()?.trim().orEmpty()
        val newTrim = newText.trim()
        if (oldTrim.isEmpty() || newTrim.isEmpty() || oldTrim == newTrim) return
        if (TIME_TEXT_PATTERN.matches(oldTrim) || TIME_TEXT_PATTERN.matches(newTrim)) return
        val now = SystemClock.elapsedRealtime()
        val lastAt = titleFadeTimes[tv] ?: 0L
        if (now - lastAt < 450L) return
        titleFadeTimes[tv] = now
        main.post {
            runCatching {
                if (!active || !tv.isAttachedToWindow) return@runCatching
                tv.animate().cancel()
                tv.alpha = 0.15f
                tv.translationY = 6f * tv.resources.displayMetrics.density
                tv.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(420L)
                    .setInterpolator(DecelerateInterpolator(2.0f))
                    .start()
            }
        }
    }

    private fun findViewsInTree(root: View?) {
        if (root == null) return
        if (root is TextView && isAudioBadge(root.text?.toString())) {
            if (!badgeViews.containsKey(root)) badgeViews[root] = root.text?.toString() ?: ""
        }
        if (root.javaClass.name == "com.apple.android.music.common.MinimalSeekBar") {
            if (!seekBars.containsKey(root)) seekBars[root] = root.elevation
        }
        if (root is ViewGroup) for (i in 0 until root.childCount) findViewsInTree(root.getChildAt(i))
    }

    private fun extractAlbumAccent(root: View?): Int? {
        if (root == null) return null
        val artworkId = runCatching {
            root.resources.getIdentifier("artwork_image", "id", root.context.packageName)
        }.getOrDefault(0)
        var bestView: ImageView? = null
        var bestArea = 0
        fun findArtwork(view: View) {
            if (view.visibility != View.VISIBLE) return
            if (view is ImageView && view.drawable != null && view.width >= 64 && view.height >= 64) {
                if (artworkId != 0 && view.id == artworkId) {
                    bestView = view
                    bestArea = Int.MAX_VALUE
                    return
                }
                val aspect = minOf(view.width, view.height).toFloat() / maxOf(view.width, view.height).toFloat()
                val area = view.width * view.height
                if (aspect >= 0.72f && area > bestArea) {
                    bestView = view
                    bestArea = area
                }
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    if (bestArea == Int.MAX_VALUE) return
                    findArtwork(view.getChildAt(i))
                }
            }
        }
        findArtwork(root)
        val imageView = bestView ?: return null
        val drawable = imageView.drawable ?: return null
        val thumb = runCatching {
            val raw = (drawable as? BitmapDrawable)?.bitmap
            if (raw != null && !raw.isRecycled && raw.width > 0 && raw.height > 0) {
                android.graphics.Bitmap.createScaledBitmap(raw, 24, 24, true)
            } else {
                android.graphics.Bitmap.createBitmap(24, 24, android.graphics.Bitmap.Config.ARGB_8888).also { bmp ->
                    val canvas = Canvas(bmp)
                    val prev = android.graphics.Rect(drawable.bounds)
                    drawable.setBounds(0, 0, 24, 24)
                    drawable.draw(canvas)
                    drawable.bounds = prev
                }
            }
        }.getOrNull() ?: return null

        return try {
            val hsv = FloatArray(3)
            var bestColor = WARM_DAWN_DEFAULT
            var bestScore = -1f
            for (y in 2 until 22 step 2) {
                for (x in 2 until 22 step 2) {
                    val c = thumb.getPixel(x, y)
                    if (Color.alpha(c) < 128) continue
                    Color.colorToHSV(c, hsv)
                    val sat = hsv[1]
                    val value = hsv[2]
                    if (value < 0.16f || sat < 0.10f) continue
                    val score = sat * (0.35f + 0.65f * value)
                    if (score > bestScore) {
                        bestScore = score
                        bestColor = c
                    }
                }
            }
            if (bestScore < 0f) return null
            Color.colorToHSV(bestColor, hsv)
            hsv[1] = hsv[1].coerceIn(0.32f, 0.72f)
            hsv[2] = hsv[2].coerceIn(0.86f, 1.0f)
            Color.HSVToColor(hsv)
        } finally {
            thumb.recycle()
        }
    }

    private fun applyBadgeTransition(badge: TextView) {
        runCatching {
            if (!active || !visualActive || badgeSpans.containsKey(badge)) return@runCatching
            if (!badgeColors.containsKey(badge)) badgeColors[badge] = badge.currentTextColor
            if (!badgeBackgrounds.containsKey(badge)) badgeBackgrounds[badge] = badge.background
            badgeAnimators.remove(badge)?.cancel()

            val out = ValueAnimator.ofFloat(1f, 0f)
            out.duration = 220L
            out.addUpdateListener { a -> badge.alpha = a.animatedValue as Float }
            out.addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    runCatching {
                        if (!active || !visualActive || badgeAnimators[badge] !== animation) {
                            if (!active) badge.alpha = 1f
                            return@runCatching
                        }
                        val sb = SpannableStringBuilder(labelText)
                        updateBadgeSpan(sb, 0L)
                        badgeSpans[badge] = sb
                        ownText = true
                        badge.background = null
                        badge.backgroundTintList = null
                        badge.setTextColor(Color.WHITE)
                        badge.text = sb
                        ownText = false
                        val inAnim = ValueAnimator.ofFloat(0f, 1f)
                        inAnim.duration = 360L
                        inAnim.addUpdateListener { a -> badge.alpha = a.animatedValue as Float }
                        badgeAnimators[badge] = inAnim
                        inAnim.start()
                    }
                }
            })
            badgeAnimators[badge] = out
            out.start()
        }
    }

    private fun updateBadgeSpan(span: SpannableStringBuilder, elapsedMs: Long) {
        val cycle = 2200L
        val waveT = elapsedMs.coerceAtLeast(0L) % cycle
        for (i in 0 until span.length) {
            val local = (waveT - i * 90L + cycle) % cycle
            val alpha = when {
                local < 620L -> 255
                local < 870L -> (255 - (local - 620L) * 95L / 250L).toInt()
                local < 1580L -> 160
                local < 1830L -> (160 + (local - 1580L) * 95L / 250L).toInt()
                else -> 255
            }
            for (old in span.getSpans(i, i + 1, ForegroundColorSpan::class.java)) span.removeSpan(old)
            span.setSpan(ForegroundColorSpan(Color.argb(alpha.coerceIn(0, 255), 255, 255, 255)),
                i, i + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun startClock() {
        if (clock != null) return
        clock = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1_000_000L
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                val now = SystemClock.elapsedRealtime()
                val elapsed = (now - activationStartedMs - 600L).coerceAtLeast(0L)
                for (badge in badgeViews.keys) {
                    runCatching {
                        if (badge.text?.toString() != labelText) return@runCatching
                        val sb = badgeSpans[badge] ?: return@runCatching
                        updateBadgeSpan(sb, elapsed)
                        badge.invalidate()
                    }
                }
                val anyVisibleOverlay = glowOverlays.values.any { it.visibility == View.VISIBLE }
                if (visualActive && !anyVisibleOverlay && now - lastWindowScanMs > 350L) {
                    lastWindowScanMs = now
                    scanActiveWindowsForViews()
                    seekBars.keys
                        .filter { it.isAttachedToWindow && it.width > 0 && effectiveAlpha(it, it.rootView) > 0.05f }
                        .maxByOrNull { it.width }
                        ?.let { bar ->
                            if (!glowOverlays.containsKey(bar)) applyBarGlow(bar)
                        }
                }
                if (visualActive && albumAccentColor == null) {
                    extractAlbumAccent(cachedRootView)?.let { albumAccentColor = it }
                }
                for ((bar, ov) in glowOverlays) {
                    runCatching {
                        ov.syncToBar()
                        ov.invalidate()
                    }
                }
            }
            start()
        }
    }

    private fun stopClock() {
        clock?.cancel()
        clock = null
    }

    private fun effectiveAlpha(view: View, stopAt: View?, includeSelf: Boolean = true): Float {
        if (!view.isAttachedToWindow) return 0f
        var a = 1f
        var cur: View? = if (includeSelf) view else view.parent as? View
        while (cur != null) {
            if (cur.visibility != View.VISIBLE) return 0f
            a *= cur.alpha
            if (a <= 0.005f) return 0f
            if (cur === stopAt) break
            cur = cur.parent as? View
        }
        return a.coerceIn(0f, 1f)
    }

    private fun mapPointToRoot(view: View, root: View, pt: FloatArray) {
        var cur: View? = view
        while (cur != null && cur !== root) {
            if (!cur.matrix.isIdentity) cur.matrix.mapPoints(pt)
            pt[0] += cur.left.toFloat()
            pt[1] += cur.top.toFloat()
            val parent = cur.parent as? View ?: break
            pt[0] -= parent.scrollX.toFloat()
            pt[1] -= parent.scrollY.toFloat()
            cur = parent
        }
    }

    private fun effectiveScaleY(view: View, root: View): Float {
        var sy = 1f
        var cur: View? = view
        while (cur != null && cur !== root) {
            sy *= kotlin.math.abs(cur.scaleY)
            cur = cur.parent as? View
        }
        return sy.coerceIn(0.25f, 4f)
    }

    private fun applyBarGlow(bar: View) {
        runCatching {
            if (bar.width <= 0 || bar.height <= 0) {
                bar.post { if (visualActive) applyBarGlow(bar) }
                return@runCatching
            }
            val root = bar.rootView as? ViewGroup ?: return@runCatching
            extractAlbumAccent(root)?.let { albumAccentColor = it }
            glowOverlays[bar]?.let { root.removeView(it) }
            val d = bar.resources.displayMetrics.density
            val sy = (22f * d).roundToInt().coerceAtLeast(16)
            val overlayH = (bar.height + sy * 2).coerceAtLeast((48f * d).roundToInt())
            val overlay = GlowOverlayView(bar.context, bar, root, sy).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, overlayH)
                isClickable = false
                isFocusable = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    setLayerType(View.LAYER_TYPE_NONE, null)
                } else {
                    setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                }
            }
            root.addView(overlay)
            glowRoots[bar] = root
            glowOverlays[bar] = overlay
            overlay.syncToBar()
        }
    }

    private fun restoreBadges() {
        for (badge in badgeViews.keys) {
            runCatching {
                val originalText = badgeViews[badge] ?: ""
                val originalColor = badgeColors[badge] ?: Color.WHITE
                val originalBg = badgeBackgrounds[badge]
                badgeAnimators.remove(badge)?.cancel()
                val out = ValueAnimator.ofFloat(1f, 0f)
                out.duration = 220L
                out.addUpdateListener { a -> badge.alpha = a.animatedValue as Float }
                out.addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        runCatching {
                            if (active || badgeAnimators[badge] !== animation) return@runCatching
                            ownText = true
                            badge.text = originalText
                            ownText = false
                            badge.setTextColor(originalColor)
                            badge.background = originalBg
                            badgeSpans.remove(badge)
                            val inAnim = ValueAnimator.ofFloat(0f, 1f)
                            inAnim.duration = 300L
                            inAnim.addUpdateListener { a -> badge.alpha = a.animatedValue as Float }
                            badgeAnimators[badge] = inAnim
                            inAnim.start()
                            main.postDelayed({
                                runCatching {
                                    if (!active) {
                                        badge.visibility = View.VISIBLE
                                        badge.alpha = 1f
                                    }
                                }
                            }, 650L)
                        }
                    }
                })
                badgeAnimators[badge] = out
                out.start()
            }
        }
        for ((v, vis) in pendingVisibility) runCatching { v.visibility = vis }
        pendingVisibility.clear()
    }

    private fun blendColor(base: Int, target: Int, ratio: Float): Int {
        val t = ratio.coerceIn(0f, 1f)
        val inv = 1f - t
        val r = (Color.red(base) * inv + Color.red(target) * t).roundToInt().coerceIn(0, 255)
        val g = (Color.green(base) * inv + Color.green(target) * t).roundToInt().coerceIn(0, 255)
        val b = (Color.blue(base) * inv + Color.blue(target) * t).roundToInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    private fun withAlpha(rgb: Int, alpha: Int): Int =
        (rgb and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    private class GlowOverlayView(
        context: android.content.Context,
        private val bar: View,
        private val root: View,
        val spillY: Int,
    ) : View(context) {
        private val shaderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val softwarePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val blurMid = android.graphics.BlurMaskFilter(6f * resources.displayMetrics.density, android.graphics.BlurMaskFilter.Blur.NORMAL)
        private val blurCore = android.graphics.BlurMaskFilter(1.2f * resources.displayMetrics.density, android.graphics.BlurMaskFilter.Blur.NORMAL)

        private var runtimeShader: RuntimeShader? = null
        private var shaderFailed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
        private var cachedTrackLeftPx: Float = 0f
        private var cachedTrackRightPx: Float = 0f
        private var cachedTrackCenterYPx: Float = 0f
        private var cachedTrackHeightPx: Float = 5f * resources.displayMetrics.density
        private var lastProbedWidth: Int = -1
        private var lastProbedHeight: Int = -1
        private var cachedSampleColor: Int? = null
        private var lastProbeMs: Long = 0L

        private var mappedLeftX = 0f
        private var mappedRightX = 0f
        private var mappedCenterY = 0f
        private var mappedScaleY = 1f
        private var lastEffAlpha = 0f
        private val morphState = ProgressMorphState()

        init {
            if (!shaderFailed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val created = runCatching { RuntimeShader(DAWN_SCRUBBER_AGSL) }.getOrNull()
                if (created != null) {
                    runtimeShader = created
                    shaderPaint.shader = created
                    shaderPaint.blendMode = BlendMode.SCREEN
                } else {
                    shaderFailed = true
                    setLayerType(LAYER_TYPE_SOFTWARE, null)
                }
            }
        }

        private fun refreshBarMetricsIfNeeded(now: Long, effAlpha: Float) {
            val bw = bar.width
            val bh = bar.height
            if (bw <= 0 || bh <= 0 || effAlpha <= 0.5f) return
            val sizeChanged = bw != lastProbedWidth || bh != lastProbedHeight
            if (!sizeChanged && lastProbeMs > 0L && now - lastProbeMs < 1_500L) return
            lastProbedWidth = bw
            lastProbedHeight = bh
            lastProbeMs = now
            val d = resources.displayMetrics.density
            val bmp = runCatching {
                android.graphics.Bitmap.createBitmap(bw, bh, android.graphics.Bitmap.Config.ARGB_8888)
            }.getOrNull() ?: return
            try {
                bar.draw(Canvas(bmp))
                var minY = bh
                var maxY = -1
                val sampleXs = intArrayOf(bw / 4, bw / 2, (bw * 3) / 4)
                for (sx in sampleXs) {
                    val x = sx.coerceIn(0, bw - 1)
                    for (py in 0 until bh) {
                        if (Color.alpha(bmp.getPixel(x, py)) >= 12) {
                            if (py < minY) minY = py
                            if (py > maxY) maxY = py
                        }
                    }
                }
                if (maxY >= minY) {
                    cachedTrackHeightPx = (maxY - minY + 1).toFloat().coerceIn(4f * d, 6.5f * d)
                    cachedTrackCenterYPx = (minY + maxY) * 0.5f
                } else {
                    cachedTrackCenterYPx = bh * 0.5f
                }
                val midY = cachedTrackCenterYPx.roundToInt().coerceIn(0, bh - 1)
                val probeYs = intArrayOf((midY - 1).coerceIn(0, bh - 1), midY, (midY + 1).coerceIn(0, bh - 1))
                var minX = bw
                var maxX = -1
                for (px in 0 until bw) {
                    val visible = probeYs.any { py -> Color.alpha(bmp.getPixel(px, py)) >= 8 }
                    if (visible) {
                        if (px < minX) minX = px
                        if (px > maxX) maxX = px
                    }
                }
                if (maxX > minX && (maxX - minX + 1) >= bw * 0.75f && kotlin.math.abs(minX - (bw - 1 - maxX)) <= (6f * d)) {
                    cachedTrackLeftPx = minX.toFloat()
                    cachedTrackRightPx = (maxX + 1).toFloat()
                } else {
                    cachedTrackLeftPx = bar.paddingLeft.toFloat()
                    cachedTrackRightPx = (bw - bar.paddingRight).toFloat().coerceAtLeast(cachedTrackLeftPx + 1f)
                }
                for (px in 0 until bw step 3) {
                    val c = bmp.getPixel(px, midY)
                    if (Color.alpha(c) >= 48) {
                        val rgb = Color.rgb(Color.red(c), Color.green(c), Color.blue(c))
                        if (rgb != Color.BLACK) {
                            cachedSampleColor = rgb
                            break
                        }
                    }
                }
            } finally {
                bmp.recycle()
            }
        }

        fun syncToBar() {
            val ancestorAlpha = effectiveAlpha(bar, root, includeSelf = false)
            val trackedApplied = appliedBarAlpha[bar]
            if (trackedApplied == null || kotlin.math.abs(bar.alpha - trackedApplied) > 0.03f) {
                hostBarAlpha[bar] = bar.alpha
            }
            val baseAlpha = hostBarAlpha.getOrPut(bar) { bar.alpha }
            val effAlpha = if (bar.isAttachedToWindow && bar.visibility == View.VISIBLE) {
                (baseAlpha * ancestorAlpha).coerceIn(0f, 1f)
            } else 0f
            lastEffAlpha = effAlpha
            if (bar.isPressed || glowIntensity <= 0.005f) {
                if (kotlin.math.abs(bar.alpha - baseAlpha) > 0.01f) bar.alpha = baseAlpha
                appliedBarAlpha[bar] = baseAlpha
            } else if (effAlpha > 0.005f) {
                val desired = (baseAlpha * (1f - 0.55f * glowIntensity)).coerceIn(0f, 1f)
                if (kotlin.math.abs(bar.alpha - desired) > 0.01f) bar.alpha = desired
                appliedBarAlpha[bar] = desired
            }
            if (effAlpha <= 0.005f || glowIntensity <= 0.005f || bar.width <= 0 || bar.height <= 0) {
                if (alpha != 0f) alpha = 0f
                if (visibility != View.GONE) visibility = View.GONE
                return
            }
            val now = SystemClock.elapsedRealtime()
            refreshBarMetricsIfNeeded(now, effAlpha)
            val hasProbe = cachedTrackRightPx > cachedTrackLeftPx && lastProbedWidth == bar.width
            val localLeft = if (hasProbe) cachedTrackLeftPx else bar.paddingLeft.toFloat()
            val localRight = if (hasProbe) cachedTrackRightPx else (bar.width - bar.paddingRight).toFloat()
            val localCy = if (lastProbedHeight == bar.height && cachedTrackCenterYPx > 0f) cachedTrackCenterYPx else bar.height * 0.5f

            val pLeft = floatArrayOf(localLeft, localCy)
            val pRight = floatArrayOf(localRight, localCy)
            mapPointToRoot(bar, root, pLeft)
            mapPointToRoot(bar, root, pRight)
            mappedLeftX = minOf(pLeft[0], pRight[0])
            mappedRightX = maxOf(pLeft[0], pRight[0])
            mappedCenterY = (pLeft[1] + pRight[1]) * 0.5f
            mappedScaleY = effectiveScaleY(bar, root)

            if (mappedRightX <= mappedLeftX + 1f || mappedCenterY < -spillY || mappedCenterY > root.height + spillY) {
                if (alpha != 0f) alpha = 0f
                if (visibility != View.GONE) visibility = View.GONE
                return
            }

            val desiredH = (cachedTrackHeightPx * mappedScaleY + spillY * 2).roundToInt().coerceAtLeast(spillY * 2 + 8)
            val lp = layoutParams
            if (lp != null && lp.height != desiredH) {
                lp.height = desiredH
                requestLayout()
            }
            val actualH = if (height > 0) height.toFloat() else desiredH.toFloat()
            x = (-left).toFloat()
            y = mappedCenterY - top - actualH * 0.5f
            alpha = effAlpha
            if (visibility != View.VISIBLE) visibility = View.VISIBLE
        }

        override fun onDraw(canvas: Canvas) {
            val k = glowIntensity.coerceIn(0f, 1f)
            if (k <= 0.005f || lastEffAlpha <= 0.005f) return

            val now = SystemClock.elapsedRealtime()
            val d = resources.displayMetrics.density
            val rawFrac = runCatching {
                val p = bar as? android.widget.ProgressBar ?: return@runCatching 1f
                p.progress.toFloat() / p.max.coerceAtLeast(1).toFloat()
            }.getOrDefault(1f).coerceIn(0f, 1f)

            val scrubbing = bar.isPressed
            val (frac, morphBell) = morphState.update(
                rawFrac = rawFrac,
                nowMs = now,
                fusionActive = active && !scrubbing,
                scrubbing = scrubbing,
            )
            val fusion = if (scrubbing) 0f else (k * (0.62f + 0.38f * morphBell)).coerceIn(0f, 1f)

            val trackH = cachedTrackHeightPx * mappedScaleY
            val halfH = trackH * 0.5f
            val left = mappedLeftX + halfH * 0.5f
            val fullRight = (mappedRightX - halfH * 0.5f).coerceAtLeast(left + 1f)
            val trackW = fullRight - left
            val playedRight = left + trackW * frac.coerceAtLeast(0.015f)
            val cy = height / 2f
            val accent = albumAccentColor ?: cachedSampleColor ?: WARM_DAWN_DEFAULT

            val agsl = runtimeShader
            if (!shaderFailed && agsl != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val drawn = runCatching {
                    val timeSec = ((now - activationStartedMs).coerceAtLeast(0L) / 1000f)
                    agsl.setFloatUniform("uLeft", left)
                    agsl.setFloatUniform("uRight", fullRight)
                    agsl.setFloatUniform("uPlayedRight", playedRight)
                    agsl.setFloatUniform("uCenterY", cy)
                    agsl.setFloatUniform("uHalfHeight", halfH)
                    agsl.setFloatUniform("uDensity", d)
                    agsl.setFloatUniform("uIntensity", k)
                    agsl.setFloatUniform("uTime", timeSec)
                    agsl.setFloatUniform("uFusion", fusion)
                    agsl.setFloatUniform(
                        "uAlbumColor",
                        Color.red(accent) / 255f,
                        Color.green(accent) / 255f,
                        Color.blue(accent) / 255f,
                    )
                    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shaderPaint)
                    true
                }.getOrDefault(false)
                if (drawn) return
                shaderFailed = true
                setLayerType(LAYER_TYPE_SOFTWARE, null)
            }

            drawSoftwareFallback(canvas, d, left, fullRight, trackW, playedRight, cy, trackH, accent, k, now, frac, fusion)
        }

        private fun drawSoftwareFallback(
            canvas: Canvas,
            d: Float,
            left: Float,
            fullRight: Float,
            trackW: Float,
            playedRight: Float,
            cy: Float,
            trackH: Float,
            accent: Int,
            k: Float,
            now: Long,
            frac: Float,
            fusion: Float,
        ) {
            val dawnOuter = blendColor(accent, WARM_DAWN_HAZE, 0.38f)
            val dawnMid = blendColor(accent, WARM_DAWN_MID, 0.70f)
            val dawnCore = blendColor(accent, Color.WHITE, 0.90f)
            val breath = 0.90f + 0.10f * kotlin.math.sin((now % 2800L) / 2800.0 * Math.PI * 2.0).toFloat()
            val fullBoost = 1f + fusion * 0.32f
            val splitWeight = 1f - fusion * 0.72f

            softwarePaint.style = Paint.Style.STROKE
            softwarePaint.strokeCap = Paint.Cap.ROUND

            softwarePaint.shader = null
            softwarePaint.color = dawnOuter
            softwarePaint.strokeWidth = trackH * 1.8f
            softwarePaint.alpha = (64f * k * breath * fullBoost).roundToInt().coerceIn(0, 255)
            softwarePaint.maskFilter = blurMid
            canvas.drawLine(left, cy, fullRight, cy, softwarePaint)

            softwarePaint.color = dawnMid
            softwarePaint.strokeWidth = trackH * 1.0f
            softwarePaint.alpha = (75f * k * fullBoost).roundToInt().coerceIn(0, 255)
            softwarePaint.maskFilter = blurCore
            canvas.drawLine(left, cy, fullRight, cy, softwarePaint)

            if (frac > 0.005f) {
                softwarePaint.color = dawnCore
                softwarePaint.strokeWidth = trackH * 1.1f
                softwarePaint.alpha = (135f * k * splitWeight).roundToInt().coerceIn(0, 255)
                softwarePaint.maskFilter = blurCore
                canvas.drawLine(left, cy, playedRight, cy, softwarePaint)

                val headRadius = 9f * d
                headPaint.shader = RadialGradient(
                    playedRight, cy, headRadius,
                    intArrayOf(
                        withAlpha(Color.WHITE, (90f * k * (1f - fusion * 0.55f)).roundToInt()),
                        0x00FFFFFF,
                    ),
                    null,
                    Shader.TileMode.CLAMP,
                )
                canvas.drawCircle(playedRight, cy, headRadius, headPaint)
                headPaint.shader = null
            }

            softwarePaint.shader = null
            softwarePaint.maskFilter = null
            softwarePaint.strokeCap = Paint.Cap.BUTT
        }
    }
}
