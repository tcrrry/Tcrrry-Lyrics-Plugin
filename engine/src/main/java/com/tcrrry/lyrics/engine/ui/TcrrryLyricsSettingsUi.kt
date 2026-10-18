package com.tcrrry.lyrics.engine.ui
import com.tcrrry.lyrics.plugin.PluginStorage.pluginCacheDir
import com.tcrrry.lyrics.plugin.PluginStorage.pluginPreferences

import android.app.Activity
import android.content.Intent
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.tcrrry.lyrics.engine.CurrentSongDetails
import com.tcrrry.lyrics.engine.model.ModuleSettings
import com.tcrrry.lyrics.engine.hook.LyricsSourceMenuPolicy
import com.tcrrry.lyrics.engine.hook.CurrentLyricsSourceStatus
import com.tcrrry.lyrics.engine.hook.TcrrryLyricsHistory
import com.tcrrry.lyrics.sources.SecretStorage
import com.tcrrry.lyrics.sources.SupplementTranslation
import com.tcrrry.lyrics.sources.TranslationApiProfiles
import android.text.InputType
import java.util.Locale

/** Independent lyrics controls with the shared host-aware day/night palette. */
internal object TcrrryLyricsSettingsUi {
    private val SOURCES = listOf<String?>(null) + com.tcrrry.lyrics.engine.hook.LyricsSourceMenuPolicy.sources
    private const val GUIDE_PREFS = "lyrics_operation_guide"
    private var activeGuide: android.app.AlertDialog? = null

    fun render(activity: Activity, parent: LinearLayout, song: CurrentSongDetails?, refreshPage: () -> Unit,
        settings: ModuleSettings, onSettingsChanged: (ModuleSettings) -> Unit,
        readSettings: () -> ModuleSettings = { settings }) {
        val id = song?.appleMusicId ?: 0L
        parent.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        parent.setPadding(dp(activity, 18), dp(activity, 16), dp(activity, 18), dp(activity, 28))

        parent.addView(card(activity).apply {
            addView(title(activity, song?.title ?: "暂无播放歌曲", 19f))
            addView(label(activity, song?.artist.orEmpty(), 13f, SettingsUiTheme.colors(activity).secondary))
            addView(sourceStatusLabel(activity, { CurrentLyricsSourceStatus.description(activity, id) }) {
                if (id > 0L) showSourceSettings(activity, id, song, refreshPage)
            }, fullMargin(activity, 12))
            addView(action(activity, "查看当前匹配信息") {
                showMatchingInformation(activity, id)
            }, fullMargin(activity, 10))
        }, fullMargin(activity, 12))
        parent.addView(title(activity, "设置", 14f), fullMargin(activity, 24))
        parent.addView(menuRow(activity, "歌词偏好", "匹配优先级与发音排版") {
            showLyricsPreferences(activity, id, refreshPage)
        }, fullMargin(activity, 8))
        parent.addView(menuRow(activity, "翻译与注音", "补充译文、离线注音与语言包") {
            showLanguageSettings(activity, id, refreshPage)
        }, fullMargin(activity, 12))
        parent.addView(menuRow(activity, "歌词效果", "辉光灵敏度、触发位置与短单元平滑") {
            bubbleDialog(activity, glowSettingsCard(activity, readSettings(), onSettingsChanged, refreshPage, id)).show()
        }, fullMargin(activity, 12))
        parent.addView(menuRow(activity, "更多工具", "缓存与系统链接") {
            showMoreSettings(activity, id)
        }, fullMargin(activity, 12))
        parent.addView(menuRow(activity, "操作说明", "播放页歌词源按钮的三种操作") {
            showOperationGuide(activity, required = false)
        }, fullMargin(activity, 12))

        prepareRenderPresentation(parent)
        maybeShowFirstRunGuide(activity, parent)
    }

    private fun showSourceSettings(activity: Activity, id: Long, song: CurrentSongDetails?, refreshPage: () -> Unit) {
        val body = card(activity)
        val dialog = bubbleDialog(activity, body)
        lateinit var redraw: () -> Unit
        val changed = { refreshPage(); redraw() }
        redraw = {
            val selected = CurrentLyricsSourceStatus.selectedSource(activity, id)
            val provider = LyricsSourceMenuPolicy.provider(CurrentLyricsSourceStatus.appliedSource(activity, id)) ?: selected
            body.removeAllViews()
            body.addView(title(activity, "当前歌曲歌词源", 20f))
            body.addView(label(activity, "${song?.title.orEmpty()} · ${song?.artist.orEmpty()}", 13f,
                SettingsUiTheme.colors(activity).secondary), fullMargin(activity, 5))
            body.addView(sourceStatusLabel(activity, { CurrentLyricsSourceStatus.description(activity, id) }, expanded = true), fullMargin(activity, 14))
            body.addView(menuRow(activity, "选择歌词源", selected ?: "自动匹配") {
                showSourcePicker(activity, id, selected, changed)
            }, fullMargin(activity, 16))
            val nextSource = action(activity, "下一个来源") {
                val next = CurrentLyricsSourceStatus.beginSourceCycle(activity, id)
                if (next != null) {
                    val started = CurrentLyricsSourceStatus.refresh(id)
                    if (!started) CurrentLyricsSourceStatus.cancelSourceCycle(id)
                    toast(activity, if (started) "正在尝试$next" else "请重新打开歌词页后生效")
                }
                changed()
            }
            val nextVersion = action(activity, "同源下一版") {
                if (CurrentLyricsSourceStatus.excludeCurrentRecord(activity, id)) {
                    toast(activity, if (CurrentLyricsSourceStatus.refresh(id)) "正在寻找下一个匹配版本" else "请重新打开歌词页后生效")
                } else toast(activity, "请先装入选中的第三方歌词，再换匹配版本")
                changed()
            }
            body.addView(pairedActions(activity, nextSource, nextVersion), fullMargin(activity, 10))
            val best = action(activity, "当前源最佳") {
                CurrentLyricsSourceStatus.retryCurrentSource(activity, id)
                toast(activity, if (CurrentLyricsSourceStatus.refresh(id)) "正在选用当前来源最佳记录" else "请重新打开歌词页后生效")
                changed()
            }.apply { isEnabled = selected != null || provider != null; alpha = if (isEnabled) 1f else .45f }
            val automatic = action(activity, "自动选择源") {
                CurrentLyricsSourceStatus.resetMatching(activity, id)
                CurrentLyricsSourceStatus.refresh(id)
                changed()
            }
            body.addView(pairedActions(activity, best, automatic), fullMargin(activity, 10))
            body.addView(menuRow(activity, "匹配记录与预览", "查看历史结果，或手动选用歌词") {
                showHistory(activity, id, song, changed)
            }, fullMargin(activity, 12))
            val offsetValue = label(activity, formatOffset(provider?.let { CurrentLyricsSourceStatus.offsetMs(activity, id, it) } ?: 0),
                14f, SettingsUiTheme.colors(activity).text)
            body.addView(LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(title(activity, "歌词偏移", 16f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(offsetValue)
            }, fullMargin(activity, 20))
            val slider = offsetSlider(activity, id, provider, offsetValue)
            body.addView(slider, fullMargin(activity, 10))
            body.addView(action(activity, "重置偏移") {
                if (provider != null) {
                    CurrentLyricsSourceStatus.setOffsetMs(activity, id, provider, 0)
                    slider.progress = 50
                    CurrentLyricsSourceStatus.refreshSilently(id)
                }
            }.apply { isEnabled = slider.isEnabled; alpha = if (isEnabled) 1f else .45f }, fullMargin(activity, 8))
        }
        redraw()
        dialog.show()
    }

    private fun showLyricsPreferences(activity: Activity, id: Long, refreshPage: () -> Unit) {
        val body = card(activity)
        val dialog = bubbleDialog(activity, body)
        val policy = com.tcrrry.lyrics.engine.lyrics.LyricsPreference
        lateinit var redraw: () -> Unit
        redraw = {
            body.removeAllViews()
            body.addView(title(activity, "歌词偏好", 20f))
            body.addView(segmentedChoice(activity, "匹配优先级", listOf("质量优先", "逐字优先"),
                if (policy.qualityFirst(activity)) 0 else 1) { which ->
                policy.update(activity, quality = which == 0)
                CurrentLyricsSourceStatus.refreshSilently(id)
                refreshPage()
            }, fullMargin(activity, 14))
            body.addView(segmentedChoice(activity, "发音排版", listOf("整行发音", "字词对应"),
                if (policy.rubyPronunciation(activity)) 1 else 0) { which ->
                val enabled = which == 1
                policy.updateRubyPronunciation(activity, enabled)
                com.tcrrry.lyrics.engine.hook.RubyPronunciationRows.refreshMode(enabled)
                CurrentLyricsSourceStatus.refreshSilently(id)
                refreshPage()
            }, fullMargin(activity, 14))
            body.addView(binaryChoice(activity, "隐藏开头署名", policy.hideOpeningCredits(activity)) { enabled ->
                policy.updateHideOpeningCredits(activity, enabled)
                CurrentLyricsSourceStatus.refreshSilently(id)
                refreshPage()
            }, fullMargin(activity, 14))
        }
        redraw()
        dialog.show()
    }

    private fun showLanguageSettings(activity: Activity, id: Long, refreshPage: () -> Unit) {
        val body = card(activity)
        val dialog = bubbleDialog(activity, body, compact = true, fixedHeight = true)
        lateinit var redraw: () -> Unit
        redraw = {
            body.removeAllViews()
            body.addView(title(activity, "翻译与注音", 20f))
            renderTranslationSettings(activity, body, id, refreshPage)
            val policy = com.tcrrry.lyrics.engine.lyrics.LyricsPreference
            body.addView(binaryChoice(activity, "繁体转简体", policy.simplifiedChinese(activity)) { enabled ->
                policy.updateSimplifiedChinese(activity, enabled)
                CurrentLyricsSourceStatus.refreshSilently(id)
                refreshPage()
            }, fullMargin(activity, 14))
            val prefs = activity.pluginPreferences("japanese_pronunciation", Context.MODE_PRIVATE)
            body.addView(title(activity, "离线注音", 17f), fullMargin(activity, 20))
            body.addView(binaryChoice(activity, "日语注音", prefs.getBoolean("enabled", true)) { enabled ->
                prefs.edit().putBoolean("enabled", enabled).apply()
                CurrentLyricsSourceStatus.refreshSilently(id); refreshPage()
            }, fullMargin(activity, 8))
            body.addView(binaryChoice(activity, "韩语注音", prefs.getBoolean("korean_enabled", true)) { enabled ->
                prefs.edit().putBoolean("korean_enabled", enabled).apply()
                CurrentLyricsSourceStatus.refreshSilently(id); refreshPage()
            }, fullMargin(activity, 10))
            body.addView(binaryChoice(activity, "当前歌曲粤语注音", prefs.getBoolean("cantonese_$id", false), id > 0L) { enabled ->
                prefs.edit().putBoolean("cantonese_$id", enabled).apply()
                CurrentLyricsSourceStatus.refreshSilently(id); refreshPage()
            }, fullMargin(activity, 10))
        }
        redraw()
        dialog.show()
    }

    private fun showMoreSettings(activity: Activity, id: Long) {
        val body = card(activity).apply { addView(title(activity, "更多工具", 20f)) }
        body.addView(action(activity, "检查更新") {
            com.tcrrry.lyrics.plugin.ReleaseUpdates.check(activity)
        }, fullMargin(activity, 14))
        body.addView(binaryChoice(activity, "新版本自动提醒", com.tcrrry.lyrics.plugin.ReleaseUpdates.automatic(activity)) { enabled ->
            com.tcrrry.lyrics.plugin.ReleaseUpdates.automatic(activity, enabled)
        }, fullMargin(activity, 14))
        body.addView(binaryChoice(activity, "强制覆盖循环方式", com.tcrrry.lyrics.plugin.PlaybackModeOptimization.enabled(activity)) { enabled ->
            com.tcrrry.lyrics.plugin.PlaybackModeOptimization.setEnabled(activity, enabled)
        }, fullMargin(activity, 14))
        body.addView(action(activity, "歌词缓存") { showCacheSettings(activity) }, fullMargin(activity, 14))
        body.addView(action(activity, "清理补充译文缓存") {
            val folder = java.io.File(activity.pluginCacheDir, "translations")
            folder.listFiles()?.filter { it.isFile }?.forEach { it.delete() }
            toast(activity, "补充译文缓存已清理")
            CurrentLyricsSourceStatus.refresh(id)
        }, fullMargin(activity, 14))
        body.addView(action(activity, "设置 Apple Music 链接打开方式") {
            val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS else Settings.ACTION_APPLICATION_DETAILS_SETTINGS
            runCatching { activity.startActivity(Intent(action, Uri.parse("package:${activity.packageName}"))) }
                .onFailure { toast(activity, "无法打开系统链接设置") }
        }, fullMargin(activity, 8))
        bubbleDialog(activity, body).show()
    }

    private fun showCacheSettings(activity: Activity) {
        val storage = com.tcrrry.lyrics.engine.hook.LyricsCacheStorage
        val body = card(activity).apply { addView(title(activity, "歌词缓存", 20f)) }
        val value = title(activity, "", 17f)
        fun capacity(mb: Int) = when (mb) { 0 -> "不保存磁盘缓存"; 1024 -> "缓存上限 · 1 GB"; else -> "缓存上限 · $mb MB" }
        value.text = capacity(storage.limitMb(activity))
        body.addView(value, fullMargin(activity, 16))
        val usage = label(activity, "正在统计缓存", 14f, SettingsUiTheme.colors(activity).secondary)
        val handler = com.tcrrry.lyrics.plugin.PluginHandler()
        val worker = com.tcrrry.lyrics.plugin.PluginExecutors.newSingleThreadExecutor()
        fun updateUsage(bytes: Long) {
            usage.text = String.format(Locale.getDefault(), "已使用 %.1f MB", bytes / (1024.0 * 1024.0))
        }
        body.addView(SeekBar(activity).apply {
            max = storage.MAX_MB / 16
            progress = storage.limitMb(activity) / 16
            progressTintList = ColorStateList.valueOf(SettingsUiTheme.colors(activity).primary)
            thumbTintList = progressTintList
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) { if (fromUser) value.text = capacity(progress * 16) }
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) {
                    storage.setLimitMb(activity, (bar?.progress ?: 4) * 16)
                    worker.execute { val remaining = storage.trim(activity); handler.post { if (!activity.isFinishing) updateUsage(remaining) } }
                }
            })
        }, fullMargin(activity, 10))
        body.addView(usage, fullMargin(activity, 8))
        val clear = action(activity, "清理歌词缓存") {}
        clear.setOnClickListener {
            clear.isEnabled = false
            worker.execute {
                val remaining = storage.clear(activity)
                handler.post {
                    if (!activity.isFinishing) {
                        updateUsage(remaining); clear.isEnabled = true
                        toast(activity, if (remaining == 0L) "歌词缓存已清理" else "部分缓存未能清理，请稍后重试")
                    }
                }
            }
        }
        body.addView(clear, fullMargin(activity, 16))
        worker.execute { val bytes = storage.bytes(activity); handler.post { if (!activity.isFinishing) updateUsage(bytes) } }
        bubbleDialog(activity, body, compact = true).apply { setOnDismissListener { worker.shutdown() } }.show()
    }

    private fun maybeShowFirstRunGuide(activity: Activity, parent: View) {
        if (activity.pluginPreferences(GUIDE_PREFS, Context.MODE_PRIVATE).getBoolean("completed", false)) return
        parent.post {
            if (parent.isAttachedToWindow && !activity.isFinishing &&
                com.tcrrry.lyrics.plugin.PluginLifetime.alive && activeGuide == null &&
                !activity.pluginPreferences(GUIDE_PREFS, Context.MODE_PRIVATE).getBoolean("completed", false)) {
                SettingsSurfaceContext.from(parent) { showOperationGuide(activity, required = true) }
            }
        }
    }

    internal fun showOperationGuide(activity: Activity, required: Boolean) {
        activeGuide?.takeIf { it.isShowing }?.let { return }
        val colors = SettingsUiTheme.colors(activity)
        val body = card(activity)
        body.addView(title(activity, "操作说明", 20f))
        val steps = listOf(
            Triple("歌词源入口", "播放页的歌词源菜单", "红框内是歌词源入口，名称随来源变化；以下三页介绍单击、双击和长按。"),
            Triple("单击", "切换歌词源", "播放页单击歌词源按钮，按顺序尝试可用来源；当前来源没有结果时会继续尝试。"),
            Triple("双击", "换一个匹配", "播放页双击歌词源按钮，在当前来源寻找另一条匹配记录。"),
            Triple("长按", "打开歌词设置", "播放页长按歌词源按钮，进入这里查看匹配信息、选源和调整偏移。"),
        )
        val pages = android.widget.ViewFlipper(activity).apply {
            minimumHeight = dp(activity, 190)
            measureAllChildren = true
        }
        steps.forEachIndexed { index, (gesture, heading, description) ->
            pages.addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(activity, 20), 0, dp(activity, 14))
                if (index == 0) {
                    addView(title(activity, heading, 16f))
                    addView(GuideSourceMenuIllustration(activity), LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 210)).apply { topMargin = dp(activity, 8) })
                } else {
                    addView(label(activity, gesture, 28f, colors.primary).apply {
                        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    })
                    addView(title(activity, heading, 19f), fullMargin(activity, 13))
                }
                addView(label(activity, description, 14f, colors.secondary), fullMargin(activity, 9))
            })
        }
        body.addView(pages, fullMargin(activity, 6))
        val progress = label(activity, "1 / ${steps.size}", 13f, colors.secondary).apply { gravity = Gravity.CENTER }
        body.addView(progress)
        val controls = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val previous = action(activity, "上一页") {}
        val next = action(activity, "下一页", emphasized = true) {}
        controls.addView(previous, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        controls.addView(next, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = dp(activity, 8) })
        body.addView(controls, fullMargin(activity, 18))
        val dialog = bubbleDialog(activity, body, outsideCancelable = !required, compact = true).apply {
            setCancelable(!required)
            setCanceledOnTouchOutside(!required)
            setOnDismissListener { if (activeGuide === this) activeGuide = null }
        }
        fun move(to: Int) {
            val target = to.coerceIn(0, steps.lastIndex)
            if (target != pages.displayedChild) {
                val direction = if (target > pages.displayedChild) 1f else -1f
                for (index in 0 until pages.childCount) pages.getChildAt(index).clearAnimation()
                val animate = android.animation.ValueAnimator.areAnimatorsEnabled()
                pages.inAnimation = if (animate) guidePageAnimation(entering = true, direction) else null
                pages.outAnimation = if (animate) guidePageAnimation(entering = false, direction) else null
                pages.displayedChild = target
            }
            progress.text = "${pages.displayedChild + 1} / ${steps.size}"
            previous.visibility = if (pages.displayedChild == 0) View.INVISIBLE else View.VISIBLE
            previous.isEnabled = pages.displayedChild > 0
            next.text = if (pages.displayedChild == steps.lastIndex) {
                if (required) "开始使用" else "关闭"
            } else "下一页"
        }
        previous.setOnClickListener { move(pages.displayedChild - 1) }
        next.setOnClickListener {
            if (pages.displayedChild == steps.lastIndex) {
                if (required) activity.pluginPreferences(GUIDE_PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean("completed", true).apply()
                dialog.dismiss()
            } else move(pages.displayedChild + 1)
        }
        var downX = 0f
        var downY = 0f
        pages.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (kotlin.math.abs(event.x - downX) > kotlin.math.abs(event.y - downY))
                        pages.parent.requestDisallowInterceptTouchEvent(true)
                }
                android.view.MotionEvent.ACTION_UP -> {
                    val dx = event.x - downX
                    if (kotlin.math.abs(dx) >= dp(activity, 55) &&
                        kotlin.math.abs(dx) > kotlin.math.abs(event.y - downY))
                        move(pages.displayedChild + if (dx < 0) 1 else -1)
                    pages.parent.requestDisallowInterceptTouchEvent(false)
                }
                android.view.MotionEvent.ACTION_CANCEL -> pages.parent.requestDisallowInterceptTouchEvent(false)
            }
            true
        }
        move(0)
        activeGuide = dialog
        dialog.show()
    }

    private fun guidePageAnimation(entering: Boolean, direction: Float) =
        android.view.animation.AnimationSet(true).apply {
            duration = 240L
            interpolator = android.view.animation.PathInterpolator(.2f, .8f, .2f, 1f)
            addAnimation(android.view.animation.AlphaAnimation(if (entering) 0f else 1f, if (entering) 1f else 0f))
            addAnimation(android.view.animation.TranslateAnimation(
                android.view.animation.Animation.RELATIVE_TO_PARENT, if (entering) direction * .12f else 0f,
                android.view.animation.Animation.RELATIVE_TO_PARENT, if (entering) 0f else -direction * .12f,
                android.view.animation.Animation.RELATIVE_TO_PARENT, 0f,
                android.view.animation.Animation.RELATIVE_TO_PARENT, 0f))
        }

    /** Our glow controls live here, separate from the upstream AM++ page. */
    internal fun glowSettingsCard(activity: Activity, settings: ModuleSettings,
        onSettingsChanged: (ModuleSettings) -> Unit, refreshPage: () -> Unit, songId: Long = 0L): LinearLayout {
        var current = settings
        fun save(next: ModuleSettings) { current = next; onSettingsChanged(next) }
        return card(activity).apply {
            addView(title(activity, "歌词辉光增强", 18f))
            addView(label(activity, "辉光增强默认启用。灵敏度越高越容易触发；位置与灵敏度保存后生效，不改变逐字时间。",
                12f, SettingsUiTheme.colors(activity).secondary), fullMargin(activity, 5))
            val value = label(activity, "辉光灵敏度：${current.lyricGlowSensitivity}%", 14f, SettingsUiTheme.colors(activity).text)
            addView(value, fullMargin(activity, 12))
            val sensitivity = SeekBar(activity).apply {
                max = ModuleSettings.MAX_LYRIC_GLOW_SENSITIVITY - ModuleSettings.MIN_LYRIC_GLOW_SENSITIVITY
                progress = current.lyricGlowSensitivity.coerceIn(ModuleSettings.MIN_LYRIC_GLOW_SENSITIVITY, ModuleSettings.MAX_LYRIC_GLOW_SENSITIVITY) - ModuleSettings.MIN_LYRIC_GLOW_SENSITIVITY
                contentDescription = "辉光灵敏度"
                progressTintList = ColorStateList.valueOf(SettingsUiTheme.colors(activity).primary)
                progressBackgroundTintList = ColorStateList.valueOf(SettingsUiTheme.colors(activity).outline)
                thumbTintList = ColorStateList.valueOf(SettingsUiTheme.colors(activity).text)
                splitTrack = false
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                        value.text = "辉光灵敏度：${progress + ModuleSettings.MIN_LYRIC_GLOW_SENSITIVITY}%"
                        if (fromUser) save(current.copy(lyricGlowSensitivity = progress + ModuleSettings.MIN_LYRIC_GLOW_SENSITIVITY))
                    }
                    override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(bar: SeekBar?) = Unit
                })
            }
            addView(sensitivity, fullMargin(activity, 4))
            addView(label(activity, "50%～500%，越高越容易触发；100% 保留默认门槛。", 12f,
                SettingsUiTheme.colors(activity).secondary), fullMargin(activity, 4))
            addView(action(activity, "恢复默认灵敏度") {
                save(current.copy(lyricGlowSensitivity = 100))
                sensitivity.progress = 100 - ModuleSettings.MIN_LYRIC_GLOW_SENSITIVITY
                refreshPage()
            }, fullMargin(activity, 8))
            val position = action(activity, "辉光触发位置：${current.lyricGlowPosition.displayName}") {}
            position.setOnClickListener { clicked -> SettingsDialogMotion.from(clicked) {
                val choices = com.tcrrry.lyrics.engine.model.LyricGlowPosition.entries
                val body = card(activity).apply { addView(title(activity, "辉光触发位置", 20f)) }
                val picker = bubbleDialog(activity, body, compact = true)
                choices.forEach { choice ->
                    body.addView(action(activity, choice.displayName, emphasized = choice == current.lyricGlowPosition) {
                        save(current.copy(lyricGlowPosition = choice))
                        position.text = "辉光触发位置：${choice.displayName}"
                        picker.dismiss()
                    }, fullMargin(activity, 9))
                }
                picker.show()
            } }
            addView(position, fullMargin(activity, 8))
            addView(label(activity, "句尾优先会减少句中触发；仅句尾按歌词位置判断，不识别人声。", 12f,
                SettingsUiTheme.colors(activity).secondary), fullMargin(activity, 7))
            val policy = com.tcrrry.lyrics.engine.lyrics.LyricsPreference
            addView(binaryChoice(activity, "短单元平滑", policy.smoothShortUnits(activity)) { enabled ->
                policy.update(activity, smooth = enabled)
                if (songId > 0L) CurrentLyricsSourceStatus.refreshSilently(songId)
                refreshPage()
            }, fullMargin(activity, 14))
            addView(label(activity, "默认开启。合并同句内相邻、少于 100ms 的单元，保留完整时间范围。原文不跨英文单词空格；突出发音时，相邻短读音可共用一次高亮。", 12f,
                SettingsUiTheme.colors(activity).secondary), fullMargin(activity, 7))
        }
    }

    /** Persist user input before asynchronous status updates can rebuild the page. */
    internal fun offsetSlider(activity: Activity, id: Long, source: String?, value: TextView,
        tracking: (Boolean) -> Unit = {}): SeekBar {
        val offset = source?.let { CurrentLyricsSourceStatus.offsetMs(activity, id, it) } ?: 0
        val bar = SeekBar(activity)
        val applyOffset = Runnable {
            if (source == null || id <= 0L) return@Runnable
            CurrentLyricsSourceStatus.refreshSilently(id)
        }
        return bar.apply {
            max = 100
            progress = offset / 100 + 50
            progressTintList = ColorStateList.valueOf(SettingsUiTheme.colors(activity).primary)
            progressBackgroundTintList = ColorStateList.valueOf(SettingsUiTheme.colors(activity).outline)
            thumbTintList = ColorStateList.valueOf(SettingsUiTheme.colors(activity).text)
            isEnabled = id > 0L && source in LyricsSourceMenuPolicy.thirdPartySources
            splitTrack = false
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val next = (progress - 50) * 100
                    value.text = formatOffset(next)
                    if (fromUser && source != null) {
                        CurrentLyricsSourceStatus.selectSource(activity, id, source)
                        CurrentLyricsSourceStatus.setOffsetMs(activity, id, source, next)
                        removeCallbacks(applyOffset)
                        postDelayed(applyOffset, 250L)
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) { tracking(true) }
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    tracking(false)
                    removeCallbacks(applyOffset)
                    applyOffset.run()
                }
            })
        }
    }

    /** Keep the complete source visible through marquee, with a fixed navigation cue. */
    internal fun sourceStatusLabel(activity: Activity, description: () -> String, expanded: Boolean = false,
        open: (() -> Unit)? = null): View = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(activity, 48)
            setPadding(dp(activity, 14), dp(activity, 8), dp(activity, 10), dp(activity, 8))
            val colors = SettingsUiTheme.colors(activity)
            background = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(colors.outline),
                shape(activity, colors.selected, 12, colors.primary),
                shape(activity, android.graphics.Color.WHITE, 12, android.graphics.Color.WHITE))
            SettingsGlassMaterial.mark(this, SettingsGlassKind.Button, accent = true)
            val ticker = if (expanded) null else SmoothSourceTicker(activity, colors.primary).apply { display(description()) }
            val fullText = if (!expanded) null else label(activity, description(), 14f, colors.primary).apply {
                setSingleLine(false)
                ellipsize = null
                setLineSpacing(dp(activity, 3).toFloat(), 1f)
            }
            addView(ticker ?: fullText!!, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val arrow = label(activity, "›", 24f, colors.primary).apply { gravity = Gravity.CENTER }
            if (open != null) addView(arrow, LinearLayout.LayoutParams(dp(activity, 28), dp(activity, 32)))
            contentDescription = description()
            isClickable = open != null
            isLongClickable = false
            isFocusable = open != null
            isSoundEffectsEnabled = false
            isHapticFeedbackEnabled = false
            if (open != null) setOnClickListener { SettingsDialogMotion.from(it, open) }
            val label = this
            val update = object : Runnable {
                override fun run() {
                    if (!label.isAttachedToWindow) return
                    val next = description()
                    if ((ticker?.label ?: fullText!!).text.toString() != next) {
                        if (ticker != null) ticker.display(next) else fullText!!.text = next
                        label.contentDescription = next
                    }
                    label.postDelayed(this, 500L)
                }
            }
            addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    label.post(update)
                    if (open != null && android.animation.ValueAnimator.areAnimatorsEnabled()) {
                        arrow.animate().translationX(dp(activity, 4).toFloat()).setStartDelay(650L)
                            .setDuration(450L).withEndAction {
                                arrow.animate().translationX(0f).setStartDelay(0L).setDuration(450L).start()
                            }.start()
                    }
                }
                override fun onViewDetachedFromWindow(v: View) {
                    label.removeCallbacks(update)
                    arrow.animate().cancel()
                }
            })
        }

    internal fun prepareRenderPresentation(parent: LinearLayout) {
        parent.animate().cancel()
        parent.alpha = 1f
        parent.translationY = 0f
    }

    private fun renderTranslationSettings(
        activity: Activity,
        parent: LinearLayout,
        id: Long,
        refreshPage: () -> Unit,
    ) {
        val prefs = activity.pluginPreferences("supplement_translation", Context.MODE_PRIVATE)
        val modes = listOf("off", "offline", "api")
        val mode = prefs.getString("mode", "off") ?: "off"
        parent.addView(title(activity, "补充翻译", 17f), fullMargin(activity, 16))
        val details = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        lateinit var refreshDetails: () -> Unit
        refreshDetails = {
            details.removeAllViews()
            when (prefs.getString("mode", "off")) {
                "offline" -> renderOfflineControls(activity, details, id)
                "api" -> renderApiControls(activity, details, id, refreshDetails)
            }
            CurrentLyricsSourceStatus.translationStatus(activity, id).takeIf(String::isNotBlank)?.let { status ->
                details.addView(label(activity, status, 12f, SettingsUiTheme.colors(activity).secondary), fullMargin(activity, 12))
            }
        }
        parent.addView(SettingsSegmentedControl(activity, "补充翻译", listOf("关闭", "离线机翻", "自定义 API"),
            modes.indexOf(mode).coerceAtLeast(0)) { index ->
            prefs.edit().putString("mode", modes[index]).apply()
            CurrentLyricsSourceStatus.refresh(id)
            refreshPage(); refreshDetails()
        }.apply { inlineGlass = true }, fullMargin(activity, 12))
        parent.addView(details, fullMargin(activity, 4))
        refreshDetails()
    }

    private fun renderApiControls(activity: Activity, card: LinearLayout, id: Long, refreshPage: () -> Unit) {
        val prefs = activity.pluginPreferences("supplement_translation", Context.MODE_PRIVATE)
        val profile = TranslationApiProfiles.find(activity, prefs.getString("active_api_profile", null))
        card.addView(action(activity, "${profile.label}  ⌄") {
            showApiProfilePicker(activity, id, refreshPage)
        }, fullMargin(activity, 12))
        val endpoint = field(activity, "HTTPS 服务地址", prefs.getString(TranslationApiProfiles.endpointKey(profile.id), profile.defaultEndpoint).orEmpty())
        val model = field(activity, "模型名称", prefs.getString(TranslationApiProfiles.modelKey(profile.id), profile.defaultModel).orEmpty())
        val secret = SecretStorage(activity, profile.id)
        val key = field(activity, if (secret.read().isBlank()) "API Key" else "API Key 已保存，留空表示不更改", "").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        listOf(endpoint, model, key).forEach { card.addView(it, fullMargin(activity, 8)) }
        val status = label(activity, "", 12f, SettingsUiTheme.colors(activity).secondary)
        card.addView(action(activity, "保存并验证连接", emphasized = true) {
            val address = endpoint.text.toString().trim()
            val modelName = model.text.toString().trim()
            if (!address.startsWith("https://") || modelName.isBlank()) {
                status.text = "请填写 HTTPS 地址和模型名称"
                return@action
            }
            val newKey = key.text.toString().trim()
            runCatching { if (newKey.isNotBlank()) secret.save(newKey) }
                .onFailure { status.text = "密钥保存失败：${it.message.orEmpty().take(80)}"; return@action }
            val savedKey = if (newKey.isNotBlank()) newKey else secret.read()
            if (savedKey.isBlank()) { status.text = "请填写 API Key"; return@action }
            prefs.edit().putString("active_api_profile", profile.id)
                .putString(TranslationApiProfiles.endpointKey(profile.id), address)
                .putString(TranslationApiProfiles.modelKey(profile.id), modelName).apply()
            key.setText("")
            status.text = "正在验证翻译服务…"
            com.tcrrry.lyrics.plugin.PluginExecutors.newSingleThreadExecutor().execute {
                val outcome = runCatching { SupplementTranslation(activity.applicationContext).testApi(address, modelName, savedKey) }
                activity.runOnUiThread {
                    if (!activity.isFinishing && com.tcrrry.lyrics.plugin.PluginLifetime.alive) {
                        status.text = outcome.fold(
                            onSuccess = { "连接成功 · 测试译文：$it" },
                            onFailure = { "连接失败：${it.message.orEmpty().take(150)}" },
                        )
                        if (outcome.isSuccess) CurrentLyricsSourceStatus.refresh(id)
                    }
                }
            }
        }, fullMargin(activity, 10))
        card.addView(action(activity, "删除当前 API Key") {
            showConfirmation(activity, "删除 API Key", "删除当前服务的本机密钥？地址和模型会保留。") {
                secret.save("")
                key.setText("")
                status.text = "已删除当前服务的密钥"
            }
        }, fullMargin(activity, 8))
        card.addView(action(activity, "管理当前 API 配置") {
            showApiProfileManagement(activity, profile.id, refreshPage)
        }, fullMargin(activity, 8))
        card.addView(status, fullMargin(activity, 8))
    }

    private fun showApiProfileManagement(activity: Activity, profileId: String, refreshPage: () -> Unit) {
        val profile = TranslationApiProfiles.find(activity, profileId)
        val body = card(activity)
        body.addView(title(activity, profile.label, 19f))
        val dialog = bubbleDialog(activity, body, compact = true)
        body.addView(action(activity, "重命名") {
            dialog.dismiss()
            showTextPrompt(activity, "重命名 API 服务", profile.label) { name ->
                TranslationApiProfiles.rename(activity, profileId, name)
                refreshPage()
            }
        }, fullMargin(activity, 10))
        body.addView(action(activity, "删除此服务及其密钥") {
            dialog.dismiss()
            showConfirmation(activity, "删除 API 配置", "将删除 ${profile.label} 的地址、模型和本机密钥。") {
                TranslationApiProfiles.remove(activity, profileId)
                refreshPage()
            }
        }, fullMargin(activity, 8))
        dialog.show()
    }

    private fun showApiProfilePicker(activity: Activity, id: Long, refreshPage: () -> Unit) {
        val body = card(activity)
        body.addView(title(activity, "选择翻译 API", 19f))
        val prefs = activity.pluginPreferences("supplement_translation", Context.MODE_PRIVATE)
        val dialog = bubbleDialog(activity, body, compact = true)
        TranslationApiProfiles.all(activity).forEach { profile ->
            body.addView(action(activity, profile.label,
                emphasized = prefs.getString("active_api_profile", null) == profile.id) {
                prefs.edit().putString("active_api_profile", profile.id).apply()
                dialog.dismiss()
                refreshPage()
            }, fullMargin(activity, 8))
        }
        body.addView(action(activity, "＋ 添加自定义 API") {
            dialog.dismiss()
            showTextPrompt(activity, "添加 API 服务", "") { name ->
                val added = TranslationApiProfiles.add(activity, name)
                prefs.edit().putString("active_api_profile", added.id).apply()
                refreshPage()
            }
        }, fullMargin(activity, 10))
        dialog.show()
    }

    private fun renderOfflineControls(activity: Activity, card: LinearLayout, id: Long) {
        val backend = com.tcrrry.lyrics.plugin.EmbeddedOfflineModels.find(activity)
        if(backend==null) {
            card.addView(label(activity,"请导入并启用「Tcrrry 离线翻译」插件",12f,SettingsUiTheme.colors(activity).secondary),fullMargin(activity,8))
            return
        }
        val panel=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
        card.addView(panel,fullMargin(activity,4))
        renderOfflinePanel(activity,panel,panel,backend)
    }

    private fun field(activity: Activity, hint: String, value: String = "") = EditText(activity).apply {
        this.hint = hint
        setText(value)
        setTextColor(SettingsUiTheme.colors(activity).text)
        setHintTextColor(SettingsUiTheme.colors(activity).muted)
        textSize = 14f
        setSingleLine(true)
        isFocusableInTouchMode = true
        showSoftInputOnFocus = true
        setOnClickListener {
            requestFocus()
            post { (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
                ?.showSoftInput(this, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT) }
        }
        setPadding(dp(activity, 14), dp(activity, 9), dp(activity, 14), dp(activity, 9))
        background = shape(activity, SettingsUiTheme.colors(activity).raised, 12, SettingsUiTheme.colors(activity).outline)
        SettingsGlassMaterial.mark(this, SettingsGlassKind.Field)
    }

    internal fun showLanguageSearch(activity: Activity, codes: List<String>, selected: String, choose: (String) -> Unit) {
        val body = card(activity).apply { addView(title(activity, "选择原语言", 20f)) }
        val input = field(activity, "搜索语言、English 或代码")
        input.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        body.addView(input, fullMargin(activity, 12))
        val results = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        body.addView(results, fullMargin(activity, 8))
        val dialog = bubbleDialog(activity, body, compact = true, fixedHeight = true)
        fun render(query: String) {
            results.removeAllViews()
            val matches = LanguageSearchPolicy.filter(codes, query)
            if (matches.isEmpty()) results.addView(label(activity, "未找到匹配语言", 14f, SettingsUiTheme.colors(activity).secondary))
            matches.forEach { code ->
                val name = if (code == "auto") "自动判断" else Locale.forLanguageTag(code).getDisplayLanguage(Locale.SIMPLIFIED_CHINESE)
                results.addView(action(activity, if (code == "auto") name else "$name · $code", emphasized = code == selected) {
                    dialog.dismiss(); choose(code)
                }, fullMargin(activity, 8))
            }
        }
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { render(s.toString()) }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        render("")
        dialog.show()
    }

    internal fun showOfflineModelManager(activity: Activity, backend: com.tcrrry.lyrics.plugin.OfflineModelBackend) {
        showOfflineDownloads(activity, backend)
    }

    private fun languageName(code: String) = Locale.forLanguageTag(code).getDisplayLanguage(Locale.SIMPLIFIED_CHINESE)
    private fun orderedModels(models: Set<String>) = models.sortedWith(compareBy<String> {
        when(it) { "zh" -> 0;"en" -> 1;else -> 2 }
    }.thenBy { languageName(it) })

    internal fun showOfflineDownloads(activity: Activity, backend: com.tcrrry.lyrics.plugin.OfflineModelBackend, changed: () -> Unit = {}) {
        val body = card(activity).apply { addView(title(activity, "离线翻译", 20f)) }
        val footer=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(activity,18),dp(activity,6),dp(activity,18),dp(activity,12)) }
        val dialog=bubbleDialog(activity,body,footer,compact=true)
        renderOfflinePanel(activity,body,footer,backend,changed)
        footer.addView(action(activity,"关闭") { dialog.dismiss() },fullMargin(activity,8))
        dialog.show()
    }

    internal fun renderOfflinePanel(activity: Activity, body: LinearLayout, footer: LinearLayout,
        backend: com.tcrrry.lyrics.plugin.OfflineModelBackend, changed: () -> Unit = {}) {
        val input = field(activity, "搜索语言、English 或代码").apply {
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            inputType = InputType.TYPE_CLASS_TEXT
        }
        body.addView(input, fullMargin(activity, 12))
        body.addView(label(activity, "下载语言包需要开启代理；下载完成后可离线使用。", 12f,
            SettingsUiTheme.colors(activity).secondary), fullMargin(activity, 8))
        val rows = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val languagesList=android.widget.ScrollView(activity).apply { addView(rows);isVerticalScrollBarEnabled=false }
        val bar = android.widget.ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max=100;visibility=View.GONE;progressTintList=ColorStateList.valueOf(SettingsUiTheme.colors(activity).primary)
        }
        body.addView(bar,fullMargin(activity,10))
        val status=label(activity,"正在读取语言状态…",13f,SettingsUiTheme.colors(activity).secondary)
        body.addView(status,fullMargin(activity,8))
        body.addView(languagesList,LinearLayout.LayoutParams(-1,dp(activity,176)).apply { topMargin=dp(activity,8) })
        var busy=true;var connected=false;var installed=emptySet<String>()
        fun active()=body.isAttachedToWindow && !activity.isDestroyed && !activity.isFinishing && com.tcrrry.lyrics.plugin.PluginLifetime.alive
        lateinit var render: () -> Unit
        fun refresh(message: String?=null) {
            backend.query { models,error -> com.tcrrry.lyrics.plugin.PluginHandler().post {
                if(!active()) return@post
                busy=false;connected=models!=null
                if(models!=null) installed=models
                status.text=if(models==null) "请导入并启用「Tcrrry 离线翻译」插件后重开宿主" else error ?: message ?: "搜索需要下载的语言"
                bar.visibility=View.GONE;render()
            } }
        }
        render = {
            rows.removeAllViews()
            val matches=if(input.text.isBlank()) emptyList() else LanguageSearchPolicy.filter(backend.languages.filterNot { it in setOf("en","zh") },input.text.toString())
            languagesList.visibility=if(input.text.isBlank()) View.GONE else View.VISIBLE
            languagesList.layoutParams.height=dp(activity,(matches.size*56).coerceIn(52,176))
            languagesList.requestLayout()
            if(input.text.isNotBlank() && matches.isEmpty()) rows.addView(label(activity,"未找到匹配语言",14f,SettingsUiTheme.colors(activity).secondary))
            matches.forEach { code ->
                val ready=code in installed
                val description=if(ready) "已下载" else "下载"
                val row=action(activity,"${languageName(code)} · $description") {
                    if(busy || !connected || code in setOf("en","zh") || code in installed) return@action
                    busy=true;status.text="正在准备下载${languageName(code)}…";bar.visibility=View.VISIBLE;bar.isIndeterminate=true;render()
                    backend.download(code, { progress -> com.tcrrry.lyrics.plugin.PluginHandler().post {
                        if(!active()) return@post
                        val percent=progress.getInt("percent",-1)
                        bar.isIndeterminate=percent<0
                        if(percent>=0) bar.progress=percent
                        status.text=progress.getString("message") ?: "正在下载${languageName(code)}…"
                    } }) { error -> com.tcrrry.lyrics.plugin.PluginHandler().post {
                        if(!active()) return@post
                        if(error!=null) { busy=false;bar.visibility=View.GONE;status.text=error;render() }
                        else { refresh("${languageName(code)}语言包已就绪");changed() }
                    } }
                }.apply {
                    contentDescription="${languageName(code)}语言包：$description"
                    isEnabled=connected && !busy && !ready && code !in setOf("en","zh")
                    alpha=if(isEnabled) 1f else .55f
                }
                rows.addView(row,fullMargin(activity,5))
            }
        }
        input.addTextChangedListener(object:android.text.TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int)=Unit
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) { render() }
            override fun afterTextChanged(s:android.text.Editable?)=Unit
        })
        footer.addView(action(activity,"已下载语言包") { showDownloadedModels(activity, backend) { refresh();changed() } },fullMargin(activity,4))
        var verifying=false
        footer.addView(action(activity,"检测离线翻译") {
            if(!verifying) {
                verifying=true;status.text="正在检测离线翻译…"
                Thread {
                    val result=runCatching { com.tcrrry.lyrics.plugin.OfflineTranslationClient.translate(activity,"en","Hello, world") }
                    com.tcrrry.lyrics.plugin.PluginHandler().post {
                        if(!active()) return@post
                        verifying=false
                        status.text=result.fold({ "翻译可用：$it" },{ "检测失败：${it.message}" })
                    }
                }.start()
            }
        },fullMargin(activity,8))
        body.addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view:View) { refresh() }
            override fun onViewDetachedFromWindow(view:View) = Unit
        })
        render()
        if(body.isAttachedToWindow) refresh()
    }

    internal fun showDownloadedModels(activity: Activity, backend: com.tcrrry.lyrics.plugin.OfflineModelBackend, changed: () -> Unit = {}) {
        val body=card(activity).apply { addView(title(activity,"已下载语言包",20f)) }
        val rows=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
        body.addView(rows,fullMargin(activity,12))
        val status=label(activity,"正在读取…",13f,SettingsUiTheme.colors(activity).secondary)
        body.addView(status,fullMargin(activity,10))
        val dialog=bubbleDialog(activity,body,compact=true)
        var alive=true;var busy=false
        dialog.setOnDismissListener { alive=false }
        fun active()=alive && dialog.isShowing && !activity.isDestroyed && !activity.isFinishing && com.tcrrry.lyrics.plugin.PluginLifetime.alive
        lateinit var refresh: () -> Unit
        refresh = {
            busy=true
            backend.query { models,error -> com.tcrrry.lyrics.plugin.PluginHandler().post {
                if(!active()) return@post
                rows.removeAllViews();busy=false
                status.text=error ?: if(models.orEmpty().none { it !in setOf("zh","en") }) "尚未下载其他语言包" else "点按语言包可删除，以后可重新下载。"
                models?.filterNot { it in setOf("zh","en") }?.toSet()?.let(::orderedModels)?.forEach { code ->
                    rows.addView(action(activity,"删除${languageName(code)}") {
                        if(busy) return@action
                        showConfirmation(activity,"删除${languageName(code)}语言包","以后需要时可以重新下载，其他语言包将保留。") {
                            if(!active()) return@showConfirmation
                            busy=true;status.text="正在删除${languageName(code)}…"
                            backend.delete(code) { failure -> com.tcrrry.lyrics.plugin.PluginHandler().post {
                                if(!active()) return@post
                                if(failure!=null) { busy=false;status.text=failure }
                                else { refresh();changed() }
                            } }
                        }
                    },fullMargin(activity,6))
                }
            } }
        }
        body.addView(action(activity,"关闭") { dialog.dismiss() },fullMargin(activity,12))
        dialog.show();refresh()
    }

    internal fun updateReminderBody(activity: Activity, heading: String, current: String,
        actions: List<Pair<String, () -> Unit>>): LinearLayout = card(activity).apply {
        addView(title(activity, heading, 20f))
        addView(label(activity, current, 13f, SettingsUiTheme.colors(activity).secondary), fullMargin(activity, 8))
        actions.forEachIndexed { index, (caption, callback) ->
            addView(action(activity, caption, emphasized = index == 0, click = callback), fullMargin(activity, 10))
        }
    }

    private fun showTextPrompt(activity: Activity, heading: String, initial: String, save: (String) -> Unit) {
        val body = card(activity).apply { addView(title(activity, heading, 19f)) }
        val input = field(activity, "服务名称", initial)
        input.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        body.addView(input, fullMargin(activity, 14))
        val dialog = bubbleDialog(activity, body, compact = true)
        val positive = action(activity, "保存", emphasized = true) {
            input.text.toString().trim().takeIf(String::isNotBlank)?.let { name -> dialog.dismiss(); save(name) }
        }
        fun updateEnabled() { positive.isEnabled = input.text.isNotBlank(); positive.alpha = if (positive.isEnabled) 1f else .45f }
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { updateEnabled() }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        input.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE && positive.isEnabled) positive.performClick() else false
        }
        val row = LinearLayout(activity).apply {
            addView(action(activity, "取消") { dialog.dismiss() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            addView(positive, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = dp(activity, 8) })
        }
        body.addView(row, fullMargin(activity, 18))
        updateEnabled()
        dialog.show()
    }

    private fun showConfirmation(activity: Activity, heading: String, message: String, confirm: () -> Unit) {
        val body = card(activity).apply {
            addView(title(activity, heading, 19f))
            addView(label(activity, message, 14f, SettingsUiTheme.colors(activity).secondary), fullMargin(activity, 12))
        }
        val dialog = bubbleDialog(activity, body, compact = true)
        body.addView(LinearLayout(activity).apply {
            addView(action(activity, "取消") { dialog.dismiss() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            addView(action(activity, "删除", emphasized = true) { dialog.dismiss(); confirm() },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = dp(activity, 8) })
        }, fullMargin(activity, 18))
        dialog.show()
    }

    internal fun mainDialog(activity: Activity, body: LinearLayout): android.app.AlertDialog {
        val close = action(activity, "关闭") {}
        val footer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 18), dp(activity, 12), dp(activity, 18), dp(activity, 16))
            addView(close, fullMargin(activity, 0))
        }
        val dialog = bubbleDialog(activity, body, footer, mainPage = true)
        close.setOnClickListener { dialog.dismiss() }
        return dialog
    }

    internal fun bubbleDialog(activity: Activity, body: LinearLayout,
        footer: View? = null, outsideCancelable: Boolean = true, compact: Boolean = false,
        fixedHeight: Boolean = false, mainPage: Boolean = false): android.app.AlertDialog {
        val origin = SettingsDialogMotion.origin()
        val returnTarget = SettingsDialogMotion.returnTarget()
        val hostSurface = SettingsSurfaceContext.hostColor
        val palette = SettingsUiTheme.colors(activity)
        val heightFraction = if (compact) .72f else .86f
        val scroll = object : android.widget.ScrollView(activity) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val cap = (resources.displayMetrics.heightPixels * heightFraction).toInt()
                val available = if (View.MeasureSpec.getMode(heightMeasureSpec) == View.MeasureSpec.UNSPECIFIED) cap
                    else minOf(cap, View.MeasureSpec.getSize(heightMeasureSpec))
                super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(available,
                    if (fixedHeight) View.MeasureSpec.EXACTLY else View.MeasureSpec.AT_MOST))
            }
        }.apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(activity, 18))
            addView(body)
        }
        val glass = runCatching {
            check(com.tcrrry.lyrics.plugin.PluginLifetime.context.hostVersionCode == 1609L)
            check(Build.VERSION.SDK_INT >= 33)
            val plate = SettingsFrostedView(activity)
            val backdrop: SettingsPanelBackdrop = hostSurface?.let(::SettingsHostBackdrop) ?: SettingsGlassBackdrop(activity, plate)
            plate.backdrop = backdrop
            plate to backdrop
        }.getOrNull()
        if (glass == null) body.background = shape(activity, SettingsUiTheme.colors(activity).surface,
            24, SettingsUiTheme.colors(activity).outline)
        var controls: SettingsGlassControls? = null
        val frame = object : android.widget.FrameLayout(activity) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val width = View.MeasureSpec.getSize(widthMeasureSpec)
                val maxHeight = minOf((resources.displayMetrics.heightPixels * heightFraction).toInt(),
                    if (View.MeasureSpec.getMode(heightMeasureSpec) == View.MeasureSpec.UNSPECIFIED) Int.MAX_VALUE
                    else View.MeasureSpec.getSize(heightMeasureSpec))
                footer?.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST))
                val footerHeight = footer?.measuredHeight ?: 0
                scroll.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec((maxHeight - footerHeight).coerceAtLeast(0), View.MeasureSpec.AT_MOST))
                val height = if (fixedHeight) maxHeight else scroll.measuredHeight + footerHeight
                setMeasuredDimension(width, height)
                glass?.first?.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                controls?.host?.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            }
            override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
                glass?.first?.layout(0, 0, right - left, bottom - top)
                controls?.host?.layout(0, 0, right - left, bottom - top)
                val footerHeight = footer?.measuredHeight ?: 0
                val scrollHeight = bottom - top - footerHeight
                scroll.layout(0, 0, right - left, scrollHeight)
                footer?.layout(0, scrollHeight, right - left, bottom - top)
            }
        }.apply {
            background = GradientDrawable().apply {
                setColor(if (glass != null) android.graphics.Color.TRANSPARENT
                    else SettingsUiTheme.colors(activity).surface)
                cornerRadius = dp(activity, 24).toFloat()
            }
            clipToOutline = true
            alpha = 0f
        }
        if (hostSurface != null) SettingsSurfaceContext.mark(frame, hostSurface)
        if (glass != null) {
            body.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            frame.addView(glass.first, android.widget.FrameLayout.LayoutParams(-1, -1))
            frame.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = Unit
                override fun onViewDetachedFromWindow(v: View) { glass.second.close() }
            })
        }
        if (glass != null) {
            frame.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = Unit
                override fun onViewDetachedFromWindow(v: View) { controls?.close() }
            })
        }
        frame.addView(scroll, android.widget.FrameLayout.LayoutParams(-1, -2))
        if (footer != null) frame.addView(footer, android.widget.FrameLayout.LayoutParams(-1, -2))
        var closing = false
        var liquidDrag = false
        val overlay = object : android.widget.FrameLayout(activity) {
            override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
                if (closing) return true
                if (controls?.touch(event) == true) {
                    if (!liquidDrag) {
                        val cancel = android.view.MotionEvent.obtain(event)
                        try { cancel.action = android.view.MotionEvent.ACTION_CANCEL; super.dispatchTouchEvent(cancel) }
                        finally { cancel.recycle() }
                    }
                    liquidDrag = event.actionMasked != android.view.MotionEvent.ACTION_UP &&
                        event.actionMasked != android.view.MotionEvent.ACTION_CANCEL
                    return true
                }
                return super.dispatchTouchEvent(event)
            }
        }.apply { clipChildren = false; clipToPadding = false }
        val scrim = View(activity).apply { setBackgroundColor(0x33000000); alpha = 0f }
        overlay.addView(scrim, android.widget.FrameLayout.LayoutParams(-1, -1))
        val standardWidth = minOf(activity.resources.displayMetrics.widthPixels - dp(activity, 48), dp(activity, 520))
        val width = if (mainPage) standardWidth else minOf((standardWidth - dp(activity, 24)).coerceAtLeast(1), maxOf(dp(activity, 280),
            minOf(dp(activity, 360), (standardWidth * .86f).toInt())))
        overlay.addView(frame, android.widget.FrameLayout.LayoutParams(width, -2, Gravity.CENTER))
        frame.isClickable = true
        var stopReveal: (() -> Unit)? = null
        return SettingsUiTheme.contentDialog(activity, overlay) { finish ->
            closing = true
            stopReveal?.invoke()
            overlay.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            SettingsDialogMotion.conceal(frame, scrim, returnTarget?.bounds(), background = glass?.first, complete = finish)
        }.apply {
            setOnShowListener {
                if (outsideCancelable) scrim.setOnClickListener { cancel() }
                if (glass != null) {
                    fun hasSelection(view: View): Boolean = if (view is SettingsSegmentedControl) !view.inlineGlass
                        else view is ViewGroup && (0 until view.childCount).any { hasSelection(view.getChildAt(it)) }
                    val host = if (hasSelection(body)) runCatching {
                        Class.forName("com.kyant.backdrop.catalog.components.LiquidBottomTabsKt", false,
                            SettingsGlassHostView::class.java.classLoader)
                        SettingsGlassHostView(settingsGlassContext(activity))
                    }.getOrElse {
                        android.util.Log.w("TcrrrySettings", "Liquid selection unavailable; retaining native controls", it)
                        null
                    } else null
                    controls = SettingsGlassControls(frame, body, host, glass.second, palette)
                    if (host != null) frame.addView(host, 1, android.widget.FrameLayout.LayoutParams(-1, -1))
                }
                stopReveal = SettingsDialogMotion.reveal(frame, scrim, origin) { ready ->
                    if (glass == null) ready() else glass.second.capture { success ->
                        android.util.Log.i("TcrrrySettings", "glass backdrop capture=$success")
                        if (success) controls?.enable()
                        ready()
                    }
                }
            }
        }
    }

    private fun showChoiceBubble(activity: Activity, heading: String, choices: List<String>, selected: Int,
        choose: (Int) -> Unit) {
        val body = card(activity).apply { addView(title(activity, heading, 20f)) }
        val dialog = bubbleDialog(activity, body, compact = true)
        choices.forEachIndexed { index, name ->
            body.addView(action(activity, name, emphasized = index == selected) {
                dialog.dismiss(); choose(index)
            }, fullMargin(activity, 9))
        }
        dialog.show()
    }

    private fun menuRow(activity: Activity, heading: String, summary: String, click: () -> Unit): View =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(activity, 66)
            setPadding(dp(activity, 16), dp(activity, 8), dp(activity, 16), dp(activity, 8))
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(title(activity, heading, 16f))
                addView(label(activity, summary, 12f, SettingsUiTheme.colors(activity).secondary), fullMargin(activity, 3))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(label(activity, "›", 23f, SettingsUiTheme.colors(activity).muted).apply {
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(dp(activity, 28), dp(activity, 44)))
            background = android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(SettingsUiTheme.colors(activity).selected), null,
                shape(activity, 0xFFFFFFFF.toInt(), 8, 0xFFFFFFFF.toInt()))
            isClickable = true; isFocusable = true
            contentDescription = "$heading，$summary"
            setOnClickListener { SettingsDialogMotion.from(it, click) }
            SettingsGlassMaterial.mark(this, SettingsGlassKind.Button)
        }

    private fun settingSwitch(activity: Activity, heading: String, checked: Boolean,
        enabled: Boolean = true, change: (Boolean) -> Unit): View = android.widget.Switch(activity).apply {
        text = heading
        textSize = 15f
        setTextColor(SettingsUiTheme.colors(activity).text)
        isChecked = checked
        isEnabled = enabled
        minimumHeight = dp(activity, 52)
        setPadding(dp(activity, 4), 0, dp(activity, 4), 0)
        thumbTintList = ColorStateList.valueOf(SettingsUiTheme.colors(activity).text)
        trackTintList = ColorStateList.valueOf(if (checked) SettingsUiTheme.colors(activity).primary
            else SettingsUiTheme.colors(activity).outline)
        SettingsGlassMaterial.mark(this, SettingsGlassKind.Button)
        setOnCheckedChangeListener { _, value ->
            trackTintList = ColorStateList.valueOf(if (value) SettingsUiTheme.colors(activity).primary
                else SettingsUiTheme.colors(activity).outline)
            change(value)
        }
    }

    internal fun binaryChoice(activity: Activity, heading: String, checked: Boolean,
        enabled: Boolean = true, change: (Boolean) -> Unit): TextView {
        var selected = checked
        val colors = SettingsUiTheme.colors(activity)
        val button = TextView(activity).apply {
            textSize = 15f; gravity = Gravity.CENTER; minimumHeight = dp(activity, 48)
            setPadding(dp(activity, 14), dp(activity, 12), dp(activity, 14), dp(activity, 12))
            isClickable = true; isFocusable = true; isEnabled = enabled
            contentDescription = heading
        }
        fun paint() {
            button.text = "$heading：${if (selected) "开启" else "关闭"}"
            button.isSelected = selected
            button.setTextColor(if (selected) colors.primary else colors.text)
            button.background = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(
                if (colors.dark) 0x24FFFFFF else 0x14000000),
                shape(activity, if (selected) colors.selected else colors.raised, 12,
                    if (selected) colors.primary else colors.outline), null)
            if (Build.VERSION.SDK_INT >= 30) button.stateDescription = if (selected) "开启" else "关闭"
        }
        paint()
        button.setOnClickListener { if (button.isEnabled) { selected = !selected; paint(); change(selected) } }
        return button
    }

    private fun segmentedChoice(activity: Activity, heading: String, choices: List<String>,
        selected: Int, choose: (Int) -> Unit): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        addView(title(activity, heading, 15f))
        addView(SettingsSegmentedControl(activity, heading, choices, selected, choose), fullMargin(activity, 10))
    }

    internal fun showMatchingInformation(activity: Activity, id: Long) {
        val body = card(activity).apply {
            addView(title(activity, "当前匹配信息", 20f))
            addView(label(activity, CurrentLyricsSourceStatus.matchingInformation(activity, id), 14f,
                SettingsUiTheme.colors(activity).text).apply { setTextIsSelectable(true) }, fullMargin(activity, 12))
        }
        val dialog = bubbleDialog(activity, body, compact = true)
        body.addView(action(activity, "关闭") { dialog.dismiss() }, fullMargin(activity, 12))
        dialog.show()
    }

    private fun showSourcePicker(activity: Activity, id: Long, selected: String?, refreshPage: () -> Unit) {
        val body = card(activity)
        body.addView(title(activity, "选择歌词源", 20f))
        body.addView(label(activity, "只记住当前歌曲的选择；自动匹配仍是其他歌曲的默认方式", 12f, SettingsUiTheme.colors(activity).secondary))
        val dialog = bubbleDialog(activity, body, compact = true)
        SOURCES.forEach { source ->
            val name = source ?: "自动匹配"
            body.addView(action(activity, name, emphasized = source == selected) {
                CurrentLyricsSourceStatus.selectSource(activity, id, source)
                val started = CurrentLyricsSourceStatus.refresh(id)
                dialog.dismiss()
                toast(activity, if (started) "正在从$name 查找歌词" else "请重新打开歌词页后生效")
                refreshPage()
            }, fullMargin(activity, 9))
        }
        dialog.show()
    }

    private fun showHistory(activity: Activity, id: Long, song: CurrentSongDetails?, refreshPage: () -> Unit) {
        val history = TcrrryLyricsHistory.entries(activity, id).asReversed()
        if (history.isEmpty()) { toast(activity, "还没有匹配历史"); return }
        val body = card(activity).apply {
            addView(title(activity, "匹配历史", 20f))
            addView(label(activity, "${song?.title.orEmpty()} · 选择版本可查看歌词预览", 12f, SettingsUiTheme.colors(activity).secondary))
        }
        val dialog = bubbleDialog(activity, body, compact = true)
        history.forEach { result ->
            body.addView(action(activity, "${result.source} · ${result.title.ifBlank { song?.title.orEmpty() }}") {
                dialog.dismiss()
                val preview = com.tcrrry.lyrics.engine.lyrics.DesktopLyricsTtmlConverter.preview(result,
                    com.tcrrry.lyrics.engine.lyrics.LyricsPreference.hideOpeningCredits(activity))
                val detail = card(activity).apply {
                    addView(title(activity, result.title.ifBlank { song?.title.orEmpty() }, 19f))
                    addView(label(activity,
                        "${result.artist} · ${result.durationMs / 1000} 秒 · " +
                            (if (com.tcrrry.lyrics.engine.lyrics.DesktopLyricsTtmlConverter.hasWordTiming(result)) "逐字" else "逐行") +
                            (if (result.translatedLyrics.isNotBlank()) " · 含平台译文" else "") +
                            (if (result.romanizedLyrics.isNotBlank()) " · 含发音" else ""), 12f, SettingsUiTheme.colors(activity).secondary))
                    addView(label(activity, preview, 13f, SettingsUiTheme.colors(activity).text), fullMargin(activity, 12))
                }
                val detailDialog = bubbleDialog(activity, detail)
                detail.addView(action(activity, "选用此版本", emphasized = true) {
                    TcrrryLyricsHistory.select(activity, id, result)
                    CurrentLyricsSourceStatus.refresh(id)
                    detailDialog.dismiss()
                    refreshPage()
                }, fullMargin(activity, 12))
                detailDialog.show()
            }, fullMargin(activity, 8))
        }
        dialog.show()
    }

    private fun card(activity: Activity) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(activity, 18), dp(activity, 18), dp(activity, 18), dp(activity, 18))
        val colors = SettingsUiTheme.colors(activity)
        val glass = com.tcrrry.lyrics.plugin.PluginLifetime.context.hostVersionCode == 1609L
        val surface = if (glass) colors.surface and 0x00FFFFFF or (0xA6 shl 24) else colors.surface
        background = shape(activity, surface, 18, colors.outline)
        SettingsGlassMaterial.mark(this, SettingsGlassKind.Card)
    }

    private fun title(activity: Activity, value: String, size: Float) = label(activity, value, size, SettingsUiTheme.colors(activity).text).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun label(activity: Activity, value: String, size: Float, color: Int) = TextView(activity).apply {
        text = value
        textSize = size
        setTextColor(color)
        setSingleLine(false)
    }

    private fun chip(activity: Activity, value: String, selected: Boolean = false) = TextView(activity).apply {
        text = value
        textSize = 13f
        gravity = Gravity.CENTER
        setTextColor(if (selected) SettingsUiTheme.colors(activity).primary else SettingsUiTheme.colors(activity).text)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(dp(activity, 12), dp(activity, 8), dp(activity, 12), dp(activity, 8))
        minimumHeight = dp(activity, 46)
        background = shape(activity, if (selected) SettingsUiTheme.colors(activity).selected else SettingsUiTheme.colors(activity).raised, 12, if (selected) SettingsUiTheme.colors(activity).primary else SettingsUiTheme.colors(activity).outline)
        SettingsGlassMaterial.mark(this, SettingsGlassKind.Button, accent = selected)
    }

    /** Equal-width controls keep their full labels and independent touch targets. */
    private fun pairedActions(activity: Activity, first: View, second: View): LinearLayout =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = false
            addView(first, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(activity, 5)
            })
            addView(second, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(activity, 5)
            })
        }

    private fun action(activity: Activity, value: String, emphasized: Boolean = false, click: () -> Unit) =
        chip(activity, value, emphasized).apply {
            minimumHeight = dp(activity, 48)
            if (emphasized) { setTextColor(SettingsUiTheme.colors(activity).onPrimary); background = shape(activity, SettingsUiTheme.colors(activity).filledAccent, 12, SettingsUiTheme.colors(activity).filledAccent) }
            SettingsGlassMaterial.mark(this, SettingsGlassKind.Button, accent = emphasized, filled = emphasized)
            val rippleColor = if (emphasized) 0x33FFFFFF else
                (SettingsUiTheme.colors(activity).primary and 0x00FFFFFF) or (0x24 shl 24)
            background = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(rippleColor), background,
                shape(activity, android.graphics.Color.WHITE, 12, android.graphics.Color.WHITE))
            isClickable = true
            isFocusable = true
            setOnClickListener { SettingsDialogMotion.from(it, click) }
        }

    private fun shape(activity: Activity, color: Int, radius: Int, stroke: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(activity, radius).toFloat()
        setStroke(dp(activity, 1), stroke)
    }

    private fun fullMargin(activity: Activity, top: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(activity, top) }

    private fun formatOffset(value: Int): String = String.format(Locale.US, "%+.1fs", value / 1000f)
    private fun dp(activity: Activity, value: Int) = (activity.resources.displayMetrics.density * value + 0.5f).toInt()
    private fun toast(activity: Activity, message: String) = Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
}
