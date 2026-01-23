package com.example.jtorrent.mapper;

import com.example.jtorrent.dto.TorrentFileResponse;
import com.example.jtorrent.dto.TorrentResponse;
import com.example.jtorrent.model.Torrent;
import com.example.jtorrent.model.TorrentFile;
import com.example.jtorrent.model.TorrentStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TorrentMapper Unit Tests")
class TorrentMapperTest {

    private final TorrentMapper mapper = new TorrentMapper();

    @Test
    @DisplayName("toResponse should map all fields and files")
    void testToResponse_MapsFields() {
        LocalDateTime added = LocalDateTime.of(2025, 1, 10, 10, 0, 0);
        LocalDateTime completed = LocalDateTime.of(2025, 1, 12, 12, 0, 0);
        LocalDateTime creation = LocalDateTime.of(2025, 1, 1, 0, 0, 0);

        Torrent torrent = new Torrent();
        torrent.setId(10L);
        torrent.setInfoHash("hash-10");
        torrent.setName("Ubuntu ISO");
        torrent.setMagnetLink("magnet:?xt=urn:btih:hash-10");
        torrent.setTotalSize(1024L);
        torrent.setDownloadedSize(512L);
        torrent.setUploadedSize(256L);
        torrent.setStatus(TorrentStatus.DOWNLOADING);
        torrent.setProgress(50.0);
        torrent.setDownloadSpeed(1000);
        torrent.setUploadSpeed(2000);
        torrent.setPeers(5);
        torrent.setSeeds(2);
        torrent.setSavePath("C:\\downloads");
        torrent.setAddedDate(added);
        torrent.setCompletedDate(completed);
        torrent.setErrorMessage("No error");
        torrent.setComment("Test comment");
        torrent.setCreatedBy("tester");
        torrent.setCreationDate(creation);

        TorrentFile file1 = new TorrentFile();
        file1.setId(1L);
        file1.setPath("file1.iso");
        file1.setSize(100L);
        file1.setDownloadedSize(50L);
        file1.setProgress(50.0);
        file1.setPriority(4);

        TorrentFile file2 = new TorrentFile();
        file2.setId(2L);
        file2.setPath("file2.nfo");
        file2.setSize(20L);
        file2.setDownloadedSize(20L);
        file2.setProgress(100.0);
        file2.setPriority(7);

        torrent.setFiles(List.of(file1, file2));

        TorrentResponse response = mapper.toResponse(torrent);

        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo(10L);
        assertThat(response.getInfoHash()).isEqualTo("hash-10");
        assertThat(response.getName()).isEqualTo("Ubuntu ISO");
        assertThat(response.getMagnetLink()).isEqualTo("magnet:?xt=urn:btih:hash-10");
        assertThat(response.getTotalSize()).isEqualTo(1024L);
        assertThat(response.getDownloadedSize()).isEqualTo(512L);
        assertThat(response.getUploadedSize()).isEqualTo(256L);
        assertThat(response.getStatus()).isEqualTo(TorrentStatus.DOWNLOADING);
        assertThat(response.getProgress()).isEqualTo(50.0);
        assertThat(response.getDownloadSpeed()).isEqualTo(1000);
        assertThat(response.getUploadSpeed()).isEqualTo(2000);
        assertThat(response.getPeers()).isEqualTo(5);
        assertThat(response.getSeeds()).isEqualTo(2);
        assertThat(response.getSavePath()).isEqualTo("C:\\downloads");
        assertThat(response.getAddedDate()).isEqualTo(added);
        assertThat(response.getCompletedDate()).isEqualTo(completed);
        assertThat(response.getErrorMessage()).isEqualTo("No error");
        assertThat(response.getComment()).isEqualTo("Test comment");
        assertThat(response.getCreatedBy()).isEqualTo("tester");
        assertThat(response.getCreationDate()).isEqualTo(creation);
        assertThat(response.getFiles()).hasSize(2);

        TorrentFileResponse mappedFile = response.getFiles().get(0);
        assertThat(mappedFile.getId()).isEqualTo(1L);
        assertThat(mappedFile.getPath()).isEqualTo("file1.iso");
    }

    @Test
    @DisplayName("toResponse should return null when input is null")
    void testToResponse_Null() {
        assertThat(mapper.toResponse(null)).isNull();
    }

    @Test
    @DisplayName("toFileResponse should map file fields")
    void testToFileResponse_MapsFields() {
        TorrentFile file = new TorrentFile();
        file.setId(99L);
        file.setPath("file99.bin");
        file.setSize(2048L);
        file.setDownloadedSize(1024L);
        file.setProgress(50.0);
        file.setPriority(1);

        TorrentFileResponse response = mapper.toFileResponse(file);

        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo(99L);
        assertThat(response.getPath()).isEqualTo("file99.bin");
        assertThat(response.getSize()).isEqualTo(2048L);
        assertThat(response.getDownloadedSize()).isEqualTo(1024L);
        assertThat(response.getProgress()).isEqualTo(50.0);
        assertThat(response.getPriority()).isEqualTo(1);
    }

    @Test
    @DisplayName("toFileResponse should return null when input is null")
    void testToFileResponse_Null() {
        assertThat(mapper.toFileResponse(null)).isNull();
    }
}
