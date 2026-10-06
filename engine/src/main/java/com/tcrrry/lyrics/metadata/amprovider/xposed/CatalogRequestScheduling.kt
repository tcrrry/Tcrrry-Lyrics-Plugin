package com.tcrrry.lyrics.metadata.amprovider.xposed

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.tcrrry.lyrics.metadata.hyper.BuildConfig
import com.tcrrry.lyrics.metadata.hyper.common.RootConstants
import com.tcrrry.lyrics.metadata.hyper.common.lyric.AppleOriginalMetadataPolicy
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.CatalogAccess
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.CatalogResponseSnapshot
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.CatalogEntitySnapshot
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.CatalogArtistSnapshot
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.CatalogIdentity
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.PreparedOriginalResolution
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.CatalogSong
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.LocalizedRequest
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.LockedIsrcFallbackTask
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.OriginalEntityRequest
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.CatalogRequestLocalization
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.Alias
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.OriginalResolution
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.LocalizedLookup
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.RequestPriority
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.LocalizedEntityType
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.Companion.CURRENT_LANGUAGE
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.Companion.CACHE_SIZE
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.Companion.LOCALIZED_CACHE_SIZE
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.Companion.LOCALIZED_ARTIST_ALIAS_CACHE_SIZE
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.Companion.REQUEST_PRIORITY_CACHE_SIZE
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.Companion.ORIGINAL_METADATA_CACHE_SCHEMA
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.Companion.LOCALIZED_BATCH_SIZE
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.Companion.MAX_LOCALIZED_BATCHES_RUNNING
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.Companion.MAX_BACKGROUND_LOCALIZED_BATCHES_RUNNING
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.Companion.MAX_LOCKED_ISRC_FALLBACK_RUNNING
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.Companion.LOCALIZED_BATCH_DELAY_MS
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.Companion.ORIGINAL_ENTITY_BATCH_SIZE
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.Companion.MAX_ORIGINAL_ENTITY_BATCHES_RUNNING
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.Companion.MAX_BACKGROUND_ORIGINAL_ENTITY_BATCHES_RUNNING
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.Companion.ORIGINAL_ENTITY_BATCH_DELAY_MS
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.Companion.QUERY_SLOW_RESPONSE_MS
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.Companion.QUERY_TIMEOUT_MS
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.Companion.ARTIST_ALIAS_CACHE_SCHEMA
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.Companion.CATALOG_REQUEST_TOKEN_PARAM
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.Companion.ORIGINAL_LANGUAGE_PROBE_ORDER
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.Companion.COLLABORATION_ARTIST_PATTERNS
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.Companion.collaborationArtistCache

import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.Companion.isCoroutineSuspended
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.Companion.originalSongCacheKey
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.Companion.originalDirectEntityCacheKey
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.Companion.legacyAmbiguousSongCacheKey
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.Companion.originalEntityCacheKey
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.Companion.originalEntityCacheLookupKeys
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.Companion.localizedMetadataCacheKey
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.Companion.isLocalizedArtistAliasCacheKey
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.Companion.originalLanguageCacheKeyVariants
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.Companion.selectNextRequestIndex
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.Companion.canStartRequest
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.Companion.higherPriority
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.Companion.priorityForRequestScope
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.Companion.normalizeRequestScopeIds
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.Companion.languageTagsForGenre
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.Companion.knownLanguageTagsForGenre
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.Companion.languageTagsForIsrc
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.Companion.languageTagsForOriginalMetadata
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.Companion.languageTagsForOriginalMetadata
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.Companion.canonicalOriginalLanguage
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.Companion.supportedOriginalLanguageOrNull
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.Companion.storefrontForOriginalLanguage
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.Companion.isLegacyTraditionalChineseLanguage
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.Companion.canonicalCachedOriginalAlias
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.Companion.regionalOriginalAliases
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.Companion.isAcceptableOriginalAlias
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.Companion.selectExactOriginalEntityAlias
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.Companion.selectExactIdentityAlias
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.Companion.containsHanCharacters
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.Companion.hasCjkArtistScript
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.Companion.containsJapaneseKana
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.Companion.containsHangul
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.Companion.storefrontForContentUiLanguage
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.Companion.languageTagsForContentUiLanguage
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.Companion.languageTagForContentUiLanguage
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.Companion.localizedStorefrontHeaderValue
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.Companion.normalizedArtistNameKey
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.Companion.artistCacheKey
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.Companion.storefrontFromContentPath
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.Companion.isAccountScopedPlaybackPath
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.Companion.selectLocalizedArtistName
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.Companion.selectOriginalAlias
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.Companion.isConfidentOriginalSongAlias
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.Companion.isReusableOriginalSongAlias
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.Companion.isCollaborationArtistName
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.Companion.isOriginalTitle
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.Companion.shouldResolve
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.Companion.nonLatinLetterCount
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.Companion.normalize
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.Companion.shouldCacheCatalogIdentity
import com.tcrrry.lyrics.metadata.amprovider.xposed.AppleInternalCatalogResolver.Companion.shouldRetryEmptyCatalogIdentity

internal fun AppleInternalCatalogResolver.enqueueOriginalEntityRequest(request: OriginalEntityRequest) {
        val prioritized = request.copy(
            priority = currentRequestPriority(request.mediaId, request.priority),
        )
        val shouldSchedule = synchronized(originalEntityPending) {
            val existing = originalEntityPending[prioritized.requestKey]
            originalEntityPending[prioritized.requestKey] = if (existing == null) {
                prioritized
            } else {
                existing.copy(
                    priority = higherPriority(existing.priority, prioritized.priority),
                    callbacks = existing.callbacks + prioritized.callbacks,
                )
            }
            if (
                originalEntityBatchScheduled ||
                !canStartOriginalEntityBatchLocked()
            ) {
                false
            } else {
                originalEntityBatchScheduled = true
                true
            }
        }
        if (shouldSchedule) {
            mainHandler.postDelayed(::processOriginalEntityBatch, ORIGINAL_ENTITY_BATCH_DELAY_MS)
        }
    }


internal fun AppleInternalCatalogResolver.processOriginalEntityBatch() {
        val batch = synchronized(originalEntityPending) {
            originalEntityBatchScheduled = false
            if (
                originalEntityPending.isEmpty() ||
                !canStartOriginalEntityBatchLocked()
            ) return
            val pendingValues = originalEntityPending.values.toList()
            val first = pendingValues[
                selectNextRequestIndex(pendingValues.map(OriginalEntityRequest::priority))
                    ?: return
            ]
            val selected = mutableListOf<OriginalEntityRequest>()
            val selectedIds = linkedSetOf<String>()
            originalEntityPending.values.forEach { request ->
                if (
                    request.priority == first.priority &&
                    request.storefront == first.storefront &&
                    request.language == first.language &&
                    request.entityType == first.entityType
                ) {
                    val newIds = request.lookupIds.filterNot(selectedIds::contains)
                    if (
                        selected.isNotEmpty() &&
                        selectedIds.size + newIds.size > ORIGINAL_ENTITY_BATCH_SIZE
                    ) return@forEach
                    selected += request
                    selectedIds += request.lookupIds
                }
            }
            selected.forEach { originalEntityPending.remove(it.requestKey) }
            originalEntityBatchesRunning += 1
            if (first.priority == RequestPriority.BACKGROUND) {
                originalEntityBackgroundBatchesRunning += 1
            }
            selected
        }

        val first = batch.first()
        queryByConfiguredRegion(
            mediaIds = batch.flatMap(OriginalEntityRequest::lookupIds).distinct(),
            entityType = first.entityType,
            storefront = first.storefront,
            language = first.language,
        ) { resolved ->
            // Matching IDs and validating language/alias candidates are pure CPU work.  Keep the
            // mutable caches and resolver callbacks on the main executor after this pre-processing.
            catalogBackgroundExecutor.execute {
                val matches = runCatching {
                    val resolvedAliases = resolved.mapValues { it.value.alias }
                    batch.map { request ->
                        request to selectExactOriginalEntityAlias(
                            mediaId = request.mediaId,
                            lookupIds = request.lookupIds,
                            resolved = resolvedAliases,
                            sourceLanguage = request.language,
                        )
                    }
                }.onFailure { error ->
                    ProviderLogger.error("Apple 原地区实体后台候选匹配失败", error)
                }.getOrElse {
                    batch.map { request -> request to null }
                }
                mainHandler.post {
                    matches.forEach { (request, alias) ->
                        if (alias != null) {
                            persistentOriginalCache.put(request.directCacheKey, alias)
                        }
                        ProviderLogger.info(
                            "Apple 原地区实体查询完成: id=${request.mediaId}, " +
                                "entityType=${request.entityType}, language=${request.language}, " +
                                "batch=${batch.size}, priority=${request.priority}, hit=${alias != null}, " +
                                "value=${alias?.title}/${alias?.artist}/${alias?.album}"
                        )
                        request.callbacks.forEach { callback -> callback(alias) }
                    }
                    synchronized(originalEntityPending) {
                        originalEntityBatchesRunning -= 1
                        if (first.priority == RequestPriority.BACKGROUND) {
                            originalEntityBackgroundBatchesRunning -= 1
                        }
                    }
                    scheduleOriginalEntityBatchIfCapacity()
                }
            }
        }
        scheduleOriginalEntityBatchIfCapacity()
    }


internal fun AppleInternalCatalogResolver.scheduleOriginalEntityBatchIfCapacity() {
        val shouldSchedule = synchronized(originalEntityPending) {
            if (
                originalEntityPending.isEmpty() ||
                originalEntityBatchScheduled ||
                !canStartOriginalEntityBatchLocked()
            ) {
                false
            } else {
                originalEntityBatchScheduled = true
                true
            }
        }
        if (shouldSchedule) mainHandler.post(::processOriginalEntityBatch)
    }


internal fun AppleInternalCatalogResolver.canStartOriginalEntityBatchLocked(): Boolean {
        val nextPriority = originalEntityPending.values
            .maxByOrNull { request -> request.priority.ordinal }
            ?.priority
            ?: return false
        return canStartRequest(
            priority = nextPriority,
            totalRunning = originalEntityBatchesRunning,
            backgroundRunning = originalEntityBackgroundBatchesRunning,
            maxRunning = MAX_ORIGINAL_ENTITY_BATCHES_RUNNING,
            maxBackgroundRunning = MAX_BACKGROUND_ORIGINAL_ENTITY_BATCHES_RUNNING,
        )
    }


internal fun AppleInternalCatalogResolver.enqueueLocalizedRequest(request: LocalizedRequest) {
        val prioritized = request.copy(
            priority = currentRequestPriority(request.mediaId, request.priority),
        )
        val shouldSchedule = synchronized(localizedPending) {
            val existing = localizedPending[prioritized.requestKey]
            localizedPending[prioritized.requestKey] = if (existing == null) {
                prioritized
            } else {
                existing.copy(
                    priority = higherPriority(existing.priority, prioritized.priority),
                )
            }
            if (
                localizedBatchScheduled ||
                !canStartLocalizedBatchLocked()
            ) {
                false
            } else {
                localizedBatchScheduled = true
                true
            }
        }
        if (shouldSchedule) {
            mainHandler.postDelayed(::processLocalizedBatch, LOCALIZED_BATCH_DELAY_MS)
        }
    }


internal fun AppleInternalCatalogResolver.processLocalizedBatch() {
        val batch = synchronized(localizedPending) {
            localizedBatchScheduled = false
            if (
                localizedPending.isEmpty() ||
                !canStartLocalizedBatchLocked()
            ) return
            val pendingValues = localizedPending.values.toList()
            val first = pendingValues[
                selectNextRequestIndex(pendingValues.map(LocalizedRequest::priority))
                    ?: return
            ]
            val selected = mutableListOf<LocalizedRequest>()
            val selectedIds = linkedSetOf<String>()
            localizedPending.values.forEach { request ->
                if (
                    request.priority == first.priority &&
                    request.storefront == first.storefront &&
                    request.language == first.language &&
                    request.entityType == first.entityType
                ) {
                    val newIds = request.lookupIds.filterNot(selectedIds::contains)
                    if (selected.isNotEmpty() && selectedIds.size + newIds.size > LOCALIZED_BATCH_SIZE) {
                        return@forEach
                    }
                    selected += request
                    selectedIds += request.lookupIds
                }
            }
            selected.forEach { localizedPending.remove(it.requestKey) }
            localizedBatchesRunning += 1
            if (first.priority == RequestPriority.BACKGROUND) {
                localizedBackgroundBatchesRunning += 1
            }
            selected
        }

        queryByConfiguredRegion(
            mediaIds = batch.flatMap(LocalizedRequest::lookupIds).distinct(),
            entityType = batch.first().entityType,
            storefront = batch.first().storefront,
            language = batch.first().language,
        ) { songs ->
            // Candidate ID matching and empty-alias filtering do not touch host objects.  Keep
            // them off the main thread, then publish the bounded cache/callback mutations there.
            catalogBackgroundExecutor.execute {
                val matches = runCatching {
                    batch.map { request ->
                        val resolvedEntry = request.lookupIds.firstNotNullOfOrNull { lookupId ->
                            songs?.get(lookupId)?.let { lookupId to it }
                        }
                        val alias = resolvedEntry?.second?.alias?.takeIf {
                            it.title.isNotBlank() || it.artist.isNotBlank()
                        }
                        Triple(request, resolvedEntry?.first, alias)
                    }
                }.onFailure { error ->
                    ProviderLogger.error("Apple 地区批量元数据后台候选匹配失败", error)
                }.getOrElse {
                    batch.map { request -> Triple(request, null, null) }
                }
                mainHandler.post {
                    var remaining = matches.size
                    fun finishBatch() {
                        remaining -= 1
                        if (remaining > 0) return
                        synchronized(localizedPending) {
                            localizedBatchesRunning -= 1
                            if (batch.first().priority == RequestPriority.BACKGROUND) {
                                localizedBackgroundBatchesRunning -= 1
                            }
                        }
                        scheduleLocalizedBatchIfCapacity()
                    }
                    matches.forEach { (request, resolvedLookupId, alias) ->
                        if (alias != null) {
                            finishLocalizedRequest(request, resolvedLookupId, alias)
                            finishBatch()
                        } else if (request.entityType == LocalizedEntityType.SONG) {
                            // A storefront can assign a different catalog ID to the same song.
                            // Reuse the original HLE identity/ISRC lookup as a fallback, but keep
                            // this second query on the fixed profile's storefront and language.
                            enqueueLockedIsrcFallback(request) { fallbackLookupId, fallbackAlias ->
                                finishLocalizedRequest(request, fallbackLookupId, fallbackAlias)
                                finishBatch()
                            }
                        } else {
                            finishLocalizedRequest(request, null, null)
                            finishBatch()
                        }
                    }
                }
            }
        }
        scheduleLocalizedBatchIfCapacity()
    }

    /** Enqueues one fixed-region miss for the bounded identity/ISRC fallback scheduler. */

internal fun AppleInternalCatalogResolver.enqueueLockedIsrcFallback(
        request: LocalizedRequest,
        onResolved: (resolvedLookupId: String?, alias: Alias?) -> Unit,
    ) {
        lockedIsrcFallbackPending += LockedIsrcFallbackTask(
            request = request,
            onResolved = onResolved,
        )
        scheduleLockedIsrcFallbacks()
    }


internal fun AppleInternalCatalogResolver.scheduleLockedIsrcFallbacks() {
        while (
            lockedIsrcFallbackRunning < MAX_LOCKED_ISRC_FALLBACK_RUNNING &&
                lockedIsrcFallbackPending.isNotEmpty()
        ) {
            val task = nextLockedIsrcFallbackTask() ?: break
            lockedIsrcFallbackRunning += 1
            resolveLocalizedRequestByLockedIsrc(task.request) { resolvedLookupId, alias ->
                lockedIsrcFallbackRunning = (lockedIsrcFallbackRunning - 1).coerceAtLeast(0)
                task.onResolved(resolvedLookupId, alias)
                scheduleLockedIsrcFallbacks()
            }
        }
    }

    /**
     * Selects the highest effective priority at dispatch time.  The effective value is read from
     * [requestPriorityByMediaId], so a visible-page promotion or a request-scope update also
     * reorders tasks that are already waiting in this fallback queue.  Equal priorities retain
     * insertion order to avoid unnecessary churn.
     */

internal fun AppleInternalCatalogResolver.nextLockedIsrcFallbackTask(): LockedIsrcFallbackTask? {
        val priorities = lockedIsrcFallbackPending.map { task ->
            currentRequestPriority(task.request.mediaId, task.request.priority)
        }
        val index = selectNextRequestIndex(priorities) ?: return null
        return lockedIsrcFallbackPending.removeAt(index)
    }

    /**
     * Resolves a fixed-region song missed by the ID batch through the same identity/ISRC fallback
     * used by the original-region HLE path.  The identity probe is only a module-owned metadata
     * lookup; the follow-up song query still uses [request.language]'s locked storefront.
     */

internal fun AppleInternalCatalogResolver.scheduleLocalizedBatchIfCapacity() {
        val shouldSchedule = synchronized(localizedPending) {
            if (
                localizedPending.isEmpty() ||
                localizedBatchScheduled ||
                !canStartLocalizedBatchLocked()
            ) {
                false
            } else {
                localizedBatchScheduled = true
                true
            }
        }
        if (shouldSchedule) mainHandler.post(::processLocalizedBatch)
    }


internal fun AppleInternalCatalogResolver.canStartLocalizedBatchLocked(): Boolean {
        val nextPriority = localizedPending.values
            .maxByOrNull { request -> request.priority.ordinal }
            ?.priority
            ?: return false
        return canStartRequest(
            priority = nextPriority,
            totalRunning = localizedBatchesRunning,
            backgroundRunning = localizedBackgroundBatchesRunning,
            maxRunning = MAX_LOCALIZED_BATCHES_RUNNING,
            maxBackgroundRunning = MAX_BACKGROUND_LOCALIZED_BATCHES_RUNNING,
        )
    }


internal fun AppleInternalCatalogResolver.updatePendingRequestPriorities(
        scopedPriorities: Map<String, RequestPriority>,
        onlyMediaIds: Set<String>? = null,
    ): Int {
        var changed = 0
        synchronized(localizedPending) {
            localizedPending.entries.forEach { entry ->
                val request = entry.value
                if (onlyMediaIds != null && request.mediaId !in onlyMediaIds) {
                    return@forEach
                }
                val next = scopedPriorities[request.mediaId] ?: RequestPriority.BACKGROUND
                if (request.priority != next) {
                    entry.setValue(request.copy(priority = next))
                    changed += 1
                }
            }
        }
        synchronized(originalEntityPending) {
            originalEntityPending.entries.forEach { entry ->
                val request = entry.value
                if (onlyMediaIds != null && request.mediaId !in onlyMediaIds) {
                    return@forEach
                }
                val next = scopedPriorities[request.mediaId] ?: RequestPriority.BACKGROUND
                if (request.priority != next) {
                    entry.setValue(request.copy(priority = next))
                    changed += 1
                }
            }
        }
        // Fallback tasks read their effective priority when a slot is selected.  Rescheduling
        // here also wakes the queue when a completed task left capacity available during a scope
        // update or explicit promotion.
        scheduleLockedIsrcFallbacks()
        return changed
    }


internal fun AppleInternalCatalogResolver.currentScopedPriority(mediaId: String): RequestPriority =
        synchronized(requestPriorityByMediaId) {
            requestPriorityByMediaId[mediaId.trim()] ?: RequestPriority.BACKGROUND
        }


internal fun AppleInternalCatalogResolver.rememberRequestPriority(mediaId: String, priority: RequestPriority) {
        val normalizedId = mediaId.trim()
        if (normalizedId.isEmpty()) return
        synchronized(requestPriorityByMediaId) {
            if (requestScopeActive) {
                requestPriorityByMediaId.putIfAbsent(
                    normalizedId,
                    RequestPriority.BACKGROUND,
                )
                return
            }
            requestPriorityByMediaId[normalizedId] = higherPriority(
                requestPriorityByMediaId[normalizedId] ?: RequestPriority.BACKGROUND,
                priority,
            )
        }
    }


internal fun AppleInternalCatalogResolver.currentRequestPriority(
        mediaId: String,
        fallback: RequestPriority,
    ): RequestPriority = synchronized(requestPriorityByMediaId) {
        if (requestScopeActive) {
            requestPriorityByMediaId[mediaId.trim()] ?: RequestPriority.BACKGROUND
        } else {
            higherPriority(requestPriorityByMediaId[mediaId.trim()] ?: fallback, fallback)
        }
    }


internal fun AppleInternalCatalogResolver.finishLocalizedRequest(
        request: LocalizedRequest,
        resolvedLookupId: String?,
        alias: Alias?,
    ) {
        if (alias != null) {
            synchronized(localizedCache) { localizedCache[request.cacheKey] = alias }
            persistentLocalizedCache.put(request.cacheKey, alias)
        }
        val callbacks = synchronized(localizedInFlight) {
            localizedInFlight.remove(request.requestKey).orEmpty()
        }
        ProviderLogger.info(
                "Apple 播放元数据地区查询完成: id=${request.mediaId}, " +
                "lookupIds=${request.lookupIds}, resolvedBy=$resolvedLookupId, " +
                "entityType=${request.entityType}, " +
                "selection=${request.selection}, storefront=${request.storefront}, " +
                "language=${request.language}, priority=${request.priority}, " +
                "value=${alias?.title}/${alias?.artist}"
        )
        callbacks.forEach { callback -> callback(alias) }
    }

