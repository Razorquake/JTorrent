package com.example.jtorrent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TorrentStatsDTO {

    private Long totalTorrents;
    private Long activeTorrents;
    private Long completedTorrents;
    private Long downloadingTorrents;
    private Long seedingTorrents;
    private Long pausedTorrents;
    private Long errorTorrents;

    private Long totalDownloadedBytes;
    private Long totalUploadedBytes;
    private Long totalCompletedSize;

    private Double overallRatio;

    private Integer totalActivePeers;
    private Integer totalActiveSeeds;

    private Integer currentDownloadSpeed; // bytes per second
    private Integer currentUploadSpeed;   // bytes per second

    public String getFormattedTotalDownloaded() {
        return formatBytes(totalDownloadedBytes);
    }

    public String getFormattedTotalUploaded() {
        return formatBytes(totalUploadedBytes);
    }

    public String getFormattedDownloadSpeed() {
        return formatBytes(currentDownloadSpeed != null ? currentDownloadSpeed.longValue() : 0L) + "/s";
    }

    public String getFormattedUploadSpeed() {
        return formatBytes(currentUploadSpeed != null ? currentUploadSpeed.longValue() : 0L) + "/s";
    }

    private String formatBytes(Long bytes) {
        if (bytes == null || bytes == 0) return "0 B";
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int unitIndex = (int) (Math.log10(bytes) / Math.log10(1024));
        double size = bytes / Math.pow(1024, unitIndex);
        return String.format("%.2f %s", size, units[unitIndex]);
    }
}
