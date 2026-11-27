package dev.kifranei.ampp.media

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

/** Android controls keep native accessibility and scrolling above the Compose glass. */
internal class IosPlayerMoreMenuView(
    private val activity: Activity,
    private val dark: Boolean,
    private val isPlaylist: Boolean = false,
    private val execute: (NativePlayerMenuEntry, View) -> Unit,
) : ScrollView(activity) {
    private val density = resources.displayMetrics.density
    private val ink = if (dark) Color.WHITE else Color.rgb(28, 28, 30)
    private val content = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(10), dp(8), dp(10), dp(10))
    }
    var signature: List<List<Any?>> = emptyList()
        private set

    init {
        isFillViewport = false
        isVerticalScrollBarEnabled = true
        isScrollbarFadingEnabled = false
        scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
        overScrollMode = View.OVER_SCROLL_NEVER
        if (android.os.Build.VERSION.SDK_INT >= 29) verticalScrollbarThumbDrawable = GradientDrawable().apply {
            setColor(if (dark) 0x66FFFFFF else 0x66000000)
            cornerRadius = dp(2).toFloat()
            setSize(dp(3), dp(24))
        }
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun dp(value: Int) = (value * density).roundToInt()
    fun update(entries: List<NativePlayerMenuEntry>, headerTitle: String? = null, headerSubtitle: String? = null) {
        val next = entries.map { it.signature } + listOf(listOf(headerTitle, headerSubtitle))
        if (next == signature) return
        signature = next
        val oldScroll = scrollY
        content.removeAllViews()
        var hasContentAbove = false
        if (!headerTitle.isNullOrBlank()) {
            val header = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(8), dp(16), dp(6))
                addView(label(headerTitle, 16f).apply {
                    typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                })
                headerSubtitle?.takeIf { it.isNotBlank() }?.let { sub ->
                    addView(label(sub, 12.5f).apply { alpha = .56f },
                        LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
                }
            }
            content.addView(header)
            hasContentAbove = true
        }
        val groups = PlayerMenuLayout.groups(entries.map { it.action }, isPlaylist = isPlaylist)
        val lookup = entries.associateBy { it.action }
        if (groups.shortcuts.isNotEmpty()) {
            if (hasContentAbove) divider()
            val shortcuts = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            groups.shortcuts.forEachIndexed { index, action ->
                val entry = action?.let(lookup::get)
                val column = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    val nativeTitle = entry?.title
                    val shortTitle = when {
                        index == 2 -> resourceLabel("share", "分享", "Share")
                        entry?.action?.key == "ADD_TO_LIBRARY" -> resourceLabel("add", "添加", "Add")
                        else -> nativeTitle ?: when (index) {
                            0 -> resourceLabel("download", "下载", "Download")
                            else -> resourceLabel("favorite", "喜爱", "Favorite")
                        }
                    }
                    val drawable = when {
                        index == 2 -> IosMenuSymbol("share", ink)
                        index == 0 && entry?.action?.key in setOf("DOWNLOAD", "REMOVE_DOWNLOAD") -> IosMenuSymbol("download", ink)
                        else -> entry?.icon
                    }
                    addView(icon(drawable), LinearLayout.LayoutParams(dp(24), dp(24)))
                    addView(label(shortTitle, 13.5f), LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(7) })
                    contentDescription = entry?.title ?: shortTitle
                    isEnabled = entry?.enabled == true
                    alpha = if (isEnabled) 1f else .35f
                    minimumHeight = dp(76)
                    if (entry != null) setOnClickListener { execute(entry, it) }
                    background = highlight()
                }
                shortcuts.addView(column, LinearLayout.LayoutParams(0, dp(76), 1f))
            }
            content.addView(shortcuts)
            hasContentAbove = true
        }
        groups.sections.forEach { section ->
            if (hasContentAbove) divider()
            for (action in section) content.addView(row(checkNotNull(lookup[action])))
            hasContentAbove = true
        }
        post { scrollTo(0, oldScroll) }
    }

    private fun row(entry: NativePlayerMenuEntry): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(12), dp(14), dp(12))
        minimumHeight = dp(if (entry.subtitle.isNullOrBlank()) 48 else 64)
        val drawable = entry.icon ?: activity.resources.getIdentifier(
            if (entry.action.key == "OPEN_ALBUM") "actionsheet_musicbadgeplus" else "actionsheet_show_artist",
            "drawable", activity.packageName).takeIf { it != 0 }?.let(activity::getDrawable)
        addView(icon(drawable), LinearLayout.LayoutParams(dp(22), dp(24)).apply { marginEnd = dp(16) })
        val text = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(entry.title, 17f))
            entry.subtitle?.takeIf(String::isNotBlank)?.let {
                addView(label(it, 12.5f).apply { alpha = .48f }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
            }
        }
        addView(text, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        if (entry.checked) addView(label("✓", 16f), LinearLayout.LayoutParams(dp(20), LayoutParams.WRAP_CONTENT))
        isEnabled = entry.enabled
        contentDescription = listOfNotNull(entry.title, entry.subtitle).joinToString(", ")
        isSelected = entry.checked
        alpha = if (entry.enabled) 1f else .35f
        background = highlight()
        setOnClickListener { execute(entry, it) }
    }

    private fun label(text: String, size: Float) = TextView(activity).apply {
        this.text = text
        textSize = size
        setTextColor(ink)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
        textDirection = View.TEXT_DIRECTION_LOCALE
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun icon(drawable: Drawable?) = ImageView(activity).apply {
        setImageDrawable(drawable?.constantState?.newDrawable(resources)?.mutate() ?: drawable)
        imageTintList = ColorStateList.valueOf(ink)
        scaleType = ImageView.ScaleType.FIT_CENTER
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun divider() {
        content.addView(View(activity).apply { setBackgroundColor(if (dark) 0x18FFFFFF else 0x18000000) },
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, maxOf(1, dp(1))).apply {
                setMargins(dp(14), dp(10), dp(14), dp(10))
            })
    }

    private fun highlight(): Drawable = RippleDrawable(ColorStateList.valueOf(if (dark) 0x20FFFFFF else 0x18000000), null,
        GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(12).toFloat() })

    private fun resourceLabel(name: String, chinese: String, english: String): String {
        val id = resources.getIdentifier(name, "string", activity.packageName)
        return if (id != 0) activity.getString(id) else if (resources.configuration.locales[0].language == "zh") chinese else english
    }
}

/** Small code-drawn symbols match the reference without shipping SF Symbols assets. */
private class IosMenuSymbol(private val kind: String, private val color: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = this@IosMenuSymbol.color; strokeWidth = 1.8f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    override fun draw(canvas: android.graphics.Canvas) {
        val save = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
        if (kind == "download") {
            paint.style = Paint.Style.FILL
            canvas.drawCircle(12f, 12f, 11f, paint)
            paint.color = if (color == Color.WHITE) 0xFF555555.toInt() else Color.WHITE
            paint.style = Paint.Style.STROKE
            canvas.drawPath(Path().apply { moveTo(12f, 6f); lineTo(12f, 17f); moveTo(8f, 13f); lineTo(12f, 17f); lineTo(16f, 13f) }, paint)
            paint.color = color
        } else {
            paint.style = Paint.Style.STROKE
            canvas.drawPath(Path().apply {
                moveTo(7f, 9f); lineTo(5f, 9f); lineTo(5f, 21f); lineTo(19f, 21f); lineTo(19f, 9f); lineTo(17f, 9f)
                moveTo(12f, 15f); lineTo(12f, 2f); moveTo(8f, 6f); lineTo(12f, 2f); lineTo(16f, 6f)
            }, paint)
        }
        canvas.restoreToCount(save)
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(filter: android.graphics.ColorFilter?) { paint.colorFilter = filter }
    @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
