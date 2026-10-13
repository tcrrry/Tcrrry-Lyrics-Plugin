package com.tcrrry.lyrics.engine.hook

import android.app.Application
import android.os.Handler
import android.os.Looper
import com.tcrrry.lyrics.engine.config.TargetConfigClient
import com.tcrrry.lyrics.engine.lyrics.CustomLyricsFilePolicy
import com.tcrrry.lyrics.engine.lyrics.CustomLyricsFileReader
import com.tcrrry.lyrics.engine.model.CustomLyricsEntry
import com.tcrrry.lyrics.engine.model.CustomLyricsSources
import com.tcrrry.lyrics.metadata.amprovider.xposed.MediaMetadataCache
import java.lang.ref.WeakReference
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Apple Music 6.5.0 adapter for user-managed, offline ID -> TTML mappings. */
internal class AppleMusicCustomLyricsTarget(
    private val application: Application,
    private val config: TargetConfigClient,
    private val symbols: TargetSymbolResolver,
    private val currentSong: CurrentSongIdentityCache,
    private val autoLyricsRuntime: AutoLyricsRuntime? = null,
) : CustomLyricsTarget {
    private var installedResult: TargetCapabilityInstall? = null
    private val registration = HookRegistrationScope()
    @Synchronized override fun install(): TargetCapabilityInstall {
        installedResult?.let { return it }
        return try {
            installOnce().also { result ->
                if (result is TargetCapabilityInstall.Active) registration.activate()
                else if (result.message.startsWith("Custom lyric I2 replacement installed")) registration.activate()
                else registration.close()
                installedResult = result
            }
        } catch (error: Throwable) { registration.close(); throw error }
    }
    private fun hook(method: java.lang.reflect.Executable, callback: ModernMethodHook): Boolean =
        ModernXposedRuntime.hookMethod(method, callback, registration)
    private fun installOnce(): TargetCapabilityInstall {
        val installMethodResolution = symbols.resolve(AppleMusicSymbols.LyricsInstallMethod)
        val installMethod = installMethodResolution.valueOrNull()
            ?: return TargetCapabilityInstall.Degraded(installMethodResolution.summary)
        if (!runCatching {
                installMethod.isAccessible = true
                true
            }.getOrDefault(false)
        ) {
            return TargetCapabilityInstall.Degraded(
                "PlayerLyricsViewFragment.I2 could not be made accessible; " +
                    installMethodResolution.summary,
            )
        }
        val ptrResolution = symbols.resolve(AppleMusicSymbols.SongInfoPtr)
        val ptrClass = ptrResolution.valueOrNull()
            ?: return TargetCapabilityInstall.Degraded(ptrResolution.summary)
        val nativeResolution = symbols.resolve(AppleMusicSymbols.SongInfoNative)
        val nativeClass = nativeResolution.valueOrNull()
            ?: return TargetCapabilityInstall.Degraded(nativeResolution.summary)
        val parserResolution = symbols.resolve(AppleMusicSymbols.TtmlParserNative)
        val parserClass = parserResolution.valueOrNull()
            ?: return TargetCapabilityInstall.Degraded(parserResolution.summary)
        val parseMethodResolution = symbols.resolve(AppleMusicSymbols.TtmlSongInfoFromTtml)
        val parseMethod = parseMethodResolution.valueOrNull()
            ?: return TargetCapabilityInstall.Degraded(parseMethodResolution.summary)
        val seam = CurrentItemIdentitySeam(symbols)
        seam.resolve(installMethod)?.let { diagnostic ->
            return TargetCapabilityInstall.Degraded(diagnostic)
        }
        val nativeParser = TtmlNativeParser.create(
            parserClass = parserClass,
            parseMethod = parseMethod,
            ptrClass = ptrClass,
            nativeClass = nativeClass,
        ) ?: return TargetCapabilityInstall.Degraded(
            "TTML native parser surface was unavailable; " +
                listOf(
                    parserResolution.summary,
                    parseMethodResolution.summary,
                    ptrResolution.summary,
                    nativeResolution.summary,
                ).joinToString("; "),
        )
        val parser = OpaqueTtmlParser(nativeParser)
        val timingObservations = TtmlTimingObservationRegistry()
        val parsingReplacement = ThreadLocal<Boolean>()
        val parseReplacement: (String) -> Any? = { ttml ->
            parsingReplacement.set(true)
            try { parser.parse(ttml) } finally { parsingReplacement.remove() }
        }
        val nativeText = java.util.Collections.synchronizedMap(java.util.WeakHashMap<Any, String>())
        val preparedSource = java.util.concurrent.ConcurrentHashMap<Long, String>()
        val nativeEnrichment = FallbackLyricsTranslation(application)
        val nativePointers = java.util.Collections.synchronizedMap(java.util.WeakHashMap<Any, Boolean>())
        val nativeById = java.util.Collections.synchronizedMap(object : java.util.LinkedHashMap<Long, Any>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Any>?) = size > 8
        })
        fun preferNative(id: Long): Boolean = NativeLyricsPreferencePolicy.preferNative(
            com.tcrrry.lyrics.engine.lyrics.LyricsPreference.qualityFirst(application),
            CurrentLyricsSourceStatus.selectedSource(application, id),
            nativeById[id]?.let(nativeText::get))

        val fileReader = CustomLyricsFileReader { fileId ->
            config.openFile(fileId)?.let { input ->
                runCatching {
                    input.use(CustomLyricsFilePolicy::readBounded)
                }.getOrNull()
            }
        }
        val mainHandler = com.tcrrry.lyrics.plugin.PluginHandler(Looper.getMainLooper())
        val missingMetadataRetries = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
        var activeFragment: WeakReference<Any>? = null
        lateinit var readyReapply: CustomLyricsReadyReapply
        var publishToPage: (Long) -> Unit = { id -> readyReapply.onReplacementPublished(id) }
        var retryReadyPage: (Long) -> Unit = {}
        var pageHasReplacement: (Long) -> Boolean = { false }
        val userRefreshes = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
        val scheduledRefreshes = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
        val configuredManualIds = runCatching {
            config.customLyricsManifest().entries
                .filter { it.enabled && it.source != CustomLyricsSources.AUTO_CACHE }
                .mapTo(mutableSetOf(), CustomLyricsEntry::appleMusicId)
        }.getOrDefault(emptySet())
        val session = CustomLyricsReplacementSession(
            index = CustomLyricsIndexProvider {
                config.customLyricsManifest().entries
                    .filter { it.source != CustomLyricsSources.AUTO_CACHE }
                    .associateBy(CustomLyricsEntry::appleMusicId)
            },
            readTtml = fileReader::read,
            parseTtml = parseReplacement,
            isAlive = parser::isAlive,
            verifyPtr = parser::isValid,
            readAdamId = parser::adamIdOf,
            bindAdamId = parser::bindAdamId,
            onReplacementPublished = { appleMusicId ->
                mainHandler.post { if (registration.isActive) publishToPage(appleMusicId) }
            },
            executor = com.tcrrry.lyrics.plugin.PluginExecutors.pool(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                ArrayBlockingQueue(1),
                { runnable -> Thread(runnable, "ampp-custom-lyrics").apply { isDaemon = true } },
                ThreadPoolExecutor.AbortPolicy(),
            ),
            logger = ModernXposedRuntime::log,
        )
        val autoSession = autoLyricsRuntime?.let { runtime ->
            AutoLyricsReplacementSession(
                fetchCandidate = fetch@{ appleMusicId ->
                    val policyRevision = com.tcrrry.lyrics.engine.lyrics.LyricsPreference.revision(application)
                    val current = currentSong.current()
                        ?.takeIf { it.details.appleMusicId == appleMusicId }
                    val metadata = MediaMetadataCache.getMetadataById(appleMusicId.toString())
                    val details = current?.details
                    val track = if (details != null || metadata != null) {
                        val item = current?.item
                        DesktopLyricsTrack(
                            appleMusicId = appleMusicId,
                            explicitSource = CurrentLyricsSourceStatus.selectedSource(application, appleMusicId) != null,
                            title = details?.title?.takeIf(String::isNotBlank)
                                ?: item?.let { itemString(it, "getTitle") ?: itemString(it, "getName") }
                                ?: metadata?.title?.takeIf(String::isNotBlank)
                                ?: metadata?.originalTitle.orEmpty(),
                            artist = details?.artist?.takeIf(String::isNotBlank)
                                ?: item?.let { itemString(it, "getArtistName") ?: itemString(it, "getArtist") }
                                ?: metadata?.artist?.takeIf(String::isNotBlank)
                                ?: metadata?.originalArtist.orEmpty(),
                            album = item?.let(::itemAlbum)
                                ?: metadata?.originalAlbum.orEmpty(),
                            durationMs = readPlaybackDurationMs(item) ?: preferredSongDurationMs(metadata?.duration,
                                item?.let { itemNumber(it, "getDuration") }),
                        )
                    } else null
                    if (track == null || track.title.isBlank() || track.artist.isBlank()) {
                        CurrentLyricsSourceStatus.rememberMatchStatus(application, appleMusicId, "等待完整歌曲信息后重试")
                        if (missingMetadataRetries.add(appleMusicId)) {
                            mainHandler.postDelayed({ CurrentLyricsSourceStatus.refreshSilently(appleMusicId) }, 3_000L)
                        }
                    } else missingMetadataRetries.remove(appleMusicId)
                    ModernXposedRuntime.log(
                        "Desktop Lyrics lookup id=$appleMusicId " +
                            "title=${track?.title.orEmpty().take(48)} " +
                            "artist=${track?.artist.orEmpty().take(48)} " +
                            "album=${track?.album.orEmpty().take(48)} " +
                            "durationMs=${track?.durationMs ?: 0L}",
                    )
                    track?.let { CurrentLyricsSourceStatus.rememberMatchInput(application, it) }
                    val nativeDocument = nativeById[appleMusicId]?.let(nativeText::get)
                    val selected = CurrentLyricsSourceStatus.selectedSource(application, appleMusicId)
                    val searched = when {
                        preferNative(appleMusicId) && nativeDocument != null ->
                            nativeEnrichment.enrichWithTrack(appleMusicId, AutoLyricsCandidate("APPLE_NATIVE", nativeDocument), track)
                        selected == LyricsSourceMenuPolicy.NATIVE -> null
                        selected == LyricsSourceMenuPolicy.AUTHOR -> runtime.resolver.fetch(appleMusicId, track,
                            selectedRepository = com.tcrrry.lyrics.engine.model.CustomLyricsSources.AM_LYRICS)
                        else -> runtime.resolver.fetch(appleMusicId, track)
                    }
                    val resolved = if (NativeLyricsPreferencePolicy.useNativeLineFallback(selected, nativeDocument, searched?.ttml))
                        nativeEnrichment.enrichWithTrack(appleMusicId, AutoLyricsCandidate("APPLE_NATIVE", nativeDocument!!), track)
                    else searched
                    if (policyRevision != com.tcrrry.lyrics.engine.lyrics.LyricsPreference.revision(application)) return@fetch null
                    ModernXposedRuntime.log(
                        "Desktop Lyrics lookup result id=$appleMusicId source=${resolved?.source ?: "none"}",
                    )
                    resolved?.let { candidate ->
                        val details = currentSong.current()
                            ?.takeIf { it.details.appleMusicId == appleMusicId }
                            ?.details
                        val displayName = listOfNotNull(
                            details?.title?.takeIf(String::isNotBlank),
                            details?.artist?.takeIf(String::isNotBlank),
                        ).joinToString(" - ").ifBlank { null }
                        candidate.copy(displayName = displayName)
                    }
                },
                cache = runtime.cache,
                onTaggedRefreshFinished = { id, success, cycleTicket ->
                    mainHandler.post {
                        val stillCurrent = currentSong.current()?.details?.appleMusicId == id
                        val next = if (stillCurrent) CurrentLyricsSourceStatus.continueSourceCycle(
                            application, id, cycleTicket, success) else null
                        if (!stillCurrent) CurrentLyricsSourceStatus.cancelSourceCycle(id)
                        if (next != null) {
                            CurrentLyricsSourceStatus.rememberMatchStatus(application, id, "上一来源暂无结果，正在尝试 $next")
                            android.widget.Toast.makeText(application, "继续尝试 · $next", android.widget.Toast.LENGTH_SHORT).show()
                            if (CurrentLyricsSourceStatus.refresh(id)) return@post
                            CurrentLyricsSourceStatus.cancelSourceCycle(id)
                        }
                        if (userRefreshes.remove(id) && stillCurrent) {
                            android.widget.Toast.makeText(application,
                                if (!success) "未找到新的可靠歌词，已保留当前歌词"
                                else if (pageHasReplacement(id)) "歌词已更新" else "歌词已准备好，打开歌词页即可查看",
                                android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onCandidatePrepared = { id, candidate ->
                    preparedSource[id] = candidate.source
                    CurrentLyricsSourceStatus.rememberMatchStatus(application, id, "歌词已准备好，等待歌词页装载")
                    CurrentLyricsSourceStatus.rememberCandidate(
                        application, id, candidate.source, candidate.ttml,
                    )
                },
                parseTtml = parseReplacement,
                isAlive = parser::isAlive,
                verifyPtr = parser::isValid,
                readAdamId = parser::adamIdOf,
                bindAdamId = parser::bindAdamId,
                onReplacementPublished = { appleMusicId ->
                    mainHandler.post { if (registration.isActive) {
                        publishToPage(appleMusicId)
                        retryReadyPage(appleMusicId)
                    } }
                },
                // Keep automatic results outside manual mappings so a later
                // Desktop Lyrics lookup can supersede an older fallback.
                publisher = null,
                isAllowed = { appleMusicId ->
                    appleMusicId !in runtime.suppressedIds &&
                        appleMusicId !in configuredManualIds &&
                        !session.isMapped(appleMusicId) &&
                        session.readyReplacementFor(appleMusicId) == null
                },
                executor = runtime.executor,
                logger = { message ->
                    ModernXposedRuntime.log(message)
                    if (message.startsWith("automatic lyrics pointer") || message.startsWith("automatic lyrics native parse failed") ||
                        message.startsWith("automatic lyrics candidate rejected")) {
                        Regex("\\bid=(\\d+)").find(message)?.groupValues?.get(1)?.toLongOrNull()?.let { id ->
                            CurrentLyricsSourceStatus.rememberMatchStatus(application, id, "歌词已找到，Apple Music 装载失败；请重新匹配")
                        }
                    }
                },
            )
        }
        val readyReplacementFor: (Long) -> Any? = { appleMusicId ->
            parser.unwrap(session.readyReplacementFor(appleMusicId)
                ?: (if (preferNative(appleMusicId)) {
                    if (preparedSource[appleMusicId] == "APPLE_NATIVE") autoSession?.readyReplacementFor(appleMusicId)
                        ?: nativeById[appleMusicId]?.takeIf(parser::isAlive)
                    else nativeById[appleMusicId]?.takeIf(parser::isAlive)
                } else null)
                ?: autoSession?.readyReplacementFor(appleMusicId))
        }
        val isTracking: (Long) -> Boolean = { appleMusicId ->
            session.isTracking(appleMusicId) || autoSession?.isTracking(appleMusicId) == true
        }
        val fragmentUsable = fragmentIsAddedPredicate(installMethod.declaringClass)
        val pageIdentity = NativeLyricsPageIdentity.resolve(installMethod.declaringClass, seam, currentSong)
        var refreshPageIdentity: (Any) -> Unit = { }
        readyReapply = CustomLyricsReadyReapply(
            installMethod = installMethod,
            seam = seam,
            readyReplacementFor = readyReplacementFor,
            isFragmentUsable = fragmentUsable,
            currentSong = currentSong,
            logger = ModernXposedRuntime::log,
        )
        // I2 is not guaranteed to run on every static/custom-lyrics page.
        // Track the visible page independently and compare the native pointer
        // before applying, rather than relying solely on an earlier I2 miss.
        val installedPointer = installMethod.declaringClass.declaredFields
            .filter { it.type == ptrClass }.singleOrNull()?.apply { isAccessible = true }
        val timedPage = NativeTimedLyricsPage.resolve(installMethod.declaringClass, installMethod.name)
        fun ownsReplacement(id: Long, pointer: Any?): Boolean = pointer != null &&
            (parser.unwrap(session.readyReplacementFor(id)) === pointer ||
                parser.unwrap(autoSession?.readyReplacementFor(id)) === pointer)
        fun repairTimedPage(fragment: Any): Boolean {
            val id = seam.currentItemAdamIdOf(fragment) ?: return false
            if (currentSong.current()?.details?.appleMusicId != id) return false
            val installed = installedPointer?.get(fragment) ?: return false
            if (!ownsReplacement(id, installed) || timedPage?.repair(fragment, id, installed) != true) return false
            ModernXposedRuntime.log("Timed replacement page restored id=$id")
            return true
        }
        val immediateAnchor = NativeLyricsImmediateAnchor.resolve(installMethod.declaringClass, installMethod.name)?.also { anchor ->
            anchor.ownsReplacement = { fragment, pointer ->
                val id = seam.currentItemAdamIdOf(fragment)
                id != null && currentSong.current()?.details?.appleMusicId == id && ownsReplacement(id, pointer)
            }
            anchor.currentSongId = { fragment ->
                currentSong.current()?.details?.appleMusicId?.takeIf { it > 0L && it == seam.currentItemAdamIdOf(fragment) }
            }
            anchor.isCurrentSong = { fragment ->
                LyricReplayRecovery.sameSong(currentSong.current()?.details?.appleMusicId,
                    seam.currentItemAdamIdOf(fragment), anchor.nativeDocumentId(fragment))
            }
        }
        pageHasReplacement = { id ->
            val fragment = activeFragment?.get()
            val replacement = readyReplacementFor(id)
            fragment != null && installedPointer != null && shouldReportAppliedLyrics(
                id, seam.currentItemAdamIdOf(fragment),
                runCatching { installedPointer.get(fragment) }.getOrNull(), replacement,
            )
        }
        fun syncAppliedStatus(id: Long) {
            val rawNative = nativeById[id]?.let(parser::unwrap)
            val fragment = activeFragment?.get()
            val installed = runCatching { fragment?.let { installedPointer?.get(it) } }.getOrNull()
            // Actual installed pointer identity determines source even when the user
            // prefers third-party lyrics but no matching replacement was available.
            val nativeRecorded = CurrentLyricsSourceStatus.recordNativeIfInstalled(application, id,
                fragment?.let(seam::currentItemAdamIdOf), installed, rawNative, nativeById[id]?.let(nativeText::get))
            if (!nativeRecorded && pageHasReplacement(id)) {
                CurrentLyricsSourceStatus.recordApplied(application, id, session.readyReplacementFor(id) != null)
            }
        }
        publishToPage = { id ->
            activeFragment?.get()?.let { fragment ->
                if (fragmentUsable(fragment) && currentSong.current()?.details?.appleMusicId == id) {
                    refreshPageIdentity(fragment)
                }
                val replacement = readyReplacementFor(id)
                if (fragmentUsable(fragment) && currentSong.current()?.details?.appleMusicId == id &&
                    seam.currentItemAdamIdOf(fragment) == id && replacement != null &&
                    (installedPointer == null || runCatching { installedPointer.get(fragment) !== replacement }.getOrDefault(false))) {
                    readyReapply.recordMiss(fragment, id)
                }
            }
            readyReapply.onReplacementPublished(id)
            activeFragment?.get()?.let { fragment ->
                runCatching { if (repairTimedPage(fragment)) immediateAnchor?.schedule(fragment) }
                    .onFailure { ModernXposedRuntime.log("Timed replacement page recovery failed open", it) }
            }
            // A cache candidate may acquire its metadata after its pointer is
            // already installed. Reconcile the verified page even if I2 needn't run again.
            syncAppliedStatus(id)
        }
        // A ready notification can race view attachment or the host's own loading update.
        // Retry the existing installation path finitely; never redo search or clear native data.
        var pageRetryTicket = 0L
        retryReadyPage = { id ->
            val ticket = ++pageRetryTicket
            val page = activeFragment?.get()?.let(::WeakReference)
            for (delay in listOf(100L, 400L, 1_200L, 3_000L)) mainHandler.postDelayed({
                val fragment = page?.get()
                if (registration.isActive && ticket == pageRetryTicket && fragment != null &&
                    activeFragment?.get() === fragment && fragmentUsable(fragment) &&
                    currentSong.current()?.details?.appleMusicId == id) {
                    publishToPage(id)
                }
            }, delay)
        }
        val metadataRefresh = NativeLyricsMetadataRefresh.resolveReplay(installMethod.declaringClass)?.let { replay ->
            NativeLyricsMetadataRefresh(currentSong, seam::currentItemAdamIdOf,
                { page -> activeFragment?.get() === page && fragmentUsable(page) },
                dispatch = { action -> mainHandler.post {
                    if (registration.isActive) runCatching(action)
                        .onFailure { ModernXposedRuntime.log("Lyrics metadata recovery failed open", it) }
                } }, replay = replay, recovered = { fragment, id ->
                    ModernXposedRuntime.log("Lyrics page identity recovered id=$id")
                    LyricsPlaybackDiagnostics.record("page-metadata-recovered", "id=$id")
                    session.ensureRequested(id)
                    autoSession?.ensureRequested(id)
                    publishToPage(id)
                    immediateAnchor?.schedule(fragment)
                }, authoritative = { page, snapshot -> pageIdentity?.isAuthoritative(page, snapshot) == true },
                restoreAlias = { page, snapshot -> pageIdentity?.restoreLibraryAlias(page, snapshot) == true })
        }
        refreshPageIdentity = { page -> metadataRefresh?.schedule(page) }
        // Apple's library callback can overwrite getId() after b2/w2 have already
        // completed. Observe that exact callback, not arbitrary library mutations.
        if (pageIdentity != null) runCatching {
            val consumer = Class.forName(installMethod.declaringClass.name + "\$e", false, installMethod.declaringClass.classLoader)
            val owner = consumer.getDeclaredField("a").apply { isAccessible = true }
            check(owner.type == installMethod.declaringClass)
            val accept = consumer.getDeclaredMethod("accept", Any::class.java)
            hook(accept, object : ModernMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null) return
                    val fragment = owner.get(param.thisObject) ?: return
                    if (fragment === activeFragment?.get() && fragmentUsable(fragment)) {
                        metadataRefresh?.schedule(fragment)
                    }
                }
            })
        }.onFailure { ModernXposedRuntime.log("Lyrics library identity observer unavailable", it) }
        // The native menu knows which exact lyrics page the user is acting on.
        // Capture it before changing source; retain the proven r18 I2/reapply path.
        CurrentLyricsSourceStatus.installPageHandler { fragment ->
            if (installMethod.declaringClass.isInstance(fragment)) {
                activeFragment = WeakReference(fragment)
                metadataRefresh?.schedule(fragment)
                seam.currentItemAdamIdOf(fragment)?.let { id ->
                    if (currentSong.current()?.details?.appleMusicId == id) {
                        session.ensureRequested(id)
                        autoSession?.ensureRequested(id)
                        publishToPage(id)
                    }
                }
            }
        }
        runCatching { installMethod.declaringClass.getMethod("onResume").declaringClass }.getOrNull()?.let { owner ->
            ModernXposedRuntime.hookAllMethods(owner, "onResume", object : ModernMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val fragment = param.thisObject?.takeIf(installMethod.declaringClass::isInstance) ?: return
                    activeFragment = WeakReference(fragment)
                    metadataRefresh?.schedule(fragment)
                    runCatching { immediateAnchor?.watch(fragment); immediateAnchor?.schedule(fragment) }
                        .onFailure { ModernXposedRuntime.log("Lyrics focus recovery registration failed open", it) }
                    seam.currentItemAdamIdOf(fragment)?.let { id ->
                        session.ensureRequested(id)
                        autoSession?.ensureRequested(id)
                        publishToPage(id)
                    }
                }
            })
        }
        val parserHooked = runCatching {
            parseMethod.isAccessible = true
            hook(parseMethod, object : ModernMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val ttml = param.args.getOrNull(0) as? String ?: return@runCatching
                        val pointer = param.result
                        val metadata = TtmlTimingPolicy.metadataOf(ttml)
                        val appleMusicId = pointer?.let(parser::adamIdOf)
                        timingObservations.record(pointer, metadata, appleMusicId)
                        // Only observed, nonempty original documents qualify. Synthetic
                        // parsed replacement pointers must never become native candidates.
                        if (pointer != null && parsingReplacement.get() != true && !ttml.contains("<!--tcrrry-") &&
                            NativeLyricsPreferencePolicy.hasContent(ttml)) {
                            NativeLyricsPronunciationSubtitle.remember(pointer, ttml)
                            nativePointers[pointer] = true
                            nativeText[pointer] = ttml
                        }
                        if (
                            appleMusicId != null &&
                            currentSong.current()?.details?.appleMusicId == appleMusicId &&
                            session.readyReplacementFor(appleMusicId) == null
                        ) {
                            autoSession?.ensureRequested(appleMusicId)
                        }
                    }.onFailure { error ->
                        ModernXposedRuntime.log("custom lyrics TTML timing observation failed: $error")
                    }
                }
            })
        }.isSuccess
        val itemUpdateContext = LyricsItemUpdateContext()
        val hooked = runCatching {
            hook(installMethod, object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    itemUpdateContext.markAppleInvokedI2()
                    param.extras["anchor-previous-pointer"] = runCatching { installedPointer?.get(param.thisObject) }.getOrNull()
                    runCatching {
                        if (!acceptsLyricsInstallArguments(param.args, ptrClass)) return@runCatching
                        val original = param.args[0]
                        val publishedCurrent = currentSong.current()
                        if (publishedCurrent != null && param.thisObject != null && fragmentUsable(param.thisObject)) {
                            if (pageIdentity?.restoreLibraryAlias(param.thisObject, publishedCurrent) == true) {
                                ModernXposedRuntime.log("Lyrics library alias restored id=${publishedCurrent.details.appleMusicId}")
                            }
                        }
                        val fragmentAdamId = seam.currentItemAdamIdOf(param.thisObject)
                        val publishedAdamId = publishedCurrent?.details?.appleMusicId
                        val adamId = selectLyricsInjectionAdamId(
                            original = original,
                            fragmentAdamId = fragmentAdamId,
                            publishedAdamId = publishedAdamId,
                        )
                        ModernXposedRuntime.log(
                            "Desktop Lyrics I2 original=${original != null} fragmentId=${fragmentAdamId ?: 0L} " +
                                "currentId=${publishedAdamId ?: 0L} selectedId=${adamId ?: 0L}",
                        )
                        adamId ?: return@runCatching
                        param.thisObject?.let { activeFragment = WeakReference(it) }
                        val manualReplacement = session.replacementFor(adamId)
                        val timingMetadata = timingObservations.metadataOf(original)
                        if (original != null && nativePointers[original] == true) {
                            val first = nativeById.put(adamId, original) !== original
                            if (first && (preferNative(adamId) || CurrentLyricsSourceStatus.selectedSource(application, adamId) == null)) mainHandler.post { autoSession?.refreshCurrent(adamId) }
                        }
                        val native = if (preferNative(adamId)) {
                            (if (preparedSource[adamId] == "APPLE_NATIVE") autoSession?.readyReplacementFor(adamId) else null)
                                ?: nativeById[adamId]?.takeIf(parser::isAlive)
                        } else null
                        val autoEligible = autoSession != null && native == null
                        val autoReplacement = if (manualReplacement == null && native == null) {
                            autoSession?.replacementFor(adamId)
                        } else null
                        // User-managed mappings win. Automatic native Word lyrics
                        // take precedence in both preference modes.
                        val replacement = manualReplacement ?: native ?: autoReplacement
                        ModernXposedRuntime.log(
                            "Desktop Lyrics I2 choice id=$adamId manual=${manualReplacement != null} " +
                                "auto=${autoReplacement != null} tracking=${autoSession?.isTracking(adamId) == true}",
                        )
                        val tracking = session.isTracking(adamId) ||
                            (autoEligible && autoSession?.isTracking(adamId) == true)
                        val needsRebind = original == null &&
                            publishedCurrent != null &&
                            publishedAdamId != null &&
                            publishedAdamId != fragmentAdamId
                        val canRebind = currentSong.canRebind(fragmentAdamId, publishedAdamId)
                        if (
                            needsRebind && tracking && canRebind &&
                            (fragmentAdamId == null || session.isMapped(adamId))
                        ) {
                            val rebound = param.thisObject?.let { fragment ->
                                seam.bindCurrentItemOf(fragment, publishedCurrent.item)
                            } == true
                            if (!rebound) return@runCatching
                        }
                        if (replacement == null) {
                            if (shouldRecordReadyLateMiss(original, replacement) && tracking) {
                                param.thisObject?.let { readyReapply.recordMiss(it, adamId) }
                            }
                        } else {
                            if (manualReplacement == null && autoReplacement != null) {
                                autoSession?.markTakeoverApplied(adamId)
                            }
                            param.thisObject?.let { readyReapply.dismiss(it) }
                            if (fragmentAdamId == adamId && installedPointer != null &&
                                runCatching { installedPointer.get(param.thisObject) === parser.unwrap(replacement) }.getOrDefault(false)) {
                                // Apple's I2 clears timing offsets before its own
                                // same-pointer early return. Avoid that redundant reset.
                                param.result = null
                                return@runCatching
                            }
                            if (replacement !== original) {
                                param.args[0] = parser.unwrap(replacement)
                            }
                        }
                    }.onFailure { error ->
                        ModernXposedRuntime.log("custom lyrics I2 replacement hook failed: $error")
                    }
                }
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null) return
                    // Installation may run after onResume, or the plugin may be
                    // enabled on an already visible page. Watch once the actual
                    // pointer/binding exists, independently of metadata refresh.
                    param.thisObject?.let { fragment ->
                        metadataRefresh?.schedule(fragment)
                        runCatching { immediateAnchor?.watch(fragment) }
                            .onFailure { ModernXposedRuntime.log("Lyrics install recovery registration failed open", it) }
                    }
                    if (param.thisObject !== activeFragment?.get()) return
                    runCatching {
                        param.thisObject?.let(::repairTimedPage)
                        param.thisObject?.let(seam::currentItemAdamIdOf)?.let(::syncAppliedStatus)
                        val fragment = param.thisObject
                        if (fragment != null && installedPointer?.get(fragment) !== param.extras["anchor-previous-pointer"]) {
                            immediateAnchor?.schedule(fragment)
                        }
                    }.onFailure { ModernXposedRuntime.log("applied lyrics status reconciliation failed", it) }
                }
            })
        }.isSuccess
        if (!hooked) {
            return TargetCapabilityInstall.Degraded(
                "PlayerLyricsViewFragment.I2 could not be hooked; ${installMethodResolution.summary}",
            )
        }
        // Native view-model callbacks can bind a static/empty adapter long after
        // publication. Observe that actual UI bind, not only the w2 pointer setter.
        // Post until the native bind finishes; a repair's own B2 must not enqueue itself.
        timedPage?.let { page ->
            runCatching {
                hook(page.bindingMethod, object : ModernMethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.throwable != null || page.isRepairing) return
                        val fragment = param.thisObject ?: return
                        val ref = WeakReference(fragment)
                        mainHandler.post {
                            val current = ref.get() ?: return@post
                            if (!registration.isActive || activeFragment?.get() !== current) return@post
                            runCatching {
                                if (repairTimedPage(current)) immediateAnchor?.schedule(current)
                            }.onFailure { ModernXposedRuntime.log("Timed replacement binding recovery failed open", it) }
                        }
                    }
                })
            }.onFailure { ModernXposedRuntime.log("Timed replacement binding observer unavailable", it) }
        }
        val itemUpdateResolution = symbols.resolve(AppleMusicSymbols.LyricsItemUpdateMethod)
        val itemUpdateMethod = itemUpdateResolution.valueOrNull()
        if (itemUpdateMethod != null) {
            val headerRefresh = NativeLyricsHeaderRefresh.resolve(installMethod.declaringClass, seam)
            val headerFlags = runCatching { ItemUpdateFlags(itemUpdateMethod.parameterTypes[2]) }.getOrNull()
            val coordinator = runCatching {
                LyricsItemUpdateCoordinator(
                    installMethod = installMethod,
                    flags = ItemUpdateFlags(itemUpdateMethod.parameterTypes[2]),
                    seam = seam,
                    readyReplacementFor = readyReplacementFor,
                    isTracking = isTracking,
                    isFragmentUsable = fragmentUsable,
                    readyReapply = readyReapply,
                    logger = ModernXposedRuntime::log,
                )
            }.getOrNull()
            if (coordinator != null) {
                val itemUpdateHooked = runCatching {
                    hook(itemUpdateMethod, object : ModernMethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            itemUpdateContext.enterO2()
                        }

                        override fun afterHookedMethod(param: MethodHookParam) {
                            try {
                                val fragment = param.thisObject
                                val appleInvokedI2 = itemUpdateContext.appleInvokedI2DuringO2()
                                val flagsHolder = param.args.getOrNull(2)
                                if (fragment != null && param.throwable == null && headerRefresh?.refresh(
                                        fragment, param.args.getOrNull(1), headerFlags?.isItemChanged(flagsHolder) == true) == true) {
                                    LyricsPlaybackDiagnostics.record("header-refresh", "id=${seam.currentItemAdamIdOf(fragment)}")
                                }
                                itemUpdateContext.reentering {
                                    runCatching {
                                        fragment?.let { currentFragment ->
                                            coordinator.onItemUpdate(
                                                fragment = currentFragment,
                                                flagsHolder = flagsHolder,
                                                appleInvokedI2 = appleInvokedI2,
                                            )
                                        }
                                    }.onFailure { error ->
                                        ModernXposedRuntime.log(
                                            "custom lyrics item update hook failed: $error",
                                        )
                                    }
                                }
                            } finally {
                                itemUpdateContext.exitO2()
                            }
                        }
                    })
                }.isSuccess
                if (!itemUpdateHooked) {
                    ModernXposedRuntime.log(
                        "PlayerLyricsViewFragment.o2 could not be hooked; " +
                            itemUpdateResolution.summary,
                    )
                }
            }
        }
        val availabilityResolution = symbols.resolve(AppleMusicSymbols.LyricsAvailabilityPredicate)
        val availabilityMethod = availabilityResolution.valueOrNull()
        val availabilityHooked = availabilityMethod != null && runCatching {
            availabilityMethod.isAccessible = true
            hook(availabilityMethod, object : ModernMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val nativeLyricsAvailable = param.result as? Boolean ?: return@runCatching
                        if (nativeLyricsAvailable) return@runCatching
                        val appleMusicId = seam.detailsOfItem(param.args.getOrNull(0))?.appleMusicId
                        appleMusicId?.let { id ->
                            session.ensureRequested(id)
                            autoSession?.ensureRequested(id)
                        }
                        val replacementReady = appleMusicId != null &&
                            (session.replacementOrPrepareFor(appleMusicId) != null ||
                                autoSession?.replacementOrPrepareFor(appleMusicId) != null)
                        val replacementPending = appleMusicId != null &&
                            (session.isTracking(appleMusicId) || autoSession?.isTracking(appleMusicId) == true)
                        if (
                            shouldExposeCustomLyrics(
                                nativeLyricsAvailable = nativeLyricsAvailable,
                                appleMusicId = appleMusicId,
                                replacementReady = replacementReady,
                                replacementPending = replacementPending,
                            )
                        ) {
                            param.result = true
                        }
                    }.onFailure { error ->
                        ModernXposedRuntime.log("custom lyrics availability hook failed: $error")
                    }
                }
            })
        }.isSuccess
        session.start()
        CurrentLyricsSourceStatus.installRefreshHandler { id, userInitiated ->
            if (currentSong.current()?.details?.appleMusicId == id && autoSession != null && !session.isMapped(id)) {
                if (userInitiated) userRefreshes.add(id)
                if (scheduledRefreshes.add(id)) mainHandler.postDelayed({
                    scheduledRefreshes.remove(id)
                    if (currentSong.current()?.details?.appleMusicId == id && !session.isMapped(id)) {
                        if (userRefreshes.contains(id)) autoLyricsRuntime?.invalidateSearch?.invoke(id)
                        activeFragment?.get()?.let { readyReapply.recordMiss(it, id) }
                        autoSession.refreshCurrent(id, CurrentLyricsSourceStatus.sourceCycleTicket(id))
                    } else userRefreshes.remove(id)
                }, 200L)
                true
            } else false
        }
        val identitySubscription = currentSong.addListener { current ->
            activeFragment?.get()?.let { metadataRefresh?.schedule(it) }
            val appleMusicId = current?.details?.appleMusicId
            CurrentLyricsSourceStatus.cancelSourceCycleUnless(appleMusicId)
            ModernXposedRuntime.log(
                "Desktop Lyrics song id=${appleMusicId ?: 0L} " +
                    "auto=${autoSession != null} " +
                    "suppressed=${appleMusicId in (autoLyricsRuntime?.suppressedIds ?: emptySet())} " +
                    "manual=${appleMusicId in configuredManualIds} " +
                    "mapped=${appleMusicId?.let(session::isMapped) ?: false}",
            )
            appleMusicId?.let(session::ensureRequested)
            autoSession?.onSongChanged(appleMusicId)
            appleMusicId?.let { id ->
                ModernXposedRuntime.log(
                    "Desktop Lyrics eligibility id=$id manualReady=${session.readyReplacementFor(id) != null} " +
                        "manualMapped=${session.isMapped(id)}",
                )
                if (session.readyReplacementFor(id) == null) autoSession?.ensureRequested(id)
            }
        }
        registration.onClose(identitySubscription::close)
        if (!availabilityHooked) {
            return TargetCapabilityInstall.Degraded(
                "Custom lyric I2 replacement installed, but unavailable-lyrics entry could not be enabled; " +
                    availabilityResolution.summary,
            )
        }
        return TargetCapabilityInstall.Active(
            "Custom lyric ID mappings installed" +
                (if (autoSession != null) " with Desktop Lyrics preferred auto TTML" else "") + "; " +
                listOf(
                    installMethodResolution.summary,
                    availabilityResolution.summary,
                    itemUpdateResolution.summary,
                    ptrResolution.summary,
                    nativeResolution.summary,
                    parserResolution.summary,
                    parseMethodResolution.summary,
                    "timingHooked=$parserHooked",
                    seam.fieldSummary.orEmpty(),
                ).joinToString("; "),
        )
    }

}

private fun itemString(item: Any, method: String): String? = runCatching {
    item.javaClass.getMethod(method).invoke(item) as? String
}.getOrNull()?.trim()?.takeIf(String::isNotEmpty)

/** Apple Music PlaybackItem stores the album title as its collection name. */
internal fun itemAlbum(item: Any): String? =
    itemString(item, "getCollectionName")
        ?: itemString(item, "getAlbumName") ?: itemString(item, "getAlbumTitle") ?: runCatching {
        item.javaClass.getMethod("getAlbum").invoke(item)
    }.getOrNull()?.let { album ->
        (album as? String)?.trim()?.takeIf(String::isNotEmpty)
            ?: itemString(album, "getName") ?: itemString(album, "getTitle")
    } ?: runCatching {
        item.javaClass.getMethod("getAttributes").invoke(item)
    }.getOrNull()?.let { attributes -> itemString(attributes, "getAlbumName") }

private fun itemNumber(item: Any, method: String): Long? = runCatching {
    (item.javaClass.getMethod(method).invoke(item) as? Number)?.toLong()
}.getOrNull()?.takeIf { it > 0L }

private fun normalizedSongDurationMs(value: Long): Long =
    if (value in 1L..999L) value * 1_000L else value

internal fun preferredSongDurationMs(mediaDurationMs: Long?, itemDuration: Long?): Long =
    mediaDurationMs?.takeIf { it > 0L }?.let(::normalizedSongDurationMs) ?: itemDuration?.takeIf { it > 0L }
        ?.let(::normalizedSongDurationMs) ?: 0L

/** PlaybackItem duration is seconds: the native metadata converter divides milliseconds by 1000. */
internal fun readPlaybackDurationMs(item: Any?): Long? = item?.let {
    runCatching {
        val method = it.javaClass.getMethod("getPlaybackDuration").apply { isAccessible = true }
        val seconds = (method.invoke(it) as? Number)?.toLong() ?: return@runCatching null
        if (seconds <= 0L || seconds > Long.MAX_VALUE / 1_000L) null else seconds * 1_000L
    }.getOrNull()
}

internal fun selectLyricsInjectionAdamId(
    original: Any?,
    fragmentAdamId: Long?,
    publishedAdamId: Long?,
): Long? = if (
    original == null &&
    publishedAdamId != null &&
    publishedAdamId > 0L &&
    publishedAdamId != fragmentAdamId
) {
    publishedAdamId
} else {
    fragmentAdamId
}

/**
 * Apple emits a null SongInfoPtr when a playback item has no native lyrics,
 * and a live SongInfoPtr otherwise. Both forms can be recorded by the
 * ready-late reapply ledger while their custom replacement is preparing; the
 * ledger applies the same current-item and lifecycle gates to both.
 */
internal fun acceptsLyricsInstallArguments(args: Array<Any?>, ptrClass: Class<*>): Boolean =
    args.isNotEmpty() && (args[0] == null || ptrClass.isInstance(args[0]))

/**
 * Automatic lookup is fail-open for a missing native pointer and opt-in only
 * when the parser seam proved that the original document is not Word-timed or
 * is a foreign Word-timed document without a translation track. An unobserved
 * live pointer is left untouched rather than guessing.
 */
internal fun shouldTryAutoLyrics(
    original: Any?,
    metadata: TtmlDocumentMetadata?,
): Boolean = original == null ||
    shouldTryAutoLyricsForMetadata(metadata)

internal fun shouldTryAutoLyricsForMetadata(metadata: TtmlDocumentMetadata?): Boolean =
    metadata?.timingMode == TtmlTimingMode.NON_WORD || metadata?.needsTranslationFallback == true

internal fun shouldPrepareAutomaticLyrics(
    manualReplacement: Any?,
    autoEligible: Boolean,
): Boolean = manualReplacement == null && autoEligible

internal fun shouldExposeCustomLyrics(
    nativeLyricsAvailable: Boolean,
    appleMusicId: Long?,
    replacementReady: Boolean,
    replacementPending: Boolean = false,
): Boolean = nativeLyricsAvailable ||
    (appleMusicId != null && appleMusicId > 0L && (replacementReady || replacementPending))
