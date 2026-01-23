package com.example.jtorrent.service;

import com.example.jtorrent.model.Torrent;
import com.example.jtorrent.model.TorrentStatus;
import com.example.jtorrent.repository.TorrentFileRepository;
import com.example.jtorrent.repository.TorrentRepository;
import com.frostwire.jlibtorrent.ErrorCode;
import com.frostwire.jlibtorrent.TorrentFlags;
import com.frostwire.jlibtorrent.TorrentHandle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TorrentStatusService Unit Tests")
class TorrentStatusServiceTest {

    @Mock
    private TorrentSessionManager sessionManager;

    @Mock
    private TorrentRepository torrentRepository;

    @Mock
    private TorrentWebSocketService webSocketService;

    @Mock
    private TorrentFileRepository torrentFileRepository;

    @InjectMocks
    private TorrentStatusService statusService;

    @Test
    @DisplayName("updateAllTorrentStatus should return when session is not running")
    void testUpdateAllTorrentStatus_NotRunning() {
        when(sessionManager.isRunning()).thenReturn(false);

        statusService.updateAllTorrentStatus();

        verify(torrentRepository, never()).findAllActive();
    }

    @Test
    @DisplayName("updateAllTorrentStatus should update each active torrent")
    void testUpdateAllTorrentStatus_UpdatesEach() {
        Torrent torrent1 = buildTorrent(1L, "hash-1", TorrentStatus.DOWNLOADING);
        Torrent torrent2 = buildTorrent(2L, "hash-2", TorrentStatus.PAUSED);

        when(sessionManager.isRunning()).thenReturn(true);
        when(torrentRepository.findAllActive()).thenReturn(List.of(torrent1, torrent2));

        TorrentStatusService spyService = spy(
                new TorrentStatusService(sessionManager, torrentRepository, webSocketService, torrentFileRepository));
        doNothing().when(spyService).updateTorrentStatus(any(Torrent.class));

        spyService.updateAllTorrentStatus();

        verify(spyService, times(2)).updateTorrentStatus(any(Torrent.class));
        verify(torrentRepository, times(1)).findAllActive();
    }

    @Test
    @DisplayName("updateTorrentStatus should mark missing handle as error")
    void testUpdateTorrentStatus_HandleMissing() {
        Torrent torrent = buildTorrent(1L, "hash-1", TorrentStatus.DOWNLOADING);

        when(sessionManager.findTorrent("hash-1")).thenReturn(null);

        statusService.updateTorrentStatus(torrent);

        assertThat(torrent.getStatus()).isEqualTo(TorrentStatus.ERROR);
        assertThat(torrent.getErrorMessage()).contains("Torrent handle not found in session");
        verify(torrentRepository, times(1)).save(torrent);
        verify(webSocketService, never()).notifyTorrentCompleted(any(), any());
    }

    @Test
    @DisplayName("updateTorrentStatus should do nothing when missing handle for completed torrent")
    void testUpdateTorrentStatus_HandleMissingCompleted() {
        Torrent torrent = buildTorrent(1L, "hash-1", TorrentStatus.COMPLETED);

        when(sessionManager.findTorrent("hash-1")).thenReturn(null);

        statusService.updateTorrentStatus(torrent);

        verify(torrentRepository, never()).save(torrent);
    }

    @Test
    @DisplayName("updateTorrentStatus should update fields and notify on completion")
    void testUpdateTorrentStatus_Completes() {
        Torrent torrent = buildTorrent(10L, "hash-10", TorrentStatus.DOWNLOADING);
        torrent.setName("Ubuntu");
        torrent.setTotalSize(0L);

        TorrentHandle handle = org.mockito.Mockito.mock(TorrentHandle.class);
        com.frostwire.jlibtorrent.TorrentStatus libStatus =
                org.mockito.Mockito.mock(com.frostwire.jlibtorrent.TorrentStatus.class);
        ErrorCode errorCode = org.mockito.Mockito.mock(ErrorCode.class);

        when(sessionManager.findTorrent("hash-10")).thenReturn(handle);
        when(handle.status()).thenReturn(libStatus);
        when(handle.torrentFile()).thenReturn(null);
        when(libStatus.totalDone()).thenReturn(100L);
        when(libStatus.allTimeUpload()).thenReturn(50L);
        when(libStatus.downloadRate()).thenReturn(10);
        when(libStatus.uploadRate()).thenReturn(5);
        when(libStatus.numPeers()).thenReturn(2);
        when(libStatus.numSeeds()).thenReturn(1);
        when(libStatus.totalWanted()).thenReturn(1234L);
        when(libStatus.flags()).thenReturn(TorrentFlags.AUTO_MANAGED);
        when(libStatus.isFinished()).thenReturn(true);
        when(libStatus.isSeeding()).thenReturn(false);
        when(libStatus.errorCode()).thenReturn(errorCode);
        when(errorCode.isError()).thenReturn(false);

        statusService.updateTorrentStatus(torrent);

        assertThat(torrent.getStatus()).isEqualTo(TorrentStatus.COMPLETED);
        assertThat(torrent.getCompletedDate()).isNotNull();
        assertThat(torrent.getTotalSize()).isEqualTo(1234L);
        assertThat(torrent.getErrorMessage()).isNull();
        verify(webSocketService, times(1)).notifyTorrentCompleted(10L, "Ubuntu");
        verify(torrentRepository, times(1)).save(torrent);
    }

    @Test
    @DisplayName("updateTorrentStatus should set error message on libtorrent error")
    void testUpdateTorrentStatus_Error() {
        Torrent torrent = buildTorrent(5L, "hash-5", TorrentStatus.DOWNLOADING);

        TorrentHandle handle = org.mockito.Mockito.mock(TorrentHandle.class);
        com.frostwire.jlibtorrent.TorrentStatus libStatus =
                org.mockito.Mockito.mock(com.frostwire.jlibtorrent.TorrentStatus.class);
        ErrorCode errorCode = org.mockito.Mockito.mock(ErrorCode.class);

        when(sessionManager.findTorrent("hash-5")).thenReturn(handle);
        when(handle.status()).thenReturn(libStatus);
        when(handle.torrentFile()).thenReturn(null);
        when(libStatus.errorCode()).thenReturn(errorCode);
        when(errorCode.isError()).thenReturn(true);
        when(errorCode.message()).thenReturn("Boom");

        statusService.updateTorrentStatus(torrent);

        assertThat(torrent.getStatus()).isEqualTo(TorrentStatus.ERROR);
        assertThat(torrent.getErrorMessage()).isEqualTo("Boom");
        verify(torrentRepository, times(1)).save(torrent);
        verify(webSocketService, never()).notifyTorrentCompleted(any(), any());
    }

    @Test
    @DisplayName("cleanupStalledTorrents should mark missing handles as error")
    void testCleanupStalledTorrents() {
        Torrent stalled = buildTorrent(7L, "hash-7", TorrentStatus.DOWNLOADING);

        when(torrentRepository.findStalledTorrents()).thenReturn(List.of(stalled));
        when(sessionManager.findTorrent("hash-7")).thenReturn(null);

        statusService.cleanupStalledTorrents();

        assertThat(stalled.getStatus()).isEqualTo(TorrentStatus.ERROR);
        assertThat(stalled.getErrorMessage()).contains("Torrent handle lost");
        verify(sessionManager, times(1)).cleanupInvalidHandles();
        verify(torrentRepository, times(1)).save(stalled);
    }

    @Test
    @DisplayName("syncWithSession should mark missing session torrents as error")
    void testSyncWithSession() {
        Torrent dbTorrent = buildTorrent(8L, "hash-8", TorrentStatus.DOWNLOADING);

        when(sessionManager.isRunning()).thenReturn(true);
        when(sessionManager.getActiveTorrents()).thenReturn(new HashMap<>());
        when(torrentRepository.findByStatusIn(any())).thenReturn(List.of(dbTorrent));
        when(sessionManager.findTorrent("hash-8")).thenReturn(null);

        statusService.syncWithSession();

        assertThat(dbTorrent.getStatus()).isEqualTo(TorrentStatus.ERROR);
        assertThat(dbTorrent.getErrorMessage()).contains("Lost from session");
        verify(torrentRepository, times(1)).save(dbTorrent);
    }

    @Test
    @DisplayName("getAllSpeeds should return speeds for active torrents")
    void testGetAllSpeeds() {
        Torrent torrent1 = buildTorrent(1L, "hash-1", TorrentStatus.DOWNLOADING);
        Torrent torrent2 = buildTorrent(2L, "hash-2", TorrentStatus.DOWNLOADING);

        TorrentHandle handle = org.mockito.Mockito.mock(TorrentHandle.class);
        com.frostwire.jlibtorrent.TorrentStatus libStatus =
                org.mockito.Mockito.mock(com.frostwire.jlibtorrent.TorrentStatus.class);

        when(torrentRepository.findAllActive()).thenReturn(List.of(torrent1, torrent2));
        when(sessionManager.findTorrent("hash-1")).thenReturn(null);
        when(sessionManager.findTorrent("hash-2")).thenReturn(handle);
        when(handle.status()).thenReturn(libStatus);
        when(libStatus.downloadRate()).thenReturn(100);
        when(libStatus.uploadRate()).thenReturn(50);

        Map<String, Integer[]> speeds = statusService.getAllSpeeds();

        assertThat(speeds).hasSize(1);
        assertThat(speeds.get("hash-2")).containsExactly(100, 50);
    }

    @Test
    @DisplayName("getRealTimeStatus should return status when handle exists")
    void testGetRealTimeStatus() {
        TorrentHandle handle = org.mockito.Mockito.mock(TorrentHandle.class);
        com.frostwire.jlibtorrent.TorrentStatus libStatus =
                org.mockito.Mockito.mock(com.frostwire.jlibtorrent.TorrentStatus.class);

        when(sessionManager.findTorrent("hash-1")).thenReturn(handle);
        when(handle.status()).thenReturn(libStatus);

        com.frostwire.jlibtorrent.TorrentStatus result = statusService.getRealTimeStatus("hash-1");

        assertThat(result).isSameAs(libStatus);
    }

    @Test
    @DisplayName("isTorrentActive should return false on errors")
    void testIsTorrentActive_Exception() {
        when(sessionManager.findTorrent("hash-1")).thenThrow(new RuntimeException("boom"));

        boolean active = statusService.isTorrentActive("hash-1");

        assertThat(active).isFalse();
    }

    private Torrent buildTorrent(Long id, String infoHash, TorrentStatus status) {
        Torrent torrent = new Torrent();
        torrent.setId(id);
        torrent.setInfoHash(infoHash);
        torrent.setName("Test Torrent");
        torrent.setStatus(status);
        torrent.setSavePath("/downloads");
        return torrent;
    }
}
