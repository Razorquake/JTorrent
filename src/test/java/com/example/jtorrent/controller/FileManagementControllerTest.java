package com.example.jtorrent.controller;

import com.example.jtorrent.security.SecurityConfig;
import com.example.jtorrent.service.FileManagementService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FileManagementController.class)
@Import(SecurityConfig.class)
@DisplayName("FileManagementController Integration Tests")
class FileManagementControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FileManagementService fileManagementService;

    // ── GET /api/files/storage ────────────────────────────────────────────────

    @Test
    @DisplayName("GET /api/files/storage - Should return 200 with storage info map")
    void testGetStorageInfo_Success() throws Exception {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("path", "/downloads");
        info.put("totalBytes", 107374182400L);
        info.put("usedBytes", 53687091200L);
        info.put("freeBytes", 53687091200L);
        info.put("totalFormatted", "100.00 GB");
        info.put("usedFormatted", "50.00 GB");
        info.put("freeFormatted", "50.00 GB");
        info.put("usedByTorrentsBytes", 2147483648L);
        info.put("usedByTorrentsFormatted", "2.00 GB");

        when(fileManagementService.getStorageInfo()).thenReturn(info);

        mockMvc.perform(get("/api/files/storage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.path", is("/downloads")))
                .andExpect(jsonPath("$.totalBytes", is(107374182400L)))
                .andExpect(jsonPath("$.freeBytes", is(53687091200L)))
                .andExpect(jsonPath("$.usedByTorrentsFormatted", is("2.00 GB")));

        verify(fileManagementService).getStorageInfo();
    }

    // ── GET /api/files/orphans ────────────────────────────────────────────────

    @Test
    @DisplayName("GET /api/files/orphans - Should return list of orphaned file paths")
    void testFindOrphanedFiles_WithOrphans() throws Exception {
        when(fileManagementService.findOrphanedFiles())
                .thenReturn(List.of("/downloads/stale1.dat", "/downloads/stale2.dat"));

        mockMvc.perform(get("/api/files/orphans"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0]", containsString("stale1.dat")))
                .andExpect(jsonPath("$[1]", containsString("stale2.dat")));

        verify(fileManagementService).findOrphanedFiles();
    }

    @Test
    @DisplayName("GET /api/files/orphans - Should return empty list when no orphans")
    void testFindOrphanedFiles_None() throws Exception {
        when(fileManagementService.findOrphanedFiles()).thenReturn(List.of());

        mockMvc.perform(get("/api/files/orphans"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    // ── DELETE /api/files/orphans ─────────────────────────────────────────────

    @Test
    @DisplayName("DELETE /api/files/orphans - Should return count and success message")
    void testCleanupOrphanedFiles_Deleted() throws Exception {
        when(fileManagementService.cleanupOrphanedFiles()).thenReturn(3);

        mockMvc.perform(delete("/api/files/orphans"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deletedCount", is(3)))
                .andExpect(jsonPath("$.message", containsString("3")));

        verify(fileManagementService).cleanupOrphanedFiles();
    }

    @Test
    @DisplayName("DELETE /api/files/orphans - Should return 'no orphans' message when count is 0")
    void testCleanupOrphanedFiles_NoneFound() throws Exception {
        when(fileManagementService.cleanupOrphanedFiles()).thenReturn(0);

        mockMvc.perform(delete("/api/files/orphans"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deletedCount", is(0)))
                .andExpect(jsonPath("$.message", containsString("No orphaned")));
    }
}
