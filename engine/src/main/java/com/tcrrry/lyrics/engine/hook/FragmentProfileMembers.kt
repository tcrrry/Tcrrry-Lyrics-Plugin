// Adapted from Zennmn/AM-plus-plus d781fc260198f26a61f3d8631a19527c6bf17594 (GPL-3.0).
package com.tcrrry.lyrics.engine.hook

import org.json.JSONObject
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Resolve the complete declared inventory once, before any hook is registered. */
internal class FragmentProfileMembers(
    private val loader: ClassLoader,
    private val names: JSONObject,
    private val section: String,
    required: FragmentMemberRequirements,
) {
    private val types = HashMap<String, Class<*>>()
    private val methods = LinkedHashMap<String, Method>()
    private val fields = LinkedHashMap<String, Field>()
    private val constructors = LinkedHashMap<String, Constructor<*>>()

    init {
        val errors = ArrayList<String>()
        fun resolve(group: String, roles: Set<String>, action: (String, JSONObject) -> Unit) {
            val declared = names.optJSONObject(group)
            roles.filter { declared?.has(it) != true }.forEach { errors += "$section.$group.$it: missing required role" }
            declared?.keys()?.forEach { role ->
                runCatching { action(role, declared.getJSONObject(role)) }.onFailure {
                    errors += "$section.$group.$role: ${it.message ?: it.javaClass.simpleName}"
                }
            }
        }
        resolve("methods", required.methods) { role, c ->
            val parameters = c.getJSONArray("parameters")
            methods[role] = type(c.getString("owner")).getDeclaredMethod(c.getString("name"),
                *Array(parameters.length()) { type(parameters.getString(it)) }).apply {
                check(returnType == type(c.getString("returns")) &&
                    Modifier.isStatic(modifiers) == c.getBoolean("static") && !isBridge && !isSynthetic &&
                    (c.optBoolean("invocationOnly") || !Modifier.isAbstract(modifiers))) { "descriptor mismatch: $this" }
                isAccessible = true
            }
        }
        resolve("fields", required.fields) { role, c ->
            fields[role] = type(c.getString("owner")).getDeclaredField(c.getString("name")).apply {
                check(type == type(c.getString("type")) && !Modifier.isStatic(modifiers)) { "descriptor mismatch: $this" }
                isAccessible = true
            }
        }
        resolve("constructors", required.constructors) { role, c ->
            val parameters = c.getJSONArray("parameters")
            constructors[role] = type(c.getString("owner")).getDeclaredConstructor(
                *Array(parameters.length()) { type(parameters.getString(it)) }).apply { isAccessible = true }
        }
        check(errors.isEmpty()) { errors.joinToString("\n") }
    }

    fun type(name: String): Class<*> = types.getOrPut(name) {
        when (name) {
            "void" -> Void.TYPE
            "boolean" -> java.lang.Boolean.TYPE
            "int" -> Integer.TYPE
            "float" -> java.lang.Float.TYPE
            "long" -> java.lang.Long.TYPE
            else -> if (name.startsWith("[")) Class.forName(name, false, loader) else loader.loadClass(name)
        }
    }
    fun method(role: String): Method = checkNotNull(methods[role]) { "$section.methods.$role: undeclared role" }
    fun field(role: String): Field = checkNotNull(fields[role]) { "$section.fields.$role: undeclared role" }
    fun constructor(role: String): Constructor<*> = checkNotNull(constructors[role]) { "$section.constructors.$role: undeclared role" }
}

internal data class FragmentMemberRequirements(
    val methods: Set<String> = emptySet(),
    val fields: Set<String> = emptySet(),
    val constructors: Set<String> = emptySet(),
)
