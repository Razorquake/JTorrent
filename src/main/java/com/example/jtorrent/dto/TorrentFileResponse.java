package com.example.jtorrent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TorrentFileResponse {

    private Long id;
    private String path;
    private Long size;
    private Long downloadedSize;
    private Double progress;
    private Integer priority;

    public String getFormattedSize() {
        if (size == null || size == 0) return "0 B";
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int unitIndex = (int) (Math.log10(size) / Math.log10(1024));
        double formattedSize = size / Math.pow(1024, unitIndex);
        return String.format("%.2f %s", formattedSize, units[unitIndex]);
    }
}
