package com.example.jtorrent.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "download_statistics")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DownloadStatistics {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne
    @JoinColumn(name = "torrent_id", unique = true)
    private Torrent torrent;

    private Long totalDownloaded; // Total bytes downloaded
    private Long totalUploaded;   // Total bytes uploaded

    private Double ratio; // Upload/Download ratio

    private Long timeActive; // Seconds the torrent has been active

    private LocalDateTime startTime;
    private LocalDateTime endTime;

    private Integer averageDownloadSpeed; // bytes per second
    private Integer averageUploadSpeed;   // bytes per second

    private Integer maxDownloadSpeed;
    private Integer maxUploadSpeed;

    private Integer totalPeers;  // Total unique peers connected

    @PrePersist
    @PreUpdate
    protected void updateRatio() {
        if (totalDownloaded != null && totalDownloaded > 0) {
            ratio = totalUploaded != null ? (double) totalUploaded / totalDownloaded : 0.0;
        } else {
            ratio = 0.0;
        }
    }
}
