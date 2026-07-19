package com.idemobile.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Файловые инструменты ИИ-агента. Порт fs_* из web-версии (tools.js).
 *
 * ВАЖНО: работает через тот же {@link ExplorerFs}, что и вкладка «Проводник» —
 * поэтому файлы/папки/архивы, созданные агентом, реально видны в проводнике,
 * в т.ч. когда пользователь открыл настоящую папку устройства (SAF / MODE_TREE),
 * а не только внутреннюю рабочую папку. Это исправляет баг «агент пишет, но в
 * проводнике ничего не появляется».
 *
 * Каждый инструмент возвращает JSONObject-результат в форме web-версии:
 *   { ok:true, result:{...} } либо { ok:false, error:"..." }.
 */
public class FsTool {

    private final ExplorerFs fs;

    public FsTool(Context ctx) {
        fs = new ExplorerFs(ctx);
    }

    /** Нормализация «виртуального» пути агента (/foo/bar). */
    private static String norm(String path) {
        return ExplorerFs.normPath(path == null ? "/" : path.trim());
    }

    /** Плоский список всех файлов проекта (виртуальные пути) — для прикрепления к чату. */
    public List<String> listAllFiles() {
        List<String> out = new ArrayList<String>();
        for (ExplorerFs.Node n : fs.listTree("/")) {
            if ("file".equals(n.type)) out.add(n.path);
        }
        Collections.sort(out);
        return out;
    }

    /** Прочитать файл проекта как байты (для вложений). null при ошибке. */
    public byte[] readBytes(String path) {
        try { return fs.readBytes(norm(path)); }
        catch (Exception e) { return null; }
    }

    // ------------------------------------------------------------- dispatch
    /** Выполнить инструмент по имени. Возвращает JSON-результат {ok,...}. */
    public JSONObject execute(String tool, JSONObject args) {
        try {
            if ("fs_read".equals(tool)) return fsRead(args);
            if ("fs_read_image".equals(tool)) return fsReadImage(args);
            if ("fs_write".equals(tool)) return fsWrite(args);
            if ("fs_replace".equals(tool)) return fsReplace(args);
            if ("fs_append".equals(tool)) return fsAppend(args);
            if ("fs_list".equals(tool)) return fsList(args);
            if ("fs_mkdir".equals(tool)) return fsMkdir(args);
            if ("fs_delete".equals(tool)) return fsDelete(args);
            if ("fs_rename".equals(tool)) return fsRename(args);
            if ("fs_search".equals(tool)) return fsSearch(args);
            if ("archive_create".equals(tool)) return archiveCreate(args);
            if ("archive_extract".equals(tool)) return archiveExtract(args);
            if ("archive_list".equals(tool)) return archiveList(args);
            if ("archive_read_entry".equals(tool)) return archiveReadEntry(args);
            if ("web_search".equals(tool)) return webSearch(args);
            if ("http_fetch".equals(tool)) return httpFetch(args);
            return err("Неизвестный инструмент: " + tool);
        } catch (IOException e) {
            return err(e.getMessage());
        } catch (JSONException e) {
            return err("Ошибка аргументов: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------- tools
    /**
     * Лимиты «инлайн»-чтения. ВЫРОВНЕНЫ С WEB-ВЕРСИЕЙ (tools.js: 4000 строк /
     * 320 КБ), чтобы поведение агента совпадало на обеих платформах.
     *
     * Раньше Android держал порог НАМНОГО ниже (1200 строк / 96 КБ) из опасения,
     * что слабые модели захлебнутся большим ответом. На практике это давало обратный
     * эффект: файл среднего размера (напр. 2000 строк) Android отдавал ЛИШЬ дайджестом,
     * и агент был вынужден делать лишние раунды fs_search + fs_read окнами — поэтому
     * на одном и том же задании Android работал МЕДЛЕННЕЕ web (где файл читался за раз).
     * Теперь пороги равны; от переполнения контекста защищает capContextSize в Agent,
     * а от OOM — потоковое чтение (streamWindow/streamDigest), не грузящее файл целиком.
     */
    private static final int MAX_INLINE_LINES = 4000;
    private static final int MAX_INLINE_BYTES = 320 * 1024;

    /**
     * Абсолютный предохранитель от OOM: файлы крупнее этого НИКОГДА не грузятся в
     * память целиком (даже под capacity JVM). Всё, что больше — обрабатывается
     * потоково (построчно). 8 МБ безопасно даже для слабых устройств.
     */
    private static final long MAX_TOTAL_BYTES = 8L * 1024 * 1024;

    private JSONObject fsRead(JSONObject a) throws IOException, JSONException {
        String path = norm(a.optString("path"));
        if (!fs.exists(path)) return err("Путь «" + a.optString("path") + "» не найден.");
        ExplorerFs.Stat st = fs.stat(path);
        if (st.isDir) return err("Путь «" + a.optString("path") + "» — это ПАПКА. Используй fs_list.");

        // Разбор окна с УЧЁТОМ АЛИАСОВ. Слабые модели (sarvam и др.) часто пишут не
        // "limit"/"offset", а "length"/"count"/"lines"/"n" и "start"/"from"/"line".
        // Раньше такие вызовы игнорировались (окно не применялось → модель получала
        // не то, что просила, и повторяла вызов). Теперь понимаем все варианты.
        int offset = firstInt(a, 0, "offset", "start", "from", "line", "startLine", "begin");
        int limit  = firstInt(a, 0, "limit", "length", "count", "lines", "n", "num", "size");
        if (offset < 0) offset = 0; else if (offset > 0) offset = Math.max(1, offset);
        if (limit < 0) limit = 0;
        boolean wantSpec = (offset > 0 || limit > 0);
        long fileSize = st.size;

        // ГЛАВНАЯ ПРАВКА ПРОТИВ ВЫЛЕТА. Решение о способе чтения принимаем ПО РАЗМЕРУ
        // ИЗ stat() — ДО загрузки файла в память. Раньше файл всегда читался целиком
        // (fs.readText → String → split → getBytes = 3-4 полные копии в памяти) ещё
        // ДО проверки размера, поэтому большой файл ронял приложение по OOM.
        // Теперь крупные файлы читаются ПОТОКОВО (построчно), в память попадает лишь
        // нужное окно строк либо дайджест (голова+хвост) — но не весь файл.
        boolean big = fileSize > MAX_INLINE_BYTES || fileSize > MAX_TOTAL_BYTES;

        if (!wantSpec && !big) {
            // Маленький файл без окна — читаем целиком (быстрый путь, как раньше).
            String content = fs.readText(path);
            int total = content.isEmpty() ? 0 : countLines(content);
            if (total > MAX_INLINE_LINES) {
                return streamDigest(path, fileSize);   // много строк при малом объёме
            }
            JSONObject r = new JSONObject();
            r.put("content", content);
            r.put("lines", total);
            r.put("size", (int) Math.min(Integer.MAX_VALUE, fileSize));
            r.put("path", path);
            return ok(r);
        }

        if (wantSpec) {
            // Запрошено окно строк — отдаём ТОЛЬКО его, читая файл потоково.
            int from = offset > 0 ? offset - 1 : 0;
            int wantLines = limit > 0 ? limit : Integer.MAX_VALUE;
            StringBuilder sb = new StringBuilder();
            int[] tot = new int[1];
            int returned = streamWindow(path, from, wantLines, sb, tot);
            JSONObject r = new JSONObject();
            r.put("content", sb.toString());
            r.put("lines", tot[0]);
            r.put("size", (int) Math.min(Integer.MAX_VALUE, fileSize));
            r.put("path", path);
            r.put("returnedLines", new JSONArray().put(from + 1).put(from + returned));
            return ok(r);
        }

        // Большой файл без окна → дайджест (голова+хвост), потоково.
        return streamDigest(path, fileSize);
    }

    /** Первое присутствующее целочисленное значение из перечня ключей-алиасов. */
    private static int firstInt(JSONObject a, int def, String... keys) {
        for (String k : keys) if (a.has(k)) return a.optInt(k, def);
        return def;
    }

    /** Считает строки без создания массива (split создаёт лишние копии → OOM). */
    private static int countLines(String s) {
        if (s.isEmpty()) return 0;
        int n = 1;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '\n') n++;
        return n;
    }

    /**
     * Потоково собирает окно строк [from, from+want) без загрузки файла целиком.
     * Возвращает число фактически возвращённых строк; в tot[0] — общее число строк.
     */
    private int streamWindow(String path, int from, int want, StringBuilder out, int[] tot)
            throws IOException {
        java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(fs.openInput(path), "UTF-8"), 32768);
        int idx = 0, returned = 0;
        try {
            String line;
            while ((line = br.readLine()) != null) {
                if (idx >= from && returned < want) {
                    if (returned > 0) out.append('\n');
                    out.append(line);
                    returned++;
                }
                idx++;
            }
        } finally { try { br.close(); } catch (Exception ignored) {} }
        tot[0] = idx;
        return returned;
    }

    /**
     * Потоковый дайджест большого файла (голова+хвост) без загрузки целиком.
     * Хвост держим в кольцевом буфере фиксированного размера — память O(TAIL),
     * а не O(файла).
     */
    private JSONObject streamDigest(String path, long fileSize) throws IOException, JSONException {
        final int HEAD = Math.min(400, MAX_INLINE_LINES / 2);
        final int TAIL = Math.min(200, MAX_INLINE_LINES / 5);
        StringBuilder head = new StringBuilder();
        String[] ring = new String[TAIL];
        int total = 0;
        java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(fs.openInput(path), "UTF-8"), 32768);
        try {
            String line;
            while ((line = br.readLine()) != null) {
                if (total < HEAD) head.append(total + 1).append('\t').append(line).append('\n');
                ring[total % TAIL] = line;   // всегда храним последние TAIL строк
                total++;
            }
        } finally { try { br.close(); } catch (Exception ignored) {} }

        StringBuilder tail = new StringBuilder();
        int tailStart = Math.max(HEAD, total - TAIL);
        for (int i = tailStart; i < total; i++) {
            tail.append(i + 1).append('\t').append(ring[i % TAIL]).append(i < total - 1 ? "\n" : "");
        }
        String preview = head.toString()
                + "\n[... пропущено " + Math.max(0, total - HEAD - TAIL)
                + " строк — файл слишком большой; читай нужные части через fs_read с offset и limit, ЛИБО найди нужное через fs_search ...]\n\n"
                + tail.toString();
        JSONObject r = new JSONObject();
        r.put("path", path);
        r.put("size", (int) Math.min(Integer.MAX_VALUE, fileSize));
        r.put("lines", total);
        r.put("mode", "digest");
        r.put("content", preview);
        r.put("hint", "Файл большой (" + total + " строк). Показаны только начало и конец. "
                + "Для чтения любой части: fs_read с offset и limit, напр. {\"path\":\"" + path
                + "\",\"offset\":1,\"limit\":500}. Чтобы найти нужный фрагмент — fs_search.");
        return ok(r);
    }

    /**
     * Прочитать изображение из проводника как data URL — чтобы модель могла его
     * увидеть (порт fs_read_image из web-версии). Результат содержит поле _image,
     * которое Agent превращает в multi-part user-message с image_url.
     */
    private JSONObject fsReadImage(JSONObject a) throws IOException, JSONException {
        String path = norm(a.optString("path"));
        if (!fs.exists(path)) return err("Путь «" + a.optString("path") + "» не найден.");
        ExplorerFs.Stat st = fs.stat(path);
        if (st.isDir) return err("Путь «" + a.optString("path") + "» — это ПАПКА.");
        byte[] data = fs.readBytes(path);
        if (data == null || data.length == 0) return err("Файл «" + a.optString("path") + "» пуст.");
        // Определяем реальный тип по сигнатуре (magic bytes), а не по расширению —
        // чтобы НИКОГДА не отправить в vision-запрос текстовую заглушку под видом
        // JPEG (это ломало провайдера: "cannot identify image file"). Большие
        // картинки даунскейлятся в JPEG (порт readImageAsDataUrl из web-версии).
        int maxSide = a.has("maxSide") ? Math.max(64, a.optInt("maxSide")) : 1600;
        String[] img = imageToVisionDataUrl(data, maxSide);
        if (img == null)
            return err("Файл «" + a.optString("path") + "» не является изображением "
                    + "(png/jpg/gif/webp/bmp). Прочитай его как текст через fs_read.");
        JSONObject r = new JSONObject();
        r.put("path", path);
        r.put("mime", img[0]);
        r.put("size", data.length);
        r.put("_image", img[1]); // спец. поле: Agent прикрепит картинку к следующему запросу
        return ok(r);
    }

    /** Минимальный размер большей стороны для vision-чёткости (апскейл мелких иконок). */
    private static final int MIN_VISION_SIDE = 256;

    /**
     * Готовит изображение для vision-запроса. Порт readImageAsDataUrl()+renderImageToPng()
     * из web-версии (tools.js). Возвращает пару { mime, dataUrl } либо null (не картинка).
     *
     * Ключевые преобразования (как в web):
     *  1. МЕЛКИЕ картинки (иконки 16/24/48px) АПСКЕЙЛЯТСЯ до {@link #MIN_VISION_SIDE}px —
     *     иначе vision-модель физически не может разобрать, что на них (это и была
     *     причина «агент ошибся с картинкой несколько раз»).
     *  2. БОЛЬШИЕ картинки (сторона &gt; maxSide) даунскейлятся — экономия токенов.
     *  3. bmp/webp всегда перекодируются в png/jpeg (не все провайдеры их принимают).
     * Перекодирование/масштабирование делается через реальный декод в Bitmap, поэтому
     * на выход идут только png/jpeg — форматы, гарантированно понятные vision-моделям.
     */
    static String[] imageToVisionDataUrl(byte[] data, int maxSide) {
        String mime = sniffImageMime(data);
        if (mime == null) return null;
        // Форматы, которые многие vision-провайдеры НЕ принимают напрямую (bmp/webp),
        // всегда нормализуем в png/jpeg (порт NEEDS_CONVERT из web-версии).
        boolean needsConvert = "image/bmp".equals(mime) || "image/webp".equals(mime);
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            int w = bounds.outWidth, h = bounds.outHeight;
            int maxDim = Math.max(w, h);
            boolean tooBig = maxDim > 0 && maxDim > maxSide;
            boolean tooSmall = maxDim > 0 && maxDim < MIN_VISION_SIDE;
            if (needsConvert || tooBig || tooSmall) {
                // Считаем inSampleSize (степень 2) для даунскейла больших картинок.
                int sample = 1;
                while (tooBig && maxDim / (sample * 2) >= maxSide) sample *= 2;
                BitmapFactory.Options opt = new BitmapFactory.Options();
                opt.inSampleSize = sample;
                Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length, opt);
                if (bmp != null) {
                    int bw = bmp.getWidth(), bh = bmp.getHeight();
                    int bmax = Math.max(bw, bh);
                    // Целевой масштаб: апскейл мелких до MIN_VISION_SIDE ИЛИ даунскейл больших до maxSide.
                    float scale = 1f;
                    if (bmax < MIN_VISION_SIDE) scale = (float) MIN_VISION_SIDE / bmax;      // апскейл
                    else if (bmax > maxSide)   scale = (float) maxSide / bmax;               // даунскейл
                    if (scale != 1f) {
                        int nw = Math.max(1, Math.round(bw * scale));
                        int nh = Math.max(1, Math.round(bh * scale));
                        // filter=true → сглаживание при уменьшении; при апскейле мелких
                        // иконок пиксельная резкость важнее — тоже оставляем true (как web canvas).
                        Bitmap scaled = Bitmap.createScaledBitmap(bmp, nw, nh, true);
                        if (scaled != bmp) bmp.recycle();
                        bmp = scaled;
                    }
                    // Есть альфа-канал (прозрачные png/webp/иконки) → PNG (без чёрного фона),
                    // иначе JPEG (компактнее). Порт логики web renderImageToPng (PNG для alpha).
                    boolean hasAlpha = bmp.hasAlpha();
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    String outMime;
                    if (hasAlpha) {
                        bmp.compress(Bitmap.CompressFormat.PNG, 100, bos);
                        outMime = "image/png";
                    } else {
                        bmp.compress(Bitmap.CompressFormat.JPEG, 90, bos);
                        outMime = "image/jpeg";
                    }
                    bmp.recycle();
                    byte[] out = bos.toByteArray();
                    return new String[]{ outMime,
                            "data:" + outMime + ";base64," + Base64.encodeToString(out, Base64.NO_WRAP) };
                }
            }
        } catch (Throwable ignored) {
            // Любая ошибка декодирования — отправляем оригинал как есть.
        }
        return new String[]{ mime, "data:" + mime + ";base64," + Base64.encodeToString(data, Base64.NO_WRAP) };
    }

    /** Определяет MIME изображения по сигнатуре файла. null — не изображение. */
    private static String sniffImageMime(byte[] d) {
        if (d.length >= 8 && (d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G') return "image/png";
        if (d.length >= 3 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8 && (d[2] & 0xFF) == 0xFF) return "image/jpeg";
        if (d.length >= 6 && d[0] == 'G' && d[1] == 'I' && d[2] == 'F' && d[3] == '8') return "image/gif";
        if (d.length >= 12 && d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F'
                && d[8] == 'W' && d[9] == 'E' && d[10] == 'B' && d[11] == 'P') return "image/webp";
        if (d.length >= 2 && d[0] == 'B' && d[1] == 'M') return "image/bmp";
        return null;
    }

    private JSONObject fsWrite(JSONObject a) throws IOException, JSONException {
        String path = norm(a.optString("path"));
        String content = a.optString("content", "");
        boolean existed = fs.exists(path);
        boolean created = !existed;
        if (existed && fs.stat(path).isDir)
            return err("Путь «" + a.optString("path") + "» — это ПАПКА, в неё нельзя записать файл.");
        String prev = created ? "" : fs.readText(path);
        fs.writeText(path, content);
        FsEvents.notifyChanged();
        JSONObject r = new JSONObject();
        int newLines = content.isEmpty() ? 0 : content.split("\n", -1).length;
        int prevLines = prev.isEmpty() ? 0 : prev.split("\n", -1).length;
        r.put("lines", newLines);
        r.put("written", content.length());
        r.put("size", content.getBytes("UTF-8").length);
        r.put("prevLines", prevLines);
        r.put("deltaLines", newLines - prevLines);
        r.put("created", created);
        r.put("path", path);
        r.put("diff", lineDiff(prev, content));
        return ok(r);
    }

    private JSONObject fsReplace(JSONObject a) throws IOException, JSONException {
        String path = norm(a.optString("path"));
        if (!fs.exists(path) || fs.stat(path).isDir) return err("Файл «" + a.optString("path") + "» не найден.");
        String prev = fs.readText(path);
        String next;
        int replacements = 0;
        if (a.has("startLine") && a.has("endLine")) {
            int s = a.optInt("startLine"), e = a.optInt("endLine");
            next = replaceLineRange(prev, s, e, a.optString("content", ""));
            replacements = 1;
        } else {
            String search = a.optString("search", "");
            String replace = a.optString("replace", "");
            if (search.isEmpty()) return err("fs_replace требует search или startLine/endLine.");
            boolean regex = a.optBoolean("regex", false);
            if (regex) {
                try {
                    java.util.regex.Pattern p = java.util.regex.Pattern.compile(search);
                    java.util.regex.Matcher m = p.matcher(prev);
                    StringBuffer sb = new StringBuffer();
                    while (m.find()) { m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replace)); replacements++; }
                    m.appendTail(sb);
                    next = sb.toString();
                } catch (Exception ex) { return err("Некорректный regex: " + ex.getMessage()); }
            } else {
                if (!prev.contains(search)) return err("Строка для замены не найдена в файле.");
                int idx; StringBuilder sb = new StringBuilder(); int from = 0;
                while ((idx = prev.indexOf(search, from)) >= 0) {
                    sb.append(prev, from, idx).append(replace);
                    from = idx + search.length(); replacements++;
                }
                sb.append(prev.substring(from));
                next = sb.toString();
            }
            if (replacements == 0) return err("Совпадений не найдено.");
        }
        fs.writeText(path, next);
        FsEvents.notifyChanged();
        JSONObject r = new JSONObject();
        int newLines = next.isEmpty() ? 0 : next.split("\n", -1).length;
        int prevLines = prev.isEmpty() ? 0 : prev.split("\n", -1).length;
        r.put("replacements", replacements);
        r.put("deltaLines", newLines - prevLines);
        r.put("size", next.getBytes("UTF-8").length);
        r.put("path", path);
        r.put("diff", lineDiff(prev, next));
        return ok(r);
    }

    private String replaceLineRange(String text, int s, int e, String content) {
        String[] lines = text.split("\n", -1);
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < lines.length; i++) {
            int ln = i + 1;
            if (ln == s) out.add(content);
            if (ln < s || ln > e) out.add(lines[i]);
        }
        if (s > lines.length) out.add(content);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < out.size(); i++) { sb.append(out.get(i)); if (i < out.size() - 1) sb.append('\n'); }
        return sb.toString();
    }

    private JSONObject fsAppend(JSONObject a) throws IOException, JSONException {
        String path = norm(a.optString("path"));
        String add = a.optString("content", "");
        boolean existed = fs.exists(path) && !fs.stat(path).isDir;
        String prev = existed ? fs.readText(path) : "";
        boolean created = !fs.exists(path);
        String next = prev.isEmpty() ? add : (prev.endsWith("\n") ? prev + add : prev + "\n" + add);
        fs.writeText(path, next);
        FsEvents.notifyChanged();
        JSONObject r = new JSONObject();
        int addLines = add.isEmpty() ? 0 : add.split("\n", -1).length;
        r.put("appendedLines", addLines);
        r.put("appendedChars", add.length());
        r.put("created", created);
        r.put("size", next.getBytes("UTF-8").length);
        r.put("path", path);
        r.put("diff", lineDiff(prev, next));
        return ok(r);
    }

    private JSONObject fsList(JSONObject a) throws IOException, JSONException {
        String path = norm(a.optString("path", "/"));
        if (!fs.exists(path)) return err("Путь не найден: " + a.optString("path"));
        if (!fs.stat(path).isDir) return err("Это файл, а не папка. Используй fs_read.");
        List<ExplorerFs.Node> kids = fs.listChildren(path);
        JSONArray entries = new JSONArray();
        int files = 0, dirs = 0;
        for (ExplorerFs.Node k : kids) {
            JSONObject e = new JSONObject();
            e.put("name", ExplorerFs.basename(k.path));
            e.put("path", k.path);
            e.put("type", "dir".equals(k.type) ? "dir" : "file");
            if ("dir".equals(k.type)) dirs++; else { files++; e.put("size", k.size); }
            entries.put(e);
        }
        JSONObject r = new JSONObject();
        r.put("entries", entries);
        r.put("files", files);
        r.put("dirs", dirs);
        r.put("path", path);
        return ok(r);
    }

    private JSONObject fsMkdir(JSONObject a) throws IOException, JSONException {
        String path = norm(a.optString("path"));
        if (fs.exists(path) && fs.stat(path).isDir) {
            JSONObject r = new JSONObject(); r.put("path", path); r.put("existed", true); return ok(r);
        }
        fs.ensureDir(path);
        FsEvents.notifyChanged();
        JSONObject r = new JSONObject(); r.put("path", path); return ok(r);
    }

    private JSONObject fsDelete(JSONObject a) throws IOException, JSONException {
        String path = norm(a.optString("path"));
        if (!fs.exists(path)) return err("Путь не найден: " + a.optString("path"));
        fs.delete(path);
        FsEvents.notifyChanged();
        JSONObject r = new JSONObject(); r.put("path", path); return ok(r);
    }

    private JSONObject fsRename(JSONObject a) throws IOException, JSONException {
        String from = norm(a.optString("from", a.optString("path")));
        String to = norm(a.optString("to"));
        if (!fs.exists(from)) return err("Источник не найден: " + a.optString("from"));
        fs.rename(from, to);
        FsEvents.notifyChanged();
        JSONObject r = new JSONObject(); r.put("from", from); r.put("to", to); return ok(r);
    }

    private JSONObject fsSearch(JSONObject a) throws IOException, JSONException {
        String query = a.optString("query", "");
        if (query.isEmpty()) return err("fs_search требует query.");
        boolean regex = a.optBoolean("regex", false);
        // Авто-regex: слабые модели (sarvam) часто пишут альтернацию через «|»
        // (например "license|check|licensed") БЕЗ regex:true, ожидая regex-семантику.
        // При подстроковом поиске это давало 0 совпадений → модель повторяла поиск
        // снова и снова и «зависала». Если query похож на regex-альтернацию (несколько
        // вариантов через «|», без пробелов вокруг «|») — трактуем как regex.
        if (!regex && query.contains("|") && query.matches("[^\\s|]+(\\|[^\\s|]+)+")) {
            regex = true;
        }
        String base = norm(a.optString("path", "/"));
        java.util.regex.Pattern pat = null;
        if (regex) { try { pat = java.util.regex.Pattern.compile(query); } catch (Exception e) { return err("Некорректный regex."); } }
        // Регистронезависимый поиск (порт web-версии): без флага 'i' слабые модели
        // (sarvam) искали "license", а в файле было "License" — 0 совпадений → модель
        // повторяла поиск снова и снова и «зависала». Теперь и подстрока, и regex
        // сравниваются без учёта регистра — как в web-версии (ln.toLowerCase()... / new RegExp(q,'i')).
        if (regex && pat != null) {
            try { pat = java.util.regex.Pattern.compile(query, java.util.regex.Pattern.CASE_INSENSITIVE); }
            catch (Exception e) { /* оставляем исходный pat */ }
        }
        String queryLc = query.toLowerCase(java.util.Locale.US);
        JSONArray hits = new JSONArray();
        int scanned = 0, count = 0;
        boolean truncated = false;
        List<ExplorerFs.Node> nodes = fs.listTree(base);
        for (ExplorerFs.Node n : nodes) {
            if (truncated) break;
            if (!"file".equals(n.type)) continue;
            // Пропускаем большие файлы (>500 КБ) — как в web-версии: на них поиск
            // мог занимать секунды/минуты и подвешивать шаг агента.
            if (n.size > 500 * 1024) { scanned++; continue; }
            scanned++;
            // ПОТОКОВОЕ чтение (построчно) вместо загрузки всего файла в память +
            // split (который создавал ещё одну полную копию массивом строк). При
            // поиске по многим/крупным файлам это устраняло риск OOM-вылета.
            java.io.BufferedReader br = null;
            try {
                br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(fs.openInput(n.path), "UTF-8"), 32768);
                String line; int i = 0; boolean binary = false;
                while ((line = br.readLine()) != null) {
                    if (i == 0 && line.indexOf('\0') >= 0) { binary = true; break; } // NUL → бинарный
                    i++;
                    boolean match = pat != null ? pat.matcher(line).find()
                            : line.toLowerCase(java.util.Locale.US).contains(queryLc);
                    if (match) {
                        JSONObject h = new JSONObject();
                        h.put("path", n.path);
                        h.put("line", i);
                        h.put("text", line.length() > 200 ? line.substring(0, 200) : line);
                        hits.put(h);
                        count++;
                        if (count >= 200) { truncated = true; break; }
                    }
                }
                if (binary) continue;
            } catch (Exception e) { continue; }
            finally { if (br != null) try { br.close(); } catch (Exception ignored) {} }
        }
        JSONObject r = new JSONObject();
        r.put("hits", hits);
        r.put("count", count);
        r.put("scanned", scanned);
        r.put("truncated", truncated);
        return ok(r);
    }

    // ------------------------------------------------------------- archives
    /**
     * Создать zip-архив из папки/файлов. Порт archive_create из web-версии.
     * args: { source | paths:[...] , archive }.
     *  - source: одна папка (или файл) — упаковывается её содержимое (пути
     *    относительно source, как в web archive_create);
     *  - paths: список путей (файлы/папки) — упаковываются с сохранением
     *    структуры относительно их общего корня.
     */
    private JSONObject archiveCreate(JSONObject a) throws JSONException {
        String archive = norm(a.optString("archive"));
        if (archive.equals("/") || archive.isEmpty())
            return err("archive_create требует путь к .zip (archive).");
        List<String> paths = new ArrayList<String>();
        String rootDir;
        if (a.has("paths")) {
            JSONArray arr = a.optJSONArray("paths");
            if (arr == null || arr.length() == 0) return err("paths пуст.");
            for (int i = 0; i < arr.length(); i++) {
                String p = norm(arr.optString(i));
                if (!fs.exists(p)) return err("Путь не найден: " + arr.optString(i));
                paths.add(p);
            }
            rootDir = commonParent(paths);
        } else {
            String source = norm(a.optString("source"));
            if (source.isEmpty() || !fs.exists(source)) return err("Папка/файл source не найдены: " + a.optString("source"));
            paths.add(source);
            // Для одной папки корень = сама папка (структура внутри неё сохраняется).
            try { rootDir = fs.stat(source).isDir ? source : ExplorerFs.dirname(source); }
            catch (Exception e) { rootDir = ExplorerFs.dirname(source); }
        }
        try {
            int count = ZipUtil.zipInto(fs, paths, rootDir, archive);
            FsEvents.notifyChanged();
            long size = 0;
            try { size = fs.stat(archive).size; } catch (Exception ignored) {}
            JSONObject r = new JSONObject();
            r.put("archive", archive);
            r.put("files", count);
            r.put("size", size);
            return ok(r);
        } catch (Exception e) {
            return err("Не удалось создать архив: " + e.getMessage());
        }
    }

    /** Распаковать zip в папку. Порт archive_extract из web-версии. args: { archive, to }. */
    private JSONObject archiveExtract(JSONObject a) throws JSONException {
        String archive = norm(a.optString("archive"));
        if (!fs.exists(archive)) return err("Архив не найден: " + a.optString("archive"));
        if ("dir".equals(safeType(archive))) return err("«" + a.optString("archive") + "» — это ПАПКА, не .zip.");
        String to = norm(a.optString("to", a.optString("dest", "/")));
        try {
            if (!to.equals("/")) fs.ensureDir(to);
            int n = ZipUtil.unzipInto(fs, archive, to);
            FsEvents.notifyChanged();
            JSONObject r = new JSONObject();
            r.put("extracted", n);
            r.put("to", to);
            return ok(r);
        } catch (Exception e) {
            return err("Не удалось распаковать архив: " + e.getMessage());
        }
    }

    /** Список содержимого zip БЕЗ распаковки. Порт archive_list из web-версии. args: { archive }. */
    private JSONObject archiveList(JSONObject a) throws JSONException {
        String archive = norm(a.optString("archive"));
        if (!fs.exists(archive)) return err("Архив не найден: " + a.optString("archive"));
        try {
            List<ZipUtil.Entry> entries = ZipUtil.listEntries(fs, archive);
            JSONArray arr = new JSONArray();
            int files = 0, dirs = 0;
            long total = 0;
            for (ZipUtil.Entry e : entries) {
                JSONObject o = new JSONObject();
                o.put("path", e.name);
                o.put("is_dir", e.isDir);
                o.put("size", e.size);
                arr.put(o);
                if (e.isDir) dirs++; else { files++; total += e.size; }
            }
            long asize = 0;
            try { asize = fs.stat(archive).size; } catch (Exception ignored) {}
            JSONObject r = new JSONObject();
            r.put("archive", archive);
            r.put("archive_size", asize);
            r.put("total_uncompressed", total);
            r.put("file_count", files);
            r.put("dir_count", dirs);
            r.put("entries", arr);
            return ok(r);
        } catch (Exception e) {
            return err("Не удалось прочитать архив: " + e.getMessage());
        }
    }

    /**
     * Прочитать одну запись внутри zip без полной распаковки.
     * Порт archive_read_entry из web-версии. args: { archive, entry, [as], [max_bytes] }.
     *  - текстовые записи → { encoding:"text", text };
     *  - картинки → { encoding:"data_url", data_url } + поле _image (vision);
     *  - прочие бинарные → { encoding:"base64", base64 }.
     */
    private JSONObject archiveReadEntry(JSONObject a) throws JSONException {
        String archive = norm(a.optString("archive"));
        String entry = a.optString("entry", "");
        if (entry.isEmpty()) return err("archive_read_entry требует entry.");
        if (!fs.exists(archive)) return err("Архив не найден: " + a.optString("archive"));
        if (entry.endsWith("/")) return err("«" + entry + "» — это папка, не файл.");
        int limit = a.has("max_bytes") ? Math.max(0, a.optInt("max_bytes")) : 200000;
        String as = a.optString("as", "");
        try {
            byte[] bytes = ZipUtil.readEntry(fs, archive, entry);
            if (bytes == null) {
                List<ZipUtil.Entry> es = ZipUtil.listEntries(fs, archive);
                StringBuilder avail = new StringBuilder();
                int shown = 0;
                for (ZipUtil.Entry e : es) {
                    if (e.isDir) continue;
                    if (shown++ > 0) avail.append(", ");
                    avail.append(e.name);
                    if (shown >= 30) { avail.append(" …"); break; }
                }
                return err("В архиве нет записи «" + entry + "». Доступны: " + avail);
            }
            int size = bytes.length;
            String imgMime = sniffImageMime(bytes);
            String mode = as;
            if (!"text".equals(mode) && !"binary".equals(mode) && !"media".equals(mode)) {
                if (imgMime != null) mode = "media";
                else mode = looksBinary(bytes) ? "binary" : "text";
            }
            JSONObject r = new JSONObject();
            r.put("archive", archive);
            r.put("entry", entry);
            r.put("size", size);
            r.put("name", ExplorerFs.basename(entry));
            if ("media".equals(mode) && imgMime != null) {
                // Даунскейлим большие картинки из архива в JPEG для vision (как в web).
                String[] img = imageToVisionDataUrl(bytes, 1600);
                String dataUrl = img != null ? img[1]
                        : "data:" + imgMime + ";base64," + Base64.encodeToString(bytes, Base64.NO_WRAP);
                String mime = img != null ? img[0] : imgMime;
                r.put("encoding", "data_url");
                r.put("mime", mime);
                r.put("kind", "image");
                r.put("data_url", dataUrl);
                r.put("_image", dataUrl); // Agent прикрепит к следующему vision-запросу
                return ok(r);
            }
            if ("text".equals(mode)) {
                String text = new String(bytes, "UTF-8");
                boolean truncated = text.length() > limit;
                if (truncated) text = text.substring(0, limit);
                r.put("encoding", "text");
                r.put("text", text);
                r.put("truncated", truncated);
                return ok(r);
            }
            // binary / прочее
            r.put("encoding", "base64");
            r.put("mime", imgMime != null ? imgMime : "application/octet-stream");
            r.put("base64", Base64.encodeToString(bytes, Base64.NO_WRAP));
            return ok(r);
        } catch (Exception e) {
            return err("Не удалось прочитать запись: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------- web / internet
    /** Поиск в интернете по всем поисковикам (порт web_search из web-версии). */
    private JSONObject webSearch(JSONObject a) {
        String query = a.optString("query", "");
        int limit = a.has("limit") ? a.optInt("limit") : 10;
        JSONArray sources = a.optJSONArray("sources"); // null → все источники
        return WebSearch.search(query, limit, sources);
    }

    /** Скачать содержимое URL (порт http_fetch из web-версии). */
    private JSONObject httpFetch(JSONObject a) {
        String url = a.optString("url", "");
        String method = a.optString("method", "GET");
        JSONObject headers = a.optJSONObject("headers");
        String body = a.has("body") ? a.optString("body") : null;
        boolean forceText = a.optBoolean("force_text", false);
        return WebSearch.httpFetch(url, method, headers, body, forceText);
    }

    /** Общий родитель списка путей (для сохранения структуры в архиве). */
    private static String commonParent(List<String> paths) {
        if (paths.isEmpty()) return "/";
        String[] first = ExplorerFs.dirname(paths.get(0)).split("/");
        int common = first.length;
        for (int i = 1; i < paths.size(); i++) {
            String[] cur = ExplorerFs.dirname(paths.get(i)).split("/");
            int k = 0;
            while (k < common && k < cur.length && first[k].equals(cur[k])) k++;
            common = k;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < common; i++) sb.append('/').append(first[i]);
        return sb.length() == 0 ? "/" : sb.toString();
    }

    private String safeType(String path) {
        try { return fs.stat(path).isDir ? "dir" : "file"; } catch (Exception e) { return "file"; }
    }

    /** Эвристика «похоже на бинарный» (порт U.looksBinary): наличие NUL в первых 4КБ. */
    private static boolean looksBinary(byte[] d) {
        int n = Math.min(d.length, 4096);
        for (int i = 0; i < n; i++) if (d[i] == 0) return true;
        return false;
    }

    // ------------------------------------------------------------- diff
    /**
     * Построчный diff (LCS). Возвращает { added, removed, hunks:[{type,text,oldNo,newNo}] }.
     * type: "ctx"|"add"|"del". Порт lineDiff() из tools.js.
     */
    static JSONObject lineDiff(String oldText, String newText) {
        JSONObject out = new JSONObject();
        try {
            String[] a = (oldText == null ? "" : oldText).split("\n", -1);
            String[] b = (newText == null ? "" : newText).split("\n", -1);
            if (oldText == null || oldText.isEmpty()) a = new String[0];
            int n = a.length, m = b.length;
            if (n + m > 6000) {
                out.put("added", Math.max(0, m - n));
                out.put("removed", Math.max(0, n - m));
                out.put("approx", true);
                return out;
            }
            int[][] dp = new int[n + 1][m + 1];
            for (int i = n - 1; i >= 0; i--)
                for (int j = m - 1; j >= 0; j--)
                    dp[i][j] = a[i].equals(b[j]) ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1]);
            JSONArray hunks = new JSONArray();
            int i = 0, j = 0, added = 0, removed = 0;
            while (i < n && j < m) {
                if (a[i].equals(b[j])) { hunks.put(hunk("ctx", a[i], i + 1, j + 1)); i++; j++; }
                else if (dp[i + 1][j] >= dp[i][j + 1]) { hunks.put(hunk("del", a[i], i + 1, 0)); removed++; i++; }
                else { hunks.put(hunk("add", b[j], 0, j + 1)); added++; j++; }
            }
            while (i < n) { hunks.put(hunk("del", a[i], i + 1, 0)); removed++; i++; }
            while (j < m) { hunks.put(hunk("add", b[j], 0, j + 1)); added++; j++; }
            out.put("added", added);
            out.put("removed", removed);
            out.put("hunks", hunks);
        } catch (JSONException ignored) {}
        return out;
    }

    private static JSONObject hunk(String type, String text, int oldNo, int newNo) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("type", type);
        o.put("text", text);
        if (oldNo > 0) o.put("oldNo", oldNo);
        if (newNo > 0) o.put("newNo", newNo);
        return o;
    }

    private static String mimeForExt(String e) {
        e = e == null ? "" : e.toLowerCase();
        if (e.equals("png")) return "image/png";
        if (e.equals("gif")) return "image/gif";
        if (e.equals("webp")) return "image/webp";
        if (e.equals("bmp")) return "image/bmp";
        if (e.equals("jpg") || e.equals("jpeg")) return "image/jpeg";
        if (e.equals("svg")) return "image/svg+xml";
        return "application/octet-stream";
    }

    // ------------------------------------------------------------- result
    static JSONObject ok(JSONObject result) {
        try { return new JSONObject().put("ok", true).put("result", result); }
        catch (JSONException e) { return new JSONObject(); }
    }
    static JSONObject err(String msg) {
        try { return new JSONObject().put("ok", false).put("error", msg == null ? "ошибка" : msg); }
        catch (JSONException e) { return new JSONObject(); }
    }
}
