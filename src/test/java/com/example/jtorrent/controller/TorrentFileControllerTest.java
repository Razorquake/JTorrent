package com.example.jtorrent.controller;

import com.example.jtorrent.dto.MessageResponse;
import com.example.jtorrent.dto.TorrentFileResponse;
import com.example.jtorrent.dto.UpdateFilePrioritiesRequest;
import com.example.jtorrent.security.SecurityConfig;
import com.example.jtorrent.service.TorrentFileService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TorrentFileController.class)
@Import(SecurityConfig.class)
@DisplayName("TorrentFileController Integration Tests")
class TorrentFileControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TorrentFileService fileService;

    @Test
    @DisplayName("GET /api/torrents/{id}/files - Should get torrent files")
    void testGetTorrentFiles() throws Exception {
        TorrentFileResponse file = buildFileResponse(1L, "video.mp4");

        when(fileService.getTorrentFiles(1L)).thenReturn(List.of(file));

        mockMvc.perform(get("/api/torrents/1/files"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id", is(1)))
                .andExpect(jsonPath("$[0].path", is("video.mp4")));
    }

    @Test
    @DisplayName("PUT /api/torrents/{id}/files/priorities - Should update file priorities")
    void testUpdateFilePriorities() throws Exception {
        when(fileService.updateFilePriorities(eq(1L), any(UpdateFilePrioritiesRequest.class)))
                .thenReturn(new MessageResponse("Updated priorities"));

        String payload = """
                {
                  "fileIds": [1, 2],
                  "priority": 7
                }
                """;

        mockMvc.perform(put("/api/torrents/1/files/priorities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message", is("Updated priorities")))
                .andExpect(jsonPath("$.timestamp", notNullValue()));
    }

    @Test
    @DisplayName("POST /api/torrents/{id}/files/skip - Should skip files")
    void testSkipFiles() throws Exception {
        when(fileService.skipFiles(eq(1L), any())).thenReturn(new MessageResponse("Skipped"));

        String payload = """
                {
                  "fileIds": [1, 2]
                }
                """;

        mockMvc.perform(post("/api/torrents/1/files/skip")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message", is("Skipped")));
    }

    @Test
    @DisplayName("POST /api/torrents/{id}/files/download - Should download files")
    void testDownloadFiles() throws Exception {
        when(fileService.downloadFiles(eq(1L), any())).thenReturn(new MessageResponse("Downloading"));

        String payload = """
                {
                  "fileIds": [3, 4]
                }
                """;

        mockMvc.perform(post("/api/torrents/1/files/download")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message", is("Downloading")));
    }

    @Test
    @DisplayName("GET /api/torrents/{id}/files/skipped - Should get skipped files")
    void testGetSkippedFiles() throws Exception {
        TorrentFileResponse file = buildFileResponse(2L, "skip.txt");

        when(fileService.getSkippedFiles(1L)).thenReturn(List.of(file));

        mockMvc.perform(get("/api/torrents/1/files/skipped"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id", is(2)));
    }

    @Test
    @DisplayName("GET /api/torrents/{id}/files/incomplete - Should get incomplete files")
    void testGetIncompleteFiles() throws Exception {
        TorrentFileResponse file = buildFileResponse(3L, "partial.mkv");

        when(fileService.getIncompleteFiles(1L)).thenReturn(List.of(file));

        mockMvc.perform(get("/api/torrents/1/files/incomplete"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].path", is("partial.mkv")));
    }

    @Test
    @DisplayName("GET /api/torrents/{id}/files/priority/{priority} - Should get files by priority")
    void testGetFilesByPriority() throws Exception {
        TorrentFileResponse file = buildFileResponse(4L, "high.prio");

        when(fileService.getFilesByPriority(1L, 7)).thenReturn(List.of(file));

        mockMvc.perform(get("/api/torrents/1/files/priority/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id", is(4)));
    }

    @Test
    @DisplayName("POST /api/torrents/{id}/files/prioritize - Should prioritize files")
    void testPrioritizeFiles() throws Exception {
        when(fileService.prioritizeFiles(eq(1L), anyList()))
                .thenReturn(new MessageResponse("Prioritized"));

        String payload = """
                {
                  "fileIds": [5, 6]
                }
                """;

        mockMvc.perform(post("/api/torrents/1/files/prioritize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message", is("Prioritized")));
    }

    @Test
    @DisplayName("POST /api/torrents/{id}/files/deprioritize - Should deprioritize files")
    void testDeprioritizeFiles() throws Exception {
        when(fileService.deprioritizeFiles(eq(1L), anyList()))
                .thenReturn(new MessageResponse("Deprioritized"));

        String payload = """
                {
                  "fileIds": [7, 8]
                }
                """;

        mockMvc.perform(post("/api/torrents/1/files/deprioritize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message", is("Deprioritized")));
    }

    @Test
    @DisplayName("POST /api/torrents/{id}/files/reset-priorities - Should reset priorities")
    void testResetFilePriorities() throws Exception {
        when(fileService.resetFilePriorities(1L)).thenReturn(new MessageResponse("Reset priorities"));

        mockMvc.perform(post("/api/torrents/1/files/reset-priorities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message", is("Reset priorities")));
    }

    @Test
    @DisplayName("POST /api/torrents/{id}/files/skip-by-extension - Should skip files by extension")
    void testSkipFilesByExtension() throws Exception {
        when(fileService.skipFilesByExtension(1L, "txt"))
                .thenReturn(new MessageResponse("Skipped by extension"));

        mockMvc.perform(post("/api/torrents/1/files/skip-by-extension")
                        .param("extension", "txt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message", is("Skipped by extension")));
    }

    @Test
    @DisplayName("GET /api/torrents/{id}/files/search - Should search files by pattern")
    void testSearchFiles() throws Exception {
        TorrentFileResponse file = buildFileResponse(9L, "readme.txt");

        when(fileService.getFilesByPattern(1L, "readme")).thenReturn(List.of(file));

        mockMvc.perform(get("/api/torrents/1/files/search")
                        .param("pattern", "readme"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].path", is("readme.txt")));
    }

    private TorrentFileResponse buildFileResponse(Long id, String path) {
        return TorrentFileResponse.builder()
                .id(id)
                .path(path)
                .size(100L)
                .downloadedSize(50L)
                .progress(50.0)
                .priority(4)
                .build();
    }
}
