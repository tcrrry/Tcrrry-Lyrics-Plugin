package dev.kifranei.ampp.media

import android.media.audiofx.Equalizer
import android.os.Handler
import android.os.Looper

/**
 * 不碰 PCM 的过渡音色处理：给全局输出挂 Equalizer，在 crossfade 期间做
 * 分频让位（低频先让、再恢复），并按过渡模式改变低/中/高比例。
 *
 * 注意：AudioEffect 作用于最终混音，无法区分 outgoing/incoming；
 * 这是频率域的近似，不是真正的 per-track bass swap。
 */
internal object AutoMixEqualizer {
    private var eq: Equalizer? = null
    private var animator: android.animation.ValueAnimator? = null
    private var bands: IntArray = IntArray(0)
    private var centers: IntArray = IntArray(0)
    private var originals: ShortArray = ShortArray(0)
    private var mode: String = "FILTER_MIX"
    private var lastError: String? = null

    fun start(durationMs: Long, depthDb: Float = 8f, transitionMode: String = "FILTER_MIX") {
        stop()
        runCatching {
            val e = Equalizer(0, 0)
            e.enabled = true
            eq = e
            mode = transitionMode
            val n = e.numberOfBands.toInt()
            val b = ArrayList<Int>()
            val c = ArrayList<Int>()
            for (i in 0 until n) {
                val range = e.getBandFreqRange(i.toShort())
                b.add(i)
                c.add((range[0] + range[1]) / 2)
            }
            bands = b.toIntArray()
            centers = c.toIntArray()
            originals = ShortArray(bands.size) { e.getBandLevel(bands[it].toShort()) }
            FileLogger.log("eq: start bands=${bands.size} duration=$durationMs depth=${depthDb}dB mode=$transitionMode")
            animator?.cancel()
            animator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                this.duration = durationMs.coerceAtLeast(4000L)
                addUpdateListener { a ->
                    val t = a.animatedValue as Float
                    val envelope = kotlin.math.sin(Math.PI * t).toFloat().coerceIn(0f, 1f)
                    for (i in bands.indices) {
                        val center = if (i < centers.size) centers[i] else 0
                        val factor = when (mode) {
                            "BASS_SWAP" -> when {
                                center < 250_000 -> 1.0f
                                center < 2_000_000 -> 0.25f
                                else -> -0.15f
                            }
                            "HARMONIC_BLEND" -> when {
                                center < 250_000 -> 0.5f
                                center < 2_000_000 -> 0.10f
                                else -> -0.10f
                            }
                            "QUICK_CUT" -> 0f
                            else -> when { // FILTER_MIX
                                center < 250_000 -> 1.0f
                                center < 2_000_000 -> 0.30f
                                else -> 0.35f
                            }
                        }
                        val target = (originals[i].toInt() - (depthDb * 100f * factor * envelope).toInt()).toShort()
                        runCatching { eq?.setBandLevel(bands[i].toShort(), target) }
                    }
                }
                start()
            }
        }.onFailure {
            lastError = it.message
            FileLogger.log("eq: start failed: ${it.message}")
        }
    }

    fun snapshot(): String = runCatching {
        val x = eq ?: return "inactive"
        val levels = (0 until bands.size).joinToString(",") { i -> "${bands[i]}:${runCatching { x.getBandLevel(bands[i].toShort()) }.getOrDefault(0)}" }
        "enabled=${x.enabled} bands=${bands.size} levels=$levels"
    }.getOrElse { "snapshot-error:${it.message}" }

    fun stop() {
        animator?.cancel()
        animator = null
        for (i in bands.indices) {
            runCatching { eq?.setBandLevel(bands[i].toShort(), originals[i]) }
        }
        runCatching { eq?.enabled = false; eq?.release() }
        eq = null
        bands = IntArray(0)
        centers = IntArray(0)
        originals = ShortArray(0)
        FileLogger.log("eq: stop")
    }
}

