package com.idemobile.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Экран «Настройки ИИ». Три секции:
 *   1) ПРОВАЙДЕРЫ — список карточек + активный/редактировать/удалить + «Добавить провайдера»;
 *   2) ПОВЕДЕНИЕ ИИ — system prompt, звук, громкость, лимиты, таймаут, max_tokens, throttle, backoff;
 *   3) GITHUB — авторизация и CORS proxy.
 * Каждое поле снабжено спойлером-подсказкой (синий заголовок).
 */
public class SettingsActivity extends Activity {

    public static final int REQ_EDIT_PROVIDER = 101;

    private Store store;
    private LinearLayout root;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_settings);
        store = Store.get(this);
        root = (LinearLayout) findViewById(R.id.settings_root);
        android.widget.ImageButton close = (android.widget.ImageButton) findViewById(R.id.btn_close);
        close.setColorFilter(getResources().getColor(R.color.text_secondary));
        close.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });
        rebuild();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_EDIT_PROVIDER && res == RESULT_OK) rebuild();
    }

    private void rebuild() {
        root.removeAllViews();
        buildProviders();
        buildBehavior();
        buildCrashReports();
        // Секция GitHub убрана — вход в GitHub теперь во вкладке «GitHub».
    }

    // ------------------------------------------------------------- providers
    private void buildProviders() {
        root.addView(UiKit.sectionHead(this, getString(R.string.sec_providers)));
        JSONArray arr = store.providers();
        if (arr.length() == 0) {
            TextView empty = new TextView(this);
            empty.setText("Провайдеров нет. Добавьте свой.");
            empty.setTextColor(Color.parseColor("#8B949E"));
            empty.setTextSize(13f);
            root.addView(empty);
        }
        for (int i = 0; i < arr.length(); i++) {
            final JSONObject p = arr.optJSONObject(i);
            if (p != null) root.addView(providerCard(p));
        }
        TextView add = UiKit.button(this, getString(R.string.add_provider), "primary");
        LinearLayout.LayoutParams lp = UiKit.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = UiKit.dp(this, 10);
        add.setLayoutParams(lp);
        add.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openEditor(null); }
        });
        root.addView(add);
    }

    private View providerCard(final JSONObject p) {
        final boolean active = p.optString("id").equals(store.activeProviderId());
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        card.setPadding(UiKit.dp(this, 12), UiKit.dp(this, 12), UiKit.dp(this, 12), UiKit.dp(this, 12));
        LinearLayout.LayoutParams clp = UiKit.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = UiKit.dp(this, 10);
        card.setLayoutParams(clp);

        // Заголовок + бейдж АКТИВНЫЙ
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = new TextView(this);
        name.setText(p.optString("name", "—"));
        name.setTextColor(Color.parseColor("#E6EDF3"));
        name.setTextSize(16f);
        name.setTextColor(Color.WHITE);
        name.getPaint().setFakeBoldText(true);
        titleRow.addView(name);
        if (active) {
            TextView badge = new TextView(this);
            badge.setText("АКТИВНЫЙ");
            badge.setTextColor(Color.WHITE);
            badge.setTextSize(10f);
            badge.setBackgroundResource(R.drawable.bg_badge);
            badge.setPadding(UiKit.dp(this, 7), UiKit.dp(this, 2), UiKit.dp(this, 7), UiKit.dp(this, 2));
            LinearLayout.LayoutParams bp = UiKit.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bp.leftMargin = UiKit.dp(this, 10);
            badge.setLayoutParams(bp);
            titleRow.addView(badge);
        }
        card.addView(titleRow);

        // Инфо
        String apiKey = p.optString("apiKey", "");
        info(card, "URL: " + (p.optString("baseUrl", "—")) + (p.optBoolean("rawUrl", false) ? "  [как есть]" : ""));
        info(card, "Модель: " + p.optString("model", "—"));
        info(card, "API key: " + (apiKey.isEmpty() ? "—" : maskKey(apiKey)));
        info(card, "CORS-прокси: " + (p.optString("corsProxy", "").isEmpty() ? "авто (встроенные публичные)" : p.optString("corsProxy")));

        // Кнопки
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams ap = UiKit.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ap.topMargin = UiKit.dp(this, 10);
        actions.setLayoutParams(ap);

        if (!active) {
            TextView mk = UiKit.button(this, "Сделать активным", "primary");
            mk.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { store.setActiveProvider(p.optString("id")); rebuild(); }
            });
            // Растягиваем на доступное место, чтобы узкие кнопки-иконки всегда
            // помещались справа и не обрезались на узких экранах.
            LinearLayout.LayoutParams mp = UiKit.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT);
            mp.weight = 1;
            mp.rightMargin = UiKit.dp(this, 8);
            mk.setLayoutParams(mp);
            mk.setTextSize(13f);
            actions.addView(mk);
        }
        android.widget.ImageView ed = UiKit.iconButton(this, R.drawable.ic_edit, "");
        ed.setContentDescription("Редактировать");
        ed.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openEditor(p.optString("id")); }
        });
        addAction(actions, ed);

        android.widget.ImageView del = UiKit.iconButton(this, R.drawable.ic_delete_w, "danger");
        del.setContentDescription("Удалить");
        del.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                new AlertDialog.Builder(SettingsActivity.this)
                        .setMessage("Удалить провайдера \"" + p.optString("name") + "\"?")
                        .setNegativeButton("Отмена", null)
                        .setPositiveButton("Удалить", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                store.deleteProvider(p.optString("id"));
                                rebuild();
                            }
                        }).show();
            }
        });
        addAction(actions, del);
        card.addView(actions);
        return card;
    }

    private void addAction(LinearLayout row, View b) {
        LinearLayout.LayoutParams p = UiKit.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.rightMargin = UiKit.dp(this, 8);
        b.setLayoutParams(p);
        if (b instanceof TextView) ((TextView) b).setTextSize(13f);
        row.addView(b);
    }

    private void info(LinearLayout card, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.parseColor("#8B949E"));
        t.setTextSize(12.5f);
        t.setPadding(0, UiKit.dp(this, 3), 0, 0);
        card.addView(t);
    }

    private void openEditor(String providerId) {
        Intent i = new Intent(this, ProviderEditActivity.class);
        if (providerId != null) i.putExtra("providerId", providerId);
        startActivityForResult(i, REQ_EDIT_PROVIDER);
    }

    // ------------------------------------------------------------- behavior
    private void buildBehavior() {
        root.addView(UiKit.sectionHead(this, getString(R.string.sec_behavior)));
        final JSONObject s = store.settings();

        // system prompt
        LinearLayout sysF = UiKit.field(this);
        sysF.addView(UiKit.label(this, "Дополнительный system prompt"));
        final EditText sysIn = UiKit.input(this, store.aiSystem(), "Например: пиши на русском, будь кратким", 0, true);
        sysF.addView(sysIn);
        sysIn.addTextChangedListener(new SimpleWatcher() {
            public void changed(String v) { store.setAiSystem(v); }
        });
        sysF.addView(UiKit.spoiler(this, "Дополнительный system prompt",
                "Ваша постоянная инструкция агенту, добавляется к каждому запросу. Например: «отвечай на русском», «будь кратким», «комментируй код». Оставьте пустым, если не нужно."));
        root.addView(sysF);

        // звук
        final android.widget.Switch sound = UiKit.switchRow(this, root, "Звуковой сигнал при получении ответа агента");
        sound.setChecked(s.optBoolean("notifySound", true));
        sound.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton b, boolean c) { store.setSetting("notifySound", c); }
        });
        root.addView(UiKit.spoiler(this, "Звуковой сигнал",
                "Проигрывать звук, когда агент закончил и прислал ответ. Удобно, если вы переключаетесь в другое приложение во время работы агента. Включено по умолчанию. Сигнал берётся из файла res/raw/notify.mp3 — его можно заменить своим mp3."));

        // громкость
        LinearLayout volF = UiKit.field(this);
        volF.addView(UiKit.label(this, "Громкость уведомления"));
        SeekBar vol = new SeekBar(this);
        vol.setMax(100);
        vol.setProgress((int) (s.optDouble("notifyVolume", 0.9) * 100));
        vol.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int p, boolean u) { store.setSetting("notifyVolume", p / 100.0); }
            public void onStartTrackingTouch(SeekBar sb) {}
            public void onStopTrackingTouch(SeekBar sb) {}
        });
        volF.addView(vol);
        // Кнопка «Проверить» — проигрывает пробный сигнал (как в web-версии).
        TextView testBtn = UiKit.button(this, "\u25B6 Проверить", "");
        testBtn.setTextSize(13f);
        LinearLayout.LayoutParams tbp = UiKit.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tbp.topMargin = UiKit.dp(this, 8);
        testBtn.setLayoutParams(tbp);
        testBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { playNotifySound(); }
        });
        volF.addView(testBtn);
        volF.addView(UiKit.spoiler(this, "Громкость уведомления",
                "Насколько громким будет звук уведомления (0 — тихо, вправо — громче). Кнопка «Проверить» проигрывает пробный звук."));
        root.addView(volF);

        // max tool calls
        numberField("Макс. число вызовов инструментов за задачу",
                s.optInt("maxToolCalls", 30), "maxToolCalls", 1, 300,
                "Макс. число вызовов инструментов за задачу",
                "Предохранитель от бесконечных циклов: сколько раз агент может вызвать инструменты (чтение/запись файлов и т.п.) за одну задачу. Для больших задач — 100–300; для мелких правок хватит 30–50.");

        // timeout (sec) -> ms
        LinearLayout toF = UiKit.field(this);
        toF.addView(UiKit.label(this, "Таймаут ответа провайдера, сек"));
        final EditText toIn = UiKit.input(this, String.valueOf(Math.round(s.optInt("providerTimeoutMs", 120000) / 1000)), null, android.text.InputType.TYPE_CLASS_NUMBER, false);
        toF.addView(toIn);
        toIn.addTextChangedListener(new SimpleWatcher() {
            public void changed(String v) {
                int sec = clampInt(v, 120, 15, 900);
                store.setSetting("providerTimeoutMs", sec * 1000);
            }
        });
        toF.addView(UiKit.spoiler(this, "Таймаут ответа провайдера, сек",
                "Сколько ждать ответа модели, прежде чем оборвать запрос с ошибкой. По умолчанию 120. Для больших контекстов/медленных моделей ставьте 300–600."));
        root.addView(toF);

        // max_tokens
        numberField("Макс. токенов в ответе (max_tokens)",
                s.optInt("providerMaxTokens", 8192), "providerMaxTokens", 256, 32768,
                "Макс. токенов в ответе (max_tokens)",
                "Максимальный размер одного ответа модели. Слишком мало — модель обрежет код при записи большого файла. Слишком много — некоторые провайдеры отклонят запрос. Оптимально 4096–8192.");

        // throttle
        LinearLayout thF = UiKit.field(this);
        final android.widget.Switch thCb = UiKit.switchRow(this, thF, "Пауза между запросами к ИИ (защита от «rate limit / 429»)");
        thCb.setChecked(s.optBoolean("toolThrottleEnabled", false));
        final EditText thIn = UiKit.input(this, String.valueOf(s.optInt("toolThrottleMs", 1000)), null, android.text.InputType.TYPE_CLASS_NUMBER, false);
        thIn.setEnabled(thCb.isChecked());
        LinearLayout.LayoutParams thp = UiKit.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        thp.topMargin = UiKit.dp(this, 6);
        thIn.setLayoutParams(thp);
        thF.addView(thIn);
        thCb.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton b, boolean c) {
                store.setSetting("toolThrottleEnabled", c); thIn.setEnabled(c);
            }
        });
        thIn.addTextChangedListener(new SimpleWatcher() {
            public void changed(String v) { store.setSetting("toolThrottleMs", clampInt(v, 1000, 0, 120000)); }
        });
        thF.addView(UiKit.spoiler(this, "Пауза между запросами к ИИ (защита от «429»)",
                "Задержка (в мс) перед каждым обращением к модели. Формула: 60000 ÷ (лимит запросов в минуту). Примеры: 1/мин → 60000; 5/мин → 12000; 20/мин → 3000; 60/мин → 1000. Не знаете лимит — начните с 6000–12000."));
        root.addView(thF);

        // backoff
        LinearLayout bkF = UiKit.field(this);
        final android.widget.Switch bkCb = UiKit.switchRow(this, bkF, "Автоповтор при ответе провайдера «rate limit / 429»");
        bkCb.setChecked(s.optBoolean("autoBackoffOn429", true));
        bkCb.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton b, boolean c) { store.setSetting("autoBackoffOn429", c); }
        });
        bkF.addView(UiKit.spoiler(this, "Автоповтор при «429»",
                "Если провайдер вернул ошибку лимита — клиент сам подождёт (1→2→4→8 сек) и повторит запрос, не теряя прогресс задачи. Рекомендуется держать включённым."));
        root.addView(bkF);
    }

    // ------------------------------------------------------------- crash reports
    /** Секция «Отчёты о сбоях»: список сохранённых отчётов с кнопками
     *  Поделиться / Открыть / Удалить, плюс «Очистить все». */
    private void buildCrashReports() {
        root.addView(UiKit.sectionHead(this, "ОТЧЁТЫ О СБОЯХ"));

        java.util.List<java.io.File> reports = CrashReports.list();

        TextView hint = new TextView(this);
        hint.setText("Если приложение неожиданно закрывается, здесь появляется подробный "
                + "отчёт о причине сбоя. Им можно поделиться или открыть в любом приложении.");
        hint.setTextColor(Color.parseColor("#8B949E"));
        hint.setTextSize(12.5f);
        hint.setPadding(0, UiKit.dp(this, 2), 0, UiKit.dp(this, 6));
        root.addView(hint);

        if (reports.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("Сбоев не зафиксировано \u2014 отлично.");
            empty.setTextColor(Color.parseColor("#8B949E"));
            empty.setTextSize(13f);
            root.addView(empty);
            return;
        }

        for (final java.io.File f : reports) root.addView(crashCard(f));

        TextView clearAll = UiKit.button(this, "Очистить все отчёты", "danger");
        clearAll.setTextSize(13f);
        LinearLayout.LayoutParams lp = UiKit.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = UiKit.dp(this, 10);
        clearAll.setLayoutParams(lp);
        clearAll.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                new AlertDialog.Builder(SettingsActivity.this)
                        .setMessage("Удалить все отчёты о сбоях?")
                        .setNegativeButton("Отмена", null)
                        .setPositiveButton("Удалить", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                CrashReports.deleteAll();
                                rebuild();
                            }
                        }).show();
            }
        });
        root.addView(clearAll);
    }

    private View crashCard(final java.io.File f) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        card.setPadding(UiKit.dp(this, 12), UiKit.dp(this, 12), UiKit.dp(this, 12), UiKit.dp(this, 12));
        LinearLayout.LayoutParams clp = UiKit.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = UiKit.dp(this, 10);
        card.setLayoutParams(clp);

        TextView name = new TextView(this);
        name.setText(f.getName());
        name.setTextColor(Color.WHITE);
        name.setTextSize(14f);
        name.getPaint().setFakeBoldText(true);
        card.addView(name);

        String when = new java.text.SimpleDateFormat("dd.MM.yyyy HH:mm:ss", java.util.Locale.US)
                .format(new java.util.Date(f.lastModified()));
        info(card, when + "  •  " + (Math.max(1, f.length() / 1024)) + " КБ");

        // Превью первых строк отчёта (причина сбоя).
        String preview = crashPreview(f);
        if (!preview.isEmpty()) {
            TextView pv = new TextView(this);
            pv.setText(preview);
            pv.setTextColor(Color.parseColor("#F85149"));
            pv.setTextSize(12f);
            pv.setPadding(0, UiKit.dp(this, 6), 0, 0);
            card.addView(pv);
        }

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams ap = UiKit.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ap.topMargin = UiKit.dp(this, 10);
        actions.setLayoutParams(ap);

        TextView share = UiKit.button(this, "Поделиться", "primary");
        LinearLayout.LayoutParams sp = UiKit.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.weight = 1; sp.rightMargin = UiKit.dp(this, 8);
        share.setLayoutParams(sp);
        share.setTextSize(13f);
        share.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { CrashReports.share(SettingsActivity.this, f); }
        });
        actions.addView(share);

        TextView open = UiKit.button(this, "Открыть", "");
        LinearLayout.LayoutParams op = UiKit.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT);
        op.weight = 1; op.rightMargin = UiKit.dp(this, 8);
        open.setLayoutParams(op);
        open.setTextSize(13f);
        open.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { CrashReports.open(SettingsActivity.this, f); }
        });
        actions.addView(open);

        android.widget.ImageView del = UiKit.iconButton(this, R.drawable.ic_delete_w, "danger");
        del.setContentDescription("Удалить отчёт");
        LinearLayout.LayoutParams dp = UiKit.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        del.setLayoutParams(dp);
        del.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { CrashReports.delete(f); rebuild(); }
        });
        actions.addView(del);

        card.addView(actions);
        return card;
    }

    /** Короткое превью причины сбоя из отчёта (блок «Причина сбоя»). */
    private String crashPreview(java.io.File f) {
        try {
            String txt = CrashReports.read(f);
            int i = txt.indexOf("--- Причина сбоя ---");
            if (i >= 0) {
                String rest = txt.substring(i + "--- Причина сбоя ---".length()).trim();
                int end = rest.indexOf("\n\n");
                String block = end > 0 ? rest.substring(0, end) : rest;
                if (block.length() > 220) block = block.substring(0, 220) + "…";
                return block.trim();
            }
            // legacy-формат: берём первую строку исключения.
            String[] lines = txt.split("\n");
            for (String l : lines) if (l.contains("Exception") || l.contains("Error")) return l.trim();
        } catch (Throwable ignored) {}
        return "";
    }

    private void numberField(String label, int value, final String key, final int min, final int max, String spTitle, String spBody) {
        LinearLayout f = UiKit.field(this);
        f.addView(UiKit.label(this, label));
        final EditText in = UiKit.input(this, String.valueOf(value), null, android.text.InputType.TYPE_CLASS_NUMBER, false);
        f.addView(in);
        in.addTextChangedListener(new SimpleWatcher() {
            public void changed(String v) { store.setSetting(key, clampInt(v, min, min, max)); }
        });
        f.addView(UiKit.spoiler(this, spTitle, spBody));
        root.addView(f);
    }

    // ------------------------------------------------------------- helpers
    /** Пробный звук уведомления (res/raw/notify.mp3) с учётом сохранённой громкости. */
    private void playNotifySound() {
        try {
            double volFraction = store.settings().optDouble("notifyVolume", 0.9);
            NotifySound.play(this, volFraction);
        } catch (Exception e) {
            toast("Не удалось воспроизвести звук");
        }
    }

    private int clampInt(String v, int def, int min, int max) {
        int n;
        try { n = Integer.parseInt(v.trim()); } catch (Exception e) { n = def; }
        return Math.max(min, Math.min(max, n));
    }

    private String maskKey(String k) {
        if (k == null || k.isEmpty()) return "—";
        if (k.length() <= 8) return "••••";
        return k.substring(0, 4) + "••••" + k.substring(k.length() - 4);
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    /** Упрощённый TextWatcher. */
    static abstract class SimpleWatcher implements TextWatcher {
        public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
        public void onTextChanged(CharSequence s, int a, int b, int c) {}
        public void afterTextChanged(Editable e) { changed(e.toString()); }
        public abstract void changed(String v);
    }
}
