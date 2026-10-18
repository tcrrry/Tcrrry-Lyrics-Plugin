# 1.0.9-preview.3

- Add the default-off simplified-Chinese display toggle to Translation and
  Pronunciation, using the existing lightweight binary-control style.
- Convert a temporary render copy immediately before replacement parsing.
  Source/cache documents remain original, so disabling restores traditional
  text. No mapping/ranking/translation-service change or separate language pack.
- Android's offline Traditional-Simplified ICU converter handles Chinese
  primary rows and Chinese subtitles. Japanese originals are preserved.
- Convert complete row text for context, preserving text-node lengths and all
  word/line timings, agent IDs and other attributes. Malformed documents fail
  open to the unchanged original. Keep preview.2 markup fixes.
- Standalone lyrics plugin only. No main/coexist package or formal release.
