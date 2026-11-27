package com.tcrrry.playlisttools;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CoverStore {
    private static final String KEY_COVER_FIX = "cover_fix_master_enabled";
    private static final String KEY_NATIVE_FIX = "cover_native_fix_enabled";
    private static final String KEY_AUTO_LOCK = "cover_auto_lock_enabled";
    private static final String KEY_LOCKED_PIDS = "cover_locked_pids";
    private static final String KEY_CAPTURED_PIDS = "cover_captured_pids";
    private static final String KEY_DIAGNOSTICS = "cover_diagnostics";
    private static final int MAX_DIAGNOSTICS = 15;
    private static final Pattern RECIPE_VERSION_01 = Pattern.compile("\"version\"\\s*:\\s*\"0\\.1\"");
    private static final Pattern RECIPE_EXPRESSION_ID = Pattern.compile("\"expressionId\"\\s*:\\s*\\d+");

    private final SharedPreferences prefs;
    private final File coverDir;
    private static volatile File overrideCacheDir;

    public CoverStore(SharedPreferences prefs, File storageRoot, File cacheRoot) {
        this.prefs = prefs;
        this.coverDir = new File(storageRoot, "tcrrry_playlist_covers");
        if (!this.coverDir.exists()) {
            this.coverDir.mkdirs();
        }
        if (cacheRoot != null) {
            File c = new File(cacheRoot, "tcrrry_playlist_covers_tmp");
            if (!c.exists()) {
                c.mkdirs();
            }
            overrideCacheDir = c;
        }
    }

    public boolean isCoverFixEnabled() {
        return prefs.getBoolean(KEY_COVER_FIX, prefs.getBoolean(KEY_NATIVE_FIX, true));
    }

    public void setCoverFixEnabled(boolean enabled) {
        prefs.edit()
                .putBoolean(KEY_COVER_FIX, enabled)
                .putBoolean(KEY_NATIVE_FIX, enabled)
                .putBoolean(KEY_AUTO_LOCK, enabled)
                .apply();
    }

    boolean isNativeFixEnabled() {
        return isCoverFixEnabled();
    }

    void setNativeFixEnabled(boolean enabled) {
        setCoverFixEnabled(enabled);
    }

    boolean isAutoLockEnabled() {
        return isCoverFixEnabled();
    }

    void setAutoLockEnabled(boolean enabled) {
        setCoverFixEnabled(enabled);
    }

    synchronized boolean isPlaylistLocked(long pid) {
        if (pid == 0L) {
            return false;
        }
        Set<String> set = prefs.getStringSet(KEY_LOCKED_PIDS, null);
        return set != null && set.contains(Long.toString(pid));
    }

    synchronized void setPlaylistLocked(long pid, boolean locked) {
        if (pid == 0L) {
            return;
        }
        Set<String> next = new LinkedHashSet<>();
        Set<String> current = prefs.getStringSet(KEY_LOCKED_PIDS, null);
        if (current != null) {
            next.addAll(current);
        }
        String key = Long.toString(pid);
        if (locked) {
            next.add(key);
        } else {
            next.remove(key);
        }
        prefs.edit().putStringSet(KEY_LOCKED_PIDS, next).apply();
    }

    synchronized String getLockedCoverFileUri(long pid) {
        if (pid == 0L || !isPlaylistLocked(pid)) {
            return null;
        }
        File file = coverFile(pid);
        if (!file.isFile() || file.length() <= 0L) {
            return null;
        }
        return Uri.fromFile(file).toString();
    }

    synchronized List<Long> listCapturedCoverPids() {
        Set<String> recorded = prefs.getStringSet(KEY_CAPTURED_PIDS, null);
        Set<Long> pids = new LinkedHashSet<>();
        if (recorded != null) {
            for (String s : recorded) {
                try {
                    long pid = Long.parseLong(s);
                    if (coverFile(pid).isFile() && coverFile(pid).length() > 0L) {
                        pids.add(pid);
                    }
                } catch (NumberFormatException ignored) {}
            }
        }
        File[] files = coverDir.listFiles();
        if (files != null) {
            for (File f : files) {
                String name = f.getName();
                if (name.startsWith("pid_") && name.endsWith(".jpg") && f.length() > 0L) {
                    try {
                        long pid = Long.parseLong(name.substring(4, name.length() - 4));
                        pids.add(pid);
                    } catch (NumberFormatException ignored) {}
                }
            }
        }
        return new ArrayList<>(pids);
    }

    synchronized void saveCapturedCover(long pid, Uri sourceUri, ContentResolver resolver) throws IOException {
        if (pid == 0L || sourceUri == null) {
            return;
        }
        if (!coverDir.exists()) {
            coverDir.mkdirs();
        }
        File target = coverFile(pid);
        File temp = new File(coverDir, "tmp_" + pid + "_" + System.nanoTime() + ".jpg");
        copyUriToFile(sourceUri, resolver, temp);
        if (target.exists() && !target.delete()) {
            // Best-effort overwrite fallback if delete fails on FS
            copyFile(temp, target);
            temp.delete();
        } else if (!temp.renameTo(target)) {
            copyFile(temp, target);
            temp.delete();
        }
        Set<String> captured = new LinkedHashSet<>();
        Set<String> current = prefs.getStringSet(KEY_CAPTURED_PIDS, null);
        if (current != null) {
            captured.addAll(current);
        }
        captured.add(Long.toString(pid));
        prefs.edit().putStringSet(KEY_CAPTURED_PIDS, captured).apply();
        if (isAutoLockEnabled()) {
            setPlaylistLocked(pid, true);
        }
    }

    synchronized void deleteCover(long pid) {
        setPlaylistLocked(pid, false);
        File file = coverFile(pid);
        if (file.exists()) {
            file.delete();
        }
        Set<String> captured = new LinkedHashSet<>();
        Set<String> current = prefs.getStringSet(KEY_CAPTURED_PIDS, null);
        if (current != null) {
            captured.addAll(current);
            captured.remove(Long.toString(pid));
            prefs.edit().putStringSet(KEY_CAPTURED_PIDS, captured).apply();
        }
    }

    static Uri copyUriToCacheFile(Context context, Uri sourceUri, long pid) throws IOException {
        File cacheDir = overrideCacheDir != null ? overrideCacheDir : context.getCacheDir();
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
        }
        File outFile = new File(cacheDir, "tcrrry_crop_" + Math.abs(pid) + "_" + System.currentTimeMillis() + ".jpg");
        copyUriToFile(sourceUri, context.getContentResolver(), outFile);
        return Uri.fromFile(outFile);
    }

    synchronized void recordDiagnostic(String message) {
        if (message == null || message.trim().isEmpty()) {
            return;
        }
        String ts = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        String line = "[" + ts + "] " + message.trim();
        String existing = prefs.getString(KEY_DIAGNOSTICS, "");
        List<String> lines = new ArrayList<>();
        if (!existing.isEmpty()) {
            Collections.addAll(lines, existing.split("\n"));
        }
        lines.add(line);
        while (lines.size() > MAX_DIAGNOSTICS) {
            lines.remove(0);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(lines.get(i));
        }
        prefs.edit().putString(KEY_DIAGNOSTICS, sb.toString()).apply();
    }

    synchronized String getDiagnostics() {
        return prefs.getString(KEY_DIAGNOSTICS, "");
    }

    synchronized void clearDiagnostics() {
        prefs.edit().remove(KEY_DIAGNOSTICS).apply();
    }

    /**
     * Rewrites clearing recipe version "0.1" -> "0.0" only when a custom local file URI is set in
     * PLAYLIST_ARTWORK_URL and no generated expressionId is present.
     */
    static String rewriteRecipeIfCustomCoverPresent(String recipeJson, String artworkUrl) {
        if (recipeJson == null || recipeJson.isEmpty() || artworkUrl == null) {
            return recipeJson;
        }
        String url = artworkUrl.trim();
        if (!url.startsWith("file://") && !url.startsWith("/")) {
            return recipeJson;
        }
        if (RECIPE_EXPRESSION_ID.matcher(recipeJson).find()) {
            return recipeJson;
        }
        Matcher m = RECIPE_VERSION_01.matcher(recipeJson);
        if (!m.find()) {
            return recipeJson;
        }
        return m.replaceFirst("\"version\":\"0.0\"");
    }

    private File coverFile(long pid) {
        return new File(coverDir, "pid_" + pid + ".jpg");
    }

    private static void copyUriToFile(Uri sourceUri, ContentResolver resolver, File destFile) throws IOException {
        try (InputStream in = resolver.openInputStream(sourceUri)) {
            if (in == null) {
                throw new IOException("无法读取图片 URI: " + sourceUri);
            }
            try (OutputStream out = new FileOutputStream(destFile)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    if (n > 0) {
                        out.write(buf, 0, n);
                    }
                }
                out.flush();
            }
        }
    }

    private static void copyFile(File src, File dst) throws IOException {
        try (InputStream in = new java.io.FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (n > 0) {
                    out.write(buf, 0, n);
                }
            }
            out.flush();
        }
    }
}
