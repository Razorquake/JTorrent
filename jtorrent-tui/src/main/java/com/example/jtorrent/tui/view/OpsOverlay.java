package com.example.jtorrent.tui.view;

import com.example.jtorrent.tui.api.TorrentApiClient;
import com.example.jtorrent.tui.controller.AppController;
import dev.tamboui.toolkit.element.Element;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static dev.tamboui.toolkit.Toolkit.*;

/**
 * Operations / health overlay backed by the server's system and file-management
 * endpoints.
 */
public class OpsOverlay {

    private static final int ORPHAN_WINDOW_SIZE = 8;
    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AppController controller;

    public OpsOverlay(AppController controller) {
        this.controller = controller;
    }

    public Element render() {
        return panel(
                "Operations & Health",
                renderTabBar(),
                renderBanner(),
                spacer(),
                renderActiveTab(),
                spacer(),
                renderFooter()
        )
                .rounded()
                .id("ops-overlay")
                .focusable();
    }

    private Element renderTabBar() {
        return row(
                text("  Tabs: ").dim(),
                tabLabel("Overview", AppController.OpsTab.OVERVIEW),
                text("  "),
                tabLabel("Orphans", AppController.OpsTab.ORPHANS),
                spacer(),
                text("[Tab] next  [Shift+Tab] prev").dim()
        );
    }

    private Element renderBanner() {
        List<Element> lines = new ArrayList<>();
        if (controller.opsError() != null && !controller.opsError().isBlank()) {
            lines.add(row(text("  ! ").red().bold(), text(controller.opsError()).red()));
        }
        if (controller.isOpsLoading()) {
            lines.add(text("  Refreshing operations snapshot...").dim());
        }
        if (lines.isEmpty()) {
            return text("");
        }
        return column(lines.toArray(Element[]::new));
    }

    private Element renderActiveTab() {
        return switch (controller.opsTab()) {
            case OVERVIEW -> renderOverviewTab();
            case ORPHANS -> renderOrphansTab();
        };
    }

    private Element renderOverviewTab() {
        TorrentApiClient.SystemHealthResponse health = controller.systemHealth();
        TorrentApiClient.SystemInfoResponse info = controller.systemInfo();
        TorrentApiClient.StorageInfoResponse storage = controller.storageInfo();

        if (health == null && info == null && storage == null && controller.isOpsLoading()) {
            return text("  Loading system and storage information...").dim();
        }

        List<Element> elements = new ArrayList<>();

        elements.add(sectionTitle("System"));
        elements.add(row(
                text("  Status: ").dim(),
                healthBadge(health),
                text("  Session: ").dim(),
                boolBadge(health != null ? health.sessionRunning : info != null ? info.sessionRunning : null),
                text("  Active Torrents: ").dim(),
                text(String.valueOf(info != null && info.activeTorrents != null ? info.activeTorrents : 0)).cyan(),
                text("  Version: ").dim(),
                text(firstNonBlank(
                        health != null ? health.version : null,
                        info != null ? info.version : null,
                        "-"
                )).bold()
        ));
        elements.add(row(
                text("  Service: ").dim(),
                text(firstNonBlank(
                        health != null ? health.service : null,
                        info != null ? info.service : null,
                        "JTorrent"
                )).bold(),
                text("  Refreshed: ").dim(),
                text(formatDate(info != null ? info.timestamp : health != null ? health.timestamp : null)).dim()
        ));
        elements.add(row(
                text("  Download Dir: ").dim(),
                text(info != null && info.downloadDirectory != null ? info.downloadDirectory : "-").dim()
        ));

        elements.add(spacer());
        elements.add(sectionTitle("Storage"));
        if (storage == null) {
            elements.add(text("  Storage information is not available yet.").dim());
        } else {
            elements.add(row(
                    text("  Path: ").dim(),
                    text(storage.path != null ? storage.path : "-").dim()
            ));
            elements.add(row(
                    text("  Total: ").dim(),
                    text(storage.displayTotal()).bold(),
                    text("  Used: ").dim(),
                    text(storage.displayUsed()).yellow(),
                    text("  Free: ").dim(),
                    text(storage.displayFree()).green(),
                    text("  Tracked Torrent Data: ").dim(),
                    text(storage.displayTrackedUsage()).cyan()
            ));
            elements.add(row(
                    text("  Usage: ").dim(),
                    text(usageBar(storage)).cyan(),
                    text("  "),
                    text(usagePercent(storage)).dim()
            ));
        }

        return column(elements.toArray(Element[]::new));
    }

    private Element renderOrphansTab() {
        List<String> orphans = controller.orphanedFiles();
        int total = orphans.size();

        List<Element> elements = new ArrayList<>();
        elements.add(row(
                text("  Orphaned Files: ").dim(),
                text(String.valueOf(total)).bold(),
                text("  Selected: ").dim(),
                text(total == 0 ? "0/0" : (controller.selectedOrphanIndex() + 1) + "/" + total).dim()
        ));

        if (total == 0) {
            elements.add(spacer());
            elements.add(text("  No orphaned files found in the download directory.").dim());
            return column(elements.toArray(Element[]::new));
        }

        elements.add(spacer());
        int start = Math.max(0, controller.selectedOrphanIndex() - (ORPHAN_WINDOW_SIZE / 2));
        start = Math.min(start, Math.max(total - ORPHAN_WINDOW_SIZE, 0));
        int end = Math.min(start + ORPHAN_WINDOW_SIZE, total);

        for (int i = start; i < end; i++) {
            boolean selected = i == controller.selectedOrphanIndex();
            String prefix = selected ? " > " : "   ";
            String label = truncateMiddle(orphans.get(i), 96);
            elements.add(row(
                    selected ? text(prefix).cyan().bold() : text(prefix).dim(),
                    selected ? text(label).cyan().bold() : text(label)
            ));
        }

        if (start > 0 || end < total) {
            elements.add(row(
                    text("  Showing ").dim(),
                    text((start + 1) + "-" + end).bold(),
                    text(" of ").dim(),
                    text(String.valueOf(total)).bold(),
                    text(" orphaned files").dim()
            ));
        }

        String selected = controller.selectedOrphanPath();
        if (selected != null) {
            elements.add(spacer());
            elements.add(row(
                    text("  Selected Path: ").dim(),
                    text(selected).dim()
            ));
        }

        return column(elements.toArray(Element[]::new));
    }

    private Element renderFooter() {
        List<Element> items = new ArrayList<>();
        items.add(text(" [r] Refresh  ").cyan());
        if (controller.opsTab() == AppController.OpsTab.ORPHANS) {
            items.add(text("[j / k] Move  ").dim());
            items.add(text("[c] Cleanup orphans  ").red());
        }
        items.add(text("[Esc] Close").dim());
        return row(items.toArray(Element[]::new));
    }

    private Element tabLabel(String label, AppController.OpsTab tab) {
        boolean active = controller.opsTab() == tab;
        String display = active ? "[" + label + "]" : label;
        return active ? text(display).cyan().bold() : text(display).dim();
    }

    private Element sectionTitle(String title) {
        return text(" " + title).bold().cyan();
    }

    private Element healthBadge(TorrentApiClient.SystemHealthResponse health) {
        if (health == null) {
            return text("Unknown").dim();
        }
        if (health.isUp()) {
            return text("UP").green().bold();
        }
        return text(firstNonBlank(health.status, "DOWN")).red().bold();
    }

    private Element boolBadge(Boolean value) {
        if (Boolean.TRUE.equals(value)) {
            return text("Running").green().bold();
        }
        if (Boolean.FALSE.equals(value)) {
            return text("Stopped").red().bold();
        }
        return text("Unknown").dim();
    }

    private String usageBar(TorrentApiClient.StorageInfoResponse storage) {
        if (storage == null || storage.totalBytes == null || storage.totalBytes <= 0 || storage.usedBytes == null) {
            return "[unknown]";
        }

        double ratio = Math.max(0.0, Math.min(1.0, storage.usedBytes / (double) storage.totalBytes));
        int filled = (int) Math.round(ratio * 18);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 18; i++) {
            sb.append(i < filled ? '#' : '.');
        }
        sb.append(']');
        return sb.toString();
    }

    private String usagePercent(TorrentApiClient.StorageInfoResponse storage) {
        if (storage == null || storage.totalBytes == null || storage.totalBytes <= 0 || storage.usedBytes == null) {
            return "Unknown";
        }
        double ratio = Math.max(0.0, Math.min(1.0, storage.usedBytes / (double) storage.totalBytes));
        return String.format("%.1f%% used", ratio * 100.0);
    }

    private String formatDate(LocalDateTime dateTime) {
        return dateTime != null ? DATE_FMT.format(dateTime) : "-";
    }

    private String truncateMiddle(String value, int maxLength) {
        if (value == null || value.length() <= maxLength || maxLength < 10) {
            return value != null ? value : "-";
        }

        int side = (maxLength - 3) / 2;
        int tail = maxLength - side - 3;
        return value.substring(0, side) + "..." + value.substring(value.length() - tail);
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return "";
    }
}
