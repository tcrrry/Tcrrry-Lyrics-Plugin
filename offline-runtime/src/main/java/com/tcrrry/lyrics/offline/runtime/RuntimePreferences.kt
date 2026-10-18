package com.tcrrry.lyrics.offline.runtime

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.CopyOnWriteArraySet

/** SDK-owned data, atomic saves, no host shared-preference files. */
class RuntimePreferences(private val file:File):SharedPreferences {
    private val lock=Any()
    private val listeners=CopyOnWriteArraySet<SharedPreferences.OnSharedPreferenceChangeListener>()
    private var values:Map<String,Any> = if(file.isFile) decode(file.readText()) else emptyMap()
    override fun getAll():Map<String,*> =synchronized(lock){values.mapValues { (_,v)->if(v is Set<*>) v.toSet() else v }}
    override fun getString(key:String?,defValue:String?)=synchronized(lock){values[key] as? String ?: defValue}
    override fun getStringSet(key:String?,defValues:MutableSet<String>?):MutableSet<String>?=synchronized(lock){
        @Suppress("UNCHECKED_CAST") (values[key] as? Set<String>)?.toMutableSet() ?: defValues?.toMutableSet()
    }
    override fun getInt(key:String?,defValue:Int)=synchronized(lock){values[key] as? Int ?: defValue}
    override fun getLong(key:String?,defValue:Long)=synchronized(lock){values[key] as? Long ?: defValue}
    override fun getFloat(key:String?,defValue:Float)=synchronized(lock){values[key] as? Float ?: defValue}
    override fun getBoolean(key:String?,defValue:Boolean)=synchronized(lock){values[key] as? Boolean ?: defValue}
    override fun contains(key:String?)=synchronized(lock){key in values}
    override fun registerOnSharedPreferenceChangeListener(listener:SharedPreferences.OnSharedPreferenceChangeListener?) { if(listener!=null)listeners+=listener }
    override fun unregisterOnSharedPreferenceChangeListener(listener:SharedPreferences.OnSharedPreferenceChangeListener?) { if(listener!=null)listeners-=listener }
    override fun edit():SharedPreferences.Editor=Editor()
    private inner class Editor:SharedPreferences.Editor {
        private val changes=linkedMapOf<String,Any?>();private var clear=false
        override fun putString(key:String?,value:String?)=apply { changes[requireNotNull(key)]=value }
        override fun putStringSet(key:String?,values:MutableSet<String>?)=apply { changes[requireNotNull(key)]=values?.toSet() }
        override fun putInt(key:String?,value:Int)=apply { changes[requireNotNull(key)]=value }
        override fun putLong(key:String?,value:Long)=apply { changes[requireNotNull(key)]=value }
        override fun putFloat(key:String?,value:Float)=apply { changes[requireNotNull(key)]=value }
        override fun putBoolean(key:String?,value:Boolean)=apply { changes[requireNotNull(key)]=value }
        override fun remove(key:String?)=apply { changes[requireNotNull(key)]=null }
        override fun clear()=apply { clear=true }
        override fun apply() { if(!commit()) android.util.Log.w("PureOffline", "Offline settings save failed") }
        override fun commit():Boolean {
            val changed=mutableSetOf<String>()
            val success=synchronized(lock) {
                val next=(if(clear)emptyMap() else values).toMutableMap()
                if(clear)changed+=values.keys
                changes.forEach { (key,value)->if(value==null)next.remove(key) else next[key]=value;changed+=key }
                try {
                    check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
                    val temp=File.createTempFile("prefs-",".tmp",file.parentFile)
                    try {
                        FileOutputStream(temp).use { out->out.write(encode(next).toByteArray());out.fd.sync() }
                        Files.move(temp.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE)
                        values=next
                    } finally { temp.delete() }
                    true
                } catch(_:Exception){false}
            }
            if(success) {
                val notify=Runnable { changed.forEach { key->listeners.forEach { it.onSharedPreferenceChanged(this@RuntimePreferences,key) } } }
                if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())notify.run() else android.os.Handler(android.os.Looper.getMainLooper()).post(notify)
            }
            return success
        }
    }
    companion object {
        private fun encode(values:Map<String,Any>):String=JSONObject().apply {
            values.forEach { (key,value)->put(key,JSONObject().apply {
                put("type",when(value){is String->"string";is Int->"int";is Long->"long";is Float->"float";is Boolean->"boolean";is Set<*>->"set";else->error("不支持的设置值")})
                put("value",if(value is Set<*>) JSONArray(value.toList()) else value)
            }) }
        }.toString()
        private fun decode(text:String):Map<String,Any> {
            val root=JSONObject(text)
            return root.keys().asSequence().associateWith { key->val row=root.getJSONObject(key);when(row.getString("type")) {
                "string"->row.getString("value");"int"->row.getInt("value");"long"->row.getLong("value");"float"->row.getDouble("value").toFloat();"boolean"->row.getBoolean("value");"set"->row.getJSONArray("value").let { a->(0 until a.length()).map { a.getString(it) }.toSet() };else->error("设置类型损坏")
            } }
        }
    }
}
