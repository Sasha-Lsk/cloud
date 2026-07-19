package com.idemobile.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Сетевой слой ИИ-провайдера. Порт логики web-версии (ai.js):
 *  - normalizeBaseUrl / candidateEndpoints — умное достраивание пути;
 *  - proxyChain / buildProxyUrl — цепочка CORS-прокси (в web нужна для обхода
 *    CORS; на Android CORS нет, поэтому прямой запрос идёт первым, а прокси —
 *    как запасной вариант, если пользователь его указал);
 *  - listModels — GET /models, нормализация ответов OpenAI/Anthropic/Google;
 *  - probeModel — минимальный POST для проверки доступности по базовому тарифу;
 *  - modelCostTier / availabilityTier — цвет кружка (free/paid/unknown).
 *
 * ВНИМАНИЕ: сетевые методы блокирующие — вызывать из фонового потока.
 */
public class ApiClient {

    public static class Model {
        public String id;
        public String name;
        public JSONObject raw;
        public Model(String id, String name, JSONObject raw) { this.id = id; this.name = name; this.raw = raw; }
    }

    public static class Probe {
        // available: TRUE доступна, FALSE недоступна, null — неизвестно
        public Boolean available;
        public int status;
        public String reason;
        public Probe(Boolean a, int s, String r) { available = a; status = s; reason = r; }
    }

    /**
     * Отмена HTTP-запроса «на лету» для МГНОВЕННОЙ остановки по кнопке «стоп».
     *
     * Раньше кнопка «стоп» только выставляла флаг, а фоновый поток оставался
     * заблокированным внутри readAll()/сети до конца ответа или таймаута (до 120с) —
     * из-за этого остановка была не мгновенной. Теперь callProvider регистрирует
     * здесь текущее соединение; когда пользователь жмёт «стоп», AgentFragment вызывает
     * cancel(), и мы немедленно рвём соединение (disconnect + закрытие потока) — это
     * выбрасывает исключение в блокирующем чтении и поток тут же выходит.
     */
    public static class Canceller {
        private volatile boolean cancelled = false;
        private volatile HttpURLConnection active;
        private volatile InputStream activeStream;
        public boolean isCancelled() { return cancelled; }
        synchronized void attach(HttpURLConnection c) { active = c; if (cancelled) doAbort(); }
        synchronized void attachStream(InputStream s) { activeStream = s; if (cancelled) doAbort(); }
        public synchronized void cancel() { cancelled = true; doAbort(); }
        private void doAbort() {
            try { if (activeStream != null) activeStream.close(); } catch (Exception ignored) {}
            try { if (active != null) active.disconnect(); } catch (Exception ignored) {}
        }
    }

    // ------------------------------------------------------------- URL helpers
    static String normalizeBaseUrl(String u) {
        if (u == null) return "";
        u = u.trim();
        if (u.isEmpty()) return "";
        u = u.replaceAll("/+$", "");
        return u;
    }

    static boolean isGoogle(String u) { return u.toLowerCase(Locale.US).contains("generativelanguage.googleapis.com") || u.toLowerCase(Locale.US).contains("/v1beta/"); }
    static boolean isAnthropic(String u) { return u.toLowerCase(Locale.US).contains("api.anthropic.com") || u.toLowerCase(Locale.US).contains("/messages"); }

    /** Кандидаты endpoint'ов для chat/completions (или probe). */
    static List<String> candidateEndpoints(String baseUrl, boolean rawUrl) {
        List<String> cands = new ArrayList<String>();
        String b = normalizeBaseUrl(baseUrl);
        if (rawUrl) { cands.add(b); return cands; }
        // Уже похоже на completions-endpoint (…/chat/completions, …/completions,
        // …/messages, …/generateContent) — используем как есть.
        if (b.matches("(?i).*/(chat/)?completions(\\?.*)?$")
                || b.matches("(?i).*/messages(\\?.*)?$")
                || b.contains("generateContent")) {
            cands.add(b);
            return cands;
        }
        // Порт candidateEndpoints() из web-версии: строим кандидатов умно, не
        // создавая дублей /v1/v1/, пробуем и legacy /completions, и сам base.
        if (b.matches("(?i).*/v\\d+[a-z]*$")) {
            // В пути уже есть версия (…/v1, …/v1beta, …/openai/v1) — НЕ добавляем ещё /v1.
            cands.add(b + "/chat/completions");
            cands.add(b + "/completions");
        } else {
            cands.add(b + "/v1/chat/completions");
            cands.add(b + "/chat/completions");
        }
        // Сам base «как есть» — крайний резерв для нестандартных шлюзов.
        cands.add(b);
        return dedup(cands);
    }

    /** Кандидаты endpoint'ов для списка моделей (/models). */
    static List<String> modelListEndpoints(String baseUrl, String apiKey) {
        return modelListEndpoints(baseUrl, apiKey, false);
    }

    static List<String> modelListEndpoints(String baseUrl, String apiKey, boolean forceOpenAI) {
        String rawBase = normalizeBaseUrl(baseUrl);
        String stripped = rawBase
                .replaceAll("(?i)/(v1/)?(chat/)?completions/?$", "")
                .replaceAll("(?i)/messages/?$", "")
                .replaceAll("/+$", "");
        List<String> cands = new ArrayList<String>();
        if (!forceOpenAI && isGoogle(rawBase)) {
            String u = stripped.replaceAll("(?i)/(v1|v1beta)?/?models.*$", "");
            u = u.replaceAll("/+$", "") + "/v1beta/models";
            if (apiKey != null && !apiKey.isEmpty()) u += "?key=" + enc(apiKey);
            cands.add(u);
        } else {
            if (stripped.matches("(?i).*/v\\d+[a-z]*$")) {
                cands.add(stripped + "/models");
            } else {
                cands.add(stripped + "/v1/models");
                cands.add(stripped + "/models");
            }
            if (!rawBase.matches("(?i).*/models/?$")) {
                cands.add(rawBase.replaceAll("/+$", "") + "/models");
            }
        }
        return dedup(cands);
    }

    /**
     * Цепочка прокси. На Android нет ограничения CORS, поэтому основной путь —
     * ПРЯМОЙ запрос (null). Публичные прокси НЕ используются по умолчанию: они
     * медленные и приводили к зависанию при загрузке больших списков. Прокси
     * подключается, только если пользователь явно указал его в поле CORS-прокси.
     */
    static List<String> proxyChain(String userProxy) {
        List<String> chain = new ArrayList<String>();
        chain.add(null); // прямой запрос
        if (userProxy != null && !userProxy.trim().isEmpty()) chain.add(userProxy.trim());
        return chain;
    }

    static String buildProxyUrl(String tmpl, String target) {
        if (tmpl == null) return target;
        if (tmpl.contains("{url_enc}")) return tmpl.replace("{url_enc}", enc(target));
        if (tmpl.contains("{url_raw}")) return tmpl.replace("{url_raw}", target);
        return tmpl + enc(target);
    }

    // ------------------------------------------------------------- callProvider
    /** Разобранный НАТИВНЫЙ tool-вызов (OpenAI function-calling). */
    public static class ToolCall {
        public String id;        // id вызова (нужен для tool_call_id в ответе)
        public String name;      // имя инструмента (function.name)
        public JSONObject args;  // распарсенные аргументы
        public boolean truncated; // arguments оборван по лимиту токенов
        public String rawArgs;   // сырая строка arguments (для спасения)
        public ToolCall(String id, String name, JSONObject args) { this.id = id; this.name = name; this.args = args; }
    }

    /** Результат вызова чата. */
    public static class ChatResult {
        public String text;
        public String realModel;
        /**
         * Нативные tool_calls, если модель их вернула (OpenAI function-calling).
         * Пусто, если модель ответила текстом (тогда Agent парсит tool-блоки из text).
         */
        public java.util.List<ToolCall> toolCalls = new java.util.ArrayList<ToolCall>();
        public ChatResult(String text, String realModel) { this.text = text; this.realModel = realModel; }
    }

    /**
     * Отправка сообщения провайдеру и получение ответа. Порт callProvider() из
     * web-версии (ai.js), но без CORS-прокси-каскада (на Android CORS нет —
     * прямой запрос). Поддерживает OpenAI-совместимый, Anthropic и Google форматы.
     *
     * @param provider  JSON провайдера (baseUrl, apiKey, model, rawUrl, extraHeaders, corsProxy)
     * @param messages  список сообщений: каждый {role: "user"|"assistant"|"system", content: "..."}
     * @param timeoutMs таймаут ожидания ответа
     * ВНИМАНИЕ: блокирующий вызов — только из фонового потока.
     */
    public static ChatResult callProvider(JSONObject provider, JSONArray messages, int timeoutMs) throws Exception {
        return callProvider(provider, messages, timeoutMs, null);
    }

    /** Вариант с отменой «на лету» (см. {@link Canceller}) — для мгновенной остановки. */
    public static ChatResult callProvider(JSONObject provider, JSONArray messages, int timeoutMs, Canceller canceller) throws Exception {
        String baseUrl = normalizeBaseUrl(provider.optString("baseUrl"));
        if (baseUrl.isEmpty()) throw new Exception("Base URL пуст");
        // Картинки отправляются провайдеру как есть. Если выбранная модель не
        // поддерживает vision — НИКАКОГО фонового прогона через другие модели не
        // делаем: ответ провайдера показывается как есть.
        String apiKey = provider.optString("apiKey", "");
        String model = provider.optString("model", "");
        // Лимит длины ответа из настроек (providerMaxTokens). Прокидывается в тело
        // запроса. Если не задан — дефолт 8192. clamp по границам поля настроек.
        int maxTokens = clampMaxTokens(provider.optInt("maxTokens", 8192));
        List<String> chain = proxyChain(provider.optString("corsProxy", ""));
        List<String> errors = new ArrayList<String>();

        // Флаг «OpenAI-compatible»: принудительно использовать OpenAI-формат
        // (/v1/chat/completions, Bearer-токен), даже если URL похож на Google/Anthropic.
        // Нужен для шлюзов-агрегаторов с нестандартными доменами.
        boolean forceOpenAI = provider.optBoolean("openaiCompatible", false);

        // 1) Google Gemini
        if (!forceOpenAI && isGoogle(baseUrl)) {
            String url = baseUrl;
            if (!url.contains("generateContent"))
                url = url.replaceAll("/+$", "") + "/v1beta/models/" + enc(model) + ":generateContent";
            if (!apiKey.isEmpty() && !url.matches("(?s).*[?&]key=.*"))
                url += (url.contains("?") ? "&" : "?") + "key=" + enc(apiKey);
            String body = buildGoogleBody(model, messages, maxTokens);
            for (String tmpl : chain) {
                String finalUrl = tmpl != null ? buildProxyUrl(tmpl, url) : url;
                try {
                    HttpResp resp = postText(finalUrl, provider, baseUrl, body, timeoutMs, canceller);
                    int code = resp.code; String txt = resp.body;
                    if (code < 200 || code >= 300) throw new Exception("response error: " + code + " " + txt);
                    String errMsg = extractError(txt);
                    if (errMsg != null && !errMsg.isEmpty()) throw new Exception("Upstream error: " + errMsg);
                    return new ChatResult(extractText(txt), extractModel(txt, model));
                } catch (Exception e) {
                    if (canceller != null && canceller.isCancelled()) throw new Exception("Остановлено пользователем");
                    errors.add("google: " + e.getMessage());
                }
            }
        }
        // 2) Anthropic
        else if (!forceOpenAI && isAnthropic(baseUrl)) {
            String url = baseUrl;
            if (!url.matches("(?i).*/messages(\\?.*)?$")) url = url.replaceAll("/+$", "") + "/v1/messages";
            String body = buildAnthropicBody(model, messages, maxTokens);
            for (String tmpl : chain) {
                String finalUrl = tmpl != null ? buildProxyUrl(tmpl, url) : url;
                try {
                    HttpResp resp = postText(finalUrl, provider, baseUrl, body, timeoutMs, canceller);
                    int code = resp.code; String txt = resp.body;
                    if (code < 200 || code >= 300) throw new Exception("response error: " + code + " " + txt);
                    String errMsg = extractError(txt);
                    if (errMsg != null && !errMsg.isEmpty()) throw new Exception("Upstream error: " + errMsg);
                    return new ChatResult(extractText(txt), extractModel(txt, model));
                } catch (Exception e) {
                    if (canceller != null && canceller.isCancelled()) throw new Exception("Остановлено пользователем");
                    errors.add("anthropic: " + e.getMessage());
                }
            }
        }
        // 3) OpenAI-совместимый (перебор кандидатов endpoint'ов)
        else {
            List<String> endpoints = candidateEndpoints(baseUrl, provider.optBoolean("rawUrl", false));
            boolean antiLoop = provider.optBoolean("_antiLoop", false);
            // NATIVE TOOLS: по умолчанию отдаём модели OpenAI function-calling
            // (tools/tool_choice:auto/parallel_tool_calls). Это критично для моделей,
            // обученных СТРОГО на native tool-calling (gpt-oss-*, cohere/command-r,
            // и др.): в legacy markdown-режиме они бесконечно «обещают выполнить» и
            // ничего не вызывают. Порт NATIVE TOOLS MODE из web-версии (ai.js).
            // Флаг disableNativeTools в провайдере отключает режим принудительно.
            boolean wantNative = !provider.optBoolean("disableNativeTools", false);
            boolean useTools = wantNative;
            boolean minimalBody = false; // повышаем при первом extra_forbidden
            String authoritativeError = null;   // ответ не 404/405 → показываем как есть
            for (String url : endpoints) {
                for (String tmpl : chain) {
                    String finalUrl = tmpl != null ? buildProxyUrl(tmpl, url) : url;
                    // До 3 попыток на endpoint: (1) с tools, (2) без tools (откат),
                    // (3) минимальное тело (после extra_forbidden).
                    for (int attempt = 0; attempt < 3; attempt++) {
                        boolean currentUseTools = useTools;
                        try {
                            String body = buildOpenAIBody(model, messages, maxTokens, antiLoop, currentUseTools, minimalBody);
                            HttpResp resp = postText(finalUrl, provider, baseUrl, body, timeoutMs, canceller);
                            int code = resp.code; String txt = resp.body;
                            if (code >= 200 && code < 300) {
                                String out = extractText(txt);
                                java.util.List<ToolCall> tcs = extractToolCalls(txt);
                                if ((out != null && !out.isEmpty()) || !tcs.isEmpty()) {
                                    ChatResult cr = new ChatResult(out == null ? "" : out, extractModel(txt, model));
                                    cr.toolCalls = tcs;
                                    return cr;
                                }
                                // HTTP 200, но тело с ошибкой (прокси-агрегаторы: Nvidia/g4f
                                // отдают {"error":...} со статусом 200).
                                String errMsg = extractError(txt);
                                if (errMsg != null && !errMsg.isEmpty()) {
                                    authoritativeError = "Upstream error: " + errMsg;
                                    break;
                                }
                                // HTTP 200 без текста, без tool_calls и без error-поля.
                                authoritativeError = describeEmpty(txt, finalUrl);
                                break;
                            }
                            errors.add(finalUrl + " -> HTTP " + code + " " + firstN(txt, 200));
                            // Провайдер отверг ЛИШНЕЕ поле (top_p/penalty/reasoning) —
                            // повторяем «минимальным» телом (базовые поля + tools).
                            if (!minimalBody && (code == 400 || code == 422)
                                    && !looksLikeToolsUnsupported(txt) && looksLikeExtraForbidden(txt)) {
                                minimalBody = true;
                                continue; // тот же endpoint, тот же useTools, минимальное тело
                            }
                            // Провайдер НЕ поддерживает native tools → откат в markdown-режим.
                            if (currentUseTools && (code == 400 || code == 422 || code == 404)
                                    && looksLikeToolsUnsupported(txt)) {
                                useTools = false;
                                continue; // повторяем этот же endpoint без tools
                            }
                            // Авторитетная ошибка (не 404/405) — показываем как есть.
                            if (code != 404 && code != 405) {
                                authoritativeError = "HTTP " + code + ": " + txt;
                            }
                            break;
                        } catch (Exception e) {
                            if (canceller != null && canceller.isCancelled()) throw new Exception("Остановлено пользователем");
                            errors.add(finalUrl + " -> " + e.getMessage());
                            break;
                        }
                    }
                    if (authoritativeError != null) break;
                }
                if (authoritativeError != null) throw new Exception(authoritativeError);
            }
        }
        StringBuilder sb = new StringBuilder("Запрос не удался:\n");
        for (int i = 0; i < Math.min(6, errors.size()); i++) sb.append(errors.get(i)).append("\n");
        throw new Exception(sb.toString());
    }

    // ---- тела запросов ----
    /** Ограничивает max_tokens диапазоном поля настроек (256..32768). */
    static int clampMaxTokens(int v) {
        if (v < 256) return 256;
        if (v > 32768) return 32768;
        return v;
    }

    static String buildOpenAIBody(String model, JSONArray messages, int maxTokens) throws JSONException {
        return buildOpenAIBody(model, messages, maxTokens, false, false, false);
    }

    static String buildOpenAIBody(String model, JSONArray messages, int maxTokens, boolean antiLoop) throws JSONException {
        return buildOpenAIBody(model, messages, maxTokens, antiLoop, false, false);
    }

    /**
     * @param antiLoop если true — усиленный анти-циклический сэмплинг (T=1.0,
     *   высокие штрафы). Применяется на повторе, когда модель залипла и выдаёт
     *   один и тот же текст/вызов (порт antiLoop из web-версии ai.js).
     * @param useTools если true — добавляем native OpenAI function-calling
     *   (tools/tool_choice:auto/parallel_tool_calls). Модели, обученные на native
     *   tool-calling, в этом режиме СРАЗУ вызывают инструменты, а не «болтают».
     * @param minimalBody если true — «безопасный минимум» (только базовые поля +
     *   опц. tools) после того как провайдер отверг доп. поле (extra_forbidden).
     */
    static String buildOpenAIBody(String model, JSONArray messages, int maxTokens,
                                  boolean antiLoop, boolean useTools, boolean minimalBody) throws JSONException {
        JSONObject b = new JSONObject();
        b.put("model", model);
        b.put("messages", normalizeMessages(messages, useTools));
        b.put("temperature", antiLoop ? 1.0 : 0.7);
        b.put("stream", false);
        b.put("max_tokens", maxTokens);
        if (useTools) {
            JSONArray spec = buildOpenAIToolsSpec();
            if (spec.length() > 0) {
                b.put("tools", spec);
                b.put("tool_choice", "auto");
                b.put("parallel_tool_calls", true);
            }
        }
        // ВАЖНО (native tools): когда отдаём native function-calling — НЕ добавляем
        // штрафы за повтор (порт из web-версии: чистый native-запрос
        // model+messages+tools+tool_choice:auto). top_p/penalty применяем только в
        // LEGACY markdown-режиме (useTools=false) или при antiLoop (ломает залипание).
        // minimalBody=true — вообще без доп. полей (провайдер отверг extra-поле).
        if (!minimalBody && (!useTools || antiLoop)) {
            b.put("top_p", antiLoop ? 0.95 : 1.0);
            b.put("frequency_penalty", antiLoop ? 1.5 : 0.5);
            b.put("presence_penalty", antiLoop ? 1.0 : 0.3);
        }
        return b.toString();
    }

    /** Признак ответа провайдера, что native tools не поддерживаются → откат. */
    static boolean looksLikeToolsUnsupported(String txt) {
        if (txt == null) return false;
        String s = txt.toLowerCase(Locale.US);
        if (s.contains("tool_choice") || s.contains("parallel_tool_calls")) return true;
        if (s.contains("function calling") || s.contains("does not support tool")) return true;
        if (s.contains("unsupported parameter") || s.contains("unknown field")
                || s.contains("unknown parameter") || s.contains("no route")) return true;
        // "tools ... unsupport/unknown/not allowed/not supported/invalid"
        if (s.matches("(?s).*\\btools?\\b.*(unsupport|unknown|not allowed|not supported|invalid).*")) return true;
        return false;
    }

    /** Признак «провайдер отверг лишнее поле» (top_p/penalty/reasoning). */
    static boolean looksLikeExtraForbidden(String txt) {
        if (txt == null) return false;
        String s = txt.toLowerCase(Locale.US);
        return s.contains("extra_forbidden") || s.contains("extra inputs are not permitted")
                || s.contains("additional properties")
                || s.matches("(?s).*unrecognized (key|field|parameter).*")
                || s.contains("top_p") || s.contains("frequency_penalty") || s.contains("presence_penalty");
    }

    // ------------------------------------------------------------- tools spec
    /**
     * Строит OpenAI-схему function-tools (порт buildOpenAIToolsSpec() из web-версии).
     * Описание инструментов синхронизировано с FsTool (что реально исполняется) и
     * system_prompt.txt. Тип аргумента угадываем по описанию; additionalProperties:true
     * — permissive, чтобы модель могла прислать чуть больше полей.
     */
    static JSONArray buildOpenAIToolsSpec() {
        JSONArray out = new JSONArray();
        try {
            for (String[] t : TOOL_SPECS) {
                String name = t[0];
                String desc = t[1];
                JSONObject properties = new JSONObject();
                JSONArray required = new JSONArray();
                // Параметры: пары "имя|описание" через ';' начиная с t[2].
                for (int i = 2; i < t.length; i++) {
                    String[] kv = t[i].split("\\|", 2);
                    String key = kv[0];
                    String pdesc = kv.length > 1 ? kv[1] : "";
                    String low = pdesc.toLowerCase(Locale.US);
                    String type = "string";
                    if (low.matches("(?s).*(number|\\bint\\b|1-based|count|limit|offset|depth|size|width|height|порог|номер|сколько|размер|таймаут).*")) type = "number";
                    else if (low.matches("(?s).*(boolean|true/false|флаг|включ).*")) type = "boolean";
                    else if (low.matches("(?s).*(array|список|массив).*") && !low.matches("(?s).*строк(а|у).*")) type = "array";
                    JSONObject prop = new JSONObject();
                    prop.put("description", pdesc);
                    if ("array".equals(type)) { prop.put("type", "array"); prop.put("items", new JSONObject().put("type", "string")); }
                    else prop.put("type", type);
                    properties.put(key, prop);
                    if (!low.contains("optional")) required.put(key);
                }
                JSONObject fn = new JSONObject()
                        .put("name", name)
                        .put("description", desc)
                        .put("parameters", new JSONObject()
                                .put("type", "object")
                                .put("properties", properties)
                                .put("required", required)
                                .put("additionalProperties", true));
                out.put(new JSONObject().put("type", "function").put("function", fn));
            }
        } catch (JSONException ignored) {}
        return out;
    }

    /**
     * Описание инструментов для native function-calling. Каждый элемент:
     * { name, description, "param|описание", ... }. Соответствует FsTool.execute
     * и system_prompt.txt.
     */
    private static final String[][] TOOL_SPECS = {
        {"fs_read", "Прочитать содержимое файла как текст. Поддерживает пагинацию offset/limit.",
            "path|string, абсолютный путь к файлу",
            "offset|number optional, 1-based номер строки",
            "limit|number optional, сколько строк вернуть"},
        {"fs_read_image", "Прочитать изображение из проводника проекта и увидеть его (vision).",
            "path|string, путь к файлу-картинке"},
        {"fs_write", "Создать или полностью перезаписать файл.",
            "path|string, путь к файлу",
            "content|string, новое содержимое файла"},
        {"fs_append", "Дописать текст в конец файла.",
            "path|string, путь к файлу",
            "content|string, что дописать"},
        {"fs_replace", "Точечная замена в файле: {search,replace,[regex]} ИЛИ {startLine,endLine,content}.",
            "path|string, путь к файлу",
            "search|string optional, что искать",
            "replace|string optional, на что заменить",
            "regex|boolean optional, трактовать search как регулярное выражение",
            "startLine|number optional, 1-based номер первой заменяемой строки",
            "endLine|number optional, 1-based номер последней заменяемой строки",
            "content|string optional, новые строки для диапазона"},
        {"fs_list", "Список файлов и папок в каталоге.",
            "path|string, путь к папке"},
        {"fs_mkdir", "Создать папку.",
            "path|string, путь новой папки"},
        {"fs_delete", "Удалить файл или папку.",
            "path|string, путь к удаляемому объекту"},
        {"fs_rename", "Переименовать или переместить файл/папку.",
            "from|string, исходный путь",
            "to|string, новый путь"},
        {"fs_search", "Поиск по содержимому файлов.",
            "query|string, что искать",
            "path|string optional, где искать (по умолчанию весь проект)",
            "regex|boolean optional, трактовать query как регулярное выражение"},
        {"archive_create", "Создать zip-архив: {source,archive} ИЛИ {paths:[...],archive}.",
            "archive|string, путь к создаваемому .zip",
            "source|string optional, папка/файл для упаковки",
            "paths|array optional, список путей для упаковки"},
        {"archive_extract", "Распаковать zip-архив в папку.",
            "archive|string, путь к .zip",
            "to|string, папка назначения"},
        {"archive_list", "Посмотреть содержимое zip без распаковки.",
            "archive|string, путь к .zip"},
        {"archive_read_entry", "Прочитать один файл внутри zip без полной распаковки.",
            "archive|string, путь к .zip",
            "entry|string, имя файла внутри архива",
            "as|string optional, режим (text/base64)",
            "max_bytes|number optional, лимит байт"},
        {"web_search", "Поиск в интернете сразу по многим поисковикам.",
            "query|string, поисковый запрос",
            "limit|number optional, сколько результатов",
            "sources|array optional, список источников"},
        {"http_fetch", "Загрузить содержимое URL (страница, JSON, картинка).",
            "url|string, адрес",
            "method|string optional, HTTP-метод",
            "headers|object optional, доп. заголовки",
            "body|string optional, тело запроса",
            "force_text|boolean optional, всегда возвращать как текст"},
    };

    // ------------------------------------------------------------- tool_calls parse
    /**
     * Извлекает нативные OpenAI tool_calls из ответа (choices[0].message.tool_calls).
     * Порт extractOpenAIToolCalls() из web-версии, включая спасение аргументов из
     * ОБОРВАННОГО JSON (когда ответ упёрся в max_tokens).
     */
    static java.util.List<ToolCall> extractToolCalls(String rawJson) {
        java.util.List<ToolCall> out = new java.util.ArrayList<ToolCall>();
        if (rawJson == null) return out;
        try {
            JSONObject j = new JSONObject(rawJson);
            JSONArray choices = j.optJSONArray("choices");
            if (choices == null || choices.length() == 0) return out;
            JSONObject ch = choices.optJSONObject(0);
            if (ch == null) return out;
            String finishReason = ch.optString("finish_reason", ch.optString("finishReason", ""));
            JSONObject msg = ch.optJSONObject("message");
            if (msg == null) return out;
            JSONArray tc = msg.optJSONArray("tool_calls");
            if (tc == null || tc.length() == 0) return out;
            for (int i = 0; i < tc.length(); i++) {
                JSONObject t = tc.optJSONObject(i);
                if (t == null) continue;
                if (!"function".equals(t.optString("type", "function"))) continue;
                JSONObject fn = t.optJSONObject("function");
                if (fn == null) continue;
                String name = fn.optString("name", "");
                if (name.isEmpty()) continue;
                JSONObject args = new JSONObject();
                boolean truncated = false;
                Object rawArgsObj = fn.opt("arguments");
                String rawArgs = null;
                if (rawArgsObj instanceof JSONObject) {
                    args = (JSONObject) rawArgsObj;   // некоторые модели шлют объект, а не строку
                } else {
                    rawArgs = rawArgsObj == null ? "" : String.valueOf(rawArgsObj);
                    if (!rawArgs.isEmpty()) {
                        try { args = new JSONObject(rawArgs); }
                        catch (JSONException e) {
                            JSONObject salv = salvagePartialArgs(rawArgs);
                            if (salv != null) { args = salv; truncated = true; }
                        }
                    }
                }
                // Пустые args + finish_reason=length → обрыв (а не осознанный пустой вызов).
                if ("length".equals(finishReason) && args.length() == 0) truncated = true;
                ToolCall call = new ToolCall(
                        t.optString("id", "call_" + Integer.toHexString((int) (Math.random() * 0x7fffffff))),
                        name, args);
                call.truncated = truncated;
                call.rawArgs = rawArgs;
                out.add(call);
            }
        } catch (JSONException ignored) {}
        return out;
    }

    /**
     * Спасение аргументов из ОБОРВАННОГО JSON tool-вызова (порт salvagePartialArgs
     * из web). Когда ответ упирается в max_tokens, строка arguments обрывается на
     * середине — JSON.parse падает. Пытаемся вытащить хотя бы path и максимум content.
     */
    static JSONObject salvagePartialArgs(String rawStr) {
        if (rawStr == null || rawStr.isEmpty()) return null;
        JSONObject res = new JSONObject();
        try {
            Matcher mp = Pattern.compile("\"(?:path|filepath|file_path|filename|file)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(rawStr);
            if (mp.find()) {
                String p = mp.group(1);
                try { res.put("path", new JSONObject("{\"v\":\"" + p + "\"}").optString("v")); }
                catch (JSONException e) { res.put("path", p); }
            }
            Matcher mc = Pattern.compile("\"(?:content|text|data|body|code|contents|value)\"\\s*:\\s*\"").matcher(rawStr);
            if (mc.find()) {
                String tail = rawStr.substring(mc.end());
                int end = -1; boolean esc = false;
                for (int i = 0; i < tail.length(); i++) {
                    char c = tail.charAt(i);
                    if (esc) { esc = false; continue; }
                    if (c == '\\') { esc = true; continue; }
                    if (c == '"') { end = i; break; }
                }
                String bodyStr = end >= 0 ? tail.substring(0, end) : tail;
                bodyStr = bodyStr.replaceAll("\\\\+$", "");
                String decoded;
                try { decoded = new JSONObject("{\"v\":\"" + bodyStr + "\"}").optString("v"); }
                catch (JSONException e) {
                    decoded = bodyStr.replace("\\n", "\n").replace("\\t", "\t").replace("\\\"", "\"").replace("\\\\", "\\");
                }
                res.put("content", decoded);
            }
        } catch (JSONException ignored) {}
        return (res.has("path") || res.has("content")) ? res : null;
    }

    /**
     * Нормализует сообщения перед отправкой: если у user-сообщения есть служебное
     * поле _images — превращает его в multi-part content ([{type:text},{type:image_url}...]),
     * чтобы модель реально «видела» прикреплённые картинки (порт логики web-версии).
     * Служебные поля (_images, realModel, silent, interject) удаляются из копии.
     */
    static JSONArray normalizeMessages(JSONArray messages) throws JSONException {
        return normalizeMessages(messages, false);
    }

    /**
     * @param useTools если true — разворачиваем native формат:
     *   assistant с полем _toolCalls → {role:assistant, tool_calls:[...]},
     *   сообщения {role:tool, _toolCallId} → {role:tool, tool_call_id, content}.
     *   Если false — эти служебные сообщения уже сконвертированы в user-текст
     *   самим Agent'ом (buildLLMMessages), сюда доходить не должны.
     */
    static JSONArray normalizeMessages(JSONArray messages, boolean useTools) throws JSONException {
        JSONArray out = new JSONArray();
        for (int i = 0; i < messages.length(); i++) {
            JSONObject m = messages.optJSONObject(i);
            if (m == null) continue;
            String role = m.optString("role");
            // Native assistant с tool_calls.
            if (useTools && "assistant".equals(role) && m.optJSONArray("_toolCalls") != null
                    && m.optJSONArray("_toolCalls").length() > 0) {
                JSONArray src = m.optJSONArray("_toolCalls");
                JSONArray tcs = new JSONArray();
                for (int k = 0; k < src.length(); k++) {
                    JSONObject tc = src.optJSONObject(k);
                    if (tc == null) continue;
                    Object a = tc.opt("args");
                    String argStr = (a instanceof String) ? (String) a
                            : (a == null ? "{}" : a.toString());
                    tcs.put(new JSONObject()
                            .put("id", tc.optString("id"))
                            .put("type", "function")
                            .put("function", new JSONObject()
                                    .put("name", tc.optString("name"))
                                    .put("arguments", argStr)));
                }
                JSONObject asst = new JSONObject().put("role", "assistant");
                String c = m.optString("content", "");
                asst.put("content", c.isEmpty() ? JSONObject.NULL : c);
                asst.put("tool_calls", tcs);
                out.put(asst);
                continue;
            }
            // Native tool-result сообщение.
            if (useTools && "tool".equals(role) && !m.optString("_toolCallId", "").isEmpty()) {
                out.put(new JSONObject().put("role", "tool")
                        .put("tool_call_id", m.optString("_toolCallId"))
                        .put("content", m.optString("content", "")));
                continue;
            }
            // LEGACY (useTools=false): tool-сообщения превращаем в user-текст,
            // а у assistant с пустым content подставляем безопасную заглушку,
            // чтобы провайдер не отверг «assistant без content и tool_calls».
            if (!useTools && "tool".equals(role)) {
                out.put(new JSONObject().put("role", "user")
                        .put("content", "Результат инструмента " + m.optString("name", "")
                                + ":\n" + m.optString("content", "")));
                continue;
            }
            if (!useTools && "assistant".equals(role)) {
                String c = m.optString("content", "");
                if (c.trim().isEmpty()) {
                    JSONArray tcs = m.optJSONArray("_toolCalls");
                    if (tcs != null && tcs.length() > 0) {
                        StringBuilder sb = new StringBuilder();
                        for (int k = 0; k < tcs.length(); k++) {
                            JSONObject tc = tcs.optJSONObject(k);
                            if (tc == null) continue;
                            Object a = tc.opt("args");
                            String argStr = (a instanceof String) ? (String) a : (a == null ? "{}" : a.toString());
                            sb.append("```tool\n{\"tool\":\"").append(tc.optString("name"))
                              .append("\",\"args\":").append(argStr).append("}\n```\n");
                        }
                        c = sb.toString();
                    } else c = "(продолжаю)";
                }
                out.put(new JSONObject().put("role", "assistant").put("content", c));
                continue;
            }
            JSONObject clean = new JSONObject();
            clean.put("role", role);
            JSONArray images = m.optJSONArray("_images");
            String content = m.optString("content", "");
            if (images != null && images.length() > 0) {
                JSONArray parts = new JSONArray();
                if (!content.isEmpty())
                    parts.put(new JSONObject().put("type", "text").put("text", content));
                for (int j = 0; j < images.length(); j++) {
                    JSONObject im = images.optJSONObject(j);
                    if (im == null) continue;
                    String url = im.optString("dataUrl", "");
                    if (url.isEmpty()) continue;
                    parts.put(new JSONObject().put("type", "image_url")
                            .put("image_url", new JSONObject().put("url", url)));
                }
                clean.put("content", parts);
            } else {
                clean.put("content", content);
            }
            out.put(clean);
        }
        return out;
    }

    static String buildAnthropicBody(String model, JSONArray messages, int maxTokens) throws JSONException {
        // Anthropic: system отдельно, остальные в messages.
        JSONObject b = new JSONObject();
        b.put("model", model);
        b.put("max_tokens", maxTokens);
        StringBuilder sys = new StringBuilder();
        JSONArray msgs = new JSONArray();
        for (int i = 0; i < messages.length(); i++) {
            JSONObject m = messages.optJSONObject(i);
            if (m == null) continue;
            String role = m.optString("role");
            if ("system".equals(role)) {
                if (sys.length() > 0) sys.append("\n\n");
                sys.append(m.optString("content"));
            } else {
                // Vision: если у сообщения есть _images — собираем multi-part content
                // в формате Anthropic ([{type:text},{type:image,source:{base64}}...]),
                // чтобы модель реально «видела» картинки (из архива/проводника).
                JSONArray images = m.optJSONArray("_images");
                String content = m.optString("content", "");
                if (images != null && images.length() > 0) {
                    JSONArray parts = new JSONArray();
                    if (!content.isEmpty())
                        parts.put(new JSONObject().put("type", "text").put("text", content));
                    for (int j = 0; j < images.length(); j++) {
                        JSONObject im = images.optJSONObject(j);
                        if (im == null) continue;
                        String[] mm = splitDataUrl(im.optString("dataUrl", ""));
                        if (mm == null) continue;
                        parts.put(new JSONObject().put("type", "image").put("source",
                                new JSONObject().put("type", "base64")
                                        .put("media_type", mm[0]).put("data", mm[1])));
                    }
                    msgs.put(new JSONObject().put("role", role).put("content", parts));
                } else {
                    msgs.put(new JSONObject().put("role", role).put("content", content));
                }
            }
        }
        if (sys.length() > 0) b.put("system", sys.toString());
        b.put("messages", msgs);
        return b.toString();
    }

    static String buildGoogleBody(String model, JSONArray messages, int maxTokens) throws JSONException {
        JSONObject b = new JSONObject();
        JSONArray contents = new JSONArray();
        StringBuilder sys = new StringBuilder();
        for (int i = 0; i < messages.length(); i++) {
            JSONObject m = messages.optJSONObject(i);
            if (m == null) continue;
            String role = m.optString("role");
            String content = m.optString("content");
            if ("system".equals(role)) { if (sys.length() > 0) sys.append("\n\n"); sys.append(content); continue; }
            String grole = "assistant".equals(role) ? "model" : "user";
            // Vision: _images → inline_data parts (формат Google Gemini),
            // чтобы модель реально видела картинки из архива/проводника.
            JSONArray images = m.optJSONArray("_images");
            JSONArray parts = new JSONArray();
            if (!content.isEmpty())
                parts.put(new JSONObject().put("text", content));
            if (images != null && images.length() > 0) {
                for (int j = 0; j < images.length(); j++) {
                    JSONObject im = images.optJSONObject(j);
                    if (im == null) continue;
                    String[] mm = splitDataUrl(im.optString("dataUrl", ""));
                    if (mm == null) continue;
                    parts.put(new JSONObject().put("inline_data",
                            new JSONObject().put("mime_type", mm[0]).put("data", mm[1])));
                }
            }
            if (parts.length() == 0) parts.put(new JSONObject().put("text", ""));
            contents.put(new JSONObject().put("role", grole).put("parts", parts));
        }
        b.put("contents", contents);
        if (sys.length() > 0)
            b.put("systemInstruction", new JSONObject().put("parts",
                    new JSONArray().put(new JSONObject().put("text", sys.toString()))));
        b.put("generationConfig", new JSONObject().put("maxOutputTokens", maxTokens));
        return b.toString();
    }

    /**
     * Формирует понятное сообщение, когда endpoint вернул HTTP 200, но без
     * видимого текста. Разбирает тело, чтобы указать вероятную причину.
     */
    static String describeEmpty(String rawJson, String url) {
        String reason = "модель вернула пустой ответ";
        try {
            JSONObject j = new JSONObject(rawJson);
            JSONArray choices = j.optJSONArray("choices");
            if (choices == null || choices.length() == 0) {
                reason = "сервер вернул ответ без choices (пустой результат модели)";
            } else {
                JSONObject ch = choices.optJSONObject(0);
                String fin = ch != null ? ch.optString("finish_reason", ch.optString("finishReason", "")) : "";
                JSONObject msg = ch != null ? ch.optJSONObject("message") : null;
                boolean hasToolCalls = msg != null && msg.optJSONArray("tool_calls") != null
                        && msg.optJSONArray("tool_calls").length() > 0;
                if ("length".equals(fin)) {
                    reason = "превышен лимит токенов (finish_reason=length) — увеличьте «Максимум токенов» в настройках";
                } else if (hasToolCalls) {
                    reason = "модель вернула только вызов инструмента без текста";
                } else if (msg != null && (msg.opt("reasoning") != null || !msg.optString("reasoning_content", "").isEmpty())) {
                    reason = "весь вывод модели ушёл в reasoning, видимого текста нет";
                } else {
                    reason = "модель вернула пустой content (finish_reason=" + (fin.isEmpty() ? "?" : fin) + ")";
                }
            }
        } catch (JSONException ignored) {}
        return "Пустой ответ от " + url + ": " + reason
                + ". Endpoint отвечает корректно — проверьте модель, лимит токенов или повторите запрос.";
    }

    // ---- разбор ответа ----
    /**
     * Достаёт reasoning-текст из message. Reasoning-модели кладут его в разные поля:
     *   - message.reasoning (строка)                — cohere/north, o-series
     *   - message.reasoning (объект {text|content}) — некоторые прокси-обёртки
     *   - message.reasoning_content (строка)        — sarvam-105b, deepseek-r1
     * Без этого при content=null клиент считал ответ пустым, шёл дальше по запасным
     * endpoint-кандидатам и ловил там 404 — пользователь видел загадочную «404
     * not_found» / «пустой ответ» вместо реального ответа модели.
     */
    static String extractReasoning(JSONObject msg) {
        if (msg == null) return "";
        Object r = msg.opt("reasoning");
        if (r instanceof String && !((String) r).isEmpty()) return (String) r;
        if (r instanceof JSONObject) {
            JSONObject ro = (JSONObject) r;
            String rt = ro.optString("text", ro.optString("content", ""));
            if (!rt.isEmpty()) return rt;
        }
        String rc = msg.optString("reasoning_content", "");
        if (!rc.isEmpty()) return rc;
        return "";
    }

    /** Извлекает текст ответа из OpenAI/Anthropic/Google JSON. */
    static String extractText(String rawJson) {
        try {
            JSONObject j = new JSONObject(rawJson);
            // OpenAI: choices[0].message.content
            JSONArray choices = j.optJSONArray("choices");
            if (choices != null && choices.length() > 0) {
                JSONObject ch = choices.optJSONObject(0);
                if (ch != null) {
                    JSONObject msg = ch.optJSONObject("message");
                    if (msg != null) {
                        Object content = msg.opt("content");
                        if (content instanceof String && !((String) content).isEmpty()) return (String) content;
                        // content может быть массивом частей
                        if (content instanceof JSONArray) {
                            StringBuilder sb = new StringBuilder();
                            JSONArray parts = (JSONArray) content;
                            for (int i = 0; i < parts.length(); i++) {
                                JSONObject p = parts.optJSONObject(i);
                                if (p != null) sb.append(p.optString("text", ""));
                            }
                            if (sb.length() > 0) return sb.toString();
                        }
                    }
                    String txt = ch.optString("text", "");
                    if (!txt.isEmpty()) return txt;
                    // Reasoning-модели (deepseek-r1, o-series, cohere/north): content
                    // часто null, а весь текст в message.reasoning — используем как fallback.
                    if (msg != null) {
                        String reason = extractReasoning(msg);
                        if (!reason.isEmpty()) return reason;
                    }
                }
            }
            // Anthropic: content[].text
            JSONArray content = j.optJSONArray("content");
            if (content != null && content.length() > 0) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < content.length(); i++) {
                    JSONObject p = content.optJSONObject(i);
                    if (p != null) sb.append(p.optString("text", ""));
                }
                if (sb.length() > 0) return sb.toString();
            }
            // Google: candidates[0].content.parts[].text
            JSONArray cands = j.optJSONArray("candidates");
            if (cands != null && cands.length() > 0) {
                JSONObject cand = cands.optJSONObject(0);
                if (cand != null) {
                    JSONObject cont = cand.optJSONObject("content");
                    if (cont != null) {
                        JSONArray parts = cont.optJSONArray("parts");
                        if (parts != null) {
                            StringBuilder sb = new StringBuilder();
                            for (int i = 0; i < parts.length(); i++) {
                                JSONObject p = parts.optJSONObject(i);
                                if (p != null) sb.append(p.optString("text", ""));
                            }
                            if (sb.length() > 0) return sb.toString();
                        }
                    }
                }
            }
            // Ollama-style / прочие плоские поля
            String resp = j.optString("response", "");
            if (!resp.isEmpty()) return resp;
            String outText = j.optString("output_text", "");
            if (!outText.isEmpty()) return outText;
            String txt = j.optString("text", "");
            if (!txt.isEmpty()) return txt;
            String mc = j.optString("message", "");
            if (!mc.isEmpty()) return mc;
            // Глубокий поиск первой осмысленной строки content/text/output/answer
            // (порт deep-scan из web extractText) — для нестандартных провайдеров.
            if (j.opt("error") == null) {
                String deep = deepScanText(j);
                if (deep != null && !deep.isEmpty()) return deep;
            }
        } catch (JSONException ignored) {}
        // ВАЖНО: тело с ошибкой (json.error) НЕ превращаем в «текст ответа» —
        // возвращаем пусто, чтобы вызывающий код увидел ошибку и (при rate-limit)
        // выполнил авто-повтор. См. extractError() и обработку в callProvider().
        return "";
    }

    /** Рекурсивный поиск первой длинной строки в полях content/text/output/answer. */
    private static String deepScanText(Object v) {
        if (v instanceof JSONObject) {
            JSONObject o = (JSONObject) v;
            String[] keys = {"content", "text", "output", "answer"};
            for (String k : keys) {
                Object val = o.opt(k);
                if (val instanceof String && ((String) val).length() > 3) return (String) val;
            }
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String r = deepScanText(o.opt(it.next()));
                if (r != null && !r.isEmpty()) return r;
            }
        } else if (v instanceof JSONArray) {
            JSONArray a = (JSONArray) v;
            for (int i = 0; i < a.length(); i++) {
                String r = deepScanText(a.opt(i));
                if (r != null && !r.isEmpty()) return r;
            }
        }
        return null;
    }

    /**
     * Достаёт сообщение об ошибке из тела ответа, даже если HTTP-код был 200.
     * Некоторые прокси-провайдеры (агрегаторы Nvidia/g4f и т.п.) отдают
     * {"error": {...}} или {"error": "..."} со статусом 200 — такое тело НЕ
     * является валидным ответом модели и должно трактоваться как ошибка
     * (в т.ч. распознаваться как rate-limit для авто-повтора).
     * Возвращает текст ошибки или null, если ошибки нет.
     */
    static String extractError(String rawJson) {
        if (rawJson == null) return null;
        try {
            JSONObject j = new JSONObject(rawJson);
            Object err = j.opt("error");
            if (err instanceof JSONObject) {
                JSONObject eo = (JSONObject) err;
                String m = eo.optString("message", "");
                if (m.isEmpty()) m = eo.optString("detail", eo.optString("reason", ""));
                if (m.isEmpty()) m = eo.toString();
                return m;
            }
            if (err instanceof String && !((String) err).isEmpty()) return (String) err;
            // Некоторые прокси кладут ошибку в поле detail/message на верхнем уровне.
            String detail = j.optString("detail", "");
            if (!detail.isEmpty() && j.optJSONArray("choices") == null
                    && j.optJSONArray("content") == null && j.optJSONArray("candidates") == null) {
                return detail;
            }
        } catch (JSONException ignored) {}
        return null;
    }

    static String extractModel(String rawJson, String fallback) {
        try {
            JSONObject j = new JSONObject(rawJson);
            String m = j.optString("model", "");
            if (!m.isEmpty()) return m;
            String mv = j.optString("modelVersion", "");
            if (!mv.isEmpty()) return mv;
        } catch (JSONException ignored) {}
        return fallback;
    }

    private static String firstN(String s, int n) {
        if (s == null) return "";
        s = s.replace('\n', ' ');
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }

    // ------------------------------------------------------------- listModels
    public static List<Model> listModels(JSONObject provider) throws Exception {
        String rawBase = normalizeBaseUrl(provider.optString("baseUrl"));
        if (rawBase.isEmpty()) throw new Exception("Base URL пуст");
        String apiKey = provider.optString("apiKey", "");
        boolean forceOpenAI = provider.optBoolean("openaiCompatible", false);
        List<String> targets = modelListEndpoints(rawBase, apiKey, forceOpenAI);
        List<String> chain = proxyChain(provider.optString("corsProxy", ""));

        List<String> errors = new ArrayList<String>();
        for (String target : targets) {
            for (String tmpl : chain) {
                String finalUrl = tmpl != null ? buildProxyUrl(tmpl, target) : target;
                try {
                    HttpURLConnection c = openGet(finalUrl, provider, rawBase);
                    int code = c.getResponseCode();
                    if (code < 200 || code >= 300) { errors.add(finalUrl + " -> HTTP " + code); c.disconnect(); continue; }
                    String txt = readAll(c.getInputStream());
                    c.disconnect();
                    List<Model> models = parseModels(txt);
                    if (models.isEmpty()) { errors.add(finalUrl + " -> ответ без моделей"); continue; }
                    return models;
                } catch (Exception e) {
                    errors.add(finalUrl + " -> " + e.getMessage());
                }
            }
        }
        StringBuilder sb = new StringBuilder("Не удалось получить список моделей:\n");
        for (int i = 0; i < Math.min(6, errors.size()); i++) sb.append(errors.get(i)).append("\n");
        throw new Exception(sb.toString());
    }

    private static List<Model> parseModels(String txt) {
        List<Model> out = new ArrayList<Model>();
        try {
            Object parsed = new org.json.JSONTokener(txt).nextValue();
            JSONArray data = null;
            if (parsed instanceof JSONObject) {
                JSONObject j = (JSONObject) parsed;
                if (j.optJSONArray("data") != null) data = j.optJSONArray("data");
                else if (j.optJSONArray("models") != null) {
                    JSONArray m = j.optJSONArray("models");
                    for (int i = 0; i < m.length(); i++) {
                        JSONObject o = m.optJSONObject(i);
                        String id = o.optString("name", o.optString("id", "")).replaceAll("^models/", "");
                        if (!id.isEmpty()) out.add(new Model(id, o.optString("displayName", id), o));
                    }
                    return out;
                }
            } else if (parsed instanceof JSONArray) {
                data = (JSONArray) parsed;
            }
            if (data != null) {
                for (int i = 0; i < data.length(); i++) {
                    Object it = data.opt(i);
                    if (it instanceof String) { out.add(new Model((String) it, (String) it, null)); }
                    else if (it instanceof JSONObject) {
                        JSONObject o = (JSONObject) it;
                        String id = o.optString("id", o.optString("name", ""));
                        if (!id.isEmpty()) out.add(new Model(id, o.optString("display_name", o.optString("name", id)), o));
                    }
                }
            }
        } catch (JSONException ignored) {}
        return out;
    }

    // ------------------------------------------------------------- probeModel
    public static Probe probeModel(JSONObject provider, String modelId, int timeoutMs) {
        String baseUrl = normalizeBaseUrl(provider.optString("baseUrl"));
        if (baseUrl.isEmpty()) return new Probe(null, 0, "Base URL пуст");
        List<String> chain = proxyChain(provider.optString("corsProxy", ""));

        boolean forceOpenAI = provider.optBoolean("openaiCompatible", false);
        List<String> targets;
        String body;
        try {
            if (!forceOpenAI && isGoogle(baseUrl)) {
                String u = baseUrl;
                if (!u.contains("generateContent"))
                    u = u.replaceAll("/+$", "") + "/v1beta/models/" + enc(modelId) + ":generateContent";
                String key = provider.optString("apiKey", "");
                if (!key.isEmpty()) u += (u.contains("?") ? "&" : "?") + "key=" + enc(key);
                targets = new ArrayList<String>(); targets.add(u);
                body = new JSONObject().put("contents", new JSONArray().put(
                        new JSONObject().put("role", "user").put("parts", new JSONArray().put(
                                new JSONObject().put("text", "hi")))))
                        .put("generationConfig", new JSONObject().put("maxOutputTokens", 1)).toString();
            } else if (!forceOpenAI && isAnthropic(baseUrl)) {
                String u = baseUrl;
                if (!u.matches("(?i).*/messages(\\?.*)?$")) u = u.replaceAll("/+$", "") + "/v1/messages";
                targets = new ArrayList<String>(); targets.add(u);
                body = new JSONObject().put("model", modelId).put("max_tokens", 1)
                        .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", "hi"))).toString();
            } else {
                targets = candidateEndpoints(baseUrl, provider.optBoolean("rawUrl", false));
                body = new JSONObject().put("model", modelId).put("max_tokens", 1)
                        .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", "hi")))
                        .put("stream", false).toString();
            }
        } catch (JSONException e) { return new Probe(null, 0, "json"); }

        int lastStatus = 0; String lastReason = "unreachable"; boolean sawThrottle = false;
        // Небольшой retry-с-паузами именно для «ложного» 429 троттлинга
        // («too many failed attempts…»): даём провайдеру остыть и пытаемся
        // получить настоящий статус (обычно 402 «нет баланса»).
        int[] backoffs = {0, 1500, 4000};
        for (String target : targets) {
            for (String tmpl : chain) {
                String finalUrl = tmpl != null ? buildProxyUrl(tmpl, target) : target;
                boolean nextEndpoint = false;
                for (int bi = 0; bi < backoffs.length && !nextEndpoint; bi++) {
                    if (backoffs[bi] > 0) { try { Thread.sleep(backoffs[bi]); } catch (InterruptedException ie) {} }
                    try {
                        HttpURLConnection c = openPost(finalUrl, provider, baseUrl, body, timeoutMs);
                        int code = c.getResponseCode();
                        String txt = code >= 200 && code < 300 ? "" : readAll(errStream(c));
                        c.disconnect();
                        if (code >= 200 && code < 300) return new Probe(Boolean.TRUE, code, null);
                        lastStatus = code;
                        String s = txt.toLowerCase(Locale.US);
                        boolean authoritative = code != 404 && code != 405;
                        boolean unavailable = matchesUnavailable(code, s);
                        boolean freeExhausted = matchesFreeExhausted(s);
                        // Троттлинг из-за прошлых ОШИБОК (Venice) — не вердикт: перепроверяем.
                        if (matchesThrottledAfterFailures(code, s)) {
                            sawThrottle = true; lastReason = "throttled-after-failures";
                            continue; // повторим после паузы
                        }
                        if (!authoritative && !unavailable && !freeExhausted) { lastReason = "HTTP " + code; nextEndpoint = true; break; }
                        if (unavailable) return new Probe(Boolean.FALSE, code, "HTTP " + code);
                        if (freeExhausted) return new Probe(Boolean.FALSE, code, "free-tier-exhausted");
                        if (matchesRateLimitOnly(code, s)) return new Probe(Boolean.TRUE, code, "rate-limited");
                        if (code == 401) return new Probe(null, 401, "invalid_api_key");
                        if (code == 400 || code == 422) return new Probe(Boolean.TRUE, code, "param-rejected");
                        return new Probe(Boolean.FALSE, code, "HTTP " + code);
                    } catch (Exception e) {
                        lastReason = (e.toString().contains("timeout")) ? "timeout" : e.getMessage();
                        nextEndpoint = true; break; // сеть/CORS → следующий прокси/endpoint
                    }
                }
            }
        }
        // Если видели только троттлинг «too many failed attempts» — вердикт неизвестен
        // (null), UI упадёт на статическую цену (для платных моделей = красный).
        return new Probe(null, lastStatus, sawThrottle ? "throttled-after-failures" : lastReason);
    }

    private static boolean matchesUnavailable(int status, String s) {
        if (s.matches("(?s).*(budget_exhausted|insufficient|balance is insufficient|add credits|no credits|out of credits|payment required).*")) return true;
        if (s.matches("(?s).*model.*(not found|not exist|unavailable|not allowed|no access|not permitted|does not exist|invalid model|unknown model).*")) return true;
        if (status == 402 || status == 403) return true;
        if (status == 404 && s.matches("(?s).*(model|resource).*")) return true;
        return false;
    }
    private static boolean matchesFreeExhausted(String s) {
        if (s.matches("(?s).*(insufficient_user_quota|user_quota|quota_exhausted|quota exceeded|out of quota|daily limit|free (tier|plan|quota|limit)).*")) return true;
        if (s.matches("(?s).*free\\s+model.*(rate.?limit|limit).*")) return true;
        if (s.matches("(?s).*(use the paid|upgrade to|switch to).*paid.*")) return true;
        if (s.matches("(?s).*no limit\\b.*")) return true;
        if (s.matches("(?s).*free providers?.*(hit|reached|exceeded).*(rate.?limit|limit).*")) return true;
        return false;
    }
    // «Ложный» 429: провайдер троттлит из-за того, что ПРЕДЫДУЩИЕ запросы
    // завершились ошибкой (напр. Venice: "Too many failed attempts (> 20)
    // resulting in a non-success status code"). Это НЕ обычный rate-limit
    // валидного ключа — это маскировка реальной причины отказа (чаще 402).
    private static boolean matchesThrottledAfterFailures(int status, String s) {
        if (status != 429) return false;
        return s.matches("(?s).*(too many failed attempts|non-?success status|resulting in (a )?failure|previous (requests?|attempts?) failed).*");
    }
    private static boolean matchesRateLimitOnly(int status, String s) {
        if (s.matches("(?s).*(budget|insufficient|balance|credits|payment).*")) return false;
        if (matchesFreeExhausted(s)) return false;
        if (matchesThrottledAfterFailures(status, s)) return false;
        return status == 429 || s.matches("(?s).*(rate.?limit|too many requests|throttl).*");
    }

    // --------------------------------------------------------------- cost tier
    /** 'free' | 'paid' | 'unknown' по данным прайсинга из /models. */
    public static String modelCostTier(Model m) {
        if (m == null) return "unknown";
        String id = (m.id == null ? "" : m.id).toLowerCase(Locale.US);
        JSONObject raw = m.raw;
        if (raw != null) {
            // Venice и ряд провайдеров кладут метаданные (в т.ч. pricing) внутрь
            // model_spec / spec. Учитываем и этот уровень.
            JSONObject spec = raw.optJSONObject("model_spec");
            if (spec == null) spec = raw.optJSONObject("spec");
            if (raw.optBoolean("free", false) || raw.optBoolean("is_free", false)) return "free";
            if (spec != null && (spec.optBoolean("free", false) || spec.optBoolean("is_free", false))) return "free";
            String tier = raw.optString("tier", "");
            if (tier.toLowerCase(Locale.US).contains("free")) return "free";
            if (spec != null && spec.optString("tier", "").toLowerCase(Locale.US).contains("free")) return "free";
            List<Double> prices = new ArrayList<Double>();
            boolean sawPricing = false;
            // pricing-объект на верхнем уровне ИЛИ в model_spec (Venice).
            JSONObject pricing = raw.optJSONObject("pricing");
            if (pricing == null && spec != null) pricing = spec.optJSONObject("pricing");
            if (pricing != null) {
                sawPricing = true;
                String[] keys = {"prompt", "completion", "input", "output", "request", "image"};
                for (String k : keys) if (pricing.has(k)) pushPrice(prices, pricing.opt(k));
                if (prices.isEmpty()) {
                    Iterator<String> it = pricing.keys();
                    while (it.hasNext()) pushPrice(prices, pricing.opt(it.next()));
                }
            }
            String[] flat = {"input_price","output_price","prompt_price","completion_price","price","cost","input_cost_per_token","output_cost_per_token"};
            for (String k : flat) {
                if (raw.has(k)) { sawPricing = true; pushPrice(prices, raw.opt(k)); }
                if (spec != null && spec.has(k)) { sawPricing = true; pushPrice(prices, spec.opt(k)); }
            }
            if (!prices.isEmpty()) {
                boolean anyPaid = false;
                for (Double d : prices) if (d != null && d > 0) anyPaid = true;
                return anyPaid ? "paid" : "free";
            }
            if (sawPricing) return "free";
        }
        if (id.matches("(?s).*:free\\b.*")) return "free";
        return "unknown";
    }

    private static void pushPrice(List<Double> out, Object v) {
        if (v == null) return;
        if (v instanceof Number) { out.add(((Number) v).doubleValue()); return; }
        if (v instanceof String) {
            try {
                String s = ((String) v).replaceAll("[^0-9.eE+\\-]", "");
                if (!s.isEmpty()) out.add(Double.parseDouble(s));
            } catch (Exception ignored) {}
            return;
        }
        // Вложенный ценник по валютам: { usd: 1.4, diem: 1.4 } (формат Venice).
        // Берём usd, а если его нет — любое числовое поле объекта.
        if (v instanceof JSONObject) {
            JSONObject o = (JSONObject) v;
            if (o.has("usd")) { pushPrice(out, o.opt("usd")); return; }
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                Object inner = o.opt(it.next());
                if (inner instanceof Number || inner instanceof String) { pushPrice(out, inner); return; }
            }
        }
    }

    /** Итоговый tier: реальная проверка приоритетнее статической цены. */
    public static String availabilityTier(Model m, Probe probe) {
        if (probe != null && Boolean.TRUE.equals(probe.available)) {
            // «Доступна», но вердикт получен не по реальному 200, а по мягкому
            // признаку (обычный rate-limit). Если статическая цена однозначно
            // «платная» — доверяем цене (красный), чтобы не показать ложно-зелёный
            // кружок у платной модели, которую сервер лишь временно притормозил.
            if (probe.reason != null && probe.reason.equals("rate-limited")
                    && "paid".equals(modelCostTier(m))) return "paid";
            return "free";
        }
        if (probe != null && Boolean.FALSE.equals(probe.available)) return "paid";
        return modelCostTier(m);
    }

    // --------------------------------------------------------------- http util
    private static HttpURLConnection openGet(String url, JSONObject provider, String base) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        applyHeaders(c, provider, base, false);
        return c;
    }

    private static HttpURLConnection openPost(String url, JSONObject provider, String base, String body, int timeoutMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(Math.min(15000, timeoutMs));
        c.setReadTimeout(timeoutMs);
        c.setDoOutput(true);
        applyHeaders(c, provider, base, true);
        OutputStream os = c.getOutputStream();
        os.write(body.getBytes("UTF-8"));
        os.close();
        return c;
    }

    private static void applyHeaders(HttpURLConnection c, JSONObject provider, String base, boolean post) {
        c.setRequestProperty("Accept", "application/json");
        if (post) c.setRequestProperty("Content-Type", "application/json");
        boolean forceOpenAI = provider.optBoolean("openaiCompatible", false);
        String apiKey = provider.optString("apiKey", "");
        // При «OpenAI-compatible» всегда используем Bearer-токен (игнорируем
        // авто-определение Google/Anthropic по домену).
        if (!apiKey.isEmpty() && (forceOpenAI || !isGoogle(base))) {
            if (!forceOpenAI && isAnthropic(base)) {
                c.setRequestProperty("x-api-key", apiKey);
                c.setRequestProperty("anthropic-version", "2023-06-01");
            } else {
                c.setRequestProperty("Authorization", "Bearer " + apiKey);
            }
        }
        String extra = provider.optString("extraHeaders", "");
        if (!extra.trim().isEmpty()) {
            try {
                JSONObject eh = new JSONObject(extra);
                Iterator<String> it = eh.keys();
                while (it.hasNext()) { String k = it.next(); c.setRequestProperty(k, eh.optString(k)); }
            } catch (JSONException ignored) {}
        }
    }

    private static InputStream errStream(HttpURLConnection c) {
        InputStream es = c.getErrorStream();
        return es != null ? es : new java.io.ByteArrayInputStream(new byte[0]);
    }

    private static String readAll(InputStream in) throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line).append('\n');
        r.close();
        return sb.toString();
    }

    /** Результат одного HTTP POST: код ответа + тело. */
    static class HttpResp { final int code; final String body; HttpResp(int c, String b){ code=c; body=b; } }

    /**
     * Выполнить POST и вернуть {код, тело}, регистрируя соединение в canceller,
     * чтобы кнопку «стоп» можно было обработать МГНОВЕННО (обрыв readAll).
     * Перед и после сети проверяем отмену — не тратим лишнего.
     */
    private static HttpResp postText(String finalUrl, JSONObject provider, String baseUrl,
                                     String body, int timeoutMs, Canceller canceller) throws Exception {
        if (canceller != null && canceller.isCancelled()) throw new Exception("Остановлено пользователем");
        HttpURLConnection c = openPost(finalUrl, provider, baseUrl, body, timeoutMs);
        if (canceller != null) canceller.attach(c);
        try {
            int code = c.getResponseCode();
            InputStream in = (code >= 200 && code < 300) ? c.getInputStream() : errStream(c);
            if (canceller != null) canceller.attachStream(in);
            String txt = readAll(in);
            if (canceller != null && canceller.isCancelled()) throw new Exception("Остановлено пользователем");
            return new HttpResp(code, txt);
        } finally {
            try { c.disconnect(); } catch (Exception ignored) {}
        }
    }

    private static List<String> dedup(List<String> in) {
        Set<String> seen = new LinkedHashSet<String>(in);
        return new ArrayList<String>(seen);
    }

    private static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; }
    }

    /**
     * Разбирает data URL «data:&lt;mime&gt;;base64,&lt;b64&gt;» на пару {mime, base64}.
     * Нужен для Anthropic/Google форматов vision (там картинка передаётся не как
     * готовый data-url, а отдельно mime + чистый base64). Возвращает null, если это
     * не base64-data-url. Порт разбора dataUrl из web-версии (ai.js).
     */
    static String[] splitDataUrl(String du) {
        if (du == null) return null;
        Matcher m = Pattern.compile("^data:([^;,]+);base64,(.+)$", Pattern.DOTALL).matcher(du.trim());
        if (!m.find()) return null;
        return new String[]{ m.group(1), m.group(2) };
    }
}
