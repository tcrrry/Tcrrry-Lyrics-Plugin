# 1.0.9-preview.11

Preserve one reading unit per Han character for Cantonese/Chinese provider tracks
instead of expanding units to native multi-character timed-word boundaries and
concatenating their readings. Reversible layout reserves internal letter spacing
and nested glyph padding while keeping native text, view indices and timestamps
intact. Each syllable is drawn over its own glyph, with one unchanged reading font
size, native per-word animation transforms and existing gradient-bound refresh.
Existing Japanese word/contracted-kana fitting remains unchanged.

Tests cover multi-character TextViews and nested native word groups, wrapping,
280-360dp width changes, animation offsets and full spacing restoration. A rendered
regression screenshot checks the separated syllables. Native playback after
manual plugin import remains a separate verification step.
Translation extension remains 1.0.0-preview.2.
