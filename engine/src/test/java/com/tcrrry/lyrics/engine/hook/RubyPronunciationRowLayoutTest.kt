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
    @Test fun readingsFillTheFirstRowInsteadOfBalancingTwoHalfEmptyRows() {
        val (words,ruby)=row("あいうえおか",List(6) { "a" })
        layout(360)
        val width=words.take(5).sumOf { it.measuredWidth }+1
        repeat(6) { layout(width);ruby.onPreDraw() }
        assertNotNull(ruby.placements())
        assertEquals(1,words.take(5).map { it.top }.distinct().size)
        assertTrue(words.last().top>words.first().top)
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
    @Test fun cantoneseKeepsEverySyllableSeparateInsideMultiCharacterTimedWords() {
        val original="不说一句的爱有多好"
        val readings=listOf("ba","syut","yat","geui","dik","oi","yau","do","hou")
        header.text=readings.joinToString(" ");header.textSize=18f
        val words=listOf("不说一句的爱","有","多","好").map { value -> TextView(activity).apply {
            text=value;textSize=32f
        }.also { container.addView(it,FlexboxLayout.LayoutParams(-2,-2)) } }
        val originalSpacing=words.map { it.letterSpacing }
        val aligned=JapaneseRubyAlignment.Result(original.indices.map { JapaneseRubyAlignment.Unit(it,it+1,readings[it]) },true,perCharacter=true)
        val fitted=RubyWordLayout.fitUnits(words,original,aligned)
        assertEquals(readings,fitted.units.map { it.reading })
        val ruby=RubyPronunciationRows.RubyView(container,words,header,fitted,original,false)
        root.addView(ruby,ConstraintLayout.LayoutParams(0,0).apply {
            topToTop=header.id;bottomToBottom=container.id;startToStart=container.id;endToEnd=container.id
        })
        repeat(8) { layout(330);ruby.onPreDraw() }
        val runs=requireNotNull(ruby.placements())
        assertEquals(readings,runs.map { it.text })
        assertEquals(4,container.childCount)
        assertEquals(original,words.joinToString("") { it.text })
        assertTrue(runs.all { it.left>=0 && it.right<=330 })
        val bitmap=android.graphics.Bitmap.createBitmap(330,root.height,android.graphics.Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(bitmap))
        val file=java.io.File("../../release-audit/cantonese-20261018/per-character-layout.png").apply { parentFile.mkdirs() }
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
        ruby.restore();assertEquals(originalSpacing,words.map { it.letterSpacing })
    }

    @Test fun cantoneseNestedTimedGroupsKeepCharacterColumnsOnResizeAndRestore() {
        val original="只有一次记得实在";val readings=listOf("zi","yau","ya","ci","gei","da","sa","zoi")
        header.text=readings.joinToString(" ");header.textSize=20f
        for(part in listOf("只有一次记得","实","在")) {
            val group=LinearLayout(activity).apply { orientation=LinearLayout.HORIZONTAL }
            part.forEach { c -> group.addView(TextView(activity).apply { text=c.toString();textSize=32f },LinearLayout.LayoutParams(-2,-2)) }
            container.addView(group,FlexboxLayout.LayoutParams(-2,-2))
        }
        val children=(0 until container.childCount).map(container::getChildAt)
        val items=RubyNativeWord.collect(container);val leaves=items.flatMap { it.glyphs }
        val before=leaves.map { listOf(it.paddingLeft,it.paddingTop,it.paddingRight,it.paddingBottom) }
        val aligned=JapaneseRubyAlignment.Result(original.indices.map { JapaneseRubyAlignment.Unit(it,it+1,readings[it]) },true,perCharacter=true)
        val fitted=RubyWordLayout.fitNativeUnits(items,original,aligned)
        val ruby=RubyPronunciationRows.RubyView(container,leaves,header,fitted,original,false,items)
        root.addView(ruby,ConstraintLayout.LayoutParams(0,0).apply {
            topToTop=header.id;bottomToBottom=container.id;startToStart=container.id;endToEnd=container.id
        })
        for(width in listOf(280,320,360,300)) {
            repeat(8) { layout(width);ruby.onPreDraw() }
            val runs=requireNotNull(ruby.placements()) { "width=$width" }
            assertEquals(readings,runs.map { it.text });assertTrue(runs.all { it.left>=0 && it.right<=width })
            assertEquals(children,(0 until container.childCount).map(container::getChildAt))
        }
        val beforeMove=ruby.placements()!!.first().left
        children.first().translationX=3f
        assertEquals(3f,ruby.placements()!!.first().left-beforeMove,.01f)
        ruby.restore();assertEquals(before,leaves.map { listOf(it.paddingLeft,it.paddingTop,it.paddingRight,it.paddingBottom) })
        assertEquals(listOf(32f),leaves.map { it.textSize }.distinct())
    }

    @Test fun koreanScreenshotJoinedRomanizationHasOneRunPerHangulBlock() {
        for((original,reading,parts,expected) in listOf(
            listOf("환한 빛 조명이","hwanhan bich jomyeong-i","환한|빛|조명이","hwan|han|bich|jo|myeong|i"),
            listOf("어딜 비추는지","eodil bichuneunji","어딜|비추는지","eo|dil|bi|chu|neun|ji"),
            listOf("너 Heaven","neo Hea ven","너|Heaven","neo|Heaven")
        )) {
            header.text=reading;header.textSize=18f
            val words=parts.split('|').map { part -> TextView(activity).apply { text=part;textSize=32f }.also {
                container.addView(it,FlexboxLayout.LayoutParams(-2,-2))
            } }
            val aligned=requireNotNull(JapaneseRubyAlignment.align(original,reading));assertTrue(aligned.perCharacter)
            val fitted=RubyWordLayout.fitUnits(words,original,aligned)
            val ruby=RubyPronunciationRows.RubyView(container,words,header,fitted,original,false)
            root.addView(ruby,ConstraintLayout.LayoutParams(0,0).apply {
                topToTop=header.id;bottomToBottom=container.id;startToStart=container.id;endToEnd=container.id
            })
            for(width in listOf(320,360)) {
                repeat(8) { layout(width);ruby.onPreDraw() }
                val runs=requireNotNull(ruby.placements()) { "$original width=$width ruby=${ruby.width}x${ruby.height} header=${header.height} words="+words.joinToString { "${it.text}:${it.left},${it.top},${it.width}x${it.height} pad=${it.paddingLeft}/${it.paddingRight} spacing=${it.letterSpacing} x="+(0..it.text.length).map { p->it.layout?.getPrimaryHorizontal(p) } } }
                assertEquals(expected.split('|'),runs.map { it.text })
                assertTrue(runs.all { it.left>=0 && it.right<=width })
            }
            if(original.startsWith("환")) {
                val bitmap=android.graphics.Bitmap.createBitmap(360,root.height,android.graphics.Bitmap.Config.ARGB_8888)
                root.draw(android.graphics.Canvas(bitmap))
                val file=java.io.File("../../release-audit/cantonese-20261018/korean-character-layout.png").apply { parentFile.mkdirs() }
                file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
            }
            ruby.restore();assertTrue(words.all { it.letterSpacing==0f })
            assertEquals(parts.split('|'),words.map { it.text.toString() })
            root.removeView(ruby);container.removeAllViews()
        }
    }

    @Test fun koreanAndCantoneseScreenshotLinesUseFullSizeMeasuredCharacterColumns() {
        val samples=listOf(
            Triple("보이니 그 유일함이 얼마나","boini geu yuilham-i eolmana","보이니|그|유일함이|얼마나"),
            Triple("아름다운지 말야","aleumdaunji mal-ya","아름다운지|말야"),
            Triple("왼손으로 그린 별 하나","oen son eu lo geu lin byeol ha na","왼손으로|그린|별|하나"),
            Triple("왜 전화 안받아 왜 답장이 없어","wae jeon hwa an ba da wae dap jang i eob seo","왜|전화|안받아|왜|답장이|없어"),
            Triple("왜 나를 힘들게만 하는데","wae na reul him deul ge man ha neun de","왜|나를|힘들게만|하는데"),
            Triple("나 지금 이러는게 아닌데","na ji geum i reo neun ge a nin de","나|지금|이러는게|아닌데"),
            Triple("一千種戀愛 一些需要情淚灌溉","jat cin zung lyun oi jat se seoi jiu cing leoi gun koi","一|千|種|戀|愛|一|些|需|要|情|淚|灌|溉"),
            Triple("Where did you go 數數多久不碰到","Where did you go sou sou do gau ba pung dou","Where |did |you |go |數|數|多|久|不|碰|到")
        )
        for((original,reading,parts) in samples) for(singleView in listOf(false,true)) for(width in listOf(240,320,360)) {
            header.text=reading;header.textSize=18f
            if(original.any { it in '\u3400'..'\u9fff' })
                com.tcrrry.lyrics.engine.lyrics.LanguagePronunciationSupplement.rememberChineseProviderRow(original,reading)
            val alignment=requireNotNull(JapaneseRubyAlignment.align(original,reading)) { original }
            assertTrue(alignment.perCharacter)
            val words=(if(singleView) listOf(original) else parts.split('|')).map { part -> TextView(activity).apply {
                text=part;textSize=32f
            }.also { container.addView(it,FlexboxLayout.LayoutParams(-2,-2)) } }
            layout(width)
            val originalRows=words.map { it.top }.distinct().sorted()
            val rowsBefore=words.map { originalRows.indexOf(it.top) }
            val widthsBefore=words.map { it.width }
            val internalLines=words.map { it.layout.lineCount }
            val oldLineSpace=words.map { it.lineSpacingExtra }
            val ruby=RubyPronunciationRows.RubyView(container,words,header,alignment,original,false)
            root.addView(ruby,ConstraintLayout.LayoutParams(0,0).apply {
                topToTop=header.id;bottomToBottom=container.id;startToStart=container.id;endToEnd=container.id
            })
            repeat(12) { layout(width);ruby.onPreDraw() }
            val runs=requireNotNull(ruby.placements()) { "$original width=$width single=$singleView header=${header.height} ruby=${ruby.height}" }
            val rowsAfter=words.map { it.top }.distinct().sorted()
            assertEquals(if(singleView) listOf(original) else parts.split('|'),words.map { it.text.toString() })
            assertTrue(words.all { it.letterSpacing==0f })
            assertEquals(alignment.units.map { JapaneseRubyAlignment.displayReading(it.reading) },runs.map { it.text })
            assertTrue(runs.all { it.left>=0 && it.right<=width })
            assertEquals(1,runs.map { it.size }.distinct().size)
            assertEquals(header.textSize,runs.first().size,0f)
            if(original.startsWith("왜 전화") && singleView && width==360) {
                val bitmap=android.graphics.Bitmap.createBitmap(width,root.height,android.graphics.Bitmap.Config.ARGB_8888)
                root.draw(android.graphics.Canvas(bitmap))
                java.io.File("../../release-audit/fixed-size-preview15/korean-full-size.png").apply { parentFile.mkdirs() }.outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
                };bitmap.recycle()
            }
            ruby.restore();assertEquals(oldLineSpace,words.map { it.lineSpacingExtra })
            root.removeView(ruby);container.removeAllViews()
        }
    }
    @Test fun characterFallbackRestoresAllOwnedGeometryWhenHeaderNoLongerMatches() {
        val original="환한 빛 조명이";val reading="hwanhan bich jomyeong-i"
        header.text="stale reading";header.textSize=18f
        val word=TextView(activity).apply { text=original;textSize=32f }
        container.addView(word,FlexboxLayout.LayoutParams(-2,-2))
        layout(240)
        val old=listOf(word.paddingLeft,word.paddingTop,word.paddingRight,word.paddingBottom)
        val oldLineSpace=word.lineSpacingExtra
        val ruby=RubyPronunciationRows.RubyView(container,listOf(word),header,
            requireNotNull(JapaneseRubyAlignment.align(original,reading)),original,false)
        root.addView(ruby,ConstraintLayout.LayoutParams(0,0).apply {
            topToTop=header.id;bottomToBottom=container.id;startToStart=container.id;endToEnd=container.id
        })
        repeat(10) { layout(240);ruby.onPreDraw() }
        assertNull(ruby.placements());assertEquals(View.VISIBLE,header.visibility)
        assertEquals(old,listOf(word.paddingLeft,word.paddingTop,word.paddingRight,word.paddingBottom))
        assertEquals(oldLineSpace,word.lineSpacingExtra,0f);assertEquals(0f,word.letterSpacing,0f)
        ruby.restore()
    }

    @Test fun koreanStaticLineViewAlsoKeepsNaturalInternalWrapping() {
        val original="왜 전화 안받아 왜 답장이 없어"
        val reading="wae jeon hwa an ba da wae dap jang i eob seo"
        header.text=reading;header.textSize=18f
        val originalView=TextView(activity).apply { id=View.generateViewId();text=original;textSize=32f }
        root.addView(originalView,ConstraintLayout.LayoutParams(0,-2).apply {
            topToBottom=header.id;startToStart=0;endToEnd=0
        })
        layout(240);val count=originalView.layout.lineCount
        val ruby=RubyPronunciationRows.RubyView(originalView,listOf(originalView),header,
            requireNotNull(JapaneseRubyAlignment.align(original,reading)),original,true)
        root.addView(ruby,ConstraintLayout.LayoutParams(0,0).apply {
            topToTop=header.id;bottomToBottom=originalView.id;startToStart=originalView.id;endToEnd=originalView.id
        })
        repeat(12) { layout(240);ruby.onPreDraw() }
        val runs=requireNotNull(ruby.placements())
        assertEquals(12,runs.size);assertEquals(count,originalView.layout.lineCount)
        assertEquals(0f,originalView.letterSpacing,0f)
        assertTrue(runs.all { it.left>=0 && it.right<=240 })
        ruby.restore();assertEquals(0f,originalView.lineSpacingExtra,0f)
    }

    @Test fun entireQqCantoneseTrackHasFullSizeCharacterPlacements() {
        val fixture=System.getProperty("cantoneseFixture")
        org.junit.Assume.assumeNotNull(fixture)
        val entries=org.json.JSONArray(java.io.File(fixture!!).readText())
        var checked=0
        for(i in 0 until entries.length()) {
            val entry=entries.getJSONObject(i)
            val reading=entry.optString("reading")
            if(!reading.matches(Regex("[A-Za-z ]+"))) continue
            val original=entry.getString("text")
            com.tcrrry.lyrics.engine.lyrics.LanguagePronunciationSupplement.rememberChineseProviderRow(original,reading)
            val aligned=requireNotNull(JapaneseRubyAlignment.align(original,reading)) { "row $i" }
            for(width in listOf(280,360)) for(grouped in listOf(false,true)) {
                header.text=reading;header.textSize=18f
                val parts=if(grouped) listOf(original) else original.map { it.toString() }
                val words=parts.map { value -> TextView(activity).apply { text=value;textSize=32f }.also {
                    container.addView(it,FlexboxLayout.LayoutParams(-2,-2))
                } }
                val ruby=RubyPronunciationRows.RubyView(container,words,header,aligned,original,false)
                root.addView(ruby,ConstraintLayout.LayoutParams(0,0).apply {
                    topToTop=header.id;bottomToBottom=container.id;startToStart=container.id;endToEnd=container.id
                })
                repeat(12) { layout(width);ruby.onPreDraw() }
                val runs=requireNotNull(ruby.placements()) { "row $i width=$width grouped=$grouped" }
                assertEquals(aligned.units.size,runs.size)
                assertTrue(runs.all { it.size==header.textSize && it.left>=0 && it.right<=width })
                assertEquals(original,words.joinToString("") { it.text })
                ruby.restore();root.removeView(ruby);container.removeAllViews()
            }
            checked++
        }
        assertEquals(40,checked)
    }

    @Test fun entireKoreanProviderTracksHaveFullSizeCharacterPlacements() {
        val directory=System.getProperty("koreanFixtureDir")
        org.junit.Assume.assumeTrue(!directory.isNullOrBlank())
        var checked=0
        for(name in listOf("celebrity-netease.json","badboy-netease.json")) {
            val json=org.json.JSONObject(java.io.File(directory!!,name).readText())
            val source=com.tcrrry.lyrics.sources.DirectLyricsRepository.Result(lyrics=json.getJSONObject("lrc").getString("lyric"),
                romanizedLyrics=json.getJSONObject("romalrc").getString("lyric"),source="网易云音乐")
            val readings=com.tcrrry.lyrics.engine.lyrics.DesktopLyricsTtmlConverter.pronunciationEntries(source)
            for((time,original) in com.tcrrry.lyrics.engine.lyrics.DesktopLyricsTtmlConverter.pronunciationLines(source)) {
                if(!Regex("[가-힣]").containsMatchIn(original)) continue
                val reading=readings.minByOrNull { kotlin.math.abs(it.first-time) }
                    ?.takeIf { kotlin.math.abs(it.first-time)<300 && it.second.isNotBlank() }?.second ?: continue
                val aligned=requireNotNull(JapaneseRubyAlignment.align(original,reading)) { name }
                for(width in listOf(280,360)) for(shape in 0..3) {
                    header.text=reading;header.textSize=18f
                    val parts=when(shape) { 0->listOf(original);1->Regex("\\S+\\s*").findAll(original).map { it.value }.toList();2->original.map { it.toString() };else->original.chunked(2) }
                    val words=parts.map { value -> TextView(activity).apply { text=value;textSize=32f }.also {
                        container.addView(it,FlexboxLayout.LayoutParams(-2,-2))
                    } }
                    val fitted=RubyWordLayout.fitUnits(words,original,aligned)
                    val ruby=RubyPronunciationRows.RubyView(container,words,header,fitted,original,false)
                    root.addView(ruby,ConstraintLayout.LayoutParams(0,0).apply {
                        topToTop=header.id;bottomToBottom=container.id;startToStart=container.id;endToEnd=container.id
                    })
                    repeat(12) { layout(width);ruby.onPreDraw() }
                    val runs=requireNotNull(ruby.placements()) { "$name t=$time width=$width shape=$shape text=$original reading=$reading" }
                    assertEquals(fitted.units.size,runs.size)
                    assertTrue(runs.all { it.size==header.textSize && it.left>=0 && it.right<=width })
                    assertEquals(original,words.joinToString("") { it.text })
                    ruby.restore();root.removeView(ruby);container.removeAllViews()
                }
                checked++
            }
        }
        assertEquals(96,checked)
    }

}
