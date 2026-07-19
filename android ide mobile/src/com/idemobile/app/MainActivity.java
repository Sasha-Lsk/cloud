package com.idemobile.app;

import android.app.Activity;
import android.app.Fragment;
import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Главный экран. Нижняя панель из 4 вкладок:
 * 1. Агент  2. Проводник  3. Редактор кода  4. GitHub
 * Переключение содержимого через android.app.Fragment (без внешних библиотек).
 */
public class MainActivity extends Activity {

    private LinearLayout tabAgent, tabExplorer, tabEditor, tabGithub;
    private ImageView icAgent, icExplorer, icEditor, icGithub;
    private TextView txAgent, txExplorer, txEditor, txGithub;

    private final Fragment agentFragment = new AgentFragment();
    private final ExplorerFragment explorerFragment = new ExplorerFragment();
    private final EditorFragment editorFragment = new EditorFragment();
    private final Fragment githubFragment = new GithubFragment();

    private boolean fragmentsAdded = false;
    private int current = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            build();
        } catch (Throwable e) {
            App.writeCrash(e);
            showError(e);
        }
    }

    private void build() {
        setContentView(R.layout.activity_main);

        tabAgent = (LinearLayout) findViewById(R.id.tab_agent);
        tabExplorer = (LinearLayout) findViewById(R.id.tab_explorer);
        tabEditor = (LinearLayout) findViewById(R.id.tab_editor);
        tabGithub = (LinearLayout) findViewById(R.id.tab_github);

        icAgent = (ImageView) findViewById(R.id.tab_agent_icon);
        icExplorer = (ImageView) findViewById(R.id.tab_explorer_icon);
        icEditor = (ImageView) findViewById(R.id.tab_editor_icon);
        icGithub = (ImageView) findViewById(R.id.tab_github_icon);

        txAgent = (TextView) findViewById(R.id.tab_agent_text);
        txExplorer = (TextView) findViewById(R.id.tab_explorer_text);
        txEditor = (TextView) findViewById(R.id.tab_editor_text);
        txGithub = (TextView) findViewById(R.id.tab_github_text);

        tabAgent.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { select(0); }
        });
        tabExplorer.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { select(1); }
        });
        tabEditor.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { select(2); }
        });
        tabGithub.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { select(3); }
        });

        // По умолчанию открыта вкладка "Агент"
        select(0);

        // Если в прошлый раз приложение аварийно завершилось — показать диалог
        // с предложением поделиться отчётом или открыть его.
        maybeShowCrashDialog();
    }

    /**
     * Показывает диалог о предыдущем аварийном завершении (если есть свежий,
     * ещё не показанный отчёт). Из диалога можно поделиться файлом отчёта или
     * открыть его в любом приложении. Флаг «ожидающего» краша сбрасывается,
     * чтобы диалог не всплывал повторно.
     */
    private void maybeShowCrashDialog() {
        final String path = App.pendingCrashPath();
        if (path == null) return;
        final java.io.File report = new java.io.File(path);
        App.clearPendingCrash();
        try {
            new android.app.AlertDialog.Builder(this)
                    .setTitle("Приложение аварийно завершилось")
                    .setMessage("В прошлый раз произошёл сбой. Сохранён подробный отчёт о причинах — "
                            + "им можно поделиться (отправить разработчику) или открыть в любом приложении.")
                    .setPositiveButton("Поделиться", new android.content.DialogInterface.OnClickListener() {
                        public void onClick(android.content.DialogInterface d, int w) {
                            CrashReports.share(MainActivity.this, report);
                        }
                    })
                    .setNeutralButton("Открыть", new android.content.DialogInterface.OnClickListener() {
                        public void onClick(android.content.DialogInterface d, int w) {
                            CrashReports.open(MainActivity.this, report);
                        }
                    })
                    .setNegativeButton("Позже", null)
                    .show();
        } catch (Throwable ignored) {}
    }

    private void select(int index) {
        if (index == current) return;
        current = index;

        FragmentManager fm = getFragmentManager();
        // Первый показ: добавляем все фрагменты один раз и скрываем.
        // Так каждая вкладка сохраняет состояние (открытые файлы редактора,
        // раскрытые папки проводника) при переключении — фрагменты не пересоздаются.
        if (!fragmentsAdded) {
            FragmentTransaction ft = fm.beginTransaction();
            ft.add(R.id.content_container, agentFragment, "agent");
            ft.add(R.id.content_container, explorerFragment, "explorer");
            ft.add(R.id.content_container, editorFragment, "editor");
            ft.add(R.id.content_container, githubFragment, "github");
            ft.hide(explorerFragment);
            ft.hide(editorFragment);
            ft.hide(githubFragment);
            ft.commit();
            fm.executePendingTransactions();
            fragmentsAdded = true;
        }

        Fragment target;
        switch (index) {
            case 1: target = explorerFragment; break;
            case 2: target = editorFragment; break;
            case 3: target = githubFragment; break;
            default: target = agentFragment; break;
        }

        FragmentTransaction ft = fm.beginTransaction();
        ft.hide(agentFragment);
        ft.hide(explorerFragment);
        ft.hide(editorFragment);
        ft.hide(githubFragment);
        ft.show(target);
        ft.commit();

        updateTabs(index);
    }

    private void updateTabs(int index) {
        int active = getResources().getColor(R.color.tab_active);
        int inactive = getResources().getColor(R.color.tab_inactive);

        setTab(icAgent, txAgent, index == 0, active, inactive);
        setTab(icExplorer, txExplorer, index == 1, active, inactive);
        setTab(icEditor, txEditor, index == 2, active, inactive);
        setTab(icGithub, txGithub, index == 3, active, inactive);
    }

    private void setTab(ImageView icon, TextView label, boolean on, int active, int inactive) {
        int c = on ? active : inactive;
        icon.setColorFilter(c);
        label.setTextColor(c);
    }

    /** Открыть файл из проводника в редакторе и переключиться на вкладку «Редактор». */
    public void openInEditor(String path) {
        select(2);
        editorFragment.openFile(path);
    }

    /** Переключиться на вкладку «Проводник» (из пустого состояния редактора). */
    public void switchToExplorer() {
        select(1);
    }

    /**
     * Открыть GitHub-репозиторий в проводнике: переключиться на вкладку
     * «Проводник» и авто-раскрыть цепочку /gh → /gh/owner → /gh/owner/repo,
     * чтобы файлы репозитория были видны сразу (как в web-версии после «OK»).
     */
    public void openGithubRepoInExplorer(String virtualRoot) {
        select(1);
        if (virtualRoot != null) explorerFragment.expandPath(virtualRoot);
    }

    /** Уведомить редактор об удалении пути (из проводника). */
    public void notifyPathDeleted(String path) {
        editorFragment.onPathDeleted(path);
    }

    /** Показывает текст ошибки прямо на экране (вместо тихого закрытия). */
    private void showError(Throwable e) {
        java.io.StringWriter sw = new java.io.StringWriter();
        e.printStackTrace(new java.io.PrintWriter(sw));
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.setBackgroundColor(0xFF0E1116);
        TextView tv = new TextView(this);
        tv.setText("Ошибка запуска (отчёт сохранён, см. Настройки → Отчёты о сбоях):\n\n" + sw.toString());
        tv.setTextColor(0xFFF85149);
        tv.setTextSize(12f);
        tv.setPadding(24, 48, 24, 24);
        tv.setTextIsSelectable(true);
        sv.addView(tv);
        setContentView(sv);
    }
}
