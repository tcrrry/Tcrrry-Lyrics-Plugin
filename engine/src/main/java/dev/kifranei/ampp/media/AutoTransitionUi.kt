package dev.kifranei.ampp.media

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.ImageView
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * AutoMix UI 按钮注入（iOS 风格）。
 *
 * 在播放队列（Up Next）页面顶部按钮行，洗牌/重复按钮旁边注入 AutoMix 胶囊按钮。
 * 样式对照 iOS：胶囊形圆角背景 + ∞ 图标（无文字），选中时白底黑字高亮。
 *
 * 关键：精确定位到 `shuffle_repeat_badge` / `repeat_mode_indicator` 的父容器，
 * 不再用泛泛的 findHorizontalButtonBar（会误注入到进度条行）。
 */
internal object AutoTransitionUi {
    private const val TAG_MARKER = "ampp_automix_button"
    private val main = Handler(Looper.getMainLooper())
    private val pillRefs = java.util.concurrent.CopyOnWriteArrayList<java.lang.ref.WeakReference<AutoMixPillView>>()
    private val pendingInjects = java.util.WeakHashMap<View, Runnable>()
    @Volatile private var lastContainerMissLogMs = 0L
    @Volatile var enabled = false
    @Volatile var showButton = true
    @Volatile private var lastPackageName = "com.apple.android.music"
    var onToggle: ((Boolean) -> Unit)? = null

    fun syncEnabled(newState: Boolean) {
        enabled = newState
        main.post {
            pillRefs.removeAll { it.get() == null }
            for (ref in pillRefs) {
                ref.get()?.active = newState
            }
        }
    }

    fun syncVisible(visible: Boolean) {
        showButton = visible
        main.post {
            pillRefs.removeAll { it.get() == null }
            for (ref in pillRefs) {
                ref.get()?.visibility = if (visible) View.VISIBLE else View.GONE
            }
            if (visible) {
                injectIntoActiveWindows(lastPackageName)
            }
        }
    }

    fun install(scope: PluginScope, context: Context, packageName: String): Boolean {
        lastPackageName = packageName
        return try {
            val fragmentCls = Class.forName("com.apple.android.music.player.fragment.NewPlayerQueueViewFragment", false, context.classLoader)
            val onViewCreated = fragmentCls.getMethod("onViewCreated", View::class.java, Bundle::class.java)
            MediaRuntime.observeMethod(onViewCreated, object : PluginMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null) return
                    val view = param.args[0] as? View ?: return
                    scheduleInject(view, packageName)
                }
            }, scope)
            for (lifecycleName in listOf("onStart", "onResume")) {
                runCatching {
                    val m = fragmentCls.getMethod(lifecycleName)
                    MediaRuntime.observeMethod(m, object : PluginMethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (param.throwable != null) return
                            val frag = param.thisObject ?: return
                            val view = runCatching { frag.javaClass.getMethod("getView").invoke(frag) as? View }.getOrNull() ?: return
                            scheduleInject(view, packageName)
                        }
                    }, scope)
                }
            }
            main.post { injectIntoActiveWindows(packageName) }
            runCatching {
                val playerCls = Class.forName("com.apple.android.music.player.fragment.PlayerMainFragment", false, context.classLoader)
                val create = playerCls.getMethod("onCreateView", android.view.LayoutInflater::class.java, ViewGroup::class.java, Bundle::class.java)
                MediaRuntime.observeMethod(create, object : PluginMethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.throwable != null) return
                        val root = param.result as? View ?: return
                        main.post { injectPlayerButton(root, packageName) }
                    }
                }, scope)
            }
            true
        } catch (e: Throwable) {
            FileLogger.log("UI install failed: ${e}")
            false
        }
    }

    private fun cardColor(view: View): Int = runCatching {
        view.javaClass.getMethod("getCardBackgroundColor").invoke(view)
            ?.let { it as? android.content.res.ColorStateList }?.defaultColor
    }.getOrNull() ?: Color.TRANSPARENT

    /** Exact on-screen color of a native button (includes its translucency). */
    private fun renderedColor(view: View): Int = runCatching {
        if (view.width <= 0 || view.height <= 0) return 0
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        // Sample only the pill's left/right body, never the centered icon.
        val xs = intArrayOf(view.width / 8, view.width * 7 / 8)
        val y = view.height / 2
        var a = 0L; var r = 0L; var g = 0L; var b = 0L; var n = 0
        for (x in xs) {
            val c = bitmap.getPixel(x.coerceIn(0, view.width - 1), y.coerceIn(0, view.height - 1))
            a += c ushr 24; r += (c shr 16) and 0xff; g += (c shr 8) and 0xff; b += c and 0xff; n++
        }
        ((a / n).toInt() shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
    }.getOrDefault(0)

    private fun setCardActive(view: View, active: Boolean, activeBg: Int, inactiveBg: Int,
                             activeBgDrawable: android.graphics.drawable.Drawable?,
                             inactiveBgDrawable: android.graphics.drawable.Drawable?,
                             activeIcon: Int, inactiveIcon: Int) {
        view.isActivated = active
        view.isSelected = active
        runCatching {
            val bg = if (active) activeBgDrawable else inactiveBgDrawable
            if (bg != null) view.background = bg.constantState?.newDrawable()?.mutate() ?: bg
            else view.javaClass.getMethod("setCardBackgroundColor", Int::class.javaPrimitiveType)
                .invoke(view, if (active) activeBg else inactiveBg)
        }
        runCatching {
            val iconId = view.resources.getIdentifier("button_off", "id", "com.apple.android.music")
            val icon = view.findViewById<ImageView>(iconId)
            icon?.imageTintList = android.content.res.ColorStateList.valueOf(if (active) activeIcon else inactiveIcon)
        }
    }

    private fun injectIntoActiveWindows(packageName: String) {
        runCatching {
            val wmgCls = Class.forName("android.view.WindowManagerGlobal")
            val wmg = wmgCls.getMethod("getInstance").invoke(null) ?: return
            val field = wmgCls.getDeclaredField("mViews").apply { isAccessible = true }
            val views = (field.get(wmg) as? List<*>)?.toList().orEmpty()
            for (v in views) {
                val root = v as? View ?: continue
                injectButton(root, packageName)
            }
        }
    }

    /** Queue pages often exist before the 3-button row is laid out; retry until it is ready. */
    private fun scheduleInject(root: View, packageName: String, attempt: Int = 0) {
        pendingInjects.remove(root)?.let { main.removeCallbacks(it) }
        val task = Runnable {
            pendingInjects.remove(root)
            if (!injectButton(root, packageName) && attempt < 20) {
                scheduleInject(root, packageName, attempt + 1)
            }
        }
        pendingInjects[root] = task
        main.postDelayed(task, if (attempt == 0) 0L else 120L)
    }

    private fun injectButton(root: View, packageName: String): Boolean {
        val existing = root.findViewWithTag<View>(TAG_MARKER)
        if (existing is AutoMixPillView) {
            existing.active = enabled
            existing.visibility = if (showButton) View.VISIBLE else View.GONE
            if (pillRefs.none { it.get() === existing }) {
                pillRefs.removeAll { it.get() == null }
                pillRefs.add(java.lang.ref.WeakReference(existing))
            }
            return true
        }
        existing?.let { (it.parent as? ViewGroup)?.removeView(it) }
        val context = root.context
        val dp = { v: Int -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics).toInt() }
        val screenHeight = context.resources.displayMetrics.heightPixels

        // 官方按钮行的容器：button_group_play_shuffle；优先用它，避免误插到顶部收藏/更多那一行。
        var container: ViewGroup? = null
        var anchor: View? = null
        val groupId = context.resources.getIdentifier("button_group_play_shuffle", "id", packageName)
        if (groupId != 0) {
            val group = root.findViewById<ViewGroup>(groupId)
            if (group != null && group.childCount >= 3) {
                container = group
                anchor = group.getChildAt(group.childCount - 1)
            }
        }
        // 精确定位：洗牌/重复按钮的父容器（横向按钮行），且必须在页面上部 1/3
        val shuffleId = context.resources.getIdentifier("shuffle_repeat_badge", "id", packageName)
        val repeatId = context.resources.getIdentifier("repeat_mode_indicator", "id", packageName)
        for (id in listOf(shuffleId, repeatId)) {
            if (container != null) break
            if (id == 0) continue
            val found = root.findViewById<View>(id)
            if (found != null && found.parent is ViewGroup) {
                val parent = found.parent as ViewGroup
                val loc = IntArray(2)
                found.getLocationOnScreen(loc)
                // 只接受页面上部 1/3 的容器（洗牌/循环按钮行），排除进度条行
                if (loc[1] < screenHeight / 3) {
                    container = parent
                    anchor = found
                    break
                }
            }
        }
        // 兜底：找队列页顶部、包含 ≥2 个 ImageView 的横向容器，且在页面上部
        if (container == null) {
            container = findNativeButtonRow(root, screenHeight / 2)
        }
        if (container == null || container !is LinearLayout || container.childCount < 3) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastContainerMissLogMs > 15_000L) {
                lastContainerMissLogMs = now
                FileLogger.log("UI container not found/invalid (shuffle/repeat ids: $shuffleId/$repeatId, container=${container?.javaClass?.simpleName})")
            }
            return false
        }

        // 直接复用 Apple Music 原生 layout_player_button（MaterialCardView + ripple），
        // 只替换内部图标，材质/高度/状态与旁边三个按钮完全一致。
        val nativeChildren = (0 until container.childCount)
            .map { container.getChildAt(it) }
            .filter { it.tag != TAG_MARKER }
        val activeSource = nativeChildren.firstOrNull { it.isActivated } ?: nativeChildren.getOrNull(0)
        val inactiveSource = nativeChildren.firstOrNull { !it.isActivated && it !== activeSource } ?: nativeChildren.getOrNull(1)
        fun vibrant(name: String, fallback: Int): Int = runCatching {
            val id = context.resources.getIdentifier(name, "color", packageName)
            if (id != 0) context.resources.getColor(id, context.theme) else fallback
        }.getOrDefault(fallback)
        // Copy the exact on-screen material of the native button directly to the left
        // (infinity), not the active/inactive samples of unrelated buttons.
        val neighbor = nativeChildren.lastOrNull()
        val neighborIcon = neighbor
            ?.findViewById<ImageView>(context.resources.getIdentifier("button_off", "id", packageName))
            ?.imageTintList?.defaultColor
            ?: neighbor?.findViewById<ImageView>(context.resources.getIdentifier("button_off", "id", packageName))
                ?.imageTintList?.defaultColor
            ?: Color.BLACK
        fun viewBg(view: View?): Int = view?.let { cardColor(it).takeIf { c -> c != 0 } ?: renderedColor(it).takeIf { c -> Color.alpha(c) > 0 } } ?: 0
        // 开启态用高对比白底黑标，关闭态用柔和半透玻璃底白标，避免与宿主状态采样反色
        val activeBg = Color.argb(235, 255, 255, 255)
        val inactiveBg = Color.argb(42, 255, 255, 255)
        val activeIcon = Color.rgb(20, 20, 20)
        val inactiveIcon = Color.argb(235, 255, 255, 255)
        FileLogger.log("UI pill colors activeBg=$activeBg inactiveBg=$inactiveBg activeIcon=$activeIcon inactiveIcon=$inactiveIcon show=$showButton enabled=$enabled")
        val pill: View = AutoMixPillView(context).apply {
            tag = TAG_MARKER
            setNativeColors(activeBg, inactiveBg)
            setNativeIcons(activeIcon, inactiveIcon)
            active = enabled
            visibility = if (showButton) View.VISIBLE else View.GONE
            setOnClickListener {
                enabled = !enabled
                syncEnabled(enabled)
                onToggle?.invoke(enabled)
            }
        }
        pillRefs.removeAll { it.get() == null }
        pillRefs.add(java.lang.ref.WeakReference(pill as AutoMixPillView))
        pill.minimumHeight = 0
        pill.layoutParams = if (container is LinearLayout) {
            val anchorLp = anchor?.layoutParams as? LinearLayout.LayoutParams
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                marginStart = anchorLp?.marginStart ?: dp(12)
                marginEnd = anchorLp?.marginEnd ?: 0
            }
        } else {
            ViewGroup.LayoutParams(dp(110), ViewGroup.LayoutParams.MATCH_PARENT)
        }
        container.post {
            val lp = pill.layoutParams as? LinearLayout.LayoutParams ?: return@post
            lp.height = anchor?.height?.takeIf { it > 0 } ?: lp.height
            lp.width = 0
            lp.weight = 1f
            pill.layoutParams = lp
        }
        // 插入到洗牌/重复按钮后面
        val index = container.indexOfChild(anchor)
        if (index >= 0) container.addView(pill, index + 1) else container.addView(pill)
        FileLogger.log("UI button injected into ${container.javaClass.simpleName} after ${anchor?.javaClass?.simpleName}")
        return true
    }

    private fun injectPlayerButton(root: View, packageName: String) {
        return
        if (root.findViewWithTag<View>(PLAYER_TAG) != null) return
        val context = root.context
        val dp = { v: Int -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics).toInt() }
        val containerId = context.resources.getIdentifier("player_container", "id", packageName)
        val parent = (if (containerId != 0) root.findViewById<ViewGroup>(containerId) else null)
            ?: (root as? ViewGroup) ?: return
        val button = AutoMixIconView(context).apply {
            tag = PLAYER_TAG
            active = enabled
            setOnClickListener {
                enabled = !enabled
                active = enabled
                    onToggle?.invoke(enabled)
            }
        }
        parent.addView(button, ViewGroup.LayoutParams(dp(42), dp(42)))
        button.post {
            button.x = (parent.width - dp(58)).toFloat()
            button.y = dp(14).toFloat()
        }
        FileLogger.log("UI player button injected into ${parent.javaClass.simpleName}")
    }

    private const val PLAYER_TAG = "ampp_automix_player_button"

    /** 找包含 3 个 MaterialCardView 按钮的原生按钮行。 */
    private fun findNativeButtonRow(root: View, maxY: Int): ViewGroup? {
        if (root is ViewGroup) {
            if (root is LinearLayout && root.orientation == LinearLayout.HORIZONTAL && root.childCount >= 3) {
                var cards = 0
                for (i in 0 until root.childCount) {
                    val child = root.getChildAt(i)
                    if (child.javaClass.name.contains("MaterialCardView") ||
                        child.findViewById<View>(root.context.resources.getIdentifier("button_off", "id", "com.apple.android.music")) != null
                    ) cards++
                }
                val loc = IntArray(2); root.getLocationOnScreen(loc)
                if (cards >= 3 && loc[1] < maxY) return root
            }
            for (i in 0 until root.childCount) {
                findNativeButtonRow(root.getChildAt(i), maxY)?.let { return it }
            }
        }
        return null
    }

    /** 找页面上部、包含 ≥2 个 ImageView 的横向按钮行 */
    private fun findTopButtonBar(root: View, maxY: Int): ViewGroup? {
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val child = root.getChildAt(i)
                if (child is LinearLayout && child.orientation == LinearLayout.HORIZONTAL) {
                    var imgCount = 0
                    for (j in 0 until child.childCount) {
                        if (child.getChildAt(j) is android.widget.ImageView) imgCount++
                    }
                    if (imgCount >= 3) {
                        val loc = IntArray(2)
                        child.getLocationOnScreen(loc)
                        if (loc[1] < maxY) return child
                    }
                }
                val found = findTopButtonBar(child, maxY)
                if (found != null) return found
            }
        }
        return null
    }

    private fun updatePill(pill: TextView) {
        val bg = pill.background as? GradientDrawable
        bg?.setColor(if (enabled) Color.WHITE else Color.parseColor("#1EFFFFFF"))
        pill.setTextColor(if (enabled) Color.BLACK else Color.WHITE)
        pill.invalidate()
    }

}

/** 与洗牌/循环同排的胶囊按钮，双环 AutoMix 图标。 */
internal class AutoMixPillView(context: Context) : View(context) {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.4f * context.resources.displayMetrics.density
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lensFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private var backdrop: Bitmap? = null
    private var capturing = false
    var active: Boolean = false
        set(value) { field = value; invalidate() }
    private var activeColor = Color.parseColor("#74FFFFFF")
    private var inactiveColor = Color.parseColor("#15FFFFFF")
    private var nativeIcon = Color.BLACK
    private var activeIconColor = Color.BLACK
    private var inactiveIconColor = Color.WHITE
    private var activeDrawable: android.graphics.drawable.Drawable? = null
    private var inactiveDrawable: android.graphics.drawable.Drawable? = null
    fun setNativeColors(activeBg: Int, inactiveBg: Int) {
        activeColor = activeBg; inactiveColor = inactiveBg; invalidate()
    }

    fun setNativeIcons(activeColor: Int, inactiveColor: Int) {
        activeIconColor = activeColor; inactiveIconColor = inactiveColor; invalidate()
    }

    fun setNativeIcon(color: Int) { nativeIcon = color; invalidate() }
    fun setNativeBackgrounds(active: android.graphics.drawable.Drawable?, inactive: android.graphics.drawable.Drawable?) {
        activeDrawable = active; inactiveDrawable = inactive; invalidate()
    }


    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post { captureBackdrop() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        backdrop = null
        post { captureBackdrop() }
    }

    private fun captureBackdrop() {
        if (capturing || width <= 0 || height <= 0) return
        capturing = true
        val root = rootView ?: return
        val w = root.width; val h = root.height
        if (w <= 0 || h <= 0) { capturing = false; return }
        runCatching {
            val full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(full)
            val visibility = this.visibility
            this.visibility = INVISIBLE
            try { root.draw(canvas) } finally { this.visibility = visibility }
            val loc = IntArray(2); getLocationInWindow(loc)
            val left = loc[0].coerceIn(0, w - 1); val top = loc[1].coerceIn(0, h - 1)
            val right = (left + width).coerceAtMost(w); val bottom = (top + height).coerceAtMost(h)
            if (right <= left || bottom <= top) return@runCatching
            val crop = Bitmap.createBitmap(full, left, top, right - left, bottom - top)
            val sw = (crop.width / 8).coerceAtLeast(1); val sh = (crop.height / 8).coerceAtLeast(1)
            val tiny = Bitmap.createScaledBitmap(crop, sw, sh, true)
            val pixels = IntArray(sw * sh); tiny.getPixels(pixels, 0, sw, 0, 0, sw, sh)
            boxBlur(pixels, sw, sh, 2)
            tiny.setPixels(pixels, 0, sw, 0, 0, sw, sh)
            backdrop = Bitmap.createScaledBitmap(tiny, crop.width, crop.height, true)
            invalidate()
        }
        capturing = false
    }

    private fun boxBlur(pixels: IntArray, w: Int, h: Int, radius: Int) {
        val src = pixels.copyOf(); val r = radius.coerceAtLeast(1)
        for (y in 0 until h) for (x in 0 until w) {
            var a = 0; var rr = 0; var gg = 0; var bb = 0; var n = 0
            for (dy in -r..r) for (dx in -r..r) {
                val xx = (x + dx).coerceIn(0, w - 1); val yy = (y + dy).coerceIn(0, h - 1)
                val c = src[yy * w + xx]; a += c ushr 24; rr += (c shr 16) and 0xff
                gg += (c shr 8) and 0xff; bb += c and 0xff; n++
            }
            pixels[y * w + x] = (a / n shl 24) or (rr / n shl 16) or (gg / n shl 8) or (bb / n)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val h = height.toFloat()
        val path = android.graphics.Path().apply { addRoundRect(RectF(0f, 0f, width.toFloat(), h), h / 2f, h / 2f, android.graphics.Path.Direction.CW) }
        var bg = if (active) activeColor else inactiveColor
        if (Color.alpha(bg) < 16) {
            bg = if (active) Color.argb(45, 255, 255, 255) else Color.argb(18, 255, 255, 255)
        }
        // Native background drawable already paints the exact material; only fall back
        // to manual painting when no native drawable is available.
        if (background == null) {
            canvas.save(); canvas.clipPath(path)
            backdrop?.let { canvas.drawBitmap(it, null, RectF(0f, 0f, width.toFloat(), h), Paint(Paint.FILTER_BITMAP_FLAG)) }
            fill.color = bg
            canvas.drawRect(0f, 0f, width.toFloat(), h, fill)
            canvas.restore()
            fill.color = Color.TRANSPARENT
        }
        val r = h * 0.20f
        val cy = h / 2f
        val cx = width / 2f
        val c1 = cx - r * 0.55f
        val c2 = cx + r * 0.55f
        val icon = if (active) activeIconColor else inactiveIconColor
        // 左圆：细描边，半透明
        stroke.color = (icon and 0x00FFFFFF) or (0x99 shl 24)
        stroke.strokeWidth = r * 0.22f
        canvas.drawCircle(c1, cy, r, stroke)
        // 右圆：粗甜甜圈，实心外圆 + 背景色内孔
        val donut = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = icon }
        canvas.drawCircle(c2, cy, r, donut)
        val hole = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = bg }
        canvas.drawCircle(c2, cy, r * 0.38f, hole)
    }
}

/** 原生按钮里的双环 AutoMix 图标。 */
internal class AutoMixIconDrawable(context: Context) : android.graphics.drawable.Drawable() {
    private val density = context.resources.displayMetrics.density
    override fun getIntrinsicWidth(): Int = (30 * density).toInt()
    override fun getIntrinsicHeight(): Int = (18 * density).toInt()
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.4f * context.resources.displayMetrics.density
    }
    private val lensFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    var active: Boolean = false
        set(value) { field = value; invalidateSelf() }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val h = b.height().toFloat(); val w = b.width().toFloat()
        val r = h * 0.20f; val cy = b.exactCenterY().toFloat(); val cx = b.exactCenterX().toFloat()
        val c1 = cx - r * 0.70f; val c2 = cx + r * 0.70f
        val a = (c2 - c1) / 2f
        val hh = kotlin.math.sqrt((r * r - a * a).coerceAtLeast(0f))
        val angle = Math.toDegrees(kotlin.math.atan2(hh, a).toDouble()).toFloat()
        val color = if (active) Color.BLACK else Color.WHITE
        val lens = android.graphics.Path()
        lens.arcTo(RectF(c1 - r, cy - r, c1 + r, cy + r), -angle, 2 * angle, true)
        lens.arcTo(RectF(c2 - r, cy - r, c2 + r, cy + r), 180f - angle, 2 * angle, true)
        lens.close()
        lensFill.color = color; canvas.drawPath(lens, lensFill)
        stroke.color = color
        canvas.drawCircle(c1, cy, r, stroke); canvas.drawCircle(c2, cy, r, stroke)
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}

/** iOS AutoMix 双环图标，玻璃圆底，激活时白底黑环。 */
internal class AutoMixIconView(context: Context) : View(context) {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * context.resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    var active: Boolean = false
        set(value) { field = value; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val r = width * 0.18f
        val cy = height / 2f
        val left = width * 0.30f
        val right = width * 0.70f
        fill.color = if (active) Color.WHITE else Color.parseColor("#1EFFFFFF")
        canvas.drawCircle(width / 2f, cy, width / 2f, fill)
        stroke.color = if (active) Color.BLACK else Color.WHITE
        canvas.drawCircle(left, cy, r, stroke)
        canvas.drawCircle(right, cy, r, stroke)
    }
}



