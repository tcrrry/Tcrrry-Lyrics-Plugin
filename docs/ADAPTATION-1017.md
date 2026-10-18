# AM++ embedded 1017

Input: Zennmn/AM-plus-plus, release `embedded-2026.08.10-r1`, asset
`1017-version-com.apple.android.music-npatched.apks` (156046699 bytes).
APKS SHA-256: `795593d041ce8725e18479e83bd79d5809ec2c048a4d33bf9722929bc341d59d`.
Embedded module SHA-256: `c85c2a690184bfdb25ad5f496114b7515ace96ab0558f68a67abf5fc4f9b4ea7`.

The original Apple Music APK is **byte-identical** to the previous 1012 input:
`599972c4296973eff39bb0eb2394c409005da2d2d387a2174c3c1c4ac93cb4e6`.
Its version tuple remains `7.0.0-beta / 1609`. Keep the exact existing native
lyric/playback contracts and the separate 1606 mappings.

The embedded module changed public Compose interfaces:

- `LiquidBottomTabs-AKObu6Q` became `LiquidBottomTabs-g4fP9js`, adding collapse,
  compact-content, expand callbacks, rebound factors and a rendering flag.
- `LiquidBottomTab` added a `Shape` parameter before its content lambda.

`HostLiquidControls.kt` resolves only the two verified, complete public/static
signatures. It passes explicit non-collapsing settings values to the new tabs,
and requests the host's default capsule shape through its Compose default mask.
The old ABI remains available for previous embedded modules. Host rendering
classes remain compile-only and are excluded from the plugin DEX.

Reproduce static checks using `scripts/verify-sdk-host.py` (42 SDK methods),
`scripts/verify-native-lyrics-host.py` (143 native extension contracts), the main
project's `scripts/verify-host-profile.py --glass` (412 profile checks), and
`scripts/verify-plugin-module.py MODULE PLUGIN` (compiled API references and both
reflective Liquid signatures). The last script accepts a compiler APK or final ZIP.
NPatch overlaps nested ZIP entries; extract its original APK with 7-Zip rather
than treating Python's overlap rejection as archive corruption.

These checks establish static compatibility. Physical-device settings rendering,
animation, playback and lyric acceptance still need an imported plugin and live use.
