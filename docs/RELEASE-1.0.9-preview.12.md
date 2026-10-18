# 1.0.9-preview.12

Enable Hangul in spatial pronunciation annotations, retaining Japanese matching
and the accepted Cantonese per-character layout. Match joined provider romanization
against Hangul initial/vowel/final sounds using bounded dynamic programming.
Prefer explicit provider separators and valid liaison boundaries; reject unmatched
or ambiguous readings instead of inventing syllable times. Keep the provider's
letters and casing, omitting separator hyphens at annotation edges.

Each Hangul block gets an independent reading column inside original timed views.
Mixed Latin words stay single lexical groups and do not acquire per-letter spacing.
Screenshot regression: 환한 빛 조명이 / hwanhan bich jomyeong-i.
Verify connected words, liaison, mixed English, viewport bounds and restoration;
retain original native text, view indices, source timestamps and translation.
Translation extension remains 1.0.0-preview.2. Device host playback still needs
manual import and actual usage validation.
