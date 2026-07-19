package com.idemobile.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;

/**
 * Минимальный собственный ContentProvider для безопасной отдачи файлов отчётов
 * о сбоях внешним приложениям (почта/мессенджеры/просмотрщики) через content://
 * URI. Написан вручную, потому что проект собирается БЕЗ androidx/support, где
 * обычно берут готовый FileProvider.
 *
 * Отдаёт ТОЛЬКО файлы из папки отчётов (App.reportsDir()) и legacy-файла —
 * никакого произвольного доступа к ФС. Доступ только на чтение.
 *
 * URI: content://<package>.fileprovider/crash/<имя-файла>
 */
public class CrashFileProvider extends ContentProvider {

    static final String SUFFIX = ".fileprovider";
    private static final String SEGMENT = "crash";

    /** Построить content:// URI для файла отчёта. */
    public static Uri uriFor(android.content.Context ctx, File f) {
        String authority = ctx.getPackageName() + SUFFIX;
        return new Uri.Builder()
                .scheme("content")
                .authority(authority)
                .appendPath(SEGMENT)
                .appendPath(f.getName())
                .build();
    }

    /** Разрешаем только файлы из папки отчётов и legacy-файл. */
    private File resolve(Uri uri) {
        java.util.List<String> seg = uri.getPathSegments();
        if (seg.size() != 2 || !SEGMENT.equals(seg.get(0))) return null;
        String name = seg.get(1);
        if (name.contains("/") || name.contains("..")) return null; // защита от traversal

        File dir = App.reportsDir();
        if (dir != null) {
            File f = new File(dir, name);
            if (f.exists() && f.isFile()) return f;
        }
        try {
            File legacy = new File(getContext().getFilesDir(), "idemobile_crash.txt");
            if (name.equals(legacy.getName()) && legacy.exists()) return legacy;
        } catch (Throwable ignored) {}
        return null;
    }

    @Override
    public boolean onCreate() { return true; }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws java.io.FileNotFoundException {
        File f = resolve(uri);
        if (f == null) throw new java.io.FileNotFoundException("Нет такого отчёта: " + uri);
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) { return "text/plain"; }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        File f = resolve(uri);
        if (f == null) return null;
        String[] cols = projection != null ? projection
                : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor cursor = new MatrixCursor(cols);
        Object[] values = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) values[i] = f.getName();
            else if (OpenableColumns.SIZE.equals(cols[i])) values[i] = f.length();
            else values[i] = null;
        }
        cursor.addRow(values);
        return cursor;
    }

    // Провайдер только для чтения — модификация не поддерживается.
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
