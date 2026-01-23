package com.example.jtorrent.service;

import com.example.jtorrent.dto.TorrentStatsDTO;
import com.example.jtorrent.model.DownloadStatistics;
import com.example.jtorrent.model.Torrent;
import com.example.jtorrent.model.TorrentStatus;
import com.example.jtorrent.repository.DownloadStatisticsRepository;
import com.example.jtorrent.repository.TorrentRepository;
import com.frostwire.jlibtorrent.TorrentHandle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("TorrentStatisticsService Unit Tests")
public class TorrentStatisticsServiceTest {

    @Mock
    private TorrentSessionManager sessionManager;

    @Mock
    private TorrentRepository torrentRepository;

    @Mock
    private DownloadStatisticsRepository statisticsRepository;

    @InjectMocks
    private TorrentStatisticsService statisticsService;

    private Torrent testTorrent;
    private DownloadStatistics testStatistics;

    @BeforeEach
    void setUp() {
        testTorrent = new Torrent();
        testTorrent.setId(1L);
        testTorrent.setInfoHash("test-hash");
        testTorrent.setName("Test Torrent");
        testTorrent.setStatus(TorrentStatus.DOWNLOADING);

        testStatistics = new DownloadStatistics();
        testStatistics.setId(1L);
        testStatistics.setTorrent(testTorrent);
        testStatistics.setTotalDownloaded(1000000L);
        testStatistics.setTotalUploaded(500000L);
        testStatistics.setRatio(0.5);
        testStatistics.setTimeActive(3600L);
        testStatistics.setAverageDownloadSpeed(100000);
        testStatistics.setAverageUploadSpeed(50000);
        testStatistics.setMaxDownloadSpeed(200000);
        testStatistics.setMaxUploadSpeed(100000);
        testStatistics.setStartTime(LocalDateTime.now().minusHours(1));
    }

    @Test
    @DisplayName("Should get overall statistics successfully")
    void testGetOverallStatistics_Success() {
        // Given
        when(torrentRepository.count()).thenReturn(10L);
        when(torrentRepository.countByStatus(TorrentStatus.DOWNLOADING)).thenReturn(3L);
        when(torrentRepository.countByStatus(TorrentStatus.COMPLETED)).thenReturn(5L);
        when(torrentRepository.countByStatus(TorrentStatus.SEEDING)).thenReturn(2L);
        when(torrentRepository.countByStatus(TorrentStatus.PAUSED)).thenReturn(0L);
        when(torrentRepository.countByStatus(TorrentStatus.ERROR)).thenReturn(0L);
        when(statisticsRepository.getTotalDownloadedAcrossAll()).thenReturn(5000000L);
        when(statisticsRepository.getTotalUploadedAcrossAll()).thenReturn(2500000L);
        when(torrentRepository.getTotalCompletedSize()).thenReturn(4000000L);
        when(sessionManager.isRunning()).thenReturn(false); // Avoid handle calls

        // When
        TorrentStatsDTO stats = statisticsService.getOverallStatistics();

        // Then
        assertThat(stats).isNotNull();
        assertThat(stats.getTotalTorrents()).isEqualTo(10L);
        assertThat(stats.getDownloadingTorrents()).isEqualTo(3L);
        assertThat(stats.getCompletedTorrents()).isEqualTo(5L);
        assertThat(stats.getSeedingTorrents()).isEqualTo(2L);
        assertThat(stats.getActiveTorrents()).isEqualTo(5L); // downloading + seeding
        assertThat(stats.getTotalDownloadedBytes()).isEqualTo(5000000L);
        assertThat(stats.getTotalUploadedBytes()).isEqualTo(2500000L);
        assertThat(stats.getOverallRatio()).isEqualTo(0.5);

        verify(torrentRepository, times(1)).count();
        verify(statisticsRepository, times(1)).getTotalDownloadedAcrossAll();
    }

    @Test
    @DisplayName("Should handle zero downloads when calculating overall ratio")
    void testGetOverallStatistics_ZeroDownloads() {
        // Given
        when(torrentRepository.count()).thenReturn(1L);
        when(torrentRepository.countByStatus(any())).thenReturn(0L);
        when(statisticsRepository.getTotalDownloadedAcrossAll()).thenReturn(0L);
        when(statisticsRepository.getTotalUploadedAcrossAll()).thenReturn(1000L);
        when(torrentRepository.getTotalCompletedSize()).thenReturn(0L);
        when(sessionManager.isRunning()).thenReturn(false);

        // When
        TorrentStatsDTO stats = statisticsService.getOverallStatistics();

        // Then
        assertThat(stats).isNotNull();
        assertThat(stats.getOverallRatio()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("Should get torrent statistics successfully")
    void testGetTorrentStatistics_Success() {
        // Given
        when(statisticsRepository.findByTorrentId(1L)).thenReturn(Optional.of(testStatistics));

        // When
        DownloadStatistics stats = statisticsService.getTorrentStatistics(1L);

        // Then
        assertThat(stats).isNotNull();
        assertThat(stats.getTotalDownloaded()).isEqualTo(1000000L);
        assertThat(stats.getTotalUploaded()).isEqualTo(500000L);
        assertThat(stats.getRatio()).isEqualTo(0.5);

        verify(statisticsRepository, times(1)).findByTorrentId(1L);
    }

    @Test
    @DisplayName("Should return null when statistics not found")
    void testGetTorrentStatistics_NotFound() {
        // Given
        when(statisticsRepository.findByTorrentId(999L)).thenReturn(Optional.empty());

        // When
        DownloadStatistics stats = statisticsService.getTorrentStatistics(999L);

        // Then
        assertThat(stats).isNull();

        verify(statisticsRepository, times(1)).findByTorrentId(999L);
    }

    @Test
    @DisplayName("Should get share ratio successfully")
    void testGetShareRatio_Success() {
        // Given
        when(statisticsRepository.findByTorrentId(1L)).thenReturn(Optional.of(testStatistics));

        // When
        Double ratio = statisticsService.getShareRatio(1L);

        // Then
        assertThat(ratio).isNotNull();
        assertThat(ratio).isEqualTo(0.5);

        verify(statisticsRepository, times(1)).findByTorrentId(1L);
    }

    @Test
    @DisplayName("Should return 0.0 ratio when statistics not found")
    void testGetShareRatio_NotFound() {
        // Given
        when(statisticsRepository.findByTorrentId(999L)).thenReturn(Optional.empty());

        // When
        Double ratio = statisticsService.getShareRatio(999L);

        // Then
        assertThat(ratio).isNotNull();
        assertThat(ratio).isEqualTo(0.0);
    }

    @Test
    @DisplayName("Should calculate ETA successfully")
    void testGetEstimatedTimeRemaining_Success() {
        // Given
        TorrentHandle mockHandle = mock(TorrentHandle.class);
        com.frostwire.jlibtorrent.TorrentStatus mockStatus = mock(com.frostwire.jlibtorrent.TorrentStatus.class);

        when(torrentRepository.findById(1L)).thenReturn(Optional.of(testTorrent));
        when(sessionManager.findTorrent("test-hash")).thenReturn(mockHandle);
        when(mockHandle.status()).thenReturn(mockStatus);
        when(mockStatus.totalWanted()).thenReturn(1000000L);
        when(mockStatus.totalDone()).thenReturn(500000L);
        when(mockStatus.downloadRate()).thenReturn(50000); // 50KB/s

        // When
        Long eta = statisticsService.getEstimatedTimeRemaining(1L);

        // Then
        assertThat(eta).isNotNull();
        assertThat(eta).isEqualTo(10L); // (1000000 - 500,000) / 50,000 = 10 seconds

        verify(sessionManager, times(1)).findTorrent("test-hash");
    }

    @Test
    @DisplayName("Should return null ETA when download speed is zero")
    void testGetEstimatedTimeRemaining_ZeroSpeed() {
        // Given
        TorrentHandle mockHandle = mock(TorrentHandle.class);
        com.frostwire.jlibtorrent.TorrentStatus mockStatus = mock(com.frostwire.jlibtorrent.TorrentStatus.class);

        when(torrentRepository.findById(1L)).thenReturn(Optional.of(testTorrent));
        when(sessionManager.findTorrent("test-hash")).thenReturn(mockHandle);
        when(mockHandle.status()).thenReturn(mockStatus);
        when(mockStatus.totalWanted()).thenReturn(1000000L);
        when(mockStatus.totalDone()).thenReturn(500000L);
        when(mockStatus.downloadRate()).thenReturn(0); // No download

        // When
        Long eta = statisticsService.getEstimatedTimeRemaining(1L);

        // Then
        assertThat(eta).isNull();
    }

    @Test
    @DisplayName("Should return null ETA when torrent not found")
    void testGetEstimatedTimeRemaining_TorrentNotFound() {
        // Given
        when(torrentRepository.findById(999L)).thenReturn(Optional.empty());

        // When
        Long eta = statisticsService.getEstimatedTimeRemaining(999L);

        // Then
        assertThat(eta).isNull();

        verify(torrentRepository, times(1)).findById(999L);
    }

    @Test
    @DisplayName("Should reset torrent statistics successfully")
    void testResetTorrentStatistics_Success() {
        // Given
        when(statisticsRepository.findByTorrentId(1L)).thenReturn(Optional.of(testStatistics));
        when(statisticsRepository.save(any(DownloadStatistics.class))).thenReturn(testStatistics);

        // When
        statisticsService.resetTorrentStatistics(1L);

        // Then
        assertThat(testStatistics.getTotalDownloaded()).isEqualTo(0L);
        assertThat(testStatistics.getTotalUploaded()).isEqualTo(0L);
        assertThat(testStatistics.getTimeActive()).isEqualTo(0L);
        assertThat(testStatistics.getEndTime()).isNull();

        verify(statisticsRepository, times(1)).save(testStatistics);
    }

    @Test
    @DisplayName("Should export statistics summary successfully")
    void testExportStatisticsSummary_Success() {
        // Given
        when(torrentRepository.count()).thenReturn(5L);
        when(torrentRepository.countByStatus(any())).thenReturn(1L);
        when(statisticsRepository.getTotalDownloadedAcrossAll()).thenReturn(1000000L);
        when(statisticsRepository.getTotalUploadedAcrossAll()).thenReturn(500000L);
        when(torrentRepository.getTotalCompletedSize()).thenReturn(800000L);
        when(sessionManager.isRunning()).thenReturn(false);

        // When
        String summary = statisticsService.exportStatisticsSummary();

        // Then
        assertThat(summary).isNotNull();
        assertThat(summary).contains("Torrent Statistics Summary");
        assertThat(summary).contains("Total Torrents: 5");
        assertThat(summary).contains("Total Downloaded:");
        assertThat(summary).contains("Total Uploaded:");
        assertThat(summary).contains("Overall Ratio:");
    }

    @Test
    @DisplayName("Should format statistics with proper units")
    void testStatisticsFormatting() {
        // Given
        when(torrentRepository.count()).thenReturn(1L);
        when(torrentRepository.countByStatus(any())).thenReturn(0L);
        when(statisticsRepository.getTotalDownloadedAcrossAll()).thenReturn(1073741824L); // 1 GB
        when(statisticsRepository.getTotalUploadedAcrossAll()).thenReturn(536870912L); // 512 MB
        when(torrentRepository.getTotalCompletedSize()).thenReturn(0L);
        when(sessionManager.isRunning()).thenReturn(false);

        // When
        TorrentStatsDTO stats = statisticsService.getOverallStatistics();

        // Then
        assertThat(stats.getFormattedTotalDownloaded()).contains("GB");
        assertThat(stats.getFormattedTotalUploaded()).contains("MB");
    }

    @Test
    @DisplayName("Should handle cleanup of orphaned statistics")
    void testCleanupOldStatistics() {
        // Given
        DownloadStatistics orphanedStats = new DownloadStatistics();
        orphanedStats.setId(999L);
        orphanedStats.setTorrent(null);

        when(statisticsRepository.findAll()).thenReturn(List.of(testStatistics, orphanedStats));
        when(torrentRepository.existsById(1L)).thenReturn(true);

        // When
        statisticsService.cleanupOldStatistics();

        // Then
        verify(statisticsRepository, times(1)).delete(orphanedStats);
        verify(statisticsRepository, never()).delete(testStatistics);
    }

    @Test
    @DisplayName("Should update statistics with active torrents")
    void testUpdateStatistics_WithActiveTorrents() {
        // Given
        TorrentHandle mockHandle = mock(TorrentHandle.class);
        com.frostwire.jlibtorrent.TorrentStatus mockStatus = mock(com.frostwire.jlibtorrent.TorrentStatus.class);

        when(sessionManager.isRunning()).thenReturn(true);
        when(torrentRepository.findAllActive()).thenReturn(List.of(testTorrent));
        when(sessionManager.findTorrent("test-hash")).thenReturn(mockHandle);
        when(mockHandle.status()).thenReturn(mockStatus);
        when(mockStatus.totalDownload()).thenReturn(1000000L);
        when(mockStatus.totalUpload()).thenReturn(500000L);
        when(mockStatus.downloadRate()).thenReturn(100000);
        when(mockStatus.uploadRate()).thenReturn(50000);
        when(mockStatus.numPeers()).thenReturn(10);
        when(mockStatus.isFinished()).thenReturn(false);
        when(statisticsRepository.findByTorrentId(1L)).thenReturn(Optional.of(testStatistics));
        when(statisticsRepository.save(any(DownloadStatistics.class))).thenReturn(testStatistics);

        // When
        statisticsService.updateStatistics();

        // Then
        verify(sessionManager, times(1)).findTorrent("test-hash");
        verify(statisticsRepository, times(1)).save(any(DownloadStatistics.class));
    }
}
