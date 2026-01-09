package com.example.jtorrent.repository;

import com.example.jtorrent.model.DownloadStatistics;
import com.example.jtorrent.model.Torrent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface DownloadStatisticsRepository extends JpaRepository<DownloadStatistics, Long> {

    // Find statistics by torrent
    Optional<DownloadStatistics> findByTorrent(Torrent torrent);

    // Find statistics by torrent ID
    Optional<DownloadStatistics> findByTorrentId(Long torrentId);

    // Delete statistics for a torrent
    void deleteByTorrent(Torrent torrent);

    // Get overall statistics across all torrents
    @Query("SELECT COALESCE(SUM(ds.totalDownloaded), 0) FROM DownloadStatistics ds")
    Long getTotalDownloadedAcrossAll();

    @Query("SELECT COALESCE(SUM(ds.totalUploaded), 0) FROM DownloadStatistics ds")
    Long getTotalUploadedAcrossAll();

    @Query("SELECT COALESCE(AVG(ds.ratio), 0) FROM DownloadStatistics ds WHERE ds.ratio > 0")
    Double getAverageRatioAcrossAll();

    @Query("SELECT COALESCE(SUM(ds.timeActive), 0) FROM DownloadStatistics ds")
    Long getTotalActiveTimeAcrossAll();
}
