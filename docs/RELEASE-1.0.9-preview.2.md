# 1.0.9-preview.2

- Fix Chinese subtitle rebuilding treating inner timed XML as ordinary text.
  Extract text before serializing the replacement track; primary body/timing
  remains untouched. This addresses the mixed-language row producer path.
- Handle the device-captured adjacent `/spanspan` representation, keeping
  punctuation, spaces and entities readable.
- Match the verified native plain-text subtitle inflater instead of assuming
  it performs HTML decoding. Update the integration fixture accordingly.
- Keep preview.1 settings improvements. Standalone plugin only; no formal
  publication and no main-project/coexist change.
