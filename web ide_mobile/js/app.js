/* ===== App bootstrap ===== */
const App = (() => {
  let currentTab = 'explorer';

  function switchTab(name) {
    if (!['ai','editor','explorer','git'].includes(name)) return;
    currentTab = name;
    U.$$('.tab-panel').forEach(p => p.classList.toggle('active', p.dataset.tab === name));
    U.$$('.bottom-tab').forEach(b => b.classList.toggle('active', b.dataset.tab === name));
    U.$('#topbar-title').textContent = {
      ai:'ИИ Агент', editor:'Редактор', explorer:'Проводник', git:'Git & GitHub'
    }[name];
    updateTopbarAction(name);
    if (name === 'git') Git.renderPanel();
    Store.set({ lastTab: name });
    if (name === 'editor') {
      Editor.ensureReady();
    }
  }

  // Right-side topbar action button is context-specific per tab.
  function updateTopbarAction(name) {
    const btn = U.$('#btn-topbar-action');
    const chatsBtn = U.$('#btn-topbar-chats');
    if (!btn) return;
    // Reset
    btn.onclick = null;
    btn.style.visibility = 'hidden';
    btn.setAttribute('aria-label', '');
    if (chatsBtn) chatsBtn.style.display = 'none';
    if (name === 'ai') {
      if (chatsBtn) chatsBtn.style.display = '';
      btn.style.visibility = 'visible';
      btn.setAttribute('aria-label', 'Настройки агента');
      btn.title = 'Настройки агента';
      // Gear icon
      btn.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09a1.65 1.65 0 0 0-1-1.51 1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09a1.65 1.65 0 0 0 1.51-1 1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33h.01A1.65 1.65 0 0 0 9 3.09V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82v0a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"/></svg>';
      btn.onclick = () => Settings.open();
    }
  }

  async function importFiles(destDir='/') {
    return new Promise(resolve => {
      const inp = U.$('#file-picker');
      inp.value = '';
      inp.onchange = async () => {
        const files = Array.from(inp.files || []);
        if (!files.length) return resolve();
        // Определяем, идём ли мы в удалённый репозиторий — тогда каждая запись
        // это отдельный commit-по-API, поэтому покажем пользователю прогресс,
        // а по завершению — принудительно обновим кеш дерева, чтобы новые файлы
        // сразу были видны в Explorer.
        const ar = (Store.get() || {}).activeRepo;
        const goingToRemote = !!(ar && ar.mode === 'remote' && destDir.startsWith(ar.virtualRoot));
        try {
          await FS.ensureDirRecursive(destDir);
        } catch(_){}
        let n = 0, errors = [];
        for (let i = 0; i < files.length; i++) {
          const f = files[i];
          if (goingToRemote && files.length > 1) {
            try { UI.toast('Загружаем в GitHub… ' + (i+1) + '/' + files.length + ': ' + f.name); } catch(_){}
          }
          // If picker gave webkitRelativePath, preserve it
          const rel = f.webkitRelativePath || f.name;
          const dst = U.joinPath(destDir, rel);
          try {
            const buf = new Uint8Array(await f.arrayBuffer());
            await FS.writeFile(dst, buf);
            n++;
          } catch(e) {
            errors.push(f.name + ': ' + (e.message || e));
          }
        }
        // Если файлы ушли на GitHub — синхронизируем локальный кеш дерева,
        // иначе только что созданные пути могут не появиться в проводнике
        // до ручного обновления.
        if (goingToRemote) {
          try { await GH.refresh(); } catch(_){}
        }
        const suffix = goingToRemote ? ' → GitHub (' + ar.fullName + ')' : '';
        if (errors.length) {
          UI.toast('Импортировано: ' + n + suffix + ', ошибок: ' + errors.length, 'err');
          console.warn('[importFiles] errors:', errors);
        } else {
          UI.toast('Импортировано: ' + n + suffix, 'ok');
        }
        await Explorer.render();
        resolve();
      };
      inp.click();
    });
  }
  async function exportZip(rootPath='/') {
    const prog = UI.progress('Экспорт в zip', 'Подготовка…');
    try {
      await prog.set(0, 'Сканирование…');
      const rows = (await FS.listTree(rootPath)).filter(r => r.type==='file');
      if (!rows.length) { prog.close(); UI.toast('Нет файлов для экспорта','err'); return; }
      const bag = {};
      const total = rows.length || 1;
      let lastPct = -1;
      for (let i = 0; i < rows.length; i++) {
        const r = rows[i];
        const rel = (rootPath === '/' ? r.path.slice(1) : r.path.slice(rootPath.length + 1));
        if (rel) bag[rel] = await FS.readFile(r.path);
        const pct = Math.round((i + 1) / total * 90);
        if (pct !== lastPct) { lastPct = pct; await prog.set(pct, `Добавлено ${i + 1} файл(ов) · ${pct}%`); }
      }
      await prog.set(92, 'Сжатие данных…');
      const data = fflate.zipSync(bag);
      await prog.done('Готово');
      const blob = new Blob([data], { type:'application/zip' });
      const name = (rootPath === '/' ? 'project' : U.basename(rootPath)) + '.zip';
      // Explorer.saveBlob умеет share, FS Access, <a download>, data-url, new tab и модалку.
      // Все свои тосты она показывает сама.
      if (typeof Explorer !== 'undefined' && Explorer.saveBlob) {
        await Explorer.saveBlob(blob, name, { preferShare: true });
      } else {
        const a = document.createElement('a');
        a.href = URL.createObjectURL(blob); a.download = name;
        document.body.appendChild(a); a.click(); a.remove();
        setTimeout(()=>URL.revokeObjectURL(a.href), 1500);
        UI.toast('Экспорт: '+name, 'ok');
      }
    } catch(e){ prog.close(); UI.toast('Ошибка экспорта: '+e.message, 'err'); }
  }

  // ===== GitHub login =====
  // Built-in fallback client_id for Device Flow. This is GitHub CLI's public client_id
  // (publicly known, not a secret) — it lets any user log in without registering their own
  // OAuth App, since Device Flow doesn't require a client secret. Users can still provide
  // their own OAuth App client_id in Settings to override this default.
  const DEFAULT_GH_CLIENT_ID = '178c6fc778ccc68e1d6a';

  // Opens a bottom sheet offering three ways to sign in:
  //   1) Device Flow via system browser (recommended — one tap, uses already-logged-in browser session)
  //   2) Personal Access Token via system browser (opens github token creation page)
  //   3) Paste existing PAT manually
  function githubLoginChoice() {
    UI.closeSheet();
    const body = U.el('div', { style:{display:'flex',flexDirection:'column',gap:'8px'} });
    body.appendChild(U.el('div', { style:{fontSize:'13px',color:'var(--fg-dim)',marginBottom:'6px',lineHeight:'1.5'} },
      'Вход выполняется через системный браузер, где вы уже авторизованы. Первый способ — надёжный и работает всегда. Второй — как в мобильных приложениях GitHub, но зависит от публичных CORS-прокси и может не сработать.'));

    // Option 1 (default & reliable): PAT — открывает страницу создания токена
    const opt1 = U.el('div', { class:'attach-source-opt' },
      U.el('div', { class:'attach-source-icon' }, '🔑'),
      U.el('div', { class:'attach-source-info' },
        U.el('div', { class:'attach-source-title' }, '★ Personal Access Token (рекомендуется)'),
        U.el('div', { class:'attach-source-sub' }, 'Откроется страница создания токена в системном браузере с уже заполненными правами. Скопируйте токен и вставьте здесь — он подхватится автоматически. Работает всегда.')
      )
    );
    opt1.addEventListener('click', () => githubLoginBrowserPAT());

    // Option 2: Device Flow (пробуется, может не работать)
    const opt2 = U.el('div', { class:'attach-source-opt' },
      U.el('div', { class:'attach-source-icon' }, '🌐'),
      U.el('div', { class:'attach-source-info' },
        U.el('div', { class:'attach-source-title' }, 'Device Flow с 6-значным кодом'),
        U.el('div', { class:'attach-source-sub' }, 'Как в приложении GitHub Mobile. Открывает github.com/login/device и просит ввести код. Требует рабочий CORS-прокси — при отказе автоматически переключит на способ выше.')
      )
    );
    opt2.addEventListener('click', () => githubLoginDeviceFlow());

    // Option 3: ручной токен
    const opt3 = U.el('div', { class:'attach-source-opt' },
      U.el('div', { class:'attach-source-icon' }, '📋'),
      U.el('div', { class:'attach-source-info' },
        U.el('div', { class:'attach-source-title' }, 'Вставить готовый токен'),
        U.el('div', { class:'attach-source-sub' }, 'Если у вас уже есть Personal Access Token — введите его напрямую.')
      )
    );
    opt3.addEventListener('click', () => githubLoginManualPAT());

    body.appendChild(opt1); body.appendChild(opt2); body.appendChild(opt3);
    UI.openSheet('Вход в GitHub', body);
  }
  // Alias: старый вход = теперь выбор способа
  const githubLogin = githubLoginChoice;

  // Robust open: creates a real <a target=_blank> and clicks it synchronously.
  // Some mobile browsers block window.open() but allow anchor navigation.
  // Returns true if the browser most likely opened the tab.
  function openInSystemBrowser(url) {
    // First try synchronous anchor click — this is the most reliable path on mobile.
    try {
      const a = document.createElement('a');
      a.href = url;
      a.target = '_blank';
      a.rel = 'noopener,noreferrer';
      a.style.display = 'none';
      document.body.appendChild(a);
      a.click();
      a.remove();
      return true;
    } catch(_){}
    // Fallback: window.open
    try {
      const w = window.open(url, '_blank', 'noopener,noreferrer');
      if (w) return true;
    } catch(_){}
    return false;
  }

  // ---- Способ 1: Device Flow (auto-uses default client_id, fallback to user's) ----
  // ИСПРАВЛЕНО: сначала получаем код от GitHub и показываем его в клиенте,
  // и только потом открываем системный браузер с verification_uri_complete
  // (в этой ссылке код уже подставлен — вводить ничего не надо).
  async function githubLoginDeviceFlow() {
    const s = Store.get();
    const cid = (s.githubClientId && s.githubClientId.trim()) || DEFAULT_GH_CLIENT_ID;
    const usingDefault = cid === DEFAULT_GH_CLIENT_ID && !s.githubClientId;
    UI.closeSheet();

    const body = U.el('div');
    if (usingDefault) {
      body.appendChild(U.el('div', {
        style:{fontSize:'11px',color:'var(--warn)',marginBottom:'8px',padding:'6px 8px',background:'rgba(204,167,0,.1)',borderRadius:'6px',border:'1px solid rgba(204,167,0,.3)'}
      }, '⚠ Используется встроенный (публичный) Client ID.'));
    }
    const codeLabel = U.el('div', { style:{fontSize:'12px',color:'var(--fg-dim)',marginBottom:'10px',lineHeight:'1.5'} },
      'Получаем 6-значный код от GitHub через CORS-прокси... (5–20 сек)');
    body.appendChild(codeLabel);
    const codeEl = U.el('div', {
      style:{fontSize:'32px',fontWeight:'700',fontFamily:'var(--mono)',letterSpacing:'6px',textAlign:'center',padding:'14px 8px',background:'var(--bg3)',borderRadius:'8px',color:'var(--fg-dim)',cursor:'default',minHeight:'54px'}
    }, '· · · ·');
    body.appendChild(codeEl);
    const linkBtn = U.el('a', {
      href: '#', target:'_blank', rel:'noopener noreferrer',
      class:'btn primary',
      style:{marginTop:'12px', textAlign:'center', textDecoration:'none', display:'none'}
    }, '🌐 Открыть страницу подтверждения (код уже подставлен)');
    body.appendChild(linkBtn);
    const status = U.el('div', { style:{marginTop:'12px',color:'var(--fg-dim)',fontSize:'12px',textAlign:'center',whiteSpace:'pre-wrap'} }, 'Подключаемся к GitHub...');
    body.appendChild(status);
    UI.openSheet('Вход через системный браузер', body);

    let dev;
    try {
      dev = await Git.githubDeviceFlow(cid, 'repo,read:user,read:org', (msg) => {
        status.textContent = msg;
      });
    }
    catch(e){
      codeLabel.textContent = '❌ Не удалось получить код';
      codeLabel.style.color = 'var(--err)';
      codeEl.textContent = '—';
      status.textContent = 'Ошибка: ' + (e.message || e) + '\n\nВсе публичные CORS-прокси недоступны или заблокировали запрос. Используйте вариант «Создать Personal Access Token» — он не требует прокси.';
      status.style.color = 'var(--err)';
      const patBtn = mkBtn('🔑 Перейти к созданию токена', () => { UI.closeSheet(); githubLoginBrowserPAT(); }, 'primary');
      patBtn.style.marginTop = '8px';
      body.appendChild(patBtn);
      return;
    }
    // Success — заполняем код и запускаем polling
    codeLabel.textContent = 'Ваш код (нажмите чтобы скопировать):';
    codeEl.textContent = dev.user_code;
    codeEl.style.color = 'var(--ok)';
    codeEl.style.cursor = 'pointer';
    codeEl.style.border = '2px solid var(--ok)';
    codeEl.title = 'Нажмите чтобы скопировать';
    codeEl.addEventListener('click', async () => { try { await navigator.clipboard.writeText(dev.user_code); UI.toast('Код скопирован','ok'); } catch(e){} });
    try { await navigator.clipboard.writeText(dev.user_code); UI.toast('Код скопирован в буфер', 'ok'); } catch(_){}
    const verifyUrl = dev.verification_uri_complete || dev.verification_uri;
    linkBtn.href = verifyUrl;
    linkBtn.style.display = 'block';
    // Открываем системный браузер после того, как код показан — это ЕСТЬ ответ на клик пользователя.
    setTimeout(() => { try { openInSystemBrowser(verifyUrl); } catch(_){ } }, 300);
    body.appendChild(U.el('div', { style:{marginTop:'8px',display:'flex',gap:'8px',flexDirection:'column'} },
      mkBtn('📋 Скопировать код ('+dev.user_code+')', async () => { try { await navigator.clipboard.writeText(dev.user_code); UI.toast('Код скопирован','ok'); } catch(e){} }),
      mkBtn('🔗 Скопировать ссылку', async () => { try { await navigator.clipboard.writeText(verifyUrl); UI.toast('Ссылка скопирована','ok'); } catch(e){} })
    ));
    status.textContent = 'Ожидание подтверждения... проверяем каждые '+dev.interval+' сек';

    try {
      const token = await dev.poll(() => { status.textContent = 'Ожидание подтверждения... ' + new Date().toLocaleTimeString(); });
      const u = await Git.fetchUser(token);
      Store.set({ githubToken: token, githubUser: u.login, githubScopes: u.__scopes || [] });
      UI.closeSheet();
      UI.toast('Вход выполнен: '+u.login, 'ok');
      updateGithubHint();
      Git.renderPanel();
    } catch(e){
      UI.toast('Ошибка входа: '+e.message, 'err');
    }
  }

  // ---- Способ 2: Open GitHub token-creation page in system browser, then paste ----
  async function githubLoginBrowserPAT() {
    // Предзаполненная ссылка на создание PAT с нужными scopes
    const tokenUrl = 'https://github.com/settings/tokens/new?scopes=repo,read:user,user:email,delete_repo&description=' + encodeURIComponent('Mobile IDE');
    // Открываем через synchronous anchor click — надёжнее чем window.open на мобилках.
    const opened = openInSystemBrowser(tokenUrl);
    const popupBlocked = !opened;
    UI.closeSheet();

    const body = U.el('div', { style:{display:'flex',flexDirection:'column',gap:'10px'} });
    if (popupBlocked) {
      body.appendChild(U.el('div', {
        style:{fontSize:'12px',color:'var(--warn)',padding:'8px 10px',background:'rgba(204,167,0,.1)',borderRadius:'6px',border:'1px solid rgba(204,167,0,.3)'}
      }, '⚠ Всплывающее окно было заблокировано. Нажмите кнопку ниже, чтобы открыть страницу вручную (или разрешите всплывающие окна для этого сайта).'));
    } else {
      body.appendChild(U.el('div', { style:{fontSize:'12px',color:'var(--ok)',padding:'8px 10px',background:'rgba(78,201,176,.1)',borderRadius:'6px',border:'1px solid rgba(78,201,176,.3)'} },
        '✓ Страница создания токена открыта в системном браузере.'));
    }
    body.appendChild(U.el('div', { style:{fontSize:'13px',color:'var(--fg)',lineHeight:'1.5'} },
      '1. В открывшейся вкладке прокрутите вниз и нажмите Generate token.\n2. Скопируйте токен (ghp_...).\n3. Вернитесь сюда — токен подставится из буфера автоматически.'));
    body.appendChild(U.el('a', {
      href: tokenUrl, target:'_blank', rel:'noopener noreferrer',
      class:'btn ' + (popupBlocked ? 'primary' : ''),
      style:{textAlign:'center', textDecoration:'none', display:'block'}
    }, popupBlocked ? '🌐 Открыть страницу создания токена' : '🌐 Открыть ещё раз'));
    body.appendChild(mkBtn('🔗 Скопировать ссылку', async () => {
      try { await navigator.clipboard.writeText(tokenUrl); UI.toast('Ссылка скопирована','ok'); } catch(e){}
    }));

    const field = U.el('div', { class:'field', style:{marginTop:'8px'} },
      U.el('label', {}, 'Personal Access Token'),
      U.el('input', { type:'password', placeholder:'ghp_...', autocomplete:'off', spellcheck:'false' }),
      U.el('div', { class:'hint' }, 'Права нужны: repo, read:user (для клонирования/push). Токен хранится только локально.')
    );
    const inp = field.querySelector('input');
    body.appendChild(field);
    const submit = mkBtn('Войти с этим токеном', async () => {
      const tok = (inp.value || '').trim();
      if (!tok) { UI.toast('Введите токен', 'err'); return; }
      try {
        const u = await Git.fetchUser(tok);
        Store.set({ githubToken: tok, githubUser: u.login, githubScopes: u.__scopes || [] });
        UI.closeSheet();
        UI.toast('Вход выполнен: '+u.login, 'ok');
        updateGithubHint();
        Git.renderPanel();
      } catch(e){ UI.toast('Плохой токен: '+e.message, 'err'); }
    }, 'primary');
    submit.style.marginTop = '4px';
    body.appendChild(submit);
    UI.openSheet('Вход через токен GitHub', body);
    // Auto-paste from clipboard if it looks like a token
    setTimeout(async () => {
      try {
        const t = await navigator.clipboard.readText();
        if (t && /^gh[ps]_[A-Za-z0-9_]{20,}/.test(t.trim())) {
          inp.value = t.trim();
          UI.toast('Токен подставлен из буфера', 'ok');
        }
      } catch(_){}
      inp.focus();
    }, 400);
  }

  // ---- Способ 3: ручная вставка токена (короткий путь) ----
  async function githubLoginManualPAT() {
    UI.closeSheet();
    const tok = await UI.prompt('Personal Access Token', {
      title:'Ручной ввод токена',
      placeholder:'ghp_...',
      okText:'Войти'
    });
    if (!tok) return;
    try {
      const u = await Git.fetchUser(tok.trim());
      Store.set({ githubToken: tok.trim(), githubUser: u.login, githubScopes: u.__scopes || [] });
      UI.toast('Вход выполнен: '+u.login, 'ok');
      updateGithubHint();
      Git.renderPanel();
    } catch(e){ UI.toast('Плохой токен: '+e.message, 'err'); }
  }

  function mkBtn(text, fn, cls='') { const b = U.el('button', { class:'btn '+cls }, text); b.addEventListener('click', fn); return b; }

  async function githubClone() {
    if (!Store.get().githubToken) {
      const ok = await UI.confirm('Вы не авторизованы в GitHub. Клонирование доступно для публичных репозиториев. Продолжить?', { okText:'Продолжить' });
      if (!ok) return;
    }
    UI.closeSheet();
    const url = await UI.prompt('URL репозитория', { title:'git clone', placeholder:'https://github.com/user/repo.git' });
    if (!url) return;
    let dir = await UI.prompt('Локальная папка', { title:'Куда клонировать', value: '/'+U.basename(url).replace(/\.git$/,'') });
    if (!dir) return;
    dir = U.normPath(dir);
    if (await FS.exists(dir)) { UI.toast('Папка уже существует','err'); return; }
    UI.toast('Клонирование...');
    try {
      await Git.clone(url, dir);
      UI.toast('Готово: '+dir, 'ok');
      Explorer.expand(dir);
      await Explorer.render();
      Git.renderPanel();
    } catch(e){ UI.toast('Ошибка clone: '+e.message, 'err'); }
  }

  function updateGithubHint() {
    const el = U.$('#gh-hint');
    if (!el) return;
    const s = Store.get();
    el.textContent = s.githubUser ? ('вход: '+s.githubUser) : 'не авторизован';
  }

  function bindDrawer() {
    U.$('#btn-menu').addEventListener('click', UI.openDrawer);
    U.$('#drawer').addEventListener('click', e => {
      const item = e.target.closest('.drawer-item');
      if (!item) return;
      const act = item.dataset.action;
      UI.closeDrawer();
      // Как и в Explorer.bind: если выбран GitHub-репо, действия «импорт/экспорт»
      // из бокового меню должны работать в контексте репозитория, а не в корне.
      const _defaultDir = (() => {
        try {
          const ar = (Store.get() || {}).activeRepo;
          if (ar && ar.mode === 'remote' && ar.virtualRoot) return ar.virtualRoot;
          if (ar && ar.mode === 'local'  && ar.localDir)    return ar.localDir;
        } catch(_){}
        return '/';
      })();
      switch(act) {
        case 'import-fs': importFiles(_defaultDir); break;
        case 'export-all': exportZip(_defaultDir); break;
        case 'settings': Settings.open(); break;
        case 'github-auth':
          if (Store.get().githubUser) {
            UI.confirm('Выйти из GitHub?').then(ok => { if (ok) { Store.set({githubToken:null,githubUser:null,githubScopes:[]}); updateGithubHint(); Git.renderPanel(); } });
          } else githubLogin();
          break;
        case 'github-clone': githubClone(); break;
        case 'about':
          // === Строка копирайта в окне «О приложении» ===
          // Явно вынесена и видна в коде, чтобы её можно было легко править.
          // (Android-аналог: AgentFragment.java, константа ABOUT_COPYRIGHT.)
          var ABOUT_COPYRIGHT = '© 2026 by Claude Opus 4.8';
          UI.dialog({ title:'О приложении', body:
            '<div style="font-size:13px;line-height:1.65">' +
            '<b>Mobile IDE</b> — редактор в стиле VS Code для мобильного браузера.<br><br>' +
            'Поддерживает произвольных AI-провайдеров, работу с файлами, архивами, git и GitHub.<br><br>' +
            'Все данные (файлы, ключи, настройки) хранятся <b>локально</b> в вашем браузере (IndexedDB и localStorage) и никуда не отправляются.<br><br>' +
            ABOUT_COPYRIGHT +
            '</div>' });
          break;
      }
    });
    U.$$('.bottom-tab').forEach(b => b.addEventListener('click', () => switchTab(b.dataset.tab)));
  }

  async function boot() {
    bindDrawer();
    Explorer.bind();
    Editor.bind();
    Git.bind();
    AI.bind();
    if (window.ChatMenu) ChatMenu.bind();
    if (window.Attachments) Attachments.bind();

    // Re-mount active remote repo (if any) so the virtual /gh/... path resolves
    // immediately on refresh. Silently ignore failures — user can reconnect.
    try {
      const ar = Store.get().activeRepo;
      if (ar && ar.mode === 'remote' && ar.owner && ar.repo && ar.branch && window.GH) {
        await GH.mount({ owner: ar.owner, repo: ar.repo, branch: ar.branch });
        // Ensure Explorer expands into the mount so user sees files after refresh
        Explorer.expand('/gh');
        Explorer.expand('/gh/'+ar.owner);
        Explorer.expand(ar.virtualRoot);
      }
    } catch(e){ console.warn('[boot] remount failed:', e); }

    await Explorer.render();
    AI.updateHeader();
    AI.renderMessages();
    updateGithubHint();

    const last = Store.get().lastTab || 'explorer';
    switchTab(last);

    // Restore open editor tabs (only if editor tab likely to be visited)
    setTimeout(() => Editor.restore().catch(()=>{}), 500);

    // Prevent iOS bounce on scroll containers etc.
    document.addEventListener('touchmove', e => {
      // Allow default scroll — the fixed layout keeps things in place
    }, { passive: true });

    // If no providers yet, hint user
    if (!Store.get().providers.length) {
      UI.toast('Добавьте AI провайдера в меню → Настройки ИИ', '');
    }
  }

  return { switchTab, importFiles, exportZip, githubLogin, githubLoginDeviceFlow, githubLoginBrowserPAT, githubLoginManualPAT, githubClone, updateGithubHint, boot };
})();
window.App = App;

// Показать видимое сообщение об ошибке, чтобы интерфейс никогда не оставался
// «мёртвым» молча (например, если не загрузился один из внешних скриптов).
function __showBootError(err) {
  try {
    var msg = (err && (err.stack || err.message)) || String(err);
    var box = document.getElementById('__boot-error');
    if (!box) {
      box = document.createElement('div');
      box.id = '__boot-error';
      box.style.cssText = 'position:fixed;left:0;right:0;top:0;z-index:99999;background:#3a2323;color:#f6a3a3;'
        + 'font:12px/1.5 monospace;padding:10px 12px;border-bottom:1px solid #5a3333;white-space:pre-wrap;'
        + 'max-height:50vh;overflow:auto;-webkit-user-select:text;user-select:text;';
      (document.body || document.documentElement).appendChild(box);
    }
    box.textContent = 'Ошибка запуска Mobile IDE:\n' + msg
      + '\n\nЕсли вы открыли файл напрямую (file://) без интернета — часть внешних библиотек '
      + '(CDN) могла не загрузиться. Откройте через локальный веб-сервер или проверьте соединение.';
  } catch (_) {}
}

// Ловим ошибки внешних скриптов и глобальные исключения.
window.addEventListener('error', function (e) {
  if (e && e.target && e.target.tagName === 'SCRIPT') {
    __showBootError('Не удалось загрузить скрипт: ' + (e.target.src || '(inline)'));
  }
}, true);

function __bootSafe() {
  if (typeof App === 'undefined' || !App || !App.boot) {
    __showBootError('Модуль App не инициализирован — вероятно, не загрузился один из скриптов приложения.');
    return;
  }
  try {
    Promise.resolve(App.boot()).catch(__showBootError);
  } catch (err) {
    __showBootError(err);
  }
}

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', __bootSafe);
} else {
  __bootSafe();
}
