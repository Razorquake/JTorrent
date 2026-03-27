package com.example.jtorrent.tui.view;

import com.example.jtorrent.tui.api.TorrentApiClient.TorrentResponse;
import com.example.jtorrent.tui.controller.AppController;
import dev.tamboui.layout.Constraint;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.widgets.table.Cell;
import dev.tamboui.widgets.table.Row;
import dev.tamboui.widgets.table.TableState;

import java.util.ArrayList;
import java.util.List;

import static dev.tamboui.toolkit.Toolkit.*;

/**
 * TorrentListView — a pure TamboUI View component.
 *
 * <p>Renders the main torrent table that occupies the center region of the
 * screen. Every row shows:
 * <pre>
 *   #  │ St │ Name                    │ Size    │ Progress bar    │ DL      │ UL      │ Peers
 *   1  │ ↓  │ Ubuntu 24.04 ISO        │ 4.7 GB  │ ████████░░  72% │ 4.2MB/s │ 1.1MB/s │ 42
 *   2  │ ✓  │ Arch Linux              │ 800 MB  │ ████████████100%│ 0 B/s   │ 512KB/s │ 8
 *   3  │ …  │ Debian 12               │ 1.2 GB  │ ░░░░░░░░░░   0% │ 0 B/s   │ 0 B/s   │ 0
 * </pre>
 *
 * <p>The selected row is highlighted via {@link TableState}. Status glyphs are
 * colour-coded (green=downloading, cyan=done, yellow=paused, red=error, etc.).
 *
 * <p>Below the table an optional filter bar appears when the user presses
 * {@code /}, showing the live search string.
 *
 * <h2>TamboUI View contract</h2>
 * <ul>
 *   <li>Pure function of {@link AppController} state — no side effects.</li>
 *   <li>No mutation of controller state.</li>
 *   <li>Called on the render thread every frame.</li>
 * </ul>
 */
public class TorrentListView {

    private final AppController controller;

    // TableState is owned here and updated each frame to reflect the
    // controller's selectedIndex. This is the correct TamboUI pattern —
    // the state object is held by the View, driven by the Controller.
    private final TableState tableState = new TableState();

    public TorrentListView(AppController controller) {
        this.controller = controller;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // render() — entry point called by JTorrentApp every frame
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns the full list panel element for the current frame.
     * The element is focusable so key events reach the root handler.
     */
    public Element render() {
        List<TorrentResponse> visible = controller.visibleTorrents();

        // Sync TableState selection to controller's selectedIndex
        if (!visible.isEmpty()) {
            tableState.select(controller.selectedIndex());
        }

        return panel(
                buildTitle(),
                buildScopeBar(),
                buildTable(visible),
                buildFilterBar(),
                buildFooterHints()
        )
                .rounded()
                .id("torrent-list")
                .focusable();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Panel title
    // ─────────────────────────────────────────────────────────────────────────

    private String buildTitle() {
        return "Torrents";
    }

    private Element buildScopeBar() {
        return row(
                scopeTab(AppController.ListScope.ALL),
                text(" "),
                scopeTab(AppController.ListScope.ACTIVE),
                text(" "),
                scopeTab(AppController.ListScope.COMPLETED),
                text(" "),
                scopeTab(AppController.ListScope.ERRORS),
                text(" "),
                scopeTab(AppController.ListScope.STALLED),
                text("  |  ").dim(),
                text("Sort: ").dim(),
                text(controller.listSort().label()).bold(),
                text(controller.isSortAscending() ? " ↑" : " ↓").dim(),
                text("  |  ").dim(),
                text("Page ").dim(),
                text((controller.page() + 1) + "/" + Math.max(controller.totalPages(), 1)).bold(),
                text("  ").dim(),
                text(controller.totalResults() + " results").dim(),
                activeQueryLabel(),
                spacer(),
                controller.isListLoading()
                        ? text("Refreshing...").dim()
                        : text("")
        );
    }

    private Element scopeTab(AppController.ListScope scope) {
        boolean active = controller.listScope() == scope;
        String label = active ? "[" + scope.label() + "]" : scope.label();
        return active
                ? text(label).cyan().bold()
                : text(label).dim();
    }

    private Element activeQueryLabel() {
        if (!controller.hasActiveQuery()) {
            return text("");
        }
        return row(
                text("  |  ").dim(),
                text("Query: ").dim(),
                text("\"" + controller.filterText() + "\"").cyan()
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Table body
    // ─────────────────────────────────────────────────────────────────────────

    private Element buildTable(List<TorrentResponse> visible) {
        if (visible.isEmpty()) {
            return renderEmptyState();
        }

        // Build all Row objects first, then pass to table()
        List<Row> rows = new ArrayList<>(visible.size());
        for (int i = 0; i < visible.size(); i++) {
            rows.add(buildRow(i, visible.get(i)));
        }

        return table()
                .header("#", "St", "Name", "Size", "Progress", "DL", "UL", "Peers")
                // Column widths: index(3), status(2), name(fill), size(7),
                //                progress(8), dl(8), ul(8), peers(5)
                .widths(
                        Constraint.length(3),
                        Constraint.length(2),
                        Constraint.fill(),
                        Constraint.length(7),
                        Constraint.length(8),
                        Constraint.length(8),
                        Constraint.length(8),
                        Constraint.length(5)
                )
                .rows(rows)
                .state(tableState)
                .highlightColor(Color.CYAN)
                .highlightSymbol("> ")
                .columnSpacing(1)
                .title(buildTableTitle())
                .rounded();
    }

    private String buildTableTitle() {
        return controller.listScope().label() + "  [" + controller.torrents().size() + " on page]";
    }

    /**
     * Builds a single {@link Row} for the table.
     *
     * <p>Each column is a {@link Cell} with its own {@link Style}.
     * The Table widget renders highlighted rows using the {@link TableState}
     * and {@code highlightColor} — we don't need to manually reverse cells.
     */
    private Row buildRow(int index, TorrentResponse t) {

        // ── Column 0: row number ──────────────────────────────────────────────
        Cell idxCell = Cell.from(String.valueOf(index + 1))
                .style(Style.EMPTY.fg(Color.DARK_GRAY));

        // ── Column 1: status glyph ────────────────────────────────────────────
        Cell statusCell = statusCell(t);

        // ── Column 2: name ────────────────────────────────────────────────────
        String name = t.name != null ? t.name : "(unknown)";
        Cell nameCell = Cell.from(name);

        // ── Column 3: total size ──────────────────────────────────────────────
        Cell sizeCell = Cell.from(t.formattedSize())
                .style(Style.EMPTY.fg(Color.DARK_GRAY));

        // ── Column 4: progress text ───────────────────────────────────────────
        // We use a text label here — embedding a full gauge inside a table cell
        // is not supported by TamboUI's Cell (Cell wraps Text, not Element).
        Cell progressCell = progressCell(t);

        // ── Column 5: download speed ──────────────────────────────────────────
        Cell dlCell = speedCell(t.downloadSpeed, true);

        // ── Column 6: upload speed ────────────────────────────────────────────
        Cell ulCell = speedCell(t.uploadSpeed, false);

        // ── Column 7: peers ───────────────────────────────────────────────────
        int peerCount = t.peers != null ? t.peers : 0;
        Style peerStyle = peerCount > 0
                ? Style.EMPTY.fg(Color.CYAN)
                : Style.EMPTY.fg(Color.DARK_GRAY);
        Cell peersCell = Cell.from(String.valueOf(peerCount)).style(peerStyle);

        return Row.from(idxCell, statusCell, nameCell, sizeCell,
                progressCell, dlCell, ulCell, peersCell);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Progress cell
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns a styled {@link Cell} showing progress as a compact text label.
     *
     * <p>TamboUI {@link Cell} wraps {@link dev.tamboui.text.Text}, not {@link Element},
     * so full gauge widgets cannot be embedded inside a table cell. We render a
     * fixed-width "███░░ 72%" style string instead using unicode block chars.
     */
    private static Cell progressCell(TorrentResponse t) {
        double pct   = t.progress != null ? t.progress : 0.0;
        String label = buildProgressBar(pct);
        Style  style = progressStyle(t.status, pct);
        return Cell.from(label).style(style);
    }

    /**
     * Builds a compact 8-char block-character progress bar + percentage label.
     * Example: "████░░░░ 50%"
     */
    private static String buildProgressBar(double pct) {
        int filled = (int) Math.round(pct / 100.0 * 5); // 5 block chars
        filled = Math.max(0, Math.min(5, filled));
        String bar = "█".repeat(filled) + "░".repeat(5 - filled);
        return String.format("%s %4.0f%%", bar, pct);
    }

    private static Style progressStyle(String status, double pct) {
        if (status == null) return Style.EMPTY;
        return switch (status) {
            case "COMPLETED", "SEEDING" -> Style.EMPTY.fg(Color.GREEN);
            case "ERROR"                -> Style.EMPTY.fg(Color.RED);
            case "PAUSED", "STOPPED"   -> Style.EMPTY.fg(Color.DARK_GRAY);
            case "CHECKING"             -> Style.EMPTY.fg(Color.CYAN);
            default                     -> pct >= 100.0
                    ? Style.EMPTY.fg(Color.GREEN)
                    : Style.EMPTY.fg(Color.BLUE);
        };
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Speed cell
    // ─────────────────────────────────────────────────────────────────────────

    private static Cell speedCell(Integer bytesPerSec, boolean isDownload) {
        long bps = bytesPerSec != null ? bytesPerSec.longValue() : 0L;
        String label = formatSpeed(bps);
        Style style;
        if (bps <= 0) {
            style = Style.EMPTY.fg(Color.DARK_GRAY);
        } else {
            style = isDownload
                    ? Style.EMPTY.fg(Color.GREEN)
                    : Style.EMPTY.fg(Color.YELLOW);
        }
        return Cell.from(label).style(style);
    }

    private static String formatSpeed(long bps) {
        if (bps <= 0)            return "  0 B/s";
        if (bps < 1_024)         return String.format("%3d B/s",   bps);
        if (bps < 1_048_576)     return String.format("%3.0fKB/s", bps / 1_024.0);
        if (bps < 1_073_741_824) return String.format("%3.1fMB/s", bps / 1_048_576.0);
        return                          String.format("%3.2fGB/s",  bps / 1_073_741_824.0);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Status glyph cell
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns a colour-coded single-character status glyph cell.
     *
     * <pre>
     *  DOWNLOADING → ↓  green  bold
     *  SEEDING     → ↑  yellow bold
     *  COMPLETED   → ✓  cyan
     *  PAUSED      → ⏸  dark_gray
     *  CHECKING    → ⟳  cyan
     *  ERROR       → ✗  red    bold
     *  PENDING     → …  dark_gray
     *  STOPPED     → ■  dark_gray
     * </pre>
     */
    private static Cell statusCell(TorrentResponse t) {
        if (t.status == null) return Cell.from("?").style(Style.EMPTY.fg(Color.DARK_GRAY));
        return switch (t.status) {
            case "DOWNLOADING" -> Cell.from("↓").style(Style.EMPTY.fg(Color.GREEN).bold());
            case "SEEDING"     -> Cell.from("↑").style(Style.EMPTY.fg(Color.YELLOW).bold());
            case "COMPLETED"   -> Cell.from("✓").style(Style.EMPTY.fg(Color.CYAN));
            case "PAUSED"      -> Cell.from("⏸").style(Style.EMPTY.fg(Color.DARK_GRAY));
            case "CHECKING"    -> Cell.from("⟳").style(Style.EMPTY.fg(Color.CYAN));
            case "ERROR"       -> Cell.from("✗").style(Style.EMPTY.fg(Color.RED).bold());
            case "PENDING"     -> Cell.from("…").style(Style.EMPTY.fg(Color.DARK_GRAY));
            case "STOPPED"     -> Cell.from("■").style(Style.EMPTY.fg(Color.DARK_GRAY));
            default            -> Cell.from(t.statusLabel()).style(Style.EMPTY.fg(Color.DARK_GRAY));
        };
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Filter bar
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Renders the live filter bar shown at the bottom of the panel when
     * the user has pressed {@code /} to enter filter mode.
     * Returns an invisible placeholder when inactive so layout stays stable.
     */
    private Element buildFilterBar() {
        if (!controller.isFilterMode()) {
            return text("");
        }

        String query = controller.filterText();
        return row(
                text(" / ").bold().cyan(),
                text(query).bold(),
                text("_").cyan(),
                spacer(),
                text(" [Esc] cancel ").dim()
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Footer key-hints
    // ─────────────────────────────────────────────────────────────────────────

    private Element buildFooterHints() {
        if (controller.isFilterMode()) {
            return text("");
        }

        String status = controller.statusMessage();
        if (status != null) {
            return controller.statusIsError()
                    ? text(" " + status).red()
                    : text(" " + status).green();
        }

        return text(
                " [1-5] Views  [s] Sort  [S] Dir  [,] Prev Pg  [.] Next Pg" +
                        "  [Enter] Details  [a] Add  [p] Pause  [r] Resume" +
                        "  [d] Delete  [c] Recheck  [/] Search  [?] Help"
        ).dim();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Empty state
    // ─────────────────────────────────────────────────────────────────────────

    private Element renderEmptyState() {
        if (controller.hasActiveQuery()) {
            return column(
                    spacer(),
                    text("  No torrents match \"" + controller.filterText() + "\" in "
                            + controller.listScope().label().toLowerCase() + ".").dim().italic(),
                    text("  Press [/] to edit the query or [Esc] while editing to clear it.").dim(),
                    spacer()
            );
        }

        if (controller.listScope() != AppController.ListScope.ALL) {
            return column(
                    spacer(),
                    text("  No torrents in the " + controller.listScope().label().toLowerCase() + " view.")
                            .dim().italic(),
                    text("  Press [1] for All torrents or [a] to add a new one.").dim(),
                    spacer()
            );
        }

        return column(
                spacer(),
                text("  No torrents yet.").dim().italic(),
                text("  Press [a] to add one via magnet link or .torrent file.").dim(),
                spacer()
        );
    }
}
