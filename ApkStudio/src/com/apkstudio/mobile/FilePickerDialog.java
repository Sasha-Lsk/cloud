package com.apkstudio.mobile;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Environment;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Простой встроенный выбор файла/папки навигацией по файловой системе.
 *
 * Используется вместо SAF, потому что приложению выдан полный доступ
 * (MANAGE_EXTERNAL_STORAGE) и работать с обычными путями File проще и надёжнее
 * для последующего запуска нативных инструментов.
 *
 * Режимы:
 *   MODE_APK     — выбрать файл .apk (папки для навигации).
 *   MODE_DIR     — выбрать папку (кнопка «Выбрать эту папку»).
 */
public class FilePickerDialog {

    public static final int MODE_APK = 0;
    public static final int MODE_DIR = 1;

    public interface OnPick { void onPick(File f); }

    private final Context ctx;
    private final int mode;
    private final OnPick cb;
    private File current;
    private AlertDialog dialog;
    private TextView pathView;
    private ArrayAdapter<String> adapter;
    private List<File> entries = new ArrayList<File>();

    public FilePickerDialog(Context ctx, int mode, OnPick cb) {
        this.ctx = ctx;
        this.mode = mode;
        this.cb = cb;
    }

    public void show() {
        current = Environment.getExternalStorageDirectory();
        // Старт из Download/ApkStudio, если существует.
        File preferred = Storage.root();
        if (preferred.exists()) current = preferred;

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0E1116);
        int pad = dp(12);
        root.setPadding(pad, pad, pad, pad);

        pathView = new TextView(ctx);
        pathView.setTextColor(0xFF8B949E);
        pathView.setTextSize(11f);
        pathView.setPadding(0, 0, 0, dp(8));
        root.addView(pathView);

        final ListView list = new ListView(ctx);
        list.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(360)));
        adapter = new ArrayAdapter<String>(ctx, android.R.layout.simple_list_item_1) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                TextView tv = (TextView) super.getView(position, convertView, parent);
                tv.setTextColor(0xFFE6EDF3);
                tv.setTextSize(14f);
                return tv;
            }
        };
        list.setAdapter(adapter);
        root.addView(list);

        list.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            public void onItemClick(android.widget.AdapterView<?> p, View v, int pos, long id) {
                File f = entries.get(pos);
                if (f == null) { // ".."
                    if (current.getParentFile() != null) { current = current.getParentFile(); refresh(); }
                    return;
                }
                if (f.isDirectory()) { current = f; refresh(); }
                else if (mode == MODE_APK && f.getName().toLowerCase().endsWith(".apk")) {
                    finish(f);
                }
            }
        });

        AlertDialog.Builder b = new AlertDialog.Builder(ctx);
        b.setTitle(mode == MODE_APK ? "Выберите APK" : "Выберите папку проекта");
        b.setView(root);
        if (mode == MODE_DIR) {
            b.setPositiveButton("Выбрать эту папку", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int w) { finish(current); }
            });
        }
        b.setNegativeButton("Отмена", null);
        dialog = b.create();
        refresh();
        dialog.show();
    }

    private void finish(File f) {
        if (dialog != null) dialog.dismiss();
        if (cb != null) cb.onPick(f);
    }

    private void refresh() {
        pathView.setText(current.getAbsolutePath());
        entries.clear();
        adapter.clear();
        // ".." вверх
        if (current.getParentFile() != null) { entries.add(null); adapter.add("⬆  .."); }

        File[] files = current.listFiles();
        if (files == null) files = new File[0];
        List<File> dirs = new ArrayList<File>();
        List<File> apks = new ArrayList<File>();
        for (File f : files) {
            if (f.isHidden()) continue;
            if (f.isDirectory()) dirs.add(f);
            else if (f.getName().toLowerCase().endsWith(".apk")) apks.add(f);
        }
        Comparator<File> byName = new Comparator<File>() {
            public int compare(File a, File b) { return a.getName().compareToIgnoreCase(b.getName()); }
        };
        java.util.Collections.sort(dirs, byName);
        java.util.Collections.sort(apks, byName);
        for (File d : dirs) { entries.add(d); adapter.add("📁  " + d.getName()); }
        if (mode == MODE_APK)
            for (File a : apks) { entries.add(a); adapter.add("📦  " + a.getName()); }
        adapter.notifyDataSetChanged();
    }

    private int dp(int v) { return (int) (v * ctx.getResources().getDisplayMetrics().density); }
}
