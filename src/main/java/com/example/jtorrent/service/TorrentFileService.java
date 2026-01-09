package com.example.jtorrent.service;

import com.example.jtorrent.dto.MessageResponse;
import com.example.jtorrent.dto.SkipFilesRequest;
import com.example.jtorrent.dto.TorrentFileResponse;
import com.example.jtorrent.dto.UpdateFilePrioritiesRequest;
import com.example.jtorrent.exception.TorrentExceptions.*;
import com.example.jtorrent.mapper.TorrentMapper;
import com.example.jtorrent.model.Torrent;
import com.example.jtorrent.model.TorrentFile;
import com.example.jtorrent.repository.TorrentFileRepository;
import com.example.jtorrent.repository.TorrentRepository;
import com.frostwire.jlibtorrent.Priority;
import com.frostwire.jlibtorrent.TorrentHandle;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;

/**
 * Service for managing individual files within torrents.
 * Handles operations like skipping files, setting priorities, and querying file information.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TorrentFileService {

    private final TorrentSessionManager sessionManager;
    private final TorrentRepository torrentRepository;
    private final TorrentFileRepository torrentFileRepository;
    private final TorrentMapper torrentMapper;

    /**
     * Get all files for a torrent.
     */
    @Transactional(readOnly = true)
    public List<TorrentFileResponse> getTorrentFiles(Long torrentId) {
        Torrent torrent = torrentRepository.findById(torrentId)
                .orElseThrow(() -> new TorrentNotFoundException(torrentId));

        return torrent.getFiles().stream()
                .map(torrentMapper::toFileResponse)
                .toList();
    }

    /**
     * Update file priorities for selected files.
     * Priority levels:
     * 0 = skip (don't download)
     * 1 = low
     * 4 = normal (default)
     * 7 = high
     */
    @Transactional
    public MessageResponse updateFilePriorities(Long torrentId, UpdateFilePrioritiesRequest request) {
        Torrent torrent = torrentRepository.findById(torrentId)
                .orElseThrow(() -> new TorrentNotFoundException(torrentId));

        try {
            TorrentHandle handle = sessionManager.findTorrent(torrent.getInfoHash());
            if (handle == null) {
                throw new TorrentNotActiveException(torrentId);
            }

            // Validate priority value
            int priority = request.getPriority();
            if (priority < 0 || priority > 7) {
                throw new IllegalArgumentException("Priority must be between 0 and 7");
            }

            // Get current priorities for all files
            com.frostwire.jlibtorrent.TorrentInfo torrentInfo = handle.torrentFile();
            if (torrentInfo == null) {
                throw new TorrentFileException("Torrent metadata not available yet");
            }

            int numFiles = torrentInfo.numFiles();

            // CRITICAL: Get CURRENT priorities from handle, don't initialize to NORMAL!
            // Otherwise we'll reset all other files to NORMAL when we only want to update specific ones
            Priority[] priorities = handle.filePriorities();

            // If priorities array is empty or wrong size, initialize it
            if (priorities.length != numFiles) {
                log.warn("File priorities not available or wrong size, initializing to NORMAL");
                priorities = new Priority[numFiles];
                for (int i = 0; i < numFiles; i++) {
                    priorities[i] = Priority.NORMAL;
                }
            }

            // Update requested files only
            List<TorrentFile> filesToUpdate = torrentFileRepository.findAllById(request.getFileIds());

            for (TorrentFile file : filesToUpdate) {
                if (!file.getTorrent().getId().equals(torrentId)) {
                    throw new IllegalArgumentException("File " + file.getId() + " does not belong to torrent " + torrentId);
                }

                // Find file index
                int fileIndex = findFileIndex(torrent, file);
                if (fileIndex >= 0 && fileIndex < numFiles) {
                    Priority newPriority = Priority.fromSwig(priority);
                    priorities[fileIndex] = newPriority;

                    log.debug("Setting file {} priority from {} to {}",
                            file.getPath(), priorities[fileIndex], newPriority);

                    // Update in database
                    file.setPriority(priority);
                }
            }

            // Apply to torrent
            handle.prioritizeFiles(priorities);

            // Save changes
            torrentFileRepository.saveAll(filesToUpdate);

            log.info("Updated priorities for {} files in torrent {}", filesToUpdate.size(), torrent.getName());
            return new MessageResponse(String.format("Updated priorities for %d files", filesToUpdate.size()));
        } catch (TorrentNotFoundException | TorrentNotActiveException | IllegalArgumentException e) {
            // Re-throw these as they are expected errors
            throw e;
        } catch (Exception e) {
            log.error("Error updating file priorities for torrent {}: {}", torrentId, e.getMessage(), e);
            throw new TorrentFileException("Failed to update file priorities: " + e.getMessage(), e);
        }
    }

    /**
     * Skip (don't download) specified files.
     * This sets their priority to 0.
     */
    @Transactional
    public MessageResponse skipFiles(Long torrentId, SkipFilesRequest request) {
        UpdateFilePrioritiesRequest updateRequest = new UpdateFilePrioritiesRequest();
        updateRequest.setFileIds(request.getFileIds());
        updateRequest.setPriority(0); // 0 = skip

        return updateFilePriorities(torrentId, updateRequest);
    }

    /**
     * Download (un-skip) previously skipped files.
     * This sets their priority to normal (4).
     */
    @Transactional
    public MessageResponse downloadFiles(Long torrentId, SkipFilesRequest request) {
        UpdateFilePrioritiesRequest updateRequest = new UpdateFilePrioritiesRequest();
        updateRequest.setFileIds(request.getFileIds());
        updateRequest.setPriority(4); // 4 = normal

        return updateFilePriorities(torrentId, updateRequest);
    }

    /**
     * Get all skipped files for a torrent.
     */
    @Transactional(readOnly = true)
    public List<TorrentFileResponse> getSkippedFiles(Long torrentId) {

        List<TorrentFile> skippedFiles = torrentFileRepository.findSkippedFiles(torrentId);

        return skippedFiles.stream()
                .map(torrentMapper::toFileResponse)
                .toList();
    }

    /**
     * Get files by priority level.
     */
    @Transactional(readOnly = true)
    public List<TorrentFileResponse> getFilesByPriority(Long torrentId, Integer priority) {

        List<TorrentFile> files = torrentFileRepository.findByTorrentIdAndPriority(torrentId, priority);

        return files.stream()
                .map(torrentMapper::toFileResponse)
                .toList();
    }

    /**
     * Get incomplete files (not fully downloaded yet).
     */
    @Transactional(readOnly = true)
    public List<TorrentFileResponse> getIncompleteFiles(Long torrentId) {
        Torrent torrent = torrentRepository.findById(torrentId)
                .orElseThrow(() -> new TorrentNotFoundException(torrentId));

        List<TorrentFile> incompleteFiles = torrentFileRepository.findIncompleteFiles(torrent);

        return incompleteFiles.stream()
                .map(torrentMapper::toFileResponse)
                .toList();
    }

    /**
     * Set high priority for specific files (priority 7).
     * This makes them download first.
     */
    @Transactional
    public MessageResponse prioritizeFiles(Long torrentId, List<Long> fileIds) {
        UpdateFilePrioritiesRequest request = new UpdateFilePrioritiesRequest();
        request.setFileIds(fileIds);
        request.setPriority(7); // 7 = high

        MessageResponse response = updateFilePriorities(torrentId, request);
        log.info("Prioritized {} files in torrent {}", fileIds.size(), torrentId);
        return response;
    }

    /**
     * Set low priority for specific files (priority 1).
     * These will be downloaded last.
     */
    @Transactional
    public MessageResponse deprioritizeFiles(Long torrentId, List<Long> fileIds) {
        UpdateFilePrioritiesRequest request = new UpdateFilePrioritiesRequest();
        request.setFileIds(fileIds);
        request.setPriority(1); // 1 = low

        MessageResponse response = updateFilePriorities(torrentId, request);
        log.info("Deprioritized {} files in torrent {}", fileIds.size(), torrentId);
        return response;
    }

    /**
     * Reset all file priorities to normal (4).
     */
    @Transactional
    public MessageResponse resetFilePriorities(Long torrentId) {
        Torrent torrent = torrentRepository.findById(torrentId)
                .orElseThrow(() -> new TorrentNotFoundException(torrentId));

        try {
            TorrentHandle handle = sessionManager.findTorrent(torrent.getInfoHash());
            if (handle == null) {
                throw new TorrentNotActiveException(torrentId);
            }

            com.frostwire.jlibtorrent.TorrentInfo torrentInfo = handle.torrentFile();
            if (torrentInfo == null) {
                throw new TorrentFileException("Torrent metadata not available yet");
            }

            int numFiles = torrentInfo.numFiles();
            Priority[] priorities = new Priority[numFiles];

            // Set all to normal
            Arrays.fill(priorities, Priority.NORMAL);

            handle.prioritizeFiles(priorities);

            // Update database
            List<TorrentFile> files = torrent.getFiles();
            for (TorrentFile file : files) {
                file.setPriority(4); // Normal
            }
            torrentFileRepository.saveAll(files);

            log.info("Reset all file priorities to normal for torrent {}", torrent.getName());
            return new MessageResponse("All file priorities reset to normal");
        } catch (TorrentNotFoundException | TorrentNotActiveException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error resetting file priorities for torrent {}: {}", torrentId, e.getMessage(), e);
            throw new TorrentFileException("Failed to reset file priorities: " + e.getMessage(), e);
        }
    }

    /**
     * Get file download progress as percentage.
     */
    @Transactional(readOnly = true)
    public Double getFileProgress(Long fileId) {
        TorrentFile file = torrentFileRepository.findById(fileId)
                .orElseThrow(() -> new RuntimeException("File not found: " + fileId));

        return file.getProgress();
    }

    /**
     * Find the index of a file within a torrent.
     */
    private int findFileIndex(Torrent torrent, TorrentFile targetFile) {
        List<TorrentFile> files = torrent.getFiles();
        for (int i = 0; i < files.size(); i++) {
            if (files.get(i).getId().equals(targetFile.getId())) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Get total size of files with a specific priority.
     */
    @Transactional(readOnly = true)
    public Long getTotalSizeByPriority(Long torrentId, Integer priority) {
        List<TorrentFile> files = torrentFileRepository.findByTorrentIdAndPriority(torrentId, priority);
        return files.stream()
                .mapToLong(TorrentFile::getSize)
                .sum();
    }

    /**
     * Get files by extension/pattern.
     */
    @Transactional(readOnly = true)
    public List<TorrentFileResponse> getFilesByPattern(Long torrentId, String pattern) {
        Torrent torrent = torrentRepository.findById(torrentId)
                .orElseThrow(() -> new TorrentNotFoundException(torrentId));

        List<TorrentFile> files = torrentFileRepository.findByTorrentAndPathContaining(torrent, pattern);

        return files.stream()
                .map(torrentMapper::toFileResponse)
                .toList();
    }

    /**
     * Skip files by extension (e.g., skip all .txt files).
     */
    @Transactional
    public MessageResponse skipFilesByExtension(Long torrentId, String extension) {
        Torrent torrent = torrentRepository.findById(torrentId)
                .orElseThrow(() -> new TorrentNotFoundException(torrentId));

        List<TorrentFile> files = torrentFileRepository.findByTorrentAndPathContaining(
                torrent, "." + extension);

        if (files.isEmpty()) {
            return new MessageResponse("No files found with extension: " + extension);
        }

        List<Long> fileIds = files.stream()
                .map(TorrentFile::getId)
                .toList();

        SkipFilesRequest request = new SkipFilesRequest();
        request.setFileIds(fileIds);

        MessageResponse response = skipFiles(torrentId, request);
        log.info("Skipped {} files with extension '{}' in torrent {}",
                files.size(), extension, torrent.getName());

        return new MessageResponse(String.format("Skipped %d files with extension: %s",
                files.size(), extension));
    }
}
