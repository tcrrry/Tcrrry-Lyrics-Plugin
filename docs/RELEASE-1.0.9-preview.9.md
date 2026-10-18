# 1.0.9-preview.9

Includes preview.8 Cantonese provider/character alignment and preservation fixes.
Generated offline Cantonese now uses a stable QQ-style sing-along display:
`bat1 jiu3 gaa2 cit3 ngo5` becomes `ba yiu ga ci o`.
This is an approximate display convention, not standard Jyutping or a claim
that QQ uses a single consistent romanization. Final checked consonants and
long-vowel spelling are simplified, so it is intended for following a song,
not learning accurate spoken Cantonese. Dictionary and phrase readings retain
standard Jyutping internally. Existing QQ/provider reading text is untouched.
Generated display rows are registered as Cantonese before ruby/timing alignment;
no inferred syllable timestamps or changes to original lyric text are introduced.
Translation extension remains 1.0.0-preview.2.
