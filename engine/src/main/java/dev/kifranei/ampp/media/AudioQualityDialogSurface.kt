package dev.kifranei.ampp.media

import android.app.Activity
import android.app.Dialog
import android.graphics.drawable.Drawable

/** Native disclosure values and actions; presentation belongs to the module. */
data class AudioQualityDialogSurface(
    val activity: Activity,
    val dialog: Dialog,
    val badge: Drawable,
    val title: CharSequence,
    val encoding: CharSequence,
    val source: CharSequence,
    val settingsLabel: CharSequence,
    val doneLabel: CharSequence,
    val openSettings: () -> Unit,
    val done: () -> Unit,
    /** Show the initialized native Dialog after replacing its content. */
    val show: () -> Unit,
)
