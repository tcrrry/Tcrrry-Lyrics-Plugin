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

internal fun AppleInternalCatalogResolver.queryById(
        mediaId: String,
        language: String?,
        onResult: (CatalogSong?) -> Unit
    ) {
        val queryParams = linkedMapOf(
            "ids" to mediaId,
            "platform" to "android",
            "include[songs]" to "artists"
        )
        language?.let { queryParams["l"] = it }
        query(
            storefront = language?.let(::storefrontForLanguage),
            language = language,
            description = "id=$mediaId",
            path = "songs",
            queryParams = queryParams,
            onResult = onResult
        )
    }


internal fun AppleInternalCatalogResolver.queryByConfiguredRegion(
        mediaIds: List<String>,
        entityType: LocalizedEntityType,
        storefront: String,
        language: String,
        onResult: (Map<String, CatalogSong>) -> Unit,
    ) {
        val queryParams = linkedMapOf(
            "ids" to mediaIds.joinToString(","),
            "l" to language,
            "platform" to "android",
        )
        if (entityType != LocalizedEntityType.ARTIST) {
            queryParams["include[${entityType.path}]"] = "artists"
        }
        queryResponse(
            storefront = storefront,
            language = language,
            description = "localized-${entityType.path}-ids=${mediaIds.size}",
            path = entityType.path,
            queryParams = queryParams,
            snapshotEntityType = entityType,
            transformOffMain = { snapshot ->
                runCatching {
                    snapshot
                        ?.let { parseCatalogEntities(it, language, entityType) }
                        .orEmpty()
                        .mapNotNull { song -> song.id?.let { it to song } }
                        .toMap()
                }.onFailure { error ->
                    ProviderLogger.error(
                        "Apple 地区批量元数据响应解析失败: entityType=$entityType, " +
                            "ids=${mediaIds.size}, storefront=$storefront, language=$language",
                        error,
                    )
                }.getOrDefault(emptyMap())
            },
        ) { songs ->
            val byId = songs.orEmpty()
            // Identity cache writes are bounded but may fan out over a 50-item response.  Keep
            // that preprocessing off the UI thread and publish only the immutable map/callback.
            catalogBackgroundExecutor.execute {
                runCatching {
                    byId.forEach(::rememberCatalogIdentity)
                }.onFailure { error ->
                    ProviderLogger.error(
                        "Apple 地区批量元数据缓存预处理失败: entityType=$entityType, " +
                            "ids=${mediaIds.size}, storefront=$storefront, language=$language",
                        error,
                    )
                }
                mainHandler.post {
                    ProviderLogger.info(
                        "Apple 地区批量元数据候选: entityType=$entityType, " +
                            "requested=${mediaIds.size}, resolved=${byId.size}, " +
                            "storefront=$storefront, language=$language"
                    )
                    onResult(byId)
                }
            }
        }
    }


internal fun AppleInternalCatalogResolver.queryByIsrc(
        isrc: String,
        language: String,
        storefrontOverride: String? = null,
        onResult: (CatalogSong?) -> Unit
    ) {
        val queryParams = linkedMapOf(
            "filter[isrc]" to isrc,
            "l" to language,
            "platform" to "android",
            "include[songs]" to "artists",
            "limit" to "1"
        )
        query(
            storefront = storefrontOverride ?: storefrontForLanguage(language),
            language = language,
            description = "isrc=$isrc",
            path = "songs",
            queryParams = queryParams,
            onResult = onResult
        )
    }


internal fun AppleInternalCatalogResolver.query(
        storefront: String?,
        language: String?,
        description: String,
        path: String,
        queryParams: Map<String, String>,
        onResult: (CatalogSong?) -> Unit
    ) {
        queryResponse(
            storefront = storefront,
            language = language,
            description = description,
            path = path,
            queryParams = queryParams,
            transformOffMain = { snapshot ->
                runCatching {
                    snapshot?.let {
                        parseCatalogSong(it, language ?: CURRENT_LANGUAGE)
                    }
                }.onFailure { error ->
                    ProviderLogger.error(
                        "Apple 内部原名响应解析失败: $description, language=$language",
                        error,
                    )
                }.getOrNull()
            },
        ) { song ->
            ProviderLogger.info(
                "Apple 内部原名候选: $description, storefront=$storefront, " +
                    "language=$language, value=${song?.alias?.title}/${song?.alias?.artist}, " +
                    "isrc=${song?.isrc}"
            )
            onResult(song)
        }
    }

    /**
     * The host query itself intentionally remains in the main adapter: Apple Music's MediaApi,
     * storefront mutation, and Continuation contract are not proven thread-safe.  After a result
     * arrives, [AppleCatalogResponseWorkDispatcher] copies host values on main and runs
     * [transformOffMain] on the Catalog CPU executor before publishing the existing main-thread
     * callback.
     */

internal fun <Result> AppleInternalCatalogResolver.queryResponse(
        storefront: String?,
        language: String?,
        description: String,
        path: String,
        queryParams: Map<String, String>,
        snapshotEntityType: LocalizedEntityType = LocalizedEntityType.SONG,
        transformOffMain: (CatalogResponseSnapshot?) -> Result,
        onResult: (Result?) -> Unit,
    ) {
        val diagnosticRequestId = catalogDiagnosticSequence.incrementAndGet().toString(36)
        val queuedAtMs = SystemClock.uptimeMillis()
        logCatalogRequestDiagnostic(
            requestId = diagnosticRequestId,
            event = "queued",
            description = description,
            storefront = storefront,
            language = language,
            elapsedMs = 0L,
        )
        mainHandler.post {
            var requestToken: String? = null
            val completed = AtomicBoolean(false)
            var slowResponse: Runnable? = null
            var timeout: Runnable? = null

            val responseDiagnostic = AtomicReference<String?>(null)
            val responseTask = catalogResponseDispatcher.newTask<Any, CatalogResponseSnapshot, Result>(
                snapshotOnMain = { response ->
                    val snapshot = response?.let {
                        snapshotCatalogResponse(it, snapshotEntityType)
                    }
                    responseDiagnostic.set(catalogResponseDiagnostic(response, snapshot))
                    snapshot
                },
                transformOffMain = transformOffMain,
                publishOnMain = { result ->
                    logCatalogRequestDiagnostic(
                        requestId = diagnosticRequestId,
                        event = "response",
                        description = description,
                        storefront = storefront,
                        language = language,
                        requestToken = requestToken,
                        elapsedMs = SystemClock.uptimeMillis() - queuedAtMs,
                        detail = responseDiagnostic.get(),
                    )
                    onResult(result)
                },
                failOnMain = { error ->
                    ProviderLogger.error(
                        "Apple 内部目录响应后台处理失败: $description, language=$language",
                        error,
                    )
                    logCatalogRequestDiagnostic(
                        requestId = diagnosticRequestId,
                        event = "transform_failed",
                        description = description,
                        storefront = storefront,
                        language = language,
                        requestToken = requestToken,
                        elapsedMs = SystemClock.uptimeMillis() - queuedAtMs,
                        detail = "error=${error.javaClass.name}:${error.message}",
                    )
                    onResult(null)
                },
            )

            fun finish(response: Any?, event: String = "response") {
                if (!completed.compareAndSet(false, true)) {
                    logCatalogRequestDiagnostic(
                        requestId = diagnosticRequestId,
                        event = "late_$event",
                        description = description,
                        storefront = storefront,
                        language = language,
                        requestToken = requestToken,
                        elapsedMs = SystemClock.uptimeMillis() - queuedAtMs,
                        detail = "late_response_callback",
                    )
                    return
                }
                slowResponse?.let(mainHandler::removeCallbacks)
                timeout?.let(mainHandler::removeCallbacks)
                requestToken?.let(pendingCatalogRequests::remove)
                if (!responseTask.submit(response)) {
                    logCatalogRequestDiagnostic(
                        requestId = diagnosticRequestId,
                        event = "late_$event",
                        description = description,
                        storefront = storefront,
                        language = language,
                        requestToken = requestToken,
                        elapsedMs = SystemClock.uptimeMillis() - queuedAtMs,
                        detail = "response_task_rejected",
                    )
                }
            }

            fun fail(event: String, error: Throwable) {
                if (!completed.compareAndSet(false, true)) {
                    logCatalogRequestDiagnostic(
                        requestId = diagnosticRequestId,
                        event = "late_$event",
                        description = description,
                        storefront = storefront,
                        language = language,
                        requestToken = requestToken,
                        elapsedMs = SystemClock.uptimeMillis() - queuedAtMs,
                        detail = "error=${error.javaClass.name}:${error.message}",
                    )
                    return
                }
                slowResponse?.let(mainHandler::removeCallbacks)
                timeout?.let(mainHandler::removeCallbacks)
                requestToken?.let(pendingCatalogRequests::remove)
                ProviderLogger.error(
                    "Apple 内部目录直连查询失败: $description, language=$language",
                    error,
                )
                logCatalogRequestDiagnostic(
                    requestId = diagnosticRequestId,
                    event = event,
                    description = description,
                    storefront = storefront,
                    language = language,
                    requestToken = requestToken,
                    elapsedMs = SystemClock.uptimeMillis() - queuedAtMs,
                    detail = "error=${error.javaClass.name}:${error.message}",
                )
                // Continuations may resume from an Apple network thread.  Preserve the resolver
                // callback contract by publishing failures on the host main executor as well.
                mainHandler.post { onResult(null) }
            }

            runCatching {
                val access = catalogAccess ?: createCatalogAccess().also { catalogAccess = it }
                val localization = if (storefront != null && language != null) {
                    CatalogRequestLocalization(storefront, language)
                } else {
                    null
                }
                if (localization != null) {
                    requestToken = catalogRequestSequence.incrementAndGet().toString(36)
                    pendingCatalogRequests[requestToken] = localization
                }
                val directQueryParams = LinkedHashMap(queryParams)
                requestToken?.let { token ->
                    directQueryParams[CATALOG_REQUEST_TOKEN_PARAM] = token
                }
                slowResponse = Runnable {
                    if (completed.get()) return@Runnable
                    logCatalogRequestDiagnostic(
                        requestId = diagnosticRequestId,
                        event = "slow_response",
                        description = description,
                        storefront = storefront,
                        language = language,
                        requestToken = requestToken,
                        elapsedMs = SystemClock.uptimeMillis() - queuedAtMs,
                        detail = "mode=direct-network, slowMs=$QUERY_SLOW_RESPONSE_MS",
                    )
                }.also { mainHandler.postDelayed(it, QUERY_SLOW_RESPONSE_MS) }
                timeout = Runnable {
                    if (!completed.compareAndSet(false, true)) return@Runnable
                    responseTask.cancel()
                    requestToken?.let(pendingCatalogRequests::remove)
                    logCatalogRequestDiagnostic(
                        requestId = diagnosticRequestId,
                        event = "timeout",
                        description = description,
                        storefront = storefront,
                        language = language,
                        requestToken = requestToken,
                        elapsedMs = SystemClock.uptimeMillis() - queuedAtMs,
                        detail = "mode=direct-network, timeoutMs=$QUERY_TIMEOUT_MS",
                    )
                    onResult(null)
                }.also { mainHandler.postDelayed(it, QUERY_TIMEOUT_MS) }

                val continuation = createDirectCatalogContinuation(
                    access = access,
                    onSuccess = { response -> finish(response) },
                    onFailure = { error -> fail("request_failed", error) },
                )
                val previousStorefront = access.storefrontField.get(access.mediaApi) as? String
                val directResult = try {
                    activeCatalogRequest.set(localization)
                    localization?.let {
                        access.storefrontField.set(access.mediaApi, it.storefront)
                    }
                    access.directQueryMethod.invoke(
                        access.mediaApi,
                        path,
                        directQueryParams,
                        continuation,
                    )
                } finally {
                    activeCatalogRequest.remove()
                    if (localization != null) {
                        access.storefrontField.set(access.mediaApi, previousStorefront)
                    }
                }
                logCatalogRequestDiagnostic(
                    requestId = diagnosticRequestId,
                    event = "observing",
                    description = description,
                    storefront = storefront,
                    language = language,
                    requestToken = requestToken,
                    elapsedMs = SystemClock.uptimeMillis() - queuedAtMs,
                    detail = "mode=direct-network, path=$path, " +
                        "suspended=${isCoroutineSuspended(directResult)}",
                )
                if (!isCoroutineSuspended(directResult)) {
                    finish(directResult)
                }
            }.onFailure { error ->
                activeCatalogRequest.remove()
                fail("start_failed", error)
            }
        }
    }


internal fun AppleInternalCatalogResolver.createDirectCatalogContinuation(
        access: CatalogAccess,
        onSuccess: (Any?) -> Unit,
        onFailure: (Throwable) -> Unit,
    ): Any = Proxy.newProxyInstance(
        access.continuationType.classLoader ?: classLoader,
        arrayOf(access.continuationType),
    ) { proxy, method, args ->
        when (method.name) {
            "getContext" -> access.emptyCoroutineContext
            "resumeWith" -> {
                val result = args?.firstOrNull()
                val failure = coroutineResultFailure(result)
                if (failure != null) onFailure(failure) else onSuccess(result)
                null
            }
            "equals" -> proxy === args?.firstOrNull()
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "AppleCatalogContinuation"
            else -> null
        }
    }


internal fun AppleInternalCatalogResolver.coroutineResultFailure(result: Any?): Throwable? {
        if (result is Throwable) return result
        val value = result ?: return null
        // Kotlin's Result.Failure is the only wrapper whose field we need to inspect.  Do not
        // reflect arbitrary Apple response objects from a continuation/network thread.
        if (value.javaClass.name != "kotlin.Result\$Failure") return null
        val fields = value.javaClass.declaredFields.filterNot { field ->
            Modifier.isStatic(field.modifiers)
        }
        if (fields.size != 1) return null
        val field = fields.single()
        if (!Throwable::class.java.isAssignableFrom(field.type)) return null
        field.isAccessible = true
        return field.get(value) as? Throwable
    }


internal fun AppleInternalCatalogResolver.createCatalogAccess(): CatalogAccess {
        val resolvedHolder = resolvedCatalogHolder
        val holderClass = resolvedHolder.clazz
        val mediaApi = NativeCatalogAccessContract.mediaApi(
            holderClass,
            resolvedHolder.target.runtimeMemberName(
                AppleMusicRuntimeMember.MEDIA_API_HOLDER_GET_MEDIA_API_METHOD
            ),
        )
        val storefrontField = NativeCatalogAccessContract.storefrontField(
            mediaApi,
            resolvedHolder.target.runtimeMemberName(
                AppleMusicRuntimeMember.MEDIA_API_STOREFRONT_FIELD
            ),
        )
        val directQueryMethod = findDirectCatalogQueryMethod(
            clazz = mediaApi.javaClass,
            methodName = resolvedHolder.target.runtimeMemberName(
                AppleMusicRuntimeMember.MEDIA_API_DIRECT_QUERY_METHOD
            ),
        )
        val continuationType = directQueryMethod.parameterTypes[2]
        val coroutineContextType = continuationType.methods.firstOrNull { method ->
            method.name == "getContext" && method.parameterCount == 0
        }?.returnType ?: error("Apple Continuation context type unavailable")
        val emptyCoroutineContext = createEmptyCoroutineContext(coroutineContextType)
        return CatalogAccess(
            mediaApi = mediaApi,
            storefrontField = storefrontField,
            directQueryMethod = directQueryMethod,
            continuationType = continuationType,
            emptyCoroutineContext = emptyCoroutineContext,
        )
    }


internal fun AppleInternalCatalogResolver.findDirectCatalogQueryMethod(clazz: Class<*>, methodName: String): Method {
        return AppleCatalogQueryMethod.resolve(clazz, methodName)
    }


internal fun AppleInternalCatalogResolver.createEmptyCoroutineContext(contextType: Class<*>): Any =
        Proxy.newProxyInstance(
            contextType.classLoader ?: classLoader,
            arrayOf(contextType),
        ) { proxy, method, args ->
            when (method.name) {
                "fold" -> args?.firstOrNull()
                "get" -> null
                "minusKey" -> proxy
                "plus" -> args?.firstOrNull()
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> 0
                "toString" -> "EmptyCoroutineContext"
                else -> null
            }
        }


internal fun AppleInternalCatalogResolver.findField(instance: Any, name: String): Field {
        var current: Class<*>? = instance.javaClass
        while (current != null) {
            current.declaredFields.firstOrNull { field -> field.name == name }?.let { field ->
                field.isAccessible = true
                return field
            }
            current = current.superclass
        }
        error("${instance.javaClass.name}#$name unavailable")
    }


internal fun AppleInternalCatalogResolver.logCatalogRequestDiagnostic(
        requestId: String,
        event: String,
        description: String,
        storefront: String?,
        language: String?,
        elapsedMs: Long,
        requestToken: String? = null,
        detail: String? = null,
    ) {
        if (!BuildConfig.DEBUG) return
        val localizedState = synchronized(localizedPending) {
            "${localizedPending.size}/$localizedBatchesRunning"
        }
        val originalState = synchronized(originalEntityPending) {
            "${originalEntityPending.size}/$originalEntityBatchesRunning"
        }
        ProviderLogger.diagnostic(
            "AppleCatalogRequest: id=$requestId, token=${requestToken ?: "none"}, " +
                "event=$event, description=$description, storefront=$storefront, " +
                "language=$language, elapsedMs=$elapsedMs, " +
                "localizedPendingRunning=$localizedState, " +
                "originalPendingRunning=$originalState" +
                detail?.let { ", $it" }.orEmpty()
        )
    }


internal fun AppleInternalCatalogResolver.catalogResponseDiagnostic(
        response: Any?,
        snapshot: CatalogResponseSnapshot?,
    ): String {
        if (response == null) return "value=null"
        // This diagnostic intentionally avoids touching the host response.  The snapshot has
        // already copied the only useful cardinality while all reflection was on the main thread.
        return "valueClass=${response.javaClass.name}, " +
            "dataSize=${snapshot?.entities?.size ?: "unknown"}"
    }


internal fun AppleInternalCatalogResolver.storefrontForLanguage(language: String): String =
        storefrontForOriginalLanguage(language)
            ?: error("Unsupported Apple storefront language: $language")

    /**
     * Copies the host response while still on the host's required main thread.  Nothing returned
     * from this method retains a reference to an Apple Music response/entity/attributes object;
     * parsing, language selection, and candidate matching consume only these immutable values on
     * the Catalog CPU executor.
     */

internal fun AppleInternalCatalogResolver.catalogMember(member: AppleMusicRuntimeMember): String =
        resolvedCatalogHolder.target.runtimeMemberName(member)

