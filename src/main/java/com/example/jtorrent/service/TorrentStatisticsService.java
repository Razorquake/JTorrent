package com.example.jtorrent.service;

import com.example.jtorrent.dto.TorrentStatsDTO;
import com.example.jtorrent.model.DownloadStatistics;
import com.example.jtorrent.model.Torrent;
import com.example.jtorrent.model.TorrentStatus;
import com.example.jtorrent.repository.DownloadStatisticsRepository;
import com.example.jtorrent.repository.TorrentRepository;
import com.frostwire.jlibtorrent.TorrentHandle;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Service for tracking and reporting download statistics.
 * Maintains detailed statistics for individual torrents and overall system statistics.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TorrentStatisticsService {

    private final TorrentSessionManager sessionManager;
    private final TorrentRepository torrentRepository;
    private final DownloadStatisticsRepository statisticsRepository;

    /**
     * Update statistics for all active torrents.
     * Runs every minute.
     */
    @Scheduled(fixedRate = 60000)
    @Transactional
    public void updateStatistics() {
        if (!sessionManager.isRunning()) {
            return;
        }

        try {
            List<Torrent> activeTorrents = torrentRepository.findAllActive();

            for (Torrent torrent : activeTorrents) {
                updateTorrentStatistics(torrent);
            }

            log.debug("Updated statistics for {} active torrents", activeTorrents.size());

        } catch (Exception e) {
            log.error("Error in scheduled statistics update", e);
        }
    }

    /**
     * Update statistics for a single torrent.
     */
    @Transactional
    public void updateTorrentStatistics(Torrent torrent) {
        try {
            TorrentHandle handle = sessionManager.findTorrent(torrent.getInfoHash());

            if (handle == null) {
                return;
            }

            com.frostwire.jlibtorrent.TorrentStatus status = handle.status();

            // Get or create statistics record
            DownloadStatistics stats = statisticsRepository.findByTorrentId(torrent.getId())
                    .orElseGet(() -> {
                        DownloadStatistics newStats = new DownloadStatistics();
                        newStats.setTorrent(torrent);
                        newStats.setStartTime(LocalDateTime.now());
                        newStats.setTotalDownloaded(0L);
                        newStats.setTotalUploaded(0L);
                        newStats.setTimeActive(0L);
                        newStats.setAverageDownloadSpeed(0);
                        newStats.setAverageUploadSpeed(0);
                        newStats.setMaxDownloadSpeed(0);
                        newStats.setMaxUploadSpeed(0);
                        newStats.setTotalPeers(0);
                        return newStats;
                    });

            // Update cumulative data
            stats.setTotalDownloaded(status.totalDownload());
            stats.setTotalUploaded(status.totalUpload());

            // Update time active
            if (stats.getStartTime() != null) {
                Duration duration = Duration.between(stats.getStartTime(), LocalDateTime.now());
                stats.setTimeActive(duration.getSeconds());
            }

            // Update speeds
            int currentDownloadSpeed = status.downloadRate();
            int currentUploadSpeed = status.uploadRate();

            // Update max speeds
            if (currentDownloadSpeed > stats.getMaxDownloadSpeed()) {
                stats.setMaxDownloadSpeed(currentDownloadSpeed);
            }
            if (currentUploadSpeed > stats.getMaxUploadSpeed()) {
                stats.setMaxUploadSpeed(currentUploadSpeed);
            }

            // Calculate average speeds
            if (stats.getTimeActive() > 0) {
                stats.setAverageDownloadSpeed((int) (stats.getTotalDownloaded() / stats.getTimeActive()));
                stats.setAverageUploadSpeed((int) (stats.getTotalUploaded() / stats.getTimeActive()));
            }

            // Update peer count
            stats.setTotalPeers(Math.max(stats.getTotalPeers(), status.numPeers()));

            // Mark end time if completed
            if (status.isFinished() && stats.getEndTime() == null) {
                stats.setEndTime(LocalDateTime.now());
            }

            // The ratio is automatically calculated in @PreUpdate

            statisticsRepository.save(stats);
        } catch (Exception e) {
            log.error("Error updating statistics for torrent {}: {}",
                    torrent.getId(), e.getMessage());
            // Continue without crashing
        }
    }

    /**
     * Get statistics for a specific torrent.
     */
    @Transactional(readOnly = true)
    public DownloadStatistics getTorrentStatistics(Long torrentId) {
        return statisticsRepository.findByTorrentId(torrentId)
                .orElse(null);
    }

    /**
     * Get overall statistics across all torrents.
     */
    @Transactional(readOnly = true)
    public TorrentStatsDTO getOverallStatistics() {
        // Count torrents by status
        long totalTorrents = torrentRepository.count();
        long downloadingTorrents = torrentRepository.countByStatus(TorrentStatus.DOWNLOADING);
        long completedTorrents = torrentRepository.countByStatus(TorrentStatus.COMPLETED);
        long seedingTorrents = torrentRepository.countByStatus(TorrentStatus.SEEDING);
        long pausedTorrents = torrentRepository.countByStatus(TorrentStatus.PAUSED);
        long errorTorrents = torrentRepository.countByStatus(TorrentStatus.ERROR);

        long activeTorrents = downloadingTorrents + seedingTorrents;

        // Get data transfer totals
        Long totalDownloaded = statisticsRepository.getTotalDownloadedAcrossAll();
        Long totalUploaded = statisticsRepository.getTotalUploadedAcrossAll();
        Long totalCompleted = torrentRepository.getTotalCompletedSize();

        // Calculate overall ratio
        double overallRatio = 0.0;
        if (totalDownloaded != null && totalDownloaded > 0) {
            overallRatio = (totalUploaded != null) ? (totalUploaded.doubleValue() / totalDownloaded) : 0.0;
        }

        // Get current speeds and peer counts
        int currentDownloadSpeed = 0;
        int currentUploadSpeed = 0;
        int totalActivePeers = 0;
        int totalActiveSeeds = 0;

        // Get fresh handles from session, don't use cached handles
        if (sessionManager.isRunning()) {
            try {
                List<Torrent> activeTorrentList = torrentRepository.findAllActive();

                for (Torrent torrent : activeTorrentList) {
                    try {
                        // Get fresh handle for each torrent
                        TorrentHandle handle = sessionManager.findTorrent(torrent.getInfoHash());

                        if (handle != null) {
                            // Wrap status() call in try-catch
                            try {
                                com.frostwire.jlibtorrent.TorrentStatus status = handle.status();
                                currentDownloadSpeed += status.downloadRate();
                                currentUploadSpeed += status.uploadRate();
                                totalActivePeers += status.numPeers();
                                totalActiveSeeds += status.numSeeds();
                            } catch (Exception e) {
                                log.trace("Error getting status for torrent {}: {}",
                                        torrent.getId(), e.getMessage());
                                // Continue with next torrent
                            }
                        }
                    } catch (Exception e) {
                        log.trace("Error processing torrent {} for stats: {}",
                                torrent.getId(), e.getMessage());
                        // Continue with next torrent
                    }
                }
            } catch (Exception e) {
                log.error("Error calculating overall statistics: {}", e.getMessage(), e);
                // Continue with stats from database only
            }
        }

        return TorrentStatsDTO.builder()
                .totalTorrents(totalTorrents)
                .activeTorrents(activeTorrents)
                .completedTorrents(completedTorrents)
                .downloadingTorrents(downloadingTorrents)
                .seedingTorrents(seedingTorrents)
                .pausedTorrents(pausedTorrents)
                .errorTorrents(errorTorrents)
                .totalDownloadedBytes(totalDownloaded != null ? totalDownloaded : 0L)
                .totalUploadedBytes(totalUploaded != null ? totalUploaded : 0L)
                .totalCompletedSize(totalCompleted != null ? totalCompleted : 0L)
                .overallRatio(overallRatio)
                .totalActivePeers(totalActivePeers)
                .totalActiveSeeds(totalActiveSeeds)
                .currentDownloadSpeed(currentDownloadSpeed)
                .currentUploadSpeed(currentUploadSpeed)
                .build();
    }

    /**
     * Get download speed history for a torrent (for graphing).
     * This could be extended to store historical data points.
     */
    @Transactional(readOnly = true)
    public List<Integer> getDownloadSpeedHistory(Long torrentId) {
        // Placeholder for speed history
        // In a real implementation, you'd store periodic speed samples
        return List.of();
    }

    /**
     * Get estimated time remaining for a downloading torrent.
     */
    public Long getEstimatedTimeRemaining(Long torrentId) {
        Torrent torrent = torrentRepository.findById(torrentId).orElse(null);
        if (torrent == null) {
            return null;
        }

        try {
            TorrentHandle handle = sessionManager.findTorrent(torrent.getInfoHash());
            if (handle == null) {
                return null;
            }

            com.frostwire.jlibtorrent.TorrentStatus status = handle.status();

            long remaining = status.totalWanted() - status.totalDone();
            int downloadRate = status.downloadRate();

            if (downloadRate <= 0) {
                return null; // Can't estimate without download speed
            }

            return remaining / downloadRate; // Seconds
        } catch (Exception e) {
            log.error("Error calculating ETA for torrent {}: {}", torrentId, e.getMessage());
            return null;
        }
    }

    /**
     * Get share ratio for a torrent (uploaded / downloaded).
     */
    public Double getShareRatio(Long torrentId) {
        DownloadStatistics stats = statisticsRepository.findByTorrentId(torrentId)
                .orElse(null);

        if (stats == null) {
            return 0.0;
        }

        return stats.getRatio();
    }

    /**
     * Clean up old statistics (optional).
     * Runs once a day.
     */
    @Scheduled(cron = "0 0 2 * * ?") // 2 AM daily
    @Transactional
    public void cleanupOldStatistics() {
        try {
            // Remove statistics for torrents that no longer exist
            List<DownloadStatistics> allStats = statisticsRepository.findAll();
            int removed = 0;

            for (DownloadStatistics stats : allStats) {
                if (stats.getTorrent() == null ||
                        !torrentRepository.existsById(stats.getTorrent().getId())) {
                    statisticsRepository.delete(stats);
                    removed++;
                }
            }

            if (removed > 0) {
                log.info("Cleaned up {} orphaned statistics records", removed);
            }

        } catch (Exception e) {
            log.error("Error during statistics cleanup", e);
        }
    }

    /**
     * Export statistics summary for reporting.
     */
    @Transactional(readOnly = true)
    public String exportStatisticsSummary() {
        TorrentStatsDTO stats = getOverallStatistics();

        StringBuilder summary = new StringBuilder();
        summary.append("=== Torrent Statistics Summary ===\n");
        summary.append(String.format("Total Torrents: %d\n", stats.getTotalTorrents()));
        summary.append(String.format("Active: %d, Downloading: %d, Seeding: %d\n",
                stats.getActiveTorrents(), stats.getDownloadingTorrents(), stats.getSeedingTorrents()));
        summary.append(String.format("Completed: %d, Paused: %d, Errors: %d\n",
                stats.getCompletedTorrents(), stats.getPausedTorrents(), stats.getErrorTorrents()));
        summary.append(String.format("\nTotal Downloaded: %s\n", stats.getFormattedTotalDownloaded()));
        summary.append(String.format("Total Uploaded: %s\n", stats.getFormattedTotalUploaded()));
        summary.append(String.format("Overall Ratio: %.2f\n", stats.getOverallRatio()));
        summary.append(String.format("\nCurrent Download Speed: %s\n", stats.getFormattedDownloadSpeed()));
        summary.append(String.format("Current Upload Speed: %s\n", stats.getFormattedUploadSpeed()));
        summary.append(String.format("Active Peers: %d, Seeds: %d\n",
                stats.getTotalActivePeers(), stats.getTotalActiveSeeds()));

        return summary.toString();
    }

    /**
     * Reset statistics for a torrent (useful for testing or restart).
     */
    @Transactional
    public void resetTorrentStatistics(Long torrentId) {
        statisticsRepository.findByTorrentId(torrentId)
                .ifPresent(stats -> {
                    stats.setTotalDownloaded(0L);
                    stats.setTotalUploaded(0L);
                    stats.setTimeActive(0L);
                    stats.setAverageDownloadSpeed(0);
                    stats.setAverageUploadSpeed(0);
                    stats.setMaxDownloadSpeed(0);
                    stats.setMaxUploadSpeed(0);
                    stats.setTotalPeers(0);
                    stats.setStartTime(LocalDateTime.now());
                    stats.setEndTime(null);
                    statisticsRepository.save(stats);
                    log.info("Reset statistics for torrent {}", torrentId);
                });
    }
}
