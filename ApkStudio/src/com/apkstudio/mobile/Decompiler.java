package com.apkstudio.mobile;

import java.io.File;
import java.lang.reflect.Method;

/**
 * Декомпиляция APK:
 *   - APK -> Smali  - через apktool (brut.androlib.ApkDecoder), полный разбор
 *     ресурсов (AndroidManifest.xml, res/, assets/) и dex -> smali/. Результат
 *     готов к обратной сборке apktool-ом.
 *   - APK -> Java   - через jadx (jadx.api.JadxDecompiler), dex -> читаемый Java
 *     плюс ресурсы. Java нужна только для чтения/правок; собирается такой проект
 *     обратно тоже через smali-скелет (см. Builder), поэтому мы дополнительно
 *     кладём рядом smali-разбор - иначе из "чистой Java" рабочий APK не собрать.
 *
 * Важно: используем публичные классы apktool (ApkDecoder), а НЕ brut.apktool.Main,
 * потому что Main вызывает System.exit(), а на Android 12+ подмена SecurityManager
 * для перехвата exit ненадёжна (может бросать UnsupportedOperationException).
 * ApkDecoder.decode() не завершает процесс.
 */
public class Decompiler {

    private final Tools tools;
    private final Logger log;

    public Decompiler(Tools tools, Logger log) {
        this.tools = tools;
        this.log = log;
    }

    /**
     * APK -> Smali. Раскладывает по "мировому стандарту" apktool:
     *   outDir/
     *     AndroidManifest.xml
     *     apktool.yml          (метаданные: версии SDK, framework - важны для сборки)
     *     res/                 (декодированные ресурсы)
     *     smali/               (код; smali_classes2..N для multidex)
     *     assets/  lib/  unknown/  original/
     */
    public void apkToSmali(File apk, File outDir) throws Exception {
        log.head("APK -> SMALI  (apktool 2.7.0)");
        log.info("Входной APK:  " + apk.getAbsolutePath() + "  (" + apk.length() / 1024 + " КБ)");
        log.info("Каталог вывода: " + outDir.getAbsolutePath());
        Tools.deleteRecursive(outDir);
        outDir.getParentFile().mkdirs();

        ClassLoader cl = tools.apktool();
        Class<?> decCls = cl.loadClass("brut.androlib.ApkDecoder");
        Object dec = decCls.getConstructor().newInstance();

        // Каталог framework внутри files (apktool кэширует framework-ресурсы туда).
        File frameworkDir = new File(tools.workDir(), "framework");
        frameworkDir.mkdirs();

        invoke(decCls, dec, "setApkFile", new Class[]{ File.class }, apk);
        invoke(decCls, dec, "setOutDir", new Class[]{ File.class }, outDir);
        invoke(decCls, dec, "setForceDelete", new Class[]{ boolean.class }, Boolean.TRUE);
        tryInvoke(decCls, dec, "setFrameworkDir", new Class[]{ String.class }, frameworkDir.getAbsolutePath());

        log.cmd("apktool d -f -o " + outDir.getName() + " " + apk.getName());
        try {
            decCls.getMethod("decode").invoke(dec);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw asException(e.getCause() != null ? e.getCause() : e);
        } finally {
            try { decCls.getMethod("close").invoke(dec); } catch (Throwable ignored) {}
        }

        if (!outDir.exists() || !new File(outDir, "AndroidManifest.xml").exists()) {
            throw new Exception("apktool не создал ожидаемую структуру (нет AndroidManifest.xml). "
                    + "Смотрите ошибки выше.");
        }
        log.ok("Готово. Структура smali-проекта в: " + outDir.getName());
        listTop(outDir);
    }

    /**
     * APK -> Java (jadx). Кладёт в outDir/ подпапки sources/ (Java) и resources/.
     * Дополнительно рядом создаём outDir/_buildable_smali/ через apktool - это тот
     * проект, который реально собирается обратно (Java-исходники jadx - только
     * для чтения и правок, компилятор из них рабочий APK не гарантирует).
     */
    public void apkToJava(File apk, File outDir) throws Exception {
        log.head("APK -> JAVA  (jadx)");
        log.info("Входной APK:  " + apk.getAbsolutePath() + "  (" + apk.length() / 1024 + " КБ)");
        log.info("Каталог вывода: " + outDir.getAbsolutePath());
        Tools.deleteRecursive(outDir);
        outDir.mkdirs();

        ClassLoader cl = tools.jadx();
        // Программный API jadx (не JadxCLI.main - тот делает System.exit).
        Class<?> argsCls = cl.loadClass("jadx.api.JadxArgs");
        Object jargs = argsCls.newInstance();

        Object inputList = argsCls.getMethod("getInputFiles").invoke(jargs);
        ((java.util.List) inputList).add(apk);
        argsCls.getMethod("setOutDir", File.class).invoke(jargs, outDir);
        tryInvoke(argsCls, jargs, "setShowInconsistentCode", new Class[]{ boolean.class }, Boolean.TRUE);
        tryInvoke(argsCls, jargs, "setThreadsCount", new Class[]{ int.class }, Integer.valueOf(1));
        tryInvoke(argsCls, jargs, "setSkipResources", new Class[]{ boolean.class }, Boolean.FALSE);

        Class<?> decCls = cl.loadClass("jadx.api.JadxDecompiler");
        Object dec = decCls.getConstructor(argsCls).newInstance(jargs);
        log.cmd("jadx --show-bad-code -d " + outDir.getName() + " " + apk.getName());
        try {
            decCls.getMethod("load").invoke(dec);
            decCls.getMethod("save").invoke(dec);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw asException(e.getCause() != null ? e.getCause() : e);
        } finally {
            try { decCls.getMethod("close").invoke(dec); } catch (Throwable ignored) {}
        }
        log.ok("Java-исходники сохранены в: " + outDir.getName() + "/sources");

        // Параллельно готовим собираемый smali-скелет (для Java -> APK).
        File smaliProj = new File(outDir, "_buildable_smali");
        log.info("Готовлю собираемый smali-скелет (для обратной сборки) -> " + smaliProj.getName());
        try {
            apkToSmali(apk, smaliProj);
            log.ok("Собираемый проект: " + smaliProj.getName()
                    + "  (правьте Java для чтения, а для сборки - smali здесь)");
        } catch (Throwable t) {
            log.warn("Не удалось подготовить smali-скелет: " + t.getMessage());
        }
        listTop(outDir);
    }

    // ------------------------------------------------ helpers

    private void invoke(Class<?> c, Object obj, String name, Class[] sig, Object... a) throws Exception {
        Method m = c.getMethod(name, sig);
        m.invoke(obj, a);
    }

    private void tryInvoke(Class<?> c, Object obj, String name, Class[] sig, Object... a) {
        try { c.getMethod(name, sig).invoke(obj, a); } catch (Throwable ignored) {}
    }

    private Exception asException(Throwable t) {
        log.exception("Ошибка декомпиляции", t);
        return (t instanceof Exception) ? (Exception) t : new Exception(t);
    }

    private void listTop(File dir) {
        File[] ch = dir.listFiles();
        if (ch == null) return;
        StringBuilder sb = new StringBuilder("Содержимое: ");
        for (File c : ch) sb.append(c.getName()).append(c.isDirectory() ? "/ " : " ");
        log.info(sb.toString());
    }
}
