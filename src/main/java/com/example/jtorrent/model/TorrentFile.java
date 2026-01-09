package com.example.jtorrent.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "torrent_files")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TorrentFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "torrent_id", nullable = false)
    @JsonIgnore
    private Torrent torrent;

    @Column(nullable = false)
    private String path; // Relative path within torrent

    @Column(nullable = false)
    private Long size; // File size in bytes

    private Long downloadedSize; // Downloaded bytes for this file

    private Double progress; // 0.0 to 100.0

    private Integer priority; // Download priority (0=skip, 1=low, 4=normal, 7=high)

    @PrePersist
    protected void onCreate() {
        if (downloadedSize == null) {
            downloadedSize = 0L;
        }
        if (progress == null) {
            progress = 0.0;
        }
        if (priority == null) {
            priority = 4; // Normal priority by default
        }
    }
}
