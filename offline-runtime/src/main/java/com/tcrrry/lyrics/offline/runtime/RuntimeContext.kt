package com.tcrrry.lyrics.offline.runtime

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import android.content.res.loader.ResourcesLoader
import android.content.res.loader.ResourcesProvider
import android.os.ParcelFileDescriptor
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

/** Retain host identity for system services; all SDK-owned storage stays inside plugin data. */
internal class RuntimeContext(base: Context, private val root: File, payload: File) : ContextWrapper(base.applicationContext) {
    private val preferences = ConcurrentHashMap<String, SharedPreferences>()
    @Suppress("DEPRECATION")
    private val runtimeResources by lazy {
        val provider = ParcelFileDescriptor.open(payload, ParcelFileDescriptor.MODE_READ_ONLY).use {
            ResourcesProvider.loadFromApk(it)
        }
        val loader = ResourcesLoader().apply { addProvider(provider) }
        Resources(Resources.getSystem().assets, base.resources.displayMetrics, Configuration(base.resources.configuration)).apply {
            addLoaders(loader)
        }
    }
    private fun child(name: String): File {
        require(name.matches(Regex("[A-Za-z0-9_.-]{1,180}")) && name != "." && name != "..")
        return File(root, name)
    }
    private fun directory(name: String) = child(name).apply { check(mkdirs() || isDirectory) }
    override fun getApplicationContext(): Context = this
    override fun getResources() = runtimeResources
    override fun getAssets() = runtimeResources.assets
    override fun getClassLoader(): ClassLoader = javaClass.classLoader!!
    override fun getFilesDir() = directory("files")
    override fun getNoBackupFilesDir() = directory("no_backup")
    override fun getCacheDir() = directory("cache")
    override fun getCodeCacheDir() = directory("code_cache")
    override fun getDataDir() = root
    override fun getDir(name: String, mode: Int) = directory("app_$name")
    override fun createDeviceProtectedStorageContext(): Context = this
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = preferences.computeIfAbsent(name) {
        require(name.matches(Regex("[A-Za-z0-9_.-]{1,180}")) && name != "." && name != "..")
        RuntimePreferences(File(directory("preferences"), "$name.json"))
    }
    override fun deleteSharedPreferences(name: String): Boolean {
        getSharedPreferences(name, 0).edit().clear().commit()
        preferences.remove(name)
        return File(directory("preferences"), "$name.json").delete()
    }
    override fun getDatabasePath(name: String): File {
        require(name.matches(Regex("[A-Za-z0-9_.-]{1,180}")) && name != "." && name != "..")
        return File(directory("databases"), name)
    }
    override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
        SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
    override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, handler: DatabaseErrorHandler?): SQLiteDatabase =
        SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, handler)
    override fun deleteDatabase(name: String) = SQLiteDatabase.deleteDatabase(getDatabasePath(name))
    override fun databaseList(): Array<String> = directory("databases").list() ?: emptyArray()
    override fun getFileStreamPath(name: String): File {
        require(name.matches(Regex("[A-Za-z0-9_.-]{1,180}")) && name != "." && name != "..")
        return File(filesDir, name)
    }
    override fun openFileInput(name: String) = FileInputStream(getFileStreamPath(name))
    override fun openFileOutput(name: String, mode: Int) = FileOutputStream(getFileStreamPath(name), mode and MODE_APPEND != 0)
    override fun deleteFile(name: String) = getFileStreamPath(name).delete()
    override fun fileList(): Array<String> = filesDir.list() ?: emptyArray()
}
