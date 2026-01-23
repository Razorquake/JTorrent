package com.example.jtorrent.controller;

import com.example.jtorrent.dto.TorrentStatsDTO;
import com.example.jtorrent.model.DownloadStatistics;
import com.example.jtorrent.model.Torrent;
import com.example.jtorrent.security.SecurityConfig;
import com.example.jtorrent.service.TorrentStatisticsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(TorrentStatsController.class)
@Import(SecurityConfig.class)
@DisplayName("TorrentStatsController Integration Tests")
class TorrentStatsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TorrentStatisticsService statisticsService;

    @Test
    @DisplayName("GET /api/stats/overall - Should get overall statistics")
    void testGetOverallStatistics_Success() throws Exception {
        // Given
        TorrentStatsDTO stats = TorrentStatsDTO.builder()
                .totalTorrents(10L)
                .activeTorrents(5L)
                .downloadingTorrents(3L)
                .seedingTorrents(2L)
                .completedTorrents(5L)
                .pausedTorrents(0L)
                .errorTorrents(0L)
                .totalDownloadedBytes(1000000L)
                .totalUploadedBytes(500000L)
                .overallRatio(0.5)
                .currentDownloadSpeed(100000)
                .currentUploadSpeed(50000)
                .totalActivePeers(20)
                .totalActiveSeeds(10)
                .build();

        when(statisticsService.getOverallStatistics()).thenReturn(stats);

        // When/Then
        mockMvc.perform(get("/api/stats/overall"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalTorrents", is(10)))
                .andExpect(jsonPath("$.activeTorrents", is(5)))
                .andExpect(jsonPath("$.downloadingTorrents", is(3)))
                .andExpect(jsonPath("$.seedingTorrents", is(2)))
                .andExpect(jsonPath("$.completedTorrents", is(5)))
                .andExpect(jsonPath("$.overallRatio", is(0.5)))
                .andExpect(jsonPath("$.currentDownloadSpeed", is(100000)))
                .andExpect(jsonPath("$.totalActivePeers", is(20)));

        verify(statisticsService, times(1)).getOverallStatistics();
    }

    @Test
    @DisplayName("GET /api/stats/torrent/{id} - Should get torrent statistics")
    void testGetTorrentStatistics_Success() throws Exception {
        // Given
        Torrent torrent = new Torrent();
        torrent.setId(1L);

        DownloadStatistics stats = new DownloadStatistics();
        stats.setId(1L);
        stats.setTorrent(torrent);
        stats.setTotalDownloaded(1000000L);
        stats.setTotalUploaded(500000L);
        stats.setRatio(0.5);
        stats.setTimeActive(3600L);
        stats.setAverageDownloadSpeed(100000);

        when(statisticsService.getTorrentStatistics(1L)).thenReturn(stats);

        // When/Then
        mockMvc.perform(get("/api/stats/torrent/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalDownloaded", is(1000000)))
                .andExpect(jsonPath("$.totalUploaded", is(500000)))
                .andExpect(jsonPath("$.ratio", is(0.5)))
                .andExpect(jsonPath("$.timeActive", is(3600)))
                .andExpect(jsonPath("$.averageDownloadSpeed", is(100000)));

        verify(statisticsService, times(1)).getTorrentStatistics(1L);
    }

    @Test
    @DisplayName("GET /api/stats/torrent/{id} - Should return 404 when statistics not found")
    void testGetTorrentStatistics_NotFound() throws Exception {
        // Given
        when(statisticsService.getTorrentStatistics(999L)).thenReturn(null);

        // When/Then
        mockMvc.perform(get("/api/stats/torrent/999"))
                .andExpect(status().isNotFound());

        verify(statisticsService, times(1)).getTorrentStatistics(999L);
    }

    @Test
    @DisplayName("GET /api/stats/torrent/{id}/ratio - Should get share ratio")
    void testGetShareRatio_Success() throws Exception {
        // Given
        when(statisticsService.getShareRatio(1L)).thenReturn(1.5);

        // When/Then
        mockMvc.perform(get("/api/stats/torrent/1/ratio"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ratio", is(1.5)));

        verify(statisticsService, times(1)).getShareRatio(1L);
    }

    @Test
    @DisplayName("GET /api/stats/torrent/{id}/eta - Should get estimated time remaining")
    void testGetEstimatedTimeRemaining_Success() throws Exception {
        // Given
        when(statisticsService.getEstimatedTimeRemaining(1L)).thenReturn(300L); // 5 minutes

        // When/Then
        mockMvc.perform(get("/api/stats/torrent/1/eta"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.etaSeconds", is(300)))
                .andExpect(jsonPath("$.etaFormatted", notNullValue()));

        verify(statisticsService, times(1)).getEstimatedTimeRemaining(1L);
    }

    @Test
    @DisplayName("GET /api/stats/torrent/{id}/eta - Should handle null ETA")
    void testGetEstimatedTimeRemaining_Null() throws Exception {
        // Given
        when(statisticsService.getEstimatedTimeRemaining(1L)).thenReturn(null);

        // When/Then
        mockMvc.perform(get("/api/stats/torrent/1/eta"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.etaSeconds", nullValue()))
                .andExpect(jsonPath("$.etaFormatted").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/stats/export - Should export statistics summary")
    void testExportStatistics_Success() throws Exception {
        // Given
        String summary = "=== Torrent Statistics Summary ===\nTotal Torrents: 10\n";
        when(statisticsService.exportStatisticsSummary()).thenReturn(summary);

        // When/Then
        mockMvc.perform(get("/api/stats/export"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Torrent Statistics Summary")))
                .andExpect(content().string(containsString("Total Torrents: 10")));

        verify(statisticsService, times(1)).exportStatisticsSummary();
    }

    @Test
    @DisplayName("POST /api/stats/torrent/{id}/reset - Should reset torrent statistics")
    void testResetTorrentStatistics_Success() throws Exception {
        // When/Then
        mockMvc.perform(post("/api/stats/torrent/1/reset"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message", containsString("reset")));

        verify(statisticsService, times(1)).resetTorrentStatistics(1L);
    }

    @Test
    @DisplayName("GET /api/stats/speeds - Should get current speeds")
    void testGetCurrentSpeeds_Success() throws Exception {
        // Given
        TorrentStatsDTO stats = TorrentStatsDTO.builder()
                .currentDownloadSpeed(100000)
                .currentUploadSpeed(50000)
                .totalActivePeers(20)
                .totalActiveSeeds(10)
                .build();

        when(statisticsService.getOverallStatistics()).thenReturn(stats);

        // When/Then
        mockMvc.perform(get("/api/stats/speeds"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.downloadSpeed", is(100000)))
                .andExpect(jsonPath("$.uploadSpeed", is(50000)))
                .andExpect(jsonPath("$.activePeers", is(20)))
                .andExpect(jsonPath("$.activeSeeds", is(10)))
                .andExpect(jsonPath("$.downloadSpeedFormatted", notNullValue()))
                .andExpect(jsonPath("$.uploadSpeedFormatted", notNullValue()));
    }

    @Test
    @DisplayName("GET /api/stats/transfer - Should get data transfer totals")
    void testGetDataTransfer_Success() throws Exception {
        // Given
        TorrentStatsDTO stats = TorrentStatsDTO.builder()
                .totalDownloadedBytes(1073741824L) // 1 GB
                .totalUploadedBytes(536870912L) // 512 MB
                .overallRatio(0.5)
                .build();

        when(statisticsService.getOverallStatistics()).thenReturn(stats);

        // When/Then
        mockMvc.perform(get("/api/stats/transfer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalDownloaded", is(1073741824)))
                .andExpect(jsonPath("$.totalUploaded", is(536870912)))
                .andExpect(jsonPath("$.overallRatio", is(0.5)))
                .andExpect(jsonPath("$.totalDownloadedFormatted", notNullValue()))
                .andExpect(jsonPath("$.totalUploadedFormatted", notNullValue()));
    }

    @Test
    @DisplayName("GET /api/stats/counts - Should get torrent counts by status")
    void testGetStatusCounts_Success() throws Exception {
        // Given
        TorrentStatsDTO stats = TorrentStatsDTO.builder()
                .totalTorrents(10L)
                .activeTorrents(5L)
                .downloadingTorrents(3L)
                .seedingTorrents(2L)
                .completedTorrents(4L)
                .pausedTorrents(1L)
                .errorTorrents(0L)
                .build();

        when(statisticsService.getOverallStatistics()).thenReturn(stats);

        // When/Then
        mockMvc.perform(get("/api/stats/counts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total", is(10)))
                .andExpect(jsonPath("$.active", is(5)))
                .andExpect(jsonPath("$.downloading", is(3)))
                .andExpect(jsonPath("$.seeding", is(2)))
                .andExpect(jsonPath("$.completed", is(4)))
                .andExpect(jsonPath("$.paused", is(1)))
                .andExpect(jsonPath("$.error", is(0)));
    }

    @Test
    @DisplayName("Should format ETA correctly for different durations")
    void testETAFormatting() throws Exception {
        // Test various ETA values
        // 1 day, 2 hours, 30 minutes, 45 seconds = 95,445 seconds
        when(statisticsService.getEstimatedTimeRemaining(1L)).thenReturn(95445L);

        mockMvc.perform(get("/api/stats/torrent/1/eta"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.etaFormatted", containsString("d")))
                .andExpect(jsonPath("$.etaFormatted", containsString("h")))
                .andExpect(jsonPath("$.etaFormatted", containsString("m")))
                .andExpect(jsonPath("$.etaFormatted", containsString("s")));
    }

    @Test
    @DisplayName("Should handle zero statistics gracefully")
    void testZeroStatistics() throws Exception {
        // Given
        TorrentStatsDTO stats = TorrentStatsDTO.builder()
                .totalTorrents(0L)
                .activeTorrents(0L)
                .downloadingTorrents(0L)
                .seedingTorrents(0L)
                .completedTorrents(0L)
                .pausedTorrents(0L)
                .errorTorrents(0L)
                .totalDownloadedBytes(0L)
                .totalUploadedBytes(0L)
                .overallRatio(0.0)
                .currentDownloadSpeed(0)
                .currentUploadSpeed(0)
                .totalActivePeers(0)
                .totalActiveSeeds(0)
                .build();

        when(statisticsService.getOverallStatistics()).thenReturn(stats);

        // When/Then
        mockMvc.perform(get("/api/stats/overall"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalTorrents", is(0)))
                .andExpect(jsonPath("$.overallRatio", is(0.0)));
    }
}
