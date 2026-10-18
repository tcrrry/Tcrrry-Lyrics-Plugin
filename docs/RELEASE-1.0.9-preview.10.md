# 1.0.9-preview.10

Fix partial Cantonese pronunciation coverage when Traditional-to-Simplified
presentation changes a row's text before the native auxiliary lookup. Compare
Han text using the same canonical simplified form on both sides, keeping all
source text, provider reading spelling and native timestamps intact. Apply the
same comparison to cross-source platform merging and known provider rows used
for per-character ruby. Register Chinese QQ/NetEase provider readings independently
of the offline Cantonese toggle, preserving provider spelling and original body.
Invalidate old auxiliary cache variants so partial tracks are not reused.
Do not convert kana/Hangul comparison strings; conflicting readings that collapse
to the same Han key still fail closed rather than selecting the wrong reading.

Regression reproduces 2/4 coverage before the fix with the user's screenshot
rows, and checks full-track enrichment -> simplified lookup using an external
QQ track fixture (never packaged). Offline display remains the user's preferred
QQ-style spelling. Translation extension remains 1.0.0-preview.2.
