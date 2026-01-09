package com.example.jtorrent.controller;

import com.example.jtorrent.dto.TorrentFilterRequest;
import com.example.jtorrent.dto.TorrentResponse;
import com.example.jtorrent.mapper.TorrentMapper;
import com.example.jtorrent.model.Torrent;
import com.example.jtorrent.model.TorrentStatus;
import com.example.jtorrent.repository.TorrentRepository;
import com.example.jtorrent.repository.specification.TorrentSpecification;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST Controller for searching and filtering torrents.
 * Provides advanced filtering, sorting, and pagination capabilities.
 */
@RestController
@RequestMapping("/api/torrents/search")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Torrent Search", description = "APIs for searching and filtering torrents")
public class TorrentSearchController {

    private final TorrentRepository torrentRepository;
    private final TorrentMapper torrentMapper;

    /**
     * Search and filter torrents with pagination.
     *
     * @param filterRequest search criteria
     * @return paginated list of torrents
     */
    @PostMapping
    @Operation(summary = "Search torrents",
            description = "Search and filter torrents with pagination and sorting")
    public ResponseEntity<Page<TorrentResponse>> searchTorrents(
            @RequestBody TorrentFilterRequest filterRequest) {

        log.debug("REST: Searching torrents with filter: {}", filterRequest);

        // Build specification from filter
        Specification<Torrent> spec = buildSpecification(filterRequest);

        // Build pageable with sorting
        Pageable pageable = buildPageable(filterRequest);

        // Execute query
        Page<Torrent> torrentsPage = torrentRepository.findAll(spec, pageable);

        // Map to response DTOs
        Page<TorrentResponse> responsePage = torrentsPage.map(torrentMapper::toResponse);

        return ResponseEntity.ok(responsePage);
    }

    /**
     * Get torrents by status.
     *
     * @param status torrent status
     * @return list of torrents with specified status
     */
    @GetMapping("/status/{status}")
    @Operation(summary = "Get by status", description = "Get all torrents with a specific status")
    public ResponseEntity<List<TorrentResponse>> getTorrentsByStatus(
            @Parameter(description = "Torrent status") @PathVariable TorrentStatus status) {

        log.debug("REST: Getting torrents with status: {}", status);

        List<Torrent> torrents = torrentRepository.findByStatus(status);
        List<TorrentResponse> responses = torrents.stream()
                .map(torrentMapper::toResponse)
                .toList();

        return ResponseEntity.ok(responses);
    }

    /**
     * Search torrents by name.
     *
     * @param name search term
     * @return list of matching torrents
     */
    @GetMapping("/name")
    @Operation(summary = "Search by name", description = "Search torrents by name (case-insensitive)")
    public ResponseEntity<List<TorrentResponse>> searchByName(
            @Parameter(description = "Search term") @RequestParam String name) {

        log.debug("REST: Searching torrents by name: {}", name);

        List<Torrent> torrents = torrentRepository.findByNameContainingIgnoreCase(name);
        List<TorrentResponse> responses = torrents.stream()
                .map(torrentMapper::toResponse)
                .toList();

        return ResponseEntity.ok(responses);
    }

    /**
     * Get active torrents (downloading or seeding).
     *
     * @return list of active torrents
     */
    @GetMapping("/active")
    @Operation(summary = "Get active torrents", description = "Get all currently active torrents")
    public ResponseEntity<List<TorrentResponse>> getActiveTorrents() {
        log.debug("REST: Getting active torrents");

        List<Torrent> torrents = torrentRepository.findAllActive();
        List<TorrentResponse> responses = torrents.stream()
                .map(torrentMapper::toResponse)
                .toList();

        return ResponseEntity.ok(responses);
    }

    /**
     * Get completed torrents.
     *
     * @return list of completed torrents
     */
    @GetMapping("/completed")
    @Operation(summary = "Get completed torrents", description = "Get all completed torrents")
    public ResponseEntity<List<TorrentResponse>> getCompletedTorrents() {
        log.debug("REST: Getting completed torrents");

        List<Torrent> torrents = torrentRepository.findByStatusAndCompletedDateIsNotNull(
                TorrentStatus.COMPLETED);
        List<TorrentResponse> responses = torrents.stream()
                .map(torrentMapper::toResponse)
                .toList();

        return ResponseEntity.ok(responses);
    }

    /**
     * Get torrents with errors.
     *
     * @return list of torrents with errors
     */
    @GetMapping("/errors")
    @Operation(summary = "Get error torrents", description = "Get all torrents with errors")
    public ResponseEntity<List<TorrentResponse>> getErrorTorrents() {
        log.debug("REST: Getting torrents with errors");

        List<Torrent> torrents = torrentRepository.findByStatusAndErrorMessageIsNotNull(
                TorrentStatus.ERROR);
        List<TorrentResponse> responses = torrents.stream()
                .map(torrentMapper::toResponse)
                .toList();

        return ResponseEntity.ok(responses);
    }

    /**
     * Get torrents by progress range.
     *
     * @param minProgress minimum progress (0-100)
     * @param maxProgress maximum progress (0-100)
     * @return list of torrents within progress range
     */
    @GetMapping("/progress")
    @Operation(summary = "Get by progress", description = "Get torrents within a progress range")
    public ResponseEntity<List<TorrentResponse>> getTorrentsByProgress(
            @Parameter(description = "Minimum progress") @RequestParam Double minProgress,
            @Parameter(description = "Maximum progress") @RequestParam Double maxProgress) {

        log.debug("REST: Getting torrents with progress {}-{}", minProgress, maxProgress);

        List<Torrent> torrents = torrentRepository.findByProgressBetween(minProgress, maxProgress);
        List<TorrentResponse> responses = torrents.stream()
                .map(torrentMapper::toResponse)
                .toList();

        return ResponseEntity.ok(responses);
    }

    /**
     * Get stalled torrents.
     *
     * @return list of stalled torrents
     */
    @GetMapping("/stalled")
    @Operation(summary = "Get stalled torrents",
            description = "Get torrents that are downloading but have no peers/speed")
    public ResponseEntity<List<TorrentResponse>> getStalledTorrents() {
        log.debug("REST: Getting stalled torrents");

        List<Torrent> torrents = torrentRepository.findStalledTorrents();
        List<TorrentResponse> responses = torrents.stream()
                .map(torrentMapper::toResponse)
                .toList();

        return ResponseEntity.ok(responses);
    }

    /**
     * Count torrents by status.
     *
     * @param status torrent status
     * @return count of torrents
     */
    @GetMapping("/count/{status}")
    @Operation(summary = "Count by status", description = "Count torrents with a specific status")
    public ResponseEntity<Long> countTorrentsByStatus(
            @Parameter(description = "Torrent status") @PathVariable TorrentStatus status) {

        log.debug("REST: Counting torrents with status: {}", status);

        long count = torrentRepository.countByStatus(status);
        return ResponseEntity.ok(count);
    }

    /**
     * Build JPA Specification from filter request.
     */
    private Specification<Torrent> buildSpecification(TorrentFilterRequest filter) {
        Specification<Torrent> spec = Specification.where((Specification<Torrent>) null);

        // Status filter
        if (filter.getStatus() != null) {
            spec = spec.and(TorrentSpecification.hasStatus(filter.getStatus()));
        }

        // Multiple statuses
        if (filter.getStatuses() != null && !filter.getStatuses().isEmpty()) {
            spec = spec.and(TorrentSpecification.hasStatusIn(filter.getStatuses()));
        }

        // Name filter
        if (filter.getName() != null && !filter.getName().isEmpty()) {
            spec = spec.and(TorrentSpecification.nameContains(filter.getName()));
        }

        // Progress range
        if (filter.getMinProgress() != null || filter.getMaxProgress() != null) {
            spec = spec.and(TorrentSpecification.progressBetween(
                    filter.getMinProgress(), filter.getMaxProgress()));
        }

        // Date range
        if (filter.getStartDate() != null || filter.getEndDate() != null) {
            spec = spec.and(TorrentSpecification.addedBetween(
                    filter.getStartDate(), filter.getEndDate()));
        }

        // Size range
        if (filter.getMinSize() != null || filter.getMaxSize() != null) {
            spec = spec.and(TorrentSpecification.sizeBetween(
                    filter.getMinSize(), filter.getMaxSize()));
        }

        // Has errors
        if (Boolean.TRUE.equals(filter.getHasErrors())) {
            spec = spec.and(TorrentSpecification.hasErrors());
        }

        return spec;
    }

    /**
     * Build Pageable from filter request.
     */
    private Pageable buildPageable(TorrentFilterRequest filter) {
        // Determine sort direction
        Sort.Direction direction = "ASC".equalsIgnoreCase(filter.getSortDirection())
                ? Sort.Direction.ASC
                : Sort.Direction.DESC;

        // Create sort
        Sort sort = Sort.by(direction, filter.getSortBy());

        // Create pageable
        return PageRequest.of(filter.getPage(), filter.getSize(), sort);
    }
}
