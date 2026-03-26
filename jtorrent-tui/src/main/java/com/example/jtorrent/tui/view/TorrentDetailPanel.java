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

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static dev.tamboui.toolkit.Toolkit.*;

/**
 * TorrentDetailPanel — a richer detail workspace backed by the server's
 * per-torrent detail, file, ratio, ETA, and statistics endpoints.
 */
public class TorrentDetailPanel {

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final AppController controller;
    private final TableState fileTableState = new TableState();

    public TorrentDetailPanel(AppController controller) {
        this.controller = controller;
    }

    public Element render() {
        TorrentApiClient.TorrentResponse t = controller.detailTorrent();
        if (t == null) {
            return renderEmptyState();
        }

        return panel(
                t.name != null ? t.name : "(unknown torrent)",
                renderTabBar(),
                renderDetailStateBanner(),
                renderSummaryRow(t),
                renderHashRow(t),
                spacer(),
                renderActiveTab(t),
                spacer(),
                renderFooterHints(t)
        )
                .rounded()
                .id("detail-panel")
                .focusable();
    }

    private Element renderEmptyState() {
        String error = controller.detailError();
        boolean loading = controller.isDetailLoading();

        List<Element> elements = new ArrayList<>();
        if (loading) {
            elements.add(text("  Loading torrent details...").dim());
        } else if (error != null && !error.isBlank()) {
            elements.add(row(text("  ! ").red().bold(), text(error).red()));
        } else {
            elements.add(text("  No torrent selected.").dim());
        }
        elements.add(spacer());
        elements.add(text("  [Esc] Back").dim());

        return panel("Torrent Detail", column(elements.toArray(Element[]::new)))
                .rounded()
                .id("detail-panel")
                .focusable();
    }

    private Element renderTabBar() {
        return row(
                text("  Tabs: ").dim(),
                tabLabel("Overview", AppController.DetailTab.OVERVIEW),
                text("  "),
                tabLabel("Files", AppController.DetailTab.FILES),
                text("  "),
                tabLabel("Stats", AppController.DetailTab.STATS),
                spacer(),
                text("[Tab] next  [Shift+Tab] prev").dim()
        );
    }

    private Element renderDetailStateBanner() {
        String error = controller.detailError();
        boolean loading = controller.isDetailLoading();
        if ((error == null || error.isBlank()) && !loading) {
            return text("");
        }

        List<Element> lines = new ArrayList<>();
        if (error != null && !error.isBlank()) {
            lines.add(row(text("  ! ").red().bold(), text(error).red()));
        }
        if (loading) {
            lines.add(text("  Refreshing detail snapshot...").dim());
        }
        return column(lines.toArray(Element[]::new));
    }

    private Element renderActiveTab(TorrentApiClient.TorrentResponse t) {
        return switch (controller.detailTab()) {
            case OVERVIEW -> renderOverview(t);
            case FILES -> renderFilesView();
            case STATS -> renderStatsView();
        };
    }

    private Element renderSummaryRow(TorrentApiClient.TorrentResponse t) {
        String progress = t.progress != null ? String.format("%.1f%%", t.progress) : "0.0%";
        String size = t.formattedSize();
        String dl = t.formattedDownloadSpeed();
        String ul = t.formattedUploadSpeed();
        int peers = t.peers != null ? t.peers : 0;
        int seeds = t.seeds != null ? t.seeds : 0;

        return row(
                text("  Status: ").dim(),
                statusBadge(t),
                text("  Progress: ").dim(),
                text(progress).bold(),
                text("  Size: ").dim(),
                text(size),
                text("  Files: ").dim(),
                text(String.valueOf(t.fileCount())).cyan(),
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

    private Element renderHashRow(TorrentApiClient.TorrentResponse t) {
        String hash = t.infoHash != null ? truncate(t.infoHash, 16) + "..." : "-";
        String added = formatDate(t.addedDate);
        String path = t.savePath != null ? t.savePath : "-";
        String error = t.errorMessage;

        Element base = row(
                text("  Hash: ").dim(),
                text(hash).dim(),
                text("  Added: ").dim(),
                text(added).dim(),
                text("  Path: ").dim(),
                text(path).dim()
        );

        if (error != null && !error.isBlank()) {
            return column(base, row(text("  ! ").red().bold(), text(error).red()));
        }
        return base;
    }

    private Element renderOverview(TorrentApiClient.TorrentResponse t) {
        List<Element> lines = new ArrayList<>();
        lines.add(row(
                text("  Downloaded: ").dim(),
                text(t.formattedDownloaded()).bold(),
                text("  Uploaded: ").dim(),
                text(t.formattedUploaded()).bold(),
                text("  Completed: ").dim(),
                text(formatDate(t.completedDate)).dim()
        ));
        lines.add(row(
                text("  Created By: ").dim(),
                text(t.createdBy != null ? t.createdBy : "-").dim(),
                text("  Created At: ").dim(),
                text(formatDate(t.creationDate)).dim()
        ));
        if (t.comment != null && !t.comment.isBlank()) {
            lines.add(row(
                    text("  Comment: ").dim(),
                    text(t.comment).dim()
            ));
        }

        return column(lines.toArray(Element[]::new));
    }

    private Element renderFilesView() {
        List<TorrentApiClient.TorrentFileResponse> files = controller.detailFiles();
        if (files.isEmpty()) {
            return text("  File metadata is not available yet for this torrent.").dim().italic();
        }

        fileTableState.select(controller.selectedFileIndex());

        List<Row> rows = new ArrayList<>(files.size());
        for (int i = 0; i < files.size(); i++) {
            rows.add(buildFileRow(i, files.get(i)));
        }

        return column(
                table()
                        .header("#", "File", "Size", "%", "Pri")
                        .widths(
                                Constraint.length(3),
                                Constraint.fill(),
                                Constraint.length(8),
                                Constraint.length(7),
                                Constraint.length(6)
                        )
                        .rows(rows)
                        .state(fileTableState)
                        .highlightColor(Color.CYAN)
                        .highlightSymbol("> ")
                        .columnSpacing(1)
                        .title("Files [" + files.size() + "]")
                        .rounded(),
                renderSelectedFileSummary(controller.selectedFile())
        );
    }

    private Row buildFileRow(int index, TorrentApiClient.TorrentFileResponse file) {
        Cell idxCell = Cell.from(String.valueOf(index + 1))
                .style(Style.EMPTY.fg(Color.DARK_GRAY));

        Cell nameCell = Cell.from(file.displayName())
                .style(fileNameStyle(file));

        Cell sizeCell = Cell.from(file.formattedSize())
                .style(Style.EMPTY.fg(Color.DARK_GRAY));

        Cell progressCell = Cell.from(file.progressLabel())
                .style(progressStyle(file));

        Cell priorityCell = Cell.from(file.priorityLabel())
                .style(priorityStyle(file));

        return Row.from(idxCell, nameCell, sizeCell, progressCell, priorityCell);
    }

    private Element renderSelectedFileSummary(TorrentApiClient.TorrentFileResponse selected) {
        if (selected == null) {
            return text("");
        }

        return column(
                row(
                        text("  Selected: ").dim(),
                        text(selected.displayName()).bold()
                ),
                row(
                        text("  Downloaded: ").dim(),
                        text(selected.formattedDownloaded()).dim(),
                        text(" / ").dim(),
                        text(selected.formattedSize()).dim(),
                        text("  Priority: ").dim(),
                        priorityBadge(selected)
                )
        );
    }

    private Element renderStatsView() {
        TorrentApiClient.TorrentDetailStatsResponse stats = controller.detailStats();
        TorrentApiClient.TorrentEtaResponse eta = controller.detailEta();
        Double ratio = controller.detailRatio();

        if (stats == null && eta == null && ratio == null) {
            return text("  Detailed statistics are not available yet.").dim().italic();
        }

        List<Element> lines = new ArrayList<>();
        lines.add(row(
                text("  Share Ratio: ").dim(),
                ratioText(ratio),
                text("  ETA: ").dim(),
                text(eta != null ? eta.displayValue() : "Unknown").cyan()
        ));

        if (stats != null) {
            lines.add(row(
                    text("  Total Downloaded: ").dim(),
                    text(stats.formattedTotalDownloaded()).bold(),
                    text("  Total Uploaded: ").dim(),
                    text(stats.formattedTotalUploaded()).bold()
            ));
            lines.add(row(
                    text("  Avg DL: ").dim(),
                    text(stats.formattedAverageDownloadSpeed()).green(),
                    text("  Avg UL: ").dim(),
                    text(stats.formattedAverageUploadSpeed()).yellow(),
                    text("  Active: ").dim(),
                    text(stats.formattedTimeActive()).cyan()
            ));
            lines.add(row(
                    text("  Max DL: ").dim(),
                    text(stats.formattedMaxDownloadSpeed()).green(),
                    text("  Max UL: ").dim(),
                    text(stats.formattedMaxUploadSpeed()).yellow(),
                    text("  Peak Peers: ").dim(),
                    text(String.valueOf(stats.totalPeers != null ? stats.totalPeers : 0)).cyan()
            ));
            lines.add(row(
                    text("  Started: ").dim(),
                    text(formatDate(stats.startTime)).dim(),
                    text("  Ended: ").dim(),
                    text(formatDate(stats.endTime)).dim()
            ));
        }

        return column(lines.toArray(Element[]::new));
    }

    private Element renderFooterHints(TorrentApiClient.TorrentResponse t) {
        boolean canPause = "DOWNLOADING".equals(t.status) || "SEEDING".equals(t.status);
        boolean canResume = "PAUSED".equals(t.status) || "STOPPED".equals(t.status);

        List<Element> lines = new ArrayList<>();
        lines.add(row(
                text(" [Esc] Back").dim(),
                text("  [Tab] Next Tab").dim(),
                text("  [Shift+Tab] Prev Tab").dim(),
                canPause ? text("  [p] Pause").dim() : text(""),
                canResume ? text("  [r] Resume").dim() : text(""),
                text("  [t] Reannounce").dim(),
                text("  [c] Recheck").dim(),
                text("  [d] Delete").dim()
        ));

        if (controller.detailTab() == AppController.DetailTab.FILES) {
            lines.add(row(
                    text(" [j/k] Select File").dim(),
                    text("  [s] Skip").dim(),
                    text("  [u] Unskip").dim(),
                    text("  [h] High").dim(),
                    text("  [l] Low").dim(),
                    text("  [n] Normal").dim(),
                    text("  [R] Reset All").dim()
            ));
        }

        return column(lines.toArray(Element[]::new));
    }

    private Element tabLabel(String label, AppController.DetailTab tab) {
        boolean active = controller.detailTab() == tab;
        String textLabel = active ? "[" + label + "]" : label;
        return active
                ? text(textLabel).cyan().bold()
                : text(textLabel).dim();
    }

    private static Element statusBadge(TorrentApiClient.TorrentResponse t) {
        if (t.status == null) return text("?").dim();
        return switch (t.status) {
            case "DOWNLOADING" -> text("↓ DOWNLOADING").green().bold();
            case "SEEDING" -> text("↑ SEEDING").yellow().bold();
            case "COMPLETED" -> text("✓ COMPLETED").cyan();
            case "PAUSED" -> text("⏸ PAUSED").dim();
            case "CHECKING" -> text("⟳ CHECKING").cyan();
            case "ERROR" -> text("✗ ERROR").red().bold();
            case "PENDING" -> text("… PENDING").dim();
            case "STOPPED" -> text("■ STOPPED").dim();
            default -> text(t.status).dim();
        };
    }

    private static Style fileNameStyle(TorrentApiClient.TorrentFileResponse file) {
        if (file.isSkipped()) {
            return Style.EMPTY.fg(Color.DARK_GRAY);
        }
        return switch (file.priority != null ? file.priority : 4) {
            case 7 -> Style.EMPTY.fg(Color.GREEN).bold();
            case 1 -> Style.EMPTY.fg(Color.YELLOW);
            default -> Style.EMPTY;
        };
    }

    private static Style progressStyle(TorrentApiClient.TorrentFileResponse file) {
        if (file.isSkipped()) {
            return Style.EMPTY.fg(Color.DARK_GRAY);
        }
        double pct = file.progress != null ? file.progress : 0.0;
        if (pct >= 100.0) {
            return Style.EMPTY.fg(Color.GREEN);
        }
        return Style.EMPTY.fg(Color.BLUE);
    }

    private static Style priorityStyle(TorrentApiClient.TorrentFileResponse file) {
        return switch (file.priority != null ? file.priority : 4) {
            case 0 -> Style.EMPTY.fg(Color.RED).bold();
            case 1 -> Style.EMPTY.fg(Color.YELLOW);
            case 7 -> Style.EMPTY.fg(Color.GREEN).bold();
            default -> Style.EMPTY.fg(Color.CYAN);
        };
    }

    private static Element priorityBadge(TorrentApiClient.TorrentFileResponse file) {
        return switch (file.priority != null ? file.priority : 4) {
            case 0 -> text(file.priorityLabel()).red().bold();
            case 1 -> text(file.priorityLabel()).yellow();
            case 7 -> text(file.priorityLabel()).green().bold();
            default -> text(file.priorityLabel()).cyan();
        };
    }

    private static Element ratioText(Double ratio) {
        if (ratio == null) {
            return text("-").dim();
        }
        String label = String.format("%.2f", ratio);
        if (ratio >= 1.0) return text(label).green().bold();
        if (ratio >= 0.5) return text(label).yellow();
        return text(label).red();
    }

    private static String formatDate(LocalDateTime value) {
        return value != null ? value.format(DATE_FMT) : "-";
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
