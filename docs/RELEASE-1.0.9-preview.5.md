# 1.0.9-preview.5

- Restore soft keyboard support for custom content dialogs by clearing the stale
  AlertController `FLAG_ALT_FOCUSABLE_IM`; fields explicitly request IME on tap.
- Keep the main lyrics ZIP free of ML Kit/native/model payloads. Offline translation
  now uses the optional `com.tcrrry.translation` SDK ZIP over a private versioned
  same-host protocol. No host APK or companion installation is required.
- Bundle the exact SHA-pinned Chinese r29 model in the extension, prepare it offline,
  and protect it from deletion in both UI and service. English needs no download.
- Open directly into searchable language rows, with downloaded-language management
  and translation verification actions; omit the readiness overview and internal-model notices.
- Show proxy requirements, real byte percentages when available, connection,
  network waiting, verification and failure states. Preserve search/focus during progress.
- Keep preview.4 native mappings, AM++ 1017/1012 glass compatibility, Chinese display
  toggle placement and existing settings keys. Main app changes remain deferred.
