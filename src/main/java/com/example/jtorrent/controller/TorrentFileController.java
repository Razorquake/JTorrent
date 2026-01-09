package com.example.jtorrent.controller;

import com.example.jtorrent.dto.MessageResponse;
import com.example.jtorrent.dto.SkipFilesRequest;
import com.example.jtorrent.dto.TorrentFileResponse;
import com.example.jtorrent.dto.UpdateFilePrioritiesRequest;
import com.example.jtorrent.service.TorrentFileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST Controller for torrent file management.
 * Provides endpoints for managing individual files within torrents.
 */
@RestController
@RequestMapping("/api/torrents/{torrentId}/files")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "File Management", description = "APIs for managing files within torrents")
public class TorrentFileController {

    private final TorrentFileService fileService;

    /**
     * Get all files for a torrent.
     *
     * @param torrentId torrent ID
     * @return list of files
     */
    @GetMapping
    @Operation(summary = "Get torrent files", description = "Get all files in a torrent")
    public ResponseEntity<List<TorrentFileResponse>> getTorrentFiles(
            @Parameter(description = "Torrent ID") @PathVariable Long torrentId) {

        log.debug("REST: Getting files for torrent {}", torrentId);

        List<TorrentFileResponse> files = fileService.getTorrentFiles(torrentId);
        return ResponseEntity.ok(files);
    }

    /**
     * Update file priorities.
     *
     * @param torrentId torrent ID
     * @param request file IDs and new priority
     * @return success message
     */
    @PutMapping("/priorities")
    @Operation(summary = "Update file priorities",
            description = "Set priority for selected files (0=skip, 1=low, 4=normal, 7=high)")
    public ResponseEntity<MessageResponse> updateFilePriorities(
            @Parameter(description = "Torrent ID") @PathVariable Long torrentId,
            @RequestBody UpdateFilePrioritiesRequest request) {

        log.info("REST: Updating priorities for {} files in torrent {}",
                request.getFileIds().size(), torrentId);

        MessageResponse response = fileService.updateFilePriorities(torrentId, request);
        return ResponseEntity.ok(response);
    }

    /**
     * Skip files (don't download).
     *
     * @param torrentId torrent ID
     * @param request file IDs to skip
     * @return success message
     */
    @PostMapping("/skip")
    @Operation(summary = "Skip files", description = "Mark files as skipped (won't be downloaded)")
    public ResponseEntity<MessageResponse> skipFiles(
            @Parameter(description = "Torrent ID") @PathVariable Long torrentId,
            @RequestBody SkipFilesRequest request) {

        log.info("REST: Skipping {} files in torrent {}",
                request.getFileIds().size(), torrentId);

        MessageResponse response = fileService.skipFiles(torrentId, request);
        return ResponseEntity.ok(response);
    }

    /**
     * Download previously skipped files.
     *
     * @param torrentId torrent ID
     * @param request file IDs to download
     * @return success message
     */
    @PostMapping("/download")
    @Operation(summary = "Download files", description = "Start downloading previously skipped files")
    public ResponseEntity<MessageResponse> downloadFiles(
            @Parameter(description = "Torrent ID") @PathVariable Long torrentId,
            @RequestBody SkipFilesRequest request) {

        log.info("REST: Downloading {} files in torrent {}",
                request.getFileIds().size(), torrentId);

        MessageResponse response = fileService.downloadFiles(torrentId, request);
        return ResponseEntity.ok(response);
    }

    /**
     * Get skipped files.
     *
     * @param torrentId torrent ID
     * @return list of skipped files
     */
    @GetMapping("/skipped")
    @Operation(summary = "Get skipped files", description = "Get all files marked as skipped")
    public ResponseEntity<List<TorrentFileResponse>> getSkippedFiles(
            @Parameter(description = "Torrent ID") @PathVariable Long torrentId) {

        log.debug("REST: Getting skipped files for torrent {}", torrentId);

        List<TorrentFileResponse> files = fileService.getSkippedFiles(torrentId);
        return ResponseEntity.ok(files);
    }

    /**
     * Get incomplete files.
     *
     * @param torrentId torrent ID
     * @return list of incomplete files
     */
    @GetMapping("/incomplete")
    @Operation(summary = "Get incomplete files", description = "Get all files not fully downloaded")
    public ResponseEntity<List<TorrentFileResponse>> getIncompleteFiles(
            @Parameter(description = "Torrent ID") @PathVariable Long torrentId) {

        log.debug("REST: Getting incomplete files for torrent {}", torrentId);

        List<TorrentFileResponse> files = fileService.getIncompleteFiles(torrentId);
        return ResponseEntity.ok(files);
    }

    /**
     * Get files by priority.
     *
     * @param torrentId torrent ID
     * @param priority priority level (0-7)
     * @return list of files with specified priority
     */
    @GetMapping("/priority/{priority}")
    @Operation(summary = "Get files by priority", description = "Get all files with a specific priority")
    public ResponseEntity<List<TorrentFileResponse>> getFilesByPriority(
            @Parameter(description = "Torrent ID") @PathVariable Long torrentId,
            @Parameter(description = "Priority level (0-7)") @PathVariable Integer priority) {

        log.debug("REST: Getting files with priority {} for torrent {}", priority, torrentId);

        List<TorrentFileResponse> files = fileService.getFilesByPriority(torrentId, priority);
        return ResponseEntity.ok(files);
    }

    /**
     * Prioritize files (high priority).
     *
     * @param torrentId torrent ID
     * @param request file IDs to prioritize
     * @return success message
     */
    @PostMapping("/prioritize")
    @Operation(summary = "Prioritize files", description = "Set files to high priority (download first)")
    public ResponseEntity<MessageResponse> prioritizeFiles(
            @Parameter(description = "Torrent ID") @PathVariable Long torrentId,
            @RequestBody SkipFilesRequest request) {

        log.info("REST: Prioritizing {} files in torrent {}",
                request.getFileIds().size(), torrentId);

        MessageResponse response = fileService.prioritizeFiles(torrentId, request.getFileIds());
        return ResponseEntity.ok(response);
    }

    /**
     * Deprioritize files (low priority).
     *
     * @param torrentId torrent ID
     * @param request file IDs to deprioritize
     * @return success message
     */
    @PostMapping("/deprioritize")
    @Operation(summary = "Deprioritize files", description = "Set files to low priority (download last)")
    public ResponseEntity<MessageResponse> deprioritizeFiles(
            @Parameter(description = "Torrent ID") @PathVariable Long torrentId,
            @RequestBody SkipFilesRequest request) {

        log.info("REST: Deprioritizing {} files in torrent {}",
                request.getFileIds().size(), torrentId);

        MessageResponse response = fileService.deprioritizeFiles(torrentId, request.getFileIds());
        return ResponseEntity.ok(response);
    }

    /**
     * Reset all file priorities to normal.
     *
     * @param torrentId torrent ID
     * @return success message
     */
    @PostMapping("/reset-priorities")
    @Operation(summary = "Reset priorities", description = "Reset all file priorities to normal")
    public ResponseEntity<MessageResponse> resetFilePriorities(
            @Parameter(description = "Torrent ID") @PathVariable Long torrentId) {

        log.info("REST: Resetting file priorities for torrent {}", torrentId);

        MessageResponse response = fileService.resetFilePriorities(torrentId);
        return ResponseEntity.ok(response);
    }

    /**
     * Skip files by extension.
     *
     * @param torrentId torrent ID
     * @param extension file extension (without dot)
     * @return success message
     */
    @PostMapping("/skip-by-extension")
    @Operation(summary = "Skip by extension", description = "Skip all files with a specific extension")
    public ResponseEntity<MessageResponse> skipFilesByExtension(
            @Parameter(description = "Torrent ID") @PathVariable Long torrentId,
            @Parameter(description = "File extension") @RequestParam String extension) {

        log.info("REST: Skipping files with extension '{}' in torrent {}", extension, torrentId);

        MessageResponse response = fileService.skipFilesByExtension(torrentId, extension);
        return ResponseEntity.ok(response);
    }

    /**
     * Get files by pattern.
     *
     * @param torrentId torrent ID
     * @param pattern search pattern
     * @return list of matching files
     */
    @GetMapping("/search")
    @Operation(summary = "Search files", description = "Search files by name pattern")
    public ResponseEntity<List<TorrentFileResponse>> searchFiles(
            @Parameter(description = "Torrent ID") @PathVariable Long torrentId,
            @Parameter(description = "Search pattern") @RequestParam String pattern) {

        log.debug("REST: Searching files with pattern '{}' in torrent {}", pattern, torrentId);

        List<TorrentFileResponse> files = fileService.getFilesByPattern(torrentId, pattern);
        return ResponseEntity.ok(files);
    }
}
