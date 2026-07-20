package com.apkstudio.mobile;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

import dalvik.system.DexClassLoader;

/**
 * Управление встроенным инструментарием (движком): распаковка ассетов, загрузка
 * dex-jar-ов через DexClassLoader (apktool / jadx / apksig — все уже в формате
 * classes.dex, поэтому грузятся прямо в текущий процесс ART без внешней JVM) и
 * подготовка нативных бинарников aapt/aapt2/zipalign под текущий ABI.
 *
 * ── Почему так ──
 * На Android нет команды `java`, поэтому jar нельзя запустить подпроцессом. Но
 * apktool.jar/jadx.jar/apksig.jar содержат classes.dex — их классы грузятся
 * DexClassLoader-ом и вызываются рефлексией в этом же процессе.
 *
 * aapt/aapt2/zipalign — нативные ELF под ABI устройства. Их apktool/сборка
 * запускают через Runtime.exec. Чтобы файл был исполняемым и на Android 10+
 * (ограничение W^X на запись+исполнение из data-каталога), бинарник кладём и
 * помечаем chmod 700; путь передаётся apktool через опцию -a.
 */
public final class Tools {

    private static final String ASSET_ROOT = "tools";

    private final Context ctx;
    private final Logger log;
    private final File toolsDir;   // <files>/tools — распакованный движок
    private final File binDir;     // <files>/tools/bin — нативные бинарники
    private final File workDir;    // <files>/work — временные каталоги операций

    private DexClassLoader apktoolCl;
    private DexClassLoader jadxCl;
    private DexClassLoader apksigCl;

    public Tools(Context ctx, Logger log) {
        this.ctx = ctx;
        this.log = log;
        File base = ctx.getFilesDir();
        this.toolsDir = new File(base, "tools");
        this.binDir = new File(toolsDir, "bin");
        this.workDir = new File(base, "work");
        toolsDir.mkdirs();
        binDir.mkdirs();
        workDir.mkdirs();
    }

    public File workDir() { return workDir; }

    // ──────────────────────────────────────────────── ABI / бинарники
    /** Основной ABI устройства (arm64-v8a / armeabi-v7a / x86_64 / x86). */
    public String primaryAbi() {
        try {
            String[] abis = Build.SUPPORTED_ABIS;
            if (abis != null && abis.length > 0) return abis[0];
        } catch (Throwable ignored) {}
        return "armeabi-v7a";
    }

    /** Имя папки ассетов с бинарниками под ABI (с фолбэком на 32-бит arm). */
    private String abiAssetDir() {
        String abi = primaryAbi();
        if (abi.startsWith("arm64")) return "arm64-v8a";
        if (abi.startsWith("armeabi") || abi.equals("armeabi-v7a")) return "armeabi-v7a";
        if (abi.equals("x86_64")) return "x86_64";
        if (abi.equals("x86")) return "x86";
        return "armeabi-v7a";
    }

    /**
     * Готовит нативный бинарник (aapt/aapt2/zipalign) под текущий ABI: копирует
     * из ассетов в <files>/tools/bin и делает исполняемым. Возвращает File или
     * бросает исключение с понятным сообщением.
     */
    public File prepareBinary(String name) throws Exception {
        String abiDir = abiAssetDir();
        File out = new File(binDir, name);
        if (!out.exists() || out.length() == 0) {
            String asset = ASSET_ROOT + "/bin/" + abiDir + "/" + name;
            log.info("Распаковка бинарника: " + asset + " → " + out.getName());
            copyAsset(asset, out);
        }
        makeExecutable(out);
        if (!out.canExecute()) {
            log.warn("Бинарник " + name + " не помечен исполняемым (canExecute=false). "
                    + "На Android 10+ возможен запрет запуска из data-каталога.");
        }
        return out;
    }

    /** chmod 700 через API и через exec, максимально совместимо. */
    private void makeExecutable(File f) {
        try { f.setReadable(true, false); } catch (Throwable ignored) {}
        try { f.setExecutable(true, false); } catch (Throwable ignored) {}
        try {
            Runtime.getRuntime().exec(new String[]{"chmod", "700", f.getAbsolutePath()}).waitFor();
        } catch (Throwable ignored) {}
    }

    // ──────────────────────────────────────────────── jar-движки (dex)
    private DexClassLoader loadJar(String jarName) throws Exception {
        File jar = new File(toolsDir, jarName);
        if (!jar.exists() || jar.length() == 0) {
            log.info("Распаковка движка: " + jarName + " (" + "из ассетов)");
            copyAsset(ASSET_ROOT + "/jars/" + jarName, jar);
        }
        File optDir = new File(toolsDir, "dex-opt");
        optDir.mkdirs();
        return new DexClassLoader(
                jar.getAbsolutePath(),
                optDir.getAbsolutePath(),
                binDir.getAbsolutePath(),
                Tools.class.getClassLoader());
    }

    public synchronized ClassLoader apktool() throws Exception {
        if (apktoolCl == null) apktoolCl = loadJar("apktool.jar");
        return apktoolCl;
    }

    public synchronized ClassLoader jadx() throws Exception {
        if (jadxCl == null) jadxCl = loadJar("jadx.jar");
        return jadxCl;
    }

    public synchronized ClassLoader apksig() throws Exception {
        if (apksigCl == null) apksigCl = loadJar("apksig.jar");
        return apksigCl;
    }

    // ──────────────────────────────────────────────── ключ подписи
    /** Распаковывает testkey.pk8 / testkey.x509.pem в <files>/tools. */
    public File[] signingKey() throws Exception {
        File pk8 = new File(toolsDir, "testkey.pk8");
        File pem = new File(toolsDir, "testkey.x509.pem");
        if (!pk8.exists()) copyAsset(ASSET_ROOT + "/testkey.pk8", pk8);
        if (!pem.exists()) copyAsset(ASSET_ROOT + "/testkey.x509.pem", pem);
        return new File[]{ pk8, pem };
    }

    // ──────────────────────────────────────────────── утилиты
    private void copyAsset(String assetPath, File out) throws Exception {
        out.getParentFile().mkdirs();
        InputStream in = ctx.getAssets().open(assetPath);
        OutputStream os = new FileOutputStream(out);
        byte[] buf = new byte[65536];
        int r;
        while ((r = in.read(buf)) != -1) os.write(buf, 0, r);
        os.close();
        in.close();
    }

    /** Рекурсивно удалить каталог/файл. */
    public static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] ch = f.listFiles();
            if (ch != null) for (File c : ch) deleteRecursive(c);
        }
        f.delete();
    }
}
