package com.example.jtorrent.service;

import com.example.jtorrent.dto.*;
import com.example.jtorrent.exception.TorrentExceptions.*;
import com.example.jtorrent.mapper.TorrentMapper;
import com.example.jtorrent.model.Torrent;
import com.example.jtorrent.model.TorrentFile;
import com.example.jtorrent.model.TorrentStatus;
import com.example.jtorrent.repository.DownloadStatisticsRepository;
import com.example.jtorrent.repository.TorrentFileRepository;
import com.example.jtorrent.repository.TorrentRepository;
import com.frostwire.jlibtorrent.*;
import com.frostwire.jlibtorrent.alerts.Alert;
import com.frostwire.jlibtorrent.alerts.AlertType;
import com.frostwire.jlibtorrent.alerts.MetadataReceivedAlert;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

/**
 * Main service for torrent operations including adding, removing, and managing torrents.
 * This service coordinates between the jlibtorrent engine and the database.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TorrentService {

    private final DownloadStatisticsRepository downloadStatisticsRepository;
    private final TorrentSessionManager sessionManager;
    private final TorrentRepository torrentRepository;
    private final TorrentFileRepository torrentFileRepository;
    private final TorrentMapper torrentMapper;

    /**
     * Add a torrent from a magnet link or .torrent file.
     *
     * @param request contains magnet link, save path, and start settings
     * @return response with torrent information
     */
    @Transactional
    public AddTorrentFileResponse addTorrent(AddTorrentRequest request) {
        log.info("Adding torrent from request: {}", request.getMagnetLink());

        try {
            String magnetLink = request.getMagnetLink().trim();

            // Validate input
            boolean isMagnet = magnetLink.startsWith("magnet:?");
            boolean isFile = !isMagnet && new File(magnetLink).exists();

            if (!isMagnet && !isFile) {
                throw new InvalidMagnetLinkException("Invalid magnet link or torrent file: " + magnetLink);
            }

            // Check concurrent download limit
            checkConcurrentDownloadLimit();

            // Determine save path
            File saveDir = determineSavePath(request.getSavePath());

            // Add torrent to session
            TorrentHandle handle;
            TorrentInfo torrentInfo;

            if (isMagnet) {
                // Download magnet - this method handles metadata fetching
                handle = addMagnetLink(magnetLink, saveDir);
                // Wait for metadata
                torrentInfo = waitForMetadata(handle);
            } else {
                // Load .torrent file
                torrentInfo = new TorrentInfo(new File(magnetLink));
                handle = addTorrentFile(torrentInfo, saveDir);
            }

            // Extract info hash
            String infoHash = handle.infoHash().toString();

            // Check if torrent already exists in database
            if (torrentRepository.existsByInfoHash(infoHash)) {
                throw new TorrentAlreadyExistsException(infoHash);
            }

            // IMPORTANT: Pause the torrent first to ensure it doesn't auto-start
            // Then we can control whether to start it based on startImmediately flag
            handle.unsetFlags(TorrentFlags.AUTO_MANAGED);
            handle.pause();

            // Create database entity
            Torrent torrent = createTorrentEntity(handle, torrentInfo, magnetLink, saveDir);

            // Start download if requested
            if (Boolean.TRUE.equals(request.getStartImmediately())) {
                log.info("Starting download immediately for: {}", torrent.getName());
                handle.setFlags(TorrentFlags.AUTO_MANAGED);
                handle.resume();
                torrent.setStatus(TorrentStatus.DOWNLOADING);
            } else {
                log.info("Torrent added in paused state: {}", torrent.getName());
                torrent.setStatus(TorrentStatus.PAUSED);
            }

            torrent = torrentRepository.save(torrent);

            log.info("Torrent added successfully: {} ({})", torrent.getName(), infoHash);

            // Build response
            return AddTorrentFileResponse.builder()
                    .torrentId(torrent.getId())
                    .infoHash(infoHash)
                    .name(torrent.getName())
                    .totalSize(torrent.getTotalSize())
                    .fileCount(torrent.getFiles().size())
                    .message("Torrent added successfully")
                    .started(request.getStartImmediately())
                    .build();

        } catch (TorrentAlreadyExistsException | InvalidMagnetLinkException | MaxConcurrentDownloadsException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error adding torrent", e);
            throw new TorrentFileException("Failed to add torrent: " + e.getMessage(), e);
        }
    }

    /**
     * Add a magnet link using SessionManager's fetchMagnet and download methods.
     */
    private TorrentHandle addMagnetLink(String magnetLink, File saveDir) {
        log.debug("Adding magnet link: {}", magnetLink);

        try {
            // Fetch magnet metadata (timeout 60 seconds)
            byte[] data = sessionManager.getSession().fetchMagnet(magnetLink, 60, saveDir);

            if (data == null || data.length == 0) {
                throw new TorrentFileException("Failed to fetch magnet metadata");
            }

            // Decode torrent info from metadata
            TorrentInfo torrentInfo = TorrentInfo.bdecode(data);

            // Download using the high-level API
            sessionManager.getSession().download(torrentInfo, saveDir);

            // Find the handle
            TorrentHandle handle = sessionManager.getSession().find(torrentInfo.infoHashV1());

            if (handle == null || !handle.isValid()) {
                throw new TorrentFileException("Failed to get torrent handle after adding");
            }

            return handle;

        } catch (Exception e) {
            log.error("Error adding magnet link", e);
            throw new TorrentFileException("Failed to add magnet link: " + e.getMessage(), e);
        }
    }

    /**
     * Add a torrent from .torrent file using SessionManager's download method.
     */
    private TorrentHandle addTorrentFile(TorrentInfo torrentInfo, File saveDir) {
        log.debug("Adding torrent file: {}", torrentInfo.name());

        try {
            // Use SessionManager's high-level download method
            sessionManager.getSession().download(torrentInfo, saveDir);

            // Find the handle
            TorrentHandle handle = sessionManager.getSession().find(torrentInfo.infoHashV1());

            if (handle == null || !handle.isValid()) {
                throw new TorrentFileException("Failed to get torrent handle after adding");
            }

            return handle;

        } catch (Exception e) {
            log.error("Error adding torrent file", e);
            throw new TorrentFileException("Failed to add torrent file: " + e.getMessage(), e);
        }
    }

    /**
     * Wait for metadata to be received for a magnet link.
     * This is necessary because magnet links don't initially contain file information.
     */
    private TorrentInfo waitForMetadata(TorrentHandle handle) {
        log.debug("Waiting for metadata...");

        CountDownLatch latch = new CountDownLatch(1);
        final TorrentInfo[] result = new TorrentInfo[1];

        // Add temporary listener for metadata
        AlertListener metadataListener = new AlertListener() {
            @Override
            public int[] types() {
                return new int[]{AlertType.METADATA_RECEIVED.swig()};
            }

            @Override
            public void alert(Alert<?> alert) {
                if (alert.type() == AlertType.METADATA_RECEIVED) {
                    MetadataReceivedAlert metadataAlert = (MetadataReceivedAlert) alert;
                    if (metadataAlert.handle().infoHash().equals(handle.infoHash())) {
                        result[0] = handle.torrentFile();
                        latch.countDown();
                    }
                }
            }
        };

        try {
            sessionManager.getSession().addListener(metadataListener);

            // Check if we already have metadata
            if (handle.torrentFile() != null) {
                return handle.torrentFile();
            }

            // Wait up to 60 seconds for metadata
            if (!latch.await(60, TimeUnit.SECONDS)) {
                throw new TorrentFileException("Timeout waiting for metadata");
            }

            if (result[0] == null) {
                throw new TorrentFileException("Failed to receive metadata");
            }

            return result[0];

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TorrentFileException("Interrupted while waiting for metadata", e);
        } finally {
            sessionManager.getSession().removeListener(metadataListener);
        }
    }

    /**
     * Create a Torrent entity from TorrentHandle and TorrentInfo.
     */
    private Torrent createTorrentEntity(TorrentHandle handle, TorrentInfo torrentInfo,
                                        String magnetLink, File saveDir) {
        Torrent torrent = new Torrent();

        // Basic information
        torrent.setInfoHash(handle.infoHash().toString());
        torrent.setName(torrentInfo.name());
        torrent.setMagnetLink(magnetLink);
        torrent.setTotalSize(torrentInfo.totalSize());
        torrent.setSavePath(saveDir.getAbsolutePath());
        torrent.setStatus(TorrentStatus.PENDING);
        torrent.setProgress(0.0);
        torrent.setDownloadedSize(0L);
        torrent.setUploadedSize(0L);
        torrent.setDownloadSpeed(0);
        torrent.setUploadSpeed(0);
        torrent.setPeers(0);
        torrent.setSeeds(0);

        // Metadata
        if (torrentInfo.comment() != null && !torrentInfo.comment().isEmpty()) {
            torrent.setComment(torrentInfo.comment());
        }
        if (torrentInfo.creator() != null && !torrentInfo.creator().isEmpty()) {
            torrent.setCreatedBy(torrentInfo.creator());
        }
        if (torrentInfo.creationDate() > 0) {
            torrent.setCreationDate(LocalDateTime.ofEpochSecond(torrentInfo.creationDate(), 0,
                    java.time.ZoneOffset.UTC));
        }

        // Add files
        FileStorage files = torrentInfo.files();
        for (int i = 0; i < files.numFiles(); i++) {
            TorrentFile file = new TorrentFile();
            file.setPath(files.filePath(i));
            file.setSize(files.fileSize(i));
            file.setDownloadedSize(0L);
            file.setProgress(0.0);
            file.setPriority(4); // Normal priority

            torrent.addFile(file);
        }

        return torrent;
    }

    /**
     * Check if we've reached the concurrent download limit.
     */
    private void checkConcurrentDownloadLimit() {
        long activeCount = torrentRepository.getActiveDownloadCount();
        // Get max from settings pack
        SettingsPack settings = sessionManager.getSession().settings();
        int maxConcurrent = settings.activeDownloads();

        if (activeCount >= maxConcurrent) {
            throw new MaxConcurrentDownloadsException(maxConcurrent);
        }
    }

    /**
     * Determine the save path for the torrent.
     */
    private File determineSavePath(String customPath) {
        if (customPath != null && !customPath.trim().isEmpty()) {
            File dir = new File(customPath);
            if (!dir.exists()) {
                boolean created = dir.mkdirs();
                if (!created) {
                    log.warn("Failed to create custom save directory: {}, using default", customPath);
                    return sessionManager.getDownloadDirectory();
                }
            }
            return dir;
        }
        return sessionManager.getDownloadDirectory();
    }

    /**
     * Get torrent by ID.
     */
    @Transactional(readOnly = true)
    public TorrentResponse getTorrent(Long id) {
        Torrent torrent = torrentRepository.findById(id)
                .orElseThrow(() -> new TorrentNotFoundException(id));

        // Update with real-time data from handle
        updateTorrentFromHandle(torrent);

        return torrentMapper.toResponse(torrent);
    }

    /**
     * Update torrent entity with real-time data from TorrentHandle.
     */
    private void updateTorrentFromHandle(Torrent torrent) {
        TorrentHandle handle = sessionManager.findTorrent(torrent.getInfoHash());
        if (handle != null && handle.isValid()) {
            com.frostwire.jlibtorrent.TorrentStatus status = handle.status();

            torrent.setDownloadedSize(
                    IntStream.range(0, handle.torrentFile().numFiles())
                            . filter(i->handle.filePriority(i) != Priority.IGNORE)
                            .mapToLong(i -> handle.fileProgress()[i])
                            .sum());
            torrent.setUploadedSize(status.allTimeUpload());
            torrent.setProgress((double) (status.progress() * 100));
            torrent.setDownloadSpeed(status.downloadRate());
            torrent.setUploadSpeed(status.uploadRate());
            torrent.setPeers(status.numPeers());
            torrent.setSeeds(status.numSeeds());

            // Update status
            updateStatus(torrent, status);
        }
    }

    /**
     * Update torrent status based on libtorrent state.
     */
    private void updateStatus(Torrent torrent, com.frostwire.jlibtorrent.TorrentStatus status) {
        // CRITICAL: Check PAUSED first, before checking other states
        // A paused torrent can still be "finished" or "seeding" internally
        if (status.flags().and_(TorrentFlags.PAUSED).nonZero()) {
            torrent.setStatus(TorrentStatus.PAUSED);
        } else if (status.errorCode().isError()) {
            torrent.setStatus(TorrentStatus.ERROR);
            torrent.setErrorMessage(status.errorCode().message());
        } else if (status.isFinished()) {
            if (status.isSeeding()) {
                torrent.setStatus(TorrentStatus.SEEDING);
            } else {
                torrent.setStatus(TorrentStatus.COMPLETED);
                if (torrent.getCompletedDate() == null) {
                    torrent.setCompletedDate(LocalDateTime.now());
                }
            }
        } else if (status.state() == com.frostwire.jlibtorrent.TorrentStatus.State.CHECKING_FILES) {
            torrent.setStatus(TorrentStatus.CHECKING);
        } else if (status.state() == com.frostwire.jlibtorrent.TorrentStatus.State.DOWNLOADING ||
                status.state() == com.frostwire.jlibtorrent.TorrentStatus.State.DOWNLOADING_METADATA) {
            torrent.setStatus(TorrentStatus.DOWNLOADING);
        } else {
            torrent.setStatus(TorrentStatus.PENDING);
        }
    }

    /**
     * Get all torrents.
     */
    @Transactional(readOnly = true)
    public List<TorrentResponse> getAllTorrents() {
        List<Torrent> torrents = torrentRepository.findAllByOrderByAddedDateDesc();

        // Update all with real-time data
        torrents.forEach(this::updateTorrentFromHandle);

        return torrents.stream()
                .map(torrentMapper::toResponse)
                .toList();
    }

    /**
     * Start/resume a torrent.
     */
    @Transactional
    public MessageResponse startTorrent(Long id) {
        Torrent torrent = torrentRepository.findById(id)
                .orElseThrow(() -> new TorrentNotFoundException(id));

        log.info("Attempting to start/resume torrent ID: {}, Name: {}", id, torrent.getName());

        TorrentHandle handle = sessionManager.findTorrent(torrent.getInfoHash());
        if (handle == null) {
            log.error("Handle not found for torrent {}", id);
            throw new TorrentNotActiveException(id);
        }

        // Wrap EVERYTHING in try-catch - ANY method call can crash
        try {
            // Resume the torrent
            log.info("Calling resume() on torrent {}", id);
            handle.resume();

            // Set AUTO_MANAGED flag so jlibtorrent manages it
            log.info("Setting AUTO_MANAGED flag");
            handle.setFlags(TorrentFlags.AUTO_MANAGED);

            // Give it a moment
            Thread.sleep(200);

            // Verify
            com.frostwire.jlibtorrent.TorrentStatus status = handle.status();
            boolean isPaused = status.flags().and_(TorrentFlags.PAUSED).nonZero();
            log.info("After resume() - Is paused: {}, State: {}, Flags: {}",
                    isPaused, status.state(), status.flags().to_int());

            // Update database
            torrent.setStatus(TorrentStatus.DOWNLOADING);
            torrentRepository.save(torrent);

            log.info("Torrent started: {} ({})", torrent.getName(), id);
            return new MessageResponse("Torrent started successfully");

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while starting torrent {}", id);
            throw new TorrentFileException("Start operation interrupted", e);
        } catch (Exception e) {
            log.error("Error starting torrent {}: {}", id, e.getMessage(), e);

            // If start failed, torrent might be removed or completed
            torrent.setStatus(TorrentStatus.ERROR);
            torrent.setErrorMessage("Failed to start: " + e.getMessage());
            torrentRepository.save(torrent);

            throw new TorrentFileException("Failed to start torrent. Torrent may have been removed or is no longer active.", e);
        }
    }

    /**
     * Pause a torrent.
     */
    @Transactional
    public MessageResponse pauseTorrent(Long id) {
        Torrent torrent = torrentRepository.findById(id)
                .orElseThrow(() -> new TorrentNotFoundException(id));

        log.info("Attempting to pause torrent ID: {}, Name: {}, InfoHash: {}",
                id, torrent.getName(), torrent.getInfoHash());

        TorrentHandle handle = sessionManager.findTorrent(torrent.getInfoHash());

        if (handle == null) {
            log.error("Handle not found for torrent {} with infoHash: {}", id, torrent.getInfoHash());
            throw new TorrentNotActiveException(id);
        }

        log.info("Handle found for torrent {}, proceeding with pause", id);

        try {
            // Get current status
            com.frostwire.jlibtorrent.TorrentStatus status = handle.status();
            log.info("Current status - State: {}, Flags: {}",
                    status.state(), status.flags().to_int());

            // Check if already paused
            boolean isPaused = status.flags().and_(TorrentFlags.PAUSED).nonZero();
            log.info("Is currently paused: {}", isPaused);

            if (isPaused) {
                log.info("Torrent {} is already paused", id);
                return new MessageResponse("Torrent is already paused");
            }

            // CRITICAL: Remove AUTO_MANAGED flag first
            // AUTO_MANAGED flag causes jlibtorrent to ignore pause commands
            boolean isAutoManaged = status.flags().and_(TorrentFlags.AUTO_MANAGED).nonZero();
            log.info("Is AUTO_MANAGED: {}", isAutoManaged);

            if (isAutoManaged) {
                log.info("Unsetting AUTO_MANAGED flag before pause");
                handle.unsetFlags(TorrentFlags.AUTO_MANAGED);
                Thread.sleep(100); // Give it a moment
            }

            // Now pause the torrent
            log.info("Calling pause() on torrent {}", id);
            handle.pause();

            // Give it a moment
            Thread.sleep(200);

            // Verify pause worked
            status = handle.status();
            isPaused = status.flags().and_(TorrentFlags.PAUSED).nonZero();
            log.info("After pause() - Is paused: {}, State: {}, Flags: {}",
                    isPaused, status.state(), status.flags().to_int());

            if (!isPaused) {
                log.warn("Pause command executed but torrent is still not paused! This may be a jlibtorrent issue.");
            }

            // Update database regardless
            torrent.setStatus(TorrentStatus.PAUSED);
            torrent.setDownloadSpeed(0);
            torrent.setUploadSpeed(0);
            torrentRepository.save(torrent);

            log.info("Torrent paused successfully: {} ({})", torrent.getName(), id);
            return new MessageResponse("Torrent paused successfully");

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while pausing torrent {}", id);
            throw new TorrentFileException("Pause operation interrupted", e);
        } catch (Exception e) {
            log.error("Error pausing torrent {}: {}", id, e.getMessage(), e);

            torrent.setStatus(TorrentStatus.ERROR);
            torrent.setErrorMessage("Failed to pause: " + e.getMessage());
            torrentRepository.save(torrent);

            throw new TorrentFileException("Failed to pause torrent: " + e.getMessage(), e);
        }
    }

    /**
     * Remove a torrent.
     */
    @Transactional
    public MessageResponse removeTorrent(Long id, boolean deleteFiles) {
        Torrent torrent = torrentRepository.findById(id)
                .orElseThrow(() -> new TorrentNotFoundException(id));

        TorrentHandle handle = sessionManager.findTorrent(torrent.getInfoHash());
        if (handle != null && handle.isValid()) {
            // Remove from session
            if (deleteFiles) {
                sessionManager.getSession().remove(handle, SessionHandle.DELETE_FILES);
            } else {
                sessionManager.getSession().remove(handle);
            }
        }

        // Delete download statistics first (foreign key constraint)
        downloadStatisticsRepository.findByTorrentId(id).ifPresent(stats -> {
            log.debug("Deleting download statistics for torrent {}", id);
            downloadStatisticsRepository.delete(stats);
        });

        // Remove from database
        torrentRepository.delete(torrent);

        log.info("Torrent removed: {} ({}), files deleted: {}", torrent.getName(), id, deleteFiles);
        return new MessageResponse("Torrent removed successfully");
    }
}