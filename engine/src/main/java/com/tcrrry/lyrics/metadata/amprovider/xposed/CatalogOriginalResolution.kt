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

internal fun AppleInternalCatalogResolver.resolveOriginalMetadataFromCatalog(
        metadata: MediaMetadataCache.Metadata,
        allowEmptyIdentityRetry: Boolean = true,
        priority: RequestPriority = RequestPriority.ACTIVE_PAGE,
    ) {
        val fallbackLanguages = if (AppleOriginalMetadataPolicy.isCjkGenre(metadata.genre)) {
            languageTagsForGenre(metadata.genre)
        } else {
            emptyList()
        }
        resolveCatalogIdentity(metadata.id, fallbackLanguages) { identity ->
            identity.fallbackAliases.firstOrNull()?.let { alias ->
                publishOriginalCandidate(metadata.id, alias)
            }
            if (allowEmptyIdentityRetry && shouldRetryEmptyCatalogIdentity(
                    mediaId = metadata.id,
                    title = metadata.title,
                    artist = metadata.artist,
                    genre = metadata.genre,
                    isrc = identity.isrc,
                    catalogGenres = identity.genres,
                )
            ) {
                ProviderLogger.info(
                    "Apple 内部歌曲空身份重试: id=${metadata.id}, " +
                        "title=${metadata.title}, artist=${metadata.artist}"
                )
                mainHandler.post {
                    resolveOriginalMetadataFromCatalog(
                        metadata = metadata,
                        allowEmptyIdentityRetry = false,
                        priority = currentRequestPriority(metadata.id, priority),
                    )
                }
                return@resolveCatalogIdentity
            }
            val results = identity.fallbackAliases.toMutableList()
            val isrc = identity.isrc
            val languages = languageTagsForOriginalMetadata(
                genre = metadata.genre,
                catalogGenres = identity.genres,
                isrc = isrc,
            )
            if (languages.isEmpty()) {
                finishResolve(
                    metadata = metadata,
                    languages = languages,
                    results = results,
                    originKnown = isrc != null,
                    artistIds = identity.artistIds,
                )
                return@resolveCatalogIdentity
            }
            fun queryNext(index: Int) {
                if (index >= languages.size) {
                    finishResolve(
                        metadata = metadata,
                        languages = languages,
                        results = results,
                        originKnown = isrc != null,
                        artistIds = identity.artistIds,
                    )
                    return
                }
                val language = languages[index]
                selectExactIdentityAlias(identity.fallbackAliases, language)?.let { exactAlias ->
                    finishResolve(
                        metadata = metadata,
                        languages = listOf(language),
                        results = listOf(exactAlias),
                        originKnown = true,
                        artistIds = identity.artistIds,
                    )
                    return
                }
                resolveOriginalEntityForLanguage(
                    mediaId = metadata.id,
                    lookupIds = listOf(metadata.id),
                    entityType = LocalizedEntityType.SONG,
                    language = language,
                    priority = currentRequestPriority(metadata.id, priority),
                ) { resolvedAlias ->
                    val exactAlias = resolvedAlias?.takeIf { alias ->
                        (alias.title.isNotBlank() || alias.artist.isNotBlank()) &&
                            isConfidentOriginalSongAlias(
                                alias = alias,
                                localizedTitle = metadata.title.orEmpty(),
                                localizedArtist = metadata.artist.orEmpty(),
                            )
                    }
                    if (exactAlias != null) {
                        val regionalArtistIds =
                            catalogIdentityCache[metadata.id]?.artistIds.orEmpty()
                        finishResolve(
                            metadata = metadata,
                            languages = listOf(language),
                            results = listOf(exactAlias),
                            originKnown = true,
                            artistIds = (
                                identity.artistIds + regionalArtistIds
                            ).distinct(),
                        )
                    } else {
                        if (resolvedAlias != null) {
                            invalidateOriginalEntity(metadata.id, LocalizedEntityType.SONG)
                        }
                        if (isrc == null) {
                            queryNext(index + 1)
                        } else {
                            queryByIsrc(isrc, language) { song ->
                                song?.alias?.let(results::add)
                                queryNext(index + 1)
                            }
                        }
                    }
                }
            }
            queryNext(0)
        }
    }


internal fun AppleInternalCatalogResolver.resolveManyForContentUiLanguageSingleLanguage(
        lookups: Collection<LocalizedLookup>,
        selection: Int,
        priority: RequestPriority,
        storefront: String,
        language: String,
        onResolved: (mediaId: String, alias: Alias?) -> Unit,
        onComplete: () -> Unit = {},
    ) {
        val requests = lookups.asSequence()
            .mapNotNull { lookup ->
                val mediaId = lookup.mediaId.trim()
                if (mediaId.isEmpty() || !mediaId.all(Char::isDigit)) return@mapNotNull null
                val normalizedLookupIds = sequenceOf(mediaId)
                    .plus(lookup.lookupIds.asSequence())
                    .map(String::trim)
                    .filter { it.isNotEmpty() && it.all(Char::isDigit) }
                    .distinct()
                    .take(LOCALIZED_BATCH_SIZE)
                    .toList()
                val cacheKey = localizedMetadataCacheKey(
                    selection,
                    lookup.entityType,
                    mediaId,
                    language,
                )
                rememberRequestPriority(mediaId, priority)
                LocalizedRequest(
                    cacheKey = cacheKey,
                    requestKey = "$cacheKey:${normalizedLookupIds.joinToString(",")}".trim(),
                    mediaId = mediaId,
                    lookupIds = normalizedLookupIds,
                    entityType = lookup.entityType,
                    selection = selection,
                    storefront = storefront,
                    language = language,
                    priority = currentRequestPriority(mediaId, priority),
                )
            }
            .distinctBy(LocalizedRequest::requestKey)
            .toList()
        if (requests.isEmpty()) {
            onComplete()
            return
        }

        val requestCompletionCount = AtomicLong(requests.size.toLong())
        val complete: (LocalizedRequest, Alias?) -> Unit = { request, alias ->
            onResolved(request.mediaId, alias)
            if (requestCompletionCount.decrementAndGet() == 0L) onComplete()
        }
        val uncached = mutableListOf<LocalizedRequest>()
        requests.forEach { request ->
            val cached = synchronized(localizedCache) { localizedCache[request.cacheKey] }
            if (cached != null) {
                complete(request, cached)
                return@forEach
            }
            val ownsRequest = synchronized(localizedInFlight) {
                val callbacks = localizedInFlight[request.requestKey]
                if (callbacks != null) {
                    callbacks += { alias -> complete(request, alias) }
                    promotePendingRequests(listOf(request.mediaId), request.priority)
                    false
                } else {
                    localizedInFlight[request.requestKey] =
                        mutableListOf({ alias -> complete(request, alias) })
                    true
                }
            }
            if (ownsRequest) uncached += request
        }
        if (uncached.isEmpty()) return
        persistentLocalizedCache.getMany(uncached.map(LocalizedRequest::cacheKey)) { cached ->
            uncached.forEach { request ->
                val alias = cached[request.cacheKey]
                if (alias != null) finishLocalizedCacheHit(request, alias)
                else enqueueLocalizedRequest(request)
            }
        }
    }


internal fun AppleInternalCatalogResolver.resolveLocalizedRequestByLockedIsrc(
        request: LocalizedRequest,
        onResolved: (resolvedLookupId: String?, alias: Alias?) -> Unit,
    ) {
        val candidateIds = (listOf(request.mediaId) + request.lookupIds)
            .map(String::trim)
            .filter { it.isNotEmpty() && it.all(Char::isDigit) }
            .distinct()
        if (candidateIds.isEmpty()) {
            onResolved(null, null)
            return
        }

        val attemptedIsrcs = mutableSetOf<String>()
        fun queryNext(index: Int) {
            if (index >= candidateIds.size) {
                onResolved(null, null)
                return
            }
            val identityId = candidateIds[index]
            fun queryIdentity(identity: CatalogIdentity?) {
                val isrc = identity?.isrc?.trim()?.takeIf(String::isNotEmpty)
                if (isrc == null || !attemptedIsrcs.add(isrc)) {
                    queryNext(index + 1)
                    return
                }
                ProviderLogger.info(
                    "Apple 固定地区歌曲启用 ISRC 备用查询: id=${request.mediaId}, " +
                        "identityId=$identityId, isrc=$isrc, storefront=${request.storefront}, " +
                        "language=${request.language}",
                )
                queryByIsrc(
                    isrc = isrc,
                    language = request.language,
                    storefrontOverride = request.storefront,
                ) { song ->
                    val alias = song?.alias?.takeIf {
                        it.title.isNotBlank() || it.artist.isNotBlank()
                    }
                    if (alias != null) {
                        onResolved(song?.id, alias)
                    } else {
                        queryNext(index + 1)
                    }
                }
            }
            catalogIdentityCache[identityId]?.let { cached ->
                queryIdentity(cached)
                return
            }
            // Keep the identity probe region-neutral.  It obtains only ISRC/relationship facts;
            // no alias from this account-region response is ever published for fixed mode.
            resolveCatalogIdentity(identityId, emptyList()) { identity ->
                queryIdentity(identity)
            }
        }
        queryNext(0)
    }


internal fun AppleInternalCatalogResolver.finishResolve(
        metadata: MediaMetadataCache.Metadata,
        languages: List<String>,
        results: List<Alias>,
        originKnown: Boolean,
        artistIds: List<String>,
    ) {
        // Canonical language filtering and alias confidence checks are pure operations over
        // immutable DTOs.  Keep cache mutation and completion publication on the host main thread,
        // but do not make the UI wait for this potentially multi-candidate selection pass.
        catalogBackgroundExecutor.execute {
            val prepared = runCatching {
                prepareOriginalResolution(
                    metadata = metadata,
                    languages = languages,
                    results = results,
                    originKnown = originKnown,
                    artistIds = artistIds,
                )
            }.onFailure { error ->
                ProviderLogger.error(
                    "Apple 内部原名候选后台匹配失败: id=${metadata.id}",
                    error,
                )
            }.getOrElse {
                PreparedOriginalResolution(
                    canonicalLanguages = languages.map(String::trim).filter(String::isNotEmpty),
                    sourceLanguage = languages.singleOrNull(),
                    originalAlias = null,
                    resolvedAlbum = null,
                    originKnown = originKnown,
                    artistIds = artistIds,
                )
            }
            mainHandler.post {
                publishOriginalResolution(metadata, prepared)
            }
        }
    }


internal fun AppleInternalCatalogResolver.prepareOriginalResolution(
        metadata: MediaMetadataCache.Metadata,
        languages: List<String>,
        results: List<Alias>,
        originKnown: Boolean,
        artistIds: List<String>,
    ): PreparedOriginalResolution {
        val canonicalLanguages = languages.map(::canonicalOriginalLanguage).distinct()
        val sourceLanguage = canonicalLanguages.singleOrNull()
        val acceptableResults = regionalOriginalAliases(results, canonicalLanguages)
        val selected = selectOriginalAlias(
            variants = acceptableResults,
            localizedTitle = metadata.title.orEmpty(),
            localizedArtist = metadata.artist.orEmpty()
        )
        val confirmedRegionalAlias = if (originKnown) {
            acceptableResults.lastOrNull { alias ->
                canonicalOriginalLanguage(alias.language) in canonicalLanguages &&
                    isConfidentOriginalSongAlias(
                        alias = alias,
                        localizedTitle = metadata.title.orEmpty(),
                        localizedArtist = metadata.artist.orEmpty(),
                    )
            }
        } else {
            null
        }
        val originalAlias = selected ?: confirmedRegionalAlias
        val resolvedAlbum = originalAlbumFromResolution(
            alias = originalAlias,
            acceptableResults = acceptableResults,
        )
        return PreparedOriginalResolution(
            canonicalLanguages = canonicalLanguages,
            sourceLanguage = sourceLanguage,
            originalAlias = originalAlias,
            resolvedAlbum = resolvedAlbum,
            originKnown = originKnown,
            artistIds = artistIds,
        )
    }


internal fun AppleInternalCatalogResolver.publishOriginalResolution(
        metadata: MediaMetadataCache.Metadata,
        prepared: PreparedOriginalResolution,
    ) {
        val originalAlias = prepared.originalAlias
        if (originalAlias != null) {
            synchronized(cache) { cache[metadata.id] = originalAlias }
            persistentOriginalCache.put(originalSongCacheKey(metadata.id), originalAlias)
        }
        discardOriginalCandidates(metadata.id)
        val callbacks = synchronized(inFlight) { inFlight.remove(metadata.id).orEmpty() }
        ProviderLogger.info(
            "Apple 内部原名查询完成: id=${metadata.id}, genre=${metadata.genre}, " +
                "languages=${prepared.canonicalLanguages}, " +
                "selected=${originalAlias?.title}/${originalAlias?.artist}"
        )
        val resolution = OriginalResolution(
            alias = originalAlias,
            language = originalAlias?.language?.takeIf(String::isNotBlank)
                ?: prepared.sourceLanguage,
            originKnown = prepared.originKnown,
            artistIds = prepared.artistIds,
            album = prepared.resolvedAlbum,
        )
        callbacks.forEach { callback -> callback(resolution) }
    }


internal fun AppleInternalCatalogResolver.finishCachedOriginalResolve(mediaId: String, alias: Alias) {
        discardOriginalCandidates(mediaId)
        val callbacks = synchronized(inFlight) { inFlight.remove(mediaId).orEmpty() }
        ProviderLogger.info(
            "Apple 原地区元数据缓存命中: id=$mediaId, language=${alias.language}"
        )
        val resolution = OriginalResolution(
            alias = alias,
            language = alias.language.takeIf(String::isNotBlank),
            originKnown = true,
            artistIds = emptyList(),
            album = alias.album,
        )
        callbacks.forEach { callback -> callback(resolution) }
    }


internal fun AppleInternalCatalogResolver.registerOriginalCandidateCallback(
        mediaId: String,
        callback: (Alias) -> Unit,
    ) {
        synchronized(originalCandidateCallbacks) {
            originalCandidateCallbacks.getOrPut(mediaId) { mutableListOf() }.add(callback)
        }
        catalogIdentityCache[mediaId]
            ?.fallbackAliases
            ?.firstOrNull()
            ?.let { alias -> publishOriginalCandidate(mediaId, alias) }
    }


internal fun AppleInternalCatalogResolver.publishOriginalCandidate(mediaId: String, alias: Alias) {
        if (alias.title.isBlank() && alias.artist.isBlank()) return
        val callbacks = synchronized(originalCandidateCallbacks) {
            originalCandidateCallbacks.remove(mediaId).orEmpty()
        }
        callbacks.forEach { callback -> callback(alias) }
    }


internal fun AppleInternalCatalogResolver.discardOriginalCandidates(mediaId: String) {
        synchronized(originalCandidateCallbacks) {
            originalCandidateCallbacks.remove(mediaId)
        }
    }


internal fun AppleInternalCatalogResolver.resolveCatalogIdentity(
        mediaId: String,
        languages: List<String>,
        onResult: (CatalogIdentity) -> Unit
    ) {
        catalogIdentityCache[mediaId]?.takeIf(::isUsefulCatalogIdentity)?.let {
            onResult(it)
            return
        }
        catalogIdentityCache.remove(mediaId)
        val ownsRequest = synchronized(catalogIdentityInFlight) {
            val callbacks = catalogIdentityInFlight[mediaId]
            if (callbacks != null) {
                callbacks += onResult
                false
            } else {
                catalogIdentityInFlight[mediaId] = mutableListOf(onResult)
                true
            }
        }
        if (!ownsRequest) return

        queryById(mediaId, null) { currentSong ->
            currentSong?.isrc?.let { isrc ->
                ProviderLogger.info("Apple 内部歌曲 ISRC: id=$mediaId, isrc=$isrc")
                finishCatalogIdentity(
                    mediaId,
                    CatalogIdentity(
                        isrc = isrc,
                        fallbackAliases = listOfNotNull(currentSong.alias),
                        genres = currentSong.genres,
                        artistIds = currentSong.artistIds,
                    ),
                )
                return@queryById
            }

            val fallbackAliases = mutableListOf<Alias>().apply {
                currentSong?.alias?.let(::add)
            }
            val fallbackGenres = mutableListOf<String>().apply {
                currentSong?.genres?.let(::addAll)
            }
            fun queryNext(index: Int) {
                if (index >= languages.size) {
                    finishCatalogIdentity(
                        mediaId,
                        CatalogIdentity(
                            isrc = null,
                            fallbackAliases = fallbackAliases,
                            genres = fallbackGenres,
                            artistIds = currentSong?.artistIds.orEmpty(),
                        ),
                    )
                    return
                }
                val language = languages[index]
                queryById(mediaId, language) { song ->
                    song?.alias?.let(fallbackAliases::add)
                    song?.genres?.let(fallbackGenres::addAll)
                    val isrc = song?.isrc
                    if (isrc != null) {
                        ProviderLogger.info(
                            "Apple 内部歌曲 ISRC: id=$mediaId, language=$language, isrc=$isrc"
                        )
                        finishCatalogIdentity(
                            mediaId,
                            CatalogIdentity(
                                isrc = isrc,
                                fallbackAliases = fallbackAliases,
                                genres = fallbackGenres.distinct(),
                                artistIds = (
                                    currentSong?.artistIds.orEmpty() + song.artistIds
                                ).distinct(),
                            ),
                        )
                    } else {
                        queryNext(index + 1)
                    }
                }
            }
            queryNext(0)
        }
    }


internal fun AppleInternalCatalogResolver.rememberCatalogIdentity(mediaId: String, song: CatalogSong) {
        val isrc = song.isrc ?: return
        val identity = CatalogIdentity(
            isrc = isrc,
            fallbackAliases = listOfNotNull(song.alias),
            genres = song.genres,
            artistIds = song.artistIds,
        )
        finishCatalogIdentity(mediaId, identity)
    }


internal fun AppleInternalCatalogResolver.finishCatalogIdentity(mediaId: String, identity: CatalogIdentity) {
        val merged = synchronized(catalogIdentityCache) {
            val previous = catalogIdentityCache[mediaId]
            val next = if (previous == null) identity else {
                CatalogIdentity(
                    isrc = previous.isrc ?: identity.isrc,
                    fallbackAliases = (previous.fallbackAliases + identity.fallbackAliases).distinct(),
                    genres = (previous.genres + identity.genres).distinct(),
                    artistIds = (previous.artistIds + identity.artistIds).distinct(),
                )
            }
            if (isUsefulCatalogIdentity(next)) {
                catalogIdentityCache[mediaId] = next
            } else {
                catalogIdentityCache.remove(mediaId)
            }
            next
        }
        val cacheable = isUsefulCatalogIdentity(merged)
        // The resolver may finish after a different metadata profile has become active. Keep
        // catalog identity facts in this resolver's namespace instead of letting a late callback
        // mutate whichever profile happens to be globally selected at that moment.
        MediaMetadataCache.updateCatalogGenres(
            mediaId = mediaId,
            genres = merged.genres,
            profile = cacheNamespace,
        )
        // Remove and drain the in-flight callbacks on main together with their publication. This
        // prevents a new request from observing a removed key while the background preprocessing
        // result is still waiting in the main queue.
        fun publish() {
            val callbacks = synchronized(catalogIdentityInFlight) {
                catalogIdentityInFlight.remove(mediaId).orEmpty()
            }
            if (callbacks.isNotEmpty()) {
                ProviderLogger.info(
                    "Apple 内部歌曲身份已就绪: id=$mediaId, isrc=${merged.isrc}, " +
                        "genres=${merged.genres}, " +
                        "artistIds=${merged.artistIds}, candidates=${merged.fallbackAliases.size}, " +
                        "cached=$cacheable"
                )
                callbacks.forEach { callback -> callback(merged) }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) publish() else mainHandler.post(::publish)
    }


internal fun AppleInternalCatalogResolver.isUsefulCatalogIdentity(identity: CatalogIdentity): Boolean =
        shouldCacheCatalogIdentity(identity.isrc, identity.genres)

