package com.apkstudio;

import java.io.File;
import java.io.FileOutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalises an apktool project before rebuild.
 *
 * The system dialog "This app was designed for an older version..." appears
 * when targetSdkVersion is too low. apktool stores the real sdk values in
 * apktool.yml, so we bump targetSdkVer there (aapt reads it into the manifest).
 */
public class Patcher {

    private final Log log;
    public Patcher(Log log) { this.log = log; }
    private void l(String s){ if(log!=null) log.line(s); }

    /**
     * aapt2 отвергает имена ресурсов с символом '$' (появляются при
     * декомпиляции векторных анимаций из Material). Переименовываем такие
     * файлы и все ссылки на них в xml: '$' -> '_'.
     */
    public void fixDollarResources(File projectDir) throws Exception {
        File res = new File(projectDir, "res");
        if (!res.exists()) return;
        int renamed = renameDollarFiles(res);
        if (renamed == 0) return;
        // заменить ссылки во всех xml
        replaceDollarInXml(res);
        l("Исправлено имён ресурсов с '$': " + renamed);
    }

    private int renameDollarFiles(File dir) {
        int count = 0;
        File[] fs = dir.listFiles();
        if (fs == null) return 0;
        for (File f : fs) {
            if (f.isDirectory()) { count += renameDollarFiles(f); continue; }
            if (f.getName().indexOf('$') >= 0) {
                File nf = new File(f.getParentFile(), f.getName().replace('$', '_'));
                if (f.renameTo(nf)) count++;
            }
        }
        return count;
    }

    private void replaceDollarInXml(File dir) throws Exception {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) { replaceDollarInXml(f); continue; }
            if (f.getName().toLowerCase().endsWith(".xml")) {
                String s = read(f);
                if (s.indexOf('$') >= 0) write(f, s.replace('$', '_'));
            }
        }
    }

    public void fixTargetSdk(File projectDir, int target) throws Exception {
        File yml = new File(projectDir, "apktool.yml");
        if (!yml.exists()) { l("apktool.yml не найден, пропускаю фикс SDK"); return; }

        String txt = read(yml);
        int min = extractInt(txt, "minSdkVersion");
        if (min <= 0) min = 21;

        // sdkInfo:
        //   minSdkVersion: 'NN'
        //   targetSdkVersion: 'NN'
        String newTarget = String.valueOf(target);

        if (txt.contains("targetSdkVersion")) {
            txt = txt.replaceAll(
                "targetSdkVersion:\\s*'?\\d+'?",
                "targetSdkVersion: '" + newTarget + "'");
        } else if (txt.contains("sdkInfo:")) {
            txt = txt.replaceFirst(
                "(sdkInfo:\\s*\\n)",
                "$1  targetSdkVersion: '" + newTarget + "'\n");
        }
        write(yml, txt);
        l("targetSdkVersion выставлен в " + newTarget + " (minSdk=" + min + ")");

        // Also patch a literal targetSdkVersion attribute if present in the manifest.
        File mf = new File(projectDir, "AndroidManifest.xml");
        if (mf.exists()) {
            String m = read(mf);
            if (m.contains("android:targetSdkVersion")) {
                m = m.replaceAll(
                    "android:targetSdkVersion=\"\\d+\"",
                    "android:targetSdkVersion=\"" + newTarget + "\"");
                write(mf, m);
            }
        }
    }

    private static int extractInt(String txt, String key) {
        Matcher mm = Pattern.compile(key + ":\\s*'?(\\d+)'?").matcher(txt);
        return mm.find() ? Integer.parseInt(mm.group(1)) : -1;
    }

    private static String read(File f) throws Exception {
        byte[] b = new byte[(int) f.length()];
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        int off=0,n; while((n=in.read(b,off,b.length-off))>0) off+=n; in.close();
        return new String(b, "UTF-8");
    }
    private static void write(File f, String s) throws Exception {
        FileOutputStream o = new FileOutputStream(f);
        o.write(s.getBytes("UTF-8")); o.close();
    }
}
