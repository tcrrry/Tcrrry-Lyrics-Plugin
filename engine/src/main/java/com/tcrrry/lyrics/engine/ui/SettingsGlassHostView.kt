package com.tcrrry.lyrics.engine.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.view.View
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.amenhancer.plugin.api.PluginContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.net.JarURLConnection

/** Own Compose owners keep the settings glass independent of host AndroidX internals. */
internal class SettingsGlassHostView(context: Context) : FrameLayout(context) {
    private var owner = Owner()
    private var scope: CoroutineScope? = null
    private var recomposer: Recomposer? = null
    val compose = ComposeView(context).apply {
        id = View.generateViewId()
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
    }

    init {
        setViewTreeLifecycleOwner(owner)
        setViewTreeSavedStateRegistryOwner(owner)
        addView(compose, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun content(block: @Composable () -> Unit) { compose.setContent(block) }

    override fun onAttachedToWindow() {
        if (owner.lifecycle.currentState == Lifecycle.State.DESTROYED) {
            owner = Owner()
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
        }
        owner.registry.currentState = Lifecycle.State.RESUMED
        val dispatcher = AndroidUiDispatcher.CurrentThread
        val active = Recomposer(dispatcher).also { recomposer = it }
        scope = CoroutineScope(dispatcher + SupervisorJob()).also { it.launch { active.runRecomposeAndApplyChanges() } }
        compose.setParentCompositionContext(active)
        super.onAttachedToWindow()
    }

    override fun onDetachedFromWindow() {
        compose.disposeComposition()
        recomposer?.cancel()
        scope?.cancel()
        recomposer = null
        scope = null
        owner.registry.currentState = Lifecycle.State.DESTROYED
        super.onDetachedFromWindow()
    }

    private class Owner : LifecycleOwner, SavedStateRegistryOwner {
        val registry = LifecycleRegistry(this)
        private val controller = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry
        init {
            controller.performAttach()
            controller.performRestore(null)
            registry.currentState = Lifecycle.State.CREATED
        }
    }
}

internal fun settingsGlassModuleNames(hostPackage: String): List<String> =
    if (hostPackage == "com.tcrrry.ampplyrics.coexist") listOf("dev.amenhancer.module.coexist")
    else listOf("dev.amenhancer.module", "dev.amenhancer.module.debug")

internal fun settingsGlassContext(activity: Context): Context {
    val names = settingsGlassModuleNames(activity.packageName)
    // Prefer the loaded module archive: an installed ordinary module may be a different build.
    val info = PluginContext::class.java.classLoader!!.getResources("AndroidManifest.xml").asSequence()
        .firstNotNullOfOrNull { resource ->
            runCatching {
                val jar = resource.openConnection() as? JarURLConnection ?: return@runCatching null
                val file = File(jar.jarFileURL.toURI())
                activity.packageManager.getPackageArchiveInfo(file.path, 0)?.applicationInfo
                    ?.takeIf { it.packageName in names }?.apply {
                        sourceDir = file.path
                        publicSourceDir = file.path
                    }
            }.getOrNull()
        } ?: names.firstNotNullOfOrNull { name ->
            runCatching { activity.packageManager.getApplicationInfo(name, 0) }.getOrNull()
        } ?: error("AM++ glass resources unavailable")
    val resources = activity.packageManager.getResourcesForApplication(info)
    @Suppress("DEPRECATION")
    val isolated = Resources(resources.assets, activity.resources.displayMetrics, Configuration(activity.resources.configuration))
    val theme = isolated.newTheme().apply {
        applyStyle(info.theme.takeIf { it != 0 } ?: android.R.style.Theme_Material_Light_NoActionBar, true)
    }
    return object : ContextWrapper(activity) {
        override fun getResources() = isolated
        override fun getAssets() = isolated.assets
        override fun getTheme() = theme
        override fun getClassLoader() = SettingsGlassHostView::class.java.classLoader!!
    }
}
