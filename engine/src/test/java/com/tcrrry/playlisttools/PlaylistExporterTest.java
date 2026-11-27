package com.tcrrry.playlisttools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class PlaylistExporterTest {

    @Test
    public void csvIncludesBomHeaderAndRfc4180Escaping() {
        List<PlaylistModels.PlaylistTrack> tracks = Arrays.asList(
                new PlaylistModels.PlaylistTrack(
                        1, "Hello, \"World\"", "Artist\nLine2", "Album 1", 215000L, 1, 1, "2024", "12345", "https://music.apple.com/song/12345"),
                new PlaylistModels.PlaylistTrack(
                        2, "夜に駆ける", "YOASOBI", "THE BOOK", 261200L, 2, 1, "2021", "98765", "")
        );
        String csv = PlaylistExporter.formatCsv(tracks);
        assertTrue(csv.startsWith("\uFEFF序号,歌名,歌手,专辑,时长,曲目号,碟号,年份,AppleMusicID,链接\r\n"));
        assertTrue(csv.contains("1,\"Hello, \"\"World\"\"\",\"Artist\nLine2\",Album 1,3:35,1,1,2024,12345,https://music.apple.com/song/12345\r\n"));
        assertTrue(csv.contains("2,夜に駆ける,YOASOBI,THE BOOK,4:21,2,1,2021,98765,\r\n"));
    }

    @Test
    public void txtFormatsTitleArtistAndArtistTitleAndHandlesMissingArtist() {
        List<PlaylistModels.PlaylistTrack> tracks = Arrays.asList(
                new PlaylistModels.PlaylistTrack(1, "Lemon", "米津玄師", "STRAY SHEEP", 255000L, 1, 1, "", "", ""),
                new PlaylistModels.PlaylistTrack(2, "Pure Instrumental", "", "", 0L, 0, 0, "", "", "")
        );
        assertEquals("Lemon - 米津玄師\nPure Instrumental\n", PlaylistExporter.formatTxt(tracks, false));
        assertEquals("米津玄師 - Lemon\nPure Instrumental\n", PlaylistExporter.formatTxt(tracks, true));
    }

    @Test
    public void multiClipboardIncludesPlaylistHeadersOnlyWhenMultiple() {
        PlaylistModels.PlaylistSummary s1 = new PlaylistModels.PlaylistSummary(1L, "JPOP", 1, false, "", "", "", "");
        PlaylistModels.PlaylistSummary s2 = new PlaylistModels.PlaylistSummary(2L, "ACG", 1, false, "", "", "", "");
        List<PlaylistModels.PlaylistTrack> t1 = Collections.singletonList(
                new PlaylistModels.PlaylistTrack(1, "Idol", "YOASOBI", "", 0L, 0, 0, "", "", ""));
        List<PlaylistModels.PlaylistTrack> t2 = Collections.singletonList(
                new PlaylistModels.PlaylistTrack(1, "unravel", "TK", "", 0L, 0, 0, "", "", ""));

        String single = PlaylistExporter.formatMultiTxtClipboard(
                Collections.singletonList(new PlaylistModels.PlaylistTracksBundle(s1, t1)), false);
        assertEquals("Idol - YOASOBI\n", single);

        String multi = PlaylistExporter.formatMultiTxtClipboard(Arrays.asList(
                new PlaylistModels.PlaylistTracksBundle(s1, t1),
                new PlaylistModels.PlaylistTracksBundle(s2, t2)), false);
        assertEquals("=== JPOP ===\nIdol - YOASOBI\n\n=== ACG ===\nunravel - TK\n", multi);
    }

    @Test
    public void durationFormatsMinutesAndHours() {
        assertEquals("", PlaylistExporter.formatDuration(0L));
        assertEquals("", PlaylistExporter.formatDuration(-500L));
        assertEquals("0:09", PlaylistExporter.formatDuration(9100L));
        assertEquals("3:05", PlaylistExporter.formatDuration(185000L));
        assertEquals("1:02:03", PlaylistExporter.formatDuration(3723000L));
    }

    @Test
    public void sanitizeFileNameStripsIllegalCharsAndFallsBack() {
        assertEquals("J_POP_2026_1", PlaylistExporter.sanitizeFileName("..J/POP:2026*?1..", 12L));
        assertEquals("playlist_42", PlaylistExporter.sanitizeFileName("   ///...  ", -42L));
    }

    @Test
    public void mergedCsvCombinesMultiplePlaylistsWithPlaylistColumnAndSequentialIndex() {
        PlaylistModels.PlaylistSummary s1 = new PlaylistModels.PlaylistSummary(1L, "JPOP", 1, false, "", "", "", "");
        PlaylistModels.PlaylistSummary s2 = new PlaylistModels.PlaylistSummary(2L, "Rock, \"Live\"", 1, false, "", "", "", "");
        List<PlaylistModels.PlaylistTrack> t1 = Collections.singletonList(
                new PlaylistModels.PlaylistTrack(1, "Idol", "YOASOBI", "THE BOOK", 210000L, 1, 1, "2023", "111", ""));
        List<PlaylistModels.PlaylistTrack> t2 = Collections.singletonList(
                new PlaylistModels.PlaylistTrack(1, "unravel", "TK", "Fantastic Magic", 240000L, 1, 1, "2014", "222", ""));

        String merged = PlaylistExporter.formatMergedCsv(Arrays.asList(
                new PlaylistModels.PlaylistTracksBundle(s1, t1),
                new PlaylistModels.PlaylistTracksBundle(s2, t2)));
        assertTrue(merged.startsWith("\uFEFF歌单,序号,歌名,歌手,专辑,时长,曲目号,碟号,年份,AppleMusicID,链接\r\n"));
        assertTrue(merged.contains("JPOP,1,Idol,YOASOBI,THE BOOK,3:30,1,1,2023,111,\r\n"));
        assertTrue(merged.contains("\"Rock, \"\"Live\"\"\",2,unravel,TK,Fantastic Magic,4:00,1,1,2014,222,\r\n"));
    }

    @Test
    public void exportLocationPathHelpersNormalizeMediaStoreAndFormatDocumentIds() {
        assertEquals("Download/MyLists", PlaylistExporter.normalizeMediaStoreRelativeDir("/storage/emulated/0/Download/MyLists/"));
        assertEquals("Documents/Music", PlaylistExporter.normalizeMediaStoreRelativeDir("/sdcard/Documents/Music"));
        assertEquals("Download", PlaylistExporter.normalizeMediaStoreRelativeDir("Download"));
        assertNull(PlaylistExporter.normalizeMediaStoreRelativeDir("/storage/emulated/0/Music/Exports"));
        assertNull(PlaylistExporter.normalizeMediaStoreRelativeDir(""));

        assertEquals("内部存储/Download/MyLists", PlaylistExporter.formatTreeDocumentId("primary:Download/MyLists/"));
        assertEquals("内部存储", PlaylistExporter.formatTreeDocumentId("primary:"));
        assertEquals("外部存储(1234-ABCD)/Exports", PlaylistExporter.formatTreeDocumentId("1234-ABCD:Exports"));

        assertEquals("primary:Music/Exports", PlaylistExporter.buildInitialDocumentIdFromPath("/storage/emulated/0/Music/Exports/"));
        assertEquals("primary:Download/TcrrryPlaylists", PlaylistExporter.buildInitialDocumentIdFromPath(null));
    }
}
