package com.tcrrry.lyrics.engine.lyrics

/** A stable sing-along spelling, not standard Jyutping or a phonetic transcription.
 * Dictionary keys, phrase selection and provider readings keep their original spelling.
 */
internal object CantoneseReadingDisplay {
    fun convert(jyutping:String):String {
        var value=jyutping.trimEnd { it in '1'..'6' }
        // QQ-style readable display: bat jiu -> ba yiu; gaa cit ngo -> ga ci o.
        value=value.replace("aa","a")
        if(value.startsWith("j")) value="y"+value.drop(1)
        if(value.startsWith("ng") && value.length>2) value=value.drop(2)
        if(value.lastOrNull() in listOf('p','t','k')) value=value.dropLast(1)
        return value
    }
}
