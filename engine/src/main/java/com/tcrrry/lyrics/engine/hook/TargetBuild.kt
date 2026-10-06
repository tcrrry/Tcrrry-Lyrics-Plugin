package com.tcrrry.lyrics.engine.hook

import com.tcrrry.lyrics.engine.ModuleConstants

/** Structured host identity; displayName is never a compatibility key. */
data class TargetBuild(val packageName: String, val versionName: String, val versionCode: Long) {
    val displayName: String
        get() = if (versionName.isBlank() && versionCode < 0) "unknown" else "$versionName ($versionCode)"
    companion object { val UNKNOWN = TargetBuild(ModuleConstants.TARGET_PACKAGE, "", -1) }
}
