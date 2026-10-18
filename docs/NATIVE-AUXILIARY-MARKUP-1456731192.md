# Native auxiliary markup regression

Reported on 2026-10-17, formal plugin 1.0.8, Apple Music 1609.
Device: vivo V2359A. Track ID: 1456731192. Source: APPLE_NATIVE.

The screenshot shows correct primary karaoke text, followed by visible serialized
TTML span timing/namespace attributes in an auxiliary row. This is not a lyric
matching error. Device logs confirmed the native source and native parse path;
the private raw native document could not be extracted from this non-debuggable
host. Do not claim the exact server response has been verified.

The old cleanup recognized angle-bracketed and at most doubly escaped timed
spans, and its native inflation protection was limited to managed repair paths.
The screenshot also contains a delimiter-stripped form with begin/end and the
TTML namespace. Cleanup now covers these forms and deeper entity layers. The
native karaoke subtitle inflation entry also cleans unmanaged native rows.

Only auxiliary display/plain text is cleaned. Never strip or retime the primary
TTML body or delete a whole lyric line because it contains punctuation. Ordinary
HTML styling, literal comparison signs and non-timed text remain unchanged.
Unrecoverable recognizable timed markup yields no auxiliary text instead of XML.

Regression coverage: nested/namespaced spans, attribute order, repeated entity
encoding, delimiter loss, ordinary markup/entities, primary-body preservation,
and the unmanaged native subtitle inflation hook. These are generic fixtures,
not title- or ID-specific rules. Physical acceptance still requires importing the
preview and reopening the affected lyrics.

The same preview uses an inline host for the translation mode liquid selector,
so its glass/labels scroll in the same Android subtree. Binary controls remain
lightweight. Offline source language is automatic even with a previously saved
manual override; language-pack management and its search remain available.

## Preview.2: device reproduction and producer fix

Preview.1 still failed after import/restart. A read-only UiAutomation snapshot
captured the actual native auxiliary TextView (no app restart/installation):
the string contained adjacent `/spanspan begin=...` tokens and a literal
`&#39;` entity. Pure Chinese lines were unaffected; mixed English/Chinese rows
were the trigger reported by the user. This is a rare document shape, not a
reason to special-case all mixed-language songs.

Two tests failed before the new fix:

1. `TtmlSubtitleTrack.cleanChineseSubtitles` read a translation entry's inner
   XML as text when rebuilding a Chinese subtitle track. `attach` then escaped
   the span structure, making tags into visible text. `needsChineseTranslation`
   admits mixed-language rows, explaining their different behavior. Extract
   plain text nodes before rebuilding; never change the timed primary body.
2. The real captured `/spanspan` string defeated word-boundary matching. Split
   bare spans by their strict numeric timing/TTML namespace headers and remove
   trailing close tokens, preserving actual lyric text and whitespace.

Original host 1609 `A.U` was also disassembled locally: it forwards the supplied
CharSequence directly to PrecomputedText/TextView; it does not call Html.fromHtml.
The test fixture had incorrectly simulated HTML decoding, hiding the unnecessary
HTML encoding added by preview.1. Correct the fixture and supply plain cleaned
text only for recognizable timed markup. Ordinary native strings remain intact.

The original private raw document is still unavailable. The producer regression
is proven with a structural fixture and the final cleaner with exact device text;
do not describe the synthetic fixture as Apple's captured server response.
