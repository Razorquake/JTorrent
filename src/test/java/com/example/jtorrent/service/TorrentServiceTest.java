package com.example.jtorrent.service;

import com.example.jtorrent.dto.AddTorrentRequest;
import com.example.jtorrent.dto.MessageResponse;
import com.example.jtorrent.dto.TorrentResponse;
import com.example.jtorrent.exception.TorrentExceptions.*;
import com.example.jtorrent.mapper.TorrentMapper;
import com.example.jtorrent.model.DownloadStatistics;
import com.example.jtorrent.model.Torrent;
import com.example.jtorrent.model.TorrentStatus;
import com.example.jtorrent.repository.DownloadStatisticsRepository;
import com.example.jtorrent.repository.TorrentFileRepository;
import com.example.jtorrent.repository.TorrentRepository;
import com.frostwire.jlibtorrent.*;
import com.frostwire.jlibtorrent.alerts.AlertType;
import com.frostwire.jlibtorrent.alerts.MetadataReceivedAlert;
import com.frostwire.jlibtorrent.swig.remove_flags_t;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("TorrentService Unit Tests")
class TorrentServiceTest {

    @Mock
    private TorrentSessionManager sessionManager;

    @Mock
    private TorrentRepository torrentRepository;

    @Mock
    private TorrentFileRepository torrentFileRepository;

    @Mock
    private TorrentMapper torrentMapper;

    @Mock
    private TorrentWebSocketService webSocketService;

    @Mock
    private DownloadStatisticsRepository downloadStatisticsRepository;

    @InjectMocks
    private TorrentService torrentService;

    private Torrent testTorrent;
    private TorrentResponse testTorrentResponse;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        // Create a test torrent
        testTorrent = new Torrent();
        testTorrent.setId(1L);
        testTorrent.setInfoHash("test-info-hash-12345");
        testTorrent.setName("Test Torrent");
        testTorrent.setMagnetLink("magnet:?xt=urn:btih:test-info-hash-12345");
        testTorrent.setTotalSize(1000000L);
        testTorrent.setDownloadedSize(500000L);
        testTorrent.setStatus(TorrentStatus.DOWNLOADING);
        testTorrent.setProgress(50.0);
        testTorrent.setSavePath("/downloads");
        testTorrent.setFiles(new ArrayList<>());

        // Create a test response
        testTorrentResponse = TorrentResponse.builder()
                .id(1L)
                .infoHash("test-info-hash-12345")
                .name("Test Torrent")
                .status(TorrentStatus.DOWNLOADING)
                .progress(50.0)
                .build();
    }

    @Test
    @DisplayName("Should get torrent by ID successfully")
    void testGetTorrent_Success() {
        // Given
        when(torrentRepository.findById(1L)).thenReturn(Optional.of(testTorrent));
        when(torrentMapper.toResponse(testTorrent)).thenReturn(testTorrentResponse);

        // When
        TorrentResponse result = torrentService.getTorrent(1L);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getName()).isEqualTo("Test Torrent");

        verify(torrentRepository, times(1)).findById(1L);
        verify(torrentMapper, times(1)).toResponse(testTorrent);
    }

    @Test
    @DisplayName("Should throw TorrentNotFoundException when torrent not found")
    void testGetTorrent_NotFound() {
        // Given
        when(torrentRepository.findById(999L)).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> torrentService.getTorrent(999L))
                .isInstanceOf(TorrentNotFoundException.class)
                .hasMessageContaining("999");

        verify(torrentRepository, times(1)).findById(999L);
        verify(torrentMapper, never()).toResponse(any());
    }

    @Test
    @DisplayName("Should get all torrents successfully")
    void testGetAllTorrents_Success() {
        // Given
        List<Torrent> torrents = List.of(testTorrent);
        when(torrentRepository.findAllByOrderByAddedDateDesc()).thenReturn(torrents);
        when(torrentMapper.toResponse(testTorrent)).thenReturn(testTorrentResponse);

        // When
        List<TorrentResponse> results = torrentService.getAllTorrents();

        // Then
        assertThat(results).isNotEmpty();
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getName()).isEqualTo("Test Torrent");

        verify(torrentRepository, times(1)).findAllByOrderByAddedDateDesc();
    }

    @Test
    @DisplayName("Should return empty list when no torrents exist")
    void testGetAllTorrents_Empty() {
        // Given
        when(torrentRepository.findAllByOrderByAddedDateDesc()).thenReturn(List.of());

        // When
        List<TorrentResponse> results = torrentService.getAllTorrents();

        // Then
        assertThat(results).isEmpty();

        verify(torrentRepository, times(1)).findAllByOrderByAddedDateDesc();
    }

    @Test
    @DisplayName("Should throw MaxConcurrentDownloadsException when limit reached")
    void testAddTorrent_MaxConcurrentDownloadsReached() {
        // Given
        AddTorrentRequest request = new AddTorrentRequest();
        request.setMagnetLink("magnet:?xt=urn:btih:test");
        request.setStartImmediately(true);

        when(torrentRepository.getActiveDownloadCount()).thenReturn(10L);

        SessionManager mockSession = mock(SessionManager.class);
        when(sessionManager.getSession()).thenReturn(mockSession);

        SettingsPack mockSettings = mock(SettingsPack.class);
        when(mockSession.settings()).thenReturn(mockSettings);
        when(mockSettings.activeDownloads()).thenReturn(5);

        // When/Then
        assertThatThrownBy(() -> torrentService.addTorrent(request))
                .isInstanceOf(MaxConcurrentDownloadsException.class)
                .hasMessageContaining("5");

        verify(torrentRepository, times(1)).getActiveDownloadCount();
    }

    @Test
    @DisplayName("Should throw InvalidMagnetLinkException for invalid magnet link")
    void testAddTorrent_InvalidMagnetLink() {
        // Given
        AddTorrentRequest request = new AddTorrentRequest();
        request.setMagnetLink("not-a-valid-magnet-link");
        request.setStartImmediately(true);

        // When/Then
        assertThatThrownBy(() -> torrentService.addTorrent(request))
                .isInstanceOf(InvalidMagnetLinkException.class);
    }

    @Test
    @DisplayName("Should throw InvalidMagnetLinkException for null request")
    void testAddTorrent_NullRequest() {
        assertThatThrownBy(() -> torrentService.addTorrent(null))
                .isInstanceOf(InvalidMagnetLinkException.class);
    }

    @Test
    @DisplayName("Should throw InvalidMagnetLinkException for blank magnet link")
    void testAddTorrent_BlankMagnetLink() {
        AddTorrentRequest request = new AddTorrentRequest();
        request.setMagnetLink("   ");

        assertThatThrownBy(() -> torrentService.addTorrent(request))
                .isInstanceOf(InvalidMagnetLinkException.class);
    }

    @Test
    @DisplayName("Should pause torrent successfully")
    void testPauseTorrent_Success() {
        // Given
        TorrentHandle mockHandle = mock(TorrentHandle.class);
        com.frostwire.jlibtorrent.TorrentStatus mockStatus = mock(com.frostwire.jlibtorrent.TorrentStatus.class);

        when(torrentRepository.findById(1L)).thenReturn(Optional.of(testTorrent));
        when(sessionManager.findTorrent("test-info-hash-12345")).thenReturn(mockHandle);
        when(mockHandle.status()).thenReturn(mockStatus);
        when(mockStatus.flags()).thenReturn(
                TorrentFlags.AUTO_MANAGED,
                TorrentFlags.AUTO_MANAGED,
                TorrentFlags.PAUSED
        );
        when(torrentRepository.save(any(Torrent.class))).thenReturn(testTorrent);

        // When
        MessageResponse response = torrentService.pauseTorrent(1L);

        // Then
        assertThat(response).isNotNull();
        assertThat(response.getMessage()).contains("paused");

        verify(mockHandle, times(1)).unsetFlags(TorrentFlags.AUTO_MANAGED);
        verify(mockHandle, times(1)).pause();
        verify(torrentRepository, times(1)).save(testTorrent);
    }

    @Test
    @DisplayName("Should return early when torrent is already paused")
    void testPauseTorrent_AlreadyPaused() {
        TorrentHandle mockHandle = mock(TorrentHandle.class);
        com.frostwire.jlibtorrent.TorrentStatus mockStatus = mock(com.frostwire.jlibtorrent.TorrentStatus.class);

        when(torrentRepository.findById(1L)).thenReturn(Optional.of(testTorrent));
        when(sessionManager.findTorrent("test-info-hash-12345")).thenReturn(mockHandle);
        when(mockHandle.status()).thenReturn(mockStatus);
        when(mockStatus.flags()).thenReturn(TorrentFlags.PAUSED);

        MessageResponse response = torrentService.pauseTorrent(1L);

        assertThat(response.getMessage()).contains("already paused");
        verify(mockHandle, never()).pause();
        verify(mockHandle, never()).unsetFlags(TorrentFlags.AUTO_MANAGED);
        verify(torrentRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should throw TorrentNotActiveException when pausing non-active torrent")
    void testPauseTorrent_NotActive() {
        // Given
        when(torrentRepository.findById(1L)).thenReturn(Optional.of(testTorrent));
        when(sessionManager.findTorrent("test-info-hash-12345")).thenReturn(null);

        // When/Then
        assertThatThrownBy(() -> torrentService.pauseTorrent(1L))
                .isInstanceOf(TorrentNotActiveException.class)
                .hasMessageContaining("1");

        verify(torrentRepository, times(1)).findById(1L);
        verify(sessionManager, times(1)).findTorrent("test-info-hash-12345");
    }

    @Test
    @DisplayName("Should start torrent successfully")
    void testStartTorrent_Success() {
        // Given
        testTorrent.setStatus(TorrentStatus.PAUSED);

        TorrentHandle mockHandle = mock(TorrentHandle.class);
        com.frostwire.jlibtorrent.TorrentStatus mockStatus = mock(com.frostwire.jlibtorrent.TorrentStatus.class);

        when(torrentRepository.findById(1L)).thenReturn(Optional.of(testTorrent));
        when(sessionManager.findTorrent("test-info-hash-12345")).thenReturn(mockHandle);
        when(mockHandle.status()).thenReturn(mockStatus);
        when(mockStatus.flags()).thenReturn(TorrentFlags.AUTO_MANAGED);
        when(torrentRepository.save(any(Torrent.class))).thenReturn(testTorrent);

        // When
        MessageResponse response = torrentService.startTorrent(1L);

        // Then
        assertThat(response).isNotNull();
        assertThat(response.getMessage()).contains("started");

        verify(mockHandle, times(1)).resume();
        verify(mockHandle, times(1)).setFlags(TorrentFlags.AUTO_MANAGED);
        verify(torrentRepository, times(1)).save(testTorrent);
    }

    @Test
    @DisplayName("Should throw TorrentNotActiveException when starting non-active torrent")
    void testStartTorrent_NotActive() {
        when(torrentRepository.findById(1L)).thenReturn(Optional.of(testTorrent));
        when(sessionManager.findTorrent("test-info-hash-12345")).thenReturn(null);

        assertThatThrownBy(() -> torrentService.startTorrent(1L))
                .isInstanceOf(TorrentNotActiveException.class);
    }

    @Test
    @DisplayName("Should remove torrent without deleting files")
    void testRemoveTorrent_WithoutDeletingFiles() {
        // Given
        TorrentHandle mockHandle = mock(TorrentHandle.class);
        SessionManager mockSession = mock(SessionManager.class);

        when(torrentRepository.findById(1L)).thenReturn(Optional.of(testTorrent));
        when(sessionManager.findTorrent("test-info-hash-12345")).thenReturn(mockHandle);
        when(sessionManager.getSession()).thenReturn(mockSession);
        when(mockHandle.isValid()).thenReturn(true);
        when(downloadStatisticsRepository.findByTorrentId(1L)).thenReturn(Optional.empty());

        // When
        MessageResponse response = torrentService.removeTorrent(1L, false);

        // Then
        assertThat(response).isNotNull();
        assertThat(response.getMessage()).contains("removed");

        verify(mockSession, times(1)).remove(mockHandle);
        verify(mockSession, never()).remove(eq(mockHandle), remove_flags_t.from_int(ArgumentMatchers.anyInt()));
        verify(torrentRepository, times(1)).delete(testTorrent);
        verify(webSocketService, times(1)).notifyTorrentRemoved(1L);
    }

    @Test
    @DisplayName("Should delete download statistics when removing torrent")
    void testRemoveTorrent_DeletesStatistics() {
        TorrentHandle mockHandle = mock(TorrentHandle.class);
        SessionManager mockSession = mock(SessionManager.class);
        DownloadStatistics stats = new DownloadStatistics();

        when(torrentRepository.findById(1L)).thenReturn(Optional.of(testTorrent));
        when(sessionManager.findTorrent("test-info-hash-12345")).thenReturn(mockHandle);
        when(sessionManager.getSession()).thenReturn(mockSession);
        when(mockHandle.isValid()).thenReturn(true);
        when(downloadStatisticsRepository.findByTorrentId(1L)).thenReturn(Optional.of(stats));

        torrentService.removeTorrent(1L, false);

        verify(downloadStatisticsRepository, times(1)).delete(stats);
        verify(torrentRepository, times(1)).delete(testTorrent);
    }

    @Test
    @DisplayName("Should remove torrent with deleting files")
    void testRemoveTorrent_WithDeletingFiles() {
        // Given
        TorrentHandle mockHandle = mock(TorrentHandle.class);
        SessionManager mockSession = mock(SessionManager.class);

        when(torrentRepository.findById(1L)).thenReturn(Optional.of(testTorrent));
        when(sessionManager.findTorrent("test-info-hash-12345")).thenReturn(mockHandle);
        when(sessionManager.getSession()).thenReturn(mockSession);
        when(mockHandle.isValid()).thenReturn(true);
        when(downloadStatisticsRepository.findByTorrentId(1L)).thenReturn(Optional.empty());

        // When
        MessageResponse response = torrentService.removeTorrent(1L, true);

        // Then
        assertThat(response).isNotNull();
        assertThat(response.getMessage()).contains("removed");

        verify(mockSession, times(1)).remove(mockHandle, SessionHandle.DELETE_FILES);
        verify(torrentRepository, times(1)).delete(testTorrent);
        verify(webSocketService, times(1)).notifyTorrentRemoved(1L);
    }

    @Test
    @DisplayName("Should throw TorrentNotFoundException when removing non-existent torrent")
    void testRemoveTorrent_NotFound() {
        // Given
        when(torrentRepository.findById(999L)).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> torrentService.removeTorrent(999L, false))
                .isInstanceOf(TorrentNotFoundException.class)
                .hasMessageContaining("999");

        verify(torrentRepository, times(1)).findById(999L);
        verify(sessionManager, never()).findTorrent(any());
    }

    @Test
    @DisplayName("Should update torrent from handle and set PAUSED status")
    void testGetTorrent_UpdatesFromHandle_Paused() {
        TorrentHandle mockHandle = mock(TorrentHandle.class);
        TorrentInfo mockInfo = mock(TorrentInfo.class);
        com.frostwire.jlibtorrent.TorrentStatus mockStatus = mock(com.frostwire.jlibtorrent.TorrentStatus.class);
        ErrorCode mockError = mock(ErrorCode.class);

        when(torrentRepository.findById(1L)).thenReturn(Optional.of(testTorrent));
        when(sessionManager.findTorrent("test-info-hash-12345")).thenReturn(mockHandle);
        when(mockHandle.isValid()).thenReturn(true);
        when(mockHandle.torrentFile()).thenReturn(mockInfo);
        when(mockInfo.numFiles()).thenReturn(2);
        when(mockHandle.filePriority(0)).thenReturn(Priority.NORMAL);
        when(mockHandle.filePriority(1)).thenReturn(Priority.IGNORE);
        when(mockHandle.fileProgress()).thenReturn(new long[]{200L, 400L});
        when(mockHandle.status()).thenReturn(mockStatus);
        when(mockStatus.allTimeUpload()).thenReturn(50L);
        when(mockStatus.progress()).thenReturn(0.5f);
        when(mockStatus.downloadRate()).thenReturn(10);
        when(mockStatus.uploadRate()).thenReturn(5);
        when(mockStatus.numPeers()).thenReturn(2);
        when(mockStatus.numSeeds()).thenReturn(1);
        when(mockStatus.flags()).thenReturn(TorrentFlags.PAUSED);
        when(torrentMapper.toResponse(testTorrent)).thenReturn(testTorrentResponse);

        torrentService.getTorrent(1L);

        assertThat(testTorrent.getStatus()).isEqualTo(TorrentStatus.PAUSED);
        assertThat(testTorrent.getDownloadedSize()).isEqualTo(200L);
        assertThat(testTorrent.getUploadedSize()).isEqualTo(50L);
        assertThat(testTorrent.getProgress()).isEqualTo(50.0);
        assertThat(testTorrent.getDownloadSpeed()).isEqualTo(10);
        assertThat(testTorrent.getUploadSpeed()).isEqualTo(5);
        assertThat(testTorrent.getPeers()).isEqualTo(2);
        assertThat(testTorrent.getSeeds()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should update torrent from handle and set ERROR status")
    void testGetTorrent_UpdatesFromHandle_Error() {
        TorrentHandle mockHandle = mock(TorrentHandle.class);
        TorrentInfo mockInfo = mock(TorrentInfo.class);
        com.frostwire.jlibtorrent.TorrentStatus mockStatus = mock(com.frostwire.jlibtorrent.TorrentStatus.class);
        ErrorCode mockError = mock(ErrorCode.class);

        when(torrentRepository.findById(1L)).thenReturn(Optional.of(testTorrent));
        when(sessionManager.findTorrent("test-info-hash-12345")).thenReturn(mockHandle);
        when(mockHandle.isValid()).thenReturn(true);
        when(mockHandle.torrentFile()).thenReturn(mockInfo);
        when(mockInfo.numFiles()).thenReturn(1);
        when(mockHandle.filePriority(0)).thenReturn(Priority.NORMAL);
        when(mockHandle.fileProgress()).thenReturn(new long[]{100L});
        when(mockHandle.status()).thenReturn(mockStatus);
        when(mockStatus.flags()).thenReturn(TorrentFlags.AUTO_MANAGED);
        when(mockStatus.errorCode()).thenReturn(mockError);
        when(mockError.isError()).thenReturn(true);
        when(mockError.message()).thenReturn("boom");
        when(torrentMapper.toResponse(testTorrent)).thenReturn(testTorrentResponse);

        torrentService.getTorrent(1L);

        assertThat(testTorrent.getStatus()).isEqualTo(TorrentStatus.ERROR);
        assertThat(testTorrent.getErrorMessage()).isEqualTo("boom");
    }

    @Test
    @DisplayName("Should update torrent from handle and set COMPLETED status")
    void testGetTorrent_UpdatesFromHandle_Completed() {
        TorrentHandle mockHandle = mock(TorrentHandle.class);
        TorrentInfo mockInfo = mock(TorrentInfo.class);
        com.frostwire.jlibtorrent.TorrentStatus mockStatus = mock(com.frostwire.jlibtorrent.TorrentStatus.class);
        ErrorCode mockError = mock(ErrorCode.class);

        when(torrentRepository.findById(1L)).thenReturn(Optional.of(testTorrent));
        when(sessionManager.findTorrent("test-info-hash-12345")).thenReturn(mockHandle);
        when(mockHandle.isValid()).thenReturn(true);
        when(mockHandle.torrentFile()).thenReturn(mockInfo);
        when(mockInfo.numFiles()).thenReturn(1);
        when(mockHandle.filePriority(0)).thenReturn(Priority.NORMAL);
        when(mockHandle.fileProgress()).thenReturn(new long[]{100L});
        when(mockHandle.status()).thenReturn(mockStatus);
        when(mockStatus.flags()).thenReturn(TorrentFlags.AUTO_MANAGED);
        when(mockStatus.errorCode()).thenReturn(mockError);
        when(mockError.isError()).thenReturn(false);
        when(mockStatus.isFinished()).thenReturn(true);
        when(mockStatus.isSeeding()).thenReturn(false);
        when(torrentMapper.toResponse(testTorrent)).thenReturn(testTorrentResponse);

        torrentService.getTorrent(1L);

        assertThat(testTorrent.getStatus()).isEqualTo(TorrentStatus.COMPLETED);
        assertThat(testTorrent.getCompletedDate()).isNotNull();
    }

    @Test
    @DisplayName("Should update torrent from handle and set CHECKING status")
    void testGetTorrent_UpdatesFromHandle_Checking() {
        TorrentHandle mockHandle = mock(TorrentHandle.class);
        TorrentInfo mockInfo = mock(TorrentInfo.class);
        com.frostwire.jlibtorrent.TorrentStatus mockStatus = mock(com.frostwire.jlibtorrent.TorrentStatus.class);
        ErrorCode mockError = mock(ErrorCode.class);

        when(torrentRepository.findById(1L)).thenReturn(Optional.of(testTorrent));
        when(sessionManager.findTorrent("test-info-hash-12345")).thenReturn(mockHandle);
        when(mockHandle.isValid()).thenReturn(true);
        when(mockHandle.torrentFile()).thenReturn(mockInfo);
        when(mockInfo.numFiles()).thenReturn(1);
        when(mockHandle.filePriority(0)).thenReturn(Priority.NORMAL);
        when(mockHandle.fileProgress()).thenReturn(new long[]{100L});
        when(mockHandle.status()).thenReturn(mockStatus);
        when(mockStatus.flags()).thenReturn(TorrentFlags.AUTO_MANAGED);
        when(mockStatus.errorCode()).thenReturn(mockError);
        when(mockError.isError()).thenReturn(false);
        when(mockStatus.isFinished()).thenReturn(false);
        when(mockStatus.state()).thenReturn(
                com.frostwire.jlibtorrent.TorrentStatus.State.CHECKING_FILES);
        when(torrentMapper.toResponse(testTorrent)).thenReturn(testTorrentResponse);

        torrentService.getTorrent(1L);

        assertThat(testTorrent.getStatus()).isEqualTo(TorrentStatus.CHECKING);
    }

    @Test
    @DisplayName("Should determine save path using custom directory")
    void testDetermineSavePath_Custom() {
        File customDir = tempDir.resolve("custom").toFile();
        assertThat(customDir.mkdirs()).isTrue();

        File result = invokeDetermineSavePath(customDir.getAbsolutePath());

        assertThat(result.getAbsolutePath()).isEqualTo(customDir.getAbsolutePath());
    }

    @Test
    @DisplayName("Should determine save path using default when blank")
    void testDetermineSavePath_Default() {
        File fallback = tempDir.resolve("fallback").toFile();
        when(sessionManager.getDownloadDirectory()).thenReturn(fallback);

        File result = invokeDetermineSavePath("   ");

        assertThat(result.getAbsolutePath()).isEqualTo(fallback.getAbsolutePath());
    }

    @Test
    @DisplayName("Should not throw when concurrent downloads under limit")
    void testCheckConcurrentDownloadLimit_NoException() {
        when(torrentRepository.getActiveDownloadCount()).thenReturn(2L);
        SessionManager mockSession = mock(SessionManager.class);
        SettingsPack mockSettings = mock(SettingsPack.class);
        when(sessionManager.getSession()).thenReturn(mockSession);
        when(mockSession.settings()).thenReturn(mockSettings);
        when(mockSettings.activeDownloads()).thenReturn(5);

        invokeCheckConcurrentDownloadLimit();
    }

    @Test
    @DisplayName("Should create torrent entity from handle and info")
    void testCreateTorrentEntity() {
        TorrentHandle handle = mock(TorrentHandle.class);
        TorrentInfo info = mock(TorrentInfo.class);
        FileStorage storage = mock(FileStorage.class);
        File saveDir = tempDir.resolve("save").toFile();

        Sha1Hash hash = new Sha1Hash("0123456789abcdef0123456789abcdef01234567");
        when(handle.infoHash()).thenReturn(hash);
        when(info.name()).thenReturn("Test Name");
        when(info.totalSize()).thenReturn(300L);
        when(info.comment()).thenReturn("Test comment");
        when(info.creator()).thenReturn("Tester");
        when(info.creationDate()).thenReturn(1000L);
        when(info.files()).thenReturn(storage);
        when(storage.numFiles()).thenReturn(2);
        when(storage.filePath(0)).thenReturn("file1.bin");
        when(storage.filePath(1)).thenReturn("dir/file2.bin");
        when(storage.fileSize(0)).thenReturn(100L);
        when(storage.fileSize(1)).thenReturn(200L);

        Torrent torrent = invokeCreateTorrentEntity(handle, info, "magnet:?xt=urn:btih:test", saveDir);

        assertThat(torrent.getInfoHash()).isEqualTo(hash.toString());
        assertThat(torrent.getName()).isEqualTo("Test Name");
        assertThat(torrent.getMagnetLink()).contains("magnet:");
        assertThat(torrent.getTotalSize()).isEqualTo(300L);
        assertThat(torrent.getSavePath()).isEqualTo(saveDir.getAbsolutePath());
        assertThat(torrent.getStatus()).isEqualTo(TorrentStatus.PENDING);
        assertThat(torrent.getComment()).isEqualTo("Test comment");
        assertThat(torrent.getCreatedBy()).isEqualTo("Tester");
        assertThat(torrent.getCreationDate()).isEqualTo(
                java.time.LocalDateTime.ofEpochSecond(1000L, 0, ZoneOffset.UTC));
        assertThat(torrent.getFiles()).hasSize(2);
        assertThat(torrent.getFiles().get(0).getTorrent()).isSameAs(torrent);
        assertThat(torrent.getFiles().get(0).getPriority()).isEqualTo(4);
    }

    @Test
    @DisplayName("Should wait for metadata and remove listener")
    void testWaitForMetadata_WithAlert() {
        TorrentHandle handle = mock(TorrentHandle.class);
        TorrentInfo info = mock(TorrentInfo.class);
        SessionManager mockSession = mock(SessionManager.class);
        MetadataReceivedAlert alert = mock(MetadataReceivedAlert.class);

        Sha1Hash hash = new Sha1Hash("0123456789abcdef0123456789abcdef01234567");
        when(sessionManager.getSession()).thenReturn(mockSession);
        when(handle.infoHash()).thenReturn(hash);
        when(handle.torrentFile()).thenReturn(info, null);
        when(alert.type()).thenReturn(AlertType.METADATA_RECEIVED);
        when(alert.handle()).thenReturn(handle);

        doAnswer(invocation -> {
            AlertListener listener = invocation.getArgument(0);
            listener.types();
            listener.alert(alert);
            return null;
        }).when(mockSession).addListener(any(AlertListener.class));

        TorrentInfo result = invokeWaitForMetadata(handle);

        assertThat(result).isSameAs(info);
        verify(mockSession).removeListener(any(AlertListener.class));
    }

    @Test
    @DisplayName("Should set SEEDING status when finished and seeding")
    void testUpdateStatus_Seeding() {
        Torrent torrent = new Torrent();
        torrent.setStatus(TorrentStatus.DOWNLOADING);

        com.frostwire.jlibtorrent.TorrentStatus status =
                mock(com.frostwire.jlibtorrent.TorrentStatus.class);
        ErrorCode errorCode = mock(ErrorCode.class);

        when(status.flags()).thenReturn(TorrentFlags.AUTO_MANAGED);
        when(status.errorCode()).thenReturn(errorCode);
        when(errorCode.isError()).thenReturn(false);
        when(status.isFinished()).thenReturn(true);
        when(status.isSeeding()).thenReturn(true);

        invokeUpdateStatus(torrent, status);

        assertThat(torrent.getStatus()).isEqualTo(TorrentStatus.SEEDING);
        assertThat(torrent.getCompletedDate()).isNull();
    }

    @Test
    @DisplayName("Should set DOWNLOADING status for downloading metadata state")
    void testUpdateStatus_DownloadingMetadata() {
        Torrent torrent = new Torrent();
        torrent.setStatus(TorrentStatus.PENDING);

        com.frostwire.jlibtorrent.TorrentStatus status =
                mock(com.frostwire.jlibtorrent.TorrentStatus.class);
        ErrorCode errorCode = mock(ErrorCode.class);

        when(status.flags()).thenReturn(TorrentFlags.AUTO_MANAGED);
        when(status.errorCode()).thenReturn(errorCode);
        when(errorCode.isError()).thenReturn(false);
        when(status.isFinished()).thenReturn(false);
        when(status.state()).thenReturn(com.frostwire.jlibtorrent.TorrentStatus.State.DOWNLOADING_METADATA);

        invokeUpdateStatus(torrent, status);

        assertThat(torrent.getStatus()).isEqualTo(TorrentStatus.DOWNLOADING);
    }

    @Test
    @DisplayName("Should set PENDING status for non-matching state")
    void testUpdateStatus_Pending() {
        Torrent torrent = new Torrent();
        torrent.setStatus(TorrentStatus.DOWNLOADING);

        com.frostwire.jlibtorrent.TorrentStatus status =
                mock(com.frostwire.jlibtorrent.TorrentStatus.class);
        ErrorCode errorCode = mock(ErrorCode.class);

        when(status.flags()).thenReturn(TorrentFlags.AUTO_MANAGED);
        when(status.errorCode()).thenReturn(errorCode);
        when(errorCode.isError()).thenReturn(false);
        when(status.isFinished()).thenReturn(false);
        when(status.state()).thenReturn(null);

        invokeUpdateStatus(torrent, status);

        assertThat(torrent.getStatus()).isEqualTo(TorrentStatus.PENDING);
    }

    // ── getTorrentByHash ──────────────────────────────────────────────────────

    @Test
    @DisplayName("Should return torrent response for a valid info hash")
    void testGetTorrentByHash_Success() {
        // Given
        when(torrentRepository.findByInfoHash("test-info-hash-12345"))
                .thenReturn(Optional.of(testTorrent));
        when(torrentMapper.toResponse(testTorrent)).thenReturn(testTorrentResponse);

        // When
        TorrentResponse result = torrentService.getTorrentByHash("test-info-hash-12345");

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(1L);
        verify(torrentRepository).findByInfoHash("test-info-hash-12345");
    }

    @Test
    @DisplayName("Should normalise hash to lower-case before lookup")
    void testGetTorrentByHash_NormalisesCase() {
        // Given
        when(torrentRepository.findByInfoHash("test-info-hash-12345"))
                .thenReturn(Optional.of(testTorrent));
        when(torrentMapper.toResponse(testTorrent)).thenReturn(testTorrentResponse);

        // When – pass upper-case hash
        TorrentResponse result = torrentService.getTorrentByHash("TEST-INFO-HASH-12345");

        // Then – repository was called with the lower-cased value
        assertThat(result).isNotNull();
        verify(torrentRepository).findByInfoHash("test-info-hash-12345");
    }

    @Test
    @DisplayName("Should throw TorrentNotFoundException for unknown hash")
    void testGetTorrentByHash_NotFound() {
        // Given
        when(torrentRepository.findByInfoHash("unknown-hash")).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> torrentService.getTorrentByHash("unknown-hash"))
                .isInstanceOf(TorrentNotFoundException.class)
                .hasMessageContaining("unknown-hash");
    }

    @Test
    @DisplayName("Should throw InvalidMagnetLinkException for blank hash")
    void testGetTorrentByHash_BlankHash() {
        assertThatThrownBy(() -> torrentService.getTorrentByHash("  "))
                .isInstanceOf(InvalidMagnetLinkException.class);

        assertThatThrownBy(() -> torrentService.getTorrentByHash(null))
                .isInstanceOf(InvalidMagnetLinkException.class);

        verifyNoInteractions(torrentRepository);
    }

    // ── recheckTorrent ────────────────────────────────────────────────────────

    @Test
    @DisplayName("Should initiate recheck and set status to CHECKING")
    void testRecheckTorrent_Success() {
        // Given
        TorrentHandle mockHandle = mock(TorrentHandle.class);
        when(torrentRepository.findById(1L)).thenReturn(Optional.of(testTorrent));
        when(sessionManager.findTorrent("test-info-hash-12345")).thenReturn(mockHandle);
        when(mockHandle.isValid()).thenReturn(true);
        when(torrentRepository.save(any(Torrent.class))).thenReturn(testTorrent);

        // When
        MessageResponse response = torrentService.recheckTorrent(1L);

        // Then
        assertThat(response.getMessage()).contains("recheck");
        verify(mockHandle).forceRecheck();
        verify(torrentRepository).save(argThat(t -> t.getStatus() == TorrentStatus.CHECKING));
    }

    @Test
    @DisplayName("Should throw TorrentNotFoundException when recheck targets unknown id")
    void testRecheckTorrent_NotFound() {
        when(torrentRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> torrentService.recheckTorrent(99L))
                .isInstanceOf(TorrentNotFoundException.class);

        verifyNoInteractions(sessionManager);
    }

    @Test
    @DisplayName("Should throw TorrentNotActiveException when handle is null for recheck")
    void testRecheckTorrent_NoHandle() {
        when(torrentRepository.findById(1L)).thenReturn(Optional.of(testTorrent));
        when(sessionManager.findTorrent("test-info-hash-12345")).thenReturn(null);

        assertThatThrownBy(() -> torrentService.recheckTorrent(1L))
                .isInstanceOf(TorrentNotActiveException.class);
    }

    @Test
    @DisplayName("Should throw TorrentNotActiveException when handle is invalid for recheck")
    void testRecheckTorrent_InvalidHandle() {
        TorrentHandle mockHandle = mock(TorrentHandle.class);
        when(torrentRepository.findById(1L)).thenReturn(Optional.of(testTorrent));
        when(sessionManager.findTorrent("test-info-hash-12345")).thenReturn(mockHandle);
        when(mockHandle.isValid()).thenReturn(false);

        assertThatThrownBy(() -> torrentService.recheckTorrent(1L))
                .isInstanceOf(TorrentNotActiveException.class);
    }

    @Test
    @DisplayName("Should wrap engine exceptions in TorrentFileException during recheck")
    void testRecheckTorrent_EngineError() {
        TorrentHandle mockHandle = mock(TorrentHandle.class);
        when(torrentRepository.findById(1L)).thenReturn(Optional.of(testTorrent));
        when(sessionManager.findTorrent("test-info-hash-12345")).thenReturn(mockHandle);
        when(mockHandle.isValid()).thenReturn(true);
        doThrow(new RuntimeException("native crash")).when(mockHandle).forceRecheck();

        assertThatThrownBy(() -> torrentService.recheckTorrent(1L))
                .isInstanceOf(TorrentFileException.class)
                .hasMessageContaining("recheck");
    }

    // ── reannounceTorrent ─────────────────────────────────────────────────────

    @Test
    @DisplayName("Should send re-announce to all trackers")
    void testReannounceTorrent_Success() {
        // Given
        TorrentHandle mockHandle = mock(TorrentHandle.class);
        when(torrentRepository.findById(1L)).thenReturn(Optional.of(testTorrent));
        when(sessionManager.findTorrent("test-info-hash-12345")).thenReturn(mockHandle);
        when(mockHandle.isValid()).thenReturn(true);

        // When
        MessageResponse response = torrentService.reannounceTorrent(1L);

        // Then
        assertThat(response.getMessage()).contains("tracker");
        verify(mockHandle).forceReannounce();
        // No DB write expected for reannounce
        verify(torrentRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should throw TorrentNotFoundException when reannounce targets unknown id")
    void testReannounceTorrent_NotFound() {
        when(torrentRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> torrentService.reannounceTorrent(99L))
                .isInstanceOf(TorrentNotFoundException.class);

        verifyNoInteractions(sessionManager);
    }

    @Test
    @DisplayName("Should throw TorrentNotActiveException when handle is null for reannounce")
    void testReannounceTorrent_NoHandle() {
        when(torrentRepository.findById(1L)).thenReturn(Optional.of(testTorrent));
        when(sessionManager.findTorrent("test-info-hash-12345")).thenReturn(null);

        assertThatThrownBy(() -> torrentService.reannounceTorrent(1L))
                .isInstanceOf(TorrentNotActiveException.class);
    }

    @Test
    @DisplayName("Should wrap engine exceptions in TorrentFileException during reannounce")
    void testReannounceTorrent_EngineError() {
        TorrentHandle mockHandle = mock(TorrentHandle.class);
        when(torrentRepository.findById(1L)).thenReturn(Optional.of(testTorrent));
        when(sessionManager.findTorrent("test-info-hash-12345")).thenReturn(mockHandle);
        when(mockHandle.isValid()).thenReturn(true);
        doThrow(new RuntimeException("tracker unreachable")).when(mockHandle).forceReannounce();

        assertThatThrownBy(() -> torrentService.reannounceTorrent(1L))
                .isInstanceOf(TorrentFileException.class)
                .hasMessageContaining("re-announce");
    }


    private void invokeUpdateStatus(Torrent torrent, com.frostwire.jlibtorrent.TorrentStatus status) {
        try {
            Method method = TorrentService.class.getDeclaredMethod(
                    "updateStatus", Torrent.class, com.frostwire.jlibtorrent.TorrentStatus.class);
            method.setAccessible(true);
            method.invoke(torrentService, torrent, status);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private File invokeDetermineSavePath(String path) {
        try {
            Method method = TorrentService.class.getDeclaredMethod("determineSavePath", String.class);
            method.setAccessible(true);
            return (File) method.invoke(torrentService, path);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private void invokeCheckConcurrentDownloadLimit() {
        try {
            Method method = TorrentService.class.getDeclaredMethod("checkConcurrentDownloadLimit");
            method.setAccessible(true);
            method.invoke(torrentService);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private Torrent invokeCreateTorrentEntity(TorrentHandle handle, TorrentInfo info,
                                              String magnet, File saveDir) {
        try {
            Method method = TorrentService.class.getDeclaredMethod(
                    "createTorrentEntity", TorrentHandle.class, TorrentInfo.class, String.class, File.class);
            method.setAccessible(true);
            return (Torrent) method.invoke(torrentService, handle, info, magnet, saveDir);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private TorrentInfo invokeWaitForMetadata(TorrentHandle handle) {
        try {
            Method method = TorrentService.class.getDeclaredMethod("waitForMetadata", TorrentHandle.class);
            method.setAccessible(true);
            return (TorrentInfo) method.invoke(torrentService, handle);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
