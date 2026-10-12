package com.tcrrry.lyrics.engine.hook

import android.graphics.Canvas
import android.util.SparseArray
import android.view.ViewGroup
import com.tcrrry.lyrics.plugin.PluginLifetime
import java.lang.reflect.Field
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** The host caches immutable line bounds before ruby reserves its inter-line band.
 * Keep those same mask objects (and their running animators), but synchronize their
 * geometry with the measured flex lines. Clearing/replacing masks loses progress.
 */
internal class RubyGradientBounds private constructor(type: Class<*>) {
    private val lines = type.getMethod("getFlexLines")
    private val topMasks = field(type, "V")
    private val bottomMasks = field(type, "W")
    private var lineFields: List<Field>? = null
    private var maskFields: List<Field>? = null
    private var wordReferences: Field? = null
    private var referenceFields: List<Field>? = null
    private val layouts=WeakHashMap<ViewGroup,Long>()
    private data class Cached(val key: Int, val mask: Any, val secondary: Any?, val first: Int, val last: Int)

    fun sync(container: ViewGroup) {
        val measured = lines.invoke(container) as? List<*> ?: return
        val first = measured.firstOrNull() ?: return
        val geometry = lineFields ?: listOf("o", "h", "g").map { field(first.javaClass, it) }.also { lineFields = it }
        @Suppress("UNCHECKED_CAST")
        val tops = topMasks.get(container) as SparseArray<Any>
        @Suppress("UNCHECKED_CAST")
        val bottoms = bottomMasks.get(container) as SparseArray<Any>
        if(tops.size()==0) return
        val sample=tops.valueAt(0)
        val fields=maskFields ?: listOf("a","b","c","d","j").map { field(sample.javaClass,it) }.also { maskFields=it }
        fun layoutKey():Long {
            var key=container.childCount.toLong()
            fun add(value:Int) { key=key*31+value }
            measured.filterNotNull().forEach { line -> geometry.forEach { add(it.getInt(line)) } }
            for(array in listOf(tops,bottoms)) {
                add(array.size())
                for(i in 0 until array.size()) {
                    add(array.keyAt(i));val mask=array.valueAt(i);add(System.identityHashCode(mask))
                    for(j in 0..3) add(fields[j].getInt(mask))
                }
            }
            add(System.identityHashCode(wordMaps[container]?.get()))
            return key
        }
        // Native animation changes colors/progress every frame, not geometry.
        // Avoid rebuilding/reindexing every mask and word map at display rate.
        if(layouts[container]==layoutKey()) return
        val cached=(0 until tops.size()).map { i ->
            val mask=tops.valueAt(i);val key=tops.keyAt(i)
            Cached(key,mask,bottoms[key],fields[2].getInt(mask),fields[3].getInt(mask))
        }
        val used=java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any,Boolean>())
        val nextTops=linkedMapOf<Int,Any>();val nextBottoms=linkedMapOf<Int,Any>()
        val lastTimedChild=cached.maxOf { it.last }
        var y = 0
        measured.forEach { line ->
            val index = geometry[0].getInt(line)
            val count = minOf(geometry[1].getInt(line),lastTimedChild-index+1)
            val height = geometry[2].getInt(line)
            // Appended full-row subtitles are not timed words. Do not invent
            // an animated mask for a translation outside the native mask range.
            if(count<=0) { y+=height;return@forEach }
            val selected=cached.firstOrNull { it.key==index && it.mask !in used }
                ?: cached.firstOrNull { index in it.first..it.last && it.mask !in used }
                ?: cached.firstOrNull { it.mask !in used && it.first<=index+count-1 && it.last>=index }
            val top=selected?.mask ?: newMask(container,sample,fields,cached,index,index+count-1,y,y+height)
            val bottom=selected?.secondary
            if (top != null) {
                used+=top;nextTops[index]=top
                if(bottom!=null) nextBottoms[index]=bottom
                // W is an optional second vertical segment with a different
                // native shader. Retain that split, without overlapping it.
                val split = if (bottom == null) y + height else
                    (y + fields[1].getInt(top) - fields[0].getInt(top)).coerceIn(y, y + height)
                update(top, fields, y, split, index, index + count - 1)
                if (bottom != null) update(bottom, fields, split, y + height, index, index + count - 1)
            }
            y += height
        }
        fun reindex(array:SparseArray<Any>, expected:Map<Int,Any>) {
            if(array.size()==expected.size && expected.all { (key,value)->array[key]===value }) return
            array.clear();expected.forEach { (key,value)->array.put(key,value) }
        }
        reindex(tops,nextTops);reindex(bottoms,nextBottoms)
        wordMaps[container]?.get()?.let { groups -> syncWordIndices(groups,nextTops.values,fields) }
        layouts[container]=layoutKey()
    }

    fun syncWord(container: ViewGroup, word: Any) {
        @Suppress("UNCHECKED_CAST")
        val masks=topMasks.get(container) as SparseArray<Any>
        val fields=maskFields ?: return
        syncWordIndices(mapOf(0 to word),(0 until masks.size()).map(masks::valueAt),fields)
    }

    fun refreshSweep(container: ViewGroup, word: Any, bindings: List<*>, reverse: Boolean, lower: Boolean): Pair<Int,List<Any>>? {
        sync(container)
        syncWord(container,word)
        val binding=if(reverse) bindings.lastOrNull() else bindings.firstOrNull()
        val references=field(word.javaClass,"n").get(word) as Map<*,*>
        val reference=references[binding] ?: return null
        val key=field(reference.javaClass,"b").get(reference) as? Int ?: return null
        @Suppress("UNCHECKED_CAST")
        val masks=(if(lower)bottomMasks else topMasks).get(container) as SparseArray<Any>
        if(masks.indexOfKey(key)<0) return null
        return key to (0 until masks.size()).filter { masks.keyAt(it)<=key }.map(masks::valueAt)
    }

    private fun syncWordIndices(groups: Map<*, *>, masks: Collection<Any>, fields: List<Field>) {
        for (word in groups.values.filterNotNull()) {
            val references=wordReferences ?: field(word.javaClass,"n").also { wordReferences=it }
            val bindings=references.get(word) as? Map<*,*> ?: continue
            for (reference in bindings.values.filterNotNull()) {
                val values=referenceFields ?: listOf("a","b").map { field(reference.javaClass,it) }.also { referenceFields=it }
                val index=(values[0].get(reference) as? Number)?.toInt() ?: continue
                val mask=masks.firstOrNull { index in fields[2].getInt(it)..fields[3].getInt(it) } ?: continue
                val key=fields[2].getInt(mask)
                // A.b0 caches B(childIndex, TOP) in r0.b BEFORE ruby reflows.
                // A.d0 later looks up z(r0.b, TOP) by exact key. Geometry alone
                // leaves continuation words routed to the old row (or no row).
                if(values[1].get(reference)!=key) values[1].set(reference,key)
            }
        }
    }

    private fun newMask(container:ViewGroup,sample:Any,fields:List<Field>,cached:List<Cached>,first:Int,last:Int,top:Int,bottom:Int):Any? {
        val type=sample.javaClass
        val constructor=type.getDeclaredConstructor(container.javaClass,Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,Int::class.javaPrimitiveType).apply { isAccessible=true }
        val result=constructor.newInstance(container,top,bottom,first,last)
        val source=cached.lastOrNull { first in it.first..it.last } ?: cached.lastOrNull { it.first<=first } ?: cached.first()
        // A newly split continuation starts with the original mask's remaining
        // (right-hand) alpha. It must not default to the constructor's full white.
        val colors=field(type,"g")
        val remaining=(colors.get(source.mask) as IntArray).last()
        colors.set(result,intArrayOf(remaining,remaining))
        field(type,"h").set(result,null)
        field(type,"i").setFloat(result,if((remaining ushr 24)==255)1f else 0f)
        fields[4].setBoolean(result,true)
        return result
    }
    private fun update(mask: Any, fields: List<Field>, top: Int, bottom: Int, first: Int, last: Int) {
        if (fields[0].getInt(mask) == top && fields[1].getInt(mask) == bottom && fields[2].getInt(mask) == first && fields[3].getInt(mask)==last) return
        fields[0].setInt(mask, top)
        fields[1].setInt(mask, bottom)
        fields[2].setInt(mask, first)
        fields[3].setInt(mask, last)
        fields[4].setBoolean(mask, true) // Host rebuilds its own Rect and shader.
    }

    companion object {
        private fun field(type: Class<*>, name: String) = type.lyricsDeclaredField(name).apply { isAccessible = true }
        private val ports = mutableMapOf<Class<*>, RubyGradientBounds>()
        private val nativeTypes = mutableSetOf<Class<*>>()
        private val owned = WeakHashMap<ViewGroup, RubyGradientBounds>()
        private val retiring = WeakHashMap<ViewGroup, Boolean>()
        private val wordMaps = WeakHashMap<ViewGroup,WeakReference<Map<*,*>>>()
        fun trackWordMap(container: ViewGroup, groups: Map<*,*>?) {
            if(groups==null) wordMaps.remove(container) else wordMaps[container]=WeakReference(groups)
        }
        private fun port(type: Class<*>): RubyGradientBounds? = ports[type] ?: runCatching {
                val value = RubyGradientBounds(type)
                ModernXposedRuntime.hookMethod(type.getDeclaredMethod("dispatchDraw", Canvas::class.java), object : ModernMethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val view = param.thisObject as? ViewGroup ?: return
                        (if(type in nativeTypes) value else owned[view])?.let { current -> runCatching { current.sync(view) }
                            .onFailure { ModernXposedRuntime.log("Ruby mask geometry sync failed open", it) } }
                        if (retiring.remove(view) == true) owned.remove(view)
                    }
                })
                PluginLifetime.close { owned.clear(); retiring.clear(); wordMaps.clear(); nativeTypes.remove(type); ports.remove(type) }
                value.also { ports[type] = it }
            }.onFailure { ModernXposedRuntime.log("Ruby gradient bounds unavailable", it) }.getOrNull()

        /** All native text, including highlighted romanization, can wrap after
         * binding. Repair geometry before draw and cached keys before the host
         * constructs a timed sweep; never change a duration or fill a future row.
         */
        fun installNative(adapter: Class<*>): Boolean = runCatching {
            val build=adapter.declaredMethods.single { it.name=="d0" && it.parameterCount==9 }
            val type=build.parameterTypes[4]
            require(type.name=="com.apple.android.music.common.views.FullWidthAlphaGradientFlexboxLayout")
            val sync=port(type) ?: return false
            nativeTypes+=type
            val bottom=field(type,"W")
            ModernXposedRuntime.hookMethod(build,object:ModernMethodHook() {
                override fun beforeHookedMethod(param:MethodHookParam) {
                    runCatching {
                        val container=param.args[4] as ViewGroup
                        val input=param.args[1] as Array<*>
                        // An empty array does not identify TOP vs BOTTOM. In
                        // particular, absent pronunciation masks must never
                        // redirect a pronunciation sweep onto original text.
                        if(input.isEmpty()) {
                            sync.sync(container);param.args[2]?.let { sync.syncWord(container,it) }
                            return@runCatching
                        }
                        @Suppress("UNCHECKED_CAST")
                        val lower=bottom.get(container) as SparseArray<Any>
                        val isLower=input.firstOrNull()?.let { mask -> (0 until lower.size()).any { lower.valueAt(it)===mask } }==true
                        val word=param.args[2] ?: return@runCatching
                        val bindings=param.args[3] as List<*>
                        val (key,prefix)=sync.refreshSweep(container,word,bindings,param.args[6]==true,isLower) ?: return@runCatching
                        val array=java.lang.reflect.Array.newInstance(requireNotNull(build.parameterTypes[1].componentType),prefix.size)
                        prefix.forEachIndexed { index,mask -> java.lang.reflect.Array.set(array,index,mask) }
                        param.args[0]=key;param.args[1]=array
                    }.onFailure { ModernXposedRuntime.log("Native word gradient refresh failed open",it) }
                }
            })
            // Static completion/reset callbacks also read the same cached key.
            adapter.declaredMethods.singleOrNull { it.name=="W" && it.parameterCount==7 }?.let { reset ->
                ModernXposedRuntime.hookMethod(reset,object:ModernMethodHook() {
                    override fun beforeHookedMethod(param:MethodHookParam) {
                        runCatching {
                            val container=param.args[2] as ViewGroup
                            sync.sync(container)
                            param.args[0]?.let { sync.syncWord(container,it) }
                        }.onFailure { ModernXposedRuntime.log("Native word reset gradient refresh failed open",it) }
                    }
                })
            }
            true
        }.onFailure { ModernXposedRuntime.log("Native gradient geometry unavailable",it) }.getOrDefault(false)

        fun acquire(container: ViewGroup) {
            if (container.javaClass.name != "com.apple.android.music.common.views.FullWidthAlphaGradientFlexboxLayout") return
            val port = port(container.javaClass) ?: return
            owned[container] = port
            retiring.remove(container)
        }
        fun syncOwned(container: ViewGroup) { owned[container]?.sync(container) }
        // Restore occurs before the next measure. Keep the hook until that draw,
        // so native bounds also return to their ordinary geometry after disabling.
        fun release(container: ViewGroup) {
            retiring[container] = true
        }
        internal fun forTest(type: Class<*>) = RubyGradientBounds(type)
    }
}
