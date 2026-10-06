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

internal fun AppleInternalCatalogResolver.warmPersistentOriginalCache() {
        if (!persistentLocalizedCacheEnabled) return
        persistentOriginalCache.warmRecentAsync { count ->
            if (count != null) {
                ProviderLogger.info("Apple 原地区元数据缓存预热完成: entries=$count")
            } else {
                ProviderLogger.info("Apple 原地区元数据缓存预热延后")
            }
        }
    }


internal fun AppleInternalCatalogResolver.warmPersistentLocalizedCache(selection: Int) {
        if (!persistentLocalizedCacheEnabled) return
        if (storefrontForContentUiLanguage(selection) == null) return
        val shouldWarm = synchronized(warmedSelections) {
            if (selection in warmedSelections) false
            else synchronized(warmingSelections) { warmingSelections.add(selection) }
        }
        if (!shouldWarm) return
        val prefix = "$selection:"
        persistentLocalizedCache.warmRecentAsync(prefix) { delayedAliases ->
            if (delayedAliases != null) {
                finishPersistentCacheWarm(selection, delayedAliases)
            } else {
                synchronized(warmingSelections) { warmingSelections.remove(selection) }
                ProviderLogger.info("Apple 地区元数据缓存预热延后: selection=$selection")
            }
        }
    }


internal fun AppleInternalCatalogResolver.finishPersistentCacheWarm(selection: Int, aliases: Map<String, Alias>) {
        val artistAliases = aliases.filterKeys(::isLocalizedArtistAliasCacheKey)
        val metadataAliases = aliases.filterKeys { key ->
            !isLocalizedArtistAliasCacheKey(key)
        }
        synchronized(localizedCache) { localizedCache.putAll(metadataAliases) }
        synchronized(localizedArtistAliasCache) {
            localizedArtistAliasCache.putAll(artistAliases)
        }
        synchronized(warmedSelections) { warmedSelections.add(selection) }
        synchronized(warmingSelections) { warmingSelections.remove(selection) }
        ProviderLogger.info(
            "Apple 地区元数据缓存预热完成: selection=$selection, " +
                "metadata=${metadataAliases.size}, artistAlias=${artistAliases.size}, " +
                "entries=${aliases.size}"
        )
    }


internal fun AppleInternalCatalogResolver.finishLocalizedCacheHit(request: LocalizedRequest, alias: Alias) {
        synchronized(localizedCache) { localizedCache[request.cacheKey] = alias }
        val callbacks = synchronized(localizedInFlight) {
            localizedInFlight.remove(request.requestKey).orEmpty()
        }
        ProviderLogger.info(
            "Apple 地区元数据持久缓存命中: id=${request.mediaId}, " +
                "entityType=${request.entityType}, selection=${request.selection}"
        )
        callbacks.forEach { callback -> callback(alias) }
    }

