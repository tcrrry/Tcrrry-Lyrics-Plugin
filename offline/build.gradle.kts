plugins { id("com.android.application") }
android { namespace = "com.tcrrry.lyrics.offline"; compileSdk = 37
 defaultConfig { applicationId = "com.tcrrry.lyrics.offline"; minSdk = 26; targetSdk = 37; versionCode = 3; versionName = "1.0.2" }
 val keyPath = System.getenv("OFFLINE_KEYSTORE")
 if (!keyPath.isNullOrBlank()) {
   signingConfigs { create("pluginRelease") { storeFile=file(keyPath);storePassword=System.getenv("OFFLINE_STORE_PASSWORD");keyAlias=System.getenv("OFFLINE_KEY_ALIAS")?:"tcrrry-plugin-offline";keyPassword=System.getenv("OFFLINE_KEY_PASSWORD") } }
   buildTypes { getByName("release") { signingConfig=signingConfigs.getByName("pluginRelease") } }
 }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies { implementation("com.google.mlkit:translate:17.0.3"); testImplementation("junit:junit:4.13.2"); testImplementation("org.robolectric:robolectric:4.14.1") }
