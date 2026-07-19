/* ===== Virtual filesystem on IndexedDB =====
 * Simple flat store: key = normalized path, value = { path, type:'file'|'dir', data:Uint8Array?, mtime }.
 * Every directory has an entry. Root '/' always exists.
 * Adapter for isomorphic-git provided at end.
 */
const FS = (() => {
  const DB_NAME = 'mide-fs';
  const DB_VER = 1;
  const STORE = 'nodes';
  let dbp;
  function openDB() {
    if (dbp) return dbp;
    dbp = new Promise((resolve, reject) => {
      const req = indexedDB.open(DB_NAME, DB_VER);
      req.onupgradeneeded = () => {
        const db = req.result;
        if (!db.objectStoreNames.contains(STORE)) {
          const s = db.createObjectStore(STORE, { keyPath: 'path' });
          s.createIndex('parent', 'parent');
        }
      };
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    });
    return dbp;
  }
  async function tx(mode='readonly') {
    const db = await openDB();
    return db.transaction(STORE, mode).objectStore(STORE);
  }
  function reqP(req){ return new Promise((res,rej)=>{ req.onsuccess=()=>res(req.result); req.onerror=()=>rej(req.error); }); }

  async function ensureRoot() {
    const s = await tx('readwrite');
    const r = await reqP(s.get('/'));
    if (!r) { const t = Date.now(); await reqP(s.put({ path:'/', parent:'', type:'dir', mtime:t, ctime:t })); }
  }

  async function exists(path) {
    path = U.normPath(path);
    const s = await tx();
    const n = await reqP(s.get(path));
    return !!n;
  }
  async function stat(path) {
    path = U.normPath(path);
    const s = await tx();
    const n = await reqP(s.get(path));
    if (!n) throw Object.assign(new Error('ENOENT: '+path), { code:'ENOENT', path });
    return { path:n.path, type:n.type, size: n.type==='file' ? (n.data ? n.data.byteLength : 0) : 0, mtime:n.mtime||0, ctime:n.ctime||n.mtime||0, isFile: ()=>n.type==='file', isDirectory: ()=>n.type==='dir', isSymbolicLink: ()=>false };
  }
  async function readdir(path) {
    path = U.normPath(path);
    const s = await tx();
    const n = await reqP(s.get(path));
    if (!n) throw Object.assign(new Error('ENOENT: '+path), { code:'ENOENT', path });
    if (n.type !== 'dir') throw Object.assign(new Error('ENOTDIR: '+path), { code:'ENOTDIR', path });
    const idx = s.index('parent');
    const rows = await reqP(idx.getAll(path));
    return rows.map(r => U.basename(r.path)).sort((a,b)=>a.localeCompare(b));
  }
  async function readFile(path) {
    path = U.normPath(path);
    const s = await tx();
    const n = await reqP(s.get(path));
    if (!n) throw Object.assign(new Error('ENOENT: '+path), { code:'ENOENT', path });
    if (n.type !== 'file') throw Object.assign(new Error('EISDIR: '+path), { code:'EISDIR', path });
    return n.data ? new Uint8Array(n.data) : new Uint8Array();
  }
  async function readFileText(path) { return U.bytesToText(await readFile(path)); }

  async function ensureDirRecursive(path) {
    path = U.normPath(path);
    if (path === '/') return;
    const parts = path.split('/').filter(Boolean);
    let cur = '';
    for (const p of parts) {
      cur += '/' + p;
      const s = await tx('readwrite');
      const n = await reqP(s.get(cur));
      if (!n) { const t = Date.now(); await reqP(s.put({ path:cur, parent:U.dirname(cur), type:'dir', mtime:t, ctime:t })); }
      else if (n.type !== 'dir') throw Object.assign(new Error('ENOTDIR: '+cur), { code:'ENOTDIR', path:cur });
    }
  }
  async function mkdir(path) {
    path = U.normPath(path);
    const parent = U.dirname(path);
    const s = await tx('readwrite');
    const pn = await reqP(s.get(parent));
    if (!pn) throw Object.assign(new Error('ENOENT: '+parent), { code:'ENOENT', path:parent });
    const cur = await reqP(s.get(path));
    if (cur) { if (cur.type==='dir') return; throw Object.assign(new Error('EEXIST: '+path), { code:'EEXIST', path }); }
    const t = Date.now();
    await reqP(s.put({ path, parent, type:'dir', mtime:t, ctime:t }));
  }
  async function writeFile(path, data) {
    path = U.normPath(path);
    if (typeof data === 'string') data = U.textToBytes(data);
    if (data instanceof ArrayBuffer) data = new Uint8Array(data);
    if (!(data instanceof Uint8Array)) throw new Error('writeFile: bad data');
    await ensureDirRecursive(U.dirname(path));
    const s = await tx('readwrite');
    const existing = await reqP(s.get(path));
    if (existing && existing.type === 'dir') throw Object.assign(new Error('EISDIR: '+path), { code:'EISDIR', path });
    const now = Date.now();
    // ctime сохраняется от первой записи; mtime обновляется при каждой перезаписи.
    const ctime = (existing && existing.ctime) || now;
    await reqP(s.put({ path, parent:U.dirname(path), type:'file', data, mtime:now, ctime }));
  }
  async function unlink(path) {
    path = U.normPath(path);
    const s = await tx('readwrite');
    const n = await reqP(s.get(path));
    if (!n) throw Object.assign(new Error('ENOENT: '+path), { code:'ENOENT', path });
    if (n.type !== 'file') throw Object.assign(new Error('EISDIR: '+path), { code:'EISDIR', path });
    await reqP(s.delete(path));
  }
  async function rmdir(path) {
    path = U.normPath(path);
    if (path === '/') throw new Error('Cannot remove root');
    const s = await tx('readwrite');
    const n = await reqP(s.get(path));
    if (!n) throw Object.assign(new Error('ENOENT: '+path), { code:'ENOENT', path });
    if (n.type !== 'dir') throw Object.assign(new Error('ENOTDIR: '+path), { code:'ENOTDIR', path });
    const idx = s.index('parent');
    const kids = await reqP(idx.getAll(path));
    if (kids.length) throw Object.assign(new Error('ENOTEMPTY: '+path), { code:'ENOTEMPTY', path });
    await reqP(s.delete(path));
  }
  async function rmrf(path) {
    path = U.normPath(path);
    if (path === '/') { // clear all except root
      const s = await tx('readwrite');
      await reqP(s.clear());
      await ensureRoot();
      return;
    }
    // gather all descendants
    const all = await listAll();
    const targets = all.filter(p => p === path || p.startsWith(path + '/'));
    targets.sort((a,b) => b.length - a.length);
    const s = await tx('readwrite');
    for (const p of targets) await reqP(s.delete(p));
  }
  async function rename(from, to) {
    from = U.normPath(from); to = U.normPath(to);
    if (from === to) return;
    const s = await tx('readwrite');
    const n = await reqP(s.get(from));
    if (!n) throw Object.assign(new Error('ENOENT: '+from), { code:'ENOENT', path:from });
    const target = await reqP(s.get(to));
    if (target) throw Object.assign(new Error('EEXIST: '+to), { code:'EEXIST', path:to });
    await ensureDirRecursive(U.dirname(to));
    // gather all under 'from' if directory
    const all = await listAll();
    const affected = n.type === 'dir' ? all.filter(p => p === from || p.startsWith(from + '/')) : [from];
    const s2 = await tx('readwrite');
    for (const p of affected) {
      const row = await reqP(s2.get(p));
      const newPath = to + p.slice(from.length);
      await reqP(s2.delete(p));
      row.path = newPath; row.parent = U.dirname(newPath); row.mtime = Date.now();
      // ctime сохраняем от исходного узла; если его нет — принимаем текущий mtime.
      if (!row.ctime) row.ctime = row.mtime;
      await reqP(s2.put(row));
    }
  }
  async function listAll() {
    const s = await tx();
    const rows = await reqP(s.getAllKeys());
    return rows;
  }
  async function listTree(root='/') {
    root = U.normPath(root);
    const s = await tx();
    const rows = await reqP(s.getAll());
    return rows.filter(r => r.path === root || r.path.startsWith(root === '/' ? '/' : root + '/'))
      .map(r => ({ path:r.path, type:r.type, size:r.type==='file'?(r.data?r.data.byteLength:0):0, mtime:r.mtime, ctime:r.ctime||r.mtime }));
  }
  // Прямые дети одной директории с метаданными (для ленивого дерева проводника).
  // Не рекурсивно — быстро даже для огромных папок.
  async function readdirStat(path) {
    path = U.normPath(path);
    const s = await tx();
    const n = await reqP(s.get(path));
    if (!n) throw Object.assign(new Error('ENOENT: '+path), { code:'ENOENT', path });
    if (n.type !== 'dir') throw Object.assign(new Error('ENOTDIR: '+path), { code:'ENOTDIR', path });
    const idx = s.index('parent');
    const rows = await reqP(idx.getAll(path));
    return rows.map(r => ({ path:r.path, type:r.type, size:r.type==='file'?(r.data?r.data.byteLength:0):0, mtime:r.mtime||0, ctime:r.ctime||r.mtime||0 }));
  }
  async function copyFile(src, dst) {
    const data = await readFile(src);
    await writeFile(dst, data);
  }

  // === isomorphic-git compatible fs adapter (promises API) ===
  const igfs = {
    promises: {
      async readFile(fp, opts) {
        const data = await readFile(fp);
        if (opts && (opts === 'utf8' || opts.encoding === 'utf8')) return U.bytesToText(data);
        return data;
      },
      async writeFile(fp, data /*, opts */) {
        if (typeof data === 'string') data = U.textToBytes(data);
        else if (data instanceof ArrayBuffer) data = new Uint8Array(data);
        else if (data && data.buffer && !(data instanceof Uint8Array)) data = new Uint8Array(data.buffer, data.byteOffset, data.byteLength);
        await writeFile(fp, data);
      },
      async unlink(fp) { return unlink(fp); },
      async readdir(fp) { return readdir(fp); },
      async mkdir(fp) { await ensureDirRecursive(fp); },
      async rmdir(fp) { return rmdir(fp); },
      async stat(fp) { return stat(fp); },
      async lstat(fp) { return stat(fp); },
      async readlink() { throw Object.assign(new Error('ENOTSUP'), { code:'ENOTSUP' }); },
      async symlink() { throw Object.assign(new Error('ENOTSUP'), { code:'ENOTSUP' }); },
      async chmod() { /* noop */ },
    }
  };

  ensureRoot();

  return {
    exists, stat, readdir, readdirStat, readFile, readFileText, writeFile, mkdir, ensureDirRecursive,
    unlink, rmdir, rmrf, rename, copyFile, listAll, listTree, igfs
  };
})();
window.FS = FS;
