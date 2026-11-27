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
                showSourceSettings(activity, id, song, refreshPage)
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
        parent.addView(menuRow(activity, "更多工具", "睡眠定时、歌单导出、封面修复与外观") {
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
            val colors = SettingsUiTheme.colors(activity)
            val policy = com.tcrrry.lyrics.engine.lyrics.LyricsPreference
            val replacementEnabled = policy.lyricsReplacementEnabled(activity)
            val controlsActive = replacementEnabled && id > 0L
            val selected = if (id > 0L) CurrentLyricsSourceStatus.selectedSource(activity, id) else null
            val provider = if (id > 0L) LyricsSourceMenuPolicy.provider(CurrentLyricsSourceStatus.appliedSource(activity, id)) ?: selected else null
            body.removeAllViews()
            body.addView(title(activity, "当前歌曲歌词源", 20f))
            body.addView(label(activity, if (id > 0L) "${song?.title.orEmpty()} · ${song?.artist.orEmpty()}" else "暂无播放歌曲", 13f,
                colors.secondary), fullMargin(activity, 5))
            body.addView(sourceStatusLabel(activity, { CurrentLyricsSourceStatus.description(activity, id) }, expanded = true), fullMargin(activity, 14))
            body.addView(menuRow(activity, "选择歌词源", when {
                !replacementEnabled -> "已关闭插件歌词源修改"
                id <= 0L -> "暂无正在播放的歌曲"
                else -> selected ?: "自动匹配"
            }) {
                showSourcePicker(activity, id, selected, changed)
            }.apply {
                isEnabled = controlsActive
                alpha = if (controlsActive) 1f else .45f
            }, fullMargin(activity, 16))
            body.addView(menuRow(activity, "自动匹配来源顺序",
                policy.sourceOrder(activity).joinToString(" › ")) {
                showSourceOrderEditor(activity, id, changed)
            }.apply {
                isEnabled = replacementEnabled
                alpha = if (replacementEnabled) 1f else .45f
            }, fullMargin(activity, 10))
            val nextSource = action(activity, "下一个来源") {
                val next = CurrentLyricsSourceStatus.beginSourceCycle(activity, id)
                if (next != null) {
                    val started = CurrentLyricsSourceStatus.refresh(id)
                    if (!started) CurrentLyricsSourceStatus.cancelSourceCycle(id)
                    toast(activity, if (started) "正在尝试$next" else "请重新打开歌词页后生效")
                }
                changed()
            }.apply { isEnabled = controlsActive; alpha = if (isEnabled) 1f else .45f }
            val nextVersion = action(activity, "同源下一版") {
                if (CurrentLyricsSourceStatus.excludeCurrentRecord(activity, id)) {
                    toast(activity, if (CurrentLyricsSourceStatus.refresh(id)) "正在寻找下一个匹配版本" else "请重新打开歌词页后生效")
                } else toast(activity, "请先装入选中的第三方歌词，再换匹配版本")
                changed()
            }.apply { isEnabled = controlsActive; alpha = if (isEnabled) 1f else .45f }
            body.addView(pairedActions(activity, nextSource, nextVersion), fullMargin(activity, 10))
            val best = action(activity, "当前源最佳") {
                CurrentLyricsSourceStatus.retryCurrentSource(activity, id)
                toast(activity, if (CurrentLyricsSourceStatus.refresh(id)) "正在选用当前来源最佳记录" else "请重新打开歌词页后生效")
                changed()
            }.apply { isEnabled = controlsActive && (selected != null || provider != null); alpha = if (isEnabled) 1f else .45f }
            val automatic = action(activity, "自动选择源") {
                CurrentLyricsSourceStatus.resetMatching(activity, id)
                CurrentLyricsSourceStatus.refresh(id)
                changed()
            }.apply { isEnabled = controlsActive; alpha = if (isEnabled) 1f else .45f }
            body.addView(pairedActions(activity, best, automatic), fullMargin(activity, 10))
            body.addView(menuRow(activity, "匹配记录与预览", "查看历史结果，或手动选用歌词") {
                showHistory(activity, id, song, changed)
            }.apply { isEnabled = controlsActive; alpha = if (isEnabled) 1f else .45f }, fullMargin(activity, 12))
            val offsetValue = label(activity, formatOffset(provider?.let { CurrentLyricsSourceStatus.offsetMs(activity, id, it) } ?: 0),
                14f, colors.text)
            body.addView(LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(title(activity, "歌词偏移", 16f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(offsetValue)
            }, fullMargin(activity, 20))
            val slider = offsetSlider(activity, id, provider, offsetValue).apply {
                if (!controlsActive) {
                    isEnabled = false
                    alpha = .45f
                }
            }
            body.addView(slider, fullMargin(activity, 10))
            body.addView(action(activity, "重置偏移") {
                if (provider != null) {
                    CurrentLyricsSourceStatus.setOffsetMs(activity, id, provider, 0)
                    slider.progress = 50
                    CurrentLyricsSourceStatus.refreshSilently(id)
                }
            }.apply { isEnabled = slider.isEnabled; alpha = if (isEnabled) 1f else .45f }, fullMargin(activity, 8))
            body.addView(reloadAuxiliariesAction(activity, id, changed).apply {
                if (!controlsActive) {
                    isEnabled = false
                    alpha = .45f
                }
            }, fullMargin(activity, 14))
            val masterFill = when {
                replacementEnabled && colors.dark -> 0x34E8345C
                replacementEnabled -> 0xD6FCE8EC.toInt()
                colors.dark -> 0x1EFFFFFF
                else -> 0xCCFFFFFF.toInt()
            }
            val masterStroke = when {
                replacementEnabled && colors.dark -> 0x80FF8BA1.toInt()
                replacementEnabled -> colors.primary
                colors.dark -> 0x38FFFFFF
                else -> 0x22000000
            }
            body.addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(activity, 12), dp(activity, 12), dp(activity, 12), dp(activity, 12))
                background = shape(activity, masterFill, 16, masterStroke)
                SettingsGlassMaterial.mark(this, SettingsGlassKind.Card, accent = replacementEnabled)
                addView(binaryChoice(activity, "插件歌词源修改", replacementEnabled) { enabled ->
                    policy.updateLyricsReplacementEnabled(activity, enabled)
                    if (id > 0L) CurrentLyricsSourceStatus.refresh(id)
                    toast(activity, if (enabled) "已开启插件歌词源修改" else "已关闭插件歌词源修改，将保留 Apple Music 原生歌词")
                    changed()
                })
                addView(label(
                    activity,
                    if (replacementEnabled) "开启后可自动匹配与切换第三方歌词源；关闭后将保留 Apple Music 原生歌词，其余增强功能不受影响"
                    else "当前已关闭，插件不再拦截或替换歌词，仅保留 Apple Music 原生歌词，其余增强功能正常使用",
                    12f,
                    if (replacementEnabled) colors.text else colors.secondary,
                ), fullMargin(activity, 8))
            }, fullMargin(activity, 18))
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
            body.addView(menuRow(activity, "自动匹配来源顺序",
                policy.sourceOrder(activity).joinToString(" › ")) {
                showSourceOrderEditor(activity, id) {
                    refreshPage()
                    redraw()
                }
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
            body.addView(reloadAuxiliariesAction(activity, id, refreshPage), fullMargin(activity, 14))
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
        body.addView(binaryChoice(activity, "优化单曲循环覆盖问题", com.tcrrry.lyrics.plugin.PlaybackModeOptimization.enabled(activity)) { enabled ->
            com.tcrrry.lyrics.plugin.PlaybackModeOptimization.setEnabled(activity, enabled)
        }, fullMargin(activity, 14))

        val tools1626Ready = com.tcrrry.lyrics.plugin.MoreToolsCoordinator.is1626Supported()
        body.addView(binaryChoice(
            activity,
            "强化睡眠定时",
            com.tcrrry.lyrics.plugin.MoreToolsCoordinator.sleepTimerEnabled(activity),
            enabled = tools1626Ready,
        ) { enabled ->
            com.tcrrry.lyrics.plugin.MoreToolsCoordinator.setSleepTimerEnabled(activity, enabled)
        }, fullMargin(activity, 14))

        body.addView(binaryChoice(
            activity,
            "自定义歌单封面修复",
            com.tcrrry.lyrics.plugin.MoreToolsCoordinator.coverFixEnabled(activity),
            enabled = tools1626Ready,
        ) { enabled ->
            com.tcrrry.lyrics.plugin.MoreToolsCoordinator.setCoverFixEnabled(activity, enabled)
        }, fullMargin(activity, 14))

        val kifRow = menuRow(activity, "Kifranei 媒体插件（移植）", com.tcrrry.lyrics.plugin.MoreToolsCoordinator.kifraneiMenuSummary(activity)) {}
        var kifSummaryView: TextView? = null
        run {
            val leftCol = (kifRow as? ViewGroup)?.getChildAt(0) as? ViewGroup
            kifSummaryView = leftCol?.getChildAt(1) as? TextView
        }
        kifRow.setOnClickListener { view ->
            SettingsDialogMotion.from(view) {
                showKifraneiSettings(activity) {
                    val summary = com.tcrrry.lyrics.plugin.MoreToolsCoordinator.kifraneiMenuSummary(activity)
                    kifSummaryView?.text = summary
                    kifRow.contentDescription = "Kifranei 媒体插件（移植），$summary"
                }
            }
        }
        body.addView(kifRow, fullMargin(activity, 14))

        val autoRow = menuRow(activity, "智能混音（自动过渡）", com.tcrrry.lyrics.plugin.MoreToolsCoordinator.autoTransitionMenuSummary(activity)) {}
        var autoSummaryView: TextView? = null
        run {
            val leftCol = (autoRow as? ViewGroup)?.getChildAt(0) as? ViewGroup
            autoSummaryView = leftCol?.getChildAt(1) as? TextView
        }
        autoRow.setOnClickListener { view ->
            SettingsDialogMotion.from(view) {
                showAutoTransitionSettings(activity) {
                    val summary = com.tcrrry.lyrics.plugin.MoreToolsCoordinator.autoTransitionMenuSummary(activity)
                    autoSummaryView?.text = summary
                    autoRow.contentDescription = "智能混音（自动过渡），$summary"
                }
            }
        }
        body.addView(autoRow, fullMargin(activity, 14))

        val speedRow = menuRow(activity, "长按倍速播放", com.tcrrry.lyrics.plugin.MoreToolsCoordinator.playbackSpeedMenuSummary(activity)) {}
        var speedSummaryView: TextView? = null
        run {
            val leftCol = (speedRow as? ViewGroup)?.getChildAt(0) as? ViewGroup
            speedSummaryView = leftCol?.getChildAt(1) as? TextView
        }
        speedRow.setOnClickListener { view ->
            SettingsDialogMotion.from(view) {
                showPlaybackSpeedSettings(activity) {
                    val summary = com.tcrrry.lyrics.plugin.MoreToolsCoordinator.playbackSpeedMenuSummary(activity)
                    speedSummaryView?.text = summary
                    speedRow.contentDescription = "长按倍速播放，$summary"
                }
            }
        }
        body.addView(speedRow, fullMargin(activity, 14))

        val exportRow = menuRow(activity, "导出播放列表", com.tcrrry.lyrics.plugin.MoreToolsCoordinator.exportMenuSummary(activity)) {}
        var exportSummaryView: TextView? = null
        run {
            val leftCol = (exportRow as? ViewGroup)?.getChildAt(0) as? ViewGroup
            exportSummaryView = leftCol?.getChildAt(1) as? TextView
        }
        exportRow.setOnClickListener { view ->
            SettingsDialogMotion.from(view) {
                showPlaylistExportSettings(activity) {
                    exportSummaryView?.text = com.tcrrry.lyrics.plugin.MoreToolsCoordinator.exportMenuSummary(activity)
                }
            }
        }
        body.addView(exportRow, fullMargin(activity, 14))

        val logRow = menuRow(activity, "诊断日志", com.tcrrry.lyrics.plugin.MoreToolsCoordinator.diagnosticLogMenuSummary(activity)) {}
        var logSummaryView: TextView? = null
        run {
            val leftCol = (logRow as? ViewGroup)?.getChildAt(0) as? ViewGroup
            logSummaryView = leftCol?.getChildAt(1) as? TextView
        }
        logRow.setOnClickListener { view ->
            SettingsDialogMotion.from(view) {
                showDiagnosticLogDialog(activity) {
                    val summary = com.tcrrry.lyrics.plugin.MoreToolsCoordinator.diagnosticLogMenuSummary(activity)
                    logSummaryView?.text = summary
                    logRow.contentDescription = "诊断日志，$summary"
                }
            }
        }
        body.addView(logRow, fullMargin(activity, 14))

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

    internal fun showKifraneiSettings(activity: Activity, onChanged: () -> Unit = {}) {
        val coordinator = com.tcrrry.lyrics.plugin.MoreToolsCoordinator
        val colors = SettingsUiTheme.colors(activity)
        val kifReady = coordinator.isKifraneiSupported()
        val oplus = coordinator.isOplusRom()
        val initialCfg = coordinator.kifraneiConfig(activity)
        val initialSnap = coordinator.kifraneiConflictSnapshot(activity)
        val body = card(activity).apply { addView(title(activity, "Kifranei 媒体插件（移植）", 20f)) }

        if (!kifReady) {
            body.addView(
                label(activity, "当前 Apple Music 版本暂未适配 Kifranei 媒体增强（不影响歌词功能）", 12.5f, colors.secondary),
                fullMargin(activity, 8),
            )
        }

        if (initialSnap.standaloneEnabled) {
            val conflictMsg = if (initialSnap.parseFallbackAll) {
                "已检测到独立版 AM 媒体增强插件已启用；因未能读取其设置，已保守避让全部 9 项内置移植功能以防止宿主冲突阻止"
            } else {
                "已检测到独立版 AM 媒体增强插件已启用：仅自动避让对方正在使用的 ${initialSnap.yielded.size} 项（如播放页玻璃与资源下载可分别共存）"
            }
            val banner = label(activity, conflictMsg, 12.5f, colors.primary).apply {
                setPadding(dp(activity, 14), dp(activity, 10), dp(activity, 14), dp(activity, 10))
                background = shape(activity, colors.selected, 14, colors.primary)
                SettingsGlassMaterial.mark(this, SettingsGlassKind.Card, accent = true)
            }
            body.addView(banner, fullMargin(activity, 10))
        }

        lateinit var playerGlassToggle: TextView
        lateinit var popupStyleRow: View
        lateinit var popupStyleNote: TextView
        lateinit var playerHandleToggle: TextView
        lateinit var systemCornersToggle: TextView
        lateinit var volumeBarToggle: TextView
        lateinit var iosControlsToggle: TextView
        lateinit var sharingToggle: TextView
        lateinit var downloadsToggle: TextView

        fun setBinaryEnabled(view: TextView, enabled: Boolean) {
            view.isEnabled = enabled
            view.alpha = if (enabled) 1f else .45f
        }

        fun setSegmentedEnabled(row: View, enabled: Boolean) {
            row.alpha = if (enabled) 1f else .45f
            val group = (row as? ViewGroup)?.let { vg ->
                (0 until vg.childCount).firstNotNullOfOrNull { idx -> vg.getChildAt(idx) as? SettingsSegmentedControl }
            }
            group?.isEnabled = enabled
        }

        fun refreshControls() {
            val cfg = coordinator.kifraneiConfig(activity)
            val snap = coordinator.kifraneiConflictSnapshot(activity)
            val subActive = kifReady && cfg.masterEnabled
            setBinaryEnabled(playerGlassToggle, subActive && !snap.isYielded(dev.kifranei.ampp.media.KifraneiCapability.PLAYER_GLASS))
            setSegmentedEnabled(popupStyleRow, subActive && cfg.playerGlass && !snap.isYielded(dev.kifranei.ampp.media.KifraneiCapability.PLAYER_GLASS))
            popupStyleNote.alpha = if (subActive && cfg.playerGlass) 1f else .55f
            setBinaryEnabled(playerHandleToggle, subActive && !snap.isYielded(dev.kifranei.ampp.media.KifraneiCapability.PLAYER_CHROME))
            setBinaryEnabled(systemCornersToggle, subActive && !snap.isYielded(dev.kifranei.ampp.media.KifraneiCapability.PLAYER_CHROME))
            setBinaryEnabled(volumeBarToggle, subActive && !oplus && !snap.isYielded(dev.kifranei.ampp.media.KifraneiCapability.VOLUME_BAR))
            setBinaryEnabled(iosControlsToggle, subActive && !snap.isYielded(dev.kifranei.ampp.media.KifraneiCapability.IOS_CONTROLS))
            setBinaryEnabled(sharingToggle, subActive && !snap.isYielded(dev.kifranei.ampp.media.KifraneiCapability.SHARING))
            setBinaryEnabled(downloadsToggle, subActive && !snap.isYielded(dev.kifranei.ampp.media.KifraneiCapability.CONTENT_DOWNLOADS))
        }

        body.addView(binaryChoice(
            activity,
            "启用 Kifranei 媒体增强",
            initialCfg.masterEnabled,
            enabled = kifReady,
        ) { enabled ->
            coordinator.updateKifraneiConfig(activity) { copy(masterEnabled = enabled) }
            refreshControls()
            onChanged()
        }, fullMargin(activity, 12))

        body.addView(title(activity, "播放页外观与液态玻璃", 16f), fullMargin(activity, 16))

        val glassYielded = initialSnap.isYielded(dev.kifranei.ampp.media.KifraneiCapability.PLAYER_GLASS)
        playerGlassToggle = binaryChoice(
            activity,
            "播放页液态玻璃",
            initialCfg.playerGlass,
        ) { enabled ->
            coordinator.updateKifraneiConfig(activity) { copy(playerGlass = enabled) }
            refreshControls()
            onChanged()
        }
        body.addView(playerGlassToggle, fullMargin(activity, 10))
        if (glassYielded) {
            body.addView(
                label(activity, "独立版 K 插件已开启此项：播放页已自动让位，歌单菜单与排序弹窗继续保留液态玻璃", 12f, colors.secondary),
                fullMargin(activity, 4),
            )
        }

        val styles = listOf(
            dev.kifranei.ampp.media.PopupGlassStyle.BOTTOM_BAR,
            dev.kifranei.ampp.media.PopupGlassStyle.MORE_MENU,
            dev.kifranei.ampp.media.PopupGlassStyle.NO_FROST,
        )
        popupStyleRow = segmentedChoice(
            activity,
            "弹窗液态玻璃样式",
            listOf("同底栏", "同更多菜单", "无磨砂"),
            styles.indexOf(initialCfg.popupGlassStyle).coerceAtLeast(0),
            inlineGlass = true,
        ) { which ->
            val picked = styles.getOrElse(which) { dev.kifranei.ampp.media.PopupGlassStyle.BOTTOM_BAR }
            coordinator.updateKifraneiConfig(activity) { copy(popupGlassStyle = picked) }
            toast(activity, "弹窗样式已设为「${listOf("同底栏", "同更多菜单", "无磨砂").getOrElse(which) { "同底栏" }}」")
        }
        body.addView(popupStyleRow, fullMargin(activity, 12))
        popupStyleNote = label(
            activity,
            if (glassYielded) "当前已同步独立版 K 插件的弹窗磨砂样式"
            else "作用于睡眠定时、排序、删除确认与音质弹窗；更多菜单保持独立效果",
            12f,
            colors.secondary,
        )
        body.addView(popupStyleNote, fullMargin(activity, 4))

        val chromeYielded = initialSnap.isYielded(dev.kifranei.ampp.media.KifraneiCapability.PLAYER_CHROME)
        playerHandleToggle = binaryChoice(activity, "播放页顶部把手", initialCfg.playerHandle) { enabled ->
            coordinator.updateKifraneiConfig(activity) { copy(playerHandle = enabled) }
        }
        body.addView(playerHandleToggle, fullMargin(activity, 10))

        systemCornersToggle = binaryChoice(activity, "播放页系统圆角", initialCfg.systemCorners) { enabled ->
            coordinator.updateKifraneiConfig(activity) { copy(systemCorners = enabled) }
        }
        body.addView(systemCornersToggle, fullMargin(activity, 10))
        if (chromeYielded) {
            body.addView(
                label(activity, "独立版 K 插件已接管播放页顶部把手/圆角，已自动让位", 12f, colors.secondary),
                fullMargin(activity, 4),
            )
        }

        val volumeYielded = initialSnap.isYielded(dev.kifranei.ampp.media.KifraneiCapability.VOLUME_BAR)
        volumeBarToggle = binaryChoice(
            activity,
            "播放页音量条",
            initialCfg.volumeBar && !oplus,
        ) { enabled ->
            coordinator.updateKifraneiConfig(activity) { copy(volumeBar = enabled) }
        }
        body.addView(volumeBarToggle, fullMargin(activity, 10))
        if (oplus || volumeYielded) {
            body.addView(
                label(
                    activity,
                    if (oplus) "ColorOS / realme UI / OxygenOS 因系统默认 DPI 原因已自动禁用音量条"
                    else "独立版 K 插件已开启音量条，已自动让位",
                    12f,
                    colors.secondary,
                ),
                fullMargin(activity, 4),
            )
        }

        val iosYielded = initialSnap.isYielded(dev.kifranei.ampp.media.KifraneiCapability.IOS_CONTROLS)
        iosControlsToggle = binaryChoice(activity, "iOS 媒体控制按钮（输出设备）", initialCfg.iosControls) { enabled ->
            coordinator.updateKifraneiConfig(activity) { copy(iosControls = enabled) }
        }
        body.addView(iosControlsToggle, fullMargin(activity, 10))
        if (iosYielded) {
            body.addView(
                label(activity, "独立版 K 插件已开启 iOS 音频输出控制，已自动让位", 12f, colors.secondary),
                fullMargin(activity, 4),
            )
        }

        body.addView(title(activity, "分享与资源下载", 16f), fullMargin(activity, 16))

        val sharingYielded = initialSnap.isYielded(dev.kifranei.ampp.media.KifraneiCapability.SHARING)
        sharingToggle = binaryChoice(activity, "解除分享白名单限制", initialCfg.sharing) { enabled ->
            coordinator.updateKifraneiConfig(activity) { copy(sharing = enabled) }
            onChanged()
        }
        body.addView(sharingToggle, fullMargin(activity, 10))
        body.addView(
            label(
                activity,
                if (sharingYielded) "独立版 K 插件已开启卡片分享解限，已自动让位"
                else "为歌曲与歌词卡片增加系统图片分享和保存到相册入口",
                12f,
                colors.secondary,
            ),
            fullMargin(activity, 4),
        )

        val downloadsYielded = initialSnap.isYielded(dev.kifranei.ampp.media.KifraneiCapability.CONTENT_DOWNLOADS)
        downloadsToggle = binaryChoice(activity, "下载封面、动态封面与原始 TTML", initialCfg.contentDownloads) { enabled ->
            coordinator.updateKifraneiConfig(activity) { copy(contentDownloads = enabled) }
            onChanged()
        }
        body.addView(downloadsToggle, fullMargin(activity, 10))
        body.addView(
            label(
                activity,
                if (downloadsYielded) "独立版 K 插件已开启资源下载，已自动让位"
                else "艺术家/专辑页可下载封面、MP4 动态封面、字标与简介；播放页更多菜单或长按歌词导出 TTML 至 Download/AM++",
                12f,
                colors.secondary,
            ),
            fullMargin(activity, 4),
        )

        refreshControls()
        bubbleDialog(activity, body).show()
    }

    internal fun showAutoTransitionSettings(activity: Activity, onChanged: () -> Unit = {}) {
        val coordinator = com.tcrrry.lyrics.plugin.MoreToolsCoordinator
        val colors = SettingsUiTheme.colors(activity)
        val initialCfg = coordinator.kifraneiConfig(activity)
        val initialSnap = coordinator.kifraneiConflictSnapshot(activity)
        val body = card(activity).apply { addView(title(activity, "智能混音（自动过渡）", 20f)) }

        if (!coordinator.isKifraneiSupported()) {
            body.addView(
                label(activity, "当前 Apple Music 版本暂未适配智能混音", 12.5f, colors.secondary),
                fullMargin(activity, 8),
            )
        } else if (initialSnap.isYielded(dev.kifranei.ampp.media.KifraneiCapability.AUTO_TRANSITION)) {
            val banner = label(
                activity,
                "检测到独立版 Kifranei 插件已开启自动过渡，本插件已自动让位以避免冲突；若想使用本插件版本，请先在独立版 K 插件中关闭自动过渡",
                12.5f,
                colors.primary,
            ).apply {
                setPadding(dp(activity, 14), dp(activity, 10), dp(activity, 14), dp(activity, 10))
                background = shape(activity, colors.selected, 14, colors.primary)
                SettingsGlassMaterial.mark(this, SettingsGlassKind.Card, accent = true)
            }
            body.addView(banner, fullMargin(activity, 10))
        }

        lateinit var autoTransitionToggle: TextView
        lateinit var autoDurationRow: View
        lateinit var autoShowQueueBtnToggle: TextView
        lateinit var autoAnimateToggle: TextView
        lateinit var autoAppleCurveToggle: TextView
        lateinit var autoAlbumSeqToggle: TextView
        lateinit var autoRepeatOneToggle: TextView
        lateinit var autoSeekTriggerToggle: TextView

        fun setBinaryEnabled(view: TextView, enabled: Boolean) {
            view.isEnabled = enabled
            view.alpha = if (enabled) 1f else .45f
        }

        fun setSegmentedEnabled(row: View, enabled: Boolean) {
            row.alpha = if (enabled) 1f else .45f
            val group = (row as? ViewGroup)?.let { vg ->
                (0 until vg.childCount).firstNotNullOfOrNull { idx -> vg.getChildAt(idx) as? SettingsSegmentedControl }
            }
            group?.isEnabled = enabled
        }

        fun refreshAutoControls() {
            val cfg = coordinator.kifraneiConfig(activity)
            val snap = coordinator.kifraneiConflictSnapshot(activity)
            val autoUnlocked = coordinator.isKifraneiSupported() &&
                !snap.isYielded(dev.kifranei.ampp.media.KifraneiCapability.AUTO_TRANSITION)
            setBinaryEnabled(autoTransitionToggle, autoUnlocked)
            setBinaryEnabled(autoShowQueueBtnToggle, autoUnlocked)
            val autoSubUnlocked = autoUnlocked && cfg.autoTransition
            setSegmentedEnabled(autoDurationRow, autoSubUnlocked)
            setBinaryEnabled(autoAnimateToggle, autoSubUnlocked)
            setBinaryEnabled(autoAppleCurveToggle, autoSubUnlocked)
            setBinaryEnabled(autoAlbumSeqToggle, autoSubUnlocked)
            setBinaryEnabled(autoRepeatOneToggle, autoSubUnlocked)
            setBinaryEnabled(autoSeekTriggerToggle, autoSubUnlocked)
        }

        autoTransitionToggle = binaryChoice(activity, "启用智能混音（自动过渡）", initialCfg.autoTransition) { enabled ->
            coordinator.updateKifraneiConfig(activity) { copy(autoTransition = enabled) }
            refreshAutoControls()
            onChanged()
        }
        body.addView(autoTransitionToggle, fullMargin(activity, 12))
        body.addView(
            label(
                activity,
                "默认关闭；自动接管原生交叉淡化引擎（无需手动关掉系统开关），支持队列快捷按钮与智能进度光效",
                12f,
                colors.secondary,
            ),
            fullMargin(activity, 4),
        )

        val durations = listOf(0, 4, 6, 8, 12)
        val initialDurationIdx = if (initialCfg.autoTransitionDuration <= 0) {
            0
        } else {
            (1 until durations.size).minByOrNull {
                kotlin.math.abs(durations[it] - initialCfg.autoTransitionDuration)
            } ?: 0
        }
        autoDurationRow = segmentedChoice(
            activity,
            "默认淡化时长",
            listOf("自动", "4秒", "6秒", "8秒", "12秒"),
            initialDurationIdx,
            inlineGlass = true,
        ) { which ->
            val sec = durations.getOrElse(which) { 0 }
            coordinator.updateKifraneiConfig(activity) { copy(autoTransitionDuration = sec) }
            onChanged()
        }
        body.addView(autoDurationRow, fullMargin(activity, 12))
        body.addView(
            label(activity, "「自动」会根据曲目契合度与尾奏响度在 0~10 秒间动态调节；不好匹配或人声持续到结尾时自动缩至极短或跳过过渡", 12f, colors.secondary),
            fullMargin(activity, 4),
        )

        autoShowQueueBtnToggle = binaryChoice(activity, "在播放列表显示过渡图标", initialCfg.autoTransitionShowQueueButton) { enabled ->
            coordinator.updateKifraneiConfig(activity) { copy(autoTransitionShowQueueButton = enabled) }
        }
        body.addView(autoShowQueueBtnToggle, fullMargin(activity, 10))
        body.addView(
            label(activity, "关闭后将隐藏播放列表顶部的渐入渐出快捷图标", 12f, colors.secondary),
            fullMargin(activity, 4),
        )

        autoAnimateToggle = binaryChoice(activity, "切歌封面过渡动画", initialCfg.autoTransitionAnimate) { enabled ->
            coordinator.updateKifraneiConfig(activity) { copy(autoTransitionAnimate = enabled) }
        }
        body.addView(autoAnimateToggle, fullMargin(activity, 10))

        autoAppleCurveToggle = binaryChoice(activity, "优先使用 Apple 原生过渡曲线", initialCfg.autoTransitionPreferAppleCurve) { enabled ->
            coordinator.updateKifraneiConfig(activity) { copy(autoTransitionPreferAppleCurve = enabled) }
        }
        body.addView(autoAppleCurveToggle, fullMargin(activity, 10))
        body.addView(
            label(activity, "默认关闭；开启后曲目具备官方过渡数据时保留原生淡化曲线", 12f, colors.secondary),
            fullMargin(activity, 4),
        )

        autoAlbumSeqToggle = binaryChoice(activity, "允许同专辑连播过渡", initialCfg.autoTransitionAllowAlbumSequential) { enabled ->
            coordinator.updateKifraneiConfig(activity) { copy(autoTransitionAllowAlbumSequential = enabled) }
        }
        body.addView(autoAlbumSeqToggle, fullMargin(activity, 10))
        body.addView(
            label(activity, "默认开启；解除系统对同一专辑相邻曲目的淡化限制，大幅提高触发率", 12f, colors.secondary),
            fullMargin(activity, 4),
        )

        autoRepeatOneToggle = binaryChoice(activity, "适配单曲循环无缝过渡", initialCfg.autoTransitionAllowSingleRepeat) { enabled ->
            coordinator.updateKifraneiConfig(activity) { copy(autoTransitionAllowSingleRepeat = enabled) }
        }
        body.addView(autoRepeatOneToggle, fullMargin(activity, 10))
        body.addView(
            label(activity, "默认关闭；开启后允许单曲循环在结尾尝试无缝重接", 12f, colors.secondary),
            fullMargin(activity, 4),
        )

        autoSeekTriggerToggle = binaryChoice(activity, "允许拖动进度条触发过渡", initialCfg.autoTransitionAllowSeekTrigger) { enabled ->
            coordinator.updateKifraneiConfig(activity) { copy(autoTransitionAllowSeekTrigger = enabled) }
        }
        body.addView(autoSeekTriggerToggle, fullMargin(activity, 10))
        body.addView(
            label(activity, "默认开启；手动拖动进度条至歌曲末尾过渡区间时，仍可立即触发平滑交叉淡化", 12f, colors.secondary),
            fullMargin(activity, 4),
        )

        val cacheStatusView = label(activity, "", 12f, colors.secondary)
        fun refreshCacheLabel() {
            val (count, bytes) = coordinator.automixCacheStats(activity)
            val kbText = if (bytes <= 0L) "0 KB" else "${kotlin.math.max(1L, (bytes + 1023L) / 1024L)} KB"
            cacheStatusView.text = "本地分析缓存：已记录 $count 首曲目（约 $kbText），完整播完一次后自动入库以加速后续触发"
        }
        refreshCacheLabel()
        body.addView(cacheStatusView, fullMargin(activity, 10))
        body.addView(action(activity, "清理过渡分析缓存") {
            val cleared = coordinator.clearAutomixCache(activity)
            refreshCacheLabel()
            toast(activity, if (cleared) "已清理本地过渡分析缓存" else "当前暂无分析缓存")
        }, fullMargin(activity, 8))

        refreshAutoControls()
        bubbleDialog(activity, body).show()
    }

    internal fun showPlaybackSpeedSettings(activity: Activity, onChanged: () -> Unit = {}) {
        val coordinator = com.tcrrry.lyrics.plugin.MoreToolsCoordinator
        val colors = SettingsUiTheme.colors(activity)
        val body = card(activity)
        val dialog = bubbleDialog(activity, body)
        lateinit var redraw: () -> Unit
        redraw = {
            val cfg = coordinator.playbackSpeedConfig(activity)
            val subUnlocked = cfg.enabled
            body.removeAllViews()
            body.addView(title(activity, "长按倍速播放", 20f))
            body.addView(
                label(
                    activity,
                    "在播放页长按「下一首」倍速快进，长按「上一首」慢速播放；短按仍正常切歌",
                    12.5f,
                    colors.secondary,
                ),
                fullMargin(activity, 6),
            )
            body.addView(binaryChoice(activity, "启用长按倍速播放", cfg.enabled) { enabled ->
                coordinator.updatePlaybackSpeedConfig(activity) { copy(enabled = enabled) }
                onChanged()
                redraw()
            }, fullMargin(activity, 14))

            val modeRow = segmentedChoice(
                activity,
                "触发方式",
                listOf("松手恢复", "长按锁定"),
                if (cfg.holdMode) 0 else 1,
                inlineGlass = true,
            ) { which ->
                coordinator.updatePlaybackSpeedConfig(activity) { copy(holdMode = which == 0) }
                onChanged()
            }.apply {
                alpha = if (subUnlocked) 1f else .45f
                (this as? ViewGroup)?.let { vg ->
                    (0 until vg.childCount).map(vg::getChildAt).filterIsInstance<SettingsSegmentedControl>()
                        .forEach { it.isEnabled = subUnlocked }
                }
            }
            body.addView(modeRow, fullMargin(activity, 14))

            val fastValueLabel = label(
                activity,
                com.tcrrry.lyrics.plugin.LongPressPlaybackSpeed.formatSpeed(cfg.fastSpeed),
                14f,
                colors.primary,
            ).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
            body.addView(LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                alpha = if (subUnlocked) 1f else .45f
                addView(title(activity, "长按下一首（倍速）", 15f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(fastValueLabel)
            }, fullMargin(activity, 16))

            val fastProgressInit = kotlin.math.round((cfg.fastSpeed - 1.25f) / 0.25f).toInt().coerceIn(0, 7)
            body.addView(SeekBar(activity).apply {
                max = 7
                progress = fastProgressInit
                isEnabled = subUnlocked
                alpha = if (subUnlocked) 1f else .45f
                progressTintList = ColorStateList.valueOf(colors.primary)
                progressBackgroundTintList = ColorStateList.valueOf(colors.outline)
                thumbTintList = ColorStateList.valueOf(colors.text)
                splitTrack = false
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        val speed = 1.25f + progress * 0.25f
                        fastValueLabel.text = com.tcrrry.lyrics.plugin.LongPressPlaybackSpeed.formatSpeed(speed)
                        if (fromUser) {
                            coordinator.updatePlaybackSpeedConfig(activity) { copy(fastSpeed = speed) }
                            onChanged()
                        }
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                })
            }, fullMargin(activity, 6))

            body.addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                alpha = if (subUnlocked) 1f else .45f
                val presets = listOf(1.5f, 2.0f, 2.5f, 3.0f)
                presets.forEachIndexed { idx, preset ->
                    val selected = kotlin.math.abs(cfg.fastSpeed - preset) < 0.01f
                    addView(action(activity, com.tcrrry.lyrics.plugin.LongPressPlaybackSpeed.formatSpeed(preset), emphasized = selected) {
                        coordinator.updatePlaybackSpeedConfig(activity) { copy(fastSpeed = preset) }
                        onChanged()
                        redraw()
                    }.apply {
                        isEnabled = subUnlocked
                        textSize = 12.5f
                        minimumHeight = dp(activity, 40)
                        setPadding(dp(activity, 6), dp(activity, 6), dp(activity, 6), dp(activity, 6))
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (idx > 0) marginStart = dp(activity, 6)
                    })
                }
            }, fullMargin(activity, 6))

            val slowValueLabel = label(
                activity,
                com.tcrrry.lyrics.plugin.LongPressPlaybackSpeed.formatSpeed(cfg.slowSpeed),
                14f,
                colors.primary,
            ).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
            body.addView(LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                alpha = if (subUnlocked) 1f else .45f
                addView(title(activity, "长按上一首（慢放）", 15f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(slowValueLabel)
            }, fullMargin(activity, 16))

            val slowProgressInit = kotlin.math.round((cfg.slowSpeed - 0.50f) / 0.05f).toInt().coerceIn(0, 8)
            body.addView(SeekBar(activity).apply {
                max = 8
                progress = slowProgressInit
                isEnabled = subUnlocked
                alpha = if (subUnlocked) 1f else .45f
                progressTintList = ColorStateList.valueOf(colors.primary)
                progressBackgroundTintList = ColorStateList.valueOf(colors.outline)
                thumbTintList = ColorStateList.valueOf(colors.text)
                splitTrack = false
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        val speed = 0.50f + progress * 0.05f
                        slowValueLabel.text = com.tcrrry.lyrics.plugin.LongPressPlaybackSpeed.formatSpeed(speed)
                        if (fromUser) {
                            coordinator.updatePlaybackSpeedConfig(activity) { copy(slowSpeed = speed) }
                            onChanged()
                        }
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                })
            }, fullMargin(activity, 6))

            body.addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                alpha = if (subUnlocked) 1f else .45f
                val presets = listOf(0.50f, 0.65f, 0.75f, 0.85f)
                presets.forEachIndexed { idx, preset ->
                    val selected = kotlin.math.abs(cfg.slowSpeed - preset) < 0.01f
                    addView(action(activity, com.tcrrry.lyrics.plugin.LongPressPlaybackSpeed.formatSpeed(preset), emphasized = selected) {
                        coordinator.updatePlaybackSpeedConfig(activity) { copy(slowSpeed = preset) }
                        onChanged()
                        redraw()
                    }.apply {
                        isEnabled = subUnlocked
                        textSize = 12.5f
                        minimumHeight = dp(activity, 40)
                        setPadding(dp(activity, 6), dp(activity, 6), dp(activity, 6), dp(activity, 6))
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (idx > 0) marginStart = dp(activity, 6)
                    })
                }
            }, fullMargin(activity, 6))

            body.addView(binaryChoice(activity, "变速保持原调", cfg.keepPitch, enabled = subUnlocked) { keep ->
                coordinator.updatePlaybackSpeedConfig(activity) { copy(keepPitch = keep) }
            }, fullMargin(activity, 14))
            body.addView(
                label(activity, "关闭后将随倍速同步升降音调（适合听 Slowed / Nightcore 效果）", 12f, colors.secondary),
                fullMargin(activity, 4),
            )

            body.addView(action(activity, "立即恢复 1.0x 原速") {
                coordinator.restoreNormalPlaybackSpeed(activity)
                toast(activity, "已恢复 1.0x 原速")
            }, fullMargin(activity, 14))
        }
        redraw()
        dialog.show()
    }

    internal fun showDiagnosticLogDialog(activity: Activity, onChanged: () -> Unit = {}) {
        val coordinator = com.tcrrry.lyrics.plugin.MoreToolsCoordinator
        val colors = SettingsUiTheme.colors(activity)
        val channels = com.tcrrry.lyrics.plugin.PluginDiagnosticLog.Channel.entries
        var selectedChannel = channels.first()
        var currentTail = ""
        var exporting = false

        val body = card(activity).apply { addView(title(activity, "诊断日志", 20f)) }
        body.addView(
            label(
                activity,
                "常驻轻量记录（单文件自动限额滚动，不影响播放），遇到歌词或混音异常可直接导出发给开发者排查。",
                12.5f,
                colors.secondary,
            ).apply { setLineSpacing(0f, 1.25f) },
            fullMargin(activity, 6),
        )

        val metaView = label(activity, "", 12f, colors.secondary).apply {
            setLineSpacing(0f, 1.2f)
        }
        val logTextView = TextView(activity).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(colors.text)
            setTextIsSelectable(true)
            setLineSpacing(0f, 1.25f)
            setPadding(dp(activity, 12), dp(activity, 10), dp(activity, 12), dp(activity, 10))
        }
        val logScroll = android.widget.ScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(activity, 250),
            ).apply { topMargin = dp(activity, 10) }
            background = shape(activity, colors.raised, 14, colors.outline)
            isVerticalScrollBarEnabled = true
            isNestedScrollingEnabled = true
            addView(
                logTextView,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }

        fun refreshPreview() {
            val info = coordinator.diagnosticLogChannelInfo(activity, selectedChannel)
            val tail = coordinator.diagnosticLogTail(activity, selectedChannel)
            currentTail = tail
            val sizeLabel = com.tcrrry.lyrics.plugin.PluginDiagnosticLog.formatSize(info.sizeBytes)
            val timeLabel = com.tcrrry.lyrics.plugin.PluginDiagnosticLog.formatTimestamp(info.lastModifiedMs)
            metaView.text = if (info.exists) {
                "${selectedChannel.fileName} · $sizeLabel · 更新：$timeLabel"
            } else {
                "${selectedChannel.fileName} · 暂无记录"
            }
            if (tail.isBlank()) {
                logTextView.text = "当前暂无日志记录"
                logTextView.setTextColor(colors.secondary)
            } else {
                logTextView.text = tail
                logTextView.setTextColor(colors.text)
                logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
            }
        }

        body.addView(
            segmentedChoice(
                activity,
                "日志分类",
                channels.map { it.label },
                0,
                inlineGlass = true,
            ) { which ->
                selectedChannel = channels.getOrElse(which) { channels.first() }
                refreshPreview()
            },
            fullMargin(activity, 14),
        )
        body.addView(metaView, fullMargin(activity, 10))
        body.addView(logScroll)

        lateinit var exportBtn: TextView
        exportBtn = action(activity, "导出全部日志到下载目录", emphasized = true) {
            if (exporting) return@action
            exporting = true
            exportBtn.isEnabled = false
            exportBtn.alpha = .6f
            exportBtn.text = "正在导出日志..."
            coordinator.exportDiagnosticLogsAsync(
                activity,
                onSuccess = { path ->
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        exporting = false
                        exportBtn.isEnabled = true
                        exportBtn.alpha = 1f
                        exportBtn.text = "导出全部日志到下载目录"
                        toast(activity, "已导出至：$path")
                    }
                },
                onError = { error ->
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        exporting = false
                        exportBtn.isEnabled = true
                        exportBtn.alpha = 1f
                        exportBtn.text = "导出全部日志到下载目录"
                        toast(activity, "导出失败：${error.message ?: error.javaClass.simpleName}")
                    }
                },
            )
        }
        body.addView(exportBtn, fullMargin(activity, 14))

        val copyBtn = action(activity, "复制当前日志") {
            val header = coordinator.diagnosticLogHeader(activity)
            val payload = if (currentTail.isBlank()) {
                "$header\n\n===== ${selectedChannel.label} =====\n(暂无记录)"
            } else {
                "$header\n\n===== ${selectedChannel.label} (${selectedChannel.fileName}) =====\n$currentTail"
            }
            val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            cm?.setPrimaryClip(android.content.ClipData.newPlainText("tcrrry-diagnostic-log", payload))
            toast(activity, "已复制当前日志到剪贴板")
        }
        val refreshBtn = action(activity, "刷新") {
            refreshPreview()
            onChanged()
            toast(activity, "已刷新")
        }
        body.addView(pairedActions(activity, copyBtn, refreshBtn), fullMargin(activity, 10))

        val clearCurrentBtn = action(activity, "清空当前") {
            coordinator.clearDiagnosticLogs(activity, selectedChannel)
            refreshPreview()
            onChanged()
            toast(activity, "已清空${selectedChannel.label}日志")
        }
        val clearAllBtn = action(activity, "清空全部") {
            coordinator.clearDiagnosticLogs(activity, null)
            refreshPreview()
            onChanged()
            toast(activity, "已清空全部诊断日志")
        }
        body.addView(pairedActions(activity, clearCurrentBtn, clearAllBtn), fullMargin(activity, 10))

        refreshPreview()
        bubbleDialog(activity, body).show()
    }

    internal fun showPlaylistExportSettings(activity: Activity, onChanged: () -> Unit = {}) {
        val coordinator = com.tcrrry.lyrics.plugin.MoreToolsCoordinator
        val colors = SettingsUiTheme.colors(activity)
        val body = card(activity).apply { addView(title(activity, "导出播放列表", 20f)) }
        val handler = com.tcrrry.lyrics.plugin.PluginHandler()
        var closed = false
        var busy = false

        val loadedPlaylists = ArrayList<com.tcrrry.playlisttools.PlaylistModels.PlaylistSummary>()
        val selectedPids = LinkedHashSet<Long>()

        body.addView(binaryChoice(activity, "启用播放列表导出", coordinator.playlistExportEnabled(activity)) { enabled ->
            coordinator.setPlaylistExportEnabled(activity, enabled)
            onChanged()
        }, fullMargin(activity, 14))

        body.addView(binaryChoice(activity, "覆盖同名旧文件", coordinator.exportOverwriteEnabled(activity)) { enabled ->
            coordinator.setExportOverwriteEnabled(activity, enabled)
            onChanged()
        }, fullMargin(activity, 10))
        body.addView(
            label(activity, "重复导出同一歌单时自动覆盖或替换旧文件，避免重复堆积", 12f, colors.secondary),
            fullMargin(activity, 5),
        )

        val formats = listOf(
            com.tcrrry.playlisttools.PlaylistModels.ExportFormat.CSV,
            com.tcrrry.playlisttools.PlaylistModels.ExportFormat.TXT_TITLE_ARTIST,
            com.tcrrry.playlisttools.PlaylistModels.ExportFormat.TXT_ARTIST_TITLE,
        )
        val initialFmtIndex = formats.indexOf(coordinator.playlistExportFormat(activity)).coerceAtLeast(0)
        body.addView(segmentedChoice(
            activity,
            "导出格式（同步原生菜单）",
            listOf("CSV", "TXT 歌名-歌手", "TXT 歌手-歌名"),
            initialFmtIndex,
            inlineGlass = true,
        ) { which ->
            coordinator.setPlaylistExportFormat(activity, formats.getOrElse(which) { com.tcrrry.playlisttools.PlaylistModels.ExportFormat.CSV })
            onChanged()
        }, fullMargin(activity, 14))

        val locationRow = menuRow(activity, "导出位置", coordinator.exportLocationDisplayLabel(activity)) {}
        val locationSummaryView = ((locationRow as? ViewGroup)?.getChildAt(0) as? ViewGroup)?.getChildAt(1) as? TextView
        fun refreshLocationSummary() {
            val label = coordinator.exportLocationDisplayLabel(activity)
            locationSummaryView?.text = label
            locationRow.contentDescription = "导出位置，$label"
        }
        locationRow.setOnClickListener { view ->
            SettingsDialogMotion.from(view) {
                showExportLocationDialog(activity) {
                    refreshLocationSummary()
                    onChanged()
                }
            }
        }
        body.addView(locationRow, fullMargin(activity, 14))

        val countBadge = label(activity, "已选 0", 12.5f, colors.primary).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        body.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title(activity, "资料库歌单", 16f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(countBadge)
        }, fullMargin(activity, 18))

        val statusView = label(activity, "正在读取资料库歌单...", 12.5f, colors.secondary)
        body.addView(statusView, fullMargin(activity, 4))

        val listContainer = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

        lateinit var copyBtn: TextView
        lateinit var exportBtn: TextView
        lateinit var refreshBtn: TextView
        lateinit var selectAllBtn: TextView
        lateinit var clearBtn: TextView

        fun updateSelectionUi() {
            val total = loadedPlaylists.size
            val selectedCount = selectedPids.size
            countBadge.text = if (total > 0) "已选 $selectedCount/$total" else "已选 $selectedCount"
            exportBtn.text = if (selectedCount > 0) "导出选中 ($selectedCount)" else "导出选中"
        }

        fun updateBusy(nextBusy: Boolean) {
            busy = nextBusy
            val enabled = !nextBusy && coordinator.is1626Supported()
            listOf(refreshBtn, selectAllBtn, clearBtn, copyBtn, exportBtn).forEach { btn ->
                btn.isEnabled = enabled
                btn.alpha = if (enabled) 1f else .45f
            }
        }

        fun paintRow(
            row: LinearLayout,
            checkView: TextView,
            titleView: TextView,
            trackBadge: TextView,
            item: com.tcrrry.playlisttools.PlaylistModels.PlaylistSummary,
            selected: Boolean,
        ) {
            checkView.text = if (selected) "✓" else "○"
            checkView.setTextColor(if (selected) colors.primary else colors.muted)
            titleView.text = item.title
            titleView.setTextColor(if (selected) colors.primary else colors.text)
            trackBadge.text = "${item.trackCount} 首"
            trackBadge.setTextColor(if (selected) colors.primary else colors.secondary)
            val fill = if (selected) colors.selected else colors.raised
            val stroke = if (selected) colors.primary else colors.outline
            val rippleColor = (colors.primary and 0x00FFFFFF) or (0x24 shl 24)
            row.background = android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(rippleColor),
                shape(activity, fill, 12, stroke),
                shape(activity, android.graphics.Color.WHITE, 12, android.graphics.Color.WHITE),
            )
            SettingsGlassMaterial.mark(row, SettingsGlassKind.Button, accent = selected)
        }

        fun renderPlaylists() {
            listContainer.removeAllViews()
            updateSelectionUi()
            if (loadedPlaylists.isEmpty()) {
                listContainer.addView(label(activity, "暂无可用歌单", 13f, colors.secondary))
                return
            }
            for (item in loadedPlaylists) {
                val selected = selectedPids.contains(item.persistentId)
                val checkView = label(activity, "", 15f, colors.muted).apply {
                    gravity = Gravity.CENTER
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                }
                val titleView = title(activity, item.title, 14.5f)
                val trackBadge = label(activity, "", 12.5f, colors.secondary).apply {
                    gravity = Gravity.END or Gravity.CENTER_VERTICAL
                }
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = dp(activity, 50)
                    setPadding(dp(activity, 14), dp(activity, 10), dp(activity, 14), dp(activity, 10))
                    isClickable = true
                    isFocusable = true
                    addView(checkView, LinearLayout.LayoutParams(dp(activity, 24), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        marginEnd = dp(activity, 8)
                    })
                    addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = dp(activity, 10)
                    })
                    addView(trackBadge)
                }
                paintRow(row, checkView, titleView, trackBadge, item, selected)
                row.setOnClickListener {
                    if (busy) return@setOnClickListener
                    val nowSelected = !selectedPids.contains(item.persistentId)
                    if (nowSelected) selectedPids.add(item.persistentId) else selectedPids.remove(item.persistentId)
                    paintRow(row, checkView, titleView, trackBadge, item, nowSelected)
                    updateSelectionUi()
                }
                listContainer.addView(row, fullMargin(activity, 8))
            }
        }

        fun reloadPlaylists() {
            if (closed || busy) return
            if (!coordinator.is1626Supported()) {
                statusView.text = "当前 Apple Music 版本暂不支持歌单读取（仅适配 7.0.3 / 1626）"
                updateBusy(false)
                return
            }
            updateBusy(true)
            statusView.text = "正在读取资料库歌单..."
            coordinator.listPlaylistsAsync(
                onSuccess = { result ->
                    handler.post {
                        if (closed) return@post
                        loadedPlaylists.clear()
                        loadedPlaylists.addAll(result)
                        val validPids = result.mapTo(HashSet()) { it.persistentId }
                        selectedPids.retainAll(validPids)
                        renderPlaylists()
                        statusView.text = if (result.isEmpty()) {
                            "未读到资料库播放列表，请先打开资料库页面同步后重试"
                        } else {
                            "共 ${result.size} 个歌单（导出将保持当前歌单排序）"
                        }
                        updateBusy(false)
                    }
                },
                onError = { error ->
                    handler.post {
                        if (closed) return@post
                        statusView.text = error.message ?: error.javaClass.simpleName
                        updateBusy(false)
                    }
                },
            )
        }

        refreshBtn = action(activity, "刷新") { reloadPlaylists() }.apply { minimumHeight = dp(activity, 40) }
        selectAllBtn = action(activity, "全选") {
            if (busy) return@action
            selectedPids.clear()
            loadedPlaylists.forEach { selectedPids.add(it.persistentId) }
            renderPlaylists()
        }.apply { minimumHeight = dp(activity, 40) }
        clearBtn = action(activity, "清空") {
            if (busy) return@action
            selectedPids.clear()
            renderPlaylists()
        }.apply { minimumHeight = dp(activity, 40) }

        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(refreshBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(activity, 6) })
            addView(selectAllBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(activity, 6) })
            addView(clearBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        body.addView(bar, fullMargin(activity, 10))
        body.addView(listContainer, fullMargin(activity, 8))

        copyBtn = action(activity, "复制文本") {
            if (busy || selectedPids.isEmpty()) {
                if (selectedPids.isEmpty()) statusView.text = "请先点击选择至少 1 个歌单"
                return@action
            }
            updateBusy(true)
            statusView.text = "正在生成文本..."
            val artistFirst = coordinator.playlistExportFormat(activity) ==
                com.tcrrry.playlisttools.PlaylistModels.ExportFormat.TXT_ARTIST_TITLE
            coordinator.copySelectedTxtAsync(
                pids = selectedPids.toList(),
                artistFirst = artistFirst,
                onSuccess = { text, playlistCount, trackCount ->
                    handler.post {
                        if (closed) return@post
                        val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                        if (cm != null) {
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("TcrrryPlaylists", text))
                            statusView.text = "已复制 $playlistCount 个歌单（共 $trackCount 首）到剪贴板"
                            toast(activity, "已复制到剪贴板")
                        } else {
                            statusView.text = "系统剪贴板不可用"
                        }
                        updateBusy(false)
                    }
                },
                onError = { error ->
                    handler.post {
                        if (closed) return@post
                        statusView.text = "复制失败：${error.message ?: error.javaClass.simpleName}"
                        updateBusy(false)
                    }
                },
            )
        }

        fun runExport(merged: Boolean) {
            val fmt = coordinator.playlistExportFormat(activity)
            val destLabel = coordinator.exportLocationDisplayLabel(activity)
            updateBusy(true)
            statusView.text = "正在导出到 $destLabel..."
            coordinator.exportSelectedAsync(
                pids = selectedPids.toList(),
                format = fmt,
                merged = merged,
                appContext = activity.applicationContext,
                onSuccess = { files ->
                    handler.post {
                        if (closed) return@post
                        if (files.isEmpty()) {
                            statusView.text = "未匹配到选中的歌单，请刷新后重试"
                        } else if (files.size == 1) {
                            val r = files[0]
                            val overwriteTag = if (r.overwritten) "（已覆盖旧文件）" else ""
                            statusView.text = "导出成功$overwriteTag：${r.displayPath}（${r.trackCount} 首）"
                        } else {
                            val total = files.sumOf { it.trackCount }
                            val overwrittenCount = files.count { it.overwritten }
                            val overwriteTag = if (overwrittenCount > 0) "（含 $overwrittenCount 个覆盖更新）" else ""
                            statusView.text = "已分开导出 ${files.size} 个文件$overwriteTag（共 $total 首）到 $destLabel"
                        }
                        toast(activity, statusView.text.toString())
                        updateBusy(false)
                    }
                },
                onError = { error ->
                    handler.post {
                        if (closed) return@post
                        statusView.text = "导出失败：${error.message ?: error.javaClass.simpleName}"
                        updateBusy(false)
                    }
                },
            )
        }

        exportBtn = action(activity, "导出选中", emphasized = true) {
            if (busy || selectedPids.isEmpty()) {
                if (selectedPids.isEmpty()) statusView.text = "请先点击选择至少 1 个歌单"
                return@action
            }
            if (selectedPids.size == 1) {
                runExport(merged = false)
                return@action
            }
            val modeCard = card(activity).apply {
                addView(title(activity, "选择导出方式", 20f))
                addView(
                    label(
                        activity,
                        "已选择 ${selectedPids.size} 个歌单（将按各自当前排序导出）",
                        13f,
                        colors.secondary,
                    ),
                    fullMargin(activity, 6),
                )
            }
            lateinit var modeDialog: android.app.AlertDialog
            modeCard.addView(action(activity, "分开导出（每个歌单独立文件）") {
                modeDialog.dismiss()
                runExport(merged = false)
            }, fullMargin(activity, 14))
            modeCard.addView(action(activity, "合并导出（合并为单个文件）", emphasized = true) {
                modeDialog.dismiss()
                runExport(merged = true)
            }, fullMargin(activity, 10))
            modeDialog = bubbleDialog(activity, modeCard, compact = true)
            modeDialog.show()
        }

        val footer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 18), dp(activity, 10), dp(activity, 18), dp(activity, 16))
            addView(pairedActions(activity, copyBtn, exportBtn), fullMargin(activity, 0))
        }

        val dialog = bubbleDialog(activity, body, footer = footer)
        dialog.setOnDismissListener {
            closed = true
            handler.removeCallbacksAndMessages(null)
        }
        updateSelectionUi()
        reloadPlaylists()
        dialog.show()
    }

    private fun showExportLocationDialog(activity: Activity, onLocationUpdated: () -> Unit) {
        val coordinator = com.tcrrry.lyrics.plugin.MoreToolsCoordinator
        val colors = SettingsUiTheme.colors(activity)
        val body = card(activity).apply { addView(title(activity, "导出位置", 20f)) }

        val infoCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 14), dp(activity, 12), dp(activity, 14), dp(activity, 12))
            background = shape(activity, colors.raised, 12, colors.outline)
        }
        infoCard.addView(label(activity, "当前保存目录", 12f, colors.secondary))
        val pathLabel = title(activity, coordinator.exportLocationDisplayLabel(activity), 14.5f)
        infoCard.addView(pathLabel, fullMargin(activity, 3))
        infoCard.addView(
            label(activity, "默认保存在 Download/${com.tcrrry.playlisttools.PlaylistExporter.DOWNLOAD_SUBDIR}，不会改变原默认习惯", 11.5f, colors.muted),
            fullMargin(activity, 5),
        )
        body.addView(infoCard, fullMargin(activity, 12))

        lateinit var resetBtn: TextView
        fun syncUi() {
            pathLabel.text = coordinator.exportLocationDisplayLabel(activity)
            val custom = coordinator.hasCustomExportLocation(activity)
            resetBtn.visibility = if (custom) View.VISIBLE else View.GONE
        }

        val onPicked: (String?) -> Unit = {
            syncUi()
            onLocationUpdated()
        }

        val hasOem = coordinator.hasOemFileManagerPicker(activity)
        if (hasOem) {
            body.addView(action(activity, "使用系统文件管理选择", emphasized = true) {
                if (!coordinator.launchOemDirectoryPicker(activity, onPicked)) {
                    toast(activity, "无法打开系统文件管理器，请尝试目录授权选择")
                }
            }, fullMargin(activity, 14))
            body.addView(action(activity, "系统目录授权选择（任意文件夹）") {
                if (!coordinator.launchSafDirectoryPicker(activity, null, onPicked)) {
                    toast(activity, "无法打开系统目录选择器")
                }
            }, fullMargin(activity, 8))
        } else {
            body.addView(action(activity, "选择导出文件夹", emphasized = true) {
                if (!coordinator.launchSafDirectoryPicker(activity, null, onPicked)) {
                    toast(activity, "无法打开系统目录选择器")
                }
            }, fullMargin(activity, 14))
        }

        body.addView(action(activity, "在文件管理中打开") {
            if (!coordinator.openExportLocationInFileManager(activity)) {
                toast(activity, "未找到可用的文件管理器")
            }
        }, fullMargin(activity, 8))

        resetBtn = action(activity, "恢复默认位置（Download/TcrrryPlaylists）") {
            coordinator.resetExportLocation(activity)
            syncUi()
            onLocationUpdated()
            toast(activity, "已恢复默认导出位置")
        }
        body.addView(resetBtn, fullMargin(activity, 8))
        syncUi()

        bubbleDialog(activity, body, compact = true).show()
    }

    internal fun showCustomSleepTimerDialog(
        activity: Activity,
        initialMinutes: Int,
        activeRemainingMs: Long,
        onConfirm: (Int) -> Unit,
    ) {
        val colors = SettingsUiTheme.colors(activity)
        val clampedInitial = HorizontalMinuteDialView.clampMinutes(initialMinutes)
        var currentMinutes = clampedInitial

        val body = card(activity).apply {
            addView(title(activity, "自定义睡眠定时", 20f).apply { gravity = Gravity.CENTER_HORIZONTAL })
        }
        if (activeRemainingMs > 0L) {
            body.addView(
                label(
                    activity,
                    "当前剩余 ${com.tcrrry.playlisttools.HardenedSleepTimer.formatRemaining(activeRemainingMs)}",
                    12.5f,
                    colors.secondary,
                ).apply { gravity = Gravity.CENTER_HORIZONTAL },
                fullMargin(activity, 4),
            )
        }

        val readoutView = title(activity, "$currentMinutes 分钟", 32f).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        body.addView(readoutView, fullMargin(activity, 12))

        fun detailLabel(mins: Int): String =
            HorizontalMinuteDialView.formatDurationDetail(mins).ifEmpty { "左右滑动刻度盘调整时长" }

        val detailView = label(activity, detailLabel(currentMinutes), 12f, colors.secondary).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        }
        body.addView(detailView, fullMargin(activity, 2))

        val dialView = HorizontalMinuteDialView(
            context = activity,
            accentColor = colors.primary,
            primaryTextColor = colors.text,
            secondaryTextColor = colors.secondary,
        ).apply {
            setMinutes(clampedInitial, notify = false)
            onMinutesChanged = { next ->
                currentMinutes = next
                readoutView.text = "$next 分钟"
                detailView.text = detailLabel(next)
            }
        }
        body.addView(
            dialView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 92)).apply {
                topMargin = dp(activity, 10)
            },
        )

        lateinit var dialog: android.app.AlertDialog
        val cancelBtn = action(activity, "取消") { dialog.dismiss() }
        val startBtn = action(activity, "开始定时", emphasized = true) {
            onConfirm(HorizontalMinuteDialView.clampMinutes(currentMinutes))
            dialog.dismiss()
        }
        body.addView(pairedActions(activity, cancelBtn, startBtn), fullMargin(activity, 12))
        dialog = bubbleDialog(activity, body, compact = true)
        dialog.show()
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
            check(com.tcrrry.lyrics.plugin.PluginLifetime.context.hostVersionCode in setOf(1609L,1613L,1614L,1622L,1626L))
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
        val density = activity.resources.displayMetrics.density
        val button = TextView(activity).apply {
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            minimumHeight = dp(activity, 50)
            setPadding(dp(activity, 16), dp(activity, 12), dp(activity, 14), dp(activity, 12))
            compoundDrawablePadding = dp(activity, 12)
            isClickable = true; isFocusable = true; isEnabled = enabled
            alpha = if (enabled) 1f else .45f
            contentDescription = heading
        }
        fun paint() {
            button.text = heading
            button.isSelected = selected
            button.setTextColor(if (selected) colors.primary else colors.text)
            val fallbackFill = when {
                selected -> if (colors.dark) 0x4CE8345C else colors.selected
                colors.dark -> 0x22FFFFFF
                else -> colors.raised
            }
            val fallbackStroke = when {
                selected -> colors.primary
                colors.dark -> 0x38FFFFFF
                else -> colors.outline
            }
            button.background = android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(if (colors.dark) 0x28FFFFFF else 0x16000000),
                shape(activity, fallbackFill, 14, fallbackStroke),
                shape(activity, android.graphics.Color.WHITE, 14, android.graphics.Color.WHITE),
            )
            button.setCompoundDrawablesRelativeWithIntrinsicBounds(
                null,
                null,
                BinaryTogglePillDrawable(selected, colors.dark, colors.primary, density),
                null,
            )
            SettingsGlassMaterial.mark(button, SettingsGlassKind.Button, accent = selected)
            if (Build.VERSION.SDK_INT >= 30) button.stateDescription = if (selected) "开启" else "关闭"
        }
        paint()
        button.setOnClickListener {
            if (button.isEnabled) {
                selected = !selected
                paint()
                button.requestLayout()
                change(selected)
            }
        }
        return button
    }

    private class BinaryTogglePillDrawable(
        private val selected: Boolean,
        private val dark: Boolean,
        private val primary: Int,
        private val density: Float,
    ) : android.graphics.drawable.Drawable() {
        private val w = (40f * density).toInt()
        private val h = (24f * density).toInt()
        private val fillPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val strokePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = maxOf(1f, density)
        }
        private val rect = android.graphics.RectF()

        override fun getIntrinsicWidth(): Int = w
        override fun getIntrinsicHeight(): Int = h

        override fun draw(canvas: android.graphics.Canvas) {
            val b = bounds
            val left = b.left.toFloat()
            val top = b.top.toFloat()
            val right = left + w
            val bottom = top + h
            val radius = h * 0.5f
            rect.set(left, top, right, bottom)
            fillPaint.color = when {
                selected -> (primary and 0x00FFFFFF) or (0xE8 shl 24)
                dark -> 0x33FFFFFF
                else -> 0x26000000
            }
            canvas.drawRoundRect(rect, radius, radius, fillPaint)
            strokePaint.color = when {
                selected -> 0x66FFFFFF
                dark -> 0x52FFFFFF
                else -> 0x2A000000
            }
            val inset = strokePaint.strokeWidth * 0.5f
            rect.inset(inset, inset)
            canvas.drawRoundRect(rect, radius, radius, strokePaint)

            val thumbRadius = radius - 3f * density
            val cy = top + radius
            val cx = if (selected) right - radius else left + radius
            fillPaint.color = 0x28000000
            canvas.drawCircle(cx, cy + 0.8f * density, thumbRadius, fillPaint)
            fillPaint.color = if (selected || !dark) android.graphics.Color.WHITE else 0xFFEBEBF5.toInt()
            canvas.drawCircle(cx, cy, thumbRadius, fillPaint)
        }

        override fun setAlpha(alpha: Int) {
            fillPaint.alpha = alpha
            strokePaint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
            fillPaint.colorFilter = colorFilter
            strokePaint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Android")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }

    private fun segmentedChoice(activity: Activity, heading: String, choices: List<String>,
        selected: Int, inlineGlass: Boolean = false, choose: (Int) -> Unit): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        addView(title(activity, heading, 15f))
        addView(SettingsSegmentedControl(activity, heading, choices, selected, choose).apply {
            this.inlineGlass = inlineGlass
        }, fullMargin(activity, 10))
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

    private fun reloadAuxiliariesAction(activity: Activity, id: Long, refreshPage: () -> Unit): TextView =
        action(activity, "重新装载译文和发音") {
            val started = CurrentLyricsSourceStatus.reloadAuxiliaries(id)
            toast(activity, if (started) "正在重新装载当前歌曲的译文和发音" else "请先播放歌曲后重试")
            refreshPage()
        }.apply {
            isEnabled = id > 0L
            alpha = if (isEnabled) 1f else .45f
        }

    private fun showSourcePicker(activity: Activity, id: Long, selected: String?, refreshPage: () -> Unit) {
        val body = card(activity)
        body.addView(title(activity, "选择歌词源", 20f))
        body.addView(label(activity, "只记住当前歌曲的选择；自动匹配将按来源顺序查找", 12f, SettingsUiTheme.colors(activity).secondary))
        val dialog = bubbleDialog(activity, body, compact = true)
        val sources = listOf<String?>(null) + com.tcrrry.lyrics.engine.lyrics.LyricsPreference.sourceOrder(activity)
        sources.forEach { source ->
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

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun showSourceOrderEditor(activity: Activity, id: Long, refreshPage: () -> Unit) {
        val policy = com.tcrrry.lyrics.engine.lyrics.LyricsPreference
        val items = policy.sourceOrder(activity).toMutableList()
        val colors = SettingsUiTheme.colors(activity)
        val body = card(activity)
        body.addView(title(activity, "自动匹配来源顺序", 20f))
        body.addView(label(activity, "长按或拖动右侧图标调整顺序；质量优先按此顺序首选，逐字优先按此顺序先找逐字再找逐行",
            12f, colors.secondary), fullMargin(activity, 6))
        val dialog = bubbleDialog(activity, body, compact = true)

        val rows = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

        fun drawRows() {
            rows.removeAllViews()
            items.forEachIndexed { index, source ->
                val rank = label(activity, "${index + 1}.", 14f, colors.primary).apply {
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    minimumWidth = dp(activity, 24)
                }
                val name = title(activity, source, 15f)
                val handle = label(activity, "☰", 18f, colors.secondary).apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(activity, 12), dp(activity, 6), dp(activity, 4), dp(activity, 6))
                }
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = dp(activity, 48)
                    setPadding(dp(activity, 14), dp(activity, 8), dp(activity, 12), dp(activity, 8))
                    background = shape(activity, colors.raised, 12, colors.outline)
                    SettingsGlassMaterial.mark(this, SettingsGlassKind.Button)
                    addView(rank)
                    addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = dp(activity, 6)
                    })
                    addView(handle)
                }
                fun startDrag() = row.startDragAndDrop(null, View.DragShadowBuilder(row), row, 0)
                row.setOnLongClickListener { startDrag() }
                handle.setOnTouchListener { _, event ->
                    event.actionMasked == android.view.MotionEvent.ACTION_DOWN && startDrag()
                }
                rows.addView(row, fullMargin(activity, 8))
            }
        }

        // Plugin ZIPs ship DEX and assets without the RecyclerView Android resource table.
        rows.setOnDragListener { _, event ->
            val dragged = event.localState as? View
            val from = rows.indexOfChild(dragged)
            when (event.action) {
                android.view.DragEvent.ACTION_DRAG_STARTED -> from >= 0
                android.view.DragEvent.ACTION_DRAG_ENTERED,
                android.view.DragEvent.ACTION_DRAG_LOCATION,
                android.view.DragEvent.ACTION_DROP -> {
                    if (from !in items.indices) return@setOnDragListener false
                    var to = items.lastIndex
                    for (index in items.indices) {
                        if (event.y < rows.getChildAt(index).bottom) {
                            to = index
                            break
                        }
                    }
                    if (event.action == android.view.DragEvent.ACTION_DROP) {
                        if (from != to) items.add(to, items.removeAt(from))
                        rows.post { drawRows() }
                    } else {
                        for (index in items.indices) {
                            rows.getChildAt(index).alpha = if (index == to) 0.7f else 1f
                        }
                    }
                    true
                }
                android.view.DragEvent.ACTION_DRAG_EXITED,
                android.view.DragEvent.ACTION_DRAG_ENDED -> {
                    for (index in 0 until rows.childCount) rows.getChildAt(index).alpha = 1f
                    true
                }
                else -> false
            }
        }
        drawRows()
        body.addView(rows, fullMargin(activity, 8))
        val reset = action(activity, "恢复默认") {
            policy.resetSourceOrder(activity)
            CurrentLyricsSourceStatus.refreshSilently(id)
            dialog.dismiss()
            toast(activity, "已恢复默认歌词源顺序")
            refreshPage()
        }
        val save = action(activity, "保存顺序", emphasized = true) {
            policy.updateSourceOrder(activity, items)
            CurrentLyricsSourceStatus.refreshSilently(id)
            dialog.dismiss()
            toast(activity, "已更新歌词源顺序")
            refreshPage()
        }
        body.addView(pairedActions(activity, reset, save), fullMargin(activity, 12))
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
        val glass = com.tcrrry.lyrics.plugin.PluginLifetime.context.hostVersionCode in setOf(1609L,1613L,1614L,1622L,1626L)
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
