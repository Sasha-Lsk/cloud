/* ===== LocalFS: адаптер к «источнику файлов на устройстве» =====
 *
 * Реализует тот же интерфейс, что и виртуальная FS (fs.js), и умеет работать в
 * двух режимах:
 *
 *   fsa      — File System Access API (window.showDirectoryPicker).
 *              Настоящая двусторонняя синхронизация с папкой на диске:
 *              любые правки в проводнике сразу пишутся на диск, а операция
 *              «Обновить» подтягивает изменения, сделанные снаружи.
 *              Поддержка: десктопный Chrome/Edge/Opera и Android Chrome.
 *              iOS Safari / Firefox — не работает.
 *
 *   import   — «Импорт папки» через <input type="file" webkitdirectory>.
 *              Работает во ВСЕХ браузерах, включая iOS Safari, Firefox,
 *              in-app WebView. Файлы копируются в отдельную IndexedDB-базу
 *              (mide-import-fs) под ключом-неймспейсом (label), после чего
 *              все FS-операции работают локально в этом пространстве.
 *              Синхронизация в одну сторону: чтобы обновить снимок папки
 *              из системы — заново выберите папку. Экспорт наружу — через
 *              «Скачать zip» / файловые скачивания.
 *
 * Пользователь может держать несколько «импортированных» пространств одновременно
 * (каждое со своим label, например: «Проект A», «Мой сайт»), и переключаться
 * между ними. Все они сохраняются между запусками.
 */
const LocalFS = (() => {
  // ---------- Handles DB (для fsa-режима) ----------
  const H_DB = 'mide-localfs-handles';
  const H_STORE = 'handles';
  const H_KEY = 'root';
  let hdbp = null;
  function openHDB() {
    if (hdbp) return hdbp;
    hdbp = new Promise((resolve, reject) => {
      const req = indexedDB.open(H_DB, 1);
      req.onupgradeneeded = () => {
        const db = req.result;
        if (!db.objectStoreNames.contains(H_STORE)) db.createObjectStore(H_STORE);
      };
      req.onsuccess = () => resolve(req.result);
      req.onerror   = () => reject(req.error);
    });
    return hdbp;
  }
  async function hGet(k){ const db = await openHDB(); return new Promise((r,j)=>{ const t=db.transaction(H_STORE,'readonly').objectStore(H_STORE).get(k); t.onsuccess=()=>r(t.result||null); t.onerror=()=>j(t.error); }); }
  async function hPut(k,v){ const db = await openHDB(); return new Promise((r,j)=>{ const t=db.transaction(H_STORE,'readwrite').objectStore(H_STORE).put(v,k); t.onsuccess=()=>r(); t.onerror=()=>j(t.error); }); }
  async function hDel(k){ const db = await openHDB(); return new Promise((r,j)=>{ const t=db.transaction(H_STORE,'readwrite').objectStore(H_STORE).delete(k); t.onsuccess=()=>r(); t.onerror=()=>j(t.error); }); }

  // ---------- Import DB (для import-режима) ----------
  // Схема: ключ = namespace + ':' + normalizedPath, значение = { ns, path, type, data?, mtime, ctime, parent }.
  // Индексы: ns (список namespace'ов), (ns,parent) — для быстрого readdir.
  const I_DB = 'mide-import-fs';
  const I_STORE = 'nodes';
  const I_META = 'meta'; // список namespace'ов: { key:label, name, importedAt, count, size }
  let idbp = null;
  function openIDB() {
    if (idbp) return idbp;
    idbp = new Promise((resolve, reject) => {
      const req = indexedDB.open(I_DB, 1);
      req.onupgradeneeded = () => {
        const db = req.result;
        if (!db.objectStoreNames.contains(I_STORE)) {
          const s = db.createObjectStore(I_STORE, { keyPath: 'key' });
          s.createIndex('ns', 'ns');
          s.createIndex('ns_parent', ['ns', 'parent']);
        }
        if (!db.objectStoreNames.contains(I_META)) {
          db.createObjectStore(I_META, { keyPath: 'key' });
        }
      };
      req.onsuccess = () => resolve(req.result);
      req.onerror   = () => reject(req.error);
    });
    return idbp;
  }
  function iReq(req){ return new Promise((r,j)=>{ req.onsuccess=()=>r(req.result); req.onerror=()=>j(req.error); }); }
  async function iStore(name, mode='readonly'){ const db = await openIDB(); return db.transaction(name, mode).objectStore(name); }

  // ---------- Path helpers ----------
  function normalize(p) {
    if (p == null) return '/';
    let s = String(p).replace(/\\/g, '/').replace(/\/+/g, '/');
    if (!s.startsWith('/')) s = '/' + s;
    if (s.length > 1 && s.endsWith('/')) s = s.slice(0, -1);
    return s;
  }
  function segments(p) { return normalize(p).split('/').filter(Boolean); }
  function basename(p) { const s = segments(p); return s.length ? s[s.length-1] : ''; }
  function dirname(p)  { const s = segments(p); if (s.length <= 1) return '/'; return '/' + s.slice(0,-1).join('/'); }

  // ---------- Общее состояние ----------
  // mode: 'fsa' | 'import' | null (не активен)
  let mode = null;
  let rootHandle = null;   // для fsa
  let rootName   = '';     // человекочитаемое имя (папка) — общее для обоих режимов
  let importNs   = '';     // ключ namespace для import-режима (label, нормализованный)

  function isSupported() { return true; /* import работает везде */ }
  function isFsaSupported() {
    return typeof window !== 'undefined' && typeof window.showDirectoryPicker === 'function';
  }
  function hasRoot() { return mode === 'fsa' ? !!rootHandle : (mode === 'import' && !!importNs); }
  function getRootName() { return rootName; }
  function getMode() { return mode; }

  async function clearRoot() {
    mode = null;
    rootHandle = null;
    rootName = '';
    importNs = '';
    try { await hDel(H_KEY); } catch(_){}
  }

  // ==================== FSA режим ====================
  async function ensurePermission(handle, kind='readwrite') {
    if (!handle || !handle.queryPermission) return true;
    const opts = { mode: kind };
    const q = await handle.queryPermission(opts);
    if (q === 'granted') return true;
    const req = await handle.requestPermission(opts);
    return req === 'granted';
  }

  async function pickRoot() {
    if (!isFsaSupported()) throw new Error('File System Access API не поддерживается этим браузером');
    const handle = await window.showDirectoryPicker({ mode: 'readwrite' });
    const ok = await ensurePermission(handle, 'readwrite');
    if (!ok) throw new Error('Нет разрешения на запись в выбранную папку');
    mode = 'fsa';
    rootHandle = handle;
    rootName = handle.name || '(папка)';
    importNs = '';
    try { await hPut(H_KEY, handle); } catch(_) {}
    return { name: rootName, mode };
  }

  async function restoreRoot({ silent = true } = {}) {
    // Пытаемся восстановить fsa handle (только он поддерживает восстановление)
    if (!isFsaSupported()) return null;
    let handle = null;
    try { handle = await hGet(H_KEY); } catch(_) { return null; }
    if (!handle) return null;
    if (silent) {
      try {
        const q = await handle.queryPermission({ mode: 'readwrite' });
        if (q !== 'granted') { mode='fsa'; rootHandle=handle; rootName=handle.name||''; return { name: rootName, mode, needsPermission: true }; }
      } catch(_) { return null; }
    } else {
      const ok = await ensurePermission(handle, 'readwrite');
      if (!ok) return null;
    }
    mode = 'fsa';
    rootHandle = handle;
    rootName = handle.name || '';
    return { name: rootName, mode };
  }

  async function requestPermissionInteractive() {
    if (mode !== 'fsa' || !rootHandle) return false;
    return ensurePermission(rootHandle, 'readwrite');
  }

  function requireFsa() {
    if (mode !== 'fsa' || !rootHandle) throw new Error('FSA root не активен');
    return rootHandle;
  }

  async function fsaResolveParent(path, { create=false } = {}) {
    const root = requireFsa();
    const segs = segments(path);
    if (!segs.length) return { parent: null, name: '' };
    let dir = root;
    for (let i = 0; i < segs.length - 1; i++) {
      dir = await dir.getDirectoryHandle(segs[i], { create });
    }
    return { parent: dir, name: segs[segs.length-1] };
  }

  async function fsaResolveNode(path) {
    const norm = normalize(path);
    if (norm === '/') return { handle: requireFsa(), kind: 'directory' };
    const segs = segments(norm);
    let dir = requireFsa();
    for (let i = 0; i < segs.length - 1; i++) {
      try { dir = await dir.getDirectoryHandle(segs[i]); }
      catch(_) { return null; }
    }
    const last = segs[segs.length - 1];
    try { const fh = await dir.getFileHandle(last); return { handle: fh, kind: 'file' }; } catch(_){}
    try { const dh = await dir.getDirectoryHandle(last); return { handle: dh, kind: 'directory' }; } catch(_){}
    return null;
  }

  // ==================== IMPORT режим ====================
  // Утилиты работы с namespace-store
  function makeKey(ns, path) { return ns + ':' + normalize(path); }

  async function iGetNode(ns, path) {
    const s = await iStore(I_STORE, 'readonly');
    return iReq(s.get(makeKey(ns, path)));
  }
  async function iPutNode(node) {
    const s = await iStore(I_STORE, 'readwrite');
    await iReq(s.put(node));
  }
  async function iDelNode(ns, path) {
    const s = await iStore(I_STORE, 'readwrite');
    await iReq(s.delete(makeKey(ns, path)));
  }
  async function iChildren(ns, parent) {
    const s = await iStore(I_STORE, 'readonly');
    const idx = s.index('ns_parent');
    return iReq(idx.getAll([ns, normalize(parent)]));
  }
  async function iAllByNs(ns) {
    const s = await iStore(I_STORE, 'readonly');
    const idx = s.index('ns');
    return iReq(idx.getAll(ns));
  }
  async function iDeleteAllByNs(ns) {
    const rows = await iAllByNs(ns);
    const s = await iStore(I_STORE, 'readwrite');
    for (const r of rows) s.delete(r.key);
    return new Promise((res, rej) => { s.transaction.oncomplete = () => res(); s.transaction.onerror = () => rej(s.transaction.error); });
  }
  async function iEnsureRoot(ns) {
    const r = await iGetNode(ns, '/');
    if (!r) { const t = Date.now(); await iPutNode({ key: makeKey(ns, '/'), ns, path:'/', parent:'', type:'dir', mtime:t, ctime:t }); }
  }
  async function iEnsureDirRecursive(ns, path) {
    const norm = normalize(path);
    if (norm === '/') { await iEnsureRoot(ns); return; }
    const parts = segments(norm);
    let cur = '';
    await iEnsureRoot(ns);
    for (const p of parts) {
      cur = cur + '/' + p;
      const ex = await iGetNode(ns, cur);
      if (!ex) {
        const t = Date.now();
        await iPutNode({ key: makeKey(ns, cur), ns, path: cur, parent: dirname(cur), type:'dir', mtime:t, ctime:t });
      }
    }
  }

  // Мета-инфо: список неймспейсов.
  async function metaGet(key) {
    const s = await iStore(I_META, 'readonly');
    return iReq(s.get(key));
  }
  async function metaPut(rec) {
    const s = await iStore(I_META, 'readwrite');
    await iReq(s.put(rec));
  }
  async function metaDel(key) {
    const s = await iStore(I_META, 'readwrite');
    await iReq(s.delete(key));
  }
  async function metaList() {
    const s = await iStore(I_META, 'readonly');
    return iReq(s.getAll());
  }

  // Санитайз label → допустимый namespace-ключ (без ':')
  function sanitizeLabel(raw) {
    let s = String(raw || '').trim().replace(/[:\/\\]/g, '_').slice(0, 80);
    if (!s) s = 'папка';
    return s;
  }

  // Получить свободный ключ на основе имени
  async function pickFreeNsKey(baseLabel) {
    const base = sanitizeLabel(baseLabel);
    let k = base, n = 1;
    while (await metaGet(k)) { n++; k = base + ' (' + n + ')'; }
    return k;
  }

  function requireImport() {
    if (mode !== 'import' || !importNs) throw new Error('Import namespace не активен');
    return importNs;
  }

  // Импорт папки из <input webkitdirectory> или из drop.
  // fileList — FileList или массив объектов { file: File, relPath: string }.
  // opts.label — читаемое имя корневой папки (обычно вычисляется из relPath).
  // opts.replace — если истина и уже есть namespace с тем же именем, заменяем его.
  // Возвращает { ns, name, count, size }.
  async function importFromFileList(fileList, opts = {}) {
    // Собираем в массив { file, relPath }
    let entries;
    if (Array.isArray(fileList)) {
      entries = fileList.map(x => ({ file: x.file, relPath: x.relPath || (x.file && (x.file.webkitRelativePath || x.file.name)) }));
    } else {
      entries = Array.from(fileList).map(f => ({ file: f, relPath: f.webkitRelativePath || f.name }));
    }
    entries = entries.filter(e => e.file && e.relPath);
    if (!entries.length) throw new Error('Не выбрано ни одного файла');

    // Определяем корневое имя: первый сегмент общего пути
    let rootLabel = opts.label;
    if (!rootLabel) {
      // Берём общий первый сегмент, если есть; иначе — 'папка'
      const firstSegs = entries.map(e => e.relPath.split('/').filter(Boolean)[0] || '');
      const uniq = new Set(firstSegs);
      if (uniq.size === 1 && [...uniq][0]) rootLabel = [...uniq][0];
      else rootLabel = 'папка';
    }
    const nsKey = opts.replace
      ? sanitizeLabel(rootLabel)
      : await pickFreeNsKey(rootLabel);
    if (opts.replace) {
      await iDeleteAllByNs(nsKey);
      try { await metaDel(nsKey); } catch(_){}
    }
    await iEnsureRoot(nsKey);

    let count = 0, size = 0;
    for (const { file, relPath } of entries) {
      // Внутренний путь: '/' + относительный путь. Убираем возможный общий root prefix — оставим "как есть",
      // чтобы структура на диске совпадала с тем, что видел пользователь при выборе.
      const p = normalize('/' + relPath);
      const par = dirname(p);
      await iEnsureDirRecursive(nsKey, par);
      const buf = new Uint8Array(await file.arrayBuffer());
      const t = file.lastModified || Date.now();
      await iPutNode({
        key: makeKey(nsKey, p), ns: nsKey, path: p, parent: par, type: 'file',
        data: buf, mtime: t, ctime: t,
      });
      count++;
      size += buf.byteLength;
    }
    await metaPut({ key: nsKey, name: rootLabel, importedAt: Date.now(), count, size });
    // Активируем
    mode = 'import';
    importNs = nsKey;
    rootName = rootLabel;
    rootHandle = null;
    return { ns: nsKey, name: rootLabel, count, size };
  }

  // Активировать существующий namespace (не импортируя).
  async function activateImportNs(nsKey) {
    const rec = await metaGet(nsKey);
    if (!rec) throw new Error('Пространство не найдено: ' + nsKey);
    mode = 'import';
    importNs = nsKey;
    rootName = rec.name || nsKey;
    rootHandle = null;
    return { ns: nsKey, name: rootName };
  }

  // Список всех сохранённых import-пространств
  async function listImportNamespaces() {
    try { return await metaList(); }
    catch(_) { return []; }
  }

  // Удалить import namespace (со всеми файлами)
  async function deleteImportNamespace(nsKey) {
    await iDeleteAllByNs(nsKey);
    try { await metaDel(nsKey); } catch(_){}
    if (mode === 'import' && importNs === nsKey) {
      mode = null; importNs = ''; rootName = '';
    }
  }

  // ==================== Единый FS-интерфейс ====================
  async function exists(path) {
    try {
      if (mode === 'fsa') {
        if (!rootHandle) return false;
        return !!(await fsaResolveNode(path));
      }
      if (mode === 'import') {
        return !!(await iGetNode(importNs, path));
      }
      return false;
    } catch(_) { return false; }
  }

  async function stat(path) {
    if (mode === 'fsa') {
      const n = await fsaResolveNode(path);
      if (!n) throw Object.assign(new Error('ENOENT: ' + path), { code:'ENOENT', path });
      if (n.kind === 'directory') {
        return { path: normalize(path), type:'dir', size:0, mtime:0, ctime:0,
                 isFile:()=>false, isDirectory:()=>true, isSymbolicLink:()=>false };
      }
      let size=0, mtime=0;
      try { const f = await n.handle.getFile(); size=f.size||0; mtime=f.lastModified||0; } catch(_){}
      return { path: normalize(path), type:'file', size, mtime, ctime: mtime,
               isFile:()=>true, isDirectory:()=>false, isSymbolicLink:()=>false };
    }
    if (mode === 'import') {
      const n = await iGetNode(importNs, path);
      if (!n) throw Object.assign(new Error('ENOENT: '+path), { code:'ENOENT', path });
      return { path: n.path, type: n.type,
               size: n.type === 'file' ? (n.data ? n.data.byteLength : 0) : 0,
               mtime: n.mtime||0, ctime: n.ctime||n.mtime||0,
               isFile:()=>n.type==='file', isDirectory:()=>n.type==='dir', isSymbolicLink:()=>false };
    }
    throw Object.assign(new Error('ENOENT: '+path), { code:'ENOENT', path });
  }

  async function readdir(path) {
    if (mode === 'fsa') {
      const n = await fsaResolveNode(path);
      if (!n) throw Object.assign(new Error('ENOENT: '+path), { code:'ENOENT', path });
      if (n.kind !== 'directory') throw Object.assign(new Error('ENOTDIR: '+path), { code:'ENOTDIR', path });
      const names = [];
      for await (const [name] of n.handle.entries()) names.push(name);
      names.sort((a,b) => a.localeCompare(b));
      return names;
    }
    if (mode === 'import') {
      const norm = normalize(path);
      const parent = await iGetNode(importNs, norm);
      if (!parent) throw Object.assign(new Error('ENOENT: '+norm), { code:'ENOENT', path:norm });
      if (parent.type !== 'dir') throw Object.assign(new Error('ENOTDIR: '+norm), { code:'ENOTDIR', path:norm });
      const rows = await iChildren(importNs, norm);
      return rows.map(r => basename(r.path)).sort((a,b) => a.localeCompare(b));
    }
    return [];
  }

  // Прямые дети одной директории с метаданными — НЕ рекурсивно.
  // Критично для больших папок (декомпилированный APK и т.п.): раньше проводник
  // строил всё дерево целиком через listTree('/')+walkFsa, что для десятков тысяч
  // файлов подвешивало UI и дерево «не отображалось». Теперь проводник грузит
  // содержимое папок лениво, по мере раскрытия, вызывая readdirStat.
  async function readdirStat(path) {
    const norm = normalize(path);
    if (mode === 'fsa') {
      const n = await fsaResolveNode(norm);
      if (!n) throw Object.assign(new Error('ENOENT: '+norm), { code:'ENOENT', path:norm });
      if (n.kind !== 'directory') throw Object.assign(new Error('ENOTDIR: '+norm), { code:'ENOTDIR', path:norm });
      const out = [];
      for await (const [name, handle] of n.handle.entries()) {
        const p = norm === '/' ? '/' + name : norm + '/' + name;
        if (handle.kind === 'directory') {
          out.push({ path: p, type: 'dir', size: 0, mtime: 0, ctime: 0 });
        } else {
          // Размер/дата: не читаем содержимое, только метаданные File.
          let size = 0, mtime = 0;
          try { const f = await handle.getFile(); size = f.size||0; mtime = f.lastModified||0; } catch(_){}
          out.push({ path: p, type: 'file', size, mtime, ctime: mtime });
        }
      }
      return out;
    }
    if (mode === 'import') {
      const parent = await iGetNode(importNs, norm);
      if (!parent) throw Object.assign(new Error('ENOENT: '+norm), { code:'ENOENT', path:norm });
      if (parent.type !== 'dir') throw Object.assign(new Error('ENOTDIR: '+norm), { code:'ENOTDIR', path:norm });
      const rows = await iChildren(importNs, norm);
      return rows.map(r => ({
        path: r.path, type: r.type,
        size: r.type === 'file' ? (r.data ? r.data.byteLength : 0) : 0,
        mtime: r.mtime||0, ctime: r.ctime||r.mtime||0,
      }));
    }
    return [];
  }

  async function readFile(path) {
    if (mode === 'fsa') {
      const n = await fsaResolveNode(path);
      if (!n) throw Object.assign(new Error('ENOENT: '+path), { code:'ENOENT', path });
      if (n.kind !== 'file') throw Object.assign(new Error('EISDIR: '+path), { code:'EISDIR', path });
      const f = await n.handle.getFile();
      return new Uint8Array(await f.arrayBuffer());
    }
    if (mode === 'import') {
      const n = await iGetNode(importNs, path);
      if (!n) throw Object.assign(new Error('ENOENT: '+path), { code:'ENOENT', path });
      if (n.type !== 'file') throw Object.assign(new Error('EISDIR: '+path), { code:'EISDIR', path });
      return n.data ? new Uint8Array(n.data) : new Uint8Array(0);
    }
    throw Object.assign(new Error('ENOENT: '+path), { code:'ENOENT', path });
  }

  async function readFileText(path) { return U.bytesToText(await readFile(path)); }

  async function ensureDirRecursive(path) {
    const norm = normalize(path);
    if (norm === '/') return;
    if (mode === 'fsa') {
      const segs = segments(norm);
      let dir = requireFsa();
      for (const s of segs) dir = await dir.getDirectoryHandle(s, { create: true });
      return;
    }
    if (mode === 'import') {
      await iEnsureDirRecursive(requireImport(), norm);
    }
  }

  async function mkdir(path) {
    const norm = normalize(path);
    if (norm === '/') return;
    if (mode === 'fsa') {
      const { parent, name } = await fsaResolveParent(norm, { create: false });
      if (!parent) throw new Error('mkdir: bad path');
      try { await parent.getFileHandle(name); throw Object.assign(new Error('EEXIST: '+norm), { code:'EEXIST', path:norm }); }
      catch(e) { if (e && e.code === 'EEXIST') throw e; }
      await parent.getDirectoryHandle(name, { create: true });
      return;
    }
    if (mode === 'import') {
      const ns = requireImport();
      const ex = await iGetNode(ns, norm);
      if (ex) throw Object.assign(new Error('EEXIST: '+norm), { code:'EEXIST', path:norm });
      const par = dirname(norm);
      if (par !== '/') await iEnsureDirRecursive(ns, par);
      const t = Date.now();
      await iPutNode({ key: makeKey(ns, norm), ns, path: norm, parent: par, type:'dir', mtime:t, ctime:t });
    }
  }

  async function writeFile(path, data) {
    if (typeof data === 'string') data = U.textToBytes(data);
    else if (data instanceof ArrayBuffer) data = new Uint8Array(data);
    if (!(data instanceof Uint8Array)) throw new Error('writeFile: bad data');
    const norm = normalize(path);
    if (mode === 'fsa') {
      await ensureDirRecursive(dirname(norm));
      const { parent, name } = await fsaResolveParent(norm, { create: true });
      const fh = await parent.getFileHandle(name, { create: true });
      const w  = await fh.createWritable();
      await w.write(data);
      await w.close();
      return;
    }
    if (mode === 'import') {
      const ns = requireImport();
      const par = dirname(norm);
      if (par !== '/') await iEnsureDirRecursive(ns, par);
      const t = Date.now();
      const ex = await iGetNode(ns, norm);
      await iPutNode({ key: makeKey(ns, norm), ns, path: norm, parent: par, type:'file',
        data, mtime: t, ctime: ex && ex.ctime || t });
    }
  }

  async function unlink(path) {
    const norm = normalize(path);
    if (mode === 'fsa') {
      const { parent, name } = await fsaResolveParent(norm);
      try { await parent.getFileHandle(name); }
      catch(_) { throw Object.assign(new Error('EISDIR or ENOENT: '+norm), { code:'EISDIR', path:norm }); }
      await parent.removeEntry(name);
      return;
    }
    if (mode === 'import') {
      const ns = requireImport();
      const n = await iGetNode(ns, norm);
      if (!n) throw Object.assign(new Error('ENOENT: '+norm), { code:'ENOENT', path:norm });
      if (n.type !== 'file') throw Object.assign(new Error('EISDIR: '+norm), { code:'EISDIR', path:norm });
      await iDelNode(ns, norm);
    }
  }

  async function rmdir(path) {
    const norm = normalize(path);
    if (norm === '/') throw new Error('Cannot remove root');
    if (mode === 'fsa') {
      const { parent, name } = await fsaResolveParent(norm);
      try {
        const dh = await parent.getDirectoryHandle(name);
        for await (const _ of dh.entries()) {
          throw Object.assign(new Error('ENOTEMPTY: '+norm), { code:'ENOTEMPTY', path:norm });
        }
      } catch(e) {
        if (e && e.code === 'ENOTEMPTY') throw e;
        throw Object.assign(new Error('ENOENT: '+norm), { code:'ENOENT', path:norm });
      }
      await parent.removeEntry(name);
      return;
    }
    if (mode === 'import') {
      const ns = requireImport();
      const n = await iGetNode(ns, norm);
      if (!n) throw Object.assign(new Error('ENOENT: '+norm), { code:'ENOENT', path:norm });
      const kids = await iChildren(ns, norm);
      if (kids.length) throw Object.assign(new Error('ENOTEMPTY: '+norm), { code:'ENOTEMPTY', path:norm });
      await iDelNode(ns, norm);
    }
  }

  async function rmrf(path) {
    const norm = normalize(path);
    if (mode === 'fsa') {
      if (norm === '/') {
        const root = requireFsa();
        const names = [];
        for await (const [name] of root.entries()) names.push(name);
        for (const n of names) { try { await root.removeEntry(n, { recursive: true }); } catch(_){} }
        return;
      }
      const { parent, name } = await fsaResolveParent(norm);
      await parent.removeEntry(name, { recursive: true });
      return;
    }
    if (mode === 'import') {
      const ns = requireImport();
      const all = await iAllByNs(ns);
      const s = await iStore(I_STORE, 'readwrite');
      const isUnderTarget = (p) => norm === '/' ? p !== '/' : (p === norm || p.startsWith(norm + '/'));
      for (const r of all) if (isUnderTarget(r.path)) s.delete(r.key);
      await new Promise((res, rej) => { s.transaction.oncomplete = () => res(); s.transaction.onerror = () => rej(s.transaction.error); });
      // Восстановить корень если удалили всё
      if (norm === '/') await iEnsureRoot(ns);
    }
  }

  async function rename(from, to) {
    from = normalize(from); to = normalize(to);
    if (from === to) return;
    const node = await (mode === 'fsa' ? fsaResolveNode(from) : (async () => {
      const n = await iGetNode(importNs, from);
      return n ? { kind: n.type === 'dir' ? 'directory' : 'file' } : null;
    })());
    if (!node) throw Object.assign(new Error('ENOENT: '+from), { code:'ENOENT', path:from });
    if (await exists(to)) throw Object.assign(new Error('EEXIST: '+to), { code:'EEXIST', path:to });
    if (mode === 'fsa') {
      if (node.kind === 'file') {
        const data = await readFile(from);
        await writeFile(to, data);
        await unlink(from);
      } else {
        await ensureDirRecursive(to);
        const rows = await listTree(from);
        rows.sort((a,b) => a.path.length - b.path.length);
        for (const r of rows) {
          if (r.path === from) continue;
          const rel = r.path.slice(from.length);
          const dst = normalize(to + rel);
          if (r.type === 'dir') await ensureDirRecursive(dst);
          else { const d = await readFile(r.path); await writeFile(dst, d); }
        }
        await rmrf(from);
      }
      return;
    }
    if (mode === 'import') {
      const ns = requireImport();
      const all = await iAllByNs(ns);
      const affected = all.filter(r => r.path === from || r.path.startsWith(from + '/'));
      const s = await iStore(I_STORE, 'readwrite');
      for (const r of affected) {
        s.delete(r.key);
        const newPath = to + r.path.slice(from.length);
        const newParent = dirname(newPath);
        s.put({ ...r, key: makeKey(ns, newPath), path: newPath, parent: newParent });
      }
      // Убедимся что родитель to существует
      await new Promise((res, rej) => { s.transaction.oncomplete = () => res(); s.transaction.onerror = () => rej(s.transaction.error); });
      const par = dirname(to);
      if (par !== '/') await iEnsureDirRecursive(ns, par);
    }
  }

  async function copyFile(from, to) {
    const data = await readFile(from);
    await writeFile(to, data);
  }

  // ---- Walkers / listing ----
  async function walkFsa(dirHandle, prefix, out) {
    for await (const [name, handle] of dirHandle.entries()) {
      const p = prefix === '/' ? '/' + name : prefix + '/' + name;
      if (handle.kind === 'directory') {
        out.push({ path: p, type: 'dir', size: 0, mtime: 0, ctime: 0 });
        await walkFsa(handle, p, out);
      } else {
        let size=0, mtime=0;
        try { const f = await handle.getFile(); size = f.size||0; mtime = f.lastModified||0; } catch(_){}
        out.push({ path: p, type: 'file', size, mtime, ctime: mtime });
      }
    }
  }

  async function listTree(root='/') {
    const norm = normalize(root);
    if (mode === 'fsa') {
      if (!rootHandle) return [];
      const startNode = await fsaResolveNode(norm);
      if (!startNode || startNode.kind !== 'directory') return [];
      const out = [{ path: norm, type: 'dir', size: 0, mtime: 0, ctime: 0 }];
      await walkFsa(startNode.handle, norm, out);
      return out;
    }
    if (mode === 'import') {
      const ns = requireImport();
      const all = await iAllByNs(ns);
      const filtered = norm === '/' ? all : all.filter(r => r.path === norm || r.path.startsWith(norm + '/'));
      return filtered.map(r => ({
        path: r.path, type: r.type,
        size: r.type === 'file' ? (r.data ? r.data.byteLength : 0) : 0,
        mtime: r.mtime||0, ctime: r.ctime||r.mtime||0,
      }));
    }
    return [];
  }

  async function listAll() {
    const tree = await listTree('/');
    return tree.map(r => r.path);
  }

  // === isomorphic-git заглушки (не поддерживаются) ===
  const igfs = { promises: {
    readFile:  async () => { throw new Error('isogit over LocalFS not supported'); },
    writeFile: async () => { throw new Error('isogit over LocalFS not supported'); },
    unlink:    async () => { throw new Error('isogit over LocalFS not supported'); },
    readdir:   async () => { throw new Error('isogit over LocalFS not supported'); },
    mkdir:     async () => { throw new Error('isogit over LocalFS not supported'); },
    rmdir:     async () => { throw new Error('isogit over LocalFS not supported'); },
    stat:      async () => { throw new Error('isogit over LocalFS not supported'); },
    lstat:     async () => { throw new Error('isogit over LocalFS not supported'); },
    readlink:  async () => { throw Object.assign(new Error('ENOTSUP'), { code:'ENOTSUP' }); },
    symlink:   async () => { throw Object.assign(new Error('ENOTSUP'), { code:'ENOTSUP' }); },
    chmod:     async () => {},
  }};

  function getImportNs() { return mode === 'import' ? importNs : ''; }

  return {
    // capabilities
    isSupported, isFsaSupported,
    // state
    hasRoot, getRootName, getMode, getImportNs, clearRoot,
    // fsa
    pickRoot, restoreRoot, requestPermissionInteractive,
    // import
    importFromFileList, activateImportNs, listImportNamespaces, deleteImportNamespace,
    // fs
    exists, stat, readdir, readdirStat, readFile, readFileText, writeFile,
    mkdir, ensureDirRecursive, unlink, rmdir, rmrf, rename, copyFile,
    listAll, listTree, igfs,
  };
})();
window.LocalFS = LocalFS;
