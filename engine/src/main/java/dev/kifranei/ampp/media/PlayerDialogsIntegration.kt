package dev.kifranei.ampp.media

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.content.res.ColorStateList
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.ImageView
import java.util.IdentityHashMap
import kotlin.math.roundToInt

/** Narrowly replace native library confirmations and the dedicated sleep-timer / sort fragment. */
internal class PlayerDialogsIntegration(
    private val build: TargetBuild,
    private val confirmation: Boolean,
    private val sleep: Boolean,
    private val playlistSort: Boolean = sleep,
) {
    fun install(): TargetCapabilityInstall {
        if (Build.VERSION.SDK_INT < 33) return TargetCapabilityInstall.Unsupported("完整玻璃折射需要 Android 13+")
        val names = PluginProfiles.find(build.packageName, build.versionName, build.versionCode)?.document?.optJSONObject("playerDialogs")
            ?: return TargetCapabilityInstall.Unsupported("No verified player-dialog contract for ${build.displayName}")
        val scope = PluginScope()
        val sleepType = if (sleep) PluginProfiles.type(names.getString("sleepFragmentClass")) else null
        val sortType = if (playlistSort) runCatching { PluginProfiles.type("com.apple.android.music.common.actionsheet.M") }.getOrNull() else null
        val sleepOwners = IdentityHashMap<Any, Dialog>()
        val confirmationOwners = IdentityHashMap<Any, Dialog>()
        val surfaces = IdentityHashMap<Dialog, Surface>()
        val bypass = ThreadLocal<Dialog?>()
        fun nativeShow(dialog: Dialog) {
            bypass.set(dialog)
            try { dialog.show() } finally { bypass.remove() }
        }
        try {
            if (confirmation) check(MediaRuntime.observeMethod(PluginProfiles.method("confirmation-dialog-create"), object : PluginMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable == null) (param.result as? Dialog)?.let { confirmationOwners[checkNotNull(param.thisObject)] = it }
                }
            }, scope))
            if (sleep || playlistSort) {
                check(MediaRuntime.observeMethod(PluginProfiles.method("more-dialog-create"), object : PluginMethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val owner = param.thisObject ?: return
                        if (param.throwable == null && ((sleep && sleepType?.isInstance(owner) == true) || (playlistSort && sortType?.isInstance(owner) == true))) {
                            (param.result as? Dialog)?.let { sleepOwners[owner] = it }
                        }
                    }
                }, scope))
                check(MediaRuntime.observeMethod(PluginProfiles.method("more-dialog-destroy"), object : PluginMethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        sleepOwners.remove(param.thisObject)?.let { surfaces.remove(it)?.close() }
                    }
                }, scope))
            }
            check(MediaRuntime.hookMethod(Dialog::class.java.getDeclaredMethod("show"), object : PluginMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val dialog = param.thisObject as? Dialog ?: return
                    if (bypass.get() === dialog || dialog.isShowing) return
                    if (surfaces.containsKey(dialog)) { param.result = null; return }
                    val isSleep = sleepOwners.containsValue(dialog)
                    if (!isSleep && !confirmationOwners.containsValue(dialog)) return
                    val activity = activity(dialog.context)?.takeIf { it.packageName == build.packageName && !it.isFinishing && !it.isDestroyed } ?: return
                    dialog.create()
                    val list = if (isSleep) dialog.findViewById<ViewGroup>(activity.resources.getIdentifier(names.getString("sleepListId"), "id", build.packageName)) ?: return else null
                    fun id(key: String) = activity.resources.getIdentifier(names.getString(key), "id", build.packageName)
                    val message = if (!isSleep) dialog.findViewById<TextView>(id("confirmationMessageId"))?.text?.toString() ?: return else ""
                    val deleteLabel = if (!isSleep) activity.getString(activity.resources.getIdentifier(names.getString("deleteLabel"), "string", build.packageName)) else ""
                    val buttons = if (!isSleep) dialog.findViewById<ViewGroup>(id("confirmationButtonsId"))?.let { container ->
                        (0 until container.childCount).mapNotNull { (container.getChildAt(it) as? TextView)
                            ?.takeIf { button -> button.visibility == View.VISIBLE && button.text.isNotBlank() && button.hasOnClickListeners() } }
                    } ?: return else emptyList()
                    if (!isSleep) {
                        val allowed = names.getJSONArray("confirmationMessages").let { values ->
                            (0 until values.length()).mapNotNull { index ->
                                activity.resources.getIdentifier(values.getString(index), "string", build.packageName).takeIf { it != 0 }?.let(activity::getString)
                            }.toSet()
                        }
                        if (message !in allowed || buttons.none { it.text.toString() == deleteLabel } || buttons.size < 2) return
                    }
                    val title = if (!isSleep) dialog.findViewById<TextView>(id("confirmationTitleId"))?.text?.toString().orEmpty() else ""
                    val actions = buttons.sortedBy { it.text.toString() == deleteLabel }.map { button ->
                        GlassDialogAction(button.text.toString(), button.text.toString() == deleteLabel) { button.performClick() }
                    }
                    val surface = runCatching { Surface(activity, dialog, list, title, message, actions,
                        if (isSleep) id("sleepTitleId") else 0) }
                        .onFailure { MediaRuntime.log("player_dialog_glass: preparation failed", it) }.getOrNull() ?: return
                    surfaces[dialog] = surface
                    try {
                        surface.capture { ready ->
                            if (surface.closed || !scope.isActive || activity.isFinishing || activity.isDestroyed) {
                                surface.close(); surfaces.remove(dialog); return@capture
                            }
                            if (!ready) { surface.close(); surfaces.remove(dialog); nativeShow(dialog); return@capture }
                            runCatching {
                                surface.mount(); nativeShow(dialog)
                                MediaRuntime.log("player_dialog_glass: ${if (isSleep) "sleep timer" else "delete confirmation"} first window shown with glass content")
                            }.onFailure {
                                surface.close(); surfaces.remove(dialog)
                                MediaRuntime.log("player_dialog_glass: native fallback", it); nativeShow(dialog)
                            }
                        }
                        param.result = null
                    } catch (error: Throwable) {
                        surface.close(); surfaces.remove(dialog)
                        MediaRuntime.log("player_dialog_glass: capture failed", error)
                    }
                }
            }, scope))
            check(MediaRuntime.observeMethod(Dialog::class.java.getDeclaredMethod("dismiss"), object : PluginMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    surfaces.remove(param.thisObject)?.close()
                    confirmationOwners.entries.removeAll { it.value === param.thisObject }
                    sleepOwners.entries.removeAll { it.value === param.thisObject }
                }
            }, scope))
            scope.onClose {
                surfaces.values.toList().forEach(Surface::close)
                surfaces.clear(); sleepOwners.clear(); confirmationOwners.clear(); bypass.remove()
            }
            scope.activate()
            val label = when {
                sleep && playlistSort -> "sleep-timer & playlist-sort options"
                sleep -> "sleep-timer options"
                playlistSort -> "playlist-sort options"
                confirmation -> "library confirmation actions"
                else -> "dialogs"
            }
            return TargetCapabilityInstall.Active("Native $label in glass popup installed")
        } catch (error: Throwable) { scope.close(); throw error }
    }

    @android.annotation.TargetApi(33)
    private class Surface(private val activity: Activity, private val dialog: Dialog, private val list: ViewGroup?,
        private val title: String, private val message: String, private val actions: List<GlassDialogAction>, private val sleepTitleId: Int) : AutoCloseable {
        private val main = Handler(Looper.getMainLooper())
        private val window = checkNotNull(dialog.window)
        private val content = checkNotNull(window.decorView.findViewById<ViewGroup>(android.R.id.content))
        private val children = (0 until content.childCount).map { content.getChildAt(it) to content.getChildAt(it).visibility }
        private val attributes = WindowManager.LayoutParams().apply { copyFrom(window.attributes) }
        private val background = window.decorView.background
        private val listParent = list?.parent as? ViewGroup
        private val listIndex = listParent?.indexOfChild(list) ?: -1
        private val listParams = list?.layoutParams
        private val listBackground = list?.background
        private val styled = IdentityHashMap<View, () -> Unit>()
        private val backgrounds = IdentityHashMap<View, android.graphics.drawable.Drawable?>()
        private val root = FrameLayout(activity).apply { setOnClickListener { dialog.cancel() } }
        private val card = FrameLayout(activity).apply {
            isClickable = true
            background = GradientDrawable().apply { setColor(Color.TRANSPARENT); cornerRadius = dp(32).toFloat() }
            // Explicit round-rect outline (same idea as the More menu card) so glass / native list
            // children cannot paint square corners outside the 32dp popup radius.
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(32).toFloat())
                }
            }
            clipToOutline = true
            clipChildren = true
        }
        private val glass = PluginGlassHostView(MediaRuntime.glassContext(activity), bleedDp = 0)
        // Live PixelCopy like More menu / audio-quality: blur tracks the player behind the popup.
        private val backdrop = DialogWindowBackdrop(activity, glass, live = true)
        private var mounted = false
        private var tree: ViewTreeObserver? = null
        private val position = IntArray(2)
        private val preDraw = ViewTreeObserver.OnPreDrawListener { layout(); backdrop.refreshPosition(); true }
        val closed: Boolean get() = cleanup.closed
        private val attached = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) { tree = root.viewTreeObserver.also { it.addOnPreDrawListener(preDraw) } }
            override fun onViewDetachedFromWindow(view: View) { cleanup.close(insideWindowDetach = true) }
        }
        private val cleanup: MenuSurfaceCleanup = MenuSurfaceCleanup(
            defer = { action -> main.post { action() } },
            stop = {
                root.removeOnAttachStateChangeListener(attached)
                tree?.takeIf { it.isAlive }?.removeOnPreDrawListener(preDraw); tree = null
                backdrop.close()
            },
            restore = {
                if (mounted) {
                    (root.parent as? ViewGroup)?.removeView(root)
                    list?.let {
                        (it.parent as? ViewGroup)?.removeView(it)
                        styled.values.toList().forEach { restore -> restore() }; styled.clear()
                        backgrounds.forEach { (view, previous) -> view.background = previous }; backgrounds.clear()
                        it.background = listBackground
                        listParent?.addView(it, listIndex.coerceAtMost(listParent.childCount), listParams)
                    }
                    children.forEach { (child, visible) -> child.visibility = visible }
                }
                window.setBackgroundDrawable(background); window.attributes = attributes
            },
        )
        init {
            glass.content {
                if (list == null) GlassDeleteConfirmation(backdrop, title, message, actions)
                else GlassPlayerDialogMaterial(backdrop)
            }
            card.addView(glass, FrameLayout.LayoutParams(-1, -1))
            root.addView(card, FrameLayout.LayoutParams(1, 1))
        }
        fun capture(ready: (Boolean) -> Unit) = backdrop.capture(ready)
        fun mount() {
            check(!closed)
            mounted = true
            list?.let {
                listParent?.removeView(it)
                it.setBackgroundColor(Color.TRANSPARENT)
                card.addView(it, FrameLayout.LayoutParams(-1, -1).apply { setMargins(dp(16), dp(12), dp(16), dp(12)) })
            }
            children.forEach { (child, _) -> child.visibility = View.INVISIBLE }
            root.addOnAttachStateChangeListener(attached)
            content.addView(root, ViewGroup.LayoutParams(-1, -1))
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.setWindowAnimations(0); window.setDimAmount(.18f)
            window.setGravity(Gravity.FILL); window.setLayout(-1, -1)
        }
        private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).roundToInt()
        private fun layout() {
            if (closed || root.width <= 0 || root.height <= 0) return
            root.getLocationOnScreen(position)
            val insets = root.rootWindowInsets?.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val top = ((insets?.top ?: 0) - position[1]).coerceAtLeast(0)
            val bottom = insets?.bottom ?: 0
            val width = minOf(dp(360), root.width - dp(32))
            val maxHeight = (root.height - top - bottom - dp(48)).coerceAtLeast(1)
            val height = if (list != null) {
                list.measure(View.MeasureSpec.makeMeasureSpec((width - dp(32)).coerceAtLeast(1), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec((maxHeight - dp(24)).coerceAtLeast(1), View.MeasureSpec.AT_MOST))
                (list.measuredHeight + dp(24)).coerceIn(dp(160).coerceAtMost(maxHeight), maxHeight)
            } else {
                val paint = TextPaint().apply { textSize = 18 * activity.resources.displayMetrics.scaledDensity }
                fun textHeight(text: String) = StaticLayout.Builder.obtain(text, 0, text.length, paint, (width - dp(44)).coerceAtLeast(1))
                    .setAlignment(Layout.Alignment.ALIGN_CENTER).build().height
                (textHeight(message) + (if (title.isBlank()) 0 else textHeight(title) + dp(12)) + dp(68) +
                    dp(if (actions.size <= 2) 48 else actions.size * 56)).coerceIn(dp(190).coerceAtMost(maxHeight), maxHeight)
            }
            val left = (root.width - width) / 2
            val y = top + (root.height - top - bottom - height) / 2
            val params = card.layoutParams as FrameLayout.LayoutParams
            if (params.width != width || params.height != height || params.leftMargin != left || params.topMargin != y) {
                params.width = width; params.height = height; params.leftMargin = left; params.topMargin = y
                card.layoutParams = params
            }
            card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            card.layout(left, y, left + width, y + height)
            list?.let { native ->
                for (index in 0 until native.childCount) {
                    val row = native.getChildAt(index)
                    if (!backgrounds.containsKey(row)) backgrounds[row] = row.background
                    if (row.background != null) row.background = null
                    styleChildren(row)
                }
            }
        }
        private fun styleChildren(view: View) {
            if (view is TextView || view is ImageView) {
                if (!backgrounds.containsKey(view)) backgrounds[view] = view.background
                if (view.background != null) view.background = null
            }
            if (!styled.containsKey(view)) when (view) {
                is TextView -> {
                    val color = view.textColors; val gravity = view.gravity; val width = view.layoutParams.width
                    styled[view] = { view.setTextColor(color); view.gravity = gravity; view.layoutParams.width = width }
                    view.setTextColor(Color.WHITE)
                    if (view.id == sleepTitleId) {
                        view.gravity = Gravity.CENTER
                        view.layoutParams = view.layoutParams.apply { this.width = ViewGroup.LayoutParams.MATCH_PARENT }
                    }
                }
                is ImageView -> {
                    val tint = view.imageTintList
                    styled[view] = { view.imageTintList = tint }
                    view.imageTintList = ColorStateList.valueOf(Color.WHITE)
                }
                else -> if (view.background is ColorDrawable) {
                    val background = view.background
                    styled[view] = { view.background = background }
                    view.background = ColorDrawable(Color.argb(40, 255, 255, 255))
                }
            }
            if (view is ViewGroup) for (index in 0 until view.childCount) styleChildren(view.getChildAt(index))
        }
        override fun close() = cleanup.close()
    }

    private fun activity(context: Context): Activity? {
        var current = context
        repeat(16) {
            if (current is Activity) return current
            val next = (current as? ContextWrapper)?.baseContext ?: return null
            if (next === current) return null
            current = next
        }
        return null
    }
}
