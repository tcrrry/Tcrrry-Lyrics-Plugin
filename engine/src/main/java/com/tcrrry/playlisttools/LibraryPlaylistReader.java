package com.tcrrry.playlisttools;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class LibraryPlaylistReader {
    private final HostContract host;

    public LibraryPlaylistReader(HostContract host) {
        this.host = host;
    }

    public List<PlaylistModels.PlaylistSummary> listPlaylists() throws Exception {
        Object ml = getReadyMediaLibrary();
        Object builder = host.playlistQueryBuilderCtor.newInstance();
        Object params = host.playlistQueryBuildMethod.invoke(builder);
        Object single = host.queryLibraryPlaylistsMethod.invoke(ml, params);
        Object results = host.blockingGetMethod.invoke(single);
        if (results == null) {
            return new ArrayList<>();
        }
        try {
            int count = (Integer) host.resultsCountMethod.invoke(results);
            List<PlaylistModels.PlaylistSummary> list = new ArrayList<>(Math.max(0, count));
            for (int i = 0; i < count; i++) {
                Object item = host.resultsItemAtMethod.invoke(results, i);
                if (item == null) {
                    continue;
                }
                PlaylistModels.PlaylistSummary summary = readPlaylistSummary(item);
                if (summary.folder) {
                    continue;
                }
                if (summary.persistentId == 0L && summary.title.isEmpty()) {
                    continue;
                }
                int resolvedCount = summary.trackCount;
                if (resolvedCount <= 0) {
                    resolvedCount = queryTrackCount(ml, item);
                }
                list.add(new PlaylistModels.PlaylistSummary(
                        summary.persistentId,
                        summary.title,
                        resolvedCount,
                        summary.folder,
                        summary.cloudId,
                        summary.globalId,
                        summary.url,
                        summary.curatorName));
            }
            return list;
        } finally {
            safeReleaseResults(results);
        }
    }

    public List<PlaylistModels.PlaylistTracksBundle> readTracksForPlaylists(Collection<Long> targetPids) throws Exception {
        if (targetPids == null || targetPids.isEmpty()) {
            return new ArrayList<>();
        }
        Object ml = getReadyMediaLibrary();
        Object builder = host.playlistQueryBuilderCtor.newInstance();
        Object params = host.playlistQueryBuildMethod.invoke(builder);
        Object single = host.queryLibraryPlaylistsMethod.invoke(ml, params);
        Object playlistResults = host.blockingGetMethod.invoke(single);
        if (playlistResults == null) {
            return new ArrayList<>();
        }

        Map<Long, PendingPlaylistQuery> matched = new LinkedHashMap<>();
        try {
            int count = (Integer) host.resultsCountMethod.invoke(playlistResults);
            for (int i = 0; i < count; i++) {
                Object item = host.resultsItemAtMethod.invoke(playlistResults, i);
                if (item == null) {
                    continue;
                }
                PlaylistModels.PlaylistSummary summary = readPlaylistSummary(item);
                if (summary.folder || !targetPids.contains(summary.persistentId) || matched.containsKey(summary.persistentId)) {
                    continue;
                }
                Object itemInfo = host.itemInfoFromCollectionItemMethod.invoke(null, item);
                int sortDescriptor = callInt(item, "getSortDescriptor", 0);
                int sortOrder = callInt(item, "getSortOrder", 0);
                matched.put(summary.persistentId, new PendingPlaylistQuery(summary, itemInfo, sortDescriptor, sortOrder));
            }
        } catch (Exception error) {
            for (PendingPlaylistQuery q : matched.values()) {
                safeReleaseItemInfo(q.itemInfo);
            }
            throw error;
        } finally {
            safeReleaseResults(playlistResults);
        }

        List<PlaylistModels.PlaylistTracksBundle> bundles = new ArrayList<>(matched.size());
        try {
            for (PendingPlaylistQuery pending : matched.values()) {
                List<PlaylistModels.PlaylistTrack> tracks = queryTracksWithItemInfo(
                        ml, pending.itemInfo, pending.sortDescriptor, pending.sortOrder);
                bundles.add(new PlaylistModels.PlaylistTracksBundle(pending.summary, tracks));
            }
        } finally {
            for (PendingPlaylistQuery q : matched.values()) {
                safeReleaseItemInfo(q.itemInfo);
            }
        }
        return bundles;
    }

    public PlaylistModels.PlaylistTracksBundle readPlaylistForExport(
            long persistentId, String cloudId, String globalId, String title) throws Exception {
        Object ml = getReadyMediaLibrary();
        Object builder = host.playlistQueryBuilderCtor.newInstance();
        Object params = host.playlistQueryBuildMethod.invoke(builder);
        Object single = host.queryLibraryPlaylistsMethod.invoke(ml, params);
        Object playlistResults = host.blockingGetMethod.invoke(single);
        if (playlistResults == null) {
            return null;
        }

        int bestIndex = -1;
        int bestPriority = 0;
        PlaylistModels.PlaylistSummary bestSummary = null;
        Object itemInfo = null;
        int sortDescriptor = 0;
        int sortOrder = 0;
        try {
            int count = (Integer) host.resultsCountMethod.invoke(playlistResults);
            for (int i = 0; i < count; i++) {
                Object item = host.resultsItemAtMethod.invoke(playlistResults, i);
                if (item == null) {
                    continue;
                }
                PlaylistModels.PlaylistSummary summary = readPlaylistSummary(item);
                if (summary.folder) {
                    continue;
                }
                int priority = matchPriority(summary, persistentId, cloudId, globalId, title);
                if (priority > bestPriority) {
                    bestPriority = priority;
                    bestIndex = i;
                    bestSummary = summary;
                    sortDescriptor = callInt(item, "getSortDescriptor", 0);
                    sortOrder = callInt(item, "getSortOrder", 0);
                    if (priority == 4) {
                        break;
                    }
                }
            }
            if (bestIndex >= 0) {
                Object bestItem = host.resultsItemAtMethod.invoke(playlistResults, bestIndex);
                if (bestItem != null) {
                    itemInfo = host.itemInfoFromCollectionItemMethod.invoke(null, bestItem);
                }
            }
        } finally {
            safeReleaseResults(playlistResults);
        }

        if (bestSummary == null || itemInfo == null) {
            safeReleaseItemInfo(itemInfo);
            return null;
        }
        try {
            List<PlaylistModels.PlaylistTrack> tracks = queryTracksWithItemInfo(ml, itemInfo, sortDescriptor, sortOrder);
            return new PlaylistModels.PlaylistTracksBundle(bestSummary, tracks);
        } finally {
            safeReleaseItemInfo(itemInfo);
        }
    }

    private static int matchPriority(
            PlaylistModels.PlaylistSummary summary,
            long persistentId,
            String cloudId,
            String globalId,
            String title) {
        if (persistentId > 0L && summary.persistentId == persistentId) {
            return 4;
        }
        if (cloudId != null && !cloudId.isEmpty()
                && (cloudId.equals(summary.cloudId) || cloudId.equals(summary.globalId))) {
            return 3;
        }
        if (globalId != null && !globalId.isEmpty()
                && (globalId.equals(summary.globalId) || globalId.equals(summary.cloudId))) {
            return 2;
        }
        if (title != null && !title.isEmpty() && title.equals(summary.title)) {
            return 1;
        }
        return 0;
    }

    private int queryTrackCount(Object ml, Object collectionItem) {
        Object itemInfo = null;
        Object trackResults = null;
        try {
            itemInfo = host.itemInfoFromCollectionItemMethod.invoke(null, collectionItem);
            if (itemInfo == null) {
                return 0;
            }
            Object itemBuilder = host.itemQueryBuilderCtor.newInstance();
            Object itemParams = host.itemQueryBuildMethod.invoke(itemBuilder);
            Object single = host.queryItemsFromPlaylistMethod.invoke(ml, itemInfo, itemParams);
            trackResults = host.blockingGetMethod.invoke(single);
            if (trackResults == null) {
                return 0;
            }
            return (Integer) host.resultsCountMethod.invoke(trackResults);
        } catch (Exception ignored) {
            return 0;
        } finally {
            if (trackResults != null) {
                safeReleaseResults(trackResults);
            }
            safeReleaseItemInfo(itemInfo);
        }
    }

    private List<PlaylistModels.PlaylistTrack> queryTracksWithItemInfo(
            Object ml, Object itemInfo, int sortDescriptor, int sortOrder) throws Exception {
        if (itemInfo == null) {
            return new ArrayList<>();
        }
        Object itemBuilder = host.itemQueryBuilderCtor.newInstance();
        applyPlaylistSort(itemBuilder, sortDescriptor, sortOrder);
        Object itemParams = host.itemQueryBuildMethod.invoke(itemBuilder);
        Object single = host.queryItemsFromPlaylistMethod.invoke(ml, itemInfo, itemParams);
        Object results = host.blockingGetMethod.invoke(single);
        if (results == null) {
            return new ArrayList<>();
        }
        try {
            int count = (Integer) host.resultsCountMethod.invoke(results);
            List<PlaylistModels.PlaylistTrack> tracks = new ArrayList<>(Math.max(0, count));
            for (int i = 0; i < count; i++) {
                Object item = host.resultsItemAtMethod.invoke(results, i);
                if (item != null) {
                    tracks.add(readTrack(i + 1, item));
                }
            }
            return tracks;
        } finally {
            safeReleaseResults(results);
        }
    }

    private void applyPlaylistSort(Object itemBuilder, int sortDescriptor, int sortOrder) {
        if (sortDescriptor == 0 && sortOrder == 0) {
            return;
        }
        try {
            Object[] fieldConstants = host.sortFieldEnumClass.getEnumConstants();
            Object[] orderConstants = host.sortOrderEnumClass.getEnumConstants();
            if (fieldConstants == null || orderConstants == null) {
                return;
            }
            Object matchedField = null;
            Object fallbackField = null;
            for (Object c : fieldConstants) {
                int val = (Integer) host.sortFieldValueMethod.invoke(c);
                if (val == sortDescriptor) {
                    matchedField = c;
                    break;
                }
                if (val == 0) {
                    fallbackField = c;
                }
            }
            if (matchedField == null) {
                matchedField = fallbackField;
            }
            Object matchedOrder = null;
            Object fallbackOrder = null;
            for (Object c : orderConstants) {
                int val = (Integer) host.sortOrderValueMethod.invoke(c);
                if (val == sortOrder) {
                    matchedOrder = c;
                    break;
                }
                if (val == 0) {
                    fallbackOrder = c;
                }
            }
            if (matchedOrder == null) {
                matchedOrder = fallbackOrder;
            }
            if (matchedField != null && matchedOrder != null) {
                Object sortObj = host.sortDescriptorCtor.newInstance(matchedField, matchedOrder);
                host.queryBuilderSortField.set(itemBuilder, sortObj);
            }
        } catch (Exception ignored) {
            // Fall back to default playlist order if host sort descriptor mapping fails.
        }
    }

    private Object getReadyMediaLibrary() throws Exception {
        Object ml = host.mlInstanceMethod.invoke(host.facadeHolderInstance);
        if (ml == null) {
            throw new IllegalStateException("Apple Music 资料库尚未初始化，请先打开资料库页面后再试");
        }
        boolean ready = (Boolean) host.isReadyMethod.invoke(ml);
        if (!ready) {
            throw new IllegalStateException("Apple Music 资料库仍在加载中，请稍候片刻再重试");
        }
        return ml;
    }

    private static PlaylistModels.PlaylistSummary readPlaylistSummary(Object item) {
        long pid = callLong(item, "getPersistentId", 0L);
        String title = nonEmpty(callString(item, "getTitle"), "未命名歌单");
        int trackCount = callInt(item, "getLibraryTrackCount", -1);
        if (trackCount < 0) {
            trackCount = callInt(item, "getTrackCount", -1);
        }
        if (trackCount < 0) {
            trackCount = callInt(item, "getItemCount", 0);
        }
        boolean folder = callBool(item, "isFolder", false);
        String cloudId = emptyIfNull(callString(item, "getCloudId"));
        String globalId = emptyIfNull(callString(item, "getGlobalId"));
        String url = emptyIfNull(callString(item, "getUrl"));
        String curatorName = emptyIfNull(callString(item, "getCuratorName"));
        return new PlaylistModels.PlaylistSummary(pid, title, trackCount, folder, cloudId, globalId, url, curatorName);
    }

    private static PlaylistModels.PlaylistTrack readTrack(int index, Object item) {
        String title = emptyIfNull(callString(item, "getTitle"));
        String artist = nonEmpty(callString(item, "getArtistName"), emptyIfNull(callString(item, "getSubTitle")));
        String album = emptyIfNull(callString(item, "getCollectionName"));
        long durationMs = callLong(item, "getPlaybackDuration", 0L);
        int trackNo = callInt(item, "getTrackNumber", 0);
        int discNo = callInt(item, "getAlbumDiscNumber", 0);
        String year = emptyIfNull(callString(item, "getReleaseYear"));

        String appleMusicId = emptyIfNull(callString(item, "getSubscriptionStoreId"));
        if (appleMusicId.isEmpty()) {
            long adamId = callLong(item, "getAssetAdamId", 0L);
            if (adamId > 0L) {
                appleMusicId = Long.toString(adamId);
            } else {
                appleMusicId = emptyIfNull(callString(item, "getId"));
            }
        }
        String url = emptyIfNull(callString(item, "getUrl"));
        if (url.isEmpty()) {
            url = emptyIfNull(callString(item, "getShortUrl"));
        }
        return new PlaylistModels.PlaylistTrack(
                index, title, artist, album, durationMs, trackNo, discNo, year, appleMusicId, url);
    }

    private void safeReleaseResults(Object results) {
        try {
            host.resultsReleaseMethod.invoke(results);
        } catch (Exception ignored) {}
    }

    private void safeReleaseItemInfo(Object itemInfo) {
        if (itemInfo == null) {
            return;
        }
        try {
            host.itemInfoReleaseMethod.invoke(itemInfo);
        } catch (Exception ignored) {}
    }

    private static String callString(Object target, String name) {
        Object res = callNoArg(target, name);
        return res instanceof String ? (String) res : null;
    }

    private static long callLong(Object target, String name, long fallback) {
        Object res = callNoArg(target, name);
        return res instanceof Number ? ((Number) res).longValue() : fallback;
    }

    private static int callInt(Object target, String name, int fallback) {
        Object res = callNoArg(target, name);
        return res instanceof Number ? ((Number) res).intValue() : fallback;
    }

    private static boolean callBool(Object target, String name, boolean fallback) {
        Object res = callNoArg(target, name);
        return res instanceof Boolean ? (Boolean) res : fallback;
    }

    private static Object callNoArg(Object target, String name) {
        if (target == null) {
            return null;
        }
        try {
            Method m = target.getClass().getMethod(name);
            return m.invoke(target);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String emptyIfNull(String s) {
        return s == null ? "" : s.trim();
    }

    private static String nonEmpty(String s, String fallback) {
        if (s == null) {
            return fallback;
        }
        String trimmed = s.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    private static final class PendingPlaylistQuery {
        final PlaylistModels.PlaylistSummary summary;
        final Object itemInfo;
        final int sortDescriptor;
        final int sortOrder;

        PendingPlaylistQuery(
                PlaylistModels.PlaylistSummary summary, Object itemInfo, int sortDescriptor, int sortOrder) {
            this.summary = summary;
            this.itemInfo = itemInfo;
            this.sortDescriptor = sortDescriptor;
            this.sortOrder = sortOrder;
        }
    }
}
