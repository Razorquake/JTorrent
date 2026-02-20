package com.example.jtorrent.controller;

import com.example.jtorrent.dto.AddTorrentFileResponse;
import com.example.jtorrent.dto.AddTorrentRequest;
import com.example.jtorrent.dto.MessageResponse;
import com.example.jtorrent.dto.TorrentResponse;
import com.example.jtorrent.exception.TorrentExceptions;
import com.example.jtorrent.model.TorrentStatus;
import com.example.jtorrent.security.SecurityConfig;
import com.example.jtorrent.service.TorrentFileUploadService;
import com.example.jtorrent.service.TorrentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TorrentController.class)
@Import(SecurityConfig.class)
@DisplayName("TorrentController Integration Tests")
public class TorrentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private TorrentService torrentService;

    @MockitoBean
    private TorrentFileUploadService uploadService;

    @Test
    @DisplayName("POST /api/torrents - Should add torrent successfully")
    void testAddTorrent_Success() throws Exception {
        // Given
        AddTorrentRequest request = new AddTorrentRequest();
        request.setMagnetLink("magnet:?xt=urn:btih:test123");
        request.setStartImmediately(true);

        // When/Then
        mockMvc.perform(post("/api/torrents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        verify(torrentService, times(1)).addTorrent(any(AddTorrentRequest.class));
    }

    @Test
    @DisplayName("POST /api/torrents - Should return 400 for invalid request")
    void testAddTorrent_InvalidRequest() throws Exception {
        // Given
        AddTorrentRequest request = new AddTorrentRequest();
        // Missing magnetLink (required field)

        // When/Then
        mockMvc.perform(post("/api/torrents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());

        verify(torrentService, never()).addTorrent(any());
    }

    @Test
    @DisplayName("GET /api/torrents - Should get all torrents")
    void testGetAllTorrents_Success() throws Exception {
        // Given
        TorrentResponse torrent1 = TorrentResponse.builder()
                .id(1L)
                .name("Torrent 1")
                .status(TorrentStatus.DOWNLOADING)
                .progress(50.0)
                .build();

        TorrentResponse torrent2 = TorrentResponse.builder()
                .id(2L)
                .name("Torrent 2")
                .status(TorrentStatus.COMPLETED)
                .progress(100.0)
                .build();

        when(torrentService.getAllTorrents()).thenReturn(List.of(torrent1, torrent2));

        // When/Then
        mockMvc.perform(get("/api/torrents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id", is(1)))
                .andExpect(jsonPath("$[0].name", is("Torrent 1")))
                .andExpect(jsonPath("$[0].status", is("DOWNLOADING")))
                .andExpect(jsonPath("$[1].id", is(2)))
                .andExpect(jsonPath("$[1].status", is("COMPLETED")));

        verify(torrentService, times(1)).getAllTorrents();
    }

    @Test
    @DisplayName("GET /api/torrents/{id} - Should get torrent by ID")
    void testGetTorrent_Success() throws Exception {
        // Given
        TorrentResponse torrent = TorrentResponse.builder()
                .id(1L)
                .infoHash("test-hash-123")
                .name("Test Torrent")
                .status(TorrentStatus.DOWNLOADING)
                .progress(75.0)
                .downloadSpeed(100000)
                .uploadSpeed(50000)
                .peers(10)
                .seeds(5)
                .totalSize(1000000L)
                .downloadedSize(750000L)
                .addedDate(LocalDateTime.now())
                .build();

        when(torrentService.getTorrent(1L)).thenReturn(torrent);

        // When/Then
        mockMvc.perform(get("/api/torrents/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(1)))
                .andExpect(jsonPath("$.name", is("Test Torrent")))
                .andExpect(jsonPath("$.status", is("DOWNLOADING")))
                .andExpect(jsonPath("$.progress", is(75.0)))
                .andExpect(jsonPath("$.peers", is(10)))
                .andExpect(jsonPath("$.seeds", is(5)));

        verify(torrentService, times(1)).getTorrent(1L);
    }

    @Test
    @DisplayName("POST /api/torrents/{id}/start - Should start torrent")
    void testStartTorrent_Success() throws Exception {
        when(torrentService.startTorrent(1L))
                .thenReturn(new MessageResponse("Torrent started successfully"));

        // When/Then
        mockMvc.perform(post("/api/torrents/1/start"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message", notNullValue()));

        verify(torrentService, times(1)).startTorrent(1L);
    }

    @Test
    @DisplayName("POST /api/torrents/{id}/pause - Should pause torrent")
    void testPauseTorrent_Success() throws Exception {
        when(torrentService.pauseTorrent(1L))
                .thenReturn(new MessageResponse("Torrent paused successfully"));

        // When/Then
        mockMvc.perform(post("/api/torrents/1/pause"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message", notNullValue()));

        verify(torrentService, times(1)).pauseTorrent(1L);
    }

    @Test
    @DisplayName("DELETE /api/torrents/{id} - Should remove torrent without deleting files")
    void testRemoveTorrent_WithoutDeletingFiles() throws Exception {
        when(torrentService.removeTorrent(1L, false))
                .thenReturn(new MessageResponse("Torrent removed successfully"));

        // When/Then
        mockMvc.perform(delete("/api/torrents/1")
                        .param("deleteFiles", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message", notNullValue()));

        verify(torrentService, times(1)).removeTorrent(1L, false);
    }

    @Test
    @DisplayName("DELETE /api/torrents/{id} - Should remove torrent with deleting files")
    void testRemoveTorrent_WithDeletingFiles() throws Exception {
        when(torrentService.removeTorrent(1L, true))
                .thenReturn(new MessageResponse("Torrent removed successfully"));

        // When/Then
        mockMvc.perform(delete("/api/torrents/1")
                        .param("deleteFiles", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message", notNullValue()));

        verify(torrentService, times(1)).removeTorrent(1L, true);
    }

    @Test
    @DisplayName("GET /api/torrents - Should handle empty torrent list")
    void testGetAllTorrents_EmptyList() throws Exception {
        // Given
        when(torrentService.getAllTorrents()).thenReturn(List.of());

        // When/Then
        mockMvc.perform(get("/api/torrents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        verify(torrentService, times(1)).getAllTorrents();
    }

    @Test
    @DisplayName("POST /api/torrents - Should handle service exceptions")
    void testAddTorrent_ServiceException() throws Exception {
        // Given
        AddTorrentRequest request = new AddTorrentRequest();
        request.setMagnetLink("magnet:?xt=urn:btih:test");

        when(torrentService.addTorrent(any(AddTorrentRequest.class)))
                .thenThrow(new TorrentExceptions.TorrentFileException("Service error"));

        // When/Then
        mockMvc.perform(post("/api/torrents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().is5xxServerError());
    }

    @Test
    @DisplayName("GET /api/torrents/{id} - Should handle not found exception")
    void testGetTorrent_NotFound() throws Exception {
        // Given
        when(torrentService.getTorrent(999L))
                .thenThrow(new com.example.jtorrent.exception.TorrentExceptions.TorrentNotFoundException(999L));

        // When/Then
        mockMvc.perform(get("/api/torrents/999"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /api/torrents - Should validate magnet link is not blank")
    void testAddTorrent_BlankMagnetLink() throws Exception {
        // Given
        AddTorrentRequest request = new AddTorrentRequest();
        request.setMagnetLink("");

        // When/Then
        mockMvc.perform(post("/api/torrents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("DELETE /api/torrents/{id} - Should default deleteFiles to false")
    void testRemoveTorrent_DefaultDeleteFiles() throws Exception {
        when(torrentService.removeTorrent(1L, false))
                .thenReturn(new MessageResponse("Torrent removed successfully"));

        // When/Then
        mockMvc.perform(delete("/api/torrents/1"))
                .andExpect(status().isOk());

        verify(torrentService, times(1)).removeTorrent(1L, false);
    }

    // ── GET /api/torrents/hash/{infoHash} ─────────────────────────────────────

    @Test
    @DisplayName("GET /api/torrents/hash/{infoHash} - Should return 200 with torrent data")
    void testGetTorrentByHash_Success() throws Exception {
        // Given
        TorrentResponse torrent = TorrentResponse.builder()
                .id(1L)
                .infoHash("abc123def456")
                .name("Test Torrent")
                .status(TorrentStatus.DOWNLOADING)
                .progress(42.0)
                .build();

        when(torrentService.getTorrentByHash("abc123def456")).thenReturn(torrent);

        // When/Then
        mockMvc.perform(get("/api/torrents/hash/abc123def456"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(1)))
                .andExpect(jsonPath("$.infoHash", is("abc123def456")))
                .andExpect(jsonPath("$.name", is("Test Torrent")))
                .andExpect(jsonPath("$.status", is("DOWNLOADING")));

        verify(torrentService).getTorrentByHash("abc123def456");
    }

    @Test
    @DisplayName("GET /api/torrents/hash/{infoHash} - Should return 404 for unknown hash")
    void testGetTorrentByHash_NotFound() throws Exception {
        // Given
        when(torrentService.getTorrentByHash("unknown"))
                .thenThrow(new TorrentExceptions.TorrentNotFoundException("unknown"));

        // When/Then
        mockMvc.perform(get("/api/torrents/hash/unknown"))
                .andExpect(status().isNotFound());

        verify(torrentService).getTorrentByHash("unknown");
    }

    // ── POST /api/torrents/{id}/recheck ───────────────────────────────────────

    @Test
    @DisplayName("POST /api/torrents/{id}/recheck - Should return 200 with confirmation")
    void testRecheckTorrent_Success() throws Exception {
        // Given
        when(torrentService.recheckTorrent(1L))
                .thenReturn(new MessageResponse("Torrent recheck started for: Test Torrent"));

        // When/Then
        mockMvc.perform(post("/api/torrents/1/recheck"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message", notNullValue()));

        verify(torrentService).recheckTorrent(1L);
    }

    @Test
    @DisplayName("POST /api/torrents/{id}/recheck - Should return 404 for unknown torrent")
    void testRecheckTorrent_NotFound() throws Exception {
        // Given
        when(torrentService.recheckTorrent(99L))
                .thenThrow(new TorrentExceptions.TorrentNotFoundException(99L));

        // When/Then
        mockMvc.perform(post("/api/torrents/99/recheck"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /api/torrents/{id}/recheck - Should return 400 for inactive torrent")
    void testRecheckTorrent_NotActive() throws Exception {
        // Given
        when(torrentService.recheckTorrent(1L))
                .thenThrow(new TorrentExceptions.TorrentNotActiveException(1L));

        // When/Then
        mockMvc.perform(post("/api/torrents/1/recheck"))
                .andExpect(status().isBadRequest());
    }

    // ── POST /api/torrents/{id}/reannounce ────────────────────────────────────

    @Test
    @DisplayName("POST /api/torrents/{id}/reannounce - Should return 200 with confirmation")
    void testReannounceTorrent_Success() throws Exception {
        // Given
        when(torrentService.reannounceTorrent(1L))
                .thenReturn(new MessageResponse("Re-announce sent to all trackers for: Test Torrent"));

        // When/Then
        mockMvc.perform(post("/api/torrents/1/reannounce"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message", notNullValue()));

        verify(torrentService).reannounceTorrent(1L);
    }

    @Test
    @DisplayName("POST /api/torrents/{id}/reannounce - Should return 404 for unknown torrent")
    void testReannounceTorrent_NotFound() throws Exception {
        // Given
        when(torrentService.reannounceTorrent(99L))
                .thenThrow(new TorrentExceptions.TorrentNotFoundException(99L));

        // When/Then
        mockMvc.perform(post("/api/torrents/99/reannounce"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /api/torrents/{id}/reannounce - Should return 400 for inactive torrent")
    void testReannounceTorrent_NotActive() throws Exception {
        // Given
        when(torrentService.reannounceTorrent(1L))
                .thenThrow(new TorrentExceptions.TorrentNotActiveException(1L));

        // When/Then
        mockMvc.perform(post("/api/torrents/1/reannounce"))
                .andExpect(status().isBadRequest());
    }

    // ── POST /api/torrents/upload ─────────────────────────────────────────────

    @Test
    @DisplayName("POST /api/torrents/upload - Should accept valid .torrent and return 201")
    void testUploadTorrentFile_Success() throws Exception {
        // Given
        MockMultipartFile torrentFile = new MockMultipartFile(
                "file",
                "ubuntu.torrent",
                "application/x-bittorrent",
                new byte[]{0x64, 0x65} // minimal non-empty content (real parsing is service-level)
        );

        AddTorrentFileResponse serviceResponse = AddTorrentFileResponse.builder()
                .torrentId(5L)
                .infoHash("deadbeef")
                .name("ubuntu-24.04-desktop-amd64")
                .totalSize(3_000_000_000L)
                .fileCount(1)
                .message("Torrent uploaded and added successfully")
                .started(true)
                .build();

        when(uploadService.addTorrentFromFile(any(), isNull(), eq(true)))
                .thenReturn(serviceResponse);

        // When/Then
        mockMvc.perform(multipart("/api/torrents/upload")
                        .file(torrentFile)
                        .param("startImmediately", "true"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.torrentId", is(5)))
                .andExpect(jsonPath("$.infoHash", is("deadbeef")))
                .andExpect(jsonPath("$.name", is("ubuntu-24.04-desktop-amd64")))
                .andExpect(jsonPath("$.fileCount", is(1)))
                .andExpect(jsonPath("$.started", is(true)));

        verify(uploadService).addTorrentFromFile(any(), isNull(), eq(true));
    }

    @Test
    @DisplayName("POST /api/torrents/upload - Should pass savePath and startImmediately=false to service")
    void testUploadTorrentFile_WithOptions() throws Exception {
        // Given
        MockMultipartFile torrentFile = new MockMultipartFile(
                "file", "linux.torrent", "application/x-bittorrent", new byte[]{1, 2, 3});

        AddTorrentFileResponse serviceResponse = AddTorrentFileResponse.builder()
                .torrentId(6L)
                .infoHash("cafe1234")
                .name("linux-mint")
                .totalSize(2_000_000_000L)
                .fileCount(1)
                .message("Torrent uploaded and added successfully")
                .started(false)
                .build();

        when(uploadService.addTorrentFromFile(any(), eq("/custom/path"), eq(false)))
                .thenReturn(serviceResponse);

        // When/Then
        mockMvc.perform(multipart("/api/torrents/upload")
                        .file(torrentFile)
                        .param("savePath", "/custom/path")
                        .param("startImmediately", "false"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.started", is(false)));

        verify(uploadService).addTorrentFromFile(any(), eq("/custom/path"), eq(false));
    }

    @Test
    @DisplayName("POST /api/torrents/upload - Should return 400 when service rejects invalid file")
    void testUploadTorrentFile_InvalidFile() throws Exception {
        // Given
        MockMultipartFile torrentFile = new MockMultipartFile(
                "file", "bad.torrent", "application/x-bittorrent", new byte[]{1});

        when(uploadService.addTorrentFromFile(any(), any(), anyBoolean()))
                .thenThrow(new TorrentExceptions.InvalidMagnetLinkException("Not valid bencode"));

        // When/Then
        mockMvc.perform(multipart("/api/torrents/upload")
                        .file(torrentFile))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/torrents/upload - Should return 409 for duplicate torrent")
    void testUploadTorrentFile_Duplicate() throws Exception {
        // Given
        MockMultipartFile torrentFile = new MockMultipartFile(
                "file", "dup.torrent", "application/x-bittorrent", new byte[]{1, 2, 3});

        when(uploadService.addTorrentFromFile(any(), any(), anyBoolean()))
                .thenThrow(new TorrentExceptions.TorrentAlreadyExistsException("deadbeef"));

        // When/Then
        mockMvc.perform(multipart("/api/torrents/upload")
                        .file(torrentFile))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("POST /api/torrents/upload - Should return 400 when no file part is supplied")
    void testUploadTorrentFile_MissingFilePart() throws Exception {
        // When/Then – no "file" part at all → Spring returns 400 automatically
        mockMvc.perform(multipart("/api/torrents/upload"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(uploadService);
    }
}
