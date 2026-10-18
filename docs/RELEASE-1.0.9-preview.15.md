# 1.0.9-preview.15

Keep the native pronunciation font size, explicitly removing the preview.14
font-fitting path. Korean/Cantonese first reuse the existing Japanese paired
column packing when native timing boundaries expose complete units. For joined
native words, reversible metric spans reserve individually measured character
columns inside the existing TextView; original characters, native spans, glyph
font, child indices and timestamps stay intact. Literal Latin fragments fit their
actual native boundaries. No glyph views or synthetic timings are created.
Restore owned spans/line bands on clear or safe fallback. Allow native binding
layout to settle before treating an initial frame as a permanent failure.

Improve Korean provider matching for q/x spellings and aspiration/liaison observed
in full-song provider data. Unrelated/missing sounds still fail conservatively.
Matched provider text is preserved; no pronunciation is rewritten.

Recognize mixed Latin/Cantonese auxiliary tracks on Apple-native primary lyrics
and cache replay via auxiliary provenance. New provider evidence invalidates
negative alignment entries; transient negative results expire and do not leave
a row permanently pending. Explicit Japanese roots/ja dictionary provenance and
Kana/Hangul tracks do not enter Cantonese provider recognition.

Regression data is external: 40 QQ Cantonese singing rows and 96 paired Korean
rows from two songs. Full-size layout exercises whole-row, word, character and
fragmented native views at multiple viewport widths. No full-song lyrics fixtures
are bundled or committed. Existing Japanese, display order, original-only
highlighting, simplified display and native result injection remain in place.
Translation extension remains 1.0.0-preview.2. Deliver the main SDK ZIP to Desktop;
no phone access. Automated layout success is distinct from physical acceptance.
