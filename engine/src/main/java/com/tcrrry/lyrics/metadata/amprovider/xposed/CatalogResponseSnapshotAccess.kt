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

internal fun AppleInternalCatalogResolver.snapshotCatalogResponse(
        response: Any,
        entityType: LocalizedEntityType,
    ): CatalogResponseSnapshot {
        val data = AppleReflection.call(
            response,
            catalogMember(AppleMusicRuntimeMember.CATALOG_RESPONSE_DATA_METHOD),
        )
        return CatalogResponseSnapshot(
            entities = collectionValues(data).mapNotNull { entity ->
                snapshotCatalogEntity(entity, entityType)
            }.toList(),
        )
    }


internal fun AppleInternalCatalogResolver.snapshotCatalogEntity(
        entity: Any,
        entityType: LocalizedEntityType,
    ): CatalogEntitySnapshot? {
        val id = (AppleReflection.call(
            entity,
            catalogMember(AppleMusicRuntimeMember.CATALOG_ENTITY_ID_METHOD),
        ) as? String)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
        val attributes = AppleReflection.call(
            entity,
            catalogMember(AppleMusicRuntimeMember.CATALOG_ENTITY_ATTRIBUTES_METHOD),
        ) ?: return null
        val rawAttributes = AppleMediaApiAttributeSnapshots.get(attributes)
        val title = if (rawAttributes != null) {
            rawAttributes.name?.trim().orEmpty()
        } else {
            (AppleReflection.call(
                attributes,
                catalogMember(AppleMusicRuntimeMember.CATALOG_ATTRIBUTES_NAME_METHOD),
            ) as? String)?.trim().orEmpty()
        }
        val attributeArtist = if (rawAttributes != null) {
            rawAttributes.artistName?.trim().orEmpty()
        } else runCatching {
            (AppleReflection.call(
                attributes,
                catalogMember(AppleMusicRuntimeMember.CATALOG_ATTRIBUTES_ARTIST_NAME_METHOD),
            ) as? String)?.trim().orEmpty()
        }.getOrDefault("")
        val albumName = if (entityType == LocalizedEntityType.ARTIST) {
            ""
        } else if (rawAttributes != null) {
            rawAttributes.albumName?.trim().orEmpty()
        } else runCatching {
            (AppleReflection.call(
                attributes,
                catalogMember(AppleMusicRuntimeMember.CATALOG_ATTRIBUTES_ALBUM_NAME_METHOD),
            ) as? String)?.trim().orEmpty()
        }.getOrDefault("")
        val relationshipArtists = if (entityType == LocalizedEntityType.ARTIST) {
            emptyList()
        } else runCatching {
            @Suppress("UNCHECKED_CAST")
            val relationships = AppleReflection.call(
                entity,
                catalogMember(AppleMusicRuntimeMember.CATALOG_ENTITY_RELATIONSHIPS_METHOD),
            ) as? Map<String, Any?>
            val artistRelationship = relationships?.get("artists")
                ?: relationships?.get("artist")
            val artistEntities = collectionValues(
                artistRelationship?.let {
                    AppleReflection.call(
                        it,
                        catalogMember(
                            AppleMusicRuntimeMember.CATALOG_RELATIONSHIP_ENTITIES_METHOD,
                        ),
                    ) ?: AppleReflection.call(
                        it,
                        catalogMember(AppleMusicRuntimeMember.CATALOG_RELATIONSHIP_DATA_METHOD),
                    )
                },
            )
            artistEntities.mapNotNull { artistEntity ->
                val artistAttributes = AppleReflection.call(
                    artistEntity,
                    catalogMember(AppleMusicRuntimeMember.CATALOG_ENTITY_ATTRIBUTES_METHOD),
                )
                val rawArtistAttributes = artistAttributes?.let(
                    AppleMediaApiAttributeSnapshots::get,
                )
                val artistName = if (rawArtistAttributes != null) {
                    rawArtistAttributes.name
                } else artistAttributes?.let {
                    AppleReflection.call(
                        it,
                        catalogMember(AppleMusicRuntimeMember.CATALOG_ATTRIBUTES_NAME_METHOD),
                    ) as? String
                }
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
                val artistId = (AppleReflection.call(
                    artistEntity,
                    catalogMember(AppleMusicRuntimeMember.CATALOG_ENTITY_ID_METHOD),
                ) as? String)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
                if (artistName == null && artistId == null) null
                else CatalogArtistSnapshot(id = artistId, name = artistName)
            }
        }.getOrDefault(emptyList())
        val isrc = if (entityType == LocalizedEntityType.SONG) {
            runCatching {
                (AppleReflection.call(
                    attributes,
                    catalogMember(AppleMusicRuntimeMember.CATALOG_ATTRIBUTES_ISRC_METHOD),
                ) as? String)
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
            }.getOrNull()
        } else {
            null
        }
        val genres = if (entityType != LocalizedEntityType.ARTIST) {
            runCatching {
                collectionValues(
                    AppleReflection.call(
                        attributes,
                        catalogMember(AppleMusicRuntimeMember.CATALOG_ATTRIBUTES_GENRE_NAMES_METHOD),
                    ),
                ).mapNotNull { value ->
                    value.toString().trim().takeIf(String::isNotEmpty)
                }
            }.getOrDefault(emptyList()).ifEmpty {
                runCatching {
                    (AppleReflection.call(
                        attributes,
                        catalogMember(AppleMusicRuntimeMember.CATALOG_ATTRIBUTES_GENRE_NAME_METHOD),
                    ) as? String)
                        ?.trim()
                        ?.takeIf(String::isNotEmpty)
                        ?.let(::listOf)
                        .orEmpty()
                }.getOrDefault(emptyList())
            }
        } else {
            emptyList()
        }
        if (title.isEmpty() && attributeArtist.isEmpty() && isrc == null) return null
        return CatalogEntitySnapshot(
            id = id,
            title = title,
            attributeArtist = attributeArtist,
            albumName = albumName,
            isrc = isrc,
            genres = genres.toList(),
            relationshipArtists = relationshipArtists.toList(),
        )
    }


internal fun AppleInternalCatalogResolver.parseCatalogSong(response: CatalogResponseSnapshot, language: String): CatalogSong? =
        parseCatalogSongs(response, language).firstOrNull()


internal fun AppleInternalCatalogResolver.parseCatalogSongs(
        response: CatalogResponseSnapshot,
        language: String,
    ): List<CatalogSong> = parseCatalogEntities(response, language, LocalizedEntityType.SONG)


internal fun AppleInternalCatalogResolver.parseCatalogEntities(
        response: CatalogResponseSnapshot,
        language: String,
        entityType: LocalizedEntityType,
    ): List<CatalogSong> = response.entities.mapNotNull { entity ->
        parseCatalogEntity(entity, language, entityType)
    }

    /** Pure conversion from the immutable host snapshot; safe to run off the main thread. */

internal fun AppleInternalCatalogResolver.parseCatalogEntity(
        entity: CatalogEntitySnapshot,
        language: String,
        entityType: LocalizedEntityType,
    ): CatalogSong? {
        val album = when (entityType) {
            LocalizedEntityType.SONG -> entity.albumName
            LocalizedEntityType.ALBUM -> entity.title
            LocalizedEntityType.ARTIST -> ""
        }
        val relationshipArtists = entity.relationshipArtists.mapNotNull(CatalogArtistSnapshot::name)
        val relationshipArtistIds = entity.relationshipArtists.mapNotNull(CatalogArtistSnapshot::id)
            .distinct()
        val artist = when (entityType) {
            LocalizedEntityType.ARTIST -> entity.title
            else -> selectLocalizedArtistName(
                attributeArtist = entity.attributeArtist,
                relationshipArtists = relationshipArtists,
                language = language,
            )
        }
        if (relationshipArtists.isNotEmpty() && artist != entity.attributeArtist) {
            ProviderLogger.info(
                "Apple 歌手关系名称已优先: attributes=${entity.attributeArtist}, relationship=$artist",
            )
        }
        val isrc = entity.isrc.takeIf { entityType == LocalizedEntityType.SONG }
        if (entity.title.isEmpty() && artist.isEmpty() && isrc == null) return null
        return CatalogSong(
            id = entity.id,
            alias = Alias(entity.title, artist, language, album),
            isrc = isrc,
            genres = if (entityType == LocalizedEntityType.ARTIST) emptyList() else entity.genres,
            artistIds = if (entityType == LocalizedEntityType.ARTIST) {
                emptyList()
            } else {
                relationshipArtistIds
            },
        )
    }


internal fun AppleInternalCatalogResolver.collectionValues(value: Any?): List<Any> = when (value) {
        is Array<*> -> value.filterNotNull()
        is Iterable<*> -> value.filterNotNull()
        is Map<*, *> -> value.values.filterNotNull()
        else -> emptyList()
    }

