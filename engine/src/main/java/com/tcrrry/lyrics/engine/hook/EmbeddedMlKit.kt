package com.tcrrry.lyrics.engine.hook
import android.content.Context
internal object EmbeddedMlKit { fun initialize(context: Context) { com.tcrrry.lyrics.plugin.OfflineTranslationClient.requireInstalled(context) } }
