plugins { id("com.android.application") version "9.3.2" apply false; id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false }
tasks.register<Exec>("packagePlugin") {
    dependsOn(":engine:assembleRelease", ":offline:assembleRelease")
    workingDir(rootDir)
    commandLine("python3", "scripts/package.py")
}
