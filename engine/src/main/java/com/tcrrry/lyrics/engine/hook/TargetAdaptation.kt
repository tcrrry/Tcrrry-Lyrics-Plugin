package com.tcrrry.lyrics.engine.hook
fun interface DualPaneTarget {
    fun install(): TargetCapabilityInstall
}

fun interface EditorialVideoTarget {
    fun install(): TargetCapabilityInstall
}

fun interface BidirectionalLyricBlurTarget {
    fun install(): TargetCapabilityInstall
}

fun interface CjkKaraokeAnimationTarget {
    fun install(): TargetCapabilityInstall
}

fun interface LyricsTypefaceTarget {
    fun install(): TargetCapabilityInstall
}

fun interface CustomLyricsTarget {
    fun install(): TargetCapabilityInstall
}

fun interface CurrentSongIdentityTarget {
    fun install(): TargetCapabilityInstall
}

fun interface HleMetadataTarget {
    fun install(): TargetCapabilityInstall
}

fun interface CellularDataEntryTarget {
    fun install(): TargetCapabilityInstall
}

sealed interface TargetCapabilityInstall {
    val message: String

    data class Active(override val message: String) : TargetCapabilityInstall {
        init {
            require(message.isNotBlank()) { "Target capability diagnostic must not be blank" }
        }
    }

    data class Degraded(override val message: String) : TargetCapabilityInstall {
        init {
            require(message.isNotBlank()) { "Target capability diagnostic must not be blank" }
        }
    }

    data class Unsupported(override val message: String) : TargetCapabilityInstall {
        init {
            require(message.isNotBlank()) { "Target capability diagnostic must not be blank" }
        }
    }
}
