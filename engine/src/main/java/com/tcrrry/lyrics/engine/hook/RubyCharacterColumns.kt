package com.tcrrry.lyrics.engine.hook

import android.graphics.Canvas
import android.graphics.Paint
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ReplacementSpan
import android.widget.TextView
import java.lang.ref.WeakReference
import kotlin.math.ceil

/** A measured pronunciation column, like Japanese ruby columns, inside a native timed view.
 * Characters, source offsets, existing spans and native child indices stay intact.
 * Only Han/Hangul glyphs receive columns; literal Latin words retain native shaping.
 */
internal class RubyCharacterColumns(view:TextView,private val text:String,
    widths:List<Triple<Int,Int,Float>>,private val band:Float) {
    private val view=WeakReference(view)
    private val extra=view.lineSpacingExtra
    private val multiplier=view.lineSpacingMultiplier
    private val spans=widths.map { (start,end,width) -> Triple(start,end,Column(width)) }
    private class Column(private val needed:Float):ReplacementSpan() {
        private fun width(paint:Paint,text:CharSequence,start:Int,end:Int)=
            ceil(maxOf(needed,paint.measureText(text,start,end))).toInt()
        override fun getSize(paint:Paint,text:CharSequence,start:Int,end:Int,fm:Paint.FontMetricsInt?):Int {
            if(fm!=null) paint.getFontMetricsInt(fm)
            return width(paint,text,start,end)
        }
        override fun draw(canvas:Canvas,text:CharSequence,start:Int,end:Int,x:Float,top:Int,y:Int,bottom:Int,paint:Paint) {
            val offset=(width(paint,text,start,end)-paint.measureText(text,start,end))/2
            canvas.drawText(text,start,end,x+offset,y.toFloat(),paint)
        }
    }
    fun intact():Boolean=view.get()?.let { leaf ->
        val current=leaf.text as? Spanned
        leaf.lineSpacingExtra==extra+band && leaf.lineSpacingMultiplier==multiplier &&
            leaf.text.toString()==text && (spans.isEmpty() || current!=null && spans.all { (a,b,span) ->
                current.getSpanStart(span)==a && current.getSpanEnd(span)==b
            })
    }==true
    fun apply() { view.get()?.let { leaf ->
        if(leaf.text.toString()!=text) return
        if(!intact() && spans.isNotEmpty()) {
            val copy=SpannableString(leaf.text)
            spans.forEach { (a,b,span) -> copy.setSpan(span,a,b,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            leaf.setText(copy,TextView.BufferType.SPANNABLE)
        }
        leaf.setLineSpacing(extra+band,multiplier)
    } }
    fun restore() { view.get()?.let { leaf ->
        val current=leaf.text as? Spanned
        if(current!=null && spans.any { current.getSpanStart(it.third)>=0 }) {
            val copy=SpannableString(current)
            spans.forEach { copy.removeSpan(it.third) }
            leaf.setText(copy,TextView.BufferType.SPANNABLE)
        }
        leaf.setLineSpacing(extra,multiplier)
    } }
}
