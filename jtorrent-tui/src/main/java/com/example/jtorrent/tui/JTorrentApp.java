package com.example.jtorrent.tui;


import com.example.jtorrent.tui.api.TorrentApiClient;
import com.example.jtorrent.tui.controller.AppController;
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
import java.nio.file.Path;
import java.time.Duration;

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
 *  │                  .top(GlobalStatsBar)       │
 *  │                  .center(active view)       │
 *  │                  .bottom(status)            │
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

    // ─────────────────────────────────────────────────────────────────────────
    // View components (constructed once, re-used every frame)
    // ─────────────────────────────────────────────────────────────────────────

    private final GlobalStatsBar statsBar;
    private final TorrentListView listView;
    private final TorrentDetailPanel detailPanel;
    private final AddTorrentDialog addDialog;
    private final HelpOverlay helpOverlay;


    // ─────────────────────────────────────────────────────────────────────────
    // Constructor
    // ─────────────────────────────────────────────────────────────────────────

    public JTorrentApp(String serverUrl) {
        this.client      = new TorrentApiClient(serverUrl);
        this.poller      = new PollingService(client, controller);
        this.statsBar    = new GlobalStatsBar(controller);
        this.listView    = new TorrentListView(controller);
        this.detailPanel = new TorrentDetailPanel(controller);
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
    }

    @Override
    protected void onStop() {
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

        return switch (view) {
            case LIST           -> handleListKey(event);
            case DETAIL         -> handleDetailKey(event);
            case ADD_DIALOG     -> handleAddDialogKey(event);
            case CONFIRM_DELETE -> handleConfirmDeleteKey(event);
            case HELP           -> handleHelpKey(event);
        };
    }

    // ── List view keys ────────────────────────────────────────────────────────

    private EventResult handleListKey(dev.tamboui.tui.event.KeyEvent event) {
        // Filter mode — capture all printable chars for the search bar
        if (controller.isFilterMode()) {
            if (event.isCancel())  { controller.closeFilter();       return EventResult.HANDLED; }
            if (event.isSelect())  { controller.closeFilter();       return EventResult.HANDLED; }
            if (isDeleteBackward(event)) {
                controller.filterBackspace();
                return EventResult.HANDLED;
            }
            Character c = printableChar(event);
            if (c != null) {
                controller.typeFilterChar(c);
                return EventResult.HANDLED;
            }
            return EventResult.UNHANDLED;
        }

        // Normal list navigation
        if (event.isDown()  || event.isChar('j')) { controller.moveDown();           return EventResult.HANDLED; }
        if (event.isUp()    || event.isChar('k')) { controller.moveUp();             return EventResult.HANDLED; }
        if (event.isPageDown())                    { pageDown();                      return EventResult.HANDLED; }
        if (event.isPageUp())                      { pageUp();                        return EventResult.HANDLED; }
        if (event.isChar('g') || event.code() == KeyCode.HOME) { controller.moveTop();    return EventResult.HANDLED; }
        if (event.isChar('G') || event.code() == KeyCode.END)  { controller.moveBottom(); return EventResult.HANDLED; }

        // Open overlays
        if (event.isSelect())   { controller.openDetail();           return EventResult.HANDLED; }
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
        if (event.isChar('p'))  { asyncPause();   controller.closeDetail(); return EventResult.HANDLED; }
        if (event.isChar('r'))  { asyncResume();  controller.closeDetail(); return EventResult.HANDLED; }
        if (event.isChar('d'))  { controller.openConfirmDelete();    return EventResult.HANDLED; }
        if (event.isChar('c'))  { asyncRecheck(); controller.closeDetail(); return EventResult.HANDLED; }
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
        if (isFocusNext(event) || event.isFocusPrevious()) {
            controller.toggleAddMode();
            addDialog.clearInput();
            return EventResult.HANDLED;
        }
        if (isDeleteBackward(event)) {
            addDialog.inputState().deleteBackward();
            return EventResult.HANDLED;
        }
        if (handleTextInputKey(addDialog.inputState(), event)) {
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
        String input = addDialog.inputText();
        if (input.isBlank()) return;

        AppController.AddMode mode = controller.addMode();
        addDialog.clearInput();
        controller.cancelAddDialog();
        Thread.ofVirtual().start(() -> {
            try {
                if (mode == AppController.AddMode.MAGNET) {
                    client.addMagnet(input);
                    runOnRender(() -> controller.setStatus("Torrent added!"));
                } else {
                    client.uploadTorrentFile(Path.of(input));
                    runOnRender(() -> controller.setStatus("Torrent uploaded!"));
                }
            } catch (TorrentApiClient.ApiException e) {
                runOnRender(() -> controller.setError("Add failed: " + e.getMessage()));
            }
        });
    }

    private void asyncPause() {
        var t = controller.selectedTorrent();
        if (t == null || t.id == null) return;
        long id = t.id;
        Thread.ofVirtual().start(() -> {
            try {
                client.pauseTorrent(id);
                runOnRender(() -> controller.setStatus("Paused"));
            } catch (TorrentApiClient.ApiException e) {
                runOnRender(() -> controller.setError("Pause failed: " + e.getMessage()));
            }
        });
    }

    private void asyncResume() {
        var t = controller.selectedTorrent();
        if (t == null || t.id == null) return;
        long id = t.id;
        Thread.ofVirtual().start(() -> {
            try {
                client.startTorrent(id);
                runOnRender(() -> controller.setStatus("Resumed"));
            } catch (TorrentApiClient.ApiException e) {
                runOnRender(() -> controller.setError("Resume failed: " + e.getMessage()));
            }
        });
    }

    private void asyncRecheck() {
        var t = controller.selectedTorrent();
        if (t == null || t.id == null) return;
        long id = t.id;
        Thread.ofVirtual().start(() -> {
            try {
                client.recheckTorrent(id);
                runOnRender(() -> controller.setStatus("Recheck started"));
            } catch (TorrentApiClient.ApiException e) {
                runOnRender(() -> controller.setError("Recheck failed: " + e.getMessage()));
            }
        });
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

            // Keep it single-line for the one-line add input widget.
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
