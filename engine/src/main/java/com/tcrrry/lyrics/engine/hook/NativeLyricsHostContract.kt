package com.tcrrry.lyrics.engine.hook

import com.tcrrry.lyrics.plugin.PluginLifetime
import com.tcrrry.lyrics.host.applemusic.AppleMusicHostProfiles
import java.lang.reflect.Field
import org.json.JSONObject

/** Only the exact 1609 build remaps the previously verified native lyric seams.
 * Resolve names once during registration, never by guessing a same-shaped field.
 */
internal object NativeLyricsHostContract {
    private fun mapping(): JSONObject? {
        if (!PluginLifetime.alive) return null
        val host = PluginLifetime.context
        if (host.hostVersionCode != 1609L || host.hostVersionName != "7.0.0-beta") return null
        return AppleMusicHostProfiles.find("com.apple.android.music", host.hostVersionName, host.hostVersionCode)
            ?.document?.getJSONObject("pluginLyricsCompatibility")
    }

    fun className(legacy: String): String = mapping()?.getJSONObject("classes")?.optString(legacy, legacy) ?: legacy
    fun fieldName(owner: String, legacy: String): String =
        mapping()?.getJSONObject("fields")?.optJSONObject(owner)?.optString(legacy, legacy) ?: legacy

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
