package dev.kifranei.ampp.media

import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/**
 * 低频交换（Bass Swap）实时 DSP 处理器。
 *
 * DJ 混音的经典手法：在 crossfade 期间，将 A 曲的低频逐渐退出、B 曲的低频逐渐引入，
 * 避免两首歌的低频叠加导致浑浊。
 *
 * 由于 Android 实时播放中 AudioTrack.write 收到的是混合后的音频（无法分离 A/B 两路），
 * 这里采用近似方案：crossfade 前半段整体低频衰减（模拟 A 低频退出），
 * 后半段低频恢复（模拟 B 低频进入），形成一个低频"呼吸"效果。
 *
 * 实现：一阶 IIR 低通滤波器提取低频成分，按 crossfade 进度调制低频增益。
 *   进度 0.0 → 0.5：低频增益 1.0 → 0.25（A 低频退出）
 *   进度 0.5 → 1.0：低频增益 0.25 → 1.0（B 低频进入）
 */
internal object BassSwapProcessor {

    /** 低通截止频率（Hz），低于此频率的成分视为低频。 */
    private const val CUTOFF_HZ = 200f

    /** 低频增益最低点（crossfade 中点）。 */
    private const val MIN_BASS_GAIN = 0.25f

    // 滤波器状态（跨 AudioTrack.write 调用保持）
    @Volatile private var lowpassStateL = 0f  // 左声道
    @Volatile private var lowpassStateR = 0f  // 右声道
    @Volatile private var lastSampleRate = 44100

    /** 重置滤波器状态（crossfade 开始时调用）。 */
    fun reset() {
        lowpassStateL = 0f
        lowpassStateR = 0f
    }

    /**
     * 对 AudioTrack.write 的 ByteBuffer 做低频交换处理。
     *
     * @param buf 原始 PCM ByteBuffer（会被原地修改）
     * @param sizeInBytes 有效数据字节数
     * @param sampleRate 采样率
     * @param channels 声道数（1=单声道，2=立体声）
     * @param isFloatPcm 是否为 float PCM（ENCODING_PCM_FLOAT）
     * @param crossfadeProgress crossfade 进度（0~1）
     */
    fun process(
        buf: ByteBuffer,
        sizeInBytes: Int,
        sampleRate: Int,
        channels: Int,
        isFloatPcm: Boolean,
        crossfadeProgress: Float
    ) {
        if (sizeInBytes <= 0 || !buf.hasRemaining()) return
        if (sampleRate != lastSampleRate) {
            lastSampleRate = sampleRate
            reset()
        }

        // 计算当前低频增益：V 形曲线（中点最低）
        val bassGain = bassGainAt(crossfadeProgress)
        if (bassGain >= 0.99f) return  // 增益接近 1，无需处理

        val alpha = lowpassAlpha(sampleRate)
        val originalPos = buf.position()

        if (isFloatPcm) {
            processFloat(buf, sizeInBytes, channels, alpha, bassGain)
        } else {
            processShort(buf, sizeInBytes, channels, alpha, bassGain)
        }

        buf.position(originalPos)  // 恢复 position，让引擎正常读取
    }

    /** crossfade 进度 → 低频增益（V 形曲线，中点最低）。 */
    private fun bassGainAt(progress: Float): Float {
        val p = progress.coerceIn(0f, 1f)
        // 0→0.5: 1.0→MIN；0.5→1.0: MIN→1.0
        return if (p <= 0.5f) {
            1f - (1f - MIN_BASS_GAIN) * (p / 0.5f)
        } else {
            MIN_BASS_GAIN + (1f - MIN_BASS_GAIN) * ((p - 0.5f) / 0.5f)
        }
    }

    /** 一阶低通滤波器系数 α = 2π·fc·dt。 */
    private fun lowpassAlpha(sampleRate: Int): Float {
        val dt = 1f / sampleRate
        return (2f * Math.PI.toFloat() * CUTOFF_HZ * dt).coerceIn(0f, 1f)
    }

    /** 处理 16-bit short PCM。 */
    private fun processShort(
        buf: ByteBuffer,
        sizeInBytes: Int,
        channels: Int,
        alpha: Float,
        bassGain: Float
    ) {
        val shortBuf = buf.asShortBuffer()
        val sampleCount = minOf(shortBuf.remaining(), sizeInBytes / 2)
        if (sampleCount <= 0) return

        val samples = ShortArray(sampleCount)
        shortBuf.get(samples)

        var stateL = lowpassStateL
        var stateR = lowpassStateR

        for (i in samples.indices step channels) {
            // 左声道
            val inputL = samples[i] / 32768f
            stateL = stateL + alpha * (inputL - stateL)
            val lowL = stateL
            val highL = inputL - lowL
            val outputL = (highL + lowL * bassGain) * 32767f
            samples[i] = outputL.coerceIn(-32768f, 32767f).toInt().toShort()

            // 右声道（如果是立体声）
            if (channels >= 2 && i + 1 < samples.size) {
                val inputR = samples[i + 1] / 32768f
                stateR = stateR + alpha * (inputR - stateR)
                val lowR = stateR
                val highR = inputR - lowR
                val outputR = (highR + lowR * bassGain) * 32767f
                samples[i + 1] = outputR.coerceIn(-32768f, 32767f).toInt().toShort()
            }
        }

        lowpassStateL = stateL
        lowpassStateR = stateR

        // 写回 ByteBuffer
        shortBuf.rewind()
        shortBuf.put(samples)
    }

    /** 处理 float PCM（ENCODING_PCM_FLOAT，范围 [-1, 1]）。 */
    private fun processFloat(
        buf: ByteBuffer,
        sizeInBytes: Int,
        channels: Int,
        alpha: Float,
        bassGain: Float
    ) {
        val floatBuf = buf.asFloatBuffer()
        val sampleCount = minOf(floatBuf.remaining(), sizeInBytes / 4)
        if (sampleCount <= 0) return

        val samples = FloatArray(sampleCount)
        floatBuf.get(samples)

        var stateL = lowpassStateL
        var stateR = lowpassStateR

        for (i in samples.indices step channels) {
            val inputL = samples[i]
            stateL = stateL + alpha * (inputL - stateL)
            val lowL = stateL
            val highL = inputL - lowL
            samples[i] = (highL + lowL * bassGain).coerceIn(-1f, 1f)

            if (channels >= 2 && i + 1 < samples.size) {
                val inputR = samples[i + 1]
                stateR = stateR + alpha * (inputR - stateR)
                val lowR = stateR
                val highR = inputR - lowR
                samples[i + 1] = (highR + lowR * bassGain).coerceIn(-1f, 1f)
            }
        }

        lowpassStateL = stateL
        lowpassStateR = stateR

        floatBuf.rewind()
        floatBuf.put(samples)
    }
}
