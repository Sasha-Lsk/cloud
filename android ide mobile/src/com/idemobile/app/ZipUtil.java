package com.idemobile.app;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * ZIP-операции для проводника на стандартном java.util.zip.
 * Работает через потоки ExplorerFs.openInput/openOutput, поэтому одинаково
 * функционирует и во внутренней папке, и в реальной папке устройства (SAF).
 *
 * Пути внутри архива строятся относительно общей папки rootDir выбранных
 * элементов — как в web-версии, чтобы структура сохранялась.
 */
public class ZipUtil {

    public static class Entry {
        public String name;
        public boolean isDir;
        public long size;
        public Entry(String n, boolean d, long s) { name = n; isDir = d; size = s; }
    }

    /**
     * Колбэк прогресса архивации/распаковки.
     * done  — сколько файлов уже обработано,
     * total — сколько всего файлов,
     * pct   — процент 0..100 (детально, на каждый файл).
     *
     * isCancelled() — опрос флага отмены (кнопка «Отмена» в окне статуса). Когда
     * возвращает true, операция прерывается и бросает {@link CancelledException},
     * а частично созданный файл/распакованные файлы очищаются вызывающим кодом.
     */
    public interface Progress {
        void onProgress(int done, int total, int pct);
        /** Дефолт: не отменяемо (обратная совместимость со старыми вызовами). */
        boolean isCancelled();
    }

    /** Бросается, когда пользователь отменил zip/unzip кнопкой «Отмена». */
    public static class CancelledException extends Exception {
        public CancelledException() { super("Операция отменена пользователем"); }
    }

    /** Упаковать список путей (файлы/папки) в zip по адресу zipVPath. Возвращает число файлов. */
    public static int zipInto(ExplorerFs fs, List<String> paths, String rootDir, String zipVPath) throws Exception {
        return zipInto(fs, paths, rootDir, zipVPath, null);
    }

    /** То же, но с колбэком прогресса. */
    public static int zipInto(ExplorerFs fs, List<String> paths, String rootDir, String zipVPath, Progress prog) throws Exception {
        String rootPrefix = rootDir.equals("/") ? "/" : (rootDir + "/");
        // Сначала строим плоский список всех файлов — чтобы знать total для прогресса.
        List<String[]> flat = new ArrayList<String[]>(); // {vpath, rel}
        for (String p : paths) {
            ExplorerFs.Stat st;
            try { st = fs.stat(p); } catch (Exception e) { continue; }
            if (!st.isDir) {
                String rel = p.startsWith(rootPrefix) ? p.substring(rootPrefix.length()) : ExplorerFs.basename(p);
                if (!rel.isEmpty()) flat.add(new String[]{ p, rel });
            } else {
                for (ExplorerFs.Node n : fs.listTree(p)) {
                    if (!"file".equals(n.type)) continue;
                    String rel = n.path.startsWith(rootPrefix)
                            ? n.path.substring(rootPrefix.length())
                            : n.path.substring(1);
                    if (!rel.isEmpty()) flat.add(new String[]{ n.path, rel });
                }
            }
        }
        int total = flat.size();
        if (prog != null && total > 0) prog.onProgress(0, total, 0);
        OutputStream fos = fs.openOutput(zipVPath);
        ZipOutputStream zos = new ZipOutputStream(fos);
        int count = 0, lastPct = -1;
        boolean cancelled = false;
        try {
            for (String[] pair : flat) {
                if (prog != null && prog.isCancelled()) { cancelled = true; break; }
                writeEntry(zos, fs, pair[0], pair[1]);
                count++;
                if (prog != null) {
                    int pct = total > 0 ? (int) Math.round(count * 100.0 / total) : 100;
                    if (pct != lastPct) { lastPct = pct; prog.onProgress(count, total, pct); }
                }
            }
        } finally {
            zos.close();
        }
        if (cancelled) {
            // Удаляем частично записанный архив, чтобы не оставлять «битый» .zip.
            try { fs.delete(zipVPath); } catch (Exception ignore) {}
            throw new CancelledException();
        }
        return count;
    }

    private static void writeEntry(ZipOutputStream zos, ExplorerFs fs, String vpath, String rel) throws Exception {
        zos.putNextEntry(new ZipEntry(rel));
        InputStream in = fs.openInput(vpath);
        byte[] buf = new byte[8192]; int r;
        while ((r = in.read(buf)) != -1) zos.write(buf, 0, r);
        in.close();
        zos.closeEntry();
    }

    /** Распаковать zipVPath в папку baseDir (виртуальный путь). Возвращает число файлов. */
    public static int unzipInto(ExplorerFs fs, String zipVPath, String baseDir) throws Exception {
        return unzipInto(fs, zipVPath, baseDir, null);
    }

    /** То же, но с колбэком прогресса. */
    public static int unzipInto(ExplorerFs fs, String zipVPath, String baseDir, Progress prog) throws Exception {
        // Предварительный проход: считаем общее число файлов для корректного процента.
        int total = 0;
        if (prog != null) {
            try {
                for (Entry en : listEntries(fs, zipVPath)) if (!en.isDir) total++;
            } catch (Exception ignore) { total = 0; }
            prog.onProgress(0, total, 0);
        }
        ZipInputStream zis = new ZipInputStream(fs.openInput(zipVPath));
        int n = 0, lastPct = -1;
        boolean cancelled = false;
        // Запоминаем распакованные пути — при отмене удаляем их (частичную распаковку).
        List<String> written = new ArrayList<String>();
        try {
            ZipEntry e;
            byte[] buf = new byte[8192];
            while ((e = zis.getNextEntry()) != null) {
                if (prog != null && prog.isCancelled()) { cancelled = true; break; }
                String dst = ExplorerFs.joinPath(baseDir, e.getName());
                if (e.isDirectory()) {
                    fs.ensureDir(dst);
                } else {
                    String parent = ExplorerFs.dirname(dst);
                    if (!parent.equals("/")) fs.ensureDir(parent);
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    int r;
                    while ((r = zis.read(buf)) != -1) bo.write(buf, 0, r);
                    fs.writeBytes(dst, bo.toByteArray());
                    written.add(dst);
                    n++;
                    if (prog != null) {
                        int pct = total > 0 ? (int) Math.round(n * 100.0 / total) : 100;
                        if (pct != lastPct) { lastPct = pct; prog.onProgress(n, total, pct); }
                    }
                }
                zis.closeEntry();
            }
        } finally {
            zis.close();
        }
        if (cancelled) {
            // Откатываем частично распакованные файлы.
            for (String w : written) { try { fs.delete(w); } catch (Exception ignore) {} }
            throw new CancelledException();
        }
        return n;
    }

    /**
     * Прочитать одну запись архива по имени (без полной распаковки на диск).
     * Возвращает байты записи или null, если записи с таким именем нет.
     * Совпадение по точному имени, а также по имени без ведущего «/».
     */
    public static byte[] readEntry(ExplorerFs fs, String zipVPath, String entryName) throws Exception {
        String want = entryName.startsWith("/") ? entryName.substring(1) : entryName;
        ZipInputStream zis = new ZipInputStream(fs.openInput(zipVPath));
        try {
            ZipEntry e;
            byte[] buf = new byte[8192];
            while ((e = zis.getNextEntry()) != null) {
                String name = e.getName();
                String norm = name.startsWith("/") ? name.substring(1) : name;
                if (!e.isDirectory() && (name.equals(entryName) || norm.equals(want))) {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    int r;
                    while ((r = zis.read(buf)) != -1) bo.write(buf, 0, r);
                    zis.closeEntry();
                    return bo.toByteArray();
                }
                zis.closeEntry();
            }
        } finally {
            zis.close();
        }
        return null;
    }

    /** Список записей архива (для просмотрщика). */
    public static List<Entry> listEntries(ExplorerFs fs, String zipVPath) throws Exception {
        ZipInputStream zis = new ZipInputStream(fs.openInput(zipVPath));
        List<Entry> out = new ArrayList<Entry>();
        try {
            ZipEntry e;
            byte[] buf = new byte[8192];
            while ((e = zis.getNextEntry()) != null) {
                long size = e.getSize();
                if (size < 0 && !e.isDirectory()) {
                    long counted = 0; int r;
                    while ((r = zis.read(buf)) != -1) counted += r;
                    size = counted;
                }
                out.add(new Entry(e.getName(), e.isDirectory(), Math.max(0, size)));
                zis.closeEntry();
            }
        } finally {
            zis.close();
        }
        java.util.Collections.sort(out, new java.util.Comparator<Entry>() {
            public int compare(Entry a, Entry b) {
                if (a.isDir != b.isDir) return a.isDir ? -1 : 1;
                return a.name.compareToIgnoreCase(b.name);
            }
        });
        return out;
    }
}
