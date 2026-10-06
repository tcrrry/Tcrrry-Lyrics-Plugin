package com.tcrrry.lyrics.engine.hook

import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(manifest = org.robolectric.annotation.Config.NONE, sdk = [35])
class NativeLyricsTimedWordsTest {
    @org.junit.Before fun attachPlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.attach() }
    @org.junit.After fun closePlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.current.close() }
    @Test fun nestedStylingInheritsActualParentTimeAndCombinesSameEnvelope() {
        val parsed = requireNotNull(NativeLyricsTimedWords.parse("<span begin='1s' end='2s'><span>君</span><span>よ</span></span>"))
        assertEquals("君よ", parsed.text)
        assertEquals(listOf(Triple(1000L, 2000L, "君よ")), parsed.words)
    }
    @Test fun xmlEntitiesCommentsAndCdataDoNotEraseTextOrLoseTimes() {
        val parsed = requireNotNull(NativeLyricsTimedWords.parse("<!--style--><span begin='0:01.000' end='2000ms'>&#x541B;&amp;<![CDATA[よ]]></span>"))
        assertEquals("君&よ", parsed.text)
        assertEquals(listOf(Triple(1000L, 2000L, "君&よ")), parsed.words)
    }
    @Test fun untimedTextMissingEndsAndConflictingNestedRangesAreRejected() {
        for (body in listOf("君<span begin='1s' end='2s'>よ</span>",
            "<span begin='1s'>君</span>", "<span begin='2s' end='1s'>君</span>",
            "<span begin='1s' end='2s'><span begin='0s' end='3s'>君</span></span>"))
            assertNull(requireNotNull(NativeLyricsTimedWords.parse(body)).words)
        assertNull(NativeLyricsTimedWords.parse("<!DOCTYPE x SYSTEM 'file:///missing'><span>君</span>"))
    }
}
