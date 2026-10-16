package com.tcrrry.lyrics.engine.lyrics

import org.junit.Assert.*
import org.junit.Test

class OpeningCreditsBlockTest {
    private fun hidden(lines: List<String>, starts: List<Long> = emptyList()) =
        OpeningCreditsPolicy.hiddenIndices("QQ音乐",lines,true,starts=starts)

    @Test fun unknownJobsAndInstrumentsUseBlockStructureNotAnEverGrowingDictionary() {
        val unknown=(1..12).map { "未收录职责$it Unknown Role $it：署名$it" }
        val lines=listOf("作词：甲","作曲：乙") + unknown + listOf("混音：丙","第一句正文")
        assertEquals((0 until lines.lastIndex).toSet(),hidden(lines))
    }
    @Test fun finalUnknownFieldDoesNotLeaveALoneCreditAfterTheBlock() {
        assertEquals(setOf(0,1,2),hidden(listOf("作词：甲","作曲：乙","全新乐器：演奏者","第一句正文")))
        assertEquals(setOf(0,1,2),hidden(listOf("作词：甲","作曲：乙","全新乐器：演奏者","我终于开始唱第一句")))
    }
    @Test fun unknownFieldsBeforeKnownAnchorsCanBelongToTheSameOpeningBlock() {
        assertEquals((0..3).toSet(),hidden(listOf("特别贡献：甲","未收录职务：乙","作词：丙","作曲：丁","正文")))
    }
    @Test fun noColonCreditsAcceptBoundedSeparatorsAndEnglishBylines() {
        val lines=listOf("作词 张三","作曲-李四","编曲（王五）","制作人 / 赵六",
            "Recording Engineer Alice Smith","Mixed by Alex","Produced by Bob","Performed by Chris",
            "作词/作曲 张三","录音师/李四","编曲_王五","正文")
        assertEquals((0 until lines.lastIndex).toSet(),hidden(lines))
    }
    @Test fun bareRolesAndFollowingNamesStayPaired() {
        assertEquals((0..5).toSet(),hidden(listOf("作词","张三","作曲","李四","制作人","Alice Smith","第一句正文")))
        assertTrue(hidden(listOf("作曲","我把梦想唱给你听","第二句正文")).isEmpty())
        assertTrue(hidden(listOf("Music","Stay With Me","第二句正文")).isEmpty())
    }
    @Test fun wrappersAndNovelLatinAliasesDoNotNeedSeparateEntries() {
        val lines=listOf("【作词：张三】","（作曲：李四）","编曲 Novel Instrument Programmer：王五","正式正文")
        assertEquals(setOf(0,1,2),hidden(lines))
    }
    @Test fun separatorsInsideTheOpeningBlockDoNotBreakRecognition() {
        assertEquals((0..5).toSet(),hidden(listOf("作词：甲","#","作曲：乙","//","未知乐器：丙","---","正文")))
    }
    @Test fun ordinaryNoColonLyricsCannotBeConsumedJustBecauseTheyAreShort() {
        for (lyric in listOf("录音机里传来旧歌","编织着我们的未来","合唱团的孩子们在歌唱","梦 还没结束","12:30 想起你"))
            assertEquals(setOf(0,1),hidden(listOf("作词：甲","作曲：乙",lyric,"混音：故事中的台词","正文")))
    }
    @Test fun dialogueAndVocalPartsEndTheCreditBlockEvenWithSeveralColonRows() {
        for (lyric in listOf("你说：别走","他说：我爱你","男：拥抱你","女声：等着你","合：一起走",
            "旁白：从前有个人","He said: stay with me","鼓起勇气：向前走")) {
            assertEquals(lyric,setOf(0,1),hidden(listOf("作词：甲","作曲：乙",lyric,"你说：我愿意","编曲：正文中的词","下一句")))
        }
    }
    @Test fun colonDialogueWithoutCreditAnchorsIsNeverACreditBlock() {
        assertTrue(hidden(listOf("约定：不再回头","答案：我还爱你","正文")).isEmpty())
    }
    @Test fun repeatedUnknownColonTextIsProtectedAsPossibleSungContent() {
        assertEquals(setOf(0,1),hidden(listOf("作词：甲","作曲：乙","答案：我爱你","正文","答案：我爱你")))
    }
    @Test fun lateAndSlowCreditsAreNotRejectedByAnArbitrarySpeedLimit() {
        assertEquals((0..3).toSet(),hidden(listOf("作词：甲","作曲：乙","未知乐器：丙","最终新职位：丁","正文"),
            listOf(0,10000,32000,45000,60000)))
    }
    @Test fun anAllMetadataResponseIsStillKeptInsteadOfBecomingEmpty() {
        assertTrue(hidden(listOf("作词：甲","作曲：乙","未收录职务：丙")).isEmpty())
    }
}
