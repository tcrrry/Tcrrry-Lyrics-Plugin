package dev.kifranei.ampp.media

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.BackdropEffectScope
import java.lang.reflect.Method

/**
 * Version-tolerant `drawBackdrop`.
 *
 * AM++ changed the compiler-mangled `drawBackdrop-<hash>` signature between releases (a `minLayerSize`
 * IntSize parameter was inserted), so a direct call compiled against one host jar throws
 * NoSuchMethodError on another. This resolves the host's `drawBackdrop-*$default` bridge reflectively
 * (14 parameters = older hosts, 15 = newer hosts), passes only backdrop/shape/effects/surface and lets
 * the host apply its own defaults (highlight, shadow, ...; mask bits exclude the receiver). If anything is missing or fails, it degrades to a plain clipped
 * translucent surface instead of throwing into Apple Music.
 */
internal object HostBackdropCompat {
    private class Bridge(val method: Method, val size: Int)

    @Volatile private var resolved = false
    @Volatile private var bridge: Bridge? = null
    @Volatile private var logged = false

    private fun resolve(): Bridge? {
        if (resolved) return bridge
        synchronized(this) {
            if (resolved) return bridge
            bridge = runCatching {
                val owner = Class.forName("com.kyant.backdrop.DrawBackdropModifierKt", false, Backdrop::class.java.classLoader)
                owner.declaredMethods.firstOrNull { m ->
                    val p = m.parameterTypes
                    java.lang.reflect.Modifier.isStatic(m.modifiers) && m.name.startsWith("drawBackdrop-") &&
                        m.name.endsWith("\$default") && p.size in 16..18 &&
                        p[0] == Modifier::class.java && p[1] == Backdrop::class.java &&
                        p[p.size - 2] == Int::class.javaPrimitiveType && p[p.size - 1] == Any::class.java
                }?.let { Bridge(it, it.parameterTypes.size - 2) }
            }.onFailure { MediaRuntime.log("backdrop_compat: resolve failed", it) }.getOrNull()
            if (bridge == null) MediaRuntime.log("backdrop_compat: no usable drawBackdrop host method; using plain surface")
            resolved = true
            return bridge
        }
    }

    /**
     * Kotlin `$default` mask: bit i is the i-th *declared* parameter, the extension receiver (Modifier)
     * is NOT counted. Declared 0..2 are backdrop/shape/effects (explicit), `onDrawSurface` is explicit,
     * everything else (highlight, shadow, innerShadow, layerBlock, clip, exported, minLayerSize,
     * onDrawBehind, onDrawBackdrop, onDrawFront) takes the host default.
     * [paramCount] is the JVM parameter count of the underlying method, [surfaceIndex] the JVM index of onDrawSurface.
     */
    internal fun defaultMask(paramCount: Int, surfaceIndex: Int, hostHighlight: Boolean = true): Int {
        val mask = ((1 shl (paramCount - 1)) - 1) and 0b111.inv() and (1 shl (surfaceIndex - 1)).inv()
        // Declared parameter 3 is `highlight`; leaving its bit clear passes the explicit null = no rim.
        return if (hostHighlight) mask else mask and (1 shl 3).inv()
    }

    fun apply(
        modifier: Modifier,
        backdrop: Backdrop,
        shape: () -> Shape,
        effects: BackdropEffectScope.() -> Unit,
        onDrawSurface: DrawScope.() -> Unit,
        hostHighlight: Boolean = true,
        fallbackSurface: DrawScope.() -> Unit = onDrawSurface,
    ): Modifier {
        val host = resolve()
        if (host != null) {
            try {
                val size = host.size
                val args = arrayOfNulls<Any>(size + 2)
                val types = host.method.parameterTypes
                for (i in 0 until size) if (types[i] == Int::class.javaPrimitiveType) args[i] = 0
                    else if (types[i] == Long::class.javaPrimitiveType) args[i] = 0L
                args[0] = modifier; args[1] = backdrop; args[2] = shape; args[3] = effects
                val surfaceIndex = if (size >= 16) 13 else size - 2
                args[surfaceIndex] = onDrawSurface
                args[size] = defaultMask(size, surfaceIndex, hostHighlight)
                args[size + 1] = null
                return host.method.invoke(null, *args) as Modifier
            } catch (error: Throwable) {
                bridge = null
                if (!logged) { logged = true; MediaRuntime.log("backdrop_compat: host drawBackdrop failed; plain surface", error) }
            }
        }
        return modifier.clip(shape()).drawBehind(fallbackSurface)
    }
}

internal fun Modifier.compatDrawBackdrop(
    backdrop: Backdrop,
    shape: () -> Shape,
    effects: BackdropEffectScope.() -> Unit,
    onDrawSurface: DrawScope.() -> Unit,
    hostHighlight: Boolean = true,
    fallbackSurface: DrawScope.() -> Unit = onDrawSurface,
): Modifier = HostBackdropCompat.apply(this, backdrop, shape, effects, onDrawSurface, hostHighlight, fallbackSurface)
