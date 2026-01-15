package com.example.jtorrent.service;

import com.example.jtorrent.dto.TorrentResponse;
import com.example.jtorrent.dto.TorrentStatsDTO;
import com.example.jtorrent.mapper.TorrentMapper;
import com.example.jtorrent.model.Torrent;
import com.example.jtorrent.repository.TorrentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * WebSocket service for broadcasting real-time torrent updates to connected clients.
 *
 * This service runs every 2 seconds and sends:
 * - Individual torrent updates to /topic/torrents/{id}
 * - List of all torrents to /topic/torrents
 * - Overall statistics to /topic/stats
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TorrentWebSocketService {

    private final SimpMessagingTemplate messagingTemplate;
    private final TorrentRepository torrentRepository;
    private final TorrentMapper torrentMapper;
    private final TorrentStatisticsService statisticsService;

    /**
     * Broadcast torrent updates to all connected clients.
     * Runs every 2 seconds.
     */
    @Scheduled(fixedRate = 2000)
    @Transactional(readOnly = true)
    public void broadcastTorrentUpdates() {
        try {
            // Get all active torrents (downloading, seeding, checking)
            List<Torrent> activeTorrents = torrentRepository.findAllActive();

            // Send individual updates for each torrent
            for (Torrent torrent : activeTorrents) {
                TorrentResponse response = torrentMapper.toResponse(torrent);

                // Send to /topic/torrents/{id} for clients watching specific torrent
                String destination = "/topic/torrents/" + torrent.getId();
                messagingTemplate.convertAndSend(destination, response);
            }

            // Get ALL torrents (including paused, completed, etc.)
            List<Torrent> allTorrents = torrentRepository.findAllByOrderByAddedDateDesc();
            List<TorrentResponse> allResponses = allTorrents.stream()
                    .map(torrentMapper::toResponse)
                    .toList();

            // Send complete list to /topic/torrents for dashboard
            messagingTemplate.convertAndSend("/topic/torrents", allResponses);

            log.trace("Broadcasted {} torrent updates via WebSocket", allTorrents.size());
        } catch (Exception e) {
            log.error("Error broadcasting torrent updates: {}", e.getMessage(), e);
        }
    }

    /**
     * Broadcast overall statistics.
     * Runs every 5 seconds (less frequent than torrent updates).
     */
    @Scheduled(fixedRate = 5000)
    @Transactional(readOnly = true)
    public void broadcastStatistics() {
        try {
            TorrentStatsDTO stats = statisticsService.getOverallStatistics();

            // Send to /topic/stats
            messagingTemplate.convertAndSend("/topic/stats", stats);

            log.trace("Broadcasted statistics via WebSocket");

        } catch (Exception e) {
            log.error("Error broadcasting statistics: {}", e.getMessage(), e);
        }
    }

    /**
     * Send a specific torrent update (useful for immediate updates).
     * Call this when a torrent is added, removed, or has a major status change.
     */
    @Transactional(readOnly = true)
    public void sendTorrentUpdate(Long torrentId) {
        try {
            Torrent torrent = torrentRepository.findById(torrentId).orElse(null);
            if (torrent == null) {
                log.warn("Cannot send WebSocket update for non-existent torrent: {}", torrentId);
                return;
            }

            TorrentResponse response = torrentMapper.toResponse(torrent);

            // Send to specific torrent topic
            messagingTemplate.convertAndSend("/topic/torrents/" + torrentId, response);

            log.debug("Sent immediate update for torrent {} via WebSocket", torrentId);

        } catch (Exception e) {
            log.error("Error sending WebSocket update for torrent {}: {}", torrentId, e.getMessage(), e);
        }
    }

    /**
     * Notify clients that a torrent was added.
     */
    public void notifyTorrentAdded(Long torrentId) {
        try {
            // Create notification object (NOT a Map to avoid ambiguous method call)
            NotificationMessage notification = new NotificationMessage(
                    "torrent_added",
                    torrentId,
                    "New torrent added",
                    null
            );

            messagingTemplate.convertAndSend("/topic/notifications", notification);

            // Also send the torrent data
            sendTorrentUpdate(torrentId);

        } catch (Exception e) {
            log.error("Error notifying torrent added: {}", e.getMessage());
        }
    }

    /**
     * Notify clients that a torrent was removed.
     */
    public void notifyTorrentRemoved(Long torrentId) {
        try {
            NotificationMessage notification = new NotificationMessage(
                    "torrent_removed",
                    torrentId,
                    "Torrent removed",
                    null
            );

            messagingTemplate.convertAndSend("/topic/notifications", notification);

        } catch (Exception e) {
            log.error("Error notifying torrent removed: {}", e.getMessage());
        }
    }

    /**
     * Notify clients about torrent completion.
     */
    public void notifyTorrentCompleted(Long torrentId, String torrentName) {
        try {
            NotificationMessage notification = new NotificationMessage(
                    "torrent_completed",
                    torrentId,
                    "Download completed: " + torrentName,
                    torrentName
            );

            messagingTemplate.convertAndSend("/topic/notifications", notification);

        } catch (Exception e) {
            log.error("Error notifying torrent completed: {}", e.getMessage());
        }
    }

    /**
     * Simple notification message class to avoid ambiguous method calls.
     * Using a proper class instead of Map<String, Object> makes the
     * convertAndSend method signature clear.
     *
     * @param event Fields are private; Lombok generates getters for JSON serialization
     */
        public record NotificationMessage(String event, Long torrentId, String message, String torrentName) {

    }
}
