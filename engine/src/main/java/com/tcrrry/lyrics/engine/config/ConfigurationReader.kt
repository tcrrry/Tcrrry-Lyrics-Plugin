package com.tcrrry.lyrics.engine.config

import java.io.InputStream
import android.os.ParcelFileDescriptor

/** Read-only configuration surface consumed by target-process features. */
interface ConfigurationReader {
    fun values(): Map<String, *>
    fun openFile(name: String): InputStream?
    fun openFileDescriptor(name: String): ParcelFileDescriptor? = null
}

