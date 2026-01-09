package com.example.jtorrent.dto;

import com.example.jtorrent.model.TorrentStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TorrentResponse {

    private Long id;
    private String infoHash;
    private String name;
    private String magnetLink;
    private Long totalSize;
    private Long downloadedSize;
    private Long uploadedSize;
    private TorrentStatus status;
    private Double progress;
    private Integer downloadSpeed;
    private Integer uploadSpeed;
    private Integer peers;
    private Integer seeds;
    private String savePath;
    private LocalDateTime addedDate;
    private LocalDateTime completedDate;
    private List<TorrentFileResponse> files;
    private String errorMessage;
    private String comment;
    private String createdBy;
    private LocalDateTime creationDate;

    // Helper method to format size
    public String getFormattedTotalSize() {
        return formatBytes(totalSize);
    }

    public String getFormattedDownloadedSize() {
        return formatBytes(downloadedSize);
    }

    public String getFormattedDownloadSpeed() {
        return formatBytes(Long.valueOf(downloadSpeed)) + "/s";
    }

    public String getFormattedUploadSpeed() {
        return formatBytes(uploadedSize) + "/s";
    }

    private String formatBytes(Long bytes) {
        if (bytes == null || bytes == 0) return "0 B";
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int unitIndex = (int) (Math.log10(bytes) / Math.log10(1024));
        double size = bytes / Math.pow(1024, unitIndex);
        return String.format("%.2f %s", size, units[unitIndex]);
    }
}
