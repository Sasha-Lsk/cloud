package com.apkstudio.tool;

import android.app.Activity;
import android.widget.TextView;

/** Простой лог, выводящий строки в TextView в UI-потоке. */
public class Log {
    private final Activity act;
    private final TextView view;
    private final StringBuilder sb = new StringBuilder();

    public Log(Activity act, TextView view) {
        this.act = act;
        this.view = view;
    }

    public void line(final String s) {
        android.util.Log.i("ApkStudio", s);
        sb.append(s).append('\n');
        act.runOnUiThread(new Runnable() {
            public void run() { view.setText(sb.toString()); }
        });
    }
}
