package com.tcrrry.lyrics.engine.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.InternalComposeApi
import androidx.compose.runtime.currentComposer
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import com.kyant.backdrop.Backdrop
import java.lang.reflect.Method
import java.lang.reflect.Modifier as JavaModifier

/** Exact public Compose ABIs verified in the 1012 and 1017 embedded modules.
 * Keep host rendering in its own class loader, without shipping host classes.
 */
internal object HostLiquidControls {
    private const val prefix = "com.kyant.backdrop.catalog.components."
    private val loader get() = HostLiquidControls::class.java.classLoader
    private const val f0 = "kotlin.jvm.functions.Function0"
    private const val f1 = "kotlin.jvm.functions.Function1"
    private const val f3 = "kotlin.jvm.functions.Function3"
    private const val composer = "androidx.compose.runtime.Composer"
    private val tabsBase = listOf(f0, f1, "com.kyant.backdrop.Backdrop", "int",
        "androidx.compose.ui.Modifier", "long", f1, f1, "float", "float", "float", f3)
    internal val tabsLegacy = tabsBase + listOf(f3, composer, "int", "int", "int")
    internal val tabs1017 = tabsBase + listOf(f0, "kotlin.jvm.functions.Function2", f0,
        "float", "float", "boolean", f3, composer, "int", "int", "int")
    internal val tabLegacy = listOf("androidx.compose.foundation.layout.RowScope", f0,
        "androidx.compose.ui.Modifier", f3, composer, "int", "int")
    internal val tab1017 = tabLegacy.take(3) + "androidx.compose.ui.graphics.Shape" + tabLegacy.drop(3)

    internal fun matches(method: Method, name: String, parameters: List<String>): Boolean =
        method.name == name && JavaModifier.isStatic(method.modifiers) &&
            JavaModifier.isPublic(method.modifiers) && method.returnType == Void.TYPE &&
            method.parameterTypes.map { it.name } == parameters

    internal fun select(methods: Array<Method>, signatures: List<Pair<String, List<String>>>): Method =
        signatures.firstNotNullOfOrNull { (name, params) -> methods.singleOrNull { matches(it, name, params) } }
            ?: error("Unsupported host Liquid control ABI")

    val tabs: Method by lazy {
        select(Class.forName(prefix + "LiquidBottomTabsKt", false, loader).declaredMethods,
            listOf("LiquidBottomTabs-g4fP9js" to tabs1017, "LiquidBottomTabs-AKObu6Q" to tabsLegacy))
    }
    val tab: Method by lazy {
        select(Class.forName(prefix + "LiquidBottomTabKt", false, loader).declaredMethods,
            listOf("LiquidBottomTab" to tab1017, "LiquidBottomTab" to tabLegacy))
    }
}

@OptIn(InternalComposeApi::class)
@Composable
internal fun HostLiquidBottomTabs(selectedTabIndex: () -> Int, onTabSelected: (Int) -> Unit,
    backdrop: Backdrop, tabsCount: Int, modifier: Modifier, accentOverride: Color,
    panelHeight: Dp, panelBlur: Dp, content: @Composable RowScope.() -> Unit) {
    val method = HostLiquidControls.tabs
    val arguments = mutableListOf<Any?>(selectedTabIndex, onTabSelected, backdrop, tabsCount,
        modifier, accentOverride.value.toLong(), null, { _: Int -> true }, panelHeight.value,
        panelBlur.value, 0f, null)
    if (method.parameterCount == HostLiquidControls.tabs1017.size) {
        arguments.addAll(listOf({ 0f }, null, null, 1f, 1f, false))
    }
    arguments.addAll(listOf(content, currentComposer, 0, 0, 0))
    method.invoke(null, *arguments.toTypedArray())
}

@OptIn(InternalComposeApi::class)
@Composable
internal fun RowScope.HostLiquidBottomTab(onClick: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val method = HostLiquidControls.tab
    val arguments = mutableListOf<Any?>(this, onClick, Modifier)
    val withShape = method.parameterCount == HostLiquidControls.tab1017.size
    if (withShape) arguments.add(null)
    // The new Shape argument uses the host's own default capsule.
    arguments.addAll(listOf(content, currentComposer, 0, if (withShape) 4 else 0))
    method.invoke(null, *arguments.toTypedArray())
}
