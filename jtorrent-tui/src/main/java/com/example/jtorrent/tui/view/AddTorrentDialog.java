package com.example.jtorrent.tui.view;

import com.example.jtorrent.tui.controller.AppController;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.widgets.input.TextInputState;

import static dev.tamboui.toolkit.Toolkit.*;

/**
 * AddTorrentDialog — overlay view for adding a new torrent.
 *
 * <p>Rendered by {@code JTorrentApp} on top of the main list when
 * {@code controller.activeView() == ACTIVE_VIEW.ADD_DIALOG}.
 *
 * <p>The dialog has two modes toggled with {@code Tab}:
 * <ul>
 *   <li><b>MAGNET</b> — user types/pastes a magnet:// URI</li>
 *   <li><b>FILE</b>   — user types a local .torrent file path</li>
 * </ul>
 *
 * <p>Text entry is managed via {@link TextInputState} which is kept as a
 * field so cursor position survives re-renders.
 *
 * <h2>TamboUI View contract</h2>
 * Pure render function — reads controller state, no side effects.
 */
public class AddTorrentDialog {

    private final AppController controller;

    /**
     * TextInputState owned by this view so TamboUI can render the cursor.
     * We sync its text from the controller each frame.
     */
    private final TextInputState inputState = new TextInputState();

    public AddTorrentDialog(AppController controller) {
        this.controller = controller;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // render()
    // ─────────────────────────────────────────────────────────────────────────

    public Element render() {
        AppController.AddMode mode        = controller.addMode();
        boolean isMagnet    = (mode == AppController.AddMode.MAGNET);
        String  modeLabel   = isMagnet ? "Magnet Link" : "File Path";
        String  placeholder = isMagnet
                ? "magnet:?xt=urn:btih:…"
                : "/path/to/file.torrent";

        return panel(
                "Add Torrent",
                // ── Mode toggle ────────────────────────────────────────────
                row(
                        isMagnet
                                ? text(" [●] Magnet  ").cyan().bold()
                                : text(" [○] Magnet  ").dim(),
                        isMagnet
                                ? text("[○] File ").dim()
                                : text("[●] File ").cyan().bold(),
                        text("  [Tab] switch").dim()
                ),
                spacer(),
                // ── Label + input ──────────────────────────────────────────
                row(
                        text(" " + modeLabel + ": ").dim()
                ),
                textInput(inputState)
                        .placeholder(placeholder)
                        .id("add-input")
                        .rounded(),
                spacer(),
                // ── Footer hints ───────────────────────────────────────────
                row(
                        text(" [Enter] Add  ").cyan(),
                        text("[Esc] Cancel  ").dim(),
                        text("[Tab] Switch mode").dim()
                )
        )
                .rounded()
                .id("add-dialog")
                .focusable();
    }

    /**
     * Returns current add-dialog input text.
     */
    public String inputText() {
        return inputState.text();
    }

    /**
     * Clears input and moves cursor to the end.
     */
    public void clearInput() {
        inputState.clear();
        inputState.moveCursorToEnd();
    }

    /**
     * Inserts text at the current cursor position.
     */
    public void insertText(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        inputState.insert(text);
    }

    /**
     * Returns the {@link TextInputState} so the key handler can route
     * backspace / character events directly to it via
     * {@code Toolkit.handleTextInputKey()}.
     */
    public TextInputState inputState() {
        return inputState;
    }
}
