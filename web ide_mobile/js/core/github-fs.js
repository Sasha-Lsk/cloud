/* ===== GitHub-backed virtual FS overlay =====
 *
 * When Store.activeRepo.mode === 'remote', we expose a virtual root
 *   /gh/<owner>/<repo>/...
 * that transparently proxies to the GitHub Contents / Git Data API.
 *
 * All read/write/list/delete/rename calls on paths matching that root are
 * intercepted before hitting IndexedDB. Everything OUTSIDE the root works
 * unchanged (Explorer will still show local files as siblings).
 *
 * Design points:
 *  - Tree is fetched once per open with `git/trees/{ref}?recursive=1` and cached.
 *  - File bytes are fetched lazily (`contents/{path}`) and cached in-memory.
 *  - Writes ARE COMMITTED IMMEDIATELY via Contents API — each write is a commit.
 *    (This matches user expectation: "agent edits the repo directly".)
 *  - After each commit we refresh the sha of that file. Directories don't
 *    have shas — they exist implicitly.
 *  - Deletions require a sha (Contents API requirement); we fetch the sha
 *    on demand if not cached.
 *  - Rename = create-at-new-path + delete-at-old-path (Contents API has no
 *    native rename); done as two commits.
 *  - Directory creation: GitHub has no empty directories. `mkdir` becomes a
 *    no-op that stores the path locally so Explorer shows it until the first
 *    file is written into it. Explorer's listTree merges in these placeholders.
 */
const GH = (() => {
  // ---- State ----
  // We keep everything keyed by mount root ('/gh/<owner>/<repo>') so multiple
  // repos in one session don't stomp each other, though the UI only ever
  // exposes one active repo at a time.
  const mounts = new Map(); // root -> { owner, repo, branch, tree, files, placeholderDirs, loaded, loadPromise }

  function activeRoot() {
    const ar = Store.get().activeRepo;
    if (!ar || ar.mode !== 'remote') return null;
    return ar.virtualRoot; // e.g. '/gh/octocat/hello-world'
  }
  function activeMount() {
    const r = activeRoot();
    return r ? mounts.get(r) : null;
  }
  function isRemotePath(p) {
    const root = activeRoot();
    if (!root) return false;
    p = normPath(p);
    return p === root || p.startsWith(root + '/');
  }
  function normPath(p) {
    if (!p) return '/';
    if (!p.startsWith('/')) p = '/' + p;
    p = p.replace(/\/+/g, '/');
    if (p.length > 1 && p.endsWith('/')) p = p.slice(0, -1);
    return p;
  }
  function relInRepo(p, root) {
    p = normPath(p);
    if (p === root) return '';
    return p.slice(root.length + 1);
  }

  // ---- Mount / open a repo ----
  async function mount({ owner, repo, branch }) {
    const root = '/gh/' + owner + '/' + repo;
    let m = mounts.get(root);
    if (!m) {
      m = {
        owner, repo, branch,
        tree: null,           // Map<path, {type:'blob'|'tree', sha, size}>  (paths are repo-relative)
        files: new Map(),     // Map<repoRelPath, {data:Uint8Array, sha:string|null, mtime}>
        placeholderDirs: new Set(), // repoRelPaths of empty dirs the user created
        loaded: false,
        loadPromise: null,
      };
      mounts.set(root, m);
    } else if (m.branch !== branch) {
      // Rebase to different branch — drop caches
      m.branch = branch;
      m.tree = null; m.files.clear(); m.placeholderDirs.clear(); m.loaded = false; m.loadPromise = null;
    }
    if (!m.loaded) await load(m);
    return { root, mount: m };
  }
  async function unmount(root) {
    mounts.delete(root);
  }

  async function load(m) {
    if (m.loadPromise) return m.loadPromise;
    m.loadPromise = (async () => {
      const token = Store.get().githubToken;
      const url = 'https://api.github.com/repos/' + m.owner + '/' + m.repo + '/git/trees/' + encodeURIComponent(m.branch) + '?recursive=1';
      const r = await fetch(url, { headers: { Authorization: 'token ' + token, Accept: 'application/vnd.github+json' } });
      if (r.status === 404 || r.status === 409) {
        // Empty repository (no commits yet on this branch). Treat as empty tree —
        // the first writeFile will bootstrap it (Contents API PUT works even
        // without a prior commit and will initialise the default branch).
        m.tree = new Map();
        m.empty = true;
        m.loaded = true;
        return;
      }
      if (!r.ok) throw new Error('GH tree: ' + r.status + ' ' + (await r.text()).slice(0,200));
      const j = await r.json();
      const tree = new Map();
      for (const it of (j.tree || [])) {
        // it.path is repo-relative, no leading slash
        tree.set(it.path, { type: it.type, sha: it.sha, size: it.size || 0, mode: it.mode });
      }
      m.tree = tree;
      m.empty = false;
      m.loaded = true;
      if (j.truncated) console.warn('[GH] tree truncated — >100k files or >7MB. Some files may not appear.');
    })();
    try { await m.loadPromise; }
    finally { m.loadPromise = null; }
  }
  async function refresh() {
    const m = activeMount();
    if (!m) return;
    m.tree = null; m.files.clear(); m.loaded = false; m.loadPromise = null;
    await load(m);
  }

  // ---- Path introspection ----
  function statSync(m, repoRel) {
    if (repoRel === '') return { type: 'dir', size: 0 };
    // Placeholder dirs
    if (m.placeholderDirs.has(repoRel)) return { type: 'dir', size: 0 };
    const t = m.tree.get(repoRel);
    if (t) {
      if (t.type === 'blob') return { type: 'file', size: t.size, sha: t.sha };
      if (t.type === 'tree') return { type: 'dir', size: 0, sha: t.sha };
    }
    // If any file path starts with `repoRel + '/'` — implicit dir
    for (const p of m.tree.keys()) {
      if (p.startsWith(repoRel + '/')) return { type: 'dir', size: 0 };
    }
    return null;
  }

  // Children directly under repoRel (repoRel === '' means repo root).
  function childrenOf(m, repoRel) {
    const prefix = repoRel === '' ? '' : repoRel + '/';
    const seen = new Map(); // name -> {type,size,sha}
    for (const [p, info] of m.tree) {
      if (!p.startsWith(prefix)) continue;
      const rest = p.slice(prefix.length);
      if (!rest) continue;
      const slash = rest.indexOf('/');
      if (slash === -1) {
        // direct file/dir at this level
        seen.set(rest, { type: info.type === 'blob' ? 'file' : 'dir', size: info.size || 0, sha: info.sha });
      } else {
        const name = rest.slice(0, slash);
        if (!seen.has(name)) seen.set(name, { type: 'dir', size: 0 });
      }
    }
    for (const p of m.placeholderDirs) {
      if (!p.startsWith(prefix)) continue;
      const rest = p.slice(prefix.length);
      if (!rest || rest.indexOf('/') !== -1) continue;
      if (!seen.has(rest)) seen.set(rest, { type: 'dir', size: 0 });
    }
    return seen;
  }

  // ---- Reads ----
  async function readFile(vpath) {
    const root = activeRoot(); const m = activeMount();
    if (!m) throw new Error('No active repo mount');
    const rel = relInRepo(vpath, root);
    const cached = m.files.get(rel);
    if (cached && cached.data) return cached.data;
    // Need to fetch. Use the blob API to get raw bytes without size limit issues.
    const entry = m.tree.get(rel);
    if (!entry || entry.type !== 'blob') {
      // Maybe pending write hasn't been indexed yet — try contents API
    }
    const token = Store.get().githubToken;
    // Prefer git/blobs by sha (works for files up to 100MB, returns base64).
    if (entry && entry.sha) {
      const r = await fetch('https://api.github.com/repos/' + m.owner + '/' + m.repo + '/git/blobs/' + entry.sha, {
        headers: { Authorization: 'token ' + token, Accept: 'application/vnd.github+json' }
      });
      if (!r.ok) throw new Error('GH blob: ' + r.status);
      const j = await r.json();
      const bytes = b64ToBytes(j.content || '');
      m.files.set(rel, { data: bytes, sha: entry.sha, mtime: Date.now() });
      return bytes;
    }
    // Fallback: Contents API
    const r = await fetch('https://api.github.com/repos/' + m.owner + '/' + m.repo + '/contents/' + encPath(rel) + '?ref=' + encodeURIComponent(m.branch), {
      headers: { Authorization: 'token ' + token, Accept: 'application/vnd.github+json' }
    });
    if (!r.ok) throw Object.assign(new Error('ENOENT: ' + vpath), { code:'ENOENT', path:vpath });
    const j = await r.json();
    const bytes = j.encoding === 'base64' ? b64ToBytes(j.content) : new TextEncoder().encode(j.content || '');
    m.files.set(rel, { data: bytes, sha: j.sha, mtime: Date.now() });
    return bytes;
  }

  async function readdir(vpath) {
    const root = activeRoot(); const m = activeMount();
    if (!m) throw new Error('No active repo mount');
    const rel = relInRepo(vpath, root);
    const st = statSync(m, rel);
    if (!st || st.type !== 'dir') throw Object.assign(new Error('ENOTDIR: ' + vpath), { code:'ENOTDIR', path:vpath });
    return Array.from(childrenOf(m, rel).keys()).sort();
  }

  // Прямые дети директории с метаданными (для ленивого дерева проводника).
  function readdirStat(vpath) {
    const root = activeRoot(); const m = activeMount();
    if (!m) throw new Error('No active repo mount');
    vpath = normPath(vpath);
    const rel = relInRepo(vpath, root);
    const st = statSync(m, rel);
    if (!st || st.type !== 'dir') throw Object.assign(new Error('ENOTDIR: ' + vpath), { code:'ENOTDIR', path:vpath });
    const kids = childrenOf(m, rel);
    const out = [];
    for (const [name, info] of kids) {
      const p = vpath === '/' ? '/' + name : vpath + '/' + name;
      out.push({ path: p, type: info.type, size: info.size || 0, mtime: Date.now(), ctime: Date.now() });
    }
    return out;
  }

  function stat(vpath) {
    const root = activeRoot(); const m = activeMount();
    if (!m) throw new Error('No active repo mount');
    const rel = relInRepo(vpath, root);
    const st = statSync(m, rel);
    if (!st) throw Object.assign(new Error('ENOENT: ' + vpath), { code:'ENOENT', path:vpath });
    return {
      path: vpath, type: st.type, size: st.size, mtime: Date.now(),
      isFile: () => st.type === 'file', isDirectory: () => st.type === 'dir', isSymbolicLink: () => false
    };
  }

  function exists(vpath) {
    const root = activeRoot(); const m = activeMount();
    if (!m) return false;
    const rel = relInRepo(vpath, root);
    return !!statSync(m, rel);
  }

  // Full recursive listing (for Explorer & fs_list/fs_search)
  function listTree(vpath) {
    const root = activeRoot(); const m = activeMount();
    if (!m || !m.tree) return [];
    vpath = normPath(vpath);
    const rel = relInRepo(vpath, root);
    const rows = [];
    // Root itself
    rows.push({ path: vpath, type: 'dir', size: 0, mtime: Date.now() });
    const prefix = rel === '' ? '' : rel + '/';
    // Files from tree
    for (const [p, info] of m.tree) {
      if (rel !== '' && !p.startsWith(prefix) && p !== rel) continue;
      rows.push({
        path: root + '/' + p,
        type: info.type === 'blob' ? 'file' : 'dir',
        size: info.size || 0,
        mtime: Date.now()
      });
    }
    // Implicit + placeholder directories (dedup by path)
    const dirs = new Set();
    for (const [p, info] of m.tree) {
      if (info.type !== 'blob') continue;
      let acc = '';
      for (const seg of p.split('/').slice(0, -1)) {
        acc = acc ? acc + '/' + seg : seg;
        dirs.add(acc);
      }
    }
    for (const d of m.placeholderDirs) dirs.add(d);
    for (const d of dirs) {
      if (rel !== '' && !(d === rel || d.startsWith(prefix))) continue;
      // Was it already added above (as a tree 'tree' node)?
      if (!rows.some(r => r.path === root + '/' + d)) {
        rows.push({ path: root + '/' + d, type: 'dir', size: 0, mtime: Date.now() });
      }
    }
    return rows;
  }
  function listAll() {
    const root = activeRoot(); const m = activeMount();
    if (!m || !m.tree) return [];
    return listTree(root).map(r => r.path);
  }

  // ---- Writes (commit-per-op) ----
  async function writeFile(vpath, data) {
    const root = activeRoot(); const m = activeMount();
    if (!m) throw new Error('No active repo mount');
    const rel = relInRepo(vpath, root);
    if (typeof data === 'string') data = new TextEncoder().encode(data);
    if (data instanceof ArrayBuffer) data = new Uint8Array(data);
    if (!(data instanceof Uint8Array)) throw new Error('writeFile: bad data');

    const token = Store.get().githubToken;
    const existing = m.tree.get(rel);
    const body = {
      message: (existing ? 'Update ' : 'Create ') + rel + ' (from Mobile IDE)',
      content: bytesToB64(data),
      branch: m.branch
    };
    // Only include sha if the file currently exists on the remote — GitHub 422s
    // if we send sha for a nonexistent path.
    if (existing && existing.type === 'blob' && existing.sha) body.sha = existing.sha;
    const r = await fetch('https://api.github.com/repos/' + m.owner + '/' + m.repo + '/contents/' + encPath(rel), {
      method: 'PUT',
      headers: { Authorization: 'token ' + token, Accept: 'application/vnd.github+json', 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    });
    if (!r.ok) throw new Error('GH write: ' + r.status + ' ' + (await r.text()).slice(0,200));
    const j = await r.json();
    // Update caches
    const newSha = j.content && j.content.sha;
    m.tree.set(rel, { type: 'blob', sha: newSha, size: data.byteLength });
    m.files.set(rel, { data, sha: newSha, mtime: Date.now() });
    // Remove any placeholder dir that just became implicit
    for (const d of Array.from(m.placeholderDirs)) if (rel.startsWith(d + '/') || rel === d) m.placeholderDirs.delete(d);
  }

  async function unlink(vpath) {
    const root = activeRoot(); const m = activeMount();
    if (!m) throw new Error('No active repo mount');
    const rel = relInRepo(vpath, root);
    const entry = m.tree.get(rel);
    if (!entry || entry.type !== 'blob') {
      throw Object.assign(new Error('ENOENT: '+vpath), { code:'ENOENT', path:vpath });
    }
    const token = Store.get().githubToken;
    const r = await fetch('https://api.github.com/repos/' + m.owner + '/' + m.repo + '/contents/' + encPath(rel), {
      method: 'DELETE',
      headers: { Authorization: 'token ' + token, Accept: 'application/vnd.github+json', 'Content-Type': 'application/json' },
      body: JSON.stringify({ message: 'Delete ' + rel + ' (from Mobile IDE)', sha: entry.sha, branch: m.branch })
    });
    if (!r.ok) throw new Error('GH delete: ' + r.status + ' ' + (await r.text()).slice(0,200));
    m.tree.delete(rel);
    m.files.delete(rel);
  }

  // Recursive remove: delete every blob under `vpath`, ignoring dirs.
  async function rmrf(vpath) {
    const root = activeRoot(); const m = activeMount();
    if (!m) throw new Error('No active repo mount');
    const rel = relInRepo(vpath, root);
    if (rel === '') throw new Error('Отказ: нельзя удалить корень репозитория');
    const victims = [];
    for (const [p, info] of m.tree) {
      if (info.type !== 'blob') continue;
      if (p === rel || p.startsWith(rel + '/')) victims.push(p);
    }
    // Delete blobs one by one via Contents API (no batch delete in REST v3 without git-data commits)
    for (const p of victims) {
      const entry = m.tree.get(p);
      await deleteBlob(m, p, entry.sha);
      m.tree.delete(p);
      m.files.delete(p);
    }
    m.placeholderDirs.delete(rel);
    for (const d of Array.from(m.placeholderDirs)) if (d.startsWith(rel + '/')) m.placeholderDirs.delete(d);
  }
  async function deleteBlob(m, rel, sha) {
    const token = Store.get().githubToken;
    const r = await fetch('https://api.github.com/repos/' + m.owner + '/' + m.repo + '/contents/' + encPath(rel), {
      method: 'DELETE',
      headers: { Authorization: 'token ' + token, Accept: 'application/vnd.github+json', 'Content-Type': 'application/json' },
      body: JSON.stringify({ message: 'Delete ' + rel + ' (from Mobile IDE)', sha, branch: m.branch })
    });
    if (!r.ok) throw new Error('GH delete '+rel+': ' + r.status);
  }

  async function rmdir(vpath) {
    // GitHub has no empty dirs — this is only meaningful for placeholders
    const root = activeRoot(); const m = activeMount();
    if (!m) throw new Error('No active repo mount');
    const rel = relInRepo(vpath, root);
    if (m.placeholderDirs.has(rel)) { m.placeholderDirs.delete(rel); return; }
    // If it has any children, ENOTEMPTY; else silently succeed
    for (const p of m.tree.keys()) if (p.startsWith(rel + '/')) throw Object.assign(new Error('ENOTEMPTY: '+vpath), { code:'ENOTEMPTY', path:vpath });
  }

  async function mkdir(vpath) {
    const root = activeRoot(); const m = activeMount();
    if (!m) throw new Error('No active repo mount');
    const rel = relInRepo(vpath, root);
    if (rel === '') return;
    // GitHub can't hold empty dirs. Register a placeholder so Explorer shows it.
    m.placeholderDirs.add(rel);
  }
  async function ensureDirRecursive(vpath) {
    // Same as mkdir but for every ancestor
    const root = activeRoot(); const m = activeMount();
    if (!m) throw new Error('No active repo mount');
    const rel = relInRepo(vpath, root);
    if (rel === '') return;
    const parts = rel.split('/');
    for (let i = 1; i <= parts.length; i++) {
      const p = parts.slice(0, i).join('/');
      const st = statSync(m, p);
      if (!st) m.placeholderDirs.add(p);
    }
  }

  // Rename = copy then delete. For directories, recursively.
  async function rename(fromV, toV) {
    const root = activeRoot(); const m = activeMount();
    if (!m) throw new Error('No active repo mount');
    const fromR = relInRepo(fromV, root);
    const toR = relInRepo(toV, root);
    if (fromR === toR) return;
    const fromEntry = m.tree.get(fromR);
    if (fromEntry && fromEntry.type === 'blob') {
      // single file
      const data = await readFile(fromV);
      await writeFile(toV, data);
      await unlink(fromV);
      return;
    }
    // directory — collect blobs under fromR, then move each
    const under = [];
    for (const [p, info] of m.tree) if (info.type === 'blob' && (p === fromR || p.startsWith(fromR + '/'))) under.push(p);
    for (const p of under) {
      const newRel = toR + p.slice(fromR.length);
      const data = await readFile(root + '/' + p);
      await writeFile(root + '/' + newRel, data);
      await deleteBlob(m, p, m.tree.get(p).sha);
      m.tree.delete(p); m.files.delete(p);
    }
    if (m.placeholderDirs.has(fromR)) { m.placeholderDirs.delete(fromR); m.placeholderDirs.add(toR); }
  }
  async function copyFile(fromV, toV) {
    const data = await readFile(fromV);
    await writeFile(toV, data);
  }

  // ---- utils ----
  function bytesToB64(bytes) {
    if (typeof btoa !== 'undefined') {
      let s = '';
      const chunk = 0x8000;
      for (let i = 0; i < bytes.length; i += chunk) s += String.fromCharCode.apply(null, bytes.subarray(i, i+chunk));
      return btoa(s);
    }
    return Buffer.from(bytes).toString('base64');
  }
  function b64ToBytes(b64) {
    const clean = String(b64 || '').replace(/\s+/g, '');
    if (typeof atob !== 'undefined') {
      const bin = atob(clean);
      const out = new Uint8Array(bin.length);
      for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
      return out;
    }
    return new Uint8Array(Buffer.from(clean, 'base64'));
  }
  function encPath(p) {
    return p.split('/').map(encodeURIComponent).join('/');
  }

  return {
    mount, unmount, refresh,
    isRemotePath, activeRoot, activeMount,
    readFile, writeFile, unlink, rmrf, rmdir, mkdir, ensureDirRecursive,
    rename, copyFile, readdir, readdirStat, stat, exists, listTree, listAll
  };
})();
window.GH = GH;
