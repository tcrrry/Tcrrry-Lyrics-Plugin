package com.tcrrry.playlisttools;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import dev.amenhancer.plugin.api.PluginCall;
import dev.amenhancer.plugin.api.PluginContext;
import dev.amenhancer.plugin.api.PluginHook;
import dev.amenhancer.plugin.api.PluginHooks;
import dev.amenhancer.plugin.api.PluginRegistration;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

public final class PlaylistActionSheetMenu {
    private static final String EXPORT_ROW_ID = "tcrrry_export_playlist_menu_row";
    private static final String EXPORT_ROW_LABEL = "导出播放列表";

    private final PluginContext runtime;
    private final HostContract host;
    private final LibraryPlaylistReader reader;
    private final ExecutorService executor;
    private final BooleanSupplier enabledSupplier;
    private final Supplier<PlaylistModels.ExportFormat> formatSupplier;
    private final Function<String, PlaylistExporter.ExportOptions> optionsProvider;
    private final BiConsumer<String, String> uriRecorder;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<PluginRegistration> registrations = new ArrayList<>();
    private final ThreadLocal<Boolean> readingNativeCount = new ThreadLocal<>();

    public PlaylistActionSheetMenu(
            PluginContext runtime,
            HostContract host,
            LibraryPlaylistReader reader,
            ExecutorService executor,
            BooleanSupplier enabledSupplier,
            Supplier<PlaylistModels.ExportFormat> formatSupplier) {
        this(runtime, host, reader, executor, enabledSupplier, formatSupplier, null, null);
    }

    public PlaylistActionSheetMenu(
            PluginContext runtime,
            HostContract host,
            LibraryPlaylistReader reader,
            ExecutorService executor,
            BooleanSupplier enabledSupplier,
            Supplier<PlaylistModels.ExportFormat> formatSupplier,
            Function<String, PlaylistExporter.ExportOptions> optionsProvider,
            BiConsumer<String, String> uriRecorder) {
        this.runtime = runtime;
        this.host = host;
        this.reader = reader;
        this.executor = executor;
        this.enabledSupplier = enabledSupplier;
        this.formatSupplier = formatSupplier;
        this.optionsProvider = optionsProvider;
        this.uriRecorder = uriRecorder;
    }

    public void install() {
        PluginHooks hooks = runtime.getHooks();

        registrations.add(hooks.hook(host.actionSheetGetItemCountMethod, false, new PluginHook() {
            @Override public void after(PluginCall call) {
                if (!enabledSupplier.getAsBoolean()
                        || Boolean.TRUE.equals(readingNativeCount.get())
                        || call.getThrowable() != null) {
                    return;
                }
                Object adapter = call.getReceiver();
                Object result = call.getResult();
                if (adapter == null || !(result instanceof Integer)) {
                    return;
                }
                try {
                    Object target = host.actionSheetTargetItemField.get(adapter);
                    if (isExportablePlaylistTarget(target)) {
                        call.returnResult(((Integer) result) + 1);
                    }
                } catch (Exception error) {
                    runtime.log("ActionSheet getItemCount hook failed", error);
                }
            }
        }));

        registrations.add(hooks.hook(host.actionSheetGetItemAtIndexMethod, false, new PluginHook() {
            @Override public void before(PluginCall call) {
                if (!enabledSupplier.getAsBoolean()) {
                    return;
                }
                Object adapter = call.getReceiver();
                Object[] args = call.getArguments();
                if (adapter == null || args == null || args.length == 0 || !(args[0] instanceof Integer)) {
                    return;
                }
                int index = (Integer) args[0];
                try {
                    Object target = host.actionSheetTargetItemField.get(adapter);
                    if (!isExportablePlaylistTarget(target)) {
                        return;
                    }
                    int nativeCount = readNativeItemCount(adapter);
                    if (index == nativeCount) {
                        Context ctx = (Context) host.actionSheetTargetContextField.get(adapter);
                        if (ctx == null) {
                            ctx = runtime.getApplication();
                        }
                        call.returnResult(createExportMenuItem(ctx));
                    }
                } catch (Exception error) {
                    runtime.log("ActionSheet getItemAtIndex hook failed", error);
                }
            }
        }));

        registrations.add(hooks.hook(host.actionSheetItemClickMethod, false, new PluginHook() {
            @Override public void before(PluginCall call) {
                Object handler = call.getReceiver();
                Object[] args = call.getArguments();
                if (handler == null || args == null || args.length < 3) {
                    return;
                }
                Object clickedItem = args[2];
                if (clickedItem == null || !host.baseContentItemClass.isInstance(clickedItem)) {
                    return;
                }
                try {
                    Object id = host.baseContentGetIdMethod.invoke(clickedItem);
                    if (!EXPORT_ROW_ID.equals(id)) {
                        return;
                    }
                } catch (Exception error) {
                    runtime.log("ActionSheet click item id check failed", error);
                    return;
                }

                // Always consume our injected item click before native dispatch.
                call.returnResult(null);

                if (!enabledSupplier.getAsBoolean()) {
                    try {
                        Object fragment = host.actionSheetFragmentField.get(handler);
                        if (fragment != null) {
                            host.actionSheetDismissMethod.invoke(fragment);
                        }
                    } catch (Exception ignored) {}
                    return;
                }

                PlaylistTargetSpec targetSpec = null;
                try {
                    Object rawTarget = host.actionSheetClickTargetItemField.get(handler);
                    targetSpec = extractTargetSpec(rawTarget);
                    Object fragment = host.actionSheetFragmentField.get(handler);
                    if (fragment != null) {
                        host.actionSheetDismissMethod.invoke(fragment);
                    }
                } catch (Exception error) {
                    runtime.log("ActionSheet dismiss or target extraction failed", error);
                }

                if (targetSpec == null) {
                    showToast("无法识别当前歌单信息", Toast.LENGTH_SHORT);
                    return;
                }
                exportPlaylistAsync(targetSpec);
            }
        }));
    }

    public void stop() {
        for (PluginRegistration reg : registrations) {
            try {
                reg.close();
            } catch (Exception ignored) {}
        }
        registrations.clear();
    }

    private int readNativeItemCount(Object adapter) throws Exception {
        readingNativeCount.set(Boolean.TRUE);
        try {
            Object count = host.actionSheetGetItemCountMethod.invoke(adapter);
            return count instanceof Integer ? (Integer) count : -1;
        } finally {
            readingNativeCount.remove();
        }
    }

    private Object createExportMenuItem(Context ctx) throws Exception {
        Drawable icon = null;
        if (ctx != null && host.actionSheetExportIconResId != 0) {
            try {
                icon = ctx.getDrawable(host.actionSheetExportIconResId);
            } catch (Exception ignored) {}
        }
        Object item = host.commonHeaderItemCtor.newInstance(EXPORT_ROW_LABEL, icon);
        host.commonHeaderSetEnabledMethod.invoke(item, true);
        host.baseContentSetIdMethod.invoke(item, EXPORT_ROW_ID);
        return item;
    }

    private boolean isExportablePlaylistTarget(Object rawTarget) {
        Object unwrapped = unwrapTarget(rawTarget);
        if (unwrapped == null || !host.playlistCollectionItemClass.isInstance(unwrapped)) {
            return false;
        }
        return !callBool(unwrapped, "isFolder", false);
    }

    private Object unwrapTarget(Object rawTarget) {
        Object current = rawTarget;
        for (int depth = 0; depth < 4 && current != null; depth++) {
            if (!host.delegatingCollectionItemClass.isInstance(current)) {
                break;
            }
            try {
                Object inner = host.delegatingItemField.get(current);
                if (inner == null || inner == current) {
                    break;
                }
                current = inner;
            } catch (Exception ignored) {
                break;
            }
        }
        return current;
    }

    private PlaylistTargetSpec extractTargetSpec(Object rawTarget) {
        Object target = unwrapTarget(rawTarget);
        if (target == null) {
            return null;
        }
        long persistentId = 0L;
        if (host.baseContentItemClass.isInstance(target)) {
            try {
                Object value = host.baseContentGetPidMethod.invoke(target);
                if (value instanceof Number) {
                    persistentId = ((Number) value).longValue();
                }
            } catch (Exception ignored) {}
        }
        if (persistentId <= 0L) {
            persistentId = callLong(target, "getPersistentId", 0L);
        }
        String cloudId = firstNonEmpty(
                callString(target, "getCloudId"),
                callString(target, "getLibraryPlaylistId"),
                callString(target, "getId"));
        String globalId = callString(target, "getGlobalId");
        String title = callString(target, "getTitle");
        if (persistentId <= 0L && isEmpty(cloudId) && isEmpty(globalId) && isEmpty(title)) {
            return null;
        }
        return new PlaylistTargetSpec(persistentId, cloudId, globalId, title);
    }

    private void exportPlaylistAsync(PlaylistTargetSpec spec) {
        PlaylistModels.ExportFormat selected = formatSupplier.get();
        PlaylistModels.ExportFormat format = selected != null ? selected : PlaylistModels.ExportFormat.CSV;
        String formatLabel = format == PlaylistModels.ExportFormat.CSV ? "CSV" : "TXT";
        showToast("正在导出歌单 (" + formatLabel + ")...", Toast.LENGTH_SHORT);
        executor.execute(() -> {
            try {
                PlaylistModels.PlaylistTracksBundle bundle = reader.readPlaylistForExport(
                        spec.persistentId, spec.cloudId, spec.globalId, spec.title);
                if (bundle == null) {
                    showToast("未能在本地资料库找到该歌单（仅支持已加入资料库的歌单）", Toast.LENGTH_LONG);
                    return;
                }
                String key = playlistExportKey(bundle.summary);
                PlaylistExporter.ExportOptions options = optionsProvider != null
                        ? optionsProvider.apply(key)
                        : PlaylistExporter.ExportOptions.DEFAULT;
                PlaylistModels.ExportFileResult result = PlaylistExporter.writeToDownloads(
                        runtime.getApplication(),
                        bundle.summary,
                        format,
                        bundle.tracks,
                        options);
                if (uriRecorder != null && result.fileUri != null) {
                    uriRecorder.accept(key, result.fileUri);
                }
                String suffix = result.overwritten ? "（已覆盖旧文件）" : "";
                showToast(
                        "已导出《" + result.playlistTitle + "》（" + result.trackCount + " 首）" + suffix + "\n"
                                + result.displayPath,
                        Toast.LENGTH_LONG);
            } catch (Exception error) {
                runtime.log("ActionSheet export failed", error);
                String msg = error.getMessage();
                showToast("导出失败：" + (msg != null && !msg.isEmpty() ? msg : error.getClass().getSimpleName()), Toast.LENGTH_LONG);
            }
        });
    }

    private void showToast(String message, int duration) {
        Context app = runtime.getApplication();
        if (app == null) {
            return;
        }
        mainHandler.post(() -> Toast.makeText(app, message, duration).show());
    }

    private static String playlistExportKey(PlaylistModels.PlaylistSummary summary) {
        if (summary != null && summary.persistentId != 0L) {
            return "pid_" + summary.persistentId;
        }
        String title = summary != null && summary.title != null ? summary.title : "";
        return "title_" + title;
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) {
            return "";
        }
        for (String v : values) {
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        return "";
    }

    private static boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }

    private static String callString(Object target, String methodName) {
        Object res = callNoArg(target, methodName);
        return res instanceof String ? (String) res : "";
    }

    private static long callLong(Object target, String methodName, long fallback) {
        Object res = callNoArg(target, methodName);
        return res instanceof Number ? ((Number) res).longValue() : fallback;
    }

    private static boolean callBool(Object target, String methodName, boolean fallback) {
        Object res = callNoArg(target, methodName);
        return res instanceof Boolean ? (Boolean) res : fallback;
    }

    private static Object callNoArg(Object target, String methodName) {
        if (target == null) {
            return null;
        }
        try {
            Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static final class PlaylistTargetSpec {
        final long persistentId;
        final String cloudId;
        final String globalId;
        final String title;

        PlaylistTargetSpec(long persistentId, String cloudId, String globalId, String title) {
            this.persistentId = persistentId;
            this.cloudId = cloudId;
            this.globalId = globalId;
            this.title = title;
        }
    }
}
