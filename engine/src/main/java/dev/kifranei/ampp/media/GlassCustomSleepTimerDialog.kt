package dev.kifranei.ampp.media

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.catalog.components.LiquidButton
import com.tcrrry.lyrics.engine.ui.HorizontalMinuteDialView
import com.tcrrry.playlisttools.HardenedSleepTimer
import kotlin.math.roundToInt

/** Liquid-glass custom minute gauge dial popup; falls back cleanly below API 33 or if PixelCopy fails. */
internal object GlassCustomSleepTimerDialog {
    fun show(
        activity: Activity,
        initialMinutes: Int,
        activeRemainingMs: Long,
        onConfirm: (Int) -> Unit,
        onFallback: () -> Unit,
    ) {
        if (Build.VERSION.SDK_INT < 33 || activity.isFinishing || activity.isDestroyed) {
            onFallback()
            return
        }
        val moduleCtx = runCatching { MediaRuntime.glassContext(activity) }.getOrElse {
            onFallback()
            return
        }
        val dialog = Dialog(activity)
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(true)
        dialog.create()
        val window = dialog.window ?: run { onFallback(); return }
        val decor = activity.window?.decorView ?: run { onFallback(); return }
        if (decor.width <= 0 || decor.height <= 0) {
            onFallback()
            return
        }

        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).roundToInt()

        val initial = HorizontalMinuteDialView.clampMinutes(initialMinutes)
        val minutesState = mutableStateOf(initial)

        val root = FrameLayout(activity).apply { setOnClickListener { dialog.cancel() } }
        val card = FrameLayout(activity).apply {
            isClickable = true
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = dp(32).toFloat()
            }
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(32).toFloat())
                }
            }
            clipToOutline = true
            clipChildren = true
        }
        val glass = PluginGlassHostView(moduleCtx, bleedDp = 0)
        val backdrop = DialogWindowBackdrop(activity, glass, live = true)
        val dialView = HorizontalMinuteDialView(
            context = activity,
            accentColor = Color.WHITE,
            primaryTextColor = Color.WHITE,
            secondaryTextColor = 0xAAFFFFFF.toInt(),
        ).apply {
            setMinutes(initial, notify = false)
            onMinutesChanged = { next -> minutesState.value = next }
        }

        val main = Handler(Looper.getMainLooper())
        var tree: ViewTreeObserver? = null
        val position = IntArray(2)
        val preDraw = ViewTreeObserver.OnPreDrawListener {
            layoutCard(activity, root, card, dialView, activeRemainingMs > 0L, position, ::dp)
            backdrop.refreshPosition()
            true
        }
        lateinit var cleanup: MenuSurfaceCleanup
        val attached = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                tree = root.viewTreeObserver.also { it.addOnPreDrawListener(preDraw) }
            }
            override fun onViewDetachedFromWindow(view: View) {
                cleanup.close(insideWindowDetach = true)
            }
        }
        cleanup = MenuSurfaceCleanup(
            defer = { action -> main.post { action() } },
            stop = {
                root.removeOnAttachStateChangeListener(attached)
                tree?.takeIf { it.isAlive }?.removeOnPreDrawListener(preDraw)
                tree = null
                backdrop.close()
            },
            restore = {
                (root.parent as? ViewGroup)?.removeView(root)
            },
        )
        dialog.setOnDismissListener { cleanup.close() }

        glass.content {
            GlassCustomSleepTimerContent(
                backdrop = backdrop,
                minutesState = minutesState,
                activeRemainingMs = activeRemainingMs,
                onCancel = { dialog.dismiss() },
                onStart = { mins ->
                    onConfirm(mins)
                    dialog.dismiss()
                    Toast.makeText(activity, "睡眠定时已设为 $mins 分钟（强力停止已开启）", Toast.LENGTH_SHORT).show()
                },
            )
        }
        card.addView(glass, FrameLayout.LayoutParams(-1, -1))
        card.addView(dialView, FrameLayout.LayoutParams(-1, dp(92)))
        root.addView(card, FrameLayout.LayoutParams(1, 1))

        val captured = runCatching {
            backdrop.capture { ready ->
                if (cleanup.closed || activity.isFinishing || activity.isDestroyed) {
                    cleanup.close()
                    return@capture
                }
                if (!ready) {
                    cleanup.close()
                    onFallback()
                    return@capture
                }
                runCatching {
                    root.addOnAttachStateChangeListener(attached)
                    window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                    window.setWindowAnimations(0)
                    window.setDimAmount(.18f)
                    window.setContentView(root, ViewGroup.LayoutParams(-1, -1))
                    window.setGravity(Gravity.FILL)
                    window.setLayout(-1, -1)
                    dialog.show()
                }.onFailure {
                    cleanup.close()
                    onFallback()
                }
            }
            true
        }.getOrDefault(false)
        if (!captured) {
            cleanup.close()
            onFallback()
        }
    }

    @android.annotation.TargetApi(33)
    private fun layoutCard(
        activity: Activity,
        root: FrameLayout,
        card: FrameLayout,
        dialView: HorizontalMinuteDialView,
        hasActiveSubtitle: Boolean,
        position: IntArray,
        dp: (Int) -> Int,
    ) {
        if (root.width <= 0 || root.height <= 0) return
        root.getLocationOnScreen(position)
        val insets = root.rootWindowInsets?.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        val top = ((insets?.top ?: 0) - position[1]).coerceAtLeast(0)
        val bottom = insets?.bottom ?: 0
        val width = minOf(dp(356), root.width - dp(32)).coerceAtLeast(1)
        val maxHeight = (root.height - top - bottom - dp(48)).coerceAtLeast(1)
        val height = minOf(dp(if (hasActiveSubtitle) 318 else 296), maxHeight)
        val left = (root.width - width) / 2
        val y = top + (root.height - top - bottom - height) / 2
        val params = card.layoutParams as FrameLayout.LayoutParams
        if (params.width != width || params.height != height || params.leftMargin != left || params.topMargin != y) {
            params.width = width
            params.height = height
            params.leftMargin = left
            params.topMargin = y
            card.layoutParams = params
        }

        // Align the Android dial view cleanly inside the reserved slot above the bottom action row.
        val dialH = dp(92)
        val dialTop = (height - dp(20 + 48 + 12) - dialH).coerceAtLeast(dp(100))
        val dialSideMargin = dp(12)
        val dialW = (width - dialSideMargin * 2).coerceAtLeast(1)
        val dialParams = dialView.layoutParams as FrameLayout.LayoutParams
        if (dialParams.width != dialW || dialParams.height != dialH ||
            dialParams.leftMargin != dialSideMargin || dialParams.topMargin != dialTop
        ) {
            dialParams.width = dialW
            dialParams.height = dialH
            dialParams.leftMargin = dialSideMargin
            dialParams.topMargin = dialTop
            dialView.layoutParams = dialParams
        }

        card.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        card.layout(left, y, left + width, y + height)
    }
}

@Composable
private fun GlassCustomSleepTimerContent(
    backdrop: Backdrop,
    minutesState: MutableState<Int>,
    activeRemainingMs: Long,
    onCancel: () -> Unit,
    onStart: (Int) -> Unit,
) {
    val minutes = minutesState.value
    val white = androidx.compose.ui.graphics.Color.White
    val subtleSurface = white.copy(alpha = .15f)
    val detail = HorizontalMinuteDialView.formatDurationDetail(minutes).ifEmpty { "左右滑动刻度盘调整时长" }

    GlassPlayerDialogMaterial(backdrop) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                BasicText(
                    "自定义睡眠定时",
                    Modifier.fillMaxWidth(),
                    TextStyle(
                        color = white,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    ),
                )
                if (activeRemainingMs > 0L) {
                    Spacer(Modifier.height(4.dp))
                    BasicText(
                        "当前剩余 ${HardenedSleepTimer.formatRemaining(activeRemainingMs)}",
                        Modifier.fillMaxWidth(),
                        TextStyle(
                            color = white.copy(alpha = .74f),
                            fontSize = 12.5.sp,
                            textAlign = TextAlign.Center,
                        ),
                    )
                }
            }

            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                BasicText(
                    "$minutes 分钟",
                    Modifier.fillMaxWidth(),
                    TextStyle(
                        color = white,
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    ),
                )
                Spacer(Modifier.height(2.dp))
                BasicText(
                    detail,
                    Modifier.fillMaxWidth(),
                    TextStyle(
                        color = white.copy(alpha = .68f),
                        fontSize = 12.5.sp,
                        textAlign = TextAlign.Center,
                    ),
                )
            }

            Spacer(Modifier.height(92.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LiquidButton(
                    onCancel,
                    backdrop,
                    Modifier.weight(1f).height(48.dp),
                    surfaceColor = subtleSurface,
                ) {
                    BasicText(
                        "取消",
                        style = TextStyle(color = white, fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
                    )
                }
                LiquidButton(
                    { onStart(minutes) },
                    backdrop,
                    Modifier.weight(1f).height(48.dp),
                    surfaceColor = white.copy(alpha = .95f),
                ) {
                    BasicText(
                        "开始定时",
                        style = TextStyle(
                            color = androidx.compose.ui.graphics.Color.Black,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                }
            }
        }
    }
}
