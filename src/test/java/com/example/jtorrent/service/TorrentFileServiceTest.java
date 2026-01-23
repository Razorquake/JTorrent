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
import com.frostwire.jlibtorrent.TorrentInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("TorrentFileService Unit Tests")
class TorrentFileServiceTest {

    @Mock
    private TorrentSessionManager sessionManager;

    @Mock
    private TorrentRepository torrentRepository;

    @Mock
    private TorrentFileRepository torrentFileRepository;

    @Mock
    private TorrentMapper torrentMapper;

    @InjectMocks
    private TorrentFileService torrentFileService;

    private Torrent testTorrent;
    private TorrentFile testFile1;
    private TorrentFile testFile2;
    private TorrentFileResponse testFileResponse;

    @BeforeEach
    void setUp() {
        // Create test torrent
        testTorrent = new Torrent();
        testTorrent.setId(1L);
        testTorrent.setInfoHash("test-hash");
        testTorrent.setName("Test Torrent");
        testTorrent.setFiles(new ArrayList<>());

        // Create test files
        testFile1 = new TorrentFile();
        testFile1.setId(1L);
        testFile1.setTorrent(testTorrent);
        testFile1.setPath("video.mp4");
        testFile1.setSize(1000000L);
        testFile1.setPriority(4);
        testFile1.setProgress(50.0);

        testFile2 = new TorrentFile();
        testFile2.setId(2L);
        testFile2.setTorrent(testTorrent);
        testFile2.setPath("subtitle.srt");
        testFile2.setSize(10000L);
        testFile2.setPriority(4);
        testFile2.setProgress(0.0);

        testTorrent.getFiles().add(testFile1);
        testTorrent.getFiles().add(testFile2);

        // Create test response
        testFileResponse = TorrentFileResponse.builder()
                .id(1L)
                .path("video.mp4")
                .size(1000000L)
                .priority(4)
                .progress(50.0)
                .build();
    }

    @Test
    @DisplayName("Should get all torrent files successfully")
    void testGetTorrentFiles_Success() {
        // Given
        when(torrentRepository.findById(1L)).thenReturn(Optional.of(testTorrent));
        when(torrentMapper.toFileResponse(any(TorrentFile.class))).thenReturn(testFileResponse);

        // When
        List<TorrentFileResponse> results = torrentFileService.getTorrentFiles(1L);

        // Then
        assertThat(results).isNotEmpty();
        assertThat(results).hasSize(2);

        verify(torrentRepository, times(1)).findById(1L);
        verify(torrentMapper, times(2)).toFileResponse(any(TorrentFile.class));
    }

    @Test
    @DisplayName("Should throw TorrentNotFoundException when getting files for non-existent torrent")
    void testGetTorrentFiles_TorrentNotFound() {
        // Given
        when(torrentRepository.findById(999L)).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> torrentFileService.getTorrentFiles(999L))
                .isInstanceOf(TorrentNotFoundException.class)
                .hasMessageContaining("999");

        verify(torrentRepository, times(1)).findById(999L);
    }

    @Test
    @DisplayName("Should skip files successfully")
    void testSkipFiles_Success() {
        // Given
        TorrentHandle mockHandle = mock(TorrentHandle.class);
        TorrentInfo mockInfo = mock(TorrentInfo.class);
        Priority[] priorities = new Priority[]{Priority.NORMAL, Priority.NORMAL};

        when(torrentRepository.findById(1L)).thenReturn(Optional.of(testTorrent));
        when(sessionManager.findTorrent("test-hash")).thenReturn(mockHandle);
        when(mockHandle.torrentFile()).thenReturn(mockInfo);
        when(mockInfo.numFiles()).thenReturn(2);
        when(mockHandle.filePriorities()).thenReturn(priorities);
        when(torrentFileRepository.findAllById(anyList())).thenReturn(List.of(testFile1));
        when(torrentFileRepository.saveAll(anyList())).thenReturn(List.of(testFile1));

        SkipFilesRequest request = new SkipFilesRequest();
        request.setFileIds(List.of(1L));

        // When
        MessageResponse response = torrentFileService.skipFiles(1L, request);

        // Then
        assertThat(response).isNotNull();
        assertThat(response.getMessage()).contains("1");

        verify(mockHandle, times(1)).prioritizeFiles(any(Priority[].class));
        verify(torrentFileRepository, times(1)).saveAll(anyList());
    }

    @Test
    @DisplayName("Should download previously skipped files")
    void testDownloadFiles_Success() {
        // Given
        testFile1.setPriority(0); // File is skipped

        TorrentHandle mockHandle = mock(TorrentHandle.class);
        TorrentInfo mockInfo = mock(TorrentInfo.class);
        Priority[] priorities = new Priority[]{Priority.IGNORE, Priority.NORMAL};

        when(torrentRepository.findById(1L)).thenReturn(Optional.of(testTorrent));
        when(sessionManager.findTorrent("test-hash")).thenReturn(mockHandle);
        when(mockHandle.torrentFile()).thenReturn(mockInfo);
        when(mockInfo.numFiles()).thenReturn(2);
        when(mockHandle.filePriorities()).thenReturn(priorities);
        when(torrentFileRepository.findAllById(anyList())).thenReturn(List.of(testFile1));
        when(torrentFileRepository.saveAll(anyList())).thenReturn(List.of(testFile1));

        SkipFilesRequest request = new SkipFilesRequest();
        request.setFileIds(List.of(1L));

        // When
        MessageResponse response = torrentFileService.downloadFiles(1L, request);

        // Then
        assertThat(response).isNotNull();
        assertThat(response.getMessage()).contains("1");

        verify(mockHandle, times(1)).prioritizeFiles(any(Priority[].class));
    }

    @Test
    @DisplayName("Should get skipped files successfully")
    void testGetSkippedFiles_Success() {
        // Given
        testFile1.setPriority(0);

        when(torrentFileRepository.findSkippedFiles(1L)).thenReturn(List.of(testFile1));
        when(torrentMapper.toFileResponse(testFile1)).thenReturn(testFileResponse);

        // When
        List<TorrentFileResponse> results = torrentFileService.getSkippedFiles(1L);

        // Then
        assertThat(results).isNotEmpty();
        assertThat(results).hasSize(1);

        verify(torrentFileRepository, times(1)).findSkippedFiles(1L);
    }

    @Test
    @DisplayName("Should get incomplete files successfully")
    void testGetIncompleteFiles_Success() {
        // Given
        when(torrentRepository.findById(1L)).thenReturn(Optional.of(testTorrent));
        when(torrentFileRepository.findIncompleteFiles(testTorrent)).thenReturn(List.of(testFile1, testFile2));
        when(torrentMapper.toFileResponse(any(TorrentFile.class))).thenReturn(testFileResponse);

        // When
        List<TorrentFileResponse> results = torrentFileService.getIncompleteFiles(1L);

        // Then
        assertThat(results).isNotEmpty();
        assertThat(results).hasSize(2);

        verify(torrentFileRepository, times(1)).findIncompleteFiles(testTorrent);
    }

    @Test
    @DisplayName("Should prioritize files successfully")
    void testPrioritizeFiles_Success() {
        // Given
        TorrentHandle mockHandle = mock(TorrentHandle.class);
        TorrentInfo mockInfo = mock(TorrentInfo.class);
        Priority[] priorities = new Priority[]{Priority.NORMAL, Priority.NORMAL};

        when(torrentRepository.findById(1L)).thenReturn(Optional.of(testTorrent));
        when(sessionManager.findTorrent("test-hash")).thenReturn(mockHandle);
        when(mockHandle.torrentFile()).thenReturn(mockInfo);
        when(mockInfo.numFiles()).thenReturn(2);
        when(mockHandle.filePriorities()).thenReturn(priorities);
        when(torrentFileRepository.findAllById(anyList())).thenReturn(List.of(testFile1));
        when(torrentFileRepository.saveAll(anyList())).thenReturn(List.of(testFile1));

        // When
        MessageResponse response = torrentFileService.prioritizeFiles(1L, List.of(1L));

        // Then
        assertThat(response).isNotNull();
        assertThat(response.getMessage()).contains("1");

        verify(mockHandle, times(1)).prioritizeFiles(any(Priority[].class));
    }

    @Test
    @DisplayName("Should deprioritize files successfully")
    void testDeprioritizeFiles_Success() {
        // Given
        TorrentHandle mockHandle = mock(TorrentHandle.class);
        TorrentInfo mockInfo = mock(TorrentInfo.class);
        Priority[] priorities = new Priority[]{Priority.NORMAL, Priority.NORMAL};

        when(torrentRepository.findById(1L)).thenReturn(Optional.of(testTorrent));
        when(sessionManager.findTorrent("test-hash")).thenReturn(mockHandle);
        when(mockHandle.torrentFile()).thenReturn(mockInfo);
        when(mockInfo.numFiles()).thenReturn(2);
        when(mockHandle.filePriorities()).thenReturn(priorities);
        when(torrentFileRepository.findAllById(anyList())).thenReturn(List.of(testFile2));
        when(torrentFileRepository.saveAll(anyList())).thenReturn(List.of(testFile2));

        // When
        MessageResponse response = torrentFileService.deprioritizeFiles(1L, List.of(2L));

        // Then
        assertThat(response).isNotNull();
        assertThat(response.getMessage()).contains("1");

        verify(mockHandle, times(1)).prioritizeFiles(any(Priority[].class));
    }

    @Test
    @DisplayName("Should reset file priorities successfully")
    void testResetFilePriorities_Success() {
        // Given
        TorrentHandle mockHandle = mock(TorrentHandle.class);
        TorrentInfo mockInfo = mock(TorrentInfo.class);

        when(torrentRepository.findById(1L)).thenReturn(Optional.of(testTorrent));
        when(sessionManager.findTorrent("test-hash")).thenReturn(mockHandle);
        when(mockHandle.torrentFile()).thenReturn(mockInfo);
        when(mockInfo.numFiles()).thenReturn(2);
        when(torrentFileRepository.saveAll(anyList())).thenReturn(testTorrent.getFiles());

        // When
        MessageResponse response = torrentFileService.resetFilePriorities(1L);

        // Then
        assertThat(response).isNotNull();
        assertThat(response.getMessage()).contains("reset");

        verify(mockHandle, times(1)).prioritizeFiles(any(Priority[].class));
        verify(torrentFileRepository, times(1)).saveAll(anyList());
    }

    @Test
    @DisplayName("Should skip files by extension")
    void testSkipFilesByExtension_Success() {
        // Given
        TorrentHandle mockHandle = mock(TorrentHandle.class);
        TorrentInfo mockInfo = mock(TorrentInfo.class);
        Priority[] priorities = new Priority[]{Priority.NORMAL, Priority.NORMAL};

        when(torrentRepository.findById(1L)).thenReturn(Optional.of(testTorrent));
        when(torrentFileRepository.findByTorrentAndPathContaining(testTorrent, ".srt"))
                .thenReturn(List.of(testFile2));
        when(sessionManager.findTorrent("test-hash")).thenReturn(mockHandle);
        when(mockHandle.torrentFile()).thenReturn(mockInfo);
        when(mockInfo.numFiles()).thenReturn(2);
        when(mockHandle.filePriorities()).thenReturn(priorities);
        when(torrentFileRepository.findAllById(anyList())).thenReturn(List.of(testFile2));
        when(torrentFileRepository.saveAll(anyList())).thenReturn(List.of(testFile2));

        // When
        MessageResponse response = torrentFileService.skipFilesByExtension(1L, "srt");

        // Then
        assertThat(response).isNotNull();
        assertThat(response.getMessage()).contains("1");
        assertThat(response.getMessage()).contains("srt");

        verify(torrentFileRepository, times(1)).findByTorrentAndPathContaining(testTorrent, ".srt");
    }

    @Test
    @DisplayName("Should validate priority range in update request")
    void testUpdateFilePriorities_InvalidPriority() {
        // Given
        when(torrentRepository.findById(1L)).thenReturn(Optional.of(testTorrent));

        UpdateFilePrioritiesRequest request = new UpdateFilePrioritiesRequest();
        request.setFileIds(List.of(1L));
        request.setPriority(10); // Invalid priority (> 7)

        // When/Then
        assertThatThrownBy(() -> torrentFileService.updateFilePriorities(1L, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Priority must be between 0 and 7");
    }

    @Test
    @DisplayName("Should throw TorrentNotActiveException when handle not found")
    void testUpdateFilePriorities_HandleNotFound() {
        // Given
        when(torrentRepository.findById(1L)).thenReturn(Optional.of(testTorrent));
        when(sessionManager.findTorrent("test-hash")).thenReturn(null);

        UpdateFilePrioritiesRequest request = new UpdateFilePrioritiesRequest();
        request.setFileIds(List.of(1L));
        request.setPriority(4);

        // When/Then
        assertThatThrownBy(() -> torrentFileService.updateFilePriorities(1L, request))
                .isInstanceOf(TorrentNotActiveException.class);
    }

    @Test
    @DisplayName("Should get files by pattern")
    void testGetFilesByPattern_Success() {
        // Given
        when(torrentRepository.findById(1L)).thenReturn(Optional.of(testTorrent));
        when(torrentFileRepository.findByTorrentAndPathContaining(testTorrent, ".mp4"))
                .thenReturn(List.of(testFile1));
        when(torrentMapper.toFileResponse(testFile1)).thenReturn(testFileResponse);

        // When
        List<TorrentFileResponse> results = torrentFileService.getFilesByPattern(1L, ".mp4");

        // Then
        assertThat(results).isNotEmpty();
        assertThat(results).hasSize(1);

        verify(torrentFileRepository, times(1)).findByTorrentAndPathContaining(testTorrent, ".mp4");
    }

    @Test
    @DisplayName("Should get files by priority")
    void testGetFilesByPriority_Success() {
        // Given
        when(torrentFileRepository.findByTorrentIdAndPriority(1L, 4))
                .thenReturn(List.of(testFile1, testFile2));
        when(torrentMapper.toFileResponse(any(TorrentFile.class))).thenReturn(testFileResponse);

        // When
        List<TorrentFileResponse> results = torrentFileService.getFilesByPriority(1L, 4);

        // Then
        assertThat(results).isNotEmpty();
        assertThat(results).hasSize(2);

        verify(torrentFileRepository, times(1)).findByTorrentIdAndPriority(1L, 4);
    }
}
