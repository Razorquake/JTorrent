package com.example.jtorrent.repository.specification;

import com.example.jtorrent.model.Torrent;
import com.example.jtorrent.model.TorrentStatus;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Specification class for building dynamic queries for Torrent entity.
 * Useful for complex filtering in search/filter operations.
 */
public class TorrentSpecification {

    // Filter by status
    public static Specification<Torrent> hasStatus(TorrentStatus status) {
        return (root, query, criteriaBuilder) ->
                status == null ? null : criteriaBuilder.equal(root.get("status"), status);
    }

    // Filter by multiple statuses
    public static Specification<Torrent> hasStatusIn(List<TorrentStatus> statuses) {
        return (root, query, criteriaBuilder) ->
                statuses == null || statuses.isEmpty() ? null : root.get("status").in(statuses);
    }

    // Filter by name containing (case-insensitive)
    public static Specification<Torrent> nameContains(String name) {
        return (root, query, criteriaBuilder) ->
                name == null ? null : criteriaBuilder.like(
                        criteriaBuilder.lower(root.get("name")),
                        "%" + name.toLowerCase() + "%"
                );
    }

    // Filter by progress range
    public static Specification<Torrent> progressBetween(Double minProgress, Double maxProgress) {
        return (root, query, criteriaBuilder) -> {
            if (minProgress == null && maxProgress == null) return null;
            if (minProgress == null) {
                return criteriaBuilder.lessThanOrEqualTo(root.get("progress"), maxProgress);
            }
            if (maxProgress == null) {
                return criteriaBuilder.greaterThanOrEqualTo(root.get("progress"), minProgress);
            }
            return criteriaBuilder.between(root.get("progress"), minProgress, maxProgress);
        };
    }

    // Filter by date range
    public static Specification<Torrent> addedBetween(LocalDateTime startDate, LocalDateTime endDate) {
        return (root, query, criteriaBuilder) -> {
            if (startDate == null && endDate == null) return null;
            if (startDate == null) {
                return criteriaBuilder.lessThanOrEqualTo(root.get("addedDate"), endDate);
            }
            if (endDate == null) {
                return criteriaBuilder.greaterThanOrEqualTo(root.get("addedDate"), startDate);
            }
            return criteriaBuilder.between(root.get("addedDate"), startDate, endDate);
        };
    }

    // Filter by size range (in bytes)
    public static Specification<Torrent> sizeBetween(Long minSize, Long maxSize) {
        return (root, query, criteriaBuilder) -> {
            if (minSize == null && maxSize == null) return null;
            if (minSize == null) {
                return criteriaBuilder.lessThanOrEqualTo(root.get("totalSize"), maxSize);
            }
            if (maxSize == null) {
                return criteriaBuilder.greaterThanOrEqualTo(root.get("totalSize"), minSize);
            }
            return criteriaBuilder.between(root.get("totalSize"), minSize, maxSize);
        };
    }

    // Filter by having errors
    public static Specification<Torrent> hasErrors() {
        return (root, query, criteriaBuilder) ->
                criteriaBuilder.isNotNull(root.get("errorMessage"));
    }

    // Filter by info hash
    public static Specification<Torrent> hasInfoHash(String infoHash) {
        return (root, query, criteriaBuilder) ->
                infoHash == null ? null : criteriaBuilder.equal(root.get("infoHash"), infoHash);
    }

    // Combine specifications with AND
    public static Specification<Torrent> filterTorrents(
            TorrentStatus status,
            String name,
            Double minProgress,
            Double maxProgress,
            LocalDateTime startDate,
            LocalDateTime endDate,
            Long minSize,
            Long maxSize
    ) {
        return Specification.where(hasStatus(status))
                .and(nameContains(name))
                .and(progressBetween(minProgress, maxProgress))
                .and(addedBetween(startDate, endDate))
                .and(sizeBetween(minSize, maxSize));
    }
}
