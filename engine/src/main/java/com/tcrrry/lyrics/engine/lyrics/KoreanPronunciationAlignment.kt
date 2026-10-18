package com.tcrrry.lyrics.engine.lyrics

import java.util.Locale

/** Project synchronized provider syllables onto Hangul only when order and sounds agree. */
internal object KoreanPronunciationAlignment {
    data class Unit(val start: Int, val end: Int, val text: String)
    private val surface = Regex("[가-힣]|[A-Za-z0-9]+(?:['’-][A-Za-z]+)*")
    private val reading = Regex("[A-Za-z0-9]+(?:['’-][A-Za-z]+)*")
    private val syllable = Regex("(kk|gg|tt|dd|pp|bb|ss|jj|ch|sh|[gkdtnrlmbpsjchzqx])?(yeo|yae|wae|weo|ae|ya|eo|ye|wa|oe|yo|wo|we|wi|yu|eu|ui|a|e|o|u|i)(ng|kk|gg|tt|dd|pp|bb|ss|lk|lg|lm|lb|ls|lt|lp|lh|nh|nj|ks|gs|bs|ch|[gkntdlrmbpsjh])?")
    private val initials = listOf(setOf("g","k","kk","gg"),setOf("kk","gg","k"),setOf("n","l","r"),
        setOf("d","t","tt","dd"),setOf("tt","dd","t"),setOf("r","l","n"),setOf("m"),
        setOf("b","p","pp","bb"),setOf("pp","bb","p"),setOf("s","ss","sh","x"),setOf("ss","s","sh","x"),
        setOf(""),setOf("j","ch","jj","z"),setOf("jj","j","z"),setOf("ch","c","q"),setOf("k","g"),setOf("t","d"),setOf("p","b"),setOf("h"))
    private val vowels = listOf(setOf("a"),setOf("ae"),setOf("ya"),setOf("yae"),setOf("eo"),setOf("e"),
        setOf("yeo"),setOf("ye"),setOf("o"),setOf("wa"),setOf("wae"),setOf("oe","we"),setOf("yo"),
        setOf("u"),setOf("wo","weo"),setOf("we"),setOf("wi"),setOf("yu"),setOf("eu"),setOf("ui","i","e"),setOf("i"))
    private val liaison = listOf(setOf(""),setOf("g","k"),setOf("kk","gg"),setOf("s","ss"),setOf("n"),
        setOf("j","ch"),setOf("h","n"),setOf("d","t"),setOf("r","l"),setOf("g","k"),setOf("m"),
        setOf("b","p"),setOf("s","ss"),setOf("t","d"),setOf("p","b"),setOf("h"),setOf("m"),
        setOf("b","p"),setOf("s","ss"),setOf("s","ss","sh","x"),setOf("ss","s","sh","x"),setOf(""),
        setOf("j","ch"),setOf("ch","c"),setOf("k","g"),setOf("t","d"),setOf("p","b"),setOf("h"))
    private val codas=listOf(setOf(""),setOf("g","k","ng"),setOf("kk","gg","k"),setOf("ks","gs","k"),setOf("n","l","m"),
        setOf("nj","n"),setOf("nh","n"),setOf("d","t","n"),setOf("l","r"),setOf("lg","lk","l","k"),
        setOf("lm","m","l"),setOf("lb","lp","l","p"),setOf("ls","l"),setOf("lt","l"),setOf("lp","l","p"),setOf("lh","l"),
        setOf("m"),setOf("b","p","m"),setOf("bs","p","b","m"),setOf("s","t","n"),setOf("ss","s","t","n"),setOf("ng"),
        setOf("j","t","n"),setOf("ch","c","t","n"),setOf("k","g","ng"),setOf("t","d","n"),setOf("p","b","m"),setOf("h","t",""))
    fun units(original:String,pronunciation:String):List<Unit>? {
        if(original.isBlank() || original.length>500 || pronunciation.length>2000) return null
        val originals=surface.findAll(original).toList()
        if(surface.replace(original,"").any(Char::isLetterOrDigit) || reading.replace(pronunciation,"").any(Char::isLetterOrDigit)) return null
        val positions=pronunciation.indices.filter { pronunciation[it].isLetterOrDigit() }
        val phonetic=positions.map { pronunciation[it] }.joinToString("").lowercase(Locale.ROOT)
        if(phonetic.isEmpty()) return null
        fun hangul(index:Int)=originals.getOrNull(index)?.value?.singleOrNull()?.takeIf { it in '가'..'힣' }
        fun separated(end:Int)=end==positions.size || (end>0 && positions[end]-positions[end-1]>1)
        data class Parse(val score:Int,val ends:List<Int>)
        val memo=HashMap<Pair<Int,Int>,Parse?>();var budget=16000
        fun solve(index:Int,offset:Int):Parse? {
            if(--budget<0) return null
            if(index==originals.size) return if(offset==phonetic.length) Parse(0,emptyList()) else null
            val key=index to offset
            if(memo.containsKey(key)) return memo[key]
            var best:Parse?=null;var ambiguous=false
            fun consider(end:Int,score:Int) {
                val tail=solve(index+1,end) ?: return
                val result=Parse(score+tail.score,listOf(end)+tail.ends)
                if(best==null || result.score>best!!.score) { best=result;ambiguous=false }
                else if(result.score==best!!.score && result.ends!=best!!.ends) ambiguous=true
            }
            val c=hangul(index)
            if(c==null) {
                val target=originals[index].value.filter(Char::isLetterOrDigit).lowercase(Locale.ROOT)
                if(phonetic.startsWith(target,offset)) consider(offset+target.length,0)
            } else {
                val value=c.code-0xAC00;val initial=value/588;val medial=value/28%21;val coda=value%28
                val previousCoda=hangul(index-1)?.let { (it.code-0xAC00)%28 }
                val nextInitial=hangul(index+1)?.let { (it.code-0xAC00)/588 }
                val shifted=if(previousCoda!=null && initial in setOf(11,18)) liaison[previousCoda] else emptySet()
                val palatal=if(initial==11 && medial==20 && previousCoda in setOf(7,25)) setOf("j","ch","q") else emptySet()
                // Provider romanization may use q/x for ch/sh, and reflects
                // aspiration/liaison rather than the spelling's standalone onset.
                val providerShift=buildSet<String> {
                    if(medial==20 && shifted.any { it in setOf("s","ss","sh") }) add("x")
                    if(initial==12 && previousCoda in setOf(6,15,27)) add("q")
                    if(initial==18) when(previousCoda) {
                        7,19,20,22,23,25,27 -> add("t")
                        1,2,3,9,24 -> add("k")
                        11,14,17,18,26 -> add("p")
                    }
                }
                val allowedInitial=initials[initial]+shifted+palatal+providerShift
                for(end in offset+1..minOf(phonetic.length,offset+9)) {
                    val part=syllable.matchEntire(phonetic.substring(offset,end)) ?: continue
                    if(part.groupValues[1] !in allowedInitial || part.groupValues[2] !in vowels[medial]) continue
                    val final=part.groupValues[3]
                    val liaisonEmpty=final.isEmpty() && coda!=0 && nextInitial in setOf(11,18)
                    if(final !in codas[coda] && !liaisonEmpty) continue
                    // Keep explicit provider boundaries; otherwise prefer the actual liaison split.
                    val score=(if(separated(end)) 100 else 0)+(if(liaisonEmpty) 2 else 0)+
                        (if(part.groupValues[1] in shifted && part.groupValues[1] !in initials[initial]) 2 else 0)
                    consider(end,score)
                }
            }
            val result=best.takeUnless { ambiguous };memo[key]=result;return result
        }
        val parsed=solve(0,0) ?: return null
        if(budget<0) return null
        var offset=0
        return originals.mapIndexed { index,word ->
            val end=parsed.ends[index]
            val from=positions[offset];val to=if(end==positions.size) pronunciation.length else positions[end]
            offset=end
            val text=pronunciation.substring(from,to).trim { it.isWhitespace() || it in "-'’" }
            Unit(word.range.first,word.range.last+1,text)
        }
    }
}
