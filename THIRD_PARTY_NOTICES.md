# Third-party notices

## AM++ and Tcrrry v1.5

This independent plugin derives its lyric engine from AM++ (bbelluga) and Tcrrry's
AMPP Lyrics v1.5. It retains their GPL-3.0 source and copyright notices. The full
GPL is in `LICENSE`. Provenance is in `docs/MIGRATION.md` and `docs/provenance.json`.
The SDK API JAR is compile-only; the author's PluginStore source is test-only.

## Kuromoji IPADIC 0.9.0

`com.atilika.kuromoji:kuromoji-core:0.9.0` and `kuromoji-ipadic:0.9.0`, Apache-2.0.
The vendored `com.tcrrry.lyrics.dictionary.Tokenizer` retains the upstream header;
its resource resolver is adapted to SDK assets. Dictionary/resource licensing
and upstream notices are preserved verbatim in `assets/licenses/kuromoji-LICENSE.md`
and `assets/licenses/kuromoji-NOTICE.md`. Eight dictionary binaries are copied
from the pinned Maven artifact; individual hashes are in `docs/provenance.json`.

## Korean and Cantonese pronunciation

CLDR Korean transform (Unicode license), pinned commit
`5b159148f1390735049e296723f6b847229b23ef`, full license in
`assets/pronunciation/ko/LICENSE`.

Rime Cantonese dictionaries, pinned commit
`259f0e48bba840c3a2e0d117539e96937f3d89bc`, CC BY 4.0, full license in
`assets/pronunciation/yue/LICENSE-CC-BY`. Sources and hashes are recorded in
`assets/pronunciation-modules.json`.

## Kotlin, AndroidX, coroutines and JSON

Kotlin stdlib 2.4.10, kotlinx-coroutines 1.9.0,
kotlinx-serialization-json 1.6.3 and AndroidX RecyclerView 1.4.0 use Apache-2.0.
Dependency notices are retained in the compiler APK and plugin META-INF material.

Libxposed API 102.0.0 and DexKit 2.2.0 (Apache-2.0) are compile-only dependencies
for retained auxiliary source signatures; plugin hooks exclusively use the SDK.

## Optional ML Kit companion

`com.google.mlkit:translate:17.0.3` and its Google dependencies are confined to the
companion APK. Android library notices are packaged by Gradle. Models are downloaded
on user request using Google's ML Kit service; none are redistributed in this ZIP.

## AMLyricBlur

Adapted from `a23bc/amlyricblur`, commit
`3417e217d7692ae742bbae80d2bd51aadffcd59e`.

MIT License

Copyright (c) 2026 a23bc

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
