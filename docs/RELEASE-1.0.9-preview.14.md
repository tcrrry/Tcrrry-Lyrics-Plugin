# 1.0.9-preview.14

Korean and Cantonese annotations follow native character positions and wrapping.
They no longer add letter spacing, column padding, forced breaks or disable
native flex shrinking. Use one fitted pronunciation font per lyric row, retaining
the original glyph font, child indices and source timestamps. Internal TextView
line wraps are mapped to their own baselines; reserve vertical reading bands only.
If annotation cannot be displayed, restore all owned geometry before showing the
ordinary reading. Japanese column fitting remains on its previous path.

Preserve literal Latin words within Cantonese lines and align each Han glyph to
its provider syllable. Register existing provider readings directly from managed
TTML, including cache/import paths that skip cross-source enrichment; never treat
Kana/Hangul lines as Cantonese. Do not invent missing sounds or timings.

Simplify mixed Chinese/English bodies under synthetic subtitle language roots,
and untagged Chinese translations, while protecting Japanese/Korean originals,
attributes, line keys and timestamps. Retain original cached TTML for reversibility.
Manual mapping caches now include presentation settings and retry a prepare when
the display mode changes during parsing. Settings refresh can wake manual mappings.

Regression examples are limited to the lines supplied in user screenshots, with
240/320/360 px layouts, grouped timed views, internal wraps and static line views.
Retain new native result submission, pronunciation/original/translation order and
original-only highlighting. Translation extension stays 1.0.0-preview.2. Deliver
only the main SDK ZIP to the Desktop; no phone connection or installation.
