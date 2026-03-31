package com.example.jtorrent.tui.live;

import com.example.jtorrent.tui.api.TorrentApiClient;
import com.example.jtorrent.tui.controller.AppController;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Minimal SockJS + STOMP client used by the TUI to receive live torrent, stats,
 * and notification updates from the server.
 */
public class LiveUpdateService {

    private static final Logger log = LoggerFactory.getLogger(LiveUpdateService.class);

    private final String baseUrl;
    private final AppController controller;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "jtorrent-live-updates");
                t.setDaemon(true);
                return t;
            });

    private final AtomicBoolean reconnectScheduled = new AtomicBoolean(false);

    private volatile boolean running = false;
    private volatile WebSocket webSocket;

    public LiveUpdateService(String baseUrl, AppController controller) {
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.controller = controller;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public void start() {
        running = true;
        scheduler.execute(this::connect);
    }

    public void stop() {
        running = false;
        controller.setLiveUpdatesConnected(false);

        WebSocket socket = webSocket;
        if (socket != null) {
            try {
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "client shutdown");
            } catch (Exception ignored) {
                socket.abort();
            }
        }
        scheduler.shutdownNow();
    }

    private void connect() {
        if (!running) {
            return;
        }

        try {
            URI uri = buildSockJsWebSocketUri();
            log.info("Connecting live-update websocket to {}", uri);

            httpClient.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .buildAsync(uri, new Listener())
                    .whenComplete((socket, error) -> {
                        if (error != null) {
                            log.debug("Live-update websocket connect failed: {}", error.getMessage());
                            controller.setLiveUpdatesConnected(false);
                            scheduleReconnect();
                            return;
                        }
                        webSocket = socket;
                    });
        } catch (Exception e) {
            log.debug("Failed to prepare live-update websocket connection: {}", e.getMessage());
            controller.setLiveUpdatesConnected(false);
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (!running) {
            return;
        }
        if (!reconnectScheduled.compareAndSet(false, true)) {
            return;
        }
        scheduler.schedule(() -> {
            reconnectScheduled.set(false);
            connect();
        }, 3, TimeUnit.SECONDS);
    }

    private void sendStompFrame(String frame) {
        WebSocket socket = webSocket;
        if (socket == null) {
            return;
        }

        try {
            String sockJsPayload = mapper.writeValueAsString(List.of(frame));
            socket.sendText(sockJsPayload, true);
        } catch (Exception e) {
            log.debug("Failed to send STOMP frame: {}", e.getMessage());
        }
    }

    private void sendConnectFrame() {
        URI uri = URI.create(baseUrl);
        String host = uri.getHost() != null ? uri.getHost() : "localhost";
        String frame = "CONNECT\n"
                + "accept-version:1.2\n"
                + "host:" + host + "\n"
                + "heart-beat:0,0\n"
                + "\n\0";
        sendStompFrame(frame);
    }

    private void subscribeToTopics() {
        sendSubscribeFrame("sub-torrents", "/topic/torrents");
        sendSubscribeFrame("sub-stats", "/topic/stats");
        sendSubscribeFrame("sub-notifications", "/topic/notifications");
    }

    private void sendSubscribeFrame(String id, String destination) {
        String frame = "SUBSCRIBE\n"
                + "id:" + id + "\n"
                + "destination:" + destination + "\n"
                + "\n\0";
        sendStompFrame(frame);
    }

    private void handleSockJsPayload(String payload) {
        if (payload == null || payload.isEmpty()) {
            return;
        }

        if ("o".equals(payload)) {
            sendConnectFrame();
            return;
        }
        if ("h".equals(payload)) {
            return;
        }
        if (payload.startsWith("c")) {
            controller.setLiveUpdatesConnected(false);
            scheduleReconnect();
            return;
        }
        if (!payload.startsWith("a")) {
            return;
        }

        try {
            String[] frames = mapper.readValue(payload.substring(1), String[].class);
            for (String frame : frames) {
                handleStompFrame(frame);
            }
        } catch (Exception e) {
            log.debug("Failed to parse SockJS payload: {}", e.getMessage());
        }
    }

    private void handleStompFrame(String rawFrame) {
        StompFrame frame = StompFrame.parse(rawFrame);
        switch (frame.command) {
            case "CONNECTED" -> {
                controller.setLiveUpdatesConnected(true);
                subscribeToTopics();
            }
            case "MESSAGE" -> handleStompMessage(frame);
            case "ERROR" -> {
                controller.setLiveUpdatesConnected(false);
                if (frame.body != null && !frame.body.isBlank()) {
                    controller.setError("Live updates error: " + frame.body, "Live");
                }
            }
            default -> {
                // Ignore RECEIPT and other control frames we do not use.
            }
        }
    }

    private void handleStompMessage(StompFrame frame) {
        String destination = frame.headers.get("destination");
        if (destination == null || frame.body == null || frame.body.isBlank()) {
            return;
        }

        try {
            switch (destination) {
                case "/topic/torrents" -> {
                    TorrentApiClient.TorrentResponse[] torrents =
                            mapper.readValue(frame.body, TorrentApiClient.TorrentResponse[].class);
                    controller.setLiveTorrentSnapshot(List.of(torrents));
                }
                case "/topic/stats" -> {
                    TorrentApiClient.StatsResponse stats =
                            mapper.readValue(frame.body, TorrentApiClient.StatsResponse.class);
                    controller.setStats(stats);
                    controller.setLiveUpdatesConnected(true);
                }
                case "/topic/notifications" -> {
                    LiveNotification notification =
                            mapper.readValue(frame.body, LiveNotification.class);
                    controller.setLiveUpdatesConnected(true);
                    if (notification.message != null && !notification.message.isBlank()) {
                        controller.setStatus(notification.message, "Live");
                    }
                }
                default -> {
                    // We only subscribe to the shared dashboard topics for now.
                }
            }
        } catch (Exception e) {
            log.debug("Failed to parse live-update message for {}: {}", destination, e.getMessage());
        }
    }

    private URI buildSockJsWebSocketUri() {
        URI uri = URI.create(baseUrl);
        String scheme = "https".equalsIgnoreCase(uri.getScheme()) ? "wss" : "ws";
        String host = uri.getHost();
        int port = uri.getPort();
        String pathPrefix = uri.getPath() == null ? "" : uri.getPath();
        if (pathPrefix.endsWith("/")) {
            pathPrefix = pathPrefix.substring(0, pathPrefix.length() - 1);
        }

        String serverId = String.format("%03d", ThreadLocalRandom.current().nextInt(0, 1000));
        String sessionId = UUID.randomUUID().toString().replace("-", "");
        String path = pathPrefix + "/ws/" + serverId + "/" + sessionId + "/websocket";

        try {
            return new URI(
                    scheme,
                    uri.getUserInfo(),
                    host,
                    port,
                    path,
                    null,
                    null
            );
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid websocket URI for " + baseUrl, e);
        }
    }

    private static String normalizeBaseUrl(String baseUrl) {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    private final class Listener implements WebSocket.Listener {

        private final StringBuilder textBuffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            textBuffer.append(data);
            if (last) {
                String payload = textBuffer.toString();
                textBuffer.setLength(0);
                handleSockJsPayload(payload);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            LiveUpdateService.this.webSocket = null;
            controller.setLiveUpdatesConnected(false);
            scheduleReconnect();
            return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            LiveUpdateService.this.webSocket = null;
            controller.setLiveUpdatesConnected(false);
            scheduleReconnect();
        }
    }

    private static final class StompFrame {
        private final String command;
        private final Map<String, String> headers;
        private final String body;

        private StompFrame(String command, Map<String, String> headers, String body) {
            this.command = command;
            this.headers = headers;
            this.body = body;
        }

        private static StompFrame parse(String rawFrame) {
            String normalized = rawFrame == null ? "" : rawFrame.replace("\r", "");
            int nullIdx = normalized.indexOf('\0');
            if (nullIdx >= 0) {
                normalized = normalized.substring(0, nullIdx);
            }

            int separator = normalized.indexOf("\n\n");
            String headerBlock = separator >= 0 ? normalized.substring(0, separator) : normalized;
            String body = separator >= 0 ? normalized.substring(separator + 2) : "";

            String[] lines = headerBlock.split("\n");
            String command = lines.length > 0 ? lines[0].trim() : "";
            java.util.LinkedHashMap<String, String> headers = new java.util.LinkedHashMap<>();
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i];
                int colon = line.indexOf(':');
                if (colon <= 0) {
                    continue;
                }
                headers.put(line.substring(0, colon), line.substring(colon + 1));
            }

            return new StompFrame(command, headers, body);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class LiveNotification {
        public String event;
        public Long torrentId;
        public String message;
        public String torrentName;
    }
}
