package com.idemobile.app;

import android.app.AlertDialog;
import android.app.Fragment;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Base64;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Вкладка «Агент» — чат с ИИ (порт поведения web-версии ai.js/chats.js).
 *
 * Сообщения хранятся персистентно в store.activeChat().history и полностью
 * перерисовываются из неё (как renderMessages() в web). Каждое сообщение —
 * JSONObject: {role, content, realModel?, attachments?[], interject?, hidden?, silent?}.
 *
 * Пузыри:
 *  - крестик удаления (справа сверху, без подтверждения);
 *  - тап по пузырю показывает/скрывает панель действий;
 *  - assistant: бейдж «модель: <realModel>», копирование, регенерация (для последнего);
 *  - user: копирование, пере-отправка (текст в поле ввода, сообщение остаётся);
 *  - вложения: картинки как <img>, текстовые файлы как имя+разворачиваемый контейнер.
 *
 * Кнопка отправки — 3 состояния: ▶ (idle), ⏹ (агент работает, поле пустое),
 * ▶ другого цвета (агент работает + есть текст → interject без остановки).
 */
public class AgentFragment extends Fragment {

    // Цвета пузырей: мои — тёмно-синий, агента — тёмно-серый.
    private static final String USER_BG = "#153A66";
    private static final String ASSISTANT_BG = "#2C333D";

    private LinearLayout chatList;
    private ScrollView chatScroll;
    private LinearLayout attachPreview;
    private EditText input;
    private TextView modelText;
    private TextView modelSub;
    private ImageButton btnSend;
    private Store store;

    // Выпадающий список моделей
    private LinearLayout modelDropdown, ddList;
    private EditText ddSearch;
    private TextView ddInfo;
    private ImageButton ddRefresh;
    private boolean ddOpen = false;
    private final List<ApiClient.Model> ddModels = new ArrayList<ApiClient.Model>();
    private final Map<String, ApiClient.Probe> ddProbes = new HashMap<String, ApiClient.Probe>();

    private boolean sending = false;
    // Чат, к которому ПРИВЯЗАН текущий запуск агента. Все сообщения агента идут
    // именно в этот чат (по id), даже если пользователь создаст/переключит чат
    // во время работы. Иначе сообщения «протекали» в новый чат, а кнопка
    // оставалась ⏹. Новый чат теперь — отдельная сессия. null = запуска нет.
    private volatile String runChatId = null;
    // volatile: флаг пишется на UI-потоке (кнопка «стоп»), а читается на фоновом
    // потоке агента. Без volatile обновление могло быть не видно фоновому потоку —
    // остановка «залипала». Теперь изменение видно немедленно.
    private volatile boolean cancelled = false;
    // Активное HTTP-соединение к провайдеру (для мгновенного обрыва по «стоп»).
    private volatile ApiClient.Canceller currentCanceller;
    // Фоновый поток агента (для interrupt() при остановке).
    private volatile Thread workerThread;
    private Agent agent;

    // Очередь interject-сообщений (пришедших во время работы агента).
    private final List<String> inbox = Collections.synchronizedList(new ArrayList<String>());
    // Вложения для СЛЕДУЮЩЕГО исходящего сообщения.
    private final List<JSONObject> pendingAttachments = new ArrayList<JSONObject>();
    private StatusCard currentCard;
    // Живая карточка статуса работающего агента и id её чата. В отличие от
    // currentCard (которую обнуляет renderMessages), liveCard переживает
    // перерисовку и переключение чатов: при возврате в рабочий чат мы снова
    // присоединяем ЭТУ ЖЕ карточку в ленту (с накопленным логом), а при уходе в
    // другой чат — она просто не добавляется в чужую ленту.
    private StatusCard liveCard;
    private String liveCardChatId;
    // ЗАВЕРШЁННЫЕ карточки статуса по чатам. Карточка НИКОГДА не исчезает сама:
    // после finish() она попадает сюда и восстанавливается при каждой перерисовке
    // чата (renderMessages). Удаляется ТОЛЬКО когда пользователь жмёт «×» на
    // карточке (см. setOnRemove в persistFinishedCard).
    private final Map<String, List<StatusCard>> finishedCards = new HashMap<String, List<StatusCard>>();
    // Чаты, ожидающие запуска: агент был занят в другом чате, поэтому сообщения
    // пользователя УЖЕ добавлены в их историю, а сам прогон отложен до
    // освобождения агента (один поток/отмена на приложение). FIFO-порядок.
    private final List<String> pendingRunChats = new ArrayList<String>();

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle saved) {
        try {
            return buildView(inflater, container);
        } catch (Throwable e) {
            App.writeCrash(e);
            java.io.StringWriter sw = new java.io.StringWriter();
            e.printStackTrace(new java.io.PrintWriter(sw));
            TextView tv = new TextView(getActivity());
            tv.setText("Ошибка вкладки «Агент»:\n\n" + sw.toString());
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
        View root = inflater.inflate(R.layout.fragment_agent, container, false);
        store = Store.get(getActivity());

        chatList = (LinearLayout) root.findViewById(R.id.chat_list);
        chatScroll = (ScrollView) root.findViewById(R.id.chat_scroll);
        attachPreview = (LinearLayout) root.findViewById(R.id.attach_preview);
        input = (EditText) root.findViewById(R.id.input_message);
        modelText = (TextView) root.findViewById(R.id.model_text);
        modelSub = (TextView) root.findViewById(R.id.model_sub);

        modelDropdown = (LinearLayout) root.findViewById(R.id.model_dropdown);
        ddList = (LinearLayout) root.findViewById(R.id.dd_list);
        ddSearch = (EditText) root.findViewById(R.id.dd_search);
        ddInfo = (TextView) root.findViewById(R.id.dd_info);
        ddRefresh = (ImageButton) root.findViewById(R.id.dd_refresh);

        View btnModel = root.findViewById(R.id.btn_model);
        ImageButton btnClear = (ImageButton) root.findViewById(R.id.btn_clear);
        btnSend = (ImageButton) root.findViewById(R.id.btn_send);
        ImageButton btnAttach = (ImageButton) root.findViewById(R.id.btn_attach);
        ImageButton btnMenu = (ImageButton) root.findViewById(R.id.btn_menu);
        ImageButton btnChats = (ImageButton) root.findViewById(R.id.btn_chats);
        ImageButton btnSettings = (ImageButton) root.findViewById(R.id.btn_settings);

        updateHeader();

        btnModel.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { toggleModelDropdown(); }
        });
        btnClear.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { clearChat(); }
        });
        btnSend.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { submitFromInput(); }
        });
        btnAttach.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pickAttachment(); }
        });
        btnMenu.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showSideMenu(); }
        });
        btnChats.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showChats(); }
        });
        btnSettings.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(new Intent(getActivity(), SettingsActivity.class));
            }
        });

        ddRefresh.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { loadModels(); }
        });
        ddSearch.addTextChangedListener(new SettingsActivity.SimpleWatcher() {
            public void changed(String s) { renderDropdown(); }
        });
        input.addTextChangedListener(new SettingsActivity.SimpleWatcher() {
            public void changed(String s) { updateSendButton(); }
        });

        renderMessages();
        renderAttachPreview();
        updateSendButton();
        return root;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (store != null) { updateHeader(); }
    }

    /** Заголовок «ПРОВАЙДЕР · модель» из активного провайдера в Store. */
    private void updateHeader() {
        JSONObject p = store.activeProvider();
        if (p == null) {
            modelText.setText("Нет провайдера");
            if (modelSub != null) modelSub.setVisibility(View.GONE);
            return;
        }
        String name = p.optString("name", "провайдер");
        String model = p.optString("model", "");
        // Две строки (как в web-версии): провайдер — голубым мелким сверху,
        // модель — ещё мельче светлым снизу.
        modelText.setText(name);
        if (modelSub != null) {
            if (model.isEmpty()) {
                modelSub.setVisibility(View.GONE);
            } else {
                modelSub.setText(model);
                modelSub.setVisibility(View.VISIBLE);
            }
        }
    }

    // =============================================== выпадающий список моделей
    private void toggleModelDropdown() {
        JSONObject p = store.activeProvider();
        if (p == null) {
            new AlertDialog.Builder(getActivity())
                    .setMessage("Провайдеров нет. Добавьте в настройках.")
                    .setPositiveButton("Настройки", new android.content.DialogInterface.OnClickListener() {
                        public void onClick(android.content.DialogInterface d, int w) {
                            startActivity(new Intent(getActivity(), SettingsActivity.class));
                        }
                    })
                    .setNegativeButton("Отмена", null).show();
            return;
        }
        ddOpen = !ddOpen;
        modelDropdown.setVisibility(ddOpen ? View.VISIBLE : View.GONE);
        if (ddOpen) {
            loadModelsFromProvider(p);
            renderDropdown();
            if (ddModels.isEmpty()) ddInfo.setText("Нажмите ⟳, чтобы загрузить список моделей");
        }
    }

    private void loadModelsFromProvider(JSONObject p) {
        ddModels.clear();
        ddProbes.clear();
        JSONArray saved = p.optJSONArray("models");
        if (saved != null) {
            for (int i = 0; i < saved.length(); i++) {
                JSONObject m = saved.optJSONObject(i);
                if (m != null) ddModels.add(new ApiClient.Model(m.optString("id"), m.optString("name"), m.optJSONObject("raw")));
            }
        }
        JSONObject probes = p.optJSONObject("probes");
        if (probes != null) {
            java.util.Iterator<String> it = probes.keys();
            while (it.hasNext()) {
                String k = it.next();
                JSONObject pr = probes.optJSONObject(k);
                if (pr != null) {
                    Boolean av = pr.has("available") && !pr.isNull("available") ? pr.optBoolean("available") : null;
                    ddProbes.put(k, new ApiClient.Probe(av, pr.optInt("status"), pr.optString("reason", null)));
                }
            }
        }
    }

    private void loadModels() {
        final JSONObject p = store.activeProvider();
        if (p == null) return;
        if (p.optString("baseUrl", "").trim().isEmpty()) { toast("У провайдера пустой Base URL"); return; }
        ddInfo.setText("Загрузка…");
        ddRefresh.setEnabled(false);
        new Thread(new Runnable() {
            public void run() {
                try {
                    final List<ApiClient.Model> res = ApiClient.listModels(p);
                    runOnUi(new Runnable() {
                        public void run() {
                            ddModels.clear(); ddModels.addAll(res); ddProbes.clear();
                            saveModelsToProvider(p);
                            ddInfo.setText("Загружено моделей: " + ddModels.size());
                            renderDropdown();
                            ddRefresh.setEnabled(true);
                        }
                    });
                } catch (final Exception e) {
                    runOnUi(new Runnable() {
                        public void run() {
                            ddInfo.setText("Ошибка загрузки");
                            ddRefresh.setEnabled(true);
                            toast("Не удалось: " + firstLine(e.getMessage()));
                        }
                    });
                }
            }
        }).start();
    }

    private void saveModelsToProvider(JSONObject p) {
        try {
            JSONArray arr = new JSONArray();
            for (ApiClient.Model m : ddModels) {
                JSONObject o = new JSONObject();
                o.put("id", m.id); o.put("name", m.name);
                if (m.raw != null) o.put("raw", m.raw);
                arr.put(o);
            }
            p.put("models", arr);
            store.upsertProvider(p);
        } catch (org.json.JSONException ignored) {}
    }

    private void renderDropdown() {
        ddList.removeAllViews();
        String q = ddSearch.getText().toString().trim().toLowerCase();
        List<ApiClient.Model> filtered = new ArrayList<ApiClient.Model>();
        for (ApiClient.Model m : ddModels) {
            if (q.isEmpty() || (m.id != null && m.id.toLowerCase().contains(q))
                    || (m.name != null && m.name.toLowerCase().contains(q))) filtered.add(m);
        }
        if (filtered.isEmpty()) {
            TextView t = new TextView(getActivity());
            t.setText(ddModels.isEmpty() ? "Список пуст — нажмите ⟳" : "Ничего не найдено");
            t.setTextColor(Color.parseColor("#8B949E"));
            t.setTextSize(12f);
            t.setPadding(dp(6), dp(6), 0, 0);
            ddList.addView(t);
            return;
        }
        String activeModel = store.activeProvider() != null ? store.activeProvider().optString("model", "") : "";
        int LIMIT = 200, shown = 0;
        for (final ApiClient.Model m : filtered) {
            if (shown++ >= LIMIT) {
                TextView more = new TextView(getActivity());
                more.setText("…показаны первые " + LIMIT + " из " + filtered.size() + ". Уточните поиск.");
                more.setTextColor(Color.parseColor("#8B949E"));
                more.setTextSize(11.5f);
                more.setPadding(dp(6), dp(8), 0, dp(4));
                ddList.addView(more);
                break;
            }
            LinearLayout row = new LinearLayout(getActivity());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(8), dp(8), dp(8), dp(8));
            if (m.id.equals(activeModel)) row.setBackgroundResource(R.drawable.bg_model_row_sel);

            LinearLayout col = new LinearLayout(getActivity());
            col.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT);
            cp.weight = 1; col.setLayoutParams(cp);
            TextView idT = new TextView(getActivity());
            idT.setText(m.id);
            idT.setTextColor(Color.parseColor("#E6EDF3"));
            idT.setTextSize(13.5f);
            idT.setTypeface(android.graphics.Typeface.MONOSPACE);
            col.addView(idT);
            if (m.name != null && !m.name.isEmpty() && !m.name.equals(m.id)) {
                TextView nm = new TextView(getActivity());
                nm.setText(m.name);
                nm.setTextColor(Color.parseColor("#8B949E"));
                nm.setTextSize(11f);
                col.addView(nm);
            }
            row.addView(col);

            ApiClient.Probe pr = ddProbes.get(m.id);
            String tier = ApiClient.availabilityTier(m, pr);
            int color = tier.equals("free") ? Color.parseColor("#3FB950")
                    : tier.equals("paid") ? Color.parseColor("#F85149")
                    : Color.parseColor("#6E7681");
            row.addView(UiKit.dot(getActivity(), color, 11));

            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    JSONObject p = store.activeProvider();
                    if (p != null) {
                        try { p.put("model", m.id); } catch (org.json.JSONException ignored) {}
                        store.upsertProvider(p);
                        updateHeader();
                    }
                    ddOpen = false;
                    modelDropdown.setVisibility(View.GONE);
                    toast("Модель выбрана: " + m.id);
                }
            });
            ddList.addView(row);
        }
    }

    // ================================================================= чаты
    /** Окно со списком чатов: «+ Новый чат» + карточки (заголовок, N сообщ · время, удалить). */
    private void showChats() {
        final android.app.Dialog dlg = new android.app.Dialog(getActivity());
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);

        LinearLayout wrap = new LinearLayout(getActivity());
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setBackgroundColor(Color.parseColor("#0E1116"));
        wrap.setPadding(dp(16), dp(16), dp(16), dp(16));

        LinearLayout head = new LinearLayout(getActivity());
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(getActivity());
        title.setText("Чаты");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20f);
        title.getPaint().setFakeBoldText(true);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.weight = 1; title.setLayoutParams(tp);
        TextView close = new TextView(getActivity());
        close.setText("\u2715");
        close.setTextColor(Color.parseColor("#8B949E"));
        close.setTextSize(20f);
        close.setPadding(dp(10), dp(4), dp(6), dp(4));
        close.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { dlg.dismiss(); } });
        head.addView(title); head.addView(close);
        wrap.addView(head);

        TextView newBtn = UiKit.button(getActivity(), "+ Новый чат", "primary");
        LinearLayout.LayoutParams nbp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nbp.topMargin = dp(14); nbp.bottomMargin = dp(6);
        newBtn.setLayoutParams(nbp);
        newBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                store.newChat(null);
                pendingAttachments.clear(); inbox.clear();
                // Новый чат — отдельная сессия: перерисуем его (пустой) и обновим
                // кнопку. Если агент ещё работает в прежнем чате, тут будет ▶, а
                // не ⏹, и его сообщения сюда не попадут.
                renderMessages(); renderAttachPreview(); updateSendButton();
                dlg.dismiss(); toast("Новый чат создан");
            }
        });
        wrap.addView(newBtn);

        ScrollView sv = new ScrollView(getActivity());
        LinearLayout.LayoutParams svp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(360));
        sv.setLayoutParams(svp);
        final LinearLayout listCol = new LinearLayout(getActivity());
        listCol.setOrientation(LinearLayout.VERTICAL);
        sv.addView(listCol);
        wrap.addView(sv);

        renderChatList(listCol, dlg);

        dlg.setContentView(wrap);
        if (dlg.getWindow() != null) {
            dlg.getWindow().setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.92), ViewGroup.LayoutParams.WRAP_CONTENT);
            dlg.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        }
        dlg.show();
    }

    private void renderChatList(final LinearLayout listCol, final android.app.Dialog dlg) {
        listCol.removeAllViews();
        // Сортировка по updatedAt (свежие сверху), как в web.
        JSONArray chats = store.chats();
        List<JSONObject> sorted = new ArrayList<JSONObject>();
        for (int i = 0; i < chats.length(); i++) {
            JSONObject c = chats.optJSONObject(i);
            if (c != null) sorted.add(c);
        }
        Collections.sort(sorted, new java.util.Comparator<JSONObject>() {
            public int compare(JSONObject a, JSONObject b) {
                long ta = a.optLong("updatedAt", a.optLong("createdAt", 0));
                long tb = b.optLong("updatedAt", b.optLong("createdAt", 0));
                return Long.compare(tb, ta);
            }
        });
        String activeId = store.activeChatId();
        for (int i = 0; i < sorted.size(); i++) {
            final JSONObject c = sorted.get(i);
            final String id = c.optString("id");
            boolean active = id.equals(activeId);

            LinearLayout card = new LinearLayout(getActivity());
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setBackgroundResource(active ? R.drawable.bg_model_row_sel : R.drawable.bg_card);
            card.setPadding(dp(14), dp(12), dp(10), dp(12));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cp.topMargin = dp(8); card.setLayoutParams(cp);

            LinearLayout col = new LinearLayout(getActivity());
            col.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams colp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT);
            colp.weight = 1; col.setLayoutParams(colp);
            TextView t = new TextView(getActivity());
            t.setText(c.optString("title", "Новый чат"));
            t.setTextColor(Color.WHITE);
            t.setTextSize(16f);
            t.getPaint().setFakeBoldText(true);
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            col.addView(t);
            TextView meta = new TextView(getActivity());
            int cnt = countVisible(c.optJSONArray("history"));
            meta.setText(cnt + " сообщ · " + timeOf(c.optLong("updatedAt", c.optLong("createdAt", 0))));
            meta.setTextColor(Color.parseColor("#8B949E"));
            meta.setTextSize(12f);
            col.addView(meta);
            card.addView(col);

            ImageButton del = new ImageButton(getActivity());
            del.setImageResource(R.drawable.ic_trash);
            del.setColorFilter(Color.parseColor("#8B949E"));
            del.setBackgroundResource(0);
            del.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(44)));
            del.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    store.deleteChat(id);
                    // Чистим связанное с удалённым чатом состояние (очередь запусков
                    // и сохранённые карточки статуса), чтобы ничего не «всплыло» позже.
                    pendingRunChats.remove(id);
                    finishedCards.remove(id);
                    pendingAttachments.clear(); inbox.clear();
                    renderMessages(); renderAttachPreview(); updateSendButton();
                    renderChatList(listCol, dlg);
                }
            });
            card.addView(del);

            card.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    store.switchChat(id);
                    pendingAttachments.clear(); inbox.clear();
                    renderMessages(); renderAttachPreview(); updateSendButton();
                    dlg.dismiss();
                }
            });
            listCol.addView(card);
        }
    }

    /** Кол-во «видимых» сообщений (без hidden/системных), как счётчик в web. */
    private int countVisible(JSONArray h) {
        if (h == null) return 0;
        int n = 0;
        for (int i = 0; i < h.length(); i++) {
            JSONObject m = h.optJSONObject(i);
            if (m == null) continue;
            if (m.optBoolean("hidden", false)) continue;
            String r = m.optString("role");
            if ("user".equals(r) || "assistant".equals(r)) n++;
        }
        return n;
    }

    private String timeOf(long ms) {
        if (ms <= 0) return "—";
        return new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(new java.util.Date(ms));
    }

    private void runOnUi(Runnable r) { if (getActivity() != null) getActivity().runOnUiThread(r); }
    private String firstLine(String s) {
        if (s == null) return "ошибка";
        int i = s.indexOf('\n');
        return i > 0 ? s.substring(0, i) : s;
    }

    /**
     * Левое ВЫДВИЖНОЕ меню (drawer) — как в web-версии (см. скрин): панель
     * выезжает слева направо с затемнением фона, содержит заголовок «Mobile IDE»
     * и пункты. Пока один пункт — «О приложении». Закрывается тапом по затемнению
     * или по кнопке «назад» устройства. Реализовано без сторонних библиотек через
     * оверлей в корневом FrameLayout окна.
     */
    private void showSideMenu() {
        if (getActivity() == null) return;
        final android.widget.FrameLayout decor =
                (android.widget.FrameLayout) getActivity().getWindow().getDecorView();

        // Затемняющий фон на весь экран.
        final android.widget.FrameLayout overlay = new android.widget.FrameLayout(getActivity());
        overlay.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlay.setBackgroundColor(Color.parseColor("#00000000"));
        overlay.setClickable(true);
        overlay.setFocusableInTouchMode(true);

        // Выдвижная панель.
        int panelW = Math.min(dp(320),
                (int) (getResources().getDisplayMetrics().widthPixels * 0.82f));
        final LinearLayout panel = new LinearLayout(getActivity());
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(Color.parseColor("#161B22"));
        android.widget.FrameLayout.LayoutParams plp = new android.widget.FrameLayout.LayoutParams(
                panelW, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START);
        panel.setLayoutParams(plp);
        panel.setPadding(0, dp(18), 0, dp(8));
        panel.setElevation(dp(8));

        // Заголовок.
        TextView title = new TextView(getActivity());
        title.setText("Mobile IDE");
        title.setTextColor(Color.WHITE);
        title.setTextSize(24f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(dp(20), dp(6), dp(20), dp(2));
        panel.addView(title);

        TextView sub = new TextView(getActivity());
        sub.setText("Локально на устройстве");
        sub.setTextColor(Color.parseColor("#8B949E"));
        sub.setTextSize(14f);
        sub.setPadding(dp(20), 0, dp(20), dp(14));
        panel.addView(sub);

        // Разделитель.
        View div = new View(getActivity());
        div.setBackgroundColor(Color.parseColor("#2A313B"));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        dlp.bottomMargin = dp(8);
        div.setLayoutParams(dlp);
        panel.addView(div);

        // Runnable закрытия панели с анимацией.
        final Runnable[] close = new Runnable[1];
        final int wPx = panelW;
        close[0] = new Runnable() {
            public void run() {
                panel.animate().translationX(-wPx).setDuration(200).start();
                overlay.animate().alpha(0f).setDuration(200)
                        .withEndAction(new Runnable() {
                            public void run() { try { decor.removeView(overlay); } catch (Exception ignored) {} }
                        }).start();
            }
        };

        // Пункт «О приложении».
        panel.addView(makeDrawerItem("\u24D8", "О приложении", new Runnable() {
            public void run() { close[0].run(); showAbout(); }
        }));

        overlay.addView(panel);
        // Тап по затемнению (вне панели) — закрыть.
        overlay.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { close[0].run(); }
        });
        // Клик по самой панели не должен закрывать (перехватываем).
        panel.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { /* поглощаем */ }
        });
        // Кнопка «назад» закрывает drawer.
        overlay.setOnKeyListener(new View.OnKeyListener() {
            public boolean onKey(View v, int keyCode, android.view.KeyEvent e) {
                if (keyCode == android.view.KeyEvent.KEYCODE_BACK
                        && e.getAction() == android.view.KeyEvent.ACTION_UP) {
                    close[0].run();
                    return true;
                }
                return false;
            }
        });

        decor.addView(overlay);
        overlay.requestFocus();

        // Анимация появления: панель выезжает слева, фон затемняется.
        panel.setTranslationX(-panelW);
        overlay.setAlpha(0f);
        panel.animate().translationX(0).setDuration(220).start();
        overlay.animate().alpha(1f).setDuration(220).start();
        overlay.setBackgroundColor(Color.parseColor("#99000000"));
    }

    /** Строка-пункт выдвижного меню: иконка + подпись. */
    private View makeDrawerItem(String icon, String label, final Runnable action) {
        LinearLayout row = new LinearLayout(getActivity());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(20), dp(16), dp(20), dp(16));
        row.setClickable(true);
        row.setBackground(rippleBg());

        TextView ic = new TextView(getActivity());
        ic.setText(icon);
        ic.setTextColor(Color.parseColor("#8B949E"));
        ic.setTextSize(20f);
        ic.setPadding(0, 0, dp(16), 0);
        row.addView(ic);

        TextView tx = new TextView(getActivity());
        tx.setText(label);
        tx.setTextColor(Color.parseColor("#E6EDF3"));
        tx.setTextSize(17f);
        tx.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(tx);

        row.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { if (action != null) action.run(); }
        });
        return row;
    }

    /** Фон с эффектом нажатия для пунктов drawer (без внешних ресурсов). */
    private android.graphics.drawable.Drawable rippleBg() {
        android.graphics.drawable.StateListDrawable sld = new android.graphics.drawable.StateListDrawable();
        android.graphics.drawable.ColorDrawable pressed =
                new android.graphics.drawable.ColorDrawable(Color.parseColor("#22314A"));
        sld.addState(new int[]{ android.R.attr.state_pressed }, pressed);
        sld.addState(new int[]{}, new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        return sld;
    }

    /**
     * Строка копирайта в окне «О приложении». Вынесена в отдельную константу и
     * явно видна в коде, чтобы её было легко править. (Web-аналог: app.js,
     * переменная ABOUT_COPYRIGHT.)
     */
    private static final String ABOUT_COPYRIGHT = "© 2026 by Claude Opus 4.8";

    private void showAbout() {
        String text =
                "IDE Mobile — редактор кода и ИИ-агент в стиле VS Code для Android.\n\n" +
                "Поддерживает произвольных ИИ-провайдеров, работу с файлами, архивами, "
                + "git и GitHub.\n\n" +
                "Все данные (файлы, ключи, настройки) хранятся локально на устройстве "
                + "и никуда не отправляются, кроме выбранного провайдера.\n\n" +
                ABOUT_COPYRIGHT;
        new AlertDialog.Builder(getActivity())
                .setTitle("О приложении")
                .setMessage(text)
                .setPositiveButton("OK", null)
                .show();
    }

    private void clearChat() {
        final String activeId = store.activeChatId();
        JSONArray h = store.history();
        List<StatusCard> done = finishedCards.get(activeId);
        boolean hasCards = done != null && !done.isEmpty();
        if (h.length() == 0 && !hasCards) { toast("Чат уже пуст"); return; }
        while (h.length() > 0) h.remove(0);
        store.touchActiveChat();
        // Очищаем и ЗАВЕРШЁННЫЕ карточки статуса этого чата — иначе renderMessages
        // восстановит их и окна статусов «повиснут» после очистки чата.
        if (done != null) done.clear();
        finishedCards.remove(activeId);
        // Если живая карточка принадлежит этому чату и агент НЕ работает — убираем
        // и её ссылку (работающий прогон не трогаем, он в другом/этом чате идёт).
        if (!sending && liveCardChatId != null && liveCardChatId.equals(activeId)) {
            liveCard = null; liveCardChatId = null;
        }
        renderMessages();
        toast("Чат очищен");
    }

    // ============================================================ рендер чата
    /**
     * Регистрирует ЗАВЕРШЁННУЮ карточку статуса как «постоянную» для её чата:
     * она будет восстанавливаться при каждой перерисовке и исчезнет ТОЛЬКО когда
     * пользователь нажмёт «×». Вешает onRemove, чтобы ручное удаление вычищало
     * карточку из хранилища (иначе renderMessages вернул бы её обратно).
     */
    private void persistFinishedCard(final String chatId, final StatusCard card) {
        if (chatId == null || card == null) return;
        List<StatusCard> list = finishedCards.get(chatId);
        if (list == null) { list = new ArrayList<StatusCard>(); finishedCards.put(chatId, list); }
        if (!list.contains(card)) list.add(card);
        card.setOnRemove(new Runnable() {
            public void run() {
                List<StatusCard> l = finishedCards.get(chatId);
                if (l != null) l.remove(card);
            }
        });
    }

    /** Полная перерисовка чата из истории активного чата (порт renderMessages). */
    private void renderMessages() {
        chatList.removeAllViews();
        currentCard = null;
        JSONArray h = store.history();
        for (int i = 0; i < h.length(); i++) {
            JSONObject m = h.optJSONObject(i);
            if (m == null) continue;
            if (m.optBoolean("hidden", false)) continue;
            if (m.optBoolean("silent", false)) continue;
            String role = m.optString("role");
            if ("user".equals(role)) addUserBubble(m);
            else if ("assistant".equals(role)) addAssistantBubble(m);
            else if ("error".equals(role)) addErrorBubble(m);
            // system/tool — в контекст модели, но не в ленту.
        }
        final String activeId = store.activeChatId();
        // 1) Восстанавливаем ЗАВЕРШЁННЫЕ карточки статуса этого чата — они видны до
        //    ручного удаления пользователем (крестик), сами никогда не пропадают.
        List<StatusCard> done = finishedCards.get(activeId);
        if (done != null) {
            for (StatusCard sc : done) {
                ViewGroup p = (ViewGroup) sc.getParent();
                if (p != null) p.removeView(sc);
                chatList.addView(sc);
            }
        }
        // 2) Если открыт ИМЕННО тот чат, где сейчас работает агент — возвращаем в
        //    ленту его живую карточку статуса (с уже накопленным логом).
        if (sending && liveCard != null && liveCardChatId != null
                && liveCardChatId.equals(activeId)) {
            ViewGroup parent = (ViewGroup) liveCard.getParent();
            if (parent != null) parent.removeView(liveCard);
            chatList.addView(liveCard);
            currentCard = liveCard;
        }
        maybeShowInterruptedRunNotice(h);
        scrollDown();
    }

    /**
     * Если предыдущий запуск был ПРЕРВАН (вылет/закрытие) — история содержит
     * промежуточную работу агента (silent-ответы, скрытые результаты инструментов),
     * но НЕ завершилась ни финальным видимым ответом, ни ошибкой. Раньше эта работа
     * была не видна и выглядела как «прогресс пропал». Теперь показываем аккуратную
     * плашку: прогресс сохранён, можно продолжить одним сообщением.
     */
    private void maybeShowInterruptedRunNotice(JSONArray h) {
        try {
            if (sending) return;               // идёт активный запуск — не мешаем
            int last = h.length() - 1;
            if (last < 0) return;
            // Пользователь уже удалил плашку в этом чате — больше не показываем.
            JSONObject chat = store.activeChat();
            if (chat != null && chat.optBoolean("interruptedNoticeDismissed", false)) return;
            // Если плашка уже есть в истории — просто рисуем её (не дублируем).
            for (int i = last; i >= 0; i--) {
                JSONObject m = h.optJSONObject(i);
                if (m != null && m.optBoolean("interruptedNotice", false)) { addAssistantBubble(m); return; }
            }
            // Ищем последнее НЕслужебное завершение: видимый assistant или error.
            boolean endedCleanly = false;
            boolean hasAgentWork = false;
            for (int i = last; i >= 0; i--) {
                JSONObject m = h.optJSONObject(i);
                if (m == null) continue;
                String role = m.optString("role");
                boolean silent = m.optBoolean("silent", false);
                boolean hidden = m.optBoolean("hidden", false);
                if ("error".equals(role)) { endedCleanly = true; break; }
                if ("assistant".equals(role) && !silent) { endedCleanly = true; break; }
                if ("user".equals(role) && !hidden) break; // дошли до последнего вопроса — дальше работы нет
                if (("assistant".equals(role) && silent) || (hidden && "user".equals(role)) || "tool".equals(role)) hasAgentWork = true;
            }
            if (!endedCleanly && hasAgentWork) {
                JSONObject note = new JSONObject();
                note.put("role", "assistant");
                note.put("interruptedNotice", true);
                note.put("content", "⚠ Предыдущий запуск был прерван (возможно, приложение закрылось). "
                        + "Прогресс агента сохранён. Напишите «продолжай», чтобы завершить задачу с того места, где он остановился.");
                // Кладём в историю активного чата, чтобы корзина могла её удалить.
                h.put(note);
                store.touchActiveChat();
                addAssistantBubble(note);
            }
        } catch (Throwable ignored) {}
    }

    // ------------------------------------------------- пузырь пользователя
    private void addUserBubble(final JSONObject m) {
        final LinearLayout rowWrap = new LinearLayout(getActivity());
        rowWrap.setOrientation(LinearLayout.VERTICAL);
        rowWrap.setGravity(Gravity.END);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        wlp.topMargin = dp(6); wlp.bottomMargin = dp(6);
        rowWrap.setLayoutParams(wlp);

        // Пузырь с корзиной удаления в углу (FrameLayout: bubble + del поверх).
        // Квадратная форма (маленькое скругление), фон тёмно-синий.
        LinearLayout bubble = new LinearLayout(getActivity());
        bubble.setOrientation(LinearLayout.VERTICAL);
        // Справа доп. отступ под корзину, чтобы значок не перекрывал текст.
        bubble.setPadding(dp(14), dp(10), dp(34), dp(10));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(dp(4));
        bg.setColor(Color.parseColor(USER_BG));
        bubble.setBackground(bg);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.gravity = Gravity.END;
        blp.leftMargin = dp(40);
        bubble.setLayoutParams(blp);

        // Вложения (только у пользователя).
        JSONArray atts = m.optJSONArray("attachments");
        if (atts != null && atts.length() > 0) bubble.addView(buildAttachmentsView(atts, true));

        String content = m.optString("content", "");
        if (!content.isEmpty()) {
            TextView tv = new TextView(getActivity());
            tv.setText(content);
            tv.setTextColor(Color.WHITE);
            tv.setTextSize(15f);
            bubble.addView(tv);
        }

        // Оборачиваем в FrameLayout, чтобы разместить корзину справа-сверху поверх.
        android.widget.FrameLayout frame = new android.widget.FrameLayout(getActivity());
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.gravity = Gravity.END;
        frame.setLayoutParams(flp);
        frame.addView(bubble);
        frame.addView(makeDeleteBadge(m));

        rowWrap.addView(frame);

        // Панель действий (copy, re-send) — под пузырём, тап по любому месту переключает.
        final LinearLayout actions = new LinearLayout(getActivity());
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END);
        actions.setVisibility(View.GONE);
        actions.addView(makeIconBtn("\u29C9", "Копировать", "#8B949E", new Runnable() {
            public void run() { copyToClipboard(m.optString("content", "")); toast("Скопировано"); }
        }));
        actions.addView(makeIconBtn("\u270E", "Пере-отправить", "#F0A742", new Runnable() {
            public void run() {
                input.setText(m.optString("content", ""));
                input.setSelection(input.getText().length());
                input.requestFocus();
                actions.setVisibility(View.GONE);
                toast("Текст скопирован в поле ввода");
            }
        }));
        rowWrap.addView(actions);

        // Клик в любом месте пузыря (и по тексту) переключает панель действий.
        View.OnClickListener toggle = new View.OnClickListener() {
            public void onClick(View v) {
                actions.setVisibility(actions.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            }
        };
        setClickRecursive(bubble, toggle);

        chatList.addView(rowWrap);
    }

    // ------------------------------------------------- пузырь ассистента
    private void addAssistantBubble(final JSONObject m) {
        final LinearLayout rowWrap = new LinearLayout(getActivity());
        rowWrap.setOrientation(LinearLayout.VERTICAL);
        rowWrap.setGravity(Gravity.START);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        wlp.topMargin = dp(6); wlp.bottomMargin = dp(6);
        rowWrap.setLayoutParams(wlp);

        // Квадратная форма (маленькое скругление), фон тёмно-серый.
        LinearLayout bubble = new LinearLayout(getActivity());
        bubble.setOrientation(LinearLayout.VERTICAL);
        bubble.setPadding(dp(14), dp(10), dp(34), dp(10));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(dp(4));
        bg.setColor(Color.parseColor(ASSISTANT_BG));
        bubble.setBackground(bg);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.gravity = Gravity.START;
        blp.rightMargin = dp(40);
        bubble.setLayoutParams(blp);

        TextView tv = new TextView(getActivity());
        tv.setText(Agent.stripToolBlocks(m.optString("content", "")));
        tv.setTextColor(Color.parseColor("#E6EDF3"));
        tv.setTextSize(15f);
        bubble.addView(tv);

        android.widget.FrameLayout frame = new android.widget.FrameLayout(getActivity());
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.gravity = Gravity.START;
        frame.setLayoutParams(flp);
        frame.addView(bubble);
        frame.addView(makeDeleteBadge(m));
        rowWrap.addView(frame);

        // Бейдж модели (виден только когда открыты действия).
        final String realModel = m.optString("realModel", "").trim();
        final LinearLayout modelBadge = makeModelBadge(realModel);
        modelBadge.setVisibility(View.GONE);
        rowWrap.addView(modelBadge);

        // Панель действий: copy + (для последнего assistant) regenerate.
        final LinearLayout actions = new LinearLayout(getActivity());
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.START);
        actions.setVisibility(View.GONE);
        actions.addView(makeIconBtn("\u29C9", "Копировать", "#8B949E", new Runnable() {
            public void run() { copyToClipboard(Agent.stripToolBlocks(m.optString("content", ""))); toast("Скопировано"); }
        }));
        if (isLastAssistant(m)) {
            actions.addView(makeIconBtn("\u21BB", "Регенерировать", "#3FB950", new Runnable() {
                public void run() { regenerate(m); }
            }));
        }
        rowWrap.addView(actions);

        View.OnClickListener toggle = new View.OnClickListener() {
            public void onClick(View v) {
                boolean show = actions.getVisibility() != View.VISIBLE;
                actions.setVisibility(show ? View.VISIBLE : View.GONE);
                modelBadge.setVisibility(show && !realModel.isEmpty() ? View.VISIBLE : View.GONE);
            }
        };
        setClickRecursive(bubble, toggle);

        chatList.addView(rowWrap);
    }

    private void addErrorBubble(final JSONObject m) {
        final LinearLayout rowWrap = new LinearLayout(getActivity());
        rowWrap.setOrientation(LinearLayout.VERTICAL);
        rowWrap.setGravity(Gravity.START);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        wlp.topMargin = dp(6); wlp.bottomMargin = dp(6);
        rowWrap.setLayoutParams(wlp);

        LinearLayout bubble = new LinearLayout(getActivity());
        bubble.setOrientation(LinearLayout.VERTICAL);
        bubble.setPadding(dp(14), dp(10), dp(34), dp(10));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(dp(4));
        bg.setColor(Color.parseColor("#3A2023"));
        bubble.setBackground(bg);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.gravity = Gravity.START;
        blp.rightMargin = dp(40);
        bubble.setLayoutParams(blp);
        TextView tv = new TextView(getActivity());
        tv.setText(m.optString("content", "Ошибка"));
        tv.setTextColor(Color.parseColor("#F85149"));
        tv.setTextSize(14f);
        bubble.addView(tv);

        android.widget.FrameLayout frame = new android.widget.FrameLayout(getActivity());
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.gravity = Gravity.START;
        frame.setLayoutParams(flp);
        frame.addView(bubble);
        frame.addView(makeDeleteBadge(m));
        rowWrap.addView(frame);

        // Кнопки как у сообщений агента: копирование + регенерация (повтор с этого места).
        final LinearLayout actions = new LinearLayout(getActivity());
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.START);
        actions.setVisibility(View.GONE);
        actions.addView(makeIconBtn("\u29C9", "Копировать", "#8B949E", new Runnable() {
            public void run() { copyToClipboard(m.optString("content", "")); toast("Скопировано"); }
        }));
        actions.addView(makeIconBtn("\u21BB", "Регенерировать", "#3FB950", new Runnable() {
            public void run() { regenerate(m); }
        }));
        rowWrap.addView(actions);

        View.OnClickListener toggle = new View.OnClickListener() {
            public void onClick(View v) {
                actions.setVisibility(actions.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            }
        };
        setClickRecursive(bubble, toggle);

        chatList.addView(rowWrap);
    }

    /** Последнее ли это видимое сообщение ассистента (для показа «регенерации»). */
    private boolean isLastAssistant(JSONObject m) {
        JSONArray h = store.history();
        int idx = -1;
        for (int i = 0; i < h.length(); i++) { if (h.optJSONObject(i) == m) { idx = i; break; } }
        if (idx < 0) return false;
        for (int i = idx + 1; i < h.length(); i++) {
            JSONObject x = h.optJSONObject(i);
            if (x == null) continue;
            String r = x.optString("role");
            if ("user".equals(r) || ("assistant".equals(r))) return false;
        }
        return true;
    }

    // -------------------------------------------------------- UI-хелперы пузырей
    /**
     * Навешивает клик-обработчик на все дочерние view пузыря, чтобы панель
     * действий открывалась при нажатии в ЛЮБОМ месте сообщения (включая сам текст).
     * View, у которых уже есть свой обработчик (напр. картинка-вложение с превью
     * или разворачиваемый файл), не трогаем.
     *
     * Поведение текста сообщения (как в web-версии):
     *  - ОДИНОЧНЫЙ тап по тексту → показать/скрыть панель действий
     *    (копирование / регенерация / пере-отправка);
     *  - ДОЛГОЕ нажатие → включить системное выделение текста и сразу выделить
     *    слово под пальцем, чтобы можно было выбрать и скопировать вручную.
     * Двойной тап при этом ничего не выделяет (текст изначально невыделяемый).
     */
    private void setClickRecursive(View v, View.OnClickListener l) {
        if (v instanceof ImageView) return;        // картинка-вложение — свой обработчик
        if (v.isClickable() && v.hasOnClickListeners()) return; // уже есть обработчик (разворачивание файла)
        if (v instanceof TextView && !(v instanceof ViewGroup)) {
            bindMessageText((TextView) v, l);
            return;
        }
        if (!(v instanceof ViewGroup)) {
            v.setOnClickListener(l);
            return;
        }
        ViewGroup g = (ViewGroup) v;
        g.setOnClickListener(l);
        for (int i = 0; i < g.getChildCount(); i++) setClickRecursive(g.getChildAt(i), l);
    }

    /**
     * Настраивает текст сообщения: одиночный тап → переключение панели действий,
     * долгое нажатие → выделение текста для ручного копирования. Текст остаётся
     * невыделяемым до долгого нажатия, поэтому двойной тап не выделяет слово.
     */
    private void bindMessageText(final TextView tv, final View.OnClickListener toggle) {
        // Храним текст как Spannable, чтобы можно было программно выделять.
        tv.setText(tv.getText(), TextView.BufferType.SPANNABLE);
        tv.setTextIsSelectable(false);
        tv.setClickable(true);
        tv.setLongClickable(true);
        tv.setOnClickListener(toggle);
        tv.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                enableTextSelection(tv);
                return true;
            }
        });
    }

    /**
     * Включает выделение для TextView и запускает его сразу же (выделяет весь
     * текст и показывает системную панель «Копировать»). После снятия выделения
     * (при следующей перерисовке) текст снова станет невыделяемым.
     */
    private void enableTextSelection(final TextView tv) {
        try {
            // Снимаем наши обработчики, чтобы дальше работал штатный механизм
            // выделения selectable-TextView (без риска рекурсии performLongClick).
            tv.setOnClickListener(null);
            tv.setOnLongClickListener(null);
            tv.setTextIsSelectable(true);
            tv.setFocusable(true);
            tv.setFocusableInTouchMode(true);
            tv.requestFocusFromTouch();
            tv.requestFocus();
            tv.post(new Runnable() {
                public void run() {
                    try {
                        CharSequence s = tv.getText();
                        if (s instanceof android.text.Spannable && s.length() > 0) {
                            // Выделяем весь текст и показываем системную панель
                            // «Копировать / Выделить всё» через штатный long-click.
                            android.text.Selection.selectAll((android.text.Spannable) s);
                        }
                        tv.performLongClick();
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable ignored) {}
    }

    /** Иконка-корзина в правом верхнем углу сообщения. Удаляет без подтверждения. */
    private TextView makeDeleteBadge(final JSONObject m) {
        final TextView del = new TextView(getActivity());
        del.setText("\uD83D\uDDD1");
        del.setTextSize(13f);
        del.setTextColor(Color.parseColor("#8B949E"));
        del.setPadding(dp(8), dp(4), dp(8), dp(4));
        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        del.setLayoutParams(lp);
        del.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { deleteMessage(m); }
        });
        return del;
    }

    /** Круглая иконка-кнопка действия (copy/regen/resend). */
    private TextView makeIconBtn(String glyph, String cd, String color, final Runnable action) {
        TextView b = new TextView(getActivity());
        b.setText(glyph);
        b.setTextSize(17f);
        b.setTextColor(Color.parseColor(color));
        b.setGravity(Gravity.CENTER);
        b.setContentDescription(cd);
        int s = dp(38);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(s, s);
        lp.leftMargin = dp(4); lp.topMargin = dp(4); lp.rightMargin = dp(4);
        b.setLayoutParams(lp);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        bg.setColor(Color.parseColor("#1C222B"));
        bg.setStroke(dp(1), Color.parseColor("#2E3742"));
        b.setBackground(bg);
        b.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { action.run(); } });
        return b;
    }

    /** Бейдж «• модель: <id>» (реальный id модели по base URL). */
    private LinearLayout makeModelBadge(final String model) {
        LinearLayout badge = new LinearLayout(getActivity());
        badge.setOrientation(LinearLayout.HORIZONTAL);
        badge.setGravity(Gravity.CENTER_VERTICAL);
        badge.setPadding(dp(10), dp(5), dp(12), dp(5));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(4);
        badge.setLayoutParams(blp);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(Color.parseColor("#1C222B"));
        bg.setStroke(dp(1), Color.parseColor("#2E3742"));
        badge.setBackground(bg);
        badge.addView(UiKit.dot(getActivity(), Color.parseColor("#3FB950"), 9));
        TextView lbl = new TextView(getActivity());
        lbl.setText("  модель: ");
        lbl.setTextColor(Color.parseColor("#8B949E"));
        lbl.setTextSize(12f);
        badge.addView(lbl);
        TextView idT = new TextView(getActivity());
        idT.setText(model.isEmpty() ? "—" : model);
        idT.setTextColor(Color.WHITE);
        idT.setTextSize(12f);
        idT.getPaint().setFakeBoldText(true);
        idT.setTypeface(android.graphics.Typeface.MONOSPACE);
        idT.setTextIsSelectable(true);
        badge.addView(idT);
        // Долгое нажатие — копирование id.
        badge.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                if (!model.isEmpty()) { copyToClipboard(model); toast("ID модели скопирован: " + model); }
                return true;
            }
        });
        return badge;
    }

    private void deleteMessage(JSONObject m) {
        // Плашка «предыдущий запуск прерван» пересоздаётся автоматически — при её
        // удалении запоминаем это в чате, чтобы она больше не появлялась.
        if (m != null && m.optBoolean("interruptedNotice", false)) {
            JSONObject chat = store.activeChat();
            if (chat != null) { try { chat.put("interruptedNoticeDismissed", true); } catch (JSONException ignored) {} }
        }
        JSONArray h = store.history();
        for (int i = 0; i < h.length(); i++) {
            if (h.optJSONObject(i) == m) { h.remove(i); break; }
        }
        store.touchActiveChat();
        renderMessages();
    }

    private void copyToClipboard(String text) {
        ClipboardManager cm = (ClipboardManager) getActivity().getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("message", text == null ? "" : text));
    }

    // ------------------------------------------------------ вложения в пузыре
    /**
     * Строит блок вложений сообщения: картинки — как <img> (миниатюра),
     * текстовые файлы — имя с расширением + разворачиваемый контейнер с содержимым.
     */
    private View buildAttachmentsView(JSONArray atts, boolean onDark) {
        LinearLayout wrap = new LinearLayout(getActivity());
        wrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wlp.bottomMargin = dp(6);
        wrap.setLayoutParams(wlp);
        for (int i = 0; i < atts.length(); i++) {
            final JSONObject a = atts.optJSONObject(i);
            if (a == null) continue;
            String kind = a.optString("kind", "");
            if ("image".equals(kind)) wrap.addView(buildImageAttachment(a));
            else wrap.addView(buildFileAttachment(a, onDark));
        }
        return wrap;
    }

    private View buildImageAttachment(final JSONObject a) {
        ImageView iv = new ImageView(getActivity());
        Bitmap bmp = decodeDataUrl(a.optString("dataUrl", ""));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(160), LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4); lp.bottomMargin = dp(4);
        iv.setLayoutParams(lp);
        iv.setAdjustViewBounds(true);
        iv.setScaleType(ImageView.ScaleType.FIT_START);
        if (bmp != null) iv.setImageBitmap(bmp);
        iv.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showImagePreview(a); }
        });
        return iv;
    }

    private View buildFileAttachment(final JSONObject a, boolean onDark) {
        final LinearLayout col = new LinearLayout(getActivity());
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(4); clp.bottomMargin = dp(4);
        col.setLayoutParams(clp);

        final String name = a.optString("name", a.optString("path", "file"));
        TextView chip = new TextView(getActivity());
        chip.setText("\uD83D\uDCC4  " + name);
        chip.setTextColor(onDark ? Color.WHITE : Color.parseColor("#E6EDF3"));
        chip.setTextSize(13f);
        chip.setPadding(dp(10), dp(7), dp(10), dp(7));
        android.graphics.drawable.GradientDrawable cb = new android.graphics.drawable.GradientDrawable();
        cb.setCornerRadius(dp(10));
        cb.setColor(Color.parseColor(onDark ? "#1565C0" : "#232B36"));
        chip.setBackground(cb);
        col.addView(chip);

        final String text = a.optString("text", "");
        final TextView box = new TextView(getActivity());
        box.setText(text);
        box.setTextColor(Color.parseColor("#C9D1D9"));
        box.setTextSize(12f);
        box.setTypeface(android.graphics.Typeface.MONOSPACE);
        box.setTextIsSelectable(true);
        box.setPadding(dp(10), dp(8), dp(10), dp(8));
        box.setVisibility(View.GONE);
        android.graphics.drawable.GradientDrawable bb = new android.graphics.drawable.GradientDrawable();
        bb.setCornerRadius(dp(8));
        bb.setColor(Color.parseColor("#0E1116"));
        box.setBackground(bb);
        LinearLayout.LayoutParams boxp = new LinearLayout.LayoutParams(dp(240), ViewGroup.LayoutParams.WRAP_CONTENT);
        boxp.topMargin = dp(4);
        box.setLayoutParams(boxp);
        col.addView(box);

        chip.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (text.isEmpty()) return;
                box.setVisibility(box.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            }
        });
        return col;
    }

    private void showImagePreview(JSONObject a) {
        Bitmap bmp = decodeDataUrl(a.optString("dataUrl", ""));
        if (bmp == null) { toast("Не удалось открыть изображение"); return; }
        ImageView iv = new ImageView(getActivity());
        iv.setImageBitmap(bmp);
        iv.setAdjustViewBounds(true);
        new AlertDialog.Builder(getActivity())
                .setTitle(a.optString("name", "Изображение"))
                .setView(iv)
                .setPositiveButton("Закрыть", null)
                .show();
    }

    private Bitmap decodeDataUrl(String dataUrl) {
        try {
            int comma = dataUrl.indexOf(',');
            if (comma < 0) return null;
            byte[] bytes = Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (Exception e) { return null; }
    }

    // ================================================= отправка / стоп / interject
    /** Три состояния кнопки: ▶ (idle) / ⏹ (работает, поле пустое) / ▶ др.цвета (interject). */
    /**
     * Отображается ли сейчас (в UI) тот чат, в котором работает агент. Если запуск
     * не привязан (runChatId == null) — считаем «да» (обычный случай без работы).
     */
    private boolean isRunChatVisible() {
        if (runChatId == null) return true;
        return runChatId.equals(store.activeChatId());
    }

    private void updateSendButton() {
        if (btnSend == null) return;
        boolean hasText = input != null && !input.getText().toString().trim().isEmpty();
        boolean hasAtt = !pendingAttachments.isEmpty();
        boolean hasContent = hasText || hasAtt;
        // ⏹/интерджект — только если агент работает в ОТКРЫТОМ СЕЙЧАС чате.
        // Если он занят в другом (фоновом) чате — в этом чате кнопка обычная ▶.
        boolean sendingHere = sending && isRunChatVisible();
        String mode;
        if (sendingHere && !hasContent) mode = "stop";
        else if (sendingHere && hasContent) mode = "interject";
        else mode = "send";

        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(dp(16));
        if ("stop".equals(mode)) {
            btnSend.setImageResource(R.drawable.ic_stop);
            bg.setColor(Color.parseColor("#F85149"));
            btnSend.setContentDescription("Остановить");
        } else if ("interject".equals(mode)) {
            btnSend.setImageResource(R.drawable.ic_send);
            bg.setColor(Color.parseColor("#F0A742"));
            btnSend.setContentDescription("Отправить агенту (без остановки)");
        } else {
            btnSend.setImageResource(R.drawable.ic_send);
            bg.setColor(Color.parseColor("#1E88FF"));
            btnSend.setContentDescription("Отправить");
        }
        btnSend.setBackground(bg);
    }

    private void submitFromInput() {
        String text = input.getText().toString().trim();
        boolean hasAtt = !pendingAttachments.isEmpty();
        if (text.isEmpty() && !hasAtt) {
            // Пустой ввод: стоп только если агент работает в ЭТОМ чате.
            if (sending && isRunChatVisible()) stop();
            return;
        }
        List<JSONObject> atts = new ArrayList<JSONObject>(pendingAttachments);
        pendingAttachments.clear();
        renderAttachPreview();
        input.setText("");
        // Агент занят В ДРУГОМ (фоновом) чате: параллельный второй прогон невозможен
        // (один поток/отмена на приложение), но сообщение НЕ теряем — сохраняем его
        // в этот чат и ставим чат в очередь на автозапуск после освобождения агента.
        if (sending && !isRunChatVisible()) {
            queueRunForActiveChat(text.isEmpty() ? "(файл(ы) прикреплены)" : text, atts);
            updateSendButton();
            return;
        }
        if (sending) interject(text.isEmpty() ? "(файл(ы) прикреплены)" : text, atts);
        else send(text, atts);
        updateSendButton();
    }

    private void stop() {
        cancelled = true;
        // 1) Рвём активный сетевой запрос к провайдеру — это МГНОВЕННО разблокирует
        //    фоновый поток, застрявший в чтении ответа (раньше он висел до конца
        //    ответа или таймаута — до 120с, из-за чего «стоп» был не мгновенным).
        ApiClient.Canceller cc = currentCanceller;
        if (cc != null) { try { cc.cancel(); } catch (Exception ignored) {} }
        // 2) Прерываем сам поток — выводит его из Thread.sleep (throttle/backoff)
        //    и любых прерываемых блокировок немедленно.
        Thread wt = workerThread;
        if (wt != null) { try { wt.interrupt(); } catch (Exception ignored) {} }
        // 3) Сразу отражаем остановку в UI, не дожидаясь фонового потока.
        toast("Остановлено");
        if (sending) {
            sending = false;
            // Финализируем ЖИВУЮ карточку (она существует даже если рабочий чат сейчас
            // не открыт — тогда currentCard == null, а liveCard != null). Карточка
            // остаётся в своём чате навсегда, до ручного удаления пользователем.
            StatusCard sc = liveCard != null ? liveCard : currentCard;
            String scChat = liveCardChatId != null ? liveCardChatId
                    : (runChatId != null ? runChatId : store.activeChatId());
            if (sc != null) {
                try { sc.finish("Остановлено", "err", false); } catch (Exception ignored) {}
                persistFinishedCard(scChat, sc);
            }
            runChatId = null;
            currentCard = null;
            liveCard = null; liveCardChatId = null;
            updateSendButton();
        }
    }

    /** Отправка сообщения пользователя во время работы агента (без остановки). */
    private void interject(String text, List<JSONObject> atts) {
        JSONObject msg = new JSONObject();
        try {
            msg.put("role", "user").put("content", text).put("interject", true);
            if (!atts.isEmpty()) msg.put("attachments", toJsonArray(atts));
        } catch (JSONException ignored) {}
        if (runChatId != null) store.pushMsgTo(runChatId, msg); else store.pushMsg(msg);
        addUserBubble(msg);
        // Ставим пузырь перед статус-карточкой (как в web), если она есть.
        if (currentCard != null) {
            int ci = chatList.indexOfChild(currentCard);
            int last = chatList.getChildCount() - 1;
            if (ci >= 0 && last >= 0 && last != ci) {
                View just = chatList.getChildAt(last);
                chatList.removeView(just);
                chatList.addView(just, ci);
            }
        }
        synchronized (inbox) { inbox.add(text); }
        scrollDown();
    }

    /**
     * Агент занят в другом чате: добавляем сообщение пользователя в АКТИВНЫЙ чат
     * (сразу видно в ленте) и ставим этот чат в очередь на запуск, который стартует
     * автоматически, когда текущий прогон завершится (drainPendingRun).
     */
    private void queueRunForActiveChat(String text, List<JSONObject> atts) {
        String chatId = store.activeChatId();
        JSONObject msg = new JSONObject();
        try {
            msg.put("role", "user").put("content", text);
            if (atts != null && !atts.isEmpty()) msg.put("attachments", toJsonArray(atts));
        } catch (JSONException ignored) {}
        store.pushMsgTo(chatId, msg);
        addUserBubble(msg);
        store.autoTitleFromFirstMsgOf(chatId);
        if (!pendingRunChats.contains(chatId)) pendingRunChats.add(chatId);
        scrollDown();
        toast("Агент занят — сообщение отправится, как только он освободится");
    }

    /**
     * Запускает следующий отложенный чат из очереди, если агент свободен. Вызывается
     * из всех точек завершения прогона (onFinal/onError/stop/fatal).
     */
    private void drainPendingRun() {
        if (sending) return;
        while (!pendingRunChats.isEmpty()) {
            String chatId = pendingRunChats.remove(0);
            if (chatId == null || store.historyOf(chatId) == null) continue; // чат удалён
            final JSONObject provider = store.activeProvider();
            if (provider == null || provider.optString("model", "").trim().isEmpty()) {
                toast("Отложенный запуск пропущен: провайдер/модель не настроены");
                pendingRunChats.clear();
                return;
            }
            runChatId = chatId;
            startRun(provider);
            return;
        }
    }

    private void send(String text, List<JSONObject> atts) {
        final JSONObject provider = store.activeProvider();
        if (provider == null) {
            new AlertDialog.Builder(getActivity())
                    .setMessage("Провайдер не выбран. Откройте настройки и создайте/выберите провайдера.")
                    .setPositiveButton("Настройки", new android.content.DialogInterface.OnClickListener() {
                        public void onClick(android.content.DialogInterface d, int w) {
                            startActivity(new Intent(getActivity(), SettingsActivity.class));
                        }
                    }).setNegativeButton("Отмена", null).show();
            return;
        }
        if (provider.optString("model", "").trim().isEmpty()) {
            toast("У провайдера не выбрана модель — откройте список моделей");
            return;
        }

        // Привязываем запуск к текущему активному чату (см. поле runChatId).
        runChatId = store.activeChatId();
        JSONObject msg = new JSONObject();
        try {
            msg.put("role", "user").put("content", text);
            if (!atts.isEmpty()) msg.put("attachments", toJsonArray(atts));
        } catch (JSONException ignored) {}
        store.pushMsg(msg);
        addUserBubble(msg);
        // Автозаголовок чата из первого сообщения (порт autoTitleFromFirstMsg).
        store.autoTitleFromFirstMsg();

        startRun(provider);
    }

    private void regenerate(JSONObject assistantMsg) {
        if (sending) { toast("Дождитесь завершения ответа"); return; }
        final JSONObject provider = store.activeProvider();
        if (provider == null) { toast("Провайдер не настроен"); return; }
        // Привязываем регенерацию к текущему чату.
        runChatId = store.activeChatId();
        JSONArray h = store.history();
        int idx = -1;
        for (int i = 0; i < h.length(); i++) { if (h.optJSONObject(i) == assistantMsg) { idx = i; break; } }
        if (idx < 0) { runChatId = null; return; }
        // Обрезаем историю начиная с этого ответа.
        while (h.length() > idx) h.remove(h.length() - 1);
        store.touchActiveChat();
        renderMessages();
        startRun(provider);
    }

    /** Запуск цикла агента над текущей историей активного чата. */
    private void startRun(final JSONObject provider) {
        sending = true;
        cancelled = false;
        // Новый прогон — снимаем метку «плашка о прерывании скрыта»: если этот
        // запуск снова прервётся, плашку нужно будет показать заново.
        JSONObject runChat = runChatId != null ? store.findChat(runChatId) : store.activeChat();
        if (runChat != null) runChat.remove("interruptedNoticeDismissed");
        updateSendButton();
        if (agent == null) agent = new Agent(getActivity());

        final StatusCard card = new StatusCard(getActivity());
        // Начальная модель под окном статуса — выбранная в провайдере (реальное
        // имя по base URL подставится в onFinal, когда придёт ответ).
        try { card.setModel(provider != null ? provider.optString("model", "") : ""); } catch (Exception ignored) {}
        // Запоминаем живую карточку и её чат, чтобы восстановить при возврате.
        liveCard = card;
        liveCardChatId = runChatId;
        // Карточку показываем В ЛЕНТЕ, только если чат запуска сейчас открыт. Для
        // отложенного запуска (агент стартует в неактивном чате) карточка не должна
        // попасть в чужую ленту — её подхватит renderMessages при возврате в свой чат.
        boolean runVisible = runChatId == null || runChatId.equals(store.activeChatId());
        if (runVisible) {
            currentCard = card;
            chatList.addView(card);
            scrollDown();
        } else {
            currentCard = null;
        }

        // История ИМЕННО чата запуска (по id), а не активного — чтобы переключение
        // чата во время работы не подменяло историю агента на лету.
        final JSONArray history = runChatId != null && store.historyOf(runChatId) != null
                ? store.historyOf(runChatId) : store.history();
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                agent.run(provider, history, new Agent.Listener() {
                    public void onCanceller(ApiClient.Canceller canceller) {
                        currentCanceller = canceller;
                        // Если пользователь уже нажал «стоп» до того, как соединение
                        // зарегистрировалось — рвём его сразу.
                        if (canceller != null && cancelled) { try { canceller.cancel(); } catch (Exception ignored) {} }
                    }
                    public void onReasoning(final String t) {
                        // Не дёргаем весь чат вниз при каждом обновлении рассуждений —
                        // окно статуса фиксированного размера, рассуждения скроллятся
                        // внутри своего контейнера (в фокусе — начало).
                        runOnUi(new Runnable() { public void run() { card.setReasoning(t); } });
                    }
                    public void onOp(final String label, final String detail, final String kind) {
                        runOnUi(new Runnable() { public void run() { card.setOp(label, detail, kind); } });
                    }
                    public void onToolStart(String tool, JSONObject args) { }
                    public void onToolResult(final String logLine, final boolean ok,
                                             final JSONObject result, final String tool, final JSONObject args) {
                        runOnUi(new Runnable() {
                            public void run() {
                                // Персист в чат запуска (по id) — не в активный.
                                if (runChatId != null) store.touchChat(runChatId); else store.touchActiveChat();
                                // Лог/превью копим на карточке ВСЕГДА (даже если чат сейчас
                                // не открыт — карточка живёт вне ленты и при возврате
                                // покажет накопленное). Автоскролл — только для видимого чата.
                                card.appendLog(logLine, ok);
                                if (ok && result != null && !"fs_list".equals(tool) && !"fs_search".equals(tool)
                                        && !"fs_read_image".equals(tool) && !"archive_list".equals(tool)
                                        && !"archive_read_entry".equals(tool) && !"web_search".equals(tool)
                                        && !"http_fetch".equals(tool)) {
                                    String p = args != null
                                            ? args.optString("path", args.optString("archive", args.optString("from", "")))
                                            : "";
                                    // ВАЖНО (защита от OOM): превью-карточка держит ссылку на result
                                    // в своём click-listener'е (лениво строит тело). Для fs_read result
                                    // содержит ВЕСЬ текст файла. При долгом перечитывании больших файлов
                                    // накапливались десятки полных копий содержимого → приложение падало
                                    // по нехватке памяти. Обрезаем тяжёлое поле "content" до 4000 символов
                                    // (как в web-версии buildFilePreview) ПЕРЕД тем, как отдать в UI —
                                    // логика самого агента (Agent.java) не затрагивается: модели уходит
                                    // отдельная, своя копия результата.
                                    card.appendFilePreview(p, slimResultForPreview(result), tool);
                                }
                                if (isRunChatVisible()) scrollDown();
                            }
                        });
                    }
                    public void onFinal(final String t, final String realModel) {
                        runOnUi(new Runnable() {
                            public void run() {
                                sending = false;
                                // Показать реальное имя модели (по base URL) под окном статуса.
                                if (realModel != null && !realModel.trim().isEmpty()) {
                                    try { card.setModel(realModel); } catch (Exception ignored) {}
                                }
                                currentCanceller = null;
                                boolean visible = isRunChatVisible();
                                String finishedChat = runChatId;
                                if (finishedChat != null) store.touchChat(finishedChat); else store.touchActiveChat();
                                if (finishedChat != null) store.autoTitleFromFirstMsgOf(finishedChat);
                                else store.autoTitleFromFirstMsg();
                                // Финализируем карточку в любом случае (даже если чат
                                // не открыт) — тогда при возврате она покажет «Готово»,
                                // а не вечное «Thinking».
                                try { card.finish("Готово", "ok", true); } catch (Exception ignored) {}
                                // Карточка остаётся в чате навсегда (до ручного удаления).
                                persistFinishedCard(finishedChat != null ? finishedChat : store.activeChatId(), card);
                                currentCard = null;
                                if (visible) {
                                    // Финальный видимый ответ ассистента как пузырь
                                    // (agent уже снял с него silent).
                                    JSONObject m = lastAssistantMsg();
                                    if (m != null) addAssistantBubble(m);
                                    else {
                                        JSONObject fm = new JSONObject();
                                        try { fm.put("role","assistant").put("content", t==null?"(готово)":t).put("realModel", realModel); } catch (JSONException ignored) {}
                                        addAssistantBubble(fm);
                                    }
                                    scrollDown();
                                }
                                liveCard = null; liveCardChatId = null;
                                runChatId = null;
                                updateSendButton();
                                // Звуковой сигнал «агент прислал ответ» (если включён в настройках).
                                playNotifySound();
                                // Запускаем следующий отложенный чат из очереди (если есть).
                                drainPendingRun();
                            }
                        });
                    }
                    public void onError(final String msg) {
                        runOnUi(new Runnable() {
                            public void run() {
                                sending = false;
                                currentCanceller = null;
                                boolean visible = isRunChatVisible();
                                String finishedChat = runChatId;
                                // Остановка пользователем — не показываем красный «пузырь ошибки»:
                                // stop() уже отразил остановку в UI. Просто аккуратно закрываем карточку.
                                boolean userStop = cancelled || (msg != null && msg.contains("Остановлено пользователем"));
                                if (userStop) {
                                    try { card.finish("Остановлено", "err", false); } catch (Exception ignored) {}
                                    persistFinishedCard(finishedChat != null ? finishedChat : store.activeChatId(), card);
                                    currentCard = null;
                                    liveCard = null; liveCardChatId = null;
                                    runChatId = null;
                                    updateSendButton();
                                    drainPendingRun();
                                    return;
                                }
                                JSONObject em = new JSONObject();
                                try { em.put("role","error").put("content", msg==null?"Ошибка":msg); } catch (JSONException ignored) {}
                                // Пишем ошибку в чат запуска (по id), а не в активный.
                                if (finishedChat != null) store.pushMsgTo(finishedChat, em); else store.pushMsg(em);
                                try { card.finish("Ошибка", "err", false); } catch (Exception ignored) {}
                                persistFinishedCard(finishedChat != null ? finishedChat : store.activeChatId(), card);
                                if (visible) {
                                    addErrorBubble(em);
                                    scrollDown();
                                }
                                currentCard = null;
                                liveCard = null; liveCardChatId = null;
                                runChatId = null;
                                updateSendButton();
                                drainPendingRun();
                            }
                        });
                    }
                    public boolean isCancelled() { return cancelled; }
                    public java.util.List<String> drainInbox() {
                        synchronized (inbox) {
                            if (inbox.isEmpty()) return java.util.Collections.emptyList();
                            List<String> out = new ArrayList<String>(inbox);
                            inbox.clear();
                            return out;
                        }
                    }
                    public boolean hasInbox() { synchronized (inbox) { return !inbox.isEmpty(); } }
                });
                } catch (final Throwable fatal) {
                    // ГЛАВНАЯ ЗАЩИТА ОТ ВЫЛЕТА. Раньше любая OutOfMemoryError (при
                    // чтении/поиске по большому файлу) или иной Error пробивали поток
                    // агента насквозь → срабатывал глобальный handler → приложение
                    // ПАДАЛО, а прерванный статус агента терялся. Теперь ловим ВСЁ
                    // (Throwable, включая OOM): освобождаем память, сохраняем историю
                    // и корректно завершаем run сообщением об ошибке — без краша.
                    try {
                        // История уже частично в store; принудительно фиксируем на диск,
                        // чтобы проделанная агентом работа не потерялась.
                        final String finishedChat = runChatId;
                        try { if (finishedChat != null) store.touchChat(finishedChat); else store.touchActiveChat(); } catch (Throwable ignored) {}
                        final boolean oom = (fatal instanceof OutOfMemoryError);
                        // Подсказываем сборщику: крупные ссылки уже вышли из области видимости.
                        System.gc();
                        runOnUi(new Runnable() { public void run() {
                            sending = false;
                            currentCanceller = null;
                            boolean visible = isRunChatVisible();
                            StatusCard c = currentCard != null ? currentCard : liveCard;
                            String cChat = liveCardChatId != null ? liveCardChatId
                                    : (finishedChat != null ? finishedChat : store.activeChatId());
                            currentCard = null;
                            liveCard = null; liveCardChatId = null;
                            if (c != null) {
                                try { c.finish("Ошибка", "err", false); } catch (Throwable ignored) {}
                                persistFinishedCard(cChat, c);
                            }
                            String msg = oom
                                ? "Не хватило памяти при обработке большого файла — операция прервана, "
                                  + "но прогресс сохранён. Попробуйте: читать файл частями (fs_read с offset/limit) "
                                  + "или искать по нему через fs_search вместо чтения целиком."
                                : ("Внутренняя ошибка агента: " + (fatal.getMessage() == null ? fatal.toString() : fatal.getMessage()));
                            JSONObject em = new JSONObject();
                            try { em.put("role", "error").put("content", msg); } catch (JSONException ignored) {}
                            if (finishedChat != null) store.pushMsgTo(finishedChat, em); else store.pushMsg(em);
                            if (visible) { addErrorBubble(em); scrollDown(); }
                            runChatId = null;
                            updateSendButton();
                            drainPendingRun();
                        }});
                    } catch (Throwable ignored) {
                        // Даже обработчик ошибки не должен ронять приложение.
                    }
                }
            }
        });
        workerThread = t;
        t.start();
    }

    /**
     * Готовит ЛЁГКУЮ копию результата инструмента для превью-карточки статуса.
     *
     * Проблема: StatusCard.appendFilePreview() держит ссылку на переданный JSONObject
     * в своём click-listener'е (тело превью строится лениво при развороте). Для fs_read
     * этот объект содержит ВЕСЬ текст файла. Когда агент долго перечитывает большой файл,
     * в ленте накапливались десятки карточек, и каждая удерживала полную копию содержимого
     * — память быстро исчерпывалась и приложение падало (OutOfMemory).
     *
     * Решение (не трогает логику агента — модели уходит отдельная копия в Agent.run()):
     * обрезаем тяжёлое текстовое поле "content" до PREVIEW_CONTENT_CAP символов, ровно как
     * это делает web-версия (buildFilePreview обрезает превью до 4000 символов). Остальные
     * поля (path, size, lines, diff и т.п.) копируются как есть — они мелкие.
     * При любой ошибке безопасно возвращаем исходный объект.
     */
    private static final int PREVIEW_CONTENT_CAP = 4000;
    private JSONObject slimResultForPreview(JSONObject result) {
        try {
            if (result == null) return null;
            String content = result.optString("content", null);
            if (content == null || content.length() <= PREVIEW_CONTENT_CAP) return result;
            // Копируем верхнеуровневые поля, заменяя только "content" на обрезанный.
            JSONObject slim = new JSONObject();
            java.util.Iterator<String> it = result.keys();
            while (it.hasNext()) {
                String k = it.next();
                if ("content".equals(k)) {
                    slim.put(k, content.substring(0, PREVIEW_CONTENT_CAP)
                            + "\n...[обрезано " + (content.length() - PREVIEW_CONTENT_CAP) + " симв. — полный файл см. в редакторе]");
                } else {
                    slim.put(k, result.opt(k));
                }
            }
            return slim;
        } catch (JSONException e) {
            return result; // безопасный откат — превью просто покажет полный текст
        }
    }

    /**
     * Звуковой сигнал при получении ответа агента. Проигрывается, если в настройках
     * включён «notifySound» (по умолчанию — включён), с учётом «notifyVolume».
     * Звук берётся из файла res/raw/notify.mp3 — его можно заменить своим mp3
     * (просто перезаписать файл с тем же именем). Один и тот же звук использует
     * и кнопка «Проверить» в настройках, и реальный ответ агента в чате.
     */
    private void playNotifySound() {
        try {
            if (store == null) return;
            org.json.JSONObject s = store.settings();
            if (!s.optBoolean("notifySound", true)) return;   // выключено пользователем
            double volFraction = s.optDouble("notifyVolume", 0.9);
            NotifySound.play(getActivity(), volFraction);
        } catch (Exception ignored) {
            // Звук — не критичная функция: молча игнорируем сбои воспроизведения.
        }
    }

    /** Последнее (по истории) assistant-сообщение — только что добавленное агентом. */
    private JSONObject lastAssistantMsg() {
        // Берём из истории чата запуска (по id), т.к. вызывается во время работы.
        JSONArray h = runChatId != null && store.historyOf(runChatId) != null
                ? store.historyOf(runChatId) : store.history();
        for (int i = h.length() - 1; i >= 0; i--) {
            JSONObject m = h.optJSONObject(i);
            if (m != null && "assistant".equals(m.optString("role"))) return m;
        }
        return null;
    }

    private JSONArray toJsonArray(List<JSONObject> list) {
        JSONArray a = new JSONArray();
        for (JSONObject o : list) a.put(o);
        return a;
    }

    // ================================================= прикрепление файлов
    private static final int REQ_PICK = 4021;

    private void pickAttachment() {
        // Источник: устройство или проводник проекта.
        final String[] items = pendingAttachments.isEmpty()
                ? new String[]{ "С устройства", "Из проводника" }
                : new String[]{ "С устройства", "Из проводника", "Очистить все (" + pendingAttachments.size() + ")" };
        new AlertDialog.Builder(getActivity())
                .setTitle("Прикрепить файлы")
                .setItems(items, new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int which) {
                        if (which == 0) pickFromDevice();
                        else if (which == 1) pickFromFs();
                        else { pendingAttachments.clear(); renderAttachPreview(); updateSendButton(); }
                    }
                }).show();
    }

    private void pickFromDevice() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("*/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try { startActivityForResult(Intent.createChooser(i, "Выберите файлы"), REQ_PICK); }
        catch (Exception e) { toast("Не удалось открыть выбор файлов"); }
    }

    private void pickFromFs() {
        FsTool fs = new FsTool(getActivity());
        final List<String> files = fs.listAllFiles();
        if (files.isEmpty()) { toast("В проводнике нет файлов"); return; }
        final boolean[] checked = new boolean[files.size()];
        final String[] arr = files.toArray(new String[0]);
        new AlertDialog.Builder(getActivity())
                .setTitle("Файлы проекта")
                .setMultiChoiceItems(arr, checked, new android.content.DialogInterface.OnMultiChoiceClickListener() {
                    public void onClick(android.content.DialogInterface d, int which, boolean isChecked) { checked[which] = isChecked; }
                })
                .setPositiveButton("Прикрепить", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        int n = 0;
                        for (int i = 0; i < arr.length; i++) {
                            if (checked[i]) { addFsAttachment(arr[i]); n++; }
                        }
                        if (n > 0) { renderAttachPreview(); updateSendButton(); toast("Прикреплено: " + n); }
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void addFsAttachment(String path) {
        try {
            FsTool fs = new FsTool(getActivity());
            String name = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
            String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase() : "";
            byte[] data = fs.readBytes(path);
            if (data == null) return;
            JSONObject a = new JSONObject();
            a.put("name", name).put("path", path).put("size", data.length).put("source", "fs");
            if (isImageExt(ext)) {
                a.put("kind", "image");
                a.put("dataUrl", "data:" + mimeForExt(ext) + ";base64," + Base64.encodeToString(data, Base64.NO_WRAP));
            } else if (isArchiveExt(ext)) {
                // Архив уже лежит в проводнике проекта — агент прочитает его
                // содержимое через archive_list / archive_read_entry по этому пути.
                a.put("kind", "archive");
            } else if (isTextExt(ext)) {
                a.put("kind", "text");
                a.put("text", new String(data, "UTF-8"));
            } else {
                a.put("kind", "binary");
            }
            pendingAttachments.add(a);
        } catch (Exception e) { toast("Ошибка: " + firstLine(e.getMessage())); }
    }

    @Override
    public void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK || res != android.app.Activity.RESULT_OK || data == null) return;
        List<Uri> uris = new ArrayList<Uri>();
        if (data.getClipData() != null) {
            ClipData cd = data.getClipData();
            for (int i = 0; i < cd.getItemCount(); i++) uris.add(cd.getItemAt(i).getUri());
        } else if (data.getData() != null) uris.add(data.getData());
        int n = 0;
        for (Uri u : uris) { if (addDeviceAttachment(u)) n++; }
        if (n > 0) { renderAttachPreview(); updateSendButton(); toast("Прикреплено: " + n); }
    }

    private boolean addDeviceAttachment(Uri uri) {
        try {
            String name = queryName(uri);
            String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase() : "";
            InputStream in = getActivity().getContentResolver().openInputStream(uri);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int r;
            while ((r = in.read(buf)) > 0) bos.write(buf, 0, r);
            in.close();
            byte[] data = bos.toByteArray();
            JSONObject a = new JSONObject();
            a.put("name", name).put("size", data.length).put("source", "device");
            if (isImageExt(ext)) {
                a.put("kind", "image");
                a.put("dataUrl", "data:" + mimeForExt(ext) + ";base64," + Base64.encodeToString(data, Base64.NO_WRAP));
            } else if (isArchiveExt(ext)) {
                // Архив с устройства: сохраняем во внутреннюю рабочую папку проекта,
                // чтобы агент мог прочитать его содержимое через archive_list /
                // archive_read_entry (как в web — прикреплённый zip кладётся в ФС).
                String fsPath = saveDeviceFileToProject(name, data);
                a.put("kind", "archive");
                if (fsPath != null) a.put("path", fsPath);
            } else if (isTextExt(ext)) {
                a.put("kind", "text");
                a.put("text", new String(data, "UTF-8"));
            } else if (data.length < 200000 && !looksBinaryBytes(data)) {
                // Небольшой текстоподобный файл без NUL-байтов — инлайним как текст.
                a.put("kind", "text");
                a.put("text", new String(data, "UTF-8"));
            } else {
                a.put("kind", "binary");
            }
            pendingAttachments.add(a);
            return true;
        } catch (Exception e) { toast("Ошибка загрузки: " + firstLine(e.getMessage())); return false; }
    }

    private String queryName(Uri uri) {
        String name = "file";
        try {
            android.database.Cursor c = getActivity().getContentResolver().query(uri, null, null, null, null);
            if (c != null) {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (c.moveToFirst() && idx >= 0) name = c.getString(idx);
                c.close();
            }
        } catch (Exception ignored) {}
        return name;
    }

    private boolean isImageExt(String e) {
        return e.equals("png")||e.equals("jpg")||e.equals("jpeg")||e.equals("gif")
                ||e.equals("webp")||e.equals("bmp");
    }
    private boolean isArchiveExt(String e) {
        return e.equals("zip");
    }
    /** Есть ли NUL-байт в первых 4КБ (эвристика «бинарный файл»). */
    private static boolean looksBinaryBytes(byte[] d) {
        int n = Math.min(d.length, 4096);
        for (int i = 0; i < n; i++) if (d[i] == 0) return true;
        return false;
    }
    /**
     * Сохраняет файл, прикреплённый с устройства, в рабочую папку проекта
     * (подбирая свободное имя), чтобы агент мог обратиться к нему инструментами
     * ФС/архивов. Возвращает виртуальный путь в проводнике или null при ошибке.
     */
    private String saveDeviceFileToProject(String name, byte[] data) {
        try {
            ExplorerFs efs = new ExplorerFs(getActivity());
            String base = (name == null || name.isEmpty()) ? "attachment" : name;
            String path = ExplorerFs.joinPath("/", base);
            // Если имя занято — добавляем числовой суффикс перед расширением.
            if (efs.exists(path)) {
                String stem = base, ext = "";
                int dot = base.lastIndexOf('.');
                if (dot > 0) { stem = base.substring(0, dot); ext = base.substring(dot); }
                for (int i = 1; i < 1000; i++) {
                    String cand = ExplorerFs.joinPath("/", stem + " (" + i + ")" + ext);
                    if (!efs.exists(cand)) { path = cand; break; }
                }
            }
            efs.writeBytes(path, data);
            FsEvents.notifyChanged();
            return path;
        } catch (Exception e) { return null; }
    }
    private boolean isTextExt(String e) {
        String[] t = {"txt","md","markdown","json","xml","yaml","yml","toml","ini","cfg","conf","csv","tsv",
                "log","html","htm","css","scss","less","js","mjs","cjs","ts","tsx","jsx","py","rb","rs","go",
                "java","kt","c","h","cpp","hpp","cs","php","sh","bash","zsh","sql","vue","svelte","env","gradle"};
        for (String x : t) if (x.equals(e)) return true;
        return false;
    }
    private String mimeForExt(String e) {
        if (e.equals("png")) return "image/png";
        if (e.equals("gif")) return "image/gif";
        if (e.equals("webp")) return "image/webp";
        if (e.equals("bmp")) return "image/bmp";
        return "image/jpeg";
    }

    /** Превью прикреплённых файлов над полем ввода. */
    private void renderAttachPreview() {
        if (attachPreview == null) return;
        attachPreview.removeAllViews();
        if (pendingAttachments.isEmpty()) { attachPreview.setVisibility(View.GONE); return; }
        attachPreview.setVisibility(View.VISIBLE);
        for (int i = 0; i < pendingAttachments.size(); i++) {
            final JSONObject a = pendingAttachments.get(i);
            final int idx = i;
            LinearLayout chip = new LinearLayout(getActivity());
            chip.setOrientation(LinearLayout.HORIZONTAL);
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setPadding(dp(8), dp(5), dp(6), dp(5));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.rightMargin = dp(6);
            chip.setLayoutParams(clp);
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setCornerRadius(dp(10));
            bg.setColor(Color.parseColor("#232B36"));
            chip.setBackground(bg);

            boolean img = "image".equals(a.optString("kind"));
            TextView t = new TextView(getActivity());
            t.setText((img ? "\uD83D\uDDBC " : "\uD83D\uDCC4 ") + a.optString("name", "file"));
            t.setTextColor(Color.parseColor("#E6EDF3"));
            t.setTextSize(12f);
            t.setMaxWidth(dp(150));
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            chip.addView(t);
            TextView rm = new TextView(getActivity());
            rm.setText("  \u2715");
            rm.setTextColor(Color.parseColor("#8B949E"));
            rm.setTextSize(13f);
            rm.setPadding(dp(4), 0, dp(4), 0);
            rm.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { pendingAttachments.remove(idx); renderAttachPreview(); updateSendButton(); }
            });
            chip.addView(rm);
            attachPreview.addView(chip);
        }
    }

    // Дебаунс прокрутки: при потоке обновлений статуса (много tool-результатов
    // подряд при редактировании большого файла) не дёргаем дорогой fullScroll на
    // каждое событие, а схлопываем в один вызов раз в ~80мс. Заметно разгружает UI.
    private final Runnable scrollTask = new Runnable() {
        public void run() { chatScroll.fullScroll(View.FOCUS_DOWN); }
    };
    private void scrollDown() {
        chatScroll.removeCallbacks(scrollTask);
        chatScroll.postDelayed(scrollTask, 80);
    }

    private int dp(int v) {
        float d = getResources().getDisplayMetrics().density;
        return (int) (v * d + 0.5f);
    }

    private void toast(String s) {
        Toast.makeText(getActivity(), s, Toast.LENGTH_SHORT).show();
    }
}
