package com.tcrrry.lyrics.engine.hook

import com.tcrrry.lyrics.sources.DirectLyricsRepository
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AuxiliaryEnrichmentProgressTest {
    private val body = """<body><div><p begin="1s" end="2s" itunes:key="L1"><span begin="1s" end="2s">君の声</span></p><p begin="3s" end="4s" itunes:key="L2"><span begin="3s" end="4s">僕にも</span></p></div></body>"""
    private fun base() = AutoLyricsCandidate("APPLE_NATIVE", requireNotNull(TtmlSubtitleTrack.attach(
        "<tt xmlns:itunes=\"urn:itunes\" xml:lang=\"ja\" itunes:timing=\"Word\">$body</tt>", mapOf(0 to "原生翻译"))))
    private val source = DirectLyricsRepository.Result(lyrics="[00:01.000]君の声\n[00:03.000]僕にも",
        translatedLyrics="[00:01.000]平台第一句\n[00:03.000]平台第二句",source="QQ音乐",score=100)
    @Test fun nativeThenPlatformThenMachineRegardlessOfCompletionOrder() {
        for (platformFirst in listOf(true,false)) {
            val progress = AuxiliaryEnrichmentProgress(base(), "offline")
            assertFalse(progress.missingPayload().contains("君の声"))
            if(platformFirst) progress.platformResult(source)
            progress.machineRow(JSONObject().put("id",0).put("text","机器第一句"))
            progress.machineRow(JSONObject().put("id",1).put("text","机器第二句"))
            if(!platformFirst) {
                assertEquals("机器第二句",TtmlSubtitleTrack.subtitleValues(progress.candidate().ttml)[1])
                progress.platformResult(source)
            }
            val result=progress.candidate()
            assertEquals(mapOf(0 to "原生翻译",1 to "平台第二句"),TtmlSubtitleTrack.subtitleValues(result.ttml))
            assertEquals(setOf("native","QQ音乐"),TtmlAuxiliaryOrigins.read(result.ttml)!!.translation)
            assertTrue(result.ttml.contains(body))
            assertFalse(progress.missingPlatform())
        }
    }
    @Test fun invalidOrUnrelatedPlatformResultsCannotDisplaceLocalFallback() {
        val progress=AuxiliaryEnrichmentProgress(base(),"offline")
        progress.machineRow(JSONObject().put("id",1).put("text","机器第二句"))
        progress.platformResult(source.copy(score=49))
        progress.platformResult(source.copy(lyrics="[00:01.000]無関係\n[00:03.000]別の曲"))
        assertEquals(mapOf(0 to "原生翻译",1 to "机器第二句"),TtmlSubtitleTrack.subtitleValues(progress.candidate().ttml))
        assertEquals(setOf("native","offline"),TtmlAuxiliaryOrigins.read(progress.candidate().ttml)!!.translation)
    }
}
