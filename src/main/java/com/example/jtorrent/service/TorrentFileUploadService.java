package com.example.jtorrent.service;


import com.example.jtorrent.dto.AddTorrentFileResponse;
import com.example.jtorrent.exception.TorrentExceptions.*;
import com.example.jtorrent.model.Torrent;
import com.example.jtorrent.model.TorrentFile;
import com.example.jtorrent.model.TorrentStatus;
import com.example.jtorrent.repository.DownloadStatisticsRepository;
import com.example.jtorrent.repository.TorrentRepository;
import com.frostwire.jlibtorrent.FileStorage;
import com.frostwire.jlibtorrent.TorrentFlags;
import com.frostwire.jlibtorrent.TorrentHandle;
import com.frostwire.jlibtorrent.TorrentInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

/**
 * Handles uploading of .torrent files via multipart HTTP requests.
 *
 * <p>This service is intentionally separate from {@link TorrentService} so that:
 * <ul>
 *   <li>The multipart / IO concerns are isolated from the core torrent lifecycle logic</li>
 *   <li>It is easy to test each class independently</li>
 * </ul>
 *
 * <p>Flow:
 * <ol>
 *   <li>Validate the uploaded file (not empty, correct extension, size limit)</li>
 *   <li>Parse the raw bytes into a {@link TorrentInfo} to verify it is valid bencode</li>
 *   <li>Duplicate-check against the database via the info hash</li>
 *   <li>Write to a temp file and hand off to jlibtorrent's session</li>
 *   <li>Persist the {@link Torrent} entity and return the response DTO</li>
 * </ol>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TorrentFileUploadService {

    /** Maximum accepted .torrent file size (10 MB). */
    private static final long MAX_TORRENT_FILE_BYTES = 10 * 1024 * 1024L;

    private final TorrentSessionManager sessionManager;
    private final TorrentRepository torrentRepository;
    private final DownloadStatisticsRepository downloadStatisticsRepository;
    private final TorrentWebSocketService webSocketService;

    /**
     * Add a torrent from an uploaded {@code .torrent} file.
     *
     * @param file             the multipart upload (must be a {@code .torrent})
     * @param savePath         optional custom download directory; falls back to the configured default
     * @param startImmediately if {@code true} the download starts right away; otherwise PAUSED
     * @return response DTO with torrent id, hash, name, size, and file count
     * @throws InvalidMagnetLinkException   if the file is empty, has the wrong extension, or is not valid bencode
     * @throws TorrentAlreadyExistsException if a torrent with the same info hash already exists
     * @throws TorrentFileException          for any other IO or engine-level error
     */
    @Transactional
    public AddTorrentFileResponse addTorrentFromFile(
            MultipartFile file,
            String savePath,
            boolean startImmediately) {

        validateUpload(file);

        Path tempFile = null;
        try {
            // Read bytes and parse — fails fast for corrupt / non-torrent data
            byte[] bytes = file.getBytes();
            TorrentInfo torrentInfo = parseTorrentBytes(bytes);

            String infoHash = torrentInfo.infoHashV1().toString();
            log.info("Processing uploaded .torrent: name='{}', hash={}, size={} bytes",
                    torrentInfo.name(), infoHash, torrentInfo.totalSize());

            // Duplicate check before touching the engine
            if (torrentRepository.existsByInfoHash(infoHash)) {
                throw new TorrentAlreadyExistsException(infoHash);
            }

            // Write to a temp file so jlibtorrent can read it via its native path-based API
            tempFile = writeTempFile(bytes, sanitiseFilename(file.getOriginalFilename()));

            // Resolve save directory
            File saveDir = determineSaveDir(savePath);

            // Hand off to the jlibtorrent session
            TorrentHandle handle = addToSession(torrentInfo, saveDir, startImmediately);

            // Build and persist the entity
            Torrent torrent = buildTorrentEntity(handle, torrentInfo, saveDir, startImmediately);
            torrent = torrentRepository.save(torrent);

            log.info("Torrent uploaded and added: {} ({})", torrent.getName(), torrent.getId());
            webSocketService.notifyTorrentAdded(torrent.getId());

            return AddTorrentFileResponse.builder()
                    .torrentId(torrent.getId())
                    .infoHash(infoHash)
                    .name(torrent.getName())
                    .totalSize(torrent.getTotalSize())
                    .fileCount(torrent.getFiles().size())
                    .message("Torrent uploaded and added successfully")
                    .started(startImmediately)
                    .build();
        } catch (TorrentAlreadyExistsException | InvalidMagnetLinkException | MaxConcurrentDownloadsException e) {
            throw e;
        } catch (IOException e) {
            log.error("IO error while processing uploaded torrent file", e);
            throw new TorrentFileException("Failed to read uploaded file: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("Error adding torrent from uploaded file", e);
            throw new TorrentFileException("Failed to add torrent: " + e.getMessage(), e);
        } finally {
            // Always clean up the temp file, regardless of success or failure
            deleteTempFile(tempFile);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Validate that the upload is a plausible .torrent before doing any heavy work.
     */
    private void validateUpload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidMagnetLinkException("Uploaded file is empty");
        }

        String originalName = file.getOriginalFilename();
        if (originalName == null || !originalName.toLowerCase().endsWith(".torrent")) {
            throw new InvalidMagnetLinkException(
                    "Only .torrent files are accepted; received: " + originalName);
        }

        if (file.getSize() > MAX_TORRENT_FILE_BYTES) {
            throw new InvalidMagnetLinkException(
                    String.format(".torrent file exceeds the 10 MB limit (received %.2f MB)",
                            file.getSize() / (1024.0 * 1024.0)));
        }
    }

    /**
     * Parse raw bytes into a {@link TorrentInfo}, throwing a descriptive error for invalid data.
     */
    private TorrentInfo parseTorrentBytes(byte[] bytes) {
        try {
            return TorrentInfo.bdecode(bytes);
        } catch (Exception e) {
            throw new InvalidMagnetLinkException(
                    "File is not a valid .torrent (bencode parse failed): " + e.getMessage());
        }
    }

    /**
     * Write bytes to a temp file in the system temp directory.
     * The caller is responsible for deleting it via {@link #deleteTempFile(Path)}.
     */
    private Path writeTempFile(byte[] bytes, String filename) throws IOException {
        Path tempDir = Files.createTempDirectory("jtorrent-upload-");
        Path tempFile = tempDir.resolve(filename);
        Files.write(tempFile, bytes);
        log.debug("Wrote temp torrent file: {}", tempFile);
        return tempFile;
    }

    /**
     * Add the torrent to the jlibtorrent session and configure its start/pause state.
     */
    private TorrentHandle addToSession(TorrentInfo torrentInfo, File saveDir, boolean startImmediately) {
        sessionManager.getSession().download(torrentInfo, saveDir);

        TorrentHandle handle = sessionManager.getSession().find(torrentInfo.infoHashV1());
        if (handle == null || !handle.isValid()) {
            throw new TorrentFileException("Failed to obtain torrent handle from session after adding");
        }

        // Always start fully controlled — clear AUTO_MANAGED, then pause
        handle.unsetFlags(TorrentFlags.AUTO_MANAGED);
        handle.pause();

        if (startImmediately) {
            handle.setFlags(TorrentFlags.AUTO_MANAGED);
            handle.resume();
        }

        return handle;
    }

    /**
     * Build a {@link Torrent} entity from the session handle and parsed torrent metadata.
     */
    private Torrent buildTorrentEntity(TorrentHandle handle, TorrentInfo torrentInfo,
                                       File saveDir, boolean startImmediately) {
        Torrent torrent = new Torrent();
        torrent.setInfoHash(handle.infoHash().toString());
        torrent.setName(torrentInfo.name());
        // No magnet link available for file uploads — leave null
        torrent.setMagnetLink(null);
        torrent.setTotalSize(torrentInfo.totalSize());
        torrent.setSavePath(saveDir.getAbsolutePath());
        torrent.setStatus(startImmediately ? TorrentStatus.DOWNLOADING : TorrentStatus.PAUSED);
        torrent.setProgress(0.0);
        torrent.setDownloadedSize(0L);
        torrent.setUploadedSize(0L);
        torrent.setDownloadSpeed(0);
        torrent.setUploadSpeed(0);
        torrent.setPeers(0);
        torrent.setSeeds(0);

        // Optional metadata
        if (torrentInfo.comment() != null && !torrentInfo.comment().isBlank()) {
            torrent.setComment(torrentInfo.comment());
        }
        if (torrentInfo.creator() != null && !torrentInfo.creator().isBlank()) {
            torrent.setCreatedBy(torrentInfo.creator());
        }
        if (torrentInfo.creationDate() > 0) {
            torrent.setCreationDate(LocalDateTime.ofEpochSecond(
                    torrentInfo.creationDate(), 0, java.time.ZoneOffset.UTC));
        }

        // Files
        FileStorage files = torrentInfo.files();
        for (int i = 0; i < files.numFiles(); i++) {
            TorrentFile tf = new TorrentFile();
            tf.setPath(files.filePath(i));
            tf.setSize(files.fileSize(i));
            tf.setDownloadedSize(0L);
            tf.setProgress(0.0);
            tf.setPriority(4); // Normal
            torrent.addFile(tf);
        }

        return torrent;
    }

    /**
     * Resolve (and create if needed) the directory to save downloaded files into.
     */
    private File determineSaveDir(String customPath) {
        if (customPath != null && !customPath.isBlank()) {
            File dir = new File(customPath.trim());
            if (!dir.exists() && !dir.mkdirs()) {
                log.warn("Could not create custom save directory '{}'; using default", customPath);
                return sessionManager.getDownloadDirectory();
            }
            return dir;
        }
        return sessionManager.getDownloadDirectory();
    }

    /**
     * Sanitize the original filename so it is safe to use as a filesystem path component.
     */
    private String sanitiseFilename(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            return "upload.torrent";
        }
        // Strip path separators and keep only the bare filename
        return new File(originalFilename).getName()
                .replaceAll("[^a-zA-Z0-9._\\-]", "_");
    }

    /**
     * Silently delete a temp file and its parent directory if they exist.
     */
    private void deleteTempFile(Path tempFile) {
        if (tempFile == null) return;
        try {
            Files.deleteIfExists(tempFile);
            // Also remove the temp directory that was created for it
            Path tempDir = tempFile.getParent();
            if (tempDir != null) {
                Files.deleteIfExists(tempDir);
            }
        } catch (IOException e) {
            log.warn("Could not delete temp torrent file: {}", tempFile, e);
        }
    }
}
