# 1.0.9-preview.8

- Add dictionary-verified Cantonese character alignment before the Japanese
  reading search, for both ruby annotations and emphasized phonetic timing.
  Match every Han code point to one syllable, including supplementary characters;
  retain provider syllables and only borrow actual source word intervals.
- For a song explicitly marked Cantonese, also align provider spelling when
  its syllables cover every Han glyph one-to-one. QQ's current 单车 uses an
  AI-generated non-Jyutping spelling, without tone digits. Keep that spelling
  authoritative; ambiguous, incomplete, mixed-script provider rows stay whole-line.
- Remove trailing tone numbers from generated offline Cantonese readings only.
  Preserve platform reading text, including its existing tone numbers.
- Keep embedded offline Cantonese/Latin mixed lines instead of dropping the row.
- Skip loading a supplementary language dictionary when all its rows already
  contain provider pronunciation. Seed native enrichment with its existing
  readings so enabling offline Cantonese does not regenerate those rows.
- Bump the local-pronunciation marker so old generated TTML is refreshed.

Physical playback after import still requires user validation; background
alignment/device probes do not validate the host row layout by themselves.
