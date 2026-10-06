package com.tcrrry.lyrics.offline

import android.content.Context
import android.content.Intent
import android.net.Uri

/** An explicit URI interaction makes this provider visible without editing the host manifest. */
internal object ClientAccess {
    val uri:Uri = Uri.parse("content://com.tcrrry.lyrics.offline.models/access")
    fun grant(context:Context) {
        for (client in listOf("com.apple.android.music","com.tcrrry.ampplyrics.coexist")) {
            // The companion declares queries for these clients. Missing clients are harmless.
            runCatching {
                context.grantUriPermission(client, uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }
        }
    }
}
