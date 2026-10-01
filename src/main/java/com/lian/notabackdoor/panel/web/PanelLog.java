package com.lian.notabackdoor.panel.web;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.io.PrintWriter;
import java.io.StringWriter;

/** A bounded view of recent server log messages, with no filesystem polling. */
public final class PanelLog extends Handler implements AutoCloseable {
    private static final int MAX_LINES = 500;
    private final ArrayDeque<Line> lines = new ArrayDeque<>();
    private final Logger root = Logger.getLogger("");
    private final Formatter formatter = new Formatter() {
        @Override public String format(LogRecord record) { return formatMessage(record); }
    };

    public PanelLog() {
        root.addHandler(this);
    }

    @Override
    public synchronized void publish(LogRecord record) {
        if (record == null || !isLoggable(record)) return;
        if (lines.size() == MAX_LINES) lines.removeFirst();
        String message = formatter.format(record);
        if (message == null) message = "";
        if (record.getThrown() != null) {
            StringWriter stack = new StringWriter();
            record.getThrown().printStackTrace(new PrintWriter(stack));
            message += "\n" + stack;
        }
        if (message.length() > 4_000) message = message.substring(0, 4_000) + "…";
        lines.addLast(new Line(record.getMillis(), record.getLevel().getName(), message));
    }

    public synchronized List<Line> recent() {
        return new ArrayList<>(lines);
    }

    @Override public void flush() { }

    @Override
    public void close() {
        root.removeHandler(this);
        synchronized (this) { lines.clear(); }
    }

    public record Line(long timestamp, String level, String message) { }
}
