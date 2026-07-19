package com.apkstudio.tool;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Пайплайны декомпиляции и пересборки APK.
 *
 * Правильный ПОРЯДОК шагов — ключ к сборке без ошибок.
 * Движки уже вложены в assets/engines в формате DEX:
 *   baksmali_dex.jar -> org.jf.baksmali.Main   (DEX -> smali)
 *   smali_dex.jar    -> org.jf.smali.Main      (smali -> DEX)
 *   jadx_dex.jar     -> jadx.cli.JadxCLI       (APK -> Java, чтение)
 *   apksig_dex.jar   -> com.android.apksig.ApkSigner (подпись)
 */
public class Pipeline {

    private final Env env;
    private final Log log;

    public Pipeline(Env env, Log log) {
        this.env = env;
        this.log = log;
    }

    // ---------- 1. APK -> smali + ресурсы (декомпиляция для правки) ----------
    public File decodeToSmali(File apk) throws Exception {
        String name = stripExt(apk.getName());
        File out = new File(env.work, name + "_smali");
        cleanDir(out);
        log.line("== APK -> smali ==");

        // Шаг 1: извлечь содержимое APK (ресурсы, manifest, dex)
        File resDir = new File(out, "res_raw");
        unzip(apk, resDir);
        log.line("1) APK распакован -> res_raw/");

        // Шаг 2: каждый classes*.dex -> smali/ через baksmali
        File smaliRoot = new File(out, "smali");
        smaliRoot.mkdirs();
        int idx = 0;
        for (File dex : listDex(resDir)) {
            File dst = idx == 0 ? smaliRoot : new File(out, "smali_classes" + (idx + 1));
            dst.mkdirs();
            log.line("2) baksmali " + dex.getName() + " -> " + dst.getName());
            engine("baksmali_dex.jar").runMain(
                    "org.jf.baksmali.Main",
                    new String[]{"disassemble", dex.getAbsolutePath(),
                            "-o", dst.getAbsolutePath()});
            idx++;
        }
        log.line("Готово. Правьте .smali в: " + out.getAbsolutePath());
        log.line("Затем: 'smali -> APK' (путь = эта папка).");
        return out;
    }

    // ---------- 2. APK -> Java (ТОЛЬКО чтение, jadx) ----------
    public File decodeToJava(File apk) throws Exception {
        String name = stripExt(apk.getName());
        File out = new File(env.work, name + "_java");
        cleanDir(out);
        log.line("== APK -> Java (только для чтения!) ==");
        log.line("Этот Java обычно НЕ компилируется обратно. Для сборки правьте smali.");
        engine("jadx_dex.jar").runMain(
                "jadx.cli.JadxCLI",
                new String[]{"-d", out.getAbsolutePath(), apk.getAbsolutePath()});
        log.line("Готово. Java для чтения: " + out.getAbsolutePath());
        return out;
    }

    // ---------- 3. smali -> APK (пересборка + выравнивание + подпись) ----------
    public File buildFromSmali(File smaliProjectDir) throws Exception {
        log.line("== smali -> APK ==");
        File resRaw = new File(smaliProjectDir, "res_raw");
        if (!resRaw.isDirectory())
            throw new IllegalStateException("Нет res_raw/ в " + smaliProjectDir
                    + " — укажите папку проекта *_smali.");

        // Шаг 1: собрать все smali-папки в classes.dex (+ classes2.dex ...)
        log.line("1) smali -> classes*.dex");
        assembleSmali(smaliProjectDir, resRaw);

        // Шаг 2: удалить старую подпись и упаковать новый APK (zip)
        File unsigned = new File(env.work, "built_unsigned.apk");
        File meta = new File(resRaw, "META-INF");
        if (meta.exists()) deleteRec(meta);
        log.line("2) упаковка APK (без старого META-INF)");
        zipDir(resRaw, unsigned);

        // Шаг 3: zipalign (если бинарник есть)
        File aligned = new File(env.work, "built_aligned.apk");
        File zipalign = new File(env.bin, "zipalign");
        File toSign = unsigned;
        if (zipalign.exists()) {
            log.line("3) zipalign 4");
            int rc = exec(new String[]{zipalign.getAbsolutePath(), "-f", "4",
                    unsigned.getAbsolutePath(), aligned.getAbsolutePath()});
            if (rc == 0 && aligned.length() > 0) toSign = aligned;
            else log.line("   zipalign не отработал — подпишу без него");
        } else {
            log.line("3) zipalign пропущен (нет бинарника)");
        }

        // Шаг 4: подпись через apksig (v1+v2)
        File signed = new File(env.work, "built_signed.apk");
        log.line("4) подпись APK (testkey)");
        Signer.sign(env,
                toSign,
                signed,
                new File(env.engines, "testkey.pk8"),
                new File(env.engines, "testkey.x509.pem"));
        log.line("ГОТОВО: " + signed.getAbsolutePath());
        return signed;
    }

    // ---------- 4. Скомпилированные .class/.jar -> DEX -> APK ----------
    // ВАЖНО: на Android нет javac. Этот режим принимает УЖЕ скомпилированные
    // классы (напр. собранные в AIDE) в виде jar и кладёт их в существующий APK.
    public File buildFromClasses(File classesJar, File baseApk) throws Exception {
        log.line("== .class/.jar -> DEX -> APK ==");
        if (!classesJar.exists())
            throw new IllegalStateException("Нет jar с классами: " + classesJar);
        if (!baseApk.exists())
            throw new IllegalStateException("Нужен базовый APK (оболочка ресурсов)");

        // Шаг 1: d8 компилирует .class/.jar в classes.dex
        File dexOut = new File(env.work, "d8out");
        cleanDir(dexOut);
        log.line("1) d8: классы -> dex");
        engine("r8_dex.jar").runMain(
                "com.android.tools.r8.D8",
                new String[]{"--min-api", "24",
                        "--output", dexOut.getAbsolutePath(),
                        classesJar.getAbsolutePath()});

        // Шаг 2: распаковать базовый APK и заменить/добавить classes.dex
        File resRaw = new File(env.work, "classes_build_raw");
        cleanDir(resRaw);
        unzip(baseApk, resRaw);
        File[] dexes = dexOut.listFiles();
        if (dexes != null) for (File d : dexes)
            if (d.getName().endsWith(".dex")) {
                copyFile(d, new File(resRaw, d.getName()));
            }
        File meta = new File(resRaw, "META-INF");
        if (meta.exists()) deleteRec(meta);

        // Шаг 3: zip + подпись
        File unsigned = new File(env.work, "built_unsigned.apk");
        log.line("2) упаковка APK");
        zipDir(resRaw, unsigned);
        File signed = new File(env.work, "built_signed.apk");
        log.line("3) подпись");
        Signer.sign(env, unsigned, signed,
                new File(env.engines, "testkey.pk8"),
                new File(env.engines, "testkey.x509.pem"));
        log.line("ГОТОВО: " + signed.getAbsolutePath());
        return signed;
    }

    private static void copyFile(File a, File b) throws Exception {
        try (InputStream in = new FileInputStream(a);
             OutputStream o = new FileOutputStream(b)) { copy(in, o); }
    }

    // ================= вспомогательные =================

    /** Собирает smali/, smali_classes2/, ... в classes.dex, classes2.dex ... */
    private void assembleSmali(File projectDir, File resRaw) throws Exception {
        // основной smali/
        File main = new File(projectDir, "smali");
        if (main.isDirectory()) {
            File dex = new File(resRaw, "classes.dex");
            engine("smali_dex.jar").runMain("org.jf.smali.Main",
                    new String[]{"assemble", main.getAbsolutePath(),
                            "-o", dex.getAbsolutePath()});
        }
        // дополнительные smali_classesN/
        File[] all = projectDir.listFiles();
        if (all != null) for (File d : all) {
            String n = d.getName();
            if (d.isDirectory() && n.matches("smali_classes\\d+")) {
                String num = n.replace("smali_classes", "");
                File dex = new File(resRaw, "classes" + num + ".dex");
                engine("smali_dex.jar").runMain("org.jf.smali.Main",
                        new String[]{"assemble", d.getAbsolutePath(),
                                "-o", dex.getAbsolutePath()});
            }
        }
    }

    private Engine engine(String jarName) {
        return new Engine(new File(env.engines, jarName),
                new File(env.work, "dexcache"));
    }

    private int exec(String[] cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try (InputStream in = p.getInputStream()) {
            byte[] buf = new byte[4096];
            while (in.read(buf) > 0) { /* drain */ }
        }
        return p.waitFor();
    }

    private static String stripExt(String n) {
        int i = n.lastIndexOf('.');
        return i > 0 ? n.substring(0, i) : n;
    }

    private static void cleanDir(File d) {
        if (d.exists()) deleteRec(d);
        d.mkdirs();
    }

    private static void deleteRec(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRec(k);
        f.delete();
    }

    private static java.util.List<File> listDex(File dir) {
        java.util.List<File> r = new java.util.ArrayList<>();
        File[] fs = dir.listFiles();
        if (fs != null) for (File f : fs)
            if (f.getName().matches("classes\\d*\\.dex")) r.add(f);
        java.util.Collections.sort(r);
        return r;
    }

    private static void unzip(File zip, File dst) throws Exception {
        dst.mkdirs();
        try (ZipFile zf = new ZipFile(zip)) {
            Enumeration<? extends ZipEntry> e = zf.entries();
            while (e.hasMoreElements()) {
                ZipEntry ze = e.nextElement();
                File out = new File(dst, ze.getName());
                if (ze.isDirectory()) { out.mkdirs(); continue; }
                out.getParentFile().mkdirs();
                try (InputStream in = zf.getInputStream(ze);
                     OutputStream o = new FileOutputStream(out)) {
                    copy(in, o);
                }
            }
        }
    }

    private static void zipDir(File dir, File outZip) throws Exception {
        try (java.util.zip.ZipOutputStream zos =
                     new java.util.zip.ZipOutputStream(new FileOutputStream(outZip))) {
            zipInto(dir, dir, zos);
        }
    }

    private static void zipInto(File root, File cur, java.util.zip.ZipOutputStream zos)
            throws Exception {
        File[] fs = cur.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) { zipInto(root, f, zos); continue; }
            String rel = root.toURI().relativize(f.toURI()).getPath();
            zos.putNextEntry(new ZipEntry(rel));
            try (InputStream in = new FileInputStream(f)) { copy(in, zos); }
            zos.closeEntry();
        }
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }
}
