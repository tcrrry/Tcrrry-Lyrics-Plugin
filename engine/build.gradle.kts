plugins { id("com.android.application"); id("org.jetbrains.kotlin.plugin.compose") }
val pluginManifest = groovy.json.JsonSlurper().parse(rootProject.file("plugin.json")) as Map<*, *>
android {
 namespace = "com.tcrrry.lyrics.engine"
 compileSdk = 37
 defaultConfig { applicationId = "com.tcrrry.lyrics.plugin.compiler"; minSdk = 26; targetSdk = 37; versionCode = 4; versionName = "1.0.3"; buildConfigField("String","HOST_PACKAGE","\"com.apple.android.music\"") }
 buildFeatures { buildConfig = true; compose = true }
 defaultConfig { buildConfigField("String", "PLUGIN_VERSION", "\"${pluginManifest["versionName"]}\"") }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 packaging { resources { merges += listOf("META-INF/LICENSE.md", "META-INF/NOTICE.md", "META-INF/CONTRIBUTORS.md") } }
}
val glassHost = files(rootProject.file("lib/ampp-backdrop-host.jar"), rootProject.file("lib/ampp-liquid-controls-host.jar"))
val glassRendering = listOf(
 "org.jetbrains.compose.foundation:foundation:1.12.0",
 "org.jetbrains.compose.ui:ui:1.12.0",
 "org.jetbrains.compose.ui:ui-graphics:1.12.0",
 "io.github.kyant0:shapes:1.2.1",
 "androidx.lifecycle:lifecycle-runtime:2.9.4",
 "androidx.savedstate:savedstate:1.3.3",
 "org.jetbrains:annotations:26.1.0",
)
dependencies {
 compileOnly(files(rootProject.file("lib/ampp-plugin-api-v1.jar")))
 compileOnly(glassHost)
 glassRendering.forEach { compileOnly(it); testImplementation(it) }
 compileOnly("io.github.libxposed:api:102.0.0")
 compileOnly("org.luckypray:dexkit:2.2.0")
 implementation("org.jetbrains.kotlin:kotlin-stdlib:2.4.10")
 implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
 implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
 implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
 implementation("com.atilika.kuromoji:kuromoji-ipadic:0.9.0")
 implementation("androidx.recyclerview:recyclerview:1.4.0")
 testImplementation("androidx.constraintlayout:constraintlayout:2.2.1")
 testImplementation("com.google.android.flexbox:flexbox:3.0.0")
 testImplementation("junit:junit:4.13.2")
 testImplementation("org.robolectric:robolectric:4.14.1")
 testImplementation("org.json:json:20240303")
 testImplementation(files(rootProject.file("lib/ampp-plugin-api-v1.jar")))
 testImplementation(glassHost)
}
tasks.withType<Test>().configureEach {
    providers.gradleProperty("fullPluginFixture").orNull?.let { systemProperty("fullPluginFixture", it); inputs.file(it) }
}
