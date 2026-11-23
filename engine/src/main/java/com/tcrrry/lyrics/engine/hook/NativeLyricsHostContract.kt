package com.tcrrry.lyrics.engine.hook

import com.tcrrry.lyrics.plugin.PluginLifetime
import com.tcrrry.lyrics.host.applemusic.AppleMusicHostProfiles
import java.lang.reflect.Field
import java.lang.reflect.Method
import org.json.JSONObject

/** Only explicitly audited builds remap the previously verified native lyric seams.
 * Resolve names once during registration, never by guessing a same-shaped field.
 */
internal object NativeLyricsHostContract {
    private fun mapping(): JSONObject? {
        if (!PluginLifetime.alive) return null
        val host = PluginLifetime.context
        if (!((host.hostVersionCode in setOf(1609L, 1613L) && host.hostVersionName == "7.0.0-beta") ||
                (host.hostVersionCode == 1614L && host.hostVersionName == "7.0.0") ||
                (host.hostVersionCode == 1622L && host.hostVersionName == "7.0.1") ||
                (host.hostVersionCode == 1626L && host.hostVersionName == "7.0.3"))) return null
        return AppleMusicHostProfiles.find("com.apple.android.music", host.hostVersionName, host.hostVersionCode)
            ?.document?.getJSONObject("pluginLyricsCompatibility")
    }

    fun className(legacy: String): String = mapping()?.getJSONObject("classes")?.optString(legacy, legacy) ?: legacy
    fun fieldName(owner: String, legacy: String): String =
        mapping()?.getJSONObject("fields")?.optJSONObject(owner)?.optString(legacy, legacy) ?: legacy

    fun shareSuppressFieldName(): String? {
        if (!PluginLifetime.alive) return null
        val mapped = fieldName("com.apple.android.music.player.fragment.PlayerLyricsViewFragment", "W0")
        if (mapped != "W0") return mapped
        val host = PluginLifetime.context
        return when {
            host.hostVersionCode in setOf(1609L, 1613L) && host.hostVersionName == "7.0.0-beta" -> "U0"
            host.hostVersionCode == 1614L && host.hostVersionName == "7.0.0" -> "U0"
            host.hostVersionCode == 1622L && host.hostVersionName == "7.0.1" -> "V0"
            host.hostVersionCode == 1626L && host.hostVersionName == "7.0.3" -> "U0"
            else -> "W0"
        }
    }

    // These names are used only for the audited 1614, 1622 and 1626 builds. Other releases keep
    // the already audited legacy names. Class-specific maps avoid changing
    // unrelated obfuscated methods that happen to share the same spelling.
    internal fun methodNameForBuild(versionCode: Long, versionName: String, owner: String, legacy: String): String {
        val is1614 = versionCode == 1614L && versionName == "7.0.0"
        val uses1622Methods = (versionCode == 1622L && versionName == "7.0.1") ||
            (versionCode == 1626L && versionName == "7.0.3")
        if (!is1614 && !uses1622Methods) return legacy
        val common = mapOf("w" to "x", "y" to "z", "E" to "F", "N" to "O", "P" to "Q", "Q" to "R", "O" to "P", "v" to "w", "B" to "C", "C" to "D")
        val adapter = mapOf("k" to "l", "l" to "m")
        val word = mapOf("o0" to "p0", "F" to "G", "i0" to "j0", "t0" to "u0", "c0" to "d0", "d0" to "e0", "U" to "V", "b0" to "c0", "W" to "X")
        return if (is1614) {
            when (owner) {
                "com.apple.android.music.player.g1" -> common[legacy]
                "com.apple.android.music.player.x" -> word[legacy] ?: adapter[legacy] ?: common[legacy]
                "com.apple.android.music.player.S0" -> (if (legacy == "U") "V" else adapter[legacy]) ?: common[legacy]
                else -> null
            }
        } else {
            when (owner) {
                "com.apple.android.music.player.i1" -> common[legacy]
                "com.apple.android.music.player.A" -> word[legacy] ?: adapter[legacy] ?: common[legacy]
                "com.apple.android.music.player.V0" -> (if (legacy == "U") "V" else adapter[legacy]) ?: common[legacy]
                "q8.X4" -> when (legacy) {
                    "q0" -> "p0"
                    "p0" -> "o0"
                    else -> null
                }
                else -> null
            }
        } ?: legacy
    }
    fun methodName(owner: String, legacy: String): String {
        if (!PluginLifetime.alive) return legacy
        val host = PluginLifetime.context
        return methodNameForBuild(host.hostVersionCode, host.hostVersionName, owner, legacy)
    }

    fun verify(loader: ClassLoader) {
        val contract = mapping() ?: return
        contract.getJSONObject("classes").let { classes ->
            classes.keys().forEach { loader.loadClass(classes.getString(it)) }
        }
        contract.getJSONObject("fields").let { owners ->
            owners.keys().forEach { owner ->
                val type = loader.loadClass(owner)
                val fields = owners.getJSONObject(owner)
                fields.keys().forEach { type.getDeclaredField(fields.getString(it)) }
            }
        }
    }
}

internal fun ClassLoader.lyricsClass(name: String): Class<*> = loadClass(NativeLyricsHostContract.className(name))
internal fun lyricsClassForName(name: String, initialize: Boolean, loader: ClassLoader?): Class<*> =
    Class.forName(NativeLyricsHostContract.className(name), initialize, loader)
internal fun Class<*>.lyricsDeclaredField(name: String): Field =
    getDeclaredField(NativeLyricsHostContract.fieldName(this.name, name))
internal fun Class<*>.lyricsField(name: String): Field =
    getField(NativeLyricsHostContract.fieldName(this.name, name))

internal fun Class<*>.lyricsDeclaredMethod(name: String, vararg parameters: Class<*>?): Method =
    getDeclaredMethod(NativeLyricsHostContract.methodName(this.name, name), *parameters)
internal fun Class<*>.lyricsMethod(name: String, vararg parameters: Class<*>?): Method =
    getMethod(NativeLyricsHostContract.methodName(this.name, name), *parameters)
