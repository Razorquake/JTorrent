package com.example.jtorrent.tui.view;

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
 * Notification history overlay that keeps recent live events and local status
 * messages visible after the footer message has moved on.
 */
public class NotificationOverlay {

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm:ss");

    private final AppController controller;
    private final TableState tableState = new TableState();

    public NotificationOverlay(AppController controller) {
        this.controller = controller;
    }

    public Element render() {
        List<AppController.NotificationEntry> notifications = controller.notifications();
        if (!notifications.isEmpty()) {
            tableState.select(controller.selectedNotificationIndex());
        }

        return panel(
                buildTitle(),
                buildSummaryRow(notifications.size()),
                buildBody(notifications),
                spacer(),
                buildFooter()
        )
                .rounded()
                .id("notification-overlay")
                .focusable();
    }

    private String buildTitle() {
        return "Notifications";
    }

    private Element buildSummaryRow(int total) {
        return row(
                text("  Unread: ").dim(),
                controller.unreadNotificationCount() > 0
                        ? text(String.valueOf(controller.unreadNotificationCount())).cyan().bold()
                        : text("0").dim(),
                text("  Total: ").dim(),
                text(String.valueOf(total)).bold(),
                spacer(),
                text("[Esc] close").dim()
        );
    }

    private Element buildBody(List<AppController.NotificationEntry> notifications) {
        if (notifications.isEmpty()) {
            return column(
                    spacer(),
                    text("  No notifications yet.").dim().italic(),
                    text("  Live updates, action results, and errors will appear here.").dim(),
                    spacer()
            );
        }

        List<Row> rows = new ArrayList<>(notifications.size());
        for (AppController.NotificationEntry entry : notifications) {
            rows.add(buildRow(entry));
        }

        return table()
                .header("Time", "Level", "Source", "Message")
                .widths(
                        Constraint.length(11),
                        Constraint.length(7),
                        Constraint.length(8),
                        Constraint.fill()
                )
                .rows(rows)
                .state(tableState)
                .highlightColor(Color.CYAN)
                .highlightSymbol("> ")
                .columnSpacing(1)
                .title("History")
                .rounded();
    }

    private Row buildRow(AppController.NotificationEntry entry) {
        Style levelStyle = entry.isError()
                ? Style.EMPTY.fg(Color.RED).bold()
                : Style.EMPTY.fg(Color.CYAN);

        Style messageStyle = entry.isError()
                ? Style.EMPTY.fg(Color.RED)
                : Style.EMPTY;

        return Row.from(
                Cell.from(formatTime(entry.timestamp())).style(Style.EMPTY.fg(Color.DARK_GRAY)),
                Cell.from(entry.levelLabel()).style(levelStyle),
                Cell.from(entry.source()).style(Style.EMPTY.fg(Color.YELLOW)),
                Cell.from(entry.message()).style(messageStyle)
        );
    }

    private Element buildFooter() {
        return row(
                text(" [j / k] Move  ").dim(),
                text("[g / G] Top/Bottom  ").dim(),
                text("[c] Clear history  ").cyan(),
                text("[Esc] Back").dim()
        );
    }

    private String formatTime(LocalDateTime timestamp) {
        return timestamp != null ? TIME_FMT.format(timestamp) : "-- --:--:--";
    }
}
