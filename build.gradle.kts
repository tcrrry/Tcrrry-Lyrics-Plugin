plugins { id("com.android.application") version "9.3.2" apply false; id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false }
tasks.register<Exec>("packagePlugin") {
    dependsOn(":engine:assembleRelease")
    workingDir(rootDir)
    environment("PYTHONUTF8", "1")
    commandLine(if(System.getProperty("os.name").startsWith("Windows")) "python" else "python3", "scripts/package.py", "--plugin-only")
}
tasks.register<Exec>("packageTranslation") {
    dependsOn(":translation-plugin:assembleRelease", ":offline-runtime:assembleRelease")
    workingDir(rootDir)
    environment("PYTHONUTF8", "1")
    commandLine(if(System.getProperty("os.name").startsWith("Windows")) "python" else "python3", "scripts/package-translation.py")
}
