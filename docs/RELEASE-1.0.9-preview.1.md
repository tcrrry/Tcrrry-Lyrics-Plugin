# 1.0.9-preview.1

- Clean serialized timed TTML in native auxiliary rows, including escaped,
  namespaced and delimiter-stripped fragments. No song-specific rules and no
  changes to primary lyric timing.
- Use an inline liquid-glass translation mode selector that scrolls with its
  content; retain lightweight binary controls and native fallback.
- Remove manual source-language selection. Offline translation always detects
  language; saved overrides no longer affect translation or cache identity.
- Standalone plugin preview only. Main-project/coexist binaries and published
  releases are unchanged. Real-device acceptance requires user import.
