package com.example.jtorrent.controller;

import com.example.jtorrent.security.SecurityConfig;
import com.example.jtorrent.service.TorrentSessionManager;
import com.frostwire.jlibtorrent.TorrentHandle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SystemController.class)
@Import(SecurityConfig.class)
@DisplayName("SystemController Integration Tests")
class SystemControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TorrentSessionManager sessionManager;

    @Test
    @DisplayName("GET /api/system/health - Should return UP when session is running")
    void testHealthCheck_Up() throws Exception {
        when(sessionManager.isRunning()).thenReturn(true);

        mockMvc.perform(get("/api/system/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("UP")))
                .andExpect(jsonPath("$.sessionRunning", is(true)))
                .andExpect(jsonPath("$.service", is("JTorrent")))
                .andExpect(jsonPath("$.version", is("1.0.0")))
                .andExpect(jsonPath("$.timestamp", notNullValue()));

        verify(sessionManager, times(1)).isRunning();
    }

    @Test
    @DisplayName("GET /api/system/health - Should return DOWN when session is not running")
    void testHealthCheck_Down() throws Exception {
        when(sessionManager.isRunning()).thenReturn(false);

        mockMvc.perform(get("/api/system/health"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status", is("DOWN")))
                .andExpect(jsonPath("$.sessionRunning", is(false)))
                .andExpect(jsonPath("$.timestamp", notNullValue()));

        verify(sessionManager, times(1)).isRunning();
    }

    @Test
    @DisplayName("GET /api/system/info - Should return info when session is running")
    void testGetSystemInfo_Running() throws Exception {
        when(sessionManager.isRunning()).thenReturn(true);
        Map<String, TorrentHandle> active = new HashMap<>();
        active.put("abc", mock(TorrentHandle.class));
        when(sessionManager.getActiveTorrents()).thenReturn(active);
        when(sessionManager.getDownloadDirectory()).thenReturn(new File("C:\\downloads"));

        mockMvc.perform(get("/api/system/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service", is("JTorrent")))
                .andExpect(jsonPath("$.sessionRunning", is(true)))
                .andExpect(jsonPath("$.activeTorrents", is(1)))
                .andExpect(jsonPath("$.downloadDirectory", is("C:\\downloads")))
                .andExpect(jsonPath("$.timestamp", notNullValue()));
    }

    @Test
    @DisplayName("GET /api/system/info - Should omit session details when not running")
    void testGetSystemInfo_NotRunning() throws Exception {
        when(sessionManager.isRunning()).thenReturn(false);

        mockMvc.perform(get("/api/system/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service", is("JTorrent")))
                .andExpect(jsonPath("$.sessionRunning", is(false)))
                .andExpect(jsonPath("$.activeTorrents").doesNotExist())
                .andExpect(jsonPath("$.downloadDirectory").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/system/ping - Should return pong message")
    void testPing() throws Exception {
        mockMvc.perform(get("/api/system/ping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message", is("pong")))
                .andExpect(jsonPath("$.timestamp", notNullValue()));
    }
}
