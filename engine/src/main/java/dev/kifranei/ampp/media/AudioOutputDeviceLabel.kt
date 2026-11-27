package dev.kifranei.ampp.media

import android.content.res.ColorStateList
import android.graphics.Color
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView
import kotlin.math.roundToInt

/** The caption sits under the route icon; taps still use the system picker. */
internal class AudioOutputDeviceLabel(private val button: View, playerRootId: Int,
    private val updateIcon: (OutputDeviceIcon) -> Unit, private val updatePresentation: () -> Unit,
    private val onDetached: () -> Unit) : AutoCloseable {
    // Cover controls can be clipped by native artwork/layout containers. Use the shared
    // player root and follow the source button's position and ancestor opacity instead.
    private val parent = generateSequence(button.parent as? View) { it.parent as? View }
        .filterIsInstance<ViewGroup>().firstOrNull { it.id == playerRootId } ?: (button.parent as ViewGroup)
    private val audio = button.context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val originalDescription = button.contentDescription
    private var closed = false
    private var callbackRegistered = false
    private var name: String? = null
    val description get() = name?.let { "投放 · 音频输出 · $it" } ?: "投放 · 音频输出"
    private val label = TextView(button.context).apply {
        textSize = 12f; typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        setTextColor(ColorStateList.valueOf(Color.argb(180, 255, 255, 255)))
        gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false; visibility = View.INVISIBLE
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        setOnClickListener { button.performClick() }
    }
    private val parentPosition = IntArray(2)
    private val buttonPosition = IntArray(2)
    private fun id(name: String) = button.resources.getIdentifier(name, "id", button.context.packageName)
    private val preDraw = ViewTreeObserver.OnPreDrawListener { updatePresentation(); layout(); true }
    private val tree = parent.viewTreeObserver
    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) { scheduleRefresh() }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) { scheduleRefresh() }
    }
    private val routeRefresh = Runnable { refresh() }
    private val output = SystemAudioOutput(button.context, audio, ::scheduleRefresh)
    private fun scheduleRefresh() {
        if (closed) return
        main.removeCallbacks(routeRefresh)
        main.post(routeRefresh)
        // OEM picker notifications can arrive before the audio policy update finishes.
        main.postDelayed(routeRefresh, 150)
        main.postDelayed(routeRefresh, 500)
    }
    private val attached = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = Unit
        override fun onViewDetachedFromWindow(view: View) { close(); onDetached() }
    }
    private val poll = object : Runnable {
        override fun run() {
            if (closed) return
            if (parent.isShown) refresh()
            main.postDelayed(this, 1000)
        }
    }
    init {
        try {
            parent.addView(label, ViewGroup.LayoutParams(1, 1))
            tree.addOnPreDrawListener(preDraw)
            button.addOnAttachStateChangeListener(attached)
            audio.registerAudioDeviceCallback(callback, main); callbackRegistered = true
            refresh(); main.postDelayed(poll, 1000)
        } catch (error: Throwable) { close(); throw error }
    }
    private fun refresh() {
        if (closed) return
        updatePresentation()
        val state = output.current()
        val current = state.name
        if (current != name) {
            name = current; label.text = current.orEmpty()
            if (current == null) label.visibility = View.INVISIBLE
        }
        updateIcon(state.icon)
        button.contentDescription = description
    }
    private fun layout() {
        if (closed) return
        if (name == null || !button.isShown || button.width <= 0 || button.height <= 0) { label.visibility = View.INVISIBLE; return }
        val density = button.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).roundToInt()
        parent.getLocationInWindow(parentPosition)
        button.getLocationInWindow(buttonPosition)
        val height = maxOf(dp(18), kotlin.math.ceil(label.paint.fontSpacing.toDouble()).toInt())
        val buttonLeft = buttonPosition[0] - parentPosition[0]
        val buttonTop = buttonPosition[1] - parentPosition[1]
        val labelWidth = minOf(dp(120), parent.width)
        val placement = AudioOutputLabelPlacement((buttonLeft + button.width / 2 - labelWidth / 2).coerceIn(0, (parent.width - labelWidth).coerceAtLeast(0)),
            (buttonTop + button.height - dp(3)).coerceAtMost(parent.height - height).coerceAtLeast(0), labelWidth, height)
        val (left, top, width) = placement
        label.gravity = Gravity.CENTER
        val params = label.layoutParams
        if (params.width != width || params.height != height) { params.width = width; params.height = height; label.layoutParams = params }
        label.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        label.layout(left, top, left + width, top + height)
        var ancestor: View? = button
        var alpha = 1f
        while (ancestor != null && ancestor !== parent) { alpha *= ancestor.alpha; ancestor = ancestor.parent as? View }
        label.alpha = alpha; label.visibility = View.VISIBLE
    }
    override fun close() {
        if (closed) return
        closed = true; main.removeCallbacks(poll); main.removeCallbacks(routeRefresh)
        output.close()
        button.removeOnAttachStateChangeListener(attached)
        if (tree.isAlive) tree.removeOnPreDrawListener(preDraw)
        if (callbackRegistered) runCatching { audio.unregisterAudioDeviceCallback(callback) }
        button.contentDescription = originalDescription
        // Never mutate the parent during Android's recursive detach dispatch.
        main.post { (label.parent as? ViewGroup)?.removeView(label) }
    }
}
