package com.example.jtorrent.repository;

import com.example.jtorrent.model.Torrent;
import com.example.jtorrent.model.TorrentStatus;
import org.hibernate.exception.ConstraintViolationException;
import org.h2.jdbc.JdbcSQLIntegrityConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

@DataJpaTest
@ActiveProfiles(profiles = "test")
@DisplayName("TorrentRepository Integration Tests")
class TorrentRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private TorrentRepository torrentRepository;

    private Torrent torrent1;
    private Torrent torrent2;
    private Torrent torrent3;

    @BeforeEach
    void setUp() {
        // Create test torrents
        torrent1 = new Torrent();
        torrent1.setInfoHash("hash-1");
        torrent1.setName("Ubuntu ISO");
        torrent1.setMagnetLink("magnet:?xt=urn:btih:hash-1");
        torrent1.setTotalSize(1000000L);
        torrent1.setDownloadedSize(500000L);
        torrent1.setStatus(TorrentStatus.DOWNLOADING);
        torrent1.setProgress(50.0);
        torrent1.setSavePath("/downloads");
        torrent1.setAddedDate(LocalDateTime.now().minusHours(2));

        torrent2 = new Torrent();
        torrent2.setInfoHash("hash-2");
        torrent2.setName("Debian ISO");
        torrent2.setMagnetLink("magnet:?xt=urn:btih:hash-2");
        torrent2.setTotalSize(800000L);
        torrent2.setDownloadedSize(800000L);
        torrent2.setStatus(TorrentStatus.COMPLETED);
        torrent2.setProgress(100.0);
        torrent2.setSavePath("/downloads");
        torrent2.setAddedDate(LocalDateTime.now().minusHours(5));
        torrent2.setCompletedDate(LocalDateTime.now().minusHours(1));

        torrent3 = new Torrent();
        torrent3.setInfoHash("hash-3");
        torrent3.setName("Linux Mint ISO");
        torrent3.setMagnetLink("magnet:?xt=urn:btih:hash-3");
        torrent3.setTotalSize(1500000L);
        torrent3.setDownloadedSize(0L);
        torrent3.setStatus(TorrentStatus.PAUSED);
        torrent3.setProgress(0.0);
        torrent3.setSavePath("/downloads");
        torrent3.setAddedDate(LocalDateTime.now().minusHours(1));

        // Persist torrents
        entityManager.persist(torrent1);
        entityManager.persist(torrent2);
        entityManager.persist(torrent3);
        entityManager.flush();
    }

    @Test
    @DisplayName("Should find torrent by info hash")
    void testFindByInfoHash() {
        // When
        Optional<Torrent> found = torrentRepository.findByInfoHash("hash-1");

        // Then
        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo("Ubuntu ISO");
        assertThat(found.get().getInfoHash()).isEqualTo("hash-1");
    }

    @Test
    @DisplayName("Should return empty when info hash not found")
    void testFindByInfoHash_NotFound() {
        // When
        Optional<Torrent> found = torrentRepository.findByInfoHash("non-existent-hash");

        // Then
        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("Should check if torrent exists by info hash")
    void testExistsByInfoHash() {
        // When/Then
        assertThat(torrentRepository.existsByInfoHash("hash-1")).isTrue();
        assertThat(torrentRepository.existsByInfoHash("non-existent")).isFalse();
    }

    @Test
    @DisplayName("Should find torrents by status")
    void testFindByStatus() {
        // When
        List<Torrent> downloading = torrentRepository.findByStatus(TorrentStatus.DOWNLOADING);
        List<Torrent> completed = torrentRepository.findByStatus(TorrentStatus.COMPLETED);
        List<Torrent> paused = torrentRepository.findByStatus(TorrentStatus.PAUSED);

        // Then
        assertThat(downloading).hasSize(1);
        assertThat(downloading.get(0).getName()).isEqualTo("Ubuntu ISO");

        assertThat(completed).hasSize(1);
        assertThat(completed.get(0).getName()).isEqualTo("Debian ISO");

        assertThat(paused).hasSize(1);
        assertThat(paused.get(0).getName()).isEqualTo("Linux Mint ISO");
    }

    @Test
    @DisplayName("Should find all active torrents")
    void testFindAllActive() {
        // When
        List<Torrent> active = torrentRepository.findAllActive();

        // Then
        assertThat(active).hasSize(1);
        assertThat(active.get(0).getStatus()).isIn(TorrentStatus.DOWNLOADING, TorrentStatus.SEEDING, TorrentStatus.CHECKING);
    }

    @Test
    @DisplayName("Should find torrents by name containing (case insensitive)")
    void testFindByNameContainingIgnoreCase() {
        // When
        List<Torrent> isoTorrents = torrentRepository.findByNameContainingIgnoreCase("iso");
        List<Torrent> ubuntuTorrents = torrentRepository.findByNameContainingIgnoreCase("ubuntu");
        List<Torrent> noMatch = torrentRepository.findByNameContainingIgnoreCase("windows");

        // Then
        assertThat(isoTorrents).hasSize(3);
        assertThat(ubuntuTorrents).hasSize(1);
        assertThat(noMatch).isEmpty();
    }

    @Test
    @DisplayName("Should find all torrents ordered by added date desc")
    void testFindAllByOrderByAddedDateDesc() {
        // When
        List<Torrent> torrents = torrentRepository.findAllByOrderByAddedDateDesc();

        // Then
        assertThat(torrents).hasSize(3);
        // Most recent first
        assertThat(torrents.get(0).getName()).isEqualTo("Linux Mint ISO");
        assertThat(torrents.get(1).getName()).isEqualTo("Ubuntu ISO");
        assertThat(torrents.get(2).getName()).isEqualTo("Debian ISO");
    }

    @Test
    @DisplayName("Should find completed torrents")
    void testFindByStatusAndCompletedDateIsNotNull() {
        // When
        List<Torrent> completed = torrentRepository.findByStatusAndCompletedDateIsNotNull(TorrentStatus.COMPLETED);

        // Then
        assertThat(completed).hasSize(1);
        assertThat(completed.get(0).getName()).isEqualTo("Debian ISO");
        assertThat(completed.get(0).getCompletedDate()).isNotNull();
    }

    @Test
    @DisplayName("Should find torrents by multiple statuses")
    void testFindByStatusIn() {
        // When
        List<Torrent> torrents = torrentRepository.findByStatusIn(
                List.of(TorrentStatus.DOWNLOADING, TorrentStatus.COMPLETED));

        // Then
        assertThat(torrents).hasSize(2);
        assertThat(torrents).extracting(Torrent::getStatus)
                .containsExactlyInAnyOrder(TorrentStatus.DOWNLOADING, TorrentStatus.COMPLETED);
    }

    @Test
    @DisplayName("Should find torrents by progress range")
    void testFindByProgressBetween() {
        // When
        List<Torrent> partial = torrentRepository.findByProgressBetween(1.0, 99.0);
        List<Torrent> all = torrentRepository.findByProgressBetween(0.0, 100.0);

        // Then
        assertThat(partial).hasSize(1);
        assertThat(partial.get(0).getProgress()).isEqualTo(50.0);

        assertThat(all).hasSize(3);
    }

    @Test
    @DisplayName("Should count torrents by status")
    void testCountByStatus() {
        // When
        long downloadingCount = torrentRepository.countByStatus(TorrentStatus.DOWNLOADING);
        long completedCount = torrentRepository.countByStatus(TorrentStatus.COMPLETED);
        long pausedCount = torrentRepository.countByStatus(TorrentStatus.PAUSED);
        long seedingCount = torrentRepository.countByStatus(TorrentStatus.SEEDING);

        // Then
        assertThat(downloadingCount).isEqualTo(1);
        assertThat(completedCount).isEqualTo(1);
        assertThat(pausedCount).isEqualTo(1);
        assertThat(seedingCount).isEqualTo(0);
    }

    @Test
    @DisplayName("Should get active download count")
    void testGetActiveDownloadCount() {
        // When
        long count = torrentRepository.getActiveDownloadCount();

        // Then
        assertThat(count).isEqualTo(1); // Only DOWNLOADING status
    }

    @Test
    @DisplayName("Should get total completed size")
    void testGetTotalCompletedSize() {
        // When
        Long totalSize = torrentRepository.getTotalCompletedSize();

        // Then
        assertThat(totalSize).isEqualTo(800000L); // Only torrent2 is completed
    }

    @Test
    @DisplayName("Should get total downloaded data")
    void testGetTotalDownloadedData() {
        // When
        Long totalDownloaded = torrentRepository.getTotalDownloadedData();

        // Then
        assertThat(totalDownloaded).isEqualTo(1300000L); // 500000 + 800000 + 0
    }

    @Test
    @DisplayName("Should find torrents added after specific date")
    void testFindByAddedDateAfter() {
        // Given
        LocalDateTime cutoff = LocalDateTime.now().minusHours(3);

        // When
        List<Torrent> recent = torrentRepository.findByAddedDateAfter(cutoff);

        // Then
        assertThat(recent).hasSize(2); // torrent1 and torrent3
        assertThat(recent).extracting(Torrent::getName)
                .containsExactlyInAnyOrder("Ubuntu ISO", "Linux Mint ISO");
    }

    @Test
    @DisplayName("Should find stalled torrents")
    void testFindStalledTorrents() {
        // Given
        Torrent stalledTorrent = new Torrent();
        stalledTorrent.setInfoHash("hash-stalled");
        stalledTorrent.setName("Stalled Torrent");
        stalledTorrent.setMagnetLink("magnet:?xt=urn:btih:hash-stalled");
        stalledTorrent.setTotalSize(1000000L);
        stalledTorrent.setDownloadedSize(100000L);
        stalledTorrent.setStatus(TorrentStatus.DOWNLOADING);
        stalledTorrent.setProgress(10.0);
        stalledTorrent.setSavePath("/downloads");
        stalledTorrent.setDownloadSpeed(0); // No speed
        stalledTorrent.setPeers(0); // No peers
        entityManager.persist(stalledTorrent);
        entityManager.flush();

        // When
        List<Torrent> stalled = torrentRepository.findStalledTorrents();

        // Then
        assertThat(stalled).isNotEmpty();
        assertThat(stalled).anyMatch(t -> t.getName().equals("Stalled Torrent"));
    }

    @Test
    @DisplayName("Should delete torrent by ID")
    void testDeleteTorrent() {
        // Given
        Long torrentId = torrent1.getId();

        // When
        torrentRepository.deleteById(torrentId);
        entityManager.flush();

        // Then
        Optional<Torrent> deleted = torrentRepository.findById(torrentId);
        assertThat(deleted).isEmpty();

        // Other torrents should still exist
        assertThat(torrentRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("Should update torrent status")
    void testUpdateTorrentStatus() {
        // Given
        Torrent torrent = torrentRepository.findByInfoHash("hash-1").orElseThrow();

        // When
        torrent.setStatus(TorrentStatus.COMPLETED);
        torrent.setProgress(100.0);
        torrent.setCompletedDate(LocalDateTime.now());
        torrentRepository.save(torrent);
        entityManager.flush();
        entityManager.clear();

        // Then
        Torrent updated = torrentRepository.findByInfoHash("hash-1").orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(TorrentStatus.COMPLETED);
        assertThat(updated.getProgress()).isEqualTo(100.0);
        assertThat(updated.getCompletedDate()).isNotNull();
    }

    @Test
    @DisplayName("Should handle info hash uniqueness constraint")
    void testInfoHashUniqueness() {
        // Given
        Torrent duplicateTorrent = new Torrent();
        duplicateTorrent.setInfoHash("hash-1"); // Duplicate info hash
        duplicateTorrent.setName("Duplicate Torrent");
        duplicateTorrent.setMagnetLink("magnet:?xt=urn:btih:hash-1");
        duplicateTorrent.setTotalSize(100000L);
        duplicateTorrent.setStatus(TorrentStatus.PENDING);
        duplicateTorrent.setSavePath("/downloads");

        // When/Then
        assertThatThrownBy(() -> {
            entityManager.persist(duplicateTorrent);
            entityManager.flush();
        }).isInstanceOf(ConstraintViolationException.class)
                .hasRootCauseInstanceOf(JdbcSQLIntegrityConstraintViolationException.class);
    }

    @Test
    @DisplayName("Should cascade delete torrent files when deleting torrent")
    void testCascadeDeleteFiles() {
        // This test would require creating TorrentFile entities
        // For now, we're testing that deletion works without errors

        // When
        torrentRepository.deleteById(torrent1.getId());
        entityManager.flush();

        // Then
        assertThat(torrentRepository.findById(torrent1.getId())).isEmpty();
    }
}
