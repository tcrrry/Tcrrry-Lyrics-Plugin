package dev.kifranei.ampp.media

import android.animation.ValueAnimator
import android.graphics.drawable.BitmapDrawable
import android.os.Handler
import android.os.Looper
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import java.lang.reflect.Method
import java.util.WeakHashMap

/**
 * 自动过渡（Crossfade）接入 + 切歌封面过渡动画。
 *
 * 关键事实（已从 Apple Music 7.0.0-beta / 1606-1611 的 dex 反查确认）：Android 版播放内核
 * **原生内置完整 crossfade 引擎**，不是只有 UI 残留——
 *   - `com.apple.android.music.playback.player.PlayerAudioFadeControl`（+ Companion.getSupportsCrossFade）
 *   - `com.google.android.exoplayer2.DefaultAudioFadeControl`（ExoPlayer 底层淡化）
 *   - `com.apple.android.music.playback.preferences.MediaPlaybackPreferences`（crossfade 状态/时长持久化）
 *   - `com.apple.android.music.renderer.javanative.CrossfadeRecipe`（C++ 无缝淡化渲染）
 *   - `settings2/fragment/CrossfadeSettingsFragment`（设置页组件已编译进 APK，入口被隐藏）
 *
 * 因此这里**不是从零实现淡化算法**，而是把插件设置"接通"到原生引擎：
 *  1. hook 关键 getter 并覆盖返回值，让引擎与任何读取方都认为 crossfade 已启用、时长为插件配置值
 *     （无需拿到 MediaPlayerController 单例，不依赖易碎的广播格式，是 Xposed 里最稳的偏好注入）。
 *  2. 监听 `ImageView.setImageDrawable` 检测封面换图，在切歌时对封面做淡入 + 轻微缩放的过渡动画。
 *
 * 注意：`getCrossFadeState` 返回 `CrossFadeState` 枚举对象，无法安全伪造，故不覆盖它，
 * 依靠 `isCrossFadeEnabled`（驱动 ExoPlayer 的 shouldLoadNextMediaPeriodWithCrossFade / doCrossFade）来生效。
 */
internal class CrossfadeIntegration(
    private val build: TargetBuild,
    private val loader: ClassLoader,
    private val enabled: () -> Boolean,
    private val durationSeconds: () -> Int,
    private val animate: () -> Boolean,
) {
    fun install(): TargetCapabilityInstall {
        val scope = PluginScope()
        val main = Handler(Looper.getMainLooper())
        try {
            var applied = 0
            var unsupported = 0

            fun type(name: String) = runCatching { loader.loadClass(name) }.getOrNull()
            fun getter(cls: String, name: String): Method? =
                type(cls)?.getDeclaredMethod(name)?.apply { isAccessible = true }

            /** 覆盖无参 getter 的返回值，把插件配置注入原生引擎。 */
            fun hookGetter(member: Method?, value: Any?): Boolean {
                if (member == null) { unsupported++; return false }
                val ok = MediaRuntime.hookMethod(member, object : PluginMethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (param.throwable == null && enabled()) param.result = value
                    }
                }, scope)
                if (ok) applied++ else unsupported++
                return ok
            }

            // ---- 1) 引擎层：告诉 ExoPlayer / Apple 淡入淡出控制"已启用"，时长为配置值 ----
            // 能力开关：宿主支持 crossfade 时原本就返回 true，这里保证始终可启用。
            hookGetter(getter("com.apple.android.music.playback.player.PlayerAudioFadeControl\$Companion", "getSupportsCrossFade"), true)
            // ExoPlayer 实现类（真正的淡化驱动）。
            hookGetter(getter("com.google.android.exoplayer2.DefaultAudioFadeControl", "isCrossFadeEnabled"), true)
            hookGetter(getter("com.google.android.exoplayer2.DefaultAudioFadeControl", "getCrossFadeDuration"), durationSeconds())
            // Apple 自己的包装层。
            hookGetter(getter("com.apple.android.music.playback.player.PlayerAudioFadeControl", "isCrossFadeEnabled"), true)
            hookGetter(getter("com.apple.android.music.playback.player.PlayerAudioFadeControl", "getCrossFadeDuration"), durationSeconds())
            // 持久化偏好层：任何读取方拿到插件配置（同时保证下次会话初始化也读到）。
            hookGetter(getter("com.apple.android.music.playback.preferences.MediaPlaybackPreferences", "getCrossFadeDuration"), durationSeconds())

            // ---- 2) 切歌封面过渡动画：封面换图时淡入 + 轻微缩放 ----
            val imageId = PluginProfiles.find(build.packageName, build.versionName, build.versionCode)
                ?.document?.optJSONObject("playerRecovery")?.optString("imageId")
            val animators = WeakHashMap<ImageView, ValueAnimator>()
            val seen = WeakHashMap<ImageView, Any?>()
            val setImageDrawable =
                ImageView::class.java.getDeclaredMethod("setImageDrawable", android.graphics.drawable.Drawable::class.java)
            var animHooked = false
            if (animate() && !imageId.isNullOrBlank()) {
                animHooked = MediaRuntime.observeMethod(setImageDrawable, object : PluginMethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.throwable != null) return
                        val view = param.thisObject as? ImageView ?: return
                        if (view.id != view.resources.getIdentifier(imageId, "id", build.packageName)) return
                        val bitmap = (view.drawable as? BitmapDrawable)?.bitmap ?: return
                        if (seen[view] === bitmap) return
                        seen[view] = bitmap
                        main.post {
                            if (!scope.isActive || !view.isAttachedToWindow) return@post
                            animators.remove(view)?.cancel()
                            view.alpha = 0.25f; view.scaleX = 0.97f; view.scaleY = 0.97f
                            val duration = (durationSeconds().coerceAtLeast(1) * 200L).coerceIn(400L, 1600L)
                            val animator = ValueAnimator.ofFloat(0f, 1f).apply {
                                this.duration = duration
                                interpolator = DecelerateInterpolator()
                                addUpdateListener {
                                    val f = it.animatedValue as Float
                                    view.alpha = 0.25f + 0.75f * f
                                    val s = 0.97f + 0.03f * f
                                    view.scaleX = s; view.scaleY = s
                                }
                            }
                            animator.start(); animators[view] = animator
                        }
                    }
                }, scope)
                if (animHooked) applied++ else unsupported++
            }

            scope.activate()
            val hookCount = applied - (if (animHooked) 1 else 0)
            return TargetCapabilityInstall.Active(
                if (hookCount > 0)
                    "Crossfade 已接通原生引擎（$hookCount 处 getter 注入）" +
                        if (animHooked) "，切歌封面过渡动画已启用" else "，切歌动画关闭"
                else "未找到 Crossfade 引擎符号，当前 Apple Music 构建可能不支持，功能未生效")
        } catch (error: Throwable) { scope.close(); throw error }
    }
}
