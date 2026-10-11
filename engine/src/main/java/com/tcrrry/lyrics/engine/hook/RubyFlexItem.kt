package com.tcrrry.lyrics.engine.hook

/** 1606's R8 output has no FlexItem setters. Verified in the actual fourth DEX:
 * M() reads k (wrapBefore), x() reads c (flexShrink). Do not guess by field order.
 */
internal class RubyFlexItem private constructor(
    val wrap: (Any) -> Boolean,
    val shrink: (Any) -> Float,
    val setWrap: (Any, Boolean) -> Unit,
    val setShrink: (Any, Float) -> Unit,
) {
    companion object {
        fun resolve(type: Class<*>): RubyFlexItem {
            if (type.name == "com.google.android.flexbox.FlexboxLayout\$a") {
                val wrap=type.getDeclaredField("k").apply { require(this.type == Boolean::class.javaPrimitiveType);isAccessible=true }
                val shrink=type.getDeclaredField("c").apply { require(this.type == Float::class.javaPrimitiveType);isAccessible=true }
                require(type.getMethod("M").returnType == Boolean::class.javaPrimitiveType)
                require(type.getMethod("x").returnType == Float::class.javaPrimitiveType)
                return RubyFlexItem(wrap::getBoolean,shrink::getFloat,wrap::setBoolean,shrink::setFloat)
            }
            val getWrap=type.getMethod("isWrapBefore")
            val getShrink=type.getMethod("getFlexShrink")
            val setWrap=type.getMethod("setWrapBefore",Boolean::class.javaPrimitiveType)
            val setShrink=type.getMethod("setFlexShrink",Float::class.javaPrimitiveType)
            return RubyFlexItem({getWrap.invoke(it) as Boolean},{getShrink.invoke(it) as Float},
                {owner,value->setWrap.invoke(owner,value);Unit},{owner,value->setShrink.invoke(owner,value);Unit})
        }
    }
}
