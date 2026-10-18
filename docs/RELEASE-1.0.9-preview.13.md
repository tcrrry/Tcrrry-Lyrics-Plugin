# 1.0.9-preview.13

For the exact Apple Music 1609 host used in AM++ 1017, select and publish lyrics
through the current native ViewModel result container. Build its time-to-lyrics
index before notifying the native observer; leave Fragment installation to Apple
Music. Background readiness, source switching and manual mappings use this one
channel. Retain request generations, native postValue coalescing, stale result
rejection, owner recovery and teardown. Failed writes reconcile actual stored
state without issuing a second rollback write.

No changes to pronunciation/original/translation order, auxiliary dim styles,
original-only word highlighting, native glyph/timing indices, Japanese ruby,
Korean Hangul-block alignment or Cantonese per-character spelling. Keep existing
source preferences, translation enrichment and simplified display. Only audited
1609 enables this channel; older profiles keep their existing adapter. The new
path does not invoke Fragment installer replay, page timing repair or metadata
replay. A missing declared contract degrades to native lyrics.

Translation extension stays 1.0.0-preview.2. Deliver the SDK ZIP to the PC Desktop;
do not connect to, install on, or restart the user's phone. Automated/static
validation does not establish physical playback acceptance.

Adapted upstream source and exact revision are recorded in provenance.json and
THIRD_PARTY_NOTICES.md. Previous artifacts are retained.
