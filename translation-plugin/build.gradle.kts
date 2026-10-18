plugins { id("com.android.application") }
android {
 namespace = "com.tcrrry.translation.plugin"
 compileSdk = 37
 defaultConfig { applicationId = "com.tcrrry.translation.compiler"; minSdk = 31; targetSdk = 37; versionCode = 1; versionName = "1.0.0-preview.1" }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
 compileOnly(files(rootProject.file("lib/ampp-plugin-api-v1.jar")))
 implementation("org.jetbrains.kotlin:kotlin-stdlib:2.4.10")
 testImplementation(files(rootProject.file("lib/ampp-plugin-api-v1.jar")))
 testImplementation("junit:junit:4.13.2")
 testImplementation("org.robolectric:robolectric:4.14.1")
}
