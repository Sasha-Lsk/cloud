package com.apkstudio.mobile;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;

/**
 * Минимальный собственный ContentProvider для отдачи собранных APK и файлов
 * внешним приложениям (открыть / поделиться / установить) через content:// URI.
 * Написан вручную, т.к. проект собирается БЕЗ androidx (нет готового FileProvider).
 *
 * Отдаёт любые файлы из рабочей папки ApkStudio (в Download) - только чтение.
 * URI: content://com.apkstudio.mobile.fileprovider/f/<abs-path-в-base64url>
 */
public class OutFileProvider extends ContentProvider {

    static final String SUFFIX = ".fileprovider";
    private static final String SEGMENT = "f";

    /** Построить content:// URI для файла. */
    public static Uri uriFor(android.content.Context ctx, File f) {
        String authority = ctx.getPackageName() + SUFFIX;
        String enc = android.util.Base64.encodeToString(
                f.getAbsolutePath().getBytes(),
                android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING);
        return new Uri.Builder()
                .scheme("content")
                .authority(authority)
                .appendPath(SEGMENT)
                .appendPath(enc)
                .build();
    }

    private File resolve(Uri uri) {
        java.util.List<String> seg = uri.getPathSegments();
        if (seg.size() != 2 || !SEGMENT.equals(seg.get(0))) return null;
        try {
            String path = new String(android.util.Base64.decode(
                    seg.get(1),
                    android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING));
            // Разрешаем только внутри рабочей папки ApkStudio.
            if (!path.contains("ApkStudio")) return null;
            File f = new File(path);
            return (f.exists() && f.isFile()) ? f : null;
        } catch (Throwable t) { return null; }
    }

    @Override public boolean onCreate() { return true; }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws java.io.FileNotFoundException {
        File f = resolve(uri);
        if (f == null) throw new java.io.FileNotFoundException("Нет файла: " + uri);
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        File f = resolve(uri);
        if (f != null && f.getName().toLowerCase().endsWith(".apk"))
            return "application/vnd.android.package-archive";
        return "application/octet-stream";
    }

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

    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
