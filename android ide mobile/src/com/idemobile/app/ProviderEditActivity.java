package com.idemobile.app;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Редактор провайдера. Повторяет editProvider() из web-версии:
 *   Название · Base URL (+спойлер) · «Использовать URL строго как есть» (чекбокс+спойлер) ·
 *   API key (с глазом +спойлер) · ID модели (+спойлер) ·
 *   [Обновить список моделей] [↻ Перепроверить доступность] · легенда кружков ·
 *   свёрнутый блок: [Свернуть/Развернуть] [Поиск] + список моделей с кружками доступности ·
 *   CORS-прокси (+спойлер) · Extra headers JSON (+спойлер).
 */
public class ProviderEditActivity extends Activity {

    private Store store;
    private LinearLayout root;
    private boolean isNew;
    private JSONObject draft;

    // model picker state
    private final List<ApiClient.Model> loadedModels = new ArrayList<ApiClient.Model>();
    private final Map<String, ApiClient.Probe> probeMap = new HashMap<String, ApiClient.Probe>();
    private final AtomicBoolean checking = new AtomicBoolean(false);
    private final AtomicBoolean stopChecking = new AtomicBoolean(false);
    private boolean expanded = true;
    private long lastListRender = 0;

    private TextView infoLine, loadBtn, checkBtn, toggleBtn;
    private EditText searchBox, modelInput;
    private LinearLayout legend, collapseBox, listBox;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_provider_edit);
        store = Store.get(this);
        root = (LinearLayout) findViewById(R.id.edit_root);

        String pid = getIntent().getStringExtra("providerId");
        isNew = pid == null;
        JSONObject existing = pid != null ? store.findProvider(pid) : null;
        draft = existing != null ? copy(existing) : newDraft();

        ((TextView) findViewById(R.id.edit_title)).setText(isNew ? "Новый провайдер" : "Редактирование");
        findViewById(R.id.btn_cancel).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });
        findViewById(R.id.btn_save).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { save(); }
        });

        build();
    }

    private void build() {
        // Название
        textField("Название", "name", "Мой провайдер", null, null);

        // Base URL
        textField("Base URL", "baseUrl", "https://api.example.com/v1",
                "Base URL",
                "Адрес API провайдера. Можно указать базу (например https://api.auriko.ai/v1) или полный путь до endpoint — клиент сам достроит /chat/completions без дублирования. Если провайдер требует нестандартный путь — включите ниже «Использовать URL строго как есть».");

        // OpenAI-compatible ползунок (над «использовать строго как есть»)
        LinearLayout compatF = UiKit.field(this);
        final android.widget.Switch compatSw = UiKit.switchRow(this, compatF, "OpenAI-compatible");
        compatSw.setChecked(draft.optBoolean("openaiCompatible", false));
        compatSw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton b, boolean c) { put("openaiCompatible", c); }
        });
        compatF.addView(UiKit.spoiler(this, "OpenAI-compatible",
                "Принудительно использовать OpenAI-совместимый формат (/v1/chat/completions, заголовок Authorization: Bearer), даже если адрес похож на Google или Anthropic. Включайте для шлюзов-агрегаторов с нестандартными доменами, которые общаются в формате OpenAI."));
        root.addView(compatF);

        // rawUrl ползунок
        LinearLayout rawF = UiKit.field(this);
        final android.widget.Switch rawCb = UiKit.switchRow(this, rawF, "Использовать URL строго как есть");
        rawCb.setChecked(draft.optBoolean("rawUrl", false));
        rawCb.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton b, boolean c) { put("rawUrl", c); }
        });
        rawF.addView(UiKit.spoiler(this, "Использовать URL строго как есть",
                "Не достраивать /v1/chat/completions к Base URL — использовать адрес точно как введён. Включайте, только если провайдер требует свой собственный путь. В обычных случаях оставьте выключенным."));
        root.addView(rawF);

        // API key с глазом
        buildApiKeyField();

        // ID модели
        LinearLayout modelF = UiKit.field(this);
        modelF.addView(UiKit.label(this, "ID модели"));
        modelInput = UiKit.input(this, draft.optString("model", ""),
                "gpt-4o-mini / claude-3-5-sonnet / gemini-1.5-flash", InputType.TYPE_CLASS_TEXT, false);
        modelF.addView(modelInput);
        modelInput.addTextChangedListener(new SettingsActivity.SimpleWatcher() {
            public void changed(String v) { put("model", v); }
        });
        modelF.addView(UiKit.spoiler(this, "ID модели",
                "Точный идентификатор модели у провайдера. Можно вписать вручную или выбрать из списка ниже, нажав «Обновить список моделей»."));
        root.addView(modelF);

        // Model picker
        buildModelPicker();

        // CORS proxy
        textField("CORS-прокси (опционально)", "corsProxy", "(оставь пустым — авто-подбор бесплатного)",
                "CORS-прокси (опционально)",
                "Оставьте ПУСТЫМ в большинстве случаев — клиент сам подберёт прокси. На Android прямой запрос идёт первым; прокси — запасной вариант. Поддерживаются шаблоны {url_raw} и {url_enc}.");

        // Extra headers
        LinearLayout ehF = UiKit.field(this);
        ehF.addView(UiKit.label(this, "Extra headers (JSON)"));
        EditText eh = UiKit.input(this, draft.optString("extraHeaders", ""), "{\"X-My-Header\":\"value\"}", 0, true);
        ehF.addView(eh);
        eh.addTextChangedListener(new SettingsActivity.SimpleWatcher() {
            public void changed(String v) { put("extraHeaders", v); }
        });
        ehF.addView(UiKit.spoiler(this, "Extra headers (JSON)",
                "Необязательно. Дополнительные HTTP-заголовки в формате JSON, которые будут добавлены к каждому запросу к провайдеру."));
        root.addView(ehF);

        // Восстановить ранее загруженный список
        restoreModels();
    }

    private void textField(String label, final String key, String hint, String spTitle, String spBody) {
        LinearLayout f = UiKit.field(this);
        f.addView(UiKit.label(this, label));
        EditText in = UiKit.input(this, draft.optString(key, ""), hint, InputType.TYPE_CLASS_TEXT, false);
        f.addView(in);
        in.addTextChangedListener(new SettingsActivity.SimpleWatcher() {
            public void changed(String v) { put(key, v); }
        });
        if (spTitle != null) f.addView(UiKit.spoiler(this, spTitle, spBody));
        root.addView(f);
    }

    private void buildApiKeyField() {
        LinearLayout f = UiKit.field(this);
        f.addView(UiKit.label(this, "API key"));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.bg_input);

        final EditText key = new EditText(this);
        key.setBackground(null);
        key.setTextColor(Color.parseColor("#E6EDF3"));
        key.setHintTextColor(Color.parseColor("#5B6570"));
        key.setHint("sk-...");
        key.setTextSize(15f);
        key.setSingleLine(true);
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        key.setText(draft.optString("apiKey", ""));
        key.setPadding(UiKit.dp(this, 12), UiKit.dp(this, 10), UiKit.dp(this, 6), UiKit.dp(this, 10));
        LinearLayout.LayoutParams kp = UiKit.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT);
        kp.weight = 1;
        key.setLayoutParams(kp);
        key.addTextChangedListener(new SettingsActivity.SimpleWatcher() {
            public void changed(String v) { put("apiKey", v); }
        });

        final ImageButton eye = new ImageButton(this);
        eye.setBackground(null);
        eye.setImageResource(R.drawable.ic_eye_off);
        eye.setLayoutParams(UiKit.lp(UiKit.dp(this, 44), UiKit.dp(this, 44)));
        eye.setOnClickListener(new View.OnClickListener() {
            boolean show = false;
            public void onClick(View v) {
                show = !show;
                int sel = key.getSelectionEnd();
                key.setInputType(InputType.TYPE_CLASS_TEXT | (show ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD : InputType.TYPE_TEXT_VARIATION_PASSWORD));
                key.setSelection(Math.min(sel, key.getText().length()));
                eye.setImageResource(show ? R.drawable.ic_eye : R.drawable.ic_eye_off);
            }
        });

        row.addView(key);
        row.addView(eye);
        f.addView(row);
        f.addView(UiKit.spoiler(this, "API key",
                "Ключ доступа к провайдеру. Хранится только локально на устройстве и отправляется лишь самому провайдеру. Нажмите «глаз» справа, чтобы показать/скрыть значение."));
        root.addView(f);
    }

    // ------------------------------------------------------------- model picker
    private void buildModelPicker() {
        LinearLayout wrap = UiKit.field(this);

        // строка кнопок
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        loadBtn = UiKit.button(this, "Обновить список моделей", "");
        loadBtn.setTextSize(13f);
        checkBtn = UiKit.button(this, "\u21BB Перепроверить доступность", "");
        checkBtn.setTextSize(13f);
        checkBtn.setVisibility(View.GONE);
        LinearLayout.LayoutParams cp = UiKit.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.leftMargin = UiKit.dp(this, 8);
        checkBtn.setLayoutParams(cp);
        btnRow.addView(loadBtn);
        btnRow.addView(checkBtn);
        wrap.addView(btnRow);

        infoLine = new TextView(this);
        infoLine.setTextColor(Color.parseColor("#8B949E"));
        infoLine.setTextSize(12f);
        infoLine.setPadding(0, UiKit.dp(this, 6), 0, 0);
        wrap.addView(infoLine);

        // легенда
        legend = new LinearLayout(this);
        legend.setOrientation(LinearLayout.VERTICAL);
        legend.setVisibility(View.GONE);
        legend.setPadding(0, UiKit.dp(this, 6), 0, 0);
        legend.addView(legendItem(Color.parseColor("#3FB950"), "доступна"));
        legend.addView(legendItem(Color.parseColor("#F85149"), "недоступна (платн./лимит исчерпан)"));
        legend.addView(legendItem(Color.parseColor("#6E7681"), "неизвестно"));
        wrap.addView(legend);

        // свёрнутый блок
        collapseBox = new LinearLayout(this);
        collapseBox.setOrientation(LinearLayout.VERTICAL);
        collapseBox.setBackgroundResource(R.drawable.bg_input);
        collapseBox.setPadding(UiKit.dp(this, 8), UiKit.dp(this, 8), UiKit.dp(this, 8), UiKit.dp(this, 8));
        collapseBox.setVisibility(View.GONE);
        LinearLayout.LayoutParams cbp = UiKit.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cbp.topMargin = UiKit.dp(this, 8);
        collapseBox.setLayoutParams(cbp);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        toggleBtn = UiKit.button(this, "\u25BE Свернуть", "");
        toggleBtn.setTextSize(12f);
        searchBox = UiKit.input(this, "", "Поиск по названию/id…", InputType.TYPE_CLASS_TEXT, false);
        LinearLayout.LayoutParams sp = UiKit.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.weight = 1; sp.leftMargin = UiKit.dp(this, 8);
        searchBox.setLayoutParams(sp);
        header.addView(toggleBtn);
        header.addView(searchBox);
        collapseBox.addView(header);

        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lbp = UiKit.lp(ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 320));
        lbp.topMargin = UiKit.dp(this, 6);
        // прокрутка списка — вложенный скролл, не двигающий всё меню настроек
        ScrollView sv = UiKit.nestedScroll(this);
        sv.setLayoutParams(lbp);
        sv.addView(listBox);
        collapseBox.addView(sv);
        wrap.addView(collapseBox);

        root.addView(wrap);

        loadBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { doLoadModels(); }
        });
        checkBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (checking.get()) { stopChecking.set(true); toast("Останавливаю проверку…"); return; }
                runAvailabilityCheck();
            }
        });
        toggleBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { expanded = !expanded; applyExpanded(); }
        });
        searchBox.addTextChangedListener(new SettingsActivity.SimpleWatcher() {
            public void changed(String v) { renderList(); }
        });
    }

    private View legendItem(int color, String text) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, UiKit.dp(this, 2), 0, UiKit.dp(this, 2));
        row.addView(UiKit.dot(this, color, 9));
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.parseColor("#8B949E"));
        t.setTextSize(11.5f);
        LinearLayout.LayoutParams tp = UiKit.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.leftMargin = UiKit.dp(this, 6);
        t.setLayoutParams(tp);
        row.addView(t);
        return row;
    }

    private void applyExpanded() {
        searchBox.setVisibility(expanded ? View.VISIBLE : View.GONE);
        ((View) listBox.getParent()).setVisibility(expanded ? View.VISIBLE : View.GONE);
        toggleBtn.setText(expanded ? "\u25BE Свернуть" : "\u25B8 Развернуть");
    }

    private void restoreModels() {
        JSONArray saved = draft.optJSONArray("models");
        if (saved != null && saved.length() > 0) {
            loadedModels.clear();
            for (int i = 0; i < saved.length(); i++) {
                JSONObject m = saved.optJSONObject(i);
                if (m != null) loadedModels.add(new ApiClient.Model(m.optString("id"), m.optString("name"), m.optJSONObject("raw")));
            }
            JSONObject probes = draft.optJSONObject("probes");
            if (probes != null) {
                java.util.Iterator<String> it = probes.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    JSONObject pr = probes.optJSONObject(k);
                    if (pr != null) {
                        Boolean av = pr.isNull("available") ? null : (Boolean) pr.optBoolean("available");
                        if (!pr.has("available")) av = null;
                        probeMap.put(k, new ApiClient.Probe(av, pr.optInt("status"), pr.optString("reason", null)));
                    }
                }
            }
            collapseBox.setVisibility(View.VISIBLE);
            legend.setVisibility(View.VISIBLE);
            checkBtn.setVisibility(View.VISIBLE);
            infoLine.setText(statLine(probeMap.isEmpty() ? "Сохранено" : "Проверено", loadedModels.size()));
            renderList();
        }
    }

    private void doLoadModels() {
        final String baseUrl = draft.optString("baseUrl", "").trim();
        if (baseUrl.isEmpty()) { toast("Заполни Base URL"); return; }
        loadBtn.setText("Загрузка…");
        loadBtn.setEnabled(false);
        infoLine.setText("");
        new Thread(new Runnable() {
            public void run() {
                try {
                    final List<ApiClient.Model> res = ApiClient.listModels(draft);
                    runOnUiThread(new Runnable() {
                        public void run() {
                            loadedModels.clear();
                            loadedModels.addAll(res);
                            probeMap.clear();
                            saveModelsToDraft();
                            collapseBox.setVisibility(View.VISIBLE);
                            legend.setVisibility(View.VISIBLE);
                            checkBtn.setVisibility(View.VISIBLE);
                            expanded = true;
                            applyExpanded();
                            renderList();
                            infoLine.setText(statLine("Загружено", loadedModels.size())
                                    + " · нажми «Перепроверить доступность» для реальной проверки");
                            loadBtn.setText("Обновить список моделей");
                            loadBtn.setEnabled(true);
                            toast("Загружено моделей: " + loadedModels.size());
                            // Авто-проверка НЕ запускается: для больших списков это сотни
                            // запросов и подвисание. Цвет кружка сразу берётся из прайсинга
                            // (modelCostTier). Реальная проверка — по кнопке.
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            infoLine.setText("Ошибка");
                            loadBtn.setText("Обновить список моделей");
                            loadBtn.setEnabled(true);
                            toast("Не удалось: " + firstLine(e.getMessage()));
                        }
                    });
                }
            }
        }).start();
    }

    private void runAvailabilityCheck() {
        if (loadedModels.isEmpty()) { toast("Сначала загрузи список моделей"); return; }
        if (checking.get()) return;
        checking.set(true);
        stopChecking.set(false);
        probeMap.clear();
        checkBtn.setText("\u23F3 Проверка… (нажми, чтобы остановить)");
        renderList();

        final int total = loadedModels.size();
        final int timeoutMs = 12000;
        final AtomicInteger idx = new AtomicInteger(0);
        final AtomicInteger done = new AtomicInteger(0);
        // Умеренная параллельность: слишком агрессивный «залп» провоцирует у части
        // провайдеров (напр. Venice) защитный троттлинг «too many failed attempts»,
        // маскирующий настоящую причину отказа (нет баланса).
        final int concurrency = 3;
        final AtomicInteger alive = new AtomicInteger(concurrency);

        for (int w = 0; w < concurrency; w++) {
            new Thread(new Runnable() {
                public void run() {
                    while (true) {
                        if (stopChecking.get()) break;
                        int i = idx.getAndIncrement();
                        if (i >= total) break;
                        final ApiClient.Model m = loadedModels.get(i);
                        ApiClient.Probe probe;
                        try { probe = ApiClient.probeModel(draft, m.id, timeoutMs); }
                        catch (Exception e) { probe = new ApiClient.Probe(null, 0, e.getMessage()); }
                        final ApiClient.Probe fp = probe;
                        synchronized (probeMap) { probeMap.put(m.id, fp); }
                        final int d = done.incrementAndGet();
                        runOnUiThread(new Runnable() {
                            public void run() {
                                Stat s = costStats();
                                infoLine.setText("Проверка " + d + "/" + total + " · \uD83D\uDFE2" + s.free + " \uD83D\uDD34" + s.paid + (s.unk > 0 ? " \u26AA" + s.unk : ""));
                                // Список перерисовываем не чаще раза в 500 мс, чтобы
                                // не пересоздавать сотни View на каждый ответ (иначе UI подвисает).
                                long now = System.currentTimeMillis();
                                if (d == total || now - lastListRender > 500) {
                                    lastListRender = now;
                                    renderList();
                                }
                            }
                        });
                    }
                    if (alive.decrementAndGet() == 0) {
                        runOnUiThread(new Runnable() {
                            public void run() {
                                checking.set(false);
                                saveModelsToDraft();
                                infoLine.setText(statLine("Проверено", total));
                                checkBtn.setText("\u21BB Перепроверить доступность");
                                renderList();
                                Stat s = costStats();
                                toast("Проверка завершена: доступно " + s.free + ", недоступно " + s.paid + (s.unk > 0 ? ", неизв. " + s.unk : ""));
                            }
                        });
                    }
                }
            }).start();
        }
    }

    private void renderList() {
        listBox.removeAllViews();
        String q = searchBox.getText().toString().trim().toLowerCase();
        List<ApiClient.Model> filtered = new ArrayList<ApiClient.Model>();
        for (ApiClient.Model m : loadedModels) {
            if (q.isEmpty() || (m.id != null && m.id.toLowerCase().contains(q)) || (m.name != null && m.name.toLowerCase().contains(q)))
                filtered.add(m);
        }
        if (filtered.isEmpty()) {
            TextView t = new TextView(this);
            t.setText(loadedModels.isEmpty() ? "Список пуст — нажми «Обновить список моделей»" : "Ничего не найдено");
            t.setTextColor(Color.parseColor("#8B949E"));
            t.setTextSize(12f);
            t.setPadding(UiKit.dp(this, 6), UiKit.dp(this, 6), 0, 0);
            listBox.addView(t);
            return;
        }
        // Ограничиваем число одновременно отрисованных строк, чтобы большие списки
        // (например ~320 у OpenRouter) не подвешивали UI. Остальное — через поиск.
        final int LIMIT = 200;
        int shown = 0;
        for (final ApiClient.Model m : filtered) {
            if (shown++ >= LIMIT) {
                TextView more = new TextView(this);
                more.setText("…показаны первые " + LIMIT + " из " + filtered.size()
                        + ". Уточните поиск, чтобы найти остальные.");
                more.setTextColor(Color.parseColor("#8B949E"));
                more.setTextSize(11.5f);
                more.setPadding(UiKit.dp(this, 6), UiKit.dp(this, 8), 0, UiKit.dp(this, 4));
                listBox.addView(more);
                break;
            }
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(UiKit.dp(this, 8), UiKit.dp(this, 7), UiKit.dp(this, 8), UiKit.dp(this, 7));
            boolean sel = m.id.equals(draft.optString("model", ""));
            if (sel) row.setBackgroundResource(R.drawable.bg_model_row_sel);

            LinearLayout textCol = new LinearLayout(this);
            textCol.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams tcp = UiKit.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT);
            tcp.weight = 1;
            textCol.setLayoutParams(tcp);
            TextView idT = new TextView(this);
            idT.setText(m.id);
            idT.setTextColor(Color.parseColor("#E6EDF3"));
            idT.setTextSize(13.5f);
            idT.setTypeface(android.graphics.Typeface.MONOSPACE);
            textCol.addView(idT);
            if (m.name != null && !m.name.equals(m.id) && !m.name.isEmpty()) {
                TextView nm = new TextView(this);
                nm.setText(m.name);
                nm.setTextColor(Color.parseColor("#8B949E"));
                nm.setTextSize(11f);
                textCol.addView(nm);
            }
            row.addView(textCol);

            // кружок
            ApiClient.Probe probe;
            synchronized (probeMap) { probe = probeMap.get(m.id); }
            boolean pending = checking.get() && probe == null;
            row.addView(dotFor(m, probe, pending));

            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    put("model", m.id);
                    modelInput.setText(m.id);
                    toast("Модель выбрана: " + m.id);
                    renderList();
                }
            });
            listBox.addView(row);
        }
    }

    private View dotFor(ApiClient.Model m, ApiClient.Probe probe, boolean pending) {
        int color;
        if (pending) color = Color.parseColor("#D29922");
        else {
            String tier = ApiClient.availabilityTier(m, probe);
            color = tier.equals("free") ? Color.parseColor("#3FB950")
                    : tier.equals("paid") ? Color.parseColor("#F85149")
                    : Color.parseColor("#6E7681");
        }
        return UiKit.dot(this, color, 11);
    }

    private static class Stat { int free, paid, unk; }

    private Stat costStats() {
        Stat s = new Stat();
        for (ApiClient.Model m : loadedModels) {
            ApiClient.Probe pr;
            synchronized (probeMap) { pr = probeMap.get(m.id); }
            String t = ApiClient.availabilityTier(m, pr);
            if (t.equals("free")) s.free++;
            else if (t.equals("paid")) s.paid++;
            else s.unk++;
        }
        return s;
    }

    private String statLine(String prefix, int total) {
        Stat s = costStats();
        return prefix + ": " + total + " · \uD83D\uDFE2" + s.free + " \uD83D\uDD34" + s.paid + (s.unk > 0 ? " \u26AA" + s.unk : "");
    }

    // ------------------------------------------------------------- save
    private void saveModelsToDraft() {
        try {
            JSONArray arr = new JSONArray();
            for (ApiClient.Model m : loadedModels) {
                JSONObject o = new JSONObject();
                o.put("id", m.id);
                o.put("name", m.name);
                if (m.raw != null) o.put("raw", m.raw);
                arr.put(o);
            }
            draft.put("models", arr);
            JSONObject probes = new JSONObject();
            synchronized (probeMap) {
                for (Map.Entry<String, ApiClient.Probe> e : probeMap.entrySet()) {
                    ApiClient.Probe pr = e.getValue();
                    JSONObject o = new JSONObject();
                    if (pr.available == null) o.put("available", JSONObject.NULL);
                    else o.put("available", pr.available.booleanValue());
                    o.put("status", pr.status);
                    if (pr.reason != null) o.put("reason", pr.reason);
                    probes.put(e.getKey(), o);
                }
            }
            draft.put("probes", probes);
        } catch (JSONException ignored) {}
    }

    private void save() {
        String name = draft.optString("name", "").trim();
        String baseUrl = draft.optString("baseUrl", "").trim();
        if (name.isEmpty()) { toast("Название обязательно"); return; }
        if (baseUrl.isEmpty()) { toast("Base URL обязателен"); return; }
        saveModelsToDraft();
        store.upsertProvider(draft);
        setResult(RESULT_OK);
        finish();
    }

    // ------------------------------------------------------------- helpers
    private JSONObject newDraft() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", "p_" + Long.toString(System.currentTimeMillis(), 36) + (int) (Math.random() * 999));
            o.put("name", ""); o.put("baseUrl", ""); o.put("apiKey", "");
            o.put("model", ""); o.put("extraHeaders", ""); o.put("rawUrl", false);
            o.put("openaiCompatible", false); o.put("corsProxy", "");
        } catch (JSONException ignored) {}
        return o;
    }

    private JSONObject copy(JSONObject src) {
        try { return new JSONObject(src.toString()); } catch (JSONException e) { return newDraft(); }
    }

    private void put(String k, Object v) { try { draft.put(k, v); } catch (JSONException ignored) {} }

    private String firstLine(String s) {
        if (s == null) return "ошибка";
        int i = s.indexOf('\n');
        return i > 0 ? s.substring(0, i) : s;
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
