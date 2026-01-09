package com.example.jtorrent.repository;

import com.example.jtorrent.model.Torrent;
import com.example.jtorrent.model.TorrentFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TorrentFileRepository extends JpaRepository<TorrentFile, Long> {

    // Find all files for a specific torrent
    List<TorrentFile> findByTorrent(Torrent torrent);

    // Find files by torrent ID
    List<TorrentFile> findByTorrentId(Long torrentId);

    // Find a specific file by torrent and path
    Optional<TorrentFile> findByTorrentAndPath(Torrent torrent, String path);

    // Find files by priority
    List<TorrentFile> findByTorrentAndPriority(Torrent torrent, Integer priority);

    // Find files with specific priority for a torrent ID
    List<TorrentFile> findByTorrentIdAndPriority(Long torrentId, Integer priority);

    // Find incomplete files for a torrent
    @Query("SELECT f FROM TorrentFile f WHERE f.torrent = :torrent AND f.progress < 100.0")
    List<TorrentFile> findIncompleteFiles(@Param("torrent") Torrent torrent);

    // Count files by torrent
    long countByTorrent(Torrent torrent);

    // Get total size of all files in a torrent
    @Query("SELECT COALESCE(SUM(f.size), 0) FROM TorrentFile f WHERE f.torrent = :torrent")
    Long getTotalSizeByTorrent(@Param("torrent") Torrent torrent);

    // Get total downloaded size for a torrent
    @Query("SELECT COALESCE(SUM(f.downloadedSize), 0) FROM TorrentFile f WHERE f.torrent = :torrent")
    Long getTotalDownloadedByTorrent(@Param("torrent") Torrent torrent);

    // Find files by path pattern (useful for filtering by extension)
    @Query("SELECT f FROM TorrentFile f WHERE f.torrent = :torrent AND f.path LIKE %:pattern%")
    List<TorrentFile> findByTorrentAndPathContaining(@Param("torrent") Torrent torrent,
                                                     @Param("pattern") String pattern);

    // Delete all files for a torrent
    void deleteByTorrent(Torrent torrent);

    // Find files skipped (priority 0)
    @Query("SELECT f FROM TorrentFile f WHERE f.torrent.id = :torrentId AND f.priority = 0")
    List<TorrentFile> findSkippedFiles(@Param("torrentId") Long torrentId);
}
