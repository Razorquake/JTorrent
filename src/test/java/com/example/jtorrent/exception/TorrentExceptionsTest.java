package com.example.jtorrent.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TorrentExceptions Unit Tests")
class TorrentExceptionsTest {

    @Test
    @DisplayName("Should format TorrentNotFoundException messages")
    void testTorrentNotFoundMessage() {
        assertThat(new TorrentExceptions.TorrentNotFoundException(1L).getMessage())
                .isEqualTo("Torrent not found with id: 1");
        assertThat(new TorrentExceptions.TorrentNotFoundException("hash").getMessage())
                .isEqualTo("Torrent not found with info hash: hash");
    }

    @Test
    @DisplayName("Should format TorrentAlreadyExistsException message")
    void testTorrentAlreadyExistsMessage() {
        assertThat(new TorrentExceptions.TorrentAlreadyExistsException("hash").getMessage())
                .isEqualTo("Torrent already exists with info hash: hash");
    }

    @Test
    @DisplayName("Should format TorrentNotActiveException message")
    void testTorrentNotActiveMessage() {
        assertThat(new TorrentExceptions.TorrentNotActiveException(2L).getMessage())
                .isEqualTo("Torrent is not active: 2");
    }

    @Test
    @DisplayName("Should format MaxConcurrentDownloadsException message")
    void testMaxConcurrentDownloadsMessage() {
        assertThat(new TorrentExceptions.MaxConcurrentDownloadsException(5).getMessage())
                .isEqualTo("Maximum concurrent downloads reached: 5");
    }

    @Test
    @DisplayName("Should format InvalidMagnetLinkException message")
    void testInvalidMagnetLinkMessage() {
        assertThat(new TorrentExceptions.InvalidMagnetLinkException("bad").getMessage())
                .isEqualTo("Invalid magnet link: bad");
    }

    @Test
    @DisplayName("Should expose TorrentFileException message and cause")
    void testTorrentFileExceptionMessage() {
        RuntimeException cause = new RuntimeException("cause");
        TorrentExceptions.TorrentFileException ex =
                new TorrentExceptions.TorrentFileException("file error", cause);

        assertThat(ex.getMessage()).isEqualTo("file error");
        assertThat(ex.getCause()).isSameAs(cause);
    }
}
