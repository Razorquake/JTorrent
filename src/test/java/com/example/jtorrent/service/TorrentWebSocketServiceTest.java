package com.example.jtorrent.service;

import com.example.jtorrent.dto.TorrentResponse;
import com.example.jtorrent.dto.TorrentStatsDTO;
import com.example.jtorrent.mapper.TorrentMapper;
import com.example.jtorrent.model.Torrent;
import com.example.jtorrent.repository.TorrentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TorrentWebSocketService Unit Tests")
class TorrentWebSocketServiceTest {

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Mock
    private TorrentRepository torrentRepository;

    @Mock
    private TorrentMapper torrentMapper;

    @Mock
    private TorrentStatisticsService statisticsService;

    @InjectMocks
    private TorrentWebSocketService webSocketService;

    private Torrent torrent1;
    private Torrent torrent2;
    private TorrentResponse response1;
    private TorrentResponse response2;

    @BeforeEach
    void setUp() {
        torrent1 = new Torrent();
        torrent1.setId(1L);
        torrent1.setName("Torrent One");

        torrent2 = new Torrent();
        torrent2.setId(2L);
        torrent2.setName("Torrent Two");

        response1 = TorrentResponse.builder()
                .id(1L)
                .name("Torrent One")
                .build();

        response2 = TorrentResponse.builder()
                .id(2L)
                .name("Torrent Two")
                .build();
    }

    @Test
    @DisplayName("Should broadcast updates for active torrents and full list")
    void testBroadcastTorrentUpdates() {
        when(torrentRepository.findAllActive()).thenReturn(List.of(torrent1));
        when(torrentRepository.findAllByOrderByAddedDateDesc()).thenReturn(List.of(torrent1, torrent2));
        when(torrentMapper.toResponse(torrent1)).thenReturn(response1);
        when(torrentMapper.toResponse(torrent2)).thenReturn(response2);

        webSocketService.broadcastTorrentUpdates();

        verify(messagingTemplate).convertAndSend("/topic/torrents/1", response1);

        ArgumentCaptor<List<TorrentResponse>> listCaptor = ArgumentCaptor.forClass(List.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/torrents"), listCaptor.capture());
        assertThat(listCaptor.getValue()).hasSize(2);
    }

    @Test
    @DisplayName("Should broadcast statistics")
    void testBroadcastStatistics() {
        TorrentStatsDTO stats = TorrentStatsDTO.builder()
                .totalTorrents(5L)
                .build();

        when(statisticsService.getOverallStatistics()).thenReturn(stats);

        webSocketService.broadcastStatistics();

        verify(messagingTemplate).convertAndSend("/topic/stats", stats);
    }

    @Test
    @DisplayName("Should send torrent update when torrent exists")
    void testSendTorrentUpdate() {
        when(torrentRepository.findById(1L)).thenReturn(Optional.of(torrent1));
        when(torrentMapper.toResponse(torrent1)).thenReturn(response1);

        webSocketService.sendTorrentUpdate(1L);

        verify(messagingTemplate).convertAndSend("/topic/torrents/1", response1);
    }

    @Test
    @DisplayName("Should not send update when torrent is missing")
    void testSendTorrentUpdate_NotFound() {
        when(torrentRepository.findById(1L)).thenReturn(Optional.empty());

        webSocketService.sendTorrentUpdate(1L);

        verify(messagingTemplate, never()).convertAndSend(eq("/topic/torrents/1"), Optional.ofNullable(ArgumentMatchers.any()));
    }

    @Test
    @DisplayName("Should notify torrent added and send update")
    void testNotifyTorrentAdded() {
        when(torrentRepository.findById(1L)).thenReturn(Optional.of(torrent1));
        when(torrentMapper.toResponse(torrent1)).thenReturn(response1);

        webSocketService.notifyTorrentAdded(1L);

        ArgumentCaptor<TorrentWebSocketService.NotificationMessage> notificationCaptor =
                ArgumentCaptor.forClass(TorrentWebSocketService.NotificationMessage.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/notifications"), notificationCaptor.capture());
        assertThat(notificationCaptor.getValue().event()).isEqualTo("torrent_added");
        assertThat(notificationCaptor.getValue().torrentId()).isEqualTo(1L);

        verify(messagingTemplate).convertAndSend("/topic/torrents/1", response1);
    }

    @Test
    @DisplayName("Should notify torrent removed")
    void testNotifyTorrentRemoved() {
        webSocketService.notifyTorrentRemoved(2L);

        ArgumentCaptor<TorrentWebSocketService.NotificationMessage> notificationCaptor =
                ArgumentCaptor.forClass(TorrentWebSocketService.NotificationMessage.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/notifications"), notificationCaptor.capture());
        assertThat(notificationCaptor.getValue().event()).isEqualTo("torrent_removed");
        assertThat(notificationCaptor.getValue().torrentId()).isEqualTo(2L);
    }

    @Test
    @DisplayName("Should notify torrent completed")
    void testNotifyTorrentCompleted() {
        webSocketService.notifyTorrentCompleted(3L, "Done Torrent");

        ArgumentCaptor<TorrentWebSocketService.NotificationMessage> notificationCaptor =
                ArgumentCaptor.forClass(TorrentWebSocketService.NotificationMessage.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/notifications"), notificationCaptor.capture());
        assertThat(notificationCaptor.getValue().event()).isEqualTo("torrent_completed");
        assertThat(notificationCaptor.getValue().torrentId()).isEqualTo(3L);
        assertThat(notificationCaptor.getValue().torrentName()).isEqualTo("Done Torrent");
    }
}
