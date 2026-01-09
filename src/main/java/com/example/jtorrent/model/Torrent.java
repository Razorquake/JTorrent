package com.example.jtorrent.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "torrents")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Torrent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String infoHash;

    @Column(nullable = false)
    private String name;

    @Column(length = 2000)
    private String magnetLink;

    private Long totalSize; // in bytes

    private Long downloadedSize; // in bytes

    private Long uploadedSize; // in bytes

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TorrentStatus status;

    private Double progress; // 0.0 to 100.0

    private Integer downloadSpeed; // bytes per second

    private Integer uploadSpeed; // bytes per second

    private Integer peers; // connected peers

    private Integer seeds; // connected seeds

    @Column(nullable = false)
    private String savePath;

    @Column(nullable = false)
    private LocalDateTime addedDate;

    private LocalDateTime completedDate;

    @OneToMany(mappedBy = "torrent", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<TorrentFile> files = new ArrayList<>();

    @Column(length = 1000)
    private String errorMessage;

    // Metadata
    private String comment;
    private String createdBy;
    private LocalDateTime creationDate;


    @PrePersist
    protected void onCreate() {
        addedDate = LocalDateTime.now();
        if (status == null) {
            status = TorrentStatus.PENDING;
        }
        if (progress == null) {
            progress = 0.0;
        }
        if (downloadedSize == null) {
            downloadedSize = 0L;
        }
        if (uploadedSize == null) {
            uploadedSize = 0L;
        }
    }

    public void addFile(TorrentFile file) {
        files.add(file);
        file.setTorrent(this);
    }

    public void removeFile(TorrentFile file) {
        files.remove(file);
        file.setTorrent(null);
    }
}
