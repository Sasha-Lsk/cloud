package com.apkstudio;

import android.content.Context;
import android.os.Build;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Core engine: extracts bundled tools from assets, then decompiles / rebuilds
 * APKs by invoking apktool.jar (smali/xml), jadx (java) and the native
 * aapt2 / zipalign binaries via Runtime.exec on the device.
 *
 * All heavy jars are run through the on-device Dalvik/ART VM (dalvikvm) which
 * is available on every Android 5.0 - 13 device.
 */
public class Engine {

    private final Context ctx;
    private final Log log;
    private final File toolDir;        // <files>/tools
    private final File abiBinDir;      // <files>/tools/bin/<abi>

    public Engine(Context ctx, Log log) {
        this.ctx = ctx;
        this.log = log;
        this.toolDir = new File(ctx.getFilesDir(), "tools");
        this.abiBinDir = new File(toolDir, "bin/" + primaryAbi());
    }

    private static String primaryAbi() {
        String[] abis = Build.SUPPORTED_ABIS;
        return (abis != null && abis.length > 0) ? abis[0] : "armeabi-v7a";
    }

    private void l(String s) { if (log != null) log.line(s); }

    // ---------------------------------------------------------------- setup

    /** Copies every asset tool to app storage once and makes binaries executable. */
    public void install() throws Exception {
        Cfg.ensureDirs();
        toolDir.mkdirs();
        File stamp = new File(toolDir, ".installed_v1");
        if (stamp.exists()) return;

        l("Установка движка (однократно)...");
        copyAssetDir("engine", toolDir);           // apktool.jar, jadx.jar, keys
        copyAssetDir("bin", new File(toolDir, "bin"));

        // make native binaries executable
        chmodTree(new File(toolDir, "bin"));

        // writable framework dir for apktool (holds 1.apk framework)
        new File(toolDir, "framework").mkdirs();

        stamp.createNewFile();
        l("Движок установлен: " + toolDir.getAbsolutePath());
    }

    private void chmodTree(File dir) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) chmodTree(f);
            else { f.setExecutable(true, false); f.setReadable(true, false); }
        }
    }

    private void copyAssetDir(String assetPath, File dst) throws Exception {
        String[] items = ctx.getAssets().list(assetPath);
        if (items == null || items.length == 0) {           // it's a file
            copyAssetFile(assetPath, new File(dst, new File(assetPath).getName()));
            return;
        }
        dst.mkdirs();
        for (String it : items) {
            String child = assetPath + "/" + it;
            String[] sub = ctx.getAssets().list(child);
            if (sub != null && sub.length > 0) {
                copyAssetDir(child, new File(dst, it));
            } else {
                copyAssetFile(child, new File(dst, it));
            }
        }
    }

    private void copyAssetFile(String assetPath, File out) throws Exception {
        out.getParentFile().mkdirs();
        InputStream in = ctx.getAssets().open(assetPath);
        OutputStream os = new FileOutputStream(out);
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        os.close();
        in.close();
    }

    // ---------------------------------------------------------------- tools

    private File apktoolJar()   { return new File(toolDir, "apktool.jar"); }
    private File jadxJar()      { return new File(toolDir, "jadx.jar"); }
    private File aapt2()        { return new File(abiBinDir, "aapt2"); }
    private File aapt()         { return new File(abiBinDir, "aapt"); }
    private File zipalign()     { return new File(abiBinDir, "zipalign"); }
    private File keyPk8()       { return new File(toolDir, "testkey.pk8"); }
    private File keyPem()       { return new File(toolDir, "testkey.x509.pem"); }
    private File apksigJar()    { return new File(toolDir, "apksig.jar"); }

    // ---------------------------------------------------------------- exec

    private int run(List<String> cmd, File workdir) throws Exception {
        l("$ " + join(cmd));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (workdir != null) pb.directory(workdir);
        pb.redirectErrorStream(true);
        // dalvikvm needs a writable, exec-friendly tmp and dex cache
        File dexopt = new File(ctx.getCacheDir(), "dalvik-cache");
        dexopt.mkdirs();
        pb.environment().put("TMPDIR", ctx.getCacheDir().getAbsolutePath());
        pb.environment().put("HOME", ctx.getFilesDir().getAbsolutePath());
        pb.environment().put("ANDROID_DATA", ctx.getCacheDir().getAbsolutePath());
        Process p = pb.start();
        BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
        String ln;
        while ((ln = br.readLine()) != null) l(ln);
        return p.waitFor();
    }

    private static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) sb.append(s).append(' ');
        return sb.toString().trim();
    }

    /** Builds a "dalvikvm -cp jar mainClass ..." command. */
    private List<String> vm(File jar, String mainClass) {
        List<String> c = new ArrayList<>();
        File dvm = new File("/system/bin/dalvikvm");
        c.add(dvm.exists() ? dvm.getAbsolutePath() : "dalvikvm");
        c.add("-Xmx512m");
        c.add("-cp");
        c.add(jar.getAbsolutePath());
        c.add(mainClass);
        return c;
    }

    // ---------------------------------------------------------------- decompile

    /** APK -> smali + resources (xml). Output: input_apk/<name>/ */
    public File decompileSmali(File apk) throws Exception {
        install();
        String name = baseName(apk);
        File out = new File(Cfg.INPUT, name);
        rmrf(out);
        l("== Декомпиляция в SMALI + XML: " + apk.getName() + " ==");

        List<String> c = vm(apktoolJar(), "brut.apktool.Main");
        c.add("d");
        c.add("-f");                                   // force
        c.add("--frame-path"); c.add(new File(toolDir, "framework").getAbsolutePath());
        c.add("-o"); c.add(out.getAbsolutePath());
        c.add(apk.getAbsolutePath());
        int rc = run(c, toolDir);
        if (rc != 0) throw new Exception("apktool d вернул код " + rc);
        l("Готово: " + out.getAbsolutePath());
        return out;
    }

    /** APK -> java sources via jadx. Output: input_apk/<name>_java/ */
    public File decompileJava(File apk) throws Exception {
        install();
        String name = baseName(apk);
        File out = new File(Cfg.INPUT, name + "_java");
        rmrf(out);
        l("== Декомпиляция в JAVA (jadx): " + apk.getName() + " ==");

        List<String> c = vm(jadxJar(), "jadx.cli.JadxCLI");
        c.add("-d"); c.add(out.getAbsolutePath());
        c.add("--no-res");        // resources are handled by smali mode; keep java clean
        c.add("-r");
        c.add(apk.getAbsolutePath());
        int rc = run(c, toolDir);
        // jadx returns non-zero on partial errors but still writes sources
        File src = new File(out, "sources");
        if (!src.exists()) throw new Exception("jadx не создал исходники (код " + rc + ")");
        l("Готово: " + out.getAbsolutePath());
        return out;
    }

    // ------------------------------------------------------------- rebuild

    /**
     * smali/xml project -> signed, aligned APK in output_apk/.
     * Also normalises targetSdkVersion so old apps don't trip the
     * "designed for an older version" system dialog on Android 8-12.
     */
    public File build(File projectDir, int forceTargetSdk) throws Exception {
        install();
        String name = projectDir.getName();
        l("== Сборка проекта: " + name + " ==");

        Patcher patcher = new Patcher(log);
        patcher.fixDollarResources(projectDir);   // совместимость с aapt2
        if (forceTargetSdk > 0) {
            patcher.fixTargetSdk(projectDir, forceTargetSdk);
        }

        File unsigned = new File(ctx.getCacheDir(), name + "-unsigned.apk");
        rmrf(unsigned);

        // Try aapt2 first (modern, matches most Play-Market apps); if it fails,
        // fall back to the classic aapt which is more forgiving for old apps.
        int rc = runBuild(projectDir, unsigned, true);
        if (rc != 0 || !unsigned.exists()) {
            l("aapt2 не справился, пробую классический aapt...");
            rmrf(unsigned);
            rc = runBuild(projectDir, unsigned, false);
        }
        if (rc != 0 || !unsigned.exists())
            throw new Exception("apktool b вернул код " + rc);

        // zipalign
        File aligned = new File(ctx.getCacheDir(), name + "-aligned.apk");
        rmrf(aligned);
        List<String> za = new ArrayList<>();
        za.add(zipalign().getAbsolutePath());
        za.add("-f"); za.add("-p"); za.add("4");
        za.add(unsigned.getAbsolutePath());
        za.add(aligned.getAbsolutePath());
        int zrc = run(za, toolDir);
        File toSign = (zrc == 0 && aligned.exists()) ? aligned : unsigned;

        // sign (v1 + v2) with pure-java signer
        File signed = new File(Cfg.OUTPUT, name + ".apk");
        Cfg.OUTPUT.mkdirs();
        rmrf(signed);
        l("Подпись APK (v1+v2+v3)...");
        int minSdk = readMinSdk(projectDir);
        File optDir = new File(ctx.getCacheDir(), "dexopt");
        Signer.sign(toSign, signed, keyPk8(), keyPem(), apksigJar(), optDir, minSdk);

        l("Готово: " + signed.getAbsolutePath());
        return signed;
    }

    private int runBuild(File projectDir, File out, boolean useAapt2) throws Exception {
        List<String> c = vm(apktoolJar(), "brut.apktool.Main");
        c.add("b");
        if (useAapt2) {
            c.add("--use-aapt2");
            c.add("--aapt"); c.add(aapt2().getAbsolutePath());
        } else {
            c.add("--aapt"); c.add(aapt().getAbsolutePath());
        }
        c.add("--frame-path"); c.add(new File(toolDir, "framework").getAbsolutePath());
        c.add("-o"); c.add(out.getAbsolutePath());
        c.add(projectDir.getAbsolutePath());
        return run(c, toolDir);
    }

    // ------------------------------------------------------------- helpers

    /** Читает minSdkVersion из apktool.yml (для корректной схемы подписи). */
    private int readMinSdk(File projectDir) {
        try {
            File yml = new File(projectDir, "apktool.yml");
            if (!yml.exists()) return 21;
            byte[] b = new byte[(int) yml.length()];
            FileInputStream in = new FileInputStream(yml);
            int off = 0, n;
            while ((n = in.read(b, off, b.length - off)) > 0) off += n;
            in.close();
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("minSdkVersion:\\s*'?(\\d+)'?")
                    .matcher(new String(b, "UTF-8"));
            if (m.find()) return Integer.parseInt(m.group(1));
        } catch (Exception ignore) {}
        return 21;
    }

    private static String baseName(File f) {
        String n = f.getName();
        int i = n.lastIndexOf('.');
        return i > 0 ? n.substring(0, i) : n;
    }

    static void rmrf(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] cs = f.listFiles();
            if (cs != null) for (File c : cs) rmrf(c);
        }
        f.delete();
    }
}
