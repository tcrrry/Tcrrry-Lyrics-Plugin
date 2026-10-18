plugins { id("com.android.application") }
android {
    namespace = "com.tcrrry.lyrics.offline.runtime"
    compileSdk = 37
    defaultConfig { applicationId = "com.tcrrry.lyrics.offline.runtime"; minSdk = 31; targetSdk = 37; versionCode = 1; versionName = "1" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    implementation("com.google.mlkit:translate:17.0.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
