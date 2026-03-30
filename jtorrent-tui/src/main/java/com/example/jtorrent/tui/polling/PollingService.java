package com.example.jtorrent.tui.polling;

import com.example.jtorrent.tui.api.TorrentApiClient;
import com.example.jtorrent.tui.api.TorrentApiClient.ApiException;
import com.example.jtorrent.tui.controller.AppController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Background polling service that periodically fetches the torrent list and
 * global statistics from the JTorrent REST API and writes the results into
 * {@link AppController}.
 *
 * <p>The poll interval is 2 seconds — fast enough for live progress bars
 * without hammering the server. The first poll fires immediately on start.
 *
 * <h2>Lifecycle</h2>
 * <pre>
 *   PollingService poller = new PollingService(client, controller);
 *   poller.start();          // begins polling
 *   // … TUI runs …
 *   poller.stop();           // stops polling (called on quit)
 * </pre>
 *
 * <h2>Error handling</h2>
 * On {@link ApiException}, the service marks the controller as disconnected
 * and sets an error status message. Normal polling resumes on the next tick
 * once the server is reachable again — no manual intervention needed.
 */
public class PollingService {

    private static final Logger log = LoggerFactory.getLogger(PollingService.class);

    /** How often (ms) to fetch fresh data from the server. */
    private static final long POLL_INTERVAL_MS = 2_000;

    private final TorrentApiClient client;
    private final AppController controller;

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "jtorrent-poller");
                t.setDaemon(true);   // don't block JVM shutdown
                return t;
            });

    private ScheduledFuture<?> scheduledTask;

    // ─────────────────────────────────────────────────────────────────────────
    // Construction
    // ─────────────────────────────────────────────────────────────────────────

    public PollingService(TorrentApiClient client, AppController controller) {
        this.client     = client;
        this.controller = controller;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Starts the polling loop.
     * The first poll executes immediately (delay = 0).
     */
    public void start() {
        log.info("Starting polling service (interval={}ms)", POLL_INTERVAL_MS);
        scheduledTask = scheduler.scheduleAtFixedRate(
                this::poll,
                0,
                POLL_INTERVAL_MS,
                TimeUnit.MILLISECONDS
        );
    }

    /**
     * Stops the polling loop and shuts down the background thread.
     * Safe to call multiple times.
     */
    public void stop() {
        log.info("Stopping polling service");
        if (scheduledTask != null) {
            scheduledTask.cancel(false);
        }
        scheduler.shutdownNow();
    }

    /**
     * Queue an immediate refresh without waiting for the next scheduled tick.
     */
    public void refreshNow() {
        if (!scheduler.isShutdown()) {
            scheduler.execute(this::poll);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Poll logic
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Single poll tick: fetches torrents + stats in sequence.
     * Exceptions are swallowed here (logged at DEBUG) so a single failure
     * never kills the scheduled task.
     */
    private void poll() {
        try {
            boolean liveConnected = controller.isLiveUpdatesConnected();
            boolean torrentsOk = pollTorrents();
            boolean statsOk = pollStats();
            boolean detailOk = pollDetailIfOpen();
            if (torrentsOk && statsOk && detailOk && !liveConnected) {
                controller.clearStatus();
            }
        } catch (Exception e) {
            // Catches anything unexpected (e.g. RuntimeException in ObjectMapper).
            // ApiExceptions are handled inside the individual methods.
            log.debug("Unexpected error during poll: {}", e.getMessage(), e);
            controller.setDisconnected();
            controller.setError("Poll error: " + e.getMessage());
        }
    }

    private boolean pollTorrents() {
        if (controller.isLiveUpdatesConnected() && controller.hasLiveTorrentSnapshot()) {
            controller.refreshVisibleListFromLiveSnapshot();
            return true;
        }

        try {
            if (controller.listScope() == AppController.ListScope.STALLED) {
                List<TorrentApiClient.TorrentResponse> list = loadStalledScopePage();
                log.debug("Polled {} stalled torrents for current page", list.size());
                return true;
            }

            TorrentApiClient.TorrentSearchRequest request = controller.buildListSearchRequest();
            TorrentApiClient.TorrentPageResponse page = client.searchTorrents(request);

            if (page.safeContent().isEmpty() && request.page != null && request.page > 0 && page.totalPages > 0) {
                request.page = page.totalPages - 1;
                page = client.searchTorrents(request);
            }

            controller.setListData(
                    page.safeContent(),
                    page.number,
                    page.totalPages,
                    page.totalElements,
                    page.size > 0 ? page.size : controller.pageSize()
            );
            log.debug("Polled {} torrents (page {} of {})",
                    page.safeContent().size(), page.number + 1, Math.max(page.totalPages, 1));
            return true;
        } catch (ApiException e) {
            handleApiError("torrent list", e);
            return false;
        }
    }

    private boolean pollStats() {
        if (controller.isLiveUpdatesConnected()) {
            return true;
        }

        try {
            TorrentApiClient.StatsResponse stats = client.getStats();
            controller.setStats(stats);
            return true;
        } catch (ApiException e) {
            handleApiError("stats", e);
            return false;
        }
    }

    private boolean pollDetailIfOpen() {
        if (controller.activeView() != AppController.View.DETAIL) {
            return true;
        }

        Long torrentId = controller.detailTorrentId();
        if (torrentId == null) {
            return true;
        }

        try {
            controller.beginDetailRefresh();

            TorrentApiClient.TorrentResponse detail = client.getTorrent(torrentId);
            if (detail == null) {
                controller.setDetailError("Torrent no longer exists on the server.");
                return false;
            }

            TorrentApiClient.TorrentDetailStatsResponse detailStats = fetchOptionalStats(torrentId);
            TorrentApiClient.TorrentEtaResponse eta = fetchOptionalEta(torrentId);
            Double ratio = fetchOptionalRatio(torrentId);

            controller.setDetailData(detail, detailStats, eta, ratio);
            return true;
        } catch (ApiException e) {
            if (e.isConnectionRefused()) {
                controller.setDisconnected();
                controller.setDetailError("Cannot refresh detail while the server is offline.");
            } else {
                controller.setDetailError("Failed to refresh detail: HTTP " + e.getStatusCode());
            }
            log.debug("Error polling detail for torrent {}: {}", torrentId, e.getMessage());
            return false;
        }
    }

    private TorrentApiClient.TorrentDetailStatsResponse fetchOptionalStats(long torrentId) throws ApiException {
        try {
            return client.getTorrentStats(torrentId);
        } catch (ApiException e) {
            if (e.getStatusCode() == 404) {
                return null;
            }
            throw e;
        }
    }

    private TorrentApiClient.TorrentEtaResponse fetchOptionalEta(long torrentId) throws ApiException {
        try {
            return client.getTorrentEta(torrentId);
        } catch (ApiException e) {
            if (e.getStatusCode() == 404) {
                return null;
            }
            throw e;
        }
    }

    private Double fetchOptionalRatio(long torrentId) throws ApiException {
        try {
            return client.getTorrentRatio(torrentId);
        } catch (ApiException e) {
            if (e.getStatusCode() == 404) {
                return null;
            }
            throw e;
        }
    }

    private List<TorrentApiClient.TorrentResponse> loadStalledScopePage() throws ApiException {
        List<TorrentApiClient.TorrentResponse> stalled = sortForCurrentScope(client.getStalledTorrents());
        String query = controller.filterText().toLowerCase();
        if (!query.isBlank()) {
            stalled = stalled.stream()
                    .filter(t -> t.name != null && t.name.toLowerCase().contains(query))
                    .toList();
        }

        int pageSize = Math.max(controller.pageSize(), 1);
        int total = stalled.size();
        int totalPages = Math.max((int) Math.ceil(total / (double) pageSize), 1);
        int safePage = Math.min(controller.page(), totalPages - 1);
        int fromIndex = Math.min(safePage * pageSize, total);
        int toIndex = Math.min(fromIndex + pageSize, total);

        List<TorrentApiClient.TorrentResponse> pageSlice = stalled.subList(fromIndex, toIndex);
        controller.setListData(pageSlice, safePage, totalPages, total, pageSize);
        return pageSlice;
    }

    private List<TorrentApiClient.TorrentResponse> sortForCurrentScope(
            List<TorrentApiClient.TorrentResponse> torrents) {
        List<TorrentApiClient.TorrentResponse> sorted = new ArrayList<>(torrents);
        Comparator<TorrentApiClient.TorrentResponse> comparator = switch (controller.listSort()) {
            case NAME -> Comparator.comparing(t -> safeString(t.name), String.CASE_INSENSITIVE_ORDER);
            case PROGRESS -> Comparator.comparing(t -> safeDouble(t.progress));
            case SIZE -> Comparator.comparing(t -> safeLong(t.totalSize));
            case ADDED -> Comparator.comparing(t -> safeDate(t.addedDate));
        };

        if (!controller.isSortAscending()) {
            comparator = comparator.reversed();
        }

        sorted.sort(comparator);
        return sorted;
    }

    private static String safeString(String value) {
        return value != null ? value : "";
    }

    private static double safeDouble(Double value) {
        return value != null ? value : 0.0;
    }

    private static long safeLong(Long value) {
        return value != null ? value : 0L;
    }

    private static java.time.LocalDateTime safeDate(java.time.LocalDateTime value) {
        return value != null ? value : java.time.LocalDateTime.MIN;
    }

    /**
     * Translates an {@link ApiException} into a user-visible error message and
     * marks the controller as disconnected when the server is unreachable.
     */
    private void handleApiError(String context, ApiException e) {
        controller.endListRefresh();
        if (controller.isLiveUpdatesConnected()) {
            log.debug("Ignoring HTTP polling error for {} because live updates are connected", context);
            return;
        }
        if (e.isConnectionRefused()) {
            controller.setDisconnected();
            controller.setError("Cannot reach server — is JTorrent running?");
            log.debug("Connection refused while polling {}", context);
        } else {
            controller.setError("Server error (" + e.getStatusCode() + ") fetching " + context);
            log.debug("HTTP {} while polling {}: {}", e.getStatusCode(), context, e.getMessage());
        }
    }
}
