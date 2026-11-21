package com.tcrrry.playbackintent;

import android.util.SparseArray;
import android.os.Handler;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

final class HostContract implements PlaybackIntentCoordinator.QueueAccess {
    static final String PREFIX = "com.apple.android.music.playback.";
    final Method addItems, addItemsInternal, publish, setRepeat;
    final Method getRepeat, setCurrentIndex;
    final Method applyRepeat, applyShuffle;
    private final Method getShuffle, canShuffle;
    private final Method itemCount, currentIndex, providerId, providerCount, isDynamic, isMirrored, canRepeat;
    private final Field repeat, softProvider, providers;
    private final Method getUuid, getQueueItem, getMediaItem, getQueueId, duration, live, episode, video, continuous, playable, title, providerLive, hasStream;
    private final Field workHandler;
    private final Class<?> radioProvider, autoplayProvider;

    HostContract(ClassLoader loader) throws Exception {
        Class<?> manager = loader.loadClass(PREFIX + "queue.LocalPlaybackQueueManager");
        Class<?> provider = loader.loadClass(PREFIX + "queue.PlaybackQueueItemProvider");
        addItems = method(manager, "addItems", void.class, provider, int.class);
        addItemsInternal = method(manager, "addItemsInternal", void.class, provider, int.class);
        publish = method(manager, "sendAllModeChangedMessage", void.class);
        setRepeat = method(manager, "setRepeatMode", void.class, int.class);
        getRepeat = method(manager, "getRepeatMode", int.class);
        applyRepeat = method(manager, "setRepeatModeInternal", void.class, int.class);
        applyShuffle = method(manager, "setShuffleModeInternal", void.class, int.class, boolean.class);
        getShuffle = method(manager, "getShuffleMode", int.class);
        canShuffle = method(manager, "canSetShuffleMode", boolean.class);
        setCurrentIndex = method(manager, "setCurrentIndex", void.class, int.class);
        itemCount = method(manager, "getItemCount", int.class);
        currentIndex = method(manager, "getCurrentIndex", int.class);
        canRepeat = method(manager, "canSetRepeatMode", boolean.class);
        providerId = method(provider, "getId", int.class);
        providerCount = method(provider, "getItemCount", int.class);
        isDynamic = method(provider, "isDynamic", boolean.class);
        isMirrored = method(provider, "isMirroringRemoteQueue", boolean.class);
        repeat = field(manager, "repeatMode", int.class);
        softProvider = field(manager, "softQueueProvider", provider);
        providers = field(manager, "providersById", SparseArray.class);
        workHandler = field(manager,"workHandler",Handler.class);
        getUuid = method(manager,"getQueueUUID",java.util.UUID.class);
        Class<?> queueItem=loader.loadClass(PREFIX+"model.PlayerQueueItem");
        Class<?> mediaItem=loader.loadClass(PREFIX+"model.PlayerMediaItem");
        radioProvider=loader.loadClass(PREFIX+"queue.RadioPlaybackQueueItemProvider");
        autoplayProvider=loader.loadClass(PREFIX+"queue.AutoPlayPlaybackQueueItemProvider");
        Class<?> baseRadio=loader.loadClass(PREFIX+"queue.BaseRadioPlaybackQueueItemProvider");
        getQueueItem=method(manager,"getItemAtIndex",queueItem,int.class);
        getMediaItem=method(queueItem,"getItem",mediaItem);
        getQueueId=method(queueItem,"getPlaybackQueueId",long.class);
        duration=method(mediaItem,"getDuration",long.class);
        live=method(mediaItem,"isLiveRadio",boolean.class);
        episode=method(mediaItem,"isRadioEpisode",boolean.class);
        video=method(mediaItem,"isMediaKindVideo",boolean.class);
        continuous=method(mediaItem,"isFromContinuousPlayback",boolean.class);
        playable=method(mediaItem,"isPlayableContent",boolean.class);
        title=method(mediaItem,"getTitle",String.class);
        providerLive=method(baseRadio,"isLiveRadio",boolean.class);
        hasStream=method(baseRadio,"hasStreamItem",boolean.class);
        Class<?> modes = loader.loadClass(PREFIX + "model.PlaybackRepeatMode");
        check(modes.getField("REPEAT_MODE_OFF").getInt(null) == 0
                && modes.getField("REPEAT_MODE_ONE").getInt(null) == 1
                && modes.getField("REPEAT_MODE_ALL").getInt(null) == 2);
        Class<?> insertions = loader.loadClass(PREFIX + "queue.PlaybackQueueInsertionType");
        check(insertions.getField("INSERTION_TYPE_REPLACE").getInt(null) == 1
                && insertions.getField("INSERTION_TYPE_CLEAR_AND_REPLACE").getInt(null) == 5
                && insertions.getField("INSERTION_TYPE_CREATE_STATION").getInt(null) == 7);
        check(Modifier.isPrivate(addItemsInternal.getModifiers()) && Modifier.isPrivate(repeat.getModifiers()));
    }
    private static void check(boolean valid) { if (!valid) throw new IllegalStateException("Host contract mismatch"); }
    private static Method method(Class<?> owner, String name, Class<?> result, Class<?>... args) throws Exception {
        Method value = owner.getDeclaredMethod(name, args);
        check(value.getReturnType() == result && !Modifier.isStatic(value.getModifiers()));
        value.setAccessible(true); return value;
    }
    private static Field field(Class<?> owner, String name, Class<?> type) throws Exception {
        Field value = owner.getDeclaredField(name);
        check(value.getType() == type && !Modifier.isStatic(value.getModifiers()));
        value.setAccessible(true); return value;
    }
    @Override public int repeat(Object manager) throws Exception {
        // Dynamic queues hide inherited repeat state in getRepeatMode(); inspect stored state for cleanup.
        return repeat.getInt(manager);
    }
    RadioRepeatExperiment.Snapshot radioSnapshot(Object manager) throws Exception {
        if(manager==null) return RadioRepeatExperiment.Snapshot.unavailable(null);
        synchronized(manager) {
            int index=(Integer)currentIndex.invoke(manager), count=(Integer)itemCount.invoke(manager);
            if(index<0 || index>=count)
                return RadioRepeatExperiment.Snapshot.unavailable(manager);
            Object entry=getQueueItem.invoke(manager,index);
            if(entry==null) return RadioRepeatExperiment.Snapshot.unavailable(manager);
            long id=(Long)getQueueId.invoke(entry);
            Object provider=((SparseArray<?>)providers.get(manager)).get((int)(id>>32));
            boolean autoplay=autoplayProvider.isInstance(provider);
            if((!radioProvider.isInstance(provider) && !autoplay)
                    || (int)(id>>32)!=(Integer)providerId.invoke(provider)
                    || (Integer)providerCount.invoke(provider)<=0)
                return RadioRepeatExperiment.Snapshot.unavailable(manager);
            Object media=getMediaItem.invoke(entry), uuid=getUuid.invoke(manager);
            if(media==null || uuid==null) return RadioRepeatExperiment.Snapshot.unavailable(manager);
            return new RadioRepeatExperiment.Snapshot(manager,provider,uuid.toString(),id,(String)title.invoke(media),true,
                mirrored(provider),(Boolean)live.invoke(media)||(Boolean)providerLive.invoke(provider)||(Boolean)hasStream.invoke(provider),
                (Boolean)episode.invoke(media),(Boolean)video.invoke(media),(Boolean)continuous.invoke(media),
                (Boolean)playable.invoke(media),(Long)duration.invoke(media),autoplay);
        }
    }
    int activeRepeat(Object manager) throws Exception { return (Integer)getRepeat.invoke(manager); }
    int shuffle(Object manager) throws Exception { return (Integer)getShuffle.invoke(manager); }
    boolean canShuffle(Object manager) throws Exception { return (Boolean)canShuffle.invoke(manager); }

    void postQueueWork(Object manager, Runnable action) throws Exception {
        ((Handler)workHandler.get(manager)).post(action);
    }

    void notifyModes(Object manager) throws Exception {
        if(manager==null) return;
        postQueueWork(manager, () -> {
            synchronized(manager) {
                try { publish.invoke(manager); }
                catch(Exception error) { android.util.Log.w("TcrrryPlayback", "Radio mode notification failed",error); }
            }
        });
    }
    @Override public boolean committed(Object manager, Object provider) throws Exception {
        int id = (Integer) providerId.invoke(provider);
        int count = (Integer) itemCount.invoke(manager);
        int index = (Integer) currentIndex.invoke(manager);
        if (id < 0 || index < 0 || index >= count || (Integer) providerCount.invoke(provider) <= 0
                || ((SparseArray<?>) providers.get(manager)).get(id) != provider) return false;
        if (softProvider.get(manager) == provider) return true;
        // Some collection queues are published without using the soft provider slot.
        // Require the current queue item to belong to this exact registered provider.
        Object entry = getQueueItem.invoke(manager, index);
        return entry != null && (int)((Long) getQueueId.invoke(entry) >> 32) == id;
    }
    @Override public boolean mirrored(Object provider) throws Exception { return (Boolean) isMirrored.invoke(provider); }
    @Override public boolean dynamic(Object provider) throws Exception { return (Boolean) isDynamic.invoke(provider); }
    @Override public boolean canRepeat(Object manager) throws Exception { return (Boolean) canRepeat.invoke(manager); }
    @Override public void clearAtNativePublication(Object manager) throws Exception {
        // Native addItemsInternal already holds this monitor and will publish/save modes next.
        check(Thread.holdsLock(manager));
        repeat.setInt(manager, 0);
    }
}
