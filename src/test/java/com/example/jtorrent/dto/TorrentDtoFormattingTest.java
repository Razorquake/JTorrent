package com.example.jtorrent.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Torrent DTO Formatting Tests")
class TorrentDtoFormattingTest {

    @Test
    @DisplayName("TorrentResponse should format sizes and speeds")
    void testTorrentResponseFormatting() {
        TorrentResponse response = new TorrentResponse();
        response.setTotalSize(1024L);
        response.setDownloadedSize(2048L);
        response.setDownloadSpeed(1024);
        response.setUploadSpeed(null);

        assertThat(response.getFormattedTotalSize()).isEqualTo("1.00 KB");
        assertThat(response.getFormattedDownloadedSize()).isEqualTo("2.00 KB");
        assertThat(response.getFormattedDownloadSpeed()).isEqualTo("1.00 KB/s");
        assertThat(response.getFormattedUploadSpeed()).isEqualTo("0 B/s");
    }

    @Test
    @DisplayName("TorrentFileResponse should format size")
    void testTorrentFileResponseFormatting() {
        TorrentFileResponse response = new TorrentFileResponse();
        response.setSize(0L);
        assertThat(response.getFormattedSize()).isEqualTo("0 B");

        response.setSize(1048576L);
        assertThat(response.getFormattedSize()).isEqualTo("1.00 MB");
    }

    @Test
    @DisplayName("TorrentStatsDTO should format totals and speeds")
    void testTorrentStatsFormatting() {
        TorrentStatsDTO stats = new TorrentStatsDTO();
        stats.setTotalDownloadedBytes(1024L);
        stats.setTotalUploadedBytes(2048L);
        stats.setCurrentDownloadSpeed(1024);
        stats.setCurrentUploadSpeed(2048);

        assertThat(stats.getFormattedTotalDownloaded()).isEqualTo("1.00 KB");
        assertThat(stats.getFormattedTotalUploaded()).isEqualTo("2.00 KB");
        assertThat(stats.getFormattedDownloadSpeed()).isEqualTo("1.00 KB/s");
        assertThat(stats.getFormattedUploadSpeed()).isEqualTo("2.00 KB/s");
    }

    @Test
    @DisplayName("AddTorrentFileResponse should format size")
    void testAddTorrentFileResponseFormatting() {
        AddTorrentFileResponse response = new AddTorrentFileResponse();
        response.setTotalSize(0L);
        assertThat(response.getFormattedSize()).isEqualTo("0 B");

        response.setTotalSize(1073741824L);
        assertThat(response.getFormattedSize()).isEqualTo("1.00 GB");
    }

    @Test
    @DisplayName("MessageResponse constructor should set timestamp")
    void testMessageResponseTimestamp() {
        MessageResponse response = new MessageResponse("ok");

        assertThat(response.getMessage()).isEqualTo("ok");
        assertThat(response.getTimestamp()).isNotNull();
    }
}
