/* ===== Explorer ===== */
const Explorer = (() => {
  const list = () => U.$('#explorer-list');
  const emptyEl = () => U.$('#explorer-empty');
  const expanded = new Set(['/']);
  let selected = null;

  // Ленивое дерево: кэш прямых детей по пути родителя. Раньше проводник строил
  // ВСЁ дерево через FS.listTree('/') (рекурсивный обход всей папки). Для больших
  // папок — например декомпилированного APK с десятками тысяч файлов — это
  // подвешивало вкладку, и дерево «не отображалось». Теперь содержимое папок
  // грузится по требованию (только раскрытые), через FS.readdirStat.
  const childrenCache = new Map();   // parentPath -> rows[]  (rows: {path,type,size,mtime})
  let renderSeq = 0;                 // защита от гонок при частых перерисовках

  // Загрузить (с кэшированием в пределах одной перерисовки) прямых детей папки,
  // отсортированных «папки, потом файлы».
  async function loadChildren(path) {
    if (childrenCache.has(path)) return childrenCache.get(path);
    let rows = [];
    try { rows = await FS.readdirStat(path); }
    catch(_) { rows = []; }
    rows.sort((a, b) => {
      if (a.type !== b.type) return a.type === 'dir' ? -1 : 1;
      return U.basename(a.path).localeCompare(U.basename(b.path));
    });
    childrenCache.set(path, rows);
    return rows;
  }

  // Предзагрузить детей корня и всех раскрытых папок (чтобы синхронно построить DOM).
  async function preloadExpanded() {
    await loadChildren('/');
    // Обходим только раскрытые пути; их немного (то, что пользователь развернул).
    for (const p of Array.from(expanded)) {
      if (p === '/') continue;
      // Родитель должен существовать в кэше, иначе путь мог устареть.
      await loadChildren(p);
    }
  }

  // Multi-select state
  let selectMode = false;
  const multiSelected = new Set(); // absolute paths

  async function render() {
    const mySeq = ++renderSeq;
    // Всегда перечитываем содержимое раскрытых папок (их немного), чтобы дерево
    // отражало актуальное состояние ФС после создания/удаления/переименования.
    // При этом НЕ обходим всё дерево целиком — только раскрытые узлы.
    childrenCache.clear();
    // Ленивая загрузка: тянем детей корня и только раскрытых папок.
    await preloadExpanded();
    // Если за время await началась более свежая перерисовка — прекращаем.
    if (mySeq !== renderSeq) return;

    list().innerHTML = '';
    const rootChildren = childrenCache.get('/') || [];
    emptyEl().style.display = rootChildren.length ? 'none' : 'block';
    function build(path, depth) {
      const arr = childrenCache.get(path) || [];
      for (const n of arr) {
        const isDir = n.type === 'dir';
        const icon = U.fileIcon(U.basename(n.path), isDir);
        const isExp = isDir && expanded.has(n.path);
        const isMulti = multiSelected.has(n.path);
        const cls = 'tree-item'
          + (selected === n.path && !selectMode ? ' selected' : '')
          + (selectMode ? ' multi-mode' : '')
          + (isMulti ? ' multi-selected' : '');
        const children = [];
        if (selectMode) {
          children.push(U.el('span', { class:'ms-check' + (isMulti ? ' on' : '') }, isMulti ? '✓' : ''));
        }
        children.push(
          U.el('span', { class: 'chev' + (isDir ? '' : ' hidden') },
            isDir ? (isExp ? '▾' : '▸') : ''),
          U.el('span', { class:'icon '+icon.cls }, isDir ? (isExp?'📂':'📁') : icon.ch),
          U.el('span', { class:'name' }, U.basename(n.path)),
          !isDir ? U.el('span', { class:'meta' }, U.bytesToStr(n.size)) : null
        );
        const item = U.el('div', {
          class: cls,
          style: { paddingLeft: (8 + depth*14) + 'px' },
          'data-path': n.path,
          'data-type': n.type,
        }, ...children);
        item.addEventListener('click', (ev) => {
          // В режиме мульти-выделения клик по шеврону папки — сворачивает/раскрывает её,
          // а не переключает выделение. Так пользователь может уменьшить дерево, чтобы
          // добраться до нужной панели действий и других файлов.
          if (selectMode && isDir && ev.target && ev.target.closest && ev.target.closest('.chev')) {
            ev.stopPropagation();
            if (expanded.has(n.path)) expanded.delete(n.path);
            else expanded.add(n.path);
            render();
            return;
          }
          onClick(n);
        });
        item.addEventListener('contextmenu', e => {
          e.preventDefault();
          // Если long-press уже открыл меню — не дублируем
          if (item.__lpFired) { item.__lpFired = false; return; }
          item.__ctxFired = true;
          setTimeout(()=>{ item.__ctxFired = false; }, 600);
          if (selectMode) toggleMulti(n);
          else openContext(n);
        });
        attachLongPress(item, () => {
          // Если браузер уже показал нативное contextmenu — не дублируем
          if (item.__ctxFired) { item.__ctxFired = false; return; }
          item.__lpFired = true;
          setTimeout(()=>{ item.__lpFired = false; }, 600);
          if (selectMode) toggleMulti(n);
          else openContext(n);
        }, item);
        list().appendChild(item);
        if (isDir && isExp) build(n.path, depth+1);
      }
    }
    build('/', 0);
    renderSelectBar();
  }

  function toggleMulti(n) {
    if (multiSelected.has(n.path)) multiSelected.delete(n.path);
    else multiSelected.add(n.path);
    render();
  }
  function enterSelectMode(preselectPath) {
    selectMode = true;
    multiSelected.clear();
    if (preselectPath) multiSelected.add(preselectPath);
    try { navigator.vibrate && navigator.vibrate(20); } catch(_){}
    render();
  }
  function exitSelectMode() {
    selectMode = false;
    multiSelected.clear();
    render();
  }
  // SVG-иконки для кнопок панели мультивыделения (24×24, currentColor).
  const ESB_ICONS = {
    all:    '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="18" height="18" rx="2"/><path d="M8 12l3 3 5-6"/></svg>',
    none:   '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="18" height="18" rx="2"/></svg>',
    copy:   '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="12" height="12" rx="2"/><path d="M5 15V5a2 2 0 0 1 2-2h10"/></svg>',
    move:   '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M5 9l-3 3 3 3"/><path d="M9 5l3-3 3 3"/><path d="M15 19l-3 3-3-3"/><path d="M19 9l3 3-3 3"/><path d="M2 12h20"/><path d="M12 2v20"/></svg>',
    del:    '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 6h18"/><path d="M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/><path d="M6 6l1 14a2 2 0 0 0 2 2h6a2 2 0 0 0 2-2l1-14"/></svg>',
    close:  '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M6 6l12 12M6 18L18 6"/></svg>',
    // Иконка zip: папка + текстовая метка "ZIP". Используем text внутри SVG,
    // чтобы значок был визуально узнаваем именно как архивация.
    zip:    '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/><text x="12" y="16.5" text-anchor="middle" font-size="6.5" font-weight="700" stroke="none" fill="currentColor" font-family="ui-sans-serif,system-ui,sans-serif">ZIP</text></svg>',
  };
  function makeIconBtn(svg, title, cls, onClick) {
    const b = U.el('button', { class:'esb-btn ' + (cls||''), title, 'aria-label':title });
    b.innerHTML = svg;
    b.addEventListener('click', onClick);
    return b;
  }
  function renderSelectBar() {
    let bar = document.getElementById('exp-select-bar');
    if (!selectMode) { if (bar) bar.remove(); return; }
    if (!bar) {
      bar = U.el('div', { id:'exp-select-bar', class:'exp-select-bar' });
      U.$('#tab-explorer').appendChild(bar);
    }
    const count = multiSelected.size;
    bar.innerHTML = '';

    const btnCancel = makeIconBtn(ESB_ICONS.close, 'Выйти из режима', 'ghost esb-cancel', exitSelectMode);
    const info = U.el('div', { class:'esb-info' }, `Выбрано: ${count}`);
    const btnAll = makeIconBtn(ESB_ICONS.all, 'Выделить все', 'ghost', () => {
      const items = list().querySelectorAll('.tree-item');
      for (const el of items) { const p = el.getAttribute('data-path'); if (p) multiSelected.add(p); }
      render();
    });
    const btnNone = makeIconBtn(ESB_ICONS.none, 'Снять выделение', 'ghost', () => { multiSelected.clear(); render(); });
    const btnCopy = makeIconBtn(ESB_ICONS.copy, 'Копировать', 'ghost', () => transferSelected('copy'));
    const btnMove = makeIconBtn(ESB_ICONS.move, 'Переместить', 'ghost', () => transferSelected('move'));
    const btnZip  = makeIconBtn(ESB_ICONS.zip,  'Архивировать выбранное в zip', 'ghost', zipSelected);
    const btnDel  = makeIconBtn(ESB_ICONS.del,  'Удалить',    'danger', deleteSelected);
    for (const b of [btnCopy, btnMove, btnZip, btnDel]) {
      if (count === 0) { b.disabled = true; b.style.opacity = '.4'; }
    }

    bar.appendChild(btnCancel);
    bar.appendChild(info);
    bar.appendChild(btnAll);
    bar.appendChild(btnNone);
    bar.appendChild(btnCopy);
    bar.appendChild(btnMove);
    bar.appendChild(btnZip);
    bar.appendChild(btnDel);
  }

  // Собрать список всех папок в проводнике, отсортированный по пути.
  async function listAllDirs() {
    const rows = await FS.listTree('/');
    const dirs = [{ path:'/', depth:0 }];
    for (const r of rows) {
      if (r.type === 'dir' && r.path !== '/') {
        dirs.push({ path:r.path, depth:(r.path.match(/\//g)||[]).length });
      }
    }
    dirs.sort((a,b) => a.path.localeCompare(b.path));
    return dirs;
  }

  // Показать модалку выбора целевой папки.
  // forbiddenPrefixes — пути, внутрь которых нельзя переносить (нельзя копировать/переместить в самого себя или в потомка).
  // Возвращает выбранный путь или null.
  function pickTargetDir(title, okText, forbiddenPrefixes = []) {
    return new Promise(async resolve => {
      const dirs = await listAllDirs();
      let selectedPath = null;
      const body = U.el('div', { class:'dir-picker' });
      const rowsWrap = U.el('div', { class:'dir-picker-list', style:'max-height:50vh;overflow:auto;' });
      body.appendChild(rowsWrap);

      const isForbidden = (p) => forbiddenPrefixes.some(fp => p === fp || p.startsWith(fp === '/' ? '/' : fp + '/'));

      for (const d of dirs) {
        const disabled = isForbidden(d.path);
        const row = U.el('div', {
          class: 'drawer-item dir-picker-item' + (disabled ? ' disabled' : ''),
          style: { paddingLeft: (10 + d.depth*14) + 'px', opacity: disabled ? '.4' : '1' },
          'data-path': d.path,
        },
          U.el('span', { class:'icon' }, d.path === '/' ? '🗂' : '📁'),
          U.el('span', { class:'label' }, d.path === '/' ? '/ (корень)' : d.path)
        );
        if (!disabled) {
          row.addEventListener('click', () => {
            selectedPath = d.path;
            rowsWrap.querySelectorAll('.dir-picker-item').forEach(x => x.classList.remove('selected'));
            row.classList.add('selected');
            okBtn.disabled = false;
            okBtn.style.opacity = '1';
          });
        }
        rowsWrap.appendChild(row);
      }

      const okBtn = U.el('button', { class:'btn primary' }, okText || 'OK');
      okBtn.disabled = true; okBtn.style.opacity = '.5';
      okBtn.addEventListener('click', () => { UI.closeSheet(); resolve(selectedPath); });
      const cancelBtn = U.el('button', { class:'btn ghost' }, 'Отмена');
      cancelBtn.addEventListener('click', () => { UI.closeSheet(); resolve(null); });

      UI.openSheet(title, body, [cancelBtn, okBtn]);
    });
  }

  // Универсальная проверка "директория ли": stat может отдавать type-строку или функции isDirectory/isFile.
  function statIsDir(st) {
    if (!st) return false;
    if (st.type === 'dir' || st.type === 'directory') return true;
    if (st.type === 'file') return false;
    if (typeof st.isDirectory === 'function') return !!st.isDirectory();
    if (typeof st.isDirectory === 'boolean') return st.isDirectory;
    if (typeof st.isFile === 'function') return !st.isFile();
    return false;
  }

  // Рекурсивное копирование содержимого пути srcPath в папку dstDir (сохраняя имя базовой ноды).
  async function copyPathInto(srcPath, dstDir) {
    let st = null;
    try { st = await FS.stat(srcPath); } catch(_){}
    const isDir = statIsDir(st);
    const targetPath = U.joinPath(dstDir, U.basename(srcPath));
    if (isDir) {
      await FS.ensureDirRecursive(targetPath);
      // Собираем список содержимого srcPath
      const rows = await FS.listTree(srcPath);
      // Сортируем чтобы папки создавались раньше файлов
      rows.sort((a,b) => {
        if (a.type !== b.type) return a.type === 'dir' ? -1 : 1;
        return a.path.length - b.path.length;
      });
      for (const r of rows) {
        if (r.path === srcPath) continue;
        const rel = r.path.slice(srcPath.length); // например /sub/f.txt
        const dst = U.normPath(targetPath + rel);
        if (r.type === 'dir') { await FS.ensureDirRecursive(dst); }
        else { await FS.copyFile(r.path, dst); }
      }
    } else {
      await FS.ensureDirRecursive(dstDir);
      await FS.copyFile(srcPath, targetPath);
    }
    return targetPath;
  }

  // Копирование / перемещение выбранных объектов.
  async function transferSelected(mode /* 'copy' | 'move' */) {
    const paths = Array.from(multiSelected);
    if (!paths.length) return;
    // Схлопнем вложенные (если выделены и родитель и его ребёнок — оставим только родителя)
    paths.sort((a,b) => a.length - b.length);
    const kept = [];
    for (const p of paths) {
      const prefixed = kept.some(k => p === k || p.startsWith(k.endsWith('/') ? k : k + '/'));
      if (!prefixed) kept.push(p);
    }
    // Запрещаем целевые папки, которые совпадают или лежат внутри выбранных папок,
    // а также сами родительские папки выбранных файлов (иначе копирование = дубликат в том же месте — разрешим для copy,
    // но для move это no-op). Для простоты запретим для обоих режимов только "self и внутрь".
    const forbidden = new Set();
    for (const p of kept) forbidden.add(p);
    const title = mode === 'copy' ? 'Копировать в…' : 'Переместить в…';
    const okText = mode === 'copy' ? 'Копировать сюда' : 'Переместить сюда';
    const target = await pickTargetDir(title, okText, Array.from(forbidden));
    if (!target) return;

    let done = 0, failed = 0, errors = [];
    for (const src of kept) {
      try {
        // Пропустим если целевая — та же папка, и режим move (нет смысла)
        const parent = U.dirname(src);
        if (mode === 'move' && parent === target) continue;
        if (mode === 'copy') {
          await copyPathInto(src, target);
        } else {
          const dst = U.joinPath(target, U.basename(src));
          // FS.rename поддерживает и файлы, и папки (см. fs.js). Для GH тоже работает.
          try {
            await FS.rename(src, dst);
          } catch(e) {
            // fallback: копировать + удалить
            await copyPathInto(src, target);
            let isDir = false;
            try { isDir = statIsDir(await FS.stat(src)); } catch(_){}
            if (isDir) await FS.rmrf(src); else { try { await FS.unlink(src); } catch(_){ await FS.rmrf(src); } }
          }
          Editor.onPathRenamed && Editor.onPathRenamed(src, U.joinPath(target, U.basename(src)));
        }
        done++;
      } catch(e) { failed++; errors.push(e.message || String(e)); }
    }
    const verb = mode === 'copy' ? 'Скопировано' : 'Перемещено';
    UI.toast(`${verb}: ${done}${failed ? ', ошибок: '+failed : ''}`, failed ? 'err' : 'ok');
    if (failed && errors.length) console.warn('[transfer errors]', errors);
    // Раскроем целевую папку и выйдем из режима
    expanded.add(U.normPath(target));
    exitSelectMode();
    await render();
  }

  // Копировать / переместить ОДИН элемент (из контекстного меню долгого нажатия).
  // Аналог Android transferOne(): показывает выбор целевой папки и выполняет
  // операцию (rename для move с fallback copy+delete; copyPathInto для copy).
  async function transferOne(src, mode /* 'copy' | 'move' */) {
    if (!src) return;
    const forbidden = [src]; // нельзя копировать/перемещать внутрь самого себя
    const name = U.basename(src);
    const title = mode === 'copy' ? 'Копировать «' + name + '» в…' : 'Переместить «' + name + '» в…';
    const okText = mode === 'copy' ? 'Копировать сюда' : 'Переместить сюда';
    const target = await pickTargetDir(title, okText, forbidden);
    if (!target) return;
    const parent = U.dirname(src);
    if (mode === 'move' && parent === target) { UI.toast('Элемент уже в этой папке'); return; }
    try {
      if (mode === 'copy') {
        await copyPathInto(src, target);
      } else {
        const dst = U.joinPath(target, U.basename(src));
        try {
          await FS.rename(src, dst);
        } catch (e) {
          // fallback: копировать + удалить исходник
          await copyPathInto(src, target);
          let isDir = false;
          try { isDir = statIsDir(await FS.stat(src)); } catch (_) {}
          if (isDir) await FS.rmrf(src); else { try { await FS.unlink(src); } catch (_) { await FS.rmrf(src); } }
        }
        Editor.onPathRenamed && Editor.onPathRenamed(src, U.joinPath(target, U.basename(src)));
      }
      expanded.add(U.normPath(target));
      await render();
      UI.toast(mode === 'copy' ? 'Скопировано в ' + target : 'Перемещено в ' + target, 'ok');
    } catch (e) {
      UI.toast('Ошибка: ' + (e.message || e), 'err');
    }
  }
  // Архивирование выбранных элементов в один zip.
  // Пути внутри архива — относительно общего родителя выбранных элементов
  // (например, если выбраны /a/b/x.js и /a/b/dir/, они попадут в архив как x.js и dir/…).
  async function zipSelected() {
    const paths = Array.from(multiSelected);
    if (!paths.length) return;
    // Схлопываем: если выбраны и родитель, и его ребёнок — оставляем только родителя.
    paths.sort((a,b) => a.length - b.length);
    const kept = [];
    for (const p of paths) {
      const prefixed = kept.some(k => p === k || p.startsWith(k.endsWith('/') ? k : k + '/'));
      if (!prefixed) kept.push(p);
    }
    // Общий родитель для относительных путей в архиве.
    // Если выбран 1 элемент — родитель = его dirname.
    // Иначе — самый длинный общий префикс dirname'ов всех выбранных.
    function commonDir(paths) {
      if (paths.length === 1) return U.dirname(paths[0]);
      const parts = paths.map(p => p.split('/').filter(Boolean));
      const min = Math.min(...parts.map(a => a.length));
      const acc = [];
      for (let i = 0; i < min; i++) {
        const seg = parts[0][i];
        if (parts.every(a => a[i] === seg)) acc.push(seg);
        else break;
      }
      return '/' + acc.join('/');
    }
    const rootDir = commonDir(kept) || '/';
    const rootPrefix = rootDir === '/' ? '/' : (rootDir + '/');

    const prog = UI.progress('Архивация', 'Подготовка…');
    const bag = {};
    let fileCount = 0, totalBytes = 0;
    try {
      // Сначала собираем плоский список файлов, чтобы знать общее число для прогресса.
      await prog.set(0, 'Сканирование…');
      const flat = [];
      for (const p of kept) {
        let st = null;
        try { st = await FS.stat(p); } catch(_){}
        const isDir = statIsDir(st);
        if (!isDir) {
          const rel = p.startsWith(rootPrefix) ? p.slice(rootPrefix.length) : U.basename(p);
          if (rel) flat.push({ path: p, rel });
        } else {
          const rows = await FS.listTree(p);
          for (const r of rows) {
            if (r.type !== 'file') continue;
            const rel = r.path.startsWith(rootPrefix) ? r.path.slice(rootPrefix.length) : r.path.slice(1);
            if (rel) flat.push({ path: r.path, rel });
          }
        }
      }
      const total = flat.length || 1;
      let lastPct = -1;
      for (let i = 0; i < flat.length; i++) {
        // Пользователь нажал «Отмена» — прерываем без записи архива.
        if (prog.isCancelled()) { prog.close(); UI.toast('Архивация отменена', 'err'); return; }
        const { path: fp, rel } = flat[i];
        const data = await FS.readFile(fp);
        bag[rel] = data;
        fileCount++;
        totalBytes += data.length || data.byteLength || 0;
        // Чтение занимает первые 90% прогресса, сжатие — оставшиеся 10%.
        const pct = Math.round((i + 1) / total * 90);
        if (pct !== lastPct) { lastPct = pct; await prog.set(pct, `Добавлено ${fileCount} файл(ов) · ${pct}%`); }
      }
      // Финальная проверка перед сжатием/записью (отмена во время последнего файла).
      if (prog.isCancelled()) { prog.close(); UI.toast('Архивация отменена', 'err'); return; }
      if (!fileCount) { prog.close(); UI.toast('Нет файлов для архивации', 'err'); return; }
      await prog.set(92, 'Сжатие данных…');
      const zipped = fflate.zipSync(bag);
      // Имя архива: если 1 выбранный элемент — по нему; иначе по общей папке;
      // если общая папка = /, то selection.zip.
      let baseName;
      if (kept.length === 1) {
        baseName = (U.basename(kept[0]) || 'archive').replace(/\.[^.]+$/, '');
      } else if (rootDir && rootDir !== '/') {
        baseName = U.basename(rootDir);
      } else {
        baseName = 'selection';
      }
      // Куда сохраняем архив: в общей папке выбранных (rootDir) — так пользователь его сразу
      // видит рядом с исходными файлами. Если это корень — /.
      const targetDir = rootDir || '/';
      const { name: zipName, path: zipPath } = await pickFreeZipName(targetDir, baseName);
      if (targetDir !== '/') { try { await FS.ensureDirRecursive(targetDir); } catch(_){} }
      await prog.set(97, 'Запись архива…');
      await FS.writeFile(zipPath, zipped);
      await prog.done(`Создан ${zipName} (${fileCount} файл(ов))`);
      expanded.add(U.normPath(targetDir));
      exitSelectMode();
      await render();
      UI.toast(`Создан ${zipName} (${fileCount} файл(ов), ${U.bytesToStr(totalBytes)})`, 'ok');
    } catch(e) {
      prog.close();
      UI.toast('Ошибка архивации: ' + (e.message || e), 'err');
    }
  }

  async function deleteSelected() {
    const paths = Array.from(multiSelected);
    if (!paths.length) return;
    paths.sort((a,b) => a.length - b.length);
    const kept = [];
    for (const p of paths) {
      const prefixed = kept.some(k => p === k || p.startsWith(k.endsWith('/') ? k : k + '/'));
      if (!prefixed) kept.push(p);
    }
    const ok = await UI.confirm(
      `Удалить выбранные объекты (${kept.length})? Это действие необратимо.`,
      { danger:true, okText:'Удалить', title:'Массовое удаление' }
    );
    if (!ok) return;
    let done = 0, failed = 0;
    for (const p of kept) {
      try {
        let isDir = false;
        try { isDir = statIsDir(await FS.stat(p)); } catch(_){}
        if (isDir) await FS.rmrf(p);
        else { try { await FS.unlink(p); } catch(_){ await FS.rmrf(p); } }
        Editor.onPathDeleted && Editor.onPathDeleted(p);
        done++;
      } catch(_) { failed++; }
    }
    UI.toast(`Удалено: ${done}${failed?', ошибок: '+failed:''}`, failed ? 'err' : 'ok');
    exitSelectMode();
    await render();
  }

  function attachLongPress(el, fn) {
    let t = null;
    const clear = ()=>{ if (t){ clearTimeout(t); t=null; } };
    el.addEventListener('touchstart', () => {
      clear();
      el.__ctxFired = false;
      t = setTimeout(()=>{ t=null; fn(); }, 500);
    }, {passive:true});
    el.addEventListener('touchmove', clear, {passive:true});
    el.addEventListener('touchend', clear);
    el.addEventListener('touchcancel', clear);
  }

  async function onClick(n) {
    if (selectMode) { toggleMulti(n); return; }
    selected = n.path;
    if (n.type === 'dir') {
      if (expanded.has(n.path)) expanded.delete(n.path);
      else expanded.add(n.path);
      render();
    } else if (isArchive(n.path)) {
      // Клик по zip — открыть встроенный просмотрщик содержимого архива, а не редактор.
      openZipViewer(n.path);
    } else {
      await Editor.openFile(n.path);
      App.switchTab('editor');
    }
  }

  // ---- ZIP viewer ----
  // Открывает модалку со списком файлов в архиве.
  // Каждый файл: превью размера, кнопка "Открыть" (декодируем как текст в редакторе через
  // временный виртуальный путь) и кнопка скачивания через saveBlob.
  // Внизу — общие действия: "Распаковать сюда", "Скачать архив", "Закрыть".
  async function openZipViewer(zipPath) {
    let entries = [];
    let raw = null;
    try {
      raw = await FS.readFile(zipPath);
      const files = fflate.unzipSync(raw);
      for (const rel in files) {
        const bytes = files[rel];
        const isDir = rel.endsWith('/');
        entries.push({ rel, isDir, size: isDir ? 0 : bytes.length, bytes: isDir ? null : bytes });
      }
      // Сортируем: сначала папки, потом файлы, внутри — по алфавиту.
      entries.sort((a,b) => {
        if (a.isDir !== b.isDir) return a.isDir ? -1 : 1;
        return a.rel.localeCompare(b.rel);
      });
    } catch(e) {
      UI.toast('Не удалось прочитать архив: ' + (e.message||e), 'err');
      return;
    }

    const body = U.el('div', { class:'zip-viewer-body', style:'display:flex;flex-direction:column;gap:6px;max-height:60vh;overflow:auto;padding:4px 0;' });
    const info = U.el('div', { style:'font-size:12px;color:var(--fg-dim);padding:0 4px 8px;border-bottom:1px solid var(--border,#2b2f36);margin-bottom:8px;' },
      `${entries.filter(e=>!e.isDir).length} файл(ов), ${entries.filter(e=>e.isDir).length} папок · ${U.bytesToStr(raw.length)} архив`
    );
    body.appendChild(info);

    if (!entries.length) {
      body.appendChild(U.el('div', { style:'color:var(--fg-dim);padding:12px;text-align:center;' }, 'Архив пуст'));
    }
    for (const e of entries) {
      const row = U.el('div', {
        class: 'zip-entry',
        style: 'display:flex;align-items:center;gap:10px;padding:8px 10px;border-radius:6px;font-size:13px;',
      });
      row.addEventListener('mouseenter', () => row.style.background = 'var(--hover, rgba(255,255,255,.05))');
      row.addEventListener('mouseleave', () => row.style.background = 'transparent');
      const icon = e.isDir ? '📁' : U.fileIcon(U.basename(e.rel), false).ch;
      row.appendChild(U.el('span', { style:'flex:0 0 20px;' }, icon));
      row.appendChild(U.el('span', {
        style:'flex:1;word-break:break-all;color:var(--fg);',
      }, e.rel));
      if (!e.isDir) {
        row.appendChild(U.el('span', { style:'flex:0 0 auto;font-size:11px;color:var(--fg-dim);' }, U.bytesToStr(e.size)));
        // Кнопка "Открыть" — если файл текстовый, покажем содержимое в модалке.
        const openBtn = U.el('button', {
          class:'btn ghost',
          style:'padding:4px 8px;font-size:11px;',
          title:'Просмотреть',
        }, '👁');
        openBtn.addEventListener('click', (ev) => {
          ev.stopPropagation();
          previewZipEntry(zipPath, e);
        });
        row.appendChild(openBtn);
        // Кнопка "Скачать" — saveBlob через каскад.
        const dlBtn = U.el('button', {
          class:'btn ghost',
          style:'padding:4px 8px;font-size:11px;',
          title:'Скачать этот файл',
        }, '⬇');
        dlBtn.addEventListener('click', async (ev) => {
          ev.stopPropagation();
          const mime = (U.mimeFromPath && U.mimeFromPath(e.rel)) || 'application/octet-stream';
          const blob = new Blob([e.bytes], { type: mime });
          await saveBlob(blob, U.basename(e.rel));
        });
        row.appendChild(dlBtn);
      }
      body.appendChild(row);
    }

    const extractBtn = U.el('button', { class:'btn' }, 'Распаковать сюда');
    extractBtn.addEventListener('click', () => { UI.closeSheet(); extractArchive(zipPath); });
    const dlAllBtn = U.el('button', { class:'btn ghost' }, 'Скачать архив');
    dlAllBtn.addEventListener('click', async () => {
      const blob = new Blob([raw], { type:'application/zip' });
      await saveBlob(blob, U.basename(zipPath), { preferShare: true });
    });
    const closeBtn = U.el('button', { class:'btn primary' }, 'Закрыть');
    closeBtn.addEventListener('click', () => UI.closeSheet());
    UI.openSheet('Архив: ' + U.basename(zipPath), body, [dlAllBtn, extractBtn, closeBtn]);
  }

  // Превью одного файла из архива (без распаковки в ФС).
  function previewZipEntry(zipPath, entry) {
    const isBinary = U.looksBinary(entry.bytes.slice(0, 4096));
    const body = U.el('div', { style:'display:flex;flex-direction:column;gap:8px;max-height:60vh;overflow:auto;' });
    body.appendChild(U.el('div', {
      style:'font-size:11px;color:var(--fg-dim);padding:0 4px 6px;border-bottom:1px solid var(--border,#2b2f36);',
    }, `${entry.rel} · ${U.bytesToStr(entry.size)}`));
    if (!isBinary && entry.size <= 1024 * 1024) {
      // Текст — покажем как есть в <pre>
      const text = U.bytesToText(entry.bytes);
      const pre = U.el('pre', {
        style:'margin:0;padding:8px;background:var(--bg-code, #0f1115);border-radius:6px;font-size:12px;white-space:pre-wrap;word-break:break-word;color:var(--fg);max-height:50vh;overflow:auto;',
      }, text);
      body.appendChild(pre);
    } else {
      // Двоичный или очень большой файл — покажем hex-превью первых 512 байт.
      const bytes = entry.bytes.slice(0, 512);
      let hex = '';
      for (let i = 0; i < bytes.length; i += 16) {
        const chunk = bytes.slice(i, i+16);
        const hexPart = [...chunk].map(b => b.toString(16).padStart(2,'0')).join(' ');
        const asciiPart = [...chunk].map(b => (b >= 32 && b < 127) ? String.fromCharCode(b) : '.').join('');
        hex += `${i.toString(16).padStart(6,'0')}  ${hexPart.padEnd(48)}  ${asciiPart}\n`;
      }
      body.appendChild(U.el('div', { style:'font-size:12px;color:var(--fg-dim);' },
        isBinary ? 'Двоичный файл. Показаны первые 512 байт (hex):' : 'Файл слишком большой для предпросмотра. Показаны первые 512 байт (hex):'));
      body.appendChild(U.el('pre', {
        style:'margin:0;padding:8px;background:var(--bg-code, #0f1115);border-radius:6px;font-size:11px;font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;overflow:auto;color:var(--fg);max-height:50vh;',
      }, hex));
    }
    const dlBtn = U.el('button', { class:'btn ghost' }, 'Скачать этот файл');
    dlBtn.addEventListener('click', async () => {
      const mime = (U.mimeFromPath && U.mimeFromPath(entry.rel)) || 'application/octet-stream';
      const blob = new Blob([entry.bytes], { type: mime });
      await saveBlob(blob, U.basename(entry.rel));
    });
    const extractOneBtn = U.el('button', { class:'btn ghost' }, 'Извлечь в проводник');
    extractOneBtn.addEventListener('click', async () => {
      try {
        // Кладём рядом с zip'ом в подпапку с именем архива.
        const base = zipPath.replace(/\.zip$/i,'') + '/';
        const dst = U.joinPath(base, entry.rel);
        const parent = U.dirname(dst);
        if (parent !== '/') await FS.ensureDirRecursive(parent);
        await FS.writeFile(dst, entry.bytes);
        expanded.add(U.normPath(base));
        await render();
        UI.toast('Извлечено: ' + entry.rel, 'ok');
        UI.closeSheet();
      } catch(e) { UI.toast('Ошибка: ' + (e.message||e), 'err'); }
    });
    const backBtn = U.el('button', { class:'btn primary' }, '← Назад');
    backBtn.addEventListener('click', () => { UI.closeSheet(); openZipViewer(zipPath); });
    UI.openSheet(U.basename(entry.rel), body, [dlBtn, extractOneBtn, backBtn]);
  }

  function openContext(n) {
    const isDir = n.type === 'dir';
    const buttons = [];
    if (isDir) {
      buttons.push(mk('Новый файл здесь', ()=>createInside(n.path, false)));
      buttons.push(mk('Новая папка здесь', ()=>createInside(n.path, true)));
      buttons.push(mk('Импорт файлов сюда…', ()=>App.importFiles(n.path)));
    } else {
      buttons.push(mk('Открыть', ()=>Editor.openFile(n.path).then(()=>App.switchTab('editor'))));
      buttons.push(mkDownload(n.path));
    }
    buttons.push(mk('Переместить в…', ()=>transferOne(n.path, 'move')));
    buttons.push(mk('Копировать в…', ()=>transferOne(n.path, 'copy')));
    buttons.push(mk('Переименовать', ()=>renameNode(n.path)));
    buttons.push(mk('Копировать путь', ()=>copyPath(n.path)));
    if (isArchive(n.path) && !isDir) buttons.push(mk('Распаковать', ()=>extractArchive(n.path)));
    // "Сжать в zip" — доступно и для файла, и для папки. Для папки заменяет старый "Экспорт в zip".
    buttons.push(mk('Сжать в zip', ()=>compressOne(n.path)));
    buttons.push(mk('Выбрать несколько', ()=>enterSelectMode(n.path)));
    buttons.push(mk('Сведения о файле', ()=>showFileInfo(n.path)));
    buttons.push(mk('Удалить', ()=>deleteNode(n.path, isDir), 'danger'));
    const body = U.el('div');
    for (const b of buttons) body.appendChild(b);
    UI.openSheet(U.basename(n.path) || '/', body);
    function mk(label, fn, kind='') {
      const b = U.el('div', { class:'drawer-item' }, U.el('span', { class:'label' }, label));
      if (kind==='danger') b.style.color = 'var(--danger)';
      b.addEventListener('click', () => { UI.closeSheet(); fn(); });
      return b;
    }
    // Пункт "Скачать" — реальный <a href="blob:..." download="name">, который начинает
    // подгружать файл СРАЗУ при показе меню (пока пользователь читает пункты). К моменту
    // тапа href уже готов, и клик обрабатывается браузером как обычный клик по ссылке —
    // это надёжно работает и в мобильном Safari, и в WebView.
    // Если пользователь тапнет раньше, чем файл прочитается — покажем toast и запустим
    // скачивание вручную по готовности.
    function mkDownload(path) {
      const name = U.basename(path);
      // Пункт-anchor: работает как обычная ссылка на blob (нативный download-путь для
      // большинства браузеров), плюс наш click-handler запускает saveBlob-каскад с полным
      // набором fallback'ов (share, FS Access API, data-url, new tab, модалка).
      const a = U.el('a', {
        class: 'drawer-item',
        download: name,
        rel: 'noopener',
        href: 'javascript:void(0)',
        style: { textDecoration:'none', color:'inherit' },
      }, U.el('span', { class:'label' }, 'Скачать'));
      let blob = null, readErr = null;
      // Preload содержимого файла в фоне, чтобы нативный <a href="blob:" download> тоже мог сработать.
      (async () => {
        try {
          const data = await FS.readFile(path);
          const mime = (U.mimeFromPath && U.mimeFromPath(path)) || 'application/octet-stream';
          blob = new Blob([data], { type: mime });
          // Также ставим href на blob — на случай, если нативный клик по <a> сработает
          // раньше, чем наш handler завершится (некоторые Android WebView так делают).
          a.href = URL.createObjectURL(blob);
        } catch(e) { readErr = e; }
      })();
      a.addEventListener('click', async (ev) => {
        // Всегда глушим нативное поведение — используем свой каскад методов, чтобы
        // единообразно работало во всех браузерах.
        ev.preventDefault();
        UI.closeSheet();
        if (readErr) { UI.toast('Ошибка чтения: ' + (readErr.message||readErr), 'err'); return; }
        if (!blob) {
          UI.toast('Готовим файл…');
          // Ждём завершения preload (обычно уже готово, но подстрахуемся).
          const started = Date.now();
          while (!blob && !readErr && Date.now() - started < 15000) {
            await new Promise(r => setTimeout(r, 40));
          }
          if (readErr) { UI.toast('Ошибка: ' + (readErr.message||readErr), 'err'); return; }
          if (!blob) { UI.toast('Файл не готов', 'err'); return; }
        }
        await saveBlob(blob, name);
      });
      return a;
    }
  }

  // ---- Сведения о файле / папке ----
  // Показывает подробную информацию: путь, имя, тип, размер (в B/KB/MB),
  // MIME, дата создания и последнего изменения, число элементов внутри (для папок).
  function fmtSizeExact(bytes) {
    if (bytes == null || isNaN(bytes)) return '—';
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(2) + ' KB (' + bytes + ' B)';
    if (bytes < 1024 * 1024 * 1024) return (bytes / 1024 / 1024).toFixed(2) + ' MB (' + bytes + ' B)';
    return (bytes / 1024 / 1024 / 1024).toFixed(2) + ' GB (' + bytes + ' B)';
  }
  function fmtDate(ts) {
    if (!ts) return '—';
    try {
      const d = new Date(ts);
      const pad = (x) => String(x).padStart(2, '0');
      return `${d.getFullYear()}-${pad(d.getMonth()+1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
    } catch(_) { return String(ts); }
  }
  async function showFileInfo(path) {
    try {
      const st = await FS.stat(path);
      const isDir = statIsDir(st);
      let totalSize = st.size || 0;
      let filesCount = 0, dirsCount = 0;
      if (isDir) {
        try {
          const rows = await FS.listTree(path);
          for (const r of rows) {
            if (r.path === path) continue;
            if (r.type === 'file') { filesCount++; totalSize += r.size || 0; }
            else if (r.type === 'dir') dirsCount++;
          }
        } catch(_){}
      }
      const name = U.basename(path) || path;
      const parent = U.dirname(path);
      const ext = isDir ? '' : (U.extname(path) || '—');
      const mime = isDir ? '—' : (U.mimeFromPath && U.mimeFromPath(path)) || '—';
      const rows = [
        ['Имя',       name],
        ['Тип',       isDir ? 'Папка' : 'Файл'],
        ['Путь',      path],
        ['Каталог',   parent],
      ];
      if (!isDir) {
        rows.push(['Расширение', ext]);
        rows.push(['MIME',       mime]);
        rows.push(['Размер',     fmtSizeExact(totalSize)]);
      } else {
        rows.push(['Содержимое', `${filesCount} файл(ов), ${dirsCount} папок`]);
        rows.push(['Общий размер', fmtSizeExact(totalSize)]);
      }
      rows.push(['Создан',        fmtDate(st.ctime)]);
      rows.push(['Изменён',       fmtDate(st.mtime)]);

      const body = U.el('div', { class:'file-info-body', style:'display:flex;flex-direction:column;gap:8px;padding:4px 0;' });
      for (const [k, v] of rows) {
        const row = U.el('div', {
          class:'file-info-row',
          style:'display:flex;gap:10px;align-items:flex-start;font-size:13px;line-height:1.4;padding:6px 8px;border-bottom:1px solid var(--border,#2b2f36);',
        },
          U.el('span', { style:'flex:0 0 100px;color:var(--muted,#8a939c);font-weight:500;' }, k),
          U.el('span', { style:'flex:1;color:var(--fg,#e8ecf1);word-break:break-word;user-select:text;' }, String(v))
        );
        body.appendChild(row);
      }

      const copyBtn = U.el('button', { class:'btn ghost' }, 'Скопировать путь');
      copyBtn.addEventListener('click', () => copyPath(path));
      const okBtn = U.el('button', { class:'btn primary' }, 'Закрыть');
      okBtn.addEventListener('click', () => UI.closeSheet());
      UI.openSheet('Сведения: ' + name, body, [copyBtn, okBtn]);
    } catch(e) {
      UI.toast('Не удалось получить сведения: ' + (e.message || e), 'err');
    }
  }

  function isArchive(path) { return ['zip'].includes(U.extname(path)); }

  async function copyPath(path) {
    try { await navigator.clipboard.writeText(path); UI.toast('Путь скопирован','ok'); }
    catch(e){ UI.toast('Копирование недоступно','err'); }
  }

  // Универсальная функция сохранения Blob'а на устройство.
  // Проблема с мобильными: iOS Safari требует, чтобы `<a download>` был кликнут в контексте
  // свежего user-gesture и физически присутствовал в DOM. Программный .click() после await
  // (когда gesture "остыл") часто игнорируется. Стратегия:
  //   1) Пробуем Web Share API с файлом — работает на iOS 15+ и Android, вызывает нативный
  //      share sheet, где есть "Сохранить в Файлы"/"Save to Downloads". Устойчиво к async.
  //   2) Fallback: <a href="blob:..." download="..."> вставленный в body, программный click().
  //      Работает в Chrome/Firefox/Android WebView.
  //   3) Если и это не сработает — открываем blob в новой вкладке (пользователь сохранит
  //      через встроенный share sheet браузера).
  // Универсальное сохранение Blob'а на устройство пользователя.
  // Проходит по цепочке методов в порядке надёжности/удобства для конкретной платформы.
  // Возвращает true если что-то сработало (даже если пользователь отменил share sheet).
  //
  // opts.preferShare: bool — попробовать Web Share API раньше File System Access.
  //   Полезно для zip/файлов, которые логично шэрить.
  async function saveBlob(blob, name, opts = {}) {
    const isMobile = /Mobile|Android|iPhone|iPad|iPod/i.test(navigator.userAgent || '');
    const isIOS = /iPhone|iPad|iPod/i.test(navigator.userAgent || '') ||
      // iPadOS маскируется под Mac
      (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
    const size = blob.size;

    // Порядок методов зависит от платформы.
    // На мобильных: share → <a download> → data-url → fallback.
    // На desktop: FS Access API → <a download> → data-url → fallback.
    const methods = [];
    if (isMobile) {
      if (opts.preferShare !== false) methods.push('share');
      methods.push('anchor', 'dataUrl', 'newTab', 'modal');
    } else {
      methods.push('fsAccess');
      if (opts.preferShare) methods.push('share');
      methods.push('anchor', 'dataUrl', 'newTab', 'modal');
    }

    for (const m of methods) {
      const ok = await tryMethod(m, blob, name, { isMobile, isIOS, size });
      if (ok === true) return true;
      if (ok === 'cancelled') return true;
      // ok === false — пробуем следующий
    }
    UI.toast('Не удалось сохранить файл', 'err');
    return false;

    async function tryMethod(method, blob, name, ctx) {
      try {
        if (method === 'share') return await mShare(blob, name);
        if (method === 'fsAccess') return await mFsAccess(blob, name);
        if (method === 'anchor') return await mAnchor(blob, name);
        if (method === 'dataUrl') return await mDataUrl(blob, name, ctx);
        if (method === 'newTab') return await mNewTab(blob, name);
        if (method === 'modal') return await mModal(blob, name);
      } catch(_) {}
      return false;
    }

    // --- методы ---

    async function mShare(blob, name) {
      if (!navigator.share || !navigator.canShare) return false;
      try {
        const file = new File([blob], name, { type: blob.type || 'application/octet-stream' });
        if (!navigator.canShare({ files: [file] })) return false;
        // Таймаут на share — если UI не открылся (некоторые WebView), падаем в fallback.
        const sharePromise = navigator.share({ files: [file], title: name });
        const timeoutPromise = new Promise((_, rej) => setTimeout(() => rej(new Error('share timeout')), 30000));
        await Promise.race([sharePromise, timeoutPromise]);
        UI.toast('Сохранено: ' + name, 'ok');
        return true;
      } catch(e) {
        if (e && e.name === 'AbortError') { UI.toast('Отменено', null); return 'cancelled'; }
        return false;
      }
    }

    async function mFsAccess(blob, name) {
      if (!window.showSaveFilePicker) return false;
      try {
        const ext = (name.match(/\.[^.]+$/) || [''])[0].slice(1);
        const opts = { suggestedName: name };
        if (ext) {
          const mime = blob.type || 'application/octet-stream';
          opts.types = [{ description: ext.toUpperCase() + ' файл', accept: { [mime]: ['.' + ext] } }];
        }
        const handle = await window.showSaveFilePicker(opts);
        const writable = await handle.createWritable();
        await writable.write(blob);
        await writable.close();
        UI.toast('Сохранено: ' + name, 'ok');
        return true;
      } catch(e) {
        if (e && (e.name === 'AbortError' || /aborted/i.test(e.message||''))) { UI.toast('Отменено', null); return 'cancelled'; }
        return false;
      }
    }

    async function mAnchor(blob, name) {
      try {
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = name;
        a.rel = 'noopener';
        a.style.cssText = 'position:fixed;top:-9999px;left:-9999px;opacity:0;pointer-events:none;';
        document.body.appendChild(a);
        // Пробуем и стандартный .click(), и dispatchEvent — для разных WebView.
        try { a.click(); } catch(_) {}
        try {
          const ev = new MouseEvent('click', { bubbles: true, cancelable: true, view: window });
          a.dispatchEvent(ev);
        } catch(_) {}
        setTimeout(() => {
          try { a.remove(); } catch(_) {}
          try { URL.revokeObjectURL(url); } catch(_) {}
        }, 30000);
        UI.toast('Скачивание: ' + name, 'ok');
        return true;
      } catch(_) { return false; }
    }

    async function mDataUrl(blob, name, ctx) {
      // Data URL работает даже в старых Safari/WebView, где blob:-download блокируется.
      // Но у него ограничение по размеру (обычно ~2 МБ), поэтому только для небольших файлов.
      if (ctx.size > 2 * 1024 * 1024) return false;
      try {
        const dataUrl = await new Promise((resolve, reject) => {
          const fr = new FileReader();
          fr.onload = () => resolve(fr.result);
          fr.onerror = () => reject(fr.error);
          fr.readAsDataURL(blob);
        });
        const a = document.createElement('a');
        a.href = dataUrl;
        a.download = name;
        a.rel = 'noopener';
        a.style.cssText = 'position:fixed;top:-9999px;left:-9999px;opacity:0;';
        document.body.appendChild(a);
        try { a.click(); } catch(_) {}
        setTimeout(() => { try { a.remove(); } catch(_) {} }, 5000);
        UI.toast('Скачивание: ' + name, 'ok');
        return true;
      } catch(_) { return false; }
    }

    async function mNewTab(blob, name) {
      // Некоторые in-app WebView (Telegram, VK, Instagram) блокируют <a download>, но
      // разрешают window.open — там пользователь может сохранить через меню браузера.
      try {
        const url = URL.createObjectURL(blob);
        const win = window.open(url, '_blank', 'noopener,noreferrer');
        if (!win) { try { URL.revokeObjectURL(url); } catch(_){} return false; }
        setTimeout(() => { try { URL.revokeObjectURL(url); } catch(_){} }, 60000);
        UI.toast('Открыт в новой вкладке. Сохраните через меню браузера.', 'ok');
        return true;
      } catch(_) { return false; }
    }

    async function mModal(blob, name) {
      // Финальный fallback: показать модалку с blob-ссылкой и инструкциями.
      // Пользователь может нажать на ссылку из модалки (гарантированный user-gesture)
      // или скопировать содержимое через FileReader → clipboard (для текстовых).
      try {
        const url = URL.createObjectURL(blob);
        const body = U.el('div', { style:'display:flex;flex-direction:column;gap:12px;padding:8px 4px;' });
        body.appendChild(U.el('div', { style:'font-size:13px;color:var(--fg-dim);line-height:1.5;' },
          'Автоматическое скачивание заблокировано. Попробуйте один из вариантов:'));
        const linkA = U.el('a', {
          href: url,
          download: name,
          rel: 'noopener',
          target: '_blank',
          class: 'btn primary',
          style: 'text-decoration:none;display:block;text-align:center;padding:12px;',
        }, U.el('span', {}, '⬇ Скачать: ' + name));
        body.appendChild(linkA);
        const openA = U.el('a', {
          href: url,
          rel: 'noopener',
          target: '_blank',
          class: 'btn ghost',
          style: 'text-decoration:none;display:block;text-align:center;padding:12px;',
        }, U.el('span', {}, '↗ Открыть в новой вкладке'));
        body.appendChild(openA);
        // Кнопка "Скопировать base64" — для случаев, когда даже blob не открывается
        // (например, в очень ограниченных WebView).
        const copyBtn = U.el('button', { class:'btn ghost', style:'padding:12px;' }, 'Скопировать содержимое (base64)');
        copyBtn.addEventListener('click', async () => {
          try {
            const dataUrl = await new Promise((res, rej) => { const fr = new FileReader(); fr.onload = () => res(fr.result); fr.onerror = () => rej(fr.error); fr.readAsDataURL(blob); });
            await navigator.clipboard.writeText(dataUrl);
            UI.toast('Скопировано', 'ok');
          } catch(e) { UI.toast('Не удалось: ' + (e.message||e), 'err'); }
        });
        body.appendChild(copyBtn);
        const closeBtn = U.el('button', { class:'btn' }, 'Закрыть');
        closeBtn.addEventListener('click', () => { UI.closeSheet(); setTimeout(() => { try { URL.revokeObjectURL(url); } catch(_){} }, 60000); });
        UI.openSheet('Сохранить: ' + name, body, [closeBtn]);
        return true;
      } catch(_) { return false; }
    }
  }

  async function downloadFile(path) {
    try {
      const data = await FS.readFile(path);
      const mime = (U.mimeFromPath && U.mimeFromPath(path)) || 'application/octet-stream';
      const blob = new Blob([data], { type: mime });
      await saveBlob(blob, U.basename(path));
    } catch(e) {
      UI.toast('Ошибка чтения: ' + (e.message || e), 'err');
    }
  }

  // Подобрать свободное имя файла в проводнике: если base.zip занято — пробуем base (1).zip, base (2).zip, ...
  async function pickFreeZipName(dirPath, baseName) {
    const stem = baseName.replace(/\.zip$/i, '');
    for (let i = 0; i < 1000; i++) {
      const candidate = i === 0 ? `${stem}.zip` : `${stem} (${i}).zip`;
      const full = U.joinPath(dirPath, candidate);
      let ex = false;
      try { ex = await FS.exists(full); } catch(_){}
      if (!ex) return { name: candidate, path: full };
    }
    // Крайний случай — timestamp
    const ts = new Date().toISOString().replace(/[:.]/g,'-').slice(0,19);
    const candidate = `${stem}-${ts}.zip`;
    return { name: candidate, path: U.joinPath(dirPath, candidate) };
  }

  // Сжать один файл/папку в zip и СОХРАНИТЬ рядом в проводнике (в родительской папке).
  async function compressOne(path) {
    const prog = UI.progress('Архивация', U.basename(path));
    try {
      await prog.set(0, 'Сканирование…');
      let st = null;
      try { st = await FS.stat(path); } catch(_){}
      const isDir = statIsDir(st);
      const bag = {};
      let fileCount = 0, totalBytes = 0;
      // Собираем плоский список файлов для точного прогресса.
      const flat = [];
      if (!isDir) {
        flat.push({ path, rel: U.basename(path) });
      } else {
        const rows = await FS.listTree(path);
        const parent = U.dirname(path);
        const prefix = parent === '/' ? '/' : (parent + '/');
        for (const r of rows) {
          if (r.type !== 'file') continue;
          const rel = r.path.startsWith(prefix) ? r.path.slice(prefix.length) : r.path.slice(1);
          if (rel) flat.push({ path: r.path, rel });
        }
      }
      const total = flat.length || 1;
      let lastPct = -1;
      for (let i = 0; i < flat.length; i++) {
        // Пользователь нажал «Отмена» — прерываем без записи архива.
        if (prog.isCancelled()) { prog.close(); UI.toast('Архивация отменена', 'err'); return; }
        const { path: fp, rel } = flat[i];
        const data = await FS.readFile(fp);
        bag[rel] = data;
        fileCount++;
        totalBytes += data.length || data.byteLength || 0;
        const pct = Math.round((i + 1) / total * 90);
        if (pct !== lastPct) { lastPct = pct; await prog.set(pct, `Добавлено ${fileCount} файл(ов) · ${pct}%`); }
      }
      if (!fileCount) { prog.close(); UI.toast('Нет файлов для архивации', 'err'); return; }
      if (prog.isCancelled()) { prog.close(); UI.toast('Архивация отменена', 'err'); return; }
      await prog.set(92, 'Сжатие данных…');
      const zipped = fflate.zipSync(bag);
      // Куда сохраняем: рядом с исходным файлом/папкой (dirname).
      const targetDir = U.dirname(path) || '/';
      const baseName = (U.basename(path) || 'archive').replace(/\.[^.]+$/, '');
      const { name: zipName, path: zipPath } = await pickFreeZipName(targetDir, baseName);
      // Убеждаемся, что папка существует (обычно да, но на всякий случай).
      if (targetDir !== '/') { try { await FS.ensureDirRecursive(targetDir); } catch(_){} }
      await prog.set(97, 'Запись архива…');
      await FS.writeFile(zipPath, zipped);
      await prog.done(`Создан ${zipName} (${fileCount} файл(ов))`);
      // Раскрываем родителя чтобы новый архив стал виден и перерисовываем дерево.
      expanded.add(U.normPath(targetDir));
      await render();
      UI.toast(`Создан ${zipName} (${fileCount} файл(ов), ${U.bytesToStr(totalBytes)})`, 'ok');
    } catch(e) {
      prog.close();
      UI.toast('Ошибка архивации: ' + (e.message || e), 'err');
    }
  }

  async function createInside(parent, isDir) {
    if (!expanded.has(parent)) expanded.add(parent);
    const ar = (window.Store && Store.get && Store.get().activeRepo) || null;
    const goingToRemote = !!(ar && ar.mode === 'remote' && parent === ar.virtualRoot || (ar && ar.mode==='remote' && parent && parent.startsWith(ar.virtualRoot)));
    const hint = goingToRemote ? ' (будет сохранено в GitHub → ' + ar.fullName + ')' : '';
    const name = await UI.prompt(
      (isDir?'Имя папки':'Имя файла') + hint,
      { title: isDir?'Новая папка':'Новый файл', placeholder: isDir?'src':'index.js' }
    );
    if (!name) return;
    // Разрешаем "path/like/name" — тогда создаём вложенные каталоги автоматически.
    const full = U.joinPath(parent, name);
    try {
      if (isDir) {
        // Убедиться, что промежуточные каталоги существуют
        await FS.ensureDirRecursive(full);
      } else {
        // Создать родителя (в т.ч. вложенные, если введён путь), потом файл.
        const parentDir = U.dirname(full);
        if (parentDir && parentDir !== '/') { try { await FS.ensureDirRecursive(parentDir); } catch(_){} }
        if (goingToRemote) { try { UI.toast('Создаём в GitHub…'); } catch(_){} }
        await FS.writeFile(full, '');
      }
      if (goingToRemote) {
        UI.toast((isDir?'Папка':'Файл') + ' создан в ' + ar.fullName + ' на GitHub', 'ok');
      }
      await render();
    } catch(e){ UI.toast('Ошибка создания: ' + (e.message||e), 'err'); }
  }

  async function renameNode(path) {
    const cur = U.basename(path);
    const nn = await UI.prompt('Новое имя', { title:'Переименовать', value: cur });
    if (!nn || nn === cur) return;
    const target = U.joinPath(U.dirname(path), nn);
    try {
      await FS.rename(path, target);
      Editor.onPathRenamed(path, target);
      await render();
    } catch(e){ UI.toast(e.message,'err'); }
  }

  async function deleteNode(path, isDir) {
    const ok = await UI.confirm(`Удалить ${isDir?'папку':'файл'} "${U.basename(path)}"${isDir?' и всё содержимое':''}?`, { danger:true, okText:'Удалить' });
    if (!ok) return;
    try {
      if (isDir) await FS.rmrf(path);
      else await FS.unlink(path);
      Editor.onPathDeleted(path);
      await render();
    } catch(e){ UI.toast(e.message,'err'); }
  }

  async function extractArchive(path) {
    const prog = UI.progress('Распаковка архива', U.basename(path));
    try {
      await prog.set(0, 'Чтение архива…');
      const data = await FS.readFile(path);
      await prog.set(3, 'Разбор содержимого…');
      const files = fflate.unzipSync(data);
      const base = path.replace(/\.zip$/i,'') + '/';
      const entries = Object.keys(files);
      const total = entries.length || 1;
      let n = 0, i = 0, lastPct = -1;
      // Запоминаем записанные пути — при отмене откатываем частичную распаковку.
      const written = [];
      for (const rel of entries) {
        // Пользователь нажал «Отмена» — откатываем и выходим.
        if (prog.isCancelled()) {
          for (const w of written) { try { await FS.rmrf(w); } catch(_){} }
          prog.close();
          UI.toast('Распаковка отменена', 'err');
          await render();
          return;
        }
        const bytes = files[rel];
        // fflate returns Uint8Array for files; folders have length 0 and name ends with '/'
        const dst = U.joinPath(base, rel);
        if (rel.endsWith('/')) { await FS.ensureDirRecursive(dst); }
        else { await FS.writeFile(dst, bytes); written.push(dst); n++; }
        i++;
        // Прогресс с детальным отображением КАЖДОГО процента.
        const pct = Math.round(i / total * 100);
        if (pct !== lastPct) { lastPct = pct; await prog.set(pct, `Распаковано ${pct}% · ${n} файл(ов)`); }
      }
      await prog.done(`Распаковано файлов: ${n}`);
      UI.toast(`Распаковано файлов: ${n}`, 'ok');
      expanded.add(U.normPath(base));
      await render();
    } catch(e){ prog.close(); UI.toast('Ошибка распаковки: '+e.message,'err'); }
  }

  // ---- FS source switcher (browser / device folder / imported folder) ----
  // Приложение поддерживает три способа получить файлы устройства в проводник:
  //   1) IndexedDB — «Локальное хранилище браузера» (fsSource='browser'). Работает везде.
  //   2) FS Access API — «Папка на устройстве» (fsSource='local', LocalFS.mode='fsa').
  //      Настоящая двусторонняя синхронизация с диском. Только Chrome/Edge/Opera/Android Chrome.
  //   3) Импорт папки — «Снимок папки с устройства» (fsSource='local', LocalFS.mode='import').
  //      Работает во ВСЕХ браузерах (в т.ч. iOS Safari, Firefox, in-app WebView).
  //      Файлы копируются один раз в отдельную IDB, дальше — read/write внутри снимка.
  //      Можно держать несколько именованных «снимков» и переключаться между ними.

  // Отрисовать состояние кнопки-переключателя. Подсвечиваем и показываем имя папки,
  // когда активна «Папка на устройстве» (FSAccess).
  function renderSourceButton() {
    const btn = document.getElementById('exp-source');
    const lbl = document.getElementById('exp-source-label');
    if (!btn) return;
    const s = (window.Store && Store.get && Store.get()) || {};
    const mode = s.fsSource || 'browser';
    if (mode === 'local' && window.LocalFS && LocalFS.hasRoot()) {
      btn.classList.add('active');
      btn.title = 'Источник: папка «' + (LocalFS.getRootName() || '—') + '». Нажмите для смены.';
      if (lbl) { lbl.style.display = ''; lbl.textContent = LocalFS.getRootName() || '—'; }
    } else {
      btn.classList.remove('active');
      btn.title = 'Источник: локальное хранилище браузера. Нажмите, чтобы выбрать папку на устройстве.';
      if (lbl) { lbl.style.display = 'none'; lbl.textContent = ''; }
    }
  }

  // Универсальный конструктор пункта в sheet-меню источника.
  function mkSourceOption({ selected=false, icon='', title='', desc='', disabled=false, onClick }) {
    const opt = U.el('div', {
      class: 'drawer-item' + (selected ? ' selected' : '') + (disabled ? ' disabled' : ''),
      style: disabled ? 'opacity:.5;cursor:not-allowed' : 'align-items:flex-start;padding:12px;'
    },
      U.el('div', { style:'display:flex;gap:10px;align-items:flex-start;width:100%;' },
        U.el('div', { style:'flex:0 0 22px;font-size:18px;line-height:1;padding-top:1px;' }, icon || ''),
        U.el('div', { style:'display:flex;flex-direction:column;gap:2px;flex:1;min-width:0;' },
          U.el('span', { class:'label', style:'font-weight:600;' }, (selected ? '✓ ' : '') + title),
          U.el('span', { class:'hint', style:'font-size:11px;color:var(--fg-dim);line-height:1.35;' }, desc)
        )
      )
    );
    if (!disabled && onClick) opt.addEventListener('click', onClick);
    return opt;
  }

  // ---- Проверка окружения ----
  // Определяет, находимся ли мы внутри cross-origin iframe. Такой iframe НЕ имеет права
  // вызывать showDirectoryPicker (и window.open с user-gesture часто тоже) — браузер бросит
  // "Cross origin sub frames aren't allowed to show a file picker". Единственный выход —
  // открыть HTML в отдельной вкладке того же браузера.
  function isCrossOriginFrame() {
    try {
      if (window.top === window.self) return false;
      // top!==self и доступа к top.location.href нет → мы во кросс-origin iframe
      const _ = window.top.location.href;
      return false;
    } catch(_) {
      return true;
    }
  }

  // Показать модалку с советами, что делать, если FSAccess API недоступен или заблокирован.
  // reason: 'no-api' | 'cross-origin' | 'error'
  function showFsaUnavailableTip(reason, errMsg) {
    const body = U.el('div', { style:'display:flex;flex-direction:column;gap:10px;padding:4px 4px 4px;font-size:13px;line-height:1.5;' });

    if (reason === 'cross-origin') {
      body.appendChild(U.el('div', { style:'color:var(--fg);' },
        'Страница IDE открыта во встроенном фрейме (iframe/WebView). ' +
        'Из соображений безопасности браузер запрещает выбирать папки устройства из такого контекста.'));
      body.appendChild(U.el('div', { style:'color:var(--fg-dim);' }, 'Что помогает:'));
      const ul = U.el('ul', { style:'margin:0;padding-left:20px;color:var(--fg-dim);' });
      ul.appendChild(U.el('li', {}, 'Откройте mobile-ide.html как отдельную вкладку браузера (не через встроенный просмотрщик другого приложения).'));
      ul.appendChild(U.el('li', {}, 'Если IDE работает как файл — сохраните HTML на устройство и откройте его напрямую через файловый менеджер → «Открыть с помощью → Браузер».'));
      ul.appendChild(U.el('li', {}, 'Внутри Telegram / VK / Instagram / TikTok WebView — выберите «Открыть в браузере» в меню приложения.'));
      body.appendChild(ul);
    } else if (reason === 'no-api') {
      body.appendChild(U.el('div', { style:'color:var(--fg);' },
        'Ваш браузер не поддерживает File System Access API — единственный веб-стандарт, дающий двустороннюю синхронизацию с настоящей папкой устройства.'));
      body.appendChild(U.el('div', { style:'color:var(--fg-dim);' }, 'Совместимые браузеры (для этой функции):'));
      const ul = U.el('ul', { style:'margin:0;padding-left:20px;color:var(--fg-dim);' });
      ul.appendChild(U.el('li', {}, 'Настольный: Chrome, Edge, Opera, Brave, Yandex, Vivaldi (все на Chromium 86+).'));
      ul.appendChild(U.el('li', {}, 'Android: Chrome, Edge, Opera, Samsung Internet, Yandex, Kiwi, Quetta.'));
      ul.appendChild(U.el('li', {}, 'НЕ поддерживают: Safari (iOS/macOS), Firefox — это ограничение самих браузеров, обойти его из веб-приложения технически невозможно.'));
      body.appendChild(ul);
      body.appendChild(U.el('div', { style:'color:var(--fg-dim);font-size:12px;' },
        'Пока используйте «Локальное хранилище браузера» — файлы остаются в этом браузере и переживают перезагрузку.'));
    } else {
      body.appendChild(U.el('div', { style:'color:var(--fg);' },
        'Не удалось открыть системный выбор папки: ' + (errMsg || 'неизвестная ошибка')));
      body.appendChild(U.el('div', { style:'color:var(--fg-dim);' }, 'Возможные причины: страница открыта во встроенном фрейме, отсутствуют разрешения, или браузер обновил политику безопасности.'));
    }

    const ok = U.el('button', { class:'btn primary' }, 'Понятно');
    ok.addEventListener('click', () => UI.closeSheet());
    UI.openSheet('Папка на устройстве недоступна', body, [ok]);
  }

  // Основной sheet выбора источника.
  // Всего две опции по требованию пользователя:
  //   • «Локальное хранилище браузера» (IndexedDB, работает везде)
  //   • «Папка на устройстве» (File System Access API — двусторонняя синхронизация с реальной папкой)
  //
  // Про кросс-браузерность: сама папка ОС доступна только через FSAccess API. Этот API есть только
  // в Chromium-браузерах (Chrome/Edge/Opera/Brave/Yandex/Kiwi/Quetta и т.д.). В Safari (iOS/macOS)
  // и Firefox его физически нет — это ограничение движков этих браузеров, а не нашего кода:
  // ни одно веб-приложение в мире не умеет читать реальные файлы с устройства в Safari/Firefox.
  // Поэтому в таких браузерах пункт «Папка на устройстве» — отключён, с честной подсказкой.
  async function openSourcePicker() {
    const s = Store.get();
    const currentMode = s.fsSource || 'browser';
    const fsaSupported = !!(window.LocalFS && LocalFS.isFsaSupported());
    const inFrame = isCrossOriginFrame();
    const activeIsFsa = currentMode === 'local' && window.LocalFS && LocalFS.getMode() === 'fsa';

    const body = U.el('div');

    body.appendChild(mkSourceOption({
      selected: currentMode === 'browser',
      icon: '💾',
      title: 'Локальное хранилище браузера',
      desc:  'Виртуальная файловая система в IndexedDB. Файлы сохраняются в этом браузере и переживают перезагрузку. Работает во всех браузерах.',
      onClick: () => { UI.closeSheet(); pickSource('browser'); },
    }));

    // Формируем описание для «Папка на устройстве» в зависимости от окружения
    let fsaDesc, fsaDisabled = false, fsaClick;
    if (!fsaSupported) {
      fsaDesc    = 'Не поддерживается этим браузером. Работает в Chrome / Edge / Opera / Yandex / Samsung Internet / Kiwi / Quetta (десктоп и Android). Нажмите, чтобы увидеть подробности.';
      fsaDisabled = false; // не блокируем — по клику покажем подсказку
      fsaClick = () => { UI.closeSheet(); showFsaUnavailableTip('no-api'); };
    } else if (inFrame) {
      fsaDesc    = 'Не работает во встроенном фрейме этой страницы. Откройте IDE как отдельную вкладку браузера. Нажмите, чтобы увидеть подробности.';
      fsaClick = () => { UI.closeSheet(); showFsaUnavailableTip('cross-origin'); };
    } else {
      fsaDesc    = 'Выберите папку на устройстве — все файлы отобразятся в проводнике, и любые правки будут синхронно уходить на реальный диск.';
      fsaClick = () => { UI.closeSheet(); pickSource('fsa'); };
    }
    body.appendChild(mkSourceOption({
      selected: activeIsFsa,
      icon: '📂',
      title: 'Папка на устройстве',
      desc:  fsaDesc,
      disabled: fsaDisabled,
      onClick: fsaClick,
    }));

    UI.openSheet('Источник файлов', body);
  }

  // Переключить источник.
  //   'browser' — обычная IDB FS
  //   'fsa'     — File System Access (реальная папка), только браузеры с поддержкой API + не в iframe
  async function pickSource(mode) {
    if (mode === 'browser') {
      Store.set({ fsSource: 'browser', fsLocalName: '' });
      renderSourceButton();
      try { Editor.closeAll && Editor.closeAll(); } catch(_){}
      await render();
      UI.toast('Источник: локальное хранилище браузера', 'ok');
      return;
    }
    if (mode === 'fsa') {
      if (!window.LocalFS || !LocalFS.isFsaSupported()) {
        showFsaUnavailableTip('no-api');
        return;
      }
      if (isCrossOriginFrame()) {
        showFsaUnavailableTip('cross-origin');
        return;
      }
      try {
        const { name } = await LocalFS.pickRoot();
        Store.set({ fsSource: 'local', fsLocalName: name, fsLocalNs: '' });
        renderSourceButton();
        try { Editor.closeAll && Editor.closeAll(); } catch(_){}
        await render();
        UI.toast('Подключена папка: ' + name, 'ok');
      } catch(e) {
        if (e && (e.name === 'AbortError' || /aborted|abort/i.test(e.message||''))) return;
        // Специальный кейс: "Cross origin sub frames aren't allowed to show a file picker"
        // Он может возникнуть даже когда isCrossOriginFrame() вернул false, если сам picker
        // так решил (некоторые WebView соврут про window.top). Покажем подсказку по фрейму.
        if (/cross origin/i.test(e.message||'') || /sub frame/i.test(e.message||'')) {
          showFsaUnavailableTip('cross-origin');
          return;
        }
        showFsaUnavailableTip('error', e.message || String(e));
      }
      return;
    }
  }

  // Каталог по умолчанию для новых файлов / импорта / экспорта в проводнике.
  // Если сейчас выбран удалённый GitHub-репозиторий (activeRepo.mode==='remote'),
  // всё, что пользователь создаёт «в проводнике», должно попадать внутрь его
  // виртуального корня /gh/<owner>/<repo>, а НЕ в глобальный корень /.
  // Раньше кнопки «+ Новый файл», «Импорт файлов», «Экспорт проекта в zip»
  // всегда работали с '/', из-за чего файлы, созданные при активном GitHub-репо,
  // сохранялись в локальный IndexedDB, а на GitHub не появлялись — пользователь
  // видел это как «синхронизация сломалась».
  function defaultTargetDir() {
    try {
      const ar = (window.Store && Store.get && Store.get()) || {};
      const active = ar.activeRepo;
      if (active && active.mode === 'remote' && active.virtualRoot) return active.virtualRoot;
      if (active && active.mode === 'local'  && active.localDir)    return active.localDir;
    } catch(_){}
    return '/';
  }

  // Короткий человекочитаемый label каталога-цели для тостов / подсказок.
  function targetDirLabel(dir) {
    if (!dir || dir === '/') return 'корень проводника';
    const ar = (window.Store && Store.get && Store.get().activeRepo) || null;
    if (ar && ar.virtualRoot === dir) return 'репозиторий ' + (ar.fullName || dir) + ' (GitHub)';
    if (ar && ar.localDir === dir)    return 'локальную копию ' + (ar.fullName || dir);
    return dir;
  }

  function bind() {
    // Кнопка переключения источника ФС.
    const btnSource = document.getElementById('exp-source');
    if (btnSource) btnSource.addEventListener('click', openSourcePicker);

    // При старте: если раньше был выбран режим local — тихо восстанавливаем fsa-handle
    // (для реальной папки устройства). В браузерах без FSAccess API восстанавливать нечего —
    // сбросим на browser-storage.
    (async () => {
      try {
        const s = Store.get();
        if (s.fsSource === 'local' && window.LocalFS && LocalFS.isFsaSupported()) {
          const res = await LocalFS.restoreRoot({ silent: true });
          if (res && res.needsPermission) {
            UI.toast && UI.toast('Нужно повторно разрешить доступ к папке «' + res.name + '»', 'err');
          } else if (!res) {
            Store.set({ fsSource:'browser', fsLocalName:'', fsLocalNs:'' });
          }
          renderSourceButton();
          await render();
        } else if (s.fsSource === 'local') {
          // Раньше был local, но браузер сейчас не поддерживает — откатываемся
          Store.set({ fsSource:'browser', fsLocalName:'', fsLocalNs:'' });
          renderSourceButton();
        } else {
          renderSourceButton();
        }
      } catch(_) { renderSourceButton(); }
    })();

    // Все кнопки-«создания» теперь работают в defaultTargetDir():
    //   • если выбран GitHub-репо → внутри /gh/<owner>/<repo> → файл сразу пушится в GitHub;
    //   • иначе → в корне '/'.
    U.$('#exp-new-file').addEventListener('click', () => {
      const d = defaultTargetDir();
      if (d !== '/') expanded.add(d);
      createInside(d, false);
    });
    U.$('#exp-new-folder').addEventListener('click', () => {
      const d = defaultTargetDir();
      if (d !== '/') expanded.add(d);
      createInside(d, true);
    });
    U.$('#exp-refresh').addEventListener('click', async () => {
      // При наличии GitHub-репо — тянем свежее дерево из GitHub, иначе просто перерисуем.
      try { if (window.GH && GH.activeRoot && GH.activeRoot()) await GH.refresh(); } catch(_){}
      render();
    });
    U.$('#exp-more').addEventListener('click', () => {
      const body = U.el('div');
      const mk = (l, fn, kind='') => { const d = U.el('div', { class:'drawer-item' }, U.el('span', { class:'label' }, l)); if(kind==='danger') d.style.color='var(--danger)'; d.addEventListener('click', ()=>{ UI.closeSheet(); fn(); }); return d; };
      const tgt = defaultTargetDir();
      const hint = tgt === '/' ? '' : ' → ' + targetDirLabel(tgt);
      body.appendChild(mk('Импорт файлов…' + hint, ()=>App.importFiles(tgt)));
      body.appendChild(mk('Экспорт проекта в zip' + hint, ()=>App.exportZip(tgt)));
      body.appendChild(mk(selectMode ? 'Выйти из режима выделения' : 'Выделить несколько…', ()=>{
        if (selectMode) exitSelectMode(); else enterSelectMode();
      }));
      body.appendChild(mk('Развернуть все', ()=>expandAll().then(render)));
      body.appendChild(mk('Свернуть все', ()=>{ expanded.clear(); expanded.add('/'); render(); }));
      body.appendChild(mk('Очистить проводник', async ()=>{ if(await UI.confirm('Удалить все файлы?', {danger:true, okText:'Стереть'})) { await FS.rmrf('/'); Editor.closeAll(); render(); } }, 'danger'));
      UI.openSheet('Действия', body);
    });
  }

  async function expandAll() {
    const rows = await FS.listTree('/');
    for (const r of rows) if (r.type === 'dir') expanded.add(r.path);
  }

  return { render, bind, expand: (p) => expanded.add(U.normPath(p)), saveBlob, renderSourceButton, openSourcePicker };
})();
window.Explorer = Explorer;
