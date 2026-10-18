# 1.0.9-preview.6 / Translation 1.0.0-preview.2

Fix the device-confirmed crash when opening offline translation: Android encoded
an anonymous sender ResultReceiver class into the broadcast Parcel, which the
translation plugin class loader cannot resolve. Send a framework ResultReceiver
created through its CREATOR instead, preserving the Binder callback endpoint.
Reject malformed request extras without escaping the broadcast dispatcher.

Use a search-first panel with no default language rows, and actions labelled
已下载语言包 and 检测离线翻译.
Both ZIPs should be updated. Tests round-trip the Intent through Parcel with a
receiver loader that refuses sender classes and verify the callback still works.
