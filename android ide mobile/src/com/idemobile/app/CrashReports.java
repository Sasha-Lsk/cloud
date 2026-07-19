package com.idemobile.app;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import java.io.File;
import java.io.FileReader;
import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Работа с сохранёнными отчётами о сбоях: получить список, прочитать,
 * поделиться файлом (можно отправить в любое приложение: почта, мессенджер,
 * «Сохранить в файлы» и т.п.), открыть на просмотр, удалить.
 *
 * Файлы отдаются наружу через собственный {@link CrashFileProvider}
 * (authority {@code <package>.fileprovider}), т.к. проект собирается без androidx.
 */
public final class CrashReports {

    private CrashReports() {}

    /** Все файлы отчётов, отсортированные от новых к старым. */
    public static List<File> list() {
        List<File> res = new ArrayList<File>();
        File dir = App.reportsDir();
        if (dir != null) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) if (f.isFile() && f.getName().endsWith(".txt")) res.add(f);
            }
        }
        // legacy-файл (старое единое имя) — тоже показываем, если есть.
        try {
            File legacy = new File(App.ctx().getFilesDir(), "idemobile_crash.txt");
            if (legacy.exists()) res.add(legacy);
        } catch (Throwable ignored) {}
        Collections_sortNewestFirst(res);
        return res;
    }

    private static void Collections_sortNewestFirst(List<File> files) {
        try {
            java.util.Collections.sort(files, new Comparator<File>() {
                public int compare(File a, File b) { return Long.compare(b.lastModified(), a.lastModified()); }
            });
        } catch (Throwable ignored) {}
    }

    /** Прочитать содержимое отчёта (полностью). */
    public static String read(File f) {
        StringBuilder sb = new StringBuilder();
        try {
            BufferedReader br = new BufferedReader(new FileReader(f));
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            br.close();
        } catch (Throwable t) {
            return "Не удалось прочитать отчёт: " + t.getMessage();
        }
        return sb.toString();
    }

    /** URI файла через собственный провайдер (для внешних приложений). */
    private static Uri uriFor(Context ctx, File f) {
        return CrashFileProvider.uriFor(ctx, f);
    }

    /**
     * Поделиться отчётом: системный выбор приложения (почта, мессенджер, диск…).
     * Прикрепляем и как вложение-файл, и как текст (на случай приложений,
     * которые не берут вложение) — но текст ограничиваем, чтобы не превысить лимит.
     */
    public static void share(Context ctx, File f) {
        try {
            Uri uri = uriFor(ctx, f);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.putExtra(Intent.EXTRA_SUBJECT, "IDE Mobile — отчёт о сбое (" + f.getName() + ")");
            String body = read(f);
            if (body.length() > 6000) body = body.substring(0, 6000) + "\n…[обрезано, полный текст во вложении]";
            send.putExtra(Intent.EXTRA_TEXT, body);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent chooser = Intent.createChooser(send, "Поделиться отчётом о сбое");
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            ctx.startActivity(chooser);
        } catch (Throwable t) {
            android.widget.Toast.makeText(ctx, "Не удалось поделиться: " + t.getMessage(),
                    android.widget.Toast.LENGTH_LONG).show();
        }
    }

    /** Открыть отчёт в любом приложении для просмотра текста. */
    public static void open(Context ctx, File f) {
        try {
            Uri uri = uriFor(ctx, f);
            Intent view = new Intent(Intent.ACTION_VIEW);
            view.setDataAndType(uri, "text/plain");
            view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Intent chooser = Intent.createChooser(view, "Открыть отчёт через…");
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            ctx.startActivity(chooser);
        } catch (Throwable t) {
            android.widget.Toast.makeText(ctx, "Нет приложения для открытия .txt — используйте «Поделиться»",
                    android.widget.Toast.LENGTH_LONG).show();
        }
    }

    /** Удалить один отчёт. */
    public static boolean delete(File f) {
        try { return f != null && f.delete(); } catch (Throwable t) { return false; }
    }

    /** Удалить все отчёты. */
    public static void deleteAll() {
        for (File f : list()) { try { f.delete(); } catch (Throwable ignored) {} }
        App.clearPendingCrash();
    }
}
