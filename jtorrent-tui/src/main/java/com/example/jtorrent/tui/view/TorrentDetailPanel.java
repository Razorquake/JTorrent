package com.example.jtorrent.tui.view;

import com.example.jtorrent.tui.api.TorrentApiClient;
import com.example.jtorrent.tui.controller.AppController;
import dev.tamboui.layout.Constraint;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.widgets.table.Cell;
import dev.tamboui.widgets.table.Row;
import dev.tamboui.widgets.table.TableState;

import java.time.format.DateTimeFormatter;

import static dev.tamboui.toolkit.Toolkit.*;

/**
 * TorrentDetailPanel — a pure TamboUI View component.
 *
 * <p>Renders a detail panel that slides in below the torrent list when the
 * user presses Enter on a selected torrent. Shows three tabs worth of info
 * laid out as a vertical column:
 *
 * <pre>
 * ╭─ Ubuntu 24.04 ISO ────────────────────────────────────────────╮
 * │ Status: ↓ DOWNLOADING   Progress: 72.0%   Size: 4.7 GB       │
 * │ Hash: abc123...         Added: 2025-01-15  Save: ./downloads  │
 * │                                                               │
 * │ Files:                                                        │
 * │  ubuntu-24.04-desktop.iso  4.7 GB  ████████░░ 72%  Normal    │
 * │                                                               │
 * │ [Esc] Close  [p] Pause  [r] Resume  [d] Delete  [c] Recheck  │
 * ╰───────────────────────────────────────────────────────────────╯
 * </pre>
 *
 * <h2>TamboUI View contract</h2>
 * Pure function of {@link AppController} — no side effects, no I/O.
 */
public class TorrentDetailPanel {

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final AppController controller;
    private final TableState fileTableState = new TableState();

    public TorrentDetailPanel(AppController controller) {
        this.controller = controller;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // render()
    // ─────────────────────────────────────────────────────────────────────────

    public Element render() {
        TorrentApiClient.TorrentResponse t = controller.selectedTorrent();
        if (t == null) {
            return panel("Detail", text("  No torrent selected.").dim()).rounded();
        }

        return panel(
                t.name != null ? t.name : "(unknown)",
                renderSummaryRow(t),
                renderHashRow(t),
                spacer(),
                renderFilesTable(t),
                spacer(),
                renderFooterHints(t)
        )
                .rounded()
                .id("detail-panel")
                .focusable();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Summary row
    // ─────────────────────────────────────────────────────────────────────────

    private Element renderSummaryRow(TorrentApiClient.TorrentResponse t) {
        String statusLabel  = t.statusLabel();
        String progress     = t.progress != null ? String.format("%.1f%%", t.progress) : "0.0%";
        String size         = t.formattedSize();
        String dl           = t.formattedDownloadSpeed();
        String ul           = t.formattedUploadSpeed();
        int    peers        = t.peers  != null ? t.peers  : 0;
        int    seeds        = t.seeds  != null ? t.seeds  : 0;

        return row(
                text("  Status: ").dim(),
                statusBadge(t),
                text("  Progress: ").dim(),
                text(progress).bold(),
                text("  Size: ").dim(),
                text(size),
                text("  ↓ ").green(),
                text(dl).green(),
                text("  ↑ ").yellow(),
                text(ul).yellow(),
                text("  Peers: ").dim(),
                text(String.valueOf(peers)).cyan(),
                text("  Seeds: ").dim(),
                text(String.valueOf(seeds)).cyan()
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Hash / path row
    // ─────────────────────────────────────────────────────────────────────────

    private Element renderHashRow(TorrentApiClient.TorrentResponse t) {
        String hash    = t.infoHash   != null ? truncate(t.infoHash, 16) + "…" : "—";
        String added   = t.addedDate  != null ? t.addedDate.format(DATE_FMT)   : "—";
        String path    = t.savePath   != null ? t.savePath                      : "—";
        String error   = t.errorMessage;

        Element base = row(
                text("  Hash: ").dim(),
                text(hash).dim(),
                text("  Added: ").dim(),
                text(added).dim(),
                text("  Path: ").dim(),
                text(path).dim()
        );

        if (error != null && !error.isBlank()) {
            return column(base,
                    row(text("  ⚠ ").red().bold(), text(error).red()));
        }
        return base;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Files table  (uses the files list from TorrentResponse if present)
    // ─────────────────────────────────────────────────────────────────────────

    private Element renderFilesTable(TorrentApiClient.TorrentResponse t) {
        // TorrentResponse.files may be null/empty for torrents that haven't
        // resolved metadata yet (magnet links in PENDING state).
        if (t.name == null) {
            return text("  Files: (metadata not yet available)").dim().italic();
        }

        // Build a single-file row from the torrent's own size / progress
        // when the files list is empty (single-file torrent or no detail).
        fileTableState.select(0);

        String fileName = t.name;
        String fileSize = t.formattedSize();
        String prog     = buildProgressBar(t.progress != null ? t.progress : 0.0);

        Row fileRow = Row.from(
                Cell.from(fileName),
                Cell.from(fileSize).style(Style.EMPTY.fg(Color.DARK_GRAY)),
                Cell.from(prog).style(progressStyle(t.status, t.progress != null ? t.progress : 0.0))
        );

        return table()
                .header("File", "Size", "Progress")
                .widths(
                        Constraint.fill(),
                        Constraint.length(8),
                        Constraint.length(12)
                )
                .row(fileRow)
                .state(fileTableState)
                .title("Files")
                .rounded();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Footer hints
    // ─────────────────────────────────────────────────────────────────────────

    private static Element renderFooterHints(TorrentApiClient.TorrentResponse t) {
        boolean canPause  = "DOWNLOADING".equals(t.status) || "SEEDING".equals(t.status);
        boolean canResume = "PAUSED".equals(t.status)      || "STOPPED".equals(t.status);

        return row(
                text(" [Esc] Back").dim(),
                canPause  ? text("  [p] Pause").dim()  : text(""),
                canResume ? text("  [r] Resume").dim() : text(""),
                text("  [d] Delete").dim(),
                text("  [c] Recheck").dim()
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers shared with TorrentListView
    // ─────────────────────────────────────────────────────────────────────────

    private static Element statusBadge(TorrentApiClient.TorrentResponse t) {
        if (t.status == null) return text("?").dim();
        return switch (t.status) {
            case "DOWNLOADING" -> text("↓ DOWNLOADING").green().bold();
            case "SEEDING"     -> text("↑ SEEDING").yellow().bold();
            case "COMPLETED"   -> text("✓ COMPLETED").cyan();
            case "PAUSED"      -> text("⏸ PAUSED").dim();
            case "CHECKING"    -> text("⟳ CHECKING").cyan();
            case "ERROR"       -> text("✗ ERROR").red().bold();
            case "PENDING"     -> text("… PENDING").dim();
            case "STOPPED"     -> text("■ STOPPED").dim();
            default            -> text(t.status).dim();
        };
    }

    private static String buildProgressBar(double pct) {
        int filled = (int) Math.round(pct / 100.0 * 8);
        filled = Math.max(0, Math.min(8, filled));
        return "█".repeat(filled) + "░".repeat(8 - filled)
                + String.format(" %4.0f%%", pct);
    }

    private static Style progressStyle(String status, double pct) {
        if (status == null) return Style.EMPTY;
        return switch (status) {
            case "COMPLETED", "SEEDING" -> Style.EMPTY.fg(Color.GREEN);
            case "ERROR"                -> Style.EMPTY.fg(Color.RED);
            case "PAUSED", "STOPPED"   -> Style.EMPTY.fg(Color.DARK_GRAY);
            default                     -> pct >= 100.0
                    ? Style.EMPTY.fg(Color.GREEN)
                    : Style.EMPTY.fg(Color.BLUE);
        };
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
