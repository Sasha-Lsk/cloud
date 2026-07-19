/* ===== Git (isomorphic-git over FS.igfs) + GitHub Device Flow ===== */
const Git = (() => {
  const git = () => window.git;
  const httpMod = () => window.GitHttp;
  const fs = FS.igfs;

  function corsProxy() { return Store.get().corsProxy || 'https://cors.isomorphic-git.org'; }

  // === Repository discovery: any dir containing .git ===
  async function listRepos() {
    const all = await FS.listAll();
    const set = new Set();
    for (const p of all) {
      const m = p.match(/^(\/.+?)\/\.git(?:\/|$)/) || (p.endsWith('/.git') ? [null, p.slice(0,-5)] : null);
      if (m) set.add(m[1]);
    }
    return Array.from(set).sort();
  }

  async function status(dir) {
    const rows = await git().statusMatrix({ fs, dir });
    // headStatus, workdirStatus, stageStatus
    const out = [];
    for (const [file, head, work, stage] of rows) {
      if (head === 1 && work === 1 && stage === 1) continue; // unchanged
      let code = '';
      if (head === 0 && work === 2) code = 'A'; // added
      else if (head === 1 && work === 0) code = 'D'; // deleted
      else if (head === 1 && work === 2) code = 'M'; // modified
      else code = 'U';
      const staged = stage === work && stage !== 0;
      out.push({ path:file, code, staged });
    }
    return out;
  }

  async function currentBranch(dir) {
    try { return await git().currentBranch({ fs, dir, fullname:false }) || 'HEAD'; }
    catch(e){ return 'HEAD'; }
  }

  async function log(dir, depth=20) {
    try { return await git().log({ fs, dir, depth }); } catch(e){ return []; }
  }

  function onAuth() {
    const s = Store.get();
    if (s.githubToken) return { username: s.githubUser || 'x-access-token', password: s.githubToken };
    return {};
  }

  async function push(dir, opts={}) {
    const branch = await currentBranch(dir);
    return git().push({
      fs, http: httpMod(), dir,
      corsProxy: corsProxy(),
      ref: branch, remote:'origin',
      onAuth,
      force: !!opts.force
    });
  }

  async function pull(dir) {
    const branch = await currentBranch(dir);
    return git().pull({
      fs, http: httpMod(), dir,
      corsProxy: corsProxy(),
      ref: branch,
      singleBranch:true,
      author: { name: Store.get().githubUser || 'user', email: (Store.get().githubUser||'user')+'@users.noreply.github.com' },
      onAuth
    });
  }

  async function add(dir, filepath) {
    return git().add({ fs, dir, filepath });
  }
  async function remove(dir, filepath) {
    return git().remove({ fs, dir, filepath });
  }
  async function addAll(dir) {
    const rows = await git().statusMatrix({ fs, dir });
    for (const [file, head, work] of rows) {
      if (work === 0 && head === 1) await git().remove({ fs, dir, filepath:file });
      else await git().add({ fs, dir, filepath:file });
    }
  }
  async function commit(dir, message) {
    const user = Store.get().githubUser || 'user';
    return git().commit({
      fs, dir, message,
      author:{ name: user, email: user+'@users.noreply.github.com' }
    });
  }
  async function init(dir) {
    await FS.ensureDirRecursive(dir);
    return git().init({ fs, dir, defaultBranch:'main' });
  }

  // === GitHub Device Flow (opens system browser) ===
  // Uses a cascade of public CORS proxies because github.com/login/* doesn't send
  // Access-Control-Allow-Origin. The isomorphic-git proxy is git-only; it won't
  // pass OAuth endpoints. So we try a set of general-purpose CORS proxies until one
  // returns a proper JSON response.
  //
  // The user can override the list via Store.githubCorsProxy (single template),
  // otherwise DEFAULT_GH_PROXIES is tried in order.
  const DEFAULT_GH_PROXIES = [
    'https://proxy.cors.sh/{url_raw}',            // verified working 2026-07 without API key (may become rate-limited)
    'https://corsproxy.io/?url={url_enc}',        // free tier is dev-only, may return 403
    'https://api.allorigins.win/raw?url={url_enc}',
    'https://cors.eu.org/{url_raw}',
    'https://api.codetabs.com/v1/proxy?quest={url_raw}',
    'https://thingproxy.freeboard.io/fetch/{url_raw}',
  ];
  function buildGhProxyUrl(tpl, target) {
    if (!tpl) return target;
    if (tpl.includes('{url_enc}')) return tpl.replace('{url_enc}', encodeURIComponent(target));
    if (tpl.includes('{url_raw}')) return tpl.replace('{url_raw}', target);
    return tpl.replace(/\/+$/, '') + '/' + target;
  }
  // Try each proxy in order; return {json, proxy} on first success or throw with details.
  // onProgress(msg) is called before each attempt so UI can show which proxy is being tried.
  // preferredProxy — if set, is tried FIRST (used during polling to reuse the working one).
  async function postJsonViaProxies(url, body, opts) {
    opts = opts || {};
    const s = Store.get();
    const userProxy = (s.githubCorsProxy || '').trim();
    let chain;
    if (opts.preferredProxy) {
      chain = [opts.preferredProxy];
    } else {
      chain = userProxy ? [userProxy] : DEFAULT_GH_PROXIES.slice();
    }
    const onProgress = opts.onProgress;
    const errors = [];
    const formBody = new URLSearchParams(body).toString();
    // Per-proxy timeout in ms — critical on mobile where a slow/hung proxy
    // can freeze the whole flow. 7s is enough for a healthy proxy to answer.
    const TIMEOUT_MS = opts.timeoutMs || 7000;
    async function fetchWithTimeout(fetchUrl) {
      // Both AbortController AND Promise.race guard — some mobile browsers ignore abort.
      const ctrl = (typeof AbortController !== 'undefined') ? new AbortController() : null;
      let timer;
      const timeoutPromise = new Promise((_, reject) => {
        timer = setTimeout(() => {
          if (ctrl) try { ctrl.abort(); } catch(_){}
          const e = new Error('timeout'); e.name = 'AbortError'; reject(e);
        }, TIMEOUT_MS);
      });
      const fetchPromise = fetch(fetchUrl, {
        method: 'POST',
        headers: { 'Accept':'application/json', 'Content-Type':'application/x-www-form-urlencoded' },
        body: formBody,
        signal: ctrl ? ctrl.signal : undefined,
      });
      try {
        return await Promise.race([fetchPromise, timeoutPromise]);
      } finally { clearTimeout(timer); }
    }
    for (let i = 0; i < chain.length; i++) {
      const tpl = chain[i];
      const host = (tpl.split('/')[2] || 'direct');
      if (onProgress) onProgress('Прокси ' + (i+1) + '/' + chain.length + ': ' + host + ' (таймаут ' + Math.round(TIMEOUT_MS/1000) + 'с)');
      const proxied = buildGhProxyUrl(tpl, url);
      try {
        const r = await fetchWithTimeout(proxied);
        if (!r.ok) { errors.push(host + ' HTTP ' + r.status); continue; }
        const text = await r.text();
        let json;
        try { json = JSON.parse(text); }
        catch(_){
          errors.push(host + ' non-JSON');
          continue;
        }
        // Some proxies return {contents: "..."} wrapping — try to unwrap
        if (json && typeof json === 'object' && typeof json.contents === 'string') {
          try { json = JSON.parse(json.contents); } catch(_){}
        }
        // Reject responses that aren't a valid GitHub OAuth reply
        if (!json || (typeof json !== 'object') || (!json.device_code && !json.access_token && !json.error)) {
          errors.push(host + ' bad-shape');
          continue;
        }
        return { json, proxy: tpl };
      } catch(e){
        const msg = e.name === 'AbortError' ? 'timeout' : ((e.message || e)+'').slice(0,60);
        errors.push(host + ' ' + msg);
      }
    }
    throw new Error('Все CORS-прокси недоступны. Детали: ' + errors.join('; '));
  }
  async function githubDeviceFlow(clientId, scope='repo,read:user,delete_repo', onProgress) {
    // Step 1: request device+user codes
    const step1r = await postJsonViaProxies('https://github.com/login/device/code', { client_id: clientId, scope }, { onProgress });
    const step1 = step1r.json;
    if (step1.error) throw new Error(step1.error_description || step1.error);
    if (!step1.device_code || !step1.user_code) throw new Error('Прокси вернул некорректный ответ (нет device_code)');
    const workingProxy = step1r.proxy;
    return {
      user_code: step1.user_code,
      verification_uri: step1.verification_uri,
      verification_uri_complete: step1.verification_uri_complete,
      expires_in: step1.expires_in,
      interval: step1.interval || 5,
      device_code: step1.device_code,
      via: workingProxy,
      async poll(onTick) {
        const start = Date.now();
        let interval = (step1.interval || 5) * 1000;
        while (Date.now() - start < (step1.expires_in * 1000)) {
          await U.sleep(interval);
          if (onTick) onTick();
          try {
            const r = (await postJsonViaProxies('https://github.com/login/oauth/access_token', {
              client_id: clientId,
              device_code: step1.device_code,
              grant_type: 'urn:ietf:params:oauth:grant-type:device_code'
            }, { preferredProxy: workingProxy })).json;
            if (r.access_token) return r.access_token;
            if (r.error === 'slow_down') interval += 5000;
            else if (r.error === 'authorization_pending') { /* keep polling */ }
            else if (r.error) throw new Error(r.error_description || r.error);
          } catch(e){ throw e; }
        }
        throw new Error('Время ожидания истекло');
      }
    };
  }

  // Fetch user info to verify token; also captures the OAuth scopes GitHub actually
  // granted (X-OAuth-Scopes header). We store them so the UI can proactively warn
  // the user when a destructive action (delete repo) needs a scope they don't have.
  async function fetchUser(token) {
    const r = await fetch('https://api.github.com/user', { headers:{ Authorization:'token '+token, Accept:'application/vnd.github+json' } });
    if (!r.ok) throw new Error('GitHub API: '+r.status);
    const scopesHeader = r.headers.get('X-OAuth-Scopes') || r.headers.get('x-oauth-scopes') || '';
    const scopes = scopesHeader.split(',').map(s => s.trim()).filter(Boolean);
    const j = await r.json();
    j.__scopes = scopes;
    return j;
  }
  // Convenience: read cached scopes for the current token
  function tokenScopes() {
    return Store.get().githubScopes || [];
  }
  function hasScope(name) {
    const sc = tokenScopes();
    return sc.includes(name) || sc.includes('*');
  }

  // Fetch ALL repositories (public + private) the authenticated user has access to.
  async function fetchAllRepos(token) {
    // GitHub responses to /user/repos are HTTP-cached and served from CDN.
    // After deleting a repo, a plain GET may still return a ghost entry for
    // a few seconds. We avoid the cache with:
    //   - a per-call cache-buster query param `&_=<unique>` (defeats URL-keyed caches)
    //   - `cache: 'no-store'` on the Request itself (defeats the browser HTTP cache)
    // We deliberately do NOT add `Cache-Control` / `If-None-Match` headers —
    // those trigger a CORS preflight which some networks/proxies fail, and would
    // manifest as "Failed to fetch" in the UI.
    const headers = {
      Authorization:'token '+token,
      Accept:'application/vnd.github+json'
    };
    const buster = Date.now() + '-' + Math.random().toString(36).slice(2,8);
    const acc = [];
    for (let page = 1; page <= 20; page++) {
      const url = 'https://api.github.com/user/repos?per_page=100&sort=updated&affiliation=owner,collaborator,organization_member&page=' + page + '&_=' + buster;
      const r = await fetch(url, { headers, cache: 'no-store' });
      if (!r.ok) throw new Error('GitHub API /user/repos: ' + r.status);
      const arr = await r.json();
      if (!Array.isArray(arr) || arr.length === 0) break;
      for (const it of arr) acc.push(it);
      if (arr.length < 100) break;
    }
    return acc;
  }
  async function fetchBranches(token, fullName) {
    const headers = { Authorization:'token '+token, Accept:'application/vnd.github+json' };
    const acc = [];
    for (let page = 1; page <= 10; page++) {
      const url = 'https://api.github.com/repos/'+fullName+'/branches?per_page=100&page='+page;
      const r = await fetch(url, { headers });
      if (!r.ok) throw new Error('GitHub API /branches: ' + r.status);
      const arr = await r.json();
      if (!Array.isArray(arr) || arr.length === 0) break;
      for (const it of arr) acc.push(it.name);
      if (arr.length < 100) break;
    }
    return acc;
  }

  // ===== Panel UI =====
  // Draft selection state (in-memory only until user presses OK).
  let draftRepo = null;   // { fullName, owner, name, cloneUrl, branch, private, defaultBranch }
  let remoteRepos = null; // cached fetch of /user/repos
  let branchesCache = {}; // fullName -> [branches]
  // Repos we deleted this session — used to filter out stale CDN responses that
  // still return the ghost entry.
  const deletedFullNames = new Set();

  async function renderPanel() {
    const body = U.$('#git-panel-body');
    body.innerHTML = '';
    const s = Store.get();

    // ---- Auth card ----
    const authCard = U.el('div', { class:'settings-section' },
      U.el('h4', {}, 'GITHUB'),
      s.githubUser
        ? U.el('div', {},
            U.el('div', {}, 'Пользователь: ', U.el('b', {}, s.githubUser)),
            U.el('div', { class:'actions', style:{marginTop:'8px', display:'flex', gap:'6px', flexWrap:'wrap'} },
              btn('+ Новый репозиторий', createRepoDialog, 'small primary'),
              btn('Обновить список', () => { remoteRepos = null; branchesCache = {}; renderPanel(); }, 'small ghost'),
              btn('Выйти', logoutGithub, 'danger small')
            )
          )
        : U.el('div', {},
            U.el('div', { style:{fontSize:'12px',color:'var(--fg-dim)',marginBottom:'8px'} }, 'Не авторизован. Вход открывает system browser.'),
            U.el('div', {},
              btn('Войти в GitHub', ()=>App.githubLogin(), 'primary small')
            )
          )
    );
    body.appendChild(authCard);

    // ---- Active-repo card (what the agent currently sees) ----
    if (s.activeRepo) {
      const ar = s.activeRepo;
      const modeLabel = ar.mode === 'remote'
        ? 'через GitHub API (без клонирования)'
        : 'локальная копия: ' + (ar.localDir || '—');
      const activeCard = U.el('div', { class:'settings-section' },
        U.el('h4', {}, 'АКТИВНЫЙ РЕПОЗИТОРИЙ ДЛЯ АГЕНТА'),
        U.el('div', { style:{fontSize:'13px',color:'var(--fg-bright)'} }, ar.fullName),
        U.el('div', { style:{fontSize:'11px',color:'var(--fg-dim)',marginTop:'2px'} },
          'ветка: ' + (ar.branch || '?') + '  ·  ' + modeLabel),
        U.el('div', { style:{fontSize:'11px',color:'var(--fg-dim)',marginTop:'2px'} },
          'корень: ' + (ar.virtualRoot || ar.localDir)),
        U.el('div', { style:{marginTop:'8px',display:'flex',gap:'6px',flexWrap:'wrap'} },
          btn('Отменить выбор', async () => {
            const cur = Store.get().activeRepo;
            if (cur && cur.mode === 'remote' && cur.virtualRoot) {
              try { await GH.unmount(cur.virtualRoot); } catch(_){}
            }
            Store.set({activeRepo:null});
            UI.toast('Выбор снят','ok');
            try { await Explorer.render(); } catch(_){}
            renderPanel();
          }, 'small ghost'),
          ar.mode === 'remote'
            ? btn('Обновить дерево', async () => {
                try { await GH.refresh(); await Explorer.render(); UI.toast('Дерево обновлено','ok'); }
                catch(e){ UI.toast('Ошибка: '+e.message,'err'); }
              }, 'small ghost')
            : null,
          btn('Открыть в проводнике', () => App.switchTab('explorer'), 'small primary')
        )
      );
      body.appendChild(activeCard);
    }

    // ---- Remote repos list (public + private) with branch selection ----
    if (s.githubToken) {
      const remoteCard = U.el('div', { class:'git-section' });
      const hdr = U.el('div', { class:'git-section-hdr' }, 'МОИ РЕПОЗИТОРИИ НА GITHUB');
      remoteCard.appendChild(hdr);
      body.appendChild(remoteCard);

      const listWrap = U.el('div', { id:'gh-repo-list', style:{display:'flex',flexDirection:'column'} });
      remoteCard.appendChild(listWrap);

      if (!remoteRepos) {
        listWrap.appendChild(U.el('div', { class:'git-empty' }, 'Загружаем репозитории…'));
        try {
          let fetched = await fetchAllRepos(s.githubToken);
          // GitHub's /user/repos is served via CDN and lags behind DELETE. Retry
          // up to 2 more times (with backoff) while any recently-deleted repo is
          // still present in the response. `deletedFullNames` is our in-session
          // truth for what SHOULD be gone.
          let attempt = 0;
          while (attempt < 2 && fetched.some(r => deletedFullNames.has(r.full_name))) {
            await new Promise(res => setTimeout(res, 1500));
            try { fetched = await fetchAllRepos(s.githubToken); } catch(_){}
            attempt++;
          }
          // Whatever we still see, filter out the ghosts so the UI is consistent.
          remoteRepos = fetched.filter(r => !deletedFullNames.has(r.full_name));
        } catch(e) {
          listWrap.innerHTML = '';
          listWrap.appendChild(U.el('div', { class:'git-empty', style:{color:'var(--err)'} }, 'Ошибка: ' + e.message));
          return;
        }
      }
      listWrap.innerHTML = '';
      hdr.textContent = 'МОИ РЕПОЗИТОРИИ НА GITHUB (' + remoteRepos.length + ')';

      if (!remoteRepos.length) {
        listWrap.appendChild(U.el('div', { class:'git-empty' }, 'Репозиториев не найдено'));
      } else {
        // Search box
        const search = U.el('input', {
          type:'search', placeholder:'Поиск по названию…',
          style:{margin:'8px 12px',padding:'8px 10px',background:'var(--bg3)',border:'1px solid var(--border)',color:'var(--fg)',borderRadius:'6px',fontSize:'13px'}
        });
        listWrap.appendChild(search);

        const rowsHolder = U.el('div');
        listWrap.appendChild(rowsHolder);

        function paint() {
          rowsHolder.innerHTML = '';
          const q = (search.value || '').trim().toLowerCase();
          const filtered = remoteRepos.filter(r => !q || r.full_name.toLowerCase().includes(q));
          for (const r of filtered) rowsHolder.appendChild(remoteRepoRow(r));
        }
        search.addEventListener('input', paint);
        paint();

        // OK + Clone buttons (footer). Stack them vertically as requested.
        const footer = U.el('div', {
          style:{padding:'12px',borderTop:'1px solid var(--border)',display:'flex',gap:'8px',flexDirection:'column',position:'sticky',bottom:'0',background:'var(--bg2)'}
        });
        const okBtn = btn('OK — использовать выбранный репозиторий', confirmSelectionRemote, 'primary');
        const cloneBtn = btn('Клонировать в проводник', confirmSelectionLocal, 'small');
        const deleteBtn = btn('Удалить выбранный репозиторий', deleteSelectedRepo, 'small danger');
        footer.appendChild(okBtn);
        footer.appendChild(cloneBtn);
        footer.appendChild(deleteBtn);
        remoteCard.appendChild(footer);
      }
    }

    // ---- Local repos list (isomorphic-git checkouts on device) ----
    const repos = await listRepos();
    const localCard = U.el('div', { class:'git-section' },
      U.el('div', { class:'git-section-hdr' }, 'ЛОКАЛЬНЫЕ КОПИИ (' + repos.length + ')')
    );
    if (!repos.length) {
      localCard.appendChild(U.el('div', { class:'git-empty' }, 'Нет клонированных репозиториев.'));
      const init = U.el('div', { style:{padding:'0 12px 12px'} }, btn('Инициализировать /project', async ()=>{
        const name = await UI.prompt('Путь папки', {title:'git init', value:'/project'});
        if (!name) return;
        await Git.init(name); UI.toast('Репозиторий создан','ok'); renderPanel(); Explorer.render();
      }, 'small'));
      localCard.appendChild(init);
    } else {
      for (const r of repos) localCard.appendChild(await repoCard(r));
    }
    body.appendChild(localCard);
  }

  // Row: one remote GitHub repository with branch selector + selection frame.
  function remoteRepoRow(repo) {
    const isSel = draftRepo && draftRepo.fullName === repo.full_name;
    const row = U.el('div', { class:'repo-card gh-repo-row' + (isSel ? ' selected' : '') });

    const top = U.el('div', { style:{display:'flex',alignItems:'center',gap:'8px',flexWrap:'wrap'} });
    const nameEl = U.el('div', { class:'name', style:{flex:'1',minWidth:'0',overflow:'hidden',textOverflow:'ellipsis',whiteSpace:'nowrap'} }, repo.full_name);
    top.appendChild(nameEl);
    if (repo.private) top.appendChild(U.el('span', {
      style:{fontSize:'10px',padding:'2px 6px',borderRadius:'10px',border:'1px solid var(--warn)',color:'var(--warn)'}
    }, 'PRIVATE'));
    else top.appendChild(U.el('span', {
      style:{fontSize:'10px',padding:'2px 6px',borderRadius:'10px',border:'1px solid var(--fg-dim)',color:'var(--fg-dim)'}
    }, 'PUBLIC'));
    // Иконка переименования репозитория.
    const repoRenameBtn = U.el('button', { class:'gh-icon-btn', title:'Переименовать репозиторий', 'aria-label':'Переименовать репозиторий' }, iconPencil());
    repoRenameBtn.addEventListener('click', (ev) => { ev.stopPropagation(); onRenameRepo(repo); });
    top.appendChild(repoRenameBtn);
    row.appendChild(top);

    if (repo.description) {
      row.appendChild(U.el('div', {
        style:{fontSize:'11px',color:'var(--fg-dim)',marginTop:'4px',overflow:'hidden',textOverflow:'ellipsis',whiteSpace:'nowrap'}
      }, repo.description));
    }

    // Строка ветки: подпись + текущая выбранная ветка + кнопка «развернуть список».
    const initialBr = (isSel && draftRepo.branch) || repo.default_branch || 'main';
    const brWrap = U.el('div', { style:{display:'flex',gap:'8px',alignItems:'center',marginTop:'8px',flexWrap:'wrap'} });
    brWrap.appendChild(U.el('span', { style:{fontSize:'11px',color:'var(--fg-dim)'} }, 'branch:'));
    const curBranchEl = U.el('span', { class:'gh-cur-branch', style:{fontSize:'12px',color:'var(--fg)',fontFamily:'var(--mono)',flex:'1',minWidth:'0',overflow:'hidden',textOverflow:'ellipsis',whiteSpace:'nowrap'} }, initialBr);
    brWrap.appendChild(curBranchEl);
    const toggleBtn = U.el('button', { class:'gh-icon-btn', title:'Показать ветки', 'aria-label':'Показать ветки' }, '▾');
    brWrap.appendChild(toggleBtn);
    row.appendChild(brWrap);

    // Разворачиваемый список веток: у каждой — клик для выбора + иконки rename/delete.
    const brList = U.el('div', { class:'gh-branch-list', style:{display:'none',marginTop:'6px',flexDirection:'column',gap:'2px'} });
    row.appendChild(brList);

    let currentBranch = initialBr;
    let expanded = false;
    let loaded = false;

    function setCurrentBranch(b) {
      currentBranch = b;
      curBranchEl.textContent = b;
      if (draftRepo && draftRepo.fullName === repo.full_name) draftRepo.branch = b;
    }

    function renderBranchItems(brs) {
      brList.innerHTML = '';
      const seen = new Set();
      const list = [];
      for (const b of brs) { if (!seen.has(b)) { seen.add(b); list.push(b); } }
      if (!seen.has(currentBranch) && currentBranch) list.unshift(currentBranch);
      if (!list.length) { brList.appendChild(U.el('div', { style:{fontSize:'11px',color:'var(--fg-dim)',padding:'4px 6px'} }, 'нет веток')); return; }
      for (const b of list) {
        const isCur = b === currentBranch;
        const isDefault = b === repo.default_branch;
        const item = U.el('div', { class:'gh-branch-item' + (isCur ? ' active' : '') });
        const label = U.el('span', { class:'gh-branch-name' }, b + (isDefault ? ' (default)' : ''));
        item.appendChild(label);
        // Выбор ветки кликом по названию.
        label.addEventListener('click', (ev) => {
          ev.stopPropagation();
          setCurrentBranch(b);
          renderBranchItems(branchesCache[repo.full_name] || brs);
        });
        // Иконка переименования ветки.
        const renameBtn = U.el('button', { class:'gh-icon-btn', title:'Переименовать ветку', 'aria-label':'Переименовать ветку' }, iconPencil());
        renameBtn.addEventListener('click', (ev) => { ev.stopPropagation(); onRenameBranch(repo, b, () => refreshBranches(true)); });
        item.appendChild(renameBtn);
        // Иконка удаления ветки.
        const delBtn = U.el('button', { class:'gh-icon-btn danger', title:'Удалить ветку', 'aria-label':'Удалить ветку' }, iconTrash());
        delBtn.addEventListener('click', (ev) => { ev.stopPropagation(); onDeleteBranch(repo, b, () => refreshBranches(true)); });
        item.appendChild(delBtn);
        brList.appendChild(item);
      }
    }

    async function refreshBranches(force) {
      try {
        if (force || !branchesCache[repo.full_name]) {
          brList.innerHTML = '';
          brList.appendChild(U.el('div', { style:{fontSize:'11px',color:'var(--fg-dim)',padding:'4px 6px'} }, 'загрузка веток…'));
          const brs = await fetchBranches(Store.get().githubToken, repo.full_name);
          branchesCache[repo.full_name] = brs;
        }
        renderBranchItems(branchesCache[repo.full_name]);
      } catch(e){
        brList.innerHTML = '';
        brList.appendChild(U.el('div', { style:{fontSize:'11px',color:'var(--err,#f66)',padding:'4px 6px'} }, 'ошибка загрузки веток: ' + e.message));
      }
    }

    async function loadBranches() {
      if (loaded) { renderBranchItems(branchesCache[repo.full_name] || [currentBranch]); return; }
      loaded = true;
      await refreshBranches(false);
    }

    function toggleExpand() {
      expanded = !expanded;
      brList.style.display = expanded ? 'flex' : 'none';
      toggleBtn.textContent = expanded ? '▴' : '▾';
      if (expanded) loadBranches();
    }
    toggleBtn.addEventListener('click', (ev) => { ev.stopPropagation(); toggleExpand(); });

    // Выбор репозитория кликом по строке (кроме кнопок/списка веток).
    row.addEventListener('click', (ev) => {
      if (ev.target.closest('.gh-branch-list') || ev.target.closest('.gh-icon-btn')) return;
      if (draftRepo && draftRepo.fullName === repo.full_name) {
        draftRepo = null;
      } else {
        const [owner, name] = repo.full_name.split('/');
        draftRepo = {
          fullName: repo.full_name, owner, name,
          cloneUrl: repo.clone_url,
          branch: currentBranch || repo.default_branch || 'main',
          defaultBranch: repo.default_branch,
          private: !!repo.private
        };
      }
      const holder = row.parentElement;
      if (holder) {
        const inp = holder.parentElement && holder.parentElement.querySelector('input[type=search]');
        holder.innerHTML = '';
        const q = inp ? (inp.value || '').trim().toLowerCase() : '';
        const filtered = remoteRepos.filter(r => !q || r.full_name.toLowerCase().includes(q));
        for (const r of filtered) holder.appendChild(remoteRepoRow(r));
      }
    });

    return row;
  }

  // SVG-иконки для действий над репо/ветками (в стиле остальных иконок приложения).
  function svgIcon(inner) {
    const span = U.el('span', { class:'gh-svg' });
    span.innerHTML = '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">' + inner + '</svg>';
    return span;
  }
  function iconPencil() {
    return svgIcon('<path d="M12 20h9"/><path d="M16.5 3.5a2.121 2.121 0 0 1 3 3L7 19l-4 1 1-4z"/>');
  }
  function iconTrash() {
    return svgIcon('<path d="M3 6h18"/><path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"/><path d="M10 11v6M14 11v6"/><path d="M9 6V4a2 2 0 0 1 2-2h2a2 2 0 0 1 2 2v2"/>');
  }

  // Переименование репозитория (обновляет список после успеха).
  async function onRenameRepo(repo) {
    const [owner, oldName] = repo.full_name.split('/');
    const newName = await UI.prompt('Новое имя репозитория', { title:'Переименовать репозиторий', value: oldName, okText:'Переименовать' });
    if (newName == null) return;
    const trimmed = newName.trim();
    if (!trimmed || trimmed === oldName) return;
    try {
      UI.toast('Переименование…');
      const updated = await renameRepoApi(repo.full_name, trimmed);
      UI.toast('Переименован в ' + updated.full_name, 'ok');
      // Если это был активный репозиторий — обновим привязку.
      const active = Store.get().activeRepo;
      if (active && active.fullName === repo.full_name) {
        active.fullName = updated.full_name; active.repo = updated.name;
        Store.set({ activeRepo: active });
      }
      remoteRepos = null; branchesCache = {};
      if (draftRepo && draftRepo.fullName === repo.full_name) draftRepo = null;
      await renderPanel();
    } catch(e) { UI.toast('Ошибка: ' + e.message, 'err'); }
  }

  // Переименование ветки.
  async function onRenameBranch(repo, branch, onDone) {
    const newName = await UI.prompt('Новое имя ветки', { title:'Переименовать ветку «' + branch + '»', value: branch, okText:'Переименовать' });
    if (newName == null) return;
    const trimmed = newName.trim();
    if (!trimmed || trimmed === branch) return;
    try {
      UI.toast('Переименование ветки…');
      await renameBranchApi(repo.full_name, branch, trimmed);
      UI.toast('Ветка → ' + trimmed, 'ok');
      delete branchesCache[repo.full_name];
      if (onDone) await onDone();
    } catch(e) { UI.toast('Ошибка: ' + e.message, 'err'); }
  }

  // Удаление ветки (с подтверждением).
  async function onDeleteBranch(repo, branch, onDone) {
    const ok = await UI.confirm('Удалить ветку «' + branch + '» из ' + repo.full_name + '? Действие необратимо.',
      { title:'Удалить ветку', okText:'Удалить', danger:true });
    if (!ok) return;
    try {
      UI.toast('Удаление ветки…');
      await deleteBranchApi(repo.full_name, branch);
      UI.toast('Ветка удалена: ' + branch, 'ok');
      delete branchesCache[repo.full_name];
      if (onDone) await onDone();
    } catch(e) { UI.toast('Ошибка: ' + e.message, 'err'); }
  }

  // POST /user/repos — create a new repo on GitHub.
  async function createRepo({ name, description, isPrivate, autoInit }) {
    const token = Store.get().githubToken;
    if (!token) throw new Error('Не авторизован в GitHub');
    const r = await fetch('https://api.github.com/user/repos', {
      method: 'POST',
      headers: {
        Authorization: 'token ' + token,
        Accept: 'application/vnd.github+json',
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        name,
        description: description || '',
        private: !!isPrivate,
        auto_init: !!autoInit
      })
    });
    if (!r.ok) {
      const txt = await r.text();
      let msg = 'HTTP ' + r.status;
      try { const j = JSON.parse(txt); msg = j.message + (j.errors ? ' — ' + JSON.stringify(j.errors) : ''); } catch(_){}
      throw new Error(msg);
    }
    return r.json();
  }

  function createRepoDialog() {
    const s = Store.get();
    if (!s.githubToken) { UI.toast('Сначала войдите в GitHub', 'err'); return; }
    const nameInp = U.el('input', {
      type:'text', placeholder:'my-new-repo',
      style:{width:'100%',padding:'8px 10px',background:'var(--bg3)',border:'1px solid var(--border)',color:'var(--fg)',borderRadius:'6px',fontSize:'14px',marginBottom:'10px'}
    });
    const descInp = U.el('input', {
      type:'text', placeholder:'Описание (необязательно)',
      style:{width:'100%',padding:'8px 10px',background:'var(--bg3)',border:'1px solid var(--border)',color:'var(--fg)',borderRadius:'6px',fontSize:'13px',marginBottom:'10px'}
    });
    const privCk = U.el('input', { type:'checkbox' });
    privCk.checked = true;
    const initCk = U.el('input', { type:'checkbox' });
    initCk.checked = true;
    const body = U.el('div', { style:{padding:'12px'} },
      U.el('div', { style:{fontSize:'12px',color:'var(--fg-dim)',marginBottom:'6px'} }, 'Имя репозитория'),
      nameInp,
      descInp,
      U.el('label', { style:{display:'flex',alignItems:'center',gap:'8px',fontSize:'13px',margin:'8px 0'} }, privCk, ' Приватный'),
      U.el('label', { style:{display:'flex',alignItems:'center',gap:'8px',fontSize:'13px',margin:'8px 0'} }, initCk, ' Инициализировать README (иначе будет пустой)'),
      U.el('div', { style:{fontSize:'11px',color:'var(--fg-dim)',marginTop:'8px'} }, 'После создания репозиторий появится в списке. Пустые репозитории поддерживаются — файлы можно загружать через проводник.')
    );
    const okBtn = U.el('button', { class:'btn primary' }, 'Создать');
    const cancelBtn = U.el('button', { class:'btn ghost' }, 'Отмена');
    okBtn.addEventListener('click', async () => {
      const name = (nameInp.value || '').trim();
      if (!name) { UI.toast('Укажите имя', 'err'); return; }
      okBtn.disabled = true;
      try {
        UI.toast('Создаём ' + name + '…');
        const repo = await createRepo({
          name,
          description: (descInp.value || '').trim(),
          isPrivate: privCk.checked,
          autoInit: initCk.checked
        });
        UI.toast('Создан ' + repo.full_name, 'ok');
        UI.closeSheet();
        // Force reload of repo list, then open it as active repo automatically
        remoteRepos = null; branchesCache = {};
        await renderPanel();
        // Auto-select the new repo
        const [owner, rname] = repo.full_name.split('/');
        draftRepo = {
          fullName: repo.full_name,
          owner, name: rname,
          cloneUrl: repo.clone_url,
          branch: repo.default_branch || 'main',
          defaultBranch: repo.default_branch || 'main',
          private: !!repo.private
        };
        await renderPanel();
      } catch(e) {
        UI.toast('Ошибка: ' + e.message, 'err');
        okBtn.disabled = false;
      }
    });
    cancelBtn.addEventListener('click', () => UI.closeSheet());
    UI.openSheet('Новый репозиторий на GitHub', body, [cancelBtn, okBtn]);
    setTimeout(() => nameInp.focus(), 100);
  }

  // DELETE /repos/{owner}/{repo} — permanently delete on GitHub.
  // Requires the token to carry the `delete_repo` scope; without it GitHub
  // returns 403 with a clear message which we surface verbatim.
  async function deleteRepo(fullName) {
    const token = Store.get().githubToken;
    if (!token) throw new Error('Не авторизован в GitHub');
    const r = await fetch('https://api.github.com/repos/' + fullName, {
      method: 'DELETE',
      headers: { Authorization: 'token ' + token, Accept: 'application/vnd.github+json' }
    });
    if (r.status === 204) return true;
    const txt = await r.text().catch(()=> '');
    let detail = txt;
    try { const j = JSON.parse(txt); detail = j.message || txt; } catch(_){}
    if (r.status === 403) {
      throw new Error('Нет прав на удаление. Токену нужен scope `delete_repo`. Выйдите и войдите заново с расширенным scope, либо создайте PAT с этим правом. Ответ GitHub: ' + detail);
    }
    if (r.status === 404) {
      throw new Error('Репозиторий не найден или недоступен: ' + detail);
    }
    throw new Error('HTTP ' + r.status + ': ' + detail);
  }

  // PATCH /repos/{owner}/{repo} — переименовать репозиторий на GitHub.
  async function renameRepoApi(fullName, newName) {
    const token = Store.get().githubToken;
    if (!token) throw new Error('Не авторизован в GitHub');
    const r = await fetch('https://api.github.com/repos/' + fullName, {
      method: 'PATCH',
      headers: { Authorization: 'token ' + token, Accept: 'application/vnd.github+json', 'Content-Type':'application/json' },
      body: JSON.stringify({ name: newName })
    });
    if (r.ok) return await r.json();
    const txt = await r.text().catch(()=> '');
    let detail = txt; try { detail = (JSON.parse(txt).message) || txt; } catch(_){}
    throw new Error('HTTP ' + r.status + ': ' + detail);
  }

  // POST /repos/{owner}/{repo}/branches/{branch}/rename — переименовать ветку.
  async function renameBranchApi(fullName, branch, newName) {
    const token = Store.get().githubToken;
    if (!token) throw new Error('Не авторизован в GitHub');
    const r = await fetch('https://api.github.com/repos/' + fullName + '/branches/' + encodeURIComponent(branch) + '/rename', {
      method: 'POST',
      headers: { Authorization: 'token ' + token, Accept: 'application/vnd.github+json', 'Content-Type':'application/json' },
      body: JSON.stringify({ new_name: newName })
    });
    if (r.ok) return await r.json();
    const txt = await r.text().catch(()=> '');
    let detail = txt; try { detail = (JSON.parse(txt).message) || txt; } catch(_){}
    throw new Error('HTTP ' + r.status + ': ' + detail);
  }

  // DELETE /repos/{owner}/{repo}/git/refs/heads/{branch} — удалить ветку.
  async function deleteBranchApi(fullName, branch) {
    const token = Store.get().githubToken;
    if (!token) throw new Error('Не авторизован в GitHub');
    const r = await fetch('https://api.github.com/repos/' + fullName + '/git/refs/heads/' + encodeURIComponent(branch), {
      method: 'DELETE',
      headers: { Authorization: 'token ' + token, Accept: 'application/vnd.github+json' }
    });
    if (r.status === 204) return true;
    const txt = await r.text().catch(()=> '');
    let detail = txt; try { detail = (JSON.parse(txt).message) || txt; } catch(_){}
    throw new Error('HTTP ' + r.status + ': ' + detail);
  }

  async function deleteSelectedRepo() {
    if (!draftRepo) { UI.toast('Сначала выберите репозиторий (нажмите на строку — появится зелёная рамка)', 'err'); return; }
    const sel = draftRepo;

    // Pre-flight: token must carry `delete_repo` scope, otherwise GitHub always
    // 403s. Device Flow with the built-in GitHub CLI client_id NEVER grants
    // `delete_repo` — so we detect this here and steer the user to log in with
    // a PAT that has the right scope.
    if (!hasScope('delete_repo')) {
      const proceed = await UI.dialog({
        title: 'Нет права на удаление',
        body: U.el('div', { style:{fontSize:'13px',lineHeight:'1.55'} },
          U.el('div', {}, 'У текущего токена нет scope ', U.el('b',{},'delete_repo'), '. GitHub не даст удалить репозиторий.'),
          U.el('div', { style:{marginTop:'8px'} }, 'Выданные scope: ', U.el('code', {}, (tokenScopes().join(', ') || '—'))),
          U.el('div', { style:{marginTop:'10px',fontSize:'12px',color:'var(--fg-dim)'} },
            'Вход через 6-значный код (Device Flow) использует OAuth-приложение GitHub CLI, которому GitHub не разрешает запрашивать удаление репозиториев. Единственный способ получить это право — войти через Personal Access Token (PAT).')
        ),
        buttons: [
          { text:'Отмена', value:'cancel' },
          { text:'Войти через PAT', primary:true, value:'pat' }
        ]
      });
      if (proceed === 'pat') {
        // Log out current token first so the login flow starts clean, then open PAT login
        Store.set({ githubToken:null, githubUser:null, githubScopes:[] });
        try { App.updateGithubHint(); } catch(_){}
        remoteRepos = null; branchesCache = {}; draftRepo = null;
        renderPanel();
        // Open the PAT-creation flow (opens github.com/settings/tokens/new with delete_repo scope pre-filled)
        setTimeout(() => { try { App.githubLoginBrowserPAT(); } catch(_){} }, 100);
      }
      return;
    }

    // Two-step confirmation: user must retype the repo name.
    const typed = await UI.prompt(
      'Введите полное имя репозитория «' + sel.fullName + '» для подтверждения удаления. ЭТО ДЕЙСТВИЕ НЕОБРАТИМО.',
      { title: 'Удалить репозиторий', placeholder: sel.fullName, okText: 'Удалить' }
    );
    if (!typed || typed.trim() !== sel.fullName) {
      if (typed !== null && typed !== undefined) UI.toast('Имя не совпало — удаление отменено', 'err');
      return;
    }
    try {
      UI.toast('Удаляем ' + sel.fullName + '…');
      await deleteRepo(sel.fullName);
      UI.toast('Удалён ' + sel.fullName, 'ok');
      // If the deleted repo was the currently active one — unmount it.
      const active = Store.get().activeRepo;
      if (active && active.fullName === sel.fullName) {
        if (active.mode === 'remote' && active.virtualRoot) { try { await GH.unmount(active.virtualRoot); } catch(_){} }
        Store.set({ activeRepo: null });
        try { await Explorer.render(); } catch(_){}
      }
      draftRepo = null;
      // Track deleted repo names in-session, so any stale response that still
      // includes the ghost is filtered out. This survives one full round-trip
      // to the GitHub CDN, which is the actual source of the stale entry.
      deletedFullNames.add(sel.fullName);
      // Optimistically drop from the cached list so the UI updates instantly
      if (Array.isArray(remoteRepos)) remoteRepos = remoteRepos.filter(r => r.full_name !== sel.fullName);
      branchesCache = {};
      // Force refetch from GitHub with no-cache; filter out any lingering ghost
      remoteRepos = null;
      await renderPanel();
    } catch(e) {
      UI.toast('Ошибка: ' + e.message, 'err');
    }
  }

  // "OK" — bind selected repo to the agent WITHOUT cloning.
  // Agent sees files through GitHub API at a virtual mount /gh/<owner>/<repo>.
  async function confirmSelectionRemote() {
    if (!draftRepo) { UI.toast('Выберите репозиторий (нажмите на него — появится зелёная рамка)', 'err'); return; }
    const sel = draftRepo;
    const virtualRoot = '/gh/' + sel.owner + '/' + sel.name;
    UI.toast('Открываем '+sel.fullName+'...');
    try {
      // Unmount previous, if any
      const prev = Store.get().activeRepo;
      if (prev && prev.mode === 'remote' && prev.virtualRoot) { try { await GH.unmount(prev.virtualRoot); } catch(_){} }

      await GH.mount({ owner: sel.owner, repo: sel.name, branch: sel.branch });

      Store.set({ activeRepo: {
        mode: 'remote',
        fullName: sel.fullName,
        owner: sel.owner,
        repo: sel.name,
        branch: sel.branch,
        virtualRoot,
        cloneUrl: sel.cloneUrl,
        private: sel.private
      }});
      draftRepo = null;
      UI.toast('Агент подключён к '+sel.fullName+' ('+sel.branch+') — без клонирования', 'ok');
      // Expand tree so the user sees files immediately in Explorer
      try {
        Explorer.expand('/gh');
        Explorer.expand('/gh/'+sel.owner);
        Explorer.expand(virtualRoot);
        await Explorer.render();
      } catch(_){}
      renderPanel();
      App.switchTab('explorer');
    } catch(e) {
      UI.toast('Ошибка: '+e.message, 'err');
    }
  }

  // "Клонировать в проводник" — clone to local IndexedDB FS and mount as local.
  async function confirmSelectionLocal() {
    if (!draftRepo) { UI.toast('Выберите репозиторий (нажмите на него — появится зелёная рамка)', 'err'); return; }
    const sel = draftRepo;
    const localDir = '/' + sel.name;
    try {
      // localDir (e.g. /myrepo) is OUTSIDE the /gh/... virtual root, so FS calls
      // fall through to the real IndexedDB FS unchanged.
      const exists = await FS.exists(localDir + '/.git');
      if (!exists) {
        UI.toast('Клонирование '+sel.fullName+'...');
        if (await FS.exists(localDir)) {
          const ok = await UI.confirm('Папка '+localDir+' существует. Перезаписать содержимым репозитория?', {okText:'Перезаписать'});
          if (!ok) return;
          await FS.rmrf(localDir);
        }
        await clone(sel.cloneUrl, localDir, { ref: sel.branch, singleBranch:true, depth:1 });
      } else {
        try {
          const cur = await currentBranch(localDir);
          if (cur !== sel.branch) {
            try { await git().checkout({ fs, dir: localDir, ref: sel.branch }); }
            catch(_){
              try {
                await git().fetch({ fs, http: httpMod(), dir: localDir, corsProxy: corsProxy(), ref: sel.branch, singleBranch:true, depth:1, onAuth });
                await git().checkout({ fs, dir: localDir, ref: sel.branch });
              } catch(e){}
            }
          }
        } catch(_){}
      }
      const prev = Store.get().activeRepo;
      if (prev && prev.mode === 'remote' && prev.virtualRoot) { try { await GH.unmount(prev.virtualRoot); } catch(_){} }
      Store.set({ activeRepo: {
        mode: 'local',
        fullName: sel.fullName,
        owner: sel.owner,
        repo: sel.name,
        branch: sel.branch,
        localDir,
        virtualRoot: localDir,
        cloneUrl: sel.cloneUrl,
        private: sel.private
      }});
      draftRepo = null;
      UI.toast('Клонировано в '+localDir, 'ok');
      try { await Explorer.render(); } catch(_){}
      renderPanel();
      App.switchTab('explorer');
    } catch(e){
      UI.toast('Ошибка клонирования: '+e.message, 'err');
    }
  }

  async function clone(url, dir, opts={}) {
    // Ensure the target dir exists locally (using the underlying FS, not routed)
    await FS.ensureDirRecursive(dir);
    return git().clone({
      fs, http: httpMod(), dir,
      corsProxy: corsProxy(),
      url,
      ref: opts.ref,
      singleBranch: opts.singleBranch !== false,
      depth: opts.depth || 1,
      onAuth,
    });
  }

  async function repoCard(dir) {
    const card = U.el('div', { class:'repo-card' });
    const st = await status(dir).catch(()=>[]);
    const br = await currentBranch(dir).catch(()=>'?');
    card.appendChild(U.el('div', { class:'name' }, U.basename(dir)));
    card.appendChild(U.el('div', { class:'path' }, dir + '  ·  ' + br + '  ·  изменений: ' + st.length));
    const actions = U.el('div', { class:'actions' },
      btn('Pull', ()=>doPull(dir), 'small'),
      btn('Commit…', ()=>doCommit(dir), 'small primary'),
      btn('Push', ()=>doPush(dir), 'small'),
      btn('Файлы', ()=>showStatus(dir, st), 'small ghost')
    );
    card.appendChild(actions);
    return card;
  }

  function btn(text, fn, cls='') {
    const b = U.el('button', { class:'btn '+cls }, text);
    b.addEventListener('click', fn);
    return b;
  }

  async function doPull(dir) {
    try { UI.toast('Pull...'); await pull(dir); UI.toast('Pull ok','ok'); renderPanel(); }
    catch(e){ UI.toast('Pull error: '+e.message, 'err'); }
  }
  async function doPush(dir) {
    if (!Store.get().githubToken) { UI.toast('Требуется вход в GitHub','err'); return; }
    try { UI.toast('Push...'); await push(dir); UI.toast('Push ok','ok'); }
    catch(e){ UI.toast('Push error: '+e.message, 'err'); }
  }
  async function doCommit(dir) {
    const msg = await UI.prompt('Сообщение коммита', { title:'Commit', placeholder:'update', multiline:true });
    if (!msg) return;
    try {
      await Editor.saveAll();
      await addAll(dir);
      const sha = await commit(dir, msg);
      UI.toast('Committed '+sha.slice(0,7),'ok');
      renderPanel();
    } catch(e){ UI.toast('Commit error: '+e.message, 'err'); }
  }
  async function showStatus(dir, st) {
    const body = U.el('div');
    if (!st.length) body.appendChild(U.el('div', { class:'git-empty' }, 'Нет изменений'));
    for (const it of st) {
      const row = U.el('div', { class:'git-status-item' },
        U.el('span', { class:'st '+it.code }, it.code),
        U.el('span', { class:'path' }, it.path)
      );
      row.addEventListener('click', ()=>{ UI.closeSheet(); Editor.openFile(dir + '/' + it.path).then(()=>App.switchTab('editor')); });
      body.appendChild(row);
    }
    UI.openSheet(U.basename(dir)+' — изменения', body);
  }

  async function logoutGithub() {
    if (!await UI.confirm('Выйти из GitHub?')) return;
    const prev = Store.get().activeRepo;
    if (prev && prev.mode === 'remote' && prev.virtualRoot) { try { await GH.unmount(prev.virtualRoot); } catch(_){} }
    Store.set({ githubToken:null, githubUser:null, githubScopes:[], activeRepo:null });
    remoteRepos = null; branchesCache = {}; draftRepo = null;
    App.updateGithubHint();
    try { await Explorer.render(); } catch(_){}
    renderPanel();
  }

  function bind() {
    U.$('#git-refresh').addEventListener('click', () => {
      remoteRepos = null; branchesCache = {};
      renderPanel();
    });
  }

  return { listRepos, status, log, clone, push, pull, add, remove, addAll, commit, init, currentBranch, renderPanel, bind, githubDeviceFlow, fetchUser };
})();
window.Git = Git;
