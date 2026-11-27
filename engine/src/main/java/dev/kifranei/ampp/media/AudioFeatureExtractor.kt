package dev.kifranei.ampp.media

import kotlin.math.*

/**
 * 本地音频特征提取器：从 PCM 数据计算 BPM、调性、能量、舞曲性。
 *
 * 当 Apple Music 的 AudioAnalysis 不可用时（LibraryMediaItem 常返回 null），
 * 用本地算法兜底，确保 AI DJ 过渡算法始终有数据驱动。
 *
 * 算法：
 *  - BPM：能量通量（energy flux）onset 检测 + 自相关 tempo 估计，范围 60-200
 *  - 调性：FFT → 12 维 chroma → Krumhansl-Schmuckler 模板匹配（24 调性）
 *  - 能量：RMS 归一化到 0-1
 *  - 舞曲性：频谱平坦度（spectral flatness），0-1，越高越像舞曲/噪声
 */
internal object AudioFeatureExtractor {

    private const val FRAME_SIZE = 2048
    private const val HOP_SIZE = 1024
    private const val MIN_BPM = 60f
    private const val MAX_BPM = 200f

    /** Krumhansl-Schmuckler 大调模板（C 为根音，顺时针半音） */
    private val MAJOR_PROFILE = floatArrayOf(
        6.35f, 2.23f, 3.48f, 2.33f, 4.38f, 4.09f,
        2.52f, 5.19f, 2.39f, 3.66f, 2.29f, 2.88f
    )
    /** Krumhansl-Schmuckler 小调模板 */
    private val MINOR_PROFILE = floatArrayOf(
        6.33f, 2.68f, 3.52f, 5.38f, 2.60f, 3.53f,
        2.54f, 4.75f, 3.98f, 2.69f, 3.34f, 3.17f
    )
    private val NOTE_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    /**
     * 从 16-bit PCM 提取音频特征。
     * @param pcm 16-bit 单声道/立体声 PCM（立体声会自动平均为单声道）
     * @param sampleRate 采样率
     * @param channels 声道数
     */
    fun extract(pcm: ShortArray, sampleRate: Int, channels: Int = 2): SongAudioAnalysis {
        if (pcm.isEmpty() || sampleRate <= 0) {
            return SongAudioAnalysis(null, null, 0.5f, 0.5f, null)
        }
        // 立体声 → 单声道（平均）
        val mono = if (channels >= 2) {
            val n = pcm.size / channels
            ShortArray(n) { i ->
                var sum = 0
                for (c in 0 until channels) sum += pcm[i * channels + c]
                (sum / channels).toShort()
            }
        } else pcm

        val numFrames = maxOf(1, (mono.size - FRAME_SIZE) / HOP_SIZE + 1)
        if (numFrames < 4) {
            val rms = computeRms(mono)
            return SongAudioAnalysis(null, null, rms, 0.5f, null)
        }

        // 每帧：能量、chroma、频谱平坦度
        val energies = FloatArray(numFrames)
        val chromaAccum = FloatArray(12)
        var flatnessSum = 0f
        var flatnessCount = 0

        val re = FloatArray(FRAME_SIZE)
        val im = FloatArray(FRAME_SIZE)

        for (f in 0 until numFrames) {
            val offset = f * HOP_SIZE
            // 加窗（Hann）+ 填充 FFT 输入
            for (i in 0 until FRAME_SIZE) {
                val idx = offset + i
                val sample = if (idx < mono.size) mono[idx] / 32768f else 0f
                val w = 0.5f * (1f - cos(2f * PI.toFloat() * i / FRAME_SIZE))
                re[i] = sample * w
                im[i] = 0f
            }
            fft(re, im)

            // 频谱幅度（只取前半）
            val mag = FloatArray(FRAME_SIZE / 2) { i ->
                sqrt(re[i] * re[i] + im[i] * im[i])
            }

            // 帧能量
            var e = 0f
            for (i in 0 until FRAME_SIZE) {
                val idx = offset + i
                if (idx < mono.size) {
                    val s = mono[idx] / 32768f
                    e += s * s
                }
            }
            energies[f] = e / FRAME_SIZE

            // chroma
            val frameChroma = computeChroma(mag, sampleRate)
            for (i in 0 until 12) chromaAccum[i] += frameChroma[i]

            // 频谱平坦度
            val flat = spectralFlatness(mag)
            if (flat > 0f) {
                flatnessSum += flat
                flatnessCount++
            }
        }

        // BPM：能量通量 + 自相关
        val bpm = detectBpm(energies, sampleRate)

        // 节拍网格：从能量通量提取 onset 峰值，转换为时间点（秒）
        val beats = detectBeats(energies, sampleRate)

        // 调性：chroma 归一化 + 模板匹配
        val key = detectKey(chromaAccum)

        // 能量：整体 RMS
        val energy = computeRms(mono)

        // 舞曲性：频谱平坦度平均
        val danceability = if (flatnessCount > 0) flatnessSum / flatnessCount else 0.5f

        // 首尾静音检测：基于帧能量，低于 -45dB 的连续段
        val frameDuration = HOP_SIZE.toFloat() / sampleRate
        val silenceThreshold = computeSilenceThreshold(energies)
        val (startSilenceFrames, endSilenceFrames) = detectSilence(energies, silenceThreshold)
        val startSilenceSec = startSilenceFrames * frameDuration
        val endSilenceSec = endSilenceFrames * frameDuration

        // Intro 检测：能量首次显著上升的点（从开头静音/低能量到主歌/副歌的过渡）
        val introEndSec = detectIntroEnd(energies, frameDuration, startSilenceFrames)

        return SongAudioAnalysis(
            bpm = bpm,
            key = key,
            energy = energy,
            danceability = danceability,
            acousticness = null,
            beats = beats,
            startSilenceSec = startSilenceSec,
            endSilenceSec = endSilenceSec,
            introEndSec = introEndSec,
        )
    }

    /** 计算静音阈值：帧能量中位数的 1/100（约 -40dB），最低兜底。 */
    private fun computeSilenceThreshold(energies: FloatArray): Float {
        if (energies.isEmpty()) return 1e-6f
        val sorted = energies.copyOf().apply { sort() }
        val median = sorted[sorted.size / 2]
        return maxOf(median * 0.01f, 1e-7f)
    }

    /**
     * 检测首尾静音帧数。
     * 从开头/结尾向中间扫描，连续低于阈值的帧数即为静音长度。
     */
    private fun detectSilence(energies: FloatArray, threshold: Float): Pair<Int, Int> {
        var startSilence = 0
        for (i in energies.indices) {
            if (energies[i] < threshold) startSilence++ else break
        }
        var endSilence = 0
        for (i in energies.indices.reversed()) {
            if (energies[i] < threshold) endSilence++ else break
        }
        // 限制：静音不超过总长的 30%（避免全静音歌曲误判）
        val maxSilence = (energies.size * 0.3f).toInt()
        return minOf(startSilence, maxSilence) to minOf(endSilence, maxSilence)
    }

    /**
     * Intro 结束位置检测：能量首次显著上升的点。
     * 从开头静音结束后开始扫描，找到能量超过整体中位数 1.5 倍的第一个稳定点。
     * 返回秒数，无明显 intro 时返回 0。
     */
    private fun detectIntroEnd(energies: FloatArray, frameDuration: Float, startSilenceFrames: Int): Float {
        if (energies.size < 10) return 0f
        val sorted = energies.copyOf().apply { sort() }
        val median = sorted[sorted.size / 2]
        val threshold = median * 1.5f
        // 从静音结束后开始，找连续 5 帧能量超过阈值的起点
        val searchStart = minOf(startSilenceFrames + 1, energies.size - 5)
        for (i in searchStart until energies.size - 5) {
            var allAbove = true
            for (j in 0 until 5) {
                if (energies[i + j] < threshold) {
                    allAbove = false
                    break
                }
            }
            if (allAbove) {
                val sec = i * frameDuration
                // intro 至少 2 秒才有意义，最多 60 秒
                if (sec in 2f..60f) return sec
                return 0f
            }
        }
        return 0f
    }

    // ---- BPM 检测 ----

    private fun detectBpm(energies: FloatArray, sampleRate: Int): Float? {
        if (energies.size < 8) return null
        // 能量通量：相邻帧能量差（正值）
        val flux = FloatArray(energies.size - 1) { i ->
            max(0f, energies[i + 1] - energies[i])
        }
        if (flux.size < 4) return null
        // 去均值
        val mean = flux.average().toFloat()
        val centered = FloatArray(flux.size) { flux[it] - mean }

        // 自相关，lag 对应 BPM 范围
        val hopSec = HOP_SIZE.toFloat() / sampleRate
        val minLag = (60f / MAX_BPM / hopSec).toInt().coerceAtLeast(1)
        val maxLag = (60f / MIN_BPM / hopSec).toInt().coerceAtMost(centered.size - 1)

        var bestLag = -1
        var bestCorr = 0f
        for (lag in minLag..maxLag) {
            var corr = 0f
            val n = centered.size - lag
            for (i in 0 until n) {
                corr += centered[i] * centered[i + lag]
            }
            corr /= n
            if (corr > bestCorr) {
                bestCorr = corr
                bestLag = lag
            }
        }
        if (bestLag < 0 || bestCorr <= 0f) return null
        val bpm = 60f / (bestLag * hopSec)
        // 倍频/半频修正：如果 BPM < 90，尝试乘 2；如果 > 160，尝试除 2
        return when {
            bpm < 70 -> (bpm * 2).coerceIn(MIN_BPM, MAX_BPM)
            bpm > 170 -> (bpm / 2).coerceIn(MIN_BPM, MAX_BPM)
            else -> bpm.coerceIn(MIN_BPM, MAX_BPM)
        }
    }

    // ---- 节拍网格检测 ----

    /**
     * 从帧能量序列提取 onset 峰值，返回节拍时间点（秒）。
     * 算法：能量通量 → 自适应阈值（均值+1.5σ）→ 局部最大值 →  Refractory 期去抖（最小间隔 0.2s）。
     */
    private fun detectBeats(energies: FloatArray, sampleRate: Int): FloatArray? {
        if (energies.size < 8) return null
        // 能量通量
        val flux = FloatArray(energies.size - 1) { i -> max(0f, energies[i + 1] - energies[i]) }
        if (flux.size < 4) return null
        // 自适应阈值：均值 + 1.5 倍标准差
        val mean = flux.average().toFloat()
        var variance = 0.0
        for (f in flux) variance += (f - mean) * (f - mean)
        val std = sqrt(variance / flux.size).toFloat()
        val threshold = mean + 1.5f * std
        // 局部最大值 + Refractory 去抖
        val hopSec = HOP_SIZE.toFloat() / sampleRate
        val minIntervalFrames = (0.2f / hopSec).toInt().coerceAtLeast(1)  // 最小间隔 200ms
        val beatFrames = mutableListOf<Int>()
        var lastBeatFrame = -minIntervalFrames
        for (i in 1 until flux.size - 1) {
            if (flux[i] > threshold && flux[i] >= flux[i - 1] && flux[i] >= flux[i + 1]) {
                if (i - lastBeatFrame >= minIntervalFrames) {
                    beatFrames.add(i)
                    lastBeatFrame = i
                }
            }
        }
        if (beatFrames.isEmpty()) return null
        return FloatArray(beatFrames.size) { beatFrames[it] * hopSec }
    }

    // ---- 调性检测 ----

    private fun computeChroma(mag: FloatArray, sampleRate: Int): FloatArray {
        val chroma = FloatArray(12)
        val nyquist = sampleRate / 2f
        val binHz = nyquist / mag.size
        for (i in 1 until mag.size) {
            val freq = i * binHz
            if (freq < 20f || freq > 8000f) continue
            // MIDI 音符号 → 半音索引
            val midi = 69 + 12 * log2(freq / 440f)
            val pitchClass = (midi.roundToInt() % 12 + 12) % 12
            chroma[pitchClass] += mag[i]
        }
        return chroma
    }

    private fun detectKey(chroma: FloatArray): String? {
        val sum = chroma.sum()
        if (sum <= 0f) return null
        val norm = chroma.map { it / sum }.toFloatArray()

        var bestScore = -1f
        var bestIdx = 0
        var bestIsMajor = true

        for (root in 0 until 12) {
            // 大调：从 root 开始旋转模板
            val majorScore = pearson(norm, rotate(MAJOR_PROFILE, root))
            if (majorScore > bestScore) {
                bestScore = majorScore
                bestIdx = root
                bestIsMajor = true
            }
            // 小调
            val minorScore = pearson(norm, rotate(MINOR_PROFILE, root))
            if (minorScore > bestScore) {
                bestScore = minorScore
                bestIdx = root
                bestIsMajor = false
            }
        }
        if (bestScore <= 0f) return null
        return "${NOTE_NAMES[bestIdx]} ${if (bestIsMajor) "major" else "minor"}"
    }

    private fun rotate(arr: FloatArray, shift: Int): FloatArray {
        val n = arr.size
        val s = (shift % n + n) % n
        return FloatArray(n) { arr[(it - s + n) % n] }
    }

    private fun pearson(a: FloatArray, b: FloatArray): Float {
        val n = a.size
        var meanA = 0f; var meanB = 0f
        for (i in 0 until n) { meanA += a[i]; meanB += b[i] }
        meanA /= n; meanB /= n
        var num = 0f; var denA = 0f; var denB = 0f
        for (i in 0 until n) {
            val da = a[i] - meanA
            val db = b[i] - meanB
            num += da * db
            denA += da * da
            denB += db * db
        }
        val den = sqrt(denA * denB)
        return if (den > 0f) num / den else 0f
    }

    // ---- 能量 & 舞曲性 ----

    private fun computeRms(mono: ShortArray): Float {
        if (mono.isEmpty()) return 0.5f
        var sum = 0.0
        for (s in mono) {
            val v = s / 32768.0
            sum += v * v
        }
        val rms = sqrt(sum / mono.size).toFloat()
        // 归一化：典型音乐 RMS 0.05-0.3，映射到 0-1
        return (rms / 0.3f).coerceIn(0f, 1f)
    }

    private fun spectralFlatness(mag: FloatArray): Float {
        var logSum = 0.0
        var linSum = 0f
        var count = 0
        val eps = 1e-10f
        for (m in mag) {
            if (m > eps) {
                logSum += ln(m.toDouble())
                linSum += m
                count++
            }
        }
        if (count == 0 || linSum <= 0f) return 0f
        val geoMean = exp(logSum / count).toFloat()
        val arithMean = linSum / count
        return (geoMean / arithMean).coerceIn(0f, 1f)
    }

    // ---- FFT（Cooley-Tukey radix-2，迭代版）----

    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        // 位反转置换
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                re[i] = re[j].also { re[j] = re[i] }
                im[i] = im[j].also { im[j] = im[i] }
            }
        }
        // 蝶形运算
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wRe = cos(ang).toFloat()
            val wIm = sin(ang).toFloat()
            for (i in 0 until n step len) {
                var curRe = 1f
                var curIm = 0f
                for (k in 0 until len / 2) {
                    val uRe = re[i + k]
                    val uIm = im[i + k]
                    val vRe = re[i + k + len / 2] * curRe - im[i + k + len / 2] * curIm
                    val vIm = re[i + k + len / 2] * curIm + im[i + k + len / 2] * curRe
                    re[i + k] = uRe + vRe
                    im[i + k] = uIm + vIm
                    re[i + k + len / 2] = uRe - vRe
                    im[i + k + len / 2] = uIm - vIm
                    val nextRe = curRe * wRe - curIm * wIm
                    val nextIm = curRe * wIm + curIm * wRe
                    curRe = nextRe
                    curIm = nextIm
                }
            }
            len = len shl 1
        }
    }
}
