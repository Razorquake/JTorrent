package com.example.jtorrent.repository.specification;

import com.example.jtorrent.model.Torrent;
import com.example.jtorrent.model.TorrentStatus;
import com.example.jtorrent.repository.TorrentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles(profiles = "test")
@DisplayName("TorrentSpecification Integration Tests")
class TorrentSpecificationTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private TorrentRepository torrentRepository;

    private LocalDateTime baseTime;

    @BeforeEach
    void setUp() {
        baseTime = LocalDateTime.of(2025, 1, 15, 12, 0, 0);

        Torrent torrent1 = buildTorrent(
                "hash-1",
                "Ubuntu ISO",
                TorrentStatus.DOWNLOADING,
                50.0,
                1500L,
                baseTime.minusDays(2)
        );

        Torrent torrent2 = buildTorrent(
                "hash-2",
                "Debian ISO",
                TorrentStatus.ERROR,
                5.0,
                500L,
                baseTime.minusDays(1)
        );
        torrent2.setErrorMessage("Disk error");

        Torrent torrent3 = buildTorrent(
                "hash-3",
                "Arch Linux",
                TorrentStatus.COMPLETED,
                100.0,
                3000L,
                baseTime.minusDays(5)
        );
        torrent3.setCompletedDate(baseTime.minusDays(1));

        Torrent torrent4 = buildTorrent(
                "hash-4",
                "Mint ISO",
                TorrentStatus.PAUSED,
                0.0,
                800L,
                baseTime.minusHours(6)
        );

        entityManager.persist(torrent1);
        entityManager.persist(torrent2);
        entityManager.persist(torrent3);
        entityManager.persist(torrent4);
        entityManager.flush();
    }

    @Test
    @DisplayName("hasStatus should filter by status and return all when null")
    void testHasStatus() {
        List<Torrent> downloading = torrentRepository.findAll(
                TorrentSpecification.hasStatus(TorrentStatus.DOWNLOADING));
        assertThat(downloading).extracting(Torrent::getInfoHash)
                .containsExactly("hash-1");

        List<Torrent> all = torrentRepository.findAll(TorrentSpecification.hasStatus(null));
        assertThat(all).hasSize(4);
    }

    @Test
    @DisplayName("hasStatusIn should filter by list and return all when null or empty")
    void testHasStatusIn() {
        List<Torrent> selected = torrentRepository.findAll(
                TorrentSpecification.hasStatusIn(List.of(TorrentStatus.ERROR, TorrentStatus.PAUSED)));
        assertThat(selected).extracting(Torrent::getStatus)
                .containsExactlyInAnyOrder(TorrentStatus.ERROR, TorrentStatus.PAUSED);

        List<Torrent> allEmpty = torrentRepository.findAll(
                TorrentSpecification.hasStatusIn(List.of()));
        assertThat(allEmpty).hasSize(4);

        List<Torrent> allNull = torrentRepository.findAll(
                TorrentSpecification.hasStatusIn(null));
        assertThat(allNull).hasSize(4);
    }

    @Test
    @DisplayName("nameContains should filter case-insensitively and return all when null")
    void testNameContains() {
        List<Torrent> iso = torrentRepository.findAll(TorrentSpecification.nameContains("iso"));
        assertThat(iso).extracting(Torrent::getInfoHash)
                .containsExactlyInAnyOrder("hash-1", "hash-2", "hash-4");

        List<Torrent> all = torrentRepository.findAll(TorrentSpecification.nameContains(null));
        assertThat(all).hasSize(4);
    }

    @Test
    @DisplayName("progressBetween should handle min, max, both, and nulls")
    void testProgressBetween() {
        List<Torrent> all = torrentRepository.findAll(
                TorrentSpecification.progressBetween(null, null));
        assertThat(all).hasSize(4);

        List<Torrent> minOnly = torrentRepository.findAll(
                TorrentSpecification.progressBetween(10.0, null));
        assertThat(minOnly).extracting(Torrent::getInfoHash)
                .containsExactlyInAnyOrder("hash-1", "hash-3");

        List<Torrent> maxOnly = torrentRepository.findAll(
                TorrentSpecification.progressBetween(null, 10.0));
        assertThat(maxOnly).extracting(Torrent::getInfoHash)
                .containsExactlyInAnyOrder("hash-2", "hash-4");

        List<Torrent> range = torrentRepository.findAll(
                TorrentSpecification.progressBetween(40.0, 60.0));
        assertThat(range).extracting(Torrent::getInfoHash)
                .containsExactly("hash-1");
    }

    @Test
    @DisplayName("addedBetween should handle start, end, both, and nulls")
    void testAddedBetween() {
        List<Torrent> all = torrentRepository.findAll(
                TorrentSpecification.addedBetween(null, null));
        assertThat(all).hasSize(4);

        List<Torrent> startOnly = torrentRepository.findAll(
                TorrentSpecification.addedBetween(baseTime.minusDays(2), null));
        assertThat(startOnly).extracting(Torrent::getInfoHash)
                .containsExactlyInAnyOrder("hash-1", "hash-2", "hash-4");

        List<Torrent> endOnly = torrentRepository.findAll(
                TorrentSpecification.addedBetween(null, baseTime.minusDays(2)));
        assertThat(endOnly).extracting(Torrent::getInfoHash)
                .containsExactlyInAnyOrder("hash-1", "hash-3");

        List<Torrent> between = torrentRepository.findAll(
                TorrentSpecification.addedBetween(baseTime.minusDays(3), baseTime.minusHours(8)));
        assertThat(between).extracting(Torrent::getInfoHash)
                .containsExactlyInAnyOrder("hash-1", "hash-2");
    }

    @Test
    @DisplayName("sizeBetween should handle min, max, both, and nulls")
    void testSizeBetween() {
        List<Torrent> all = torrentRepository.findAll(
                TorrentSpecification.sizeBetween(null, null));
        assertThat(all).hasSize(4);

        List<Torrent> minOnly = torrentRepository.findAll(
                TorrentSpecification.sizeBetween(1000L, null));
        assertThat(minOnly).extracting(Torrent::getInfoHash)
                .containsExactlyInAnyOrder("hash-1", "hash-3");

        List<Torrent> maxOnly = torrentRepository.findAll(
                TorrentSpecification.sizeBetween(null, 800L));
        assertThat(maxOnly).extracting(Torrent::getInfoHash)
                .containsExactlyInAnyOrder("hash-2", "hash-4");

        List<Torrent> range = torrentRepository.findAll(
                TorrentSpecification.sizeBetween(700L, 1600L));
        assertThat(range).extracting(Torrent::getInfoHash)
                .containsExactlyInAnyOrder("hash-1", "hash-4");
    }

    @Test
    @DisplayName("hasErrors should return torrents with error message")
    void testHasErrors() {
        List<Torrent> errors = torrentRepository.findAll(TorrentSpecification.hasErrors());
        assertThat(errors).extracting(Torrent::getInfoHash)
                .containsExactly("hash-2");
    }

    @Test
    @DisplayName("hasInfoHash should filter by hash and return all when null")
    void testHasInfoHash() {
        List<Torrent> infoHash = torrentRepository.findAll(
                TorrentSpecification.hasInfoHash("hash-3"));
        assertThat(infoHash).extracting(Torrent::getInfoHash)
                .containsExactly("hash-3");

        List<Torrent> all = torrentRepository.findAll(
                TorrentSpecification.hasInfoHash(null));
        assertThat(all).hasSize(4);
    }

    @Test
    @DisplayName("filterTorrents should combine multiple filters")
    void testFilterTorrents() {
        List<Torrent> filtered = torrentRepository.findAll(
                TorrentSpecification.filterTorrents(
                        TorrentStatus.DOWNLOADING,
                        "ubuntu",
                        40.0,
                        60.0,
                        baseTime.minusDays(3),
                        baseTime.minusDays(1),
                        1000L,
                        2000L
                ));

        assertThat(filtered).extracting(Torrent::getInfoHash)
                .containsExactly("hash-1");
    }

    private Torrent buildTorrent(String infoHash, String name, TorrentStatus status,
                                 Double progress, Long totalSize, LocalDateTime addedDate) {
        Torrent torrent = new Torrent();
        torrent.setInfoHash(infoHash);
        torrent.setName(name);
        torrent.setMagnetLink("magnet:?xt=urn:btih:" + infoHash);
        torrent.setStatus(status);
        torrent.setProgress(progress);
        torrent.setTotalSize(totalSize);
        torrent.setSavePath("/downloads");
        torrent.setAddedDate(addedDate);
        return torrent;
    }
}
