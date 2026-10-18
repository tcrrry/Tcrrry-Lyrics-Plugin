package com.tcrrry.lyrics.engine.ui

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.tcrrry.lyrics.engine.CurrentSongDetails
import com.tcrrry.lyrics.engine.hook.CurrentLyricsSourceStatus
import com.tcrrry.lyrics.engine.model.ModuleSettings
import com.tcrrry.lyrics.plugin.PluginStorage.pluginPreferences
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class TcrrrySettingsLayoutAndroidTest {
    @org.junit.Before fun attachPlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.attach() }
    @org.junit.After fun closePlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.current.close() }

    private fun texts(view: View): List<TextView> = if (view is ViewGroup)
        (0 until view.childCount).flatMap { texts(view.getChildAt(it)) } else listOfNotNull(view as? TextView)
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun page(activity: Activity): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        activity.pluginPreferences("lyrics_operation_guide", Context.MODE_PRIVATE).edit()
            .putBoolean("completed", true).commit()
        CurrentLyricsSourceStatus.recordNativeApplied(activity, 321)
        TcrrryLyricsSettingsUi.render(activity, this,
            CurrentSongDetails(321, "一首很长的歌曲名称用于验证卡片自适应布局", "歌手"),
            {}, ModuleSettings(), {})
    }
    private fun openMenu(page: View, heading: String) {
        val row = views(page).single { it.contentDescription?.toString()?.startsWith("$heading，") == true }
        assertTrue(row.performClick())
    }

    @Test fun translationUsesInlineLiquidSegmentsAndNoManualSourceLanguage() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        activity.pluginPreferences("supplement_translation", Context.MODE_PRIVATE).edit()
            .putString("mode", "offline").putString("offline_source_language", "ko").commit()
        openMenu(page(activity), "翻译与注音")
        val root = ShadowAlertDialog.getLatestAlertDialog().window!!.decorView
        val group = views(root).filterIsInstance<SettingsSegmentedControl>().single { it.heading == "补充翻译" }
        assertTrue(group.inlineGlass)
        assertEquals(listOf("关闭", "离线机翻", "自定义 API"), group.choices)
        assertFalse(texts(root).any { it.text.toString().startsWith("原语言：") })
        assertFalse(texts(root).any { it.text.toString().startsWith("打开离线翻译") })
        assertEquals(1,views(root).filterIsInstance<android.widget.EditText>().size)
        assertTrue(texts(root).any { it.text=="已下载语言包" })
        assertTrue(texts(root).any { it.text=="检测离线翻译" })
    }
    @Test fun simplifiedChineseUsesTheSharedLightweightToggleAndPersistsBothDirections() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        val policy = com.tcrrry.lyrics.engine.lyrics.LyricsPreference
        var refreshes = 0
        CurrentLyricsSourceStatus.installRefreshHandler { _, _ -> refreshes++; true }
        try {
            assertFalse(policy.simplifiedChinese(activity))
            openMenu(page(activity), "翻译与注音")
            val root = ShadowAlertDialog.getLatestAlertDialog().window!!.decorView
            val toggle = texts(root).single { it.contentDescription == "繁体转简体" }
            assertNull(SettingsGlassMaterial.style(toggle))
            assertFalse(toggle.isSelected)
            val revision = policy.revision(activity)
            toggle.performClick()
            assertTrue(toggle.isSelected)
            assertTrue(policy.simplifiedChinese(activity))
            assertEquals(revision + 1, policy.revision(activity))
            toggle.performClick()
            assertFalse(policy.simplifiedChinese(activity))
            assertEquals(2, refreshes)
        } finally { CurrentLyricsSourceStatus.installRefreshHandler { _, _ -> false } }
    }

    @Test fun languageSearchFiltersAndSelectsWithoutResizingItsPopup() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        var selected = ""
        TcrrryLyricsSettingsUi.showLanguageSearch(activity, listOf("auto","en","ja","ko"), "auto") { selected=it }
        val dialog=ShadowAlertDialog.getLatestAlertDialog()
        val root=dialog.window!!.decorView
        val input=views(root).filterIsInstance<android.widget.EditText>().single()
        assertEquals(SettingsGlassKind.Field,SettingsGlassMaterial.style(input)?.kind)
        input.setText("Japanese")
        assertTrue(texts(root).any { it.text == "日语 · ja" })
        assertFalse(texts(root).any { it.text == "英语 · en" })
        input.setText("不存在的语言")
        assertTrue(texts(root).any { it.text == "未找到匹配语言" })
        assertEquals("",selected)
        input.setText("日语")
        texts(root).single { it.text == "日语 · ja" }.performClick()
        assertEquals("ja",selected); assertFalse(dialog.isShowing)
    }

    @Test fun updateReminderPreferencesAndPopupUseTheSharedGlassControls() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        openMenu(page(activity),"更多工具")
        val tools=ShadowAlertDialog.getLatestAlertDialog()
        val selector=texts(tools.window!!.decorView).single { it.contentDescription == "新版本自动提醒" }
        assertTrue(selector.isSelected)
        assertNull(SettingsGlassMaterial.style(selector))
        selector.performClick()
        assertFalse(com.tcrrry.lyrics.plugin.ReleaseUpdates.automatic(activity))
        selector.performClick()
        assertTrue(com.tcrrry.lyrics.plugin.ReleaseUpdates.automatic(activity))
        tools.dismiss()
        var clicks=0
        val body=TcrrryLyricsSettingsUi.updateReminderBody(activity,"有新版本","当前版本",listOf("查看并下载更新" to { clicks++ },"稍后提醒" to { clicks++ }))
        val actions=texts(body).filter { it.isClickable }
        assertEquals(2,actions.size)
        assertTrue(actions.all { SettingsGlassMaterial.style(it)?.kind == SettingsGlassKind.Button })
        assertTrue(SettingsGlassMaterial.style(actions.first())!!.filled)
        actions.last().performClick(); assertEquals(1,clicks)
    }

    @Test fun pronunciationChoicesUseLightweightNativeButtonsAndPersistTheirOriginalKeys() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        activity.pluginPreferences("japanese_pronunciation",Context.MODE_PRIVATE).edit()
            .putBoolean("enabled",false).putBoolean("korean_enabled",false).putBoolean("cantonese_321",false).commit()
        openMenu(page(activity),"翻译与注音")
        val dialog=ShadowAlertDialog.getLatestAlertDialog()
        assertEquals(1,views(dialog.window!!.decorView).filterIsInstance<SettingsSegmentedControl>().size)
        for ((heading,key) in listOf("日语注音" to "enabled","韩语注音" to "korean_enabled","当前歌曲粤语注音" to "cantonese_321")) {
            val button=texts(dialog.window!!.decorView).single { it.contentDescription == heading }
            assertNull(SettingsGlassMaterial.style(button))
            button.performClick()
            assertTrue(activity.pluginPreferences("japanese_pronunciation",Context.MODE_PRIVATE).getBoolean(key,false))
            button.performClick()
            assertFalse(activity.pluginPreferences("japanese_pronunciation",Context.MODE_PRIVATE).getBoolean(key,true))
        }
        dialog.dismiss()
    }

    @Test fun settingsUseSoftScrollEdgesWithoutAScrollbarOrFooterDivider() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        val body = page(activity)
        val dialog = TcrrryLyricsSettingsUi.mainDialog(activity, body)
        dialog.show()
        val scroll = body.parent as android.widget.ScrollView
        assertFalse(scroll.isVerticalScrollBarEnabled)
        assertTrue(scroll.isVerticalFadingEdgeEnabled)
        assertEquals((18 * activity.resources.displayMetrics.density).toInt(), scroll.verticalFadingEdgeLength)
        val footer = texts(dialog.window!!.decorView).single { it.text == "关闭" }.parent as ViewGroup
        assertEquals(1, footer.childCount)
        dialog.dismiss()
        activity.finish()
    }
    @Test fun cacheControlsLiveInACompactToolsSubmenuWithOneGbMaximum() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        val body = page(activity)
        openMenu(body, "更多工具")
        val tools = ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(texts(tools.window!!.decorView).single { it.text == "歌词缓存" }.performClick())
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        val contents = dialog.window!!.decorView
        assertTrue(texts(contents).any { it.text == "缓存上限 · 64 MB" })
        assertTrue(texts(contents).any { it.text == "清理歌词缓存" })
        assertEquals(64, views(contents).filterIsInstance<android.widget.SeekBar>().single().max)
        dialog.dismiss(); tools.dismiss()
    }

    @Test fun compactHomeRoutesCurrentSourceToItsOwnPage() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        val parent = page(activity)
        val home = texts(parent).map { it.text.toString() }
        for (heading in listOf("歌词偏好", "翻译与注音", "歌词效果", "操作说明"))
            assertTrue(home.contains(heading))
        assertTrue(home.any { it.contains("一首很长的歌曲名称") })
        assertFalse(home.any { it == "外观" || it.contains("双击寻找") || it == "恢复自动选源" })
        assertFalse(home.any { it.startsWith("当前来源") })
        val sourceText = texts(parent).single { it.text == CurrentLyricsSourceStatus.description(activity, 321) }
        assertNull(sourceText.ellipsize)
        assertTrue(sourceText.parent is SmoothSourceTicker)
        val source = sourceText.parent.parent as View
        assertTrue(source.isClickable)
        assertTrue(source.performClick())
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        val menu = texts(dialog.window!!.decorView).map { it.text.toString() }
        for (heading in listOf("选择歌词源", "恢复自动选源", "匹配历史与预览", "歌词偏移"))
            assertTrue(menu.contains(heading))
        dialog.dismiss()
        val width = (300 * activity.resources.displayMetrics.density).toInt()
        parent.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        parent.layout(0, 0, width, parent.measuredHeight)
        for (i in 1 until parent.childCount)
            assertTrue(parent.getChildAt(i - 1).bottom <= parent.getChildAt(i).top)
    }

    @Test @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun sourceDetailsWrapTheFullDescriptionInsteadOfUsingAnotherMarquee() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        val description = "Apple Music 原生 · 逐字歌词 · 网易云音乐译文 · 离线日语字词对应发音 · 补充注音信息"
        val row = TcrrryLyricsSettingsUi.sourceStatusLabel(activity, { description }, expanded = true)
        val width = (220 * activity.resources.displayMetrics.density).toInt()
        row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        row.layout(0, 0, row.measuredWidth, row.measuredHeight)
        val text = texts(row).single()
        assertEquals(description, text.text.toString())
        assertNull(text.ellipsize)
        assertFalse(views(row).any { it is SmoothSourceTicker })
        assertTrue(text.layout.lineCount > 1)
        assertTrue(text.height <= row.height)
        assertTrue(row.width <= width)
        activity.finish()
    }

    @Test fun matchingDetailsKeepSelectableFullInformation() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        TcrrryLyricsSettingsUi.showMatchingInformation(activity, 321)
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        val all = texts(dialog.window!!.decorView)
        assertTrue(all.any { it.text == "当前匹配信息" })
        assertTrue(all.any { it.isTextSelectable && it.text.toString().contains("321") })
        assertNull(dialog.listView)
        all.single { it.text == "关闭" }.performClick()
        assertFalse(dialog.isShowing)
    }

    @Test fun preferencesMoveToSecondLevelAndPreserveIndependentChoices() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        val policy = com.tcrrry.lyrics.engine.lyrics.LyricsPreference
        policy.update(activity, quality = true)
        policy.updateRubyPronunciation(activity, true)
        openMenu(page(activity), "歌词偏好")
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        val labels = texts(dialog.window!!.decorView).map { it.text.toString() }
        for (heading in listOf("匹配优先级", "发音排版", "质量优先", "字词对应"))
            assertTrue(labels.contains(heading))
        val credits = texts(dialog.window!!.decorView).single { it.contentDescription == "隐藏开头署名" }
        assertFalse(credits.isSelected)
        credits.performClick()
        assertTrue(policy.hideOpeningCredits(activity))
        credits.performClick()
        assertFalse(policy.hideOpeningCredits(activity))
        val originalGroups = views(dialog.window!!.decorView).filterIsInstance<SettingsSegmentedControl>()
        texts(dialog.window!!.decorView).single { it.contentDescription == "发音排版：整行发音" }.performClick()
        assertSame(dialog, ShadowAlertDialog.getLatestAlertDialog())
        assertFalse(policy.rubyPronunciation(activity))
        texts(dialog.window!!.decorView).single { it.contentDescription == "发音排版：字词对应" }.performClick()
        assertTrue(policy.rubyPronunciation(activity))
        val visible = texts(dialog.window!!.decorView).map { it.text.toString() }
        assertFalse(visible.contains("自动匹配歌词"))
        assertFalse(visible.contains("单曲循环歌词复位"))
        activity.pluginPreferences("tcrrry_lyrics_policy", Context.MODE_PRIVATE).edit()
            .putBoolean("force_repeat_reset", false).commit()
        assertTrue(policy.forceRepeatReset(activity))
        assertTrue(policy.rubyPronunciation(activity))
        assertTrue(policy.qualityFirst(activity))
        assertEquals(originalGroups, views(dialog.window!!.decorView).filterIsInstance<SettingsSegmentedControl>())
        dialog.dismiss()
    }

    @Test fun guideHasMenuIllustrationAndThreeGesturePagesAndFirstRunRequiresCompletion() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        TcrrryLyricsSettingsUi.showOperationGuide(activity, required = true)
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        dialog.onBackPressed()
        assertTrue(dialog.isShowing)
        val prefs = activity.pluginPreferences("lyrics_operation_guide", Context.MODE_PRIVATE)
        assertFalse(prefs.getBoolean("completed", false))
        val root = dialog.window!!.decorView
        val flipper = views(root).filterIsInstance<android.widget.ViewFlipper>().single()
        assertEquals(0, flipper.displayedChild)
        assertEquals(4, flipper.childCount)
        assertTrue(flipper.measureAllChildren)
        val illustration = views(flipper.getChildAt(0)).filterIsInstance<GuideSourceMenuIllustration>().single()
        assertTrue(illustration.imageAvailable)
        illustration.measure(View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(210, View.MeasureSpec.EXACTLY))
        illustration.layout(0,0,240,210)
        assertTrue(illustration.sourceHighlight.left >= 0)
        assertTrue(illustration.sourceHighlight.right <= illustration.width)
        assertTrue(illustration.sourceHighlight.top > illustration.height / 2)
        assertTrue(illustration.sourceHighlight.bottom < illustration.height)
        texts(root).single { it.text == "下一页" }.performClick()
        assertEquals(1, flipper.displayedChild)
        assertNotNull(flipper.inAnimation)
        assertNotNull(flipper.outAnimation)
        assertEquals(240L, flipper.inAnimation.duration)
        texts(root).single { it.text == "上一页" }.performClick()
        assertEquals(0, flipper.displayedChild)
        texts(root).single { it.text == "下一页" }.performClick()
        texts(root).single { it.text == "下一页" }.performClick()
        assertEquals(2, flipper.displayedChild)
        texts(root).single { it.text == "下一页" }.performClick()
        assertEquals(3, flipper.displayedChild)
        texts(root).single { it.text == "开始使用" }.performClick()
        assertTrue(prefs.getBoolean("completed", false))
        assertFalse(dialog.isShowing)
    }

    @Test
    @Config(qualifiers = "w420dp-h900dp")
    fun nestedPopupsShareTheNarrowWidthWhileTheMainPageKeepsItsOriginalWidth() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        val parent = page(activity)
        val sourceText = texts(parent).single { it.text == CurrentLyricsSourceStatus.description(activity, 321) }
        (sourceText.parent.parent as View).performClick()
        val sourceDialog = ShadowAlertDialog.getLatestAlertDialog()
        val sourceTitle = texts(sourceDialog.window!!.decorView).single { it.text == "当前歌曲歌词源" }
        assertFalse(texts(sourceDialog.window!!.decorView).single { it.text == "重置偏移" }.isEnabled)
        val sourcePanel = sourceTitle.parent.parent.parent as View
        openMenu(sourceDialog.window!!.decorView, "选择歌词源")
        val picker = ShadowAlertDialog.getLatestAlertDialog()
        val pickerTitle = texts(picker.window!!.decorView).single { it.text == "选择歌词源" }
        val pickerPanel = pickerTitle.parent.parent.parent as View
        assertEquals(sourcePanel.layoutParams.width, pickerPanel.layoutParams.width)
        val density = activity.resources.displayMetrics.density
        assertTrue(sourcePanel.layoutParams.width < activity.resources.displayMetrics.widthPixels - (48 * density).toInt())
        assertTrue(sourceDialog.isShowing)
        val provider = com.tcrrry.lyrics.engine.hook.LyricsSourceMenuPolicy.thirdPartySources.first()
        texts(picker.window!!.decorView).single { it.text == provider }.performClick()
        assertFalse(picker.isShowing)
        assertTrue(sourceDialog.isShowing)
        assertTrue(texts(sourceDialog.window!!.decorView).any { it.text == provider })
        sourceDialog.dismiss()
        for (heading in listOf("歌词偏好", "翻译与注音", "歌词效果", "更多工具", "操作说明")) {
            openMenu(parent, heading)
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            val title = texts(dialog.window!!.decorView).single {
                it.text == if (heading == "歌词效果") "歌词辉光增强" else heading
            }
            val panel = title.parent.parent.parent as View
            assertEquals("Width of $heading", pickerPanel.layoutParams.width, panel.layoutParams.width)
            dialog.dismiss()
        }
        val main = TcrrryLyricsSettingsUi.mainDialog(activity, parent)
        main.show()
        val mainPanel = parent.parent.parent as View
        assertEquals(activity.resources.displayMetrics.widthPixels - (48 * density).toInt(), mainPanel.layoutParams.width)
        assertTrue(mainPanel.layoutParams.width > pickerPanel.layoutParams.width)
        main.dismiss()
    }

    private fun assertMeasuredPopupIsNarrowerThanMain(activity: Activity) {
        val body = page(activity)
        val main = TcrrryLyricsSettingsUi.mainDialog(activity, body)
        main.show()
        fun measure(dialog: android.app.AlertDialog) {
            val root = dialog.window!!.decorView
            val metrics = activity.resources.displayMetrics
            root.measure(View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, metrics.widthPixels, metrics.heightPixels)
        }
        measure(main)
        val mainPanel = body.parent.parent as View
        val density = activity.resources.displayMetrics.density
        assertEquals(minOf(activity.resources.displayMetrics.widthPixels - (48 * density).toInt(),
            (520 * density).toInt()), mainPanel.width)
        for (heading in listOf("歌词偏好", "翻译与注音", "歌词效果", "更多工具", "操作说明")) {
            openMenu(body, heading)
            val popup = ShadowAlertDialog.getLatestAlertDialog()
            measure(popup)
            val title = texts(popup.window!!.decorView).single {
                it.text == if (heading == "歌词效果") "歌词辉光增强" else heading
            }
            val panel = title.parent.parent.parent as View
            assertTrue("$heading must be narrower than main", panel.width < mainPanel.width)
            assertTrue("$heading must leave visible side margins", panel.width <= mainPanel.width - (24 * density).toInt())
            popup.dismiss()
        }
        main.dismiss()
    }

    @Test @Config(qualifiers = "w320dp-h800dp")
    fun narrowPhoneStillKeepsMainAndPopupWidthsDistinct() {
        assertMeasuredPopupIsNarrowerThanMain(Robolectric.buildActivity(Activity::class.java).setup().visible().get())
    }

    @Test @Config(qualifiers = "w420dp-h900dp")
    fun phoneMainAndPopupWidthsRemainDistinctAfterLayout() {
        assertMeasuredPopupIsNarrowerThanMain(Robolectric.buildActivity(Activity::class.java).setup().visible().get())
    }

    @Test @Config(qualifiers = "w720dp-h1024dp")
    fun tabletMainAndPopupWidthsRemainDistinctAfterLayout() {
        assertMeasuredPopupIsNarrowerThanMain(Robolectric.buildActivity(Activity::class.java).setup().visible().get())
    }

    @Test
    @Config(qualifiers = "w420dp-h900dp")
    fun translationModesKeepTheSamePanelBoundsAndScrollLongContent() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        openMenu(page(activity), "翻译与注音")
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        val root = dialog.window!!.decorView
        val title = texts(root).single { it.text == "翻译与注音" }
        val panel = title.parent.parent.parent as View
        val metrics = activity.resources.displayMetrics
        fun layout() {
            root.measure(View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, metrics.widthPixels, metrics.heightPixels)
        }
        layout()
        val bounds = android.graphics.Rect(panel.left, panel.top, panel.right, panel.bottom)
        val originalSelector = views(root).filterIsInstance<SettingsSegmentedControl>().single { it.heading == "补充翻译" }
        for (mode in listOf("离线机翻", "自定义 API", "关闭", "自定义 API", "离线机翻")) {
            originalSelector.labels.single { it.text == mode }.performClick()
            layout()
            assertEquals(mode, bounds, android.graphics.Rect(panel.left, panel.top, panel.right, panel.bottom))
            assertSame(originalSelector, views(root).filterIsInstance<SettingsSegmentedControl>().single { it.heading == "补充翻译" })
        }
        texts(root).single { it.text == "自定义 API" }.performClick()
        layout()
        val scroll = views(root).filterIsInstance<android.widget.ScrollView>().single()
        assertTrue(scroll.getChildAt(0).height > scroll.height)
        scroll.fullScroll(View.FOCUS_DOWN)
        assertTrue(scroll.scrollY > 0)
        dialog.dismiss()
    }

    @Test fun apiProfilePromptsValidateNamesAndCancelWithoutChangingProfiles() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        openMenu(page(activity), "翻译与注音")
        val languageDialog = ShadowAlertDialog.getLatestAlertDialog()
        texts(languageDialog.window!!.decorView).single { it.text == "自定义 API" }.performClick()
        val policy = com.tcrrry.lyrics.sources.TranslationApiProfiles
        val count = policy.all(activity).size
        fun addPrompt() {
            texts(languageDialog.window!!.decorView).single { it.text.toString().endsWith("⌄") }.performClick()
            val picker = ShadowAlertDialog.getLatestAlertDialog()
            texts(picker.window!!.decorView).single { it.text == "＋ 添加自定义 API" }.performClick()
        }
        addPrompt()
        val canceled = ShadowAlertDialog.getLatestAlertDialog()
        assertFalse(texts(canceled.window!!.decorView).single { it.text == "保存" }.isEnabled)
        texts(canceled.window!!.decorView).single { it.text == "取消" }.performClick()
        assertEquals(count, policy.all(activity).size)
        assertTrue(languageDialog.isShowing)
        addPrompt()
        val saved = ShadowAlertDialog.getLatestAlertDialog()
        views(saved.window!!.decorView).filterIsInstance<android.widget.EditText>().single().setText("  我的服务  ")
        texts(saved.window!!.decorView).single { it.text == "保存" }.performClick()
        assertFalse(saved.isShowing)
        assertTrue(policy.all(activity).any { it.label == "我的服务" })
        texts(languageDialog.window!!.decorView).single { it.text == "管理当前 API 配置" }.performClick()
        val management = ShadowAlertDialog.getLatestAlertDialog()
        texts(management.window!!.decorView).single { it.text == "删除此服务及其密钥" }.performClick()
        val confirmation = ShadowAlertDialog.getLatestAlertDialog()
        texts(confirmation.window!!.decorView).single { it.text == "取消" }.performClick()
        assertTrue(policy.all(activity).any { it.label == "我的服务" })
        languageDialog.dismiss()
    }

    @Test fun mainCloseButtonBackAndScrimAllWaitForTheExitAnimation() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        val clock = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
        for (path in listOf("button", "back", "scrim")) {
            val body = page(activity)
            val dialog = TcrrryLyricsSettingsUi.mainDialog(activity, body)
            var dismissals = 0
            dialog.setOnDismissListener { dismissals++ }
            dialog.show()
            val root = dialog.window!!.decorView
            val metrics = activity.resources.displayMetrics
            root.measure(View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, metrics.widthPixels, metrics.heightPixels)
            val panel = body.parent.parent as View
            panel.viewTreeObserver.dispatchOnPreDraw()
            panel.viewTreeObserver.dispatchOnPreDraw()
            clock.idleFor(java.time.Duration.ofSeconds(1))
            assertEquals(1f, panel.alpha, .001f)
            when (path) {
                "button" -> texts(root).single { it.text == "关闭" }.performClick()
                "back" -> @Suppress("DEPRECATION") dialog.onBackPressed()
                "scrim" -> (panel.parent as ViewGroup).getChildAt(0).performClick()
            }
            assertTrue("Exit must animate: $path", dialog.isShowing)
            dialog.dismiss()
            clock.idleFor(java.time.Duration.ofSeconds(1))
            assertFalse("Exit must finish: $path", dialog.isShowing)
            assertEquals(1, dismissals)
            assertEquals(0f, panel.alpha, .001f)
        }
    }

    @Test fun mainCardAndCommandsUseGlassWhileBinaryButtonsStayNative() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        val body = page(activity)
        val song = texts(body).single { it.text.toString().startsWith("一首很长的歌曲") }
        assertEquals(SettingsGlassKind.Card, SettingsGlassMaterial.style(song.parent as View)?.kind)
        fun checkCommands(root: View) {
            val commands = views(root).filter { view -> view.isClickable &&
                (view is LinearLayout || view is TextView && !view.isTextSelectable) }
            assertTrue(commands.isNotEmpty())
            commands.forEach { view ->
                val style = SettingsGlassMaterial.style(view)
                if (view.contentDescription in setOf("繁体转简体", "隐藏开头署名", "日语注音", "韩语注音", "当前歌曲粤语注音", "新版本自动提醒", "强制覆盖循环方式", "短单元平滑")) {
                    assertNull(style)
                    return@forEach
                }
                assertNotNull("Missing material: ${(view as? TextView)?.text ?: view.contentDescription}", style)
                assertEquals(if (view is android.widget.EditText) SettingsGlassKind.Field else SettingsGlassKind.Button,
                    style!!.kind)
            }
        }
        val main = TcrrryLyricsSettingsUi.mainDialog(activity, body)
        main.show()
        checkCommands(main.window!!.decorView)
        for (heading in listOf("歌词偏好", "翻译与注音", "歌词效果", "更多工具", "操作说明")) {
            openMenu(body, heading)
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            if (heading == "翻译与注音") texts(dialog.window!!.decorView).single { it.text == "自定义 API" }.performClick()
            checkCommands(dialog.window!!.decorView)
            dialog.dismiss()
        }
        main.dismiss()
    }

    @Test
    @Config(qualifiers = "w320dp-h800dp")
    fun narrowScreenAndLargeFontsDoNotClipTranslationModeButtons() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        val configuration = android.content.res.Configuration(activity.resources.configuration).apply { fontScale = 1.7f }
        @Suppress("DEPRECATION")
        activity.resources.updateConfiguration(configuration, activity.resources.displayMetrics)
        openMenu(page(activity), "翻译与注音")
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        val root = dialog.window!!.decorView
        val metrics = activity.resources.displayMetrics
        root.measure(View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, metrics.widthPixels, metrics.heightPixels)
        for (caption in listOf("关闭", "离线机翻", "自定义 API")) {
            val button = views(root).filterIsInstance<SettingsSegmentedControl>().single { it.heading == "补充翻译" }
                .labels.single { it.text == caption }
            val layout = requireNotNull(button.layout)
            assertTrue("Clipped height: $caption", button.height >= layout.height + button.compoundPaddingTop + button.compoundPaddingBottom)
            for (line in 0 until layout.lineCount) assertTrue("Clipped width: $caption",
                layout.getLineWidth(line) <= button.width - button.compoundPaddingLeft - button.compoundPaddingRight + 1)
        }
        dialog.dismiss()
    }
}
