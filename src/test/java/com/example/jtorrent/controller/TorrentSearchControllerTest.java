package com.example.jtorrent.controller;

import com.example.jtorrent.dto.TorrentResponse;
import com.example.jtorrent.mapper.TorrentMapper;
import com.example.jtorrent.model.Torrent;
import com.example.jtorrent.model.TorrentStatus;
import com.example.jtorrent.repository.TorrentRepository;
import com.example.jtorrent.security.SecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TorrentSearchController.class)
@Import(SecurityConfig.class)
@DisplayName("TorrentSearchController Integration Tests")
class TorrentSearchControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TorrentRepository torrentRepository;

    @MockitoBean
    private TorrentMapper torrentMapper;

    @Test
    @DisplayName("POST /api/torrents/search - Should search torrents with filters and ASC sort")
    void testSearchTorrents_WithFilters() throws Exception {
        Torrent torrent = buildTorrent(1L, "Ubuntu", TorrentStatus.DOWNLOADING);
        TorrentResponse response = buildResponse(1L, "Ubuntu", TorrentStatus.DOWNLOADING);
        Page<Torrent> page = new PageImpl<>(List.of(torrent), PageRequest.of(1, 5), 1);

        when(torrentRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);
        when(torrentMapper.toResponse(torrent)).thenReturn(response);

        String payload = """
                {
                  "status": "DOWNLOADING",
                  "statuses": ["DOWNLOADING", "PAUSED"],
                  "name": "ubuntu",
                  "minProgress": 10.0,
                  "maxProgress": 90.0,
                  "startDate": "2025-01-01T00:00:00",
                  "endDate": "2025-01-31T23:59:59",
                  "minSize": 1000,
                  "maxSize": 2000,
                  "hasErrors": true,
                  "sortBy": "name",
                  "sortDirection": "ASC",
                  "page": 1,
                  "size": 5
                }
                """;

        mockMvc.perform(post("/api/torrents/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id", is(1)))
                .andExpect(jsonPath("$.content[0].name", is("Ubuntu")));

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(torrentRepository, times(1)).findAll(any(Specification.class), pageableCaptor.capture());
        Pageable pageable = pageableCaptor.getValue();
        assertEquals(1, pageable.getPageNumber());
        assertEquals(5, pageable.getPageSize());
        assertEquals(Sort.Direction.ASC, pageable.getSort().getOrderFor("name").getDirection());
    }

    @Test
    @DisplayName("POST /api/torrents/search - Should handle DESC sort direction")
    void testSearchTorrents_DescSort() throws Exception {
        Torrent torrent = buildTorrent(2L, "Fedora", TorrentStatus.PAUSED);
        TorrentResponse response = buildResponse(2L, "Fedora", TorrentStatus.PAUSED);
        Page<Torrent> page = new PageImpl<>(List.of(torrent), PageRequest.of(0, 2), 1);

        when(torrentRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);
        when(torrentMapper.toResponse(torrent)).thenReturn(response);

        String payload = """
                {
                  "name": "fedora",
                  "sortBy": "addedDate",
                  "sortDirection": "DESC",
                  "page": 0,
                  "size": 2
                }
                """;

        mockMvc.perform(post("/api/torrents/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id", is(2)));

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(torrentRepository, times(1)).findAll(any(Specification.class), pageableCaptor.capture());
        Pageable pageable = pageableCaptor.getValue();
        assertEquals(Sort.Direction.DESC, pageable.getSort().getOrderFor("addedDate").getDirection());
    }

    @Test
    @DisplayName("POST /api/torrents/search - Should support empty filters")
    void testSearchTorrents_EmptyFilters() throws Exception {
        Torrent torrent = buildTorrent(10L, "Arch Linux", TorrentStatus.SEEDING);
        TorrentResponse response = buildResponse(10L, "Arch Linux", TorrentStatus.SEEDING);
        Page<Torrent> page = new PageImpl<>(List.of(torrent), PageRequest.of(0, 20), 1);

        when(torrentRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);
        when(torrentMapper.toResponse(torrent)).thenReturn(response);

        String payload = """
                {
                  "status": null,
                  "statuses": null,
                  "name": null,
                  "minProgress": null,
                  "maxProgress": null,
                  "startDate": null,
                  "endDate": null,
                  "minSize": null,
                  "maxSize": null,
                  "hasErrors": null,
                  "sortBy": "addedDate",
                  "sortDirection": "DESC",
                  "page": 0,
                  "size": 20
                }
                """;

        mockMvc.perform(post("/api/torrents/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id", is(10)));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Specification<Torrent>> specCaptor = ArgumentCaptor.forClass((Class) Specification.class);
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(torrentRepository, times(1)).findAll(specCaptor.capture(), pageableCaptor.capture());

        assertNotNull(specCaptor.getValue());
        Pageable pageable = pageableCaptor.getValue();
        assertEquals(0, pageable.getPageNumber());
        assertEquals(20, pageable.getPageSize());
        assertEquals(Sort.Direction.DESC, pageable.getSort().getOrderFor("addedDate").getDirection());
    }

    @Test
    @DisplayName("GET /api/torrents/search/status/{status} - Should get torrents by status")
    void testGetTorrentsByStatus() throws Exception {
        Torrent torrent = buildTorrent(3L, "Torrent 3", TorrentStatus.DOWNLOADING);
        TorrentResponse response = buildResponse(3L, "Torrent 3", TorrentStatus.DOWNLOADING);

        when(torrentRepository.findByStatus(TorrentStatus.DOWNLOADING)).thenReturn(List.of(torrent));
        when(torrentMapper.toResponse(torrent)).thenReturn(response);

        mockMvc.perform(get("/api/torrents/search/status/DOWNLOADING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id", is(3)));
    }

    @Test
    @DisplayName("GET /api/torrents/search/name - Should search torrents by name")
    void testSearchByName() throws Exception {
        Torrent torrent = buildTorrent(4L, "Ubuntu ISO", TorrentStatus.DOWNLOADING);
        TorrentResponse response = buildResponse(4L, "Ubuntu ISO", TorrentStatus.DOWNLOADING);

        when(torrentRepository.findByNameContainingIgnoreCase("ubuntu")).thenReturn(List.of(torrent));
        when(torrentMapper.toResponse(torrent)).thenReturn(response);

        mockMvc.perform(get("/api/torrents/search/name")
                        .param("name", "ubuntu"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name", is("Ubuntu ISO")));
    }

    @Test
    @DisplayName("GET /api/torrents/search/active - Should get active torrents")
    void testGetActiveTorrents() throws Exception {
        Torrent torrent = buildTorrent(5L, "Active Torrent", TorrentStatus.DOWNLOADING);
        TorrentResponse response = buildResponse(5L, "Active Torrent", TorrentStatus.DOWNLOADING);

        when(torrentRepository.findAllActive()).thenReturn(List.of(torrent));
        when(torrentMapper.toResponse(torrent)).thenReturn(response);

        mockMvc.perform(get("/api/torrents/search/active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id", is(5)));
    }

    @Test
    @DisplayName("GET /api/torrents/search/completed - Should get completed torrents")
    void testGetCompletedTorrents() throws Exception {
        Torrent torrent = buildTorrent(6L, "Completed Torrent", TorrentStatus.COMPLETED);
        TorrentResponse response = buildResponse(6L, "Completed Torrent", TorrentStatus.COMPLETED);

        when(torrentRepository.findByStatusAndCompletedDateIsNotNull(TorrentStatus.COMPLETED))
                .thenReturn(List.of(torrent));
        when(torrentMapper.toResponse(torrent)).thenReturn(response);

        mockMvc.perform(get("/api/torrents/search/completed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name", is("Completed Torrent")));
    }

    @Test
    @DisplayName("GET /api/torrents/search/errors - Should get error torrents")
    void testGetErrorTorrents() throws Exception {
        Torrent torrent = buildTorrent(7L, "Error Torrent", TorrentStatus.ERROR);
        TorrentResponse response = buildResponse(7L, "Error Torrent", TorrentStatus.ERROR);

        when(torrentRepository.findByStatusAndErrorMessageIsNotNull(TorrentStatus.ERROR))
                .thenReturn(List.of(torrent));
        when(torrentMapper.toResponse(torrent)).thenReturn(response);

        mockMvc.perform(get("/api/torrents/search/errors"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id", is(7)));
    }

    @Test
    @DisplayName("GET /api/torrents/search/progress - Should get torrents by progress range")
    void testGetTorrentsByProgress() throws Exception {
        Torrent torrent = buildTorrent(8L, "Progress Torrent", TorrentStatus.DOWNLOADING);
        TorrentResponse response = buildResponse(8L, "Progress Torrent", TorrentStatus.DOWNLOADING);

        when(torrentRepository.findByProgressBetween(10.0, 90.0)).thenReturn(List.of(torrent));
        when(torrentMapper.toResponse(torrent)).thenReturn(response);

        mockMvc.perform(get("/api/torrents/search/progress")
                        .param("minProgress", "10.0")
                        .param("maxProgress", "90.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name", is("Progress Torrent")));
    }

    @Test
    @DisplayName("GET /api/torrents/search/stalled - Should get stalled torrents")
    void testGetStalledTorrents() throws Exception {
        Torrent torrent = buildTorrent(9L, "Stalled Torrent", TorrentStatus.DOWNLOADING);
        TorrentResponse response = buildResponse(9L, "Stalled Torrent", TorrentStatus.DOWNLOADING);

        when(torrentRepository.findStalledTorrents()).thenReturn(List.of(torrent));
        when(torrentMapper.toResponse(torrent)).thenReturn(response);

        mockMvc.perform(get("/api/torrents/search/stalled"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id", is(9)));
    }

    @Test
    @DisplayName("GET /api/torrents/search/count/{status} - Should count torrents by status")
    void testCountTorrentsByStatus() throws Exception {
        when(torrentRepository.countByStatus(TorrentStatus.DOWNLOADING)).thenReturn(3L);

        mockMvc.perform(get("/api/torrents/search/count/DOWNLOADING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", is(3)));
    }

    private Torrent buildTorrent(Long id, String name, TorrentStatus status) {
        Torrent torrent = new Torrent();
        torrent.setId(id);
        torrent.setName(name);
        torrent.setStatus(status);
        return torrent;
    }

    private TorrentResponse buildResponse(Long id, String name, TorrentStatus status) {
        return TorrentResponse.builder()
                .id(id)
                .name(name)
                .status(status)
                .build();
    }
}
