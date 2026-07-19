package com.idemobile.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Веб-поиск и загрузка страниц для ИИ-агента. Порт web_search / http_fetch из
 * web-версии (tools.js), адаптированный под Android.
 *
 * ВАЖНО: на Android НЕТ ограничения CORS (в отличие от браузера), поэтому к
 * поисковикам (Bing, Yahoo, Startpage→Google, DuckDuckGo, Brave, Mojeek) и к
 * JSON-API (DuckDuckGo Instant Answer, Wikipedia, Stack Overflow, GitHub, npm)
 * обращаемся НАПРЯМУЮ, без CORS-прокси. Это быстрее и надёжнее.
 *
 * По умолчанию запрос идёт КО ВСЕМ источникам ПАРАЛЛЕЛЬНО (каждый в своём
 * потоке), результаты объединяются и дедуплицируются по URL. Добавлены Google
 * (через анонимный прокси Startpage) и Yahoo, как просил пользователь.
 *
 * Блокирующие методы — вызывать из фонового потока (агент так и делает).
 */
public class WebSearch {

    /** Все источники поиска, к которым обращаемся по умолчанию. */
    public static final String[] ALL_SOURCES = {
            "ddg", "ddg_html", "google", "bing", "yahoo", "startpage", "brave", "mojeek",
            "wiki", "stackoverflow", "github", "npm"
    };

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/120.0 Mobile Safari/537.36";

    // ------------------------------------------------------------- web_search
    /**
     * Поиск в интернете по всем источникам. Возвращает JSON-результат в форме
     * web-версии: { ok:true, result:{ query, count, results:[{title,url,snippet,source}], sources, errors } }.
     */
    public static JSONObject search(String query, int limit, JSONArray sourcesArg) {
        if (query == null || query.trim().isEmpty()) return err("query обязателен");
        final int n = Math.max(1, Math.min(25, limit <= 0 ? 10 : limit));
        final int perSource = Math.max(3, (int) Math.ceil(n / 2.0));
        final String q = query.trim();

        // Набор источников: по умолчанию — ВСЕ.
        final Set<String> enabled = new LinkedHashSet<String>();
        if (sourcesArg != null && sourcesArg.length() > 0) {
            for (int i = 0; i < sourcesArg.length(); i++) enabled.add(sourcesArg.optString(i));
        } else {
            Collections.addAll(enabled, ALL_SOURCES);
        }

        final CopyOnWriteArrayList<JSONObject> results = new CopyOnWriteArrayList<JSONObject>();
        final CopyOnWriteArrayList<String> errors = new CopyOnWriteArrayList<String>();
        final List<Thread> threads = new ArrayList<Thread>();

        for (final String src : enabled) {
            Thread t = new Thread(new Runnable() {
                public void run() {
                    try { runSource(src, q, perSource, results, errors); }
                    catch (Throwable e) { errors.add(src + " → " + e); }
                }
            });
            t.start();
            threads.add(t);
        }
        // Ждём завершения всех источников (с общим потолком времени).
        long deadline = System.currentTimeMillis() + 20000;
        for (Thread t : threads) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) break;
            try { t.join(left); } catch (InterruptedException ignored) {}
        }

        // Дедуп по URL, ограничение количества.
        List<JSONObject> deduped = new ArrayList<JSONObject>();
        Set<String> seen = new LinkedHashSet<String>();
        for (JSONObject r : results) {
            String url = r.optString("url", "");
            String key = url.replaceAll("/+$", "").toLowerCase();
            if (key.isEmpty() || seen.contains(key)) continue;
            seen.add(key);
            deduped.add(r);
        }

        try {
            JSONArray arr = new JSONArray();
            for (int i = 0; i < Math.min(n, deduped.size()); i++) arr.put(deduped.get(i));
            JSONObject res = new JSONObject();
            res.put("query", q);
            res.put("count", arr.length());
            res.put("results", arr);
            JSONArray usedSources = new JSONArray();
            for (String s : enabled) usedSources.put(s);
            res.put("sources", usedSources);
            if (arr.length() == 0) {
                JSONArray er = new JSONArray();
                for (int i = 0; i < Math.min(8, errors.size()); i++) er.put(errors.get(i));
                res.put("errors", er);
                res.put("hint", "Ничего не найдено. Попробуй http_fetch с конкретным URL или уточни запрос.");
            }
            return ok(res);
        } catch (JSONException e) {
            return err("Ошибка формирования результата: " + e.getMessage());
        }
    }

    private static void runSource(String src, String q, int perSource,
                                  List<JSONObject> results, List<String> errors) throws Exception {
        if ("ddg".equals(src)) ddgInstant(q, results, errors);
        else if ("ddg_html".equals(src)) ddgHtml(q, perSource, results, errors);
        else if ("google".equals(src)) startpage(q, perSource, results, errors, "Google"); // Startpage = Google-выдача
        else if ("startpage".equals(src)) startpage(q, perSource, results, errors, "Startpage (Google)");
        else if ("bing".equals(src)) bing(q, perSource, results, errors);
        else if ("yahoo".equals(src)) yahoo(q, perSource, results, errors);
        else if ("brave".equals(src)) brave(q, perSource, results, errors);
        else if ("mojeek".equals(src)) mojeek(q, perSource, results, errors);
        else if ("wiki".equals(src)) wikipedia(q, perSource, results, errors);
        else if ("stackoverflow".equals(src)) stackoverflow(q, perSource, results, errors);
        else if ("github".equals(src)) github(q, perSource, results, errors);
        else if ("npm".equals(src)) npm(q, perSource, results, errors);
    }

    // ------------------------------------------------------------- JSON API sources
    private static void ddgInstant(String q, List<JSONObject> out, List<String> errors) {
        try {
            String u = "https://api.duckduckgo.com/?q=" + enc(q) + "&format=json&no_html=1&skip_disambig=1";
            String body = httpGet(u, false);
            JSONObject j = new JSONObject(body);
            String abstractText = j.optString("AbstractText", "");
            if (!abstractText.isEmpty()) {
                out.add(res(j.optString("Heading", q), j.optString("AbstractURL",
                        "https://duckduckgo.com/?q=" + enc(q)), abstractText, "DuckDuckGo"));
            }
            String answer = j.optString("Answer", "");
            if (!answer.isEmpty()) {
                out.add(res("[" + j.optString("AnswerType", "answer") + "] " + q,
                        "https://duckduckgo.com/?q=" + enc(q), answer, "DuckDuckGo instant"));
            }
            JSONArray related = j.optJSONArray("RelatedTopics");
            if (related != null) {
                for (int i = 0; i < related.length(); i++) {
                    JSONObject t = related.optJSONObject(i);
                    if (t == null) continue;
                    String text = t.optString("Text", "");
                    String url = t.optString("FirstURL", "");
                    if (!text.isEmpty() && !url.isEmpty())
                        out.add(res(text.length() > 90 ? text.substring(0, 90) : text, url, text, "DuckDuckGo"));
                }
            }
        } catch (Exception e) { errors.add("ddg → " + e); }
    }

    private static void wikipedia(String q, int per, List<JSONObject> out, List<String> errors) {
        // Автоопределение языка: кириллица → ru, иначе en.
        boolean cyr = Pattern.compile("[а-яА-ЯёЁ]").matcher(q).find();
        String[] langs = cyr ? new String[]{"ru", "en"} : new String[]{"en", "ru"};
        for (String lang : langs) {
            try {
                String u = "https://" + lang + ".wikipedia.org/w/api.php?action=opensearch&format=json&limit="
                        + per + "&search=" + enc(q);
                String body = httpGet(u, false);
                JSONArray j = new JSONArray(body);
                JSONArray titles = j.optJSONArray(1), descs = j.optJSONArray(2), urls = j.optJSONArray(3);
                if (titles == null || urls == null) continue;
                for (int i = 0; i < titles.length(); i++) {
                    out.add(res(titles.optString(i), urls.optString(i),
                            descs != null ? descs.optString(i, "") : "", "Wikipedia (" + lang + ")"));
                }
            } catch (Exception e) { errors.add("wiki(" + lang + ") → " + e); }
        }
    }

    private static void stackoverflow(String q, int per, List<JSONObject> out, List<String> errors) {
        try {
            String u = "https://api.stackexchange.com/2.3/search/advanced?order=desc&sort=relevance&q="
                    + enc(q) + "&site=stackoverflow&pagesize=" + per;
            String body = httpGet(u, true); // gzip
            JSONObject j = new JSONObject(body);
            JSONArray items = j.optJSONArray("items");
            if (items == null) return;
            for (int i = 0; i < items.length() && i < per; i++) {
                JSONObject it = items.optJSONObject(i);
                if (it == null) continue;
                out.add(res(unescapeHtml(it.optString("title")), it.optString("link"),
                        "Score " + it.optInt("score") + " · ответов " + it.optInt("answer_count"),
                        "Stack Overflow"));
            }
        } catch (Exception e) { errors.add("stackoverflow → " + e); }
    }

    private static void github(String q, int per, List<JSONObject> out, List<String> errors) {
        try {
            String u = "https://api.github.com/search/repositories?q=" + enc(q) + "&per_page=" + per;
            String body = httpGet(u, false);
            JSONObject j = new JSONObject(body);
            JSONArray items = j.optJSONArray("items");
            if (items == null) return;
            for (int i = 0; i < items.length() && i < per; i++) {
                JSONObject it = items.optJSONObject(i);
                if (it == null) continue;
                out.add(res(it.optString("full_name"), it.optString("html_url"),
                        it.optString("description", "") + " ★" + it.optInt("stargazers_count"),
                        "GitHub"));
            }
        } catch (Exception e) { errors.add("github → " + e); }
    }

    private static void npm(String q, int per, List<JSONObject> out, List<String> errors) {
        try {
            String u = "https://registry.npmjs.org/-/v1/search?text=" + enc(q) + "&size=" + per;
            String body = httpGet(u, false);
            JSONObject j = new JSONObject(body);
            JSONArray objs = j.optJSONArray("objects");
            if (objs == null) return;
            for (int i = 0; i < objs.length() && i < per; i++) {
                JSONObject pkg = objs.optJSONObject(i);
                if (pkg == null) continue;
                JSONObject p = pkg.optJSONObject("package");
                if (p == null) continue;
                String link = p.optJSONObject("links") != null
                        ? p.optJSONObject("links").optString("npm", "https://www.npmjs.com/package/" + p.optString("name"))
                        : "https://www.npmjs.com/package/" + p.optString("name");
                out.add(res(p.optString("name") + "@" + p.optString("version"), link,
                        p.optString("description", ""), "npm"));
            }
        } catch (Exception e) { errors.add("npm → " + e); }
    }

    // ------------------------------------------------------------- HTML scraping sources
    private static void bing(String q, int per, List<JSONObject> out, List<String> errors) {
        try {
            String html = httpGet("https://www.bing.com/search?q=" + enc(q) + "&adlt=off", false);
            int n = 0;
            // <li class="b_algo"> ... <h2><a href="URL">TITLE</a></h2> ... <p>SNIPPET</p>
            Matcher block = Pattern.compile("<li class=\"b_algo\".*?</li>", Pattern.DOTALL).matcher(html);
            while (block.find() && n < per) {
                String li = block.group();
                Matcher a = Pattern.compile("<h2>\\s*<a[^>]+href=\"([^\"]+)\"[^>]*>(.*?)</a>", Pattern.DOTALL).matcher(li);
                if (!a.find()) continue;
                String url = unwrapBing(a.group(1));
                if (!url.startsWith("http")) continue;
                String title = stripTags(a.group(2));
                Matcher p = Pattern.compile("<p[^>]*>(.*?)</p>", Pattern.DOTALL).matcher(li);
                String snip = p.find() ? stripTags(p.group(1)) : "";
                out.add(res(title, url, cut(snip, 400), "Bing"));
                n++;
            }
            if (n == 0) errors.add("bing → пустой парсинг (" + html.length() + " б)");
        } catch (Exception e) { errors.add("bing → " + e); }
    }

    private static void yahoo(String q, int per, List<JSONObject> out, List<String> errors) {
        try {
            String html = httpGet("https://search.yahoo.com/search?p=" + enc(q) + "&vm=r", false);
            int n = 0;
            Set<String> seen = new LinkedHashSet<String>();
            // Yahoo результаты: <h3 ...><a ... href="URL">TITLE</a>. Ссылки часто
            // обёрнуты через /RU=<url>/RK=... — разворачиваем unwrapYahoo.
            Matcher m = Pattern.compile("<h3[^>]*>\\s*<a[^>]+href=\"([^\"]+)\"[^>]*>(.*?)</a>", Pattern.DOTALL).matcher(html);
            while (m.find() && n < per) {
                String url = unwrapYahoo(m.group(1));
                if (!url.startsWith("http") || seen.contains(url)) continue;
                if (url.contains("yahoo.com/search") || url.contains("r.search.yahoo")) continue;
                seen.add(url);
                out.add(res(cut(stripTags(m.group(2)), 120), url, "", "Yahoo"));
                n++;
            }
            if (n == 0) errors.add("yahoo → пустой парсинг (" + html.length() + " б)");
        } catch (Exception e) { errors.add("yahoo → " + e); }
    }

    private static void startpage(String q, int per, List<JSONObject> out, List<String> errors, String label) {
        try {
            String html = httpGet("https://www.startpage.com/sp/search?query=" + enc(q) + "&qadf=off", false);
            int n = 0;
            Set<String> seen = new LinkedHashSet<String>();
            // Startpage: <a class="result-link" href="URL">TITLE</a> + .description
            Matcher m = Pattern.compile("<a[^>]+class=\"[^\"]*result-link[^\"]*\"[^>]+href=\"([^\"]+)\"[^>]*>(.*?)</a>", Pattern.DOTALL).matcher(html);
            while (m.find() && n < per) {
                String url = m.group(1);
                if (!url.startsWith("http") || url.contains("startpage.com") || seen.contains(url)) continue;
                seen.add(url);
                out.add(res(cut(stripTags(m.group(2)), 160), url, "", label));
                n++;
            }
            if (n == 0) {
                // Fallback: любые внешние ссылки на выдаче.
                Matcher any = Pattern.compile("href=\"(https?://[^\"]+)\"", Pattern.DOTALL).matcher(html);
                while (any.find() && n < per) {
                    String url = any.group(1);
                    if (url.contains("startpage.com") || seen.contains(url)) continue;
                    seen.add(url);
                    out.add(res(hostOf(url), url, "", label));
                    n++;
                }
            }
            if (n == 0) errors.add("startpage → пустой парсинг (" + html.length() + " б)");
        } catch (Exception e) { errors.add("startpage → " + e); }
    }

    private static void ddgHtml(String q, int per, List<JSONObject> out, List<String> errors) {
        try {
            String html = httpGet("https://html.duckduckgo.com/html/?q=" + enc(q) + "&kp=-2", false);
            int n = 0;
            Set<String> seen = new LinkedHashSet<String>();
            Matcher m = Pattern.compile("<a[^>]+class=\"[^\"]*result__a[^\"]*\"[^>]+href=\"([^\"]+)\"[^>]*>(.*?)</a>", Pattern.DOTALL).matcher(html);
            while (m.find() && n < per) {
                String url = m.group(1);
                Matcher uddg = Pattern.compile("[?&]uddg=([^&]+)").matcher(url);
                if (uddg.find()) { try { url = URLDecoder.decode(uddg.group(1), "UTF-8"); } catch (Exception ignored) {} }
                if (url.startsWith("//")) url = "https:" + url;
                if (!url.startsWith("http") || seen.contains(url)) continue;
                seen.add(url);
                out.add(res(cut(stripTags(m.group(2)), 120), url, "", "DuckDuckGo"));
                n++;
            }
            if (n == 0) errors.add("ddg_html → пустой парсинг (" + html.length() + " б)");
        } catch (Exception e) { errors.add("ddg_html → " + e); }
    }

    private static void brave(String q, int per, List<JSONObject> out, List<String> errors) {
        try {
            String html = httpGet("https://search.brave.com/search?q=" + enc(q) + "&safesearch=off", false);
            int n = 0;
            Set<String> seen = new LinkedHashSet<String>();
            Matcher m = Pattern.compile("<a[^>]+href=\"(https?://[^\"]+)\"[^>]*>(.*?)</a>", Pattern.DOTALL).matcher(html);
            while (m.find() && n < per) {
                String url = m.group(1);
                if (url.contains("search.brave.com") || seen.contains(url)) continue;
                String title = stripTags(m.group(2)).trim();
                if (title.length() < 8) continue;
                seen.add(url);
                out.add(res(cut(title, 120), url, "", "Brave"));
                n++;
            }
            if (n == 0) errors.add("brave → пустой парсинг (" + html.length() + " б)");
        } catch (Exception e) { errors.add("brave → " + e); }
    }

    private static void mojeek(String q, int per, List<JSONObject> out, List<String> errors) {
        try {
            String html = httpGet("https://www.mojeek.com/search?q=" + enc(q) + "&safe=0", false);
            int n = 0;
            Set<String> seen = new LinkedHashSet<String>();
            Matcher m = Pattern.compile("<a[^>]+href=\"(https?://[^\"]+)\"[^>]*class=\"[^\"]*(?:title|ob)[^\"]*\"[^>]*>(.*?)</a>", Pattern.DOTALL).matcher(html);
            if (!m.find()) {
                m = Pattern.compile("<h2>\\s*<a[^>]+href=\"(https?://[^\"]+)\"[^>]*>(.*?)</a>", Pattern.DOTALL).matcher(html);
            } else { m.reset(); }
            while (m.find() && n < per) {
                String url = m.group(1);
                if (url.contains("mojeek.com") || seen.contains(url)) continue;
                seen.add(url);
                out.add(res(cut(stripTags(m.group(2)), 120), url, "", "Mojeek"));
                n++;
            }
            if (n == 0) errors.add("mojeek → пустой парсинг (" + html.length() + " б)");
        } catch (Exception e) { errors.add("mojeek → " + e); }
    }

    // ------------------------------------------------------------- http_fetch
    /**
     * Скачать содержимое URL. Возвращает { ok:true, result:{ status, url, contentType,
     * text | data_url, size } }. Картинки/бинарные → data_url (+ поле _image для vision).
     */
    public static JSONObject httpFetch(String url, String method, JSONObject headers,
                                       String body, boolean forceText) {
        if (url == null || url.trim().isEmpty()) return err("url обязателен");
        try {
            HttpURLConnection c = open(url.trim(), method == null ? "GET" : method, false);
            if (headers != null) {
                java.util.Iterator<String> it = headers.keys();
                while (it.hasNext()) { String k = it.next(); c.setRequestProperty(k, headers.optString(k)); }
            }
            if (body != null && !body.isEmpty()) {
                c.setDoOutput(true);
                OutputStream os = c.getOutputStream();
                os.write(body.getBytes("UTF-8")); os.close();
            }
            int status = c.getResponseCode();
            String ct = c.getContentType() == null ? "" : c.getContentType();
            String finalUrl = c.getURL() != null ? c.getURL().toString() : url;
            InputStream in = (status >= 200 && status < 400) ? c.getInputStream() : c.getErrorStream();
            byte[] data = readBytes(in);
            c.disconnect();

            boolean looksImage = Pattern.compile("\\.(png|jpe?g|gif|webp|svg|bmp|ico|avif)($|\\?)", Pattern.CASE_INSENSITIVE)
                    .matcher(finalUrl).find();
            boolean binary = !forceText && (isBinaryCT(ct) || (looksImage && (ct.isEmpty() || ct.startsWith("text/"))));

            JSONObject res = new JSONObject();
            res.put("status", status);
            res.put("url", finalUrl);
            res.put("contentType", ct);
            if (binary) {
                if (data.length > 4 * 1024 * 1024)
                    return err("Файл слишком большой (" + data.length + " б) для встраивания. Лимит 4MB.");
                String mime = ct.isEmpty() ? mimeFromUrl(finalUrl) : ct.split(";")[0].trim();
                String dataUrl = "data:" + mime + ";base64," + android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP);
                res.put("size", data.length);
                res.put("kind", mime.startsWith("image/") ? "image" : "binary");
                res.put("data_url", dataUrl);
                if (mime.startsWith("image/")) {
                    res.put("_image", dataUrl); // Agent прикрепит к следующему vision-запросу
                    res.put("hint", "Чтобы показать картинку — вставь в ответ ![alt](data_url).");
                }
            } else {
                String text = new String(data, "UTF-8");
                if (text.length() > 200000) text = text.substring(0, 200000);
                res.put("text", text);
            }
            return ok(res);
        } catch (Exception e) {
            return err("Не удалось загрузить URL: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }

    // ------------------------------------------------------------- HTTP helpers
    private static HttpURLConnection open(String url, String method, boolean gzip) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept-Language", "ru,en;q=0.9,*;q=0.8");
        if (gzip) c.setRequestProperty("Accept-Encoding", "gzip");
        return c;
    }

    private static String httpGet(String url, boolean gzip) throws Exception {
        HttpURLConnection c = open(url, "GET", gzip);
        int code = c.getResponseCode();
        InputStream in = (code >= 200 && code < 400) ? c.getInputStream() : c.getErrorStream();
        if (gzip && "gzip".equalsIgnoreCase(c.getContentEncoding()))
            in = new java.util.zip.GZIPInputStream(in);
        byte[] data = readBytes(in);
        c.disconnect();
        return new String(data, "UTF-8");
    }

    private static byte[] readBytes(InputStream in) throws Exception {
        if (in == null) return new byte[0];
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192]; int r;
        while ((r = in.read(buf)) != -1) bo.write(buf, 0, r);
        in.close();
        return bo.toByteArray();
    }

    // ------------------------------------------------------------- text utils
    private static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; }
    }
    private static String stripTags(String s) {
        if (s == null) return "";
        String t = s.replaceAll("(?s)<[^>]+>", " ");
        return unescapeHtml(t).replaceAll("\\s+", " ").trim();
    }
    private static String unescapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'")
                .replace("&nbsp;", " ").replace("&mdash;", "—").replace("&ndash;", "–");
    }
    private static String cut(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) : s;
    }
    private static String hostOf(String url) {
        try { return new URL(url).getHost(); } catch (Exception e) { return url; }
    }
    /** Bing оборачивает ссылки редиректом ...&u=aHR0... (base64url). Разворачиваем. */
    private static String unwrapBing(String href) {
        Matcher m = Pattern.compile("[?&]u=a1([A-Za-z0-9_\\-]+)").matcher(href);
        if (m.find()) {
            try {
                String b64 = m.group(1).replace('-', '+').replace('_', '/');
                while (b64.length() % 4 != 0) b64 += "=";
                return new String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT), "UTF-8");
            } catch (Exception ignored) {}
        }
        return href;
    }
    /** Yahoo оборачивает ссылки в /RU=<urlencoded>/RK=... — извлекаем. */
    private static String unwrapYahoo(String href) {
        Matcher m = Pattern.compile("/RU=([^/]+)/").matcher(href);
        if (m.find()) { try { return URLDecoder.decode(m.group(1), "UTF-8"); } catch (Exception ignored) {} }
        return href;
    }

    private static boolean isBinaryCT(String ct) {
        if (ct == null || ct.isEmpty()) return false;
        ct = ct.toLowerCase();
        return ct.startsWith("image/") || ct.startsWith("audio/") || ct.startsWith("video/")
                || ct.equals("application/pdf") || ct.equals("application/octet-stream");
    }
    private static String mimeFromUrl(String url) {
        String u = url.toLowerCase();
        if (u.matches("(?s).*\\.png($|\\?).*")) return "image/png";
        if (u.matches("(?s).*\\.(jpe?g)($|\\?).*")) return "image/jpeg";
        if (u.matches("(?s).*\\.gif($|\\?).*")) return "image/gif";
        if (u.matches("(?s).*\\.webp($|\\?).*")) return "image/webp";
        if (u.matches("(?s).*\\.svg($|\\?).*")) return "image/svg+xml";
        if (u.matches("(?s).*\\.pdf($|\\?).*")) return "application/pdf";
        return "application/octet-stream";
    }

    private static JSONObject res(String title, String url, String snippet, String source) {
        try {
            return new JSONObject().put("title", title == null ? "" : title.trim())
                    .put("url", url == null ? "" : url).put("snippet", snippet == null ? "" : snippet)
                    .put("source", source);
        } catch (JSONException e) { return new JSONObject(); }
    }
    private static JSONObject ok(JSONObject r) {
        try { return new JSONObject().put("ok", true).put("result", r); }
        catch (JSONException e) { return new JSONObject(); }
    }
    private static JSONObject err(String m) {
        try { return new JSONObject().put("ok", false).put("error", m == null ? "ошибка" : m); }
        catch (JSONException e) { return new JSONObject(); }
    }
}
