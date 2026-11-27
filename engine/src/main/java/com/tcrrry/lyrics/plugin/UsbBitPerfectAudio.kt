package com.tcrrry.lyrics.plugin

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import com.tcrrry.lyrics.plugin.PluginStorage.pluginPreferences
import dev.amenhancer.plugin.api.PluginCall
import dev.amenhancer.plugin.api.PluginContext
import dev.amenhancer.plugin.api.PluginHook
import dev.amenhancer.plugin.api.PluginRegistration
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

internal object UsbMixerSelector {
    data class MixerCandidate(
        val sampleRate: Int,
        val encoding: Int,
        val channelMask: Int,
        val bitPerfect: Boolean,
        val index: Int,
    )

    fun selectBest(
        targetRate: Int,
        targetEncoding: Int,
        targetChannelMask: Int,
        candidates: List<MixerCandidate>,
    ): MixerCandidate? {
        if (candidates.isEmpty() || targetRate <= 0) return null

        val exactRate = candidates.filter { it.sampleRate == targetRate }
        val ratePool = if (exactRate.isNotEmpty()) {
            exactRate
        } else {
            // Never downsample; pick the closest higher supported sample rate if exact is absent.
            val higher = candidates.filter { it.sampleRate > targetRate }
            if (higher.isEmpty()) return null
            val minHigher = higher.minOf { it.sampleRate }
            higher.filter { it.sampleRate == minHigher }
        }

        val bitPerfectPool = ratePool.filter { it.bitPerfect }
        val preferredBehaviorPool = bitPerfectPool.ifEmpty { ratePool }

        val exactEncodingPool = if (bitPerfectPool.isNotEmpty()) {
            preferredBehaviorPool.filter { it.encoding == targetEncoding }
        } else {
            emptyList()
        }
        val encodingPool = exactEncodingPool.ifEmpty { preferredBehaviorPool }

        val maskPool = encodingPool.filter {
            targetChannelMask != 0 && it.channelMask == targetChannelMask
        }.ifEmpty { encodingPool }

        return maskPool.maxWithOrNull(
            compareBy<MixerCandidate>(
                { if (it.bitPerfect) 1 else 0 },
                { encodingRank(it.encoding, preferHeadroom = !it.bitPerfect) },
                { -it.index },
            )
        )
    }

    private fun encodingRank(encoding: Int, preferHeadroom: Boolean): Int = when (encoding) {
        AudioFormat.ENCODING_PCM_32BIT -> if (preferHeadroom) 5 else 4
        AudioFormat.ENCODING_PCM_FLOAT -> if (preferHeadroom) 4 else 5
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
        AudioFormat.ENCODING_PCM_16BIT -> 2
        AudioFormat.ENCODING_PCM_8BIT -> 1
        else -> 0
    }

    fun encodingShortName(encoding: Int): String = when (encoding) {
        AudioFormat.ENCODING_PCM_16BIT -> "16bit"
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> "24bit"
        AudioFormat.ENCODING_PCM_32BIT -> "32bit"
        AudioFormat.ENCODING_PCM_FLOAT -> "Float32"
        AudioFormat.ENCODING_PCM_8BIT -> "8bit"
        0 -> "未知"
        else -> "fmt#$encoding"
    }

    fun formatSampleRateLabel(rateHz: Int): String = when {
        rateHz <= 0 -> "未知"
        rateHz % 1000 == 0 -> "${rateHz / 1000} kHz"
        else -> "${"%.1f".format(Locale.US, rateHz / 1000.0)} kHz"
    }

    fun formatSampleRates(rates: List<Int>): String =
        rates.distinct().sorted().joinToString(" / ") {
            if (it % 1000 == 0) "${it / 1000}k" else "${"%.1f".format(Locale.US, it / 1000.0)}k"
        }.ifEmpty { "系统托管" }

    fun formatEncodings(encodings: List<Int>): String =
        encodings.distinct().sortedByDescending { encodingRank(it, preferHeadroom = true) }
            .joinToString(" · ") { encodingShortName(it) }
            .ifEmpty { "系统托管" }
}

internal data class UsbDiagnosticSnapshot(
    val apiSupported: Boolean,
    val enabled: Boolean,
    val devicePresent: Boolean,
    val usbDacPresent: Boolean,
    val wiredHeadsetPresent: Boolean,
    val deviceName: String,
    val deviceTypeLabel: String,
    val deviceAddress: String,
    val supportedRates: List<Int>,
    val supportedEncodings: List<Int>,
    val maxDeviceRateHz: Int,
    val maxDeviceBitDepthLabel: String,
    val totalMixers: Int,
    val bitPerfectCount: Int,
    val mixerSummary: String,
    val lastTrackRateHz: Int,
    val lastTrackEncoding: Int,
    val lastTrackRequest: String,
    val lastAppliedResult: String,
    val routedDescription: String,
    val activeMixerDescription: String,
    val channelStateLabel: String,
    val logFilePath: String,
) {
    fun formatCleanCard(): String {
        val sb = StringBuilder()
        val deviceLine = when {
            usbDacPresent -> "$deviceName（USB 小尾巴）"
            wiredHeadsetPresent -> "$deviceName（有线耳机）"
            else -> "未检测到有线耳机 / USB 小尾巴"
        }
        sb.appendLine("连接设备：$deviceLine")

        val specLine = when {
            usbDacPresent && maxDeviceRateHz > 0 -> {
                val maxRate = UsbMixerSelector.formatSampleRateLabel(maxDeviceRateHz)
                if (maxDeviceBitDepthLabel.isNotBlank() && maxDeviceBitDepthLabel != "系统托管") {
                    "最高 $maxRate · $maxDeviceBitDepthLabel"
                } else {
                    "最高 $maxRate"
                }
            }
            usbDacPresent -> "USB DAC 外置声卡"
            wiredHeadsetPresent -> "机身有线耳机接口"
            else -> "等待接入有线输出设备"
        }
        sb.appendLine("小尾巴 / 耳机素质：$specLine")

        val liveStreamLine = if (lastTrackRateHz > 0) {
            "${UsbMixerSelector.formatSampleRateLabel(lastTrackRateHz)} / ${UsbMixerSelector.encodingShortName(lastTrackEncoding)}"
        } else {
            "等待播歌捕捉（切歌或播放后自动刷新）"
        }
        sb.appendLine("当前实时采样率：$liveStreamLine")

        sb.append("当前通道状态：$channelStateLabel")
        return sb.toString().trim()
    }

    fun formatCard(): String = formatCleanCard()
}

internal object UsbBitPerfectAudio {
    private const val PREFS_NAME = "more_tools_usb_audio"
    private const val KEY_ENABLED = "usb_bit_perfect_enabled"
    private const val LOG_FILE_NAME = "usb_bitperfect_log.txt"

    private data class AppliedKey(
        val deviceId: Int,
        val usage: Int,
        val contentType: Int,
    )

    private val lock = Any()
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val notifyPending = AtomicBoolean(false)
    private val appliedPrefs = LinkedHashMap<AppliedKey, Pair<AudioAttributes, AudioDeviceInfo>>()

    @Volatile private var appContext: Context? = null
    private val hookRegistrations = ArrayList<PluginRegistration>()
    @Volatile private var deviceCallbackRegistered = false
    @Volatile private var deviceCallbackObj: Any? = null

    @Volatile private var enabledCached = false
    @Volatile private var lastTrackRateHz = 0
    @Volatile private var lastTrackEncoding = 0
    @Volatile private var lastTrackRequest = ""
    @Volatile private var lastAppliedResult = ""
    @Volatile private var lastAppliedBitPerfect = false
    @Volatile private var lastAppliedDedicated = false

    fun isApiSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    fun isEnabled(context: Context): Boolean = synchronized(lock) {
        val prefs = context.applicationContext.pluginPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val v = prefs.getBoolean(KEY_ENABLED, false)
        enabledCached = v
        v
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        val app = context.applicationContext
        synchronized(lock) {
            enabledCached = enabled
            app.pluginPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ENABLED, enabled)
                .apply()
            if (!enabled) {
                lastAppliedBitPerfect = false
                lastAppliedDedicated = false
                lastAppliedResult = ""
                if (isApiSupported()) {
                    Api34.clearAllTracked(app, appliedPrefs)
                }
            }
            appendLog(app, "setEnabled=$enabled")
        }
        notifyListeners()
    }

    fun addListener(listener: () -> Unit): AutoCloseable {
        listeners.add(listener)
        return AutoCloseable { listeners.remove(listener) }
    }

    private fun notifyListeners() {
        if (listeners.isEmpty()) return
        if (!notifyPending.compareAndSet(false, true)) return
        mainHandler.post {
            notifyPending.set(false)
            for (listener in listeners) {
                runCatching { listener() }
            }
        }
    }

    fun attach(context: PluginContext) {
        val app = context.application.applicationContext
        appContext = app
        isEnabled(app)
        installHooksIfNeeded(context)
        registerDeviceCallbackIfNeeded(app)
    }

    fun close() {
        val app = appContext
        synchronized(lock) {
            hookRegistrations.forEach { reg -> runCatching { reg.close() } }
            hookRegistrations.clear()
            if (app != null && isApiSupported()) {
                Api34.clearAllTracked(app, appliedPrefs)
            }
        }
        if (app != null && isApiSupported()) {
            val cb = deviceCallbackObj
            if (cb is AudioDeviceCallback) {
                runCatching {
                    val am = app.getSystemService(AudioManager::class.java)
                    am?.unregisterAudioDeviceCallback(cb)
                }
            }
        }
        deviceCallbackObj = null
        deviceCallbackRegistered = false
        listeners.clear()
    }

    fun menuSummary(context: Context): String {
        val snap = probeNow(context)
        val deviceText = when {
            snap.usbDacPresent -> snap.deviceName
            snap.wiredHeadsetPresent -> snap.deviceName
            else -> "独立通道 / Root独占兼容"
        }
        return if (snap.enabled) {
            val rateText = if (snap.lastTrackRateHz > 0) {
                " · ${UsbMixerSelector.formatSampleRateLabel(snap.lastTrackRateHz)}"
            } else {
                ""
            }
            "已开启 · $deviceText$rateText"
        } else {
            if (snap.devicePresent) {
                "已关闭 · 已连接 $deviceText"
            } else {
                "已关闭 · 独立Hi-Fi通道与源码直通"
            }
        }
    }

    fun clearPreferred(context: Context): String {
        val app = context.applicationContext
        val clearedCount = synchronized(lock) {
            lastAppliedResult = ""
            lastAppliedBitPerfect = false
            lastAppliedDedicated = false
            if (isApiSupported()) {
                Api34.clearAllTracked(app, appliedPrefs)
            } else {
                appliedPrefs.clear()
                0
            }
        }
        appendLog(app, "clearPreferred count=$clearedCount")
        notifyListeners()
        return if (clearedCount > 0) {
            "已恢复系统默认混音配置（清除 $clearedCount 项偏好）"
        } else {
            "已处于系统默认混音状态"
        }
    }

    fun probeNow(context: Context): UsbDiagnosticSnapshot {
        val app = context.applicationContext
        val enabled = isEnabled(app)
        val supported = isApiSupported()
        val am = runCatching { app.getSystemService(AudioManager::class.java) }.getOrNull()
        val logPath = File(app.getExternalFilesDir(null) ?: app.filesDir, LOG_FILE_NAME).absolutePath

        if (am == null) {
            return UsbDiagnosticSnapshot(
                apiSupported = supported,
                enabled = enabled,
                devicePresent = false,
                usbDacPresent = false,
                wiredHeadsetPresent = false,
                deviceName = "",
                deviceTypeLabel = "",
                deviceAddress = "",
                supportedRates = emptyList(),
                supportedEncodings = emptyList(),
                maxDeviceRateHz = 0,
                maxDeviceBitDepthLabel = "",
                totalMixers = 0,
                bitPerfectCount = 0,
                mixerSummary = "",
                lastTrackRateHz = lastTrackRateHz,
                lastTrackEncoding = lastTrackEncoding,
                lastTrackRequest = lastTrackRequest,
                lastAppliedResult = lastAppliedResult,
                routedDescription = "",
                activeMixerDescription = "",
                channelStateLabel = "音频服务暂不可用",
                logFilePath = logPath,
            )
        }

        val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val usbDevice = outputs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                it.type == AudioDeviceInfo.TYPE_USB_ACCESSORY
        }
        val wiredDevice = outputs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == AudioDeviceInfo.TYPE_LINE_DIGITAL ||
                it.type == AudioDeviceInfo.TYPE_LINE_ANALOG
        }
        val primary = usbDevice ?: wiredDevice

        val rates = primary?.sampleRates?.toList()?.filter { it > 0 }?.distinct()?.sorted().orEmpty()
        val encodings = primary?.encodings?.toList()?.filter { it > 0 }?.distinct().orEmpty()
        val maxRate = rates.maxOrNull() ?: 0
        val maxBitLabel = when {
            encodings.contains(AudioFormat.ENCODING_PCM_32BIT) || encodings.contains(AudioFormat.ENCODING_PCM_FLOAT) -> "32bit / Float32"
            encodings.contains(AudioFormat.ENCODING_PCM_24BIT_PACKED) -> "24bit"
            encodings.contains(AudioFormat.ENCODING_PCM_16BIT) -> "16bit"
            encodings.isNotEmpty() -> UsbMixerSelector.formatEncodings(encodings)
            else -> ""
        }

        val deviceName = primary?.productName?.toString()?.trim().orEmpty().ifEmpty {
            if (usbDevice != null) "USB DAC 小尾巴" else if (wiredDevice != null) "有线耳机" else ""
        }
        val deviceTypeLabel = when (primary?.type) {
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB_ACCESSORY"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "WIRED_HEADPHONES"
            AudioDeviceInfo.TYPE_LINE_DIGITAL -> "LINE_DIGITAL"
            AudioDeviceInfo.TYPE_LINE_ANALOG -> "LINE_ANALOG"
            else -> ""
        }
        val deviceAddress = primary?.address.orEmpty()

        val api34Info = if (supported && primary != null) {
            Api34.inspectDevice(am, primary)
        } else {
            Api34.InspectResult(0, 0, "", "", false, false)
        }

        val effectiveBitPerfect = api34Info.readbackBitPerfect || lastAppliedBitPerfect
        val effectiveDedicated = api34Info.readbackApplied || lastAppliedDedicated
        val channelStateLabel = when {
            !supported -> "需 Android 14+ 支持系统直通混音接口"
            !enabled -> "未启用（当前使用系统默认通道）"
            primary == null -> "已开启，等待接入有线耳机或小尾巴"
            effectiveBitPerfect -> "硬件 Bit-Perfect 源码直通已激活"
            usbDevice != null && effectiveDedicated -> "独立 Hi-Fi 通道已激活（已绕过系统音效，32bit 余裕）"
            usbDevice != null && lastTrackRateHz <= 0 -> "已开启，播歌或切歌后自动申请独立 Hi-Fi 通道"
            usbDevice != null -> "已匹配设备配置（系统托管路由）"
            else -> "有线耳机标准系统通道"
        }

        return UsbDiagnosticSnapshot(
            apiSupported = supported,
            enabled = enabled,
            devicePresent = primary != null,
            usbDacPresent = usbDevice != null,
            wiredHeadsetPresent = wiredDevice != null,
            deviceName = deviceName,
            deviceTypeLabel = deviceTypeLabel,
            deviceAddress = deviceAddress,
            supportedRates = rates,
            supportedEncodings = encodings,
            maxDeviceRateHz = maxRate,
            maxDeviceBitDepthLabel = maxBitLabel,
            totalMixers = api34Info.totalMixers,
            bitPerfectCount = api34Info.bitPerfectCount,
            mixerSummary = api34Info.summary,
            lastTrackRateHz = lastTrackRateHz,
            lastTrackEncoding = lastTrackEncoding,
            lastTrackRequest = lastTrackRequest,
            lastAppliedResult = lastAppliedResult,
            routedDescription = api34Info.readbackText,
            activeMixerDescription = api34Info.readbackText,
            channelStateLabel = channelStateLabel,
            logFilePath = logPath,
        )
    }

    private fun registerDeviceCallbackIfNeeded(app: Context) {
        if (deviceCallbackRegistered) return
        val am = runCatching { app.getSystemService(AudioManager::class.java) }.getOrNull() ?: return
        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                val relevant = addedDevices.any { isWiredOrUsbOutput(it) }
                if (relevant) notifyListeners()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                val relevant = removedDevices.any { isWiredOrUsbOutput(it) }
                if (relevant) notifyListeners()
            }
        }
        runCatching {
            am.registerAudioDeviceCallback(cb, mainHandler)
            deviceCallbackObj = cb
            deviceCallbackRegistered = true
        }
    }

    private fun isWiredOrUsbOutput(info: AudioDeviceInfo): Boolean {
        if (!info.isSink) return false
        return when (info.type) {
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_LINE_DIGITAL,
            AudioDeviceInfo.TYPE_LINE_ANALOG -> true
            else -> false
        }
    }

    private fun installHooksIfNeeded(context: PluginContext) {
        synchronized(lock) {
            if (hookRegistrations.isNotEmpty()) return

            // 1) AudioTrack.Builder.build(): always observe live sample rate & encoding; apply mixer pref when enabled.
            runCatching {
                val buildMethod = AudioTrack.Builder::class.java.getDeclaredMethod("build")
                val reg = context.hooks.hook(buildMethod, false, object : PluginHook {
                    override fun before(call: PluginCall) {
                        val builder = call.receiver ?: return
                        val format = extractField(builder, "mFormat") as? AudioFormat ?: return
                        val attrs = (extractField(builder, "mAttributes") as? AudioAttributes)
                            ?: defaultMediaAttributes()
                        handleTrackInit("Builder.build", attrs, format)
                    }
                })
                hookRegistrations.add(reg)
            }

            // 2) AudioTrack.<init>(...): fallback for direct constructor calls.
            for (ctor in AudioTrack::class.java.declaredConstructors) {
                runCatching {
                    ctor.isAccessible = true
                    val reg = context.hooks.hook(ctor, false, object : PluginHook {
                        override fun before(call: PluginCall) {
                            val args = call.arguments
                            val attrs = args.filterIsInstance<AudioAttributes>().firstOrNull()
                            val format = args.filterIsInstance<AudioFormat>().firstOrNull()
                            if (attrs != null && format != null) {
                                handleTrackInit("AudioTrack.<init>", attrs, format)
                                return
                            }
                            // Legacy constructor: (int streamType, int sampleRateInHz, int channelConfig, int audioFormat, ...)
                            if (args.size >= 4 && args[0] is Int && args[1] is Int && args[2] is Int && args[3] is Int) {
                                val rate = args[1] as Int
                                val chMask = args[2] as Int
                                val enc = args[3] as Int
                                if (rate > 0) {
                                    recordTrackInfo("AudioTrack.legacy", rate, enc)
                                    if (enabledCached && isApiSupported()) {
                                        val legacyFormat = runCatching {
                                            AudioFormat.Builder()
                                                .setSampleRate(rate)
                                                .setEncoding(enc)
                                                .setChannelMask(chMask)
                                                .build()
                                        }.getOrNull() ?: return
                                        applyOnTrackStart("AudioTrack.legacy", defaultMediaAttributes(), legacyFormat)
                                    }
                                }
                            }
                        }
                    })
                    hookRegistrations.add(reg)
                }
            }
        }
    }

    private fun handleTrackInit(source: String, attrs: AudioAttributes, format: AudioFormat) {
        val rate = runCatching { format.sampleRate }.getOrDefault(0)
        val encoding = runCatching { format.encoding }.getOrDefault(0)
        if (rate <= 0) return
        recordTrackInfo(source, rate, encoding)
        if (enabledCached && isApiSupported()) {
            applyOnTrackStart(source, attrs, format)
        }
    }

    private fun recordTrackInfo(source: String, rate: Int, encoding: Int) {
        val requestDesc = "${rate}Hz/${UsbMixerSelector.encodingShortName(encoding)} ($source)"
        val changed = lastTrackRateHz != rate || lastTrackEncoding != encoding
        lastTrackRateHz = rate
        lastTrackEncoding = encoding
        lastTrackRequest = requestDesc
        if (changed) {
            notifyListeners()
        }
    }

    private fun applyOnTrackStart(source: String, attrs: AudioAttributes, format: AudioFormat) {
        val app = appContext ?: return
        val outcome = if (isApiSupported()) {
            synchronized(lock) {
                Api34.applyPreferredForTrack(app, attrs, format, appliedPrefs)
            }
        } else {
            return
        }
        lastAppliedBitPerfect = outcome.bitPerfect
        lastAppliedDedicated = outcome.dedicatedApplied
        lastAppliedResult = outcome.summary
        appendLog(app, "$source rate=${format.sampleRate} enc=${format.encoding} -> ${outcome.summary}")
        notifyListeners()
    }

    private fun defaultMediaAttributes(): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

    private fun extractField(obj: Any, fieldName: String): Any? {
        var c: Class<*>? = obj.javaClass
        while (c != null) {
            try {
                val f = c.getDeclaredField(fieldName)
                f.isAccessible = true
                return f.get(obj)
            } catch (_: Throwable) {
                c = c.superclass
            }
        }
        return null
    }

    private fun appendLog(context: Context, line: String) {
        runCatching {
            val file = File(context.getExternalFilesDir(null) ?: context.filesDir, LOG_FILE_NAME)
            val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            if (file.exists() && file.length() > 128 * 1024) {
                file.writeText("")
            }
            file.appendText("[$stamp] $line\n")
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private object Api34 {
        data class ApplyOutcome(
            val dedicatedApplied: Boolean,
            val bitPerfect: Boolean,
            val summary: String,
        )

        data class InspectResult(
            val totalMixers: Int,
            val bitPerfectCount: Int,
            val summary: String,
            val readbackText: String,
            val readbackApplied: Boolean,
            val readbackBitPerfect: Boolean,
        )

        fun inspectDevice(am: AudioManager, device: AudioDeviceInfo): InspectResult {
            val attrsList = runCatching { am.getSupportedMixerAttributes(device) }.getOrNull().orEmpty()
            var bpCount = 0
            for (ma in attrsList) {
                if (ma.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT) {
                    bpCount++
                }
            }
            val mediaAttrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val active = runCatching { am.getPreferredMixerAttributes(mediaAttrs, device) }.getOrNull()
            val readbackText = if (active != null) {
                val fmt = active.format
                val behavior = if (active.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT) {
                    "BIT_PERFECT"
                } else {
                    "MIXER_DEFAULT"
                }
                "$behavior ${fmt.sampleRate}Hz/${UsbMixerSelector.encodingShortName(fmt.encoding)}"
            } else {
                ""
            }
            return InspectResult(
                totalMixers = attrsList.size,
                bitPerfectCount = bpCount,
                summary = if (attrsList.isEmpty()) "" else "${attrsList.size} 项（Bit-Perfect $bpCount 项）",
                readbackText = readbackText,
                readbackApplied = active != null,
                readbackBitPerfect = active?.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT,
            )
        }

        fun applyPreferredForTrack(
            context: Context,
            attrs: AudioAttributes,
            format: AudioFormat,
            cache: MutableMap<AppliedKey, Pair<AudioAttributes, AudioDeviceInfo>>,
        ): ApplyOutcome {
            val am = runCatching { context.getSystemService(AudioManager::class.java) }.getOrNull()
                ?: return ApplyOutcome(false, false, "AudioManager 不可用")
            val usb = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                    it.type == AudioDeviceInfo.TYPE_USB_ACCESSORY
            } ?: return ApplyOutcome(false, false, "未连接 USB 小尾巴")

            val mixerList = runCatching { am.getSupportedMixerAttributes(usb) }.getOrNull().orEmpty()
            if (mixerList.isEmpty()) {
                return ApplyOutcome(false, false, "设备未公开可选混音配置")
            }

            val candidates = mixerList.mapIndexed { idx, ma ->
                val mf = ma.format
                UsbMixerSelector.MixerCandidate(
                    sampleRate = mf.sampleRate,
                    encoding = mf.encoding,
                    channelMask = mf.channelMask,
                    bitPerfect = ma.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT,
                    index = idx,
                )
            }

            val best = UsbMixerSelector.selectBest(
                targetRate = format.sampleRate,
                targetEncoding = format.encoding,
                targetChannelMask = format.channelMask,
                candidates = candidates,
            ) ?: return ApplyOutcome(false, false, "无不降采样的匹配混音器")

            val chosenAttr = mixerList[best.index]
            val ok = runCatching { am.setPreferredMixerAttributes(attrs, usb, chosenAttr) }.getOrDefault(false)
            if (ok) {
                val key = AppliedKey(usb.id, attrs.usage, attrs.contentType)
                cache[key] = attrs to usb
            }
            val label = if (best.bitPerfect) "BIT_PERFECT" else "MIXER_DEFAULT"
            val summary = if (ok) {
                "$label ${best.sampleRate}Hz/${UsbMixerSelector.encodingShortName(best.encoding)}"
            } else {
                "申请被系统拒绝 ($label ${best.sampleRate}Hz)"
            }
            return ApplyOutcome(
                dedicatedApplied = ok,
                bitPerfect = ok && best.bitPerfect,
                summary = summary,
            )
        }

        fun clearAllTracked(
            context: Context,
            cache: MutableMap<AppliedKey, Pair<AudioAttributes, AudioDeviceInfo>>,
        ): Int {
            val am = runCatching { context.getSystemService(AudioManager::class.java) }.getOrNull()
            var count = 0
            if (am != null) {
                for ((_, pair) in cache) {
                    val (attrs, device) = pair
                    if (runCatching { am.clearPreferredMixerAttributes(attrs, device) }.getOrDefault(false)) {
                        count++
                    }
                }
                // Also clear default media attributes on currently attached USB devices even if not in memory cache.
                val mediaAttrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
                val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                for (d in devices) {
                    if (d.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                        d.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                        d.type == AudioDeviceInfo.TYPE_USB_ACCESSORY
                    ) {
                        if (runCatching { am.clearPreferredMixerAttributes(mediaAttrs, d) }.getOrDefault(false)) {
                            count++
                        }
                    }
                }
            }
            cache.clear()
            return count
        }
    }
}
