package com.idemobile.app;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Глобальная шина событий файловой системы.
 *
 * Порт поведения web-версии, где каждый мутирующий инструмент (fs_write,
 * fs_mkdir, archive_extract, …) вызывает Explorer.render() — благодаря чему
 * дерево проводника обновляется СРАЗУ, без ручного нажатия «Обновить».
 *
 * В Android агент работает в фоновом потоке (FsTool.execute), а проводник —
 * отдельный фрагмент. Прямой ссылки между ними нет, поэтому используем
 * лёгкую шину: FsTool после любой мутации зовёт {@link #notifyChanged()},
 * а ExplorerFragment (и любой другой заинтересованный экран) подписывается
 * через {@link #addListener}. Колбэк ВСЕГДА доставляется в UI-поток, поэтому
 * слушатель может безопасно перерисовывать вьюхи.
 */
public final class FsEvents {

    public interface Listener {
        /** Файловая система изменилась — перерисуй представление. */
        void onFsChanged();
    }

    private static final CopyOnWriteArrayList<Listener> LISTENERS =
            new CopyOnWriteArrayList<Listener>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private FsEvents() {}

    public static void addListener(Listener l) {
        if (l != null && !LISTENERS.contains(l)) LISTENERS.add(l);
    }

    public static void removeListener(Listener l) {
        if (l != null) LISTENERS.remove(l);
    }

    /**
     * Сообщить всем подписчикам, что ФС изменилась. Безопасно вызывать из
     * любого потока — доставка идёт в главный поток. Частые вызовы подряд
     * коалесцируются в один кадр (debounce), чтобы при пачке операций агента
     * не перерисовывать дерево десятки раз.
     */
    public static void notifyChanged() {
        MAIN.removeCallbacks(DISPATCH);
        MAIN.postDelayed(DISPATCH, 60);
    }

    private static final Runnable DISPATCH = new Runnable() {
        public void run() {
            for (Listener l : LISTENERS) {
                try { l.onFsChanged(); } catch (Throwable ignored) {}
            }
        }
    };
}
