package com.example.jtorrent.tui.controller;

import com.example.jtorrent.tui.api.TorrentApiClient;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.List;

/**
 * TamboUI Controller for the JTorrent TUI.
 *
 * <p>Following TamboUI's MVC pattern this class owns <em>all</em> application
 * state and exposes it through:
 * <ul>
 *   <li><strong>Queries</strong> — pure, read-only methods the View calls every frame.</li>
 *   <li><strong>Commands</strong> — void mutation methods called by the key-handler.</li>
 * </ul>
 *
 * <p>The background polling loop also writes state here (via {@code setTorrents}
 * / {@code setStats}) after each API call. All mutations are {@code synchronized}
 * so the render thread and the polling thread never race.
 *
 * <h2>View enum — what is currently visible</h2>
 * <pre>
 *  LIST            normal torrent table (default)
 *  DETAIL          detail panel slid in below the selected row
 *  ADD_DIALOG      floating "add torrent" input overlay
 *  CONFIRM_DELETE  yes/no dialog before removing a torrent
 *  HELP            keybinding reference overlay
 * </pre>
 */
public class AppController {

    // ─────────────────────────────────────────────────────────────────────────
    // View enum
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Which overlay / panel is currently active.
     */
    public enum View {
        LIST,
        DETAIL,
        OPS,
        NOTIFICATIONS,
        ADD_DIALOG,
        CONFIRM_DELETE,
        HELP
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Add-dialog input mode
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Whether the user is typing a magnet link or a file path.
     */
    public enum AddMode {
        MAGNET,
        FILE
    }

    /**
     * Which section is visible inside the detail panel.
     */
    public enum DetailTab {
        OVERVIEW,
        FILES,
        STATS
    }

    /**
     * Which section is visible inside the operations/health overlay.
     */
    public enum OpsTab {
        OVERVIEW,
        ORPHANS
    }

    /**
     * Server-backed list scopes exposed in the TUI.
     */
    public enum ListScope {
        ALL("All"),
        ACTIVE("Active"),
        COMPLETED("Completed"),
        ERRORS("Errors"),
        STALLED("Stalled");

        private final String label;

        ListScope(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * Sort fields supported by the server-backed search endpoint.
     */
    public enum ListSort {
        ADDED("addedDate", "Added"),
        NAME("name", "Name"),
        PROGRESS("progress", "Progress"),
        SIZE("totalSize", "Size");

        private final String serverField;
        private final String label;

        ListSort(String serverField, String label) {
            this.serverField = serverField;
            this.label = label;
        }

        public String serverField() {
            return serverField;
        }

        public String label() {
            return label;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // State fields  (all private, mutated only through commands)
    // ─────────────────────────────────────────────────────────────────────────

    // --- Torrent list ---
    private List<TorrentApiClient.TorrentResponse> torrents = Collections.emptyList();
    private int selectedIndex = 0;
    private Long selectedTorrentId = null;
    private ListScope listScope = ListScope.ALL;
    private ListSort listSort = ListSort.ADDED;
    private boolean sortAscending = false;
    private int page = 0;
    private int totalPages = 1;
    private long totalResults = 0;
    private int pageSize = 20;
    private boolean listLoading = false;
    private List<TorrentApiClient.TorrentResponse> liveTorrentSnapshot = Collections.emptyList();
    private boolean liveSnapshotAvailable = false;
    private boolean liveUpdatesConnected = false;

    // --- Global stats ---
    private TorrentApiClient.StatsResponse stats = new TorrentApiClient.StatsResponse();

    // --- Active view ---
    private View activeView = View.LIST;

    // --- Add-torrent dialog ---
    private AddMode addMode = AddMode.MAGNET;

    // --- Operations / health overlay ---
    private OpsTab opsTab = OpsTab.OVERVIEW;
    private TorrentApiClient.SystemHealthResponse systemHealth = null;
    private TorrentApiClient.SystemInfoResponse systemInfo = null;
    private TorrentApiClient.StorageInfoResponse storageInfo = null;
    private List<String> orphanedFiles = Collections.emptyList();
    private int selectedOrphanIndex = 0;
    private boolean opsLoading = false;
    private String opsError = null;

    // --- Detail panel ---
    private Long detailTorrentId = null;
    private TorrentApiClient.TorrentResponse detailTorrent = null;
    private TorrentApiClient.TorrentDetailStatsResponse detailStats = null;
    private TorrentApiClient.TorrentEtaResponse detailEta = null;
    private Double detailRatio = null;
    private DetailTab detailTab = DetailTab.OVERVIEW;
    private int selectedFileIndex = 0;
    private boolean detailLoading = false;
    private String detailError = null;

    // --- Name filter (search) ---
    private boolean filterMode = false;
    private StringBuilder filterInput = new StringBuilder();

    // --- Status / error bar ---
    /** Short message shown in the footer (e.g. "Paused", "Error: …"). Cleared on next poll. */
    private String          statusMessage   = null;
    private boolean         statusIsError   = false;

    // --- Notification history ---
    private final List<NotificationEntry> notifications = new ArrayList<>();
    private int selectedNotificationIndex = 0;
    private int unreadNotificationCount = 0;
    private View notificationsReturnView = View.LIST;

    // --- Connection state ---
    /** True once at least one successful poll has completed. */
    private boolean         connected       = false;

    // ─────────────────────────────────────────────────────────────────────────
    // Queries  (read-only, called by View every frame — must be fast)
    // ─────────────────────────────────────────────────────────────────────────

    /** Returns a snapshot of the current torrent list. Never {@code null}. */
    public synchronized List<TorrentApiClient.TorrentResponse> torrents() {
        return List.copyOf(torrents);
    }

    /** Returns the visible torrents after applying the active name filter. */
    public synchronized List<TorrentApiClient.TorrentResponse> visibleTorrents() {
        return List.copyOf(torrents);
    }

    /** Returns the currently selected torrent, or {@code null} if the list is empty. */
    public synchronized TorrentApiClient.TorrentResponse selectedTorrent() {
        List<TorrentApiClient.TorrentResponse> visible = visibleTorrents();
        if (visible.isEmpty() || selectedIndex >= visible.size()) return null;
        return visible.get(selectedIndex);
    }

    /** Zero-based index of the highlighted row in the visible list. */
    public synchronized int selectedIndex() {
        return selectedIndex;
    }

    /** Active list scope. */
    public synchronized ListScope listScope() {
        return listScope;
    }

    /** Active list sort field. */
    public synchronized ListSort listSort() {
        return listSort;
    }

    /** True when the list is sorted ascending. */
    public synchronized boolean isSortAscending() {
        return sortAscending;
    }

    /** Current page number (0-based). */
    public synchronized int page() {
        return page;
    }

    /** Total number of pages available for the current query. */
    public synchronized int totalPages() {
        return totalPages;
    }

    /** Total number of results available for the current query. */
    public synchronized long totalResults() {
        return totalResults;
    }

    /** Requested page size for the server-backed list. */
    public synchronized int pageSize() {
        return pageSize;
    }

    /** True while the list is waiting on a fresh server response. */
    public synchronized boolean isListLoading() {
        return listLoading;
    }

    /** True when websocket live updates are currently connected. */
    public synchronized boolean isLiveUpdatesConnected() {
        return liveUpdatesConnected;
    }

    /** True once at least one live websocket torrent snapshot has been received. */
    public synchronized boolean hasLiveTorrentSnapshot() {
        return liveSnapshotAvailable;
    }

    /** True when there is another page after the current one. */
    public synchronized boolean hasNextPage() {
        return page + 1 < totalPages;
    }

    /** True when there is a page before the current one. */
    public synchronized boolean hasPreviousPage() {
        return page > 0;
    }

    /** True when a server-side name query is active. */
    public synchronized boolean hasActiveQuery() {
        return !filterInput.isEmpty();
    }

    /** Latest global statistics snapshot. Never {@code null}. */
    public synchronized TorrentApiClient.StatsResponse stats() {
        return stats;
    }

    /** The currently active view / overlay. */
    public synchronized View activeView() {
        return activeView;
    }

    /** Whether the add-dialog is in MAGNET or FILE mode. */
    public synchronized AddMode addMode() {
        return addMode;
    }

    /** Active operations/health tab. */
    public synchronized OpsTab opsTab() {
        return opsTab;
    }

    /** Latest system health snapshot, or {@code null} if not loaded yet. */
    public synchronized TorrentApiClient.SystemHealthResponse systemHealth() {
        return systemHealth;
    }

    /** Latest system info snapshot, or {@code null} if not loaded yet. */
    public synchronized TorrentApiClient.SystemInfoResponse systemInfo() {
        return systemInfo;
    }

    /** Latest storage snapshot, or {@code null} if not loaded yet. */
    public synchronized TorrentApiClient.StorageInfoResponse storageInfo() {
        return storageInfo;
    }

    /** Snapshot of orphaned file paths from the last refresh. */
    public synchronized List<String> orphanedFiles() {
        return List.copyOf(orphanedFiles);
    }

    /** Zero-based selection inside the orphaned-file list. */
    public synchronized int selectedOrphanIndex() {
        return selectedOrphanIndex;
    }

    /** Currently highlighted orphaned file, or {@code null} if none exist. */
    public synchronized String selectedOrphanPath() {
        if (orphanedFiles.isEmpty() || selectedOrphanIndex >= orphanedFiles.size()) {
            return null;
        }
        return orphanedFiles.get(selectedOrphanIndex);
    }

    /** True while the operations overlay is refreshing. */
    public synchronized boolean isOpsLoading() {
        return opsLoading;
    }

    /** Last operations-overlay error, or {@code null}. */
    public synchronized String opsError() {
        return opsError;
    }

    /** ID of the torrent currently open in the detail panel, if any. */
    public synchronized Long detailTorrentId() {
        return detailTorrentId;
    }

    /** Detail-panel snapshot for the selected torrent, or {@code null} until loaded. */
    public synchronized TorrentApiClient.TorrentResponse detailTorrent() {
        return detailTorrent;
    }

    /** Per-torrent statistics for the detail view, or {@code null} if unavailable. */
    public synchronized TorrentApiClient.TorrentDetailStatsResponse detailStats() {
        return detailStats;
    }

    /** ETA snapshot for the detail view, or {@code null} if unavailable. */
    public synchronized TorrentApiClient.TorrentEtaResponse detailEta() {
        return detailEta;
    }

    /** Share ratio for the torrent currently shown in detail, or {@code null}. */
    public synchronized Double detailRatio() {
        return detailRatio;
    }

    /** Active detail tab. */
    public synchronized DetailTab detailTab() {
        return detailTab;
    }

    /** Returns a snapshot of the files for the torrent shown in detail. */
    public synchronized List<TorrentApiClient.TorrentFileResponse> detailFiles() {
        if (detailTorrent == null || detailTorrent.files == null) {
            return List.of();
        }
        return List.copyOf(detailTorrent.files);
    }

    /** Zero-based index of the highlighted file row in the detail panel. */
    public synchronized int selectedFileIndex() {
        return selectedFileIndex;
    }

    /** Currently highlighted file in the detail panel, or {@code null}. */
    public synchronized TorrentApiClient.TorrentFileResponse selectedFile() {
        List<TorrentApiClient.TorrentFileResponse> files = detailFiles();
        if (files.isEmpty() || selectedFileIndex >= files.size()) {
            return null;
        }
        return files.get(selectedFileIndex);
    }

    /** True while detail data is being refreshed. */
    public synchronized boolean isDetailLoading() {
        return detailLoading;
    }

    /** Last detail-loading error, or {@code null}. */
    public synchronized String detailError() {
        return detailError;
    }

    /** Current text typed into the name-filter bar. */
    public synchronized String filterText() {
        return filterInput.toString();
    }

    /** True while the filter bar is open. */
    public synchronized boolean isFilterMode() {
        return filterMode;
    }

    /** Returns the footer status message, or {@code null} if there is none. */
    public synchronized String statusMessage() {
        return statusMessage;
    }

    /** Snapshot of notification history, newest first. */
    public synchronized List<NotificationEntry> notifications() {
        return List.copyOf(notifications);
    }

    /** Currently selected notification history row. */
    public synchronized int selectedNotificationIndex() {
        return selectedNotificationIndex;
    }

    /** Currently selected notification entry, or {@code null} if history is empty. */
    public synchronized NotificationEntry selectedNotification() {
        if (notifications.isEmpty() || selectedNotificationIndex >= notifications.size()) {
            return null;
        }
        return notifications.get(selectedNotificationIndex);
    }

    /** Number of unread notification entries. */
    public synchronized int unreadNotificationCount() {
        return unreadNotificationCount;
    }

    /** True if the current status message is an error (shown in red). */
    public synchronized boolean statusIsError() {
        return statusIsError;
    }

    /** True once the first successful poll has completed. */
    public synchronized boolean isConnected() {
        return connected;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Commands — navigation
    // ─────────────────────────────────────────────────────────────────────────

    /** Move the selection one row up. */
    public synchronized void moveUp() {
        if (selectedIndex > 0) {
            selectedIndex--;
            syncSelectedTorrentId();
        }
    }

    /** Move the selection one row down. */
    public synchronized void moveDown() {
        List<TorrentApiClient.TorrentResponse> visible = visibleTorrents();
        if (selectedIndex < visible.size() - 1) {
            selectedIndex++;
            syncSelectedTorrentId();
        }
    }

    /** Jump to the first row. */
    public synchronized void moveTop() {
        selectedIndex = 0;
        syncSelectedTorrentId();
    }

    /** Jump to the last row. */
    public synchronized void moveBottom() {
        int size = visibleTorrents().size();
        if (size > 0) {
            selectedIndex = size - 1;
            syncSelectedTorrentId();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Commands — view transitions
    // ─────────────────────────────────────────────────────────────────────────

    /** Open the detail panel for the selected torrent. */
    public synchronized void openDetail() {
        TorrentApiClient.TorrentResponse selected = selectedTorrent();
        if (selected == null || selected.id == null) {
            return;
        }

        activeView = View.DETAIL;
        detailTab = DetailTab.OVERVIEW;
        selectedFileIndex = 0;
        detailTorrentId = selected.id;
        detailTorrent = selected;
        detailStats = null;
        detailEta = null;
        detailRatio = null;
        detailLoading = true;
        detailError = null;
    }

    /** Close detail panel and return to the list. */
    public synchronized void closeDetail() {
        activeView = View.LIST;
        detailLoading = false;
        detailError = null;
    }

    /** Open the add-torrent dialog (defaults to MAGNET mode). */
    public synchronized void openAddDialog() {
        addMode = AddMode.MAGNET;
        activeView = View.ADD_DIALOG;
    }

    /** Open the operations / health overlay. */
    public synchronized void openOps() {
        opsTab = OpsTab.OVERVIEW;
        opsLoading = true;
        opsError = null;
        activeView = View.OPS;
    }

    /** Close the operations overlay and return to the list. */
    public synchronized void closeOps() {
        activeView = View.LIST;
    }

    /** Open the notification history overlay and remember where to return. */
    public synchronized void openNotifications(View returnView) {
        notificationsReturnView = returnView != null ? returnView : View.LIST;
        activeView = View.NOTIFICATIONS;
        selectedNotificationIndex = 0;
        unreadNotificationCount = 0;
        clampSelectedNotificationIndex();
    }

    /** Close the notification history overlay and return to the previous view. */
    public synchronized void closeNotifications() {
        activeView = notificationsReturnView != View.NOTIFICATIONS
                ? notificationsReturnView
                : View.LIST;
        unreadNotificationCount = 0;
    }

    /** Switch to the next operations tab. */
    public synchronized void nextOpsTab() {
        opsTab = (opsTab == OpsTab.OVERVIEW) ? OpsTab.ORPHANS : OpsTab.OVERVIEW;
    }

    /** Switch to the previous operations tab. */
    public synchronized void previousOpsTab() {
        nextOpsTab();
    }

    /** Cancel the add-dialog without adding a torrent. */
    public synchronized void cancelAddDialog() {
        activeView = View.LIST;
    }

    /** Toggle between MAGNET and FILE input mode inside the add-dialog. */
    public synchronized void toggleAddMode() {
        addMode = (addMode == AddMode.MAGNET) ? AddMode.FILE : AddMode.MAGNET;
    }

    /** Open the delete-confirmation overlay for the selected torrent. */
    public synchronized void openConfirmDelete() {
        if (selectedTorrent() != null) activeView = View.CONFIRM_DELETE;
    }

    /** Dismiss the delete-confirmation overlay without deleting. */
    public synchronized void cancelConfirmDelete() {
        activeView = View.LIST;
    }

    /** Open the help overlay. */
    public synchronized void openHelp() {
        activeView = View.HELP;
    }

    /** Close the help overlay and return to the list. */
    public synchronized void closeHelp() {
        activeView = View.LIST;
    }

    /** Switch to the next detail tab. */
    public synchronized void nextDetailTab() {
        detailTab = switch (detailTab) {
            case OVERVIEW -> DetailTab.FILES;
            case FILES -> DetailTab.STATS;
            case STATS -> DetailTab.OVERVIEW;
        };
    }

    /** Switch to the previous detail tab. */
    public synchronized void previousDetailTab() {
        detailTab = switch (detailTab) {
            case OVERVIEW -> DetailTab.STATS;
            case FILES -> DetailTab.OVERVIEW;
            case STATS -> DetailTab.FILES;
        };
    }

    /** Move the file selection one row up. */
    public synchronized void moveFileUp() {
        if (selectedFileIndex > 0) {
            selectedFileIndex--;
        }
    }

    /** Move the file selection one row down. */
    public synchronized void moveFileDown() {
        List<TorrentApiClient.TorrentFileResponse> files = detailFiles();
        if (selectedFileIndex < files.size() - 1) {
            selectedFileIndex++;
        }
    }

    /** Move the orphan-file selection one row up. */
    public synchronized void moveOrphanUp() {
        if (selectedOrphanIndex > 0) {
            selectedOrphanIndex--;
        }
    }

    /** Move the orphan-file selection one row down. */
    public synchronized void moveOrphanDown() {
        if (selectedOrphanIndex < orphanedFiles.size() - 1) {
            selectedOrphanIndex++;
        }
    }

    /** Move the notification selection one row up. */
    public synchronized void moveNotificationUp() {
        if (selectedNotificationIndex > 0) {
            selectedNotificationIndex--;
        }
    }

    /** Move the notification selection one row down. */
    public synchronized void moveNotificationDown() {
        if (selectedNotificationIndex < notifications.size() - 1) {
            selectedNotificationIndex++;
        }
    }

    /** Jump to the newest notification. */
    public synchronized void moveNotificationTop() {
        selectedNotificationIndex = 0;
    }

    /** Jump to the oldest notification. */
    public synchronized void moveNotificationBottom() {
        if (!notifications.isEmpty()) {
            selectedNotificationIndex = notifications.size() - 1;
        }
    }

    /** Clear the notification history. */
    public synchronized void clearNotifications() {
        notifications.clear();
        selectedNotificationIndex = 0;
        unreadNotificationCount = 0;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Commands — text input (filter bar)
    // ─────────────────────────────────────────────────────────────────────────

    /** Open the filter bar. */
    public synchronized void openFilter() {
        filterMode  = true;
    }

    /** Append a character to the filter buffer. */
    public synchronized void typeFilterChar(char c) {
        filterInput.append(c);
        page = 0;
        selectedIndex = 0;
        selectedTorrentId = null;
        listLoading = true;
    }

    /** Delete the last filter character, or close the bar if it is already empty. */
    public synchronized void filterBackspace() {
        if (filterInput.length() > 0) {
            filterInput.setLength(filterInput.length() - 1);
            page = 0;
            selectedIndex = 0;
            selectedTorrentId = null;
            listLoading = true;
        } else {
            clearFilter();
        }
    }

    /** Stop editing the filter while keeping the current query active. */
    public synchronized void applyFilter() {
        filterMode  = false;
    }

    /** Close and clear the filter bar. */
    public synchronized void clearFilter() {
        filterMode  = false;
        filterInput.setLength(0);
        page = 0;
        selectedIndex = 0;
        selectedTorrentId = null;
        listLoading = true;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Commands — status messages
    // ─────────────────────────────────────────────────────────────────────────

    /** Show a success message in the footer. */
    public synchronized void setStatus(String message) {
        setStatus(message, "TUI");
    }

    /** Show a success/info message with a source label. */
    public synchronized void setStatus(String message, String source) {
        statusMessage = message;
        statusIsError = false;
        recordNotification(message, false, source);
    }

    /** Show an error message in the footer (rendered red). */
    public synchronized void setError(String message) {
        setError(message, "TUI");
    }

    /** Show an error message with a source label. */
    public synchronized void setError(String message, String source) {
        statusMessage = message;
        statusIsError = true;
        recordNotification(message, true, source);
    }

    /** Clear the footer status message. */
    public synchronized void clearStatus() {
        statusMessage = null;
        statusIsError = false;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Commands — operations overlay data
    // ─────────────────────────────────────────────────────────────────────────

    /** Mark the operations overlay as loading. */
    public synchronized void beginOpsRefresh() {
        opsLoading = true;
        opsError = null;
    }

    /** Replace the operations snapshot with fresh server data. */
    public synchronized void setOpsData(
            TorrentApiClient.SystemHealthResponse health,
            TorrentApiClient.SystemInfoResponse info,
            TorrentApiClient.StorageInfoResponse storage,
            List<String> orphanedFiles) {

        systemHealth = health;
        systemInfo = info;
        storageInfo = storage;
        this.orphanedFiles = orphanedFiles == null ? Collections.emptyList() : List.copyOf(orphanedFiles);
        opsLoading = false;
        opsError = null;
        clampSelectedOrphanIndex();
    }

    /** Record an operations refresh failure without dropping the last good snapshot. */
    public synchronized void setOpsError(String message) {
        opsLoading = false;
        opsError = message;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Commands — list query state
    // ─────────────────────────────────────────────────────────────────────────

    /** Mark the server-backed list as loading. */
    public synchronized void beginListRefresh() {
        listLoading = true;
    }

    /** Clear the list-loading indicator without replacing the current rows. */
    public synchronized void endListRefresh() {
        listLoading = false;
    }

    /** Switch to a different list scope and reset paging. */
    public synchronized void setListScope(ListScope scope) {
        if (scope == null || scope == listScope) {
            return;
        }
        listScope = scope;
        page = 0;
        selectedIndex = 0;
        selectedTorrentId = null;
        listLoading = true;
    }

    /** Cycle through supported server-side sort fields. */
    public synchronized void cycleListSort() {
        listSort = switch (listSort) {
            case ADDED -> ListSort.NAME;
            case NAME -> ListSort.PROGRESS;
            case PROGRESS -> ListSort.SIZE;
            case SIZE -> ListSort.ADDED;
        };
        page = 0;
        listLoading = true;
    }

    /** Flip the current server-side sort direction. */
    public synchronized void toggleSortDirection() {
        sortAscending = !sortAscending;
        page = 0;
        listLoading = true;
    }

    /** Move to the next page if one exists. */
    public synchronized void nextPage() {
        if (page + 1 < totalPages) {
            page++;
            selectedIndex = 0;
            selectedTorrentId = null;
            listLoading = true;
        }
    }

    /** Move to the previous page if one exists. */
    public synchronized void previousPage() {
        if (page > 0) {
            page--;
            selectedIndex = 0;
            selectedTorrentId = null;
            listLoading = true;
        }
    }

    /**
     * Build a server-side search request for the current list scope/query.
     * The dedicated STALLED view is fetched through its own endpoint.
     */
    public synchronized TorrentApiClient.TorrentSearchRequest buildListSearchRequest() {
        TorrentApiClient.TorrentSearchRequest request = new TorrentApiClient.TorrentSearchRequest();
        request.name = filterInput.isEmpty() ? null : filterInput.toString();
        request.sortBy = listSort.serverField();
        request.sortDirection = sortAscending ? "ASC" : "DESC";
        request.page = page;
        request.size = pageSize;

        switch (listScope) {
            case ACTIVE -> request.statuses = List.of("DOWNLOADING", "SEEDING", "CHECKING");
            case COMPLETED -> request.status = "COMPLETED";
            case ERRORS -> request.hasErrors = true;
            default -> {
                // ALL uses the default request, STALLED is handled separately.
            }
        }
        return request;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Commands — detail panel data
    // ─────────────────────────────────────────────────────────────────────────

    /** Mark the detail panel as loading. */
    public synchronized void beginDetailRefresh() {
        if (detailTorrentId == null) {
            return;
        }
        detailLoading = true;
        detailError = null;
    }

    /**
     * Replace the detail snapshot with fresh server data.
     */
    public synchronized void setDetailData(
            TorrentApiClient.TorrentResponse torrent,
            TorrentApiClient.TorrentDetailStatsResponse stats,
            TorrentApiClient.TorrentEtaResponse eta,
            Double ratio) {

        if (detailTorrentId == null) {
            return;
        }
        if (torrent != null && torrent.id != null && !detailTorrentId.equals(torrent.id)) {
            return;
        }

        if (torrent != null) {
            detailTorrent = torrent;
            detailTorrentId = torrent.id;
        }
        detailStats = stats;
        detailEta = eta;
        detailRatio = ratio;
        detailLoading = false;
        detailError = null;
        clampSelectedFileIndex();
    }

    /** Record a detail-refresh failure without dropping the already loaded snapshot. */
    public synchronized void setDetailError(String message) {
        detailLoading = false;
        detailError = message;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Commands — background polling writes
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Called by the polling thread with a fresh torrent list from the server.
     *
     * <p>The selection index is clamped so it never goes out of bounds after a
     * torrent is removed between polls.
     */
    public synchronized void setListData(
            List<TorrentApiClient.TorrentResponse> freshList,
            int page,
            int totalPages,
            long totalResults,
            int pageSize) {
        this.torrents  = freshList == null ? Collections.emptyList() : freshList;
        this.connected = true;
        this.listLoading = false;
        this.page = Math.max(page, 0);
        this.totalPages = Math.max(totalPages, 1);
        this.totalResults = Math.max(totalResults, 0L);
        this.pageSize = Math.max(pageSize, 1);

        syncSelectionAfterListRefresh();

        // Keep the detail header in sync with the live list while the richer
        // detail snapshot is being refreshed in the background.
        if (detailTorrentId != null) {
            this.torrents.stream()
                    .filter(t -> detailTorrentId.equals(t.id))
                    .findFirst()
                    .ifPresent(t -> {
                        if (detailTorrent == null) {
                            detailTorrent = t;
                        } else {
                            detailTorrent.name = t.name;
                            detailTorrent.status = t.status;
                            detailTorrent.progress = t.progress;
                            detailTorrent.totalSize = t.totalSize;
                            detailTorrent.downloadedSize = t.downloadedSize;
                            detailTorrent.uploadedSize = t.uploadedSize;
                            detailTorrent.downloadSpeed = t.downloadSpeed;
                            detailTorrent.uploadSpeed = t.uploadSpeed;
                            detailTorrent.peers = t.peers;
                            detailTorrent.seeds = t.seeds;
                            detailTorrent.errorMessage = t.errorMessage;
                            detailTorrent.completedDate = t.completedDate;
                        }
                    });
        }
    }

    /**
     * Called by the polling thread with fresh global statistics.
     */
    public synchronized void setStats(TorrentApiClient.StatsResponse freshStats) {
        this.stats = freshStats != null ? freshStats : new TorrentApiClient.StatsResponse();
    }

    /**
     * Called by the polling thread when the server is unreachable.
     */
    public synchronized void setDisconnected() {
        this.connected = false;
        this.listLoading = false;
    }

    /**
     * Mark the websocket live-update channel as connected or disconnected.
     */
    public synchronized void setLiveUpdatesConnected(boolean connected) {
        this.liveUpdatesConnected = connected;
    }

    /**
     * Replace the cached full torrent list received from websocket live updates
     * and project the current page/scope/query from it.
     */
    public synchronized void setLiveTorrentSnapshot(List<TorrentApiClient.TorrentResponse> snapshot) {
        liveTorrentSnapshot = snapshot == null ? Collections.emptyList() : List.copyOf(snapshot);
        liveSnapshotAvailable = true;
        liveUpdatesConnected = true;
        connected = true;
        applyCurrentListQueryToLiveSnapshot();
        syncDetailHeaderFromLiveSnapshot();
    }

    /**
     * Re-apply the current list query/sort/page onto the cached websocket
     * snapshot. Used when the user changes views while live updates are active.
     */
    public synchronized void refreshVisibleListFromLiveSnapshot() {
        if (!liveSnapshotAvailable) {
            return;
        }
        applyCurrentListQueryToLiveSnapshot();
        syncDetailHeaderFromLiveSnapshot();
    }

    private void clampSelectedFileIndex() {
        int size = detailFiles().size();
        if (size == 0) {
            selectedFileIndex = 0;
        } else if (selectedFileIndex >= size) {
            selectedFileIndex = size - 1;
        }
    }

    private void clampSelectedOrphanIndex() {
        if (orphanedFiles.isEmpty()) {
            selectedOrphanIndex = 0;
        } else if (selectedOrphanIndex >= orphanedFiles.size()) {
            selectedOrphanIndex = orphanedFiles.size() - 1;
        }
    }

    private void clampSelectedNotificationIndex() {
        if (notifications.isEmpty()) {
            selectedNotificationIndex = 0;
        } else if (selectedNotificationIndex >= notifications.size()) {
            selectedNotificationIndex = notifications.size() - 1;
        }
    }

    private void syncSelectedTorrentId() {
        if (torrents.isEmpty() || selectedIndex >= torrents.size()) {
            selectedTorrentId = null;
            return;
        }
        selectedTorrentId = torrents.get(selectedIndex).id;
    }

    private void syncSelectionAfterListRefresh() {
        if (torrents.isEmpty()) {
            selectedIndex = 0;
            selectedTorrentId = null;
            return;
        }

        if (selectedTorrentId != null) {
            for (int i = 0; i < torrents.size(); i++) {
                if (selectedTorrentId.equals(torrents.get(i).id)) {
                    selectedIndex = i;
                    return;
                }
            }
        }

        if (selectedIndex >= torrents.size()) {
            selectedIndex = torrents.size() - 1;
        }
        if (selectedIndex < 0) {
            selectedIndex = 0;
        }
        syncSelectedTorrentId();
    }

    private void applyCurrentListQueryToLiveSnapshot() {
        List<TorrentApiClient.TorrentResponse> filtered = new ArrayList<>(liveTorrentSnapshot);
        filtered.removeIf(t -> !matchesCurrentScope(t) || !matchesCurrentQuery(t));
        filtered.sort(currentListComparator());

        int safePageSize = Math.max(pageSize, 1);
        int total = filtered.size();
        int computedTotalPages = Math.max((int) Math.ceil(total / (double) safePageSize), 1);
        int safePage = Math.min(page, computedTotalPages - 1);
        int fromIndex = Math.min(safePage * safePageSize, total);
        int toIndex = Math.min(fromIndex + safePageSize, total);

        torrents = List.copyOf(filtered.subList(fromIndex, toIndex));
        page = safePage;
        totalPages = computedTotalPages;
        totalResults = total;
        listLoading = false;
        syncSelectionAfterListRefresh();
    }

    private boolean matchesCurrentScope(TorrentApiClient.TorrentResponse torrent) {
        return switch (listScope) {
            case ALL -> true;
            case ACTIVE -> matchesAnyStatus(torrent, "DOWNLOADING", "SEEDING", "CHECKING");
            case COMPLETED -> matchesStatus(torrent, "COMPLETED");
            case ERRORS -> matchesStatus(torrent, "ERROR")
                    || (torrent.errorMessage != null && !torrent.errorMessage.isBlank());
            case STALLED -> matchesStatus(torrent, "DOWNLOADING")
                    && (torrent.downloadSpeed == null || torrent.downloadSpeed == 0)
                    && (torrent.peers == null || torrent.peers == 0);
        };
    }

    private boolean matchesCurrentQuery(TorrentApiClient.TorrentResponse torrent) {
        if (filterInput.isEmpty()) {
            return true;
        }
        if (torrent.name == null) {
            return false;
        }
        return torrent.name.toLowerCase().contains(filterInput.toString().toLowerCase());
    }

    private Comparator<TorrentApiClient.TorrentResponse> currentListComparator() {
        Comparator<TorrentApiClient.TorrentResponse> comparator = switch (listSort) {
            case NAME -> Comparator.comparing(
                    t -> safeString(t.name),
                    String.CASE_INSENSITIVE_ORDER
            );
            case PROGRESS -> Comparator.comparing(t -> safeDouble(t.progress));
            case SIZE -> Comparator.comparing(t -> safeLong(t.totalSize));
            case ADDED -> Comparator.comparing(t -> safeDate(t.addedDate));
        };
        return sortAscending ? comparator : comparator.reversed();
    }

    private void syncDetailHeaderFromLiveSnapshot() {
        if (detailTorrentId == null || liveTorrentSnapshot.isEmpty()) {
            return;
        }

        liveTorrentSnapshot.stream()
                .filter(t -> detailTorrentId.equals(t.id))
                .findFirst()
                .ifPresent(t -> {
                    if (detailTorrent == null) {
                        detailTorrent = t;
                    } else {
                        detailTorrent.name = t.name;
                        detailTorrent.status = t.status;
                        detailTorrent.progress = t.progress;
                        detailTorrent.totalSize = t.totalSize;
                        detailTorrent.downloadedSize = t.downloadedSize;
                        detailTorrent.uploadedSize = t.uploadedSize;
                        detailTorrent.downloadSpeed = t.downloadSpeed;
                        detailTorrent.uploadSpeed = t.uploadSpeed;
                        detailTorrent.peers = t.peers;
                        detailTorrent.seeds = t.seeds;
                        detailTorrent.errorMessage = t.errorMessage;
                        detailTorrent.completedDate = t.completedDate;
                    }
                });
    }

    private boolean matchesStatus(TorrentApiClient.TorrentResponse torrent, String status) {
        return torrent != null && torrent.status != null && torrent.status.equalsIgnoreCase(status);
    }

    private boolean matchesAnyStatus(TorrentApiClient.TorrentResponse torrent, String... statuses) {
        if (torrent == null || torrent.status == null) {
            return false;
        }
        for (String status : statuses) {
            if (torrent.status.equalsIgnoreCase(status)) {
                return true;
            }
        }
        return false;
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

    private static LocalDateTime safeDate(LocalDateTime value) {
        return value != null ? value : LocalDateTime.MIN;
    }

    private void recordNotification(String message, boolean error, String source) {
        if (message == null || message.isBlank()) {
            return;
        }

        String normalizedSource = source != null && !source.isBlank() ? source : "TUI";
        LocalDateTime now = LocalDateTime.now();
        NotificationEntry latest = notifications.isEmpty() ? null : notifications.get(0);
        if (latest != null
                && latest.error == error
                && latest.message.equals(message)
                && latest.source.equals(normalizedSource)
                && Duration.between(latest.timestamp, now).abs().getSeconds() <= 10) {
            return;
        }

        notifications.add(0, new NotificationEntry(now, normalizedSource, message, error));
        if (notifications.size() > 100) {
            notifications.remove(notifications.size() - 1);
        }

        if (activeView == View.NOTIFICATIONS) {
            unreadNotificationCount = 0;
            selectedNotificationIndex = 0;
        } else {
            unreadNotificationCount++;
        }
        clampSelectedNotificationIndex();
    }

    /** Immutable notification-history entry. */
    public static final class NotificationEntry {
        private final LocalDateTime timestamp;
        private final String source;
        private final String message;
        private final boolean error;

        public NotificationEntry(LocalDateTime timestamp, String source, String message, boolean error) {
            this.timestamp = timestamp;
            this.source = source != null && !source.isBlank() ? source : "TUI";
            this.message = message != null ? message : "";
            this.error = error;
        }

        public LocalDateTime timestamp() {
            return timestamp;
        }

        public String source() {
            return source;
        }

        public String message() {
            return message;
        }

        public boolean isError() {
            return error;
        }

        public String levelLabel() {
            return error ? "ERROR" : "INFO";
        }
    }
}
