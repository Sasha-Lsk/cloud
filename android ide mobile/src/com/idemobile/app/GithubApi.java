package com.idemobile.app;

import android.util.Base64;

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
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * Клиент GitHub REST/OAuth. Порт сетевой части web-версии (git.js + github-fs.js).
 *
 * ВАЖНОЕ ОТЛИЧИЕ ОТ WEB: в браузере github.com/login/* и api.github.com не
 * присылают Access-Control-Allow-Origin, поэтому web-версия ходит через каскад
 * публичных CORS-прокси. На Android CORS НЕ ДЕЙСТВУЕТ (нативный HTTP), поэтому мы
 * ходим НАПРЯМУЮ — быстрее, надёжнее и без зависимости от чужих прокси.
 *
 * Все методы блокирующие — вызывать из фонового потока.
 */
public class GithubApi {

    /** Публичный client_id GitHub CLI (как в приложении GitHub Mobile / web-версии). */
    public static final String DEFAULT_CLIENT_ID = "178c6fc778ccc68e1d6a";
    /** Скоупы: работа с репозиториями + чтение профиля + удаление репозиториев. */
    public static final String DEFAULT_SCOPE = "repo,read:user,delete_repo";

    private static final String API = "https://api.github.com";

    // =============================================================== device flow
    /** Результат первого шага device flow. */
    public static class DeviceCode {
        public String deviceCode;
        public String userCode;
        public String verificationUri;
        public String verificationUriComplete;
        public int expiresIn;
        public int interval;
    }

    /**
     * Шаг 1 device flow: получить device_code + user_code.
     * POST https://github.com/login/device/code
     */
    public static DeviceCode requestDeviceCode(String clientId, String scope) throws Exception {
        String body = "client_id=" + enc(clientId) + "&scope=" + enc(scope);
        JSONObject j = postForm("https://github.com/login/device/code", body);
        if (j.has("error"))
            throw new Exception(j.optString("error_description", j.optString("error")));
        if (j.optString("device_code", "").isEmpty() || j.optString("user_code", "").isEmpty())
            throw new Exception("GitHub вернул некорректный ответ (нет device_code)");
        DeviceCode dc = new DeviceCode();
        dc.deviceCode = j.optString("device_code");
        dc.userCode = j.optString("user_code");
        dc.verificationUri = j.optString("verification_uri", "https://github.com/login/device");
        dc.verificationUriComplete = j.optString("verification_uri_complete",
                dc.verificationUri + "?user_code=" + enc(dc.userCode));
        dc.expiresIn = j.optInt("expires_in", 900);
        dc.interval = Math.max(1, j.optInt("interval", 5));
        return dc;
    }

    /**
     * Шаг 2 device flow: один опрос access_token.
     * Возвращает: токен (успех) | "" (authorization_pending) | "slow_down" |
     * бросает исключение при фатальной ошибке (access_denied, expired_token).
     * POST https://github.com/login/oauth/access_token
     */
    public static String pollAccessToken(String clientId, String deviceCode) throws Exception {
        String body = "client_id=" + enc(clientId)
                + "&device_code=" + enc(deviceCode)
                + "&grant_type=" + enc("urn:ietf:params:oauth:grant-type:device_code");
        JSONObject j = postForm("https://github.com/login/oauth/access_token", body);
        String token = j.optString("access_token", "");
        if (!token.isEmpty()) return token;
        String err = j.optString("error", "");
        if ("authorization_pending".equals(err)) return "";
        if ("slow_down".equals(err)) return "slow_down";
        if (err.isEmpty()) return "";
        throw new Exception(j.optString("error_description", err));
    }

    // =============================================================== user / repos
    /** GET /user — проверка токена + захват выданных scope (X-OAuth-Scopes). */
    public static JSONObject fetchUser(String token) throws Exception {
        HttpURLConnection c = open("GET", API + "/user", token, null);
        int code = c.getResponseCode();
        String txt = readAll(code >= 200 && code < 300 ? c.getInputStream() : err(c));
        String scopesHeader = c.getHeaderField("X-OAuth-Scopes");
        if (scopesHeader == null) scopesHeader = c.getHeaderField("x-oauth-scopes");
        c.disconnect();
        if (code < 200 || code >= 300) throw new Exception("GitHub API: " + code);
        JSONObject j = new JSONObject(txt);
        JSONArray scopes = new JSONArray();
        if (scopesHeader != null)
            for (String s : scopesHeader.split(",")) { String t = s.trim(); if (!t.isEmpty()) scopes.put(t); }
        j.put("__scopes", scopes);
        return j;
    }

    /** Все репозитории (public+private) авторизованного пользователя. Порт fetchAllRepos. */
    public static JSONArray fetchAllRepos(String token) throws Exception {
        JSONArray acc = new JSONArray();
        String buster = System.currentTimeMillis() + "-" + Integer.toString((int) (Math.random() * 1e6), 36);
        for (int page = 1; page <= 20; page++) {
            String url = API + "/user/repos?per_page=100&sort=updated"
                    + "&affiliation=owner,collaborator,organization_member&page=" + page + "&_=" + buster;
            HttpURLConnection c = open("GET", url, token, null);
            c.setUseCaches(false);
            int code = c.getResponseCode();
            String txt = readAll(code >= 200 && code < 300 ? c.getInputStream() : err(c));
            c.disconnect();
            if (code < 200 || code >= 300) throw new Exception("GitHub API /user/repos: " + code);
            JSONArray arr = new JSONArray(txt);
            if (arr.length() == 0) break;
            for (int i = 0; i < arr.length(); i++) acc.put(arr.opt(i));
            if (arr.length() < 100) break;
        }
        return acc;
    }

    /** Список веток репозитория. Порт fetchBranches. */
    public static List<String> fetchBranches(String token, String fullName) throws Exception {
        List<String> out = new ArrayList<String>();
        for (int page = 1; page <= 10; page++) {
            String url = API + "/repos/" + fullName + "/branches?per_page=100&page=" + page;
            HttpURLConnection c = open("GET", url, token, null);
            int code = c.getResponseCode();
            String txt = readAll(code >= 200 && code < 300 ? c.getInputStream() : err(c));
            c.disconnect();
            if (code < 200 || code >= 300) throw new Exception("GitHub API /branches: " + code);
            JSONArray arr = new JSONArray(txt);
            if (arr.length() == 0) break;
            for (int i = 0; i < arr.length(); i++) out.add(arr.optJSONObject(i).optString("name"));
            if (arr.length() < 100) break;
        }
        return out;
    }

    /** POST /user/repos — создать репозиторий. Порт createRepo. */
    public static JSONObject createRepo(String token, String name, String description,
                                        boolean isPrivate, boolean autoInit) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("name", name);
        payload.put("description", description == null ? "" : description);
        payload.put("private", isPrivate);
        payload.put("auto_init", autoInit);
        HttpURLConnection c = open("POST", API + "/user/repos", token, payload.toString());
        int code = c.getResponseCode();
        String txt = readAll(code >= 200 && code < 300 ? c.getInputStream() : err(c));
        c.disconnect();
        if (code < 200 || code >= 300) {
            String msg = "HTTP " + code;
            try {
                JSONObject j = new JSONObject(txt);
                msg = j.optString("message", msg);
                if (j.has("errors")) msg += " — " + j.optJSONArray("errors");
            } catch (Exception ignored) {}
            throw new Exception(msg);
        }
        return new JSONObject(txt);
    }

    /** DELETE /repos/{full} — удалить репозиторий. Порт deleteRepo. */
    public static void deleteRepo(String token, String fullName) throws Exception {
        HttpURLConnection c = open("DELETE", API + "/repos/" + fullName, token, null);
        int code = c.getResponseCode();
        if (code == 204) { c.disconnect(); return; }
        String txt = readAll(err(c));
        c.disconnect();
        String detail = txt;
        try { detail = new JSONObject(txt).optString("message", txt); } catch (Exception ignored) {}
        if (code == 403)
            throw new Exception("Нет прав на удаление. Токену нужен scope `delete_repo`. "
                    + "Войдите заново с расширенным scope, либо используйте PAT с этим правом. Ответ GitHub: " + detail);
        if (code == 404) throw new Exception("Репозиторий не найден или недоступен: " + detail);
        throw new Exception("HTTP " + code + ": " + detail);
    }

    /** PATCH /repos/{full} — переименовать репозиторий. Возвращает обновлённый JSON. */
    public static JSONObject renameRepo(String token, String fullName, String newName) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("name", newName);
        HttpURLConnection c = openPatch(API + "/repos/" + fullName, token, payload.toString());
        int code = c.getResponseCode();
        String txt = readAll(code >= 200 && code < 300 ? c.getInputStream() : err(c));
        c.disconnect();
        if (code < 200 || code >= 300) throw new Exception(apiError(code, txt));
        return new JSONObject(txt);
    }

    /** POST /repos/{full}/branches/{branch}/rename — переименовать ветку. */
    public static void renameBranch(String token, String fullName, String branch, String newName) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("new_name", newName);
        HttpURLConnection c = open("POST", API + "/repos/" + fullName + "/branches/" + enc(branch) + "/rename", token, payload.toString());
        int code = c.getResponseCode();
        String txt = readAll(code >= 200 && code < 300 ? c.getInputStream() : err(c));
        c.disconnect();
        if (code < 200 || code >= 300) throw new Exception(apiError(code, txt));
    }

    /** DELETE /repos/{full}/git/refs/heads/{branch} — удалить ветку. */
    public static void deleteBranch(String token, String fullName, String branch) throws Exception {
        HttpURLConnection c = open("DELETE", API + "/repos/" + fullName + "/git/refs/heads/" + enc(branch), token, null);
        int code = c.getResponseCode();
        if (code == 204) { c.disconnect(); return; }
        String txt = readAll(err(c));
        c.disconnect();
        throw new Exception(apiError(code, txt));
    }

    private static String apiError(int code, String txt) {
        String detail = txt;
        try { detail = new JSONObject(txt).optString("message", txt); } catch (Exception ignored) {}
        return "HTTP " + code + ": " + detail;
    }

    // =============================================================== contents / tree
    /** GET git/trees/{ref}?recursive=1. Возвращает JSON дерева (или null-tree для пустого репо). */
    public static JSONObject fetchTree(String token, String owner, String repo, String branch) throws Exception {
        String url = API + "/repos/" + owner + "/" + repo + "/git/trees/" + enc(branch) + "?recursive=1";
        HttpURLConnection c = open("GET", url, token, null);
        int code = c.getResponseCode();
        String txt = readAll(code >= 200 && code < 300 ? c.getInputStream() : err(c));
        c.disconnect();
        if (code == 404 || code == 409) {
            // Пустой репозиторий (нет коммитов на ветке) — пустое дерево.
            JSONObject empty = new JSONObject();
            empty.put("tree", new JSONArray());
            empty.put("__empty", true);
            return empty;
        }
        if (code < 200 || code >= 300) throw new Exception("GH tree: " + code + " " + cut(txt, 200));
        return new JSONObject(txt);
    }

    /**
     * GET git/blobs/{sha} → сырые байты.
     *
     * ВАЖНО (память): раньше ответ читался в String через readAll(StringBuilder),
     * а затем base64-декодировался — на больших файлах это давало пик памяти в
     * несколько раз больше самого файла (StringBuilder + String + base64-строка +
     * декод) и OutOfMemoryError при скачивании. Теперь запрашиваем media type
     * `application/vnd.github.raw`, при котором GitHub отдаёт СЫРЫЕ байты файла
     * напрямую (без JSON и base64), и читаем их потоково в byte[] одним буфером.
     * Fallback на старый JSON+base64 путь оставлен на случай, если raw недоступен.
     */
    public static byte[] fetchBlob(String token, String owner, String repo, String sha) throws Exception {
        String url = API + "/repos/" + owner + "/" + repo + "/git/blobs/" + sha;
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setRequestProperty("Accept", "application/vnd.github.raw");
        c.setRequestProperty("User-Agent", "IDEMobile-Android");
        if (token != null && !token.isEmpty()) c.setRequestProperty("Authorization", "token " + token);
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) {
            String txt = readAll(err(c));
            c.disconnect();
            throw new Exception("GH blob: " + code + " " + cut(txt, 200));
        }
        String ctype = c.getContentType();
        // Если сервер всё же вернул JSON (raw не поддержан) — разбираем как раньше.
        if (ctype != null && ctype.contains("application/json")) {
            String txt = readAll(c.getInputStream());
            c.disconnect();
            JSONObject j = new JSONObject(txt);
            return Base64.decode(j.optString("content", "").replaceAll("\\s+", ""), Base64.DEFAULT);
        }
        try {
            return readAllBytes(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    /** Потоковое чтение InputStream в byte[] без промежуточных String-буферов. */
    private static byte[] readAllBytes(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream(64 * 1024);
        byte[] buf = new byte[64 * 1024];
        int r;
        while ((r = in.read(buf)) != -1) bo.write(buf, 0, r);
        in.close();
        return bo.toByteArray();
    }

    /** GET contents/{path}?ref= → {bytes, sha}. */
    public static Object[] fetchContents(String token, String owner, String repo, String relPath, String branch) throws Exception {
        String url = API + "/repos/" + owner + "/" + repo + "/contents/" + encPath(relPath) + "?ref=" + enc(branch);
        HttpURLConnection c = open("GET", url, token, null);
        int code = c.getResponseCode();
        String txt = readAll(code >= 200 && code < 300 ? c.getInputStream() : err(c));
        c.disconnect();
        if (code < 200 || code >= 300) throw new Exception("ENOENT: " + relPath);
        JSONObject j = new JSONObject(txt);
        byte[] bytes;
        if ("base64".equals(j.optString("encoding")))
            bytes = Base64.decode(j.optString("content", "").replaceAll("\\s+", ""), Base64.DEFAULT);
        else bytes = j.optString("content", "").getBytes("UTF-8");
        return new Object[]{ bytes, j.optString("sha", null) };
    }

    /**
     * PUT contents/{path} — создать/обновить файл (КОММИТ). Порт writeFile.
     * Возвращает новый sha файла.
     */
    public static String putContents(String token, String owner, String repo, String relPath,
                                      byte[] data, String branch, String existingSha) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("message", (existingSha != null ? "Update " : "Create ") + relPath + " (from Mobile IDE)");
        payload.put("content", Base64.encodeToString(data, Base64.NO_WRAP));
        payload.put("branch", branch);
        if (existingSha != null) payload.put("sha", existingSha);
        String url = API + "/repos/" + owner + "/" + repo + "/contents/" + encPath(relPath);
        HttpURLConnection c = open("PUT", url, token, payload.toString());
        int code = c.getResponseCode();
        String txt = readAll(code >= 200 && code < 300 ? c.getInputStream() : err(c));
        c.disconnect();
        if (code < 200 || code >= 300) throw new Exception("GH write: " + code + " " + cut(txt, 200));
        JSONObject j = new JSONObject(txt);
        JSONObject content = j.optJSONObject("content");
        return content != null ? content.optString("sha", null) : null;
    }

    /** DELETE contents/{path} — удалить файл (КОММИТ). Порт unlink/deleteBlob. */
    public static void deleteContents(String token, String owner, String repo, String relPath,
                                      String sha, String branch) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("message", "Delete " + relPath + " (from Mobile IDE)");
        payload.put("sha", sha);
        payload.put("branch", branch);
        String url = API + "/repos/" + owner + "/" + repo + "/contents/" + encPath(relPath);
        HttpURLConnection c = open("DELETE", url, token, payload.toString());
        int code = c.getResponseCode();
        String txt = readAll(code >= 200 && code < 300 ? c.getInputStream() : err(c));
        c.disconnect();
        if (code < 200 || code >= 300) throw new Exception("GH delete: " + code + " " + cut(txt, 200));
    }

    // =============================================================== http helpers
    private static JSONObject postForm(String url, String formBody) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setDoOutput(true);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        c.setRequestProperty("User-Agent", "IDEMobile-Android");
        OutputStream os = c.getOutputStream();
        os.write(formBody.getBytes("UTF-8"));
        os.close();
        int code = c.getResponseCode();
        String txt = readAll(code >= 200 && code < 300 ? c.getInputStream() : err(c));
        c.disconnect();
        if (txt.trim().isEmpty()) throw new Exception("Пустой ответ GitHub (HTTP " + code + ")");
        try { return new JSONObject(txt); }
        catch (JSONException e) { throw new Exception("Некорректный ответ GitHub: " + cut(txt, 120)); }
    }

    private static HttpURLConnection open(String method, String url, String token, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("User-Agent", "IDEMobile-Android");
        if (token != null && !token.isEmpty()) c.setRequestProperty("Authorization", "token " + token);
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            OutputStream os = c.getOutputStream();
            os.write(body.getBytes("UTF-8"));
            os.close();
        }
        return c;
    }

    /**
     * PATCH-запрос. HttpURLConnection нативно не поддерживает метод PATCH
     * (setRequestMethod("PATCH") бросает ProtocolException). Форсируем метод
     * через рефлексию поля `method` — надёжный приём для всех версий Android.
     * Дополнительно шлём X-HTTP-Method-Override на случай прокси.
     */
    private static HttpURLConnection openPatch(String url, String token, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("User-Agent", "IDEMobile-Android");
        c.setRequestProperty("X-HTTP-Method-Override", "PATCH");
        if (token != null && !token.isEmpty()) c.setRequestProperty("Authorization", "token " + token);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        forceMethod(c, "PATCH");
        OutputStream os = c.getOutputStream();
        os.write((body == null ? "" : body).getBytes("UTF-8"));
        os.close();
        return c;
    }

    /** Принудительно выставить HTTP-метод (обход ограничения PATCH в HttpURLConnection). */
    private static void forceMethod(HttpURLConnection c, String method) {
        try {
            java.lang.reflect.Field f = HttpURLConnection.class.getDeclaredField("method");
            f.setAccessible(true);
            f.set(c, method);
        } catch (Throwable ignored) {
            // Рефлексия не удалась — пробуем штатно (может бросить, тогда останется POST).
            try { c.setRequestMethod(method); } catch (Throwable ignored2) {
                try { c.setRequestMethod("POST"); } catch (Throwable ignored3) {}
            }
        }
    }

    private static InputStream err(HttpURLConnection c) {
        InputStream e = c.getErrorStream();
        return e != null ? e : new java.io.ByteArrayInputStream(new byte[0]);
    }
    private static String readAll(InputStream in) throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line).append('\n');
        r.close();
        return sb.toString().trim();
    }
    private static String enc(String s) {
        try { return URLEncoder.encode(s == null ? "" : s, "UTF-8"); } catch (Exception e) { return s; }
    }
    private static String encPath(String p) {
        StringBuilder sb = new StringBuilder();
        for (String seg : p.split("/")) {
            if (seg.isEmpty()) continue;
            if (sb.length() > 0) sb.append('/');
            sb.append(enc(seg).replace("+", "%20"));
        }
        return sb.toString();
    }
    private static String cut(String s, int n) {
        if (s == null) return "";
        return s.length() > n ? s.substring(0, n) : s;
    }
}
