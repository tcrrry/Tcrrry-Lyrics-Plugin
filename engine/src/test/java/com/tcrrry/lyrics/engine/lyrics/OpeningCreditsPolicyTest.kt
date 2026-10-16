package com.tcrrry.lyrics.engine.lyrics

import org.junit.Assert.*
import org.junit.Test

class OpeningCreditsPolicyTest {
    @Test fun copyrightNamesOnlyQualifyAnExplicitOpOrSpRole() {
        for (label in listOf("词OP（石天天）","曲SP(某作者)","OP（出版方）"))
            assertEquals(setOf(0),OpeningCreditsPolicy.hiddenIndices("QQ音乐",listOf("$label：出版社","正文"),true))
        for (label in listOf("你OP（朋友）","词OP（未闭合","OP不是我"))
            assertTrue(OpeningCreditsPolicy.hiddenIndices("QQ音乐",listOf("$label：这是歌词","正文"),true).isEmpty())
    }
    @Test fun productionQualifiersComposeWithoutGreedilyRemovingKnownRoles() {
        val labels=listOf("人声和声", "人声录音", "乐器录音", "制作企划", "歌曲统筹", "音乐制作公司", "项目统筹", "音乐出品",
            "後期音樂製作公司", "联合音乐制作人", "人声和声Harmony", "乐器录音Recording Engineer")
        assertEquals(labels.indices.toSet(), OpeningCreditsPolicy.hiddenIndices("网易云音乐", labels.map { "$it：署名" } + "正式正文", true))
        for (lyric in listOf("音乐不是制作：这是歌词", "项目之外：仍然是歌词", "人声里的故事：仍然是歌词"))
            assertTrue(OpeningCreditsPolicy.hiddenIndices("QQ音乐", listOf(lyric,"作曲：某人","正文"),true).isEmpty())
    }
    @Test fun ostParenthesesOnlyNormalizeAnOtherwiseMatchingSongHeader() {
        val lines=listOf("作词：作者","作曲：作者","将安（《与晋长安》插曲） - 黄霄雲","词：作者","曲：作者","正式正文")
        assertEquals((0..4).toSet(),OpeningCreditsPolicy.hiddenIndices("网易云音乐",lines,true,"将安","黄霄雲",listOf(0,0,0,3060,3730,32600)))
        assertEquals((0..4).toSet(),OpeningCreditsPolicy.hiddenIndices("QQ音乐",lines,true,"将安 (影视剧插曲)","黄霄雲"))
        assertEquals(setOf(0,1),OpeningCreditsPolicy.hiddenIndices("QQ音乐",lines,true,"另一首歌","黄霄雲"))
        assertEquals(setOf(0,1),OpeningCreditsPolicy.hiddenIndices("QQ音乐",lines,true,"将安","黄霄雲",listOf(0,0,12000,13000,14000,32600)))
        assertTrue(OpeningCreditsPolicy.hiddenIndices("QQ音乐",listOf("将安（我会唱出的这一句）","第二句"),true,"将安").isEmpty())
        assertTrue(OpeningCreditsPolicy.hiddenIndices("QQ音乐",listOf("将安(未闭合 - 黄霄雲","词：作者","曲：作者","正文"),true,"将安").isEmpty())
    }
    @Test fun laterProductionRolesNoLongerBreakTheOpeningBlock() {
        val lines=listOf("作词：作者", "监制Supervisor：作者", "录音师Recording Engineer：录音师",
            "后期母带处理制作人Mastering Producer：作者", "混音母带Mixing/Mastering：工程师", "正式正文")
        assertEquals((0..4).toSet(),OpeningCreditsPolicy.hiddenIndices("QQ音乐",lines,true))
    }
    @Test fun aDenseProductionLabelBetweenKnownCreditsCanBeRecognizedByContext() {
        val lines=listOf("作词：作者", "声乐录音协调Vocal Recording Coordinator：某人", "编曲：某人", "真正的歌词")
        assertEquals(setOf(0,1,2),OpeningCreditsPolicy.hiddenIndices("QQ音乐",lines,true,starts=listOf(0,800,1500,40000)))
        assertEquals(setOf(0,1,2),OpeningCreditsPolicy.hiddenIndices("QQ音乐",lines,true,starts=listOf(0,20000,40000,60000)))
        assertEquals(setOf(0),OpeningCreditsPolicy.hiddenIndices("QQ音乐",listOf("作词：作者","你说：我为你录音","编曲：某人","正文"),true,starts=listOf(0,800,1500,40000)))
    }
    @Test fun aSmallRunOfUnknownProductionLabelsCanShareStrongContextButCannotCrossPlainLyrics() {
        val lines=listOf("作词：作者","声乐录音协调Vocal Recording Coordinator：某人","音乐版权协助Music Rights Assistant：某人","编曲：作者","正文")
        assertEquals((0..3).toSet(),OpeningCreditsPolicy.hiddenIndices("QQ音乐",lines,true,starts=listOf(0,800,1600,2400,40000)))
        val withLyric=lines.toMutableList().apply { this[2]="我把所有的话都说给你听" }
        assertEquals(setOf(0),OpeningCreditsPolicy.hiddenIndices("QQ音乐",withLyric,true,starts=listOf(0,800,1600,2400,40000)))
    }
    @Test fun matchingTitleAllowsArtistAliasesWithoutDisablingAllLaterCredits() {
        val lines=listOf("测试歌 - 歌手 (艺名)","作词：作者","作曲：作者","正式正文")
        assertEquals(setOf(0,1,2),OpeningCreditsPolicy.hiddenIndices("QQ音乐",lines,true,"测试歌","歌手"))
    }
    @Test fun mixedChineseEnglishLabelsAndProductionRolesAreRecognized() {
        val labels = listOf("词Lyricist", "曲Composer", "制作人Music Producer", "编曲Arrangement",
            "键盘Keyboard", "吉他Guitar", "鼓Drum", "贝斯Bass", "弦乐Strings", "和声编写Backing Vocal",
            "和声Harmony", "配唱Vocal Producer", "监制Producer", "录音师Recording Engineer", "人声编辑Vocal Editing")
        val lines = labels.map { "$it：测试署名" } + "正式正文"
        assertEquals(labels.indices.toSet(), OpeningCreditsPolicy.hiddenIndices("QQ音乐", lines, true))
    }
    @Test fun traditionalFullwidthCombinedAndEnglishBylinesWorkWithoutSongSpecificNames() {
        val lines = listOf("\uFEFF作詞・作曲：作者", "編曲（Arranged by）：作者", "Lyrics & Music: Author",
            "Lyrics by Author", "Music by Author", "Ｍａｓｔｅｒｉｎｇ　Ｅｎｇｉｎｅｅｒ：Engineer", "正文")
        assertEquals((0..5).toSet(), OpeningCreditsPolicy.hiddenIndices("网易云音乐", lines, true))
    }
    @Test fun titleHeaderNeedsSongIdentityAndCreditContext() {
        val lines = listOf("测试歌 - 测试歌手", "作词：作者", "作曲：作者", "正式正文")
        assertEquals(setOf(0,1,2), OpeningCreditsPolicy.hiddenIndices("QQ音乐", lines, true, "测试歌", "测试歌手"))
        assertTrue(OpeningCreditsPolicy.hiddenIndices("QQ音乐", lines, true, "另一首歌", "测试歌手").isEmpty())
        assertTrue(OpeningCreditsPolicy.hiddenIndices("QQ音乐", listOf("测试歌", "真正的正文"), true, "测试歌", "测试歌手").isEmpty())
    }
    @Test fun timingOnlyStrengthensAnAlreadyMatchingHeaderNeverDeletesFastLyrics() {
        val lines = listOf("测试歌 - 测试歌手", "作词：作者", "正文")
        assertEquals(setOf(0,1), OpeningCreditsPolicy.hiddenIndices("QQ音乐", lines, true, "测试歌", "测试歌手", listOf(0,800,40000)))
        assertTrue(OpeningCreditsPolicy.hiddenIndices("QQ音乐", lines, true, "测试歌", "测试歌手", listOf(0,20000,40000)).isEmpty())
        val fast = listOf("一大串非常快速的真实歌词", "另一大串快速的歌词", "仍然是正文")
        assertTrue(OpeningCreditsPolicy.hiddenIndices("QQ音乐", fast, true, starts=listOf(0,300,600)).isEmpty())
    }
    @Test fun unrecognizedBilingualPhrasesAndOrdinaryColonDialogueStopFiltering() {
        for (text in listOf("你说：别走", "制作人不是我：这是正文", "词I love you：正文", "作曲家：")) {
            assertTrue(OpeningCreditsPolicy.hiddenIndices("网易云音乐", listOf(text, "编曲：某人", "正文"), true).isEmpty())
        }
    }
    @Test fun explicitOpeningCreditsIncludingCombinedRolesAreHidden() {
        val lines = listOf("作词/作曲：张三", "编曲: 李四", "发行公司：某某音乐", "OP：Some Publisher", "这是第一句")
        assertEquals(setOf(0, 1, 2, 3), OpeningCreditsPolicy.hiddenIndices("QQ音乐", lines, true))
    }
    @Test fun unknownTextAndSungWordsStopTheOpeningBlock() {
        val lines = listOf("作曲：张三", "你为我作词", "编曲：故事的一部分")
        assertEquals(setOf(0), OpeningCreditsPolicy.hiddenIndices("网易云音乐", lines, true))
        assertTrue(OpeningCreditsPolicy.hiddenIndices("QQ音乐", listOf("张三", "作曲：李四", "你好"), true).isEmpty())
    }
    @Test fun switchAndSourceBoundaryPreserveOriginal() {
        val lines = listOf("作曲：张三", "你好")
        assertTrue(OpeningCreditsPolicy.hiddenIndices("QQ音乐", lines, false).isEmpty())
        for (source in listOf("Apple Music", "LRCLIB", ""))
            assertTrue(OpeningCreditsPolicy.hiddenIndices(source, lines, true).isEmpty())
    }
    @Test fun incompleteLabelsAndAllCreditResponsesAreRetained() {
        for (lines in listOf(listOf("作曲：", "你好"), listOf("作曲：张三"), listOf("叙述者：你好", "正文")))
            assertTrue(OpeningCreditsPolicy.hiddenIndices("QQ音乐", lines, true).isEmpty())
    }
}
