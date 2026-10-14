package com.tcrrry.lyrics.engine.hook

import android.graphics.Paint
import android.view.ViewGroup
import android.view.View
import android.widget.TextView
import com.tcrrry.lyrics.engine.lyrics.JapaneseRubyAlignment
import java.lang.ref.WeakReference
import kotlin.math.ceil

/** Measured pronunciation columns; native children and timestamps stay intact.
 * Extra vertical space belongs to the BOTTOM of glyph boxes, so the first
 * native line starts at its original top (unlike the rejected r1 top margins).
 */
internal class RubyWordLayout private constructor(private val saved: List<Saved>, private val plans: List<Plan>) {
    private data class Saved(val view: WeakReference<View>, val left: Int, val top: Int, val right: Int, val bottom: Int,
        val wrap: Boolean, val shrink: Float, val flex: RubyFlexItem)
    private data class Plan(val left: Int, val right: Int, var bottom: Int, val wrap: Boolean)
    fun isIntact(): Boolean = saved.zip(plans).all { (old, plan) -> old.view.get()?.let { word ->
        word.paddingLeft==old.left+plan.left && word.paddingRight==old.right+plan.right &&
            word.paddingTop==old.top && word.paddingBottom==old.bottom+plan.bottom &&
            old.flex.wrap(word.layoutParams)==plan.wrap && old.flex.shrink(word.layoutParams)==0f
    } == true }
    fun reserveActualLines(header: TextView): Boolean {
        val words=saved.mapNotNull { it.view.get() }
        if(words.isEmpty()) return false
        val fm=header.paint.fontMetrics
        val band=ceil(fm.descent-fm.ascent+8f*header.resources.displayMetrics.density).toInt()
        // Flex items in a row share its top even if a nested character has a
        // different baseline. Only rows preceding another row need a band.
        val lastTop=words.maxOf { it.top }
        val tolerance=2f*header.resources.displayMetrics.density
        var changed=false
        saved.zip(plans).forEach { (old,plan) -> old.view.get()?.let { word ->
            val bottom=if(word.top<lastTop-tolerance) band else 0
            if(plan.bottom!=bottom) { plan.bottom=bottom;changed=true }
        } }
        return changed
    }
    fun apply() {
        saved.zip(plans).forEach { (old, plan) -> old.view.get()?.let { word ->
            old.flex.setWrap(word.layoutParams, plan.wrap)
            old.flex.setShrink(word.layoutParams, 0f)
            word.layoutParams = word.layoutParams
            word.setPadding(old.left + plan.left, old.top, old.right + plan.right, old.bottom + plan.bottom)
        } }
    }
    fun restore() {
        saved.forEach { old -> old.view.get()?.let { word ->
            old.flex.setWrap(word.layoutParams, old.wrap)
            old.flex.setShrink(word.layoutParams, old.shrink)
            word.layoutParams = word.layoutParams
            word.setPadding(old.left,old.top,old.right,old.bottom)
        } }
    }
    companion object {
        /** Source timing sometimes stores several Japanese characters in one
         * view. Keep that timed view intact and annotate it as one word instead
         * of rejecting every otherwise valid pronunciation on the lyric row.
         */
        fun fitUnits(words: List<TextView>, original: String, alignment: JapaneseRubyAlignment.Result): JapaneseRubyAlignment.Result {
            return fitNativeUnits(words.map { RubyNativeWord(it,listOf(it)) },original,alignment)
        }
        fun fitNativeUnits(words: List<RubyNativeWord>, original: String, alignment: JapaneseRubyAlignment.Result): JapaneseRubyAlignment.Result {
            fun compact(s: String)=s.filterNot { it.isWhitespace() || it=='\u00a0' }
            val boundaries=mutableListOf(0);var offset=0
            words.forEach { offset+=compact(it.text).length;if(boundaries.last()!=offset)boundaries+=offset }
            if (compact(original).length!=offset) return alignment
            val positions=original.indices.filter { !original[it].isWhitespace() && original[it]!='\u00a0' }
            val units=mutableListOf<JapaneseRubyAlignment.Unit>()
            for (unit in alignment.units) {
                val start=compact(original.take(unit.start)).length;val end=compact(original.take(unit.end)).length
                val low=boundaries.lastOrNull { it<=start } ?: return alignment
                val high=boundaries.firstOrNull { it>=end } ?: return alignment
                val expanded=JapaneseRubyAlignment.Unit(positions.getOrElse(low) { original.length },
                    if(high==positions.size)original.length else positions[high],unit.reading)
                val previous=units.lastOrNull()
                if(previous!=null && expanded.start<previous.end) {
                    units[units.lastIndex]=JapaneseRubyAlignment.Unit(previous.start,maxOf(previous.end,expanded.end),previous.reading+expanded.reading)
                } else units+=expanded
            }
            return JapaneseRubyAlignment.Result(units,alignment.dictionaryMatch)
        }

        fun create(words: List<TextView>, header: TextView, original: String,
            alignment: JapaneseRubyAlignment.Result, available: Int): RubyWordLayout? =
            createNative(words.map { RubyNativeWord(it,listOf(it)) },header,original,alignment,available)

        fun createNative(items: List<RubyNativeWord>, header: TextView, original: String,
            alignment: JapaneseRubyAlignment.Result, available: Int): RubyWordLayout? = runCatching {
            val words=items.map { it.view }
            if (available <= 0 || words.isEmpty()) return null
            fun compact(s: String) = s.filterNot { it.isWhitespace() || it == '\u00a0' }
            val starts = mutableListOf<Int>(); val ends = mutableListOf<Int>(); var offset=0
            items.forEach { word -> starts += offset; offset += compact(word.text).length; ends += offset }
            if (compact(items.joinToString("") { it.text }) != compact(original)) return null
            val saved = words.map { word ->
                val flex=RubyFlexItem.resolve(word.layoutParams.javaClass)
                Saved(WeakReference(word),word.paddingLeft,word.paddingTop,word.paddingRight,word.paddingBottom,
                    flex.wrap(word.layoutParams),flex.shrink(word.layoutParams),flex)
            }
            // Native wrap hints belong to the old layout. Keeping one inside a
            // reading pair can strand its first character on a line by itself.
            val left=IntArray(words.size); val right=IntArray(words.size); val wraps=BooleanArray(words.size)
            val lines=IntArray(words.size)
            val paint=Paint(header.paint)
            val gap=4f*header.resources.displayMetrics.density
            val band=ceil(paint.fontMetrics.descent-paint.fontMetrics.ascent+gap*2).toInt()
            fun nativeWidth(i: Int): Float {
                val word=words[i]; val margins=word.layoutParams as? ViewGroup.MarginLayoutParams
                // Use TextView/compound-container measurement, including font
                // shaping, letter spacing and nested padding. Fractional paint
                // estimates can overflow just before a planned forced break.
                val spec=if(word.layoutParams.width>=0) View.MeasureSpec.makeMeasureSpec(word.layoutParams.width,View.MeasureSpec.EXACTLY)
                    else View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED)
                word.measure(spec,View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
                return word.measuredWidth.toFloat()+
                    (margins?.leftMargin?:0)+(margins?.rightMargin?:0)
            }
            val widths=words.indices.map(::nativeWidth)
            data class Column(val last:Int,val width:Float,val before:Float)
            val columns=mutableListOf<Column>()
            var lastEnd=0; var previousLast=-1
            for (unit in alignment.units) {
                val start=compact(original.take(unit.start)).length; val end=compact(original.take(unit.end)).length
                // Whitespace views have duplicate compact offsets. They are not
                // the glyph receiving a reading's left/right column padding.
                val first=words.indices.firstOrNull { starts[it]==start && ends[it]>start } ?: return null
                val last=words.indices.lastOrNull { ends[it]==end && starts[it]<end } ?: return null
                // A native multi-character timed word cannot be cut into new
                // views. Reject that layout instead of changing timing indices.
                if (first<0 || last<first || start<lastEnd) return null
                lastEnd=end
                val native=(first..last).sumOf { widths[it].toDouble() }.toFloat()
                val intervening=(previousLast+1 until first).sumOf { widths[it].toDouble() }.toFloat()
                val required=maxOf(native,ceil(paint.measureText(JapaneseRubyAlignment.displayReading(unit.reading))+gap))
                if (required>available) return null
                val extra=ceil(required-native).toInt().coerceAtLeast(0)
                // Widen the entire original/reading pair, never the font.
                left[first] += extra/2; right[last] += extra-extra/2
                columns+=Column(last,native+extra,intervening)
                previousLast=last
            }
            if(columns.isEmpty()) return null
            // Fill each row before wrapping, keeping each reading pair intact.
            // Balancing slack across rows caused conspicuous early line breaks.
            val n=columns.size
            val next=IntArray(n)
            for(i in 0 until n) {
                var width=columns[i].before
                for(j in i until n) {
                    if(j>i) width+=columns[j].before
                    width+=columns[j].width
                    if(width>available) break
                    next[i]=j+1
                }
            }
            var i=0;var line=0;previousLast=-1
            while(i<n) {
                val end=next[i]
                if(end<=i) return null
                if(i>0) wraps[previousLast+1]=true
                for(k in previousLast+1..columns[end-1].last) lines[k]=line
                previousLast=columns[end-1].last;i=end;line++
            }
            val lineCount=line
            for(i in previousLast+1 until words.size) lines[i]=lineCount-1
            RubyWordLayout(saved,words.indices.map { i -> Plan(left[i],right[i],if(lines[i]<lineCount-1) band else 0,wraps[i]) })
        }.getOrNull()
    }
}
