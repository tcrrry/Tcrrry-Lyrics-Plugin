package com.tcrrry.playlisttools;

import android.app.Application;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.view.View;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

public final class HostContract {
    // Module A: MediaLibrary & Playlist query
    final Object facadeHolderInstance;
    final Method mlInstanceMethod;
    final Method isReadyMethod;
    final Method queryLibraryPlaylistsMethod;
    final Method queryItemsFromPlaylistMethod;
    final Method blockingGetMethod;
    final Constructor<?> playlistQueryBuilderCtor;
    final Method playlistQueryBuildMethod;
    final Constructor<?> itemQueryBuilderCtor;
    final Method itemQueryBuildMethod;
    final Method itemInfoFromCollectionItemMethod;
    final Method itemInfoReleaseMethod;
    final Method resultsCountMethod;
    final Method resultsItemAtMethod;
    final Method resultsReleaseMethod;

    // Host model classes
    final Class<?> baseContentItemClass;
    final Class<?> playlistCollectionItemClass;
    final Class<?> mediaApiPlaylistClass;
    final Class<?> delegatingCollectionItemClass;

    // Module B: Playlist cover fix
    final Class<?> editViewModelClass;
    final Method updateWithCroppedImageMethod;
    final Method populateCoversDataMethod;
    final Method setCoverSelectedMethod;
    final Field playlistImageUrlField;
    final Field initStateField;
    final Field pidField;
    final Field playlistEditSessionField;
    final Object customArtworkState;

    final Method newUpdateWithCroppedImageMethod;
    final Field newPidField;
    final Field newPlaylistImageUrlField;

    final Object propArtworkUrl;
    final Object propCoverRecipe;
    final Method sessionGetPropertyMethod;
    final Method sessionJ9PidMethod;
    final Method sessionJ6PidMethod;
    final Method sessionImplASetPropertyMethod;
    final Method sessionImplQSetPropertyMethod;
    final Method svMediaErrorCodeMethod;

    final Method baseContentGetImageUrlMethod;
    final Method baseContentSetImageUrlMethod;
    final Method baseContentGetPidMethod;
    final Method playlistItemGetImageUrlsMethod;
    final Method playlistItemSetRecipeMethod;
    final Method mediaEntityGetImageUrlMethod;
    final Method mediaEntityGetPidMethod;
    final Method mediaApiPlaylistToCollectionItemMethod;
    final Method mlArtworkObserverOnChangedMethod;
    final Field mlArtworkObserverItemField;
    final Method commonNResolveEntityKeyMethod;
    final Field delegatingItemField;
    final Method delegatingGetPidMethod;
    final Method delegatingSetImageUrlMethod;
    final Method delegatingSetImageUrlVariantMethod;
    final Method delegatingSetImageUrlsMethod;
    final Field playlistHeaderLiveImageUrlsField;
    final Method playlistAddHeaderModelMethod;
    final Method playlistCreateArtworkRecipeCoverMethod;
    final Method liveDataPostValueMethod;

    // Optional cache invalidator
    final Object imageCacheInstance;
    final Method imageCacheClearPid1Method;
    final Method imageCacheClearPid2Method;

    // Module C: Playlist ActionSheet menu
    final Class<?> actionSheetTargetAdapterClass;
    final Field actionSheetTargetContextField;
    final Field actionSheetTargetItemField;
    final Method actionSheetGetItemCountMethod;
    final Method actionSheetGetItemAtIndexMethod;
    final Field actionSheetFragmentField;
    final Field actionSheetClickTargetItemField;
    final Method actionSheetItemClickMethod;
    final Method actionSheetDismissMethod;
    final Constructor<?> commonHeaderItemCtor;
    final Method commonHeaderSetEnabledMethod;
    final Method baseContentSetIdMethod;
    final Method baseContentGetIdMethod;
    final int actionSheetExportIconResId;

    // Module D: Hardened Sleep Timer
    final Class<?> sleepTimerClass;
    final Method sleepTimerGetInstanceMethod;
    final Method sleepTimerSetTimerMethod;
    final Method sleepTimerSetEndOfEntityMethod;
    final Method sleepTimerCleanupMethod;
    final Method sleepTimerStopPlaybackMethod;
    final Method sleepTimerGetCurrentOptionMethod;
    final Method sleepTimerSetCurrentOptionMethod;
    final Method sleepTimerGetMediaBrowserMethod;
    final Field sleepTimerIsSetField;
    final Field sleepTimerCountDownField;
    final Field sleepTimerOnFinishField;
    final Field sleepTimerOnEndedField;
    final Field sleepTimerOnSetField;
    final Field sleepTimerOnTickField;
    final Field sleepTimerCurrentOptionField;
    final Object sleepTimerOffOption;
    final Method sleepTimerPredefinedStateChangedMethod;
    final Method singleLiveEventPostEventMethod;
    final Method liveDataSetValueMethod;
    final Method sleepTimerEpoxyBuildModelsMethod;
    final Method sleepTimerEpoxyGetViewModelMethod;
    final Field sleepTimerEpoxyContextField;
    final Method sleepTimerViewModelPostEventMethod;
    final int sleepTimerCloseDialogEvent;
    final Constructor<?> sleepTimerRowModelCtor;
    final Field sleepTimerRowLabelField;
    final Field sleepTimerRowSelectedField;
    final Field sleepTimerRowBoundListenerField;
    final Class<?> epoxyOnModelBoundListenerClass;
    final Method epoxyModelIdMethod;
    final Method epoxyModelOnMutationMethod;
    final Method epoxyControllerAddMethod;
    final Field epoxyDataBindingHolderField;
    final Field viewDataBindingRootField;
    final Field mediaBrowserHolderField;
    final Method mediaBrowserConnectMethod;
    final Method mediaControllerPauseMethod;
    final Method mediaControllerStopMethod;
    final Method mediaControllerSetPlayWhenReadyMethod;
    final Method mediaControllerIsPlayingMethod;
    final Method mediaControllerGetPlayWhenReadyMethod;
    final Method mediaControllerGetPlaybackStateMethod;
    final Class<?> sleepTimerTitleModelClass;
    final Class<?> sortDescriptorClass;
    final Class<?> sortFieldEnumClass;
    final Class<?> sortOrderEnumClass;
    final Constructor<?> sortDescriptorCtor;
    final Method sortFieldValueMethod;
    final Method sortOrderValueMethod;
    final Field queryBuilderSortField;

    public HostContract(ClassLoader loader) throws Exception {
        Class<?> facadeHolderClass = loader.loadClass("com.apple.android.music.mediaapi.repository.MediaLibraryFacadeHolder");
        Class<?> facadeCompanionClass = loader.loadClass("com.apple.android.music.mediaapi.repository.MediaLibraryFacadeHolder$Companion");
        Field companionField = field(facadeHolderClass, "Companion", facadeCompanionClass, true);
        Object companion = companionField.get(null);
        Method getInstanceMethod = method(facadeCompanionClass, "getInstance", facadeHolderClass, false);
        facadeHolderInstance = getInstanceMethod.invoke(companion);
        if (facadeHolderInstance == null) {
            throw new IllegalStateException("MediaLibraryFacadeHolder.getInstance() returned null");
        }

        Class<?> mediaLibraryClass = loader.loadClass("com.apple.android.medialibrary.library.MediaLibrary");
        mlInstanceMethod = method(facadeHolderClass, "mlInstance", mediaLibraryClass, false);
        isReadyMethod = method(mediaLibraryClass, "isReady", boolean.class, false);

        Class<?> queryParamsInterface = loader.loadClass("N6.g");
        Class<?> itemInfoClass = loader.loadClass("M6.d");
        Class<?> singleClass = loader.loadClass("zh.m");
        queryLibraryPlaylistsMethod = method(mediaLibraryClass, "R", singleClass, false, queryParamsInterface);
        queryItemsFromPlaylistMethod = method(mediaLibraryClass, "e", singleClass, false, itemInfoClass, queryParamsInterface);
        blockingGetMethod = method(singleClass, "d", Object.class, false);

        Class<?> playlistQueryBuilderClass = loader.loadClass("N6.i$a");
        playlistQueryBuilderCtor = playlistQueryBuilderClass.getDeclaredConstructor();
        playlistQueryBuilderCtor.setAccessible(true);
        playlistQueryBuildMethod = method(playlistQueryBuilderClass, "a", queryParamsInterface, false);

        Class<?> itemQueryBuilderClass = loader.loadClass("N6.f$a");
        itemQueryBuilderCtor = itemQueryBuilderClass.getDeclaredConstructor();
        itemQueryBuilderCtor.setAccessible(true);
        itemQueryBuildMethod = method(itemQueryBuilderClass, "a", queryParamsInterface, false);

        Class<?> collectionItemViewClass = loader.loadClass("com.apple.android.music.model.CollectionItemView");
        itemInfoFromCollectionItemMethod = method(itemInfoClass, "a", itemInfoClass, true, collectionItemViewClass);
        itemInfoReleaseMethod = method(itemInfoClass, "d", void.class, false);

        Class<?> queryResultsClass = loader.loadClass("com.apple.android.medialibrary.results.l");
        resultsCountMethod = method(queryResultsClass, "getItemCount", int.class, false);
        resultsItemAtMethod = method(queryResultsClass, "getItemAtIndex", collectionItemViewClass, false, int.class);
        resultsReleaseMethod = method(queryResultsClass, "release", void.class, false);

        baseContentItemClass = loader.loadClass("com.apple.android.music.model.BaseContentItem");
        playlistCollectionItemClass = loader.loadClass("com.apple.android.music.model.PlaylistCollectionItem");
        Class<?> mediaEntityClass = loader.loadClass("com.apple.android.music.mediaapi.models.MediaEntity");
        mediaApiPlaylistClass = loader.loadClass("com.apple.android.music.mediaapi.models.Playlist");

        editViewModelClass = loader.loadClass("com.apple.android.music.collection.mediaapi.viewmodel.PlaylistEditViewModel");
        updateWithCroppedImageMethod = method(editViewModelClass, "updatePlaylistWithCroppedImage", void.class, false, Uri.class);
        populateCoversDataMethod = method(editViewModelClass, "populateCoversData", void.class, false, boolean.class);
        setCoverSelectedMethod = method(editViewModelClass, "setCoverSelected", void.class, false, int.class);

        Class<?> coverInitStateClass = loader.loadClass("n7.a$d");
        Class<?> editSessionInterface = loader.loadClass("J9.a");
        playlistImageUrlField = field(editViewModelClass, "playlistImageUrl", String.class, false);
        initStateField = field(editViewModelClass, "initState", coverInitStateClass, false);
        pidField = field(editViewModelClass, "pid", long.class, false);
        playlistEditSessionField = field(editViewModelClass, "playlistEditSession", editSessionInterface, false);
        customArtworkState = field(coverInitStateClass, "CUSTOM_ARTWORK", coverInitStateClass, true).get(null);

        Class<?> newPlaylistViewModelClass = loader.loadClass("com.apple.android.music.collection.mediaapi.viewmodel.NewPlaylistViewModel");
        newUpdateWithCroppedImageMethod = method(newPlaylistViewModelClass, "updatePlaylistWithCroppedImage", void.class, false, Uri.class);
        newPidField = field(newPlaylistViewModelClass, "persistentId", long.class, false);
        newPlaylistImageUrlField = field(newPlaylistViewModelClass, "playlistImageUrl", String.class, false);

        Class<?> propertyEnumClass = loader.loadClass("J6.f$a");
        propArtworkUrl = field(propertyEnumClass, "PLAYLIST_ARTWORK_URL", propertyEnumClass, true).get(null);
        propCoverRecipe = field(propertyEnumClass, "PLAYLIST_COVER_ARTWORK_RECIPE", propertyEnumClass, true).get(null);

        Class<?> svMediaErrorClass = loader.loadClass("com.apple.android.medialibrary.results.SVMediaError");
        Class<?> errorCodeClass = loader.loadClass("com.apple.android.medialibrary.results.SVMediaError$a");
        svMediaErrorCodeMethod = method(svMediaErrorClass, "code", errorCodeClass, false);

        Class<?> sessionImplA = loader.loadClass("I9.d");
        Class<?> sessionImplQ = loader.loadClass("J6.q");
        Class<?> j6Interface = loader.loadClass("J6.f");
        sessionGetPropertyMethod = method(editSessionInterface, "getPlaylistProperty", String.class, false, propertyEnumClass);
        sessionJ9PidMethod = method(editSessionInterface, "playlistPersistentID", long.class, false);
        sessionJ6PidMethod = method(j6Interface, "playlistPersistentID", long.class, false);
        sessionImplASetPropertyMethod = method(sessionImplA, "setPlaylistProperty", svMediaErrorClass, false, propertyEnumClass, String.class);
        sessionImplQSetPropertyMethod = method(sessionImplQ, "setPlaylistProperty", svMediaErrorClass, false, propertyEnumClass, String.class);

        Class<?> recipeClass = loader.loadClass("com.apple.android.music.model.CoverArtworkRecipe");
        baseContentGetImageUrlMethod = method(baseContentItemClass, "getImageUrl", String.class, false);
        baseContentSetImageUrlMethod = method(baseContentItemClass, "setImageUrl", void.class, false, String.class);
        baseContentGetPidMethod = method(baseContentItemClass, "getPersistentId", long.class, false);
        playlistItemGetImageUrlsMethod = method(playlistCollectionItemClass, "getImageUrls", String[].class, false);
        playlistItemSetRecipeMethod = method(playlistCollectionItemClass, "setCoverArtworkRecipe", void.class, false, recipeClass);
        mediaEntityGetImageUrlMethod = method(mediaEntityClass, "getImageUrl", String.class, false);
        mediaEntityGetPidMethod = method(mediaEntityClass, "getPersistentId", Long.class, false);
        mediaApiPlaylistToCollectionItemMethod = method(mediaApiPlaylistClass, "toCollectionItemView", collectionItemViewClass, false, Bundle.class, boolean.class);

        Class<?> mlArtworkObserverClass = loader.loadClass("com.apple.android.music.common.MLArtworkObserver");
        mlArtworkObserverOnChangedMethod = method(mlArtworkObserverClass, "onChanged", void.class, false, String[].class);
        mlArtworkObserverItemField = field(mlArtworkObserverClass, "item", collectionItemViewClass, false);

        Class<?> commonNClass = loader.loadClass("com.apple.android.music.common.N");
        commonNResolveEntityKeyMethod = method(commonNClass, "s", String.class, true, mediaEntityClass);

        delegatingCollectionItemClass = loader.loadClass("com.apple.android.music.model.extensions.DelegatingCollectionItemView");
        Class<?> variantTypeClass = loader.loadClass("com.apple.android.music.model.ArtworkVariantType");
        delegatingItemField = field(delegatingCollectionItemClass, "item", collectionItemViewClass, false);
        delegatingGetPidMethod = method(delegatingCollectionItemClass, "getPersistentId", long.class, false);
        delegatingSetImageUrlMethod = method(delegatingCollectionItemClass, "setImageUrl", void.class, false, String.class);
        delegatingSetImageUrlVariantMethod = method(delegatingCollectionItemClass, "setImageUrl", void.class, false, String.class, variantTypeClass);
        delegatingSetImageUrlsMethod = method(delegatingCollectionItemClass, "setImageUrls", void.class, false, String[].class);

        Class<?> pageControllerClass = loader.loadClass("com.apple.android.music.collection.mediaapi.controller.PlaylistPageController");
        Class<?> mutableLiveDataClass = loader.loadClass("androidx.lifecycle.MutableLiveData");
        Class<?> epoxyHeaderModelClass = loader.loadClass("com.airbnb.epoxy.l");
        playlistHeaderLiveImageUrlsField = field(pageControllerClass, "headerLiveImageUrls", mutableLiveDataClass, false);
        playlistAddHeaderModelMethod = method(pageControllerClass, "addHeaderModel", epoxyHeaderModelClass, false, mediaApiPlaylistClass);
        playlistCreateArtworkRecipeCoverMethod = method(pageControllerClass, "createArtworkRecipeCover", void.class, false, mediaApiPlaylistClass, recipeClass);
        liveDataPostValueMethod = method(mutableLiveDataClass, "postValue", void.class, false, Object.class);

        Class<?> imageCacheClass = loader.loadClass("i7.j");
        imageCacheInstance = field(imageCacheClass, "INSTANCE", imageCacheClass, true).get(null);
        imageCacheClearPid1Method = method(imageCacheClass, "s", void.class, false, long.class);
        imageCacheClearPid2Method = method(imageCacheClass, "u", void.class, false, long.class);

        // Module C: Playlist ActionSheet menu
        actionSheetTargetAdapterClass = loader.loadClass("com.apple.android.music.common.actionsheet.t");
        actionSheetTargetContextField = field(actionSheetTargetAdapterClass, "a", Context.class, false);
        actionSheetTargetItemField = field(actionSheetTargetAdapterClass, "b", collectionItemViewClass, false);
        actionSheetGetItemCountMethod = method(actionSheetTargetAdapterClass, "getItemCount", int.class, false);
        actionSheetGetItemAtIndexMethod = method(actionSheetTargetAdapterClass, "getItemAtIndex", collectionItemViewClass, false, int.class);

        Class<?> actionSheetClickHandlerClass = loader.loadClass("com.apple.android.music.common.actionsheet.e");
        Class<?> actionSheetFragmentClass = loader.loadClass("com.apple.android.music.common.actionsheet.ActionSheetDialogFragment");
        actionSheetFragmentField = field(actionSheetClickHandlerClass, "T", actionSheetFragmentClass, false);
        actionSheetClickTargetItemField = field(actionSheetClickHandlerClass, "W", collectionItemViewClass, false);
        actionSheetItemClickMethod = method(actionSheetClickHandlerClass, "z", void.class, false, int.class, View.class, collectionItemViewClass);
        actionSheetDismissMethod = method(actionSheetFragmentClass, "dismiss", void.class, false);

        Class<?> commonHeaderClass = loader.loadClass("com.apple.android.music.model.CommonHeaderCollectionItem");
        commonHeaderItemCtor = commonHeaderClass.getDeclaredConstructor(String.class, Drawable.class);
        commonHeaderItemCtor.setAccessible(true);
        commonHeaderSetEnabledMethod = method(commonHeaderClass, "setEnabled", void.class, false, boolean.class);
        baseContentSetIdMethod = method(baseContentItemClass, "setId", void.class, false, String.class);
        baseContentGetIdMethod = method(baseContentItemClass, "getId", String.class, false);

        Class<?> actionSheetActionEnumClass = loader.loadClass("com.apple.android.music.common.actionsheet.A");
        Object downloadAction = field(actionSheetActionEnumClass, "DOWNLOAD", actionSheetActionEnumClass, true).get(null);
        actionSheetExportIconResId = field(actionSheetActionEnumClass, "iconResourceId", int.class, false).getInt(downloadAction);

        // Module D: Hardened Sleep Timer
        sleepTimerClass = loader.loadClass("com.apple.android.music.playback.SleepTimer");
        Class<?> sleepTimerOptionClass = loader.loadClass("com.apple.android.music.common.actionsheet.d0$a");
        Class<?> mediaBrowserClass = loader.loadClass("J4.x");
        Class<?> mediaControllerBaseClass = loader.loadClass("J4.B");
        Class<?> singleLiveEventClass = loader.loadClass("com.apple.android.music.viewmodel.SingleLiveEventObservable");

        sleepTimerGetInstanceMethod = method(sleepTimerClass, "getInstance", sleepTimerClass, true, Application.class);
        sleepTimerSetTimerMethod = method(sleepTimerClass, "setTimer", void.class, false, long.class);
        sleepTimerSetEndOfEntityMethod = method(sleepTimerClass, "setTimerEndofEntity", void.class, false, mediaEntityClass);
        sleepTimerCleanupMethod = method(sleepTimerClass, "cleanup", void.class, false);
        sleepTimerStopPlaybackMethod = method(sleepTimerClass, "stopPlayback", void.class, false);
        sleepTimerGetCurrentOptionMethod = method(sleepTimerClass, "getCurrentlySelectedOption", sleepTimerOptionClass, false);
        sleepTimerSetCurrentOptionMethod = method(sleepTimerClass, "setCurrentlySelectedOption", void.class, false, sleepTimerOptionClass);
        sleepTimerGetMediaBrowserMethod = method(sleepTimerClass, "getMediaBrowser", mediaBrowserClass, false);

        sleepTimerIsSetField = field(sleepTimerClass, "isSleepTimerSet", boolean.class, true);
        sleepTimerCountDownField = field(sleepTimerClass, "timer", CountDownTimer.class, true);
        sleepTimerOnFinishField = field(sleepTimerClass, "onFinishLiveData", singleLiveEventClass, true);
        sleepTimerOnEndedField = field(sleepTimerClass, "onSleepTimerEndedLiveData", singleLiveEventClass, true);
        sleepTimerOnSetField = field(sleepTimerClass, "onSleepTimerSetLiveData", mutableLiveDataClass, true);
        sleepTimerOnTickField = field(sleepTimerClass, "onTickLiveData", mutableLiveDataClass, true);
        sleepTimerCurrentOptionField = field(sleepTimerClass, "currentlySelectedOption", sleepTimerOptionClass, false);
        sleepTimerOffOption = field(sleepTimerOptionClass, "TIMER_OFF", sleepTimerOptionClass, true).get(null);

        Class<?> predefinedListenerClass = loader.loadClass("com.apple.android.music.playback.SleepTimer$PredefinedTimerListener");
        sleepTimerPredefinedStateChangedMethod = method(predefinedListenerClass, "onPlaybackStateChanged", void.class, false, int.class);

        singleLiveEventPostEventMethod = method(singleLiveEventClass, "postEvent", void.class, false, Object.class);
        liveDataSetValueMethod = method(mutableLiveDataClass, "setValue", void.class, false, Object.class);

        Class<?> sleepTimerEpoxyClass = loader.loadClass("com.apple.android.music.common.actionsheet.SleepTimerEpoxyController");
        Class<?> sleepTimerVmClass = loader.loadClass("com.apple.android.music.common.actionsheet.SleepTimerViewModel");
        sleepTimerEpoxyBuildModelsMethod = method(sleepTimerEpoxyClass, "buildModels", void.class, false);
        sleepTimerEpoxyGetViewModelMethod = method(sleepTimerEpoxyClass, "getViewModel", sleepTimerVmClass, false);
        sleepTimerEpoxyContextField = field(sleepTimerEpoxyClass, "context", Context.class, false);
        sleepTimerViewModelPostEventMethod = method(sleepTimerVmClass, "postEvent", void.class, false, int.class);
        sleepTimerCloseDialogEvent = field(sleepTimerVmClass, "CLOSE_DIALOG", int.class, true).getInt(null);

        Class<?> sleepTimerRowClass = loader.loadClass("com.apple.android.music.s0");
        Class<?> epoxyControllerClass = loader.loadClass("com.airbnb.epoxy.r");
        Class<?> epoxyModelClass = loader.loadClass("com.airbnb.epoxy.w");
        Class<?> epoxyHolderClass = loader.loadClass("com.airbnb.epoxy.l$a");
        Class<?> viewDataBindingClass = loader.loadClass("androidx.databinding.ViewDataBinding");
        epoxyOnModelBoundListenerClass = loader.loadClass("com.airbnb.epoxy.b0");

        sleepTimerRowModelCtor = sleepTimerRowClass.getDeclaredConstructor();
        sleepTimerRowModelCtor.setAccessible(true);
        sleepTimerRowLabelField = field(sleepTimerRowClass, "l", String.class, false);
        sleepTimerRowSelectedField = field(sleepTimerRowClass, "x", boolean.class, false);
        sleepTimerRowBoundListenerField = field(sleepTimerRowClass, "k", epoxyOnModelBoundListenerClass, false);
        epoxyModelIdMethod = method(epoxyModelClass, "p", void.class, false, CharSequence.class);
        epoxyModelOnMutationMethod = method(epoxyModelClass, "t", void.class, false);
        epoxyControllerAddMethod = method(epoxyControllerClass, "add", void.class, false, epoxyModelClass);
        epoxyDataBindingHolderField = field(epoxyHolderClass, "a", viewDataBindingClass, false);
        viewDataBindingRootField = field(viewDataBindingClass, "d", View.class, false);

        Class<?> mediaBrowserMgrClass = loader.loadClass("ia.a");
        mediaBrowserHolderField = field(mediaBrowserMgrClass, "f", mediaBrowserClass, true);
        mediaBrowserConnectMethod = method(mediaBrowserMgrClass, "b", void.class, true);
        mediaControllerPauseMethod = method(mediaControllerBaseClass, "pause", void.class, false);
        mediaControllerStopMethod = method(mediaControllerBaseClass, "stop", void.class, false);
        mediaControllerSetPlayWhenReadyMethod = method(mediaControllerBaseClass, "setPlayWhenReady", void.class, false, boolean.class);
        mediaControllerIsPlayingMethod = method(mediaControllerBaseClass, "isPlaying", boolean.class, false);
        mediaControllerGetPlayWhenReadyMethod = method(mediaControllerBaseClass, "getPlayWhenReady", boolean.class, false);
        mediaControllerGetPlaybackStateMethod = method(mediaControllerBaseClass, "getPlaybackState", int.class, false);

        sleepTimerTitleModelClass = loader.loadClass("com.apple.android.music.t0");
        sortDescriptorClass = loader.loadClass("N6.l");
        sortFieldEnumClass = loader.loadClass("N6.l$a");
        sortOrderEnumClass = loader.loadClass("N6.l$b");
        sortDescriptorCtor = sortDescriptorClass.getDeclaredConstructor(sortFieldEnumClass, sortOrderEnumClass);
        sortDescriptorCtor.setAccessible(true);
        sortFieldValueMethod = method(sortFieldEnumClass, "e", int.class, false);
        sortOrderValueMethod = method(sortOrderEnumClass, "a", int.class, false);
        Class<?> itemQueryBaseBuilderClass = loader.loadClass("N6.h$a");
        queryBuilderSortField = field(itemQueryBaseBuilderClass, "a", sortDescriptorClass, false);
    }

    private static Method method(Class<?> owner, String name, Class<?> result, boolean isStatic, Class<?>... args) throws Exception {
        Method value = owner.getDeclaredMethod(name, args);
        if (value.getReturnType() != result || Modifier.isStatic(value.getModifiers()) != isStatic) {
            throw new IllegalStateException("Host method mismatch: " + owner.getName() + "." + name);
        }
        value.setAccessible(true);
        return value;
    }

    private static Field field(Class<?> owner, String name, Class<?> type, boolean isStatic) throws Exception {
        Field value = owner.getDeclaredField(name);
        if (value.getType() != type || Modifier.isStatic(value.getModifiers()) != isStatic) {
            throw new IllegalStateException("Host field mismatch: " + owner.getName() + "." + name);
        }
        value.setAccessible(true);
        return value;
    }
}
