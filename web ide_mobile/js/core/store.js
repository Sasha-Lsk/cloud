/* ===== Persistent config store (localStorage) ===== */
const Store = (() => {
  const KEY = 'mide.config.v1';
  const defaults = {
    providers: [], // {id,name,apiKey,baseUrl,model,extraHeaders,requestFormat}
    activeProviderId: null,
    githubToken: null,
    githubUser: null,
    githubScopes: [],
    githubClientId: '', // optional user-provided device flow client id
    // Multi-chat model: each chat = { id, title, createdAt, updatedAt, history:[] }
    chats: [],
    activeChatId: null,
    // Legacy field kept for migration; will be moved into chats[0] on first load.
    aiHistory: [],
    aiSystem: '',
    openTabs: [],   // [{path}]
    activeTab: null,
    lastTab: 'explorer',
    corsProxy: 'https://cors.isomorphic-git.org',
    // Selected GitHub repository the agent is scoped to.
    // Two modes:
    //   { mode:'remote', fullName:'owner/repo', owner, repo, branch, virtualRoot:'/gh/owner/repo' }
    //     — agent sees the repo directly through GitHub API, no local clone.
    //   { mode:'local',  fullName:'owner/repo', localDir:'/repo', branch, virtualRoot:'/repo' }
    //     — agent sees a locally-cloned copy under localDir.
    activeRepo: null,
    // Источник файловой системы для проводника:
    //   'browser' — виртуальная FS в IndexedDB (по умолчанию)
    //   'local'   — реальная папка с устройства (File System Access API,
    //               см. LocalFS). Handle корня хранится отдельно в IDB.
    fsSource: 'browser',
    fsLocalName: '',    // человекочитаемое имя выбранной локальной папки (для UI)
    // Если fsSource='local' и LocalFS.mode='import' — храним ключ namespace, чтобы
    // восстановить снимок при следующем открытии IDE. Для fsa-режима handle хранится
    // отдельно в IndexedDB (mide-localfs-handles), а поле остаётся пустым.
    fsLocalNs: '',
    theme: 'dark',
    settings: {
      streamResponses: false,  // stream or not (many providers differ)
      maxToolCalls: 30,
      notifySound: true,       // play a click when the agent replies
      notifyVolume: 0.9,       // 0..1 громкость уведомления
      // ---- Rate limiting for tool calls (обход провайдерских лимитов) ----
      toolThrottleEnabled: false,     // включить пользовательский throttle
      toolThrottleMs: 1000,           // минимальный интервал между вызовами инструментов (мс)
      // ---- Auto-backoff on provider 429 / rate-limit errors ----
      autoBackoffOn429: true,         // при HTTP 429 / "rate limit" — ждать и повторять запрос к провайдеру
      autoBackoffMaxRetries: 4,       // сколько раз повторить с экспоненциальным ожиданием
      // ---- Watchdog / размер ответа модели ----
      providerTimeoutMs: 120000,      // максимум времени ожидания ответа провайдера (мс). При истечении запрос обрывается.
      providerMaxTokens: 8192,        // max_tokens в теле запроса к провайдеру. Регулирует размер ответа модели.
    }
  };
  let state = null;
  function load() {
    try { state = { ...defaults, ...(JSON.parse(localStorage.getItem(KEY)) || {}) }; }
    catch(e){ state = { ...defaults }; }
    state.settings = { ...defaults.settings, ...(state.settings||{}) };
    if (!Array.isArray(state.chats)) state.chats = [];
    // Готовый профиль OPENROUTER: seed'ится один раз (флаг _seededOpenRouter),
    // после чего пользователь может свободно редактировать/удалить его —
    // повторной автоподстановки не будет.
    if (!Array.isArray(state.providers)) state.providers = [];
    if (!state._seededOpenRouter) {
      state.providers.push({
        id: 'p_openrouter_' + Math.random().toString(36).slice(2, 8),
        name: 'OPENROUTER',
        baseUrl: 'https://openrouter.ai/api/v1/chat/completions',
        apiKey: 'sk-or-v1-REPLACE_WITH_YOUR_OPENROUTER_KEY',
        model: 'openrouter/free',
        extraHeaders: '',
        rawUrl: false,
        corsProxy: ''
      });
      if (!state.activeProviderId) state.activeProviderId = state.providers[state.providers.length - 1].id;
      state._seededOpenRouter = true;
      try { localStorage.setItem(KEY, JSON.stringify(state)); } catch(_){}
    }
    // Migration: if we have legacy aiHistory but no chats, create initial chat
    if (state.chats.length === 0) {
      const first = {
        id: 'chat_' + Date.now().toString(36),
        title: 'Новый чат',
        createdAt: Date.now(),
        updatedAt: Date.now(),
        history: Array.isArray(state.aiHistory) ? state.aiHistory : []
      };
      state.chats.push(first);
      state.activeChatId = first.id;
      state.aiHistory = []; // clear legacy — history now lives in chat
    } else if (!state.activeChatId || !state.chats.find(c => c.id === state.activeChatId)) {
      state.activeChatId = state.chats[0].id;
    }
    return state;
  }
  function save() { localStorage.setItem(KEY, JSON.stringify(state)); }
  function get() { if (!state) load(); return state; }
  function set(patch) { Object.assign(get(), patch); save(); }
  function reset() { state = { ...defaults }; save(); load(); }

  // ---- Chat helpers ----
  function activeChat() {
    const s = get();
    let c = s.chats.find(x => x.id === s.activeChatId);
    if (!c) {
      // Repair: create a new one
      c = { id:'chat_'+Date.now().toString(36), title:'Новый чат', createdAt:Date.now(), updatedAt:Date.now(), history:[] };
      s.chats.push(c); s.activeChatId = c.id; save();
    }
    return c;
  }
  function newChat(title) {
    const s = get();
    const c = {
      id: 'chat_' + Date.now().toString(36) + Math.random().toString(36).slice(2,6),
      title: title || 'Новый чат',
      createdAt: Date.now(), updatedAt: Date.now(),
      history: []
    };
    s.chats.unshift(c);
    s.activeChatId = c.id;
    save();
    return c;
  }
  function switchChat(id) {
    const s = get();
    if (s.chats.find(c => c.id === id)) { s.activeChatId = id; save(); return true; }
    return false;
  }
  function deleteChat(id) {
    const s = get();
    const idx = s.chats.findIndex(c => c.id === id);
    if (idx < 0) return false;
    s.chats.splice(idx, 1);
    if (s.chats.length === 0) {
      const c = { id:'chat_'+Date.now().toString(36), title:'Новый чат', createdAt:Date.now(), updatedAt:Date.now(), history:[] };
      s.chats.push(c); s.activeChatId = c.id;
    } else if (s.activeChatId === id) {
      s.activeChatId = s.chats[0].id;
    }
    save();
    return true;
  }
  function renameChat(id, title) {
    const s = get();
    const c = s.chats.find(x => x.id === id);
    if (!c) return false;
    c.title = title || c.title;
    c.updatedAt = Date.now();
    save();
    return true;
  }
  // Auto-generate a chat title from the first user message if still default.
  function autoTitleFromFirstMsg(chat) {
    if (!chat || chat.title !== 'Новый чат') return;
    const firstUser = (chat.history || []).find(m => m.role === 'user' && m.content);
    if (!firstUser) return;
    const t = String(firstUser.content).replace(/\s+/g,' ').trim().slice(0, 40);
    if (t) { chat.title = t + (firstUser.content.length > 40 ? '…' : ''); chat.updatedAt = Date.now(); save(); }
  }

  load();
  return { get, set, save, reset, activeChat, newChat, switchChat, deleteChat, renameChat, autoTitleFromFirstMsg };
})();
window.Store = Store;
