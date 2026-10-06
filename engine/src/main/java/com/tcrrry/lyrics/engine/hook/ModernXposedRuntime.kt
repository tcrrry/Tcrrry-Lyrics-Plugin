package com.tcrrry.lyrics.engine.hook

import android.util.Log
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Executable
import java.lang.reflect.Method

abstract class ModernMethodHook {
    open fun beforeHookedMethod(param: MethodHookParam) = Unit
    open fun afterHookedMethod(param: MethodHookParam) = Unit

    class MethodHookParam internal constructor(
        val method: Executable,
        val thisObject: Any?,
        val args: Array<Any?>,
    ) {
        val extras = mutableMapOf<String, Any?>()
        private var returnEarly = false
        private var resultValue: Any? = null

        var result: Any?
            get() = resultValue
            set(value) {
                resultValue = value
                throwable = null
                returnEarly = true
            }

        private var failure: Throwable? = null
        var throwable: Throwable?
            get() = failure
            set(value) {
                failure = value
                if (value != null) returnEarly = true
            }

        internal fun shouldReturnEarly(): Boolean = returnEarly
        internal fun setInvocationResult(value: Any?) {
            resultValue = value
            returnEarly = false
        }
        internal fun setInvocationThrowable(value: Throwable?) { failure=value }
    }
}

object ModernXposedRuntime {
    private val ownedScopes = java.util.Collections.synchronizedSet(java.util.Collections.newSetFromMap(java.util.WeakHashMap<HookRegistrationScope,Boolean>()))

    fun activeModule(): XposedModule? = null
    fun hookMethod(executable: Executable, callback: ModernMethodHook, scope: HookRegistrationScope? = null): Boolean {
        val calls = ThreadLocal.withInitial { java.util.ArrayDeque<ModernMethodHook.MethodHookParam>() }
        val runtime = com.tcrrry.lyrics.plugin.PluginLifetime.context
        if (scope != null && ownedScopes.add(scope)) runtime.onClose { scope.close() }
        val registration = runtime.hooks.hook(executable, false, object : dev.amenhancer.plugin.api.PluginHook {
            override fun before(call: dev.amenhancer.plugin.api.PluginCall) {
                if (scope != null && !scope.isActive) return
                val param = ModernMethodHook.MethodHookParam(executable, call.receiver, call.arguments.copyOf())
                calls.get().addLast(param)
                try { callback.beforeHookedMethod(param) } catch (error: Throwable) { calls.get().removeLast(); throw error }
                param.args.copyInto(call.arguments)
                if (param.shouldReturnEarly()) {
                    param.throwable?.let(call::throwException) ?: call.returnResult(param.result)
                }
            }
            override fun after(call: dev.amenhancer.plugin.api.PluginCall) {
                val stack=calls.get()
                if (stack.isEmpty()) return
                val param=stack.removeLast()
                if (scope != null && !scope.isActive) return
                param.setInvocationResult(call.result)
                param.setInvocationThrowable(call.throwable)
                param.args.indices.forEach { param.args[it]=call.arguments[it] }
                callback.afterHookedMethod(param)
                param.args.copyInto(call.arguments)
                if (param.shouldReturnEarly()) {
                    param.throwable?.let(call::throwException) ?: call.returnResult(param.result)
                }
            }
        })
        scope?.onClose { registration.close() }
        runtime.onClose { calls.remove() }
        return true
    }

    fun hookAllMethods(type: Class<*>, name: String, callback: ModernMethodHook, scope: HookRegistrationScope? = null): Set<Executable> =
        type.declaredMethods
            .filter { it.name == name }
            .onEach { hookMethod(it, callback, scope) }
            .toSet()

    fun callMethod(receiver: Any, name: String, vararg args: Any?): Any? {
        val method = findCompatibleMethod(receiver.javaClass, name, args)
            ?: throw NoSuchMethodException("${receiver.javaClass.name}#$name(${args.size})")
        method.isAccessible = true
        return method.invoke(receiver, *args)
    }

    fun log(message: String, error: Throwable? = null) {
        com.tcrrry.lyrics.plugin.PluginLifetime.context.log(message, error)
    }

    private fun findCompatibleMethod(type: Class<*>, name: String, args: Array<out Any?>): Method? {
        var current: Class<*>? = type
        while (current != null) {
            current.declaredMethods.firstOrNull { method ->
                method.name == name &&
                    method.parameterTypes.size == args.size &&
                    method.parameterTypes.indices.all { index -> compatible(method.parameterTypes[index], args[index]) }
            }?.let { return it }
            current = current.superclass
        }
        return null
    }

    private fun compatible(parameter: Class<*>, value: Any?): Boolean {
        if (value == null) return !parameter.isPrimitive
        val boxed = when (parameter) {
            java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
            java.lang.Byte.TYPE -> java.lang.Byte::class.java
            java.lang.Character.TYPE -> java.lang.Character::class.java
            java.lang.Short.TYPE -> java.lang.Short::class.java
            java.lang.Integer.TYPE -> java.lang.Integer::class.java
            java.lang.Long.TYPE -> java.lang.Long::class.java
            java.lang.Float.TYPE -> java.lang.Float::class.java
            java.lang.Double.TYPE -> java.lang.Double::class.java
            else -> parameter
        }
        return boxed.isInstance(value)
    }
}
