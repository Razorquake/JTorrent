package com.example.jtorrent.service;

import com.example.jtorrent.model.Torrent;
import com.example.jtorrent.model.TorrentFile;
import com.example.jtorrent.repository.TorrentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("FileManagementService Unit Tests")
class FileManagementServiceTest {

    @Mock private TorrentSessionManager sessionManager;
    @Mock
    private TorrentRepository torrentRepository;

    @InjectMocks
    private FileManagementService fileManagementService;

    @TempDir
    Path tempDir;

    // ── deleteTorrentFiles ────────────────────────────────────────────────────

    @Test
    @DisplayName("Should delete all registered files and return correct count")
    void testDeleteTorrentFiles_DeletesAllFiles() throws IOException {
        // Given — create real files on disk
        Path fileA = tempDir.resolve("movie.mkv");
        Path fileB = tempDir.resolve("subs/movie.srt");
        Files.createDirectories(fileB.getParent());
        Files.writeString(fileA, "data");
        Files.writeString(fileB, "sub data");

        Torrent torrent = buildTorrent(tempDir, "movie.mkv", "subs/movie.srt");

        // When
        int deleted = fileManagementService.deleteTorrentFiles(torrent);

        // Then
        assertThat(deleted).isEqualTo(2);
        assertThat(Files.exists(fileA)).isFalse();
        assertThat(Files.exists(fileB)).isFalse();
    }

    @Test
    @DisplayName("Should return 0 when no files exist on disk")
    void testDeleteTorrentFiles_FilesAlreadyGone() {
        // Given — files do NOT exist on disk
        Torrent torrent = buildTorrent(tempDir, "ghost.mkv");

        // When
        int deleted = fileManagementService.deleteTorrentFiles(torrent);

        // Then
        assertThat(deleted).isZero();
    }

    @Test
    @DisplayName("Should return 0 when savePath is null")
    void testDeleteTorrentFiles_NullSavePath() {
        Torrent torrent = new Torrent();
        torrent.setId(1L);
        torrent.setName("test");
        torrent.setSavePath(null);
        torrent.setFiles(new ArrayList<>());

        int deleted = fileManagementService.deleteTorrentFiles(torrent);

        assertThat(deleted).isZero();
    }

    @Test
    @DisplayName("Should return 0 when savePath does not exist on disk")
    void testDeleteTorrentFiles_SaveDirMissing() {
        Torrent torrent = new Torrent();
        torrent.setId(1L);
        torrent.setName("test");
        torrent.setSavePath("/nonexistent/path/that/does/not/exist");
        torrent.setFiles(new ArrayList<>());

        int deleted = fileManagementService.deleteTorrentFiles(torrent);

        assertThat(deleted).isZero();
    }

    @Test
    @DisplayName("Should not follow path traversal outside saveDir")
    void testDeleteTorrentFiles_PathTraversalBlocked() throws IOException {
        // Create a file outside tempDir
        Path outsideFile = tempDir.getParent().resolve("sensitive.txt");
        Files.writeString(outsideFile, "sensitive");

        // Build torrent with a path that tries to escape saveDir
        Torrent torrent = new Torrent();
        torrent.setId(1L);
        torrent.setName("evil");
        torrent.setSavePath(tempDir.toString());
        torrent.setFiles(new ArrayList<>());

        TorrentFile evilFile = new TorrentFile();
        evilFile.setPath("../sensitive.txt"); // path traversal attempt
        torrent.getFiles().add(evilFile);

        fileManagementService.deleteTorrentFiles(torrent);

        // The file outside saveDir must NOT have been deleted
        assertThat(Files.exists(outsideFile)).isTrue();

        // Cleanup
        Files.deleteIfExists(outsideFile);
    }

    @Test
    @DisplayName("Should remove empty directories after file deletion")
    void testDeleteTorrentFiles_RemovesEmptyDirs() throws IOException {
        Path subDir = tempDir.resolve("Season 1");
        Files.createDirectories(subDir);
        Path episode = subDir.resolve("ep1.mkv");
        Files.writeString(episode, "data");

        Torrent torrent = buildTorrent(tempDir, "Season 1/ep1.mkv");

        fileManagementService.deleteTorrentFiles(torrent);

        assertThat(Files.exists(episode)).isFalse();
        assertThat(Files.exists(subDir)).isFalse(); // empty dir removed
    }

    // ── getStorageInfo ────────────────────────────────────────────────────────

    @Test
    @DisplayName("Should return storage info map with expected keys")
    void testGetStorageInfo_ReturnsExpectedKeys() {
        when(sessionManager.getDownloadDirectory()).thenReturn(tempDir.toFile());
        when(torrentRepository.findAll()).thenReturn(List.of());

        Map<String, Object> info = fileManagementService.getStorageInfo();

        assertThat(info).containsKeys(
                "path", "totalBytes", "usedBytes", "freeBytes",
                "totalFormatted", "usedFormatted", "freeFormatted",
                "usedByTorrentsBytes", "usedByTorrentsFormatted");
        assertThat(info.get("path")).isEqualTo(tempDir.toFile().getAbsolutePath());
    }

    @Test
    @DisplayName("usedByTorrentsBytes should reflect actual file sizes on disk")
    void testGetStorageInfo_TrackedBytesReflectDisk() throws IOException {
        // Create a real tracked file
        Path trackedFile = tempDir.resolve("ubuntu.iso");
        byte[] content = new byte[1024];
        Files.write(trackedFile, content);

        Torrent torrent = buildTorrent(tempDir, "ubuntu.iso");
        when(sessionManager.getDownloadDirectory()).thenReturn(tempDir.toFile());
        when(torrentRepository.findAll()).thenReturn(List.of(torrent));

        Map<String, Object> info = fileManagementService.getStorageInfo();

        assertThat((Long) info.get("usedByTorrentsBytes")).isEqualTo(1024L);
    }

    // ── findOrphanedFiles ─────────────────────────────────────────────────────

    @Test
    @DisplayName("Should return empty list when all files are tracked")
    void testFindOrphanedFiles_NoneWhenAllTracked() throws IOException {
        Path tracked = tempDir.resolve("ubuntu.iso");
        Files.writeString(tracked, "data");

        Torrent torrent = buildTorrent(tempDir, "ubuntu.iso");
        when(sessionManager.getDownloadDirectory()).thenReturn(tempDir.toFile());
        when(torrentRepository.findAll()).thenReturn(List.of(torrent));

        List<String> orphans = fileManagementService.findOrphanedFiles();

        assertThat(orphans).isEmpty();
    }

    @Test
    @DisplayName("Should return untracked files as orphans")
    void testFindOrphanedFiles_DetectsOrphans() throws IOException {
        Path orphan = tempDir.resolve("leftover.dat");
        Files.writeString(orphan, "leftover");

        when(sessionManager.getDownloadDirectory()).thenReturn(tempDir.toFile());
        when(torrentRepository.findAll()).thenReturn(List.of()); // nothing tracked

        List<String> orphans = fileManagementService.findOrphanedFiles();

        assertThat(orphans).hasSize(1);
        assertThat(orphans.get(0)).contains("leftover.dat");
    }

    @Test
    @DisplayName("Should return empty list when downloads dir does not exist")
    void testFindOrphanedFiles_MissingDir() {
        File missing = new File("/nonexistent/downloads");
        when(sessionManager.getDownloadDirectory()).thenReturn(missing);

        List<String> orphans = fileManagementService.findOrphanedFiles();

        assertThat(orphans).isEmpty();
    }

    // ── cleanupOrphanedFiles ──────────────────────────────────────────────────

    @Test
    @DisplayName("Should delete orphaned files and return correct count")
    void testCleanupOrphanedFiles_DeletesOrphans() throws IOException {
        Path orphan = tempDir.resolve("stale.dat");
        Files.writeString(orphan, "stale");

        when(sessionManager.getDownloadDirectory()).thenReturn(tempDir.toFile());
        when(torrentRepository.findAll()).thenReturn(List.of());

        int deleted = fileManagementService.cleanupOrphanedFiles();

        assertThat(deleted).isEqualTo(1);
        assertThat(Files.exists(orphan)).isFalse();
    }

    @Test
    @DisplayName("Should return 0 when no orphans exist")
    void testCleanupOrphanedFiles_NoneToDelete() throws IOException {
        Path tracked = tempDir.resolve("tracked.iso");
        Files.writeString(tracked, "data");

        Torrent torrent = buildTorrent(tempDir, "tracked.iso");
        when(sessionManager.getDownloadDirectory()).thenReturn(tempDir.toFile());
        when(torrentRepository.findAll()).thenReturn(List.of(torrent));

        int deleted = fileManagementService.cleanupOrphanedFiles();

        assertThat(deleted).isZero();
        assertThat(Files.exists(tracked)).isTrue(); // tracked file untouched
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Torrent buildTorrent(Path saveDir, String... filePaths) {
        Torrent torrent = new Torrent();
        torrent.setId(1L);
        torrent.setName("Test Torrent");
        torrent.setSavePath(saveDir.toAbsolutePath().toString());
        torrent.setFiles(new ArrayList<>());

        for (String path : filePaths) {
            TorrentFile tf = new TorrentFile();
            tf.setPath(path);
            torrent.getFiles().add(tf);
        }
        return torrent;
    }
}
