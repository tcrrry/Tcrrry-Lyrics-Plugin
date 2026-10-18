package com.tcrrry.lyrics.engine.hook

import com.tcrrry.lyrics.engine.model.CustomLyricsEntry
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executor

class CustomLyricsPresentationCacheTest {
    private class Queue:Executor {
        val tasks=java.util.ArrayDeque<Runnable>()
        override fun execute(command:Runnable) { tasks.add(command) }
        fun drain() { var limit=20;while(tasks.isNotEmpty()) { check(--limit>0);tasks.removeFirst().run() } }
    }
    private data class Pointer(var id:Long=0,val presentation:String)
    @Test fun manualMappingPresentationChangesInvalidateOnlyRenderedPointers() {
        val queue=Queue();var mode="traditional";val seen=mutableListOf<String>();val published=mutableListOf<Long>()
        val entry=CustomLyricsEntry(7,"song","lyrics.ttml",100,"hash","manual")
        val session=CustomLyricsReplacementSession(CustomLyricsIndexProvider { mapOf(7L to entry) },
            { "same original TTML" },{ seen+=mode;Pointer(presentation=mode) },{ it is Pointer },{ it is Pointer },
            { (it as Pointer).id },{ ptr,id -> (ptr as Pointer).id=id;true },published::add,queue,{}, { mode })
        session.start();session.ensureRequested(7);queue.drain()
        assertEquals("traditional",(session.readyReplacementFor(7) as Pointer).presentation)
        mode="simplified";assertNull(session.readyReplacementFor(7))
        session.ensureRequested(7);queue.drain()
        assertEquals("simplified",(session.readyReplacementFor(7) as Pointer).presentation)
        mode="traditional"
        assertEquals("traditional",(session.readyReplacementFor(7) as Pointer).presentation)
        assertEquals(listOf("traditional","simplified"),seen)
        assertEquals(listOf(7L,7L),published)
    }
    @Test fun optionChangedDuringParsingQueuesAReparseInsteadOfStrandingTheReadyEvent() {
        val queue=Queue();var mode="traditional";var parses=0
        val entry=CustomLyricsEntry(7,"song","lyrics.ttml",100,"hash","manual")
        val session=CustomLyricsReplacementSession(CustomLyricsIndexProvider { mapOf(7L to entry) },
            { "same original TTML" },{ val captured=mode;parses++;mode="simplified";Pointer(presentation=captured) },
            { it is Pointer },{ it is Pointer },{ (it as Pointer).id },{ ptr,id -> (ptr as Pointer).id=id;true },
            null,queue,{}, { mode })
        session.start();session.ensureRequested(7);queue.drain()
        assertEquals(2,parses)
        assertEquals("simplified",(session.readyReplacementFor(7) as Pointer).presentation)
    }
}
