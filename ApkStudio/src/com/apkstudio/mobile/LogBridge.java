package com.apkstudio.mobile;

import java.io.OutputStream;
import java.io.PrintStream;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;

/**
 * Мост вывода встроенных инструментов (apktool/jadx) в наше окно журнала.
 *
 * apktool и jadx пишут прогресс/ошибки через java.util.logging и в System.out/err.
 * По умолчанию это уходит в logcat (не видно пользователю без ПК). На время
 * операции LogBridge:
 *   - вешает Handler на корневой JUL-логгер -> строки идут в Logger;
 *   - подменяет System.out/System.err на потоки, пишущие в Logger.
 * По завершении всё возвращается назад (attach/detach в try/finally).
 *
 * Это и обеспечивает "детализированное окно статуса", по которому видно каждый
 * шаг сборки и точную причину ошибки.
 */
public final class LogBridge {

    private final Logger log;
    private Handler julHandler;
    private PrintStream oldOut, oldErr;

    public LogBridge(Logger log) { this.log = log; }

    /** Начать перехват вывода инструментов. */
    public void attach() {
        // 1) java.util.logging
        try {
            java.util.logging.Logger root = LogManager.getLogManager().getLogger("");
            julHandler = new Handler() {
                @Override public void publish(LogRecord r) {
                    if (r == null) return;
                    String msg = safeFormat(r);
                    Level lv = r.getLevel();
                    if (lv == null) { log.info(msg); return; }
                    int v = lv.intValue();
                    if (v >= Level.SEVERE.intValue()) log.err(msg);
                    else if (v >= Level.WARNING.intValue()) log.warn(msg);
                    else log.info(msg);
                }
                @Override public void flush() {}
                @Override public void close() {}
            };
            julHandler.setLevel(Level.ALL);
            if (root != null) {
                root.addHandler(julHandler);
                root.setLevel(Level.ALL);
            }
        } catch (Throwable ignored) {}

        // 2) System.out / System.err
        try {
            oldOut = System.out;
            oldErr = System.err;
            System.setOut(new PrintStream(new LineStream(false), true));
            System.setErr(new PrintStream(new LineStream(true), true));
        } catch (Throwable ignored) {}
    }

    /** Завершить перехват, вернуть всё как было. */
    public void detach() {
        try {
            java.util.logging.Logger root = LogManager.getLogManager().getLogger("");
            if (root != null && julHandler != null) root.removeHandler(julHandler);
        } catch (Throwable ignored) {}
        try { if (oldOut != null) System.setOut(oldOut); } catch (Throwable ignored) {}
        try { if (oldErr != null) System.setErr(oldErr); } catch (Throwable ignored) {}
    }

    private String safeFormat(LogRecord r) {
        try {
            String m = r.getMessage();
            Object[] p = r.getParameters();
            if (m != null && p != null && p.length > 0) {
                try { return java.text.MessageFormat.format(m, p); } catch (Throwable t) { return m; }
            }
            return m != null ? m : "";
        } catch (Throwable t) { return String.valueOf(t.getMessage()); }
    }

    /** Поток, копящий байты до перевода строки и отдающий строку в Logger. */
    private final class LineStream extends OutputStream {
        private final boolean err;
        private final StringBuilder sb = new StringBuilder();
        LineStream(boolean err) { this.err = err; }

        @Override public void write(int b) {
            char c = (char) (b & 0xFF);
            if (c == '\n') { flushLine(); }
            else if (c != '\r') { sb.append(c); }
        }
        private void flushLine() {
            String line = sb.toString();
            sb.setLength(0);
            if (line.trim().isEmpty()) return;
            if (err) log.warn(line); else log.info(line);
        }
    }
}
