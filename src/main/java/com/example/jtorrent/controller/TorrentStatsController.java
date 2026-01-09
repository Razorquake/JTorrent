package com.example.jtorrent.controller;

import com.example.jtorrent.dto.TorrentStatsDTO;
import com.example.jtorrent.model.DownloadStatistics;
import com.example.jtorrent.service.TorrentStatisticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * REST Controller for torrent statistics and monitoring.
 * Provides endpoints for viewing statistics, speeds, and ratios.
 */
@RestController
@RequestMapping("/api/stats")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Statistics", description = "APIs for torrent statistics and monitoring")
public class TorrentStatsController {

    private final TorrentStatisticsService statisticsService;

    /**
     * Get overall system statistics.
     *
     * @return overall statistics across all torrents
     */
    @GetMapping("/overall")
    @Operation(summary = "Get overall stats",
            description = "Get aggregated statistics across all torrents")
    public ResponseEntity<TorrentStatsDTO> getOverallStatistics() {
        log.debug("REST: Getting overall statistics");

        TorrentStatsDTO stats = statisticsService.getOverallStatistics();
        return ResponseEntity.ok(stats);
    }

    /**
     * Get statistics for a specific torrent.
     *
     * @param torrentId torrent ID
     * @return detailed statistics for the torrent
     */
    @GetMapping("/torrent/{torrentId}")
    @Operation(summary = "Get torrent stats", description = "Get detailed statistics for a torrent")
    public ResponseEntity<DownloadStatistics> getTorrentStatistics(
            @Parameter(description = "Torrent ID") @PathVariable Long torrentId) {

        log.debug("REST: Getting statistics for torrent {}", torrentId);

        DownloadStatistics stats = statisticsService.getTorrentStatistics(torrentId);

        if (stats == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(stats);
    }

    /**
     * Get share ratio for a torrent.
     *
     * @param torrentId torrent ID
     * @return share ratio (uploaded/downloaded)
     */
    @GetMapping("/torrent/{torrentId}/ratio")
    @Operation(summary = "Get share ratio", description = "Get upload/download ratio for a torrent")
    public ResponseEntity<Map<String, Double>> getShareRatio(
            @Parameter(description = "Torrent ID") @PathVariable Long torrentId) {

        log.debug("REST: Getting share ratio for torrent {}", torrentId);

        Double ratio = statisticsService.getShareRatio(torrentId);

        Map<String, Double> response = new HashMap<>();
        response.put("ratio", ratio);

        return ResponseEntity.ok(response);
    }

    /**
     * Get estimated time remaining for a downloading torrent.
     *
     * @param torrentId torrent ID
     * @return estimated seconds remaining
     */
    @GetMapping("/torrent/{torrentId}/eta")
    @Operation(summary = "Get ETA", description = "Get estimated time remaining for download")
    public ResponseEntity<Map<String, Object>> getEstimatedTimeRemaining(
            @Parameter(description = "Torrent ID") @PathVariable Long torrentId) {

        log.debug("REST: Getting ETA for torrent {}", torrentId);

        Long eta = statisticsService.getEstimatedTimeRemaining(torrentId);

        Map<String, Object> response = new HashMap<>();
        response.put("etaSeconds", eta);

        if (eta != null) {
            response.put("etaFormatted", formatETA(eta));
        }

        return ResponseEntity.ok(response);
    }

    /**
     * Export statistics summary as text.
     *
     * @return text summary of statistics
     */
    @GetMapping("/export")
    @Operation(summary = "Export stats", description = "Export statistics summary as text")
    public ResponseEntity<String> exportStatistics() {
        log.debug("REST: Exporting statistics summary");

        String summary = statisticsService.exportStatisticsSummary();
        return ResponseEntity.ok(summary);
    }

    /**
     * Reset statistics for a torrent.
     *
     * @param torrentId torrent ID
     * @return success message
     */
    @PostMapping("/torrent/{torrentId}/reset")
    @Operation(summary = "Reset stats", description = "Reset statistics for a torrent")
    public ResponseEntity<Map<String, String>> resetTorrentStatistics(
            @Parameter(description = "Torrent ID") @PathVariable Long torrentId) {

        log.info("REST: Resetting statistics for torrent {}", torrentId);

        statisticsService.resetTorrentStatistics(torrentId);

        Map<String, String> response = new HashMap<>();
        response.put("message", "Statistics reset successfully");

        return ResponseEntity.ok(response);
    }

    /**
     * Get current speeds summary.
     *
     * @return current download and upload speeds
     */
    @GetMapping("/speeds")
    @Operation(summary = "Get speeds", description = "Get current download and upload speeds")
    public ResponseEntity<Map<String, Object>> getCurrentSpeeds() {
        log.debug("REST: Getting current speeds");

        TorrentStatsDTO stats = statisticsService.getOverallStatistics();

        Map<String, Object> response = new HashMap<>();
        response.put("downloadSpeed", stats.getCurrentDownloadSpeed());
        response.put("uploadSpeed", stats.getCurrentUploadSpeed());
        response.put("downloadSpeedFormatted", stats.getFormattedDownloadSpeed());
        response.put("uploadSpeedFormatted", stats.getFormattedUploadSpeed());
        response.put("activePeers", stats.getTotalActivePeers());
        response.put("activeSeeds", stats.getTotalActiveSeeds());

        return ResponseEntity.ok(response);
    }

    /**
     * Get data transfer totals.
     *
     * @return total downloaded and uploaded data
     */
    @GetMapping("/transfer")
    @Operation(summary = "Get transfer totals", description = "Get total data transferred")
    public ResponseEntity<Map<String, Object>> getDataTransfer() {
        log.debug("REST: Getting data transfer totals");

        TorrentStatsDTO stats = statisticsService.getOverallStatistics();

        Map<String, Object> response = new HashMap<>();
        response.put("totalDownloaded", stats.getTotalDownloadedBytes());
        response.put("totalUploaded", stats.getTotalUploadedBytes());
        response.put("totalDownloadedFormatted", stats.getFormattedTotalDownloaded());
        response.put("totalUploadedFormatted", stats.getFormattedTotalUploaded());
        response.put("overallRatio", stats.getOverallRatio());

        return ResponseEntity.ok(response);
    }

    /**
     * Get torrent counts by status.
     *
     * @return count of torrents in each status
     */
    @GetMapping("/counts")
    @Operation(summary = "Get status counts", description = "Get count of torrents by status")
    public ResponseEntity<Map<String, Long>> getStatusCounts() {
        log.debug("REST: Getting torrent counts by status");

        TorrentStatsDTO stats = statisticsService.getOverallStatistics();

        Map<String, Long> response = new HashMap<>();
        response.put("total", stats.getTotalTorrents());
        response.put("active", stats.getActiveTorrents());
        response.put("downloading", stats.getDownloadingTorrents());
        response.put("seeding", stats.getSeedingTorrents());
        response.put("completed", stats.getCompletedTorrents());
        response.put("paused", stats.getPausedTorrents());
        response.put("error", stats.getErrorTorrents());

        return ResponseEntity.ok(response);
    }

    /**
     * Format ETA seconds to human-readable string.
     */
    private String formatETA(long seconds) {
        if (seconds < 0) {
            return "Unknown";
        }

        long days = seconds / 86400;
        long hours = (seconds % 86400) / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;

        StringBuilder sb = new StringBuilder();

        if (days > 0) {
            sb.append(days).append("d ");
        }
        if (hours > 0) {
            sb.append(hours).append("h ");
        }
        if (minutes > 0) {
            sb.append(minutes).append("m ");
        }
        if (secs > 0 || sb.isEmpty()) {
            sb.append(secs).append("s");
        }

        return sb.toString().trim();
    }
}
