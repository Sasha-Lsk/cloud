package com.apkstudio.mobile;

import android.app.Activity;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Подробный журнал процессов сборки/декомпиляции.
 *
 * Пишет цветные строки с отметкой времени в окно статуса (TextView в ScrollView).
 * Все методы можно вызывать из фонового потока — обновление UI переносится в
 * главный поток. Полный текст хранится отдельно (plainBuffer) для кнопки «копировать».
 *
 * Уровни: cmd (голубой, выполняемая команда), info (серый), ok (зелёный),
 * warn (жёлтый), err (красный). Такой детальный лог нужен, чтобы по нему любой
 * агент/человек мог понять, на каком шаге и почему сломалась сборка.
 */
public class Logger {

    private final Activity act;
    private final TextView view;
    private final ScrollView scroll;
    private final SpannableStringBuilder colored = new SpannableStringBuilder();
    private final StringBuilder plain = new StringBuilder();
    private final SimpleDateFormat ts = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private static final int C_INFO = 0xFF9DA7B0;
    private static final int C_OK   = 0xFF3FB950;
    private static final int C_WARN = 0xFFD29922;
    private static final int C_ERR  = 0xFFF85149;
    private static final int C_CMD  = 0xFF58A6FF;
    private static final int C_HEAD = 0xFFE6EDF3;

    public Logger(Activity act, TextView view, ScrollView scroll) {
        this.act = act;
        this.view = view;
        this.scroll = scroll;
    }

    public void info(String s)  { line(s, C_INFO, "");    }
    public void ok(String s)    { line(s, C_OK,   "[OK] "); }
    public void warn(String s)  { line(s, C_WARN, "[!] ");  }
    public void err(String s)   { line(s, C_ERR,  "[ERR] "); }
    public void cmd(String s)   { line("$ " + s, C_CMD, ""); }

    /** Заголовок раздела (жирный белый, с разделителем). */
    public void head(String s) {
        line("", C_INFO, "");
        line("──────────────────────────────────────", C_INFO, "");
        line(s, C_HEAD, "");
        line("──────────────────────────────────────", C_INFO, "");
    }

    /** Логировать исключение целиком (со стеком) — красным. */
    public void exception(String prefix, Throwable e) {
        java.io.StringWriter sw = new java.io.StringWriter();
        e.printStackTrace(new java.io.PrintWriter(sw));
        err(prefix + ": " + e);
        line(sw.toString().trim(), C_ERR, "");
    }

    private synchronized void line(final String text, final int color, final String tag) {
        final String stamp = ts.format(new Date());
        final String full = "[" + stamp + "] " + tag + text + "\n";
        plain.append(full);
        act.runOnUiThread(new Runnable() {
            public void run() {
                int start = colored.length();
                colored.append(full);
                colored.setSpan(new ForegroundColorSpan(color), start, colored.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                view.setText(colored);
                if (scroll != null) {
                    scroll.post(new Runnable() {
                        public void run() { scroll.fullScroll(ScrollView.FOCUS_DOWN); }
                    });
                }
            }
        });
    }

    /** Полный текст журнала (для копирования в буфер обмена). */
    public synchronized String fullText() { return plain.toString(); }

    /** Очистить журнал. */
    public synchronized void clear() {
        plain.setLength(0);
        act.runOnUiThread(new Runnable() {
            public void run() {
                colored.clear();
                view.setText("");
            }
        });
    }
}
