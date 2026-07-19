/* ===== Editor: нативный <textarea> для текста + media viewer =====
 * Текстовые файлы редактируются в обычном <textarea id="text-editor">, а не в Monaco.
 * Причина: только настоящий textarea даёт нативное выделение ОС — синюю подсветку,
 * ручки-«капли» для ручного изменения границ выделения и системную плавающую панель
 * (лупа / выделить всё / вырезать / копировать / вставить) при двойном тапе или
 * удерживающем нажатии на слово. Monaco рисует текст в своих слоях и такое поведение
 * на мобильном не поддерживает.
 */
const Editor = (() => {
  // path -> запись о вкладке.
  //   Текст:  { kind:'text', value, dirty, scrollTop, selStart, selEnd }
  //   Медиа:  { kind:'media', mediaKind, blobUrl, size, mime, dirty:false }
  const models = new Map();
  let activePath = null;

  function ta() { return U.$('#text-editor'); }
  function codeWrap() { return U.$('#editor-code'); }
  function hlPre() { return U.$('#editor-highlight'); }
  function hlCode() { const p = hlPre(); return p ? p.querySelector('code') : null; }
  function gutter() { return U.$('#editor-gutter'); }

  // Совместимость: ряд мест в коде вызывает Editor.ensureReady() перед открытием.
  // Нативному textarea готовиться не нужно — резолвимся сразу.
  function ensureReady() { return Promise.resolve(); }

  // Перерисовать слой подсветки под активной текстовой вкладкой.
  function renderHighlight() {
    const code = hlCode();
    if (!code) return;
    if (!activePath) { code.innerHTML = ''; return; }
    const rec = models.get(activePath);
    if (!rec || rec.kind !== 'text') { code.innerHTML = ''; return; }
    const el = ta();
    const text = el ? el.value : rec.value;
    let html = '';
    try { html = window.Highlight ? Highlight.byPath(text, activePath) : U.escapeHtml(text); }
    catch (e) { html = U.escapeHtml(text); }
    // Хвостовой перевод строки нужен, чтобы высота слоя совпадала с textarea.
    code.innerHTML = html + '\n';
    renderGutter(text);
  }
  // Построить номера строк в жёлобе по количеству строк текста.
  function renderGutter(text) {
    const g = gutter();
    if (!g) return;
    if (text == null) { const el = ta(); text = el ? el.value : ''; }
    const lines = text.split('\n').length;
    let s = '';
    for (let i = 1; i <= lines; i++) s += i + '\n';
    g.textContent = s;
    // Ширину жёлоба подгоняем под число разрядов.
    g.style.minWidth = (String(lines).length * 8 + 18) + 'px';
  }
  // Синхронизировать скролл слоя подсветки и жёлоба со скроллом textarea.
  function syncScroll() {
    const el = ta(), pre = hlPre(), g = gutter();
    if (el && pre) { pre.scrollTop = el.scrollTop; pre.scrollLeft = el.scrollLeft; }
    if (el && g) { g.scrollTop = el.scrollTop; }
  }

  function renderTabs() {
    const cont = U.$('#editor-tabs');
    cont.innerHTML = '';
    for (const [path, m] of models) {
      const t = U.el('div', { class:'etab'+(path===activePath?' active':'')+(m.dirty?' dirty':''), 'data-path':path },
        U.el('span', { class:'dirty-dot' }),
        U.el('span', { class:'label' }, U.basename(path)),
        U.el('span', { class:'close' }, '×')
      );
      t.addEventListener('click', e => {
        if (e.target.classList.contains('close')) { closeFile(path); return; }
        setActive(path);
      });
      cont.appendChild(t);
    }
    // Видимость областей: пустое состояние / текст / медиа.
    const mono = U.$('#monaco-container'), media = U.$('#media-container');
    const empty = U.$('#editor-empty'), tabsEl = U.$('#editor-tabs');
    const codeEl = codeWrap();
    if (mono) mono.style.display = 'none'; // Monaco больше не используется.
    if (models.size === 0) {
      if (codeEl) codeEl.style.display = 'none';
      media.style.display = 'none';
      empty.style.display = 'flex';
      tabsEl.style.display = 'none';
    } else {
      empty.style.display = 'none';
      tabsEl.style.display = 'flex';
      const rec = activePath ? models.get(activePath) : null;
      if (rec && rec.kind === 'media') {
        if (codeEl) codeEl.style.display = 'none';
        media.style.display = 'flex';
      } else {
        if (codeEl) codeEl.style.display = 'flex';
        media.style.display = 'none';
      }
    }
  }

  async function openFile(path) {
    path = U.normPath(path);
    if (models.has(path)) { setActive(path); return; }
    let data;
    try { data = await FS.readFile(path); }
    catch(e){ UI.toast('Не удалось открыть: '+e.message, 'err'); return; }

    const mk = U.mediaKind(path);
    if (mk) {
      const mime = U.mimeFromPath(path);
      const blob = new Blob([data], { type: mime });
      const blobUrl = URL.createObjectURL(blob);
      const rec = { kind:'media', mediaKind: mk, blobUrl, size: data.byteLength, mime, dirty:false };
      models.set(path, rec);
      setActive(path);
      persistOpen();
      return;
    }

    let text;
    if (U.looksBinary(data) && !U.isTextExt(U.extname(path))) {
      text = `// Бинарный файл: ${path}\n// Размер: ${U.bytesToStr(data.byteLength)}\n// Открыт как hex (первые 512 байт)\n\n` +
        Array.from(data.slice(0,512)).map(b=>b.toString(16).padStart(2,'0')).join(' ');
    } else {
      try { text = U.bytesToText(data); } catch(e){ text = ''; }
    }
    const rec = { kind:'text', value:text, dirty:false, scrollTop:0, selStart:0, selEnd:0 };
    models.set(path, rec);
    setActive(path);
    persistOpen();
  }

  // Открыть файл принудительно как текст (для просмотра исходника SVG и т.п.).
  async function openAsText(path) {
    path = U.normPath(path);
    if (models.has(path)) { setActive(path); return; }
    let data;
    try { data = await FS.readFile(path); } catch(e){ UI.toast(e.message,'err'); return; }
    let text = '';
    try { text = U.bytesToText(data); } catch(e){}
    const rec = { kind:'text', value:text, dirty:false, scrollTop:0, selStart:0, selEnd:0 };
    models.set(path, rec);
    setActive(path);
    persistOpen();
  }

  // Сохранить состояние textarea (значение, скролл, выделение) в запись активной вкладки.
  function syncFromTextarea() {
    if (!activePath) return;
    const rec = models.get(activePath);
    if (!rec || rec.kind !== 'text') return;
    const el = ta();
    if (!el) return;
    rec.value = el.value;
    rec.scrollTop = el.scrollTop;
    rec.selStart = el.selectionStart;
    rec.selEnd = el.selectionEnd;
  }

  function setActive(path) {
    // Сохраняем состояние предыдущей текстовой вкладки.
    if (activePath && activePath !== path) syncFromTextarea();
    activePath = path;
    const rec = models.get(path);
    const el = ta();
    if (rec && rec.kind === 'text' && el) {
      // Перенос строк применяем ДО установки значения: атрибут textarea.wrap,
      // выставленный только при инициализации, не переносит текст, загруженный
      // позже (открытие файла/восстановление вкладок) — из-за этого перенос
      // «отваливался» и лечился лишь повторным выкл→вкл. Некоторые движки к тому
      // же не переносят уже присвоенное значение при смене wrap, поэтому порядок
      // важен: сначала режим переноса, затем текст.
      applyWordWrap();
      el.value = rec.value;
      el.readOnly = false;
      renderHighlight();
      // Восстанавливаем скролл и выделение.
      requestAnimationFrame(() => {
        try { el.scrollTop = rec.scrollTop || 0; } catch(_){}
        try { el.setSelectionRange(rec.selStart || 0, rec.selEnd || 0); } catch(_){}
        syncScroll();
      });
    }
    renderMedia();
    renderTabs();
    Store.set({ activeTab: path });
  }

  function renderMedia() {
    const c = U.$('#media-container');
    if (!c) return;
    c.innerHTML = '';
    if (!activePath) return;
    const rec = models.get(activePath);
    if (!rec || rec.kind !== 'media') return;
    const info = U.el('div', { class:'media-info' }, `${activePath} · ${U.bytesToStr(rec.size)} · ${rec.mime}`);
    c.appendChild(info);
    let mediaEl;
    if (rec.mediaKind === 'image') {
      mediaEl = U.el('img', { src: rec.blobUrl, alt: U.basename(activePath) });
      c.classList.add('checker');
    } else if (rec.mediaKind === 'svg') {
      c.classList.add('checker');
      const wrap = U.el('div', { class:'svg-wrap' });
      FS.readFileText(activePath).then(text => {
        try {
          const clean = DOMPurify.sanitize(text, { USE_PROFILES:{ svg:true, svgFilters:true } });
          wrap.innerHTML = clean;
        } catch(e){
          const img = U.el('img', { src: rec.blobUrl });
          wrap.appendChild(img);
        }
      });
      mediaEl = wrap;
    } else if (rec.mediaKind === 'video') {
      mediaEl = U.el('video', { src: rec.blobUrl, controls:'', playsinline:'', preload:'metadata' });
      c.classList.remove('checker');
    } else if (rec.mediaKind === 'audio') {
      mediaEl = U.el('audio', { src: rec.blobUrl, controls:'', preload:'metadata' });
      c.classList.remove('checker');
    } else if (rec.mediaKind === 'pdf') {
      mediaEl = U.el('object', { data: rec.blobUrl, type: rec.mime, style:'width:100%;height:70vh;background:#fff' });
      c.classList.remove('checker');
    }
    if (mediaEl) c.appendChild(mediaEl);
    const actions = U.el('div', { class:'media-actions' });
    const dl = U.el('button', { class:'btn small' }, 'Скачать');
    dl.addEventListener('click', () => {
      const a = document.createElement('a');
      a.href = rec.blobUrl; a.download = U.basename(activePath);
      document.body.appendChild(a); a.click(); a.remove();
    });
    const openTab = U.el('button', { class:'btn small' }, 'Открыть в новой вкладке');
    openTab.addEventListener('click', () => window.open(rec.blobUrl, '_blank', 'noopener'));
    actions.appendChild(dl); actions.appendChild(openTab);
    if (rec.mediaKind === 'svg') {
      const asText = U.el('button', { class:'btn small' }, 'Открыть как текст');
      asText.addEventListener('click', async () => {
        await closeFile(activePath);
        await openAsText(activePath);
      });
      actions.appendChild(asText);
    }
    c.appendChild(actions);
  }

  async function closeFile(path) {
    const rec = models.get(path);
    if (!rec) return;
    if (path === activePath) syncFromTextarea();
    if (rec.dirty && rec.kind === 'text') {
      const r = await UI.dialog({
        title:'Несохранённые изменения',
        body:`Сохранить изменения в "${U.basename(path)}"?`,
        buttons:[{text:'Отмена',value:'cancel'},{text:'Не сохранять',value:'discard'},{text:'Сохранить',primary:true,value:'save'}]
      });
      if (r === 'cancel') return;
      if (r === 'save') await save(path);
    }
    disposeRec(rec);
    models.delete(path);
    if (activePath === path) {
      const nextKey = models.keys().next().value || null;
      activePath = null;
      if (nextKey) setActive(nextKey); else renderTabs();
    } else renderTabs();
    persistOpen();
  }

  function disposeRec(rec) {
    if (!rec) return;
    if (rec.kind === 'media' && rec.blobUrl) { try { URL.revokeObjectURL(rec.blobUrl); } catch(e){} }
  }

  async function save(path=activePath) {
    if (!path) return;
    if (path === activePath) syncFromTextarea();
    const rec = models.get(path);
    if (!rec || rec.kind !== 'text') return;
    await FS.writeFile(path, rec.value);
    rec.dirty = false; renderTabs();
    UI.toast('Сохранено','ok');
  }
  async function saveAll() {
    if (activePath) syncFromTextarea();
    for (const [p, r] of models) if (r.kind === 'text' && r.dirty) { await FS.writeFile(p, r.value); r.dirty=false; }
    renderTabs();
  }

  function closeAll() {
    for (const [, r] of models) disposeRec(r);
    models.clear(); activePath = null; renderTabs(); persistOpen();
  }

  function onPathDeleted(path) {
    for (const p of Array.from(models.keys())) {
      if (p === path || p.startsWith(path + '/')) {
        disposeRec(models.get(p));
        models.delete(p);
        if (activePath === p) activePath = null;
      }
    }
    if (!activePath && models.size) activePath = models.keys().next().value;
    renderTabs(); renderMedia();
    persistOpen();
  }
  function onPathRenamed(oldP, newP) {
    for (const p of Array.from(models.keys())) {
      if (p === oldP || p.startsWith(oldP + '/')) {
        const newPath = newP + p.slice(oldP.length);
        const rec = models.get(p);
        models.delete(p);
        models.set(newPath, rec);
        if (activePath === p) activePath = newPath;
      }
    }
    renderTabs(); renderMedia();
    persistOpen();
  }

  function persistOpen() {
    Store.set({ openTabs: Array.from(models.keys()).map(p => ({ path:p })), activeTab: activePath });
  }
  async function restore() {
    const s = Store.get();
    if (!s.openTabs || !s.openTabs.length) return;
    for (const t of s.openTabs) {
      try { if (await FS.exists(t.path)) await openFile(t.path); } catch(e){}
    }
    if (s.activeTab && models.has(s.activeTab)) setActive(s.activeTab);
  }

  function activeTextRec() {
    if (!activePath || !models.has(activePath)) return null;
    const rec = models.get(activePath);
    return rec && rec.kind === 'text' ? rec : null;
  }

  // ===== Настройка «перенос текста» (word wrap) =====
  // Хранится в Store.editorWordWrap; применяется ко всем текстовым файлам.
  function applyWordWrap() {
    const wrap = !!Store.get().editorWordWrap;
    const code = codeWrap();
    if (code) code.classList.toggle('wrap', wrap);
    const el = ta();
    if (el) el.wrap = wrap ? 'soft' : 'off';
    syncScroll();
  }

  // Меню настроек редактора (пока одна настройка — «перенос текста» с ползунком).
  function showEditorSettings() {
    const cur = !!Store.get().editorWordWrap;
    const body = U.el('div');
    const row = U.el('div', { class:'setting-row' });
    row.appendChild(U.el('span', {}, 'Перенос текста'));
    const sw = U.el('label', { class:'toggle-switch' });
    const cb = U.el('input', { type:'checkbox' });
    cb.checked = cur;
    sw.appendChild(cb);
    sw.appendChild(U.el('span', { class:'slider' }));
    row.appendChild(sw);
    body.appendChild(row);
    // Живой предпросмотр: переключатель сразу применяет и сохраняет режим.
    cb.addEventListener('change', () => {
      Store.set({ editorWordWrap: cb.checked });
      applyWordWrap();
    });
    UI.dialog({ title:'Настройки редактора', body, buttons:[{ text:'Готово', primary:true, value:true }] });
  }

  function bind() {
    U.$('#empty-open-explorer').addEventListener('click', () => App.switchTab('explorer'));

    const settingsBtn = U.$('#editor-settings-btn');
    if (settingsBtn) settingsBtn.addEventListener('click', showEditorSettings);
    const saveBtn = U.$('#editor-save-btn');
    if (saveBtn) saveBtn.addEventListener('click', () => save());

    applyWordWrap(); // применить сохранённый режим при инициализации

    const el = ta();
    if (el) {
      // Правки в textarea помечают файл как «грязный» и сохраняются в запись вкладки.
      el.addEventListener('input', () => {
        const rec = activeTextRec();
        if (!rec) return;
        rec.value = el.value;
        renderHighlight();
        syncScroll();
        if (!rec.dirty) { rec.dirty = true; renderTabs(); }
      });
      // Поддержка Tab внутри textarea (вставка отступа вместо смены фокуса).
      el.addEventListener('keydown', (e) => {
        if (e.key === 'Tab') {
          e.preventDefault();
          const s = el.selectionStart, en = el.selectionEnd;
          el.value = el.value.slice(0, s) + '  ' + el.value.slice(en);
          el.selectionStart = el.selectionEnd = s + 2;
          el.dispatchEvent(new Event('input'));
          renderHighlight();
        }
      });
      // Ctrl/Cmd+S — сохранить активный файл.
      el.addEventListener('keydown', (e) => {
        if ((e.ctrlKey || e.metaKey) && (e.key === 's' || e.key === 'S')) {
          e.preventDefault();
          save();
        }
      });
      // Сохраняем позицию скролла/выделения при взаимодействии, чтобы не терять её.
      el.addEventListener('scroll', () => { const r = activeTextRec(); if (r) r.scrollTop = el.scrollTop; syncScroll(); });
      el.addEventListener('select', () => { const r = activeTextRec(); if (r) { r.selStart = el.selectionStart; r.selEnd = el.selectionEnd; } });
      el.addEventListener('blur', () => syncFromTextarea());
    }
  }

  function getActive() {
    if (!activePath || !models.has(activePath)) return null;
    if (activePath) syncFromTextarea();
    const rec = models.get(activePath);
    if (rec.kind === 'text') return { path: activePath, kind:'text', content: rec.value };
    return { path: activePath, kind:'media', mediaKind: rec.mediaKind, mime: rec.mime, size: rec.size };
  }

  // Файл изменён извне (например, ИИ записал на диск) — перечитываем.
  async function onExternalWrite(path) {
    if (!models.has(path)) return;
    const rec = models.get(path);
    if (rec.kind === 'text') {
      const data = await FS.readFile(path);
      let text = '';
      try { text = U.bytesToText(data); } catch(e){}
      if (rec.value !== text) {
        rec.value = text;
        rec.dirty = false;
        if (activePath === path) { const el = ta(); if (el) el.value = text; renderHighlight(); }
        renderTabs();
      }
    } else if (rec.kind === 'media') {
      const data = await FS.readFile(path);
      try { URL.revokeObjectURL(rec.blobUrl); } catch(e){}
      rec.blobUrl = URL.createObjectURL(new Blob([data], { type: rec.mime }));
      rec.size = data.byteLength;
      if (activePath === path) renderMedia();
    }
  }

  return { openFile, openAsText, closeFile, save, saveAll, closeAll, onPathDeleted, onPathRenamed, bind, restore, getActive, onExternalWrite, ensureReady };
})();
window.Editor = Editor;
