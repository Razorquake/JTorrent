package com.example.jtorrent.tui.view;

import com.example.jtorrent.tui.controller.AppController;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.widgets.input.TextInputState;

import static dev.tamboui.toolkit.Toolkit.*;

/**
 * AddTorrentDialog renders the richer add-torrent form.
 *
 * <p>The controller owns only the add mode (magnet vs file). The view owns the
 * form widget state so cursor position and partially typed values survive
 * re-renders while the dialog stays open.
 */
public class AddTorrentDialog {

    private enum FocusField {
        MODE,
        SOURCE,
        SAVE_PATH,
        AUTO_START
    }

    private final AppController controller;

    private final TextInputState magnetInputState = new TextInputState();
    private final TextInputState fileInputState = new TextInputState();
    private final TextInputState savePathInputState = new TextInputState();

    private FocusField focusField = FocusField.SOURCE;
    private boolean startImmediately = true;

    public AddTorrentDialog(AppController controller) {
        this.controller = controller;
    }

    public Element render() {
        boolean isMagnet = controller.addMode() == AppController.AddMode.MAGNET;
        String sourceLabel = isMagnet ? "Magnet Link" : "Torrent File";
        String sourcePlaceholder = isMagnet
                ? "magnet:?xt=urn:btih:..."
                : "C:/path/to/file.torrent";

        return panel(
                "Add Torrent",
                renderModeSelector(isMagnet),
                spacer(),
                renderFieldLabel(sourceLabel, focusField == FocusField.SOURCE),
                renderInputField(
                        currentSourceState(),
                        sourcePlaceholder,
                        "add-source",
                        focusField == FocusField.SOURCE
                ),
                spacer(),
                renderFieldLabel("Save Path (Optional)", focusField == FocusField.SAVE_PATH),
                renderInputField(
                        savePathInputState,
                        "Leave blank to use the server default download directory",
                        "add-save-path",
                        focusField == FocusField.SAVE_PATH
                ),
                spacer(),
                renderAutoStartToggle(),
                spacer(),
                text("  Leave Save Path empty to keep the server's default download directory.")
                        .dim(),
                text(startImmediately
                        ? "  Torrent will begin downloading immediately after add."
                        : "  Torrent will be added in a paused state.")
                        .dim(),
                spacer(),
                row(
                        text(" [Enter] Add  ").cyan(),
                        text("[Tab] Next field  ").dim(),
                        text("[Shift+Tab] Previous  ").dim(),
                        text("[Space] Toggle selected option  ").dim(),
                        text("[Esc] Cancel").dim()
                )
        )
                .rounded()
                .id("add-dialog")
                .focusable();
    }

    public String inputText() {
        return currentSourceState().text();
    }

    public String savePathText() {
        return savePathInputState.text();
    }

    public boolean startImmediately() {
        return startImmediately;
    }

    public void clearInput() {
        clearState(magnetInputState);
        clearState(fileInputState);
        clearState(savePathInputState);
        startImmediately = true;
        focusField = FocusField.SOURCE;
    }

    public void insertText(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }

        TextInputState target = activeInputState();
        if (target == null) {
            focusField = FocusField.SOURCE;
            target = currentSourceState();
        }
        target.insert(text);
    }

    public TextInputState activeInputState() {
        return switch (focusField) {
            case SOURCE -> currentSourceState();
            case SAVE_PATH -> savePathInputState;
            default -> null;
        };
    }

    public boolean isModeFieldFocused() {
        return focusField == FocusField.MODE;
    }

    public boolean isAutoStartFieldFocused() {
        return focusField == FocusField.AUTO_START;
    }

    public void focusNextField() {
        focusField = switch (focusField) {
            case MODE -> FocusField.SOURCE;
            case SOURCE -> FocusField.SAVE_PATH;
            case SAVE_PATH -> FocusField.AUTO_START;
            case AUTO_START -> FocusField.MODE;
        };
    }

    public void focusPreviousField() {
        focusField = switch (focusField) {
            case MODE -> FocusField.AUTO_START;
            case SOURCE -> FocusField.MODE;
            case SAVE_PATH -> FocusField.SOURCE;
            case AUTO_START -> FocusField.SAVE_PATH;
        };
    }

    public void toggleMode() {
        controller.toggleAddMode();
    }

    public void toggleAutoStart() {
        startImmediately = !startImmediately;
    }

    private Element renderModeSelector(boolean isMagnet) {
        boolean focused = focusField == FocusField.MODE;
        return row(
                focusPrefix(focused),
                focused
                        ? text("Mode").cyan().bold()
                        : text("Mode").dim(),
                text(": ").dim(),
                modeChip("Magnet", isMagnet, focused),
                text("  "),
                modeChip("File", !isMagnet, focused),
                text("  [←/→ or Space] switch").dim()
        );
    }

    private Element renderFieldLabel(String label, boolean focused) {
        return row(
                focusPrefix(focused),
                focused ? text(label).cyan().bold() : text(label).dim()
        );
    }

    private Element renderInputField(
            TextInputState state,
            String placeholder,
            String id,
            boolean focused) {

        if (focused) {
            return textInput(state)
                    .placeholder(placeholder)
                    .id(id)
                    .rounded();
        }

        String value = state.text();
        Element content = value == null || value.isBlank()
                ? text(" " + placeholder + " ").dim()
                : text(" " + value + " ");

        return panel("", content)
                .rounded()
                .id(id + "-preview");
    }

    private Element renderAutoStartToggle() {
        boolean focused = focusField == FocusField.AUTO_START;
        String marker = startImmediately ? "[x]" : "[ ]";
        String stateText = startImmediately ? "Start Immediately" : "Add Paused";

        return row(
                focusPrefix(focused),
                focused ? text(marker).cyan().bold() : text(marker).cyan(),
                text(" "),
                focused ? text(stateText).cyan().bold() : text(stateText),
                text("  ").dim(),
                text(startImmediately
                        ? "torrent starts downloading after add"
                        : "torrent is added without auto-start")
                        .dim()
        );
    }

    private Element modeChip(String label, boolean selected, boolean modeFocused) {
        String display = selected ? "[" + label + "]" : label;
        if (selected && modeFocused) {
            return text(display).cyan().bold();
        }
        if (selected) {
            return text(display).bold();
        }
        return text(display).dim();
    }

    private Element focusPrefix(boolean focused) {
        return focused
                ? text(" > ").cyan().bold()
                : text("   ").dim();
    }

    private TextInputState currentSourceState() {
        return controller.addMode() == AppController.AddMode.MAGNET
                ? magnetInputState
                : fileInputState;
    }

    private static void clearState(TextInputState state) {
        state.clear();
        state.moveCursorToEnd();
    }
}
