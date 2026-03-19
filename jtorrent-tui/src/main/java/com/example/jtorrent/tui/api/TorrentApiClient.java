package com.example.jtorrent.tui.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Typed HTTP client that wraps all JTorrent REST API endpoints.
 *
 * <p>Uses the JDK built-in {@link HttpClient} (Java 11+) — no extra dependency.
 * All methods are synchronous (blocking) because the TUI's polling loop already
 * runs on a background thread managed by {@code ScheduledExecutorService}.
 *
 * <p>The base URL defaults to {@code http://localhost:8080} but can be
 * overridden at construction time for connecting to a remote server.
 *
 * <h2>Endpoints covered</h2>
 * <ul>
 *   <li>GET  /api/torrents              — list all torrents</li>
 *   <li>GET  /api/torrents/{id}         — single torrent</li>
 *   <li>POST /api/torrents              — add via magnet link</li>
 *   <li>POST /api/torrents/upload       — add via .torrent file</li>
 *   <li>POST /api/torrents/{id}/start   — resume a torrent</li>
 *   <li>POST /api/torrents/{id}/pause   — pause a torrent</li>
 *   <li>DELETE /api/torrents/{id}       — remove a torrent</li>
 *   <li>POST /api/torrents/{id}/recheck — force piece recheck</li>
 *   <li>GET  /api/stats/overall         — global statistics</li>
 * </ul>
 */
public class TorrentApiClient {

    private static final Logger log = LoggerFactory.getLogger(TorrentApiClient.class);

    private final String baseUrl;
    private final HttpClient http;
    private final ObjectMapper mapper;

    // ─────────────────────────────────────────────────────────────────────────
    // Construction
    // ─────────────────────────────────────────────────────────────────────────

    public TorrentApiClient(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                // Ignore unknown JSON fields so new server fields don't break the TUI
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                // Don't fail if the server sends dates as timestamps
                .configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, false);
    }

    /** Convenience constructor that points to the default local server. */
    public TorrentApiClient() {
        this("http://localhost:8080");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Torrent list / detail
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns all torrents tracked by the server.
     *
     * @return list of torrent responses, never {@code null}
     * @throws ApiException if the server returns a non-2xx status or is unreachable
     */
    public List<TorrentResponse> getAllTorrents() throws ApiException {
        String json = get("/api/torrents");
        try {
            TorrentResponse[] array = mapper.readValue(json, TorrentResponse[].class);
            return Arrays.asList(array);
        } catch (IOException e) {
            throw new ApiException("Failed to parse torrent list: " + e.getMessage(), e);
        }
    }

    /**
     * Returns a single torrent by its database ID.
     *
     * @param id torrent database ID
     * @return the torrent, or {@code null} if not found (404)
     * @throws ApiException on network or parse errors
     */
    public TorrentResponse getTorrent(long id) throws ApiException {
        try {
            String json = get("/api/torrents/" + id);
            return mapper.readValue(json, TorrentResponse.class);
        } catch (ApiException e) {
            if (e.getStatusCode() == 404) return null;
            throw e;
        } catch (IOException e) {
            throw new ApiException("Failed to parse torrent: " + e.getMessage(), e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Add torrents
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Adds a torrent via a magnet link.
     *
     * @param magnetLink full magnet URI string
     * @return the newly created torrent
     * @throws ApiException on server or network error
     */
    public TorrentResponse addMagnet(String magnetLink) throws ApiException {
        String body = String.format("{\"magnetLink\":\"%s\"}", escapedJson(magnetLink));
        String json = post("/api/torrents", body);
        try {
            return mapper.readValue(json, TorrentResponse.class);
        } catch (IOException e) {
            throw new ApiException("Failed to parse add-magnet response: " + e.getMessage(), e);
        }
    }

    /**
     * Adds a torrent by uploading a {@code .torrent} file from the local filesystem.
     *
     * <p>Uses multipart/form-data to match the server's
     * {@code POST /api/torrents/upload} endpoint.
     *
     * @param torrentFile path to the {@code .torrent} file
     * @return the newly created torrent
     * @throws ApiException on server or network error
     */
    public TorrentResponse uploadTorrentFile(Path torrentFile) throws ApiException {
        if (!Files.exists(torrentFile)) {
            throw new ApiException("File not found: " + torrentFile, null);
        }

        String boundary = UUID.randomUUID().toString();
        byte[] fileBytes;
        try {
            fileBytes = Files.readAllBytes(torrentFile);
        } catch (IOException e) {
            throw new ApiException("Cannot read .torrent file: " + e.getMessage(), e);
        }

        // Build a minimal multipart/form-data body manually.
        // The server expects a part named "file".
        String crlf = "\r\n";
        String separator = "--" + boundary;
        String header = separator + crlf
                + "Content-Disposition: form-data; name=\"file\"; filename=\""
                + torrentFile.getFileName() + "\"" + crlf
                + "Content-Type: application/x-bittorrent" + crlf
                + crlf;
        String footer = crlf + separator + "--" + crlf;

        byte[] headerBytes = header.getBytes();
        byte[] footerBytes = footer.getBytes();
        byte[] body = new byte[headerBytes.length + fileBytes.length + footerBytes.length];
        System.arraycopy(headerBytes, 0, body, 0, headerBytes.length);
        System.arraycopy(fileBytes, 0, body, headerBytes.length, fileBytes.length);
        System.arraycopy(footerBytes, 0, body, headerBytes.length + fileBytes.length, footerBytes.length);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/torrents/upload"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        String json = execute(request);
        try {
            return mapper.readValue(json, TorrentResponse.class);
        } catch (IOException e) {
            throw new ApiException("Failed to parse upload response: " + e.getMessage(), e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Torrent control
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Resumes/starts a paused or pending torrent.
     *
     * @param id torrent database ID
     * @throws ApiException on server or network error
     */
    public void startTorrent(long id) throws ApiException {
        post("/api/torrents/" + id + "/start", "");
    }

    /**
     * Pauses an active torrent.
     *
     * @param id torrent database ID
     * @throws ApiException on server or network error
     */
    public void pauseTorrent(long id) throws ApiException {
        post("/api/torrents/" + id + "/pause", "");
    }

    /**
     * Removes a torrent from the server.
     *
     * @param id          torrent database ID
     * @param deleteFiles if {@code true}, also delete downloaded files from disk
     * @throws ApiException on server or network error
     */
    public void removeTorrent(long id, boolean deleteFiles) throws ApiException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/torrents/" + id + "?deleteFiles=" + deleteFiles))
                .timeout(Duration.ofSeconds(10))
                .DELETE()
                .build();
        execute(request);
    }

    /**
     * Triggers a piece-hash recheck for a torrent.
     *
     * @param id torrent database ID
     * @throws ApiException on server or network error
     */
    public void recheckTorrent(long id) throws ApiException {
        post("/api/torrents/" + id + "/recheck", "");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Statistics
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns global statistics (speeds, counts, ratios).
     *
     * @return stats DTO, never {@code null}
     * @throws ApiException on server or network error
     */
    public StatsResponse getStats() throws ApiException {
        String json = get("/api/stats/overall");
        try {
            return mapper.readValue(json, StatsResponse.class);
        } catch (IOException e) {
            throw new ApiException("Failed to parse stats: " + e.getMessage(), e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Low-level HTTP helpers
    // ─────────────────────────────────────────────────────────────────────────

    private String get(String path) throws ApiException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .GET()
                .build();
        return execute(request);
    }

    private String post(String path, String jsonBody) throws ApiException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        return execute(request);
    }

    private String execute(HttpRequest request) throws ApiException {
        try {
            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString());

            int status = response.statusCode();
            log.debug("HTTP {} {} → {}", request.method(), request.uri(), status);

            if (status >= 200 && status < 300) {
                return response.body();
            }

            throw new ApiException(
                    "Server returned HTTP " + status + " for " + request.uri(),
                    status
            );

        } catch (IOException e) {
            throw new ApiException("Network error: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException("Request interrupted", e);
        }
    }

    /** Minimal JSON-string escaping for the magnet-link body. */
    private static String escapedJson(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Response DTOs  (mirrors server-side DTOs — no shared code dependency)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Mirror of {@code TorrentResponse} from the server module.
     *
     * <p>We intentionally do NOT share the class across modules.
     * The TUI must remain a standalone JAR with no Spring Boot classpath.
     * {@code @JsonIgnoreProperties(ignoreUnknown = true)} ensures new server
     * fields don't break older TUI builds.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TorrentResponse {
        public Long id;
        public String infoHash;
        public String name;
        public String magnetLink;
        public Long totalSize;
        public Long downloadedSize;
        public Long uploadedSize;
        public String status;         // TorrentStatus enum → String
        public Double progress;       // 0.0 – 100.0
        public Integer downloadSpeed; // bytes/s
        public Integer uploadSpeed;   // bytes/s
        public Integer peers;
        public Integer seeds;
        public String savePath;
        public LocalDateTime addedDate;
        public LocalDateTime completedDate;
        public String errorMessage;

        // ── Formatting helpers used by the TUI widgets ──────────────────────

        public String formattedSize() {
            return formatBytes(totalSize);
        }

        public String formattedDownloaded() {
            return formatBytes(downloadedSize);
        }

        public String formattedDownloadSpeed() {
            return formatBytes(downloadSpeed != null ? downloadSpeed.longValue() : 0L) + "/s";
        }

        public String formattedUploadSpeed() {
            return formatBytes(uploadSpeed != null ? uploadSpeed.longValue() : 0L) + "/s";
        }

        /** Returns a short one-word status label suitable for a table cell. */
        public String statusLabel() {
            if (status == null) return "?";
            return switch (status) {
                case "DOWNLOADING" -> "↓";
                case "SEEDING"     -> "↑";
                case "PAUSED"      -> "⏸";
                case "COMPLETED"   -> "✓";
                case "CHECKING"    -> "⟳";
                case "ERROR"       -> "✗";
                case "PENDING"     -> "…";
                case "STOPPED"     -> "■";
                default            -> status;
            };
        }

        private static String formatBytes(Long bytes) {
            if (bytes == null || bytes <= 0) return "0 B";
            String[] units = {"B", "KB", "MB", "GB", "TB"};
            int idx = (int) (Math.log10(bytes) / Math.log10(1024));
            idx = Math.min(idx, units.length - 1);
            double val = bytes / Math.pow(1024, idx);
            return String.format("%.1f %s", val, units[idx]);
        }

        @Override
        public String toString() {
            return "TorrentResponse{id=" + id + ", name='" + name + "', status=" + status + "}";
        }
    }

    /**
     * Mirror of the server's {@code TorrentStatsDTO}.
     * Only the fields actually rendered by the TUI are declared here.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class StatsResponse {
        public long totalTorrents;
        public long activeTorrents;
        public long downloadingTorrents;
        public long seedingTorrents;
        public long completedTorrents;
        public long pausedTorrents;
        public long errorTorrents;
        public long totalDownloadedBytes;
        public long totalUploadedBytes;
        public int  currentDownloadSpeed; // bytes/s
        public int  currentUploadSpeed;   // bytes/s
        public double overallRatio;
        public int  totalActivePeers;

        public String formattedDownloadSpeed() {
            return formatSpeed(currentDownloadSpeed);
        }

        public String formattedUploadSpeed() {
            return formatSpeed(currentUploadSpeed);
        }

        private static String formatSpeed(int bytesPerSec) {
            if (bytesPerSec <= 0) return "0 B/s";
            if (bytesPerSec < 1_024) return bytesPerSec + " B/s";
            if (bytesPerSec < 1_048_576) return String.format("%.1f KB/s", bytesPerSec / 1_024.0);
            return String.format("%.1f MB/s", bytesPerSec / 1_048_576.0);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Exception
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Thrown by all {@link TorrentApiClient} methods when a request cannot
     * be completed successfully.
     */
    public static class ApiException extends Exception {

        private final int statusCode;

        /** Network-level failure (no HTTP status available). */
        public ApiException(String message, Throwable cause) {
            super(message, cause);
            this.statusCode = -1;
        }

        /** HTTP-level failure with a known status code. */
        public ApiException(String message, int statusCode) {
            super(message);
            this.statusCode = statusCode;
        }

        /**
         * Returns the HTTP status code, or {@code -1} if the failure occurred
         * before a response was received (e.g. connection refused).
         */
        public int getStatusCode() {
            return statusCode;
        }

        public boolean isConnectionRefused() {
            return statusCode == -1;
        }
    }
}
