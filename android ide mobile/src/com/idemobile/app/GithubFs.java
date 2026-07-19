package com.idemobile.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Виртуальная файловая система поверх GitHub Contents/Git-Data API.
 * Порт github-fs.js. Когда активный репозиторий в режиме remote, путь
 *   /gh/&lt;owner&gt;/&lt;repo&gt;/...
 * прозрачно проксируется на GitHub. Все read/list/write/delete/rename по
 * этому корню перехватываются {@link ExplorerFs} до обращения к локальной ФС.
 *
 * Ключевые свойства (как в web):
 *  - Дерево грузится один раз (git/trees/{ref}?recursive=1) и кешируется.
 *  - Файлы читаются лениво (git/blobs по sha) и кешируются в памяти.
 *  - Записи КОММИТЯТСЯ СРАЗУ через Contents API — каждая запись = коммит на сайте.
 *  - Удаление требует sha (берётся из дерева).
 *  - Rename = запись-в-новый-путь + удаление-старого (два коммита).
 *  - Пустых папок в GitHub нет: mkdir создаёт placeholder, чтобы проводник
 *    показывал папку до первой записи файла в неё.
 *
 * Синглтон: активный mount один (как и в web UI).
 */
public final class GithubFs {

    private static GithubFs INSTANCE;
    public static synchronized GithubFs get(Context ctx) {
        if (INSTANCE == null) INSTANCE = new GithubFs(ctx);
        return INSTANCE;
    }

    private final Store store;

    /** Запись дерева. */
    static class Entry {
        String type;   // "blob" | "tree"
        String sha;
        long size;
        Entry(String type, String sha, long size) { this.type = type; this.sha = sha; this.size = size; }
    }

    /** Один смонтированный репозиторий. */
    static class Mount {
        String owner, repo, branch;
        Map<String, Entry> tree = new LinkedHashMap<String, Entry>(); // repoRel → Entry
        Map<String, byte[]> files = new LinkedHashMap<String, byte[]>(); // repoRel → data (кеш)
        Set<String> placeholderDirs = new TreeSet<String>();
        boolean loaded = false;
        boolean empty = false;
        volatile boolean loading = false;
        volatile String loadError = null;
    }

    private final Map<String, Mount> mounts = new LinkedHashMap<String, Mount>();

    private GithubFs(Context ctx) {
        store = Store.get(ctx);
    }

    // --------------------------------------------------------------- active mount
    /** Корень активного remote-репозитория (/gh/owner/repo) или null. */
    public String activeRoot() {
        JSONObject ar = store.activeRepo();
        if (ar == null || !"remote".equals(ar.optString("mode"))) return null;
        return ar.optString("virtualRoot", null);
    }
    private Mount activeMount() {
        String r = activeRoot();
        return r == null ? null : mounts.get(r);
    }

    /**
     * Гарантирует, что активный репозиторий смонтирован и его дерево загружено.
     * Ключевой фикс: после ПЕРЕЗАПУСКА приложения activeRepo восстанавливается из
     * Store, но mounts (в памяти) пуст — дерево нужно подгрузить заново. Если mount
     * ещё не загружен, запускаем фоновую загрузку и по завершении дёргаем
     * FsEvents.notifyChanged(), чтобы проводник перерисовался с файлами.
     * Возвращает true, если mount УЖЕ готов (дерево доступно синхронно).
     */
    public boolean ensureActiveLoaded() {
        JSONObject ar = store.activeRepo();
        if (ar == null || !"remote".equals(ar.optString("mode"))) return false;
        final String root = ar.optString("virtualRoot", null);
        if (root == null) return false;
        Mount m = mounts.get(root);
        if (m != null && m.loaded) return true;
        final String owner = ar.optString("owner");
        final String repo = ar.optString("repo");
        final String branch = ar.optString("branch", "main");
        if (m == null) {
            m = new Mount();
            m.owner = owner; m.repo = repo; m.branch = branch;
            mounts.put(root, m);
        }
        if (m.loading) return false; // уже грузится — ждём
        m.loading = true;
        final Mount mm = m;
        new Thread(new Runnable() {
            public void run() {
                try {
                    load(mm);
                } catch (Exception e) {
                    mm.loadError = e.getMessage();
                } finally {
                    mm.loading = false;
                    // Перерисовать проводник в UI-потоке.
                    FsEvents.notifyChanged();
                }
            }
        }).start();
        return false;
    }
    /** Является ли путь частью активного remote-корня. */
    public boolean isRemotePath(String p) {
        String root = activeRoot();
        if (root == null) return false;
        p = normPath(p);
        return p.equals(root) || p.startsWith(root + "/");
    }

    static String normPath(String p) {
        if (p == null || p.isEmpty()) return "/";
        if (!p.startsWith("/")) p = "/" + p;
        p = p.replaceAll("/+", "/");
        if (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p;
    }
    private String relInRepo(String p, String root) {
        p = normPath(p);
        if (p.equals(root)) return "";
        return p.substring(root.length() + 1);
    }

    // --------------------------------------------------------------- mount / load
    /** Смонтировать репозиторий и загрузить дерево. Возвращает virtualRoot. */
    public String mount(String owner, String repo, String branch) throws Exception {
        String root = "/gh/" + owner + "/" + repo;
        Mount m = mounts.get(root);
        if (m == null) {
            m = new Mount();
            m.owner = owner; m.repo = repo; m.branch = branch;
            mounts.put(root, m);
        } else if (!branch.equals(m.branch)) {
            m.branch = branch;
            m.tree.clear(); m.files.clear(); m.placeholderDirs.clear(); m.loaded = false;
        }
        if (!m.loaded) load(m);
        return root;
    }
    public void unmount(String root) { if (root != null) mounts.remove(root); }

    private void load(Mount m) throws Exception {
        String token = store.githubToken();
        JSONObject j = GithubApi.fetchTree(token, m.owner, m.repo, m.branch);
        m.tree.clear();
        m.empty = j.optBoolean("__empty", false);
        JSONArray arr = j.optJSONArray("tree");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject it = arr.optJSONObject(i);
                if (it == null) continue;
                m.tree.put(it.optString("path"),
                        new Entry(it.optString("type"), it.optString("sha", null), it.optLong("size", 0)));
            }
        }
        m.loaded = true;
    }

    /** Перечитать дерево активного mount с GitHub (сбросить кеш). */
    public void refresh() throws Exception {
        Mount m = activeMount();
        if (m == null) return;
        m.tree.clear(); m.files.clear(); m.loaded = false;
        load(m);
    }

    // --------------------------------------------------------------- introspection
    /** stat: возвращает {"dir"|"file", size} или null. */
    private Object[] statSync(Mount m, String rel) {
        if (rel.isEmpty()) return new Object[]{ "dir", 0L };
        if (m.placeholderDirs.contains(rel)) return new Object[]{ "dir", 0L };
        Entry t = m.tree.get(rel);
        if (t != null) {
            if ("blob".equals(t.type)) return new Object[]{ "file", t.size };
            if ("tree".equals(t.type)) return new Object[]{ "dir", 0L };
        }
        for (String p : m.tree.keySet())
            if (p.startsWith(rel + "/")) return new Object[]{ "dir", 0L };
        return null;
    }

    public boolean exists(String vpath) {
        Mount m = activeMount();
        if (m == null) return false;
        return statSync(m, relInRepo(vpath, activeRoot())) != null;
    }

    public ExplorerFs.Stat stat(String vpath) throws Exception {
        Mount m = activeMount();
        if (m == null) throw new Exception("Нет активного репозитория");
        Object[] st = statSync(m, relInRepo(vpath, activeRoot()));
        if (st == null) throw new Exception("Путь не найден: " + vpath);
        ExplorerFs.Stat s = new ExplorerFs.Stat();
        s.isDir = "dir".equals(st[0]);
        s.size = (Long) st[1];
        s.mtime = System.currentTimeMillis();
        s.ctime = s.mtime;
        return s;
    }

    /** Прямые дети repoRel (rel="" — корень репо). name → {"dir"|"file", size}. */
    private Map<String, Object[]> childrenOf(Mount m, String rel) {
        String prefix = rel.isEmpty() ? "" : rel + "/";
        Map<String, Object[]> seen = new LinkedHashMap<String, Object[]>();
        for (Map.Entry<String, Entry> e : m.tree.entrySet()) {
            String p = e.getKey();
            if (!p.startsWith(prefix)) continue;
            String rest = p.substring(prefix.length());
            if (rest.isEmpty()) continue;
            int slash = rest.indexOf('/');
            if (slash == -1) {
                Entry info = e.getValue();
                seen.put(rest, new Object[]{ "blob".equals(info.type) ? "file" : "dir", info.size });
            } else {
                String name = rest.substring(0, slash);
                if (!seen.containsKey(name)) seen.put(name, new Object[]{ "dir", 0L });
            }
        }
        for (String p : m.placeholderDirs) {
            if (!p.startsWith(prefix)) continue;
            String rest = p.substring(prefix.length());
            if (rest.isEmpty() || rest.indexOf('/') != -1) continue;
            if (!seen.containsKey(rest)) seen.put(rest, new Object[]{ "dir", 0L });
        }
        return seen;
    }

    /** Дети как список Node (для проводника). Папки сначала, по алфавиту. */
    public List<ExplorerFs.Node> listChildren(String vpath) {
        List<ExplorerFs.Node> out = new ArrayList<ExplorerFs.Node>();
        Mount m = activeMount();
        if (m == null) return out;
        String root = activeRoot();
        String rel = relInRepo(vpath, root);
        final Map<String, Object[]> kids = childrenOf(m, rel);
        List<String> names = new ArrayList<String>(kids.keySet());
        Collections.sort(names, new java.util.Comparator<String>() {
            public int compare(String a, String b) {
                boolean da = "dir".equals(kids.get(a)[0]), db = "dir".equals(kids.get(b)[0]);
                if (da != db) return da ? -1 : 1;
                return a.compareToIgnoreCase(b);
            }
        });
        for (String nm : names) {
            Object[] info = kids.get(nm);
            String childPath = normPath(vpath + "/" + nm);
            out.add(new ExplorerFs.Node(childPath, "dir".equals(info[0]) ? "dir" : "file", (Long) info[1]));
        }
        return out;
    }

    /** Рекурсивный список всех узлов под vpath (для listTree). */
    public List<ExplorerFs.Node> listTree(String vpath) {
        List<ExplorerFs.Node> out = new ArrayList<ExplorerFs.Node>();
        walk(vpath, out);
        return out;
    }
    private void walk(String vpath, List<ExplorerFs.Node> out) {
        for (ExplorerFs.Node n : listChildren(vpath)) {
            out.add(n);
            if ("dir".equals(n.type)) walk(n.path, out);
        }
    }

    // --------------------------------------------------------------- reads
    public byte[] readBytes(String vpath) throws Exception {
        Mount m = activeMount();
        if (m == null) throw new Exception("Нет активного репозитория");
        String root = activeRoot();
        String rel = relInRepo(vpath, root);
        byte[] cached = m.files.get(rel);
        if (cached != null) return cached;
        String token = store.githubToken();
        Entry entry = m.tree.get(rel);
        if (entry != null && entry.sha != null && "blob".equals(entry.type)) {
            byte[] bytes = GithubApi.fetchBlob(token, m.owner, m.repo, entry.sha);
            cachePut(m, rel, bytes);
            return bytes;
        }
        // Fallback: Contents API
        Object[] res = GithubApi.fetchContents(token, m.owner, m.repo, rel, m.branch);
        byte[] bytes = (byte[]) res[0];
        String sha = (String) res[1];
        m.tree.put(rel, new Entry("blob", sha, bytes.length));
        cachePut(m, rel, bytes);
        return bytes;
    }

    /**
     * Максимальный размер файла, который кешируем в памяти (в байтах).
     * Большие файлы (например скачиваемые архивы) НЕ кешируем — иначе они
     * копятся в Mount.files и приводят к OutOfMemoryError. Кеш нужен лишь
     * для быстрого повторного открытия небольших (обычно текстовых) файлов.
     */
    private static final int MAX_CACHE_BYTES = 2 * 1024 * 1024; // 2 МБ

    private static void cachePut(Mount m, String rel, byte[] bytes) {
        if (bytes != null && bytes.length <= MAX_CACHE_BYTES) {
            m.files.put(rel, bytes);
        } else {
            // Крупный файл — не держим в памяти, чтобы не спровоцировать OOM.
            m.files.remove(rel);
        }
    }

    // --------------------------------------------------------------- writes (commit-per-op)
    public void writeBytes(String vpath, byte[] data) throws Exception {
        Mount m = activeMount();
        if (m == null) throw new Exception("Нет активного репозитория");
        if (data == null) data = new byte[0];
        String root = activeRoot();
        String rel = relInRepo(vpath, root);
        String token = store.githubToken();
        Entry existing = m.tree.get(rel);
        String existingSha = (existing != null && "blob".equals(existing.type)) ? existing.sha : null;
        String newSha = GithubApi.putContents(token, m.owner, m.repo, rel, data, m.branch, existingSha);
        m.tree.put(rel, new Entry("blob", newSha, data.length));
        m.files.put(rel, data);
        // Убираем placeholder-папки, которые стали неявными
        for (String d : new ArrayList<String>(m.placeholderDirs))
            if (rel.equals(d) || rel.startsWith(d + "/")) m.placeholderDirs.remove(d);
    }

    /** Удалить (файл или рекурсивно папку). */
    public void delete(String vpath) throws Exception {
        Mount m = activeMount();
        if (m == null) throw new Exception("Нет активного репозитория");
        String root = activeRoot();
        String rel = relInRepo(vpath, root);
        if (rel.isEmpty()) throw new Exception("Нельзя удалить корень репозитория");
        String token = store.githubToken();
        Entry entry = m.tree.get(rel);
        if (entry != null && "blob".equals(entry.type)) {
            GithubApi.deleteContents(token, m.owner, m.repo, rel, entry.sha, m.branch);
            m.tree.remove(rel); m.files.remove(rel);
            return;
        }
        // Папка: удалить все blob под rel
        List<String> victims = new ArrayList<String>();
        for (Map.Entry<String, Entry> e : m.tree.entrySet())
            if ("blob".equals(e.getValue().type) && (e.getKey().equals(rel) || e.getKey().startsWith(rel + "/")))
                victims.add(e.getKey());
        if (victims.isEmpty() && !m.placeholderDirs.contains(rel) && statSync(m, rel) == null)
            throw new Exception("Путь не найден: " + vpath);
        for (String p : victims) {
            Entry e = m.tree.get(p);
            GithubApi.deleteContents(token, m.owner, m.repo, p, e.sha, m.branch);
            m.tree.remove(p); m.files.remove(p);
        }
        m.placeholderDirs.remove(rel);
        for (String d : new ArrayList<String>(m.placeholderDirs))
            if (d.startsWith(rel + "/")) m.placeholderDirs.remove(d);
    }

    public void ensureDir(String vpath) throws Exception {
        Mount m = activeMount();
        if (m == null) throw new Exception("Нет активного репозитория");
        String rel = relInRepo(vpath, activeRoot());
        if (rel.isEmpty()) return;
        // GitHub не хранит пустые папки — регистрируем placeholder для проводника
        String[] parts = rel.split("/");
        StringBuilder acc = new StringBuilder();
        for (String seg : parts) {
            if (seg.isEmpty()) continue;
            if (acc.length() > 0) acc.append('/');
            acc.append(seg);
            if (statSync(m, acc.toString()) == null) m.placeholderDirs.add(acc.toString());
        }
    }

    /** Rename = копирование + удаление (для файла и рекурсивно для папки). */
    public void rename(String fromV, String toV) throws Exception {
        Mount m = activeMount();
        if (m == null) throw new Exception("Нет активного репозитория");
        String root = activeRoot();
        String fromR = relInRepo(fromV, root);
        String toR = relInRepo(toV, root);
        if (fromR.equals(toR)) return;
        Entry fromEntry = m.tree.get(fromR);
        if (fromEntry != null && "blob".equals(fromEntry.type)) {
            byte[] data = readBytes(fromV);
            writeBytes(toV, data);
            delete(fromV);
            return;
        }
        // Папка
        List<String> under = new ArrayList<String>();
        for (Map.Entry<String, Entry> e : m.tree.entrySet())
            if ("blob".equals(e.getValue().type) && (e.getKey().equals(fromR) || e.getKey().startsWith(fromR + "/")))
                under.add(e.getKey());
        String token = store.githubToken();
        for (String p : under) {
            String newRel = toR + p.substring(fromR.length());
            byte[] data = readBytes(root + "/" + p);
            writeBytes(root + "/" + newRel, data);
            Entry e = m.tree.get(p);
            GithubApi.deleteContents(token, m.owner, m.repo, p, e.sha, m.branch);
            m.tree.remove(p); m.files.remove(p);
        }
        if (m.placeholderDirs.remove(fromR)) m.placeholderDirs.add(toR);
    }

    /** Копировать (файл или папку) внутрь целевой папки. */
    public void copyInto(String srcPath, String dstDir) throws Exception {
        Mount m = activeMount();
        if (m == null) throw new Exception("Нет активного репозитория");
        String root = activeRoot();
        String srcRel = relInRepo(srcPath, root);
        String base = ExplorerFs.basename(srcPath);
        Entry srcEntry = m.tree.get(srcRel);
        if (srcEntry != null && "blob".equals(srcEntry.type)) {
            byte[] data = readBytes(srcPath);
            writeBytes(ExplorerFs.joinPath(dstDir, base), data);
            return;
        }
        // Папка: копируем все blob c сохранением структуры
        List<String> under = new ArrayList<String>();
        for (Map.Entry<String, Entry> e : m.tree.entrySet())
            if ("blob".equals(e.getValue().type) && (e.getKey().equals(srcRel) || e.getKey().startsWith(srcRel + "/")))
                under.add(e.getKey());
        for (String p : under) {
            String tail = p.substring(srcRel.length()); // /a/b.txt
            String dst = ExplorerFs.joinPath(dstDir, base + tail);
            byte[] data = readBytes(root + "/" + p);
            writeBytes(dst, data);
        }
    }
}
