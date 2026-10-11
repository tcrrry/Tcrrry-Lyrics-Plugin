package com.tcrrry.lyrics.engine.hook

import android.app.Activity
import android.widget.TextView
import com.google.android.flexbox.`FlexboxLayout$a`
import com.tcrrry.lyrics.engine.lyrics.JapaneseRubyAlignment
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class RubyHostLayoutTest {
    @Test fun punctuationAttachedToNativeTimedWordDoesNotRejectTheWholeQuotedRow() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        val original="「バスよ」"
        val words=listOf("「バ","ス","よ」").map { text->TextView(activity).apply {
            this.text=text;textSize=32f;layoutParams=`FlexboxLayout$a`(-2,-2)
        } }
        val aligned=JapaneseRubyAlignment.Result(listOf(
            JapaneseRubyAlignment.Unit(1,2,"ba"),JapaneseRubyAlignment.Unit(2,3,"su"),JapaneseRubyAlignment.Unit(3,4,"yo 」")
        ),true)
        val fitted=RubyWordLayout.fitUnits(words,original,aligned)
        assertEquals(listOf("「バ","ス","よ」"),fitted.units.map { original.substring(it.start,it.end) })
        assertNotNull(RubyWordLayout.create(words,TextView(activity).apply { textSize=18f },original,fitted,180))
        assertEquals("yo",JapaneseRubyAlignment.displayReading(fitted.units.last().reading))
    }
    @Test fun actualObfuscatedFlexItemContractCanWidenWrapAndRestoreWithoutAnyNamedSetters() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        val header=TextView(activity).apply { textSize=22f }
        val original="私の定位置";val readings=listOf("watashi","no","tei","i","chi")
        val words=original.map { TextView(activity).apply {
            text=it.toString();textSize=32f;layoutParams=`FlexboxLayout$a`(-2,-2)
        } }
        val originalSizes=words.map { it.textSize }
        val units=original.indices.map { JapaneseRubyAlignment.Unit(it,it+1,readings[it]) }
        assertFalse(words.first().layoutParams.javaClass.methods.any { it.name=="setWrapBefore" })
        val plan=requireNotNull(RubyWordLayout.create(words,header,original,JapaneseRubyAlignment.Result(units,true),180))
        plan.apply()
        assertTrue(words.first().paddingLeft>0)
        assertTrue(words.any { (it.layoutParams as `FlexboxLayout$a`).k })
        assertTrue(words.all { (it.layoutParams as `FlexboxLayout$a`).c==0f })
        assertTrue(words.first().paddingBottom>0)
        assertEquals(0,words.last().paddingBottom)
        assertEquals(originalSizes,words.map { it.textSize })
        plan.restore()
        assertTrue(words.all { it.paddingLeft==0 && it.paddingRight==0 && it.paddingBottom==0 })
        assertTrue(words.all { !(it.layoutParams as `FlexboxLayout$a`).k && (it.layoutParams as `FlexboxLayout$a`).c==1f })
    }
    @Test fun realMultiCharacterTimedWordsDoNotDisableRubyForTheWholeRow() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        val original="失うのが怖くて"
        val words=listOf("失う","の","が","怖く","て").map { text -> TextView(activity).apply {
            this.text=text;textSize=32f;layoutParams=`FlexboxLayout$a`(-2,-2)
        } }
        val readings=listOf("ushina","u","no","ga","kowa","ku","te")
        val aligned=JapaneseRubyAlignment.Result(original.indices.map { JapaneseRubyAlignment.Unit(it,it+1,readings[it]) },true)
        val header=TextView(activity).apply { textSize=18f }
        assertNull(RubyWordLayout.create(words,header,original,aligned,360))
        val fitted=RubyWordLayout.fitUnits(words,original,aligned)
        assertEquals(listOf("失う","の","が","怖く","て"),fitted.units.map { original.substring(it.start,it.end) })
        assertEquals("ushinau",fitted.units.first().reading)
        assertNotNull(RubyWordLayout.create(words,header,original,fitted,360))
        assertEquals(words.size,5)
    }
}
