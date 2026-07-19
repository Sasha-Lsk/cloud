/* ===== Chat list menu + attachment picker for the AI tab ===== */
const ChatMenu = (() => {
  function refresh() {
    // Re-render the currently open sheet if it's showing the chat list
    if (document.body.classList.contains('sheet-open') && U.$('#sheet-title').textContent === 'Чаты') {
      openMenu();
    }
  }

  function fmtTime(t) {
    const d = new Date(t);
    const now = new Date();
    const sameDay = d.toDateString() === now.toDateString();
    if (sameDay) return d.toTimeString().slice(0,5);
    return d.toLocaleDateString();
  }

  function openMenu() {
    const s = Store.get();
    const active = Store.activeChat();
    const body = U.el('div', { class:'chat-menu' });

    const newBtn = U.el('button', { class:'btn primary chat-new-btn' },
      U.el('span', {}, '+ Новый чат')
    );
    newBtn.addEventListener('click', () => {
      Store.newChat();
      // Reset AI attachments buffer
      Attachments.clear();
      // onChatSwitched перерисует новый (пустой) чат И синхронизирует кнопку
      // отправки — если агент ещё работает в предыдущем чате, здесь будет ▶,
      // а не ⏹, и сообщения того чата сюда не попадут.
      AI.onChatSwitched();
      UI.closeSheet();
      UI.toast('Создан новый чат');
    });
    body.appendChild(newBtn);

    const list = U.el('div', { class:'chat-list' });
    // Sort by updatedAt desc
    const chats = s.chats.slice().sort((a,b) => (b.updatedAt||0) - (a.updatedAt||0));
    for (const c of chats) {
      const isActive = c.id === s.activeChatId;
      const item = U.el('div', { class:'chat-item'+(isActive?' active':'') });
      const info = U.el('div', { class:'chat-info' },
        U.el('div', { class:'chat-title' }, c.title || 'Без названия'),
        U.el('div', { class:'chat-sub' },
          (c.history||[]).length + ' сообщ · ' + fmtTime(c.updatedAt||c.createdAt||Date.now())
        )
      );
      info.addEventListener('click', () => {
        if (!isActive) {
          Store.switchChat(c.id);
          Attachments.clear();
          AI.onChatSwitched();
        }
        UI.closeSheet();
      });
      const del = U.el('button', { class:'icon-btn chat-del', 'aria-label':'Удалить чат', title:'Удалить чат' });
      del.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="3 6 5 6 21 6"/><path d="M19 6l-2 14a2 2 0 0 1-2 2H9a2 2 0 0 1-2-2L5 6"/><path d="M10 11v6M14 11v6"/><path d="M9 6V4a2 2 0 0 1 2-2h2a2 2 0 0 1 2 2v2"/></svg>';
      del.addEventListener('click', async (e) => {
        e.stopPropagation();
        Store.deleteChat(c.id);
        AI.onChatSwitched();
        openMenu(); // refresh
      });
      item.appendChild(info);
      item.appendChild(del);
      list.appendChild(item);
    }
    body.appendChild(list);

    UI.openSheet('Чаты', body);
  }

  function bind() {
    const btn = U.$('#btn-topbar-chats');
    if (btn) btn.addEventListener('click', openMenu);
  }

  return { bind, openMenu, refresh };
})();
window.ChatMenu = ChatMenu;

/* ===== Attachments manager for chat input ===== */
const Attachments = (() => {
  // Pending attachments for the NEXT outgoing user message
  let pending = [];

  function clear() {
    pending = [];
    render();
  }
  function get() { return pending.slice(); }
  function take() {
    const items = pending.slice();
    pending = [];
    render();
    return items;
  }

  function add(att) {
    pending.push(att);
    render();
  }

  function render() {
    const cont = U.$('#ai-attachments-preview');
    if (!cont) return;
    cont.innerHTML = '';
    if (window.AI && AI._updateSendButton) AI._updateSendButton();
    if (!pending.length) { cont.classList.remove('show'); cont.style.display = ''; return; }
    cont.classList.add('show');
    cont.style.display = '';
    for (let i = 0; i < pending.length; i++) {
      const a = pending[i];
      const chip = U.el('div', {
        class:'att-preview-chip'+(a.kind==='image'?' img':''),
        title: (a.path || a.name || '') + (a.source==='fs' ? ' (из проводника)' : '')
      });
      if (a.kind === 'image' && a.dataUrl) {
        chip.appendChild(U.el('img', { src: a.dataUrl, alt: a.name || '' }));
      } else {
        // Simple emoji icon by extension
        const ext = (a.name||'').split('.').pop().toLowerCase();
        let ico = '📄';
        if (['png','jpg','jpeg','gif','svg','webp','bmp','ico'].includes(ext)) ico = '🖼';
        else if (['js','ts','jsx','tsx','mjs','cjs'].includes(ext)) ico = '📜';
        else if (['py'].includes(ext)) ico = '🐍';
        else if (['json','yaml','yml','toml'].includes(ext)) ico = '⚙️';
        else if (['md','txt','rst'].includes(ext)) ico = '📝';
        else if (['html','htm','xml'].includes(ext)) ico = '🌐';
        else if (['zip','tar','gz','7z','rar'].includes(ext)) ico = '📦';
        chip.appendChild(U.el('span', { class:'att-icon' }, ico));
      }
      const displayName = a.name || (a.path ? a.path.split('/').pop() : '') || 'file';
      chip.appendChild(U.el('span', { class:'att-name', title: displayName }, displayName));
      const rm = U.el('button', { class:'att-remove', 'aria-label':'Убрать' }, '×');
      rm.addEventListener('click', () => { pending.splice(i,1); render(); });
      chip.appendChild(rm);
      cont.appendChild(chip);
    }
  }

  // ---- Нормализация изображения для vision ----
  // ВАЖНО (паритет с андроидом): картинку, которую пользователь прикрепил в чат,
  // нельзя отправлять «как есть». Причины: 1) форматы webp/bmp/ico/svg/tiff многие
  // vision-модели НЕ принимают; 2) большое фото с телефона (10-50 МБ base64) не
  // проходит через публичные CORS-прокси, которыми пользуется веб-версия (в андроиде
  // запрос идёт напрямую — поэтому там картинку и «видно»). Поэтому декодируем через
  // <canvas>, при необходимости уменьшаем до maxSide и перекодируем в JPEG/PNG.
  // Возвращает { dataUrl, mime } либо null (если декодировать не удалось — берём исходник).
  async function normalizeImageDataUrl(srcDataUrl, srcMime, maxSide) {
    maxSide = maxSide || 1600;
    try {
      const img = new Image();
      img.decoding = 'async';
      img.src = srcDataUrl;
      await new Promise((resolve, reject) => {
        img.onload = resolve;
        img.onerror = () => reject(new Error('decode failed'));
      });
      let w = img.naturalWidth || img.width || 0;
      let h = img.naturalHeight || img.height || 0;
      if (!w || !h) return null;
      // Не принимаемые/проблемные форматы конвертируем всегда; растровые — только если крупные.
      const NEEDS_CONVERT = /^image\/(x-icon|vnd\.microsoft\.icon|bmp|svg\+xml|tiff)$/i.test(srcMime || '');
      const scaleDown = Math.min(1, maxSide / Math.max(w, h));
      // Мелкую векторную/иконочную графику апскейлим для читаемости моделью.
      let minTarget = 0;
      if (/svg/i.test(srcMime || '')) minTarget = 512;
      else if (NEEDS_CONVERT) minTarget = 128;
      const scaleUp = minTarget ? Math.max(1, minTarget / Math.max(w, h)) : 1;
      const scale = scaleUp > 1 ? scaleUp : scaleDown;
      if (scale === 1 && !NEEDS_CONVERT) return null; // исходник уже пригоден
      const outW = Math.max(1, Math.round(w * scale));
      const outH = Math.max(1, Math.round(h * scale));
      const c = document.createElement('canvas');
      c.width = outW; c.height = outH;
      const ctx = c.getContext('2d');
      ctx.imageSmoothingEnabled = true;
      ctx.imageSmoothingQuality = 'high';
      ctx.drawImage(img, 0, 0, outW, outH);
      // PNG сохраняет прозрачность (нужно для png/svg/ico/gif); фото → JPEG (компактнее).
      const keepAlpha = /^image\/(png|svg\+xml|x-icon|vnd\.microsoft\.icon|gif|webp|apng|avif)$/i.test(srcMime || '');
      const outMime = keepAlpha ? 'image/png' : 'image/jpeg';
      const outBlob = await new Promise(res => c.toBlob(res, outMime, outMime === 'image/jpeg' ? 0.85 : undefined));
      if (!outBlob) return null;
      const buf = new Uint8Array(await outBlob.arrayBuffer());
      return { dataUrl: 'data:' + outMime + ';base64,' + U.bytesToB64(buf), mime: outMime };
    } catch (e) {
      return null; // не удалось — вызывающий использует исходный dataUrl
    }
  }

  // ---- Attachment loaders ----
  async function loadDeviceFile(file) {
    const name = file.name;
    const size = file.size;
    const mime = file.type || U.mimeFromPath(name);
    const kind = U.mediaKind ? U.mediaKind(name) : (mime.startsWith('image/') ? 'image' : 'other');
    if (kind === 'image' || mime.startsWith('image/')) {
      let dataUrl = await new Promise((res, rej) => {
        const r = new FileReader();
        r.onload = () => res(r.result);
        r.onerror = () => rej(r.error);
        r.readAsDataURL(file);
      });
      let outMime = mime;
      const norm = await normalizeImageDataUrl(dataUrl, mime, 1600);
      if (norm) { dataUrl = norm.dataUrl; outMime = norm.mime; }
      return { kind:'image', name, size, mime: outMime, dataUrl, source:'device' };
    }
    // Try to read as text if plausible
    if (isProbablyText(name, mime)) {
      const text = await file.text();
      return { kind:'text', name, size, mime, text, source:'device' };
    }
    // Binary fallback — data URL
    const dataUrl = await new Promise((res, rej) => {
      const r = new FileReader();
      r.onload = () => res(r.result);
      r.onerror = () => rej(r.error);
      r.readAsDataURL(file);
    });
    return { kind:'binary', name, size, mime, dataUrl, source:'device' };
  }

  async function loadFsFile(path) {
    const data = await FS.readFile(path);
    const name = path.split('/').pop();
    const mime = U.mimeFromPath(name);
    const kind = U.mediaKind ? U.mediaKind(name) : (mime.startsWith('image/') ? 'image' : 'other');
    const size = data.byteLength;
    if (kind === 'image' || mime.startsWith('image/')) {
      const b64 = U.bytesToB64(data);
      let dataUrl = 'data:'+mime+';base64,'+b64;
      let outMime = mime;
      const norm = await normalizeImageDataUrl(dataUrl, mime, 1600);
      if (norm) { dataUrl = norm.dataUrl; outMime = norm.mime; }
      return { kind:'image', name, path, size, mime: outMime, dataUrl, source:'fs' };
    }
    if (isProbablyText(name, mime) && !U.looksBinary(data)) {
      const text = U.bytesToText(data);
      return { kind:'text', name, path, size, mime, text, source:'fs' };
    }
    const b64 = U.bytesToB64(data);
    return { kind:'binary', name, path, size, mime, dataUrl:'data:'+mime+';base64,'+b64, source:'fs' };
  }

  function isProbablyText(name, mime) {
    if (!name) return false;
    const ext = (name.split('.').pop()||'').toLowerCase();
    const textExts = ['txt','md','markdown','json','xml','yaml','yml','toml','ini','cfg','conf','csv','tsv','log','html','htm','css','scss','less','js','mjs','cjs','ts','tsx','jsx','py','rb','rs','go','java','kt','c','h','cpp','hpp','cs','php','sh','bash','zsh','sql','vue','svelte','env','gitignore','dockerfile'];
    if (textExts.includes(ext)) return true;
    if (mime && (mime.startsWith('text/') || /json|xml|yaml|javascript|typescript|shellscript/.test(mime))) return true;
    return false;
  }

  // ---- Source picker sheet ----
  function openSourcePicker() {
    const body = U.el('div', { class:'attach-source' });
    const optDevice = U.el('div', { class:'attach-source-opt' },
      U.el('div', { class:'attach-source-icon' }, '📱'),
      U.el('div', { class:'attach-source-info' },
        U.el('div', { class:'attach-source-title' }, 'С устройства'),
        U.el('div', { class:'attach-source-sub' }, 'Выбрать файлы с телефона/компьютера')
      )
    );
    optDevice.addEventListener('click', () => {
      UI.closeSheet();
      pickFromDevice();
    });
    const optFs = U.el('div', { class:'attach-source-opt' },
      U.el('div', { class:'attach-source-icon' }, '📁'),
      U.el('div', { class:'attach-source-info' },
        U.el('div', { class:'attach-source-title' }, 'Из проводника'),
        U.el('div', { class:'attach-source-sub' }, 'Файл(ы) проекта в IDE')
      )
    );
    optFs.addEventListener('click', () => {
      UI.closeSheet();
      pickFromFs();
    });
    body.appendChild(optDevice);
    body.appendChild(optFs);
    UI.openSheet('Прикрепить файлы', body);
  }

  function pickFromDevice() {
    const picker = U.$('#chat-attach-picker');
    picker.value = '';
    picker.onchange = async () => {
      const files = Array.from(picker.files || []);
      for (const f of files) {
        try {
          const att = await loadDeviceFile(f);
          add(att);
        } catch(e) { UI.toast('Ошибка загрузки: '+e.message); }
      }
    };
    picker.click();
  }

  async function pickFromFs() {
    // Build a flat list of files in the VFS
    const all = await FS.listTree('/');
    const files = all.filter(n => n.type === 'file').sort((a,b) => a.path.localeCompare(b.path));
    if (!files.length) { UI.toast('В проводнике нет файлов'); return; }
    const body = U.el('div', { class:'fs-picker' });
    const inputSearch = U.el('input', { class:'fs-picker-search', placeholder:'Поиск по пути…', type:'text' });
    const list = U.el('div', { class:'fs-picker-list' });
    body.appendChild(inputSearch);
    body.appendChild(list);
    let selected = new Set();
    // Header with counter + Select-all
    const header = U.el('div', { class:'fs-picker-header' });
    const counter = U.el('div', { class:'fs-picker-counter' }, 'Выбрано: 0');
    const btnAll = U.el('button', { class:'btn small ghost' }, 'Выбрать все видимые');
    const btnNone = U.el('button', { class:'btn small ghost' }, 'Снять все');
    header.appendChild(counter);
    header.appendChild(btnAll);
    header.appendChild(btnNone);
    body.insertBefore(header, list);

    function updateCounter() {
      counter.textContent = 'Выбрано: ' + selected.size;
      // enable/disable attach button
      if (btnAttach) {
        btnAttach.disabled = selected.size === 0;
        btnAttach.classList.toggle('is-disabled', selected.size === 0);
        btnAttach.textContent = selected.size ? ('Прикрепить (' + selected.size + ')') : 'Прикрепить';
      }
    }
    function toggle(path, row, cb) {
      if (selected.has(path)) { selected.delete(path); if (cb) cb.checked = false; row.classList.remove('selected'); }
      else { selected.add(path); if (cb) cb.checked = true; row.classList.add('selected'); }
      updateCounter();
    }
    let visiblePaths = [];
    function render() {
      const q = inputSearch.value.trim().toLowerCase();
      list.innerHTML = '';
      visiblePaths = [];
      let shown = 0;
      for (const f of files) {
        if (q && !f.path.toLowerCase().includes(q)) continue;
        if (shown++ > 500) break;
        visiblePaths.push(f.path);
        const isSel = selected.has(f.path);
        const row = U.el('div', { class:'fs-picker-row' + (isSel ? ' selected' : '') });
        const cb = U.el('input', { type:'checkbox', class:'fs-picker-cb' });
        cb.checked = isSel;
        // Click anywhere on the row toggles selection (including checkbox itself)
        row.addEventListener('click', (e) => {
          // If user clicked the checkbox itself, just sync state after native toggle
          if (e.target === cb) {
            if (cb.checked) { selected.add(f.path); row.classList.add('selected'); }
            else { selected.delete(f.path); row.classList.remove('selected'); }
            updateCounter();
            return;
          }
          toggle(f.path, row, cb);
        });
        row.appendChild(cb);
        row.appendChild(U.el('span', { class:'fs-picker-path' }, f.path));
        list.appendChild(row);
      }
    }
    btnAll.addEventListener('click', () => {
      for (const p of visiblePaths) selected.add(p);
      render(); updateCounter();
    });
    btnNone.addEventListener('click', () => {
      selected.clear();
      render(); updateCounter();
    });
    inputSearch.addEventListener('input', render);
    render();
    const btnAttach = U.el('button', { class:'btn primary is-disabled' }, 'Прикрепить');
    btnAttach.disabled = true;
    btnAttach.addEventListener('click', async () => {
      if (!selected.size) { UI.toast('Не выбрано ни одного файла', 'err'); return; }
      const paths = Array.from(selected);
      UI.closeSheet();
      let okN = 0, errN = 0;
      for (const p of paths) {
        try { const att = await loadFsFile(p); add(att); okN++; }
        catch(e) { errN++; UI.toast('Ошибка '+p+': '+e.message, 'err'); }
      }
      if (okN) UI.toast('Прикреплено: '+okN+(errN?', ошибок: '+errN:''), errN?'err':'ok');
    });
    const btnCancel = U.el('button', { class:'btn ghost' }, 'Отмена');
    btnCancel.addEventListener('click', () => UI.closeSheet());
    updateCounter();
    UI.openSheet('Выберите файлы из проводника', body, [btnCancel, btnAttach]);
  }

  function bind() {
    const btn = U.$('#ai-attach');
    if (btn) btn.addEventListener('click', () => {
      if (!pending.length) openSourcePicker();
      else {
        // Ask: add more or clear
        UI.openSheet('Прикреплённые файлы', (() => {
          const b = U.el('div', {},
            U.el('div', { style:'padding:8px 12px;color:var(--fg-dim);font-size:13px' }, 'Уже прикреплено: '+pending.length),
            (() => { const a = U.el('button', { class:'btn ghost', style:'margin:6px 12px' }, 'Добавить ещё'); a.addEventListener('click', () => { UI.closeSheet(); openSourcePicker(); }); return a; })(),
            (() => { const a = U.el('button', { class:'btn danger', style:'margin:6px 12px' }, 'Очистить все'); a.addEventListener('click', () => { clear(); UI.closeSheet(); }); return a; })()
          );
          return b;
        })());
      }
    });
    render();
  }

  return { bind, add, clear, get, take, render, openSourcePicker };
})();
window.Attachments = Attachments;
