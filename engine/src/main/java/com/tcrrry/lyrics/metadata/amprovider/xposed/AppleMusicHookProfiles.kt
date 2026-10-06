/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.tcrrry.lyrics.metadata.amprovider.xposed

import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

/** Apple Music 安装包版本，用于选择对应的混淆 Hook 档案。 */
internal data class AppleMusicVersion(
    val versionName: String?,
    val versionCode: Long?,
) {
    val displayName: String
        get() = "${versionName ?: "unknown"} (${versionCode ?: "unknown"})"
}

/**
 * 所有已经确认会随 Apple Music 混淆版本变化的 Hook 入口。
 *
 * 新版 Apple Music 适配应优先只修改本文件中的版本档案；业务 Hook 不应再直接写死这些类名。
 */
internal enum class AppleMusicHookPoint {
    MEDIA_API_LOCALIZATION,
    CONTENT_HTTP_LOCALIZATION,
    EXO_MEDIA_PLAYER,
    EXO_AUDIO_SESSION_ID,
    LOCAL_MEDIA_PLAYER_CONTROLLER_STATE,
    LOCAL_MEDIA_PLAYER_AUDIO_VARIANT_CHANGED,
    DEBUG_ATMOS_MEDIA_CODEC_PERIOD_ID,
    DEBUG_ATMOS_MEDIA_CODEC_INPUT_FORMAT,
    DEBUG_ATMOS_MEDIA_CODEC_AUDIO_SESSION,
    DEBUG_ATMOS_MEDIA_CODEC_OUTPUT_BUFFER,
    DEBUG_ATMOS_SV_AUDIO_PERIOD_ID,
    DEBUG_ATMOS_SV_AUDIO_STREAM_CHANGED,
    DEBUG_ATMOS_SV_AUDIO_SESSION,
    DEBUG_ATMOS_SV_AUDIO_FIRST_BUFFER,
    LOCAL_MEDIA_PLAYER_METADATA_UPDATED,
    LOCAL_MEDIA_PLAYER_INDEX_CHANGED,
    LYRICS_NETWORK_REQUEST,
    LYRICS_COOKIE_JAR,
    EPOXY_FINAL_BIND,
    LYRICS_SOURCE_MENU_CLICK_LISTENER,
    LYRICS_WORD_RENDER_ADAPTER,
    LYRICS_RECYCLER_ADAPTER,
    LYRICS_TRANSLATION_PREFERENCE,
    LYRICS_PRONUNCIATION_PREFERENCE,
    LYRICS_OFFICIAL_PRONUNCIATION_MATCH,
    LYRICS_PREFERRED_LANGUAGES_REQUEST,
    LYRICS_VIEW_MODEL_LOAD,
    LYRICS_VIEW_MODEL_BUILD,
    LYRICS_RESULT_PRESENTATION,
    LYRICS_NATIVE_PRESENTATION,
    LYRICS_UI_ON_CREATE_VIEW,
    LYRICS_UI_ON_RESUME,
    LYRICS_UI_ON_DESTROY_VIEW,
    LYRICS_WORD_VECTOR_CLASS,
    LYRICS_TTML_PARSER,
    LYRICS_AVAILABILITY_HAS_LYRICS,
    LYRICS_AVAILABILITY_TIME_SYNCED,
    PLAYER_LYRICS_AVAILABILITY_CALCULATOR,
    PLAYER_SONG_BINDING_EXECUTE,
    APPLE_CUSTOM_TEXT_VIEW,
    LYRICS_GRADIENT_MASK_UPDATE,
    COMPOSE_TEXT_LAYOUT,
    APPLE_TEXT_STYLE_UTILS,
    IN_APP_ACTION_SHEET_BINDING,
    IN_APP_GLOBAL_METADATA_DISPATCHER,
    IN_APP_NOW_PLAYING_METADATA_LISTENER,
    IN_APP_QUEUE_UPDATE,
    IN_APP_HISTORY_UPDATE,
    IN_APP_QUEUE_ADAPTER_SUBMIT,
    IN_APP_QUEUE_ADAPTER_BIND,
    CONTENT_ITEM_METADATA_CLASSES,
    RECENTLY_SEARCHED_CONTROLLER,
    RECENTLY_SEARCHED_MODEL_BOUND,
    RECENTLY_SEARCHED_MEDIA_ENTITY,
    APPLE_MAIN_CONTENT_ACTIVITY,
    APPLE_SHARED_PREFERENCES_CLASS,
    APPLE_SONG_MODEL_CLASS,
    APPLE_PLAYER_UTIL_CLASS,
    PLAYER_LYRICS_VIEW_MODEL_CLASS,
    IN_APP_CONTAINER_ARTIST_CLASS,
    IN_APP_CONTAINER_ALBUM_CLASS,
    MEDIA_API_REPOSITORY_HOLDER_CLASS,
    COMPOSE_NEVER_EQUAL_POLICY,
    LIBRARY_COMPOSE_VIEW_MODEL_GETTER,
    LIBRARY_EPOXY_BUILD,
    LIBRARY_COMPOSE_CONTENT,
    COMPOSE_OBSERVE_AS_STATE,
    LIBRARY_ENTITY_CLASSES,
    DATA_BINDING_RUNTIME_CLASSES,
    COLLECTION_SURFACE_CLASSES,
    ARTIST_SURFACE_CLASSES,
    LISTEN_NOW_MODEL_BUILDER,
    LISTEN_NOW_BOUND_LISTENER,
    LISTEN_NOW_MODEL,
    LISTEN_NOW_ARTWORK_RESOLVER,
    LISTEN_NOW_DELEGATING_ITEM,
    LISTEN_NOW_CUSTOM_IMAGE_VIEW,
    LISTEN_NOW_MEDIA_ENTITY,
    LISTEN_NOW_COLLECTION_ITEM_VIEW,
}

internal enum class AppleMusicRuntimeMember {
    CONTENT_HTTP_CHAIN_REQUEST_FIELD,
    CONTENT_HTTP_REQUEST_URL_FIELD,
    CONTENT_HTTP_REQUEST_HEADERS_FIELD,
    CONTENT_HTTP_RESPONSE_STATUS_FIELD,
    CONTENT_HTTP_REQUEST_NEW_BUILDER_METHOD,
    CONTENT_HTTP_REQUEST_BUILDER_URL_METHOD,
    CONTENT_HTTP_REQUEST_BUILDER_HEADER_METHOD,
    CONTENT_HTTP_REQUEST_BUILDER_BUILD_METHOD,
    CONTENT_HTTP_HEADERS_GET_METHOD,
    EXO_SEEK_METHOD,
    EXO_PLAY_METHOD,
    EXO_PAUSE_METHOD,
    EXO_STOP_METHOD,
    EXO_RELEASE_METHOD,
    EXO_CURRENT_POSITION_METHOD,
    DEBUG_FORMAT_HOLDER_FORMAT_FIELD,
    DEBUG_FORMAT_CODECS_FIELD,
    DEBUG_FORMAT_SAMPLE_MIME_TYPE_FIELD,
    DEBUG_FORMAT_LOUDNESS_FIELD,
    DEBUG_FORMAT_CHANNEL_COUNT_FIELD,
    DEBUG_FORMAT_SAMPLE_RATE_FIELD,
    DEBUG_FORMAT_BITRATE_FIELD,
    PLAYBACK_PLAYER_CURRENT_ITEM_METHOD,
    PLAYBACK_QUEUE_ITEM_ITEM_METHOD,
    PLAYBACK_QUEUE_ITEM_ID_METHOD,
    PLAYBACK_MEDIA_ITEM_TITLE_METHOD,
    PLAYBACK_MEDIA_ITEM_ARTIST_NAME_METHOD,
    PLAYBACK_MEDIA_ITEM_GENRE_NAME_METHOD,
    PLAYBACK_MEDIA_ITEM_DURATION_METHOD,
    PLAYBACK_MEDIA_ITEM_SUBSCRIPTION_STORE_ID_METHOD,
    PLAYBACK_MEDIA_ITEM_PERSISTENT_ID_METHOD,
    APPLE_SONG_SET_ID_METHOD,
    APPLE_SONG_SET_QUEUE_ID_METHOD,
    APPLE_SONG_SET_HAS_LYRICS_METHOD,
    APPLE_PLAYER_UTIL_CONTAINER_METHOD,
    APPLE_PLAYER_UTIL_PLAYBACK_ITEM_METHOD,
    CONTENT_HTTP_RESPONSE_REQUEST_FIELD,
    CONTENT_HTTP_RESPONSE_HEADERS_FIELD,
    CONTENT_HTTP_HEADERS_VALUES_FIELD,
    LYRICS_COOKIE_NAME_FIELD,
    LYRICS_COOKIE_VALUE_FIELD,
    LYRICS_SOURCE_MENU_FRAGMENT_FIELD,
    LYRICS_SOURCE_MENU_FRAGMENT_CLASS,
    LYRICS_NATIVE_LINE_TEXT_METHOD,
    LYRICS_NATIVE_TRANSLATION_TEXT_METHOD,
    LYRICS_NATIVE_PRONUNCIATION_TEXT_METHOD,
    LYRICS_NATIVE_BACKGROUND_TEXT_METHOD,
    LYRICS_NATIVE_TRANSLATED_BACKGROUND_TEXT_METHOD,
    LYRICS_NATIVE_PRONUNCIATION_BACKGROUND_TEXT_METHOD,
    LYRICS_NATIVE_PRONUNCIATION_WORDS_METHOD,
    LYRICS_NATIVE_PRONUNCIATION_BACKGROUND_WORDS_METHOD,
    LYRICS_NATIVE_WORDS_METHOD,
    LYRICS_NATIVE_BACKGROUND_WORDS_METHOD,
    LYRICS_NATIVE_SET_TRANSLATION_METHOD,
    LYRICS_NATIVE_HAS_TRANSLATION_METHOD,
    LYRICS_NATIVE_SET_PRONUNCIATION_METHOD,
    LYRICS_NATIVE_HAS_PRONUNCIATION_METHOD,
    LYRICS_NATIVE_POINTER_GET_METHOD,
    LYRICS_NATIVE_VECTOR_GET_METHOD,
    LYRICS_NATIVE_VECTOR_SIZE_METHOD,
    LYRICS_NATIVE_POINTER_ADDRESS_METHOD,
    LYRICS_NATIVE_SONG_SECTIONS_METHOD,
    LYRICS_NATIVE_SECTION_LINES_METHOD,
    LYRICS_NATIVE_BEGIN_METHOD,
    LYRICS_NATIVE_END_METHOD,
    LYRICS_NATIVE_DURATION_METHOD,
    LYRICS_NATIVE_WORD_ID_METHOD,
    LYRICS_NATIVE_WHITESPACE_METHOD,
    LYRICS_NATIVE_SONG_PRONUNCIATION_LANGUAGES_METHOD,
    LYRICS_NATIVE_SONG_TRANSLATION_LANGUAGES_METHOD,
    LYRICS_NATIVE_SET_ADAM_ID_METHOD,
    LYRICS_NATIVE_SET_QUEUE_ID_METHOD,
    LYRICS_NATIVE_SONG_QUEUE_ID_METHOD,
    LYRICS_NATIVE_SONG_AGENTS_METHOD,
    LYRICS_NATIVE_AGENT_METHOD,
    LYRICS_NATIVE_AGENT_NAME_TYPES_METHOD,
    LYRICS_NATIVE_AGENT_TYPE_METHOD,
    LYRICS_NATIVE_AGENT_ID_METHOD,
    LYRICS_SONG_ADAM_ID_METHOD,
    LYRICS_SONG_ID_METHOD,
    LYRICS_SONG_QUEUE_ID_METHOD,
    LYRICS_VIEW_MODEL_CURRENT_LANGUAGE_METHOD,
    LYRICS_VIEW_MODEL_RESULT_GETTER,
    LYRICS_UI_RECYCLER_VIEW_METHOD,
    LYRICS_UI_ROOT_VIEW_GETTER,
    LYRICS_UI_BINDING_FIELD,
    LYRICS_UI_BINDING_RECYCLER_FIELD,
    LYRICS_UI_ADAPTER_FIELD,
    LYRICS_UI_VIEW_MODEL_FIELD,
    LYRICS_UI_LOADING_PROGRESS_RESOURCE_NAME,
    LYRICS_ADAPTER_ACTIVE_POSITIONS_METHOD,
    LYRICS_ADAPTER_LYRICS_METHOD,
    LYRICS_ADAPTER_LINE_COUNT_METHOD,
    LYRICS_ADAPTER_LINE_AT_METHOD,
    LYRICS_ADAPTER_ITEM_VIEW_TYPE_METHOD,
    LYRICS_ADAPTER_ITEM_COUNT_METHOD,
    LYRICS_ADAPTER_NOTIFY_DATA_CHANGED_METHOD,
    LYRICS_ADAPTER_ACTIVE_LINES_UPDATE_METHOD,
    LYRICS_ADAPTER_TRANSLATION_SELECTED_FIELD,
    LYRICS_ADAPTER_PRONUNCIATION_SELECTED_FIELD,
    LYRICS_VIEW_MODEL_PRONUNCIATION_SELECTED_GETTER,
    LYRICS_VIEW_MODEL_PRONUNCIATION_AVAILABLE_GETTER,
    LYRICS_VIEW_MODEL_TRANSLATION_SELECTED_GETTER,
    LYRICS_VIEW_MODEL_TRANSLATION_AVAILABLE_GETTER,
    PLAYER_LYRICS_ITEM_HAS_LYRICS_METHOD,
    PLAYER_LYRICS_ITEM_HAS_CUSTOM_LYRICS_METHOD,
    PLAYER_SONG_BINDING_PLAYBACK_ITEM_FIELD,
    PLAYER_SONG_BINDING_LYRICS_BUTTON_FIELD,
    LYRICS_WORD_VECTOR_CLASS_NAME,
    LYRICS_GRADIENT_LAYOUT_CLASS_NAME,
    LYRICS_GRADIENT_MASK_START_CHILD_FIELD,
    LYRICS_GRADIENT_MASK_END_CHILD_FIELD,
    LYRICS_GRADIENT_MASK_POSITIONS_FIELD,
    LYRICS_GRADIENT_MASK_FRACTION_FIELD,
    QUEUE_ADAPTER_DISPLAYED_ENTRY_METHOD,
    QUEUE_ADAPTER_SUBMITTED_ENTRIES_FIELD,
    QUEUE_ENTRY_ITEM_FIELD,
    QUEUE_ITEM_METADATA_FIELD,
    QUEUE_ITEM_ID_FIELD,
    QUEUE_HISTORY_ENTRY_CLASS_NAME,
    MEDIA3_METADATA_BUNDLE_FIELD,
    MEDIA3_METADATA_TITLE_FIELD,
    MEDIA3_METADATA_ARTIST_FIELD,
    CONTENT_ITEM_ROLE,
    LIBRARY_RECENT_ITEMS_LIVE_RESULT_METHOD,
    LIBRARY_COMPOSE_STATE_POLICY_FIELD,
    LIBRARY_COMPOSE_STATE_GET_VALUE_METHOD,
    LIBRARY_COMPOSE_STATE_SET_VALUE_METHOD,
    LIBRARY_ENTITY_ROLE,
    LIBRARY_ENTITY_KIND,
    DATA_BINDING_RUNTIME_ROLE,
    DATA_BINDING_REGISTRATION_METHOD,
    DATA_BINDING_INVALIDATE_METHOD,
    DATA_BINDING_EXECUTE_METHOD,
    DATA_BINDING_SET_VARIABLE_METHOD,
    DATA_BINDING_TITLE_VARIABLE_FIELD,
    DATA_BINDING_SUBTITLE_VARIABLE_FIELD,
    COLLECTION_RUNTIME_ROLE,
    COLLECTION_ALBUM_HEADER_BUILD_METHOD,
    COLLECTION_PLAYLIST_BUILD_ITEM_METHOD,
    COLLECTION_CONTROLLER_ATTACH_METHOD,
    COLLECTION_CONTROLLER_DETACH_METHOD,
    COLLECTION_CONTROLLER_SET_DATA_METHOD,
    COLLECTION_CONTROLLER_FORCE_BUILD_METHOD,
    COLLECTION_PLAYLIST_TITLE_FIELD,
    COLLECTION_PLAYLIST_SUBTITLE_FIELD,
    COLLECTION_ENTITY_EXPLICIT_METHOD,
    APPLE_TEXT_STYLE_EXPLICIT_TITLE_METHOD,
    EPOXY_FINAL_HOLDER_MODEL_HOLDER_METHOD,
    ARTIST_RUNTIME_ROLE,
    ARTIST_TOP_SONG_BUILD_METHOD,
    ARTIST_PROFILE_BUILD_METHOD,
    ARTIST_MODEL_BIND_METHOD,
    ARTIST_CONTROLLER_ATTACH_METHOD,
    ARTIST_CONTROLLER_DETACH_METHOD,
    ARTIST_CONTROLLER_SET_DATA_METHOD,
    ARTIST_TOP_SONG_TITLE_FIELD,
    ARTIST_TOP_SONG_SUBTITLE_FIELD,
    ARTIST_TOP_SONG_CAPTION_FIELD,
    ARTIST_HEADER_TITLE_FIELD,
    COLLECTION_ITEM_GET_ID_METHOD,
    COLLECTION_ITEM_GET_PERSISTENT_ID_METHOD,
    COLLECTION_ITEM_GET_CONTENT_TYPE_METHOD,
    COLLECTION_ITEM_GET_TITLE_METHOD,
    COLLECTION_ITEM_SET_TITLE_METHOD,
    COLLECTION_ITEM_NOTIFY_CHANGE_METHOD,
    ARTWORK_GET_ARTWORK_TOKEN_METHOD,
    ARTWORK_GET_ALL_ARTWORK_TOKENS_METHOD,
    ARTWORK_GET_FETCHABLE_ARTWORK_TOKEN_METHOD,
    ARTWORK_GET_IMAGE_URL_METHOD,
    ARTWORK_GET_IMAGE_URLS_METHOD,
    ARTWORK_SET_IMAGE_URL_METHOD,
    ARTWORK_SET_IMAGE_URLS_METHOD,
    ARTWORK_NOTIFY_INITIAL_IMAGE_URL_METHOD,
    CUSTOM_IMAGE_SET_BITMAP_METHOD,
    CONTENT_ITEM_TITLE_GETTER,
    CONTENT_ITEM_NOW_PLAYING_TITLE_GETTER,
    CONTENT_ITEM_ARTIST_GETTER,
    CONTENT_ITEM_NOW_PLAYING_SUBTITLE_GETTER,
    CONTENT_ITEM_SUBTITLE_GETTER,
    CONTENT_ITEM_COLLECTION_GETTER,
    CONTENT_ITEM_SUBSCRIPTION_STORE_ID_GETTER,
    CONTENT_ITEM_ID_GETTER,
    CONTENT_ITEM_PERSISTENT_ID_GETTER,
    CONTENT_ITEM_ASSET_ADAM_ID_GETTER,
    CONTENT_ITEM_REPORTING_ADAM_ID_GETTER,
    CONTENT_ITEM_FORMER_IDS_GETTER,
    CONTENT_ITEM_ARTIST_ID_GETTER,
    CONTENT_ITEM_ARTIST_ADAM_ID_GETTER,
    CONTENT_ITEM_ARTIST_STORE_ID_GETTER,
    CONTENT_ITEM_ARTIST_SUBSCRIPTION_STORE_ID_GETTER,
    CONTENT_ITEM_TITLE_FIELD,
    CONTENT_ITEM_ARTIST_FIELD,
    CONTENT_ITEM_COLLECTION_FIELD,
    CONTENT_ITEM_SET_TITLE_METHOD,
    CONTENT_ITEM_SET_ARTIST_METHOD,
    CONTENT_ITEM_SET_COLLECTION_METHOD,
    CONTENT_ITEM_SET_SUBTITLE_METHOD,
    CONTENT_ITEM_NOTIFY_CHANGE_METHOD,
    MEDIA_API_HOLDER_GET_MEDIA_API_METHOD,
    MEDIA_API_STOREFRONT_FIELD,
    MEDIA_API_DIRECT_QUERY_METHOD,
    CATALOG_RESPONSE_DATA_METHOD,
    CATALOG_ENTITY_ID_METHOD,
    CATALOG_ENTITY_SUBSCRIPTION_STORE_ID_METHOD,
    CATALOG_ENTITY_ASSET_ADAM_ID_METHOD,
    CATALOG_ENTITY_REPORTING_ADAM_ID_METHOD,
    CATALOG_ENTITY_FORMER_IDS_METHOD,
    CATALOG_ENTITY_ATTRIBUTES_METHOD,
    CATALOG_ATTRIBUTES_PLAY_PARAMS_METHOD,
    CATALOG_PLAY_PARAMS_CATALOG_ID_METHOD,
    CATALOG_ATTRIBUTES_NAME_METHOD,
    CATALOG_ATTRIBUTES_ARTIST_NAME_METHOD,
    CATALOG_ATTRIBUTES_ALBUM_NAME_METHOD,
    CATALOG_ATTRIBUTES_ARTIST_ID_METHOD,
    CATALOG_ATTRIBUTES_ARTIST_ADAM_ID_METHOD,
    CATALOG_ATTRIBUTES_ARTIST_STORE_ID_METHOD,
    CATALOG_ATTRIBUTES_ARTIST_SUBSCRIPTION_STORE_ID_METHOD,
    CATALOG_ATTRIBUTES_SET_NAME_METHOD,
    CATALOG_ATTRIBUTES_SET_ARTIST_NAME_METHOD,
    CATALOG_ATTRIBUTES_SET_ALBUM_NAME_METHOD,
    CATALOG_ENTITY_RELATIONSHIPS_METHOD,
    CATALOG_RELATIONSHIP_ENTITIES_METHOD,
    CATALOG_RELATIONSHIP_DATA_METHOD,
    CATALOG_ATTRIBUTES_ISRC_METHOD,
    CATALOG_ATTRIBUTES_GENRE_NAMES_METHOD,
    CATALOG_ATTRIBUTES_GENRE_NAME_METHOD,
    CUSTOM_TEXT_VIEW_SET_TYPEFACE_METHOD,
    CUSTOM_TEXT_VIEW_SET_TEXT_METHOD,
    CUSTOM_TEXT_VIEW_ON_DRAW_METHOD,
    CUSTOM_TEXT_VIEW_FUTURE_RESOLVE_METHOD,
    IN_APP_CONTAINER_SET_TITLE_METHOD,
    IN_APP_CONTAINER_NOTIFY_CHANGE_METHOD,
}

internal data class AppleMusicHookTarget(
    val className: String,
    val methodName: String? = null,
    val parameterCount: Int? = null,
    val parameterTypeNames: List<String?>? = null,
    val returnTypeName: String? = null,
    val isStatic: Boolean? = null,
    val includeSynthetic: Boolean = false,
    val allowFirstMatch: Boolean = false,
    val runtimeMemberNames: Map<AppleMusicRuntimeMember, String> = emptyMap(),
    val requiredInvokedMethodDescriptors: List<String> = emptyList(),
    val requiredInvokedMethodNames: List<String> = emptyList(),
    val requiredCallerMethodNames: List<String> = emptyList(),
    val contract: AppleMusicHookContract? = null,
) {
    init {
        require(
            parameterTypeNames == null ||
                parameterCount == null ||
                parameterTypeNames.size == parameterCount
        ) {
            "parameterTypeNames must match parameterCount"
        }
    }

    fun runtimeMemberName(member: AppleMusicRuntimeMember): String =
        checkNotNull(runtimeMemberNames[member]) {
            "Missing runtime member $member for $className#${methodName ?: "<class>"}"
        }

    fun runtimeMemberNameOrNull(member: AppleMusicRuntimeMember): String? =
        runtimeMemberNames[member]
}

internal data class AppleMusicHookProfile(
    val id: String,
    val versionName: String,
    val versionCodes: Set<Long>,
    private val hookTargets: Map<AppleMusicHookPoint, List<AppleMusicHookTarget>>,
    val strict: Boolean = false,
) {
    fun targets(hookPoint: AppleMusicHookPoint): List<AppleMusicHookTarget> =
        hookTargets[hookPoint].orEmpty()

    fun matches(version: AppleMusicVersion): Boolean =
        if (strict) version.versionName == versionName && version.versionCode in versionCodes
        else version.versionCode?.let(versionCodes::contains) == true ||
            version.versionName == versionName
}

/**
 * JSON 版本资料到旧 HLE 解析引擎的兼容桥。
 *
 * 后续版本更新流程：反编译新版 APK，确认每个 [AppleMusicHookPoint] 的目标类和方法，
 * 在 host-profiles 中记录精确 tuple、证据和契约；index 决定已审核候选顺序。
 * 生产资格由统一注册表精确判断。此处保留旧引擎的候选回退行为。
 */
internal object AppleMusicHookProfiles {
    private val KNOWN_PROFILES by lazy {
        com.tcrrry.lyrics.host.applemusic.AppleMusicHostProfiles.all.map { profile ->
            val points = profile.document.getJSONObject("hookTargets")
            val targets = AppleMusicHookPoint.entries.associateWith { point ->
                val entries = points.getJSONArray(point.name)
                List(entries.length()) { index -> decodeTarget(point, entries.getJSONObject(index)) }
            }
            AppleMusicHookProfile(profile.document.getString("hookProfileId"), profile.versionName,
                setOf(profile.versionCode), targets, profile.family == "fragment-content")
        }
    }

    private fun decodeTarget(point: AppleMusicHookPoint, json: org.json.JSONObject): AppleMusicHookTarget {
        require(json.getString("contractId") == point.name) { "Unknown target contract for $point" }
        fun strings(name: String): List<String> = json.getJSONArray(name).let { array ->
            List(array.length()) { array.getString(it) }
        }
        val members = json.getJSONObject("runtimeMemberNames")
        return AppleMusicHookTarget(
            className = json.getString("className"),
            methodName = if (json.isNull("methodName")) null else json.getString("methodName"),
            parameterCount = if (json.isNull("parameterCount")) null else json.getInt("parameterCount"),
            parameterTypeNames = if (json.isNull("parameterTypeNames")) null else json.getJSONArray("parameterTypeNames").let { array ->
                List(array.length()) { if (array.isNull(it)) null else array.getString(it) }
            },
            returnTypeName = if (json.isNull("returnTypeName")) null else json.getString("returnTypeName"),
            isStatic = if (json.isNull("isStatic")) null else json.getBoolean("isStatic"),
            includeSynthetic = json.getBoolean("includeSynthetic"),
            allowFirstMatch = json.getBoolean("allowFirstMatch"),
            runtimeMemberNames = members.keys().asSequence().associate { key ->
                AppleMusicRuntimeMember.valueOf(key) to members.getString(key)
            },
            requiredInvokedMethodDescriptors = strings("requiredInvokedMethodDescriptors"),
            requiredInvokedMethodNames = strings("requiredInvokedMethodNames"),
            requiredCallerMethodNames = strings("requiredCallerMethodNames"),
        )
    }

    fun profileFor(version: AppleMusicVersion): AppleMusicHookProfile? =
        KNOWN_PROFILES.firstOrNull { it.matches(version) }

    fun exactTargets(version: AppleMusicVersion, hookPoint: AppleMusicHookPoint): List<AppleMusicHookTarget> =
        profileFor(version)?.targets(hookPoint).orEmpty()

    fun candidates(version: AppleMusicVersion, hookPoint: AppleMusicHookPoint): List<AppleMusicHookTarget> {
        val profile = profileFor(version)
        if (profile?.strict == true) return profile.targets(hookPoint)
        return (exactTargets(version, hookPoint) + KNOWN_PROFILES.filterNot { it.strict }
            .flatMap { it.targets(hookPoint) }).distinct()
    }
}

internal data class ResolvedAppleMusicHookClass(
    val target: AppleMusicHookTarget,
    val clazz: Class<*>,
    val compatibilityFallback: Boolean,
    val contractReason: String? = null,
)

internal data class ResolvedAppleMusicHookMethod(
    val target: AppleMusicHookTarget,
    val method: Method,
    val compatibilityFallback: Boolean,
    val contractReason: String? = null,
)

/** 统一负责按 Apple Music 版本加载并校验混淆 Hook 目标。 */
internal class AppleMusicHookResolver(
    val version: AppleMusicVersion,
    private val classLookup: (String) -> Class<*>,
    private val dexKitResolver: AppleMusicDexKitResolver? = null,
) {
    constructor(version: AppleMusicVersion, classLoader: ClassLoader) : this(
        version = version,
        classLookup = classLoader::loadClass,
    )

    constructor(
        version: AppleMusicVersion,
        application: android.app.Application,
        nativeLibraryDir: String,
        moduleApkPaths: List<String> = emptyList(),
    ) : this(
        version = version,
        classLookup = application.classLoader::loadClass,
        dexKitResolver = AppleMusicDexKitResolver(
            application = application,
            classLoader = application.classLoader,
            nativeLibraryDir = nativeLibraryDir,
            moduleApkPaths = moduleApkPaths,
        ),
    )

    val profile: AppleMusicHookProfile? = AppleMusicHookProfiles.profileFor(version)

    // Each resolver belongs to one host version/class loader. Only complete profile groups
    // are retained; some stable groups are inherited from older profiles (including on 6.5.2).
    // A transient missing class must remain retryable.
    private val resolvedClassGroups =
        ConcurrentHashMap<AppleMusicHookPoint, List<ResolvedAppleMusicHookClass>>()

    fun configuredClassNames(hookPoint: AppleMusicHookPoint): Set<String> {
        val exact = AppleMusicHookProfiles.exactTargets(version, hookPoint)
        val targets = if (exact.isNotEmpty()) {
            exact
        } else {
            AppleMusicHookProfiles.candidates(version, hookPoint)
        }
        return targets.mapTo(LinkedHashSet(), AppleMusicHookTarget::className)
    }

    /**
     * 加载一个 Hook 点在当前精确档案里的全部类。精确目标全部缺失时才进入兼容回退，
     * 避免在已知版本里同时 Hook 旧版本碰巧仍存在、但语义已经变化的类。
     */
    fun resolveClasses(hookPoint: AppleMusicHookPoint): List<ResolvedAppleMusicHookClass> {
        resolvedClassGroups[hookPoint]?.let { return it }
        return synchronized(resolvedClassGroups) {
            resolvedClassGroups[hookPoint]?.let { return@synchronized it }
            val classes = resolveClassesUncached(hookPoint)
            val exact = AppleMusicHookProfiles.exactTargets(version, hookPoint)
            val expected = exact.ifEmpty { AppleMusicHookProfiles.candidates(version, hookPoint) }
            if (expected.isNotEmpty() && expected.all { target ->
                    classes.any {
                        it.target.className == target.className &&
                            (exact.isEmpty() || !it.compatibilityFallback)
                    }
                }
            ) {
                resolvedClassGroups[hookPoint] = classes
            }
            classes
        }
    }

    private fun resolveClassesUncached(hookPoint: AppleMusicHookPoint): List<ResolvedAppleMusicHookClass> {
        val exactTargets = AppleMusicHookProfiles.exactTargets(version, hookPoint)
        val exactClasses = exactTargets.mapNotNull { target ->
            loadClass(hookPoint, target, compatibilityFallback = false)
                ?.let { resolved -> repairAndRecordClass(hookPoint, resolved, target.className) }
        }
        val resolved = LinkedHashMap<String, ResolvedAppleMusicHookClass>()
        exactClasses.forEach { resolved.putIfAbsent(it.clazz.name, it) }
        if (profile?.strict == true) return resolved.values.toList()

        val compatibilityClasses = AppleMusicHookProfiles.candidates(version, hookPoint)
            .filterNot { target -> exactClasses.any { it.target.className == target.className } }
            .mapNotNull { target -> loadClass(hookPoint, target, compatibilityFallback = true) }
            .map { resolved ->
                repairAndRecordClass(hookPoint, resolved, resolved.target.className)
            }
        compatibilityClasses.forEach { resolved.putIfAbsent(it.clazz.name, it) }

        val dexKitClasses = dexKitResolver?.resolveClasses(
            hookPoint,
            AppleMusicHookProfiles.candidates(version, hookPoint).filterNot { target ->
                resolved.values.any { it.target.className == target.className }
            },
        ).orEmpty()
        dexKitClasses.forEach { resolved.putIfAbsent(it.clazz.name, it) }
        if (resolved.isNotEmpty()) return resolved.values.toList()

        return resolveDexKitMethod(hookPoint)?.let { resolved ->
            listOf(
                ResolvedAppleMusicHookClass(
                    target = resolved.target,
                    clazz = resolved.method.declaringClass,
                    compatibilityFallback = true,
                    contractReason = resolved.contractReason,
                ),
            )
        }.orEmpty()
    }

    /** 解析单个类；精确档案缺失时才尝试已知版本候选。通过语义契约校验才允许返回。 */
    fun resolveClass(hookPoint: AppleMusicHookPoint): ResolvedAppleMusicHookClass {
        val exactTargets = AppleMusicHookProfiles.exactTargets(version, hookPoint).toSet()
        val failures = mutableListOf<String>()
        AppleMusicHookProfiles.candidates(version, hookPoint).forEach { target ->
            val clazz = runCatching { classLookup(target.className) }
                .getOrElse { throwable ->
                    failures += "${target.className}:${throwable.javaClass.simpleName}"
                    return@forEach
                }
            val contractResult = AppleMusicHookContracts.validate(
                HookContractContext(
                    hookPoint = hookPoint,
                    target = target,
                    clazz = clazz,
                    method = null,
                    classLookup = classLookup,
                    dexKitResolver = dexKitResolver,
                ),
            )
            if (contractResult is ContractResult.Rejected) {
                failures += "${target.className}:contract:${contractResult.reason}"
                return@forEach
            }
            return repairAndRecordClass(
                hookPoint = hookPoint,
                baselineClassName = target.className,
                resolved = ResolvedAppleMusicHookClass(
                    target = target,
                    clazz = clazz,
                    compatibilityFallback = target !in exactTargets,
                    contractReason = if (target !in exactTargets) "contract_passed" else null,
                ),
            )
        }
        if (profile?.strict != true) dexKitResolver?.resolveClasses(hookPoint, AppleMusicHookProfiles.candidates(version, hookPoint))
            ?.firstOrNull()
            ?.let { return it }
        resolveDexKitMethod(hookPoint)?.let { resolved ->
            return ResolvedAppleMusicHookClass(
                target = resolved.target,
                clazz = resolved.method.declaringClass,
                compatibilityFallback = true,
                contractReason = resolved.contractReason,
            )
        }
        throw ClassNotFoundException(
            "Apple Music ${version.displayName} $hookPoint unresolved: " +
                failures.joinToString(),
        )
    }

    /** 解析单个方法；候选类存在但方法签名或语义契约不符时继续尝试下一版本候选。 */
    fun resolveMethod(hookPoint: AppleMusicHookPoint): ResolvedAppleMusicHookMethod {
        val exactTargets = AppleMusicHookProfiles.exactTargets(version, hookPoint).toSet()
        val failures = mutableListOf<String>()
        AppleMusicHookProfiles.candidates(version, hookPoint).forEach { target ->
            val clazz = runCatching { classLookup(target.className) }
                .getOrElse { throwable ->
                    failures += "${target.className}:class:${throwable.javaClass.simpleName}"
                    return@forEach
                }
            val matchingMethods = allDeclaredMethods(
                clazz = clazz,
                includeSynthetic = target.includeSynthetic,
            )
                .filter { method -> methodMatches(hookPoint, target, method) }
                .toList()
            if (matchingMethods.size == 1 || target.allowFirstMatch && matchingMethods.isNotEmpty()) {
                val method = matchingMethods.first().apply { isAccessible = true }
                val contractResult = AppleMusicHookContracts.validate(
                    HookContractContext(
                        hookPoint = hookPoint,
                        target = target,
                        clazz = clazz,
                        method = method,
                        classLookup = classLookup,
                        dexKitResolver = dexKitResolver,
                    ),
                )
                if (contractResult is ContractResult.Rejected) {
                    failures += "${target.className}#${method.name}:contract:${contractResult.reason}"
                    return@forEach
                }
                return repairAndRecordMethod(
                    hookPoint = hookPoint,
                    baselineClassName = target.className,
                    resolved = ResolvedAppleMusicHookMethod(
                        target = target,
                        method = method,
                        compatibilityFallback = target !in exactTargets,
                        contractReason = if (target !in exactTargets) "contract_passed" else null,
                    ),
                )
            }
            failures += if (matchingMethods.isEmpty()) {
                "${target.className}#${target.methodName}:signature"
            } else {
                "${target.className}#${target.methodName}:ambiguous(${matchingMethods.size})"
            }
        }
        resolveDexKitMethod(hookPoint)?.let { return it }

        throw NoSuchMethodException(
            "Apple Music ${version.displayName} $hookPoint unresolved: " +
                failures.joinToString(),
        )
    }

    private fun resolveDexKitMethod(
        hookPoint: AppleMusicHookPoint,
    ): ResolvedAppleMusicHookMethod? {
        if (profile?.strict == true) return null
        val candidates = AppleMusicHookProfiles.candidates(version, hookPoint)
        if (candidates.none { it.methodName != null || it.parameterCount != null }) return null
        return dexKitResolver?.resolveMethod(
            hookPoint = hookPoint,
            templates = candidates,
            validator = { template, method ->
                val matches = methodMatches(
                    hookPoint = hookPoint,
                    target = template.copy(
                        className = method.declaringClass.name,
                        methodName = method.name,
                        parameterCount = method.parameterCount,
                        parameterTypeNames = method.parameterTypes.map(Class<*>::getName),
                        returnTypeName = method.returnType.name,
                        isStatic = Modifier.isStatic(method.modifiers),
                    ),
                    method = method,
                )
                if (!matches) return@resolveMethod false
                val contractResult = AppleMusicHookContracts.validate(
                    HookContractContext(
                        hookPoint = hookPoint,
                        target = template,
                        clazz = method.declaringClass,
                        method = method,
                        classLookup = classLookup,
                        dexKitResolver = dexKitResolver,
                    ),
                )
                contractResult is ContractResult.Passed
            },
        )
    }

    private fun repairAndRecordClass(
        hookPoint: AppleMusicHookPoint,
        resolved: ResolvedAppleMusicHookClass,
        baselineClassName: String,
    ): ResolvedAppleMusicHookClass {
        if (profile?.strict == true) return resolved
        val repairedTarget = dexKitResolver?.repairRuntimeMembers(
            hookPoint = hookPoint,
            target = resolved.target,
            clazz = resolved.clazz,
            baselineClassName = baselineClassName,
        ) ?: resolved.target
        dexKitResolver?.recordBaseline(
            hookPoint = hookPoint,
            target = repairedTarget,
            clazz = resolved.clazz,
            baselineClassName = baselineClassName,
        )
        return resolved.copy(target = repairedTarget)
    }

    private fun repairAndRecordMethod(
        hookPoint: AppleMusicHookPoint,
        resolved: ResolvedAppleMusicHookMethod,
        baselineClassName: String,
    ): ResolvedAppleMusicHookMethod {
        if (profile?.strict == true) return resolved
        val repairedTarget = dexKitResolver?.repairRuntimeMembers(
            hookPoint = hookPoint,
            target = resolved.target,
            clazz = resolved.method.declaringClass,
            baselineClassName = baselineClassName,
        ) ?: resolved.target
        dexKitResolver?.recordMethodBaseline(
            hookPoint = hookPoint,
            target = repairedTarget,
            method = resolved.method,
            baselineClassName = baselineClassName,
        )
        return resolved.copy(target = repairedTarget)
    }

    private fun loadClass(
        hookPoint: AppleMusicHookPoint,
        target: AppleMusicHookTarget,
        compatibilityFallback: Boolean,
    ): ResolvedAppleMusicHookClass? = runCatching {
        val clazz = classLookup(target.className)
        val contractResult = AppleMusicHookContracts.validate(
            HookContractContext(
                hookPoint = hookPoint,
                target = target,
                clazz = clazz,
                method = null,
                classLookup = classLookup,
                dexKitResolver = dexKitResolver,
            ),
        )
        if (contractResult is ContractResult.Rejected) return null
        ResolvedAppleMusicHookClass(
            target = target,
            clazz = clazz,
            compatibilityFallback = compatibilityFallback,
            contractReason = if (compatibilityFallback) "contract_passed" else null,
        )
    }.getOrNull()

    private fun methodMatches(
        hookPoint: AppleMusicHookPoint,
        target: AppleMusicHookTarget,
        method: Method,
    ): Boolean {
        if (target.methodName != null && method.name != target.methodName) return false
        if (target.parameterCount != null && method.parameterCount != target.parameterCount) {
            return false
        }
        target.parameterTypeNames?.forEachIndexed { index, expectedName ->
            if (expectedName != null && method.parameterTypes[index].name != expectedName) {
                return false
            }
        }
        if (target.returnTypeName != null && method.returnType.name != target.returnTypeName) {
            return false
        }
        if (target.isStatic != null && Modifier.isStatic(method.modifiers) != target.isStatic) {
            return false
        }
        return when (hookPoint) {
            AppleMusicHookPoint.MEDIA_API_LOCALIZATION ->
                Map::class.java.isAssignableFrom(method.returnType)

            AppleMusicHookPoint.CONTENT_HTTP_LOCALIZATION,
            AppleMusicHookPoint.EXO_MEDIA_PLAYER,
            AppleMusicHookPoint.EXO_AUDIO_SESSION_ID,
            AppleMusicHookPoint.LOCAL_MEDIA_PLAYER_CONTROLLER_STATE,
            AppleMusicHookPoint.LOCAL_MEDIA_PLAYER_AUDIO_VARIANT_CHANGED,
            AppleMusicHookPoint.DEBUG_ATMOS_MEDIA_CODEC_PERIOD_ID,
            AppleMusicHookPoint.DEBUG_ATMOS_MEDIA_CODEC_INPUT_FORMAT,
            AppleMusicHookPoint.DEBUG_ATMOS_MEDIA_CODEC_AUDIO_SESSION,
            AppleMusicHookPoint.DEBUG_ATMOS_MEDIA_CODEC_OUTPUT_BUFFER,
            AppleMusicHookPoint.DEBUG_ATMOS_SV_AUDIO_PERIOD_ID,
            AppleMusicHookPoint.DEBUG_ATMOS_SV_AUDIO_STREAM_CHANGED,
            AppleMusicHookPoint.DEBUG_ATMOS_SV_AUDIO_SESSION,
            AppleMusicHookPoint.DEBUG_ATMOS_SV_AUDIO_FIRST_BUFFER,
            AppleMusicHookPoint.LOCAL_MEDIA_PLAYER_METADATA_UPDATED,
            AppleMusicHookPoint.LOCAL_MEDIA_PLAYER_INDEX_CHANGED,
            AppleMusicHookPoint.LYRICS_NETWORK_REQUEST,
            AppleMusicHookPoint.LYRICS_COOKIE_JAR,
            AppleMusicHookPoint.LYRICS_TRANSLATION_PREFERENCE,
            AppleMusicHookPoint.LYRICS_PRONUNCIATION_PREFERENCE,
            AppleMusicHookPoint.LYRICS_OFFICIAL_PRONUNCIATION_MATCH,
            AppleMusicHookPoint.LYRICS_VIEW_MODEL_LOAD,
            AppleMusicHookPoint.LYRICS_VIEW_MODEL_BUILD,
            AppleMusicHookPoint.LYRICS_RESULT_PRESENTATION,
            AppleMusicHookPoint.LYRICS_NATIVE_PRESENTATION,
            AppleMusicHookPoint.LYRICS_UI_ON_CREATE_VIEW,
            AppleMusicHookPoint.LYRICS_UI_ON_RESUME,
            AppleMusicHookPoint.LYRICS_UI_ON_DESTROY_VIEW,
            AppleMusicHookPoint.LYRICS_TTML_PARSER,
            AppleMusicHookPoint.LYRICS_AVAILABILITY_HAS_LYRICS,
            AppleMusicHookPoint.LYRICS_AVAILABILITY_TIME_SYNCED,
            AppleMusicHookPoint.PLAYER_LYRICS_AVAILABILITY_CALCULATOR,
            AppleMusicHookPoint.PLAYER_SONG_BINDING_EXECUTE,
            AppleMusicHookPoint.LYRICS_GRADIENT_MASK_UPDATE -> true

            AppleMusicHookPoint.IN_APP_GLOBAL_METADATA_DISPATCHER,
            AppleMusicHookPoint.IN_APP_NOW_PLAYING_METADATA_LISTENER,
            AppleMusicHookPoint.IN_APP_QUEUE_UPDATE,
            AppleMusicHookPoint.IN_APP_HISTORY_UPDATE,
            AppleMusicHookPoint.IN_APP_QUEUE_ADAPTER_SUBMIT,
            AppleMusicHookPoint.IN_APP_QUEUE_ADAPTER_BIND,
            AppleMusicHookPoint.LIBRARY_EPOXY_BUILD,
            AppleMusicHookPoint.LIBRARY_COMPOSE_CONTENT,
            AppleMusicHookPoint.COMPOSE_OBSERVE_AS_STATE,
            AppleMusicHookPoint.LIBRARY_ENTITY_CLASSES,
            AppleMusicHookPoint.DATA_BINDING_RUNTIME_CLASSES,
            AppleMusicHookPoint.COLLECTION_SURFACE_CLASSES,
            AppleMusicHookPoint.ARTIST_SURFACE_CLASSES -> true

            AppleMusicHookPoint.CONTENT_ITEM_METADATA_CLASSES,
            AppleMusicHookPoint.RECENTLY_SEARCHED_MEDIA_ENTITY -> true

            AppleMusicHookPoint.RECENTLY_SEARCHED_CONTROLLER ->
                method.name == "setData" &&
                    !method.isBridge &&
                    method.parameterCount == 1 &&
                    List::class.java.isAssignableFrom(method.parameterTypes[0])

            AppleMusicHookPoint.RECENTLY_SEARCHED_MODEL_BOUND ->
                !method.isBridge && method.parameterCount == 4

            AppleMusicHookPoint.APPLE_SHARED_PREFERENCES_CLASS,
            AppleMusicHookPoint.APPLE_MAIN_CONTENT_ACTIVITY,
            AppleMusicHookPoint.APPLE_SONG_MODEL_CLASS,
            AppleMusicHookPoint.APPLE_PLAYER_UTIL_CLASS,
            AppleMusicHookPoint.PLAYER_LYRICS_VIEW_MODEL_CLASS,
            AppleMusicHookPoint.IN_APP_CONTAINER_ARTIST_CLASS,
            AppleMusicHookPoint.IN_APP_CONTAINER_ALBUM_CLASS -> true

            AppleMusicHookPoint.MEDIA_API_REPOSITORY_HOLDER_CLASS -> true

            AppleMusicHookPoint.EPOXY_FINAL_BIND -> {
                val parameters = method.parameterTypes
                method.returnType == Void.TYPE &&
                    parameters.size == 4 &&
                    parameters[0] == parameters[1] &&
                    List::class.java.isAssignableFrom(parameters[2]) &&
                    parameters[3] == Int::class.javaPrimitiveType
            }

            AppleMusicHookPoint.LYRICS_SOURCE_MENU_CLICK_LISTENER -> {
                val parameters = method.parameterTypes
                method.returnType == Void.TYPE &&
                    parameters.size == 1 &&
                    parameters[0].name == "android.view.View"
            }

            AppleMusicHookPoint.IN_APP_ACTION_SHEET_BINDING ->
                method.returnType == Void.TYPE && method.parameterCount == 0

            AppleMusicHookPoint.LIBRARY_COMPOSE_VIEW_MODEL_GETTER ->
                method.parameterCount == 0 &&
                    method.returnType.name ==
                    "com.apple.android.music.library2.LibraryViewModel"

            AppleMusicHookPoint.LYRICS_WORD_RENDER_ADAPTER,
            AppleMusicHookPoint.LYRICS_RECYCLER_ADAPTER,
            AppleMusicHookPoint.LYRICS_PREFERRED_LANGUAGES_REQUEST,
            AppleMusicHookPoint.LYRICS_WORD_VECTOR_CLASS,
            AppleMusicHookPoint.APPLE_CUSTOM_TEXT_VIEW,
            AppleMusicHookPoint.COMPOSE_TEXT_LAYOUT,
            AppleMusicHookPoint.APPLE_TEXT_STYLE_UTILS,
            AppleMusicHookPoint.COMPOSE_NEVER_EQUAL_POLICY,
            AppleMusicHookPoint.LISTEN_NOW_MODEL_BUILDER,
            AppleMusicHookPoint.LISTEN_NOW_BOUND_LISTENER,
            AppleMusicHookPoint.LISTEN_NOW_MODEL,
            AppleMusicHookPoint.LISTEN_NOW_ARTWORK_RESOLVER,
            AppleMusicHookPoint.LISTEN_NOW_DELEGATING_ITEM,
            AppleMusicHookPoint.LISTEN_NOW_CUSTOM_IMAGE_VIEW,
            AppleMusicHookPoint.LISTEN_NOW_MEDIA_ENTITY,
            AppleMusicHookPoint.LISTEN_NOW_COLLECTION_ITEM_VIEW -> true
        }
    }

    private fun allDeclaredMethods(
        clazz: Class<*>,
        includeSynthetic: Boolean,
    ): Sequence<Method> =
        generateSequence(clazz) { current -> current.superclass }
            .flatMap { current -> current.declaredMethods.asSequence() }
            .filter { method ->
                includeSynthetic || (!method.isBridge && !method.isSynthetic)
            }
            .distinctBy { method ->
                method.name to method.parameterTypes.joinToString(separator = ",") { it.name }
            }
}
