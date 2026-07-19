/* ===== FS router =====
 * Маршрутизирует вызовы FS.* между тремя источниками:
 *
 *   1. GitHub remote (пути /gh/<owner>/<repo>/...) — всегда в GH.*
 *   2. LocalFS (папка с устройства через File System Access API)
 *      — если Store.fsSource === 'local' и корневая папка выбрана.
 *   3. Виртуальная FS в IndexedDB (по умолчанию) — «Локально в браузере».
 *
 * Позволяет Explorer / Editor / Tools работать с одинаковым API вне
 * зависимости от того, где физически лежат файлы.
 */
(() => {
  if (!window.FS) { console.warn('[FS-router] FS not ready'); return; }
  if (!window.GH) { console.warn('[FS-router] GH not ready — GitHub routes disabled'); }
  const orig = { ...FS };

  function isRemote(p) {
    return !!window.GH && typeof p === 'string' && GH.isRemotePath(p);
  }
  // Мы в режиме «Устройство»? Проверяем и Store, и что LocalFS-корень активен
  // (если пользователь ещё не выбрал папку — падать в LocalFS нельзя).
  function useLocalFS() {
    try {
      if (!window.LocalFS || !LocalFS.hasRoot()) return false;
      const s = (window.Store && Store.get && Store.get()) || {};
      return s.fsSource === 'local';
    } catch(_) { return false; }
  }
  // Возвращает активный не-GH источник (LocalFS или browser FS)
  function localOrBrowser() { return useLocalFS() ? window.LocalFS : orig; }

  FS.exists = async function(p) {
    if (isRemote(p)) return GH.exists(p);
    return localOrBrowser().exists(p);
  };
  FS.stat = async function(p) {
    if (isRemote(p)) return GH.stat(p);
    return localOrBrowser().stat(p);
  };
  FS.readdir = async function(p) {
    if (isRemote(p)) return GH.readdir(p);
    return localOrBrowser().readdir(p);
  };
  // Ленивое чтение содержимого ОДНОЙ папки с метаданными (для дерева проводника).
  // Не рекурсивно — не подвешивает UI на больших папках.
  FS.readdirStat = async function(p) {
    if (isRemote(p)) return GH.readdirStat(p);
    const src = localOrBrowser();
    let out = [];
    // Синтетические предки смонтированного GitHub-репо (/gh, /gh/<owner>) НЕ
    // существуют в локальной ФС — src.readdirStat/readdir по ним бросает ENOENT.
    // Это НЕ ошибка: содержимое подмешивается ниже из GH.activeRoot(). Поэтому
    // ошибку локального источника здесь глотаем и продолжаем с пустым out —
    // иначе исключение всплывает в проводник и /gh раскрывается пустым.
    try {
      if (src.readdirStat) {
        out = await src.readdirStat(p);
      } else {
        // Фолбэк на случай старого источника без readdirStat.
        const names = await src.readdir(p);
        const base = (p === '/' ? '' : p);
        out = [];
        for (const name of names) {
          const cp = base + '/' + name;
          try { const st = await src.stat(cp); out.push({ path: cp, type: st.type, size: st.size||0, mtime: st.mtime||0, ctime: st.ctime||0 }); }
          catch(_){ out.push({ path: cp, type: 'file', size: 0, mtime: 0, ctime: 0 }); }
        }
      }
    } catch (e) {
      // Путь не найден в локальной ФС. Если это предок GitHub-корня — не страшно,
      // ниже подмешаем нужный сегмент. Иначе (реально несуществующий путь) —
      // пробрасываем ошибку дальше.
      const remoteRootChk = window.GH && GH.activeRoot && GH.activeRoot();
      const normChk = (p || '/').replace(/\/+$/, '') || '/';
      const prefixChk = normChk === '/' ? '/' : normChk + '/';
      const isGhAncestor = remoteRootChk && (remoteRootChk === normChk || remoteRootChk.startsWith(prefixChk));
      if (!isGhAncestor) throw e;
      out = [];
    }
    // Подмешиваем смонтированный GitHub-репозиторий: если `p` — предок
    // remoteRoot (/gh/<owner>/<repo>), добавляем следующий сегмент пути как папку.
    // Симметрично FS.listTree / FS.listAll (иначе /gh не появляется в дереве).
    try {
      const remoteRoot = window.GH && GH.activeRoot && GH.activeRoot();
      if (remoteRoot) {
        const norm = (p || '/').replace(/\/+$/, '') || '/';
        const prefix = norm === '/' ? '/' : norm + '/';
        if (remoteRoot.startsWith(prefix)) {
          const rest = remoteRoot.slice(prefix.length);
          const nextSeg = rest.split('/')[0];
          if (nextSeg) {
            const childPath = (norm === '/' ? '' : norm) + '/' + nextSeg;
            if (!out.some(r => r.path === childPath))
              out.push({ path: childPath, type: 'dir', size: 0, mtime: Date.now(), ctime: Date.now() });
          }
        }
      }
    } catch(_){}
    return out;
  };
  FS.readFile = async function(p) {
    if (isRemote(p)) return GH.readFile(p);
    return localOrBrowser().readFile(p);
  };
  FS.readFileText = async function(p) {
    if (isRemote(p)) return U.bytesToText(await GH.readFile(p));
    return localOrBrowser().readFileText(p);
  };
  FS.writeFile = async function(p, data) {
    if (isRemote(p)) return GH.writeFile(p, data);
    return localOrBrowser().writeFile(p, data);
  };
  FS.mkdir = async function(p) {
    if (isRemote(p)) return GH.mkdir(p);
    return localOrBrowser().mkdir(p);
  };
  FS.ensureDirRecursive = async function(p) {
    if (isRemote(p)) return GH.ensureDirRecursive(p);
    return localOrBrowser().ensureDirRecursive(p);
  };
  FS.unlink = async function(p) {
    if (isRemote(p)) return GH.unlink(p);
    return localOrBrowser().unlink(p);
  };
  FS.rmdir = async function(p) {
    if (isRemote(p)) return GH.rmdir(p);
    return localOrBrowser().rmdir(p);
  };
  FS.rmrf = async function(p) {
    if (isRemote(p)) return GH.rmrf(p);
    return localOrBrowser().rmrf(p);
  };
  FS.rename = async function(from, to) {
    const fRem = isRemote(from), tRem = isRemote(to);
    if (fRem && tRem) return GH.rename(from, to);
    if (fRem || tRem) throw new Error('Переименование между локальным диском и GitHub не поддерживается');
    return localOrBrowser().rename(from, to);
  };
  FS.copyFile = async function(from, to) {
    const fRem = isRemote(from), tRem = isRemote(to);
    if (fRem && tRem) return GH.copyFile(from, to);
    if (fRem && !tRem) { const data = await GH.readFile(from); return localOrBrowser().writeFile(to, data); }
    if (!fRem && tRem) { const data = await localOrBrowser().readFile(from); return GH.writeFile(to, data); }
    return localOrBrowser().copyFile(from, to);
  };
  FS.listAll = async function() {
    const base = await localOrBrowser().listAll();
    const root = window.GH && GH.activeRoot && GH.activeRoot();
    if (!root) return base;
    const remote = GH.listAll();
    const set = new Set(base);
    for (const p of remote) set.add(p);
    // Синтетические предки /gh, /gh/<owner>
    const segs = root.split('/').filter(Boolean);
    let acc = '';
    for (const s of segs) { acc = acc + '/' + s; set.add(acc); }
    return Array.from(set);
  };
  FS.listTree = async function(root='/') {
    root = (root || '/').replace(/\/+$/, '') || '/';
    const remoteRoot = window.GH && GH.activeRoot && GH.activeRoot();
    // Case 1: запрос внутри remote-дерева — только GH
    if (remoteRoot && (root === remoteRoot || root.startsWith(remoteRoot + '/'))) {
      return GH.listTree(root);
    }
    // Case 2: запрос вне remote-дерева — берём из активного источника (LocalFS/browser)
    const localTree = await localOrBrowser().listTree(root);
    if (!remoteRoot) return localTree;
    // Если root — предок remoteRoot, объединяем
    if (remoteRoot.startsWith(root === '/' ? '/' : root + '/') || remoteRoot === root) {
      const ancestors = [];
      const parts = remoteRoot.split('/').filter(Boolean);
      let acc = '';
      for (const s of parts) {
        acc = acc + '/' + s;
        if (acc.length > root.length) ancestors.push(acc);
      }
      const combined = localTree.slice();
      const seen = new Set(localTree.map(r => r.path));
      for (const a of ancestors) {
        if (!seen.has(a)) { combined.push({ path:a, type:'dir', size:0, mtime:Date.now() }); seen.add(a); }
      }
      const remote = GH.listTree(remoteRoot);
      for (const r of remote) if (!seen.has(r.path)) { combined.push(r); seen.add(r.path); }
      return combined;
    }
    return localTree;
  };
})();
