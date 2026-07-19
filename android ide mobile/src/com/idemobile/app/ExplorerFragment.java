package com.idemobile.app;

import android.app.AlertDialog;
import android.app.Fragment;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Вкладка «Проводник» — точная копия web-версии (explorer.js) на Android.
 *
 * Работает с той же рабочей папкой, что и ИИ-агент: getFilesDir()/workspace
 * (через ExplorerFs — тонкую обёртку над реальной ФС). Корень отображается как «/».
 *
 * Возможности (как в web):
 *   • Дерево файлов: раскрытие/сворачивание папок, иконки, размеры, подсветка.
 *   • Верхняя панель инструментов: источник, новый файл, новая папка, обновить, «…».
 *   • Меню «…» (Действия): импорт, экспорт в zip, мульти-выделение,
 *     развернуть/свернуть всё, очистить проводник.
 *   • Контекстное меню (долгое нажатие) для папки и для файла.
 *   • Режим мульти-выделения: чекбоксы + нижняя панель
 *     (выбрать всё / снять / копировать / переместить / zip / удалить).
 */
public class ExplorerFragment extends Fragment {

    private ExplorerFs fs;
    private LinearLayout tree;
    private ScrollView scroll;
    private TextView emptyEl;
    private LinearLayout selectBar;
    private TextView sbInfo;
    private TextView titleLabel;
    private TextView subtitleLabel;

    /** Раскрытые папки (виртуальные пути, начинаются с «/»). */
    private final Set<String> expanded = new HashSet<String>();
    /** Текущий одиночно-выделенный элемент (подсветка). */
    private String selected = null;

    /** Режим мульти-выделения. */
    private boolean selectMode = false;
    private final Set<String> multiSelected = new LinkedHashSet<String>();

    private final Handler ui = new Handler();

    /**
     * Подписка на глобальную шину ФС: как только агент (или любая операция)
     * изменяет файлы, проводник перерисовывается СРАЗУ, без ручного «Обновить».
     * Колбэк уже приходит в UI-потоке (см. FsEvents).
     */
    private final FsEvents.Listener fsListener = new FsEvents.Listener() {
        public void onFsChanged() {
            if (fs != null && tree != null && isAdded()) render();
        }
    };

    // -------------------------------------------------------------- lifecycle
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle saved) {
        try {
            return buildView(inflater, container);
        } catch (Throwable e) {
            App.writeCrash(e);
            java.io.StringWriter sw = new java.io.StringWriter();
            e.printStackTrace(new java.io.PrintWriter(sw));
            TextView tv = new TextView(getActivity());
            tv.setText("Ошибка вкладки «Проводник»:\n\n" + sw.toString());
            tv.setTextColor(Color.parseColor("#F85149"));
            tv.setTextSize(12f);
            tv.setPadding(24, 24, 24, 24);
            tv.setTextIsSelectable(true);
            ScrollView s = new ScrollView(getActivity());
            s.addView(tv);
            return s;
        }
    }

    private View buildView(LayoutInflater inflater, ViewGroup container) {
        View root = inflater.inflate(R.layout.fragment_explorer, container, false);
        fs = new ExplorerFs(getActivity());
        expanded.add("/");
        // Если при старте уже выбран GitHub-репозиторий (сохранён с прошлого раза) —
        // авто-раскроем путь /gh/owner/repo, чтобы файлы были видны сразу.
        try {
            String gr = GithubFs.get(getActivity()).activeRoot();
            if (gr != null) {
                String acc = "";
                for (String seg : gr.split("/")) { if (seg.isEmpty()) continue; acc += "/" + seg; expanded.add(acc); }
            }
        } catch (Throwable ignored) {}

        tree = (LinearLayout) root.findViewById(R.id.exp_tree);
        scroll = (ScrollView) root.findViewById(R.id.exp_scroll);
        emptyEl = (TextView) root.findViewById(R.id.exp_empty);
        selectBar = (LinearLayout) root.findViewById(R.id.exp_select_bar);
        sbInfo = (TextView) root.findViewById(R.id.sb_info);
        titleLabel = (TextView) root.findViewById(R.id.exp_title);
        subtitleLabel = (TextView) root.findViewById(R.id.exp_subtitle);

        root.findViewById(R.id.exp_menu).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showAbout(); }
        });
        root.findViewById(R.id.exp_source).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openProject(); }
        });
        root.findViewById(R.id.exp_new_file).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { createInside("/", false); }
        });
        root.findViewById(R.id.exp_new_folder).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { createInside("/", true); }
        });
        root.findViewById(R.id.exp_refresh).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { render(); toast("Обновлено"); }
        });
        root.findViewById(R.id.exp_more).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openActionsMenu(); }
        });

        // Кнопки нижней панели мультивыделения.
        root.findViewById(R.id.sb_cancel).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { exitSelectMode(); }
        });
        root.findViewById(R.id.sb_all).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { selectAll(); }
        });
        root.findViewById(R.id.sb_none).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { multiSelected.clear(); render(); }
        });
        root.findViewById(R.id.sb_copy).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { transferSelected(false); }
        });
        root.findViewById(R.id.sb_move).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { transferSelected(true); }
        });
        root.findViewById(R.id.sb_zip).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { zipSelected(); }
        });
        root.findViewById(R.id.sb_delete).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { deleteSelected(); }
        });

        FsEvents.addListener(fsListener);
        render();
        return root;
    }

    @Override
    public void onResume() {
        super.onResume();
        FsEvents.addListener(fsListener);
        if (fs != null) render();
    }

    @Override
    public void onDestroyView() {
        FsEvents.removeListener(fsListener);
        super.onDestroyView();
    }

    /**
     * Раскрыть цепочку папок до указанного пути (и перерисовать дерево).
     * Используется при открытии GitHub-репозитория: авто-раскрывает
     * /gh → /gh/owner → /gh/owner/repo, чтобы файлы были видны сразу.
     */
    public void expandPath(String vpath) {
        if (vpath == null) return;
        String acc = "";
        expanded.add("/");
        for (String seg : vpath.split("/")) {
            if (seg.isEmpty()) continue;
            acc = acc + "/" + seg;
            expanded.add(acc);
        }
        if (tree != null && isAdded()) render();
    }

    private int dp(int v) { return UiKit.dp(getActivity(), v); }
    private void toast(String s) {
        if (getActivity() != null) Toast.makeText(getActivity(), s, Toast.LENGTH_SHORT).show();
    }
    private void notifyEditorDeleted(String path) {
        if (getActivity() instanceof MainActivity) ((MainActivity) getActivity()).notifyPathDeleted(path);
    }

    /** Есть ли смонтированный GitHub-репозиторий (тогда операции идут по сети). */
    private boolean ghActive() {
        try { return GithubFs.get(getActivity()).activeRoot() != null; } catch (Throwable e) { return false; }
    }
    /** Путь относится к GitHub-репозиторию (/gh/…)? */
    private boolean ghPath(String p) {
        try { return GithubFs.get(getActivity()).isRemotePath(ExplorerFs.normPath(p)); } catch (Throwable e) { return false; }
    }

    /**
     * Выполнить ФС-операцию. Если операция затрагивает GitHub-путь — выполняем в
     * фоне (сеть нельзя в UI-потоке; каждая запись/удаление = коммит на сайт) с
     * тостом «Синхронизация с GitHub…»; иначе синхронно в UI-потоке (локальная ФС).
     * onDone вызывается в UI-потоке после успешного завершения.
     */
    private interface FsOp { void run() throws Exception; }
    private void doFs(boolean touchesGh, final String progressMsg, final FsOp op, final Runnable onDone) {
        if (!touchesGh) {
            try { op.run(); if (onDone != null) onDone.run(); }
            catch (Exception e) { toast("Ошибка: " + firstLine(e.getMessage())); }
            return;
        }
        if (progressMsg != null) toast(progressMsg);
        new Thread(new Runnable() {
            public void run() {
                try {
                    op.run();
                    ui.post(new Runnable() { public void run() {
                        if (onDone != null) onDone.run();
                    }});
                } catch (final Exception e) {
                    ui.post(new Runnable() { public void run() {
                        toast("Ошибка GitHub: " + firstLine(e.getMessage()));
                    }});
                }
            }
        }).start();
    }
    private String firstLine(String s) {
        if (s == null) return "неизвестная ошибка";
        int nl = s.indexOf('\n');
        return nl > 0 ? s.substring(0, nl) : s;
    }

    // ============================ ПРОГРЕСС АРХИВАЦИИ / РАСПАКОВКИ =============
    /** Операция zip/unzip, принимающая колбэк прогресса. Возвращает число файлов. */
    private interface ZipOp { int run(ZipUtil.Progress prog) throws Exception; }

    /**
     * Показывает статусное окно с процентами (0–100%) и полосой прогресса,
     * выполняет zip/unzip в фоновом потоке и детально обновляет прогресс на каждый процент.
     * По завершении вызывает onDone(число файлов) на UI-потоке.
     */
    private void doZipProgress(final String title, final ZipOp op, final ZipResult onDone) {
        final Context ctx = getActivity();
        // Корневой контейнер активности — сюда добавляем плавающий оверлей поверх всего UI.
        final ViewGroup root = (ViewGroup) getActivity().findViewById(android.R.id.content);

        // ---- Общий контейнер оверлея на весь экран ----
        final FrameLayout overlay = new FrameLayout(ctx);
        overlay.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ---- Затемняющий фон (только для развёрнутого состояния) ----
        final View dim = new View(ctx);
        dim.setBackgroundColor(Color.parseColor("#99000000"));
        dim.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        dim.setClickable(true); // перехватывает нажатия под окном
        overlay.addView(dim);

        // ---- Карточка с прогрессом (развёрнутое состояние) ----
        final LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(18), dp(22), dp(18));
        GradientDrawable boxBg = new GradientDrawable();
        boxBg.setColor(Color.parseColor("#252526"));
        boxBg.setCornerRadius(dp(14));
        boxBg.setStroke(dp(1), Color.parseColor("#3C3C3C"));
        box.setBackground(boxBg);
        FrameLayout.LayoutParams boxLp = new FrameLayout.LayoutParams(dp(300), ViewGroup.LayoutParams.WRAP_CONTENT);
        boxLp.gravity = Gravity.CENTER;
        box.setLayoutParams(boxLp);

        // Флаг отмены — общий для UI-кнопки и фонового потока (порт «Отмена»).
        final boolean[] cancelled = { false };

        // Заголовок + кнопка «свернуть»
        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        final TextView titleTv = new TextView(ctx);
        titleTv.setText(title);
        titleTv.setTextColor(Color.parseColor("#EAEAEA"));
        titleTv.setTextSize(15);
        titleTv.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleTv.setLayoutParams(titleLp);
        head.addView(titleTv);
        final TextView minBtn = new TextView(ctx);
        minBtn.setText("—");
        minBtn.setTextColor(Color.parseColor("#EAEAEA"));
        minBtn.setTextSize(20);
        minBtn.setTypeface(Typeface.DEFAULT_BOLD);
        minBtn.setGravity(Gravity.CENTER);
        int mb = dp(32);
        LinearLayout.LayoutParams minLp = new LinearLayout.LayoutParams(mb, mb);
        minBtn.setLayoutParams(minLp);
        GradientDrawable minBg = new GradientDrawable();
        minBg.setColor(Color.parseColor("#333333"));
        minBg.setCornerRadius(dp(8));
        minBtn.setBackground(minBg);
        head.addView(minBtn);
        box.addView(head);

        final TextView sub = new TextView(ctx);
        sub.setText("Подготовка…");
        sub.setTextColor(Color.parseColor("#A0A0A0"));
        sub.setTextSize(12);
        sub.setPadding(0, dp(8), 0, dp(12));
        box.addView(sub);

        final android.widget.ProgressBar bar =
                new android.widget.ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgress(0);
        LinearLayout.LayoutParams bp =
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10));
        bar.setLayoutParams(bp);
        box.addView(bar);

        final TextView pct = new TextView(ctx);
        pct.setText("0%");
        pct.setTextColor(Color.parseColor("#1177bb"));
        pct.setTextSize(22);
        pct.setTypeface(Typeface.DEFAULT_BOLD);
        pct.setGravity(Gravity.END);
        pct.setPadding(0, dp(12), 0, 0);
        box.addView(pct);

        // ---- Кнопка «Отмена» ----
        final TextView cancelBtn = new TextView(ctx);
        cancelBtn.setText("Отмена");
        cancelBtn.setTextColor(Color.parseColor("#EAEAEA"));
        cancelBtn.setTextSize(14);
        cancelBtn.setTypeface(Typeface.DEFAULT_BOLD);
        cancelBtn.setGravity(Gravity.CENTER);
        cancelBtn.setPadding(dp(16), dp(10), dp(16), dp(10));
        GradientDrawable cancelBg = new GradientDrawable();
        cancelBg.setColor(Color.parseColor("#5A2222"));
        cancelBg.setCornerRadius(dp(8));
        cancelBg.setStroke(dp(1), Color.parseColor("#8B3A3A"));
        cancelBtn.setBackground(cancelBg);
        LinearLayout.LayoutParams cancelLp =
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cancelLp.topMargin = dp(14);
        cancelBtn.setLayoutParams(cancelLp);
        cancelBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                cancelled[0] = true;
                cancelBtn.setText("Отмена…");
                cancelBtn.setEnabled(false);
                sub.setText("Отмена операции…");
            }
        });
        box.addView(cancelBtn);

        overlay.addView(box);

        // ---- Плавающая круглая кнопка (свёрнутое состояние), у правого края по центру ----
        final FrameLayout fab = new FrameLayout(ctx);
        int fabSz = dp(64);
        FrameLayout.LayoutParams fabLp = new FrameLayout.LayoutParams(fabSz, fabSz);
        fabLp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        fabLp.rightMargin = dp(12);
        fab.setLayoutParams(fabLp);
        final GradientDrawable fabBg = new GradientDrawable();
        fabBg.setShape(GradientDrawable.OVAL);
        fabBg.setColor(Color.parseColor("#252526"));
        fabBg.setStroke(dp(3), Color.parseColor("#1177bb"));
        fab.setBackground(fabBg);
        fab.setElevation(dp(6));
        final TextView fabPct = new TextView(ctx);
        fabPct.setText("0%");
        fabPct.setTextColor(Color.parseColor("#EAEAEA"));
        fabPct.setTextSize(15);
        fabPct.setTypeface(Typeface.DEFAULT_BOLD);
        fabPct.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams fabPctLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        fabPct.setLayoutParams(fabPctLp);
        fab.addView(fabPct);
        fab.setVisibility(View.GONE);
        overlay.addView(fab);

        root.addView(overlay);

        // ---- Логика сворачивания/разворачивания ----
        minBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                dim.setVisibility(View.GONE);
                box.setVisibility(View.GONE);
                fab.setVisibility(View.VISIBLE);
            }
        });
        fab.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                fab.setVisibility(View.GONE);
                dim.setVisibility(View.VISIBLE);
                box.setVisibility(View.VISIBLE);
            }
        });

        final Runnable removeOverlay = new Runnable() {
            public void run() { try { root.removeView(overlay); } catch (Exception ignore) {} }
        };

        final int[] n = { 0 };
        new Thread(new Runnable() {
            public void run() {
                try {
                    n[0] = op.run(new ZipUtil.Progress() {
                        public void onProgress(final int done, final int total, final int p) {
                            ui.post(new Runnable() { public void run() {
                                bar.setProgress(p);
                                pct.setText(p + "%");
                                fabPct.setText(p + "%");
                                sub.setText(done + " / " + total + " файл(ов)");
                            }});
                        }
                        public boolean isCancelled() { return cancelled[0]; }
                    });
                    ui.post(new Runnable() { public void run() {
                        bar.setProgress(100);
                        pct.setText("100%");
                        pct.setTextColor(Color.parseColor("#4ec9b0"));
                        fabPct.setText("100%");
                        fabPct.setTextColor(Color.parseColor("#4ec9b0"));
                        fabBg.setStroke(dp(3), Color.parseColor("#4ec9b0"));
                        // Небольшая пауза, чтобы пользователь увидел 100%.
                        ui.postDelayed(new Runnable() { public void run() {
                            removeOverlay.run();
                            if (onDone != null) onDone.done(n[0]);
                        }}, 500);
                    }});
                } catch (final ZipUtil.CancelledException ce) {
                    // Пользователь нажал «Отмена» — убираем окно и сообщаем, без ошибки.
                    ui.post(new Runnable() { public void run() {
                        removeOverlay.run();
                        toast("Операция отменена");
                        render();
                    }});
                } catch (final Exception e) {
                    ui.post(new Runnable() { public void run() {
                        removeOverlay.run();
                        toast("Ошибка: " + firstLine(e.getMessage()));
                    }});
                }
            }
        }).start();
    }
    private interface ZipResult { void done(int fileCount); }

    // =========================================================== РЕНДЕР ДЕРЕВА
    /** Перестроить дерево целиком, начиная с корня «/». */
    void render() {
        if (tree == null) return;
        // Если есть активный GitHub-репозиторий, но его дерево ещё не загружено
        // (например после перезапуска приложения) — подгрузим в фоне; по готовности
        // придёт FsEvents.notifyChanged() и дерево перерисуется с файлами.
        try { GithubFs.get(getActivity()).ensureActiveLoaded(); } catch (Throwable ignored) {}
        tree.removeAllViews();
        if (titleLabel != null) {
            if (fs.isTree()) {
                // Открыт реальный проект: «ПРОЕКТ» (голубым) сверху, имя папки — снизу.
                titleLabel.setText("ПРОЕКТ");
                if (subtitleLabel != null) {
                    subtitleLabel.setText(fs.treeName());
                    subtitleLabel.setVisibility(View.VISIBLE);
                }
            } else {
                titleLabel.setText("ПРОВОДНИК");
                if (subtitleLabel != null) subtitleLabel.setVisibility(View.GONE);
            }
        }
        List<ExplorerFs.Node> rootKids = fs.listChildren("/");
        emptyEl.setVisibility(rootKids.isEmpty() ? View.VISIBLE : View.GONE);
        buildLevel("/", 0);
        renderSelectBar();
    }

    /** Рекурсивно добавляет строки уровня (папки раскрываются, если в expanded). */
    private void buildLevel(String parent, int depth) {
        List<ExplorerFs.Node> kids = fs.listChildren(parent);
        for (final ExplorerFs.Node n : kids) {
            final boolean isDir = "dir".equals(n.type);
            final boolean isExp = isDir && expanded.contains(n.path);
            addRow(n, depth, isDir, isExp);
            if (isDir && isExp) buildLevel(n.path, depth + 1);
        }
    }

    private void addRow(final ExplorerFs.Node n, int depth, final boolean isDir, final boolean isExp) {
        final boolean isMulti = multiSelected.contains(n.path);

        LinearLayout row = new LinearLayout(getActivity());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8) + depth * dp(14), dp(11), dp(12), dp(11));
        LinearLayout.LayoutParams rp =
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        row.setLayoutParams(rp);

        // Подсветка: одиночное выделение (синий фон) или мульти-выделение.
        boolean single = n.path.equals(selected) && !selectMode;
        if (single || isMulti) row.setBackgroundColor(Color.parseColor("#132C47"));
        else row.setBackgroundColor(Color.TRANSPARENT);

        // Чекбокс в режиме мультивыделения.
        if (selectMode) {
            View cb = makeCheckbox(isMulti);
            LinearLayout.LayoutParams cbp = new LinearLayout.LayoutParams(dp(22), dp(22));
            cbp.rightMargin = dp(10);
            cb.setLayoutParams(cbp);
            row.addView(cb);
        }

        // Шеврон (только для папок).
        TextView chev = new TextView(getActivity());
        chev.setTextColor(Color.parseColor("#8B949E"));
        chev.setTextSize(12f);
        chev.setText(isDir ? (isExp ? "\u25BE" : "\u25B8") : "");
        LinearLayout.LayoutParams chp = new LinearLayout.LayoutParams(dp(18), ViewGroup.LayoutParams.WRAP_CONTENT);
        chev.setLayoutParams(chp);
        // В режиме мультивыделения тап по шеврону сворачивает/раскрывает папку
        // (не переключая выделение) — как в web-версии.
        if (selectMode && isDir) {
            chev.setClickable(true);
            chev.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (expanded.contains(n.path)) expanded.remove(n.path);
                    else expanded.add(n.path);
                    render();
                }
            });
        }
        row.addView(chev);

        // Иконка: папка — эмодзи, файл — цветной кружок.
        if (isDir) {
            TextView ic = new TextView(getActivity());
            ic.setText(isExp ? "\uD83D\uDCC2" : "\uD83D\uDCC1");
            ic.setTextSize(16f);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            ip.rightMargin = dp(8);
            ic.setLayoutParams(ip);
            row.addView(ic);
        } else {
            View dot = new View(getActivity());
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(fileDotColor(n.path));
            dot.setBackground(g);
            LinearLayout.LayoutParams dpar = new LinearLayout.LayoutParams(dp(8), dp(8));
            dpar.leftMargin = dp(6);
            dpar.rightMargin = dp(12);
            dot.setLayoutParams(dpar);
            row.addView(dot);
        }

        // Имя.
        TextView name = new TextView(getActivity());
        name.setText(ExplorerFs.basename(n.path));
        name.setTextColor(Color.parseColor("#E6EDF3"));
        name.setTextSize(15.5f);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT);
        np.weight = 1;
        name.setLayoutParams(np);
        row.addView(name);

        // Размер (только файл).
        if (!isDir) {
            TextView meta = new TextView(getActivity());
            meta.setText(ExplorerFs.bytesToStr(n.size));
            meta.setTextColor(Color.parseColor("#6E7681"));
            meta.setTextSize(13f);
            meta.setPadding(dp(8), 0, 0, 0);
            row.addView(meta);
        }

        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { onRowClick(n, isDir); }
        });
        row.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                if (selectMode) { toggleMulti(n); }
                else openContext(n, isDir);
                return true;
            }
        });

        tree.addView(row);
    }

    /** Мини-чекбокс (квадрат / синий с галочкой). */
    private View makeCheckbox(boolean on) {
        TextView box = new TextView(getActivity());
        box.setGravity(Gravity.CENTER);
        box.setTextSize(13f);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadius(dp(4));
        if (on) {
            g.setColor(Color.parseColor("#1E88FF"));
            box.setText("\u2713");
            box.setTextColor(Color.WHITE);
            box.setTypeface(Typeface.DEFAULT_BOLD);
        } else {
            g.setColor(Color.TRANSPARENT);
            g.setStroke(dp(2), Color.parseColor("#5B6570"));
            box.setText("");
        }
        box.setBackground(g);
        return box;
    }

    /** Цвет кружка файла по расширению (как ft-* классы web-версии). */
    private int fileDotColor(String path) {
        String e = ExplorerFs.extname(path);
        if (e.equals("js") || e.equals("mjs") || e.equals("cjs") || e.equals("jsx")) return Color.parseColor("#F1E05A");
        if (e.equals("ts") || e.equals("tsx")) return Color.parseColor("#3178C6");
        if (e.equals("py")) return Color.parseColor("#3572A5");
        if (e.equals("html") || e.equals("htm")) return Color.parseColor("#E34C26");
        if (e.equals("css") || e.equals("scss") || e.equals("less")) return Color.parseColor("#563D7C");
        if (e.equals("json")) return Color.parseColor("#CBCB41");
        if (e.equals("md") || e.equals("markdown")) return Color.parseColor("#519ABA");
        if (e.equals("kt") || e.equals("kts")) return Color.parseColor("#A97BFF");
        if (e.equals("java")) return Color.parseColor("#B07219");
        if (e.equals("xml")) return Color.parseColor("#8B949E");
        if (e.equals("zip") || e.equals("tar") || e.equals("gz") || e.equals("rar") || e.equals("7z"))
            return Color.parseColor("#C0A060");
        return Color.parseColor("#4C9AFF");
    }

    // =========================================================== КЛИКИ
    private void onRowClick(ExplorerFs.Node n, boolean isDir) {
        if (selectMode) { toggleMulti(n); return; }
        selected = n.path;
        if (isDir) {
            if (expanded.contains(n.path)) expanded.remove(n.path);
            else expanded.add(n.path);
            render();
        } else if (isArchive(n.path)) {
            openZipViewer(n.path);
        } else {
            openFile(n.path);
        }
    }

    private boolean isArchive(String path) { return "zip".equals(ExplorerFs.extname(path)); }

    /** Открыть файл: попросить MainActivity переключиться на редактор. */
    private void openFile(String path) {
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).openInEditor(path);
        } else {
            showTextViewer(path);
        }
    }

    // =========================================================== МУЛЬТИ-ВЫДЕЛЕНИЕ
    private void toggleMulti(ExplorerFs.Node n) {
        if (multiSelected.contains(n.path)) multiSelected.remove(n.path);
        else multiSelected.add(n.path);
        render();
    }
    private void enterSelectMode(String preselect) {
        selectMode = true;
        multiSelected.clear();
        if (preselect != null) multiSelected.add(preselect);
        render();
    }
    private void exitSelectMode() {
        selectMode = false;
        multiSelected.clear();
        selected = null;
        render();
    }
    private void selectAll() {
        List<ExplorerFs.Node> all = fs.listTree("/");
        for (ExplorerFs.Node n : all) multiSelected.add(n.path);
        render();
    }

    /** Показать/скрыть нижнюю панель и обновить счётчик + активность кнопок. */
    private void renderSelectBar() {
        if (selectBar == null) return;
        selectBar.setVisibility(selectMode ? View.VISIBLE : View.GONE);
        if (!selectMode) return;
        int count = multiSelected.size();
        sbInfo.setText("Выбрано: " + count);
        int[] ids = { R.id.sb_copy, R.id.sb_move, R.id.sb_zip, R.id.sb_delete };
        for (int id : ids) {
            View b = selectBar.findViewById(id);
            if (b != null) {
                b.setEnabled(count > 0);
                b.setAlpha(count > 0 ? 1f : 0.4f);
            }
        }
    }

    /** Схлопнуть вложенные пути: если выбран и родитель, и потомок — оставить только родителя. */
    private List<String> collapseNested(Set<String> paths) {
        List<String> arr = new ArrayList<String>(paths);
        Collections.sort(arr, new Comparator<String>() {
            public int compare(String a, String b) { return a.length() - b.length(); }
        });
        List<String> kept = new ArrayList<String>();
        for (String p : arr) {
            boolean prefixed = false;
            for (String k : kept) {
                if (p.equals(k) || p.startsWith(k.equals("/") ? "/" : k + "/")) { prefixed = true; break; }
            }
            if (!prefixed) kept.add(p);
        }
        return kept;
    }

    // ------------------------------------------------- копирование/перемещение
    /**
     * Копировать/переместить ОДИН элемент (из контекстного меню долгого нажатия).
     * Показывает выбор целевой папки и выполняет операцию (для GitHub — в фоне,
     * с синхронным коммитом на сайт).
     */
    private void transferOne(final String src, final boolean move) {
        final Set<String> forbidden = new HashSet<String>();
        forbidden.add(src); // нельзя копировать/перемещать внутрь самого себя
        pickTargetDir(move ? "Переместить «" + ExplorerFs.basename(src) + "» в…"
                        : "Копировать «" + ExplorerFs.basename(src) + "» в…",
                move ? "Переместить сюда" : "Копировать сюда", forbidden,
                new TargetCb() {
            public void onPick(final String target) {
                if (target == null) return;
                final String parent = ExplorerFs.dirname(src);
                if (move && parent.equals(target)) { toast("Элемент уже в этой папке"); return; }
                boolean gh = ghPath(target) || ghPath(src);
                doFs(gh, "Синхронизация с GitHub…", new FsOp() {
                    public void run() throws Exception {
                        if (move) fs.rename(src, ExplorerFs.joinPath(target, ExplorerFs.basename(src)));
                        else fs.copyInto(src, target);
                    }
                }, new Runnable() { public void run() {
                    if (move && src.equals(selected)) selected = null;
                    if (move) notifyEditorDeleted(src);
                    expanded.add(target);
                    render();
                    toast(move ? "Перемещено в " + target : "Скопировано в " + target);
                } });
            }
        });
    }

    private void transferSelected(final boolean move) {
        if (multiSelected.isEmpty()) return;
        final List<String> kept = collapseNested(multiSelected);
        final Set<String> forbidden = new HashSet<String>(kept);
        pickTargetDir(move ? "Переместить в…" : "Копировать в…",
                move ? "Переместить сюда" : "Копировать сюда", forbidden,
                new TargetCb() {
            public void onPick(final String target) {
                if (target == null) return;
                boolean gh = ghPath(target);
                for (String p : kept) if (ghPath(p)) { gh = true; break; }
                final int[] res = { 0, 0 };
                doFs(gh, "Синхронизация с GitHub…", new FsOp() {
                    public void run() throws Exception {
                        for (String src : kept) {
                            try {
                                String parent = ExplorerFs.dirname(src);
                                if (move && parent.equals(target)) continue;
                                if (move) fs.rename(src, ExplorerFs.joinPath(target, ExplorerFs.basename(src)));
                                else fs.copyInto(src, target);
                                res[0]++;
                            } catch (Exception e) { res[1]++; }
                        }
                    }
                }, new Runnable() { public void run() {
                    toast((move ? "Перемещено: " : "Скопировано: ") + res[0] + (res[1] > 0 ? ", ошибок: " + res[1] : ""));
                    expanded.add(target);
                    exitSelectMode();
                } });
            }
        });
    }

    // ------------------------------------------------- массовое удаление
    private void deleteSelected() {
        if (multiSelected.isEmpty()) return;
        final List<String> kept = collapseNested(multiSelected);
        confirm("Массовое удаление", "Удалить выбранные объекты (" + kept.size() + ")? Это действие необратимо.",
                "Удалить", true, new Runnable() {
            public void run() {
                boolean gh = false;
                for (String p : kept) if (ghPath(p)) { gh = true; break; }
                final int[] res = { 0, 0 };
                doFs(gh, "Удаление в GitHub…", new FsOp() {
                    public void run() throws Exception {
                        for (String p : kept) {
                            try { fs.delete(p); res[0]++; } catch (Exception e) { res[1]++; }
                        }
                    }
                }, new Runnable() { public void run() {
                    for (String p : kept) notifyEditorDeleted(p);
                    toast("Удалено: " + res[0] + (res[1] > 0 ? ", ошибок: " + res[1] : ""));
                    exitSelectMode();
                } });
            }
        });
    }

    // ------------------------------------------------- архивирование выбранного
    private void zipSelected() {
        if (multiSelected.isEmpty()) return;
        final List<String> kept = collapseNested(multiSelected);
        final String rootDir = commonDir(kept);
        final String[] free = fs.pickFreeZipName(rootDir, zipBaseName(kept, rootDir));
        doZipProgress("Архивация", new ZipOp() {
            public int run(ZipUtil.Progress prog) throws Exception {
                return ZipUtil.zipInto(fs, kept, rootDir, free[1], prog);
            }
        }, new ZipResult() { public void done(int n) {
            if (n == 0) { toast("Нет файлов для архивации"); return; }
            expanded.add(rootDir);
            exitSelectMode();
            render();
            toast("Создан " + free[0] + " (" + n + " файл(ов))");
        } });
    }

    private String zipBaseName(List<String> kept, String rootDir) {
        if (kept.size() == 1) return ExplorerFs.basename(kept.get(0)).replaceFirst("\\.[^.]+$", "");
        if (rootDir != null && !rootDir.equals("/")) return ExplorerFs.basename(rootDir);
        return "selection";
    }
    private String commonDir(List<String> paths) {
        if (paths.size() == 1) return ExplorerFs.dirname(paths.get(0));
        int min = Integer.MAX_VALUE;
        List<String[]> all = new ArrayList<String[]>();
        for (String p : paths) {
            String[] seg = splitNonEmpty(p);
            all.add(seg);
            if (seg.length < min) min = seg.length;
        }
        List<String> acc = new ArrayList<String>();
        for (int i = 0; i < min; i++) {
            String seg = all.get(0)[i];
            boolean same = true;
            for (String[] a : all) if (!a[i].equals(seg)) { same = false; break; }
            if (same) acc.add(seg); else break;
        }
        StringBuilder sb = new StringBuilder();
        for (String s : acc) sb.append('/').append(s);
        return sb.length() == 0 ? "/" : sb.toString();
    }
    private String[] splitNonEmpty(String p) {
        List<String> out = new ArrayList<String>();
        for (String s : p.split("/")) if (!s.isEmpty()) out.add(s);
        return out.toArray(new String[0]);
    }

    // =========================================================== МЕНЮ «ДЕЙСТВИЯ»
    private void openActionsMenu() {
        Sheet sheet = new Sheet("Действия");
        sheet.item("Импорт файлов…", false, new Runnable() {
            public void run() { importFiles("/"); }
        });
        sheet.item("Экспорт проекта в zip", false, new Runnable() {
            public void run() { exportProjectZip(); }
        });
        sheet.item(selectMode ? "Выйти из режима выделения" : "Выделить несколько…", false, new Runnable() {
            public void run() { if (selectMode) exitSelectMode(); else enterSelectMode(null); }
        });
        sheet.item("Развернуть все", false, new Runnable() {
            public void run() {
                for (ExplorerFs.Node n : fs.listTree("/")) if ("dir".equals(n.type)) expanded.add(n.path);
                render();
            }
        });
        sheet.item("Свернуть все", false, new Runnable() {
            public void run() { expanded.clear(); expanded.add("/"); render(); }
        });
        sheet.item("Очистить проводник", true, new Runnable() {
            public void run() {
                confirm("Очистить проводник", "Удалить все файлы?", "Стереть", true, new Runnable() {
                    public void run() {
                        for (ExplorerFs.Node n : fs.listChildren("/")) { try { fs.delete(n.path); } catch (Exception e) {} }
                        selected = null;
                        render();
                        toast("Проводник очищен");
                    }
                });
            }
        });
        sheet.show();
    }

    // =========================================================== КОНТЕКСТНОЕ МЕНЮ
    private void openContext(final ExplorerFs.Node n, final boolean isDir) {
        Sheet sheet = new Sheet(ExplorerFs.basename(n.path).isEmpty() ? "/" : ExplorerFs.basename(n.path));
        if (isDir) {
            sheet.item("Новый файл здесь", false, new Runnable() {
                public void run() { createInside(n.path, false); }
            });
            sheet.item("Новая папка здесь", false, new Runnable() {
                public void run() { createInside(n.path, true); }
            });
            sheet.item("Импорт файлов сюда…", false, new Runnable() {
                public void run() { importFiles(n.path); }
            });
        } else {
            sheet.item("Открыть", false, new Runnable() {
                public void run() { openFile(n.path); }
            });
            sheet.item("Скачать", false, new Runnable() {
                public void run() { downloadFile(n.path); }
            });
        }
        sheet.item("Переместить в…", false, new Runnable() {
            public void run() { transferOne(n.path, true); }
        });
        sheet.item("Копировать в…", false, new Runnable() {
            public void run() { transferOne(n.path, false); }
        });
        sheet.item("Переименовать", false, new Runnable() {
            public void run() { renameNode(n.path); }
        });
        sheet.item("Копировать путь", false, new Runnable() {
            public void run() { copyPath(n.path); }
        });
        if (isArchive(n.path) && !isDir) {
            sheet.item("Распаковать", false, new Runnable() {
                public void run() { extractArchive(n.path); }
            });
        }
        sheet.item("Сжать в zip", false, new Runnable() {
            public void run() { compressOne(n.path); }
        });
        sheet.item("Выбрать несколько", false, new Runnable() {
            public void run() { enterSelectMode(n.path); }
        });
        sheet.item("Сведения о файле", false, new Runnable() {
            public void run() { showFileInfo(n.path); }
        });
        sheet.item("Удалить", true, new Runnable() {
            public void run() { deleteNode(n.path, isDir); }
        });
        sheet.show();
    }

    // =========================================================== ОПЕРАЦИИ
    private void createInside(final String parent, final boolean isDir) {
        if (!parent.equals("/")) expanded.add(parent);
        prompt(isDir ? "Новая папка" : "Новый файл", isDir ? "Имя папки" : "Имя файла",
                isDir ? "src" : "index.js", "", new PromptCb() {
            public void onOk(String name) {
                if (name == null || name.trim().isEmpty()) return;
                final String full = ExplorerFs.joinPath(parent, name.trim());
                doFs(ghPath(full), "Создаём в GitHub…", new FsOp() {
                    public void run() throws Exception {
                        if (isDir) fs.ensureDir(full);
                        else {
                            String pd = ExplorerFs.dirname(full);
                            if (!pd.equals("/")) fs.ensureDir(pd);
                            fs.writeText(full, "");
                        }
                    }
                }, new Runnable() { public void run() { render(); } });
            }
        });
    }

    private void renameNode(final String path) {
        final String cur = ExplorerFs.basename(path);
        prompt("Переименовать", "Новое имя", "", cur, new PromptCb() {
            public void onOk(String nn) {
                if (nn == null || nn.trim().isEmpty() || nn.equals(cur)) return;
                final String target = ExplorerFs.joinPath(ExplorerFs.dirname(path), nn.trim());
                doFs(ghPath(path) || ghPath(target), "Переименование в GitHub…", new FsOp() {
                    public void run() throws Exception { fs.rename(path, target); }
                }, new Runnable() { public void run() { render(); } });
            }
        });
    }

    private void deleteNode(final String path, final boolean isDir) {
        confirm("Удалить", "Удалить " + (isDir ? "папку" : "файл") + " «" + ExplorerFs.basename(path) + "»"
                + (isDir ? " и всё содержимое" : "") + "?", "Удалить", true, new Runnable() {
            public void run() {
                doFs(ghPath(path), "Удаление в GitHub…", new FsOp() {
                    public void run() throws Exception { fs.delete(path); }
                }, new Runnable() { public void run() {
                    notifyEditorDeleted(path);
                    if (path.equals(selected)) selected = null;
                    render();
                } });
            }
        });
    }

    private void copyPath(String path) {
        try {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getActivity().getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("path", path));
            toast("Путь скопирован");
        } catch (Exception e) { toast("Копирование недоступно"); }
    }

    private void compressOne(final String path) {
        final List<String> one = new ArrayList<String>();
        one.add(path);
        final String targetDir = ExplorerFs.dirname(path);
        String base = ExplorerFs.basename(path).replaceFirst("\\.[^.]+$", "");
        final String[] free = fs.pickFreeZipName(targetDir, base);
        doZipProgress("Архивация", new ZipOp() {
            public int run(ZipUtil.Progress prog) throws Exception {
                return ZipUtil.zipInto(fs, one, targetDir, free[1], prog);
            }
        }, new ZipResult() { public void done(int n) {
            if (n == 0) { toast("Нет файлов для архивации"); return; }
            expanded.add(targetDir);
            render();
            toast("Создан " + free[0] + " (" + n + " файл(ов))");
        } });
    }

    private void extractArchive(final String path) {
        final String base = path.replaceFirst("(?i)\\.zip$", "") + "/";
        doZipProgress("Распаковка архива", new ZipOp() {
            public int run(ZipUtil.Progress prog) throws Exception {
                return ZipUtil.unzipInto(fs, path, base, prog);
            }
        }, new ZipResult() { public void done(int n) {
            expanded.add(ExplorerFs.normPath(base));
            render();
            toast("Распаковано файлов: " + n);
        } });
    }

    private void exportProjectZip() {
        final List<String> roots = new ArrayList<String>();
        for (ExplorerFs.Node n : fs.listChildren("/")) roots.add(n.path);
        if (roots.isEmpty()) { toast("Проводник пуст"); return; }
        final String[] free = fs.pickFreeZipName("/", "project");
        doZipProgress("Экспорт в zip", new ZipOp() {
            public int run(ZipUtil.Progress prog) throws Exception {
                return ZipUtil.zipInto(fs, roots, "/", free[1], prog);
            }
        }, new ZipResult() { public void done(int n) {
            render();
            toast("Экспортирован " + free[0] + " (" + n + " файл(ов))");
        } });
    }

    // ------------------------------------------------------------- сведения
    private void showFileInfo(String path) {
        try {
            ExplorerFs.Stat st = fs.stat(path);
            long totalSize = st.size;
            int files = 0, dirs = 0;
            if (st.isDir) {
                for (ExplorerFs.Node r : fs.listTree(path)) {
                    if ("file".equals(r.type)) { files++; totalSize += r.size; } else dirs++;
                }
            }
            StringBuilder sb = new StringBuilder();
            sb.append("Имя: ").append(ExplorerFs.basename(path)).append('\n');
            sb.append("Тип: ").append(st.isDir ? "Папка" : "Файл").append('\n');
            sb.append("Путь: ").append(path).append('\n');
            sb.append("Каталог: ").append(ExplorerFs.dirname(path)).append('\n');
            if (!st.isDir) {
                sb.append("Расширение: ").append(ExplorerFs.extname(path).isEmpty() ? "—" : ExplorerFs.extname(path)).append('\n');
                sb.append("Размер: ").append(fmtSize(totalSize)).append('\n');
            } else {
                sb.append("Содержимое: ").append(files).append(" файл(ов), ").append(dirs).append(" папок\n");
                sb.append("Общий размер: ").append(fmtSize(totalSize)).append('\n');
            }
            sb.append("Изменён: ").append(fmtDate(st.mtime));
            final String fp = path;
            new AlertDialog.Builder(getActivity())
                    .setTitle("Сведения: " + ExplorerFs.basename(path))
                    .setMessage(sb.toString())
                    .setPositiveButton("Закрыть", null)
                    .setNeutralButton("Скопировать путь", new android.content.DialogInterface.OnClickListener() {
                        public void onClick(android.content.DialogInterface d, int w) { copyPath(fp); }
                    })
                    .show();
        } catch (Exception e) { toast("Не удалось получить сведения: " + e.getMessage()); }
    }
    private String fmtSize(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format(java.util.Locale.US, "%.2f KB (%d B)", b / 1024.0, b);
        return String.format(java.util.Locale.US, "%.2f MB (%d B)", b / 1024.0 / 1024.0, b);
    }
    private String fmtDate(long ts) {
        if (ts <= 0) return "—";
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(new java.util.Date(ts));
    }

    // =========================================================== ZIP-ПРОСМОТР
    private void openZipViewer(final String zipPath) {
        if (ghPath(zipPath)) {
            toast("Чтение архива из GitHub…");
            new Thread(new Runnable() {
                public void run() {
                    try {
                        final List<ZipUtil.Entry> es = ZipUtil.listEntries(fs, zipPath);
                        final int ab = (int) fs.stat(zipPath).size;
                        ui.post(new Runnable() { public void run() { buildZipViewer(zipPath, es, ab); } });
                    } catch (final Exception e) {
                        ui.post(new Runnable() { public void run() { toast("Не удалось прочитать архив: " + firstLine(e.getMessage())); } });
                    }
                }
            }).start();
            return;
        }
        final List<ZipUtil.Entry> entries;
        final int archBytes;
        try {
            entries = ZipUtil.listEntries(fs, zipPath);
            archBytes = (int) fs.stat(zipPath).size;
        } catch (Exception e) { toast("Не удалось прочитать архив: " + e.getMessage()); return; }
        buildZipViewer(zipPath, entries, archBytes);
    }

    private void buildZipViewer(final String zipPath, final List<ZipUtil.Entry> entries, final int archBytes) {
        LinearLayout body = new LinearLayout(getActivity());
        body.setOrientation(LinearLayout.VERTICAL);

        int fileCnt = 0, dirCnt = 0;
        for (ZipUtil.Entry en : entries) { if (en.isDir) dirCnt++; else fileCnt++; }
        TextView info = new TextView(getActivity());
        info.setText(fileCnt + " файл(ов), " + dirCnt + " папок · " + ExplorerFs.bytesToStr(archBytes) + " архив");
        info.setTextColor(Color.parseColor("#8B949E"));
        info.setTextSize(12f);
        info.setPadding(dp(4), 0, dp(4), dp(8));
        body.addView(info);

        for (final ZipUtil.Entry en : entries) {
            LinearLayout r = new LinearLayout(getActivity());
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(dp(6), dp(8), dp(6), dp(8));
            TextView ic = new TextView(getActivity());
            ic.setText(en.isDir ? "\uD83D\uDCC1" : "\u2022");
            ic.setTextColor(Color.parseColor("#4C9AFF"));
            ic.setPadding(0, 0, dp(10), 0);
            r.addView(ic);
            TextView nm = new TextView(getActivity());
            nm.setText(en.name);
            nm.setTextColor(Color.parseColor("#E6EDF3"));
            nm.setTextSize(13f);
            LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT);
            np.weight = 1; nm.setLayoutParams(np);
            r.addView(nm);
            if (!en.isDir) {
                TextView sz = new TextView(getActivity());
                sz.setText(ExplorerFs.bytesToStr(en.size));
                sz.setTextColor(Color.parseColor("#6E7681"));
                sz.setTextSize(11f);
                r.addView(sz);
                // Иконка «глаз» — просмотр содержимого записи (текст/картинка в контейнере).
                TextView eye = new TextView(getActivity());
                eye.setText("\uD83D\uDC41");
                eye.setTextSize(15f);
                eye.setPadding(dp(10), 0, dp(4), 0);
                r.addView(eye);
                // Клик по строке или по «глазу» — открыть просмотр записи.
                View.OnClickListener open = new View.OnClickListener() {
                    public void onClick(View v) { previewZipEntry(zipPath, en); }
                };
                r.setOnClickListener(open);
                eye.setOnClickListener(open);
                r.setBackgroundResource(R.drawable.bg_card);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rp.topMargin = dp(4);
                r.setLayoutParams(rp);
            }
            body.addView(r);
        }

        ScrollView sv = new ScrollView(getActivity());
        sv.addView(body);

        new AlertDialog.Builder(getActivity())
                .setTitle("Архив: " + ExplorerFs.basename(zipPath))
                .setView(wrapPadded(sv))
                .setPositiveButton("Закрыть", null)
                .setNeutralButton("Распаковать сюда", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) { extractArchive(zipPath); }
                })
                .setNegativeButton("Скачать", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) { downloadFile(zipPath); }
                })
                .show();
    }

    /**
     * Просмотр одной записи архива без распаковки (порт previewZipEntry из web).
     * Картинка → показывается в контейнере; текст → в прокручиваемом контейнере;
     * двоичный/большой файл → hex-превью первых 512 байт.
     */
    private void previewZipEntry(final String zipPath, final ZipUtil.Entry en) {
        if (ghPath(zipPath)) {
            new Thread(new Runnable() {
                public void run() {
                    try {
                        final byte[] b = ZipUtil.readEntry(fs, zipPath, en.name);
                        ui.post(new Runnable() { public void run() {
                            if (b == null) { toast("Запись не найдена в архиве"); return; }
                            buildEntryPreview(en, b);
                        } });
                    } catch (final Exception e) {
                        ui.post(new Runnable() { public void run() { toast("Не удалось прочитать: " + firstLine(e.getMessage())); } });
                    }
                }
            }).start();
            return;
        }
        final byte[] bytes;
        try {
            bytes = ZipUtil.readEntry(fs, zipPath, en.name);
        } catch (Exception e) { toast("Не удалось прочитать: " + e.getMessage()); return; }
        if (bytes == null) { toast("Запись не найдена в архиве"); return; }
        buildEntryPreview(en, bytes);
    }

    private void buildEntryPreview(final ZipUtil.Entry en, final byte[] bytes) {
        String ext = ExplorerFs.extname(en.name);
        boolean isImage = ext.equals("png") || ext.equals("jpg") || ext.equals("jpeg")
                || ext.equals("gif") || ext.equals("webp") || ext.equals("bmp");

        LinearLayout body = new LinearLayout(getActivity());
        body.setOrientation(LinearLayout.VERTICAL);

        TextView head = new TextView(getActivity());
        head.setText(en.name + " · " + ExplorerFs.bytesToStr(en.size));
        head.setTextColor(Color.parseColor("#8B949E"));
        head.setTextSize(11f);
        head.setPadding(dp(4), 0, dp(4), dp(8));
        body.addView(head);

        if (isImage) {
            android.graphics.Bitmap bmp = null;
            try { bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length); } catch (Throwable ignored) {}
            if (bmp != null) {
                ImageView iv = new ImageView(getActivity());
                iv.setImageBitmap(bmp);
                iv.setAdjustViewBounds(true);
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                iv.setPadding(dp(4), dp(4), dp(4), dp(4));
                body.addView(iv);
            } else {
                body.addView(hexPreview(bytes, "Не удалось декодировать изображение. Первые 512 байт (hex):"));
            }
        } else if (looksBinary(bytes) || bytes.length > 1024 * 1024) {
            body.addView(hexPreview(bytes, bytes.length > 1024 * 1024
                    ? "Файл слишком большой для предпросмотра. Первые 512 байт (hex):"
                    : "Двоичный файл. Первые 512 байт (hex):"));
        } else {
            String text;
            try { text = new String(bytes, "UTF-8"); } catch (Exception e) { text = new String(bytes); }
            TextView tv = new TextView(getActivity());
            tv.setText(text);
            tv.setTextColor(Color.parseColor("#E6EDF3"));
            tv.setTextSize(12f);
            tv.setTypeface(Typeface.MONOSPACE);
            tv.setTextIsSelectable(true);
            tv.setPadding(dp(10), dp(8), dp(10), dp(8));
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setCornerRadius(dp(6));
            bg.setColor(Color.parseColor("#0F1115"));
            tv.setBackground(bg);
            body.addView(tv);
        }

        ScrollView sv = new ScrollView(getActivity());
        sv.addView(body);
        new AlertDialog.Builder(getActivity())
                .setTitle(ExplorerFs.basename(en.name))
                .setView(wrapPadded(sv))
                .setPositiveButton("Закрыть", null)
                .show();
    }

    /** hex-превью первых 512 байт (для двоичных записей архива). */
    private View hexPreview(byte[] bytes, String note) {
        LinearLayout col = new LinearLayout(getActivity());
        col.setOrientation(LinearLayout.VERTICAL);
        TextView n = new TextView(getActivity());
        n.setText(note);
        n.setTextColor(Color.parseColor("#8B949E"));
        n.setTextSize(12f);
        n.setPadding(dp(4), 0, dp(4), dp(6));
        col.addView(n);
        int len = Math.min(512, bytes.length);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i += 16) {
            int end = Math.min(i + 16, len);
            StringBuilder hex = new StringBuilder(), asc = new StringBuilder();
            for (int j = i; j < end; j++) {
                int b = bytes[j] & 0xFF;
                hex.append(String.format("%02x ", b));
                asc.append(b >= 32 && b < 127 ? (char) b : '.');
            }
            while (hex.length() < 48) hex.append(' ');
            sb.append(String.format("%06x  %s %s\n", i, hex.toString(), asc.toString()));
        }
        TextView tv = new TextView(getActivity());
        tv.setText(sb.toString());
        tv.setTextColor(Color.parseColor("#C9D1D9"));
        tv.setTextSize(11f);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextIsSelectable(true);
        tv.setPadding(dp(10), dp(8), dp(10), dp(8));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(dp(6));
        bg.setColor(Color.parseColor("#0F1115"));
        tv.setBackground(bg);
        col.addView(tv);
        return col;
    }

    /** Грубая эвристика «двоичный ли контент» — есть ли NUL-байты в первых 4КБ. */
    private boolean looksBinary(byte[] bytes) {
        int n = Math.min(4096, bytes.length);
        for (int i = 0; i < n; i++) if (bytes[i] == 0) return true;
        return false;
    }

    // =========================================================== ПРОСМОТР ТЕКСТА
    private void showTextViewer(String path) {
        try {
            String text = fs.readText(path);
            if (text.length() > 200000) text = text.substring(0, 200000) + "\n… (обрезано)";
            TextView tv = new TextView(getActivity());
            tv.setText(text);
            tv.setTextColor(Color.parseColor("#E6EDF3"));
            tv.setTextSize(12f);
            tv.setTypeface(Typeface.MONOSPACE);
            tv.setTextIsSelectable(true);
            tv.setPadding(dp(12), dp(12), dp(12), dp(12));
            ScrollView sv = new ScrollView(getActivity());
            sv.addView(tv);
            new AlertDialog.Builder(getActivity())
                    .setTitle(ExplorerFs.basename(path))
                    .setView(sv)
                    .setPositiveButton("Закрыть", null)
                    .show();
        } catch (Exception e) { toast("Ошибка чтения: " + e.getMessage()); }
    }

    // =========================================================== ИМПОРТ / СКАЧИВАНИЕ
    private String importTargetDir = "/";
    private static final int REQ_IMPORT = 4101;
    private static final int REQ_DOWNLOAD = 4102;
    private String pendingDownloadPath = null;

    private void importFiles(String targetDir) {
        importTargetDir = targetDir;
        try {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("*/*");
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(Intent.createChooser(i, "Выберите файлы"), REQ_IMPORT);
        } catch (Exception e) { toast("Не удалось открыть выбор файлов"); }
    }

    private void downloadFile(String path) {
        pendingDownloadPath = path;
        try {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/octet-stream");
            i.putExtra(Intent.EXTRA_TITLE, ExplorerFs.basename(path));
            startActivityForResult(i, REQ_DOWNLOAD);
        } catch (Exception e) { toast("Не удалось начать сохранение"); }
    }

    @Override
    public void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != android.app.Activity.RESULT_OK || data == null) return;
        if (req == REQ_OPEN_TREE) {
            Uri uri = data.getData();
            if (uri != null && fs.openTree(uri)) {
                selected = null; expanded.clear(); expanded.add("/");
                render();
                toast("Открыт проект: " + fs.treeName());
            } else {
                toast("Не удалось открыть папку");
            }
            return;
        }
        if (req == REQ_IMPORT) {
            final List<Uri> uris = new ArrayList<Uri>();
            if (data.getClipData() != null) {
                android.content.ClipData cd = data.getClipData();
                for (int k = 0; k < cd.getItemCount(); k++) uris.add(cd.getItemAt(k).getUri());
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
            final boolean gh = ghPath(importTargetDir);
            final int[] cnt = { 0 };
            // Для GitHub-цели каждая выгрузка = коммит на сайт → выполняем в фоне.
            doFs(gh, "Выгрузка в GitHub…", new FsOp() {
                public void run() throws Exception {
                    for (Uri u : uris) if (importUri(u)) cnt[0]++;
                }
            }, new Runnable() { public void run() {
                if (!importTargetDir.equals("/")) expanded.add(importTargetDir);
                render();
                toast(gh ? ("Выгружено на GitHub: " + cnt[0]) : ("Импортировано файлов: " + cnt[0]));
            } });
        } else if (req == REQ_DOWNLOAD && pendingDownloadPath != null) {
            final String dlPath = pendingDownloadPath;
            final Uri outUri = data.getData();
            pendingDownloadPath = null;
            doFs(ghPath(dlPath), "Загрузка из GitHub…", new FsOp() {
                public void run() throws Exception {
                    byte[] bytes = fs.readBytes(dlPath);
                    java.io.OutputStream os = getActivity().getContentResolver().openOutputStream(outUri);
                    try {
                        int off = 0, chunk = 256 * 1024;
                        while (off < bytes.length) {
                            int n = Math.min(chunk, bytes.length - off);
                            os.write(bytes, off, n);
                            off += n;
                        }
                        os.flush();
                    } finally {
                        try { os.close(); } catch (Exception ignored) {}
                    }
                }
            }, new Runnable() { public void run() {
                toast("Сохранено: " + ExplorerFs.basename(dlPath));
            } });
        }
    }

    private boolean importUri(Uri uri) {
        try {
            String name = queryName(uri);
            if (name == null) name = "file_" + System.currentTimeMillis();
            java.io.InputStream in = getActivity().getContentResolver().openInputStream(uri);
            ByteArrayOutputStreamCompat bo = new ByteArrayOutputStreamCompat();
            byte[] buf = new byte[8192]; int r;
            while ((r = in.read(buf)) != -1) bo.write(buf, 0, r);
            in.close();
            fs.writeBytes(ExplorerFs.joinPath(importTargetDir, name), bo.toByteArray());
            return true;
        } catch (Exception e) { return false; }
    }
    private String queryName(Uri uri) {
        try {
            android.database.Cursor c = getActivity().getContentResolver()
                    .query(uri, null, null, null, null);
            if (c != null) {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0 && c.moveToFirst()) { String n = c.getString(idx); c.close(); return n; }
                c.close();
            }
        } catch (Exception e) {}
        String p = uri.getLastPathSegment();
        return p != null ? p.substring(p.lastIndexOf('/') + 1) : null;
    }
    /** Совместимая обёртка ByteArrayOutputStream (короткий алиас). */
    private static class ByteArrayOutputStreamCompat extends java.io.ByteArrayOutputStream {}

    // =========================================================== ИНФО-ДИАЛОГИ
    private void showAbout() {
        new AlertDialog.Builder(getActivity())
                .setTitle("Проводник")
                .setMessage("Файловый менеджер рабочей папки проекта.\n\n"
                        + "Все файлы хранятся локально на устройстве и общие с ИИ-агентом: "
                        + "то, что создаёт агент, сразу видно здесь, и наоборот.")
                .setPositiveButton("OK", null)
                .show();
    }
    private static final int REQ_OPEN_TREE = 4103;

    /**
     * Открыть проект — реальную папку на устройстве через системный выбор папки.
     * После выбора все операции идут напрямую на устройство (синхронно).
     * Если папка уже открыта — предлагаем вернуться к внутренней рабочей папке.
     */
    private void openProject() {
        if (fs.isTree()) {
            new AlertDialog.Builder(getActivity())
                    .setTitle("Проект: " + fs.treeName())
                    .setMessage("Открыта папка устройства «" + fs.treeName() + "».\n"
                            + "Все изменения сохраняются прямо на устройстве.")
                    .setPositiveButton("Открыть другую папку", new android.content.DialogInterface.OnClickListener() {
                        public void onClick(android.content.DialogInterface d, int w) { launchTreePicker(); }
                    })
                    .setNeutralButton("Закрыть проект", new android.content.DialogInterface.OnClickListener() {
                        public void onClick(android.content.DialogInterface d, int w) {
                            fs.useInternal(); selected = null; expanded.clear(); expanded.add("/"); render();
                            toast("Проект закрыт");
                        }
                    })
                    .setNegativeButton("Отмена", null)
                    .show();
        } else {
            launchTreePicker();
        }
    }
    private void launchTreePicker() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(i, REQ_OPEN_TREE);
        } catch (Exception e) { toast("Не удалось открыть выбор папки"); }
    }

    private View wrapPadded(View v) {
        LinearLayout w = new LinearLayout(getActivity());
        w.setPadding(dp(20), dp(4), dp(20), dp(4));
        w.addView(v);
        return w;
    }

    // =========================================================== ДИАЛОГИ (helpers)
    interface PromptCb { void onOk(String value); }
    interface TargetCb { void onPick(String target); }

    private void prompt(String title, String hint, String placeholder, String value, final PromptCb cb) {
        final EditText et = new EditText(getActivity());
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setHint(placeholder);
        et.setText(value == null ? "" : value);
        et.setTextColor(Color.parseColor("#E6EDF3"));
        et.setHintTextColor(Color.parseColor("#5B6570"));
        if (value != null && !value.isEmpty()) et.setSelection(value.length());
        LinearLayout w = new LinearLayout(getActivity());
        w.setOrientation(LinearLayout.VERTICAL);
        w.setPadding(dp(20), dp(8), dp(20), dp(4));
        if (hint != null) {
            TextView h = new TextView(getActivity());
            h.setText(hint);
            h.setTextColor(Color.parseColor("#8B949E"));
            h.setTextSize(13f);
            h.setPadding(0, 0, 0, dp(6));
            w.addView(h);
        }
        w.addView(et);
        new AlertDialog.Builder(getActivity())
                .setTitle(title)
                .setView(w)
                .setPositiveButton("OK", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int wi) { cb.onOk(et.getText().toString()); }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void confirm(String title, String message, String okText, boolean danger, final Runnable onOk) {
        AlertDialog dlg = new AlertDialog.Builder(getActivity())
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(okText, new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) { onOk.run(); }
                })
                .setNegativeButton("Отмена", null)
                .create();
        dlg.show();
        if (danger) {
            android.widget.Button b = dlg.getButton(AlertDialog.BUTTON_POSITIVE);
            if (b != null) b.setTextColor(Color.parseColor("#F85149"));
        }
    }

    /** Выбор целевой папки для копирования/перемещения (список всех папок дерева). */
    private void pickTargetDir(String title, String okText, final Set<String> forbidden, final TargetCb cb) {
        final List<String> dirs = new ArrayList<String>();
        dirs.add("/");
        for (ExplorerFs.Node n : fs.listTree("/")) if ("dir".equals(n.type)) dirs.add(n.path);
        Collections.sort(dirs);

        final String[] sel = { null };
        LinearLayout body = new LinearLayout(getActivity());
        body.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(getActivity());
        sv.addView(body);

        final AlertDialog dlg = new AlertDialog.Builder(getActivity())
                .setTitle(title)
                .setView(wrapPadded(sv))
                .setPositiveButton(okText, new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) { cb.onPick(sel[0]); }
                })
                .setNegativeButton("Отмена", null)
                .create();

        final List<TextView> rows = new ArrayList<TextView>();
        for (final String d : dirs) {
            boolean forbid = false;
            for (String fp : forbidden) {
                if (d.equals(fp) || d.startsWith(fp.equals("/") ? "/" : fp + "/")) { forbid = true; break; }
            }
            int depth = splitNonEmpty(d).length;
            final TextView row = new TextView(getActivity());
            row.setText((d.equals("/") ? "🗂 / (корень)" : "📁 " + d));
            row.setTextColor(Color.parseColor("#E6EDF3"));
            row.setTextSize(15f);
            row.setPadding(dp(10) + depth * dp(14), dp(10), dp(10), dp(10));
            if (forbid) { row.setAlpha(0.4f); }
            else {
                row.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        sel[0] = d;
                        for (TextView t : rows) t.setBackgroundColor(Color.TRANSPARENT);
                        row.setBackgroundColor(Color.parseColor("#132C47"));
                        android.widget.Button ok = dlg.getButton(AlertDialog.BUTTON_POSITIVE);
                        if (ok != null) ok.setEnabled(true);
                    }
                });
            }
            rows.add(row);
            body.addView(row);
        }
        dlg.show();
        android.widget.Button ok = dlg.getButton(AlertDialog.BUTTON_POSITIVE);
        if (ok != null) ok.setEnabled(false);
    }

    /**
     * Компактная реализация «bottom sheet»-меню (как в web-версии UI.openSheet):
     * тёмный лист снизу с заголовком, крестиком и списком пунктов.
     */
    private class Sheet {
        private final android.app.Dialog dlg;
        private final LinearLayout itemsWrap;

        Sheet(String title) {
            dlg = new android.app.Dialog(getActivity());
            dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);

            LinearLayout wrap = new LinearLayout(getActivity());
            wrap.setOrientation(LinearLayout.VERTICAL);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.parseColor("#161B22"));
            bg.setCornerRadii(new float[]{dp(18), dp(18), dp(18), dp(18), 0, 0, 0, 0});
            wrap.setBackground(bg);
            wrap.setPadding(dp(18), dp(10), dp(18), dp(18));

            // «ручка» сверху
            View handle = new View(getActivity());
            GradientDrawable hg = new GradientDrawable();
            hg.setColor(Color.parseColor("#3B434D"));
            hg.setCornerRadius(dp(3));
            handle.setBackground(hg);
            LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(dp(44), dp(5));
            hp.gravity = Gravity.CENTER_HORIZONTAL;
            hp.bottomMargin = dp(10);
            handle.setLayoutParams(hp);
            wrap.addView(handle);

            LinearLayout head = new LinearLayout(getActivity());
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            TextView t = new TextView(getActivity());
            t.setText(title);
            t.setTextColor(Color.WHITE);
            t.setTextSize(20f);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT);
            tp.weight = 1; t.setLayoutParams(tp);
            TextView close = new TextView(getActivity());
            close.setText("\u2715");
            close.setTextColor(Color.parseColor("#8B949E"));
            close.setTextSize(20f);
            close.setPadding(dp(10), dp(4), dp(6), dp(4));
            close.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { dlg.dismiss(); } });
            head.addView(t); head.addView(close);
            wrap.addView(head);

            View div = new View(getActivity());
            div.setBackgroundColor(Color.parseColor("#232A33"));
            LinearLayout.LayoutParams dvp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
            dvp.topMargin = dp(10); dvp.bottomMargin = dp(4);
            div.setLayoutParams(dvp);
            wrap.addView(div);

            ScrollView sv = new ScrollView(getActivity());
            LinearLayout.LayoutParams svp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            svp.weight = 0; sv.setLayoutParams(svp);
            itemsWrap = new LinearLayout(getActivity());
            itemsWrap.setOrientation(LinearLayout.VERTICAL);
            sv.addView(itemsWrap);
            wrap.addView(sv);

            dlg.setContentView(wrap);
            if (dlg.getWindow() != null) {
                dlg.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                dlg.getWindow().setGravity(Gravity.BOTTOM);
                dlg.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            }
        }

        void item(String label, boolean danger, final Runnable action) {
            TextView t = new TextView(getActivity());
            t.setText(label);
            t.setTextColor(danger ? Color.parseColor("#F85149") : Color.parseColor("#E6EDF3"));
            t.setTextSize(16.5f);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setPadding(dp(6), dp(15), dp(6), dp(15));
            t.setClickable(true);
            t.setFocusable(true);
            t.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { dlg.dismiss(); if (action != null) action.run(); }
            });
            itemsWrap.addView(t);
        }

        void show() { dlg.show(); }
    }
}
