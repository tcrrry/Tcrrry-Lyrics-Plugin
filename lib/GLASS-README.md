# AM++ glass API references

This JAR is a compile-only reference to public classes in
Zennmn/AM-plus-plus commit `75dde33e51c6c724c4697d25f5c7788b3e7a8099`.
They are not shipped as plugin runtime classes.

- `ampp-backdrop-host.jar`: Kyant Backdrop public rendering interface.
- `ampp-liquid-controls-host.jar`: host LiquidButton and catalog rendering interface,
  taken unchanged from Kifranei/AM-plus-plus-media-plugin's SDK v1 references.

SHA-256: `94770edc041a3d3f660be5c873fae7954c998174ae0e2575d843b8b2120ee032`.

Liquid controls SHA-256:
`d478da33f7197ab09435c099e179249ec9409a00b5cb33688040949837d3d27f`.

Settings selection strips call the actual host LiquidBottomTabs / LiquidBottomTab
for sliding selection and spring release. Ordinary pages create no Compose owner:
the plate is one downsampled snapshot, blurred once off the UI thread through
Android HardwareRenderer / RenderEffect and displayed by a native View.
Its wash strength adapts to artwork brightness instead of using an 88% black fill.
Only pages with selection strips create a Compose host, and only those small
strips run live lens effects. Native controls keep whole-View press feedback,
measurements and accessibility. All controls share the processed snapshot; no
host rendering classes ship in DEX.

The source is GPL-3.0 as part of AM++; AndroidLiquidGlass's original reference
is Apache-2.0 (see `assets/licenses/AndroidLiquidGlass.txt`).
