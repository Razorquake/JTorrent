package com.example.jtorrent.controller;

import com.example.jtorrent.dto.AddTorrentFileResponse;
import com.example.jtorrent.dto.AddTorrentRequest;
import com.example.jtorrent.dto.MessageResponse;
import com.example.jtorrent.dto.TorrentResponse;
import com.example.jtorrent.service.TorrentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST Controller for torrent management operations.
 * Provides endpoints for adding, removing, and controlling torrents.
 */
@RestController
@RequestMapping("/api/torrents")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Torrent Management", description = "APIs for managing torrents")
public class TorrentController {

    private final TorrentService torrentService;

    /**
     * Add a new torrent from magnet link or .torrent file.
     *
     * @param request contains magnet link/file path and options
     * @return torrent information
     */
    @PostMapping
    @Operation(summary = "Add torrent", description = "Add a torrent from magnet link or .torrent file")
    public ResponseEntity<AddTorrentFileResponse> addTorrent(
            @Valid @RequestBody AddTorrentRequest request) {

        log.info("REST: Adding torrent: {}", request.getMagnetLink());

        AddTorrentFileResponse response = torrentService.addTorrent(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Get all torrents.
     *
     * @return list of all torrents
     */
    @GetMapping
    @Operation(summary = "Get all torrents", description = "Retrieve all torrents with their current status")
    public ResponseEntity<List<TorrentResponse>> getAllTorrents() {
        log.debug("REST: Getting all torrents");

        List<TorrentResponse> torrents = torrentService.getAllTorrents();
        return ResponseEntity.ok(torrents);
    }

    /**
     * Get a specific torrent by ID.
     *
     * @param id torrent ID
     * @return torrent details
     */
    @GetMapping("/{id}")
    @Operation(summary = "Get torrent", description = "Get detailed information about a specific torrent")
    public ResponseEntity<TorrentResponse> getTorrent(
            @Parameter(description = "Torrent ID") @PathVariable Long id) {

        log.debug("REST: Getting torrent {}", id);

        TorrentResponse torrent = torrentService.getTorrent(id);
        return ResponseEntity.ok(torrent);
    }

    /**
     * Start/resume a torrent.
     *
     * @param id torrent ID
     * @return success message
     */
    @PostMapping("/{id}/start")
    @Operation(summary = "Start torrent", description = "Start or resume downloading a torrent")
    public ResponseEntity<MessageResponse> startTorrent(
            @Parameter(description = "Torrent ID") @PathVariable Long id) {

        log.info("REST: Starting torrent {}", id);

        MessageResponse response = torrentService.startTorrent(id);
        return ResponseEntity.ok(response);
    }

    /**
     * Pause a torrent.
     *
     * @param id torrent ID
     * @return success message
     */
    @PostMapping("/{id}/pause")
    @Operation(summary = "Pause torrent", description = "Pause a downloading or seeding torrent")
    public ResponseEntity<MessageResponse> pauseTorrent(
            @Parameter(description = "Torrent ID") @PathVariable Long id) {

        log.info("REST: Pausing torrent {}", id);

        MessageResponse response = torrentService.pauseTorrent(id);
        return ResponseEntity.ok(response);
    }

    /**
     * Remove a torrent.
     *
     * @param id torrent ID
     * @param deleteFiles whether to delete downloaded files
     * @return success message
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "Remove torrent", description = "Remove a torrent, optionally deleting downloaded files")
    public ResponseEntity<MessageResponse> removeTorrent(
            @Parameter(description = "Torrent ID") @PathVariable Long id,
            @Parameter(description = "Delete downloaded files")
            @RequestParam(defaultValue = "false") boolean deleteFiles) {

        log.info("REST: Removing torrent {}, deleteFiles={}", id, deleteFiles);

        MessageResponse response = torrentService.removeTorrent(id, deleteFiles);
        return ResponseEntity.ok(response);
    }

    /**
     * Get torrent by info hash.
     *
     * @param infoHash torrent info hash
     * @return torrent details
     */
    @GetMapping("/hash/{infoHash}")
    @Operation(summary = "Get torrent by hash", description = "Find torrent by its info hash")
    public ResponseEntity<TorrentResponse> getTorrentByHash(
            @Parameter(description = "Torrent info hash") @PathVariable String infoHash) {

        log.debug("REST: Getting torrent by hash: {}", infoHash);

        // This would need a new method in TorrentService
        // For now, return 501 Not Implemented
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }

    /**
     * Force recheck of torrent files.
     *
     * @param id torrent ID
     * @return success message
     */
    @PostMapping("/{id}/recheck")
    @Operation(summary = "Recheck torrent", description = "Force recheck of all downloaded pieces")
    public ResponseEntity<MessageResponse> recheckTorrent(
            @Parameter(description = "Torrent ID") @PathVariable Long id) {

        log.info("REST: Rechecking torrent {}", id);

        // This would need implementation in TorrentService
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }

    /**
     * Force reannounce to trackers.
     *
     * @param id torrent ID
     * @return success message
     */
    @PostMapping("/{id}/reannounce")
    @Operation(summary = "Reannounce torrent", description = "Force reannounce to all trackers")
    public ResponseEntity<MessageResponse> reannounceTorrent(
            @Parameter(description = "Torrent ID") @PathVariable Long id) {

        log.info("REST: Reannouncing torrent {}", id);

        // This would need implementation in TorrentService
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }
}
