package com.tcrrry.lyrics.engine.hook

import com.tcrrry.lyrics.engine.CurrentSongDetails
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicReference

/**
 * Publishes the verified current-item identity for custom-lyrics hooks and
 * Apple Music's embedded AM++ settings. Reuses [CurrentItemIdentitySeam] and
 * never falls back to title or metadata matching.
 */
internal class AppleMusicCurrentSongIdentityTarget(
    private val symbols: TargetSymbolResolver,
    private val cache: CurrentSongIdentityCache,
) : CurrentSongIdentityTarget {
    private var installedResult: TargetCapabilityInstall? = null
    private val registration = HookRegistrationScope()
    @Synchronized override fun install(): TargetCapabilityInstall {
        installedResult?.let { return it }
        return try {
            installOnce().also { result ->
                if (result is TargetCapabilityInstall.Active) registration.activate()
                else if (false) registration.activate()
                else registration.close()
                installedResult = result
            }
        } catch (error: Throwable) { registration.close(); throw error }
    }
    private fun hook(method: java.lang.reflect.Executable, callback: ModernMethodHook): Boolean =
        ModernXposedRuntime.hookMethod(method,callback,registration)
    private fun installOnce(): TargetCapabilityInstall {
        val installMethodResolution = symbols.resolve(AppleMusicSymbols.LyricsInstallMethod)
        val installMethod = installMethodResolution.valueOrNull()
            ?: return TargetCapabilityInstall.Degraded(installMethodResolution.summary)
        val metadataPublishResolution = symbols.resolve(AppleMusicSymbols.PlayerMetadataPublishMethod)
        val metadataPublishMethod = metadataPublishResolution.valueOrNull()
            ?: return TargetCapabilityInstall.Degraded(metadataPublishResolution.summary)
        val converterResolution = symbols.resolve(AppleMusicSymbols.MetadataToPlaybackItemMethod)
        val converterMethod = converterResolution.valueOrNull()
            ?: return TargetCapabilityInstall.Degraded(converterResolution.summary)
        if (!runCatching {
                metadataPublishMethod.isAccessible = true
                converterMethod.isAccessible = true
                true
            }.getOrDefault(false)
        ) {
            return TargetCapabilityInstall.Degraded(
                "Player metadata identity surface could not be made accessible; " +
                    listOf(metadataPublishResolution.summary, converterResolution.summary)
                        .joinToString("; "),
            )
        }
        val seam = CurrentItemIdentitySeam(symbols)
        seam.resolve(installMethod)?.let { diagnostic ->
            return TargetCapabilityInstall.Degraded(diagnostic)
        }
        val hooked = runCatching {
            hook(metadataPublishMethod, object : ModernMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val item = converterMethod.invoke(null, param.args.getOrNull(0))
                        cache.publish(item, seam.detailsOfItem(item), param.args.getOrNull(0))
                    }.onFailure { error ->
                        ModernXposedRuntime.log("current song identity publish failed: $error")
                    }
                }
            })
        }.isSuccess
        if (!hooked) {
            return TargetCapabilityInstall.Degraded(
                "Player metadata publish method could not be hooked; ${metadataPublishResolution.summary}",
            )
        }
        // A cold-start restored track need not publish metadata again. Read only the
        // verified current-item field of the actual lyrics page, never saved preferences.
        val fragmentClass = installMethod.declaringClass
        val recoverPage: (Any) -> Unit = { fragment ->
            if (registration.isActive && fragmentClass.isInstance(fragment)) runCatching {
                if (recoverCurrentSongFromLyricsPage(fragment, seam, cache))
                    LyricsPlaybackDiagnostics.record("identity-restored", "id=${cache.current()?.details?.appleMusicId}")
            }.onFailure { ModernXposedRuntime.log("Restored song identity failed open", it) }
        }
        val pageSubscription = CurrentLyricsSourceStatus.installIdentityPageHandler(recoverPage)
        registration.onClose(pageSubscription::close)
        runCatching {
            hook(fragmentClass.getMethod("onResume"), object : ModernMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    param.thisObject?.let(recoverPage)
                }
            })
        }.onFailure { ModernXposedRuntime.log("Startup song lifecycle fallback unavailable", it) }
        return TargetCapabilityInstall.Active(
            "Current song identity cache installed for embedded settings; " +
                listOfNotNull(
                    installMethodResolution.summary,
                    metadataPublishResolution.summary,
                    converterResolution.summary,
                    seam.fieldSummary.orEmpty(),
                    seam.metadataSummary,
                ).joinToString("; "),
        )
    }
}

internal fun recoverCurrentSongFromLyricsPage(fragment: Any, seam: CurrentItemIdentitySeam, cache: CurrentSongIdentityCache): Boolean {
    val item = seam.currentItemOf(fragment) ?: return false
    return cache.bootstrap(item, seam.detailsOfItem(item))
}
