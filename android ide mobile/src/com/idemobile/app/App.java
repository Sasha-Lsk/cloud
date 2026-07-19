package com.idemobile.app;

import android.app.Application;
import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Класс приложения. Ставит глобальный перехватчик необработанных исключений,
 * который записывает подробный отчёт о сбое в файл, чтобы «тихий краш» был
 * диагностируемым без logcat/ПК, и чтобы пользователь мог этим файлом
 * поделиться / открыть его в любом приложении.
 *
 * Отчёты складываются в папку {@code getExternalFilesDir()/crash_reports}
 * (или во внутреннюю память, если внешняя недоступна). Каждый краш — отдельный
 * файл {@code crash_<timestamp>.txt}, старые НЕ перезатираются (хранится до
 * {@link #MAX_REPORTS} последних). Кроме того last-crash-путь запоминается в
 * SharedPreferences, чтобы при следующем запуске показать пользователю диалог.
 */
public class App extends Application {

    private static final String PREFS = "crash_prefs";
    private static final String KEY_PENDING = "pending_crash_path";
    static final String DIR_NAME = "crash_reports";
    private static final int MAX_REPORTS = 30;

    private static Context appCtx;

    /** Глобальный контекст приложения (для доступа к ресурсам, например звуку уведомления). */
    public static Context ctx() { return appCtx; }

    @Override
    public void onCreate() {
        super.onCreate();
        appCtx = getApplicationContext();

        final Thread.UncaughtExceptionHandler prev =
                Thread.getDefaultUncaughtExceptionHandler();

        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    writeCrash(t, e);
                } catch (Throwable ignored) {}
                if (prev != null) prev.uncaughtException(t, e);
            }
        });
    }

    /** Совместимость со старыми вызовами (например MainActivity.onCreate). */
    static File writeCrash(Throwable e) {
        return writeCrash(Thread.currentThread(), e);
    }

    /**
     * Собирает подробный отчёт и пишет его в отдельный файл в папке crash_reports.
     * @return файл отчёта (или null, если записать не удалось).
     */
    static synchronized File writeCrash(Thread thread, Throwable e) {
        String text = buildReport(thread, e);
        android.util.Log.e("IDEMobile", "CRASH:\n" + text);

        File dir = reportsDir();
        File out = null;
        if (dir != null) {
            try {
                String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                out = new File(dir, "crash_" + ts + ".txt");
                // Если за одну секунду два краша — не перезатираем.
                int n = 1;
                while (out.exists() && n < 100) { out = new File(dir, "crash_" + ts + "_" + (n++) + ".txt"); }
                FileWriter fw = new FileWriter(out, false);
                fw.write(text);
                fw.close();
                pruneOld(dir);
                rememberPending(out);
            } catch (Throwable ignored) { out = null; }
        }
        // Фолбэк для обратной совместимости: старое единое имя файла.
        if (out == null && appCtx != null) {
            try {
                File legacy = new File(appCtx.getFilesDir(), "idemobile_crash.txt");
                FileWriter fw = new FileWriter(legacy, false);
                fw.write(text);
                fw.close();
                rememberPending(legacy);
                out = legacy;
            } catch (Throwable ignored) {}
        }
        return out;
    }

    /** Формирует человекочитаемый отчёт: окружение + вся цепочка причин + logcat. */
    static String buildReport(Thread thread, Throwable e) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());

        pw.println("========================================");
        pw.println("  IDE Mobile — отчёт о сбое приложения");
        pw.println("========================================");
        pw.println("Время сбоя:      " + stamp);
        pw.println("Версия прилож.:  " + appVersion());
        pw.println("Поток:           " + (thread != null ? thread.getName() + " (id=" + thread.getId() + ")" : "—"));
        pw.println();
        pw.println("--- Устройство ---");
        pw.println("Производитель:   " + android.os.Build.MANUFACTURER);
        pw.println("Модель:          " + android.os.Build.MODEL);
        pw.println("Устройство:      " + android.os.Build.DEVICE);
        pw.println("Android:         " + android.os.Build.VERSION.RELEASE + " (API " + android.os.Build.VERSION.SDK_INT + ")");
        pw.println("ABI:             " + java.util.Arrays.toString(android.os.Build.SUPPORTED_ABIS));
        pw.println(memoryInfo());
        pw.println();

        pw.println("--- Причина сбоя ---");
        pw.println(shortCause(e));
        pw.println();

        pw.println("--- Цепочка причин ---");
        Throwable c = e; int depth = 0;
        while (c != null && depth < 20) {
            pw.println((depth == 0 ? "• " : "  ↳ вызвано: ") + c.getClass().getName()
                    + (c.getMessage() != null ? ": " + c.getMessage() : ""));
            c = c.getCause(); depth++;
        }
        pw.println();

        pw.println("--- Полный стек ---");
        if (e != null) e.printStackTrace(pw);
        pw.println();

        pw.println("--- Последние строки logcat ---");
        pw.println(readLogcatTail());

        pw.flush();
        return sw.toString();
    }

    private static String appVersion() {
        try {
            if (appCtx == null) return "—";
            android.content.pm.PackageInfo pi =
                    appCtx.getPackageManager().getPackageInfo(appCtx.getPackageName(), 0);
            return pi.versionName + " (code " + pi.versionCode + ")";
        } catch (Throwable t) { return "—"; }
    }

    private static String memoryInfo() {
        try {
            Runtime rt = Runtime.getRuntime();
            long used = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
            long max = rt.maxMemory() / (1024 * 1024);
            StringBuilder sb = new StringBuilder();
            sb.append("Память JVM:      ").append(used).append(" / ").append(max).append(" МБ");
            if (appCtx != null) {
                android.app.ActivityManager am = (android.app.ActivityManager)
                        appCtx.getSystemService(Context.ACTIVITY_SERVICE);
                if (am != null) {
                    android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
                    am.getMemoryInfo(mi);
                    sb.append("\nПамять системы:  свободно ").append(mi.availMem / (1024 * 1024))
                      .append(" МБ").append(mi.lowMemory ? "  [МАЛО ПАМЯТИ]" : "");
                }
            }
            return sb.toString();
        } catch (Throwable t) { return "Память:          —"; }
    }

    private static String shortCause(Throwable e) {
        if (e == null) return "неизвестно";
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String where = "";
        StackTraceElement[] st = root.getStackTrace();
        if (st != null && st.length > 0) where = "\nМесто:  " + st[0].toString();
        return root.getClass().getName()
                + (root.getMessage() != null ? ": " + root.getMessage() : "") + where;
    }

    /** Читает хвост системного логката (может быть пустым без соотв. разрешения). */
    private static String readLogcatTail() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"logcat", "-d", "-t", "200", "-v", "time"});
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line; int count = 0;
            while ((line = br.readLine()) != null && count < 400) { sb.append(line).append('\n'); count++; }
            br.close();
            String s = sb.toString().trim();
            return s.isEmpty() ? "(logcat недоступен на этом устройстве)" : s;
        } catch (Throwable t) {
            return "(logcat недоступен: " + t.getMessage() + ")";
        }
    }

    // ------------------------------------------------------------ files
    static File reportsDir() {
        try {
            if (appCtx == null) return null;
            File base = appCtx.getExternalFilesDir(null);
            if (base == null) base = appCtx.getFilesDir();
            File dir = new File(base, DIR_NAME);
            if (!dir.exists()) dir.mkdirs();
            return dir.exists() ? dir : null;
        } catch (Throwable t) { return null; }
    }

    /** Удаляет самые старые отчёты сверх лимита MAX_REPORTS. */
    private static void pruneOld(File dir) {
        try {
            File[] files = dir.listFiles();
            if (files == null || files.length <= MAX_REPORTS) return;
            java.util.Arrays.sort(files, new java.util.Comparator<File>() {
                public int compare(File a, File b) { return Long.compare(a.lastModified(), b.lastModified()); }
            });
            for (int i = 0; i < files.length - MAX_REPORTS; i++) files[i].delete();
        } catch (Throwable ignored) {}
    }

    private static void rememberPending(File f) {
        try {
            if (appCtx == null || f == null) return;
            appCtx.getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit().putString(KEY_PENDING, f.getAbsolutePath()).apply();
        } catch (Throwable ignored) {}
    }

    /** Путь к отчёту о последнем крахе, который ещё не показали пользователю (или null). */
    public static String pendingCrashPath() {
        try {
            if (appCtx == null) return null;
            String p = appCtx.getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_PENDING, null);
            if (p != null && new File(p).exists()) return p;
        } catch (Throwable ignored) {}
        return null;
    }

    /** Сбросить «ожидающий» краш (после того как пользователь его увидел). */
    public static void clearPendingCrash() {
        try {
            if (appCtx == null) return;
            appCtx.getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(KEY_PENDING).apply();
        } catch (Throwable ignored) {}
    }
}
