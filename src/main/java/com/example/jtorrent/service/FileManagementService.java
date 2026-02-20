package com.example.jtorrent.service;

import com.example.jtorrent.model.Torrent;
import com.example.jtorrent.repository.TorrentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/**
 * Service responsible for all disk-level file management operations.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Delete downloaded files for a torrent when it is removed</li>
 *   <li>Report disk usage of the downloads directory</li>
 *   <li>Detect and clean up orphaned files (on disk but not in the database)</li>
 * </ul>
 *
 * <p>This service is intentionally separate from {@link TorrentService} to keep
 * IO/filesystem concerns isolated from the torrent lifecycle logic.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FileManagementService {

    private final TorrentSessionManager sessionManager;
    private final TorrentRepository torrentRepository;

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Delete all downloaded files that belong to the given torrent.
     *
     * <p>This is the Java-side fallback for cases where jlibtorrent's
     * {@code SessionHandle.DELETE_FILES} cannot be used — e.g. when the torrent
     * handle has already been removed from the session.
     *
     * <p>The method walks {@code torrent.getSavePath()} and deletes every file
     * whose relative path is listed in {@code torrent.getFiles()}, then removes
     * any directories that become empty after the deletion.
     *
     * @param torrent the torrent whose files should be deleted
     * @return number of files successfully deleted
     */
    public int deleteTorrentFiles(Torrent torrent) {
        String savePath = torrent.getSavePath();
        if (savePath == null || savePath.isBlank()) {
            log.warn("Torrent {} has no savePath — skipping file deletion", torrent.getId());
            return 0;
        }

        Path saveDir = Path.of(savePath);
        if (!Files.exists(saveDir)) {
            log.debug("Save directory does not exist for torrent {}: {}", torrent.getId(), saveDir);
            return 0;
        }

        int deleted = 0;

        // Delete each file registered in the DB
        for (com.example.jtorrent.model.TorrentFile tf : torrent.getFiles()) {
            if (tf.getPath() == null) continue;

            Path filePath = saveDir.resolve(tf.getPath()).normalize();

            // Security: ensure the resolved path stays inside saveDir
            if (!filePath.startsWith(saveDir)) {
                log.warn("Refusing to delete path outside saveDir: {}", filePath);
                continue;
            }

            try {
                if (Files.deleteIfExists(filePath)) {
                    log.debug("Deleted file: {}", filePath);
                    deleted++;
                }
            } catch (IOException e) {
                log.warn("Could not delete file {}: {}", filePath, e.getMessage());
            }
        }

        // For single-file torrents the torrent name IS the file; also try that path
        Path namedFile = saveDir.resolve(torrent.getName()).normalize();
        if (namedFile.startsWith(saveDir)) {
            try {
                if (Files.deleteIfExists(namedFile)) {
                    log.debug("Deleted torrent-named file: {}", namedFile);
                    deleted++;
                }
            } catch (IOException e) {
                log.debug("Could not delete torrent-named file {}: {}", namedFile, e.getMessage());
            }
        }

        // Remove now-empty subdirectories within saveDir
        deleteEmptyDirectories(saveDir);

        log.info("Deleted {} file(s) for torrent: {} ({})",
                deleted, torrent.getName(), torrent.getId());
        return deleted;
    }

    /**
     * Return disk usage information for the configured downloads directory.
     *
     * @return map with keys: {@code path}, {@code totalBytes}, {@code usedBytes},
     *         {@code freeBytes}, {@code usedByTorrentsBytes}
     */
    public Map<String, Object> getStorageInfo() {
        File downloadDir = sessionManager.getDownloadDirectory();
        Map<String, Object> info = new LinkedHashMap<>();

        info.put("path", downloadDir.getAbsolutePath());

        try {
            FileStore store = Files.getFileStore(downloadDir.toPath());
            long total = store.getTotalSpace();
            long free  = store.getUsableSpace();
            long used  = total - free;

            info.put("totalBytes",    total);
            info.put("usedBytes",     used);
            info.put("freeBytes",     free);
            info.put("totalFormatted", formatBytes(total));
            info.put("usedFormatted",  formatBytes(used));
            info.put("freeFormatted",  formatBytes(free));
        } catch (IOException e) {
            log.warn("Could not read file store stats: {}", e.getMessage());
            info.put("totalBytes", -1L);
            info.put("usedBytes",  -1L);
            info.put("freeBytes",  -1L);
        }

        // Calculate disk space occupied by tracked torrent files
        long trackedBytes = calculateTrackedBytes(downloadDir.toPath());
        info.put("usedByTorrentsBytes",    trackedBytes);
        info.put("usedByTorrentsFormatted", formatBytes(trackedBytes));

        return info;
    }

    /**
     * Scan the downloads directory and return paths of files that exist on disk
     * but are not referenced by any {@link Torrent} record in the database.
     *
     * @return list of absolute path strings of orphaned files
     */
    public List<String> findOrphanedFiles() {
        File downloadDir = sessionManager.getDownloadDirectory();
        Path baseDir = downloadDir.toPath();

        if (!Files.exists(baseDir)) {
            return Collections.emptyList();
        }

        // Build the set of all paths that are tracked in the DB
        Set<Path> trackedPaths = buildTrackedPathSet(baseDir);

        // Walk the directory tree and collect files not in the tracked set
        List<String> orphans = new ArrayList<>();
        try {
            Files.walkFileTree(baseDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (!trackedPaths.contains(file.normalize())) {
                        orphans.add(file.toAbsolutePath().toString());
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    log.warn("Could not access file during orphan scan: {}", file);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.error("Error scanning downloads directory for orphans: {}", e.getMessage(), e);
        }

        log.info("Found {} orphaned file(s) in {}", orphans.size(), baseDir);
        return orphans;
    }

    /**
     * Delete all orphaned files found by {@link #findOrphanedFiles()}.
     *
     * @return number of files deleted
     */
    public int cleanupOrphanedFiles() {
        List<String> orphans = findOrphanedFiles();
        int deleted = 0;

        for (String orphanPath : orphans) {
            try {
                if (Files.deleteIfExists(Path.of(orphanPath))) {
                    log.info("Deleted orphaned file: {}", orphanPath);
                    deleted++;
                }
            } catch (IOException e) {
                log.warn("Could not delete orphaned file {}: {}", orphanPath, e.getMessage());
            }
        }

        // Clean up any empty directories left behind
        deleteEmptyDirectories(sessionManager.getDownloadDirectory().toPath());

        log.info("Orphan cleanup complete: {} file(s) deleted", deleted);
        return deleted;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Build the set of all file paths that are currently tracked by any torrent
     * in the database, resolved against {@code baseDir}.
     */
    private Set<Path> buildTrackedPathSet(Path baseDir) {
        List<Torrent> allTorrents = torrentRepository.findAll();
        Set<Path> tracked = new HashSet<>();

        for (Torrent torrent : allTorrents) {
            String sp = torrent.getSavePath();
            if (sp == null) continue;
            Path saveDir = Path.of(sp);

            for (com.example.jtorrent.model.TorrentFile tf : torrent.getFiles()) {
                if (tf.getPath() == null) continue;
                Path resolved = saveDir.resolve(tf.getPath()).normalize();
                tracked.add(resolved);
            }

            // Also track the torrent-named file for single-file torrents
            Path namedFile = saveDir.resolve(torrent.getName()).normalize();
            tracked.add(namedFile);
        }

        return tracked;
    }

    /**
     * Sum the sizes of all files on disk that are currently tracked by torrents
     * whose save path is within {@code baseDir}.
     */
    private long calculateTrackedBytes(Path baseDir) {
        List<Torrent> torrents = torrentRepository.findAll();
        long total = 0L;

        for (Torrent torrent : torrents) {
            String sp = torrent.getSavePath();
            if (sp == null) continue;
            Path saveDir = Path.of(sp);

            // Only count torrents that save inside the monitored base directory
            if (!saveDir.normalize().startsWith(baseDir.normalize())) continue;

            for (com.example.jtorrent.model.TorrentFile tf : torrent.getFiles()) {
                if (tf.getPath() == null) continue;
                Path filePath = saveDir.resolve(tf.getPath()).normalize();
                try {
                    if (Files.exists(filePath)) {
                        total += Files.size(filePath);
                    }
                } catch (IOException e) {
                    log.trace("Could not get size of {}: {}", filePath, e.getMessage());
                }
            }
        }

        return total;
    }

    /**
     * Recursively remove directories inside {@code root} that contain no files.
     * Walks bottom-up so nested empty trees are pruned in one pass.
     */
    private void deleteEmptyDirectories(Path root) {
        if (!Files.isDirectory(root)) return;

        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                    // Never delete the root downloads directory itself
                    if (dir.equals(root)) return FileVisitResult.CONTINUE;
                    try {
                        // deleteIfExists on a directory only succeeds when it is empty
                        if (Files.deleteIfExists(dir)) {
                            log.debug("Removed empty directory: {}", dir);
                        }
                    } catch (IOException e) {
                        // Directory not empty — expected, not an error
                        log.trace("Directory not empty (skipped): {}", dir);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("Error while cleaning up empty directories: {}", e.getMessage());
        }
    }

    /**
     * Format a byte count into a human-readable string (B / KB / MB / GB / TB).
     */
    private String formatBytes(long bytes) {
        if (bytes <= 0) return "0 B";
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int idx = (int) (Math.log10(bytes) / Math.log10(1024));
        idx = Math.min(idx, units.length - 1);
        double value = bytes / Math.pow(1024, idx);
        return String.format("%.2f %s", value, units[idx]);
    }
}