package com.example.jtorrent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AddTorrentFileResponse {

    private Long torrentId;
    private String infoHash;
    private String name;
    private Long totalSize;
    private Integer fileCount;
    private String message;
    private Boolean started;

    public String getFormattedSize() {
        if (totalSize == null || totalSize == 0) return "0 B";
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int unitIndex = (int) (Math.log10(totalSize) / Math.log10(1024));
        double size = totalSize / Math.pow(1024, unitIndex);
        return String.format("%.2f %s", size, units[unitIndex]);
    }
}
