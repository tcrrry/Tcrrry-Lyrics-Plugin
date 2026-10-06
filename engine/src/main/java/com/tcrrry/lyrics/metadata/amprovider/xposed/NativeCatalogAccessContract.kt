package com.tcrrry.lyrics.metadata.amprovider.xposed

import java.lang.reflect.Field
import java.lang.reflect.Modifier

/** Resolves the holder's uncached API without accepting a same-name static or bridge method. */
internal object NativeCatalogAccessContract {
    fun mediaApi(holderClass: Class<*>, getterName: String): Any {
        val companionTypeName = "${holderClass.name}\$Companion"
        val companionField = holderClass.declaredFields.filter { field ->
            Modifier.isStatic(field.modifiers) && field.type.name == companionTypeName
        }.singleOrNull() ?: error("MediaApiRepositoryHolder companion unavailable or ambiguous")
        val companion = requireNotNull(companionField.apply { isAccessible = true }.get(null))
        val getter = companion.javaClass.declaredMethods.filter { method ->
            method.name == getterName && method.parameterCount == 0 &&
                !Modifier.isStatic(method.modifiers) && !Modifier.isAbstract(method.modifiers) &&
                !method.isBridge && !method.isSynthetic &&
                method.returnType != Void.TYPE && !method.returnType.isPrimitive
        }.singleOrNull() ?: error("Apple MediaApi uncached getter unavailable or ambiguous")
        return requireNotNull(getter.apply { isAccessible = true }.invoke(companion)) {
            "Apple MediaApi without HTTP cache unavailable"
        }
    }

    fun storefrontField(mediaApi: Any, fieldName: String): Field {
        val field = generateSequence(mediaApi.javaClass) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .filter { it.name == fieldName }
            .singleOrNull() ?: error("Apple MediaApi storefront field unavailable or ambiguous")
        check(!Modifier.isStatic(field.modifiers) && field.type == String::class.java) {
            "Apple MediaApi storefront field has unexpected contract"
        }
        return field.apply { isAccessible = true }
    }
}
