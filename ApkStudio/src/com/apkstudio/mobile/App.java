package com.apkstudio.mobile;

import android.app.Application;
import android.content.Context;

/**
 * Класс приложения. Хранит глобальный контекст и ставит перехватчик
 * необработанных исключений, чтобы «тихий краш» попадал хотя бы в logcat
 * (подробные логи самой сборки/декомпиляции ведёт MainActivity в окне журнала).
 */
public class App extends Application {

    private static Context appCtx;

    /** Глобальный контекст приложения. */
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
                    android.util.Log.e("ApkStudio", "CRASH in " + t.getName(), e);
                } catch (Throwable ignored) {}
                if (prev != null) prev.uncaughtException(t, e);
            }
        });
    }
}
