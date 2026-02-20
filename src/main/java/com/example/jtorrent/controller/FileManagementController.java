package com.example.jtorrent.controller;

import com.example.jtorrent.service.FileManagementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * REST Controller for disk-level file management operations.
 *
 * <p>Exposes three areas of functionality:
 * <ul>
 *   <li>Storage information — disk usage of the downloads directory</li>
 *   <li>Orphan detection — files on disk with no matching DB record</li>
 *   <li>Orphan cleanup — delete those stale files</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "File Management", description = "APIs for disk-level file management and cleanup")
public class FileManagementController {

    private final FileManagementService fileManagementService;

    /**
     * Get disk usage information for the downloads directory.
     *
     * <p>Returns total, used, and free space for the underlying filesystem,
     * plus the subset of used space that belongs to tracked torrents.
     *
     * @return storage info map
     */
    @GetMapping("/storage")
    @Operation(
            summary = "Get storage info",
            description = "Report total, used, and free disk space for the downloads directory, "
                    + "including how much is occupied by tracked torrent files.")
    public ResponseEntity<Map<String, Object>> getStorageInfo() {
        log.debug("REST: Getting storage info");
        return ResponseEntity.ok(fileManagementService.getStorageInfo());
    }

    /**
     * Scan the downloads directory for orphaned files.
     *
     * <p>An orphaned file is one that exists on disk but has no matching record
     * in the database. This can happen after a crash, manual DB manipulation, or
     * if a torrent was removed without deleting its files.
     *
     * @return list of absolute paths of orphaned files
     */
    @GetMapping("/orphans")
    @Operation(
            summary = "Find orphaned files",
            description = "Scan the downloads directory and return paths of files that are "
                    + "not tracked by any torrent in the database.")
    public ResponseEntity<List<String>> findOrphanedFiles() {
        log.info("REST: Scanning for orphaned files");
        List<String> orphans = fileManagementService.findOrphanedFiles();
        return ResponseEntity.ok(orphans);
    }

    /**
     * Delete all orphaned files in the downloads directory.
     *
     * <p>Runs an orphan scan and then deletes every file found.
     * Also removes any directories that become empty after deletion.
     *
     * @return result map with {@code deletedCount} and {@code message}
     */
    @DeleteMapping("/orphans")
    @Operation(
            summary = "Clean up orphaned files",
            description = "Delete all files in the downloads directory that are not tracked "
                    + "by any torrent in the database. Also removes empty directories.")
    public ResponseEntity<Map<String, Object>> cleanupOrphanedFiles() {
        log.info("REST: Starting orphan file cleanup");
        int deleted = fileManagementService.cleanupOrphanedFiles();

        Map<String, Object> response = Map.of(
                "deletedCount", deleted,
                "message", deleted == 0
                        ? "No orphaned files found"
                        : "Deleted " + deleted + " orphaned file(s)"
        );
        return ResponseEntity.ok(response);
    }
}
