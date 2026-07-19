package com.idemobile.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ядро ИИ-агента. Порт цикла выполнения из web-версии (ai.js):
 *   Thinking → запрос к провайдеру → парсинг tool-вызовов → выполнение
 *   инструментов (FsTool) → результат обратно модели → повтор,
 *   пока модель не даст финальный ответ БЕЗ tool-вызовов.
 *
 * Всё общение с UI идёт через Listener (вызывается в фоновом потоке —
 * UI должен сам делать runOnUiThread).
 */
public class Agent {

    /** Разобранный tool-вызов. */
    public static class Call {
        public String tool;
        public JSONObject args;
        public String nativeId;   // id нативного tool_call (для tool_call_id в ответе); null для текстовых
        public Call(String tool, JSONObject args) { this.tool = tool; this.args = args; }
        public Call(String tool, JSONObject args, String nativeId) { this.tool = tool; this.args = args; this.nativeId = nativeId; }
    }

    /** Слушатель событий агента (для отрисовки статус-карточки). */
    public interface Listener {
        void onReasoning(String text);                 // «размышление» модели
        void onOp(String label, String detail, String kind); // op: think/ok/err
        void onToolStart(String tool, JSONObject args);       // начали вызов
        void onToolResult(String logLine, boolean ok, JSONObject result, String tool, JSONObject args); // готово
        void onFinal(String text, String realModel);   // финальный ответ модели (+ реальный id модели)
        void onError(String message);                  // ошибка
        boolean isCancelled();                          // остановка пользователем
        /**
         * Текущее отменяемое HTTP-соединение (или null, когда запрос завершён).
         * Фрагмент сохраняет его и вызывает canceller.cancel() при нажатии «стоп»,
         * чтобы оборвать сетевой запрос мгновенно. Дефолтная реализация — no-op.
         */
        void onCanceller(ApiClient.Canceller canceller);
        /**
         * Забрать накопленные во время работы сообщения пользователя (interject).
         * Возвращает список текстов новых сообщений (или пустой список). Реализация
         * должна очистить свою очередь. Вызывается на границах шагов/инструментов.
         */
        java.util.List<String> drainInbox();
        /** Есть ли непрочитанные interject-сообщения (для быстрого прерывания пачки инструментов). */
        boolean hasInbox();
    }

    private final Context ctx;
    private final Store store;
    private FsTool fs;

    // Отметки времени для throttle (пауза между запросами к ИИ / вызовами инструментов).
    // Порт _lastProviderAt / _lastToolAt из web-версии (ai.js).
    private long lastProviderAt = 0;
    private long lastToolAt = 0;

    public Agent(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.store = Store.get(ctx);
        this.fs = new FsTool(ctx);
    }

    public FsTool fs() { return fs; }

    /**
     * Синхронно фиксирует историю чата на диск ПРЯМО ИЗ ФОНОВОГО ПОТОКА агента.
     *
     * Раньше персист происходил только через асинхронные UI-колбэки
     * (onToolResult → touchActiveChat на главном потоке). Если приложение падало
     * во время работы агента (напр. OOM на большом файле), последние шаги не
     * успевали записаться, и после перезапуска прерванный прогресс агента ТЕРЯЛСЯ.
     * Теперь после каждого значимого шага (ответ модели, результат инструмента)
     * мы сохраняем сразу — и уже проделанная работа переживает вылет.
     */
    private void persist() {
        try { store.touchActiveChat(); } catch (Throwable ignored) {}
    }

    /**
     * Запуск агента. Блокирующий — вызывать из фонового потока.
     * @param provider  активный провайдер
     * @param history   история чата (массив {role,content}) — user/assistant/tool
     * @param l         слушатель
     */
    public void run(JSONObject provider, JSONArray history, Listener l) {
        // Пересоздаём FsTool на каждый запуск, чтобы подхватить актуальный
        // источник проводника (INTERNAL или открытая пользователем папка).
        fs = new FsTool(ctx);
        int maxTool = store.settings().optInt("maxToolCalls", 30);
        int timeoutMs = clampTimeout(store.settings().optInt("providerTimeoutMs", 120000));
        // Прокидываем лимит длины ответа (providerMaxTokens) в объект провайдера,
        // чтобы ApiClient.callProvider вставил его в тело запроса (max_tokens /
        // maxOutputTokens). Раньше значение из настроек игнорировалось.
        try { provider.put("maxTokens", store.settings().optInt("providerMaxTokens", 8192)); }
        catch (org.json.JSONException ignored) {}
        int toolCount = 0, step = 0;
        int noToolTurns = 0;          // сколько turn'ов подряд модель «болтала» без валидного вызова
        final int noToolLimit = 3;    // после стольких безуспешных nudge — сдаёмся (не зацикливаемся)
        // Детектор зацикливания инструментов (порт makeToolLoopDetector() из web-версии).
        // Раньше в Android его НЕ было вовсе: слабые модели (sarvam-* и др.) могли
        // бесконечно перечитывать/переискивать один и тот же файл — задача «зависала».
        ToolLoopDetector loopDet = new ToolLoopDetector();
        String prevAssistantText = null;   // текст предыдущего ответа модели (для детекта залипания)
        try {
            while (true) {
                if (l.isCancelled()) { l.onError("Остановлено пользователем"); return; }
                step++;
                // Забираем сообщения, пришедшие во время работы — как приоритетную
                // system-note (порт drainInbox() из web-версии). Так агент помнит,
                // на чём остановился, и продолжает без перезапуска задачи.
                drainInboxInto(history, l);
                l.onOp("Waiting", step == 1 ? "Анализирую…" : "Продолжаю…", "think");

                // Собираем сообщения: system + история (с разворачиванием вложений).
                JSONArray messages = buildLLMMessages(provider, history);

                ApiClient.ChatResult resp;
                try {
                    resp = callWithRetry(provider, messages, timeoutMs, l);
                } catch (CancelledException ce) {
                    l.onError("Остановлено пользователем");
                    return;
                } catch (Exception e) {
                    String em = e.getMessage() == null ? "" : e.getMessage();
                    // 403 от шлюза (Azure Application Gateway и др.): часто «тело запроса
                    // слишком большое». Контекст уже сжат capContextSize, но если 403 всё
                    // равно пришёл — агрессивно ужимаем историю ещё раз и повторяем ОДИН раз.
                    // ВАЖНО: любое сообщение об ошибке отдаём ПОЛЬЗОВАТЕЛЮ КАК ЕСТЬ — ровно то,
                    // что вернул base url провайдера, без перевода/подмены в коде. Если повтор
                    // не помог — показываем именно сырой ответ провайдера (не свой текст).
                    if (isGatewayForbidden(em)) {
                        hardTrimHistory(history);
                        try {
                            JSONArray retryMsgs = buildLLMMessages(provider, history);
                            resp = callWithRetry(provider, retryMsgs, timeoutMs, l);
                        } catch (CancelledException ce) { l.onError("Остановлено пользователем"); return; }
                        catch (Exception e2) {
                            String em2 = e2.getMessage() == null ? "" : e2.getMessage();
                            l.onError(em2.isEmpty() ? (em.isEmpty() ? "Ошибка провайдера" : em) : em2);
                            return;
                        }
                    } else {
                        l.onError(em.isEmpty() ? "Ошибка провайдера" : em);
                        return;
                    }
                }
                String text = resp.text == null ? "" : resp.text;
                String realModel = resp.realModel == null ? "" : resp.realModel;
                java.util.List<ApiClient.ToolCall> nativeCalls =
                        resp.toolCalls != null ? resp.toolCalls : new java.util.ArrayList<ApiClient.ToolCall>();

                // ANTI-LOOP (порт из web ai.js). Если модель залипла — выдала текст
                // БЕЗ валидного вызова инструмента, который почти совпадает с прошлым
                // ответом ИЛИ сам содержит навязчивые повторы («обещаю…обещаю…», один
                // и тот же текст на любое сообщение) — повторяем запрос с усиленным
                // сэмплингом (T=1.0, высокие штрафы), чтобы разорвать детерминированный
                // цикл. Это то, из-за чего «на любое сообщение отвечает одинаково».
                // При native tool_calls анти-луп НЕ нужен — модель уже действует.
                if (nativeCalls.isEmpty() && parseToolCalls(text).isEmpty()
                        && (looksRepetitive(text) || isNearDuplicate(text, prevAssistantText))) {
                    try {
                        provider.put("_antiLoop", true);
                        ApiClient.ChatResult retry = callWithRetry(provider, messages, timeoutMs, l);
                        String rt = retry.text == null ? "" : retry.text;
                        java.util.List<ApiClient.ToolCall> rtc = retry.toolCalls != null ? retry.toolCalls : new java.util.ArrayList<ApiClient.ToolCall>();
                        // Берём повторный ответ, если он «сдвинулся» (вызвал инструмент
                        // ИЛИ дал не-дубликат текста).
                        if (!rtc.isEmpty() || (!rt.isEmpty() && !isNearDuplicate(rt, text))) {
                            resp = retry; text = rt; realModel = retry.realModel == null ? "" : retry.realModel;
                            nativeCalls = rtc;
                        }
                    } catch (CancelledException ce) { l.onError("Остановлено пользователем"); return; }
                    catch (Exception ignored) { /* оставляем первый ответ */ }
                    finally { provider.remove("_antiLoop"); }
                }
                prevAssistantText = text;

                // Разбираем reasoning и вызовы. Native tool_calls (OpenAI
                // function-calling) имеют ПРИОРИТЕТ над текстовым парсингом — именно
                // они чинят «болтовню без действия» у моделей типа gpt-oss-120b.
                List<Call> calls;
                String reasoning;
                if (!nativeCalls.isEmpty()) {
                    calls = new ArrayList<Call>();
                    for (ApiClient.ToolCall tc : nativeCalls)
                        calls.add(new Call(tc.name, tc.args != null ? tc.args : new JSONObject(), tc.id));
                    reasoning = text.trim(); // весь текст — это reasoning модели рядом с вызовом
                } else {
                    calls = parseToolCalls(text);
                    reasoning = stripToolBlocks(text).trim();
                }
                if (!reasoning.isEmpty()) l.onReasoning(reasoning);

                // assistant-сообщение в историю. При native — сохраняем _toolCalls,
                // чтобы следующий turn отправил корректный OpenAI-запрос
                // (assistant.tool_calls → role:tool с tool_call_id).
                JSONObject asstMsg = new JSONObject().put("role", "assistant").put("content", text)
                        .put("realModel", realModel).put("silent", true);
                if (!nativeCalls.isEmpty()) {
                    JSONArray tcArr = new JSONArray();
                    for (ApiClient.ToolCall tc : nativeCalls) {
                        tcArr.put(new JSONObject()
                                .put("id", tc.id)
                                .put("name", tc.name)
                                .put("args", tc.args != null ? tc.args : new JSONObject()));
                    }
                    asstMsg.put("_toolCalls", tcArr);
                }
                history.put(asstMsg);
                persist();   // фиксируем промежуточный ответ на диск СРАЗУ (не ждём UI-колбэка)

                if (calls.isEmpty()) {
                    // Пока модель отвечала, могли прийти новые сообщения — не выходим,
                    // продолжим следующий turn с их учётом.
                    if (l.hasInbox()) continue;
                    // Модель «пообещала» действие, но не выдала валидный вызов
                    // инструмента (ролевые модели типа sarvam: "сделаю…сделаю…",
                    // "**Tool:** …" в неверном формате, курсивный мусор). Раньше это
                    // приводило либо к молчаливому выходу вместо работы, либо — если
                    // модель повторяла обещание — к «бесконечной болтовне».
                    // Даём строгий nudge и повторяем turn; после noToolLimit попыток
                    // сдаёмся, чтобы не зациклиться самим.
                    if (looksLikeToolIntent(text) && noToolTurns < noToolLimit) {
                        noToolTurns++;
                        history.put(new JSONObject().put("role", "system").put("content",
                            "СТОП. Ты написал намерение (\"сделаю\", \"сейчас исправлю\" и т.п.), но НЕ вызвал "
                          + "инструмент в правильном формате — поэтому НИЧЕГО не произошло. "
                          + "НЕ описывай действие словами и НЕ отыгрывай роль. Немедленно выведи РОВНО один "
                          + "вызов в формате:\n```tool\n{\"tool\":\"<имя>\",\"args\":{...}}\n```\n"
                          + "Допустимые имена: fs_read, fs_write, fs_replace, fs_append, fs_list, fs_search, "
                          + "fs_mkdir, fs_delete, fs_rename. Никакого текста вокруг блока."));
                        // Со 2-й попытки включаем усиленный сэмплинг: детерминированную
                        // «болтовню без действия» (частый режим слабых ролевых моделей)
                        // одним и тем же промптом не разорвать — нужен другой сэмплинг.
                        if (noToolTurns >= 2) {
                            try {
                                provider.put("_antiLoop", true);
                                JSONArray m2 = buildLLMMessages(provider, history);
                                ApiClient.ChatResult r2 = callWithRetry(provider, m2, timeoutMs, l);
                                String t2 = r2.text == null ? "" : r2.text;
                                java.util.List<ApiClient.ToolCall> nc2 = r2.toolCalls != null ? r2.toolCalls : new java.util.ArrayList<ApiClient.ToolCall>();
                                if (!nc2.isEmpty() || !parseToolCalls(t2).isEmpty()) {
                                    // Модель наконец выдала вызов — обрабатываем его в этом же turn'е.
                                    resp = r2; text = t2; realModel = r2.realModel == null ? "" : r2.realModel;
                                    nativeCalls = nc2;
                                    if (!nativeCalls.isEmpty()) {
                                        calls = new ArrayList<Call>();
                                        for (ApiClient.ToolCall tc : nativeCalls)
                                            calls.add(new Call(tc.name, tc.args != null ? tc.args : new JSONObject(), tc.id));
                                        reasoning = text.trim();
                                        JSONArray tcArr = new JSONArray();
                                        for (ApiClient.ToolCall tc : nativeCalls)
                                            tcArr.put(new JSONObject().put("id", tc.id).put("name", tc.name)
                                                    .put("args", tc.args != null ? tc.args : new JSONObject()));
                                        asstMsg.put("_toolCalls", tcArr);
                                    } else {
                                        calls = parseToolCalls(text);
                                        reasoning = stripToolBlocks(text).trim();
                                    }
                                    asstMsg.put("content", text).put("realModel", realModel);
                                    if (!reasoning.isEmpty()) l.onReasoning(reasoning);
                                    noToolTurns = 0;
                                    // проваливаемся ниже к исполнению calls
                                } else {
                                    continue;
                                }
                            } catch (CancelledException ce) { l.onError("Остановлено пользователем"); return; }
                            catch (Exception ignored) { continue; }
                            finally { provider.remove("_antiLoop"); }
                        } else {
                            continue;
                        }
                    } else {
                        // Финал — снимаем silent, показываем пузырь.
                        asstMsg.put("silent", false);
                        l.onFinal(reasoning.isEmpty() ? text : reasoning, realModel);
                        return;
                    }
                }
                // Модель снова начала вызывать инструменты — сбрасываем счётчик болтовни.
                noToolTurns = 0;

                // Выполняем все вызовы этого шага.
                // Отслеживаем выполненные native tool_call id: OpenAI требует, чтобы
                // ЗА КАЖДЫМ assistant.tool_calls шёл role:tool с тем же id. Если пачку
                // прервали (inbox/лимит) — недостающие ответы дозаполним заглушкой,
                // иначе следующий запрос упадёт с HTTP 400.
                java.util.Set<String> doneNativeIds = new java.util.HashSet<String>();
                for (Call c : calls) {
                    if (l.isCancelled()) { fillMissingToolResults(history, calls, doneNativeIds); l.onError("Остановлено пользователем"); return; }
                    // Пришло новое сообщение — прерываем оставшиеся вызовы этой пачки,
                    // чтобы модель ответила на него на следующем turn'е.
                    if (l.hasInbox()) break;
                    if (toolCount++ >= maxTool) {
                        fillMissingToolResults(history, calls, doneNativeIds);
                        l.onError("Достигнут лимит вызовов инструментов (" + maxTool + ")");
                        return;
                    }
                    // Пауза между вызовами инструментов (если включена в настройках).
                    try { throttleBeforeTool(l); }
                    catch (CancelledException ce) { l.onError("Остановлено пользователем"); return; }
                    Label lbl = labelFor(c.tool, c.args, false);
                    l.onOp(lbl.op, lbl.detail, "think");
                    l.onToolStart(c.tool, c.args);

                    long t0 = System.currentTimeMillis();
                    JSONObject res = fs.execute(c.tool, c.args);
                    long ms = System.currentTimeMillis() - t0;
                    boolean rok = res.optBoolean("ok", false);
                    Label past = labelFor(c.tool, c.args, true);
                    String sum = rok ? summarize(c.tool, res.optJSONObject("result"))
                                     : res.optString("error", "ошибка");
                    String logLine = past.op + (past.detail.isEmpty() ? "" : " " + past.detail)
                            + "  " + sum + " · " + ms + "ms";
                    l.onToolResult(logLine, rok, rok ? res.optJSONObject("result") : null, c.tool, c.args);

                    // Результат обратно модели (как tool-сообщение).
                    JSONObject forModel = rok ? res.optJSONObject("result")
                                              : new JSONObject().put("error", res.optString("error"));
                    // Особый случай: инструмент вернул картинку (data URL в _image).
                    // Это может быть fs_read_image (файл из проводника), archive_read_entry
                    // (картинка ВНУТРИ zip без распаковки) или http_fetch (картинка из сети).
                    // Прикрепляем её как multi-part изображение, чтобы модель реально
                    // «увидела» пиксели на следующем шаге (порт из web-версии).
                    String imageUrl = forModel != null ? forModel.optString("_image", "") : "";
                    boolean isNative = c.nativeId != null && !c.nativeId.isEmpty();
                    if (rok && !imageUrl.isEmpty()) {
                        // Имя/источник картинки: путь в проводнике, либо archive!entry для zip.
                        String imgName = forModel.optString("path", "");
                        if (imgName.isEmpty() && forModel.has("entry")) {
                            imgName = forModel.optString("archive", "архив") + " → " + forModel.optString("entry");
                        }
                        JSONObject slim = new JSONObject();
                        if (forModel.has("path"))    slim.put("path", forModel.optString("path"));
                        if (forModel.has("archive")) slim.put("archive", forModel.optString("archive"));
                        if (forModel.has("entry"))   slim.put("entry", forModel.optString("entry"));
                        slim.put("mime", forModel.optString("mime"));
                        slim.put("size", forModel.optInt("size"));
                        String desc = "Результат инструмента " + c.tool + " (изображение):\n" + slim.toString();
                        if (isNative) {
                            // Native: сначала обязательный role:tool с tool_call_id
                            // (иначе OpenAI/Cohere отвергнут запрос), затем картинку —
                            // отдельным user-сообщением (role:tool не несёт image parts).
                            history.put(new JSONObject().put("role", "tool")
                                    .put("_toolCallId", c.nativeId).put("name", c.tool)
                                    .put("content", desc));
                        }
                        JSONObject imgMsg = new JSONObject()
                                .put("role", "user")
                                .put("content", (isNative ? "" : desc + "\n")
                                        + "Ниже прикреплены реальные пиксели — опиши, что на них ИЗОБРАЖЕНО.");
                        JSONArray imgs = new JSONArray().put(new JSONObject()
                                .put("dataUrl", imageUrl).put("name", imgName));
                        imgMsg.put("_images", imgs);
                        imgMsg.put("hidden", true); // видно модели, но не в ленте чата
                        history.put(imgMsg);
                    } else {
                        String summary = forModel == null ? "" : forModel.toString();
                        if (summary.length() > 4000) summary = summary.substring(0, 4000) + "\n...[truncated]";
                        // Сигнатура (tool+args) — чтобы свернуть ПРЕДЫДУЩИЕ идентичные
                        // результаты этого же вызова в короткую заглушку. Иначе при
                        // повторном перечитывании файла история копит десятки копий
                        // одного и того же содержимого → тело запроса раздувается до
                        // 403-лимита шлюза (Azure). Оставляем только САМЫЙ СВЕЖИЙ.
                        String toolSig = ToolLoopDetector.argSig(c.tool, c.args);
                        collapseOldToolResults(history, toolSig);
                        if (isNative) {
                            // Native tool-результат: role:tool + tool_call_id (обязательно
                            // для OpenAI function-calling). ApiClient развернёт корректно.
                            history.put(new JSONObject().put("role", "tool")
                                    .put("hidden", true)
                                    .put("_toolCallId", c.nativeId)
                                    .put("name", c.tool)
                                    .put("_toolSig", toolSig)
                                    .put("content", (rok ? "" : "ERROR: ")
                                            + "Результат инструмента " + c.tool + ":\n" + summary));
                        } else {
                            history.put(new JSONObject().put("role", "user")
                                    .put("hidden", true) // видно модели, но не в ленте чата
                                    .put("_toolSig", toolSig)
                                    .put("content", "Результат инструмента " + c.tool + (rok ? "" : " (ОШИБКА)") + ":\n" + summary));
                        }
                    }
                    if (isNative) doneNativeIds.add(c.nativeId);
                    persist();   // фиксируем результат tool-вызова на диск СРАЗУ из фонового потока

                    // Проверяем: не залип ли агент в цикле повторяющихся вызовов/ошибок.
                    LoopSignal sig = loopDet.observe(c.tool, c.args, rok, rok ? "" : res.optString("error", ""));
                    if (sig != null && sig.nudge) {
                        // Мягкое подталкивание (без обрыва): read-only повтор — данные уже есть.
                        history.put(new JSONObject().put("role", "system").put("content", sig.note));
                        l.onOp("Действую", "Данные уже прочитаны — перехожу к следующему шагу…", "think");
                    } else if (sig != null && sig.stop) {
                        fillMissingToolResults(history, calls, doneNativeIds);
                        l.onError("⚠ " + sig.reason);
                        return;
                    }
                }
                // Пачка инструментов могла прерваться (inbox) — дозаполняем недостающие
                // native tool-ответы, чтобы OpenAI-запрос следующего turn'а был валиден.
                fillMissingToolResults(history, calls, doneNativeIds);
            }
        } catch (JSONException e) {
            l.onError("Внутренняя ошибка: " + e.getMessage());
        }
    }

    /**
     * Дозаполняет ответы на НЕ выполненные native tool_calls (пачку прервали —
     * inbox/лимит/стоп). OpenAI-инвариант: за каждым assistant.tool_calls должен
     * идти role:tool с тем же tool_call_id, иначе провайдер вернёт HTTP 400
     * «tool_calls must be followed by tool messages».
     */
    private void fillMissingToolResults(JSONArray history, List<Call> calls, java.util.Set<String> done) {
        try {
            for (Call c : calls) {
                if (c.nativeId == null || c.nativeId.isEmpty()) continue;
                if (done.contains(c.nativeId)) continue;
                done.add(c.nativeId);
                history.put(new JSONObject().put("role", "tool")
                        .put("hidden", true)
                        .put("_toolCallId", c.nativeId)
                        .put("name", c.tool)
                        .put("content", "Вызов прерван и не выполнен (пользователь прислал новое сообщение "
                                + "или достигнут лимит). Продолжи с учётом этого."));
            }
        } catch (JSONException ignored) {}
    }

    /**
     * Порт drainInbox() из ai.js. Забирает накопленные interject-сообщения
     * и дописывает в историю скрытую system-note с приоритетным маркером,
     * инструктирующую продолжить с места остановки, не начиная задачу заново.
     */
    private void drainInboxInto(JSONArray history, Listener l) throws JSONException {
        java.util.List<String> items = l.drainInbox();
        if (items == null || items.isEmpty()) return;
        String note = "[ПРИОРИТЕТНОЕ СООБЩЕНИЕ ВО ВРЕМЯ РАБОТЫ] Пользователь прислал "
                + (items.size() == 1 ? "новое сообщение" : items.size() + " новых сообщения")
                + " во время выполнения текущей задачи. Кратко подтверди получение в reasoning, "
                + "учти его и ПРОДОЛЖИ работу с того шага, на котором остановился (не начинай задачу заново). "
                + "Если сообщение просит остановиться — заверши текущую задачу коротким итогом.";
        history.put(new JSONObject().put("role", "system").put("content", note));
    }

    /**
     * Порт buildLLMMessages() из ai.js. Собирает сообщения для провайдера:
     * system-prompt + история. Вложения user-сообщений разворачиваются:
     *  - текстовые файлы инлайнятся в текст;
     *  - картинки складываются в служебное поле _images (ApiClient превратит
     *    их в multi-part content с image_url — модель реально «видит» картинку).
     * Служебные поля (realModel, silent, interject, attachments) в API не идут.
     */
    private JSONArray buildLLMMessages(JSONObject provider, JSONArray history) throws JSONException {
        JSONArray msgs = new JSONArray();
        msgs.put(new JSONObject().put("role", "system").put("content", systemPrompt(provider)));
        for (int i = 0; i < history.length(); i++) {
            JSONObject m = history.optJSONObject(i);
            if (m == null) continue;
            // Плашка «предыдущий запуск прерван» — чисто UI-подсказка, в контекст
            // модели не идёт (иначе засоряет диалог служебным текстом).
            if (m.optBoolean("interruptedNotice", false)) continue;
            String role = m.optString("role");
            if ("user".equals(role)) {
                String content = m.optBoolean("interject", false)
                        ? "[ПРИОРИТЕТНОЕ СООБЩЕНИЕ ВО ВРЕМЯ РАБОТЫ] " + m.optString("content", "")
                        : m.optString("content", "");
                JSONArray images = new JSONArray();
                // Готовые _images (например от fs_read_image) пробрасываем как есть.
                JSONArray existing = m.optJSONArray("_images");
                if (existing != null) for (int k = 0; k < existing.length(); k++) images.put(existing.opt(k));
                JSONArray atts = m.optJSONArray("attachments");
                if (atts != null && atts.length() > 0) {
                    StringBuilder tp = new StringBuilder();
                    for (int j = 0; j < atts.length(); j++) {
                        JSONObject a = atts.optJSONObject(j);
                        if (a == null) continue;
                        String kind = a.optString("kind", "");
                        if ("image".equals(kind) && !a.optString("dataUrl", "").isEmpty()) {
                            images.put(new JSONObject().put("dataUrl", a.optString("dataUrl")).put("name", a.optString("name")));
                            tp.append("\n[прикреплено изображение: ").append(a.optString("name", "image")).append("]");
                        } else if ("text".equals(kind) && a.has("text")) {
                            String txt = a.optString("text", "");
                            if (txt.length() > 8000) txt = txt.substring(0, 8000) + "\n...[truncated]";
                            tp.append("\nФайл ").append(a.optString("name", "file"))
                              .append(":\n```\n").append(txt).append("\n```");
                        } else if ("archive".equals(kind)) {
                            // Прикреплён zip-архив. Он лежит в проводнике проекта по пути
                            // path — модель должна просмотреть его содержимое инструментами
                            // archive_list / archive_read_entry (текст/картинки), не гадая.
                            String ap = a.optString("path", "");
                            tp.append("\n[прикреплён ZIP-архив: ").append(a.optString("name", "archive.zip"));
                            if (!ap.isEmpty()) tp.append(" — путь в проводнике: ").append(ap);
                            tp.append("]. Чтобы посмотреть что внутри — вызови archive_list; ")
                              .append("чтобы прочитать/описать конкретный файл (текст или картинку) — archive_read_entry ")
                              .append("(картинки автоматически попадут в твой vision, и на следующем шаге ты их увидишь и опишешь).");
                        } else {
                            tp.append("\nПрикреплён файл: ").append(a.optString("name", a.optString("path", "file")));
                        }
                    }
                    if (tp.length() > 0) content = (content.isEmpty() ? "" : content + "\n\n") + tp.toString().trim();
                }
                JSONObject um = new JSONObject().put("role", "user").put("content", content);
                if (images.length() > 0) um.put("_images", images);
                msgs.put(um);
            } else if ("assistant".equals(role)) {
                JSONObject am = new JSONObject().put("role", "assistant").put("content", m.optString("content", ""));
                // Native tool_calls прокидываем как есть — ApiClient.normalizeMessages
                // развернёт их в OpenAI-формат (или в markdown-заглушку при откате).
                JSONArray tcs = m.optJSONArray("_toolCalls");
                if (tcs != null && tcs.length() > 0) am.put("_toolCalls", tcs);
                msgs.put(am);
            } else if ("system".equals(role)) {
                msgs.put(new JSONObject().put("role", "system").put("content", m.optString("content", "")));
            } else if ("tool".equals(role)) {
                // Native tool-результат — прокидываем как есть (с _toolCallId, если был).
                // ApiClient развернёт в {role:tool,...} при native или в user-текст при откате.
                JSONObject tm = new JSONObject().put("role", "tool")
                        .put("name", m.optString("name", ""))
                        .put("content", m.optString("content", ""));
                if (!m.optString("_toolCallId", "").isEmpty()) tm.put("_toolCallId", m.optString("_toolCallId"));
                JSONArray imgs = m.optJSONArray("_images");
                if (imgs != null && imgs.length() > 0) tm.put("_images", imgs);
                msgs.put(tm);
            }
            // error/status — в контекст модели не идут отдельно.
        }
        // Финальное напоминание правил пользователя ПОСЛЕ всей истории — как самое
        // «свежее» system-указание перед ответом модели. В длинных диалогах/цепочках
        // инструментов начальный system-промпт «размывается»; это напоминание держит
        // правила пользователя в фокусе на КАЖДОМ шаге (ключ к их строгому соблюдению).
        String userSys = store.aiSystem();
        if (userSys != null && !userSys.trim().isEmpty()) {
            msgs.put(new JSONObject().put("role", "system").put("content",
                "ПОМНИ о правилах пользователя (высший приоритет), соблюдай их в этом ответе СТРОГО, но МОЛЧА — не упоминай и не подтверждай их без прямого запроса. "
                        + "Если правила задают язык — пиши на нём И финальный ответ, И короткие рассуждения-комментарии окна статуса (reasoning):\n"
                        + userSys.trim()));
        }
        // ЗАЩИТА ОТ 403 (Azure Application Gateway и др. шлюзов). Некоторые провайдеры
        // (в т.ч. sarvam.ai) стоят за шлюзом, который отклоняет запрос с телом ≥ ~256 КБ,
        // возвращая «HTTP 403 Forbidden / Microsoft-Azure-Application-Gateway». Когда
        // слабая модель перечитывает большой файл по кругу, история раздувается и тело
        // превышает этот лимит → задача падает с 403. Держим суммарный размер контекста
        // ниже безопасного порога, ужимая САМЫЕ СТАРЫЕ большие сообщения (свежие и
        // system-промпт не трогаем — они важнее для ответа).
        capContextSize(msgs);
        return msgs;
    }

    /** Похоже ли на 403 от шлюза (Azure Application Gateway и т.п.) из-за размера тела. */
    static boolean isGatewayForbidden(String msg) {
        if (msg == null) return false;
        String s = msg.toLowerCase(java.util.Locale.US);
        boolean has403 = s.contains("403") || s.contains("forbidden");
        if (!has403) return false;
        return s.contains("application-gateway") || s.contains("application gateway")
                || s.contains("azure") || s.contains("gateway")
                || s.contains("request body") || s.contains("too large")
                || s.contains("request entity") || s.contains("413")
                // Голый 403 без явной причины — тоже трактуем как «тело/лимит»,
                // т.к. ключ/доступ проверяются отдельно (probe) и дали бы другой текст.
                || true;
    }

    /**
     * Агрессивно ужимает историю после 403: сохраняет самое первое (постановка
     * задачи) и последние 4 сообщения, а всё крупное между ними сворачивает в
     * заглушку. Крайняя мера — только когда обычный capContextSize не спас.
     */
    private void hardTrimHistory(JSONArray history) {
        try {
            int n = history.length();
            if (n <= 6) return;
            for (int i = 1; i < n - 4; i++) {
                JSONObject m = history.optJSONObject(i);
                if (m == null) continue;
                if (m.optJSONArray("_images") != null) continue;   // картинки не трогаем
                String c = m.optString("content", "");
                if (c.length() > 200) {
                    m.put("content", (c.length() > 120 ? c.substring(0, 120) : c)
                            + " …[свёрнуто ради лимита провайдера]");
                }
            }
        } catch (Exception ignored) {}
    }

    /**
     * Сворачивает ПРЕДЫДУЩИЕ результаты инструмента с той же сигнатурой (tool+args)
     * в короткую заглушку, оставляя только самый свежий (который допишется следом).
     * Предотвращает накопление десятков копий одного и того же большого содержимого
     * в истории при повторном перечитывании файла (главная причина 403 от шлюза).
     */
    private void collapseOldToolResults(JSONArray history, String toolSig) {
        if (toolSig == null || toolSig.isEmpty()) return;
        try {
            for (int i = 0; i < history.length(); i++) {
                JSONObject m = history.optJSONObject(i);
                if (m == null) continue;
                if (!toolSig.equals(m.optString("_toolSig", ""))) continue;
                String c = m.optString("content", "");
                if (c.length() > 160) {
                    m.put("content", (c.length() > 80 ? c.substring(0, 80) : c)
                            + " …[повторный вызов — прежний результат свёрнут, актуальный ниже]");
                }
            }
        } catch (Exception ignored) {}
    }

    /** Безопасный лимит тела запроса (запас до 256 КБ шлюза Azure). */
    private static final int MAX_CONTEXT_BYTES = 180 * 1024;

    /**
     * Ужимает историю сообщений, если суммарный размер приближается к лимиту шлюза.
     * Идём от СТАРЫХ к новым и обрезаем крупные не-финальные сообщения, пока общий
     * размер не уложится в MAX_CONTEXT_BYTES. Никогда не трогаем: system-сообщения,
     * два последних сообщения (актуальный запрос/результат) и сообщения с картинками.
     */
    private void capContextSize(JSONArray msgs) {
        try {
            int total = approxBytes(msgs);
            if (total <= MAX_CONTEXT_BYTES) return;
            int n = msgs.length();
            // Обрезаем крупные текстовые сообщения от начала к концу (кроме system и
            // двух последних), заменяя содержимое короткой пометкой.
            for (int i = 0; i < n - 2 && total > MAX_CONTEXT_BYTES; i++) {
                JSONObject m = msgs.optJSONObject(i);
                if (m == null) continue;
                if ("system".equals(m.optString("role"))) continue;
                if (m.optJSONArray("_images") != null) continue; // картинки не режем
                String c = m.optString("content", "");
                if (c.length() <= 400) continue;
                int before = c.getBytes().length;
                String trimmed = c.substring(0, 200)
                        + "\n…[старое содержимое свёрнуто для экономии контекста — перечитай файл заново при необходимости]…\n"
                        + c.substring(Math.max(0, c.length() - 100));
                m.put("content", trimmed);
                total -= (before - trimmed.getBytes().length);
            }
            // Если всё ещё великовато — жёстко удаляем самые старые не-system сообщения
            // (кроме двух последних), пока не уложимся.
            while (total > MAX_CONTEXT_BYTES && msgs.length() > 4) {
                int removeIdx = -1;
                for (int i = 0; i < msgs.length() - 2; i++) {
                    JSONObject m = msgs.optJSONObject(i);
                    if (m == null) continue;
                    if ("system".equals(m.optString("role"))) continue;
                    if (m.optJSONArray("_images") != null) continue;
                    // НЕ удаляем структурные native-сообщения (assistant с _toolCalls и
                    // role:tool) поодиночке — иначе нарушим OpenAI-инвариант
                    // «tool_calls → tool» и провайдер вернёт HTTP 400. Их content уже
                    // ужат циклом выше; сами сообщения оставляем на месте.
                    if ("tool".equals(m.optString("role"))) continue;
                    if (m.optJSONArray("_toolCalls") != null) continue;
                    removeIdx = i; break;
                }
                if (removeIdx < 0) break;
                int sz = msgs.optJSONObject(removeIdx).toString().getBytes().length;
                msgs.remove(removeIdx);
                total -= sz;
            }
        } catch (Exception ignored) {}
    }

    /** Приблизительный размер тела (сумма длин content в байтах + накладные). */
    private static int approxBytes(JSONArray msgs) {
        int total = 0;
        for (int i = 0; i < msgs.length(); i++) {
            JSONObject m = msgs.optJSONObject(i);
            if (m == null) continue;
            total += m.optString("content", "").getBytes().length + 40;
            JSONArray imgs = m.optJSONArray("_images");
            if (imgs != null) for (int k = 0; k < imgs.length(); k++) {
                JSONObject im = imgs.optJSONObject(k);
                if (im != null) total += im.optString("dataUrl", "").length();
            }
        }
        return total;
    }

    private int clampTimeout(int v) { return Math.max(15000, Math.min(900000, v)); }

    // ------------------------------------------------------------- throttle
    // Прерываемая пауза: спит ms миллисекунд, но каждые 200мс проверяет отмену.
    private void interruptibleSleep(long ms, Listener l) throws CancelledException {
        long waited = 0;
        while (waited < ms) {
            if (l.isCancelled()) throw new CancelledException();
            long chunk = Math.min(200, ms - waited);
            try { Thread.sleep(chunk); } catch (InterruptedException ie) { throw new CancelledException(); }
            waited += chunk;
        }
    }

    /**
     * Пауза ПЕРЕД запросом к провайдеру (главная защита от 429). Порт
     * throttleBeforeProvider() из ai.js: если включена настройка «Пауза между
     * запросами к ИИ», выдерживаем минимальный интервал toolThrottleMs между
     * последовательными запросами к модели. Показываем статус ожидания.
     */
    private void throttleBeforeProvider(Listener l) throws CancelledException {
        JSONObject st = store.settings();
        if (!st.optBoolean("toolThrottleEnabled", false)) { lastProviderAt = System.currentTimeMillis(); return; }
        long interval = Math.max(0, st.optInt("toolThrottleMs", 0));
        if (interval == 0) { lastProviderAt = System.currentTimeMillis(); return; }
        long wait = Math.max(0, interval - (System.currentTimeMillis() - lastProviderAt));
        if (wait > 0) {
            double sec = Math.round(wait / 100.0) / 10.0;
            l.onOp("Wait", "Пауза " + sec + "s перед запросом к модели (throttle)", "think");
            interruptibleSleep(wait, l);
        }
        lastProviderAt = System.currentTimeMillis();
    }

    /**
     * Пауза ПЕРЕД вызовом инструмента. Порт throttleBeforeTool() из ai.js:
     * выдерживает минимальный интервал toolThrottleMs между вызовами инструментов.
     * Именно этот статус («Пауза Nмс перед следующим вызовом») виден в веб-версии.
     */
    private void throttleBeforeTool(Listener l) throws CancelledException {
        JSONObject st = store.settings();
        if (!st.optBoolean("toolThrottleEnabled", false)) { lastToolAt = System.currentTimeMillis(); return; }
        long interval = Math.max(0, st.optInt("toolThrottleMs", 0));
        if (interval == 0) { lastToolAt = System.currentTimeMillis(); return; }
        long wait = Math.max(0, interval - (System.currentTimeMillis() - lastToolAt));
        if (wait > 0) {
            l.onOp("Wait", "Пауза " + wait + "мс перед следующим вызовом (throttle)", "think");
            interruptibleSleep(wait, l);
        }
        lastToolAt = System.currentTimeMillis();
    }

    // ------------------------------------------------------------- loop detector
    /** Сигнал детектора: либо мягкий nudge (note), либо жёсткая остановка (reason). */
    static class LoopSignal {
        boolean stop, nudge;
        String reason, note;
        static LoopSignal stop(String r){ LoopSignal s=new LoopSignal(); s.stop=true; s.reason=r; return s; }
        static LoopSignal nudge(String n){ LoopSignal s=new LoopSignal(); s.nudge=true; s.note=n; return s; }
    }

    /**
     * Порт makeToolLoopDetector() из web-версии (ai.js). Отслеживает зацикливание:
     *   • успешный read-only повтор (fs_read/fs_search/…) — НИКОГДА не обрываем, лишь
     *     мягко подталкиваем перейти к действию (1-в-1 как web); это позволяет
     *     легитимно читать большие файлы окнами; предохранитель от бесконечности — maxTool;
     *   • 10 одинаковых НЕ-read-only вызовов подряд → стоп;
     *   • 3 одинаковые ошибки подряд / 6 любых ошибок подряд → стоп.
     */
    static class ToolLoopDetector {
        private static final java.util.Set<String> READONLY = new java.util.HashSet<String>(java.util.Arrays.asList(
                "fs_read", "fs_search", "fs_list", "archive_list", "archive_read_entry",
                "git_status", "fs_read_image", "fs_read_media", "fs_read_binary"));
        private String sameCallKey = ""; private int sameCallCount = 0;
        private String sameErrKey = "";  private int sameErrCount = 0;
        private int anyErrRun = 0;
        private int roNudges = 0;

        static String argSig(String tool, JSONObject args) {
            try {
                JSONObject copy = args != null ? new JSONObject(args.toString()) : new JSONObject();
                if (copy.has("content")) copy.put("content", "<content>");
                return tool + ":" + copy.toString();
            } catch (Exception e) { return tool; }
        }
        private static String errSig(boolean ok, String error) {
            if (ok) return "";
            String e = (error == null ? "" : error).toLowerCase(java.util.Locale.US).replaceAll("\\s+", " ").trim();
            e = e.replaceAll("'[^']{1,200}'", "'…'").replaceAll("\"[^\"]{1,200}\"", "\"…\"")
                 .replaceAll("/[a-z0-9._\\-/]{1,200}", "/…");
            if (e.length() > 200) e = e.substring(0, 200);
            return e.isEmpty() ? "unknown" : e;
        }

        LoopSignal observe(String tool, JSONObject args, boolean ok, String error) {
            String aSig = argSig(tool, args);
            String eSig = errSig(ok, error);
            boolean readonly = READONLY.contains(tool);
            // 1) Одинаковый вызов подряд.
            if (aSig.equals(sameCallKey)) sameCallCount++;
            else { sameCallKey = aSig; sameCallCount = 1; roNudges = 0; }
            // 1a) УСПЕШНЫЙ read-only повтор — НЕ обрываем (выровнено с web-версией
            // ai.js: makeToolLoopDetector). Слабые модели ЛЕГИТИМНО перечитывают
            // большой файл (напр. smali 2000+ строк) множеством последовательных
            // fs_read с разными/одинаковыми offset и серией fs_search перед
            // единственным fs_replace. Прежний ЖЁСТКИЙ обрыв на 5 одинаковых read-only
            // ложно срабатывал именно на таких файлах: модель ещё не успела перейти к
            // правке, а задача уже прерывалась сообщением «перечитывает одно и то же».
            // Теперь, как в web, только МЯГКО подталкиваем «данные уже есть — действуй»
            // (не чаще раза в 4 повтора, максимум 3 раза), а от переполнения контекста
            // защищают collapseOldToolResults + capContextSize, от бесконечности — maxTool.
            if (readonly && ok) {
                if (sameCallCount >= 4 && roNudges < 3 && sameCallCount % 4 == 0) {
                    roNudges++;
                    return LoopSignal.nudge("Ты уже " + sameCallCount + " раз(а) подряд вызвал `" + tool
                            + "` с одними и теми же параметрами и получил ТОТ ЖЕ результат — эти данные УЖЕ У ТЕБЯ ЕСТЬ, "
                            + "перечитывать их снова НЕ НУЖНО (это только раздувает контекст). Переходи к СЛЕДУЮЩЕМУ шагу: "
                            + "если нужно изменить файл — вызови fs_replace/fs_write ПРЯМО СЕЙЧАС; если задача выполнена — дай финальный ответ текстом.");
                }
                anyErrRun = 0; sameErrKey = ""; sameErrCount = 0;
                return null;
            }
            // 1b) 10 одинаковых НЕ-read-only (или неуспешных) → стоп.
            if (sameCallCount >= 10) {
                return LoopSignal.stop("Агент 10 раз подряд вызывает `" + tool
                        + "` с одинаковыми параметрами — похоже, он застрял в цикле. Прерываю выполнение.");
            }
            // 2) Ошибочные вызовы.
            if (!ok) {
                anyErrRun++;
                String key = tool + "::" + eSig;
                if (key.equals(sameErrKey)) sameErrCount++;
                else { sameErrKey = key; sameErrCount = 1; }
                boolean recoverable = (error != null) && error.toLowerCase(java.util.Locale.US)
                        .matches("(?s).*(не указан аргумент|нужны непустые|нужен непустой|нельзя удалять|нельзя создавать|повтори вызов|повтори с реальными).*");
                int stopAt = recoverable ? 5 : 3;
                if (sameErrCount >= stopAt) {
                    return LoopSignal.stop("Инструмент `" + tool + "` несколько раз подряд возвращает одну и ту же ошибку: «"
                            + (error == null ? "неизвестная ошибка" : error) + "». Похоже, ресурс недоступен — остановил задачу.");
                }
                if (anyErrRun >= 6) {
                    return LoopSignal.stop("Шесть инструментов подряд завершились ошибкой (последняя: `" + tool + "` — «"
                            + (error == null ? "неизвестная ошибка" : error) + "»). Прерываю выполнение — проверьте доступ и настройки.");
                }
            } else {
                anyErrRun = 0; sameErrKey = ""; sameErrCount = 0;
            }
            return null;
        }
    }

    // ------------------------------------------------------------- retry/backoff
    /** Прерывание запроса пользователем во время ожидания повтора. */
    static class CancelledException extends Exception {}

    /**
     * Вызов провайдера с авто-повтором при ВРЕМЕННЫХ ошибках. Порт логики
     * web-версии (ai.js: auto-backoff on 429 / rate-limit): такие ошибки, как
     * «429 / rate limit / too many requests / ResourceExhausted / worker limit /
     * 5xx / timeout», приходят ОТ САМОГО провайдера (не от кода приложения) и
     * почти всегда временные. Мы повторяем запрос с экспоненциальной задержкой,
     * показывая статус пользователю. Ошибки «по существу» (401/402/403, неверная
     * модель, нет баланса) НЕ повторяем — показываем сразу.
     */
    private ApiClient.ChatResult callWithRetry(JSONObject provider, JSONArray messages,
                                               int timeoutMs, Listener l) throws Exception {
        boolean backoffOn = store.settings().optBoolean("autoBackoffOn429", true);
        int maxRetries = backoffOn ? store.settings().optInt("autoBackoffMaxRetries", 4) : 0;
        int attempt = 0;
        while (true) {
            if (l.isCancelled()) throw new CancelledException();
            try {
                // Выдерживаем минимальный интервал МЕЖДУ запросами к провайдеру.
                throttleBeforeProvider(l);
                // Создаём отменяемое соединение и отдаём его слушателю (фрагменту),
                // чтобы кнопка «стоп» могла оборвать запрос МГНОВЕННО, не дожидаясь ответа.
                ApiClient.Canceller canceller = new ApiClient.Canceller();
                l.onCanceller(canceller);
                if (l.isCancelled()) { canceller.cancel(); throw new CancelledException(); }
                ApiClient.ChatResult r;
                try {
                    r = ApiClient.callProvider(provider, messages, timeoutMs, canceller);
                } finally {
                    l.onCanceller(null); // соединение больше не активно
                }
                if (canceller.isCancelled() || l.isCancelled()) throw new CancelledException();
                lastProviderAt = System.currentTimeMillis(); // отметка успешного запроса
                return r;
            } catch (CancelledException ce) {
                throw ce;
            } catch (Exception e) {
                if (l.isCancelled()) throw new CancelledException();
                String msg = e.getMessage() == null ? "" : e.getMessage();
                if (msg.contains("Остановлено пользователем")) throw new CancelledException();
                if (attempt >= maxRetries || !isTransientError(msg)) throw e;
                // Сколько ждать: из текста ошибки (retry-after), иначе экспоненциально.
                long fromMsg = parseRetryAfterMs(msg);
                long delay = fromMsg > 0
                        ? Math.min(60000L, fromMsg)
                        : Math.min(30000L, (long) (1000 * Math.pow(2, attempt))) + (long) (Math.random() * 300);
                attempt++;
                double sec = Math.round(delay / 100.0) / 10.0;
                l.onOp("Retry", "Провайдер перегружен (rate-limit) → жду " + sec
                        + "s · попытка " + attempt + "/" + maxRetries, "think");
                // Ждём с проверкой отмены каждые 200мс.
                long waited = 0;
                while (waited < delay) {
                    if (l.isCancelled()) throw new CancelledException();
                    long chunk = Math.min(200, delay - waited);
                    try { Thread.sleep(chunk); } catch (InterruptedException ie) { throw new CancelledException(); }
                    waited += chunk;
                }
                // После ожидания сдвигаем отметку, чтобы следующий throttle не добавлял лишнего.
                lastProviderAt = System.currentTimeMillis();
            }
        }
    }

    /**
     * Временная (recoverable) ли ошибка провайдера. Признаки rate-limit'а и
     * перегрузки: HTTP 429/500/502/503/504, «rate limit», «too many requests»,
     * «quota», «throttl», «ResourceExhausted», «worker … limit reached»,
     * «overloaded», «temporarily», а также сетевые таймауты. Исчерпание баланса
     * (insufficient/balance/credit/budget) — НЕ временная ошибка.
     */
    static boolean isTransientError(String msg) {
        if (msg == null) return false;
        String s = msg.toLowerCase(java.util.Locale.US);
        // Явно НЕ временные — деньги/доступ.
        if (s.matches("(?s).*(insufficient|no balance|not enough|out of credit|budget_exhausted|payment required|402).*")
                && !s.contains("rate")) return false;
        return s.contains("429")
                || s.contains("rate limit") || s.contains("rate_limit") || s.contains("ratelimit")
                || s.contains("too many requests")
                || s.contains("quota")
                || s.contains("throttl")
                || s.contains("resourceexhausted") || s.contains("resource exhausted")
                || s.contains("request limit reached") || s.contains("limit reached")
                || s.contains("overload")
                || s.contains("temporar")
                || s.contains("try again")
                || s.contains("timeout") || s.contains("timed out") || s.contains("time out")
                || s.matches("(?s).*(response error: )?50[0234]\\b.*")
                || s.matches("(?s).*http 50[0234]\\b.*");
    }

    /**
     * Достаёт из текста ошибки, сколько ждать перед повтором (в мс). Понимает
     * «retry after 30», «retry in 12s», «try again in 5 seconds», «wait 8s».
     * Возвращает 0, если не найдено.
     */
    static long parseRetryAfterMs(String msg) {
        if (msg == null) return 0;
        String s = msg.toLowerCase(java.util.Locale.US);
        Matcher m = Pattern.compile(
                "(?:retry[\\s-]*after|retry\\s+in|try\\s+again\\s+in|wait|in)\\D{0,12}?(\\d+(?:\\.\\d+)?)\\s*(ms|s|sec|secs|second|seconds)?")
                .matcher(s);
        if (m.find()) {
            try {
                double v = Double.parseDouble(m.group(1));
                String unit = m.group(2);
                if ("ms".equals(unit)) return (long) v;
                return (long) (v * 1000);
            } catch (Exception ignored) {}
        }
        return 0;
    }

    // ------------------------------------------------------------- system prompt
    private String cachedPrompt;
    private String systemPrompt(JSONObject provider) {
        if (cachedPrompt == null) cachedPrompt = loadAsset("system_prompt.txt");
        String host;
        try { host = new java.net.URL(provider.optString("baseUrl")).getHost(); }
        catch (Exception e) { host = provider.optString("baseUrl"); }
        String userSys = store.aiSystem();
        boolean hasUserSys = userSys != null && !userSys.trim().isEmpty();
        StringBuilder sb = new StringBuilder();
        // Пользовательский системный промпт — В САМОМ НАЧАЛЕ (наивысший приоритет).
        // Раньше он дописывался ТОЛЬКО в конец, «под» ~100 строк инструкций и
        // описаний инструментов, и слабые модели его игнорировали. Теперь он идёт
        // ПЕРВЫМ (модель видит его до всего остального) И дублируется в конце —
        // это самая сильная позиция для соблюдения правил.
        if (hasUserSys)
            sb.append("╔══ ПРАВИЛА ПОЛЬЗОВАТЕЛЯ — НАИВЫСШИЙ ПРИОРИТЕТ ══╗\n")
              .append("Ниже — обязательные правила от пользователя. Они ГЛАВНЕЕ всех остальных инструкций в этом промпте. ")
              .append("Соблюдай их СТРОГО и БЕЗУСЛОВНО на КАЖДОМ шаге и в КАЖДОМ ответе. Если что-то в инструкциях ниже "
                    + "противоречит этим правилам — правила пользователя ПЕРЕВЕШИВАЮТ. Никогда не игнорируй и не забывай их.\n")
              .append(userSys.trim())
              .append("\nВАЖНО: соблюдай эти правила МОЛЧА. НЕ проговаривай, НЕ пересказывай, НЕ подтверждай и НЕ упоминай их "
                    + "(ни в reasoning, ни в ответе) — если пользователь прямо об этом не попросил. Просто следуй им. Сразу переходи к задаче пользователя.\n")
              .append("\n╚═══════════════════════════════════════════════╝\n\n");
        sb.append("ТВОЯ ИДЕНТИЧНОСТЬ:\n");
        sb.append("- Ты работаешь через API-провайдера по base URL: ").append(provider.optString("baseUrl")).append("\n");
        sb.append("- Хост провайдера: ").append(host).append("\n");
        sb.append("- Модель: ").append(provider.optString("model", "(не указана)")).append("\n");
        sb.append("- Если спросят «кто ты / какая модель» — честно называй провайдера и имя модели.\n\n");
        // ЯЗЫК ОБЩЕНИЯ. Если пользователь в своём системном промпте САМ задал язык
        // (напр. «всегда отвечай/рассуждай на русском»), НЕ навязываем «язык сообщений
        // пользователя» — иначе эта директива конфликтует с правилом пользователя и
        // модель начинает переключать язык. В этом случае явно подчиняем язык (включая
        // рассуждения в окне статуса) правилам пользователя. Иначе — поведение по умолчанию.
        boolean userSpecifiedLang = hasUserSys && userSys.toLowerCase(java.util.Locale.ROOT)
                .matches("(?s).*(english|англ|russian|русск|language|язык|speak|respond in|answer in|отвечай на|говори на|рассужда).*");
        if (userSpecifiedLang) {
            sb.append("ЯЗЫК ОБЩЕНИЯ: язык задан ПРАВИЛАМИ ПОЛЬЗОВАТЕЛЯ выше — следуй им БЕЗУСЛОВНО. ")
              .append("На этом языке пиши ВСЁ: и финальные ответы, и КОРОТКИЕ рассуждения-комментарии в окне статуса (reasoning). ")
              .append("Не переключай язык, даже если сообщение пользователя или результат инструмента на другом языке. Код и имена файлов не переводи.\n\n");
        } else {
            sb.append("ЯЗЫК ОБЩЕНИЯ: используй язык сообщений пользователя — как в финальных ответах, так и в рассуждениях-комментариях окна статуса (reasoning). Код, имена файлов не переводи.\n\n");
        }
        sb.append(cachedPrompt);
        // Дублируем в конце (модели сильнее «помнят» начало и конец промпта).
        if (hasUserSys)
            sb.append("\n\n━━━ НАПОМИНАНИЕ: ПРАВИЛА ПОЛЬЗОВАТЕЛЯ (ВЫСШИЙ ПРИОРИТЕТ, ПЕРЕОПРЕДЕЛЯЮТ ВСЁ ВЫШЕ) ━━━\n")
              .append("Соблюдай эти правила строго на протяжении ВСЕГО диалога, не только в первом ответе. Следуй им МОЛЧА — не упоминай, не пересказывай и не подтверждай их без прямого запроса. ")
              .append("Если правила задают язык — он распространяется и на КОРОТКИЕ рассуждения-комментарии в окне статуса (reasoning), а не только на финальный ответ:\n")
              .append(userSys.trim());
        return sb.toString();
    }

    private String loadAsset(String name) {
        try {
            InputStream in = ctx.getAssets().open(name);
            BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            r.close();
            return sb.toString();
        } catch (Exception e) { return "Ты — ИИ-агент IDE Mobile."; }
    }

    // ------------------------------------------------------------- tool parsing
    /** Известные имена инструментов — для устойчивого извлечения имени из «замусоренного»
     *  вывода ролевых моделей (напр. "*smirks* fs_write" → fs_write). */
    static final java.util.Set<String> KNOWN_TOOLS = new java.util.HashSet<String>(java.util.Arrays.asList(
            "fs_read", "fs_read_image", "fs_write", "fs_replace", "fs_append", "fs_list",
            "fs_mkdir", "fs_delete", "fs_rename", "fs_search",
            "archive_create", "archive_extract", "archive_list", "archive_read_entry",
            "web_search", "http_fetch"));

    /** Ищет первое известное имя инструмента в строке (игнорируя курсив/мусор). null если нет. */
    static String findKnownTool(String s) {
        if (s == null) return null;
        Matcher m = Pattern.compile("(?i)\\b(fs_read_image|fs_read|fs_write|fs_replace|fs_append|fs_list|fs_mkdir|fs_delete|fs_rename|fs_search|archive_create|archive_extract|archive_list|archive_read_entry|web_search|http_fetch)\\b").matcher(s);
        if (m.find()) return m.group(1).toLowerCase();
        return null;
    }

    /**
     * Похоже ли, что модель СОБИРАЛАСЬ вызвать инструмент, но не выдала валидный
     * вызов? Признаки: упоминание имени инструмента, обломки формата (Tool:/arg_key/
     * ```tool), либо фразы-обещания ("сделаю", "let me", "I'll", "on it").
     * Используется, чтобы вместо молчаливого выхода/бесконечной болтовни дать nudge.
     */
    static boolean looksLikeToolIntent(String text) {
        if (text == null || text.isEmpty()) return false;
        if (findKnownTool(text) != null) return true;
        String t = text.toLowerCase();
        return t.contains("```tool") || t.contains("<arg_key") || t.contains("tool:")
            || t.contains("**tool") || t.contains("сделаю") || t.contains("щас")
            || t.contains("сейчас сдела") || t.contains("let me") || t.contains("i'll")
            || t.contains("i will") || t.contains("on it") || t.contains("выполню")
            || t.contains("удалю") || t.contains("найду") || t.contains("открою");
    }

    /**
     * Похож ли ответ на «залипание» — навязчивый внутренний повтор (одна и та же
     * фраза/строка много раз). Признак модели, застрявшей в цикле обещаний.
     */
    static boolean looksRepetitive(String text) {
        if (text == null) return false;
        String t = text.trim();
        if (t.length() < 40) return false;
        // 1) Повтор строк.
        String[] lines = t.split("\n");
        if (lines.length >= 4) {
            java.util.HashMap<String,Integer> freq = new java.util.HashMap<String,Integer>();
            int maxRep = 0;
            for (String ln : lines) {
                String k = ln.trim().toLowerCase(java.util.Locale.US);
                if (k.length() < 8) continue;
                int c = (freq.containsKey(k) ? freq.get(k) : 0) + 1;
                freq.put(k, c);
                if (c > maxRep) maxRep = c;
            }
            if (maxRep >= 3) return true;
        }
        // 2) Одна и та же короткая фраза, повторённая подряд (напр. «сделаю. сделаю. сделаю.»).
        Matcher m = Pattern.compile("(?i)(\\b.{6,40}?\\b)(?:\\W+\\1){2,}").matcher(t);
        if (m.find()) return true;
        return false;
    }

    /** Почти дубликат ли текст a относительно b (нормализованное сравнение). */
    static boolean isNearDuplicate(String a, String b) {
        if (a == null || b == null) return false;
        String na = a.replaceAll("\\s+", " ").trim().toLowerCase(java.util.Locale.US);
        String nb = b.replaceAll("\\s+", " ").trim().toLowerCase(java.util.Locale.US);
        if (na.isEmpty() || nb.isEmpty()) return false;
        if (na.length() < 30) return false;         // короткие уточнения не считаем залипанием
        if (na.equals(nb)) return true;
        // Существенное совпадение по длинному префиксу (модель повторяет тот же текст).
        int min = Math.min(na.length(), nb.length());
        int same = 0;
        while (same < min && na.charAt(same) == nb.charAt(same)) same++;
        return same >= (int) (min * 0.85);
    }

    /** Порт parseToolCalls() из ai.js. */
    public static List<Call> parseToolCalls(String text) {
        List<Call> calls = new ArrayList<Call>();
        if (text == null || text.isEmpty()) return calls;
        Set<String> seen = new LinkedHashSet<String>();
        Pattern[] rxes = {
                Pattern.compile("```(?:tool|json|javascript|js)?\\s*([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE),
                Pattern.compile("~~~(?:tool|json|javascript|js)?\\s*([\\s\\S]*?)~~~", Pattern.CASE_INSENSITIVE),
        };
        for (Pattern rx : rxes) {
            Matcher m = rx.matcher(text);
            while (m.find()) {
                String raw = m.group(1) == null ? "" : m.group(1).trim();
                if (raw.isEmpty()) continue;
                String key = m.start() + ":" + raw.length();
                if (seen.contains(key)) continue;
                seen.add(key);
                if (raw.startsWith("{")) {
                    JSONObject obj = tryParse(raw);
                    if (obj != null && obj.optString("tool", "").length() > 0)
                        calls.add(new Call(obj.optString("tool"), obj.optJSONObject("args") != null ? obj.optJSONObject("args") : new JSONObject()));
                    else calls.addAll(parseXmlToolCalls(raw)); // JSON не распарсился — вдруг это arg_key/arg_value
                } else {
                    // Содержимое fenced-блока — НЕ JSON. Это часто XML-стиль sarvam:
                    //   <arg_key>tool</arg_key><arg_value>fs_search</arg_value>
                    //   <arg_key>args</arg_key><arg_value>{...}</arg_value>
                    // Разбираем его тем же XML-парсером (он теперь понимает пары tool/args).
                    calls.addAll(parseXmlToolCalls(raw));
                }
            }
        }
        // Fallback: голый {"tool":...} без бэктиков.
        if (calls.isEmpty()) {
            Pattern rxTool = Pattern.compile("\\{\\s*\"tool\"\\s*:");
            Matcher mm = rxTool.matcher(text);
            while (mm.find()) {
                int start = mm.start();
                String json = extractBalanced(text, start);
                if (json == null) continue;
                JSONObject obj = tryParse(json);
                if (obj != null && obj.optString("tool", "").length() > 0) {
                    String key = start + ":" + json.length();
                    if (seen.contains(key)) continue;
                    seen.add(key);
                    calls.add(new Call(obj.optString("tool"), obj.optJSONObject("args") != null ? obj.optJSONObject("args") : new JSONObject()));
                }
            }
        }
        // Fallback B: XML-стиль вызова инструмента. Часть моделей (напр. sarvam-105b)
        // отдаёт tool-call не JSON'ом, а тегами:
        //   <tool_call>fs_list
        //   <arg_key>path</arg_key>
        //   <arg_value>/</arg_value>
        //   </tool_call>
        // Раньше такой формат не распознавался — вызов не выполнялся, а сырой
        // текст «утекал» пользователю. Разбираем его в обычный Call.
        if (calls.isEmpty()) calls.addAll(parseXmlToolCalls(text));
        // Fallback C: markdown-стиль ролевых моделей (sarvam в «character»-режиме):
        //   **Tool:** fs_search
        //   **Args:** {"query":".smali","path":"/"}
        // (либо "Tool:"/"Args:" без звёздочек, любой регистр). Раньше такой вызов
        // не распознавался — агент «болтал» ("сделаю...сделаю..."), но ничего не
        // выполнял и зацикливался.
        if (calls.isEmpty()) calls.addAll(parseMarkdownToolCalls(text));
        // Fallback D: формат {"name":..,"args"/"arguments"/"parameters":..} и массивы
        // таких объектов. Модели Mistral (mistral-tiny/-small и др.), когда провайдер
        // НЕ вернул native tool_calls, выкладывают вызов в ТЕКСТ в OpenAI-подобном виде:
        //   [{"name":"fs_read","args":{"path":"/f.smali","offset":1,"limit":50}}]
        //   {"name":"fs_replace","arguments":{...}}
        // Раньше такой ответ не парсился → агент «ничего не делал», плодил assistant-
        // сообщения и падал на Mistral с HTTP 400 (invalid_request_message_order).
        if (calls.isEmpty()) calls.addAll(parseNameArgsToolCalls(text));
        return calls;
    }

    /**
     * Разбор tool-call в формате {"name":..,"args"/"arguments"/"parameters":..} и
     * массивов таких объектов (порт parseNameArgsToolCalls() из ai.js). Ищем
     * сбалансированные JSON-объекты с ключом "name" где угодно в тексте (в т.ч.
     * внутри [ ... ]) и превращаем их в Call.
     */
    static List<Call> parseNameArgsToolCalls(String text) {
        List<Call> calls = new ArrayList<Call>();
        if (text == null || text.isEmpty()) return calls;
        Set<String> seen = new LinkedHashSet<String>();
        Matcher mm = Pattern.compile("\\{\\s*\"name\"\\s*:").matcher(text);
        while (mm.find()) {
            int start = mm.start();
            String json = extractBalanced(text, start);
            if (json == null) continue;
            JSONObject obj = tryParse(json);
            if (obj == null) continue;
            String name = obj.optString("name", "");
            if (name.isEmpty()) continue;
            JSONObject args = obj.optJSONObject("args");
            if (args == null) args = obj.optJSONObject("arguments");
            if (args == null) args = obj.optJSONObject("parameters");
            if (args == null) {
                // args могли прийти строкой с вложенным JSON.
                String as = obj.optString("args", obj.optString("arguments", obj.optString("parameters", "")));
                if (!as.isEmpty()) args = tryParse(as);
            }
            if (args == null) args = new JSONObject();
            String key = start + ":" + json.length();
            if (seen.contains(key)) continue;
            seen.add(key);
            calls.add(new Call(name, args));
        }
        return calls;
    }

    /** Разбор markdown-стиля: строка "Tool: <name>" + строка "Args: {json}". */
    static List<Call> parseMarkdownToolCalls(String text) {
        List<Call> calls = new ArrayList<Call>();
        if (text == null || text.isEmpty()) return calls;
        // Ищем "Tool:" (с необязательными **) → имя, затем ближайший "Args:" → JSON.
        Matcher mt = Pattern.compile("(?im)^\\s*\\**\\s*tool\\s*\\**\\s*:\\s*\\**\\s*([a-zA-Z_][\\w]*)\\s*\\**\\s*$").matcher(text);
        while (mt.find()) {
            String name = findKnownTool(mt.group(1));
            if (name == null) continue;
            // Ищем Args: после позиции имени.
            JSONObject args = new JSONObject();
            Matcher ma = Pattern.compile("(?is)\\**\\s*args\\s*\\**\\s*:\\s*\\**\\s*(\\{.*?\\})").matcher(text);
            if (ma.find(mt.end())) {
                JSONObject parsed = tryParse(ma.group(1).trim());
                if (parsed != null) {
                    // Иногда модель кладёт {"tool":...,"args":{...}} прямо в Args-JSON.
                    if (parsed.has("args") && parsed.optJSONObject("args") != null) args = parsed.optJSONObject("args");
                    else args = parsed;
                }
            }
            calls.add(new Call(name, args));
        }
        return calls;
    }

    /**
     * Разбор XML-стиля <tool_call>name ...pairs... </tool_call>.
     * Имя инструмента — первый непустой токен после <tool_call> (до первого
     * тега/переноса). Аргументы — пары <arg_key>K</arg_key><arg_value>V</arg_value>.
     * Значение приводится к числу/boolean/JSON, если это возможно, иначе строка.
     */
    static List<Call> parseXmlToolCalls(String text) {
        List<Call> calls = new ArrayList<Call>();
        if (text == null || text.isEmpty()) return calls;
        // Держим паттерн отдельно; Matcher создаём через pair.matcher(body) в цикле.
        Pattern pair = Pattern.compile("(?is)<arg_key>\\s*(.*?)\\s*</arg_key>\\s*<arg_value>(.*?)</arg_value>");
        // 1) Обычные обёрнутые блоки <tool_call>...</tool_call>.
        Matcher block = Pattern.compile("(?is)<tool_call>\\s*(.*?)</tool_call>").matcher(text);
        boolean anyWrapped = false;
        while (block.find()) {
            anyWrapped = true;
            Call c = parseOneXmlBlock(block.group(1), pair);
            if (c != null) calls.add(c);
        }
        // 2) Формат sarvam-105b БЕЗ открывающего тега: fenced-блок ```tool ... ```
        //    содержит только пары arg_key/arg_value (иногда с болтающимся </tool_call>).
        //    Первый ключ — «tool» (имя инструмента), ключ «args» — вложенный JSON.
        //    Если обёрнутых блоков не нашлось, но пары есть — разбираем весь текст как один блок.
        if (!anyWrapped && pair.matcher(text).find()) {
            Call c = parseOneXmlBlock(text, pair);
            if (c != null) calls.add(c);
        }
        return calls;
    }

    /** Разбирает ОДИН XML-блок (тело между тегами или весь fenced-блок) в Call. */
    private static Call parseOneXmlBlock(String body, Pattern pair) {
        if (body == null) return null;
        // Сначала собираем все пары arg_key/arg_value.
        JSONObject rawArgs = new JSONObject();
        String toolFromPair = null, argsJsonFromPair = null;
        Matcher pm = pair.matcher(body);
        while (pm.find()) {
            String k = pm.group(1) == null ? "" : pm.group(1).trim();
            String v = pm.group(2) == null ? "" : pm.group(2).trim();
            if (k.isEmpty()) continue;
            // Спец-ключи формата sarvam: "tool" = имя инструмента, "args" = JSON-объект аргументов.
            if (k.equals("tool") && toolFromPair == null) { toolFromPair = v.replaceAll("(?is)<[^>]*>", "").trim(); continue; }
            if (k.equals("args")) { argsJsonFromPair = v; continue; }
            try { rawArgs.put(k, coerceArgValue(v)); } catch (JSONException ignored) {}
        }
        // Определяем имя инструмента.
        String name = toolFromPair;
        if (name == null || name.isEmpty()) {
            // Имя — то, что до первого <arg_key> (или тег <tool_name>).
            String head = body;
            int firstTag = head.indexOf("<arg_key");
            if (firstTag >= 0) head = head.substring(0, firstTag);
            Matcher nameTag = Pattern.compile("(?is)<tool_name>\\s*(.*?)\\s*</tool_name>").matcher(head);
            if (nameTag.find()) name = nameTag.group(1).trim();
            else name = head.replaceAll("(?is)<[^>]*>", " ").replace("```", " ").trim();
            // Ролевые модели (sarvam «character»-режим) пишут перед именем курсивный
            // мусор: "*fingers fly across keyboard*\nfs_write". Берём НЕ первый токен
            // (это будет "*smirks*" → «Неизвестный инструмент»), а первое ИЗВЕСТНОЕ имя.
            String known = findKnownTool(name);
            if (known != null) name = known;
            else if (name != null && !name.isEmpty()) {
                String[] parts = name.split("\\s+");
                name = parts.length > 0 ? parts[0].trim() : "";
            }
        } else {
            // Даже имя из пары <arg_key>tool</arg_key> может прийти замусоренным.
            String known = findKnownTool(name);
            if (known != null) name = known;
        }
        if (name == null || name.isEmpty()) return null;
        // Собираем аргументы: приоритет — вложенный JSON из ключа "args".
        JSONObject args = new JSONObject();
        if (argsJsonFromPair != null) {
            JSONObject parsed = tryParse(argsJsonFromPair.trim());
            if (parsed != null) args = parsed;
        }
        // Добавляем/дополняем прочими парами (не перетирая уже пришедшие из args-JSON).
        java.util.Iterator<String> it = rawArgs.keys();
        while (it.hasNext()) {
            String k = it.next();
            if (!args.has(k)) { try { args.put(k, rawArgs.opt(k)); } catch (JSONException ignored) {} }
        }
        return new Call(name, args);
    }

    /** Приводит строковое значение аргумента к number/boolean/JSON/строке. */
    static Object coerceArgValue(String v) {
        if (v == null) return "";
        String t = v.trim();
        if (t.isEmpty()) return "";
        if (t.equals("true")) return Boolean.TRUE;
        if (t.equals("false")) return Boolean.FALSE;
        // Число (целое/дробное) без потери точности для типичных offset/limit.
        if (t.matches("-?\\d+")) {
            try { return Integer.valueOf(t); } catch (NumberFormatException e) {
                try { return Long.valueOf(t); } catch (NumberFormatException e2) {}
            }
        }
        if (t.matches("-?\\d+\\.\\d+")) {
            try { return Double.valueOf(t); } catch (NumberFormatException ignored) {}
        }
        // Вложенный JSON-объект/массив (напр. content с массивом строк).
        if ((t.startsWith("{") && t.endsWith("}")) || (t.startsWith("[") && t.endsWith("]"))) {
            try { return new org.json.JSONTokener(t).nextValue(); } catch (JSONException ignored) {}
        }
        return v; // строка как есть (без trim, чтобы не портить содержимое файла)
    }

    /** Убирает tool-блоки из текста, оставляя reasoning. */
    public static String stripToolBlocks(String text) {
        if (text == null) return "";
        // Fenced-блоки с JSON-объектом инструмента.
        String t = text.replaceAll("(?is)```(?:tool|json|javascript|js)?\\s*\\{[\\s\\S]*?```", "");
        t = t.replaceAll("(?is)~~~(?:tool|json|javascript|js)?\\s*\\{[\\s\\S]*?~~~", "");
        // Fenced-блоки XML-стиля sarvam (внутри arg_key/arg_value, а не JSON) —
        // убираем целиком, чтобы сырые теги не «утекли» в блок рассуждений.
        t = t.replaceAll("(?is)```(?:tool|json)?\\s*(?:<tool_call>|<arg_key>)[\\s\\S]*?```", "");
        t = t.replaceAll("(?is)~~~(?:tool|json)?\\s*(?:<tool_call>|<arg_key>)[\\s\\S]*?~~~", "");
        // убрать голые {"tool":...} объекты
        Pattern rxTool = Pattern.compile("\\{\\s*\"tool\"\\s*:");
        Matcher mm = rxTool.matcher(t);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (mm.find()) {
            int start = mm.start();
            String json = extractBalanced(t, start);
            if (json == null) continue;
            sb.append(t, last, start);
            last = start + json.length();
        }
        sb.append(t.substring(last));
        String out = sb.toString();
        // Убрать голые {"name":...,"args":...} объекты (формат Mistral в тексте,
        // см. parseNameArgsToolCalls) — тем же приёмом сбалансированной вырезки.
        Pattern rxName = Pattern.compile("\\{\\s*\"name\"\\s*:");
        Matcher nm = rxName.matcher(out);
        StringBuilder nsb = new StringBuilder();
        int nlast = 0;
        while (nm.find()) {
            int start = nm.start();
            String json = extractBalanced(out, start);
            if (json == null) continue;
            JSONObject obj = tryParse(json);
            if (obj == null || obj.optString("name", "").isEmpty()) continue;
            nsb.append(out, nlast, start);
            nlast = start + json.length();
        }
        nsb.append(out.substring(nlast));
        out = nsb.toString();
        // Убрать осиротевшие скобки/запятые массива вызовов ([ , ]) на отдельных строках.
        out = out.replaceAll("(?m)^\\s*[\\[\\],]+\\s*$", "");
        // Убрать XML-стиль <tool_call>...</tool_call> (см. parseXmlToolCalls).
        out = out.replaceAll("(?is)<tool_call>.*?</tool_call>", "");
        // Убрать болтающиеся пары arg_key/arg_value и одиночные теги tool_call
        // (формат sarvam без открывающего <tool_call>).
        out = out.replaceAll("(?is)<arg_key>.*?</arg_key>\\s*<arg_value>[\\s\\S]*?</arg_value>", "");
        out = out.replaceAll("(?is)</?tool_call>", "");
        // Убрать markdown-обломки вызова (**Tool:** name / **Args:** {...}) — они уже
        // разобраны parseMarkdownToolCalls, в reasoning их показывать не нужно.
        out = out.replaceAll("(?im)^\\s*\\**\\s*tool\\s*\\**\\s*:\\s*\\**\\s*[a-zA-Z_][\\w]*\\s*\\**\\s*$", "");
        out = out.replaceAll("(?is)\\**\\s*args\\s*\\**\\s*:\\s*\\**\\s*\\{[\\s\\S]*?\\}", "");
        return out.replaceAll("\\n{3,}", "\n\n").trim();
    }

    private static String extractBalanced(String text, int start) {
        int depth = 0; boolean inStr = false, esc = false;
        for (int i = start; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (inStr) {
                if (esc) esc = false;
                else if (ch == '\\') esc = true;
                else if (ch == '"') inStr = false;
                continue;
            }
            if (ch == '"') inStr = true;
            else if (ch == '{') depth++;
            else if (ch == '}') { depth--; if (depth == 0) return text.substring(start, i + 1); }
        }
        return null;
    }

    private static JSONObject tryParse(String raw) {
        try { return new JSONObject(raw); } catch (JSONException ignored) {}
        int last = raw.lastIndexOf('}');
        if (last > 0 && last < raw.length() - 1) {
            try { return new JSONObject(raw.substring(0, last + 1)); } catch (JSONException ignored) {}
        }
        String cleaned = raw.replaceAll("(?m)//[^\\n]*$", "").replaceAll(",\\s*([}\\]])", "$1");
        try { return new JSONObject(cleaned); } catch (JSONException ignored) {}
        return null;
    }

    // ------------------------------------------------------------- labels
    public static class Label { public String op = ""; public String detail = ""; }

    /** Порт labelFor() — человекочитаемая метка операции. */
    public static Label labelFor(String tool, JSONObject a, boolean past) {
        Label l = new Label();
        String p = a.optString("path", a.optString("from", ""));
        if ("fs_read".equals(tool)) { l.op = past ? "Read" : "Читаю"; l.detail = p; }
        else if ("fs_read_image".equals(tool)) { l.op = past ? "Viewed image" : "Смотрю картинку"; l.detail = p; }
        else if ("fs_write".equals(tool)) { l.op = past ? "Wrote" : "Пишу"; l.detail = p; }
        else if ("fs_replace".equals(tool)) { l.op = past ? "Replaced" : "Заменяю"; l.detail = p; }
        else if ("fs_append".equals(tool)) { l.op = past ? "Appended" : "Дописываю"; l.detail = p; }
        else if ("fs_list".equals(tool)) { l.op = past ? "Listed" : "Смотрю"; l.detail = p.isEmpty() ? "/" : p; }
        else if ("fs_mkdir".equals(tool)) { l.op = past ? "Created folder" : "Создаю папку"; l.detail = p; }
        else if ("fs_delete".equals(tool)) { l.op = past ? "Deleted" : "Удаляю"; l.detail = p; }
        else if ("fs_rename".equals(tool)) { l.op = past ? "Renamed" : "Переименовываю"; l.detail = p + " → " + a.optString("to"); }
        else if ("fs_search".equals(tool)) { l.op = past ? "Searched" : "Ищу"; l.detail = a.optString("query"); }
        else if ("archive_create".equals(tool)) { l.op = past ? "Archived" : "Архивирую"; l.detail = a.optString("archive", a.optString("source", "")); }
        else if ("archive_extract".equals(tool)) { l.op = past ? "Extracted" : "Распаковываю"; l.detail = a.optString("archive", "") + " → " + a.optString("to", "/"); }
        else if ("archive_list".equals(tool)) { l.op = past ? "Listed archive" : "Смотрю архив"; l.detail = a.optString("archive", ""); }
        else if ("archive_read_entry".equals(tool)) { l.op = past ? "Read entry" : "Читаю из архива"; l.detail = a.optString("entry", ""); }
        else if ("web_search".equals(tool)) { l.op = past ? "Searched web" : "Ищу в интернете"; l.detail = a.optString("query", ""); }
        else if ("http_fetch".equals(tool)) { l.op = past ? "Fetched" : "Загружаю"; l.detail = a.optString("url", ""); }
        else { l.op = tool; l.detail = p; }
        return l;
    }

    /** Порт summarizeToolResult() — краткое описание результата. */
    public static String summarize(String tool, JSONObject r) {
        if (r == null) return "";
        try {
            if ("fs_read".equals(tool)) {
                if (r.has("returnedLines")) {
                    JSONArray rl = r.optJSONArray("returnedLines");
                    return "строки " + rl.optInt(0) + "–" + rl.optInt(1) + " из " + r.optInt("lines");
                }
                return r.optInt("lines") + " строк · " + r.optInt("size") + " б";
            }
            if ("fs_write".equals(tool)) {
                StringBuilder p = new StringBuilder();
                p.append(r.optInt("lines")).append(" строк");
                if (r.has("written")) p.append(" · ").append(r.optInt("written")).append(" симв.");
                if (r.optBoolean("created")) p.append(" · новый файл");
                return p.toString();
            }
            if ("fs_replace".equals(tool)) {
                StringBuilder p = new StringBuilder();
                p.append(r.optInt("replacements")).append(" замен");
                int d = r.optInt("deltaLines");
                p.append(" · Δ").append(d >= 0 ? "+" : "").append(d).append(" строк");
                return p.toString();
            }
            if ("fs_append".equals(tool))
                return "+" + r.optInt("appendedLines") + " строк · +" + r.optInt("appendedChars") + " симв.";
            if ("fs_list".equals(tool))
                return r.optInt("files") + " файлов · " + r.optInt("dirs") + " папок";
            if ("fs_search".equals(tool))
                return r.optInt("count") + " совп. в " + r.optInt("scanned") + " файлах";
            if ("fs_delete".equals(tool) || "fs_mkdir".equals(tool) || "fs_rename".equals(tool))
                return "готово";
            if ("archive_create".equals(tool))
                return r.optInt("files") + " файлов · " + r.optLong("size") + " б";
            if ("archive_extract".equals(tool))
                return r.optInt("extracted") + " файлов → " + r.optString("to");
            if ("archive_list".equals(tool))
                return r.optInt("file_count") + " файлов · " + r.optInt("dir_count") + " папок";
            if ("archive_read_entry".equals(tool))
                return r.optString("encoding") + " · " + r.optInt("size") + " б";
            if ("web_search".equals(tool))
                return r.optInt("count") + " результатов";
            if ("http_fetch".equals(tool)) {
                if (r.has("data_url")) return r.optString("kind", "файл") + " · " + r.optInt("size") + " б";
                String t = r.optString("text", "");
                return "HTTP " + r.optInt("status") + " · " + t.length() + " симв.";
            }
        } catch (Exception ignored) {}
        return "";
    }
}
