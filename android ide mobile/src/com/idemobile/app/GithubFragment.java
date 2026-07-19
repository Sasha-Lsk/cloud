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
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Вкладка «Git & GitHub» — точная копия web-версии (git.js) на Android.
 *
 * Возможности (как в web):
 *  - Вход в GitHub через Device Flow (6-значный код + системный браузер) ИЛИ
 *    вставкой готового Personal Access Token.
 *  - Карточка авторизации: пользователь, «+ Новый репозиторий», «Обновить список»,
 *    «Выйти».
 *  - Карточка активного репозитория для агента (mode=remote — через GitHub API
 *    без клонирования): «Отменить выбор», «Обновить дерево», «Открыть в проводнике».
 *  - Список репозиториев (public+private) с бейджем PRIVATE/PUBLIC, выбором ветки
 *    (кнопка/выпадающий список), зелёной рамкой выбранного, кнопками
 *    «OK — использовать выбранный», «Клонировать в проводник» (недоступно без git),
 *    «Удалить выбранный репозиторий».
 *  - Локальные копии + «Инициализировать /project».
 *
 * После «OK» репозиторий монтируется как виртуальная ФС /gh/owner/repo и
 * происходит авто-переход в проводник, где им можно управлять синхронно с сайтом.
 */
public class GithubFragment extends Fragment {

    private Store store;
    private LinearLayout body;      // контейнер карточек (перерисовывается)
    private final Handler ui = new Handler();

    // Черновой выбор репозитория (до нажатия OK). Аналог draftRepo в git.js.
    private JSONObject draftRepo;   // {fullName,owner,name,cloneUrl,branch,defaultBranch,private}
    private JSONArray remoteRepos;  // кеш /user/repos
    private final java.util.Map<String, List<String>> branchesCache = new java.util.HashMap<String, List<String>>();
    private String repoSearch = ""; // текущий фильтр поиска

    // Активное device-flow ожидание (чтобы остановить при уходе).
    private volatile boolean deviceFlowActive = false;

    // ------------------------------------------------------------- lifecycle
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle saved) {
        try {
            return buildView();
        } catch (Throwable e) {
            App.writeCrash(e);
            java.io.StringWriter sw = new java.io.StringWriter();
            e.printStackTrace(new java.io.PrintWriter(sw));
            TextView tv = new TextView(getActivity());
            tv.setText("Ошибка вкладки «GitHub»:\n\n" + sw.toString());
            tv.setTextColor(Color.parseColor("#F85149"));
            tv.setTextSize(12f);
            tv.setPadding(24, 24, 24, 24);
            tv.setTextIsSelectable(true);
            ScrollView s = new ScrollView(getActivity());
            s.addView(tv);
            return s;
        }
    }

    private int dp(int v) { return UiKit.dp(getActivity(), v); }
    private void toast(String s) {
        if (getActivity() != null) Toast.makeText(getActivity(), s, Toast.LENGTH_SHORT).show();
    }
    private void runUi(Runnable r) { if (getActivity() != null) getActivity().runOnUiThread(r); }

    private View buildView() {
        store = Store.get(getActivity());

        LinearLayout rootCol = new LinearLayout(getActivity());
        rootCol.setOrientation(LinearLayout.VERTICAL);
        rootCol.setBackgroundColor(Color.parseColor("#0E1116"));
        rootCol.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Заголовок вкладки.
        LinearLayout topBar = new LinearLayout(getActivity());
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackgroundColor(Color.parseColor("#161B22"));
        topBar.setPadding(dp(16), dp(14), dp(16), dp(14));
        TextView title = new TextView(getActivity());
        title.setText("Git & GitHub");
        title.setTextColor(Color.WHITE);
        title.setTextSize(19f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.weight = 1; title.setLayoutParams(tlp);
        topBar.addView(title);
        rootCol.addView(topBar);

        // Подзаголовок-панель «GIT & GITHUB» + refresh.
        LinearLayout subBar = new LinearLayout(getActivity());
        subBar.setOrientation(LinearLayout.HORIZONTAL);
        subBar.setGravity(Gravity.CENTER_VERTICAL);
        subBar.setBackgroundColor(Color.parseColor("#161B22"));
        subBar.setPadding(dp(16), dp(8), dp(16), dp(8));
        TextView sub = new TextView(getActivity());
        sub.setText("GIT & GITHUB");
        sub.setTextColor(Color.parseColor("#8B949E"));
        sub.setTextSize(13f);
        sub.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.weight = 1; sub.setLayoutParams(slp);
        subBar.addView(sub);
        TextView refresh = new TextView(getActivity());
        refresh.setText("\u21BB");
        refresh.setTextColor(Color.parseColor("#8B949E"));
        refresh.setTextSize(22f);
        refresh.setPadding(dp(10), dp(2), dp(6), dp(2));
        refresh.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { remoteRepos = null; branchesCache.clear(); render(); toast("Обновлено"); }
        });
        subBar.addView(refresh);
        rootCol.addView(subBar);

        View div = new View(getActivity());
        div.setBackgroundColor(Color.parseColor("#232A33"));
        rootCol.addView(div, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        // Прокручиваемое тело.
        ScrollView scroll = new ScrollView(getActivity());
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        body = new LinearLayout(getActivity());
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(12), dp(16), dp(24));
        scroll.addView(body);
        rootCol.addView(scroll);

        render();
        return rootCol;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (body != null) render();
    }

    // ============================================================== RENDER
    private void render() {
        if (body == null) return;
        body.removeAllViews();
        String user = store.githubUser();
        String token = store.githubToken();

        // ---- Карточка авторизации ----
        LinearLayout authCard = card();
        authCard.addView(head("GITHUB"));
        if (user != null && !user.isEmpty()) {
            TextView u = new TextView(getActivity());
            u.setText("Пользователь: ");
            u.setTextColor(Color.parseColor("#E6EDF3"));
            u.setTextSize(15f);
            TextView ub = new TextView(getActivity());
            ub.setText(user);
            ub.setTextColor(Color.WHITE);
            ub.setTextSize(15f);
            ub.setTypeface(Typeface.DEFAULT_BOLD);
            LinearLayout urow = new LinearLayout(getActivity());
            urow.setOrientation(LinearLayout.HORIZONTAL);
            urow.addView(u); urow.addView(ub);
            authCard.addView(urow);

            LinearLayout acts = flowRow();
            acts.addView(btn("+ Новый репозиторий", "primary", new Runnable() {
                public void run() { createRepoDialog(); }
            }));
            acts.addView(btn("Обновить список", "ghost", new Runnable() {
                public void run() { remoteRepos = null; branchesCache.clear(); render(); }
            }));
            authCard.addView(acts);
            LinearLayout out = new LinearLayout(getActivity());
            out.setPadding(0, dp(6), 0, 0);
            out.addView(btn("Выйти", "danger", new Runnable() {
                public void run() { logoutGithub(); }
            }));
            authCard.addView(out);
        } else {
            TextView hint = new TextView(getActivity());
            hint.setText("Не авторизован. Вход открывает system browser.");
            hint.setTextColor(Color.parseColor("#8B949E"));
            hint.setTextSize(14f);
            hint.setPadding(0, 0, 0, dp(10));
            authCard.addView(hint);
            authCard.addView(btn("Войти в GitHub", "primary", new Runnable() {
                public void run() { githubLoginMenu(); }
            }));
        }
        body.addView(authCard);

        // ---- Карточка активного репозитория ----
        JSONObject ar = store.activeRepo();
        if (ar != null) {
            LinearLayout ac = card();
            ac.addView(head("АКТИВНЫЙ РЕПОЗИТОРИЙ ДЛЯ АГЕНТА"));
            TextView fn = new TextView(getActivity());
            fn.setText(ar.optString("fullName"));
            fn.setTextColor(Color.WHITE);
            fn.setTextSize(15f);
            fn.setTypeface(Typeface.DEFAULT_BOLD);
            ac.addView(fn);
            String modeLabel = "remote".equals(ar.optString("mode"))
                    ? "через GitHub API (без клонирования)"
                    : "локальная копия: " + ar.optString("localDir", "—");
            TextView meta = new TextView(getActivity());
            meta.setText("ветка: " + ar.optString("branch", "?") + "  ·  " + modeLabel);
            meta.setTextColor(Color.parseColor("#8B949E"));
            meta.setTextSize(12f);
            ac.addView(meta);
            TextView rootv = new TextView(getActivity());
            rootv.setText("корень: " + ar.optString("virtualRoot", ar.optString("localDir", "")));
            rootv.setTextColor(Color.parseColor("#8B949E"));
            rootv.setTextSize(12f);
            ac.addView(rootv);

            LinearLayout acts = flowRow();
            acts.addView(btn("Отменить выбор", "ghost", new Runnable() {
                public void run() { cancelActiveRepo(); }
            }));
            if ("remote".equals(ar.optString("mode"))) {
                acts.addView(btn("Обновить дерево", "ghost", new Runnable() {
                    public void run() { refreshTree(); }
                }));
            }
            ac.addView(acts);
            LinearLayout openRow = new LinearLayout(getActivity());
            openRow.setPadding(0, dp(6), 0, 0);
            openRow.addView(btn("Открыть в проводнике", "primary", new Runnable() {
                public void run() { switchToExplorer(); }
            }));
            ac.addView(openRow);
            body.addView(ac);
        }

        // ---- Список удалённых репозиториев ----
        if (token != null && !token.isEmpty()) {
            renderRemoteRepos(token);
        }

        // ---- Локальные копии (задел под isomorphic-git) ----
        LinearLayout localCard = card();
        localCard.addView(head("ЛОКАЛЬНЫЕ КОПИИ (0)"));
        TextView empty = new TextView(getActivity());
        empty.setText("Нет клонированных репозиториев.");
        empty.setTextColor(Color.parseColor("#8B949E"));
        empty.setTextSize(14f);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(0, dp(6), 0, dp(10));
        localCard.addView(empty);
        localCard.addView(btn("Инициализировать /project", "ghost", new Runnable() {
            public void run() { initProjectDialog(); }
        }));
        body.addView(localCard);
    }

    // ============================================================== UI helpers
    private LinearLayout card() {
        LinearLayout c = new LinearLayout(getActivity());
        c.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#12161C"));
        bg.setCornerRadius(dp(10));
        bg.setStroke(dp(1), Color.parseColor("#232A33"));
        c.setBackground(bg);
        c.setPadding(dp(14), dp(12), dp(14), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(14);
        c.setLayoutParams(lp);
        return c;
    }
    private TextView head(String text) {
        TextView t = new TextView(getActivity());
        t.setText(text);
        t.setTextColor(Color.parseColor("#8B949E"));
        t.setTextSize(12f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, 0, 0, dp(8));
        return t;
    }
    /** Ряд с переносом (кнопки в строку, но без реального flow — просто горизонтальный). */
    private LinearLayout flowRow() {
        LinearLayout r = new LinearLayout(getActivity());
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(0, dp(4), 0, 0);
        return r;
    }
    private TextView btn(String text, String variant, final Runnable onClick) {
        TextView b = UiKit.button(getActivity(), text, "ghost".equals(variant) ? "normal" : variant);
        b.setTextSize(13.5f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(6);
        lp.topMargin = dp(4);
        b.setLayoutParams(lp);
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { if (onClick != null) onClick.run(); }
        });
        return b;
    }
    /** Кнопка на всю ширину (для футера списка). */
    private TextView wideBtn(String text, String variant, final Runnable onClick) {
        TextView b = UiKit.button(getActivity(), text, "ghost".equals(variant) ? "normal" : variant);
        b.setTextSize(15f);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { if (onClick != null) onClick.run(); }
        });
        return b;
    }

    // ============================================================== remote repos
    private void renderRemoteRepos(final String token) {
        final LinearLayout remoteCard = card();
        final TextView hdr = head("МОИ РЕПОЗИТОРИИ НА GITHUB");
        remoteCard.addView(hdr);
        final LinearLayout listWrap = new LinearLayout(getActivity());
        listWrap.setOrientation(LinearLayout.VERTICAL);
        remoteCard.addView(listWrap);
        body.addView(remoteCard);

        if (remoteRepos == null) {
            TextView loading = new TextView(getActivity());
            loading.setText("Загружаем репозитории…");
            loading.setTextColor(Color.parseColor("#8B949E"));
            loading.setTextSize(14f);
            loading.setPadding(0, dp(6), 0, dp(6));
            listWrap.addView(loading);
            new Thread(new Runnable() {
                public void run() {
                    try {
                        final JSONArray fetched = GithubApi.fetchAllRepos(token);
                        runUi(new Runnable() { public void run() {
                            remoteRepos = fetched;
                            render();
                        }});
                    } catch (final Exception e) {
                        runUi(new Runnable() { public void run() {
                            listWrap.removeAllViews();
                            TextView err = new TextView(getActivity());
                            err.setText("Ошибка: " + e.getMessage());
                            err.setTextColor(Color.parseColor("#F85149"));
                            err.setTextSize(13f);
                            listWrap.addView(err);
                        }});
                    }
                }
            }).start();
            return;
        }

        hdr.setText("МОИ РЕПОЗИТОРИИ НА GITHUB (" + remoteRepos.length() + ")");
        if (remoteRepos.length() == 0) {
            TextView none = new TextView(getActivity());
            none.setText("Репозиториев не найдено");
            none.setTextColor(Color.parseColor("#8B949E"));
            none.setTextSize(14f);
            listWrap.addView(none);
            return;
        }

        // Поле поиска.
        final EditText search = new EditText(getActivity());
        search.setSingleLine(true);
        search.setHint("Поиск по названию…");
        search.setText(repoSearch);
        search.setTextColor(Color.parseColor("#E6EDF3"));
        search.setHintTextColor(Color.parseColor("#5B6570"));
        search.setTextSize(14f);
        GradientDrawable sbg = new GradientDrawable();
        sbg.setColor(Color.parseColor("#1C222B"));
        sbg.setCornerRadius(dp(8));
        sbg.setStroke(dp(1), Color.parseColor("#2A313B"));
        search.setBackground(sbg);
        search.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = dp(6); sp.bottomMargin = dp(6);
        search.setLayoutParams(sp);
        listWrap.addView(search);

        final LinearLayout rowsHolder = new LinearLayout(getActivity());
        rowsHolder.setOrientation(LinearLayout.VERTICAL);
        listWrap.addView(rowsHolder);
        paintRepoRows(rowsHolder, token);

        search.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(android.text.Editable e) {
                repoSearch = e.toString();
                paintRepoRows(rowsHolder, token);
            }
        });

        // Футер: OK / Клонировать / Удалить.
        remoteCard.addView(wideBtn("OK — использовать выбранный репозиторий", "primary", new Runnable() {
            public void run() { confirmSelectionRemote(); }
        }));
        remoteCard.addView(wideBtn("Клонировать в проводник", "ghost", new Runnable() {
            public void run() { toast("Клонирование через git недоступно в этой сборке — используйте «OK» (работа через GitHub API)"); }
        }));
        remoteCard.addView(wideBtn("Удалить выбранный репозиторий", "danger", new Runnable() {
            public void run() { deleteSelectedRepo(); }
        }));
    }

    private void paintRepoRows(LinearLayout holder, String token) {
        holder.removeAllViews();
        String q = repoSearch.trim().toLowerCase();
        for (int i = 0; i < remoteRepos.length(); i++) {
            JSONObject r = remoteRepos.optJSONObject(i);
            if (r == null) continue;
            if (!q.isEmpty() && !r.optString("full_name").toLowerCase().contains(q)) continue;
            holder.addView(remoteRepoRow(r, token, holder));
        }
    }

    /** Одна строка репозитория: имя + бейдж + выбор ветки + зелёная рамка выбранного. */
    private View remoteRepoRow(final JSONObject repo, final String token, final LinearLayout holder) {
        final String fullName = repo.optString("full_name");
        final boolean isSel = draftRepo != null && fullName.equals(draftRepo.optString("fullName"));

        LinearLayout row = new LinearLayout(getActivity());
        row.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#171C23"));
        bg.setCornerRadius(dp(10));
        bg.setStroke(dp(isSel ? 2 : 1), Color.parseColor(isSel ? "#2EA043" : "#232A33"));
        row.setBackground(bg);
        row.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(6);
        row.setLayoutParams(rlp);

        // Верхняя строка: имя + PRIVATE/PUBLIC.
        LinearLayout top = new LinearLayout(getActivity());
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = new TextView(getActivity());
        name.setText(fullName);
        name.setTextColor(Color.WHITE);
        name.setTextSize(15f);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.weight = 1; name.setLayoutParams(nlp);
        top.addView(name);
        boolean priv = repo.optBoolean("private");
        TextView badge = new TextView(getActivity());
        badge.setText(priv ? "PRIVATE" : "PUBLIC");
        badge.setTextSize(10f);
        badge.setTextColor(Color.parseColor(priv ? "#D29922" : "#8B949E"));
        GradientDrawable bbg = new GradientDrawable();
        bbg.setCornerRadius(dp(10));
        bbg.setStroke(dp(1), Color.parseColor(priv ? "#D29922" : "#8B949E"));
        badge.setBackground(bbg);
        badge.setPadding(dp(8), dp(2), dp(8), dp(2));
        top.addView(badge);
        // Иконка переименования репозитория.
        android.widget.ImageView repoRename = new android.widget.ImageView(getActivity());
        repoRename.setImageResource(R.drawable.ic_pencil);
        repoRename.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        int rp = dp(6);
        repoRename.setPadding(rp, rp, rp, rp);
        LinearLayout.LayoutParams rrlp = new LinearLayout.LayoutParams(dp(32), dp(32));
        rrlp.leftMargin = dp(6);
        repoRename.setLayoutParams(rrlp);
        repoRename.setBackgroundResource(R.drawable.btn_normal);
        repoRename.setClickable(true);
        repoRename.setContentDescription("Переименовать репозиторий");
        repoRename.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { renameRepoDialog(repo); }
        });
        top.addView(repoRename);
        row.addView(top);

        // Описание.
        String desc = repo.optString("description", "");
        if (!desc.isEmpty() && !"null".equals(desc)) {
            TextView d = new TextView(getActivity());
            d.setText(desc);
            d.setTextColor(Color.parseColor("#8B949E"));
            d.setTextSize(11f);
            d.setSingleLine(true);
            d.setEllipsize(android.text.TextUtils.TruncateAt.END);
            d.setPadding(0, dp(4), 0, 0);
            row.addView(d);
        }

        // Строка ветки: подпись + текущая ветка + кнопка разворачивания списка веток.
        final String defBranch = repo.optString("default_branch", "main");
        final String[] curBranch = { isSel ? draftRepo.optString("branch", defBranch) : defBranch };
        LinearLayout brRow = new LinearLayout(getActivity());
        brRow.setOrientation(LinearLayout.HORIZONTAL);
        brRow.setGravity(Gravity.CENTER_VERTICAL);
        brRow.setPadding(0, dp(8), 0, 0);
        TextView brLabel = new TextView(getActivity());
        brLabel.setText("branch:");
        brLabel.setTextColor(Color.parseColor("#8B949E"));
        brLabel.setTextSize(12f);
        brLabel.setPadding(0, 0, dp(8), 0);
        brRow.addView(brLabel);
        final TextView brBtn = new TextView(getActivity());
        brBtn.setText(branchLabel(curBranch[0], defBranch));
        brBtn.setTextColor(Color.parseColor("#E6EDF3"));
        brBtn.setTextSize(13f);
        GradientDrawable brbg = new GradientDrawable();
        brbg.setColor(Color.parseColor("#1C222B"));
        brbg.setCornerRadius(dp(8));
        brbg.setStroke(dp(1), Color.parseColor("#2A313B"));
        brBtn.setBackground(brbg);
        brBtn.setPadding(dp(12), dp(8), dp(12), dp(8));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.weight = 1; brBtn.setLayoutParams(blp);
        brRow.addView(brBtn);
        // Кнопка ▾ — развернуть/свернуть список веток.
        final TextView brToggle = new TextView(getActivity());
        brToggle.setText("\u25BE");
        brToggle.setTextColor(Color.parseColor("#8B949E"));
        brToggle.setTextSize(14f);
        brToggle.setPadding(dp(10), dp(8), dp(10), dp(8));
        brRow.addView(brToggle);
        row.addView(brRow);

        // Разворачиваемый список веток (с иконками переименования/удаления).
        final LinearLayout brList = new LinearLayout(getActivity());
        brList.setOrientation(LinearLayout.VERTICAL);
        brList.setPadding(0, dp(6), 0, 0);
        brList.setVisibility(View.GONE);
        brList.setClickable(true); // поглощаем клики по фону списка (не переключаем выбор репо)
        row.addView(brList);

        final Runnable[] refreshList = new Runnable[1];
        // Заполнение списка веток (использует кеш; параметр force перезагружает с GitHub).
        final BranchListFiller filler = new BranchListFiller(fullName, token, defBranch, curBranch, brBtn, brList) {
            void onBranchPicked(String br) {
                if (draftRepo != null && fullName.equals(draftRepo.optString("fullName")))
                    try { draftRepo.put("branch", br); } catch (Exception ignored) {}
            }
            void reload() { if (refreshList[0] != null) refreshList[0].run(); }
        };
        refreshList[0] = new Runnable() { public void run() { filler.fill(true); } };

        final boolean[] expanded = { false };
        View.OnClickListener toggle = new View.OnClickListener() {
            public void onClick(View v) {
                expanded[0] = !expanded[0];
                brToggle.setText(expanded[0] ? "\u25B4" : "\u25BE");
                brList.setVisibility(expanded[0] ? View.VISIBLE : View.GONE);
                if (expanded[0]) filler.fill(false);
            }
        };
        brToggle.setOnClickListener(toggle);
        brBtn.setOnClickListener(toggle);

        // Клик по строке (не по ветке) — переключить выбор.
        top.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { toggleSelect(repo, curBranch[0], defBranch, holder, token); }
        });
        name.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { toggleSelect(repo, curBranch[0], defBranch, holder, token); }
        });
        row.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { toggleSelect(repo, curBranch[0], defBranch, holder, token); }
        });
        return row;
    }

    private String branchLabel(String br, String def) {
        return br + (br.equals(def) ? " (default)" : "");
    }

    private void toggleSelect(JSONObject repo, String branch, String defBranch, LinearLayout holder, String token) {
        String fullName = repo.optString("full_name");
        if (draftRepo != null && fullName.equals(draftRepo.optString("fullName"))) {
            draftRepo = null;
        } else {
            String[] parts = fullName.split("/", 2);
            draftRepo = new JSONObject();
            try {
                draftRepo.put("fullName", fullName);
                draftRepo.put("owner", parts.length > 0 ? parts[0] : "");
                draftRepo.put("name", parts.length > 1 ? parts[1] : fullName);
                draftRepo.put("cloneUrl", repo.optString("clone_url"));
                draftRepo.put("branch", branch != null ? branch : defBranch);
                draftRepo.put("defaultBranch", defBranch);
                draftRepo.put("private", repo.optBoolean("private"));
            } catch (Exception ignored) {}
        }
        paintRepoRows(holder, token);
    }

    /**
     * Наполняет разворачиваемый список веток: у каждой ветки — название (клик =
     * выбрать) и иконки переименования/удаления. Использует branchesCache; при
     * force=true перезагружает список с GitHub.
     */
    private abstract class BranchListFiller {
        final String fullName, token, defBranch;
        final String[] curBranch;
        final TextView brBtn;
        final LinearLayout brList;
        BranchListFiller(String fullName, String token, String defBranch, String[] curBranch,
                         TextView brBtn, LinearLayout brList) {
            this.fullName = fullName; this.token = token; this.defBranch = defBranch;
            this.curBranch = curBranch; this.brBtn = brBtn; this.brList = brList;
        }
        abstract void onBranchPicked(String br);
        abstract void reload();

        void fill(boolean force) {
            List<String> cached = branchesCache.get(fullName);
            if (cached != null && !force) { paint(cached); return; }
            brList.removeAllViews();
            TextView loading = new TextView(getActivity());
            loading.setText("загрузка веток…");
            loading.setTextColor(Color.parseColor("#8B949E"));
            loading.setTextSize(12f);
            loading.setPadding(dp(6), dp(4), dp(6), dp(4));
            brList.addView(loading);
            new Thread(new Runnable() { public void run() {
                try {
                    final List<String> brs = GithubApi.fetchBranches(token, fullName);
                    branchesCache.put(fullName, brs);
                    runUi(new Runnable() { public void run() { paint(brs); } });
                } catch (final Exception e) {
                    runUi(new Runnable() { public void run() {
                        brList.removeAllViews();
                        TextView err = new TextView(getActivity());
                        err.setText("ошибка загрузки веток: " + e.getMessage());
                        err.setTextColor(Color.parseColor("#F85149"));
                        err.setTextSize(12f);
                        err.setPadding(dp(6), dp(4), dp(6), dp(4));
                        brList.addView(err);
                    }});
                }
            }}).start();
        }

        void paint(List<String> brs) {
            brList.removeAllViews();
            List<String> uniq = new ArrayList<String>();
            for (String b : brs) if (!uniq.contains(b)) uniq.add(b);
            if (!uniq.contains(curBranch[0]) && curBranch[0] != null && !curBranch[0].isEmpty())
                uniq.add(0, curBranch[0]);
            if (uniq.isEmpty()) {
                TextView none = new TextView(getActivity());
                none.setText("нет веток");
                none.setTextColor(Color.parseColor("#8B949E"));
                none.setTextSize(12f);
                none.setPadding(dp(6), dp(4), dp(6), dp(4));
                brList.addView(none);
                return;
            }
            for (final String b : uniq) {
                boolean isCur = b.equals(curBranch[0]);
                boolean isDef = b.equals(defBranch);
                LinearLayout item = new LinearLayout(getActivity());
                item.setOrientation(LinearLayout.HORIZONTAL);
                item.setGravity(Gravity.CENTER_VERTICAL);
                item.setPadding(dp(6), dp(4), dp(6), dp(4));
                if (isCur) {
                    GradientDrawable ib = new GradientDrawable();
                    ib.setColor(Color.parseColor("#14321F"));
                    ib.setCornerRadius(dp(6));
                    item.setBackground(ib);
                }
                final TextView nm = new TextView(getActivity());
                nm.setText(b + (isDef ? " (default)" : ""));
                nm.setTextColor(Color.parseColor(isCur ? "#3FB950" : "#E6EDF3"));
                nm.setTextSize(13f);
                nm.setSingleLine(true);
                nm.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT);
                nlp.weight = 1; nm.setLayoutParams(nlp);
                nm.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        curBranch[0] = b;
                        brBtn.setText(branchLabel(b, defBranch));
                        onBranchPicked(b);
                        paint(branchesCache.get(fullName));
                    }
                });
                item.addView(nm);
                // Иконка переименования ветки.
                android.widget.ImageView ren = new android.widget.ImageView(getActivity());
                ren.setImageResource(R.drawable.ic_pencil);
                ren.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
                ren.setPadding(dp(6), dp(6), dp(6), dp(6));
                ren.setLayoutParams(new LinearLayout.LayoutParams(dp(32), dp(32)));
                ren.setBackgroundResource(R.drawable.btn_normal);
                ren.setClickable(true);
                ren.setContentDescription("Переименовать ветку");
                ren.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) { renameBranchDialog(fullName, token, b, curBranch, brBtn, defBranch, new Runnable() {
                        public void run() { reload(); }
                    }); }
                });
                item.addView(ren);
                // Иконка удаления ветки.
                android.widget.ImageView del = new android.widget.ImageView(getActivity());
                del.setImageResource(R.drawable.ic_delete_w);
                del.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
                del.setPadding(dp(6), dp(6), dp(6), dp(6));
                LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(32), dp(32));
                dlp.leftMargin = dp(4);
                del.setLayoutParams(dlp);
                del.setBackgroundResource(R.drawable.btn_danger);
                del.setClickable(true);
                del.setContentDescription("Удалить ветку");
                del.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) { deleteBranchDialog(fullName, token, b, new Runnable() {
                        public void run() { reload(); }
                    }); }
                });
                item.addView(del);
                brList.addView(item);
            }
        }
    }

    /** Диалог переименования репозитория. */
    private void renameRepoDialog(final JSONObject repo) {
        final String fullName = repo.optString("full_name");
        String[] parts = fullName.split("/", 2);
        final String oldName = parts.length > 1 ? parts[1] : fullName;
        final EditText in = new EditText(getActivity());
        in.setText(oldName);
        in.setSelectAllOnFocus(true);
        in.setSingleLine(true);
        new AlertDialog.Builder(getActivity())
                .setTitle("Переименовать репозиторий")
                .setView(pad(in))
                .setPositiveButton("Переименовать", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        final String nn = in.getText().toString().trim();
                        if (nn.isEmpty() || nn.equals(oldName)) return;
                        toast("Переименование…");
                        new Thread(new Runnable() { public void run() {
                            try {
                                final JSONObject upd = GithubApi.renameRepo(store.githubToken(), fullName, nn);
                                runUi(new Runnable() { public void run() {
                                    toast("Переименован в " + upd.optString("full_name"));
                                    JSONObject ar = store.activeRepo();
                                    if (ar != null && fullName.equals(ar.optString("fullName"))) {
                                        try { ar.put("fullName", upd.optString("full_name")); ar.put("repo", upd.optString("name")); } catch (Exception ignored) {}
                                        store.setActiveRepo(ar);
                                    }
                                    remoteRepos = null; branchesCache.clear();
                                    if (draftRepo != null && fullName.equals(draftRepo.optString("fullName"))) draftRepo = null;
                                    render();
                                }});
                            } catch (final Exception e) {
                                runUi(new Runnable() { public void run() { toast("Ошибка: " + e.getMessage()); }});
                            }
                        }}).start();
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    /** Диалог переименования ветки. */
    private void renameBranchDialog(final String fullName, final String token, final String branch,
                                    final String[] curBranch, final TextView brBtn, final String defBranch,
                                    final Runnable onDone) {
        final EditText in = new EditText(getActivity());
        in.setText(branch);
        in.setSelectAllOnFocus(true);
        in.setSingleLine(true);
        new AlertDialog.Builder(getActivity())
                .setTitle("Переименовать ветку «" + branch + "»")
                .setView(pad(in))
                .setPositiveButton("Переименовать", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        final String nn = in.getText().toString().trim();
                        if (nn.isEmpty() || nn.equals(branch)) return;
                        toast("Переименование ветки…");
                        new Thread(new Runnable() { public void run() {
                            try {
                                GithubApi.renameBranch(token, fullName, branch, nn);
                                branchesCache.remove(fullName);
                                runUi(new Runnable() { public void run() {
                                    toast("Ветка → " + nn);
                                    if (branch.equals(curBranch[0])) {
                                        curBranch[0] = nn;
                                        brBtn.setText(branchLabel(nn, defBranch));
                                        if (draftRepo != null && fullName.equals(draftRepo.optString("fullName")))
                                            try { draftRepo.put("branch", nn); } catch (Exception ignored) {}
                                    }
                                    if (onDone != null) onDone.run();
                                }});
                            } catch (final Exception e) {
                                runUi(new Runnable() { public void run() { toast("Ошибка: " + e.getMessage()); }});
                            }
                        }}).start();
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    /** Диалог удаления ветки (с подтверждением). */
    private void deleteBranchDialog(final String fullName, final String token, final String branch, final Runnable onDone) {
        new AlertDialog.Builder(getActivity())
                .setTitle("Удалить ветку")
                .setMessage("Удалить ветку «" + branch + "» из " + fullName + "?\n\nДействие необратимо.")
                .setPositiveButton("Удалить", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        toast("Удаление ветки…");
                        new Thread(new Runnable() { public void run() {
                            try {
                                GithubApi.deleteBranch(token, fullName, branch);
                                branchesCache.remove(fullName);
                                runUi(new Runnable() { public void run() {
                                    toast("Ветка удалена: " + branch);
                                    if (onDone != null) onDone.run();
                                }});
                            } catch (final Exception e) {
                                runUi(new Runnable() { public void run() { toast("Ошибка: " + e.getMessage()); }});
                            }
                        }}).start();
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    /** Обёртка вью с отступами для диалога. */
    private LinearLayout pad(View v) {
        LinearLayout wrap = new LinearLayout(getActivity());
        wrap.setPadding(dp(20), dp(8), dp(20), dp(4));
        wrap.addView(v, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return wrap;
    }

    interface BranchCb { void onPick(String branch); }

    /** Диалог выбора ветки (грузит список с GitHub). Порт applyBranches + select. */
    private void showBranchPicker(final String fullName, final String token, final String defBranch,
                                  final String current, final BranchCb cb) {
        List<String> cached = branchesCache.get(fullName);
        if (cached != null) { showBranchList(cached, defBranch, current, cb); return; }
        final AlertDialog loading = new AlertDialog.Builder(getActivity())
                .setMessage("Загрузка веток…").setCancelable(true).create();
        loading.show();
        new Thread(new Runnable() {
            public void run() {
                try {
                    final List<String> brs = GithubApi.fetchBranches(token, fullName);
                    branchesCache.put(fullName, brs);
                    runUi(new Runnable() { public void run() {
                        loading.dismiss();
                        showBranchList(brs, defBranch, current, cb);
                    }});
                } catch (final Exception e) {
                    runUi(new Runnable() { public void run() {
                        loading.dismiss();
                        toast("Ошибка загрузки веток: " + e.getMessage());
                    }});
                }
            }
        }).start();
    }

    private void showBranchList(List<String> brs, String defBranch, String current, final BranchCb cb) {
        final List<String> uniq = new ArrayList<String>();
        for (String b : brs) if (!uniq.contains(b)) uniq.add(b);
        if (!uniq.contains(defBranch)) uniq.add(0, defBranch);
        final String[] labels = new String[uniq.size()];
        int checked = 0;
        for (int i = 0; i < uniq.size(); i++) {
            labels[i] = uniq.get(i) + (uniq.get(i).equals(defBranch) ? " (default)" : "");
            if (uniq.get(i).equals(current)) checked = i;
        }
        new AlertDialog.Builder(getActivity())
                .setTitle("Выберите ветку")
                .setSingleChoiceItems(labels, checked, new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int which) {
                        d.dismiss();
                        cb.onPick(uniq.get(which));
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    // ============================================================== login
    /** Меню входа: Device Flow (6-значный код) или вставить готовый токен. */
    private void githubLoginMenu() {
        final String[] items = {
                "\uD83C\uDF10  Device Flow с 6-значным кодом",
                "\uD83D\uDCCB  Вставить готовый токен"
        };
        new AlertDialog.Builder(getActivity())
                .setTitle("Вход в GitHub")
                .setItems(items, new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int which) {
                        if (which == 0) startDeviceFlow();
                        else pasteTokenDialog();
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    /** Ручной ввод Personal Access Token. Порт App.githubLoginBrowserPAT (вставка). */
    private void pasteTokenDialog() {
        final EditText et = new EditText(getActivity());
        et.setSingleLine(true);
        et.setHint("ghp_…");
        et.setTextColor(Color.parseColor("#E6EDF3"));
        et.setHintTextColor(Color.parseColor("#5B6570"));
        LinearLayout w = new LinearLayout(getActivity());
        w.setOrientation(LinearLayout.VERTICAL);
        w.setPadding(dp(20), dp(8), dp(20), dp(4));
        TextView lbl = new TextView(getActivity());
        lbl.setText("Personal Access Token");
        lbl.setTextColor(Color.parseColor("#8B949E"));
        lbl.setTextSize(13f);
        lbl.setPadding(0, 0, 0, dp(6));
        w.addView(lbl);
        w.addView(et);
        new AlertDialog.Builder(getActivity())
                .setTitle("Ручной ввод токена")
                .setView(w)
                .setPositiveButton("Войти", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int wi) {
                        String tok = et.getText().toString().trim();
                        if (tok.isEmpty()) { toast("Токен пуст"); return; }
                        verifyAndSaveToken(tok);
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    /** Проверяет токен (GET /user), сохраняет пользователя + scope, перерисовывает. */
    private void verifyAndSaveToken(final String token) {
        final AlertDialog loading = new AlertDialog.Builder(getActivity())
                .setMessage("Проверка токена…").setCancelable(false).create();
        loading.show();
        new Thread(new Runnable() {
            public void run() {
                try {
                    final JSONObject u = GithubApi.fetchUser(token);
                    runUi(new Runnable() { public void run() {
                        loading.dismiss();
                        store.setGithubToken(token);
                        store.setGithubUser(u.optString("login"));
                        store.setGithubScopes(u.optJSONArray("__scopes"));
                        remoteRepos = null; branchesCache.clear();
                        toast("Вход выполнен: " + u.optString("login"));
                        render();
                    }});
                } catch (final Exception e) {
                    runUi(new Runnable() { public void run() {
                        loading.dismiss();
                        toast("Ошибка токена: " + e.getMessage());
                    }});
                }
            }
        }).start();
    }

    // ---------- Device Flow ----------
    private AlertDialog deviceDialog;
    private TextView deviceCodeView, deviceStatusView;
    private String deviceUserCode, deviceVerifyUrl;

    /**
     * Запускает Device Flow: сразу открывает системный браузер на странице
     * подтверждения GitHub (с подставленным кодом) и показывает 6-значный код
     * в диалоге; в фоне опрашивает access_token.
     */
    private void startDeviceFlow() {
        final String clientId = store.githubClientId().isEmpty()
                ? GithubApi.DEFAULT_CLIENT_ID : store.githubClientId();
        final AlertDialog loading = new AlertDialog.Builder(getActivity())
                .setMessage("Запрашиваем код у GitHub…").setCancelable(false).create();
        loading.show();
        new Thread(new Runnable() {
            public void run() {
                try {
                    final GithubApi.DeviceCode dc = GithubApi.requestDeviceCode(clientId, GithubApi.DEFAULT_SCOPE);
                    runUi(new Runnable() { public void run() {
                        loading.dismiss();
                        deviceUserCode = dc.userCode;
                        deviceVerifyUrl = dc.verificationUriComplete;
                        showDeviceDialog(dc);
                        // Сразу открываем системный браузер на странице подтверждения.
                        openBrowser(dc.verificationUriComplete);
                        // Запускаем опрос в фоне.
                        pollDeviceFlow(clientId, dc);
                    }});
                } catch (final Exception e) {
                    runUi(new Runnable() { public void run() {
                        loading.dismiss();
                        toast("Device Flow: " + e.getMessage());
                    }});
                }
            }
        }).start();
    }

    private void showDeviceDialog(GithubApi.DeviceCode dc) {
        LinearLayout w = new LinearLayout(getActivity());
        w.setOrientation(LinearLayout.VERTICAL);
        w.setPadding(dp(20), dp(12), dp(20), dp(8));

        TextView warn = new TextView(getActivity());
        warn.setText("\u26A0 Используется встроенный (публичный) Client ID.");
        warn.setTextColor(Color.parseColor("#D29922"));
        warn.setTextSize(13f);
        GradientDrawable wbg = new GradientDrawable();
        wbg.setColor(Color.parseColor("#2A2410"));
        wbg.setCornerRadius(dp(8));
        warn.setBackground(wbg);
        warn.setPadding(dp(12), dp(10), dp(12), dp(10));
        w.addView(warn);

        TextView cap = new TextView(getActivity());
        cap.setText("Ваш код (нажмите чтобы скопировать):");
        cap.setTextColor(Color.parseColor("#8B949E"));
        cap.setTextSize(13f);
        cap.setPadding(0, dp(14), 0, dp(6));
        w.addView(cap);

        deviceCodeView = new TextView(getActivity());
        deviceCodeView.setText(dc.userCode);
        deviceCodeView.setTextColor(Color.parseColor("#2ED4C4"));
        deviceCodeView.setTextSize(38f);
        deviceCodeView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        deviceCodeView.setGravity(Gravity.CENTER);
        deviceCodeView.setLetterSpacing(0.15f);
        GradientDrawable cbg = new GradientDrawable();
        cbg.setColor(Color.parseColor("#12211F"));
        cbg.setCornerRadius(dp(10));
        cbg.setStroke(dp(1), Color.parseColor("#2EA79B"));
        deviceCodeView.setBackground(cbg);
        deviceCodeView.setPadding(dp(10), dp(18), dp(10), dp(18));
        deviceCodeView.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { copyToClipboard(deviceUserCode); toast("Код скопирован"); }
        });
        w.addView(deviceCodeView);

        TextView openBtn = wideBtn("\uD83C\uDF10 Открыть страницу подтверждения (код уже подставлен)", "primary", new Runnable() {
            public void run() { openBrowser(deviceVerifyUrl); }
        });
        w.addView(openBtn);

        deviceStatusView = new TextView(getActivity());
        deviceStatusView.setText("Ожидание подтверждения…");
        deviceStatusView.setTextColor(Color.parseColor("#8B949E"));
        deviceStatusView.setTextSize(13f);
        deviceStatusView.setGravity(Gravity.CENTER);
        deviceStatusView.setPadding(0, dp(12), 0, dp(6));
        w.addView(deviceStatusView);

        w.addView(wideBtn("\uD83D\uDCCB Скопировать код (" + dc.userCode + ")", "ghost", new Runnable() {
            public void run() { copyToClipboard(deviceUserCode); toast("Код скопирован"); }
        }));
        w.addView(wideBtn("\uD83D\uDD17 Скопировать ссылку", "ghost", new Runnable() {
            public void run() { copyToClipboard(deviceVerifyUrl); toast("Ссылка скопирована"); }
        }));

        ScrollView sv = new ScrollView(getActivity());
        sv.addView(w);
        deviceDialog = new AlertDialog.Builder(getActivity())
                .setTitle("Вход через системный браузер")
                .setView(sv)
                .setNegativeButton("Закрыть", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int wi) { deviceFlowActive = false; }
                })
                .setCancelable(false)
                .create();
        deviceDialog.show();
    }

    private void pollDeviceFlow(final String clientId, final GithubApi.DeviceCode dc) {
        deviceFlowActive = true;
        final long start = System.currentTimeMillis();
        new Thread(new Runnable() {
            public void run() {
                long interval = dc.interval * 1000L;
                while (deviceFlowActive && (System.currentTimeMillis() - start) < dc.expiresIn * 1000L) {
                    try { Thread.sleep(interval); } catch (InterruptedException e) { return; }
                    if (!deviceFlowActive) return;
                    try {
                        String res = GithubApi.pollAccessToken(clientId, dc.deviceCode);
                        if ("slow_down".equals(res)) { interval += 5000; continue; }
                        if (res != null && !res.isEmpty()) {
                            final String token = res;
                            deviceFlowActive = false;
                            runUi(new Runnable() { public void run() {
                                if (deviceDialog != null) deviceDialog.dismiss();
                                verifyAndSaveToken(token);
                            }});
                            return;
                        }
                        // authorization_pending — продолжаем; обновим таймер.
                        runUi(new Runnable() { public void run() {
                            if (deviceStatusView != null)
                                deviceStatusView.setText("Ожидание подтверждения… "
                                        + new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                                        .format(new java.util.Date()));
                        }});
                    } catch (final Exception e) {
                        deviceFlowActive = false;
                        runUi(new Runnable() { public void run() {
                            if (deviceStatusView != null) {
                                deviceStatusView.setText("Ошибка: " + e.getMessage());
                                deviceStatusView.setTextColor(Color.parseColor("#F85149"));
                            }
                        }});
                        return;
                    }
                }
                if (deviceFlowActive) {
                    deviceFlowActive = false;
                    runUi(new Runnable() { public void run() {
                        if (deviceStatusView != null) deviceStatusView.setText("Время ожидания истекло");
                    }});
                }
            }
        }).start();
    }

    private void openBrowser(String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) { toast("Не удалось открыть браузер"); }
    }
    private void copyToClipboard(String text) {
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    getActivity().getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("github", text));
        } catch (Exception ignored) {}
    }

    // ============================================================== repo actions
    private void logoutGithub() {
        new AlertDialog.Builder(getActivity())
                .setTitle("Выйти из GitHub?")
                .setPositiveButton("Выйти", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        JSONObject ar = store.activeRepo();
                        if (ar != null && "remote".equals(ar.optString("mode")))
                            try { GithubFs.get(getActivity()).unmount(ar.optString("virtualRoot")); } catch (Exception ignored) {}
                        store.githubLogout();
                        remoteRepos = null; branchesCache.clear(); draftRepo = null;
                        FsEvents.notifyChanged();
                        render();
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void cancelActiveRepo() {
        JSONObject ar = store.activeRepo();
        if (ar != null && "remote".equals(ar.optString("mode")))
            try { GithubFs.get(getActivity()).unmount(ar.optString("virtualRoot")); } catch (Exception ignored) {}
        store.setActiveRepo(null);
        FsEvents.notifyChanged();
        toast("Выбор снят");
        render();
    }

    private void refreshTree() {
        final AlertDialog loading = new AlertDialog.Builder(getActivity())
                .setMessage("Обновляем дерево…").setCancelable(false).create();
        loading.show();
        new Thread(new Runnable() {
            public void run() {
                try {
                    GithubFs.get(getActivity()).refresh();
                    runUi(new Runnable() { public void run() {
                        loading.dismiss(); FsEvents.notifyChanged(); toast("Дерево обновлено");
                    }});
                } catch (final Exception e) {
                    runUi(new Runnable() { public void run() {
                        loading.dismiss(); toast("Ошибка: " + e.getMessage());
                    }});
                }
            }
        }).start();
    }

    private void switchToExplorer() {
        if (getActivity() instanceof MainActivity) ((MainActivity) getActivity()).switchToExplorer();
    }

    /** OK — привязать выбранный репозиторий к агенту БЕЗ клонирования (mode=remote). */
    private void confirmSelectionRemote() {
        if (draftRepo == null) {
            toast("Выберите репозиторий (нажмите на него — появится зелёная рамка)");
            return;
        }
        final JSONObject sel = draftRepo;
        final String owner = sel.optString("owner");
        final String name = sel.optString("name");
        final String branch = sel.optString("branch", "main");
        final String virtualRoot = "/gh/" + owner + "/" + name;
        final AlertDialog loading = new AlertDialog.Builder(getActivity())
                .setMessage("Открываем " + sel.optString("fullName") + "…").setCancelable(false).create();
        loading.show();
        new Thread(new Runnable() {
            public void run() {
                try {
                    GithubFs gh = GithubFs.get(getActivity());
                    JSONObject prev = store.activeRepo();
                    if (prev != null && "remote".equals(prev.optString("mode")))
                        try { gh.unmount(prev.optString("virtualRoot")); } catch (Exception ignored) {}
                    gh.mount(owner, name, branch);
                    JSONObject rec = new JSONObject();
                    rec.put("mode", "remote");
                    rec.put("fullName", sel.optString("fullName"));
                    rec.put("owner", owner);
                    rec.put("repo", name);
                    rec.put("branch", branch);
                    rec.put("virtualRoot", virtualRoot);
                    rec.put("cloneUrl", sel.optString("cloneUrl"));
                    rec.put("private", sel.optBoolean("private"));
                    store.setActiveRepo(rec);
                    draftRepo = null;
                    runUi(new Runnable() { public void run() {
                        loading.dismiss();
                        FsEvents.notifyChanged();
                        toast("Подключён " + sel.optString("fullName") + " (" + branch + ")");
                        render();
                        // Авто-переход в проводник с раскрытием дерева репозитория.
                        if (getActivity() instanceof MainActivity)
                            ((MainActivity) getActivity()).openGithubRepoInExplorer(virtualRoot);
                    }});
                } catch (final Exception e) {
                    runUi(new Runnable() { public void run() {
                        loading.dismiss(); toast("Ошибка: " + e.getMessage());
                    }});
                }
            }
        }).start();
    }

    private void deleteSelectedRepo() {
        if (draftRepo == null) {
            toast("Сначала выберите репозиторий (нажмите — появится зелёная рамка)");
            return;
        }
        final JSONObject sel = draftRepo;
        final String fullName = sel.optString("fullName");
        if (!store.githubHasScope("delete_repo")) {
            new AlertDialog.Builder(getActivity())
                    .setTitle("Нет права на удаление")
                    .setMessage("У текущего токена нет scope delete_repo — GitHub не даст удалить репозиторий.\n\n"
                            + "Войдите заново через PAT (Personal Access Token) с этим правом.\n\n"
                            + "Выданные scope: " + scopesText())
                    .setPositiveButton("Войти через PAT", new android.content.DialogInterface.OnClickListener() {
                        public void onClick(android.content.DialogInterface d, int w) {
                            store.githubLogout();
                            remoteRepos = null; branchesCache.clear(); draftRepo = null;
                            render();
                            openBrowser("https://github.com/settings/tokens/new?scopes=repo,delete_repo&description=IDE%20Mobile");
                            ui.postDelayed(new Runnable() { public void run() { pasteTokenDialog(); } }, 400);
                        }
                    })
                    .setNegativeButton("Отмена", null)
                    .show();
            return;
        }
        // Двухшаговое подтверждение: ввод полного имени.
        final EditText et = new EditText(getActivity());
        et.setSingleLine(true);
        et.setHint(fullName);
        et.setTextColor(Color.parseColor("#E6EDF3"));
        et.setHintTextColor(Color.parseColor("#5B6570"));
        LinearLayout w = new LinearLayout(getActivity());
        w.setOrientation(LinearLayout.VERTICAL);
        w.setPadding(dp(20), dp(8), dp(20), dp(4));
        TextView msg = new TextView(getActivity());
        msg.setText("Введите полное имя «" + fullName + "» для подтверждения. ЭТО НЕОБРАТИМО.");
        msg.setTextColor(Color.parseColor("#8B949E"));
        msg.setTextSize(13f);
        msg.setPadding(0, 0, 0, dp(8));
        w.addView(msg); w.addView(et);
        new AlertDialog.Builder(getActivity())
                .setTitle("Удалить репозиторий")
                .setView(w)
                .setPositiveButton("Удалить", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int wi) {
                        if (!et.getText().toString().trim().equals(fullName)) {
                            toast("Имя не совпало — удаление отменено");
                            return;
                        }
                        doDeleteRepo(fullName);
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void doDeleteRepo(final String fullName) {
        final AlertDialog loading = new AlertDialog.Builder(getActivity())
                .setMessage("Удаляем " + fullName + "…").setCancelable(false).create();
        loading.show();
        new Thread(new Runnable() {
            public void run() {
                try {
                    GithubApi.deleteRepo(store.githubToken(), fullName);
                    JSONObject ar = store.activeRepo();
                    if (ar != null && fullName.equals(ar.optString("fullName"))) {
                        if ("remote".equals(ar.optString("mode")))
                            try { GithubFs.get(getActivity()).unmount(ar.optString("virtualRoot")); } catch (Exception ignored) {}
                        store.setActiveRepo(null);
                    }
                    runUi(new Runnable() { public void run() {
                        loading.dismiss();
                        toast("Удалён " + fullName);
                        draftRepo = null; remoteRepos = null; branchesCache.clear();
                        FsEvents.notifyChanged();
                        render();
                    }});
                } catch (final Exception e) {
                    runUi(new Runnable() { public void run() {
                        loading.dismiss(); toast("Ошибка: " + e.getMessage());
                    }});
                }
            }
        }).start();
    }

    private String scopesText() {
        JSONArray a = store.githubScopes();
        if (a == null || a.length() == 0) return "—";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length(); i++) { if (i > 0) sb.append(", "); sb.append(a.optString(i)); }
        return sb.toString();
    }

    private void createRepoDialog() {
        if (store.githubToken() == null) { toast("Сначала войдите в GitHub"); return; }
        final EditText nameInp = new EditText(getActivity());
        nameInp.setSingleLine(true);
        nameInp.setHint("my-new-repo");
        nameInp.setTextColor(Color.parseColor("#E6EDF3"));
        nameInp.setHintTextColor(Color.parseColor("#5B6570"));
        final EditText descInp = new EditText(getActivity());
        descInp.setSingleLine(true);
        descInp.setHint("Описание (необязательно)");
        descInp.setTextColor(Color.parseColor("#E6EDF3"));
        descInp.setHintTextColor(Color.parseColor("#5B6570"));
        final android.widget.CheckBox priv = new android.widget.CheckBox(getActivity());
        priv.setText(" Приватный"); priv.setChecked(true);
        priv.setTextColor(Color.parseColor("#E6EDF3"));
        final android.widget.CheckBox init = new android.widget.CheckBox(getActivity());
        init.setText(" Инициализировать README"); init.setChecked(true);
        init.setTextColor(Color.parseColor("#E6EDF3"));
        LinearLayout w = new LinearLayout(getActivity());
        w.setOrientation(LinearLayout.VERTICAL);
        w.setPadding(dp(20), dp(8), dp(20), dp(4));
        TextView l = new TextView(getActivity());
        l.setText("Имя репозитория");
        l.setTextColor(Color.parseColor("#8B949E"));
        l.setTextSize(13f); l.setPadding(0, 0, 0, dp(6));
        w.addView(l); w.addView(nameInp); w.addView(descInp);
        w.addView(priv); w.addView(init);
        new AlertDialog.Builder(getActivity())
                .setTitle("Новый репозиторий на GitHub")
                .setView(w)
                .setPositiveButton("Создать", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int wi) {
                        String name = nameInp.getText().toString().trim();
                        if (name.isEmpty()) { toast("Укажите имя"); return; }
                        doCreateRepo(name, descInp.getText().toString().trim(), priv.isChecked(), init.isChecked());
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void doCreateRepo(final String name, final String desc, final boolean priv, final boolean init) {
        final AlertDialog loading = new AlertDialog.Builder(getActivity())
                .setMessage("Создаём " + name + "…").setCancelable(false).create();
        loading.show();
        new Thread(new Runnable() {
            public void run() {
                try {
                    final JSONObject repo = GithubApi.createRepo(store.githubToken(), name, desc, priv, init);
                    runUi(new Runnable() { public void run() {
                        loading.dismiss();
                        toast("Создан " + repo.optString("full_name"));
                        remoteRepos = null; branchesCache.clear();
                        String[] parts = repo.optString("full_name").split("/", 2);
                        draftRepo = new JSONObject();
                        try {
                            draftRepo.put("fullName", repo.optString("full_name"));
                            draftRepo.put("owner", parts.length > 0 ? parts[0] : "");
                            draftRepo.put("name", parts.length > 1 ? parts[1] : name);
                            draftRepo.put("cloneUrl", repo.optString("clone_url"));
                            draftRepo.put("branch", repo.optString("default_branch", "main"));
                            draftRepo.put("defaultBranch", repo.optString("default_branch", "main"));
                            draftRepo.put("private", repo.optBoolean("private"));
                        } catch (Exception ignored) {}
                        render();
                    }});
                } catch (final Exception e) {
                    runUi(new Runnable() { public void run() {
                        loading.dismiss(); toast("Ошибка: " + e.getMessage());
                    }});
                }
            }
        }).start();
    }

    private void initProjectDialog() {
        final EditText et = new EditText(getActivity());
        et.setSingleLine(true);
        et.setText("/project");
        et.setTextColor(Color.parseColor("#E6EDF3"));
        LinearLayout w = new LinearLayout(getActivity());
        w.setOrientation(LinearLayout.VERTICAL);
        w.setPadding(dp(20), dp(8), dp(20), dp(4));
        TextView l = new TextView(getActivity());
        l.setText("Путь папки");
        l.setTextColor(Color.parseColor("#8B949E"));
        l.setTextSize(14f); l.setPadding(0, 0, 0, dp(6));
        w.addView(l); w.addView(et);
        new AlertDialog.Builder(getActivity())
                .setTitle("git init")
                .setView(w)
                .setPositiveButton("OK", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int wi) {
                        String path = et.getText().toString().trim();
                        if (path.isEmpty()) return;
                        try {
                            ExplorerFs fs = new ExplorerFs(getActivity());
                            if (!path.equals("/")) fs.ensureDir(ExplorerFs.normPath(path));
                            FsEvents.notifyChanged();
                            toast("Папка " + path + " создана");
                            render();
                        } catch (Exception e) { toast("Ошибка: " + e.getMessage()); }
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }
}
