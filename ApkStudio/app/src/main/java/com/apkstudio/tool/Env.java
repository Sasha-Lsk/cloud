package com.apkstudio.tool;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Готовит рабочее окружение: выбирает ABI, распаковывает движки и нативные
 * бинарники (aapt) из assets в приватную папку приложения и делает aapt
 * исполняемым. Всё, что вызывается позже, берётся отсюда.
 */
public class Env {

    public final File work;      // рабочая корневая папка
    public final File engines;   // распакованные .jar движки
    public final File bin;       // нативные бинарники (aapt) под текущий ABI
    public final File androidJar;// платформенный android.jar
    public final String abi;

    private final Context ctx;

    public Env(Context ctx) throws Exception {
        this.ctx = ctx;
        this.abi = pickAbi();
        this.work = new File(ctx.getFilesDir(), "work");
        this.engines = new File(ctx.getFilesDir(), "engines");
        this.bin = new File(ctx.getFilesDir(), "bin");
        this.androidJar = new File(engines, "android.jar");
        work.mkdirs();
        engines.mkdirs();
        bin.mkdirs();
    }

    /** Выбираем ABI, для которого есть aapt в assets. */
    private String pickAbi() {
        for (String a : Build.SUPPORTED_ABIS) {
            if (a.startsWith("arm64")) return "arm64-v8a";
            if (a.startsWith("armeabi")) return "armeabi-v7a";
            if (a.equals("x86_64")) return "x86_64";
            if (a.equals("x86")) return "x86";
        }
        return "arm64-v8a";
    }

    /** Копирует один asset в файл назначения (если ещё не скопирован). */
    public void extractAsset(String assetPath, File dst, boolean executable) throws Exception {
        if (dst.exists() && dst.length() > 0) {
            if (executable) dst.setExecutable(true, false);
            return;
        }
        dst.getParentFile().mkdirs();
        try (InputStream in = ctx.getAssets().open(assetPath);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        if (executable) dst.setExecutable(true, false);
    }

    /** Распаковка всех необходимых инструментов. Вызывать один раз при старте. */
    public void prepare(Log log) throws Exception {
        log.line("ABI: " + abi);

        // 1) Нативные бинарники под текущую архитектуру (aapt, zipalign)
        File aapt = new File(bin, "aapt");
        try {
            extractAsset("bin/" + abi + "/aapt", aapt, true);
            log.line("aapt готов");
        } catch (Exception e) { log.line("!! aapt для " + abi + " не найден"); }

        File zipalign = new File(bin, "zipalign");
        try {
            extractAsset("bin/" + abi + "/zipalign", zipalign, true);
            log.line("zipalign готов");
        } catch (Exception e) { log.line("(zipalign для " + abi + " нет — пропущу выравнивание)"); }

        // 2) Движки в формате DEX (готовые, конвертированы через d8).
        //    Имена фиксированы — файлы уже вложены в assets/engines.
        String[] jars = {
                "smali_dex.jar", "baksmali_dex.jar",
                "jadx_dex.jar", "apksig_dex.jar", "r8_dex.jar"
        };
        for (String j : jars) {
            File dst = new File(engines, j);
            try {
                extractAsset("engines/" + j, dst, false);
                log.line("движок: " + j + " (" + dst.length() / 1024 + " KB)");
            } catch (Exception e) {
                log.line("!! НЕ найден движок engines/" + j);
            }
        }

        // 3) Ключ подписи (testkey)
        extractAsset("engines/testkey.pk8", new File(engines, "testkey.pk8"), false);
        extractAsset("engines/testkey.x509.pem", new File(engines, "testkey.x509.pem"), false);
        log.line("Окружение готово.");
    }
}
