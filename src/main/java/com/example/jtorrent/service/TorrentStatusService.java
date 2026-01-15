package com.example.jtorrent.service;

import com.example.jtorrent.model.Torrent;
import com.example.jtorrent.model.TorrentFile;
import com.example.jtorrent.model.TorrentStatus;
import com.example.jtorrent.repository.TorrentFileRepository;
import com.example.jtorrent.repository.TorrentRepository;
import com.frostwire.jlibtorrent.FileStorage;
import com.frostwire.jlibtorrent.TorrentFlags;
import com.frostwire.jlibtorrent.TorrentHandle;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Service for monitoring and updating torrent status.
 * Runs periodic tasks to sync database state with actual torrent engine state.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TorrentStatusService {

    private final TorrentSessionManager sessionManager;
    private final TorrentRepository torrentRepository;
    private final TorrentWebSocketService webSocketService;
    private final TorrentFileRepository torrentFileRepository;

    /**
     * Update all active torrents' status from the torrent engine.
     * Runs every 5 seconds.
     */
    @Scheduled(fixedRate = 5000)
    @Transactional
    public void updateAllTorrentStatus() {
        if (!sessionManager.isRunning()) {
            return;
        }

        try {
            List<Torrent> activeTorrents = torrentRepository.findAllActive();

            for (Torrent torrent : activeTorrents) {
                updateTorrentStatus(torrent);
            }

            log.debug("Updated status for {} active torrents", activeTorrents.size());

        } catch (Exception e) {
            log.error("Error in scheduled status update", e);
        }
    }

    /**
     * Update a single torrent's status from the engine.
     */
    @Transactional
    public void updateTorrentStatus(Torrent torrent) {
        try {
            TorrentHandle handle = sessionManager.findTorrent(torrent.getInfoHash());

            if (handle == null) {
                // Torrent not in session anymore
                if (torrent.getStatus() != TorrentStatus.COMPLETED &&
                        torrent.getStatus() != TorrentStatus.STOPPED) {
                    torrent.setStatus(TorrentStatus.ERROR);
                    torrent.setErrorMessage("Torrent handle not found in session");
                    torrentRepository.save(torrent);
                }
                return;
            }

            com.frostwire.jlibtorrent.TorrentStatus status = handle.status();

            // Update basic stats
            torrent.setDownloadedSize(status.totalDone());
            torrent.setUploadedSize(status.allTimeUpload());
            torrent.setProgress(status.progress() * 100.0);
            torrent.setDownloadSpeed(status.downloadRate());
            torrent.setUploadSpeed(status.uploadRate());
            torrent.setPeers(status.numPeers());
            torrent.setSeeds(status.numSeeds());

            // Update total size if it was unknown (common with magnets)
            if (torrent.getTotalSize() == null || torrent.getTotalSize() == 0) {
                torrent.setTotalSize(status.totalWanted());
            }

            // Update status
            TorrentStatus oldStatus = torrent.getStatus();
            TorrentStatus newStatus = determineStatus(status);

            if (oldStatus != newStatus) {
                log.info("Torrent {} status changed: {} -> {}",
                        torrent.getName(), oldStatus, newStatus);

                torrent.setStatus(newStatus);

                // Mark completion time
                if (newStatus == TorrentStatus.COMPLETED || newStatus == TorrentStatus.SEEDING) {
                    if (torrent.getCompletedDate() == null) {
                        torrent.setCompletedDate(LocalDateTime.now());
                        log.info("Torrent completed: {}", torrent.getName());
                        webSocketService.notifyTorrentCompleted(torrent.getId(), torrent.getName());
                    }
                }
            }

            // Update error message if any
            if (status.errorCode().isError()) {
                String errorMsg = status.errorCode().message();
                if (!errorMsg.equals(torrent.getErrorMessage())) {
                    torrent.setErrorMessage(errorMsg);
                    log.warn("Torrent {} error: {}", torrent.getName(), errorMsg);
                }
            } else {
                torrent.setErrorMessage(null);
            }

            // Update file progress
            updateFileProgress(torrent, handle);

            torrentRepository.save(torrent);
        } catch (Exception e) {
            log.error("Error updating status for torrent {}: {}",
                    torrent.getId(), e.getMessage());
            // Don't crash, continue
        }
    }

    /**
     * Determine torrent status from libtorrent status.
     */
    private TorrentStatus determineStatus(com.frostwire.jlibtorrent.TorrentStatus status) {
        if (status.errorCode().isError()) {
            return TorrentStatus.ERROR;
        }

        if (status.flags().and_(TorrentFlags.PAUSED).nonZero()) {
            return TorrentStatus.PAUSED;
        }

        if (status.isFinished()) {
            if (status.isSeeding()) {
                return TorrentStatus.SEEDING;
            }
            return TorrentStatus.COMPLETED;
        }

        return switch (status.state()) {
            case CHECKING_FILES, CHECKING_RESUME_DATA -> TorrentStatus.CHECKING;
            case DOWNLOADING, DOWNLOADING_METADATA -> TorrentStatus.DOWNLOADING;
            default -> TorrentStatus.PENDING;
        };
    }

    /**
     * Update individual file progress for a torrent.
     */
    private void updateFileProgress(Torrent torrent, TorrentHandle handle) {
        try {
            com.frostwire.jlibtorrent.TorrentInfo torrentInfo = handle.torrentFile();
            if (torrentInfo == null) {
                return; // Metadata not yet available
            }

            FileStorage files = torrentInfo.files();
            int numFiles = files.numFiles();

            // Get file progress - try simple call first
            long[] fileProgress;
            try {
                // Try with PIECE_GRANULARITY if available
                fileProgress = handle.fileProgress();
            } catch (Exception e) {
                log.trace("Could not get file progress", e);
                return;
            }

            List<TorrentFile> dbFiles = torrent.getFiles();

            if (dbFiles.isEmpty())
                return;

            // CRITICAL: Create map of path -> TorrentFile for correct matching
            // Database files may be in different order than jlibtorrent files!
            Map<String, TorrentFile> fileMap = new HashMap<>();
            for (TorrentFile dbFile : dbFiles)
                fileMap.put(dbFile.getPath(), dbFile);

            // Match files by PATH, not by position in list
            for (int i = 0; i < numFiles && i < fileProgress.length; i++) {
                String filePath = files.filePath(i);
                long downloaded = fileProgress[i];
                long fileSize = files.fileSize(i);

                // Find the database file by path
                TorrentFile dbFile = fileMap.get(filePath);
                if (dbFile == null) {
                    log.warn("File in torrent not found in DB: {} (torrent {})", filePath, torrent.getId());
                    continue;
                }

                // CRITICAL: If file is skipped (priority 0), force progress to 0
                // jlibtorrent might report some progress due to piece boundaries
                if (dbFile.getPriority() != null && dbFile.getPriority() == 0) {
                    dbFile.setDownloadedSize(0L);
                    dbFile.setProgress(0.0);
                    continue; // Skip further processing for this file
                }

                dbFile.setDownloadedSize(downloaded);
                dbFile.setSize(fileSize);

                if (fileSize > 0) {
                    double progress = (downloaded * 100.0) / fileSize;
                    dbFile.setProgress(Math.min(progress, 100.0));
                } else {
                    dbFile.setProgress(0.0);
                }
            }

        } catch (Exception e) {
            log.error("Error updating file progress for torrent {}: {}",
                    torrent.getId(), e.getMessage());
        }
    }

    /**
     * Force update a specific torrent by ID.
     */
    @Transactional
    public void forceUpdateTorrent(Long torrentId) {
        Torrent torrent = torrentRepository.findById(torrentId)
                .orElseThrow(() -> new RuntimeException("Torrent not found: " + torrentId));

        updateTorrentStatus(torrent);
        log.info("Forced status update for torrent: {}", torrent.getName());
    }

    /**
     * Clean up stalled or dead torrents.
     * Runs every hour.
     */
    @Scheduled(fixedRate = 3600000)
    @Transactional
    public void cleanupStalledTorrents() {
        try {
            // First, cleanup invalid handles from cache
            sessionManager.cleanupInvalidHandles();

            // Then check for stalled torrents
            List<Torrent> stalledTorrents = torrentRepository.findStalledTorrents();

            for (Torrent torrent : stalledTorrents) {
                log.warn("Detected stalled torrent: {} ({})", torrent.getName(), torrent.getId());

                try {
                    TorrentHandle handle = sessionManager.findTorrent(torrent.getInfoHash());
                    if (handle == null) {
                        torrent.setStatus(TorrentStatus.ERROR);
                        torrent.setErrorMessage("Torrent handle lost, possibly stalled");
                        torrentRepository.save(torrent);
                    }
                } catch (Exception e) {
                    log.error("Error checking stalled torrent {}: {}", torrent.getId(), e.getMessage());
                }
            }

            if (!stalledTorrents.isEmpty()) {
                log.info("Cleaned up {} stalled torrents", stalledTorrents.size());
            }

        } catch (Exception e) {
            log.error("Error during stalled torrent cleanup", e);
        }
    }

    /**
     * Sync database with active session torrents.
     * This ensures consistency between the database and the torrent engine.
     * Runs every 30 seconds.
     */
    @Scheduled(fixedRate = 30000)
    @Transactional
    public void syncWithSession() {
        if (!sessionManager.isRunning()) {
            return;
        }

        try {
            Map<String, TorrentHandle> activeTorrents = sessionManager.getActiveTorrents();

            // Check for torrents in DB but not in session
            List<Torrent> dbTorrents = torrentRepository.findByStatusIn(
                    List.of(TorrentStatus.DOWNLOADING, TorrentStatus.CHECKING, TorrentStatus.SEEDING));

            for (Torrent dbTorrent : dbTorrents) {
                if (!activeTorrents.containsKey(dbTorrent.getInfoHash())) {
                    log.warn("Torrent in DB but not in session: {} ({})",
                            dbTorrent.getName(), dbTorrent.getId());

                    try {
                        // Try to re-add or mark as error
                        TorrentHandle handle = sessionManager.findTorrent(dbTorrent.getInfoHash());
                        if (handle == null) {
                            dbTorrent.setStatus(TorrentStatus.ERROR);
                            dbTorrent.setErrorMessage("Lost from session, requires restart");
                            torrentRepository.save(dbTorrent);
                        }
                    } catch (Exception e) {
                        log.error("Error checking torrent {}: {}", dbTorrent.getId(), e.getMessage());
                    }
                }
            }

            log.trace("Session sync completed: {} active torrents", activeTorrents.size());

        } catch (Exception e) {
            log.error("Error during session sync", e);
        }
    }

    /**
     * Get real-time status for a torrent without persisting to DB.
     */
    public com.frostwire.jlibtorrent.TorrentStatus getRealTimeStatus(String infoHash) {
        try {
            TorrentHandle handle = sessionManager.findTorrent(infoHash);
            if (handle != null) {
                return handle.status();
            }
        } catch (Exception e) {
            log.error("Error getting real-time status for {}: {}", infoHash, e.getMessage());
        }
        return null;
    }

    /**
     * Check if a torrent is currently active in the session.
     */
    public boolean isTorrentActive(String infoHash) {
        try {
            TorrentHandle handle = sessionManager.findTorrent(infoHash);
            return handle != null;
        } catch (Exception e) {
            log.error("Error checking if torrent active {}: {}", infoHash, e.getMessage());
            return false;
        }
    }

    /**
     * Get current download/upload speeds for all active torrents.
     */
    public Map<String, Integer[]> getAllSpeeds() {
        Map<String, Integer[]> speeds = new java.util.HashMap<>();

        try {
            List<Torrent> activeTorrents = torrentRepository.findAllActive();

            for (Torrent torrent : activeTorrents) {
                try {
                    TorrentHandle handle = sessionManager.findTorrent(torrent.getInfoHash());
                    if (handle != null) {
                        com.frostwire.jlibtorrent.TorrentStatus status = handle.status();
                        speeds.put(torrent.getInfoHash(), new Integer[]{
                                status.downloadRate(),
                                status.uploadRate()
                        });
                    }
                } catch (Exception e) {
                    log.trace("Error getting speeds for torrent {}: {}",
                            torrent.getId(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Error getting all speeds: {}", e.getMessage());
        }

        return speeds;
    }
}