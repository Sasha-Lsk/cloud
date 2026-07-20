package com.apkstudio.mobile;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;

import java.io.File;

/**
 * Единые пути рабочих папок и открытие результата в системном приложении.
 *
 * Корень (папка распаковки == папка сборки):
 *   /storage/emulated/0/Download/ApkStudio/
 *     decompiler_apk/   ← результат декомпиляции (smali/java-проекты)
 *     compiler_apk/     ← собранные .apk
 */
public final class Storage {

    public static final String ROOT_NAME = "ApkStudio";
    public static final String DECOMPILE_DIR = "decompiler_apk";
    public static final String COMPILE_DIR = "compiler_apk";

    private Storage() {}

    /** /storage/emulated/0/Download/ApkStudio */
    public static File root() {
        File dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File root = new File(dl, ROOT_NAME);
        root.mkdirs();
        return root;
    }

    /** Папка декомпиляции: <root>/decompiler_apk */
    public static File decompileDir() {
        File d = new File(root(), DECOMPILE_DIR);
        d.mkdirs();
        return d;
    }

    /** Папка сборки: <root>/compiler_apk */
    public static File compileDir() {
        File d = new File(root(), COMPILE_DIR);
        d.mkdirs();
        return d;
    }

    /** Открыть папку в любом системном приложении (файловый менеджер). */
    public static void openFolder(Context ctx, File dir) {
        try {
            Uri uri = Uri.parse(dir.getAbsolutePath());
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "resource/folder");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Intent chooser = Intent.createChooser(i, "Открыть папку через…");
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(chooser);
        } catch (Throwable t) {
            // Фолбэк: показать содержимое как список файлов (DocumentsUI).
            try {
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setType("*/*");
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(Intent.createChooser(i, "Открыть проводник").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Throwable ignored) {}
        }
    }

    /** Открыть/установить готовый APK через системное приложение. */
    public static void openApk(Context ctx, File apk) {
        try {
            Uri uri = OutFileProvider.uriFor(ctx, apk);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Throwable t) {
            android.widget.Toast.makeText(ctx, "Не удалось открыть APK: " + t.getMessage(),
                    android.widget.Toast.LENGTH_LONG).show();
        }
    }
}
