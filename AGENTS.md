# Independent lyrics plugin

- Baseline is Tcrrry's AMPP Lyrics **v1.5 formal release**, not an early test or 0.1 prototype.
- This is a separate SDK v1 project. Do not modify, replace or publish the four existing Apple Music artifacts as part of a plugin change.
- All own controls belong in Tcrrry lyrics settings / the plugin's own settings session. Preserve saved keys when moving controls.
- Compile against `lib/ampp-plugin-api-v1.jar`; never bundle SDK classes or call libxposed to install plugin hooks. Register every hook through PluginContext.hooks.
- Plugin resources are ordinary ZIP assets, configuration/cache use SDK directories, and background/UI work must honor stop. Do not read host preferences or secret files.
- Preserve “查看当前匹配信息” and source provenance in production. Do not add diagnostic-copy/test-only UI.
- Test changes to timings, source ranking, cross-source fusion, SDK lifetime and settings. Package real DEX; verify import with the author's PluginStore before release.
- User authorized splitting pure offline translation into an optional SDK plugin ZIP on 2026-10-18, with Chinese bundled and protected. Keep the lyrics ZIP free of the runtime/model payload. Preserve the companion APK and previous trial artifacts. The outer deliverables must remain real SDK ZIPs; never install compiler payloads or claim desktop checks verify physical UI.
- User authorized integrating the standalone playback-intent preview.3 into the lyrics plugin for preview.2. Preserve its 1609-only contracts, memory-only radio/autoplay repeat, lifecycle cleanup, and shared exclusive resource; document disabling the separate playback plugin when enabling the merged build.
