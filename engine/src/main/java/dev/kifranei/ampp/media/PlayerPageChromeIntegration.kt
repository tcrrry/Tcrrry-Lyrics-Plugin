package dev.kifranei.ampp.media

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.ViewTreeObserver
import java.util.IdentityHashMap
import kotlin.math.max

/** Keep material islands outside native background clipping during the mini-player morph. */
internal class PlayerPageChromeIntegration(
    private val build: TargetBuild,
    private val showHandle: Boolean,
    private val systemCorners: Boolean,
) : PlayerPageChromeTarget {
    override fun install(): TargetCapabilityInstall {
        val method = PluginProfiles.find(build.packageName, build.versionName, build.versionCode)
            ?.let { PluginProfiles.method("player-controller-create-view") }
            ?: return TargetCapabilityInstall.Unsupported("No verified player-page contract for ${build.displayName}")
        val scope = PluginScope()
        val slide = PluginProfiles.method("player-page-progress")
        val resume = PluginProfiles.method("player-controller-resume")
        val getView = PluginProfiles.method("player-fragment-view")
        val destroy = PluginProfiles.method("player-controller-destroy-view")
        val motionOutline = PluginProfiles.method("player-motion-outline")
        val motionOwner = PluginProfiles.field("player-motion-outline-owner")
        val motionProgress = PluginProfiles.field("player-motion-outline-progress")
        val collapsedRadius = PluginProfiles.field("player-motion-collapsed-radius")
        val callbackOwner = PluginProfiles.field("player-page-callback-owner")
        val callback = PluginProfiles.field("player-page-callback")
        val progress = PluginProfiles.field("player-page-progress-value")
        val behavior = PluginProfiles.field("player-page-behavior")
        val behaviorState = PluginProfiles.field("player-page-behavior-state")
        val backgroundPath = PluginProfiles.field("player-background-path")
        val main = Handler(Looper.getMainLooper())
        val installed = IdentityHashMap<View, ChromeState>()
        val owners = IdentityHashMap<Any, View>()
        fun remove(root: View, state: ChromeState) {
            root.removeOnAttachStateChangeListener(state.attachListener)
            root.removeOnLayoutChangeListener(state.layoutListener)
            state.tree?.takeIf { it.isAlive }?.removeOnPreDrawListener(state.preDrawListener)
            if (root.foreground === state.foreground) root.foreground = state.originalForeground
            state.background?.let { layer ->
                if (layer.outlineProvider === state.outlineProvider) {
                    layer.outlineProvider = state.originalOutline
                    layer.clipToOutline = state.originalClipToOutline
                }
                if (backgroundPath.get(layer) === state.path) backgroundPath.set(layer, state.originalPath)
                layer.invalidateOutline(); layer.invalidate()
            }
            state.motion?.invalidateOutline()
            if (state.page?.background === state.pageBackground && state.pageBackground != null) {
                state.page.background = state.originalPageBackground
            }
            installed.remove(root)
            owners.entries.removeAll { it.value === root }
        }
        fun update(root: View, state: ChromeState) {
            val portrait = root.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
            root.getLocationInWindow(state.position)
            root.rootView.getLocationInWindow(state.decorPosition)
            val inset = (SystemWindowGeometry.statusBarInset(root) - (state.position[1] - state.decorPosition[1])).coerceAtLeast(0)
            val artwork = root.findViewById<View>(state.artworkId)
            // AM retains its hidden square card while displaying immersive artwork
            // in the motion switcher. Check the rendered layer, not that stale card.
            val fullscreen = state.motion?.let { it.isShown && it.alpha > .01f } == true ||
                (artwork != null && artwork.isShown && root.width > 0 && artwork.width >= root.width - 1)
            // Two-pane (w600dp) players already carry AM++'s handle above the artwork column; draw ours only on single-pane.
            val alpha = if (showHandle) PlayerChromeGeometry.handleAlpha(state.expansion, fullscreen,
                root.resources.configuration.screenWidthDp) else 0
            state.foreground.update(inset, alpha)
            val radius = if (systemCorners && portrait) SystemWindowGeometry.topCornerRadius(root) else 0f
            val bottomRadius = if (radius > 0f) SystemWindowGeometry.bottomCornerRadius(root) else 0f
            state.page?.let { page ->
                val background = page.background
                if (radius > 0f && background is GradientDrawable) {
                    if (background !== state.pageBackground) {
                        state.originalPageBackground = background
                        state.pageBackground = background.constantState?.newDrawable(page.resources)?.mutate() as? GradientDrawable
                        state.pageRadius = -1f
                    }
                    val pageKey = radius + bottomRadius * 1000f
                    state.pageBackground?.let { rounded ->
                        if (state.pageRadius != pageKey) {
                            val native = state.originalPageBackground as GradientDrawable
                            val corners = native.cornerRadii?.copyOf() ?: FloatArray(8) { native.cornerRadius }
                            for (index in 0..3) corners[index] = radius
                            for (index in 4..7) corners[index] = bottomRadius
                            rounded.cornerRadii = corners
                            state.pageRadius = pageKey
                        }
                        if (page.background !== rounded) page.background = rounded
                    }
                } else if (radius <= 0f && background === state.pageBackground && background != null) {
                    page.background = state.originalPageBackground
                }
            }
            val layer = state.background
            if (layer != null) {
                if (radius > 0f) {
                    val currentPath = backgroundPath.get(layer)
                    if (currentPath !== state.path) state.originalPath = currentPath as? Path
                    if (state.outlineProvider.radius != radius || state.bottomRadius != bottomRadius || state.pathWidth != layer.width ||
                        state.pathHeight != layer.height || currentPath !== state.path) {
                        state.outlineProvider.radius = radius
                        state.bottomRadius = bottomRadius
                        state.outlineProvider.bottomRadius = bottomRadius
                        state.pathWidth = layer.width; state.pathHeight = layer.height
                        state.path.reset()
                        state.path.addRoundRect(RectF(0f, 0f, layer.width.toFloat(), layer.height.toFloat()),
                            floatArrayOf(radius, radius, radius, radius, bottomRadius, bottomRadius, bottomRadius, bottomRadius), Path.Direction.CW)
                        backgroundPath.set(layer, state.path)
                        layer.invalidateOutline(); layer.invalidate()
                        state.motion?.invalidateOutline()
                    }
                    if (layer.outlineProvider !== state.outlineProvider) layer.outlineProvider = state.outlineProvider
                    if (!layer.clipToOutline) layer.clipToOutline = true
                } else {
                    if (backgroundPath.get(layer) === state.path) backgroundPath.set(layer, state.originalPath)
                    if (layer.outlineProvider === state.outlineProvider) {
                        layer.outlineProvider = state.originalOutline
                        layer.clipToOutline = state.originalClipToOutline
                        layer.invalidateOutline(); layer.invalidate()
                    }
                }
            }
            val mode = if (fullscreen) "fullscreen" else "normal"
            if (state.expansion >= .999f && state.loggedMode != mode) {
                state.loggedMode = mode
                MediaRuntime.log("player_chrome mode=$mode handle=$alpha radius=$radius motionAlpha=${state.motion?.alpha} artwork=${artwork?.width}x${artwork?.height} root=${root.width}x${root.height}")
            }
        }
        fun attach(owner: Any, root: View) {
            if (!scope.isActive || installed.containsKey(root)) return
            val state = ChromeState(root)
            val expanded = behavior.get(owner)?.let { behaviorState.getInt(it) == 3 } == true
            val initial = callback.get(owner)?.let { progress.getFloat(it) } ?: 0f
            state.expansion = if (expanded) 1f else initial.coerceIn(0f, 1f)
            state.background?.let { state.originalPath = backgroundPath.get(it) as? Path }
            installed[root] = state
            owners[owner] = root
            if (showHandle) root.foreground = state.foreground
            val refresh = { if (scope.isActive) update(root, state) }
            state.preDrawListener = ViewTreeObserver.OnPreDrawListener { refresh(); true }
            fun listen() {
                state.tree?.takeIf { it.isAlive }?.removeOnPreDrawListener(state.preDrawListener)
                state.tree = root.viewTreeObserver.also { it.addOnPreDrawListener(state.preDrawListener) }
            }
            state.layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> refresh() }
            state.attachListener = object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) { listen(); refresh() }
                override fun onViewDetachedFromWindow(view: View) {
                    state.tree?.takeIf { it.isAlive }?.removeOnPreDrawListener(state.preDrawListener)
                    state.tree = null
                }
            }
            root.addOnLayoutChangeListener(state.layoutListener)
            root.addOnAttachStateChangeListener(state.attachListener)
            listen(); refresh()
            MediaRuntime.log("player_chrome attached initial=${state.expansion}; native background clipping only")
        }
        fun ensure(owner: Any): View? {
            owners[owner]?.let { return it }
            val root = getView.invoke(owner) as? View ?: return null
            attach(owner, root)
            return owners[owner]
        }
        try {
            check(MediaRuntime.observeMethod(method, object : PluginMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null) return
                    val root = param.result as? View ?: return
                    val owner = param.thisObject ?: return
                    if (Looper.myLooper() == Looper.getMainLooper()) attach(owner, root)
                    else main.post { attach(owner, root) }
                }
            }, scope))
            check(MediaRuntime.observeMethod(getView, object : PluginMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null || !method.declaringClass.isInstance(param.thisObject)) return
                    val root = param.result as? View ?: return
                    val owner = param.thisObject ?: return
                    if (Looper.myLooper() == Looper.getMainLooper()) attach(owner, root)
                    else main.post { attach(owner, root) }
                }
            }, scope))
            check(MediaRuntime.observeMethod(resume, object : PluginMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable == null) param.thisObject?.let(::ensure)
                }
            }, scope))
            check(MediaRuntime.observeMethod(slide, object : PluginMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null) return
                    val owner = param.thisObject?.let(callbackOwner::get) ?: return
                    val root = ensure(owner) ?: return
                    val state = installed[root] ?: return
                    state.expansion = (param.args[0] as Number).toFloat().coerceIn(0f, 1f)
                    update(root, state)
                }
            }, scope))
            if (systemCorners) check(MediaRuntime.observeMethod(motionOutline, object : PluginMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null) return
                    val native = param.thisObject ?: return
                    val owner = motionOwner.get(native) ?: return
                    val root = ensure(owner) ?: return
                    if (root.resources.configuration.orientation != Configuration.ORIENTATION_PORTRAIT) return
                    val system = SystemWindowGeometry.topCornerRadius(root)
                    if (system <= 0f) return
                    val view = param.args[0] as View
                    val outline = param.args[1] as android.graphics.Outline
                    val radius = PlayerChromeGeometry.motionRadius(system, collapsedRadius.getFloat(native), motionProgress.getFloat(native))
                    // Keep the provider's native type: AM casts it during every slide.
                    // Only the upper corners follow the system; preserve native lower corners.
                    if (Build.VERSION.SDK_INT >= 33) {
                        val bottom = outline.radius.coerceAtLeast(0f)
                        val path = installed[root]?.motionPath ?: return
                        path.reset()
                        path.addRoundRect(RectF(0f, 0f, view.width.toFloat(), view.height.toFloat()),
                            floatArrayOf(radius, radius, radius, radius, bottom, bottom, bottom, bottom), Path.Direction.CW)
                        outline.setPath(path)
                    } else {
                        outline.setRoundRect(0, 0, view.width, view.height + kotlin.math.ceil(radius).toInt(), radius)
                    }
                }
            }, scope))
            check(MediaRuntime.observeMethod(destroy, object : PluginMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val root = owners[param.thisObject] ?: return
                    installed[root]?.let { remove(root, it) }
                }
            }, scope))
            scope.onClose {
                val action = {
                    installed.entries.toList().forEach { (root, state) -> remove(root, state) }
                    installed.clear(); owners.clear(); main.removeCallbacksAndMessages(null)
                }
                if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post(action)
            }
            scope.activate()
            return TargetCapabilityInstall.Active("Player handle=$showHandle, system rounded corners=$systemCorners installed")
        } catch (error: Throwable) { scope.close(); throw error }
    }

    private class ChromeState(val root: View) {
        fun id(name: String) = root.resources.getIdentifier(name, "id", "com.apple.android.music")
        val artworkId = id("fullplayerSongImage")
        val page = root.findViewById<View>(id("player_root"))
        var originalPageBackground: Drawable? = null
        var pageBackground: GradientDrawable? = null
        var pageRadius = -1f
        var bottomRadius = -1f
        val background = root.findViewById<View>(id("background_layers"))
        val motion = root.findViewById<View>(id("motion_switcher"))
        val originalForeground = root.foreground
        val foreground = PlayerPageChromeDrawable(originalForeground, root.resources.displayMetrics.density)
        val originalOutline = background?.outlineProvider
        val originalClipToOutline = background?.clipToOutline ?: false
        val outlineProvider = PlayerPageOutlineProvider()
        val path = Path()
        val motionPath = Path()
        var originalPath: Path? = null
        var pathWidth = -1
        var pathHeight = -1
        var expansion = 0f
        var loggedMode: String? = null
        val position = IntArray(2)
        val decorPosition = IntArray(2)
        var tree: ViewTreeObserver? = null
        lateinit var preDrawListener: ViewTreeObserver.OnPreDrawListener
        lateinit var attachListener: View.OnAttachStateChangeListener
        lateinit var layoutListener: View.OnLayoutChangeListener
    }
}

internal fun interface PlayerPageChromeTarget { fun install(): TargetCapabilityInstall }

/** Draws only the handle; the original Apple Music foreground remains untouched underneath. */
private class PlayerPageChromeDrawable(
    private val base: Drawable?,
    private val density: Float,
) : Drawable() {
    var topInset: Int = 0
    fun update(inset: Int, alpha: Int) {
        if (topInset == inset && paint.alpha == alpha) return
        topInset = inset
        paint.alpha = alpha
        invalidateSelf()
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        alpha = 0
    }

    override fun draw(canvas: Canvas) {
        base?.let {
            val old = android.graphics.Rect(it.bounds)
            it.bounds = bounds
            it.draw(canvas)
            it.bounds = old
        }
        if (paint.alpha == 0) return
        val width = 52f * density
        val height = 4f * density
        val margin = 16f * density
        val top = max(margin, topInset + 14f * density)
        val left = bounds.exactCenterX() - width / 2f
        canvas.drawRoundRect(RectF(left, top, left + width, top + height), height / 2f, height / 2f, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha.coerceIn(0, 255)
        base?.alpha = alpha
        invalidateSelf()
    }
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        paint.colorFilter = colorFilter
        base?.colorFilter = colorFilter
        invalidateSelf()
    }
    @Deprecated("Drawable opacity contract")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth(): Int = -1
    override fun getIntrinsicHeight(): Int = -1

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        super.onBoundsChange(bounds)
        base?.bounds = bounds
    }

    override fun isStateful(): Boolean = base?.isStateful == true
    override fun onStateChange(state: IntArray): Boolean = base?.setState(state) == true
}

private class PlayerPageOutlineProvider : ViewOutlineProvider() {
    var radius: Float = 0f
    var bottomRadius: Float = 0f

    override fun getOutline(view: View, outline: android.graphics.Outline) {
        if (radius <= 0f || view.width <= 0 || view.height <= 0) {
            outline.setRect(0, 0, view.width, view.height)
            return
        }
        if (Build.VERSION.SDK_INT >= 33) {
            val path = Path()
            val radii = floatArrayOf(radius, radius, radius, radius, bottomRadius, bottomRadius, bottomRadius, bottomRadius)
            path.addRoundRect(RectF(0f, 0f, view.width.toFloat(), view.height.toFloat()), radii, Path.Direction.CW)
            outline.setPath(path)
        } else {
            outline.setRoundRect(0, 0, view.width, view.height + kotlin.math.ceil(radius).toInt(), radius)
        }
    }
}

private object SystemWindowGeometry {
    fun statusBarInset(view: View): Int {
        val insets = view.rootView.rootWindowInsets ?: view.rootWindowInsets ?: return 0
        return if (Build.VERSION.SDK_INT >= 30) insets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top
        else @Suppress("DEPRECATION") insets.systemWindowInsetTop
    }

    /** Bottom screen corners; falls back to the top radius because most devices are symmetric. */
    fun bottomCornerRadius(view: View): Float {
        val insets = view.rootView.rootWindowInsets ?: view.rootWindowInsets
        if (Build.VERSION.SDK_INT >= 31 && insets != null) {
            val left = insets.getRoundedCorner(android.view.RoundedCorner.POSITION_BOTTOM_LEFT)
            val right = insets.getRoundedCorner(android.view.RoundedCorner.POSITION_BOTTOM_RIGHT)
            if (left != null || right != null) return max(left?.radius ?: 0, right?.radius ?: 0).toFloat()
        }
        val resources = view.resources
        for (name in listOf("rounded_corner_radius_bottom", "rounded_corner_radius")) {
            val id = resources.getIdentifier(name, "dimen", "android")
            if (id != 0) return runCatching { resources.getDimension(id).coerceAtLeast(0f) }.getOrDefault(0f)
        }
        return topCornerRadius(view)
    }

    fun topCornerRadius(view: View): Float {
        val insets = view.rootView.rootWindowInsets ?: view.rootWindowInsets
        if (Build.VERSION.SDK_INT >= 31 && insets != null) {
            val left = insets.getRoundedCorner(android.view.RoundedCorner.POSITION_TOP_LEFT)
            val right = insets.getRoundedCorner(android.view.RoundedCorner.POSITION_TOP_RIGHT)
            if (left != null || right != null) return max(left?.radius ?: 0, right?.radius ?: 0).toFloat()
        }
        val resources = view.resources
        for (name in listOf("rounded_corner_radius_top", "rounded_corner_radius")) {
            val id = resources.getIdentifier(name, "dimen", "android")
            if (id != 0) return runCatching { resources.getDimension(id).coerceAtLeast(0f) }.getOrDefault(0f)
        }
        return 0f
    }
}
