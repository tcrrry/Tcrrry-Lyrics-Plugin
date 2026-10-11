package com.tcrrry.lyrics.engine.hook

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.text.Html
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView
import com.tcrrry.lyrics.engine.lyrics.JapaneseRubyAlignment
import com.tcrrry.lyrics.engine.lyrics.LyricsPreference
import com.tcrrry.lyrics.plugin.PluginLifetime
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.Executors

/** Annotations stay outside native gradient children. Native glyph boxes may
 * reserve columns and bottom space; top margins never shift their mask origin.
 */
internal object RubyPronunciationRows {
    private data class Key(val original: String, val reading: String)
    private data class Request(val key: Key)
    private val pending = WeakHashMap<View, Request>()
    private val rows = WeakHashMap<View, RubyView>()
    private val cache = object : LinkedHashMap<Key, JapaneseRubyAlignment.Result?>(128, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, JapaneseRubyAlignment.Result?>?) = size > 128
    }
    private var worker: java.util.concurrent.ExecutorService? = null
    private val main = Handler(Looper.getMainLooper())
    private fun plain(text: String) = Html.fromHtml(text, 0).toString()
    private fun compact(text: String) = text.filterNot { it.isWhitespace() || it == '\u00a0' }

    fun bindWords(container: ViewGroup, original: String, reading: String?, primary: Boolean) =
        bind(container, original, reading, primary) { result ->
            val items=RubyNativeWord.collect(container)
            val words = items.flatMap { it.glyphs }
            if (words.isEmpty() || compact(words.joinToString("") { it.text.toString() }) != compact(plain(original))) return@bind null
            RubyView(container, words, PronunciationHeaderLayout.auxiliary(container, true) as? TextView,
                RubyWordLayout.fitNativeUnits(items,plain(original),result), plain(original), false,items)
        }

    fun bindLine(originalView: TextView, pronunciation: TextView, original: String, reading: String?, primary: Boolean) =
        bind(originalView, original, reading, primary) { result ->
            if (compact(originalView.text.toString()) != compact(plain(original))) return@bind null
            RubyView(originalView, listOf(originalView), pronunciation, result, plain(original), true)
        }

    private fun bind(target: View, original: String, reading: String?, primary: Boolean,
        create: (JapaneseRubyAlignment.Result) -> RubyView?) {
        if (!PluginLifetime.alive || primary || reading.isNullOrBlank() || !LyricsPreference.rubyPronunciation(target.context)) {
            clear(target); return
        }
        val key = Key(plain(original), plain(reading))
        if (pending[target]?.key != key) clear(target)
        rows[target]?.let { if (it.hasCurrentHeader()) return else clear(target) }
        if (pending.containsKey(target)) return
        val request = Request(key)
        pending[target] = request
        fun install(result: JapaneseRubyAlignment.Result?) {
            if (pending[target] !== request || !PluginLifetime.alive || !LyricsPreference.rubyPronunciation(target.context)) return
            if (result == null) return
            val ruby = create(result) ?: run { pending.remove(target);return }
            val parent = target.parent as? ViewGroup ?: run { pending.remove(target);return }
            val header = ruby.currentHeader()?.takeIf { it.parent === parent && it.id != View.NO_ID }
                ?: run { pending.remove(target);return }
            val type = target.layoutParams.javaClass
            runCatching {
                val params = type.getConstructor(ViewGroup.LayoutParams::class.java)
                    .newInstance(ViewGroup.LayoutParams(0, 0)) as ViewGroup.LayoutParams
                for ((name, value) in listOf("topToTop" to header.id, "topToBottom" to -1,
                    "bottomToTop" to -1, "bottomToBottom" to target.id, "startToStart" to target.id, "endToEnd" to target.id))
                    PronunciationHeaderLayout.field(type, name).setInt(params, value)
                ruby.id = View.generateViewId()
                parent.addView(ruby, params)
                rows[target] = ruby
            }.onFailure {
                ruby.restore()
                parent.removeView(ruby)
                pending.remove(target)
                ModernXposedRuntime.log("ruby row kept ordinary pronunciation", it)
            }
        }
        synchronized(cache) {
            if (cache.containsKey(key)) { install(cache[key]); return }
        }
        // Never initialize or search Kuromoji on the UI thread. Do not capture
        // the row or its factory in the worker: recycled/detached rows can die.
        val reference = WeakReference(target)
        // Keep the callback only as long as this target's pending request.
        callbacks[target] = ::install
        val executor = worker ?: Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "Tcrrry-ruby").apply { isDaemon = true } }.also { worker = it }
        executor.execute {
            val result = runCatching { JapaneseRubyAlignment.align(key.original, key.reading) }.getOrNull()
            synchronized(cache) { cache[key] = result }
            main.post {
                val current = reference.get() ?: return@post
                if (pending[current] === request) callbacks.remove(current)?.invoke(result)
            }
        }
    }
    private val callbacks = WeakHashMap<View, (JapaneseRubyAlignment.Result?) -> Unit>()

    fun clear(target: View) {
        pending.remove(target); callbacks.remove(target)
        rows.remove(target)?.let { ruby -> ruby.restore(); (ruby.parent as? ViewGroup)?.removeView(ruby) }
    }
    fun refreshMode(enabled: Boolean) { if (!enabled) (rows.keys.toList() + pending.keys.toList()).distinct().forEach(::clear) }
    fun close() {
        rows.keys.toList().forEach(::clear)
        pending.clear(); callbacks.clear(); worker?.shutdownNow(); worker = null
        synchronized(cache) { cache.clear() }
    }

    private data class Glyph(val bounds: RectF, val nativeBaseline: Float, val stableCenter: Float, val stableTop: Float)
    internal data class Run(val text: String, val left: Float, val right: Float, val top: Float, val size: Float)

    internal class RubyView(
        target: View, words: List<TextView>, header: TextView?,
        private val alignment: JapaneseRubyAlignment.Result, private val original: String, private val lineMode: Boolean,
        items: List<RubyNativeWord> = words.map { RubyNativeWord(it,listOf(it)) },
    ) : View(target.context), ViewTreeObserver.OnPreDrawListener {
        private val target = WeakReference(target)
        private val words = words.map(::WeakReference)
        private val header = WeakReference(header)
        private val items = items.map { WeakReference(it.view) to it.glyphs.map(::WeakReference) }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val gap = 2f * resources.displayMetrics.density
        private var rendered: List<Run>? = null
        private var wordLayout: RubyWordLayout? = null
        private var preparedWidth = -1
        private var headerHeight: Int? = null
        init { isClickable = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

        internal fun currentHeader(): TextView? = header.get()
        fun hasCurrentHeader(): Boolean {
            val target = target.get() ?: return false
            val header = header.get() ?: return false
            return header.parent === target.parent && (lineMode ||
                PronunciationHeaderLayout.auxiliary(target as ViewGroup, true) === header) &&
                items.all { it.first.get()?.parent === if(lineMode) target.parent else target } &&
                (lineMode || RubyNativeWord.collect(target as ViewGroup).flatMap { it.glyphs } == words.mapNotNull { it.get() })
        }
        fun restore() {
            wordLayout?.restore(); wordLayout=null; preparedWidth=-1
            (target.get() as? ViewGroup)?.let(RubyGradientBounds::release)
            header.get()?.takeIf { it.visibility == INVISIBLE }?.visibility = VISIBLE
            restoreHeaderHeight()
        }
        private fun restoreHeaderHeight(): Boolean {
            val saved=headerHeight ?: return false
            headerHeight=null
            val header=header.get() ?: return false
            if(header.layoutParams.height==saved) return false
            header.layoutParams=header.layoutParams.apply { height=saved }
            return true
        }
        private fun compactHeader(): Boolean {
            val header=header.get() ?: return false
            // Hidden ordinary pronunciation may wrap to several lines. Reserve
            // just one reading row, retaining the native text for safe fallback.
            val desired=header.compoundPaddingTop+header.compoundPaddingBottom+
                (header.layout?.getLineBottom(0) ?: header.lineHeight)
            if(header.height==desired || header.layoutParams.height==desired) return false
            if(headerHeight==null) headerHeight=header.layoutParams.height
            header.layoutParams=header.layoutParams.apply { height=desired }
            return true
        }
        override fun onAttachedToWindow() { super.onAttachedToWindow(); viewTreeObserver.addOnPreDrawListener(this) }
        override fun onDetachedFromWindow() {
            restore()
            if (viewTreeObserver.isAlive) viewTreeObserver.removeOnPreDrawListener(this)
            super.onDetachedFromWindow()
        }
        override fun onPreDraw(): Boolean {
            val current=target.get()
            if(compact(words.mapNotNull { it.get() }.joinToString("") { it.text.toString() })!=compact(original)) {
                restore();rendered=null;invalidate();return true
            }
            if (!lineMode && current != null && current.width>0 && preparedWidth != current.width) {
                wordLayout?.restore(); wordLayout=null; preparedWidth=current.width
                val header=header.get()
                if (header != null) {
                    val nativeItems=items.mapNotNull { (root,leaves) -> root.get()?.let { RubyNativeWord(it,leaves.mapNotNull { leaf -> leaf.get() }) } }
                    wordLayout=RubyWordLayout.createNative(nativeItems,header,original,alignment,
                        current.width-current.paddingLeft-current.paddingRight)
                    wordLayout?.apply()
                    if (wordLayout != null && current is ViewGroup) RubyGradientBounds.acquire(current)
                    if (wordLayout != null) return false // measure before showing native gradients
                }
            }
            val plan=wordLayout
            if(plan!=null) {
                val extraBand=header.get()?.let(plan::reserveActualLines)==true
                if(extraBand || !plan.isIntact()) { plan.apply();return false }
            }
            if (wordLayout != null && current is ViewGroup) RubyGradientBounds.syncOwned(current)
            val next = placements()
            val headerChanged=if(next==null) restoreHeaderHeight() else compactHeader()
            header.get()?.let { header ->
                if (header.visibility != GONE) header.visibility = if (next == null) VISIBLE else INVISIBLE
            }
            if (next != rendered) { rendered = next; invalidate() }
            return !headerChanged
        }

        internal fun placements(): List<Run>? {
            val target = target.get() ?: return null
            val header = header.get() ?: return null
            val parent = target.parent as? ViewGroup ?: return null
            if (header.parent !== parent || header.visibility == GONE || width <= 0 || height <= 0) return null
            val source = words.mapNotNull { it.get() }
            if (compact(source.joinToString("") { it.text.toString() }) != compact(original)) return null
            fun normalized(text: String) = text.filter(Char::isLetterOrDigit).lowercase(java.util.Locale.ROOT)
            if (normalized(header.text.toString()) != normalized(alignment.units.joinToString("") { it.reading })) return null
            val glyphs = mutableListOf<Glyph>()
            for (word in source) {
                var ancestor: View?=word
                while(ancestor!==target && ancestor!==parent) ancestor=ancestor?.parent as? View ?: return null
                if (word.visibility == GONE) return null
                val layout = word.layout ?: return null
                if (layout.lineCount != 1) return null
                for (offset in word.text.indices) {
                    if (word.text[offset].isWhitespace() || word.text[offset] == '\u00a0') continue
                    val x1 = layout.getPrimaryHorizontal(offset)
                    val x2 = layout.getPrimaryHorizontal(offset + 1)
                    val baseline = word.compoundPaddingTop + layout.getLineBaseline(0)
                    val bounds = RectF(word.compoundPaddingLeft + minOf(x1, x2), baseline + word.paint.fontMetrics.ascent,
                        word.compoundPaddingLeft + maxOf(x1, x2), baseline.toFloat())
                    fun mapToRow(rect: RectF, animated: Boolean): Boolean {
                        var node: View=word
                        while(true) {
                            if(animated) node.matrix.mapRect(rect)
                            rect.offset(node.left.toFloat(),node.top.toFloat())
                            val owner=node.parent as? ViewGroup ?: return false
                            if(owner===parent) break
                            rect.offset(-owner.scrollX.toFloat(),-owner.scrollY.toFloat())
                            node=owner
                        }
                        rect.offset(-left.toFloat(),-top.toFloat())
                        return true
                    }
                    val stable=RectF(bounds)
                    if(!mapToRow(stable,false)) return null
                    // Include the native per-word scale/pivot/translation. These
                    // transforms animate without triggering another layout.
                    if(!mapToRow(bounds,true)) return null
                    glyphs += Glyph(bounds, stable.bottom, stable.centerX(),stable.top)
                }
            }
            if (glyphs.size != compact(original).length || glyphs.isEmpty()) return null
            // Only the reversible word layout reserves an inter-line band.
            val tolerance = source.minOf { it.textSize } * .35f
            val nativeLines=glyphs.map { it.nativeBaseline }.sorted().fold(mutableListOf<Float>()) { lines, y ->
                if (lines.isEmpty() || y-lines.last()>tolerance) lines+=y
                lines
            }
            if (nativeLines.size>1 && wordLayout==null) return null
            paint.set(header.paint)
            val baseline = header.top - top + header.baseline.toFloat()
            if (baseline + paint.fontMetrics.ascent < 0 || baseline + paint.fontMetrics.descent > height) return null
            val stableRanges = mutableListOf<Triple<Float, Float, Float>>()
            val originalGlyphs=compact(original)
            val result = alignment.units.map { unit ->
                val start = compact(original.take(unit.start)).length
                val end = compact(original.take(unit.end)).length
                if (start >= end || end > glyphs.size) return null
                // Punctuation attached to a timed view affects its width, but
                // the reading remains centered over its pronounced characters.
                val touched = glyphs.subList(start, end).filterIndexed { i,_->originalGlyphs[start+i].isLetterOrDigit() }
                if(touched.isEmpty()) return null
                if (touched.maxOf { it.nativeBaseline }-touched.minOf { it.nativeBaseline }>tolerance) return null
                val text = JapaneseRubyAlignment.displayReading(unit.reading)
                val center = (touched.minOf { it.bounds.left } + touched.maxOf { it.bounds.right }) / 2
                val half = paint.measureText(text) / 2
                // One font size for the complete row. No per-word shrinking,
                // heuristic merging or displacement away from its original.
                val stableCenter = (touched.first().stableCenter + touched.last().stableCenter) / 2
                if (stableCenter - half < 0 || stableCenter + half > width) return null
                val line=nativeLines.indexOfFirst { kotlin.math.abs(touched.first().nativeBaseline-it)<tolerance }
                val sameLine=glyphs.filter { kotlin.math.abs(it.nativeBaseline-nativeLines[maxOf(0,line)])<tolerance }
                // One stable vertical anchor per visual line. A word's pulse
                // must not pull only its reading up into the preceding glyphs.
                val readingBaseline=sameLine.minOf { it.stableTop }-gap-paint.fontMetrics.descent
                if(line>0) {
                    val previous=glyphs.filter { kotlin.math.abs(it.nativeBaseline-nativeLines[line-1])<tolerance }
                    if(readingBaseline+paint.fontMetrics.ascent<previous.maxOf { it.bounds.bottom }+gap) return null
                }
                if (readingBaseline+paint.fontMetrics.ascent<0 || readingBaseline+paint.fontMetrics.descent>height) return null
                stableRanges += Triple(stableCenter-half,stableCenter+half,touched.first().nativeBaseline)
                Run(text, center - half, center + half, readingBaseline, header.textSize)
            }
            // Animation alone must not toggle between ruby and ordinary text.
            if (stableRanges.zipWithNext().any { (a, b) -> kotlin.math.abs(a.third-b.third)<tolerance && a.second + gap > b.first }) return null
            return result
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val header = header.get() ?: return
            if (header.visibility != INVISIBLE) return
            paint.set(header.paint)
            paint.color = header.currentTextColor
            rendered?.forEach { run ->
                paint.textSize = run.size
                canvas.drawText(run.text, run.left, run.top, paint)
            }
        }
    }
}
