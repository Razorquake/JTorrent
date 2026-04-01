package com.example.jtorrent.tui.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

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
 *   <li>POST /api/torrents/search       — paged/sorted torrent search</li>
 *   <li>GET  /api/torrents/search/stalled — stalled torrents</li>
 *   <li>GET  /api/torrents/{id}         — single torrent</li>
 *   <li>GET  /api/stats/torrent/{id}    — per-torrent statistics</li>
 *   <li>GET  /api/stats/torrent/{id}/ratio — share ratio</li>
 *   <li>GET  /api/stats/torrent/{id}/eta   — estimated time remaining</li>
 *   <li>POST /api/torrents              — add via magnet link</li>
 *   <li>POST /api/torrents/upload       — add via .torrent file</li>
 *   <li>POST /api/torrents/{id}/start   — resume a torrent</li>
 *   <li>POST /api/torrents/{id}/pause   — pause a torrent</li>
 *   <li>DELETE /api/torrents/{id}       — remove a torrent</li>
 *   <li>POST /api/torrents/{id}/recheck — force piece recheck</li>
 *   <li>POST /api/torrents/{id}/reannounce — force tracker reannounce</li>
 *   <li>PUT  /api/torrents/{id}/files/priorities — set file priority</li>
 *   <li>POST /api/torrents/{id}/files/skip — skip selected files</li>
 *   <li>POST /api/torrents/{id}/files/download — resume selected files</li>
 *   <li>POST /api/torrents/{id}/files/prioritize — set selected files high priority</li>
 *   <li>POST /api/torrents/{id}/files/deprioritize — set selected files low priority</li>
 *   <li>POST /api/torrents/{id}/files/reset-priorities — reset all file priorities</li>
 *   <li>GET  /api/files/storage        — storage usage for the downloads directory</li>
 *   <li>GET  /api/files/orphans        — orphaned files on disk</li>
 *   <li>DELETE /api/files/orphans      — clean orphaned files</li>
 *   <li>GET  /api/system/health        — service health and session state</li>
 *   <li>GET  /api/system/info          — service metadata and download directory</li>
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
     * @return add-response summary from the server
     * @throws ApiException on server or network error
     */
    public AddTorrentResponse addMagnet(String magnetLink) throws ApiException {
        return addMagnet(magnetLink, null, true);
    }

    /**
     * Adds a torrent via a magnet link with optional save-path and auto-start
     * settings.
     *
     * @param magnetLink       full magnet URI string
     * @param savePath         optional custom download directory
     * @param startImmediately whether the torrent should start right away
     * @return add-response summary from the server
     * @throws ApiException on server or network error
     */
    public AddTorrentResponse addMagnet(
            String magnetLink,
            String savePath,
            boolean startImmediately) throws ApiException {

        AddTorrentRequest request = new AddTorrentRequest();
        request.magnetLink = magnetLink;
        request.savePath = blankToNull(savePath);
        request.startImmediately = startImmediately;

        String json = post("/api/torrents", writeJson(request));
        try {
            return mapper.readValue(json, AddTorrentResponse.class);
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
     * @return add-response summary from the server
     * @throws ApiException on server or network error
     */
    public AddTorrentResponse uploadTorrentFile(Path torrentFile) throws ApiException {
        return uploadTorrentFile(torrentFile, null, true);
    }

    /**
     * Adds a torrent by uploading a {@code .torrent} file with optional save
     * path and auto-start settings.
     *
     * @param torrentFile      path to the {@code .torrent} file
     * @param savePath         optional custom download directory
     * @param startImmediately whether the torrent should start right away
     * @return add-response summary from the server
     * @throws ApiException on server or network error
     */
    public AddTorrentResponse uploadTorrentFile(
            Path torrentFile,
            String savePath,
            boolean startImmediately) throws ApiException {
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

        byte[] body = buildUploadMultipartBody(
                boundary,
                torrentFile.getFileName().toString(),
                fileBytes,
                savePath,
                startImmediately
        );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/torrents/upload"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        String json = execute(request);
        try {
            return mapper.readValue(json, AddTorrentResponse.class);
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

    /**
     * Forces an immediate tracker announce for the torrent.
     *
     * @param id torrent database ID
     * @throws ApiException on server or network error
     */
    public void reannounceTorrent(long id) throws ApiException {
        post("/api/torrents/" + id + "/reannounce", "");
    }

    /**
     * Sets a specific priority for the selected files in a torrent.
     *
     * @param torrentId torrent database ID
     * @param fileIds   selected file IDs
     * @param priority  file priority (0=skip, 1=low, 4=normal, 7=high)
     * @throws ApiException on server or network error
     */
    public void updateFilePriorities(long torrentId, List<Long> fileIds, int priority) throws ApiException {
        put("/api/torrents/" + torrentId + "/files/priorities", priorityBody(fileIds, priority));
    }

    /**
     * Marks the selected files as skipped.
     */
    public void skipFiles(long torrentId, List<Long> fileIds) throws ApiException {
        post("/api/torrents/" + torrentId + "/files/skip", fileIdsBody(fileIds));
    }

    /**
     * Resumes downloading for previously skipped files.
     */
    public void downloadFiles(long torrentId, List<Long> fileIds) throws ApiException {
        post("/api/torrents/" + torrentId + "/files/download", fileIdsBody(fileIds));
    }

    /**
     * Marks the selected files as high priority.
     */
    public void prioritizeFiles(long torrentId, List<Long> fileIds) throws ApiException {
        post("/api/torrents/" + torrentId + "/files/prioritize", fileIdsBody(fileIds));
    }

    /**
     * Marks the selected files as low priority.
     */
    public void deprioritizeFiles(long torrentId, List<Long> fileIds) throws ApiException {
        post("/api/torrents/" + torrentId + "/files/deprioritize", fileIdsBody(fileIds));
    }

    /**
     * Resets all file priorities for the torrent back to normal.
     */
    public void resetFilePriorities(long torrentId) throws ApiException {
        post("/api/torrents/" + torrentId + "/files/reset-priorities", "");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Per-torrent detail / statistics
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns detailed statistics for a single torrent.
     *
     * @param torrentId torrent database ID
     * @return stats DTO, or {@code null} if no stats record exists yet
     * @throws ApiException on server or network error
     */
    public TorrentDetailStatsResponse getTorrentStats(long torrentId) throws ApiException {
        try {
            String json = get("/api/stats/torrent/" + torrentId);
            return mapper.readValue(json, TorrentDetailStatsResponse.class);
        } catch (ApiException e) {
            if (e.getStatusCode() == 404) return null;
            throw e;
        } catch (IOException e) {
            throw new ApiException("Failed to parse torrent stats: " + e.getMessage(), e);
        }
    }

    /**
     * Executes the server-side torrent search endpoint.
     *
     * @param request search/filter/sort request
     * @return paged search response
     * @throws ApiException on server or network error
     */
    public TorrentPageResponse searchTorrents(TorrentSearchRequest request) throws ApiException {
        String json = post("/api/torrents/search", writeJson(request));
        try {
            return mapper.readValue(json, TorrentPageResponse.class);
        } catch (IOException e) {
            throw new ApiException("Failed to parse search results: " + e.getMessage(), e);
        }
    }

    /**
     * Returns stalled torrents from the dedicated backend endpoint.
     *
     * @return stalled torrent list
     * @throws ApiException on server or network error
     */
    public List<TorrentResponse> getStalledTorrents() throws ApiException {
        String json = get("/api/torrents/search/stalled");
        try {
            TorrentResponse[] array = mapper.readValue(json, TorrentResponse[].class);
            return Arrays.asList(array);
        } catch (IOException e) {
            throw new ApiException("Failed to parse stalled torrents: " + e.getMessage(), e);
        }
    }

    /**
     * Returns share-ratio information for a single torrent.
     *
     * @param torrentId torrent database ID
     * @return ratio value, or {@code null} if unavailable
     * @throws ApiException on server or network error
     */
    public Double getTorrentRatio(long torrentId) throws ApiException {
        String json = get("/api/stats/torrent/" + torrentId + "/ratio");
        try {
            RatioResponse response = mapper.readValue(json, RatioResponse.class);
            return response.ratio;
        } catch (IOException e) {
            throw new ApiException("Failed to parse torrent ratio: " + e.getMessage(), e);
        }
    }

    /**
     * Returns ETA information for a single torrent.
     *
     * @param torrentId torrent database ID
     * @return ETA DTO, or {@code null} if the server has no estimate
     * @throws ApiException on server or network error
     */
    public TorrentEtaResponse getTorrentEta(long torrentId) throws ApiException {
        String json = get("/api/stats/torrent/" + torrentId + "/eta");
        try {
            return mapper.readValue(json, TorrentEtaResponse.class);
        } catch (IOException e) {
            throw new ApiException("Failed to parse torrent ETA: " + e.getMessage(), e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // System / operations
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns the server's health payload.
     *
     * <p>The endpoint returns HTTP 503 when the torrent session is down, but it
     * still includes a useful JSON body. This method preserves that payload so
     * the TUI can render a "DOWN" state instead of only showing a transport
     * error.
     *
     * @return health DTO
     * @throws ApiException on network failure or unexpected HTTP status
     */
    public SystemHealthResponse getSystemHealth() throws ApiException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/system/health"))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = send(request);
        int status = response.statusCode();
        if (status != 200 && status != 503) {
            throw new ApiException("Server returned HTTP " + status + " for " + request.uri(), status);
        }

        try {
            return mapper.readValue(response.body(), SystemHealthResponse.class);
        } catch (IOException e) {
            throw new ApiException("Failed to parse system health: " + e.getMessage(), e);
        }
    }

    /**
     * Returns service metadata and download-directory information.
     */
    public SystemInfoResponse getSystemInfo() throws ApiException {
        String json = get("/api/system/info");
        try {
            return mapper.readValue(json, SystemInfoResponse.class);
        } catch (IOException e) {
            throw new ApiException("Failed to parse system info: " + e.getMessage(), e);
        }
    }

    /**
     * Returns storage usage for the downloads directory.
     */
    public StorageInfoResponse getStorageInfo() throws ApiException {
        String json = get("/api/files/storage");
        try {
            return mapper.readValue(json, StorageInfoResponse.class);
        } catch (IOException e) {
            throw new ApiException("Failed to parse storage info: " + e.getMessage(), e);
        }
    }

    /**
     * Returns orphaned file paths from the downloads directory.
     */
    public List<String> getOrphanedFiles() throws ApiException {
        String json = get("/api/files/orphans");
        try {
            String[] array = mapper.readValue(json, String[].class);
            return Arrays.asList(array);
        } catch (IOException e) {
            throw new ApiException("Failed to parse orphaned files: " + e.getMessage(), e);
        }
    }

    /**
     * Deletes orphaned files and returns a cleanup summary.
     */
    public CleanupOrphansResponse cleanupOrphanedFiles() throws ApiException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/files/orphans"))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .DELETE()
                .build();

        String json = execute(request);
        try {
            return mapper.readValue(json, CleanupOrphansResponse.class);
        } catch (IOException e) {
            throw new ApiException("Failed to parse orphan cleanup response: " + e.getMessage(), e);
        }
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

    private String put(String path, String jsonBody) throws ApiException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        return execute(request);
    }

    private HttpResponse<String> send(HttpRequest request) throws ApiException {
        try {
            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString());

            log.debug("HTTP {} {} → {}", request.method(), request.uri(), response.statusCode());
            return response;
        } catch (IOException e) {
            throw new ApiException("Network error: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException("Request interrupted", e);
        }
    }

    private String execute(HttpRequest request) throws ApiException {
        HttpResponse<String> response = send(request);
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return response.body();
        }
        throw new ApiException(
                "Server returned HTTP " + status + " for " + request.uri(),
                status
        );
    }

    private static String fileIdsBody(List<Long> fileIds) {
        String ids = safeFileIds(fileIds).stream()
                .map(String::valueOf)
                .collect(Collectors.joining(","));
        return "{\"fileIds\":[" + ids + "]}";
    }

    private static String priorityBody(List<Long> fileIds, int priority) {
        String ids = safeFileIds(fileIds).stream()
                .map(String::valueOf)
                .collect(Collectors.joining(","));
        return "{\"fileIds\":[" + ids + "],\"priority\":" + priority + "}";
    }

    private static List<Long> safeFileIds(List<Long> fileIds) {
        if (fileIds == null) {
            throw new IllegalArgumentException("fileIds must not be null");
        }
        return fileIds.stream().filter(Objects::nonNull).toList();
    }

    private String writeJson(Object value) throws ApiException {
        try {
            return mapper.writeValueAsString(value);
        } catch (IOException e) {
            throw new ApiException("Failed to encode JSON request: " + e.getMessage(), e);
        }
    }

    private static byte[] buildUploadMultipartBody(
            String boundary,
            String filename,
            byte[] fileBytes,
            String savePath,
            boolean startImmediately) {

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeFilePart(body, boundary, "file", filename, "application/x-bittorrent", fileBytes);

        String normalizedSavePath = blankToNull(savePath);
        if (normalizedSavePath != null) {
            writeTextPart(body, boundary, "savePath", normalizedSavePath);
        }
        writeTextPart(body, boundary, "startImmediately", Boolean.toString(startImmediately));
        writeBoundaryLine(body, boundary + "--");
        return body.toByteArray();
    }

    private static void writeFilePart(
            ByteArrayOutputStream body,
            String boundary,
            String name,
            String filename,
            String contentType,
            byte[] bytes) {

        writeBoundaryLine(body, boundary);
        writeUtf8(body, "Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + filename + "\"\r\n");
        writeUtf8(body, "Content-Type: " + contentType + "\r\n\r\n");
        body.writeBytes(bytes);
        writeUtf8(body, "\r\n");
    }

    private static void writeTextPart(
            ByteArrayOutputStream body,
            String boundary,
            String name,
            String value) {

        writeBoundaryLine(body, boundary);
        writeUtf8(body, "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n");
        writeUtf8(body, value);
        writeUtf8(body, "\r\n");
    }

    private static void writeBoundaryLine(ByteArrayOutputStream body, String boundary) {
        writeUtf8(body, "--" + boundary + "\r\n");
    }

    private static void writeUtf8(ByteArrayOutputStream body, String value) {
        body.writeBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
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
        public List<TorrentFileResponse> files;
        public String errorMessage;
        public String comment;
        public String createdBy;
        public LocalDateTime creationDate;

        // ── Formatting helpers used by the TUI widgets ──────────────────────

        public String formattedSize() {
            return formatBytes(totalSize);
        }

        public String formattedDownloaded() {
            return formatBytes(downloadedSize);
        }

        public String formattedUploaded() {
            return formatBytes(uploadedSize);
        }

        public String formattedDownloadSpeed() {
            return formatSpeed(downloadSpeed != null ? downloadSpeed.longValue() : 0L);
        }

        public String formattedUploadSpeed() {
            return formatSpeed(uploadSpeed != null ? uploadSpeed.longValue() : 0L);
        }

        public int fileCount() {
            return files != null ? files.size() : 0;
        }

        public List<TorrentFileResponse> safeFiles() {
            return files == null ? List.of() : List.copyOf(files);
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
            return TorrentApiClient.formatBytes(bytes);
        }

        @Override
        public String toString() {
            return "TorrentResponse{id=" + id + ", name='" + name + "', status=" + status + "}";
        }
    }

    /**
     * Mirror of the server's {@code AddTorrentRequest}.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AddTorrentRequest {
        public String magnetLink;
        public String savePath;
        public Boolean startImmediately = true;
    }

    /**
     * Mirror of the server's {@code AddTorrentFileResponse}.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AddTorrentResponse {
        public Long torrentId;
        public String infoHash;
        public String name;
        public Long totalSize;
        public Integer fileCount;
        public String message;
        public Boolean started;

        public String formattedSize() {
            return formatBytes(totalSize);
        }
    }

    /**
     * Mirror of the system health payload.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SystemHealthResponse {
        public String status;
        public LocalDateTime timestamp;
        public Boolean sessionRunning;
        public String service;
        public String version;

        public boolean isUp() {
            return "UP".equalsIgnoreCase(status);
        }
    }

    /**
     * Mirror of the system info payload.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SystemInfoResponse {
        public String service;
        public String version;
        public Boolean sessionRunning;
        public LocalDateTime timestamp;
        public Integer activeTorrents;
        public String downloadDirectory;
    }

    /**
     * Mirror of the file-management storage payload.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class StorageInfoResponse {
        public String path;
        public Long totalBytes;
        public Long usedBytes;
        public Long freeBytes;
        public String totalFormatted;
        public String usedFormatted;
        public String freeFormatted;
        public Long usedByTorrentsBytes;
        public String usedByTorrentsFormatted;

        public String displayTotal() {
            return totalFormatted != null && !totalFormatted.isBlank()
                    ? totalFormatted
                    : safeDisplayBytes(totalBytes);
        }

        public String displayUsed() {
            return usedFormatted != null && !usedFormatted.isBlank()
                    ? usedFormatted
                    : safeDisplayBytes(usedBytes);
        }

        public String displayFree() {
            return freeFormatted != null && !freeFormatted.isBlank()
                    ? freeFormatted
                    : safeDisplayBytes(freeBytes);
        }

        public String displayTrackedUsage() {
            return usedByTorrentsFormatted != null && !usedByTorrentsFormatted.isBlank()
                    ? usedByTorrentsFormatted
                    : safeDisplayBytes(usedByTorrentsBytes);
        }

        private String safeDisplayBytes(Long bytes) {
            return bytes != null && bytes >= 0 ? formatBytes(bytes) : "Unknown";
        }
    }

    /**
     * Mirror of the orphan cleanup result map.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CleanupOrphansResponse {
        public Integer deletedCount;
        public String message;
    }

    /**
     * Mirror of the server's {@code TorrentFilterRequest}.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TorrentSearchRequest {
        public String status;
        public List<String> statuses;
        public String name;
        public Double minProgress;
        public Double maxProgress;
        public Boolean hasErrors;
        public String sortBy = "addedDate";
        public String sortDirection = "DESC";
        public Integer page = 0;
        public Integer size = 20;
    }

    /**
     * Minimal mirror of Spring Data's paged response body.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TorrentPageResponse {
        public List<TorrentResponse> content;
        public int totalPages;
        public long totalElements;
        public int number;
        public int size;
        public int numberOfElements;
        public boolean first;
        public boolean last;
        public boolean empty;

        public List<TorrentResponse> safeContent() {
            return content == null ? List.of() : List.copyOf(content);
        }
    }

    /**
     * Mirror of the server's {@code TorrentFileResponse}.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TorrentFileResponse {
        public Long id;
        public String path;
        public Long size;
        public Long downloadedSize;
        public Double progress;
        public Integer priority;

        public String displayName() {
            return path != null && !path.isBlank() ? path : "(unnamed file)";
        }

        public String formattedSize() {
            return formatBytes(size);
        }

        public String formattedDownloaded() {
            return formatBytes(downloadedSize);
        }

        public String progressLabel() {
            double pct = progress != null ? progress : 0.0;
            return String.format("%5.1f%%", pct);
        }

        public String priorityLabel() {
            return switch (priority != null ? priority : 4) {
                case 0 -> "SKIP";
                case 1 -> "LOW";
                case 4 -> "NORM";
                case 7 -> "HIGH";
                default -> "P" + priority;
            };
        }

        public boolean isSkipped() {
            return priority != null && priority == 0;
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
    }

    /**
     * Mirror of the server's {@code DownloadStatistics} payload.
     * Unknown fields like the nested torrent object are intentionally ignored.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TorrentDetailStatsResponse {
        public Long totalDownloaded;
        public Long totalUploaded;
        public Double ratio;
        public Long timeActive;
        public LocalDateTime startTime;
        public LocalDateTime endTime;
        public Integer averageDownloadSpeed;
        public Integer averageUploadSpeed;
        public Integer maxDownloadSpeed;
        public Integer maxUploadSpeed;
        public Integer totalPeers;

        public String formattedTotalDownloaded() {
            return formatBytes(totalDownloaded);
        }

        public String formattedTotalUploaded() {
            return formatBytes(totalUploaded);
        }

        public String formattedAverageDownloadSpeed() {
            return formatSpeed(averageDownloadSpeed != null ? averageDownloadSpeed.longValue() : 0L);
        }

        public String formattedAverageUploadSpeed() {
            return formatSpeed(averageUploadSpeed != null ? averageUploadSpeed.longValue() : 0L);
        }

        public String formattedMaxDownloadSpeed() {
            return formatSpeed(maxDownloadSpeed != null ? maxDownloadSpeed.longValue() : 0L);
        }

        public String formattedMaxUploadSpeed() {
            return formatSpeed(maxUploadSpeed != null ? maxUploadSpeed.longValue() : 0L);
        }

        public String formattedTimeActive() {
            return formatDuration(timeActive);
        }
    }

    /**
     * Small wrapper used by the ratio endpoint.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RatioResponse {
        public Double ratio;
    }

    /**
     * Mirror of the server's ETA response map.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TorrentEtaResponse {
        public Long etaSeconds;
        public String etaFormatted;

        public String displayValue() {
            if (etaFormatted != null && !etaFormatted.isBlank()) {
                return etaFormatted;
            }
            if (etaSeconds == null) {
                return "Unknown";
            }
            return formatDuration(etaSeconds);
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

    private static String formatBytes(Long bytes) {
        long safeBytes = bytes != null ? bytes : 0L;
        if (safeBytes <= 0) return "0 B";
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int idx = (int) (Math.log10(safeBytes) / Math.log10(1024));
        idx = Math.min(idx, units.length - 1);
        double val = safeBytes / Math.pow(1024, idx);
        return String.format("%.1f %s", val, units[idx]);
    }

    private static String formatSpeed(long bytesPerSec) {
        return formatBytes(bytesPerSec) + "/s";
    }

    private static String formatDuration(Long seconds) {
        if (seconds == null || seconds < 0) {
            return "Unknown";
        }
        long totalSeconds = seconds;
        long days = totalSeconds / 86_400;
        long hours = (totalSeconds % 86_400) / 3_600;
        long minutes = (totalSeconds % 3_600) / 60;
        long secs = totalSeconds % 60;

        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("d ");
        if (hours > 0) sb.append(hours).append("h ");
        if (minutes > 0) sb.append(minutes).append("m ");
        if (secs > 0 || sb.isEmpty()) sb.append(secs).append("s");
        return sb.toString().trim();
    }
}
