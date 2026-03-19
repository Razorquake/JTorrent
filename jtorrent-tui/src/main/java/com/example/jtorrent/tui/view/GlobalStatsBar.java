package com.example.jtorrent.tui.view;

import com.example.jtorrent.tui.api.TorrentApiClient;
import com.example.jtorrent.tui.controller.AppController;
import dev.tamboui.toolkit.element.Element;

import static dev.tamboui.toolkit.Toolkit.*;

/**
 * GlobalStatsBar — a pure TamboUI View component.
 *
 * <p>Renders a single-line panel pinned to the top of the screen showing:
 * <pre>
 *  JTorrent  ↓ 4.2 MB/s   ↑ 1.1 MB/s   Ratio: 0.42   12 torrents (3↓  2↑  5✓  2⏸)   [?] Help  [q] Quit
 * </pre>
 *
 * <p>Following TamboUI MVC:
 * <ul>
 *   <li>This class is a <strong>View</strong> — a pure function of controller state.</li>
 *   <li>It only reads from {@link AppController} queries; it never modifies state.</li>
 *   <li>No side effects, no I/O, no threading.</li>
 * </ul>
 *
 * <p>Used by the root {@code JTorrentApp.render()} like:
 * <pre>
 *   dock()
 *     .top(statsBar.render())
 *     .topHeight(Constraint.length(3))
 *     .center(torrentListView.render())
 *     ...
 * </pre>
 */
public class GlobalStatsBar {

    private final AppController controller;

    public GlobalStatsBar(AppController controller) {
        this.controller = controller;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // render() — called every frame by JTorrentApp
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Builds and returns the stats bar element tree for the current frame.
     *
     * <p>The bar adapts to connection state:
     * <ul>
     *   <li>Connected → full speed / count / ratio metrics</li>
     *   <li>Disconnected → pulsing "Connecting…" message</li>
     * </ul>
     */
    public Element render() {
        if (!controller.isConnected()) {
            return renderDisconnected();
        }
        return renderConnected();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Connected state
    // ─────────────────────────────────────────────────────────────────────────

    private Element renderConnected() {
        TorrentApiClient.StatsResponse stats = controller.stats();

        return panel(
                "",                          // no panel title — content carries all info
                row(
                        // ── Brand ────────────────────────────────────────────────
                        text(" JTorrent ").bold().cyan(),
                        separator(),

                        // ── Speeds ───────────────────────────────────────────────
                        text(" ↓ ").green(),
                        text(stats.formattedDownloadSpeed()).bold(),
                        text("  ↑ ").yellow(),
                        text(stats.formattedUploadSpeed()).bold(),
                        separator(),

                        // ── Ratio ────────────────────────────────────────────────
                        text(" Ratio: ").dim(),
                        ratioText(stats.overallRatio),
                        separator(),

                        // ── Torrent counts ───────────────────────────────────────
                        text(" " + stats.totalTorrents + " torrents ").dim(),
                        text("("),
                        countBadge(stats.downloadingTorrents, "↓", "green"),
                        text(" "),
                        countBadge(stats.seedingTorrents,     "↑", "yellow"),
                        text(" "),
                        countBadge(stats.completedTorrents,   "✓", "cyan"),
                        text(" "),
                        countBadge(stats.pausedTorrents,      "⏸", "dim"),
                        text(" "),
                        countBadge(stats.errorTorrents,       "✗", "red"),
                        text(")"),
                        separator(),

                        // ── Peers ────────────────────────────────────────────────
                        text(" "),
                        text(stats.totalActivePeers + " peers").dim(),
                        separator(),

                        // ── Keybinding hints (right-aligned via spacer) ──────────
                        spacer(),
                        text("[?] Help").dim(),
                        text("  "),
                        text("[q] Quit").dim(),
                        text(" ")
                )
        ).rounded();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Disconnected state
    // ─────────────────────────────────────────────────────────────────────────

    private Element renderDisconnected() {
        // Show an error message if the controller has one, otherwise generic text
        String msg = controller.statusMessage();
        String displayMsg = (msg != null)
                ? msg
                : "Connecting to JTorrent server…";

        return panel(
                "",
                row(
                        text(" JTorrent ").bold().cyan(),
                        separator(),
                        text(" ⚠ ").yellow().bold(),
                        text(displayMsg).yellow(),
                        spacer(),
                        text("[q] Quit").dim(),
                        text(" ")
                )
        ).rounded();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Thin vertical separator character used between stat groups.
     * Rendered dimmed so it doesn't compete with the actual values.
     */
    private static Element separator() {
        return text("  │  ").dim();
    }

    /**
     * Renders a count badge like "3↓" coloured according to {@code colour}.
     * Returns dim "0↓" when the count is zero to reduce visual noise.
     *
     * @param count  the numeric count
     * @param symbol glyph appended after the number (↓ ↑ ✓ ⏸ ✗)
     * @param colour one of: "green", "yellow", "cyan", "red", "dim"
     */
    private static Element countBadge(long count, String symbol, String colour) {
        String label = count + symbol;
        var element = text(label);

        if (count == 0) {
            return element.dim();
        }

        return switch (colour) {
            case "green"  -> element.green();
            case "yellow" -> element.yellow();
            case "cyan"   -> element.cyan();
            case "red"    -> element.red();
            default       -> element.dim();
        };
    }

    /**
     * Renders the ratio value with colour-coding:
     * <ul>
     *   <li>&gt;= 1.0 → green (giving back more than received)</li>
     *   <li>&gt;= 0.5 → yellow (acceptable)</li>
     *   <li>&lt; 0.5  → red (leeching)</li>
     * </ul>
     */
    private static Element ratioText(double ratio) {
        String label = String.format("%.2f", ratio);
        if (ratio >= 1.0) return text(label).green().bold();
        if (ratio >= 0.5) return text(label).yellow();
        return text(label).red();
    }
}
