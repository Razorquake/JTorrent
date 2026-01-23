package com.example.jtorrent.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Model Unit Tests")
class ModelTest {

    @Test
    @DisplayName("Torrent onCreate should set defaults when null")
    void testTorrentOnCreateDefaults() {
        Torrent torrent = new Torrent();

        invokeLifecycle(torrent, "onCreate");

        assertThat(torrent.getAddedDate()).isNotNull();
        assertThat(torrent.getStatus()).isEqualTo(TorrentStatus.PENDING);
        assertThat(torrent.getProgress()).isEqualTo(0.0);
        assertThat(torrent.getDownloadedSize()).isEqualTo(0L);
        assertThat(torrent.getUploadedSize()).isEqualTo(0L);
    }

    @Test
    @DisplayName("Torrent onCreate should not override existing values")
    void testTorrentOnCreatePreservesValues() {
        Torrent torrent = new Torrent();
        LocalDateTime added = LocalDateTime.of(2025, 1, 1, 0, 0);
        torrent.setAddedDate(added);
        torrent.setStatus(TorrentStatus.DOWNLOADING);
        torrent.setProgress(12.5);
        torrent.setDownloadedSize(10L);
        torrent.setUploadedSize(20L);

        invokeLifecycle(torrent, "onCreate");

        assertThat(torrent.getAddedDate()).isEqualTo(added);
        assertThat(torrent.getStatus()).isEqualTo(TorrentStatus.DOWNLOADING);
        assertThat(torrent.getProgress()).isEqualTo(12.5);
        assertThat(torrent.getDownloadedSize()).isEqualTo(10L);
        assertThat(torrent.getUploadedSize()).isEqualTo(20L);
    }

    @Test
    @DisplayName("Torrent addFile/removeFile should manage relationship")
    void testTorrentAddRemoveFile() {
        Torrent torrent = new Torrent();
        TorrentFile file = new TorrentFile();

        torrent.addFile(file);

        assertThat(torrent.getFiles()).containsExactly(file);
        assertThat(file.getTorrent()).isSameAs(torrent);

        torrent.removeFile(file);

        assertThat(torrent.getFiles()).isEmpty();
        assertThat(file.getTorrent()).isNull();
    }

    @Test
    @DisplayName("TorrentFile onCreate should set defaults when null")
    void testTorrentFileOnCreateDefaults() {
        TorrentFile file = new TorrentFile();

        invokeLifecycle(file, "onCreate");

        assertThat(file.getDownloadedSize()).isEqualTo(0L);
        assertThat(file.getProgress()).isEqualTo(0.0);
        assertThat(file.getPriority()).isEqualTo(4);
    }

    @Test
    @DisplayName("TorrentFile onCreate should not override existing values")
    void testTorrentFileOnCreatePreservesValues() {
        TorrentFile file = new TorrentFile();
        file.setDownloadedSize(5L);
        file.setProgress(12.5);
        file.setPriority(7);

        invokeLifecycle(file, "onCreate");

        assertThat(file.getDownloadedSize()).isEqualTo(5L);
        assertThat(file.getProgress()).isEqualTo(12.5);
        assertThat(file.getPriority()).isEqualTo(7);
    }

    @Test
    @DisplayName("DownloadStatistics updateRatio should compute ratio")
    void testDownloadStatisticsUpdateRatio() {
        DownloadStatistics stats = new DownloadStatistics();
        stats.setTotalDownloaded(1000L);
        stats.setTotalUploaded(500L);

        invokeLifecycle(stats, "updateRatio");

        assertThat(stats.getRatio()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("DownloadStatistics updateRatio should handle no downloads")
    void testDownloadStatisticsUpdateRatio_NoDownloads() {
        DownloadStatistics stats = new DownloadStatistics();
        stats.setTotalDownloaded(0L);
        stats.setTotalUploaded(500L);

        invokeLifecycle(stats, "updateRatio");

        assertThat(stats.getRatio()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("AppRole values should be accessible")
    void testAppRoleValues() {
        assertThat(AppRole.valueOf("ROLE_USER")).isEqualTo(AppRole.ROLE_USER);
        assertThat(AppRole.values()).containsExactly(AppRole.ROLE_USER, AppRole.ROLE_ADMIN);
    }

    private void invokeLifecycle(Object target, String methodName) {
        try {
            Method method = target.getClass().getDeclaredMethod(methodName);
            method.setAccessible(true);
            method.invoke(target);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
