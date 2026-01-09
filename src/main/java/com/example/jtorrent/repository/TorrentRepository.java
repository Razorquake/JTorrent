package com.example.jtorrent.repository;

import com.example.jtorrent.model.Torrent;
import com.example.jtorrent.model.TorrentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface TorrentRepository extends JpaRepository<Torrent, Long>, JpaSpecificationExecutor<Torrent> {

    // Find by info hash (unique identifier for torrents)
    Optional<Torrent> findByInfoHash(String infoHash);

    // Check if torrent exists by info hash
    boolean existsByInfoHash(String infoHash);

    // Find all torrents by status
    List<Torrent> findByStatus(TorrentStatus status);

    // Find all active torrents (downloading or seeding)
    @Query("SELECT t FROM Torrent t WHERE t.status IN ('DOWNLOADING', 'SEEDING', 'CHECKING')")
    List<Torrent> findAllActive();

    // Find torrents by multiple statuses
    List<Torrent> findByStatusIn(List<TorrentStatus> statuses);

    // Find all torrents ordered by added date (newest first)
    List<Torrent> findAllByOrderByAddedDateDesc();

    // Find torrents by name containing (case-insensitive search)
    List<Torrent> findByNameContainingIgnoreCase(String name);

    // Find completed torrents
    List<Torrent> findByStatusAndCompletedDateIsNotNull(TorrentStatus status);

    // Find torrents added after a certain date
    List<Torrent> findByAddedDateAfter(LocalDateTime date);

    // Find torrents with errors
    List<Torrent> findByStatusAndErrorMessageIsNotNull(TorrentStatus status);

    // Count torrents by status
    long countByStatus(TorrentStatus status);

    // Get total download size
    @Query("SELECT COALESCE(SUM(t.totalSize), 0) FROM Torrent t WHERE t.status = 'COMPLETED'")
    Long getTotalCompletedSize();

    // Get total downloaded data across all torrents
    @Query("SELECT COALESCE(SUM(t.downloadedSize), 0) FROM Torrent t")
    Long getTotalDownloadedData();

    // Get total uploaded data across all torrents
    @Query("SELECT COALESCE(SUM(t.uploadedSize), 0) FROM Torrent t")
    Long getTotalUploadedData();

    // Find torrents with progress between range
    @Query("SELECT t FROM Torrent t WHERE t.progress >= :minProgress AND t.progress <= :maxProgress")
    List<Torrent> findByProgressBetween(@Param("minProgress") Double minProgress,
                                        @Param("maxProgress") Double maxProgress);

    // Get active download count
    @Query("SELECT COUNT(t) FROM Torrent t WHERE t.status IN ('DOWNLOADING', 'CHECKING')")
    long getActiveDownloadCount();

    // Find stalled torrents (no progress in last X minutes with active status)
    @Query("SELECT t FROM Torrent t WHERE t.status = 'DOWNLOADING' " +
            "AND (t.downloadSpeed IS NULL OR t.downloadSpeed = 0) " +
            "AND t.peers = 0")
    List<Torrent> findStalledTorrents();
}
