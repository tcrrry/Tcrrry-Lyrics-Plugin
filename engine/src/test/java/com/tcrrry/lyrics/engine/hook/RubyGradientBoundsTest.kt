package com.tcrrry.lyrics.engine.hook

import android.content.Context
import android.graphics.*
import android.util.SparseArray
import android.view.ViewGroup
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RubyGradientBoundsTest {
    // Immutable cached host fields, actual R8 names and native DST_IN behavior.
    class Line(@JvmField val o:Int,@JvmField val h:Int,@JvmField val g:Int)
    class Reference(@JvmField val a:Any,@JvmField var b:java.io.Serializable?)
    class Word(indices:List<Int>,key:Int) {
        @JvmField val n=indices.associateWith { Reference(it,key) }
    }
    class Mask(@JvmField val a:Int,@JvmField val b:Int,@JvmField val c:Int,@JvmField val d:Int) {
        constructor(owner:Host,a:Int,b:Int,c:Int,d:Int):this(a,b,c,d)
        @JvmField var j=true
        @JvmField val e=Rect()
        @JvmField var g=intArrayOf(0x33000000,0x33000000)
        @JvmField var h:FloatArray?=null
        @JvmField var i=0f
        var colors:IntArray
            get()=g
            set(value) { g=value }
        @JvmField val paint=Paint().apply { xfermode=PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    }
    class Host(context:Context):ViewGroup(context) {
        @JvmField val V=SparseArray<Mask>()
        @JvmField val W=SparseArray<Mask>()
        var measuredLines=listOf(Line(0,4,80),Line(4,4,80))
        fun getFlexLines()=measuredLines
        override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int) = Unit
        fun pixels():Bitmap {
            val image=Bitmap.createBitmap(100,height,Bitmap.Config.ARGB_8888)
            val canvas=Canvas(image)
            val layer=canvas.saveLayer(0f,0f,100f,height.toFloat(),null)
            // Solid white glyph areas on each line; the second glyph extends
            // well below the old mask bottom, as in the user's photograph.
            canvas.drawRect(10f,10f,40f,45f,Paint().apply { color=Color.WHITE })
            canvas.drawRect(10f,90f,40f,125f,Paint().apply { color=Color.WHITE })
            if(height>=240) canvas.drawRect(10f,170f,40f,205f,Paint().apply { color=Color.WHITE })
            for(array in listOf(V,W)) for(i in 0 until array.size()) {
                val mask=array.valueAt(i)
                if(mask.j) {
                    mask.e.set(0,mask.a,100,mask.b)
                    mask.paint.shader=LinearGradient(0f,0f,100f,0f,mask.colors,mask.h,Shader.TileMode.CLAMP)
                    mask.j=false
                }
                canvas.drawRect(mask.e,mask.paint)
            }
            canvas.restoreToCount(layer)
            return image
        }
    }
    @Test fun secondLineBottomNoLongerEscapesNativeUnplayedMask() {
        val host=Host(RuntimeEnvironment.getApplication()).apply { layout(0,0,100,160) }
        val first=Mask(0,60,0,3);val second=Mask(60,100,4,7)
        host.V.put(0,first);host.V.put(4,second)
        val broken=host.pixels()
        assertEquals(255,Color.alpha(broken.getPixel(20,120)))
        val port=RubyGradientBounds.forTest(Host::class.java)
        port.sync(host)
        assertSame(second,host.V[4]) // Running native animator still owns this object.
        val fixed=host.pixels()
        assertEquals(51,Color.alpha(fixed.getPixel(20,120)))
        assertEquals(51,Color.alpha(fixed.getPixel(20,92)))
        second.colors=intArrayOf(Color.BLACK,Color.BLACK);second.j=true
        port.sync(host)
        assertEquals(255,Color.alpha(host.pixels().getPixel(20,120)))
        // Turning ruby off restores ordinary native line geometry as well.
        host.measuredLines=listOf(Line(0,4,60),Line(4,4,40))
        port.sync(host)
        assertEquals(60,second.a);assertEquals(100,second.b)
    }
    @Test fun secondaryNativeSegmentKeepsItsShaderAndDoesNotDoubleMaskFirstSegment() {
        val host=Host(RuntimeEnvironment.getApplication()).apply { layout(0,0,100,160) }
        val upper=Mask(60,85,4,7);val lower=Mask(85,100,4,7)
        host.V.put(4,upper);host.W.put(4,lower)
        RubyGradientBounds.forTest(Host::class.java).sync(host)
        assertEquals(80,upper.a);assertEquals(105,upper.b)
        assertEquals(upper.b,lower.a);assertEquals(160,lower.b)
        assertSame(lower,host.W[4])
    }
    @Test fun changedWrapStartReindexesSameNativeMaskWithoutLeavingAnUnmaskedLine() {
        val host=Host(RuntimeEnvironment.getApplication()).apply { layout(0,0,100,160) }
        val first=Mask(0,50,0,5);val second=Mask(50,100,6,7)
        host.V.put(0,first);host.V.put(6,second)
        val port=RubyGradientBounds.forTest(Host::class.java)
        port.sync(host)
        assertSame(second,host.V[4]);assertNull(host.V[6]);assertEquals(4,second.c)
        assertEquals(51,Color.alpha(host.pixels().getPixel(20,120)))
        port.sync(host);assertSame(second,host.V[4]);assertEquals(2,host.V.size())
    }
    @Test fun anAdditionalWrappedLineReceivesRemainingAlphaInsteadOfDefaultingToFullBrightness() {
        val host=Host(RuntimeEnvironment.getApplication()).apply { layout(0,0,100,160) }
        val only=Mask(0,80,0,7).apply { colors=intArrayOf(Color.BLACK,0x33000000) }
        host.V.put(0,only)
        val port=RubyGradientBounds.forTest(Host::class.java)
        port.sync(host)
        assertSame(only,host.V[0]);assertNotNull(host.V[4]);assertEquals(2,host.V.size())
        assertEquals(51,Color.alpha(host.pixels().getPixel(20,120)))
        val continuation=host.V[4]
        continuation.colors=intArrayOf(Color.BLACK,Color.BLACK);continuation.j=true
        port.sync(host)
        assertSame(continuation,host.V[4]);assertEquals(255,Color.alpha(host.pixels().getPixel(20,120)))
    }
    @Test fun newlyWrappedWordsReceivePartialHighlightBeforeTheSentenceEnds() {
        val host=Host(RuntimeEnvironment.getApplication()).apply { layout(0,0,100,160) }
        // The native binder cached every word in one old visual line. Ruby
        // introduces a second line, retaining the original timed word indices.
        val first=Mask(0,80,0,7)
        host.V.put(0,first)
        val words=(0..7).associateWith { Word(listOf(it),0) }
        RubyGradientBounds.trackWordMap(host,words)
        val port=RubyGradientBounds.forTest(Host::class.java)
        port.sync(host)
        assertSame(first,host.V[0])
        val word=words.getValue(5).n.getValue(5)
        assertEquals(4,word.b)
        val active=host.V[word.b as Int]
        assertSame(host.V[4],active)
        // Emulate A.d0's exact-key lookup and a live native gradient update.
        assertEquals(51,Color.alpha(host.pixels().getPixel(20,120)))
        active.colors=intArrayOf(Color.BLACK,Color.BLACK,0x33000000,0x33000000)
        active.h=floatArrayOf(0f,.3f,.3f,1f);active.i=.3f;active.j=true
        port.sync(host)
        val halfway=host.pixels()
        assertEquals(255,Color.alpha(halfway.getPixel(20,120)))
        assertEquals(51,Color.alpha(halfway.getPixel(38,120)))
        assertEquals(.3f,active.i,0f) // Geometry synchronization cannot finish the animation.
        assertEquals(listOf(0,0,0,0,4,4,4,4),words.values.map { it.n.values.single().b })
    }
    @Test fun changedWrapRefreshesCachedMaskKeysAndRestoresThemWhenRubyIsDisabled() {
        val host=Host(RuntimeEnvironment.getApplication()).apply { layout(0,0,100,160) }
        val first=Mask(0,60,0,5);val second=Mask(60,100,6,7)
        host.V.put(0,first);host.V.put(6,second)
        // Multiple inner bindings in a native grouped word retain individual
        // child indices, including a stale key and an uninitialized key.
        val word=Word((0..7).toList(),0)
        word.n.getValue(6).b=6;word.n.getValue(7).b=null
        val words=mapOf(123 to word)
        RubyGradientBounds.trackWordMap(host,words)
        val port=RubyGradientBounds.forTest(Host::class.java)
        port.sync(host)
        assertSame(second,host.V[4])
        assertEquals(4,word.n.getValue(5).b)
        assertEquals(4,word.n.getValue(6).b)
        assertEquals(4,word.n.getValue(7).b)
        assertEquals((0..7).toList(),word.n.values.map { it.a })
        host.measuredLines=listOf(Line(0,6,60),Line(6,2,40))
        port.sync(host)
        assertEquals(0,word.n.getValue(5).b)
        assertEquals(6,word.n.getValue(6).b)
        assertSame(second,host.V[6])
    }
    @Test fun nativeEnglishAndHighlightedLatinNeverFillTheFutureRowDuringAnEarlierWord() {
        for(text in listOf("Songs with all our childhood friends","ne e koi yo zu tto to ke na i de")) {
            val host=Host(RuntimeEnvironment.getApplication()).apply {
                layout(0,0,100,240)
                measuredLines=listOf(Line(0,3,80),Line(3,2,80),Line(5,1,80))
            }
            host.V.put(0,Mask(0,50,0,2));host.V.put(3,Mask(50,100,3,5))
            val port=RubyGradientBounds.forTest(Host::class.java)
            // There is no RubyView or registered word map in native mode.
            val middle=Word(listOf(4),0)
            val (key,prefix)=requireNotNull(port.refreshSweep(host,middle,listOf(4),false,false))
            assertEquals(text,3,key)
            assertEquals(listOf(0,3),prefix.map { (it as Mask).c })
            assertNotNull(host.V[5]);assertFalse(prefix.contains(host.V[5]))
            // Faithful A.d0 behavior: complete earlier masks, animate only the
            // current one. A subsequent native word callback cannot touch row 3.
            for(mask in prefix.map { it as Mask }) {
                mask.colors=if(mask.c==key) intArrayOf(Color.BLACK,Color.BLACK,0x33000000,0x33000000)
                    else intArrayOf(Color.BLACK,Color.BLACK)
                mask.h=if(mask.c==key) floatArrayOf(0f,.3f,.3f,1f) else null
                mask.i=if(mask.c==key).3f else 2f;mask.j=true
            }
            port.sync(host)
            assertEquals(255,Color.alpha(host.pixels().getPixel(20,120)))
            assertEquals(51,Color.alpha(host.pixels().getPixel(38,120)))
            assertEquals(.3f,host.V[3].i,0f)
            assertEquals(51,Color.alpha(host.V[5].colors.last()))
            assertEquals(51,Color.alpha(host.pixels().getPixel(20,190)))
            val end=Word(listOf(5),3)
            assertEquals(5,requireNotNull(port.refreshSweep(host,end,listOf(5),false,false)).first)
            assertEquals(0f,host.V[5].i,0f)
        }
    }
    @Test fun originalAndNativePronunciationKeepSeparateMasksAndProgress() {
        val host=Host(RuntimeEnvironment.getApplication()).apply { layout(0,0,100,160) }
        host.V.put(0,Mask(0,40,0,3));host.V.put(4,Mask(80,120,4,7))
        host.W.put(0,Mask(40,80,0,3));host.W.put(4,Mask(120,160,4,7))
        val port=RubyGradientBounds.forTest(Host::class.java)
        val word=Word(listOf(5),0)
        val (key,prefix)=requireNotNull(port.refreshSweep(host,word,listOf(5),false,true))
        assertEquals(4,key)
        assertSame(host.W[4],prefix.last());assertFalse(prefix.contains(host.V[4]))
        assertEquals(0f,host.V[4].i,0f)
        host.W.clear()
        assertNull(port.refreshSweep(host,word,listOf(5),false,true))
    }
    @Test fun appendedUntimedTranslationDoesNotAcquireAWordMask() {
        val host=Host(RuntimeEnvironment.getApplication()).apply {
            layout(0,0,100,240)
            measuredLines=listOf(Line(0,4,80),Line(4,4,80),Line(8,1,80))
        }
        host.V.put(0,Mask(0,60,0,3));host.V.put(4,Mask(60,100,4,7))
        RubyGradientBounds.forTest(Host::class.java).sync(host)
        assertEquals(2,host.V.size());assertNull(host.V[8])
        assertEquals(255,Color.alpha(host.pixels().getPixel(20,190)))
    }
}
