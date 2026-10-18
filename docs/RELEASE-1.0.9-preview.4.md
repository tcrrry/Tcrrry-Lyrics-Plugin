# 1.0.9-preview.4

- Adapt the settings selection strips to AM++ embedded 1017. Its Apple Music
  build remains 7.0.0-beta / 1609; native lyric mappings remain unchanged.
- Resolve two exact public host Compose contracts: 1012's original tab functions
  and 1017's extended tabs/capsule-shape functions. Use the host's own rendering,
  with collapse disabled for settings strips and the host's default tab shape.
  Do not bundle host classes or remove native version gates.
- Move 繁体转简体 below supplementary translation settings, immediately before
  离线注音. Preserve its saved key, default, conversion behavior and lightweight toggle.
- Retain preview.3 features and merged playback controls. Disable the separate
  playback-intent plugin when using the merged controls.
- Static/module ABI checks, automated tests and ZIP import are separate from
  physical-device playback, animation and visual acceptance. No host install,
  restart, main/coexist package update or public release is part of this build.
