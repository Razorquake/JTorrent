package com.example.jtorrent.exception;

public class TorrentExceptions {
    public static class TorrentNotFoundException extends RuntimeException {
        public TorrentNotFoundException(Long id) {
            super("Torrent not found with id: " + id);
        }

        public TorrentNotFoundException(String infoHash) {
            super("Torrent not found with info hash: " + infoHash);
        }
    }

    public static class TorrentAlreadyExistsException extends RuntimeException {
        public TorrentAlreadyExistsException(String infoHash) {
            super("Torrent already exists with info hash: " + infoHash);
        }
    }

    public static class TorrentNotActiveException extends RuntimeException {
        public TorrentNotActiveException(Long id) {
            super("Torrent is not active: " + id);
        }
    }

    public static class MaxConcurrentDownloadsException extends RuntimeException {
        public MaxConcurrentDownloadsException(int max) {
            super("Maximum concurrent downloads reached: " + max);
        }
    }

    public static class InvalidMagnetLinkException extends RuntimeException {
        public InvalidMagnetLinkException(String message) {
            super("Invalid magnet link: " + message);
        }
    }

    public static class TorrentFileException extends RuntimeException {
        public TorrentFileException(String message) {
            super(message);
        }

        public TorrentFileException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
