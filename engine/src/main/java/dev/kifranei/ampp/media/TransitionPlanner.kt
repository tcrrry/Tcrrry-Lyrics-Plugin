package dev.kifranei.ampp.media

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * AI DJ 过渡规划器——模拟 Apple Music AutoMix 的核心管线：
 *
 *   1. 调性兼容性先行判断（Camelot 轮连续评分 0~1），兼容性 < 0.35 时退化为简单交叉淡化
 *   2. 动态过渡时长决策：基于调性兼容性 + BPM 差距，兼容性高→长（最多 40s），低→短（最少 4s）
 *   3. 节拍网格对齐：在"结尾前 crossfade_sec 附近"找到最接近的强拍点，淡化在节拍上开始/结束
 *   4. BPM 对拍（时间拉伸）：±10% 内且非低能量时启用（默认关闭，防杂音）
 *   5. 能量驱动曲线：高能量用陡曲线，低能量用缓曲线
 *
 * 与传统固定时长 crossfade 的区别：淡化时长随每首歌的调性/BPM/能量动态变化，
 * 且对齐到节拍网格，听感像 DJ 现场混音而非机械淡入淡出。
 */
internal object TransitionPlanner {
    private var lastEntryLog = ""

    /** 4/4 拍下一个乐句的拍数（4 小节 × 4 拍）。淡化时长对齐到该值的整数倍。 */
    private const val BEATS_PER_PHRASE = 16

    /** 调性兼容性阈值：低于此值退化为简单交叉淡化（不做变速、短时长、线性曲线）。 */
    private const val DEGRADE_THRESHOLD = 0.35f

    /** 过渡时长范围（秒）：用户要求上限 40s。 */
    private const val MIN_DURATION = 2
    private const val MAX_DURATION = 20
    private const val MIN_AUTO_DURATION = 4
    private const val MAX_AUTO_DURATION = 10

    /** Camelot Wheel：音乐调性 → 编号+大小调。常见调性映射，未覆盖的返回 null。 */
    private val CAMELOT = mapOf(
        "C major" to "8B", "G major" to "9B", "D major" to "10B", "A major" to "11B",
        "E major" to "12B", "B major" to "1B", "F# major" to "2B", "Db major" to "3B",
        "Ab major" to "4B", "Eb major" to "5B", "Bb major" to "6B", "F major" to "7B",
        "A minor" to "8A", "E minor" to "9A", "B minor" to "10A", "F# minor" to "11A",
        "C# minor" to "12A", "G# minor" to "1A", "D# minor" to "2A", "Bb minor" to "3A",
        "F minor" to "4A", "C minor" to "5A", "G minor" to "6A", "D minor" to "7A",
    )

    fun plan(current: SongAudioAnalysis?, next: SongAudioAnalysis?, requestedDurationSec: Int): TransitionPlan {
        val isAutoDuration = requestedDurationSec <= 0
        val defaultDurationSec = if (isAutoDuration) 6 else requestedDurationSec.coerceIn(MIN_DURATION, MAX_DURATION)
        if (current == null || !current.usable) {
            return TransitionPlan(enabled = isAutoDuration, durationSec = defaultDurationSec, speedRatio = 1f,
                curve = FadeCurve.GENTLE, reason = "音频分析数据不足，采用${defaultDurationSec}s平滑淡化")
        }
        val eC = (current.energy ?: 0.5f).coerceIn(0f, 1f)
        val dC = (current.danceability ?: 0.3f).coerceIn(0f, 1f)
        val bpmC = current.bpm?.takeIf { it > 0f } ?: 120f

        // next 没有可用数据：按当前曲的“性格”选温和重叠时长，避免生硬或裁切过多。
        if (next == null || !next.usable) {
            val mode = when {
                eC > 0.62f && dC > 0.45f -> TransitionMode.BASS_SWAP
                eC < 0.30f -> TransitionMode.FILTER_MIX
                dC > 0.55f -> TransitionMode.HARMONIC_BLEND
                else -> TransitionMode.FILTER_MIX
            }
            val mixability = (0.35f + 0.35f * eC + 0.30f * dC).coerceIn(0f, 1f)
            val targetBpm = bpmC
            val phraseMs = phraseMsFrom(current)
            val baseSec = when (mode) {
                TransitionMode.HARMONIC_BLEND -> 7
                TransitionMode.BASS_SWAP -> 6
                TransitionMode.FILTER_MIX -> 5
                TransitionMode.QUICK_CUT -> 4
            }
            val tailBonus = minOf(4, current.endSilenceSec.toInt().coerceAtLeast(0))
            val duration = if (isAutoDuration) {
                (baseSec + tailBonus).coerceIn(MIN_AUTO_DURATION, MAX_AUTO_DURATION)
            } else {
                defaultDurationSec
            }
            val phrases = (duration * 1000f / phraseMs).roundToInt().coerceAtLeast(1)
            val introSkip = (duration * 0.22f).coerceIn(3f, 6f)
            val eqDepth = when (mode) {
                TransitionMode.HARMONIC_BLEND -> 4f
                TransitionMode.BASS_SWAP -> 9f
                TransitionMode.FILTER_MIX -> 11f
                TransitionMode.QUICK_CUT -> 0f
            }
            val curve = when (mode) {
                TransitionMode.HARMONIC_BLEND -> FadeCurve.GENTLE
                TransitionMode.BASS_SWAP -> FadeCurve.STEEP
                TransitionMode.FILTER_MIX -> FadeCurve.STEEP
                TransitionMode.QUICK_CUT -> FadeCurve.LINEAR
            }
            return TransitionPlan(
                enabled = true, durationSec = duration, speedRatio = 1f, needsSpeedMatch = false,
                curve = curve, keyCompatibility = KeyCompatibility.FAIR, currentBpm = current.bpm,
                nextBpm = null, phraseCount = phrases, phraseMs = phraseMs, keyCompatScore = 0.5f,
                mode = mode, mixability = mixability, targetBpm = targetBpm,
                eqDepthDb = eqDepth, introSkipSec = introSkip,
                reason = "单曲模式 ${mode.name}，mixability=${"%.2f".format(mixability)} " +
                    "BPM=${bpmC.roundToInt()} 能量=${"%.2f".format(eC)} 舞曲=${"%.2f".format(dC)} " +
                    "→ ${duration}s/${phrases}乐句，EQ ${eqDepth}dB，跳前奏${"%.1f".format(introSkip)}s，曲线${curve.name}",
            )
        }

        // 两首歌都有数据：按“这一对歌”的 mixability 选过渡模式、时长、EQ 和入口。
        val keyScore = camelotCompatibilityScore(current.key, next.key)
        val keyCompat = keyCompatibilityFromScore(keyScore)
        val eN = (next.energy ?: 0.5f).coerceIn(0f, 1f)
        val dN = (next.danceability ?: 0.3f).coerceIn(0f, 1f)
        val hasBothBpm = current.hasBpm && next.hasBpm
        val ratio = if (hasBothBpm) (next.bpm!! / current.bpm!!) else 1f
        val bpmScore = if (hasBothBpm && ratio > 0f) {
            (1f - abs(kotlin.math.ln(ratio)) / 0.18f).coerceIn(0f, 1f)
        } else 0.4f
        val energyScore = (1f - abs(eC - eN)).coerceIn(0f, 1f)
        val danceScore = ((dC + dN) / 2f).coerceIn(0f, 1f)
        val mixability = (0.40f * keyScore + 0.30f * bpmScore + 0.20f * energyScore + 0.10f * danceScore)
            .coerceIn(0f, 1f)

        val mode = when {
            mixability >= 0.72f && eC > 0.35f -> TransitionMode.HARMONIC_BLEND
            mixability >= 0.52f && (eC > 0.45f || eN > 0.45f) -> TransitionMode.BASS_SWAP
            mixability >= 0.32f -> TransitionMode.FILTER_MIX
            else -> TransitionMode.QUICK_CUT
        }
        val targetBpm = if (hasBothBpm) (current.bpm!! + next.bpm!!) / 2f else (current.bpm ?: 120f)
        val phraseMs = 16f * 60000f / targetBpm.coerceIn(60f, 200f)
        val baseSec = when (mode) {
            TransitionMode.HARMONIC_BLEND -> 8f
            TransitionMode.BASS_SWAP -> 7f
            TransitionMode.FILTER_MIX -> 5f
            TransitionMode.QUICK_CUT -> 4f
        }
        val tailBonus = minOf(3f, current.endSilenceSec.coerceAtLeast(0f))
        val phrases = (baseSec * 1000f / phraseMs).roundToInt().coerceAtLeast(1)
        val duration = if (isAutoDuration) {
            (baseSec + tailBonus).roundToInt().coerceIn(MIN_AUTO_DURATION, MAX_AUTO_DURATION)
        } else {
            defaultDurationSec
        }

        val lowEnergy = eC < 0.3f || eN < 0.3f
        val needsSpeedMatch = hasBothBpm && !lowEnergy && abs(ratio - 1f) > 0.04f &&
            ratio in 0.9f..1.1f && mixability > 0.45f
        val speedRatio = if (needsSpeedMatch) ratio else 1f

        val curve = when (mode) {
            TransitionMode.HARMONIC_BLEND -> FadeCurve.GENTLE
            TransitionMode.BASS_SWAP -> FadeCurve.STEEP
            TransitionMode.FILTER_MIX -> FadeCurve.STEEP
            TransitionMode.QUICK_CUT -> FadeCurve.LINEAR
        }
        val eqDepth = when (mode) {
            TransitionMode.HARMONIC_BLEND -> 4f
            TransitionMode.BASS_SWAP -> 9f
            TransitionMode.FILTER_MIX -> 12f
            TransitionMode.QUICK_CUT -> 0f
        }
        val analyzedSkip = maxOf(next.startSilenceSec, next.introEndSec)
        val appleIntroSec = (next.introFadeStartMs ?: 0f) / 1000f
        // 用下一首的小节线选 musical entry：不同 BPM/模式落在不同小节上。
        val barEntry = chooseBarEntry(next, mode)
        val entrySource = when {
            appleIntroSec > 0.5f -> "apple-fadeIn"
            barEntry > 0f -> "bar"
            analyzedSkip > 1f -> "silence/intro"
            else -> "fallback-mode"
        }
        val introSkip = when (entrySource) {
            "apple-fadeIn" -> appleIntroSec
            "bar" -> barEntry
            "silence/intro" -> analyzedSkip
            else -> when (mode) {
                TransitionMode.HARMONIC_BLEND -> 4f
                TransitionMode.BASS_SWAP -> 6f
                TransitionMode.FILTER_MIX -> 8f
                TransitionMode.QUICK_CUT -> 3f
            }
        }
        val introSkipClamped = introSkip.coerceIn(1f, minOf(15f, duration * 0.55f))
        val entryLog = "${next.barTimesSec?.size ?: 0}|${mode.name}|${introSkipClamped}|$entrySource"
        if (entryLog != lastEntryLog) {
            lastEntryLog = entryLog
            FileLogger.log(
                "entry: bars=${next.barTimesSec?.size ?: 0} mode=${mode.name} " +
                    "target=${"%.1f".format(entryTarget(mode))} chosen=${"%.2f".format(introSkipClamped)}s " +
                    "source=$entrySource",
            )
        }
        val mixEntryBeat = findNearestBeat(current.beats, duration)

        val energyDrop = eC - eN
        val outgoingDuck = if (eC > 0.62f && eN < 0.50f && energyDrop > 0.22f) 1.8f else 1f
        val bpmNote = when {
            !hasBothBpm -> "BPM数据不全"
            needsSpeedMatch -> "变速匹配 %.2f".format(ratio)
            lowEnergy -> "低能量跳过变速"
            else -> "无需变速"
        }
        return TransitionPlan(
            enabled = true, durationSec = duration, speedRatio = speedRatio,
            needsSpeedMatch = needsSpeedMatch, curve = curve, keyCompatibility = keyCompat,
            currentBpm = current.bpm, nextBpm = next.bpm, phraseCount = phrases, phraseMs = phraseMs,
            keyCompatScore = keyScore, mode = mode, mixability = mixability, targetBpm = targetBpm,
            eqDepthDb = eqDepth, introSkipSec = introSkipClamped, mixEntryBeatSec = mixEntryBeat,
            reason = "组合 ${mode.name}，mixability=${"%.2f".format(mixability)} " +
                "(key=${"%.2f".format(keyScore)} bpm=${"%.2f".format(bpmScore)} energy=${"%.2f".format(energyScore)})，" +
                "BPM ${current.bpm?.roundToInt() ?: "?"}→${next.bpm?.roundToInt() ?: "?"}($bpmNote)，" +
                "→ ${duration}s/${phrases}乐句@${"%.1f".format(phraseMs / 1000f)}s，EQ ${eqDepth}dB，" +
                "跳前奏${"%.1f".format(introSkipClamped)}s，曲线${curve.name}，outDuck=${"%.1f".format(outgoingDuck)}",
            outroFadeStartMs = current.outroFadeStartMs,
            introFadeStartMs = next.introFadeStartMs,
            outgoingDuck = outgoingDuck,
        )
    }

    /**
     * Camelot 轮调性兼容性连续评分（0~1）。
     * 规则（参考 PyCamelot）：
     *   - 同调（8A → 8A）：1.0
     *   - 同字母 ±1（8A → 7A / 9A，环形）：0.85
     *   - 大小调互换（8A → 8B）：0.7
     *   - 同字母距离 2：0.55
     *   - 同字母距离 3：0.4
     *   - 其他（跨字母且非互换）：0.2~0.3
     */
    private fun entryTarget(mode: TransitionMode): Float = when (mode) {
        TransitionMode.HARMONIC_BLEND -> 12f
        TransitionMode.BASS_SWAP -> 10f
        TransitionMode.FILTER_MIX -> 6f
        TransitionMode.QUICK_CUT -> 4f
    }

    /** 从下一首的小节线里选一个靠近目标秒数的强小节，作为 incoming entry。 */
    private fun chooseBarEntry(song: SongAudioAnalysis, mode: TransitionMode): Float {
        val bars = song.barTimesSec ?: return 0f
        if (bars.size < 4) return 0f
        val target = entryTarget(mode)
        var best = -1f
        var bestDiff = Float.MAX_VALUE
        for (b in bars) {
            if (b < 1.5f) continue
            val diff = abs(b - target)
            if (diff < bestDiff) { bestDiff = diff; best = b }
        }
        return best.coerceIn(0f, 15f)
    }

    fun camelotCompatibilityScore(keyA: String?, keyB: String?): Float {
        val a = toCamelot(keyA) ?: return 0.5f
        val b = toCamelot(keyB) ?: return 0.5f
        if (a == b) return 1.0f
        val (numA, modeA) = a
        val (numB, modeB) = b
        // 环形距离（12 进制）
        val rawDiff = abs(numA - numB)
        val ringDiff = minOf(rawDiff, 12 - rawDiff)

        return when {
            // 大小调互换（同编号 A↔B）
            numA == numB && modeA != modeB -> 0.7f
            // 同字母相邻
            modeA == modeB && ringDiff == 1 -> 0.85f
            // 同字母距离 2
            modeA == modeB && ringDiff == 2 -> 0.55f
            // 同字母距离 3
            modeA == modeB && ringDiff == 3 -> 0.4f
            // 同字母更远
            modeA == modeB -> maxOf(0.2f, 1.0f - ringDiff * 0.15f)
            // 跨字母且非互换：按距离衰减
            else -> maxOf(0.2f, 0.5f - ringDiff * 0.1f)
        }
    }

    /** 从连续评分映射到离散兼容等级（用于日志/UI）。 */
    private fun keyCompatibilityFromScore(score: Float): KeyCompatibility = when {
        score >= 0.85f -> KeyCompatibility.PERFECT
        score >= 0.6f -> KeyCompatibility.GOOD
        score >= 0.4f -> KeyCompatibility.FAIR
        else -> KeyCompatibility.POOR
    }

    /**
     * 动态过渡时长决策：基于调性兼容性 + BPM 差距。
     * 兼容性高、BPM 接近 → 长过渡（最多 40s）；
     * 兼容性低、BPM 差距大 → 短过渡（最少 4s）。
     *
     * 公式：duration = base * (0.5 + compat * (1 - 0.5 * bpmPenalty))
     *   base = 20s（基准）
     *   bpmPenalty = min(1.0, bpmDiff / 30)（差距 30 BPM 时惩罚满额）
     */
    private fun decideDuration(compat: Float, bpmDiff: Float): Int {
        val base = 20f
        val bpmPenalty = minOf(1.0f, bpmDiff / 30f)
        val factor = compat * (1.0f - 0.5f * bpmPenalty)
        val duration = base * (0.5f + factor)
        return duration.roundToInt().coerceIn(MIN_DURATION, MAX_DURATION)
    }

    /**
     * 节拍网格对齐：在 beats 数组中找到最接近 targetSec 的节拍点。
     * 用于让 crossfade 在强拍上开始，而不是任意时间点。
     * 返回该节拍的时间（秒），无节拍数据时返回 null。
     */
    private fun findNearestBeat(beats: FloatArray?, targetSec: Int): Float? {
        if (beats == null || beats.isEmpty()) return null
        // 目标：crossfade 开始时间（歌曲末尾前 targetSec 处）的最近节拍
        // 由于我们不知道歌曲总时长，这里返回 beats 中最接近 targetSec 的节拍
        // （实际 crossfade 开始时间由引擎控制，这里仅作参考对齐）
        var best = beats[0]
        var bestDiff = abs(beats[0] - targetSec)
        for (b in beats) {
            val diff = abs(b - targetSec)
            if (diff < bestDiff) {
                bestDiff = diff
                best = b
            }
        }
        return best
    }

    /** 从 beats 或 BPM 计算乐句时长（毫秒）。优先用实际节拍间隔的中位数。 */
    private fun phraseMsFrom(song: SongAudioAnalysis): Float {
        val beats = song.beats
        if (beats != null && beats.size >= 4) {
            // 计算相邻节拍间隔，取中位数（抗异常值）
            val intervals = FloatArray(beats.size - 1) { beats[it + 1] - beats[it] }
            intervals.sort()
            // 过滤异常间隔（<0.2s 或 >2s 视为噪声）
            val valid = intervals.filter { it in 0.2f..2f }
            if (valid.size >= 4) {
                val avgInterval = valid.average().toFloat()
                return BEATS_PER_PHRASE * avgInterval * 1000f
            }
        }
        // fallback：用 BPM 估算
        val bpm = (song.bpm ?: 120f).coerceIn(60f, 200f)
        return BEATS_PER_PHRASE * (60000f / bpm)
    }

    /** Camelot 调性兼容性（离散等级，保留用于向后兼容）。 */
    fun keyCompatibility(keyA: String?, keyB: String?): KeyCompatibility {
        return keyCompatibilityFromScore(camelotCompatibilityScore(keyA, keyB))
    }

    private fun toCamelot(key: String?): Pair<Int, Char>? {
        if (key.isNullOrBlank()) return null
        val normalized = key.trim().lowercase()
        val camelot = CAMELOT.entries.firstOrNull { it.key.lowercase() == normalized }?.value
            ?: CAMELOT.entries.firstOrNull { normalized.contains(it.key.lowercase()) }?.value
            ?: return null
        val num = camelot.dropLast(1).toIntOrNull() ?: return null
        return num to camelot.last()
    }

    private fun keyCompatibilityLabel(c: KeyCompatibility) = when (c) {
        KeyCompatibility.PERFECT -> "完美兼容"
        KeyCompatibility.GOOD -> "良好兼容"
        KeyCompatibility.FAIR -> "一般"
        KeyCompatibility.POOR -> "不兼容"
    }
}

internal enum class KeyCompatibility { PERFECT, GOOD, FAIR, POOR }
internal enum class FadeCurve { LINEAR, STEEP, GENTLE }

internal enum class TransitionMode { HARMONIC_BLEND, BASS_SWAP, FILTER_MIX, QUICK_CUT }

internal data class TransitionPlan(
    val enabled: Boolean,
    val durationSec: Int,
    val speedRatio: Float = 1f,
    val needsSpeedMatch: Boolean = false,
    val curve: FadeCurve = FadeCurve.LINEAR,
    val keyCompatibility: KeyCompatibility = KeyCompatibility.FAIR,
    val currentBpm: Float? = null,
    val nextBpm: Float? = null,
    /** 淡化覆盖的乐句数（1 乐句 = 16 拍）。 */
    val phraseCount: Int = 0,
    /** 单个乐句的时长（毫秒），用于封面动画/辉光节奏对齐。 */
    val phraseMs: Float = 0f,
    /** 调性兼容性连续评分（0~1），来自 Camelot 轮。 */
    val keyCompatScore: Float = 0.5f,
    /** 是否退化为简单交叉淡化（调性兼容性过低时）。 */
    val degraded: Boolean = false,
    /** 节拍对齐的进入点（秒），无节拍数据时为 null。 */
    val mixEntryBeatSec: Float? = null,
    val reason: String = "",
    val mode: TransitionMode = TransitionMode.FILTER_MIX,
    val mixability: Float = 0.5f,
    val targetBpm: Float? = null,
    val eqDepthDb: Float = 6f,
    val introSkipSec: Float = 0f,
    /** Apple 分析给出的尾部淡出起点（毫秒），实际接歌点。 */
    val outroFadeStartMs: Float? = null,
    /** Apple 分析给出的头部淡入起点（毫秒），实际 entry 点。 */
    val introFadeStartMs: Float? = null,
    /** 高能量结尾接低能量开头时，outgoing 额外加速衰减的指数（1=普通）。 */
    val outgoingDuck: Float = 1f,
)

