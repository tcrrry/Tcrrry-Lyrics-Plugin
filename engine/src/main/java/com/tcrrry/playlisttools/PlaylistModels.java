package com.tcrrry.playlisttools;

import java.util.List;

public final class PlaylistModels {
    private PlaylistModels() {}

    public static final class PlaylistSummary {
        public final long persistentId;
        public final String title;
        public final int trackCount;
        public final boolean folder;
        public final String cloudId;
        public final String globalId;
        public final String url;
        public final String curatorName;

        public PlaylistSummary(
                long persistentId,
                String title,
                int trackCount,
                boolean folder,
                String cloudId,
                String globalId,
                String url,
                String curatorName) {
            this.persistentId = persistentId;
            this.title = title;
            this.trackCount = trackCount;
            this.folder = folder;
            this.cloudId = cloudId;
            this.globalId = globalId;
            this.url = url;
            this.curatorName = curatorName;
        }
    }

    public static final class PlaylistTrack {
        public final int index;
        public final String title;
        public final String artist;
        public final String album;
        public final long durationMs;
        public final int trackNumber;
        public final int discNumber;
        public final String releaseYear;
        public final String appleMusicId;
        public final String url;

        public PlaylistTrack(
                int index,
                String title,
                String artist,
                String album,
                long durationMs,
                int trackNumber,
                int discNumber,
                String releaseYear,
                String appleMusicId,
                String url) {
            this.index = index;
            this.title = title;
            this.artist = artist;
            this.album = album;
            this.durationMs = durationMs;
            this.trackNumber = trackNumber;
            this.discNumber = discNumber;
            this.releaseYear = releaseYear;
            this.appleMusicId = appleMusicId;
            this.url = url;
        }
    }

    public enum ExportFormat {
        CSV("csv", "text/csv"),
        TXT_TITLE_ARTIST("txt", "text/plain"),
        TXT_ARTIST_TITLE("txt", "text/plain");

        public final String extension;
        public final String mimeType;

        ExportFormat(String extension, String mimeType) {
            this.extension = extension;
            this.mimeType = mimeType;
        }
    }

    public static final class ExportFileResult {
        public final String playlistTitle;
        public final String fileName;
        public final String displayPath;
        public final int trackCount;
        public final String fileUri;
        public final boolean overwritten;

        public ExportFileResult(String playlistTitle, String fileName, String displayPath, int trackCount) {
            this(playlistTitle, fileName, displayPath, trackCount, null, false);
        }

        public ExportFileResult(
                String playlistTitle,
                String fileName,
                String displayPath,
                int trackCount,
                String fileUri,
                boolean overwritten) {
            this.playlistTitle = playlistTitle;
            this.fileName = fileName;
            this.displayPath = displayPath;
            this.trackCount = trackCount;
            this.fileUri = fileUri;
            this.overwritten = overwritten;
        }
    }

    public static final class PlaylistTracksBundle {
        public final PlaylistSummary summary;
        public final List<PlaylistTrack> tracks;

        public PlaylistTracksBundle(PlaylistSummary summary, List<PlaylistTrack> tracks) {
            this.summary = summary;
            this.tracks = tracks;
        }
    }
}
