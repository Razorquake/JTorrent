package com.example.jtorrent.tui.view;

import dev.tamboui.toolkit.element.Element;

import static dev.tamboui.toolkit.Toolkit.*;

/**
 * HelpOverlay — a pure stateless view that renders the keybinding reference.
 *
 * <p>Displayed when {@code controller.activeView() == HELP}.
 * Press {@code ?} to open, {@code Esc} or {@code ?} again to close.
 */
public class HelpOverlay {

    public Element render() {
        return panel(
                "Help — Keyboard Shortcuts",
                spacer(),
                // ── Navigation ────────────────────────────────────────────
                text(" Navigation").bold().cyan(),
                row(text("  j / ↓").bold(),   text("    Move selection down").dim()),
                row(text("  k / ↑").bold(),   text("    Move selection up").dim()),
                row(text("  g / Home").bold(), text("  Jump to first torrent").dim()),
                row(text("  G / End").bold(),  text("   Jump to last torrent").dim()),
                spacer(),
                // ── Actions ───────────────────────────────────────────────
                text(" Actions").bold().cyan(),
                row(text("  Enter").bold(),    text("    Open detail panel").dim()),
                row(text("  a").bold(),        text("        Add torrent (magnet or file)").dim()),
                row(text("  p").bold(),        text("        Pause selected torrent").dim()),
                row(text("  r").bold(),        text("        Resume selected torrent").dim()),
                row(text("  d").bold(),        text("        Delete selected torrent").dim()),
                row(text("  c").bold(),        text("        Force piece recheck").dim()),
                row(text("  t").bold(),        text("        Reannounce selected torrent").dim()),
                spacer(),
                // ── Search ────────────────────────────────────────────────
                text(" Search").bold().cyan(),
                row(text("  /").bold(),        text("        Open filter bar").dim()),
                row(text("  Esc").bold(),      text("      Close filter / cancel dialog").dim()),
                spacer(),
                // ── Detail View ───────────────────────────────────────────
                text(" Detail View").bold().cyan(),
                row(text("  Tab").bold(),      text("      Switch detail tabs").dim()),
                row(text("  Shift+Tab").bold(), text("  Previous detail tab").dim()),
                row(text("  j / k").bold(),    text("    Move file selection (Files tab)").dim()),
                row(text("  s / u").bold(),    text("    Skip or unskip selected file").dim()),
                row(text("  h / l").bold(),    text("    Set selected file high/low priority").dim()),
                row(text("  n").bold(),        text("        Reset selected file to normal priority").dim()),
                row(text("  R").bold(),        text("        Reset all file priorities").dim()),
                spacer(),
                // ── General ───────────────────────────────────────────────
                text(" General").bold().cyan(),
                row(text("  ?").bold(),        text("        Toggle this help screen").dim()),
                row(text("  q").bold(),        text("        Quit JTorrent").dim()),
                spacer(),
                text(" [Esc] or [?] Close help").dim()
        )
                .rounded()
                .id("help-overlay")
                .focusable();
    }
}
