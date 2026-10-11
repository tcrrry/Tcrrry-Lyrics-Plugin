package com.tcrrry.lyrics.engine.hook

import android.app.Activity
import android.graphics.Rect
import android.view.View
import android.widget.TextView
import android.widget.LinearLayout
import androidx.constraintlayout.widget.ConstraintLayout
import com.google.android.flexbox.FlexboxLayout
import com.tcrrry.lyrics.engine.lyrics.JapaneseRubyAlignment
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RubyPronunciationRowLayoutTest {
    @org.junit.Before fun attach() { com.tcrrry.lyrics.plugin.TestPluginContext.attach() }
    @org.junit.After fun close() { com.tcrrry.lyrics.plugin.TestPluginContext.current.close() }
    private val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    private val root = ConstraintLayout(activity).also { activity.setContentView(it) }
    private val container = FlexboxLayout(activity).apply { id=View.generateViewId(); flexWrap=1 }
    private val header = TextView(activity).apply { id=View.generateViewId(); textSize=12f }
    init {
        root.addView(header,ConstraintLayout.LayoutParams(0,-2).apply { topToTop=0; startToStart=0; endToEnd=0 })
        root.addView(container,ConstraintLayout.LayoutParams(0,-2).apply { topToBottom=header.id; startToStart=0; endToEnd=0 })
    }
    private fun layout(width: Int) {
        root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
        root.layout(0,0,width,root.measuredHeight)
    }
    private fun row(original: String, readings: List<String>): Pair<List<TextView>,RubyPronunciationRows.RubyView> {
        header.text=readings.joinToString(" ")
        val words=original.map { c -> TextView(activity).apply { text=c.toString(); textSize=32f }.also {
            container.addView(it,FlexboxLayout.LayoutParams(-2,-2).apply { topMargin=2 })
        } }
        val result=JapaneseRubyAlignment.Result(original.indices.map { JapaneseRubyAlignment.Unit(it,it+1,readings[it]) },true)
        val ruby=RubyPronunciationRows.RubyView(container,words,header,result,original,false)
        root.addView(ruby,ConstraintLayout.LayoutParams(0,0).apply {
            topToTop=header.id; bottomToBottom=container.id; startToStart=container.id; endToEnd=container.id
        })
        return words to ruby
    }
    @Test fun singleLineUsesOneFontSizeAndLeavesAllNativeGradientGeometryUntouched() {
        val (words,ruby)=row("君の花を見た",listOf("kimi","no","hana","wo","mi","ta"))
        layout(360)
        val before=words.map { Rect(it.left,it.top,it.right,it.bottom) }
        val containerBounds=Rect(container.left,container.top,container.right,container.bottom)
        val runs=requireNotNull(ruby.placements())
        assertEquals(1,runs.map { it.size }.distinct().size)
        assertEquals(header.textSize,runs.single { it.text=="kimi" }.size)
        ruby.onPreDraw(); layout(360); ruby.onPreDraw()
        assertEquals(View.INVISIBLE,header.visibility)
        assertEquals(before,words.map { Rect(it.left,it.top,it.right,it.bottom) })
        assertEquals(containerBounds,Rect(container.left,container.top,container.right,container.bottom))
        assertTrue(words.all { (it.layoutParams as FlexboxLayout.LayoutParams).topMargin==2 })
        ruby.restore(); layout(360)
        assertEquals(View.VISIBLE,header.visibility)
        assertEquals(before,words.map { Rect(it.left,it.top,it.right,it.bottom) })
    }
    @Test fun wrappedOriginalHasUniformReadingsAboveEachNativeLineWithoutMovingFirstLineTop() {
        val (words,ruby)=row("君の花を見た",listOf("kimi","no","hana","wo","mi","ta"))
        layout(120)
        assertTrue(words.map { it.top }.distinct().size>=2)
        val firstTop=words.first().top
        ruby.onPreDraw(); layout(120); ruby.onPreDraw()
        val runs=requireNotNull(ruby.placements())
        assertTrue(runs.map { it.top }.distinct().size>=2)
        assertEquals(1,runs.map { it.size }.distinct().size)
        assertEquals(View.INVISIBLE,header.visibility)
        assertEquals(firstTop,words.first().top)
        assertTrue(words.all { (it.layoutParams as FlexboxLayout.LayoutParams).topMargin==2 })
        assertEquals(words,(0 until container.childCount).map(container::getChildAt))
        ruby.restore(); layout(120)
        assertTrue(words.all { it.paddingBottom==0 })
        assertTrue(words.all { !(it.layoutParams as FlexboxLayout.LayoutParams).isWrapBefore })
    }
    @Test fun sameWidthNativePaddingResetRestoresSpaceBeforeDrawingSecondLineReadings() {
        val(words,ruby)=row("君の花を見た",listOf("kimi","no","hana","wo","mi","ta"))
        layout(120);ruby.onPreDraw();layout(120);ruby.onPreDraw()
        val band=words.first().paddingBottom;assertTrue(band>0)
        words.forEach { it.setPadding(it.paddingLeft,it.paddingTop,it.paddingRight,0) }
        layout(120)
        assertFalse(ruby.onPreDraw());assertTrue(words.filter { it.top<words.last().top }.all { it.paddingBottom==band })
        assertEquals(0,words.last().paddingBottom)
        layout(120);assertTrue(ruby.onPreDraw());assertNotNull(ruby.placements())
    }
    @Test fun unexpectedNativeWrapAtSameOuterWidthReservesAnInterlineReadingBand() {
        val(words,ruby)=row("君の花を見た",listOf("kimi","no","hana","wo","mi","ta"))
        layout(360);ruby.onPreDraw();layout(360);ruby.onPreDraw()
        assertTrue(words.all { it.paddingBottom==0 })
        container.setPadding(0,0,240,0);layout(360)
        assertTrue(words.map { it.top }.distinct().size>1)
        assertFalse(ruby.onPreDraw());layout(360);ruby.onPreDraw()
        assertTrue(words.filter { it.top<words.last().top }.all { it.paddingBottom>0 })
        assertEquals(0,words.last().paddingBottom);assertNotNull(ruby.placements())
    }
    @Test fun secondLineReadingsShareStableHeightWhileIndividualWordsPulse() {
        val(words,ruby)=row("君の花を見た",listOf("kimi","no","hana","wo","mi","ta"))
        layout(120);ruby.onPreDraw();layout(120);ruby.onPreDraw()
        val before=requireNotNull(ruby.placements())
        val lastTop=words.last().top
        val index=words.indexOfFirst { it.top==lastTop }
        words[index].scaleY=1.1f;words[index].translationY=-3f
        val after=requireNotNull(ruby.placements())
        assertEquals(before.map { it.top },after.map { it.top })
        assertEquals(1,after.filterIndexed { i,_->words[i].top==lastTop }.map { it.top }.distinct().size)
    }
    @Test fun screenshotBrandLineWrapsAtACompleteReadingPairWithNativeTimedWords() {
        val original="ブランドのバッグ彼氏のスペック"
        val reading="bu ra n do no ba ggu ka re shi no su pe kku"
        header.text=reading;header.textSize=20f
        val nativeTexts=listOf("ブ","ラ","ン","ド","の","バ","ッ","グ","彼","氏","の","ス","ペ","ッ","ク")
        val words=nativeTexts.map { text -> TextView(activity).apply { this.text=text; textSize=32.3f }.also {
            container.addView(it,FlexboxLayout.LayoutParams(-2,-2).apply { topMargin=2 })
        } }
        val result=RubyWordLayout.fitUnits(words,original,requireNotNull(JapaneseRubyAlignment.align(original,reading)))
        val ruby=RubyPronunciationRows.RubyView(container,words,header,result,original,false)
        root.addView(ruby,ConstraintLayout.LayoutParams(0,0).apply {
            topToTop=header.id;bottomToBottom=container.id;startToStart=container.id;endToEnd=container.id
        })
        for(width in listOf(300,301,310,315,320,330,340,360)) {
            layout(width);ruby.onPreDraw();layout(width);ruby.onPreDraw()
            assertNotNull("width=$width",ruby.placements())
            assertEquals(View.INVISIBLE,header.visibility)
        }
    }
    @Test fun providerVariantReadingFitsWrappedNativeWordsWithoutChangingDisplayOrFont() {
        val original="こんなにこんなに切なくする"
        val reading="ko n na ni ko n na ni secu na ku su lu"
        header.text=reading;header.textSize=20f
        val words=original.map { c -> TextView(activity).apply { text=c.toString();textSize=32f }.also {
            container.addView(it,FlexboxLayout.LayoutParams(-2,-2))
        } }
        val result=requireNotNull(JapaneseRubyAlignment.align(original,reading))
        val ruby=RubyPronunciationRows.RubyView(container,words,header,result,original,false)
        root.addView(ruby,ConstraintLayout.LayoutParams(0,0).apply {
            topToTop=header.id;bottomToBottom=container.id;startToStart=container.id;endToEnd=container.id
        })
        repeat(6) { layout(330);ruby.onPreDraw() }
        val runs=requireNotNull(ruby.placements())
        assertEquals(View.INVISIBLE,header.visibility)
        assertTrue(runs.map { it.top }.distinct().size>=2)
        assertEquals(listOf(header.textSize),runs.map { it.size }.distinct())
        assertEquals(reading.filter(Char::isLetterOrDigit),runs.joinToString("") { it.text }.filter(Char::isLetterOrDigit))
        assertTrue(runs.all { it.left>=-1 && it.right<=331 })
        assertEquals(original,words.joinToString("") { it.text })
        ruby.restore()
        assertEquals(View.VISIBLE,header.visibility)
    }
    @Test fun quotedAndMixedLatinScreenshotLinesKeepAlignedReadingsAtPhoneWidths() {
        for ((original, reading, nativeTexts) in listOf(
            Triple("色違いのTシャツ", "i ro chi ga i no T sha tsu", "色違いのTシャツ".map(Char::toString)),
            Triple("「バス一つ見送ろうよ」", "「 ba su hi to tsu mi o ku ro u yo 」", "「バス一つ見送ろうよ」".map(Char::toString)),
            Triple("色違いのTシャツ", "i ro chi ga i no T sha tsu", listOf("色違","い","の","Tシャツ")),
            Triple("「バス一つ見送ろうよ」", "「 ba su hi to tsu mi o ku ro u yo 」", listOf("「バス","一つ","見送ろ","うよ」"))
        )) {
            header.text=reading;header.textSize=20f
            val words=nativeTexts.map { value -> TextView(activity).apply { text=value;textSize=32.3f }.also {
                container.addView(it,FlexboxLayout.LayoutParams(-2,-2))
            } }
            val result=RubyWordLayout.fitUnits(words,original,requireNotNull(JapaneseRubyAlignment.align(original,reading)) { original })
            println("Screenshot $original $result")
            val ruby=RubyPronunciationRows.RubyView(container,words,header,result,original,false)
            root.addView(ruby,ConstraintLayout.LayoutParams(0,0).apply {
                topToTop=header.id;bottomToBottom=container.id;startToStart=container.id;endToEnd=container.id
            })
            for(width in listOf(260,280,300,320,340,360)) {
                repeat(5) { layout(width);ruby.onPreDraw() }
                assertNotNull("$original width=$width",ruby.placements())
                assertEquals(View.INVISIBLE,header.visibility)
            }
            ruby.restore();root.removeView(ruby);container.removeAllViews()
        }
    }
    @Test fun nestedNativeLatinAndQuotedWordsAreReadWithoutChangingTimedFlexIndices() {
        for ((original, reading, parts) in listOf(
            Triple("色違いのTシャツ", "i ro chi ga i no T sha tsu", listOf("色","違","い","の","Tシャツ")),
            Triple("「バス一つ見送ろうよ」", "「 ba su hi to tsu mi o ku ro u yo 」", listOf("「バス","一","つ","見","送","ろ","う","よ」"))
        )) {
            header.text=reading;header.textSize=20f
            parts.forEach { part ->
                val group=LinearLayout(activity).apply { orientation=LinearLayout.HORIZONTAL }
                part.forEach { c -> group.addView(TextView(activity).apply { text=c.toString();textSize=32.3f },LinearLayout.LayoutParams(-2,-2)) }
                container.addView(group,FlexboxLayout.LayoutParams(-2,-2))
            }
            val children=(0 until container.childCount).map(container::getChildAt)
            val items=RubyNativeWord.collect(container)
            val result=RubyWordLayout.fitNativeUnits(items,original,requireNotNull(JapaneseRubyAlignment.align(original,reading)))
            val ruby=RubyPronunciationRows.RubyView(container,items.flatMap { it.glyphs },header,result,original,false,items)
            root.addView(ruby,ConstraintLayout.LayoutParams(0,0).apply {
                topToTop=header.id;bottomToBottom=container.id;startToStart=container.id;endToEnd=container.id
            })
            repeat(6) { layout(320);ruby.onPreDraw() }
            assertEquals(View.INVISIBLE,header.visibility)
            val before=requireNotNull(ruby.placements()) { original }
            items.first().view.translationX=3f
            assertEquals(3f,requireNotNull(ruby.placements()).first().left-before.first().left,.01f)
            assertEquals(children,(0 until container.childCount).map(container::getChildAt))
            ruby.restore();root.removeView(ruby);container.removeAllViews()
        }
    }
    @Test fun wrappedHeaderAndLastOriginalRowDoNotReserveUnusedReadingRows() {
        val (words,ruby)=row("君の花を見た",listOf("kimi","no","hana","wo","mi","ta"))
        header.textSize=22f
        repeat(6) { layout(140);ruby.onPreDraw() }
        val runs=requireNotNull(ruby.placements())
        assertEquals(View.INVISIBLE,header.visibility)
        assertTrue(header.layout.lineCount>1)
        assertEquals(header.layout.getLineBottom(0),header.height)
        val lastTop=words.maxOf { it.top }
        assertTrue(words.filter { it.top<lastTop }.all { it.paddingBottom>0 })
        assertTrue(words.filter { it.top==lastTop }.all { it.paddingBottom==0 })
        // Each reading uses the same ink-to-ink gap above its original row.
        val gaps=words.indices.map { i -> words[i].top+words[i].baseline+words[i].paint.fontMetrics.ascent+container.top-
            (runs[i].top+ruby.top+header.paint.fontMetrics.descent) }
        assertTrue(gaps.maxOrNull()!!-gaps.minOrNull()!!<.01f)
        ruby.restore();layout(140)
        assertEquals(-2,header.layoutParams.height)
        assertTrue(header.height>header.layout.getLineBottom(0))
    }
    @Test fun whitespaceTimedViewsDoNotStealReadingColumnPaddingOrCauseUnplannedWrap() {
        val original="私 の定位置";val reading="wa ta shi no te i i chi"
        header.text=reading;header.textSize=22f
        val words=original.map { text -> TextView(activity).apply { this.text=text.toString();textSize=32.3f }.also {
            container.addView(it,FlexboxLayout.LayoutParams(-2,-2))
        } }
        val result=RubyWordLayout.fitUnits(words,original,requireNotNull(JapaneseRubyAlignment.align(original,reading)))
        val ruby=RubyPronunciationRows.RubyView(container,words,header,result,original,false)
        root.addView(ruby,ConstraintLayout.LayoutParams(0,0).apply {
            topToTop=header.id;bottomToBottom=container.id;startToStart=container.id;endToEnd=container.id
        })
        for(width in listOf(180,200,220,250,300)) {
            layout(width);ruby.onPreDraw();layout(width);ruby.onPreDraw()
            assertNotNull("width=$width",ruby.placements())
            assertEquals(0,words[1].paddingLeft);assertEquals(0,words[1].paddingRight)
        }
    }
    @Test fun nativeScaleAndTranslationAreTrackedAndRecycledTextCannotRetainOldReading() {
        val (words,ruby)=row("君の",listOf("kimi","no")); layout(360)
        val before=requireNotNull(ruby.placements())
        words[0].translationX=4f; words[0].scaleX=1.1f
        val after=requireNotNull(ruby.placements())
        assertEquals(4f,(after[0].left+after[0].right-before[0].left-before[0].right)/2,.01f)
        assertEquals(before[0].size,after[0].size)
        ruby.onPreDraw(); layout(360); ruby.onPreDraw(); assertEquals(View.INVISIBLE,header.visibility)
        words[0].text="花"
        ruby.onPreDraw(); assertNull(ruby.placements()); assertEquals(View.VISIBLE,header.visibility)
    }
    @Test fun annotationCollisionGetsWiderColumnsWithoutShrinkingIndividualWords() {
        val (_,ruby)=row("君の",listOf("verylongreading","no")); layout(360)
        assertNull(ruby.placements()); ruby.onPreDraw(); layout(360);ruby.onPreDraw()
        assertEquals(View.INVISIBLE,header.visibility)
        assertEquals(header.textSize,requireNotNull(ruby.placements()).first().size)
    }
    @Test fun shortOriginalWithLongFirstKanjiReadingGetsRoomWithoutChangingFontSize() {
        val (words,ruby)=row("私の定位置",listOf("watashi","no","tei","i","chi"))
        header.textSize=22f;layout(360)
        assertNull(ruby.placements())
        val size=words.first().textSize
        ruby.onPreDraw();layout(360);ruby.onPreDraw()
        val runs=requireNotNull(ruby.placements())
        assertEquals("watashi",runs.first().text)
        assertTrue(runs.first().left>=0)
        assertTrue(words.first().paddingLeft>0)
        assertEquals(size,words.first().textSize)
        assertEquals(1,runs.map { it.size }.distinct().size)
    }
    @Test fun crowdedSingleLineWrapsOriginalAndPronunciationTogether() {
        val (words,ruby)=row("私の定位置",listOf("watashi","no","tei","i","chi"))
        header.textSize=22f;layout(180)
        assertEquals(1,words.map { it.top }.distinct().size)
        ruby.onPreDraw();layout(180);ruby.onPreDraw()
        assertTrue(words.map { it.top }.distinct().size>1)
        assertNotNull(ruby.placements())
        assertEquals(1,requireNotNull(ruby.placements()).map { it.size }.distinct().size)
    }
    @Test fun yukikoiScreenshotDoesNotStrandTheFirstCharacterOfTokenOnItsOwnRow() {
        val original="ねえ 恋よ ずっと 溶けないで"
        val reading="ne e koi yo zu tto to ke na i de"
        header.text=reading;header.textSize=20f
        val words=original.map { c -> TextView(activity).apply {
            text=c.toString();textSize=32.3f;letterSpacing=.025f
        }.also { word -> container.addView(word,FlexboxLayout.LayoutParams(-2,-2).apply {
            isWrapBefore=c=='溶' || c=='け'
        }) } }
        val hints=words.map { (it.layoutParams as FlexboxLayout.LayoutParams).isWrapBefore }
        val alignment=RubyWordLayout.fitUnits(words,original,requireNotNull(JapaneseRubyAlignment.align(original,reading)))
        val ruby=RubyPronunciationRows.RubyView(container,words,header,alignment,original,false)
        root.addView(ruby,ConstraintLayout.LayoutParams(0,0).apply {
            topToTop=header.id;bottomToBottom=container.id;startToStart=container.id;endToEnd=container.id
        })
        for(width in listOf(260,280,300,320,340,360)) {
            repeat(6) { layout(width);ruby.onPreDraw() }
            assertNotNull("width=$width",ruby.placements())
            val lines=words.filter { it.text.toString().isNotBlank() }.groupBy { it.top }
            assertTrue("width=$width lines=${lines.values.map { line->line.map { it.text } }}",lines.values.all { it.size>1 })
            assertTrue(words.all { it.textSize==words.first().textSize })
            assertTrue(requireNotNull(ruby.placements()).all { it.size==header.textSize })
        }
        ruby.restore()
        assertEquals(hints,words.map { (it.layoutParams as FlexboxLayout.LayoutParams).isWrapBefore })
    }
}
