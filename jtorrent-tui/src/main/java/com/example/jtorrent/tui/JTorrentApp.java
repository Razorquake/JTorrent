package com.example.jtorrent.tui;


import com.example.jtorrent.tui.api.TorrentApiClient;
import com.example.jtorrent.tui.controller.AppController;
import com.example.jtorrent.tui.live.LiveUpdateService;
import com.example.jtorrent.tui.polling.PollingService;
import com.example.jtorrent.tui.view.*;
import dev.tamboui.layout.Constraint;
import dev.tamboui.toolkit.app.ToolkitApp;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.bindings.BindingSets;
import dev.tamboui.tui.bindings.KeyTrigger;
import dev.tamboui.tui.event.KeyCode;

import java.awt.HeadlessException;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static dev.tamboui.toolkit.Toolkit.*;

/**
 * JTorrentApp — the root {@link ToolkitApp} that wires all views together.
 *
 * <h2>Architecture</h2>
 * <pre>
 *  ┌────────────────────────────────────────────┐
 *  │ JTorrentApp (ToolkitApp)                   │
 *  │                                            │
 *  │  render()  ──► dock()                      │
 *  │                  .top(GlobalStatsBar)      │
 *  │                  .center(active view)      │
 *  │                  .bottom(status)           │
 *  │                                            │
 *  │  onStart() ──► PollingService.start()      │
 *  │  onStop()  ──► PollingService.stop()       │
 *  └────────────────────────────────────────────┘
 *         │                     ▲
 *   key events             poll writes
 *         ▼                     │
 *   AppController ◄──── PollingService
 *         │
 *   view queries
 *         ▼
 *   View classes (pure render functions)
 * </pre>
 *
 * <h2>Threading</h2>
 * <ul>
 *   <li>Render thread — calls {@code render()} and all view methods.</li>
 *   <li>Polling thread — calls {@code AppController.setTorrents()} /
 *       {@code setStats()} which are {@code synchronized}. No
 *       {@code runOnRenderThread()} is needed because {@code AppController}
 *       is fully synchronized and the views snapshot state via
 *       {@code List.copyOf()} inside synchronized queries.</li>
 * </ul>
 *
 * <h2>Launching</h2>
 * <pre>
 *   java -jar jtorrent-tui-all.jar [--server <a href="http://host:8080">...</a>]
 * </pre>
 */
public class JTorrentApp extends ToolkitApp {

    // ─────────────────────────────────────────────────────────────────────────
    // State shared between render thread and polling thread
    // ─────────────────────────────────────────────────────────────────────────

    private final AppController controller  = new AppController();
    private final TorrentApiClient client;
    private final PollingService poller;
    private final LiveUpdateService liveUpdates;

    // ─────────────────────────────────────────────────────────────────────────
    // View components (constructed once, re-used every frame)
    // ─────────────────────────────────────────────────────────────────────────

    private final GlobalStatsBar statsBar;
    private final TorrentListView listView;
    private final TorrentDetailPanel detailPanel;
    private final OpsOverlay opsOverlay;
    private final NotificationOverlay notificationOverlay;
    private final AddTorrentDialog addDialog;
    private final HelpOverlay helpOverlay;


    // ─────────────────────────────────────────────────────────────────────────
    // Constructor
    // ─────────────────────────────────────────────────────────────────────────

    public JTorrentApp(String serverUrl) {
        this.client      = new TorrentApiClient(serverUrl);
        this.poller      = new PollingService(client, controller);
        this.liveUpdates = new LiveUpdateService(serverUrl, controller);
        this.statsBar    = new GlobalStatsBar(controller);
        this.listView    = new TorrentListView(controller);
        this.detailPanel = new TorrentDetailPanel(controller);
        this.opsOverlay  = new OpsOverlay(controller);
        this.notificationOverlay = new NotificationOverlay(controller);
        this.addDialog   = new AddTorrentDialog(controller);
        this.helpOverlay = new HelpOverlay();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ToolkitApp lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected TuiConfig configure() {
        var bindings = BindingSets.defaults()
                .toBuilder()
                // Keep Tab available for app-level behavior (mode switch in Add dialog).
                .unbind("focusNext")
                .unbind("focusPrevious")
                // Some terminals encode Backspace as Ctrl+H.
                .bind(KeyTrigger.ctrl('h'), "deleteBackward")
                .build();

        return TuiConfig.builder()
                // 500 ms tick drives re-renders so live progress updates appear
                // even when no key is pressed.
                .tickRate(Duration.ofMillis(500))
                .bindings(bindings)
                .build();
    }

    @Override
    protected void onStart() {
        poller.start();
        liveUpdates.start();
    }

    @Override
    protected void onStop() {
        liveUpdates.stop();
        poller.stop();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // render() — called every frame on the render thread
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected Element render() {
        AppController.View activeView = controller.activeView();

        Element center = switch (activeView) {
            case DETAIL         -> detailPanel.render();
            case OPS            -> opsOverlay.render();
            case NOTIFICATIONS  -> notificationOverlay.render();
            case ADD_DIALOG     -> addDialog.render();
            case CONFIRM_DELETE -> confirmDeleteOverlay();
            case HELP           -> helpOverlay.render();
            default             -> listView.render();
        };

        return dock()
                .top(statsBar.render(), Constraint.length(3))
                .center(center)
                .onKeyEvent(event -> handleKey(event, activeView));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Key handler — attached at the root dock so it sees keys across views
    // ─────────────────────────────────────────────────────────────────────────

    private EventResult handleKey(dev.tamboui.tui.event.KeyEvent event, AppController.View view) {

        // ── Global quit (works in any view) ──────────────────────────────────
        if (event.isQuit()) {
            quit();
            return EventResult.HANDLED;
        }

        if (event.isChar('N')
                && view != AppController.View.NOTIFICATIONS
                && view != AppController.View.ADD_DIALOG
                && view != AppController.View.CONFIRM_DELETE
                && !(view == AppController.View.LIST && controller.isFilterMode())) {
            controller.openNotifications(view);
            return EventResult.HANDLED;
        }

        return switch (view) {
            case LIST           -> handleListKey(event);
            case DETAIL         -> handleDetailKey(event);
            case OPS            -> handleOpsKey(event);
            case NOTIFICATIONS  -> handleNotificationsKey(event);
            case ADD_DIALOG     -> handleAddDialogKey(event);
            case CONFIRM_DELETE -> handleConfirmDeleteKey(event);
            case HELP           -> handleHelpKey(event);
        };
    }

    // ── List view keys ────────────────────────────────────────────────────────

    private EventResult handleListKey(dev.tamboui.tui.event.KeyEvent event) {
        // Filter mode — capture all printable chars for the search bar
        if (controller.isFilterMode()) {
            if (event.isCancel())  {
                controller.clearFilter();
                requestListRefresh();
                return EventResult.HANDLED;
            }
            if (event.isSelect())  {
                controller.applyFilter();
                requestListRefresh();
                return EventResult.HANDLED;
            }
            if (isDeleteBackward(event)) {
                controller.filterBackspace();
                requestListRefresh();
                return EventResult.HANDLED;
            }
            Character c = printableChar(event);
            if (c != null) {
                controller.typeFilterChar(c);
                requestListRefresh();
                return EventResult.HANDLED;
            }
            return EventResult.UNHANDLED;
        }

        // Normal list navigation
        if (event.isChar('1')) { controller.setListScope(AppController.ListScope.ALL);        requestListRefresh(); return EventResult.HANDLED; }
        if (event.isChar('2')) { controller.setListScope(AppController.ListScope.ACTIVE);     requestListRefresh(); return EventResult.HANDLED; }
        if (event.isChar('3')) { controller.setListScope(AppController.ListScope.COMPLETED);  requestListRefresh(); return EventResult.HANDLED; }
        if (event.isChar('4')) { controller.setListScope(AppController.ListScope.ERRORS);     requestListRefresh(); return EventResult.HANDLED; }
        if (event.isChar('5')) { controller.setListScope(AppController.ListScope.STALLED);    requestListRefresh(); return EventResult.HANDLED; }
        if (event.isChar('s')) { controller.cycleListSort();                                   requestListRefresh(); return EventResult.HANDLED; }
        if (event.isChar('S')) { controller.toggleSortDirection();                            requestListRefresh(); return EventResult.HANDLED; }
        if (event.isChar(',')) { controller.previousPage();                                   requestListRefresh(); return EventResult.HANDLED; }
        if (event.isChar('.')) { controller.nextPage();                                       requestListRefresh(); return EventResult.HANDLED; }
        if (event.isDown()  || event.isChar('j')) { controller.moveDown();           return EventResult.HANDLED; }
        if (event.isUp()    || event.isChar('k')) { controller.moveUp();             return EventResult.HANDLED; }
        if (event.isPageDown())                    { pageDown();                      return EventResult.HANDLED; }
        if (event.isPageUp())                      { pageUp();                        return EventResult.HANDLED; }
        if (event.isChar('g') || event.code() == KeyCode.HOME) { controller.moveTop();    return EventResult.HANDLED; }
        if (event.isChar('G') || event.code() == KeyCode.END)  { controller.moveBottom(); return EventResult.HANDLED; }

        // Open overlays
        if (event.isSelect())   {
            controller.openDetail();
            requestDetailRefresh();
            return EventResult.HANDLED;
        }
        if (event.isChar('o'))  {
            controller.openOps();
            requestOpsRefresh();
            return EventResult.HANDLED;
        }
        if (event.isChar('a'))  {
            addDialog.clearInput();
            controller.openAddDialog();
            return EventResult.HANDLED;
        }
        if (event.isChar('d'))  { controller.openConfirmDelete();    return EventResult.HANDLED; }
        if (event.isChar('?'))  { controller.openHelp();             return EventResult.HANDLED; }
        if (event.isChar('/'))  { controller.openFilter();           return EventResult.HANDLED; }

        // Torrent control — fire and forget (actions run on render thread, API on background)
        if (event.isChar('p'))  { asyncPause();   return EventResult.HANDLED; }
        if (event.isChar('r'))  { asyncResume();  return EventResult.HANDLED; }
        if (event.isChar('c'))  { asyncRecheck(); return EventResult.HANDLED; }

        return EventResult.UNHANDLED;
    }

    // ── Detail view keys ──────────────────────────────────────────────────────

    private EventResult handleDetailKey(dev.tamboui.tui.event.KeyEvent event) {
        if (event.isCancel())   { controller.closeDetail();          return EventResult.HANDLED; }
        if (isFocusNext(event) || event.code() == KeyCode.RIGHT) {
            controller.nextDetailTab();
            return EventResult.HANDLED;
        }
        if (event.isFocusPrevious() || event.code() == KeyCode.LEFT) {
            controller.previousDetailTab();
            return EventResult.HANDLED;
        }

        if (controller.detailTab() == AppController.DetailTab.FILES) {
            if (event.isDown() || event.isChar('j')) { controller.moveFileDown(); return EventResult.HANDLED; }
            if (event.isUp() || event.isChar('k'))   { controller.moveFileUp();   return EventResult.HANDLED; }
            if (event.isChar('s')) { asyncSkipSelectedFile();        return EventResult.HANDLED; }
            if (event.isChar('u')) { asyncDownloadSelectedFile();    return EventResult.HANDLED; }
            if (event.isChar('h')) { asyncPrioritizeSelectedFile();  return EventResult.HANDLED; }
            if (event.isChar('l')) { asyncDeprioritizeSelectedFile(); return EventResult.HANDLED; }
            if (event.isChar('n')) { asyncSetSelectedFilePriority(4, "Priority set to normal"); return EventResult.HANDLED; }
            if (event.isChar('R')) { asyncResetAllFilePriorities();  return EventResult.HANDLED; }
        }

        if (event.isChar('p'))  { asyncPause();   return EventResult.HANDLED; }
        if (event.isChar('r'))  { asyncResume();  return EventResult.HANDLED; }
        if (event.isChar('d'))  { controller.openConfirmDelete();    return EventResult.HANDLED; }
        if (event.isChar('c'))  { asyncRecheck(); return EventResult.HANDLED; }
        if (event.isChar('t'))  { asyncReannounce(); return EventResult.HANDLED; }
        return EventResult.UNHANDLED;
    }

    // ── Operations overlay keys ──────────────────────────────────────────────

    private EventResult handleOpsKey(dev.tamboui.tui.event.KeyEvent event) {
        if (event.isCancel()) {
            controller.closeOps();
            return EventResult.HANDLED;
        }
        if (isFocusNext(event) || event.code() == KeyCode.RIGHT) {
            controller.nextOpsTab();
            return EventResult.HANDLED;
        }
        if (event.isFocusPrevious() || event.code() == KeyCode.LEFT) {
            controller.previousOpsTab();
            return EventResult.HANDLED;
        }
        if (event.isChar('r')) {
            requestOpsRefresh();
            return EventResult.HANDLED;
        }
        if (controller.opsTab() == AppController.OpsTab.ORPHANS) {
            if (event.isDown() || event.isChar('j')) { controller.moveOrphanDown(); return EventResult.HANDLED; }
            if (event.isUp() || event.isChar('k'))   { controller.moveOrphanUp();   return EventResult.HANDLED; }
            if (event.isChar('c'))                   { asyncCleanupOrphans();        return EventResult.HANDLED; }
        }
        return EventResult.UNHANDLED;
    }

    // ── Notification overlay keys ───────────────────────────────────────────

    private EventResult handleNotificationsKey(dev.tamboui.tui.event.KeyEvent event) {
        if (event.isCancel() || event.isChar('N')) {
            controller.closeNotifications();
            return EventResult.HANDLED;
        }
        if (event.isSelect()) {
            activateSelectedNotification();
            return EventResult.HANDLED;
        }
        if (event.isDown() || event.isChar('j')) { controller.moveNotificationDown(); return EventResult.HANDLED; }
        if (event.isUp() || event.isChar('k'))   { controller.moveNotificationUp();   return EventResult.HANDLED; }
        if (event.isChar('g') || event.code() == KeyCode.HOME) { controller.moveNotificationTop(); return EventResult.HANDLED; }
        if (event.isChar('G') || event.code() == KeyCode.END)  { controller.moveNotificationBottom(); return EventResult.HANDLED; }
        if (event.isChar('c')) { controller.clearNotifications(); return EventResult.HANDLED; }
        return EventResult.UNHANDLED;
    }

    // ── Add dialog keys ───────────────────────────────────────────────────────

    private EventResult handleAddDialogKey(dev.tamboui.tui.event.KeyEvent event) {
        if (event.isCancel()) {
            addDialog.clearInput();
            controller.cancelAddDialog();
            return EventResult.HANDLED;
        }
        if (event.isSelect()) {
            submitAddDialog();
            return EventResult.HANDLED;
        }
        if (isPasteShortcut(event)) {
            pasteClipboardIntoAddInput();
            return EventResult.HANDLED;
        }
        if (isFocusNext(event)) {
            addDialog.focusNextField();
            return EventResult.HANDLED;
        }
        if (event.isFocusPrevious()) {
            addDialog.focusPreviousField();
            return EventResult.HANDLED;
        }
        if (addDialog.isModeFieldFocused()
                && (event.code() == KeyCode.LEFT || event.code() == KeyCode.RIGHT || event.isChar(' '))) {
            addDialog.toggleMode();
            return EventResult.HANDLED;
        }
        if (addDialog.isAutoStartFieldFocused()
                && (event.code() == KeyCode.LEFT || event.code() == KeyCode.RIGHT || event.isChar(' '))) {
            addDialog.toggleAutoStart();
            return EventResult.HANDLED;
        }
        var activeInputState = addDialog.activeInputState();
        if (activeInputState != null && isDeleteBackward(event)) {
            activeInputState.deleteBackward();
            return EventResult.HANDLED;
        }
        if (activeInputState != null && handleTextInputKey(activeInputState, event)) {
            return EventResult.HANDLED;
        }
        return EventResult.UNHANDLED;
    }

    // ── Confirm delete keys ─────────────────────────────────────────────────

    private EventResult handleConfirmDeleteKey(dev.tamboui.tui.event.KeyEvent event) {
        if (event.isCancel() || event.isChar('n')) {
            controller.cancelConfirmDelete();
            return EventResult.HANDLED;
        }
        if (event.isChar('y') || event.isSelect()) {
            asyncDelete(false);
            controller.cancelConfirmDelete();
            return EventResult.HANDLED;
        }
        if (event.isChar('Y')) {          // capital Y = delete files too
            asyncDelete(true);
            controller.cancelConfirmDelete();
            return EventResult.HANDLED;
        }
        return EventResult.UNHANDLED;
    }

    // ── Help overlay keys ─────────────────────────────────────────────────────

    private EventResult handleHelpKey(dev.tamboui.tui.event.KeyEvent event) {
        if (event.isCancel() || event.isChar('?')) {
            controller.closeHelp();
            return EventResult.HANDLED;
        }
        return EventResult.UNHANDLED;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Confirm-delete overlay (inline — no separate view class needed)
    // ─────────────────────────────────────────────────────────────────────────

    private Element confirmDeleteOverlay() {
        var t = controller.selectedTorrent();
        String name = t != null && t.name != null ? t.name : "selected torrent";

        return panel(
                "Delete Torrent",
                spacer(),
                text("  Delete " + name + "?").bold(),
                spacer(),
                text("  Downloaded files will NOT be deleted.").dim(),
                text("  Press [Y] (capital) to also delete files from disk.").dim(),
                spacer(),
                row(
                        text("  [y] Delete torrent record  ").cyan(),
                        text("[Y] Delete + files  ").red(),
                        text("[n / Esc] Cancel").dim()
                ),
                spacer()
        )
                .rounded()
                .id("confirm-delete")
                .focusable();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Async API actions
    // All API calls go on a background thread so the render thread never blocks.
    // Result (success/error) is written back to controller via runOnRenderThread.
    // ─────────────────────────────────────────────────────────────────────────

    private void submitAddDialog() {
        String input = addDialog.inputText().trim();
        if (input.isBlank()) return;

        AppController.AddMode mode = controller.addMode();
        String savePath = addDialog.savePathText().trim();
        boolean startImmediately = addDialog.startImmediately();

        Path torrentFile = null;
        if (mode == AppController.AddMode.FILE) {
            try {
                torrentFile = Path.of(input);
            } catch (InvalidPathException e) {
                addDialog.clearInput();
                controller.cancelAddDialog();
                controller.setError("Add failed: Invalid file path");
                return;
            }
        }

        addDialog.clearInput();
        controller.cancelAddDialog();

        Path finalTorrentFile = torrentFile;
        Thread.ofVirtual().start(() -> {
            try {
                TorrentApiClient.AddTorrentResponse response;
                if (mode == AppController.AddMode.MAGNET) {
                    response = client.addMagnet(input, savePath, startImmediately);
                } else {
                    response = client.uploadTorrentFile(finalTorrentFile, savePath, startImmediately);
                }

                TorrentApiClient.AddTorrentResponse finalResponse = response;
                runOnRender(() -> {
                    controller.setStatus(
                            formatAddSuccess(finalResponse),
                            "Add",
                            AppController.NotificationAction.openTorrentDetail(
                                    finalResponse.torrentId,
                                    "Open added torrent"));
                    requestListRefresh();
                });
            } catch (TorrentApiClient.ApiException e) {
                runOnRender(() -> controller.setError("Add failed: " + e.getMessage()));
            }
        });
    }

    private void asyncPause() {
        asyncTorrentAction("Paused", "Pause failed", client::pauseTorrent);
    }

    private void asyncResume() {
        asyncTorrentAction("Resumed", "Resume failed", client::startTorrent);
    }

    private void asyncRecheck() {
        asyncTorrentAction("Recheck started", "Recheck failed", client::recheckTorrent);
    }

    private void asyncReannounce() {
        asyncTorrentAction("Reannounce requested", "Reannounce failed", client::reannounceTorrent);
    }

    private void asyncDelete(boolean deleteFiles) {
        var t = controller.selectedTorrent();
        if (t == null || t.id == null) return;
        long id = t.id;
        Thread.ofVirtual().start(() -> {
            try {
                client.removeTorrent(id, deleteFiles);
                runOnRender(() -> controller.setStatus(
                        deleteFiles ? "Deleted (with files)" : "Deleted"));
            } catch (TorrentApiClient.ApiException e) {
                runOnRender(() -> controller.setError("Delete failed: " + e.getMessage()));
            }
        });
    }

    private void asyncSkipSelectedFile() {
        asyncSelectedFileAction(
                "File skipped",
                "Skip failed",
                (torrentId, fileIds) -> client.skipFiles(torrentId, fileIds)
        );
    }

    private void asyncDownloadSelectedFile() {
        asyncSelectedFileAction(
                "File resumed",
                "Resume file failed",
                (torrentId, fileIds) -> client.downloadFiles(torrentId, fileIds)
        );
    }

    private void asyncPrioritizeSelectedFile() {
        asyncSelectedFileAction(
                "File set to high priority",
                "Prioritize failed",
                (torrentId, fileIds) -> client.prioritizeFiles(torrentId, fileIds)
        );
    }

    private void asyncDeprioritizeSelectedFile() {
        asyncSelectedFileAction(
                "File set to low priority",
                "Deprioritize failed",
                (torrentId, fileIds) -> client.deprioritizeFiles(torrentId, fileIds)
        );
    }

    private void asyncSetSelectedFilePriority(int priority, String successMessage) {
        asyncSelectedFileAction(
                successMessage,
                "Priority update failed",
                (torrentId, fileIds) -> client.updateFilePriorities(torrentId, fileIds, priority)
        );
    }

    private void asyncResetAllFilePriorities() {
        Long torrentId = controller.detailTorrentId();
        if (torrentId == null) return;

        Thread.ofVirtual().start(() -> {
            try {
                client.resetFilePriorities(torrentId);
                runOnRender(() -> controller.setStatus("All file priorities reset"));
                requestDetailRefresh(torrentId);
            } catch (TorrentApiClient.ApiException e) {
                runOnRender(() -> controller.setError("Reset priorities failed: " + e.getMessage()));
            }
        });
    }

    private void asyncCleanupOrphans() {
        Thread.ofVirtual().start(() -> {
            try {
                TorrentApiClient.CleanupOrphansResponse response = client.cleanupOrphanedFiles();
                runOnRender(() -> {
                    controller.setStatus(
                            formatCleanupMessage(response),
                            "Ops",
                            AppController.NotificationAction.openOpsOrphans());
                    requestOpsRefresh();
                });
            } catch (TorrentApiClient.ApiException e) {
                runOnRender(() -> controller.setError(
                        "Cleanup failed: " + e.getMessage(),
                        "Ops",
                        AppController.NotificationAction.openOpsOrphans()));
            }
        });
    }

    private void asyncTorrentAction(
            String successMessage,
            String failurePrefix,
            TorrentAction action) {
        Long torrentId = currentTorrentIdForAction();
        if (torrentId == null) return;
        boolean refreshDetail = torrentId.equals(controller.detailTorrentId());

        Thread.ofVirtual().start(() -> {
            try {
                action.run(torrentId);
                runOnRender(() -> controller.setStatus(successMessage));
                if (refreshDetail) {
                    requestDetailRefresh(torrentId);
                }
            } catch (TorrentApiClient.ApiException e) {
                runOnRender(() -> controller.setError(failurePrefix + ": " + e.getMessage()));
            }
        });
    }

    private void asyncSelectedFileAction(
            String successMessage,
            String failurePrefix,
            FileSelectionAction action) {
        Long torrentId = controller.detailTorrentId();
        var file = controller.selectedFile();
        if (torrentId == null || file == null || file.id == null) return;

        List<Long> fileIds = List.of(file.id);
        Thread.ofVirtual().start(() -> {
            try {
                action.run(torrentId, fileIds);
                runOnRender(() -> controller.setStatus(successMessage));
                requestDetailRefresh(torrentId);
            } catch (TorrentApiClient.ApiException e) {
                runOnRender(() -> controller.setError(failurePrefix + ": " + e.getMessage()));
            }
        });
    }

    private void requestDetailRefresh() {
        Long torrentId = controller.detailTorrentId();
        if (torrentId != null) {
            requestDetailRefresh(torrentId);
        }
    }

    private void requestListRefresh() {
        controller.beginListRefresh();
        poller.refreshNow();
    }

    private void requestDetailRefresh(long torrentId) {
        controller.beginDetailRefresh();
        Thread.ofVirtual().start(() -> loadDetailSnapshot(torrentId));
    }

    private void requestOpsRefresh() {
        controller.beginOpsRefresh();
        Thread.ofVirtual().start(this::loadOpsSnapshot);
    }

    private void loadDetailSnapshot(long torrentId) {
        try {
            TorrentApiClient.TorrentResponse detail = client.getTorrent(torrentId);
            if (detail == null) {
                runOnRender(() -> controller.setDetailError("Torrent no longer exists on the server."));
                return;
            }

            TorrentApiClient.TorrentDetailStatsResponse detailStats = loadOptionalDetailStats(torrentId);
            TorrentApiClient.TorrentEtaResponse eta = loadOptionalDetailEta(torrentId);
            Double ratio = loadOptionalDetailRatio(torrentId);

            runOnRender(() -> controller.setDetailData(detail, detailStats, eta, ratio));
        } catch (TorrentApiClient.ApiException e) {
            String message = e.isConnectionRefused()
                    ? "Cannot refresh detail while the server is offline."
                    : "Failed to refresh detail: " + e.getMessage();
            runOnRender(() -> controller.setDetailError(message));
        }
    }

    private void loadOpsSnapshot() {
        try {
            TorrentApiClient.SystemHealthResponse health = client.getSystemHealth();
            TorrentApiClient.SystemInfoResponse info = client.getSystemInfo();
            TorrentApiClient.StorageInfoResponse storage = client.getStorageInfo();
            List<String> orphanedFiles = client.getOrphanedFiles();

            runOnRender(() -> controller.setOpsData(health, info, storage, orphanedFiles));
        } catch (TorrentApiClient.ApiException e) {
            String message = e.isConnectionRefused()
                    ? "Cannot refresh operations while the server is offline."
                    : "Failed to refresh operations: " + e.getMessage();
            runOnRender(() -> controller.setOpsError(message));
        }
    }

    private TorrentApiClient.TorrentDetailStatsResponse loadOptionalDetailStats(long torrentId)
            throws TorrentApiClient.ApiException {
        try {
            return client.getTorrentStats(torrentId);
        } catch (TorrentApiClient.ApiException e) {
            if (e.getStatusCode() == 404) {
                return null;
            }
            throw e;
        }
    }

    private TorrentApiClient.TorrentEtaResponse loadOptionalDetailEta(long torrentId)
            throws TorrentApiClient.ApiException {
        try {
            return client.getTorrentEta(torrentId);
        } catch (TorrentApiClient.ApiException e) {
            if (e.getStatusCode() == 404) {
                return null;
            }
            throw e;
        }
    }

    private Double loadOptionalDetailRatio(long torrentId) throws TorrentApiClient.ApiException {
        try {
            return client.getTorrentRatio(torrentId);
        } catch (TorrentApiClient.ApiException e) {
            if (e.getStatusCode() == 404) {
                return null;
            }
            throw e;
        }
    }

    private Long currentTorrentIdForAction() {
        if (controller.activeView() == AppController.View.DETAIL) {
            return controller.detailTorrentId();
        }

        var t = controller.selectedTorrent();
        return t != null ? t.id : null;
    }

    private void activateSelectedNotification() {
        AppController.NotificationAction action = controller.selectedNotificationAction();
        if (action == null) {
            return;
        }

        switch (action.type()) {
            case OPEN_TORRENT_DETAIL -> {
                if (action.torrentId() == null) {
                    return;
                }
                controller.openDetailByTorrentId(action.torrentId());
                requestDetailRefresh(action.torrentId());
            }
            case OPEN_OPS_ORPHANS -> {
                controller.openOpsOrphans();
                requestOpsRefresh();
            }
            case RETRY_LIVE_UPDATES -> {
                controller.setStatus("Retrying live connection...", "Live");
                liveUpdates.reconnectNow();
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Post a runnable to the TamboUI render thread from a background thread. */
    private void runOnRender(Runnable action) {
        if (runner() != null) {
            runner().runOnRenderThread(action);
        }
    }

    private static boolean isPrintable(char c) {
        return c >= 32 && c < 127;
    }

    /**
     * Returns a printable character from the key event, or null if the event should
     * not be treated as text input (e.g. Ctrl/Alt key chords).
     */
    private static Character printableChar(dev.tamboui.tui.event.KeyEvent event) {
        if (event.code() != KeyCode.CHAR) return null;
        if (event.hasCtrl() || event.hasAlt()) return null;
        char c = event.character();
        return isPrintable(c) ? c : null;
    }

    /**
     * Cross-terminal backward-delete detection.
     * Some terminals send Backspace as Ctrl+H or raw control chars.
     */
    private static boolean isDeleteBackward(dev.tamboui.tui.event.KeyEvent event) {
        if (event.isDeleteBackward() || event.code() == KeyCode.BACKSPACE) return true;
        if (event.code() != KeyCode.CHAR) return false;

        char c = event.character();
        return c == '\b' || c == 127 || (event.hasCtrl() && (c == 'h' || c == 'H'));
    }

    /**
     * Cross-terminal focus-next / Tab detection.
     * Some terminals report Tab as Ctrl+I or raw tab char.
     */
    private static boolean isFocusNext(dev.tamboui.tui.event.KeyEvent event) {
        if (event.isFocusNext() || event.code() == KeyCode.TAB) return true;
        if (event.code() != KeyCode.CHAR) return false;

        char c = event.character();
        return c == '\t' || (event.hasCtrl() && (c == 'i' || c == 'I'));
    }

    /**
     * Detects common paste shortcuts inside terminals.
     */
    private static boolean isPasteShortcut(dev.tamboui.tui.event.KeyEvent event) {
        if (event.code() == KeyCode.INSERT && event.hasShift()) return true; // Shift+Insert
        return event.code() == KeyCode.CHAR
                && event.hasCtrl()
                && (event.character() == 'v' || event.character() == 'V');   // Ctrl+V
    }

    /**
     * Best-effort clipboard paste for add-dialog input.
     * If clipboard access is unavailable, this is a no-op.
     */
    private void pasteClipboardIntoAddInput() {
        try {
            var clipboard = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard();
            Object data = clipboard.getData(DataFlavor.stringFlavor);
            if (!(data instanceof String text) || text.isEmpty()) {
                return;
            }

            // Keep it single-line so pasted text behaves predictably in the form.
            String singleLine = text.replace("\r", "").replace("\n", "");
            addDialog.insertText(singleLine);
        } catch (HeadlessException | UnsupportedFlavorException | IOException | IllegalStateException ignored) {
            // Clipboard may be unavailable in some terminal/remote contexts.
        }
    }

    private void pageDown() {
        for (int i = 0; i < 10; i++) controller.moveDown();
    }

    private void pageUp() {
        for (int i = 0; i < 10; i++) controller.moveUp();
    }

    private String formatAddSuccess(TorrentApiClient.AddTorrentResponse response) {
        if (response == null) {
            return "Torrent added";
        }

        String name = response.name != null && !response.name.isBlank()
                ? response.name
                : "torrent";

        List<String> details = new ArrayList<>();
        if (response.fileCount != null) {
            details.add(response.fileCount + " files");
        }
        if (response.totalSize != null && response.totalSize > 0) {
            details.add(response.formattedSize());
        }
        details.add(Boolean.FALSE.equals(response.started) ? "paused" : "started");

        if (details.isEmpty()) {
            return "Added " + name;
        }
        return "Added " + name + " (" + String.join(", ", details) + ")";
    }

    private String formatCleanupMessage(TorrentApiClient.CleanupOrphansResponse response) {
        if (response == null) {
            return "Orphan cleanup complete";
        }
        if (response.message != null && !response.message.isBlank()) {
            return response.message;
        }
        if (response.deletedCount != null) {
            return "Deleted " + response.deletedCount + " orphaned file(s)";
        }
        return "Orphan cleanup complete";
    }

    @FunctionalInterface
    private interface TorrentAction {
        void run(long torrentId) throws TorrentApiClient.ApiException;
    }

    @FunctionalInterface
    private interface FileSelectionAction {
        void run(long torrentId, List<Long> fileIds) throws TorrentApiClient.ApiException;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Entry point
    // ─────────────────────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        String serverUrl = "http://localhost:8080";
        for (int i = 0; i < args.length - 1; i++) {
            if ("--server".equals(args[i])) {
                serverUrl = args[i + 1];
            }
        }
        new JTorrentApp(serverUrl).run();
    }


}
