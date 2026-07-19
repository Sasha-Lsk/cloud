package com.idemobile.app;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Файловая система вкладок «Проводник» и «Редактор». Два режима:
 *
 *   INTERNAL — внутренняя рабочая папка приложения getFilesDir()/workspace.
 *              Та же, что использует ИИ-агент (FsTool) → файлы общие.
 *
 *   TREE     — РЕАЛЬНАЯ папка на устройстве, открытая пользователем через
 *              системный выбор папки (ACTION_OPEN_DOCUMENT_TREE / SAF).
 *              Все операции идут напрямую на устройство — синхронно.
 *              Разрешение сохраняется постоянно (takePersistableUriPermission),
 *              поэтому папка остаётся открытой после перезапуска.
 *
 * TREE реализован на чистом DocumentsContract + ContentResolver, БЕЗ внешних
 * библиотек (androidx.documentfile не требуется) — проект собирается только
 * на android framework, как остальной код.
 *
 * Виртуальные пути начинаются с «/» и относятся к корню активного источника.
 */
public class ExplorerFs {

    public static final int MODE_INTERNAL = 0;
    public static final int MODE_TREE = 1;

    private static final String PREF = "mide.explorer.v1";
    private static final String KEY_MODE = "mode";
    private static final String KEY_TREE_URI = "treeUri";
    private static final String KEY_TREE_NAME = "treeName";

    public static class Node {
        public String path;
        public String type;   // "dir" | "file"
        public long size;
        public Node(String p, String t, long s) { path = p; type = t; size = s; }
    }

    public static class Stat {
        public boolean isDir;
        public long size;
        public long ctime;
        public long mtime;
    }

    private final Context ctx;
    private final ContentResolver cr;
    private final SharedPreferences sp;
    private final File rootDir;
    private final GithubFs ghfs;

    private int mode = MODE_INTERNAL;
    private Uri treeUri = null;          // общий tree-uri (для permission)
    private String treeName = null;
    private String rootDocId = null;     // documentId корня дерева

    public ExplorerFs(Context c) {
        ctx = c.getApplicationContext();
        cr = ctx.getContentResolver();
        sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        rootDir = new File(ctx.getFilesDir(), "workspace");
        if (!rootDir.exists()) rootDir.mkdirs();
        ghfs = GithubFs.get(ctx);
        restore();
    }

    /**
     * Путь принадлежит смонтированному GitHub-репозиторию (/gh/owner/repo/…)?
     * Если да — операции над ним проксируются в {@link GithubFs} (синхронно на
     * сайт GitHub), а не в локальную/SAF ФС. Это работает независимо от текущего
     * MODE_INTERNAL/MODE_TREE — GitHub-корень «наложен» поверх.
     */
    private boolean isGh(String vpath) {
        try { return ghfs.isRemotePath(vpath); } catch (Throwable e) { return false; }
    }
    /** Есть ли активный remote-репозиторий (для показа /gh в корне проводника). */
    private boolean hasGhMount() {
        try { return ghfs.activeRoot() != null; } catch (Throwable e) { return false; }
    }

    // ------------------------------------------------------------- source mgmt
    private void restore() {
        mode = sp.getInt(KEY_MODE, MODE_INTERNAL);
        String u = sp.getString(KEY_TREE_URI, null);
        treeName = sp.getString(KEY_TREE_NAME, null);
        if (mode == MODE_TREE && u != null) {
            treeUri = Uri.parse(u);
            boolean ok = false;
            try {
                for (UriPermission p : cr.getPersistedUriPermissions()) {
                    if (p.getUri().equals(treeUri) && p.isReadPermission()) { ok = true; break; }
                }
            } catch (Exception e) {}
            if (ok) {
                try { rootDocId = DocumentsContract.getTreeDocumentId(treeUri); }
                catch (Exception e) { rootDocId = null; }
            }
            if (rootDocId == null) useInternal();
        } else {
            mode = MODE_INTERNAL;
        }
    }

    public int mode() { return mode; }
    public boolean isTree() { return mode == MODE_TREE && treeUri != null && rootDocId != null; }
    public String treeName() { return treeName; }

    public void useInternal() {
        mode = MODE_INTERNAL;
        treeUri = null; rootDocId = null; treeName = null;
        sp.edit().putInt(KEY_MODE, MODE_INTERNAL).remove(KEY_TREE_URI).remove(KEY_TREE_NAME).apply();
    }

    /** Открыть реальную папку по URI из ACTION_OPEN_DOCUMENT_TREE. */
    public boolean openTree(Uri uri) {
        try {
            final int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
            cr.takePersistableUriPermission(uri, flags);
        } catch (Exception e) {}
        try {
            rootDocId = DocumentsContract.getTreeDocumentId(uri);
        } catch (Exception e) { return false; }
        if (rootDocId == null) return false;
        treeUri = uri;
        mode = MODE_TREE;
        treeName = queryName(docUri(rootDocId));
        if (treeName == null) treeName = "папка";
        sp.edit().putInt(KEY_MODE, MODE_TREE)
                .putString(KEY_TREE_URI, uri.toString())
                .putString(KEY_TREE_NAME, treeName).apply();
        return true;
    }

    public File root() { return rootDir; }

    // ------------------------------------------------------------- path utils
    public static String dirname(String p) {
        if (p == null || p.equals("/")) return "/";
        int i = p.lastIndexOf('/');
        return i <= 0 ? "/" : p.substring(0, i);
    }
    public static String basename(String p) {
        if (p == null) return "";
        int i = p.lastIndexOf('/');
        return i < 0 ? p : p.substring(i + 1);
    }
    public static String extname(String p) {
        String b = basename(p);
        int i = b.lastIndexOf('.');
        return i <= 0 ? "" : b.substring(i + 1).toLowerCase();
    }
    public static String normPath(String p) {
        if (p == null || p.isEmpty()) return "/";
        String[] parts = p.split("/");
        ArrayList<String> out = new ArrayList<String>();
        for (String s : parts) {
            if (s.isEmpty() || s.equals(".")) continue;
            if (s.equals("..")) { if (!out.isEmpty()) out.remove(out.size() - 1); continue; }
            out.add(s);
        }
        StringBuilder sb = new StringBuilder();
        for (String s : out) sb.append('/').append(s);
        return sb.length() == 0 ? "/" : sb.toString();
    }
    public static String joinPath(String a, String b) {
        return normPath((a == null ? "/" : a) + "/" + (b == null ? "" : b));
    }
    public static String bytesToStr(long n) {
        if (n < 1024) return n + " B";
        if (n < 1024 * 1024) return String.format(java.util.Locale.US, "%.1f KB", n / 1024.0);
        return String.format(java.util.Locale.US, "%.1f MB", n / 1024.0 / 1024.0);
    }

    private String[] segs(String vpath) {
        List<String> out = new ArrayList<String>();
        for (String s : (vpath == null ? "" : vpath).split("/")) if (!s.isEmpty()) out.add(s);
        return out.toArray(new String[0]);
    }

    // ------------------------------------------------------------- INTERNAL resolve
    private File resolveFileInternal(String vpath) throws IOException {
        String rel = (vpath == null ? "/" : vpath).replaceFirst("^/+", "");
        File f = new File(rootDir, rel);
        String canonRoot = rootDir.getCanonicalPath();
        String canon = f.getCanonicalPath();
        if (!canon.equals(canonRoot) && !canon.startsWith(canonRoot + File.separator))
            throw new IOException("Путь вне рабочей папки: " + vpath);
        return f;
    }
    private String vpathInternal(File f) {
        String r = rootDir.getAbsolutePath();
        String a = f.getAbsolutePath();
        String rel = a.length() > r.length() ? a.substring(r.length()) : "";
        rel = rel.replace(File.separatorChar, '/');
        if (!rel.startsWith("/")) rel = "/" + rel;
        return rel;
    }

    // ------------------------------------------------------------- TREE (DocumentsContract)
    /** Построить document Uri по documentId. */
    private Uri docUri(String docId) {
        return DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
    }
    /** Uri для перечисления детей папки docId. */
    private Uri childrenUri(String docId) {
        return DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId);
    }

    private static class Doc {
        String id; String name; boolean isDir; long size; long mtime;
    }

    /** Дети папки docId. */
    private List<Doc> childDocs(String parentDocId) {
        List<Doc> out = new ArrayList<Doc>();
        Cursor c = null;
        try {
            c = cr.query(childrenUri(parentDocId), new String[]{
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            }, null, null, null);
            while (c != null && c.moveToNext()) {
                Doc d = new Doc();
                d.id = c.getString(0);
                d.name = c.getString(1);
                String mime = c.getString(2);
                d.isDir = DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
                d.size = c.isNull(3) ? 0 : c.getLong(3);
                d.mtime = c.isNull(4) ? 0 : c.getLong(4);
                out.add(d);
            }
        } catch (Exception e) {
        } finally { if (c != null) c.close(); }
        return out;
    }

    /** Найти документ по виртуальному пути (без создания). */
    private Doc resolveDoc(String vpath) {
        String[] parts = segs(vpath);
        // корень
        Doc root = new Doc();
        root.id = rootDocId; root.isDir = true; root.name = treeName;
        Doc cur = root;
        for (String s : parts) {
            if (!cur.isDir) return null;
            Doc found = null;
            for (Doc d : childDocs(cur.id)) {
                if (s.equals(d.name)) { found = d; break; }
            }
            if (found == null) return null;
            cur = found;
        }
        return cur;
    }

    /** Найти/создать папку по виртуальному пути. Возвращает docId папки. */
    private String ensureDocDir(String vpath) {
        String curId = rootDocId;
        for (String s : segs(vpath)) {
            Doc found = null;
            for (Doc d : childDocs(curId)) if (s.equals(d.name)) { found = d; break; }
            if (found == null) {
                try {
                    Uri created = DocumentsContract.createDocument(cr, docUri(curId),
                            DocumentsContract.Document.MIME_TYPE_DIR, s);
                    if (created == null) return null;
                    curId = DocumentsContract.getDocumentId(created);
                } catch (Exception e) { return null; }
            } else {
                if (!found.isDir) return null;
                curId = found.id;
            }
        }
        return curId;
    }

    private String queryName(Uri docU) {
        Cursor c = null;
        try {
            c = cr.query(docU, new String[]{ DocumentsContract.Document.COLUMN_DISPLAY_NAME }, null, null, null);
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception e) {} finally { if (c != null) c.close(); }
        return null;
    }

    // ------------------------------------------------------------- queries
    public boolean exists(String vpath) {
        vpath = normPath(vpath);
        if (isGh(vpath)) return ghfs.exists(vpath);
        if (isGhPrefix(vpath)) return true; // /gh, /gh/owner — синтетические папки
        if (isTree()) return vpath.equals("/") || resolveDoc(vpath) != null;
        try { return resolveFileInternal(vpath).exists(); } catch (IOException e) { return false; }
    }

    /** Является ли путь синтетическим префиксом активного GH-корня (/gh или /gh/owner). */
    private boolean isGhPrefix(String vpath) {
        String root = ghfs.activeRoot();
        if (root == null) return false;
        vpath = normPath(vpath);
        return root.startsWith(vpath + "/") && !vpath.equals("/");
    }

    public Stat stat(String vpath) throws IOException {
        vpath = normPath(vpath);
        if (isGh(vpath)) {
            try { return ghfs.stat(vpath); }
            catch (Exception e) { throw new IOException(e.getMessage()); }
        }
        if (isGhPrefix(vpath)) { Stat gs = new Stat(); gs.isDir = true; return gs; }
        Stat s = new Stat();
        if (isTree()) {
            if (vpath.equals("/")) { s.isDir = true; return s; }
            Doc d = resolveDoc(vpath);
            if (d == null) throw new IOException("Путь не найден: " + vpath);
            s.isDir = d.isDir; s.size = d.isDir ? 0 : d.size; s.mtime = d.mtime; s.ctime = d.mtime;
        } else {
            File f = resolveFileInternal(vpath);
            if (!f.exists()) throw new IOException("Путь не найден: " + vpath);
            s.isDir = f.isDirectory(); s.size = f.isFile() ? f.length() : 0;
            s.mtime = f.lastModified(); s.ctime = f.lastModified();
        }
        return s;
    }

    public List<Node> listChildren(String vpath) {
        List<Node> out = new ArrayList<Node>();
        vpath = normPath(vpath);
        // Внутри смонтированного репозитория — целиком отдаём GitHubFs.
        if (isGh(vpath)) return ghfs.listChildren(vpath);
        // Синтетический префикс (/gh или /gh/owner) — один дочерний сегмент к корню репо.
        if (isGhPrefix(vpath)) {
            String root = ghfs.activeRoot();
            String rest = root.substring(vpath.length() + 1); // owner/repo или repo
            String seg = rest.contains("/") ? rest.substring(0, rest.indexOf('/')) : rest;
            out.add(new Node(joinPath(vpath, seg), "dir", 0));
            return out;
        }
        List<Node> base = listChildrenReal(vpath);
        // В корне «/» подмешиваем виртуальную папку /gh, если репозиторий смонтирован.
        if (vpath.equals("/") && hasGhMount()) {
            boolean has = false;
            for (Node n : base) if ("/gh".equals(n.path)) { has = true; break; }
            if (!has) base.add(0, new Node("/gh", "dir", 0));
        }
        return base;
    }

    private List<Node> listChildrenReal(String vpath) {
        List<Node> out = new ArrayList<Node>();
        if (isTree()) {
            String id;
            if (vpath.equals("/")) id = rootDocId;
            else { Doc d = resolveDoc(vpath); if (d == null || !d.isDir) return out; id = d.id; }
            List<Doc> kids = childDocs(id);
            java.util.Collections.sort(kids, new java.util.Comparator<Doc>() {
                public int compare(Doc x, Doc y) {
                    if (x.isDir != y.isDir) return x.isDir ? -1 : 1;
                    String a = x.name == null ? "" : x.name, b = y.name == null ? "" : y.name;
                    return a.compareToIgnoreCase(b);
                }
            });
            for (Doc d : kids) {
                String nm = d.name == null ? "?" : d.name;
                out.add(new Node(joinPath(vpath, nm), d.isDir ? "dir" : "file", d.isDir ? 0 : d.size));
            }
        } else {
            try {
                File dir = resolveFileInternal(vpath);
                File[] kids = dir.listFiles();
                if (kids == null) return out;
                List<File> list = new ArrayList<File>();
                for (File k : kids) list.add(k);
                java.util.Collections.sort(list, new java.util.Comparator<File>() {
                    public int compare(File x, File y) {
                        if (x.isDirectory() != y.isDirectory()) return x.isDirectory() ? -1 : 1;
                        return x.getName().compareToIgnoreCase(y.getName());
                    }
                });
                for (File k : list)
                    out.add(new Node(vpathInternal(k), k.isDirectory() ? "dir" : "file", k.isFile() ? k.length() : 0));
            } catch (IOException ignored) {}
        }
        return out;
    }

    public List<Node> listTree(String vpath) {
        List<Node> out = new ArrayList<Node>();
        walk(vpath, out);
        return out;
    }
    private void walk(String vpath, List<Node> out) {
        for (Node n : listChildren(vpath)) {
            out.add(n);
            if ("dir".equals(n.type)) walk(n.path, out);
        }
    }

    // ------------------------------------------------------------- mutations
    public void ensureDir(String vpath) throws IOException {
        vpath = normPath(vpath);
        if (isGh(vpath)) {
            try { ghfs.ensureDir(vpath); return; }
            catch (Exception e) { throw new IOException(e.getMessage()); }
        }
        if (isTree()) {
            if (ensureDocDir(vpath) == null) throw new IOException("Не удалось создать папку: " + vpath);
        } else {
            File f = resolveFileInternal(vpath);
            if (!f.exists() && !f.mkdirs()) throw new IOException("Не удалось создать папку: " + vpath);
        }
    }

    public void writeText(String vpath, String content) throws IOException {
        writeBytes(vpath, content.getBytes("UTF-8"));
    }

    public void writeBytes(String vpath, byte[] data) throws IOException {
        vpath = normPath(vpath);
        if (isGh(vpath)) {
            // Синхронная запись — КОММИТ на GitHub.
            try { ghfs.writeBytes(vpath, data); return; }
            catch (Exception e) { throw new IOException(e.getMessage()); }
        }
        OutputStream os = openOutput(vpath);
        os.write(data); os.close();
    }

    public String readText(String vpath) throws IOException { return new String(readBytes(vpath), "UTF-8"); }

    public byte[] readBytes(String vpath) throws IOException {
        vpath = normPath(vpath);
        if (isGh(vpath)) {
            try { return ghfs.readBytes(vpath); }
            catch (Exception e) { throw new IOException(e.getMessage()); }
        }
        InputStream in = openInput(vpath);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192]; int r;
        while ((r = in.read(buf)) != -1) bo.write(buf, 0, r);
        in.close();
        return bo.toByteArray();
    }

    public void rename(String from, String to) throws IOException {
        from = normPath(from); to = normPath(to);
        if (isGh(from) || isGh(to)) {
            try { ghfs.rename(from, to); return; }
            catch (Exception e) { throw new IOException(e.getMessage()); }
        }
        if (isTree()) {
            Doc src = resolveDoc(from);
            if (src == null) throw new IOException("Источник не найден: " + from);
            String oldParent = dirname(from), newParent = dirname(to), newName = basename(to);
            if (oldParent.equals(newParent)) {
                try {
                    Uri res = DocumentsContract.renameDocument(cr, docUri(src.id), newName);
                    if (res == null) throw new Exception();
                } catch (Exception e) { copyDocTree(from, to); deleteDoc(src.id); }
            } else {
                copyDocTree(from, to);
                deleteDoc(src.id);
            }
        } else {
            File a = resolveFileInternal(from), b = resolveFileInternal(to);
            File parent = b.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            if (!a.renameTo(b)) { copyRecursive(a, b); deleteRecursive(a); }
        }
    }

    public void delete(String vpath) throws IOException {
        vpath = normPath(vpath);
        if (isGh(vpath)) {
            try { ghfs.delete(vpath); return; }
            catch (Exception e) { throw new IOException(e.getMessage()); }
        }
        if (isTree()) {
            Doc d = resolveDoc(vpath);
            if (d == null) throw new IOException("Путь не найден: " + vpath);
            if (!deleteDoc(d.id)) throw new IOException("Не удалось удалить: " + vpath);
        } else {
            deleteRecursive(resolveFileInternal(vpath));
        }
    }

    public void copyInto(String srcPath, String dstDir) throws IOException {
        srcPath = normPath(srcPath); dstDir = normPath(dstDir);
        // Копирование внутри/в GitHub-репозиторий (в т.ч. выгрузка локального файла
        // в открытый репозиторий — синхронно коммитится на сайт).
        if (isGh(dstDir)) {
            try {
                if (isGh(srcPath)) { ghfs.copyInto(srcPath, dstDir); return; }
                // Источник локальный → читаем байты и пишем в репозиторий (коммит).
                copyLocalIntoGh(srcPath, dstDir);
                return;
            } catch (Exception e) { throw new IOException(e.getMessage()); }
        }
        if (isGh(srcPath)) {
            // Из репозитория в локальную ФС — читаем через ghfs, пишем локально.
            try { copyGhIntoLocal(srcPath, dstDir); return; }
            catch (Exception e) { throw new IOException(e.getMessage()); }
        }
        String dst = joinPath(dstDir, basename(srcPath));
        if (isTree()) copyDocTree(srcPath, dst);
        else copyRecursive(resolveFileInternal(srcPath), resolveFileInternal(dst));
    }

    /** Копировать локальный файл/папку В GitHub-репозиторий (каждый файл — коммит). */
    private void copyLocalIntoGh(String srcPath, String dstDir) throws IOException {
        Stat st;
        try { st = statLocal(srcPath); } catch (IOException e) { throw e; }
        String base = basename(srcPath);
        if (!st.isDir) {
            byte[] data = readBytesLocal(srcPath);
            writeBytes(joinPath(dstDir, base), data);
            return;
        }
        String newDir = joinPath(dstDir, base);
        for (Node n : listChildrenReal(srcPath)) {
            copyLocalIntoGh(n.path, newDir);
        }
    }
    /** Копировать из GitHub-репозитория в локальную ФС. */
    private void copyGhIntoLocal(String srcPath, String dstDir) throws IOException {
        ExplorerFs.Stat st;
        try { st = ghfs.stat(srcPath); } catch (Exception e) { throw new IOException(e.getMessage()); }
        String base = basename(srcPath);
        if (!st.isDir) {
            byte[] data;
            try { data = ghfs.readBytes(srcPath); } catch (Exception e) { throw new IOException(e.getMessage()); }
            String dst = joinPath(dstDir, base);
            if (isTree()) writeBytes(dst, data);
            else {
                // Потоковая запись с гарантированным закрытием потока даже при ошибке,
                // иначе при сбое оставался файл нулевого/битого размера.
                OutputStream os = openOutput(dst);
                try {
                    int off = 0, chunk = 256 * 1024;
                    while (off < data.length) {
                        int n = Math.min(chunk, data.length - off);
                        os.write(data, off, n);
                        off += n;
                    }
                    os.flush();
                } finally {
                    try { os.close(); } catch (IOException ignored) {}
                }
            }
            return;
        }
        String newDir = joinPath(dstDir, base);
        ensureDir(newDir);
        for (Node n : ghfs.listChildren(srcPath)) copyGhIntoLocal(n.path, newDir);
    }
    private Stat statLocal(String vpath) throws IOException {
        Stat s = new Stat();
        if (isTree()) {
            Doc d = resolveDoc(vpath);
            if (d == null) throw new IOException("Путь не найден: " + vpath);
            s.isDir = d.isDir; s.size = d.isDir ? 0 : d.size;
        } else {
            File f = resolveFileInternal(vpath);
            if (!f.exists()) throw new IOException("Путь не найден: " + vpath);
            s.isDir = f.isDirectory(); s.size = f.isFile() ? f.length() : 0;
        }
        return s;
    }
    private byte[] readBytesLocal(String vpath) throws IOException {
        InputStream in = openInput(vpath);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192]; int r;
        while ((r = in.read(buf)) != -1) bo.write(buf, 0, r);
        in.close();
        return bo.toByteArray();
    }

    // ---- TREE helpers ----
    private boolean deleteDoc(String docId) {
        try { return DocumentsContract.deleteDocument(cr, docUri(docId)); }
        catch (Exception e) { return false; }
    }
    private void copyDocTree(String srcPath, String dstPath) throws IOException {
        Doc src = resolveDoc(srcPath);
        if (src == null) throw new IOException("Источник не найден: " + srcPath);
        if (src.isDir) {
            ensureDocDir(dstPath);
            for (Node n : listChildren(srcPath)) copyDocTree(n.path, joinPath(dstPath, basename(n.path)));
        } else {
            writeBytes(dstPath, readBytes(srcPath));
        }
    }

    // ---- INTERNAL helpers ----
    private void copyRecursive(File src, File dst) throws IOException {
        if (src.isDirectory()) {
            if (!dst.exists()) dst.mkdirs();
            File[] kids = src.listFiles();
            if (kids != null) for (File k : kids) copyRecursive(k, new File(dst, k.getName()));
        } else {
            File parent = dst.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            FileInputStream in = new FileInputStream(src);
            FileOutputStream os = new FileOutputStream(dst);
            byte[] buf = new byte[8192]; int r;
            while ((r = in.read(buf)) != -1) os.write(buf, 0, r);
            in.close(); os.close();
        }
    }
    private boolean deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursive(k);
        }
        return f.delete();
    }

    private String guessMime(String name) {
        String e = extname(name);
        if (e.equals("txt") || e.isEmpty()) return "text/plain";
        if (e.equals("json")) return "application/json";
        if (e.equals("html") || e.equals("htm")) return "text/html";
        if (e.equals("js")) return "application/javascript";
        if (e.equals("css")) return "text/css";
        if (e.equals("zip")) return "application/zip";
        if (e.equals("png")) return "image/png";
        if (e.equals("jpg") || e.equals("jpeg")) return "image/jpeg";
        return "application/octet-stream";
    }

    public String[] pickFreeZipName(String dir, String baseName) {
        String stem = baseName.replaceFirst("(?i)\\.zip$", "");
        for (int i = 0; i < 1000; i++) {
            String cand = i == 0 ? stem + ".zip" : stem + " (" + i + ").zip";
            String full = joinPath(dir, cand);
            if (!exists(full)) return new String[]{cand, full};
        }
        String cand = stem + "-" + System.currentTimeMillis() + ".zip";
        return new String[]{cand, joinPath(dir, cand)};
    }

    // ---- streams (оба режима) ----
    public InputStream openInput(String vpath) throws IOException {
        if (isTree()) {
            Doc d = resolveDoc(vpath);
            if (d == null) throw new IOException("Файл не найден: " + vpath);
            InputStream in = cr.openInputStream(docUri(d.id));
            if (in == null) throw new IOException("Не открыть: " + vpath);
            return in;
        }
        return new FileInputStream(resolveFileInternal(vpath));
    }
    public OutputStream openOutput(String vpath) throws IOException {
        if (isTree()) {
            Doc d = resolveDoc(vpath);
            String docId;
            if (d != null) docId = d.id;
            else {
                String parentId = ensureDocDir(dirname(vpath));
                if (parentId == null) throw new IOException("Нет родителя: " + vpath);
                String name = basename(vpath);
                try {
                    Uri created = DocumentsContract.createDocument(cr, docUri(parentId), guessMime(name), name);
                    if (created == null) throw new IOException("Не создать: " + vpath);
                    docId = DocumentsContract.getDocumentId(created);
                } catch (Exception e) { throw new IOException("Не создать: " + vpath); }
            }
            OutputStream os = cr.openOutputStream(docUri(docId), "wt");
            if (os == null) throw new IOException("Не открыть на запись: " + vpath);
            return os;
        }
        File f = resolveFileInternal(vpath);
        File parent = f.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        return new FileOutputStream(f);
    }
}
