package com.apkstudio.mobile;

import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Collections;
import java.util.List;

/**
 * Сборка проекта обратно в устанавливаемый APK.
 *
 * Конвейер (мировой стандарт для on-device):
 *   1) apktool build - компиляция ресурсов (нативный aapt2 под ABI устройства,
 *      путь задаётся в BuildOptions.aaptPath) + упаковка smali -> dex ->
 *      неподписанный APK;
 *   2) zipalign       - выравнивание по 4 байта (нативный бинарник);
 *   3) apksig         - подпись v1+v2+v3 (com.android.apksig.ApkSigner),
 *      иначе Android откажется устанавливать.
 *
 * Сборку выполняем через brut.androlib.Androlib.build(dir, outApk) с
 * BuildOptions - публичный API apktool без System.exit (в отличие от Main.main).
 *
 * Java -> APK и Smali -> APK используют один и тот же путь: реально собирается
 * smali-проект. Если выбрана папка с jadx-выводом, берём вложенный
 * _buildable_smali (его создаёт Decompiler.apkToJava).
 */
public class Builder {

    private final Tools tools;
    private final Logger log;

    public Builder(Tools tools, Logger log) {
        this.tools = tools;
        this.log = log;
    }

    /** Smali-проект (папка с apktool.yml) -> подписанный APK. */
    public File smaliToApk(File projectDir, File outApk) throws Exception {
        log.head("SMALI -> APK");
        File proj = resolveSmaliProject(projectDir);
        log.info("Проект (smali):  " + proj.getAbsolutePath());
        if (!new File(proj, "apktool.yml").exists()) {
            log.warn("В папке нет apktool.yml - это может быть не apktool-проект. "
                    + "Ожидается структура: AndroidManifest.xml, apktool.yml, res/, smali/.");
        }
        return buildAndSign(proj, outApk);
    }

    /**
     * Java -> APK. jadx-вывод сам по себе не собирается компилятором в рабочий
     * APK, поэтому используем подготовленный при декомпиляции _buildable_smali.
     */
    public File javaToApk(File projectDir, File outApk) throws Exception {
        log.head("JAVA -> APK");
        File smali = new File(projectDir, "_buildable_smali");
        if (smali.exists() && new File(smali, "apktool.yml").exists()) {
            log.info("Использую собираемый smali-скелет: " + smali.getName()
                    + "  (правки Java-логики переносите в соответствующий smali)");
            return buildAndSign(smali, outApk);
        }
        File proj = resolveSmaliProject(projectDir);
        if (new File(proj, "apktool.yml").exists()) {
            log.warn("Не найден _buildable_smali; собираю как smali-проект: " + proj.getName());
            return buildAndSign(proj, outApk);
        }
        throw new Exception("Не найден собираемый проект. Для Java->APK выберите папку, "
                + "полученную кнопкой "APK -> Java" (в ней есть _buildable_smali), "
                + "или используйте "Smali -> APK".");
    }

    // -------------------------------------------- общий конвейер

    private File buildAndSign(File proj, File outApk) throws Exception {
        outApk.getParentFile().mkdirs();
        File unsigned = new File(outApk.getParentFile(), stripExt(outApk.getName()) + "-unsigned.apk");
        File aligned = new File(outApk.getParentFile(), stripExt(outApk.getName()) + "-aligned.apk");
        if (unsigned.exists()) unsigned.delete();
        if (aligned.exists()) aligned.delete();

        // 1) apktool build с нативным aapt2
        File aapt2 = tools.prepareBinary("aapt2");
        ClassLoader cl = tools.apktool();

        Class<?> boCls = cl.loadClass("brut.androlib.options.BuildOptions");
        Object bo = boCls.getConstructor().newInstance();
        setField(boCls, bo, "useAapt2", Boolean.TRUE);
        setField(boCls, bo, "aaptPath", aapt2.getAbsolutePath());
        setField(boCls, bo, "aaptVersion", Integer.valueOf(2));
        setField(boCls, bo, "forceBuildAll", Boolean.TRUE);
        File frameworkDir = new File(tools.workDir(), "framework");
        frameworkDir.mkdirs();
        setField(boCls, bo, "frameworkFolderLocation", frameworkDir.getAbsolutePath());

        Class<?> androlibCls = cl.loadClass("brut.androlib.Androlib");
        Object androlib = androlibCls.getConstructor(boCls).newInstance(bo);

        log.cmd("apktool b --use-aapt2 -a " + aapt2.getName() + " -o " + unsigned.getName() + " " + proj.getName());
        log.info("aapt2: " + aapt2.getAbsolutePath() + "  (executable=" + aapt2.canExecute() + ")");
        try {
            androlibCls.getMethod("build", File.class, File.class).invoke(androlib, proj, unsigned);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable c = e.getCause() != null ? e.getCause() : e;
            log.exception("apktool build не удался", c);
            log.warn("Частая причина на Android 10+: система запрещает запуск нативного aapt2 "
                    + "из data-каталога. Смотрите текст ошибки выше - там точная причина.");
            throw toEx(c);
        }
        if (!unsigned.exists()) throw new Exception("apktool не создал APK: " + unsigned.getName());
        log.ok("Неподписанный APK собран: " + unsigned.getName() + "  (" + unsigned.length() / 1024 + " КБ)");

        // 2) zipalign
        File toSign = unsigned;
        try {
            File zipalign = tools.prepareBinary("zipalign");
            String[] za = { zipalign.getAbsolutePath(), "-f", "4", unsigned.getAbsolutePath(), aligned.getAbsolutePath() };
            log.cmd(join(za));
            int rc = exec(za);
            if (rc == 0 && aligned.exists()) { toSign = aligned; log.ok("zipalign OK"); }
            else log.warn("zipalign вернул код " + rc + " - продолжаю без выравнивания.");
        } catch (Throwable t) {
            log.warn("zipalign недоступен (" + t.getMessage() + ") - продолжаю без него.");
        }

        // 3) подпись apksig (v1+v2+v3)
        if (outApk.exists()) outApk.delete();
        sign(toSign, outApk);
        log.ok("Подписанный APK готов: " + outApk.getAbsolutePath() + "  (" + outApk.length() / 1024 + " КБ)");

        if (!unsigned.equals(outApk)) unsigned.delete();
        if (aligned.exists() && !aligned.equals(outApk)) aligned.delete();
        return outApk;
    }

    /** Подпись APK через com.android.apksig.ApkSigner (v1+v2+v3). */
    private void sign(File in, File out) throws Exception {
        log.info("Подпись APK (apksig, v1+v2+v3)...");
        File[] keys = tools.signingKey();
        PrivateKey key = loadPk8(keys[0]);
        X509Certificate cert = loadCert(keys[1]);

        ClassLoader cl = tools.apksig();
        Class<?> signerCls = cl.loadClass("com.android.apksig.ApkSigner");
        Class<?> sbCls = cl.loadClass("com.android.apksig.ApkSigner$Builder");
        Class<?> scbCls = cl.loadClass("com.android.apksig.ApkSigner$SignerConfig$Builder");

        // SignerConfig.Builder(name, privateKey, List<X509Certificate>).build()
        Constructor<?> scbCtor = scbCls.getConstructor(String.class, PrivateKey.class, List.class);
        Object scb = scbCtor.newInstance("apkstudio", key, Collections.singletonList(cert));
        Object signerConfig = scbCls.getMethod("build").invoke(scb);

        // ApkSigner.Builder(List<SignerConfig>)
        List<Object> configs = Collections.singletonList(signerConfig);
        Constructor<?> sbCtor = sbCls.getConstructor(List.class);
        Object sb = sbCtor.newInstance(configs);
        sbCls.getMethod("setInputApk", File.class).invoke(sb, in);
        sbCls.getMethod("setOutputApk", File.class).invoke(sb, out);
        try { sbCls.getMethod("setV1SigningEnabled", boolean.class).invoke(sb, true); } catch (Throwable ignored) {}
        try { sbCls.getMethod("setV2SigningEnabled", boolean.class).invoke(sb, true); } catch (Throwable ignored) {}
        try { sbCls.getMethod("setV3SigningEnabled", boolean.class).invoke(sb, true); } catch (Throwable ignored) {}

        Object signer = sbCls.getMethod("build").invoke(sb);
        signerCls.getMethod("sign").invoke(signer);
    }

    private PrivateKey loadPk8(File pk8) throws Exception {
        byte[] data = readAll(pk8);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(data);
        try { return KeyFactory.getInstance("RSA").generatePrivate(spec); }
        catch (Exception e) {
            try { return KeyFactory.getInstance("EC").generatePrivate(spec); }
            catch (Exception e2) { return KeyFactory.getInstance("DSA").generatePrivate(spec); }
        }
    }

    private X509Certificate loadCert(File pem) throws Exception {
        FileInputStream in = new FileInputStream(pem);
        try {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
        } finally { in.close(); }
    }

    // -------------------------------------------- helpers

    private void setField(Class<?> c, Object obj, String name, Object value) {
        try {
            Field f = c.getField(name);
            f.setAccessible(true);
            f.set(obj, value);
        } catch (Throwable t) {
            log.warn("Не удалось задать опцию " + name + ": " + t.getMessage());
        }
    }

    private File resolveSmaliProject(File dir) {
        if (new File(dir, "apktool.yml").exists()) return dir;
        File[] ch = dir.listFiles();
        if (ch != null) {
            for (File c : ch) if (c.isDirectory() && new File(c, "apktool.yml").exists()) return c;
        }
        return dir;
    }

    private int exec(String[] cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(p.getInputStream()));
        String ln;
        while ((ln = br.readLine()) != null) log.info("  " + ln);
        br.close();
        return p.waitFor();
    }

    private Exception toEx(Throwable t) { return (t instanceof Exception) ? (Exception) t : new Exception(t); }

    private static byte[] readAll(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192]; int r;
        while ((r = in.read(buf)) != -1) bo.write(buf, 0, r);
        in.close();
        return bo.toByteArray();
    }

    private static String stripExt(String name) {
        int i = name.lastIndexOf('.');
        return i > 0 ? name.substring(0, i) : name;
    }

    private static String join(String[] a) {
        StringBuilder sb = new StringBuilder();
        for (String s : a) sb.append(s).append(' ');
        return sb.toString().trim();
    }
}
