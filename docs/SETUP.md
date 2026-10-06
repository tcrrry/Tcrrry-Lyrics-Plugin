# Build environment

JDK17, Android SDK37/build-tools37, Python3, wrapper Gradle9.7.1. No Kotlin/Gradle
installation is required beyond the wrapper. `ANDROID_HOME` selects the SDK;
`local.properties` is local-only. Run `./gradlew :engine:testDebugUnitTest
:offline:testDebugUnitTest :engine:assembleRelease :offline:assembleDebug` to
validate an environment, then `python3 scripts/package.py --offline-variant debug`
for development. Production uses the independent companion signing key.

In the managed cloud used for this migration:

```sh
export JAVA_HOME=/workspace/toolchains/jdk-17.0.16+8
export ANDROID_HOME=/workspace/android-sdk
export GRADLE_USER_HOME=/workspace/.gradle
export ANDROID_USER_HOME=/workspace/.android
export JAVA_TOOL_OPTIONS='-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts'
```

Java/Gradle use the session proxy configured in the user Gradle properties and
system CA trust. Do not copy credentials or proxy authentication into this repo.
Signing material lives outside the checkout and CI uses repository secrets;
compiler APKs, caches, local SDK paths and signing files are excluded from source ZIP.

The signed workflow is provided but its three `PLUGIN_OFFLINE_*` secrets require
repository-owner configuration: the current integration cannot read/write Actions
secrets. The local companion is independently signed and verified; its key is kept
outside this source checkout in `/workspace/plugin-private-signing/`. Preserve that
key for future companion updates; do not commit it or add it to a release/source ZIP.
