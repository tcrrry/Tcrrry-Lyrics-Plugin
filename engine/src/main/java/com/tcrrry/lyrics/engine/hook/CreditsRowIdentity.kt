package com.tcrrry.lyrics.engine.hook

import android.view.View
import java.util.Collections
import java.util.WeakHashMap

/**
 * Process-local semantic identity for inflated lyric writers-credits rows.
 *
 * Credits rows are direct RecyclerView items that the generic lyrics-line heuristic rejects
 * (dynamic roots contain an ImageView; static roots are a custom TextView), so the blur runtime
 * classifies them through this identity instead. Deliberately independent from
 * [InstrumentalRowIdentity]: credits are never three-dot anchors.
 */
internal object CreditsRowIdentity {
    val layoutNames: List<String> = listOf(
        "lyrics_line_writers_credits",
        "lyrics_line_static_writers_credits",
    )

    private val rows = Collections.synchronizedMap(WeakHashMap<View, Boolean>())

    fun mark(view: View) {
        rows[view] = true
    }

    fun matches(view: View): Boolean = rows.containsKey(view)
}
