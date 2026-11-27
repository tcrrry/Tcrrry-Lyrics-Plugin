package com.tcrrry.playlisttools;

import android.app.Application;
import android.net.Uri;
import dev.amenhancer.plugin.api.PluginCall;
import dev.amenhancer.plugin.api.PluginContext;
import dev.amenhancer.plugin.api.PluginHook;
import dev.amenhancer.plugin.api.PluginHooks;
import dev.amenhancer.plugin.api.PluginRegistration;
import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public final class PlaylistCoverFixer {
    private final PluginContext runtime;
    private final HostContract host;
    private final CoverStore store;
    private final List<PluginRegistration> registrations = new ArrayList<>();
    private final ThreadLocal<Boolean> reentrancyGuard = new ThreadLocal<>();

    public PlaylistCoverFixer(PluginContext runtime, HostContract host, CoverStore store) {
        this.runtime = runtime;
        this.host = host;
        this.store = store;
    }

    public void install() {
        PluginHooks hooks = runtime.getHooks();
        Application app = runtime.getApplication();

        // Layer 1A: Fix missing playlistImageUrl update & non-file URI handling in updatePlaylistWithCroppedImage(Uri)
        registrations.add(hooks.hook(host.updateWithCroppedImageMethod, false, new PluginHook() {
            @Override public void before(PluginCall call) {
                if (!store.isNativeFixEnabled()) {
                    return;
                }
                Object vm = call.getReceiver();
                Object[] args = call.getArguments();
                if (vm == null || args == null || args.length == 0 || !(args[0] instanceof Uri)) {
                    return;
                }
                try {
                    long pid = host.pidField.getLong(vm);
                    Uri uri = (Uri) args[0];
                    Uri finalUri = uri;
                    String path = uri.getPath();
                    if (!"file".equalsIgnoreCase(uri.getScheme()) || path == null || !new File(path).exists()) {
                        finalUri = CoverStore.copyUriToCacheFile(app, uri, pid);
                        args[0] = finalUri;
                    }
                    host.playlistImageUrlField.set(vm, finalUri.toString());
                    if (pid != 0L) {
                        store.saveCapturedCover(pid, finalUri, app.getContentResolver());
                    }
                    store.recordDiagnostic("裁剪图写入会话 pid=" + pid + " scheme=" + uri.getScheme()
                            + (store.isPlaylistLocked(pid) ? " (已锁定保底)" : ""));
                } catch (Exception error) {
                    runtime.log("Playlist cover pre-fix failed", error);
                    store.recordDiagnostic("裁剪图预处理失败: " + error.getMessage());
                }
            }

            @Override public void after(PluginCall call) {
                if (!store.isNativeFixEnabled() || call.getThrowable() != null) {
                    return;
                }
                Object vm = call.getReceiver();
                if (vm == null) {
                    return;
                }
                try {
                    host.initStateField.set(vm, host.customArtworkState);
                    host.setCoverSelectedMethod.invoke(vm, 0);
                } catch (Exception error) {
                    runtime.log("Playlist cover post-fix failed", error);
                }
            }
        }));

        // Layer 1B: Ensure populateCoversData(true) sees PLAYLIST_ARTWORK_URL in playlistImageUrl
        registrations.add(hooks.hook(host.populateCoversDataMethod, false, new PluginHook() {
            @Override public void before(PluginCall call) {
                if (!store.isNativeFixEnabled()) {
                    return;
                }
                Object vm = call.getReceiver();
                Object[] args = call.getArguments();
                if (vm == null || args == null || args.length == 0 || !Boolean.TRUE.equals(args[0])) {
                    return;
                }
                try {
                    String current = (String) host.playlistImageUrlField.get(vm);
                    if (current == null || current.isEmpty() || "error url".equals(current)) {
                        Object session = host.playlistEditSessionField.get(vm);
                        if (session != null) {
                            String sessionUrl = (String) host.sessionGetPropertyMethod.invoke(session, host.propArtworkUrl);
                            if (sessionUrl != null && !sessionUrl.trim().isEmpty()) {
                                host.playlistImageUrlField.set(vm, sessionUrl.trim());
                            }
                        }
                    }
                } catch (Exception error) {
                    runtime.log("populateCoversData cover fix failed", error);
                }
            }
        }));

        // Layer 1B2: Also hook NewPlaylistViewModel.updatePlaylistWithCroppedImage(Uri)
        registrations.add(hooks.hook(host.newUpdateWithCroppedImageMethod, false, new PluginHook() {
            @Override public void before(PluginCall call) {
                if (!store.isNativeFixEnabled()) {
                    return;
                }
                Object vm = call.getReceiver();
                Object[] args = call.getArguments();
                if (vm == null || args == null || args.length == 0 || !(args[0] instanceof Uri)) {
                    return;
                }
                try {
                    long pid = host.newPidField.getLong(vm);
                    Uri uri = (Uri) args[0];
                    Uri finalUri = uri;
                    String path = uri.getPath();
                    if (!"file".equalsIgnoreCase(uri.getScheme()) || path == null || !new File(path).exists()) {
                        finalUri = CoverStore.copyUriToCacheFile(app, uri, pid);
                        args[0] = finalUri;
                    }
                    host.newPlaylistImageUrlField.set(vm, finalUri.toString());
                    if (pid != 0L) {
                        store.saveCapturedCover(pid, finalUri, app.getContentResolver());
                        if (store.isPlaylistLocked(pid)) {
                            invalidateHostArtworkCache(pid);
                        }
                        store.recordDiagnostic("NewVM裁剪图捕获 pid=" + pid + (store.isPlaylistLocked(pid) ? " (已锁定保底)" : ""));
                    }
                } catch (Exception error) {
                    runtime.log("NewPlaylistViewModel cover pre-fix failed", error);
                }
            }
        }));

        // Layer 1C: Prevent "0.1" clear-cover recipe overwrite, capture session artwork URI, & log SVPlaylistSession errors
        for (Method setPropMethod : new Method[]{host.sessionImplASetPropertyMethod, host.sessionImplQSetPropertyMethod}) {
            registrations.add(hooks.hook(setPropMethod, false, new PluginHook() {
                @Override public void before(PluginCall call) {
                    Object[] args = call.getArguments();
                    Object session = call.getReceiver();
                    if (session == null || args == null || args.length < 2) {
                        return;
                    }
                    if (args[0] == host.propArtworkUrl && args[1] instanceof String) {
                        String url = ((String) args[1]).trim();
                        if (url.startsWith("file://") || url.startsWith("/")) {
                            try {
                                long pid = readSessionPid(session);
                                if (pid != 0L) {
                                    Uri uri = url.startsWith("file://") ? Uri.parse(url) : Uri.fromFile(new File(url));
                                    store.saveCapturedCover(pid, uri, app.getContentResolver());
                                    if (store.isPlaylistLocked(pid)) {
                                        invalidateHostArtworkCache(pid);
                                    }
                                    store.recordDiagnostic("捕获封面 pid=" + pid + (store.isPlaylistLocked(pid) ? " (已锁定保底)" : ""));
                                } else {
                                    store.recordDiagnostic("发现封面写入但 pid=0 (可能为新建歌单)");
                                }
                            } catch (Exception error) {
                                runtime.log("Session cover capture failed", error);
                                store.recordDiagnostic("会话封面捕获失败: " + error.getMessage());
                            }
                        }
                        return;
                    }
                    if (!store.isNativeFixEnabled() || args[0] != host.propCoverRecipe || !(args[1] instanceof String)) {
                        return;
                    }
                    try {
                        String artworkUrl = readSessionArtworkUrl(session);
                        String original = (String) args[1];
                        String rewritten = CoverStore.rewriteRecipeIfCustomCoverPresent(original, artworkUrl);
                        if (!rewritten.equals(original)) {
                            args[1] = rewritten;
                            store.recordDiagnostic("拦截清除封面配方 0.1->0.0 (已存在自定义图)");
                        }
                    } catch (Exception error) {
                        runtime.log("Cover recipe guard failed", error);
                    }
                }

                @Override public void after(PluginCall call) {
                    Object[] args = call.getArguments();
                    if (args == null || args.length < 2) {
                        return;
                    }
                    if (args[0] != host.propArtworkUrl && args[0] != host.propCoverRecipe) {
                        return;
                    }
                    try {
                        String label = args[0] == host.propArtworkUrl ? "ARTWORK_URL" : "COVER_RECIPE";
                        String val = args[1] instanceof String ? abbreviate((String) args[1], 72) : String.valueOf(args[1]);
                        String errCode;
                        if (call.getThrowable() != null) {
                            errCode = "EX:" + call.getThrowable().getClass().getSimpleName();
                        } else if (call.getResult() != null) {
                            errCode = String.valueOf(host.svMediaErrorCodeMethod.invoke(call.getResult()));
                        } else {
                            errCode = "null";
                        }
                        store.recordDiagnostic("写入 " + label + "=" + val + " -> " + errCode);
                    } catch (Exception ignored) {}
                }
            }));
        }

        // Layer 2: Local cover lock fallback (guards against Apple cloud sync reverting locked playlists)
        registrations.add(hooks.hook(host.baseContentGetImageUrlMethod, false, new PluginHook() {
            @Override public void after(PluginCall call) {
                if (reentrancyGuard.get() != null || call.getThrowable() != null) {
                    return;
                }
                Object receiver = call.getReceiver();
                if (!host.playlistCollectionItemClass.isInstance(receiver)) {
                    return;
                }
                reentrancyGuard.set(Boolean.TRUE);
                try {
                    long pid = (Long) host.baseContentGetPidMethod.invoke(receiver);
                    String lockedUri = store.getLockedCoverFileUri(pid);
                    if (lockedUri != null) {
                        call.returnResult(lockedUri);
                    }
                } catch (Exception ignored) {
                } finally {
                    reentrancyGuard.remove();
                }
            }
        }));

        registrations.add(hooks.hook(host.playlistItemGetImageUrlsMethod, false, new PluginHook() {
            @Override public void after(PluginCall call) {
                if (reentrancyGuard.get() != null || call.getThrowable() != null) {
                    return;
                }
                Object receiver = call.getReceiver();
                if (receiver == null) {
                    return;
                }
                reentrancyGuard.set(Boolean.TRUE);
                try {
                    long pid = (Long) host.baseContentGetPidMethod.invoke(receiver);
                    String lockedUri = store.getLockedCoverFileUri(pid);
                    if (lockedUri != null) {
                        call.returnResult(new String[]{lockedUri});
                    }
                } catch (Exception ignored) {
                } finally {
                    reentrancyGuard.remove();
                }
            }
        }));

        registrations.add(hooks.hook(host.mediaEntityGetImageUrlMethod, false, new PluginHook() {
            @Override public void after(PluginCall call) {
                if (reentrancyGuard.get() != null || call.getThrowable() != null) {
                    return;
                }
                Object receiver = call.getReceiver();
                if (!host.mediaApiPlaylistClass.isInstance(receiver)) {
                    return;
                }
                reentrancyGuard.set(Boolean.TRUE);
                try {
                    Long pid = (Long) host.mediaEntityGetPidMethod.invoke(receiver);
                    if (pid != null) {
                        String lockedUri = store.getLockedCoverFileUri(pid);
                        if (lockedUri != null) {
                            call.returnResult(lockedUri);
                        }
                    }
                } catch (Exception ignored) {
                } finally {
                    reentrancyGuard.remove();
                }
            }
        }));

        registrations.add(hooks.hook(host.mediaApiPlaylistToCollectionItemMethod, false, new PluginHook() {
            @Override public void after(PluginCall call) {
                if (reentrancyGuard.get() != null || call.getThrowable() != null) {
                    return;
                }
                Object item = call.getResult();
                if (!host.playlistCollectionItemClass.isInstance(item)) {
                    return;
                }
                reentrancyGuard.set(Boolean.TRUE);
                try {
                    long pid = (Long) host.baseContentGetPidMethod.invoke(item);
                    String lockedUri = store.getLockedCoverFileUri(pid);
                    if (lockedUri != null) {
                        host.baseContentSetImageUrlMethod.invoke(item, lockedUri);
                        host.playlistItemSetRecipeMethod.invoke(item, new Object[]{null});
                    }
                } catch (Exception ignored) {
                } finally {
                    reentrancyGuard.remove();
                }
            }
        }));

        registrations.add(hooks.hook(host.mlArtworkObserverOnChangedMethod, false, new PluginHook() {
            @Override public void before(PluginCall call) {
                if (reentrancyGuard.get() != null) {
                    return;
                }
                Object observer = call.getReceiver();
                Object[] args = call.getArguments();
                if (observer == null || args == null || args.length == 0) {
                    return;
                }
                reentrancyGuard.set(Boolean.TRUE);
                try {
                    Object item = host.mlArtworkObserverItemField.get(observer);
                    if (host.playlistCollectionItemClass.isInstance(item)) {
                        long pid = (Long) host.baseContentGetPidMethod.invoke(item);
                        String lockedUri = store.getLockedCoverFileUri(pid);
                        if (lockedUri != null) {
                            args[0] = new String[]{lockedUri};
                        }
                    }
                } catch (Exception ignored) {
                } finally {
                    reentrancyGuard.remove();
                }
            }
        }));

        registrations.add(hooks.hook(host.commonNResolveEntityKeyMethod, false, new PluginHook() {
            @Override public void after(PluginCall call) {
                if (reentrancyGuard.get() != null || call.getThrowable() != null) {
                    return;
                }
                Object[] args = call.getArguments();
                if (args == null || args.length == 0 || !host.mediaApiPlaylistClass.isInstance(args[0])) {
                    return;
                }
                reentrancyGuard.set(Boolean.TRUE);
                try {
                    Long pid = (Long) host.mediaEntityGetPidMethod.invoke(args[0]);
                    if (pid != null) {
                        String lockedUri = store.getLockedCoverFileUri(pid);
                        if (lockedUri != null) {
                            call.returnResult(lockedUri);
                        }
                    }
                } catch (Exception ignored) {
                } finally {
                    reentrancyGuard.remove();
                }
            }
        }));

        registrations.add(hooks.hook(host.delegatingSetImageUrlsMethod, false, new PluginHook() {
            @Override public void before(PluginCall call) {
                if (reentrancyGuard.get() != null) {
                    return;
                }
                Object receiver = call.getReceiver();
                Object[] args = call.getArguments();
                if (receiver == null || args == null || args.length == 0) {
                    return;
                }
                reentrancyGuard.set(Boolean.TRUE);
                try {
                    Object inner = host.delegatingItemField.get(receiver);
                    if (host.playlistCollectionItemClass.isInstance(inner)) {
                        long pid = (Long) host.delegatingGetPidMethod.invoke(receiver);
                        String lockedUri = store.getLockedCoverFileUri(pid);
                        if (lockedUri != null) {
                            args[0] = new String[]{lockedUri};
                        }
                    }
                } catch (Exception ignored) {
                } finally {
                    reentrancyGuard.remove();
                }
            }
        }));

        for (Method delegatingSetUrl : new Method[]{host.delegatingSetImageUrlMethod, host.delegatingSetImageUrlVariantMethod}) {
            registrations.add(hooks.hook(delegatingSetUrl, false, new PluginHook() {
                @Override public void before(PluginCall call) {
                    if (reentrancyGuard.get() != null) {
                        return;
                    }
                    Object receiver = call.getReceiver();
                    Object[] args = call.getArguments();
                    if (receiver == null || args == null || args.length == 0) {
                        return;
                    }
                    reentrancyGuard.set(Boolean.TRUE);
                    try {
                        Object inner = host.delegatingItemField.get(receiver);
                        if (host.playlistCollectionItemClass.isInstance(inner)) {
                            long pid = (Long) host.delegatingGetPidMethod.invoke(receiver);
                            String lockedUri = store.getLockedCoverFileUri(pid);
                            if (lockedUri != null) {
                                args[0] = lockedUri;
                            }
                        }
                    } catch (Exception ignored) {
                    } finally {
                        reentrancyGuard.remove();
                    }
                }
            }));
        }

        registrations.add(hooks.hook(host.playlistAddHeaderModelMethod, false, new PluginHook() {
            @Override public void after(PluginCall call) {
                if (reentrancyGuard.get() != null || call.getThrowable() != null) {
                    return;
                }
                Object controller = call.getReceiver();
                Object[] args = call.getArguments();
                if (controller == null || args == null || args.length == 0 || args[0] == null) {
                    return;
                }
                reentrancyGuard.set(Boolean.TRUE);
                try {
                    Long pid = (Long) host.mediaEntityGetPidMethod.invoke(args[0]);
                    if (pid != null) {
                        String lockedUri = store.getLockedCoverFileUri(pid);
                        if (lockedUri != null) {
                            Object liveData = host.playlistHeaderLiveImageUrlsField.get(controller);
                            if (liveData != null) {
                                host.liveDataPostValueMethod.invoke(liveData, new Object[]{new String[]{lockedUri}});
                            }
                        }
                    }
                } catch (Exception ignored) {
                } finally {
                    reentrancyGuard.remove();
                }
            }
        }));

        registrations.add(hooks.hook(host.playlistCreateArtworkRecipeCoverMethod, false, new PluginHook() {
            @Override public void before(PluginCall call) {
                if (reentrancyGuard.get() != null) {
                    return;
                }
                Object[] args = call.getArguments();
                if (args == null || args.length == 0 || args[0] == null) {
                    return;
                }
                reentrancyGuard.set(Boolean.TRUE);
                try {
                    Long pid = (Long) host.mediaEntityGetPidMethod.invoke(args[0]);
                    if (pid != null && store.getLockedCoverFileUri(pid) != null) {
                        call.returnResult(null);
                    }
                } catch (Exception ignored) {
                } finally {
                    reentrancyGuard.remove();
                }
            }
        }));
    }

    private long readSessionPid(Object session) throws Exception {
        if (host.sessionJ9PidMethod.getDeclaringClass().isInstance(session)) {
            return (Long) host.sessionJ9PidMethod.invoke(session);
        }
        if (host.sessionJ6PidMethod.getDeclaringClass().isInstance(session)) {
            return (Long) host.sessionJ6PidMethod.invoke(session);
        }
        return 0L;
    }

    private String readSessionArtworkUrl(Object session) throws Exception {
        if (host.sessionGetPropertyMethod.getDeclaringClass().isInstance(session)) {
            return (String) host.sessionGetPropertyMethod.invoke(session, host.propArtworkUrl);
        }
        Method m = session.getClass().getMethod("getPlaylistProperty", host.propArtworkUrl.getClass());
        return (String) m.invoke(session, host.propArtworkUrl);
    }

    void invalidateHostArtworkCache(long pid) {
        if (pid == 0L || host.imageCacheInstance == null) {
            return;
        }
        try {
            host.imageCacheClearPid1Method.invoke(host.imageCacheInstance, pid);
            host.imageCacheClearPid2Method.invoke(host.imageCacheInstance, pid);
        } catch (Exception error) {
            runtime.log("Invalidate host artwork cache failed for pid=" + pid, error);
        }
    }

    public void stop() {
        for (int i = registrations.size() - 1; i >= 0; i--) {
            try {
                registrations.get(i).close();
            } catch (Exception error) {
                runtime.log("Playlist cover hook cleanup failed", error);
            }
        }
        registrations.clear();
    }

    private static String abbreviate(String s, int max) {
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max - 3) + "...";
    }
}
