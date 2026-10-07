plugins { id("com.android.application") }
android {
 namespace = "com.tcrrry.lyrics.engine"
 compileSdk = 37
 defaultConfig { applicationId = "com.tcrrry.lyrics.plugin.compiler"; minSdk = 26; targetSdk = 37; versionCode = 2; versionName = "1.0.1"; buildConfigField("String","HOST_PACKAGE","\"com.apple.android.music\"") }
 buildFeatures { buildConfig = true }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 packaging { resources { merges += listOf("META-INF/LICENSE.md", "META-INF/NOTICE.md", "META-INF/CONTRIBUTORS.md") } }
}
dependencies {
 compileOnly(files(rootProject.file("lib/ampp-plugin-api-v1.jar")))
 compileOnly("io.github.libxposed:api:102.0.0")
 compileOnly("org.luckypray:dexkit:2.2.0")
 implementation("org.jetbrains.kotlin:kotlin-stdlib:2.4.10")
 implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
 implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
 implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
 implementation("com.atilika.kuromoji:kuromoji-ipadic:0.9.0")
 implementation("androidx.recyclerview:recyclerview:1.4.0")
 testImplementation("junit:junit:4.13.2")
 testImplementation("org.robolectric:robolectric:4.14.1")
 testImplementation("org.json:json:20240303")
 testImplementation(files(rootProject.file("lib/ampp-plugin-api-v1.jar")))
}
tasks.withType<Test>().configureEach {
    providers.gradleProperty("fullPluginFixture").orNull?.let { systemProperty("fullPluginFixture", it); inputs.file(it) }
}
