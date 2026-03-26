package com.example.jtorrent.tui.controller;

import com.example.jtorrent.tui.api.TorrentApiClient;

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

    // ─────────────────────────────────────────────────────────────────────────
    // State fields  (all private, mutated only through commands)
    // ─────────────────────────────────────────────────────────────────────────

    // --- Torrent list ---
    private List<TorrentApiClient.TorrentResponse> torrents = Collections.emptyList();
    private int selectedIndex = 0;

    // --- Global stats ---
    private TorrentApiClient.StatsResponse stats = new TorrentApiClient.StatsResponse();

    // --- Active view ---
    private View activeView = View.LIST;

    // --- Add-torrent dialog ---
    private AddMode addMode = AddMode.MAGNET;
    private StringBuilder addInput = new StringBuilder();

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
        if (!filterMode || filterInput.isEmpty()) {
            return List.copyOf(torrents);
        }
        String needle = filterInput.toString().toLowerCase();
        return torrents.stream()
                .filter(t -> t.name != null && t.name.toLowerCase().contains(needle))
                .toList();
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

    /** Latest global statistics snapshot. Never {@code null}. */
    public synchronized TorrentApiClient.StatsResponse stats() {
        return stats;
    }

    /** The currently active view / overlay. */
    public synchronized View activeView() {
        return activeView;
    }

    /** Current text typed into the add-torrent dialog. */
    public synchronized String addInputText() {
        return addInput.toString();
    }

    /** Whether the add-dialog is in MAGNET or FILE mode. */
    public synchronized AddMode addMode() {
        return addMode;
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
        if (selectedIndex > 0) selectedIndex--;
    }

    /** Move the selection one row down. */
    public synchronized void moveDown() {
        List<TorrentApiClient.TorrentResponse> visible = visibleTorrents();
        if (selectedIndex < visible.size() - 1) selectedIndex++;
    }

    /** Jump to the first row. */
    public synchronized void moveTop() {
        selectedIndex = 0;
    }

    /** Jump to the last row. */
    public synchronized void moveBottom() {
        int size = visibleTorrents().size();
        if (size > 0) selectedIndex = size - 1;
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
        addMode  = AddMode.MAGNET;
        addInput.setLength(0);
        activeView = View.ADD_DIALOG;
    }

    /** Cancel the add-dialog without adding a torrent. */
    public synchronized void cancelAddDialog() {
        addInput.setLength(0);
        activeView = View.LIST;
    }

    /** Toggle between MAGNET and FILE input mode inside the add-dialog. */
    public synchronized void toggleAddMode() {
        addMode = (addMode == AddMode.MAGNET) ? AddMode.FILE : AddMode.MAGNET;
        addInput.setLength(0);   // clear input when switching modes
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

    // ─────────────────────────────────────────────────────────────────────────
    // Commands — text input (add-dialog)
    // ─────────────────────────────────────────────────────────────────────────

    /** Append a printable character to the add-dialog input buffer. */
    public synchronized void typeAddChar(char c) {
        addInput.append(c);
    }

    /** Delete the last character from the add-dialog input buffer. */
    public synchronized void addBackspace() {
        if (addInput.length() > 0) addInput.setLength(addInput.length() - 1);
    }

    /** Returns the add-dialog input text and clears the buffer. */
    public synchronized String consumeAddInput() {
        String text = addInput.toString();
        addInput.setLength(0);
        activeView = View.LIST;
        return text;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Commands — text input (filter bar)
    // ─────────────────────────────────────────────────────────────────────────

    /** Open the filter bar. */
    public synchronized void openFilter() {
        filterMode  = true;
        filterInput.setLength(0);
        selectedIndex = 0;
    }

    /** Append a character to the filter buffer. */
    public synchronized void typeFilterChar(char c) {
        filterInput.append(c);
        selectedIndex = 0;   // reset selection whenever filter changes
    }

    /** Delete the last filter character, or close the bar if it is already empty. */
    public synchronized void filterBackspace() {
        if (filterInput.length() > 0) {
            filterInput.setLength(filterInput.length() - 1);
            selectedIndex = 0;
        } else {
            closeFilter();
        }
    }

    /** Close and clear the filter bar. */
    public synchronized void closeFilter() {
        filterMode  = false;
        filterInput.setLength(0);
        selectedIndex = 0;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Commands — status messages
    // ─────────────────────────────────────────────────────────────────────────

    /** Show a success message in the footer. */
    public synchronized void setStatus(String message) {
        statusMessage = message;
        statusIsError = false;
    }

    /** Show an error message in the footer (rendered red). */
    public synchronized void setError(String message) {
        statusMessage = message;
        statusIsError = true;
    }

    /** Clear the footer status message. */
    public synchronized void clearStatus() {
        statusMessage = null;
        statusIsError = false;
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
    public synchronized void setTorrents(List<TorrentApiClient.TorrentResponse> freshList) {
        this.torrents  = freshList == null ? Collections.emptyList() : freshList;
        this.connected = true;

        // Clamp selectedIndex to the new list size
        int visible = visibleTorrents().size();
        if (visible == 0) {
            selectedIndex = 0;
        } else if (selectedIndex >= visible) {
            selectedIndex = visible - 1;
        }

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
    }

    private void clampSelectedFileIndex() {
        int size = detailFiles().size();
        if (size == 0) {
            selectedFileIndex = 0;
        } else if (selectedFileIndex >= size) {
            selectedFileIndex = size - 1;
        }
    }
}
