package com.tcrrry.playlisttools;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class PlaylistExporter {
    public static final String DOWNLOAD_SUBDIR = "TcrrryPlaylists";

    public static final class ExportOptions {
        public static final ExportOptions DEFAULT = new ExportOptions(null, null, null, false, null);

        public final Uri customTreeUri;
        public final String customRelativeDir;
        public final String customDisplayDir;
        public final boolean overwriteExisting;
        public final Uri previousExportUri;

        public ExportOptions(
                Uri customTreeUri,
                String customRelativeDir,
                String customDisplayDir,
                boolean overwriteExisting,
                Uri previousExportUri) {
            this.customTreeUri = customTreeUri;
            this.customRelativeDir = customRelativeDir;
            this.customDisplayDir = customDisplayDir;
            this.overwriteExisting = overwriteExisting;
            this.previousExportUri = previousExportUri;
        }
    }

    private PlaylistExporter() {}

    public static String format(PlaylistModels.ExportFormat format, List<PlaylistModels.PlaylistTrack> tracks) {
        switch (format) {
            case CSV:
                return formatCsv(tracks);
            case TXT_ARTIST_TITLE:
                return formatTxt(tracks, true);
            case TXT_TITLE_ARTIST:
            default:
                return formatTxt(tracks, false);
        }
    }

    static String formatCsv(List<PlaylistModels.PlaylistTrack> tracks) {
        StringBuilder sb = new StringBuilder();
        sb.append('\uFEFF');
        sb.append("序号,歌名,歌手,专辑,时长,曲目号,碟号,年份,AppleMusicID,链接\r\n");
        if (tracks == null) {
            return sb.toString();
        }
        for (int i = 0; i < tracks.size(); i++) {
            PlaylistModels.PlaylistTrack t = tracks.get(i);
            sb.append(t.index > 0 ? t.index : (i + 1)).append(',');
            appendCsvCell(sb, t.title).append(',');
            appendCsvCell(sb, t.artist).append(',');
            appendCsvCell(sb, t.album).append(',');
            appendCsvCell(sb, formatDuration(t.durationMs)).append(',');
            sb.append(t.trackNumber > 0 ? Integer.toString(t.trackNumber) : "").append(',');
            sb.append(t.discNumber > 0 ? Integer.toString(t.discNumber) : "").append(',');
            appendCsvCell(sb, t.releaseYear).append(',');
            appendCsvCell(sb, t.appleMusicId).append(',');
            appendCsvCell(sb, t.url).append("\r\n");
        }
        return sb.toString();
    }

    static String formatTxt(List<PlaylistModels.PlaylistTrack> tracks, boolean artistFirst) {
        StringBuilder sb = new StringBuilder();
        if (tracks == null) {
            return "";
        }
        for (PlaylistModels.PlaylistTrack t : tracks) {
            String title = safe(t.title);
            String artist = safe(t.artist);
            if (title.isEmpty() && artist.isEmpty()) {
                continue;
            }
            if (artist.isEmpty()) {
                sb.append(title);
            } else if (title.isEmpty()) {
                sb.append(artist);
            } else if (artistFirst) {
                sb.append(artist).append(" - ").append(title);
            } else {
                sb.append(title).append(" - ").append(artist);
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    public static String formatMultiTxtClipboard(List<PlaylistModels.PlaylistTracksBundle> bundles, boolean artistFirst) {
        StringBuilder sb = new StringBuilder();
        if (bundles == null) {
            return "";
        }
        boolean includeHeaders = bundles.size() > 1;
        for (int i = 0; i < bundles.size(); i++) {
            PlaylistModels.PlaylistTracksBundle bundle = bundles.get(i);
            if (i > 0) {
                sb.append('\n');
            }
            if (includeHeaders) {
                String name = safe(bundle.summary != null ? bundle.summary.title : null);
                if (name.isEmpty()) {
                    name = "未命名歌单";
                }
                sb.append("=== ").append(name).append(" ===\n");
            }
            sb.append(formatTxt(bundle.tracks, artistFirst));
        }
        return sb.toString();
    }

    static String formatDuration(long durationMs) {
        if (durationMs <= 0L) {
            return "";
        }
        long totalSeconds = durationMs / 1000L;
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0L) {
            return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.US, "%d:%02d", minutes, seconds);
    }

    static String sanitizeFileName(String title, long fallbackPid) {
        String cleaned = title == null ? "" : title.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]+", "_").trim();
        while (cleaned.startsWith(".")) {
            cleaned = cleaned.substring(1).trim();
        }
        while (cleaned.endsWith(".")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1).trim();
        }
        if (cleaned.length() > 80) {
            cleaned = cleaned.substring(0, 80).trim();
        }
        if (cleaned.isEmpty() || cleaned.replace("_", "").replace(".", "").isEmpty()) {
            return "playlist_" + Math.abs(fallbackPid);
        }
        return cleaned;
    }

    public static String formatMergedCsv(List<PlaylistModels.PlaylistTracksBundle> bundles) {
        StringBuilder sb = new StringBuilder();
        sb.append('\uFEFF');
        sb.append("歌单,序号,歌名,歌手,专辑,时长,曲目号,碟号,年份,AppleMusicID,链接\r\n");
        if (bundles == null) {
            return sb.toString();
        }
        int seq = 0;
        for (PlaylistModels.PlaylistTracksBundle bundle : bundles) {
            if (bundle == null || bundle.tracks == null) {
                continue;
            }
            String playlistTitle = safe(bundle.summary != null ? bundle.summary.title : null);
            if (playlistTitle.isEmpty()) {
                playlistTitle = "未命名歌单";
            }
            for (PlaylistModels.PlaylistTrack t : bundle.tracks) {
                seq++;
                appendCsvCell(sb, playlistTitle).append(',');
                sb.append(seq).append(',');
                appendCsvCell(sb, t.title).append(',');
                appendCsvCell(sb, t.artist).append(',');
                appendCsvCell(sb, t.album).append(',');
                appendCsvCell(sb, formatDuration(t.durationMs)).append(',');
                sb.append(t.trackNumber > 0 ? Integer.toString(t.trackNumber) : "").append(',');
                sb.append(t.discNumber > 0 ? Integer.toString(t.discNumber) : "").append(',');
                appendCsvCell(sb, t.releaseYear).append(',');
                appendCsvCell(sb, t.appleMusicId).append(',');
                appendCsvCell(sb, t.url).append("\r\n");
            }
        }
        return sb.toString();
    }

    public static String formatMergedTxt(List<PlaylistModels.PlaylistTracksBundle> bundles, boolean artistFirst) {
        return formatMultiTxtClipboard(bundles, artistFirst);
    }

    public static PlaylistModels.ExportFileResult writeMergedToDownloads(
            Context context,
            List<PlaylistModels.PlaylistTracksBundle> bundles,
            PlaylistModels.ExportFormat format) throws IOException {
        return writeMergedToDownloads(context, bundles, format, ExportOptions.DEFAULT);
    }

    public static PlaylistModels.ExportFileResult writeMergedToDownloads(
            Context context,
            List<PlaylistModels.PlaylistTracksBundle> bundles,
            PlaylistModels.ExportFormat format,
            ExportOptions options) throws IOException {
        if (bundles == null || bundles.isEmpty()) {
            throw new IllegalArgumentException("没有可导出的歌单");
        }
        if (bundles.size() == 1) {
            PlaylistModels.PlaylistTracksBundle single = bundles.get(0);
            return writeToDownloads(context, single.summary, format, single.tracks, options);
        }
        PlaylistModels.PlaylistSummary first = bundles.get(0).summary;
        String baseName = sanitizeFileName(first.title, first.persistentId) + "_等" + bundles.size() + "个歌单";
        String fileName = baseName + "." + format.extension;
        String content = format == PlaylistModels.ExportFormat.CSV
                ? formatMergedCsv(bundles)
                : formatMergedTxt(bundles, format == PlaylistModels.ExportFormat.TXT_ARTIST_TITLE);
        int totalTracks = 0;
        for (PlaylistModels.PlaylistTracksBundle b : bundles) {
            if (b != null && b.tracks != null) {
                totalTracks += b.tracks.size();
            }
        }
        return writeContent(context, bundles.size() + " 个歌单合并", fileName, format.mimeType, content, totalTracks, options);
    }

    public static PlaylistModels.ExportFileResult writeToDownloads(
            Context context,
            PlaylistModels.PlaylistSummary summary,
            PlaylistModels.ExportFormat format,
            List<PlaylistModels.PlaylistTrack> tracks) throws IOException {
        return writeToDownloads(context, summary, format, tracks, ExportOptions.DEFAULT);
    }

    public static PlaylistModels.ExportFileResult writeToDownloads(
            Context context,
            PlaylistModels.PlaylistSummary summary,
            PlaylistModels.ExportFormat format,
            List<PlaylistModels.PlaylistTrack> tracks,
            ExportOptions options) throws IOException {
        String baseName = sanitizeFileName(summary.title, summary.persistentId);
        String fileName = baseName + "." + format.extension;
        String content = format(format, tracks);
        return writeContent(
                context,
                summary.title,
                fileName,
                format.mimeType,
                content,
                tracks != null ? tracks.size() : 0,
                options);
    }

    public static String normalizeMediaStoreRelativeDir(String rawPath) {
        if (rawPath == null) {
            return null;
        }
        String trimmed = rawPath.trim().replace('\\', '/');
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.startsWith("/storage/emulated/0/")) {
            trimmed = trimmed.substring("/storage/emulated/0/".length());
        } else if (trimmed.equals("/storage/emulated/0") || trimmed.equals("/sdcard")) {
            return null;
        } else if (trimmed.startsWith("/sdcard/")) {
            trimmed = trimmed.substring("/sdcard/".length());
        }
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.equals("Download") || trimmed.startsWith("Download/")
                || trimmed.equals("Documents") || trimmed.startsWith("Documents/")) {
            return trimmed;
        }
        return null;
    }

    public static String formatTreeDocumentId(String docId) {
        if (docId == null || docId.trim().isEmpty()) {
            return "已选文件夹";
        }
        int colon = docId.indexOf(':');
        if (colon < 0) {
            return docId;
        }
        String vol = docId.substring(0, colon);
        String sub = docId.substring(colon + 1);
        while (sub.startsWith("/")) {
            sub = sub.substring(1);
        }
        while (sub.endsWith("/")) {
            sub = sub.substring(0, sub.length() - 1);
        }
        String root = "primary".equalsIgnoreCase(vol) ? "内部存储" : ("外部存储(" + vol + ")");
        return sub.isEmpty() ? root : (root + "/" + sub);
    }

    public static String buildInitialDocumentIdFromPath(String rawPath) {
        if (rawPath == null) {
            String downloadsDir = Environment.DIRECTORY_DOWNLOADS != null
                    ? Environment.DIRECTORY_DOWNLOADS
                    : "Download";
            return "primary:" + downloadsDir + "/" + DOWNLOAD_SUBDIR;
        }
        String cleaned = rawPath.trim().replace('\\', '/');
        if (cleaned.startsWith("/storage/emulated/0/")) {
            cleaned = cleaned.substring("/storage/emulated/0/".length());
        } else if (cleaned.startsWith("/sdcard/")) {
            cleaned = cleaned.substring("/sdcard/".length());
        }
        while (cleaned.startsWith("/")) {
            cleaned = cleaned.substring(1);
        }
        while (cleaned.endsWith("/")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        return "primary:" + cleaned;
    }

    private static PlaylistModels.ExportFileResult writeContent(
            Context context,
            String displayTitle,
            String fileName,
            String mimeType,
            String content,
            int trackCount,
            ExportOptions rawOptions) throws IOException {
        ExportOptions options = rawOptions != null ? rawOptions : ExportOptions.DEFAULT;
        if (options.customTreeUri != null) {
            return writeContentToSafTree(context, displayTitle, fileName, mimeType, content, trackCount, options);
        }
        return writeContentToMediaStore(context, displayTitle, fileName, mimeType, content, trackCount, options);
    }

    private static PlaylistModels.ExportFileResult writeContentToSafTree(
            Context context,
            String displayTitle,
            String fileName,
            String mimeType,
            String content,
            int trackCount,
            ExportOptions options) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        Uri treeUri = options.customTreeUri;
        String treeDocId;
        Uri parentDocUri;
        Uri childrenUri;
        try {
            treeDocId = DocumentsContract.getTreeDocumentId(treeUri);
            parentDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId);
            childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId);
        } catch (RuntimeException error) {
            throw new IOException("自定义导出目录授权已失效，请重新选择导出位置或恢复默认", error);
        }

        String displayPrefix = options.customDisplayDir != null && !options.customDisplayDir.isEmpty()
                ? options.customDisplayDir
                : formatTreeDocumentId(treeDocId);
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);

        if (options.overwriteExisting) {
            Uri existing = findChildDocumentByName(resolver, childrenUri, treeUri, fileName);
            if (existing != null) {
                try (OutputStream out = resolver.openOutputStream(existing, "wt")) {
                    if (out != null) {
                        out.write(bytes);
                        out.flush();
                        deleteDifferentPreviousUri(context, options.previousExportUri, existing);
                        return new PlaylistModels.ExportFileResult(
                                displayTitle,
                                fileName,
                                displayPrefix + "/" + fileName,
                                trackCount,
                                existing.toString(),
                                true);
                    }
                } catch (IOException | RuntimeException ignored) {
                    try {
                        DocumentsContract.deleteDocument(resolver, existing);
                    } catch (Exception ignoredDelete) {}
                }
            }
        }

        Uri created;
        try {
            created = DocumentsContract.createDocument(resolver, parentDocUri, mimeType, fileName);
        } catch (RuntimeException error) {
            throw new IOException("无法写入所选文件夹，请重新授权或恢复默认导出位置", error);
        }
        if (created == null) {
            throw new IOException("无法在所选文件夹创建导出文件: " + fileName);
        }

        boolean replacedPrevious = false;
        try {
            try (OutputStream out = resolver.openOutputStream(created, "w")) {
                if (out == null) {
                    throw new IOException("无法打开导出文件输出流: " + fileName);
                }
                out.write(bytes);
                out.flush();
            }
            if (options.overwriteExisting && options.previousExportUri != null
                    && !options.previousExportUri.equals(created)) {
                replacedPrevious = deleteUriQuietly(context, options.previousExportUri);
            }
        } catch (IOException | RuntimeException error) {
            try {
                DocumentsContract.deleteDocument(resolver, created);
            } catch (Exception ignored) {}
            throw error;
        }

        String actualName = querySafDisplayName(resolver, created, fileName);
        return new PlaylistModels.ExportFileResult(
                displayTitle,
                actualName,
                displayPrefix + "/" + actualName,
                trackCount,
                created.toString(),
                replacedPrevious);
    }

    private static PlaylistModels.ExportFileResult writeContentToMediaStore(
            Context context,
            String displayTitle,
            String fileName,
            String mimeType,
            String content,
            int trackCount,
            ExportOptions options) throws IOException {
        String normalizedCustom = normalizeMediaStoreRelativeDir(options.customRelativeDir);
        String relativePath = normalizedCustom != null
                ? normalizedCustom
                : (Environment.DIRECTORY_DOWNLOADS + "/" + DOWNLOAD_SUBDIR);
        boolean isDocuments = "Documents".equals(relativePath) || relativePath.startsWith("Documents/");
        Uri collectionUri = isDocuments
                ? MediaStore.Files.getContentUri("external")
                : MediaStore.Downloads.EXTERNAL_CONTENT_URI;
        ContentResolver resolver = context.getContentResolver();
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);

        if (options.overwriteExisting) {
            List<Uri> existingUris = queryMediaStoreMatches(resolver, collectionUri, relativePath, fileName);
            if (!existingUris.isEmpty()) {
                Uri primary = existingUris.get(0);
                try (OutputStream out = resolver.openOutputStream(primary, "wt")) {
                    if (out != null) {
                        out.write(bytes);
                        out.flush();
                        for (int i = 1; i < existingUris.size(); i++) {
                            deleteUriQuietly(context, existingUris.get(i));
                        }
                        deleteDifferentPreviousUri(context, options.previousExportUri, primary);
                        return new PlaylistModels.ExportFileResult(
                                displayTitle,
                                fileName,
                                relativePath + "/" + fileName,
                                trackCount,
                                primary.toString(),
                                true);
                    }
                } catch (IOException | RuntimeException ignored) {
                    for (Uri stale : existingUris) {
                        deleteUriQuietly(context, stale);
                    }
                }
            }
        }

        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);

        Uri uri = resolver.insert(collectionUri, values);
        if (uri == null) {
            throw new IOException("无法在 " + relativePath + " 创建导出文件");
        }
        boolean replacedPrevious = false;
        try {
            try (OutputStream out = resolver.openOutputStream(uri, "w")) {
                if (out == null) {
                    throw new IOException("无法打开导出文件输出流: " + fileName);
                }
                out.write(bytes);
                out.flush();
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(uri, done, null, null);
            if (options.overwriteExisting && options.previousExportUri != null
                    && !options.previousExportUri.equals(uri)) {
                replacedPrevious = deleteUriQuietly(context, options.previousExportUri);
            }
        } catch (IOException | RuntimeException error) {
            try {
                resolver.delete(uri, null, null);
            } catch (Exception ignored) {}
            throw error;
        }
        return new PlaylistModels.ExportFileResult(
                displayTitle,
                fileName,
                relativePath + "/" + fileName,
                trackCount,
                uri.toString(),
                replacedPrevious);
    }

    private static List<Uri> queryMediaStoreMatches(
            ContentResolver resolver, Uri collectionUri, String relativePath, String fileName) {
        List<Uri> matches = new ArrayList<>();
        String[] projection = new String[] { MediaStore.MediaColumns._ID };
        String selection = MediaStore.MediaColumns.DISPLAY_NAME + "=? AND ("
                + MediaStore.MediaColumns.RELATIVE_PATH + "=? OR "
                + MediaStore.MediaColumns.RELATIVE_PATH + "=?)";
        String[] args = new String[] { fileName, relativePath, relativePath + "/" };
        try (Cursor cursor = resolver.query(collectionUri, projection, selection, args, null)) {
            if (cursor != null) {
                int idCol = cursor.getColumnIndex(MediaStore.MediaColumns._ID);
                while (cursor.moveToNext() && idCol >= 0) {
                    long id = cursor.getLong(idCol);
                    matches.add(ContentUris.withAppendedId(collectionUri, id));
                }
            }
        } catch (Exception ignored) {}
        return matches;
    }

    private static Uri findChildDocumentByName(
            ContentResolver resolver, Uri childrenUri, Uri treeUri, String targetName) {
        String[] projection = new String[] {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
        };
        try (Cursor cursor = resolver.query(childrenUri, projection, null, null, null)) {
            if (cursor == null) {
                return null;
            }
            int idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
            while (cursor.moveToNext()) {
                String docId = idIdx >= 0 ? cursor.getString(idIdx) : null;
                String name = nameIdx >= 0 ? cursor.getString(nameIdx) : null;
                String mime = mimeIdx >= 0 ? cursor.getString(mimeIdx) : null;
                if (docId != null && name != null && name.equalsIgnoreCase(targetName)
                        && !DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    return DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static String querySafDisplayName(ContentResolver resolver, Uri docUri, String fallback) {
        try (Cursor cursor = resolver.query(
                docUri, new String[] { DocumentsContract.Document.COLUMN_DISPLAY_NAME }, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null && !name.trim().isEmpty()) {
                    return name.trim();
                }
            }
        } catch (Exception ignored) {}
        return fallback;
    }

    private static void deleteDifferentPreviousUri(Context context, Uri previous, Uri current) {
        if (previous != null && !previous.equals(current)) {
            deleteUriQuietly(context, previous);
        }
    }

    public static boolean deleteUriQuietly(Context context, Uri uri) {
        if (context == null || uri == null) {
            return false;
        }
        ContentResolver resolver = context.getContentResolver();
        try {
            if (DocumentsContract.isDocumentUri(context, uri)) {
                return DocumentsContract.deleteDocument(resolver, uri);
            }
        } catch (Exception ignored) {}
        try {
            return resolver.delete(uri, null, null) > 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static StringBuilder appendCsvCell(StringBuilder sb, String raw) {
        String value = safe(raw);
        boolean quote = value.indexOf(',') >= 0
                || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0;
        if (!quote) {
            sb.append(value);
            return sb;
        }
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '"') {
                sb.append("\"\"");
            } else {
                sb.append(ch);
            }
        }
        sb.append('"');
        return sb;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
