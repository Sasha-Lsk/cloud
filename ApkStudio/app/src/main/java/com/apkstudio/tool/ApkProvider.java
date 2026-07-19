package com.apkstudio.tool;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;

/**
 * Минимальный ContentProvider без androidx: отдаёт собранный APK по
 * content://com.apkstudio.tool.fileprovider/<имя>, чтобы им можно было
 * поделиться / открыть установщик. Файлы берутся из files/work.
 */
public class ApkProvider extends ContentProvider {

    public static final String AUTHORITY = "com.apkstudio.tool.fileprovider";

    public static Uri uriFor(String fileName) {
        return Uri.parse("content://" + AUTHORITY + "/" + fileName);
    }

    private File resolve(Uri uri) {
        String name = uri.getLastPathSegment();
        return new File("/storage/emulated/0/Download/ApkStudio", name);
    }

    @Override public boolean onCreate() { return true; }

    @Override public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode)
            throws java.io.FileNotFoundException {
        return ParcelFileDescriptor.open(resolve(uri),
                ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Cursor query(Uri uri, String[] proj, String sel,
                                  String[] selArgs, String sort) {
        File f = resolve(uri);
        MatrixCursor c = new MatrixCursor(
                new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        c.addRow(new Object[]{f.getName(), f.length()});
        return c;
    }

    @Override public Uri insert(Uri uri, ContentValues v) { return null; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
