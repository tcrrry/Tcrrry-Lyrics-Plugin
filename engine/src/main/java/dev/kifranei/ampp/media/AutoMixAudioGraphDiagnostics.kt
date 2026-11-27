package dev.kifranei.ampp.media

import android.media.AudioTrack
import android.os.SystemClock
import java.lang.reflect.Modifier
import java.nio.ByteBuffer

/**
 * 只读诊断：确认 Apple Music 的播放拓扑，为 AutoMix 的 tempo/EQ 实现选路。
 *
 * 不修改任何参数/音频数据，只记录：
 *  - ExoMediaPlayer 实例、内层 player 类、可用的 audio sink / processor 方法；
 *  - PlaybackParameters 的读写；
 *  - AudioTrack 的 session/采样率/声道/编码；
 *  - PlayerAudioFadeControl 的字段和方法。
 */
internal class AutoMixAudioGraphDiagnostics(
    private val loader: ClassLoader,
    private val scope: PluginScope,
) {
    private val dumpedClasses = HashSet<String>()
    private var lastTrackLogMs = 0L
    private var lastSpeedLogMs = 0L
    private var capturedHolder: Any? = null
    private var dumpedConcrete = false

    fun install() {
        val exoCls = runCatching {
            loader.loadClass("com.apple.android.music.playback.player.ExoMediaPlayer")
        }.getOrNull()
        if (exoCls == null) {
            diag("ExoMediaPlayer NOT FOUND")
            return
        }
        diag("ExoMediaPlayer found: ${exoCls.name}")
        dumpClass(exoCls, "ExoMediaPlayer")
        hookInstanceMethods(exoCls)
        for (m in exoCls.declaredMethods) {
            if (m.name == "onAudioSessionId" || m.name == "getActiveAudioRenderer") {
                runCatching {
                    MediaRuntime.observeMethod(m, object : PluginMethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            diag("${m.name} result=${param.result} this=${param.thisObject?.javaClass?.simpleName}")
                        }
                    }, scope)
                }
            }
        }

        for (ctor in exoCls.declaredConstructors) {
            runCatching {
                MediaRuntime.observeMethod(ctor, object : PluginMethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val holder = param.thisObject ?: return
                        diag("ExoMediaPlayer instance created: ${holder.javaClass.name}")
                        dumpPlayerFields(holder)
                        for (fname in listOf("fadeInTransition", "fadeOutTransition", "fadeControl")) {
                            runCatching {
                                val f = holder.javaClass.getDeclaredField(fname).apply { isAccessible = true }
                                val v = f.get(holder)
                                diag("$fname instance=${v?.javaClass?.name}")
                                if (v != null) dumpObjectFields(v, fname)
                            }
                        }
                        hookConcretePlayer(holder)
                    }
                }, scope)
            }.onFailure { diag("ctor hook failed: ${it.message}") }
        }

        val playerField = runCatching { exoCls.getDeclaredField("player").apply { isAccessible = true } }.getOrNull()
        if (playerField != null) {
            playerField.type?.let { playerCls ->
                diag("inner player class: ${playerCls.name}")
                dumpClass(playerCls, "innerPlayer")
                hookPlaybackParameters(playerCls)
            }
        } else {
            diag("ExoMediaPlayer.player field NOT FOUND")
        }

        hookAudioTrack()
        hookFadeControl()
        hookAudioEffectClasses()
    }

    private fun hookPlaybackParameters(playerCls: Class<*>) {
        for (methodName in listOf("setPlaybackParameters", "getPlaybackParameters", "getPlaybackSpeed")) {
            val m = runCatching {
                playerCls.getMethod(methodName, *if (methodName == "setPlaybackParameters") {
                    arrayOf(loader.loadClass("com.google.android.exoplayer2.PlaybackParameters"))
                } else emptyArray())
            }.getOrNull() ?: continue
            runCatching {
                MediaRuntime.observeMethod(m, object : PluginMethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastSpeedLogMs < 800) return
                        lastSpeedLogMs = now
                        val result = param.result
                        val args = param.args.joinToString(",") { it?.toString() ?: "null" }
                        diag("PlaybackParameters.$methodName args=[$args] result=$result")
                    }
                }, scope)
                diag("hooked PlaybackParameters.$methodName")
            }.onFailure { diag("hook PlaybackParameters.$methodName failed: ${it.message}") }
        }
    }

    private fun hookInstanceMethods(exoCls: Class<*>) {
        val names = listOf(
            "getCurrentPosition", "getPlaybackState", "getPlaybackRate",
            "getCurrentPlaybackFormat", "getActiveAudioRenderer", "onAudioSessionId",
            "onAudioEnabled", "onAudioInputFormatChanged",
        )
        for (m in exoCls.declaredMethods.filter { it.name in names }) {
            runCatching {
                MediaRuntime.observeMethod(m, object : PluginMethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val holder = param.thisObject ?: return
                        capturedHolder = holder
                        if (!dumpedConcrete) {
                            dumpedConcrete = true
                            diag("captured instance via ${m.name}; result=${param.result}")
                            hookConcretePlayer(holder)
                        }
                        if (m.name == "onAudioSessionId" || m.name == "getActiveAudioRenderer") {
                            diag("${m.name} result=${param.result}")
                        }
                    }
                }, scope)
                diag("hooked instance.${m.name}")
            }
        }
    }

    private fun hookConcretePlayer(holder: Any) {
        runCatching {
            val f = holder.javaClass.getDeclaredField("player").apply { isAccessible = true }
            val player = f.get(holder) ?: return@runCatching
            val cls = player.javaClass
            diag("concrete player: ${cls.name} super=${cls.superclass?.name}")
            dumpClass(cls, "concretePlayer")
            for (name in listOf("setPlaybackParameters", "getPlaybackParameters", "getPlaybackSpeed")) {
                val methods = cls.methods.filter { it.name == name }
                for (m in methods) {
                    if (java.lang.reflect.Modifier.isAbstract(m.modifiers)) continue
                    runCatching {
                        MediaRuntime.observeMethod(m, object : PluginMethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                diag("concretePlayer.$name result=${param.result} args=${param.args.joinToString(",")}")
                            }
                        }, scope)
                        diag("hooked concretePlayer.$name")
                    }.onFailure { diag("hook concretePlayer.$name failed: ${it.message}") }
                }
            }
            runCatching {
                val renderer = cls.methods.firstOrNull { it.name == "getAudioSink" }?.invoke(player)
                diag("audioSink=${renderer?.javaClass?.name ?: "null"}")
            }
        }.onFailure { diag("hookConcretePlayer failed: ${it.message}") }
    }

    private fun dumpPlayerFields(holder: Any) {
        runCatching {
            val cls = holder.javaClass
            var c: Class<*>? = cls
            var depth = 0
            while (c != null && depth < 4) {
                for (f in c.declaredFields) {
                    if (Modifier.isStatic(f.modifiers)) continue
                    val type = f.type?.name ?: continue
                    if (type.contains("Player", true) || type.contains("Audio", true) ||
                        type.contains("Renderer", true) || type.contains("Sink", true)
                    ) {
                        f.isAccessible = true
                        val value = runCatching { f.get(holder) }.getOrNull()
                        diag("field ${c.simpleName}.${f.name}: $type = ${value?.javaClass?.name ?: value}")
                    }
                }
                c = c.superclass
                depth++
            }
        }
    }

    private fun hookAudioTrack() {
        runCatching {
            for (m in AudioTrack::class.java.declaredMethods.filter { it.name == "write" }) {
                if (java.lang.reflect.Modifier.isAbstract(m.modifiers)) continue
                runCatching {
                    MediaRuntime.observeMethod(m, object : PluginMethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastTrackLogMs < 1000) return
                            lastTrackLogMs = now
                            val track = param.thisObject as? AudioTrack ?: return
                            runCatching {
                                diag("AudioTrack.write session=${track.audioSessionId} rate=${track.sampleRate} ch=${track.channelCount} fmt=${track.audioFormat} state=${track.state}")
                            }
                        }
                    }, scope)
                    diag("hooked AudioTrack.${m.name}(${m.parameterTypes.joinToString(",") { it.simpleName }})")
                }
            }
            for (ctor in AudioTrack::class.java.declaredConstructors) {
                runCatching {
                    MediaRuntime.observeMethod(ctor, object : PluginMethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val track = param.thisObject as? AudioTrack ?: return
                            runCatching {
                                diag("AudioTrack created session=${track.audioSessionId} rate=${track.sampleRate} ch=${track.channelCount} fmt=${track.audioFormat}")
                            }
                        }
                    }, scope)
                }
            }
        }.onFailure { diag("AudioTrack hook failed: ${it.message}") }
    }

    private fun hookFadeControl() {
        val cls = runCatching {
            loader.loadClass("com.apple.android.music.playback.player.PlayerAudioFadeControl")
        }.getOrNull()
        if (cls == null) {
            diag("PlayerAudioFadeControl NOT FOUND")
            return
        }
        diag("PlayerAudioFadeControl found")
        dumpClass(cls, "PlayerAudioFadeControl")
        for (methodName in listOf("prepareForCrossFade", "maybeStartCrossFading", "doCrossFade")) {
            val m = cls.declaredMethods.firstOrNull { it.name == methodName } ?: continue
            runCatching {
                MediaRuntime.observeMethod(m, object : PluginMethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        diag("FadeControl.$methodName called")
                        if (methodName == "prepareForCrossFade") {
                            for (i in 0 until 2) {
                                val h = param.args.getOrNull(i) ?: continue
                                diag("prepare arg$i holder=${h.javaClass.name}")
                                dumpObjectFields(h, "holder$i")
                            }
                        }
                        if (methodName == "doFadeIn") {
                            val h = param.args.getOrNull(0) ?: return
                            diag("doFadeIn holder=${h.javaClass.name} pos=${param.args.getOrNull(1)}")
                            dumpObjectFields(h, "fadeInHolder")
                        }
                    }
                }, scope)
            }
        }
    }

    private fun hookAudioEffectClasses() {
        for (name in listOf(
            "android.media.audiofx.Equalizer",
            "android.media.audiofx.DynamicsProcessing",
            "android.media.audiofx.BassBoost",
            "com.google.android.exoplayer2.audio.AudioProcessor",
            "com.google.android.exoplayer2.audio.DefaultAudioSink",
        )) {
            val c = runCatching { loader.loadClass(name) }.getOrNull()
            if (c != null) diag("available: ${c.name}") else diag("not found: $name")
        }
    }

    private fun dumpObjectFields(obj: Any, tag: String) {
        runCatching {
            val cls = obj.javaClass
            val sb = StringBuilder()
            var c: Class<*>? = cls
            var depth = 0
            while (c != null && depth < 3) {
                for (f in c.declaredFields) {
                    if (Modifier.isStatic(f.modifiers)) continue
                    f.isAccessible = true
                    val v = runCatching { f.get(obj) }.getOrNull()
                    if (v == null || v is Number || v is Boolean || v is String) {
                        sb.append("${f.name}=$v; ")
                    } else {
                        sb.append("${f.name}:${f.type.simpleName}=${v.javaClass.simpleName}; ")
                    }
                }
                c = c.superclass
                depth++
            }
            diag("$tag fields: $sb")
        }
    }

    private fun dumpClass(cls: Class<*>, tag: String) {
        if (!dumpedClasses.add(cls.name)) return
        val methods = cls.declaredMethods
            .filter { m ->
                m.name.contains("Audio", true) || m.name.contains("Playback", true) ||
                    m.name.contains("Speed", true) || m.name.contains("Sink", true) ||
                    m.name.contains("Processor", true) || m.name.contains("Fade", true)
            }
            .take(40)
            .joinToString("; ") { it.name + "(" + it.parameterTypes.joinToString(",") { p -> p.simpleName } + ")" }
        val fields = cls.declaredFields
            .filter { f -> f.type.name.contains("Audio", true) || f.type.name.contains("Sink", true) || f.name.contains("fade", true) }
            .take(30)
            .joinToString("; ") { f -> "${f.name}:${f.type.simpleName}" }
        diag("$tag methods: $methods")
        diag("$tag fields: $fields")
        diag("$tag superclass: ${cls.superclass?.name}")
    }

    private fun diag(message: String) {
        FileLogger.log("automix-diag: $message")
    }
}
