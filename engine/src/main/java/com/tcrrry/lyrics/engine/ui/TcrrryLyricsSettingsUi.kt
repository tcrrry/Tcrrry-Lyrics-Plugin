package com.tcrrry.lyrics.engine.ui
import com.tcrrry.lyrics.plugin.PluginStorage.pluginCacheDir
import com.tcrrry.lyrics.plugin.PluginStorage.pluginPreferences

import android.app.Activity
import android.app.DownloadManager
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
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.tcrrry.lyrics.engine.CurrentSongDetails
import com.tcrrry.lyrics.engine.model.ModuleSettings
import com.tcrrry.lyrics.engine.hook.LyricsSourceMenuPolicy
import com.tcrrry.lyrics.engine.hook.CurrentLyricsSourceStatus
import com.tcrrry.lyrics.engine.hook.EmbeddedMlKit
import com.tcrrry.lyrics.engine.hook.TcrrryLyricsHistory
import com.tcrrry.lyrics.sources.SecretStorage
import com.tcrrry.lyrics.sources.SupplementTranslation
import com.tcrrry.lyrics.sources.TranslationApiProfiles
import android.text.InputType
import java.util.Locale

/** Independent lyrics controls with the shared host-aware day/night palette. */
internal object TcrrryLyricsSettingsUi {
    private val SOURCES = listOf<String?>(null) + com.tcrrry.lyrics.engine.hook.LyricsSourceMenuPolicy.sources

    fun render(activity: Activity, parent: LinearLayout, song: CurrentSongDetails?, refreshPage: () -> Unit,
        refreshAppearance: () -> Unit, settings: ModuleSettings, onSettingsChanged: (ModuleSettings) -> Unit,
        openMatchingSettings: () -> Unit) {
        val id = song?.appleMusicId ?: 0L
        val selected = CurrentLyricsSourceStatus.selectedSource(activity, id)
        val applied = CurrentLyricsSourceStatus.appliedSource(activity, id)
        val provider = com.tcrrry.lyrics.engine.hook.LyricsSourceMenuPolicy.provider(applied) ?: selected
        var offsetDragging = false
        parent.setBackgroundColor(SettingsUiTheme.colors(activity).background)
        parent.setPadding(dp(activity, 18), dp(activity, 16), dp(activity, 18), dp(activity, 28))

        parent.addView(card(activity).apply {
            addView(title(activity, song?.title ?: "暂无播放歌曲", 19f))
            addView(label(activity, song?.artist.orEmpty(), 13f, SettingsUiTheme.colors(activity).secondary))
            val matchingDisabled = !settings.customLyricsEnabled || !settings.automaticLyricsEnabled
            if (matchingDisabled) {
                addView(label(activity,
                    "自动匹配尚未开启：请在自定义歌词中开启“自定义歌词替换”和“自动实时补全”，保存后完全退出重开应用。",
                    13f, SettingsUiTheme.colors(activity).secondary), fullMargin(activity, 10))
                addView(action(activity, "前往开启歌词匹配", click = openMatchingSettings), fullMargin(activity, 10))
            }
            addView(sourceStatusLabel(activity) { CurrentLyricsSourceStatus.description(activity, id) },
                fullMargin(activity, 12))
            addView(action(activity, "查看当前匹配信息") {
                showMatchingInformation(activity, id)
            }, fullMargin(activity, 10))

            addView(label(activity, "播放页歌词源按钮：单击切换来源（无结果会继续尝试）；双击不执行操作；长按打开本页。", 12f,
                SettingsUiTheme.colors(activity).secondary), fullMargin(activity, 8))
            addView(title(activity, "歌词偏好", 18f), fullMargin(activity, 18))
            val policy = com.tcrrry.lyrics.engine.lyrics.LyricsPreference
            addView(label(activity, "质量优先：Apple Music 原生 → AM++ 适配歌词 → 第三方。逐字优先：优先原生 / AM++ 的真实逐字；否则匹配第三方，逐字权重高于译文、发音。手动选源优先于本设置。", 12f, SettingsUiTheme.colors(activity).secondary))
            addView(action(activity, if (policy.qualityFirst(activity)) "当前：质量优先" else "当前：逐字优先") {
                policy.update(activity, quality = !policy.qualityFirst(activity))
                if (id > 0L) CurrentLyricsSourceStatus.refreshSilently(id)
                toast(activity, "已更新；重新打开歌词页可恢复原生歌词")
                refreshPage()
            }, fullMargin(activity, 10))
            addView(title(activity, "歌词源", 18f), fullMargin(activity, 18))
            addView(label(activity, "默认自动匹配；这里的选择只对当前歌曲记忆。已有匹配可直接切换。", 12f, SettingsUiTheme.colors(activity).secondary))
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(activity, 12), 0, 0)
                addView(chip(activity, selected ?: "自动匹配${provider?.let { " · $it" }.orEmpty()}", selected = true).apply {
                    setOnClickListener {
                        if (id <= 0L) return@setOnClickListener
                        val next = CurrentLyricsSourceStatus.beginSourceCycle(activity, id) ?: return@setOnClickListener
                        val started = CurrentLyricsSourceStatus.refresh(id)
                        if (!started) CurrentLyricsSourceStatus.cancelSourceCycle(id)
                        toast(activity, if (started) "正在尝试$next" else "请重新打开歌词页后生效")
                        refreshPage()
                    }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(chip(activity, "切换来源  ›").apply {
                    setOnClickListener { if (id > 0L) showSourcePicker(activity, id, selected, refreshPage) }
                }, LinearLayout.LayoutParams(dp(activity, 116), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = dp(activity, 8)
                })
            })
            val fixedSource = selected ?: provider
            if (fixedSource != null) addView(action(activity, "当前源选择最佳结果", emphasized = true) {
                CurrentLyricsSourceStatus.retryCurrentSource(activity, id)
                if (id > 0L && CurrentLyricsSourceStatus.refresh(id)) toast(activity, "正在选用当前来源最佳记录")
                else toast(activity, "请重新打开歌词页后生效")
                refreshPage()
            }, fullMargin(activity, 12))
            addView(action(activity, "换一个匹配版本") {
                if (CurrentLyricsSourceStatus.excludeCurrentRecord(activity, id)) {
                    if (CurrentLyricsSourceStatus.refresh(id)) toast(activity, "正在寻找下一个匹配版本")
                    else toast(activity, "请重新打开歌词页后生效")
                } else toast(activity, "请先装入选中的第三方歌词，再换匹配版本")
                refreshPage()
            }, fullMargin(activity, 8))
            addView(label(activity, "固定来源：只在该平台选最佳已知记录，没有记录时搜索；换版本：只跳过当前匹配。", 12f,
                SettingsUiTheme.colors(activity).secondary), fullMargin(activity, 8))
            addView(action(activity, "恢复自动选源") {
                if (id > 0L) {
                    CurrentLyricsSourceStatus.resetMatching(activity, id)
                    CurrentLyricsSourceStatus.refresh(id)
                    refreshPage()
                }
            }, fullMargin(activity, 8))
            addView(label(activity, "自动选源：解除本曲固定来源和跳过记录，按歌词偏好在所有来源中选择。", 12f,
                SettingsUiTheme.colors(activity).secondary), fullMargin(activity, 8))
            addView(action(activity, "匹配历史与预览") { showHistory(activity, id, song, refreshPage) }, fullMargin(activity, 8))
        }, fullMargin(activity, 12))

        parent.addView(glowSettingsCard(activity, settings, onSettingsChanged, refreshPage, id), fullMargin(activity, 12))
        renderTranslationSettings(activity, parent, id, refreshPage)
        val pronunciationPrefs = activity.pluginPreferences("japanese_pronunciation", Context.MODE_PRIVATE)
        val pronunciationEnabled = pronunciationPrefs.getBoolean("enabled", true)
        parent.addView(card(activity).apply {
            addView(title(activity, "离线发音与注音", 18f))
            addView(label(activity, "自带发音优先，只补缺失行。日语、韩语与粤语组件均内置，离线使用，无需下载或代理。", 12f, SettingsUiTheme.colors(activity).secondary), fullMargin(activity, 5))
            addView(chip(activity, if (pronunciationEnabled) "日语注音：开启" else "日语注音：关闭", selected = pronunciationEnabled).apply {
                setOnClickListener {
                    pronunciationPrefs.edit().putBoolean("enabled", !pronunciationEnabled).apply()
                    CurrentLyricsSourceStatus.refreshSilently(id)
                    refreshPage()
                }
            }, fullMargin(activity, 10))

            addView(label(activity, "日语、韩语自动补缺失行；粤语只对手动指定的当前歌曲补粤拼，避免误注普通话。专名、多音字和特殊唱法可能不准确。", 12f, SettingsUiTheme.colors(activity).secondary), fullMargin(activity, 5))
            val korean = pronunciationPrefs.getBoolean("korean_enabled", true)
            addView(chip(activity, if (korean) "韩语注音：开启" else "韩语注音：关闭", selected = korean).apply {
                setOnClickListener {
                    pronunciationPrefs.edit().putBoolean("korean_enabled", !korean).apply()
                    CurrentLyricsSourceStatus.refreshSilently(id); refreshPage()
                }
            }, fullMargin(activity, 10))
            val cantonese = pronunciationPrefs.getBoolean("cantonese_$id", false)
            addView(chip(activity, if (cantonese) "当前歌曲粤语注音：开" else "当前歌曲粤语注音：关", selected = cantonese).apply {
                isEnabled = id > 0L
                setOnClickListener {
                    if (id <= 0L) return@setOnClickListener
                    pronunciationPrefs.edit().putBoolean("cantonese_$id", !cantonese).apply()
                    CurrentLyricsSourceStatus.refreshSilently(id); refreshPage()
                }
            }, fullMargin(activity, 10))
            addView(label(activity, "韩语：Unicode CLDR（Unicode-3.0）；粤语：Rime / CanCLID 粤拼字词典（CC-BY-4.0）。许可和来源随包保留。", 11f, SettingsUiTheme.colors(activity).secondary), fullMargin(activity, 7))
        }, fullMargin(activity, 12))

        parent.addView(card(activity).apply {
            addView(LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(title(activity, "歌词偏移", 18f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                val value = label(activity, formatOffset(provider?.let { CurrentLyricsSourceStatus.offsetMs(activity, id, it) } ?: 0), 13f, SettingsUiTheme.colors(activity).text)
                addView(value)
                addView(View(activity), LinearLayout.LayoutParams(0, 1))
                val bar = offsetSlider(activity, id, provider, value) { offsetDragging = it }
                // Keep the value beside the title while the slider gets its own row.
                setTag(bar)
            })
            val slider = (getChildAt(childCount - 1) as LinearLayout).tag as SeekBar
            addView(slider, fullMargin(activity, 8))
            addView(LinearLayout(activity).apply {
                addView(label(activity, "延后 5 秒", 11f, SettingsUiTheme.colors(activity).muted), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(label(activity, "提前 5 秒", 11f, SettingsUiTheme.colors(activity).muted))
            })
            addView(label(activity, "每首歌、每个歌词源独立保存。正数让歌词更早显示。", 12f, SettingsUiTheme.colors(activity).secondary), fullMargin(activity, 8))
            addView(action(activity, "重置当前来源偏移") {
                if (id > 0L && provider != null) {
                    CurrentLyricsSourceStatus.setOffsetMs(activity, id, provider, 0)
                    slider.progress = 50
                    CurrentLyricsSourceStatus.refreshSilently(id)
                }
            }, fullMargin(activity, 10))
        }, fullMargin(activity, 12))

        parent.addView(card(activity).apply {
            addView(action(activity, "设置 Apple Music 链接打开方式") {
                val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS else Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                runCatching { activity.startActivity(Intent(action, Uri.parse("package:${activity.packageName}"))) }
                    .onFailure { toast(activity, "无法打开系统链接设置") }
            })
        }, fullMargin(activity, 12))

        parent.addView(SettingsUiTheme.appearanceCard(activity, refreshAppearance), fullMargin(activity, 12))

        prepareRenderPresentation(parent)
        val initial = CurrentLyricsSourceStatus.description(activity, id)
        val initialTranslation = CurrentLyricsSourceStatus.translationStatus(activity, id)
        parent.postDelayed(object : Runnable {
            override fun run() {
                if (!parent.isAttachedToWindow) return
                if (CurrentLyricsSourceStatus.selectedSource(activity, id) != selected ||
                    CurrentLyricsSourceStatus.description(activity, id) != initial ||
                    CurrentLyricsSourceStatus.translationStatus(activity, id) != initialTranslation) {
                    if (offsetDragging) parent.postDelayed(this, 1_000L) else refreshPage()
                }
                else parent.postDelayed(this, 1_000L)
            }
        }, 1_000L)
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
            addView(SeekBar(activity).apply {
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
            }, fullMargin(activity, 4))
            addView(label(activity, "50%～500%，越高越容易触发；100% 保留默认门槛。", 12f,
                SettingsUiTheme.colors(activity).secondary), fullMargin(activity, 4))
            addView(action(activity, "恢复默认灵敏度") {
                save(current.copy(lyricGlowSensitivity = 100)); refreshPage()
            }, fullMargin(activity, 8))
            val position = action(activity, "辉光触发位置：${current.lyricGlowPosition.displayName}") {}
            position.setOnClickListener {
                val choices = com.tcrrry.lyrics.engine.model.LyricGlowPosition.entries
                val body = card(activity).apply { addView(title(activity, "辉光触发位置", 20f)) }
                val picker = bubbleDialog(activity, body)
                choices.forEach { choice ->
                    body.addView(action(activity, choice.displayName, emphasized = choice == current.lyricGlowPosition) {
                        save(current.copy(lyricGlowPosition = choice))
                        position.text = "辉光触发位置：${choice.displayName}"
                        picker.dismiss()
                    }, fullMargin(activity, 9))
                }
                picker.show()
            }
            addView(position, fullMargin(activity, 8))
            addView(label(activity, "句尾优先会减少句中触发；仅句尾按歌词位置判断，不识别人声。", 12f,
                SettingsUiTheme.colors(activity).secondary), fullMargin(activity, 7))
            val policy = com.tcrrry.lyrics.engine.lyrics.LyricsPreference
            val smooth = policy.smoothShortUnits(activity)
            addView(chip(activity, "短单元平滑：" + if (smooth) "开启" else "关闭", selected = smooth).apply {
                minimumHeight = dp(activity, 48)
                isClickable = true; isFocusable = true
                setOnClickListener {
                    policy.update(activity, smooth = !smooth)
                    if (songId > 0L) CurrentLyricsSourceStatus.refreshSilently(songId)
                    refreshPage()
                }
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

    /** Status is informational; it updates itself without a click action. */
    internal fun sourceStatusLabel(activity: Activity, description: () -> String): TextView =
        chip(activity, description(), selected = true).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            contentDescription = description()
            isClickable = false
            isLongClickable = false
            isFocusable = false
            isSoundEffectsEnabled = false
            isHapticFeedbackEnabled = false
            val label = this
            val update = object : Runnable {
                override fun run() {
                    if (!label.isAttachedToWindow) return
                    val next = description()
                    if (label.text.toString() != next) { label.text = next; label.contentDescription = next }
                    label.postDelayed(this, 500L)
                }
            }
            addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) { label.post(update) }
                override fun onViewDetachedFromWindow(v: View) { label.removeCallbacks(update) }
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
        val mode = prefs.getString("mode", "off") ?: "off"
        parent.addView(card(activity).apply {
            addView(title(activity, "补充翻译", 18f))
            addView(label(activity, "平台译文优先，仅对缺少译文的句子补译。", 12f, SettingsUiTheme.colors(activity).secondary), fullMargin(activity, 5))
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(activity, 12), 0, 0)
                listOf("off" to "关闭", "offline" to "离线机翻", "api" to "自定义 API").forEachIndexed { index, (value, caption) ->
                    addView(chip(activity, caption, selected = mode == value).apply {
                        setOnClickListener {
                            if (mode != value) {
                                prefs.edit().putString("mode", value).apply()
                                CurrentLyricsSourceStatus.refresh(id)
                                refreshPage()
                            }
                        }
                    }, LinearLayout.LayoutParams(0, dp(activity, 43), 1f).apply {
                        if (index > 0) marginStart = dp(activity, 5)
                    })
                }
            })
            if (mode == "offline") renderOfflineControls(activity, this, id)
            if (mode == "api") renderApiControls(activity, this, id, refreshPage)
            CurrentLyricsSourceStatus.translationStatus(activity, id).takeIf(String::isNotBlank)?.let { status ->
                addView(label(activity, status, 12f, SettingsUiTheme.colors(activity).secondary), fullMargin(activity, 12))
            }
            addView(action(activity, "清理补充译文缓存") {
                val folder = java.io.File(activity.pluginCacheDir, "translations")
                folder.listFiles()?.filter { it.isFile }?.forEach { it.delete() }
                toast(activity, "补充译文缓存已清理")
                CurrentLyricsSourceStatus.refresh(id)
            }, fullMargin(activity, 10))
        }, fullMargin(activity, 12))
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
            secret.save("")
            key.setText("")
            status.text = "已删除当前服务的密钥"
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
        val dialog = bubbleDialog(activity, body)
        body.addView(action(activity, "重命名") {
            dialog.dismiss()
            val input = field(activity, "服务名称", profile.label)
            SettingsUiTheme.dialogBuilder(activity).setTitle("重命名 API 服务").setView(input)
                .setPositiveButton("保存") { _, _ ->
                    TranslationApiProfiles.rename(activity, profileId, input.text.toString())
                    refreshPage()
                }.setNegativeButton("取消", null).show()
        }, fullMargin(activity, 10))
        body.addView(action(activity, "删除此服务及其密钥") {
            dialog.dismiss()
            SettingsUiTheme.dialogBuilder(activity).setTitle("删除 API 配置")
                .setMessage("将删除 ${profile.label} 的地址、模型和本机密钥。")
                .setPositiveButton("删除") { _, _ ->
                    TranslationApiProfiles.remove(activity, profileId)
                    refreshPage()
                }.setNegativeButton("取消", null).show()
        }, fullMargin(activity, 8))
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    private fun showApiProfilePicker(activity: Activity, id: Long, refreshPage: () -> Unit) {
        val body = card(activity)
        body.addView(title(activity, "选择翻译 API", 19f))
        val prefs = activity.pluginPreferences("supplement_translation", Context.MODE_PRIVATE)
        val dialog = bubbleDialog(activity, body)
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
            val input = field(activity, "服务名称")
            SettingsUiTheme.dialogBuilder(activity).setTitle("添加 API 服务").setView(input)
                .setPositiveButton("添加") { _, _ ->
                    val added = TranslationApiProfiles.add(activity, input.text.toString())
                    prefs.edit().putString("active_api_profile", added.id).apply()
                    refreshPage()
                }.setNegativeButton("取消", null).show()
        }, fullMargin(activity, 10))
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    private fun renderOfflineControls(activity: Activity, card: LinearLayout, id: Long) {
        card.addView(label(activity, "离线机翻使用单独的 Tcrrry 离线翻译 APK，模型与下载由伴随应用管理；插件本体无需它即可匹配平台译文、使用 API 翻译和离线注音。",12f,SettingsUiTheme.colors(activity).secondary),fullMargin(activity,8))
        val prefs=activity.pluginPreferences("supplement_translation",Context.MODE_PRIVATE)
        val codes=listOf("auto") + com.tcrrry.lyrics.plugin.OfflineTranslationClient.languages
        card.addView(action(activity,"原语言："+prefs.getString("offline_source_language","auto")) {
            showChoiceBubble(activity,"选择原语言",codes.map { if(it=="auto") "自动判断" else Locale.forLanguageTag(it).getDisplayLanguage(Locale.SIMPLIFIED_CHINESE) }, codes.indexOf(prefs.getString("offline_source_language","auto"))) { which ->
                prefs.edit().putString("offline_source_language",codes[which]).apply();CurrentLyricsSourceStatus.refresh(id)
            }
        },fullMargin(activity,8))
        card.addView(action(activity,"打开离线翻译／语言包管理") {
            com.tcrrry.lyrics.plugin.OfflineTranslationClient.openManager(activity)
        },fullMargin(activity,8))
        val state=label(activity,if(com.tcrrry.lyrics.plugin.OfflineTranslationClient.installed(activity)) "伴随应用已安装" else "尚未安装伴随应用",12f,SettingsUiTheme.colors(activity).secondary)
        card.addView(state,fullMargin(activity,8))
        card.addView(action(activity,"验证离线翻译") {
            state.text="正在验证…"
            Thread {
                val result=runCatching { com.tcrrry.lyrics.plugin.OfflineTranslationClient.translate(activity,"en","Hello, world") }
                com.tcrrry.lyrics.plugin.PluginHandler().post {
                    if(!activity.isFinishing) state.text=result.fold({"离线翻译可用：$it"},{"离线翻译失败：${it.message}"})
                }
            }.start()
        },fullMargin(activity,8))
    }

    private fun field(activity: Activity, hint: String, value: String = "") = EditText(activity).apply {
        this.hint = hint
        setText(value)
        setTextColor(SettingsUiTheme.colors(activity).text)
        setHintTextColor(SettingsUiTheme.colors(activity).muted)
        textSize = 14f
        setSingleLine(true)
        setPadding(dp(activity, 14), dp(activity, 9), dp(activity, 14), dp(activity, 9))
        background = shape(activity, SettingsUiTheme.colors(activity).raised, 12, SettingsUiTheme.colors(activity).outline)
    }

    internal fun bubbleDialog(activity: Activity, body: LinearLayout): android.app.AlertDialog {
        val scroll = object : android.widget.ScrollView(activity) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val cap = (resources.displayMetrics.heightPixels * 0.82f).toInt()
                val available = if (View.MeasureSpec.getMode(heightMeasureSpec) == View.MeasureSpec.UNSPECIFIED) cap
                    else minOf(cap, View.MeasureSpec.getSize(heightMeasureSpec))
                super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(available, View.MeasureSpec.AT_MOST))
            }
        }.apply { addView(body) }
        return SettingsUiTheme.dialogBuilder(activity).setView(scroll).create().apply {
            window?.setBackgroundDrawableResource(android.R.color.transparent)
        }
    }

    private fun showChoiceBubble(activity: Activity, heading: String, choices: List<String>, selected: Int,
        choose: (Int) -> Unit) {
        val body = card(activity).apply { addView(title(activity, heading, 20f)) }
        val dialog = bubbleDialog(activity, body)
        choices.forEachIndexed { index, name ->
            body.addView(action(activity, name, emphasized = index == selected) {
                dialog.dismiss(); choose(index)
            }, fullMargin(activity, 9))
        }
        dialog.show()
    }

    internal fun showMatchingInformation(activity: Activity, id: Long) {
        val body = card(activity).apply {
            addView(title(activity, "当前匹配信息", 20f))
            addView(label(activity, CurrentLyricsSourceStatus.matchingInformation(activity, id), 14f,
                SettingsUiTheme.colors(activity).text).apply { setTextIsSelectable(true) }, fullMargin(activity, 12))
        }
        val dialog = bubbleDialog(activity, body)
        body.addView(action(activity, "关闭") { dialog.dismiss() }, fullMargin(activity, 12))
        dialog.show()
    }

    private fun showSourcePicker(activity: Activity, id: Long, selected: String?, refreshPage: () -> Unit) {
        val body = card(activity)
        body.addView(title(activity, "选择歌词源", 20f))
        body.addView(label(activity, "只记住当前歌曲的选择；自动匹配仍是其他歌曲的默认方式", 12f, SettingsUiTheme.colors(activity).secondary))
        val dialog = bubbleDialog(activity, body)
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
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    private fun showHistory(activity: Activity, id: Long, song: CurrentSongDetails?, refreshPage: () -> Unit) {
        val history = TcrrryLyricsHistory.entries(activity, id).asReversed()
        if (history.isEmpty()) { toast(activity, "还没有匹配历史"); return }
        val body = card(activity).apply {
            addView(title(activity, "匹配历史", 20f))
            addView(label(activity, "${song?.title.orEmpty()} · 选择版本可查看歌词预览", 12f, SettingsUiTheme.colors(activity).secondary))
        }
        val dialog = bubbleDialog(activity, body)
        history.forEach { result ->
            body.addView(action(activity, "${result.source} · ${result.title.ifBlank { song?.title.orEmpty() }}") {
                dialog.dismiss()
                val preview = result.lyrics.lineSequence().take(16).joinToString("\n")
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
                detailDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            }, fullMargin(activity, 8))
        }
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    private fun card(activity: Activity) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(activity, 18), dp(activity, 18), dp(activity, 18), dp(activity, 18))
        background = shape(activity, SettingsUiTheme.colors(activity).surface, 18, SettingsUiTheme.colors(activity).outline)
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
    }

    private fun action(activity: Activity, value: String, emphasized: Boolean = false, click: () -> Unit) =
        chip(activity, value, emphasized).apply {
            minimumHeight = dp(activity, 48)
            if (emphasized) { setTextColor(SettingsUiTheme.colors(activity).onPrimary); background = shape(activity, SettingsUiTheme.colors(activity).filledAccent, 12, SettingsUiTheme.colors(activity).filledAccent) }
            isClickable = true
            isFocusable = true
            setOnClickListener { click() }
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
