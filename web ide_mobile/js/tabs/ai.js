/* ===== AI agent: universal provider client with tool-calls via prompt + vision ===== */
const AI = (() => {
  let running = false;
  let abortCtrl = null;
  // ---- Per-chat run binding ----
  // Каждый запуск агента ПРИВЯЗАН к конкретному чату, в котором он был начат
  // (_runChatId). Все записи в историю во время работы идут ИМЕННО в этот чат,
  // а не в Store.activeChat(). Иначе при создании/переключении на новый чат
  // работающий агент начинал писать сообщения в новый чат (они «протекали»),
  // а кнопка оставалась ⏹. Теперь новый чат — это отдельная сессия.
  let _runChatId = null;
  // Возвращает объект чата, в который должен писать ТЕКУЩИЙ запуск. Если запуск
  // не привязан (нет _runChatId) — падаем на активный чат (обычный случай для
  // прямых пользовательских действий вне цикла агента).
  function runChat() {
    if (_runChatId) {
      const s = Store.get();
      const c = s.chats.find(x => x.id === _runChatId);
      if (c) return c;
    }
    return Store.activeChat();
  }
  // Отображается ли сейчас (в UI) тот чат, в котором идёт запуск. Если true —
  // можно рисовать сообщения/статус в DOM; если false — только писать в историю.
  function isRunChatVisible() {
    return !_runChatId || _runChatId === Store.get().activeChatId;
  }
  // Vision attachments queued for the NEXT provider request (populated by tools like fs_read_image)
  const _pendingAttachments = [];

  // ---- Inline media store ----
  // Когда http_fetch скачивает картинку, data_url может быть 10-100+ KB. Класть
  // такую строку в контекст модели — глупо (ей всё равно не нужно видеть base64).
  // Вместо этого сохраняем data_url под коротким ID здесь, а модели показываем
  // ссылку chatimg:<id>. Когда сообщение ассистента рендерится в чат, мы перед
  // marked.parse заменяем chatimg:<id> обратно на настоящий data URL. Так картинка
  // отображается в чате, а модель тратит минимум токенов.
  const _mediaStore = new Map(); // id -> { dataUrl, kind, mime, name }
  let _mediaCounter = 0;
  function kindFromMime(mime) {
    const m = String(mime || '').toLowerCase();
    if (m.startsWith('image/')) return 'image';
    if (m.startsWith('video/')) return 'video';
    if (m.startsWith('audio/')) return 'audio';
    if (m === 'application/pdf') return 'pdf';
    return 'file';
  }
  function registerMedia(dataUrl, extra) {
    if (!dataUrl || typeof dataUrl !== 'string') return null;
    _mediaCounter++;
    const id = 'm' + Date.now().toString(36).slice(-4) + _mediaCounter.toString(36);
    // Extract mime from data URL header
    let mime = '';
    const mm = dataUrl.match(/^data:([^;,]+)/i);
    if (mm) mime = mm[1];
    const kind = (extra && extra.kind) || kindFromMime(mime);
    _mediaStore.set(id, {
      dataUrl,
      kind,
      mime,
      name: (extra && extra.name) || ''
    });
    return id;
  }
  // Escape for HTML attribute values
  function escAttr(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  }
  // Build an HTML tag for a media entry (video/audio/pdf/file).
  function mediaEntryHtml(entry) {
    if (!entry) return '';
    const url = entry.dataUrl;
    const name = escAttr(entry.name || '');
    const mime = escAttr(entry.mime || '');
    if (entry.kind === 'video') {
      return `<video controls playsinline preload="metadata" class="chat-media chat-video" src="${escAttr(url)}"${mime ? ` type="${mime}"` : ''}></video>`;
    }
    if (entry.kind === 'audio') {
      return `<audio controls preload="metadata" class="chat-media chat-audio" src="${escAttr(url)}"></audio>`;
    }
    if (entry.kind === 'pdf') {
      return `<iframe class="chat-media chat-pdf" src="${escAttr(url)}" title="${name || 'PDF'}"></iframe>`;
    }
    // Fallback for other files — link
    return `<a class="chat-media chat-file" href="${escAttr(url)}" download="${name}">${name || 'файл'}</a>`;
  }
  function resolveMediaRefs(text) {
    if (!text || typeof text !== 'string') return text;
    // 1) Полный markdown-паттерн ![alt](chatimg:xxx) — если это НЕ картинка,
    //    подменяем ВСЮ ссылку на raw HTML тег (иначе marked не распознаёт
    //    URL со скобками/кавычками внутри). Для картинок оставляем markdown,
    //    подменяя только chatimg:xxx на data: URL — marked превратит в <img>.
    text = text.replace(/!\[([^\]]*)\]\(\s*chatimg:([a-z0-9]+)\s*\)/gi, (m, alt, id) => {
      const entry = _mediaStore.get(id);
      if (!entry) return m;
      if (entry.kind === 'image') return '![' + alt + '](' + entry.dataUrl + ')';
      return mediaEntryHtml(entry);
    });
    // 2) Голые chatimg:xxx без markdown-обёртки — тоже поддержим (агент мог забыть скобки)
    text = text.replace(/chatimg:([a-z0-9]+)/gi, (m, id) => {
      const entry = _mediaStore.get(id);
      if (!entry) return m;
      if (entry.kind === 'image') return entry.dataUrl;
      return mediaEntryHtml(entry);
    });
    return text;
  }
  // Заменяет data_url в результате инструмента на короткую ссылку, добавляя
  // hint модели. Возвращает новый объект (не мутирует оригинал).
  //
  // ВАЖНО (vision-first): если это картинка (image/*), то помимо media_ref мы
  // ТАКЖЕ автоматически кладём её в _pendingAttachments — она уйдёт в vision
  // модели с СЛЕДУЮЩИМ запросом. Это гарантирует, что модель РЕАЛЬНО «видит»
  // картинку глазами (мультимодальный анализ), а не гадает по имени файла.
  // Работает для archive_read_entry, http_fetch, image_search и любого
  // другого инструмента, вернувшего data_url картинки.
  function processToolResultForModel(res) {
    if (!res || !res.ok || !res.result || typeof res.result !== 'object') return res;
    const r = res.result;
    if (r.data_url && typeof r.data_url === 'string' && r.data_url.startsWith('data:')) {
      const id = registerMedia(r.data_url, { name: r.name || r.path || '' });
      const entry = _mediaStore.get(id);
      const clone = Object.assign({}, r);
      delete clone.data_url;
      clone.media_ref = 'chatimg:' + id;
      const kind = entry ? entry.kind : 'file';
      const kindRu = ({image:'картинка', video:'видео', audio:'аудио', pdf:'PDF', file:'файл'})[kind] || 'файл';
      // Auto-attach IMAGES to next vision request. This is the vision-first rule:
      // не полагаться на имя файла — сначала пусть модель увидит пиксели.
      let visionNote = '';
      if (kind === 'image') {
        // Картинку НЕ кладём в одноразовый _pendingAttachments — вместо этого она
        // сохраняется в само tool-сообщение (attachToolImageToMsg → поле _image),
        // персистит в истории и уходит модели на КАЖДОМ шаге (см. buildLLMMessages).
        // Так картинка не теряется после перезагрузки и работает для vision- и
        // текстовых (авто-описание) моделей одинаково.
        clone._vision_attached = true;
        visionNote = ' ВАЖНО: эта картинка автоматически прикреплена к твоему следующему запросу — на следующем шаге ты получишь её содержимое (vision-модель увидит пиксели; текстовая модель — авто-описание картинки). Опиши что на ней ТОЛЬКО после того, как реально получишь её содержимое в следующем сообщении, НЕ ГАДАЙ по имени файла или пути.';
      }
      clone.hint = 'Медиа-файл (' + kindRu + ') сохранён. Чтобы показать его пользователю в чате — вставь в свой следующий ответ ровно строку: ![](chatimg:' + id + '). Не пиши base64, не пиши сам URL — только эту markdown-ссылку. Она автоматически превратится в <img>/<video>/<audio>/<iframe> в зависимости от типа.' + visionNote;
      return { ok:true, result: clone };
    }
    return res;
  }

  // Сериализует результат инструмента для модели с УМНЫМ лимитом длины.
  //
  // РАНЬШЕ здесь был жёсткий cap в 4000 символов на ЛЮБОЙ результат. Это ломало
  // чтение файлов: fs_read/archive_read_entry сами уже ограничивают объём
  // (320KB / 4000 строк, для больших файлов — digest), но затем весь JSON-ответ
  // резался до 4000 символов (~70 строк smali/js). Модель видела только начало
  // файла, не находила искомый код (напр. проверку лицензии в глубине .smali)
  // и снова и снова перечитывала одни и те же куски — визуально «застревала».
  //
  // Теперь лимит зависит от инструмента: инструменты, чей смысл — вернуть
  // СОДЕРЖИМОЕ (уже само-ограниченное на уровне тулзы), получают большой бюджет;
  // остальные — прежний компактный лимит. Так поведение для не-контентных тулз
  // не меняется, а чтение файлов больше не зацикливается.
  const CONTENT_TOOLS = new Set([
    'fs_read', 'archive_read_entry', 'fs_search', 'fs_list', 'http_fetch', 'web_search'
  ]);
  // 320KB — совпадает с MAX_INLINE_BYTES у fs_read: тула УЖЕ не отдаёт больше,
  // поэтому этот cap для fs_read фактически не режет валидный вывод, а лишь
  // страхует от аномально раздутого JSON. Прочие тулзы — компактные 4000.
  const CONTENT_RESULT_CAP = 340 * 1024;
  const DEFAULT_RESULT_CAP = 4000;
  function serializeToolResultForModel(toolName, res) {
    const forModel = processToolResultForModel(res);
    const summary = JSON.stringify(forModel.ok ? forModel.result : { error: forModel.error }, null, 2);
    const cap = CONTENT_TOOLS.has(toolName) ? CONTENT_RESULT_CAP : DEFAULT_RESULT_CAP;
    if (summary.length <= cap) return summary;
    return summary.slice(0, cap) + '\n...[truncated ' + (summary.length - cap) + ' симв.]';
  }
  // Если инструмент вернул КАРТИНКУ (data_url image/*) — сохраняем её dataUrl прямо
  // в tool-сообщение (поле _image), чтобы она ПЕРСИСТИЛА в истории чата (localStorage)
  // и на КАЖДОМ последующем шаге снова уходила модели (vision — как пиксели, а для
  // текстовых моделей — как авто-описание). Раньше картинки из инструментов жили
  // только в in-memory _pendingAttachments и терялись после перезагрузки —
  // «агент не видел давних картинок». Порт персистентной схемы из android-версии.
  function attachToolImageToMsg(toolMsg, res) {
    try {
      if (!res || !res.ok || !res.result) return;
      const r = res.result;
      if (r.data_url && typeof r.data_url === 'string' && r.data_url.startsWith('data:')) {
        const parts = dataUrlToParts(r.data_url);
        const mime = (parts && parts.mime) || r.mime || '';
        if (/^image\//i.test(mime) || U.mediaKind(r.entry || r.path || r.name || r.url || '') === 'image') {
          toolMsg._image = {
            dataUrl: r.data_url,
            name: r.entry ? ((r.archive || 'архив') + ' → ' + r.entry) : (r.path || r.name || r.url || 'image'),
            mime: mime || 'image/png'
          };
        }
      }
    } catch(_) {}
  }
  // Inbox queue: messages sent by the user while the agent is running. They are
  // interjected into the conversation at the NEXT boundary (between LLM turns
  // and between tool calls) without stopping the current task.
  const inbox = [];
  let currentStatus = null; // reference to active status bubble (for updates from outside send loop)

  // ---- URL normalization: works with/without trailing slash and with/without "chat/completions" ----
  function normalizeBaseUrl(u) {
    if (!u) return '';
    return String(u).trim().replace(/\/+$/, '');
  }
  // Detects if user url looks like OpenAI-compat chat endpoint
  function isCompletionsUrl(u) {
    return /\/(chat\/completions|completions|messages|generateContent)(\?|$)/.test(u);
  }
  function isAnthropic(u) {
    return /anthropic\.com|\/v1\/messages(\?|$)/.test(u);
  }
  function isGoogle(u) {
    return /generativelanguage\.googleapis\.com|generateContent/.test(u);
  }

  // ---- CORS-proxy wrapper ----
  // Many providers (Kilo, OpenAI, Anthropic, ...) do NOT send Access-Control-Allow-Origin,
  // so direct browser fetch fails with "Failed to fetch". Solution: route through a CORS proxy.
  //
  // If user provided a proxy → use only that.
  // If user did NOT provide a proxy → try direct first, then a built-in list of free public
  // proxies (verified 2026-07). This means the app works out-of-the-box for any provider.
  //
  // NOTE: public proxies see the API key in transit. Displayed with a warning in UI.
  const DEFAULT_PROXIES = [
    'https://proxy.cors.sh/{url_raw}',            // {url_raw} = raw target URL after prefix
    'https://test.cors.workers.dev/?{url_raw}',
    'https://corsproxy.io/?{url_enc}',            // {url_enc} = encoded target URL
  ];
  // Build the actual proxy URL from a template.
  // Templates: '{url_raw}' — raw target url; '{url_enc}' — encoded; else use heuristics.
  function buildProxyUrl(template, targetUrl) {
    if (!template) return targetUrl;
    const t = String(template).trim();
    if (!t) return targetUrl;
    if (t.includes('{url_raw}')) return t.replace('{url_raw}', targetUrl);
    if (t.includes('{url_enc}')) return t.replace('{url_enc}', encodeURIComponent(targetUrl));
    if (t.includes('{url}'))     return t.replace('{url}', encodeURIComponent(targetUrl));
    // Heuristic fallback: prefix ending with ? or = → encoded; plain path → raw suffix
    if (/[?&=]$/.test(t)) return t + encodeURIComponent(targetUrl);
    return t.replace(/\/+$/, '') + '/' + targetUrl.replace(/^https?:\/\//, '');
  }
  // Return array of proxy templates to try for the given user-provided proxy.
  // '' or null → default cascade (direct + built-in list).
  // Non-empty → only that one.
  function proxyChain(userProxy) {
    const u = (userProxy || '').trim();
    if (u) return [u]; // trust the user
    // Direct attempt first (empty template = no wrapping), then built-ins
    return ['', ...DEFAULT_PROXIES];
  }

  // Try several URL variants until one that returns 200/JSON succeeds is used.
  // Returns array of candidate endpoint URLs to try (in order).
  //
  // Rules:
  //   1. If provider.rawUrl === true → ТОЛЬКО URL как есть, никаких добавок.
  //   2. Если URL уже похож на completions-endpoint (заканчивается на
  //      /chat/completions, /completions, /messages, /generateContent) —
  //      использовать как есть.
  //   3. Иначе строим кандидатов УМНО, не создавая дублей вида /v1/v1/:
  //      — если base уже заканчивается на /v1 (или /v3, /openai/v1 и т.п.),
  //        добавляем ТОЛЬКО /chat/completions (а не /v1/chat/completions);
  //      — иначе первым пробуем /v1/chat/completions, затем /chat/completions;
  //      — сам base «как есть» пробуем последним (обычно это не рабочий endpoint,
  //        но вдруг у провайдера нестандартный путь).
  function candidateEndpoints(base, opts) {
    const b = normalizeBaseUrl(base);
    const rawOnly = !!(opts && opts.rawUrl);
    if (rawOnly) return [b];
    if (isCompletionsUrl(b)) return [b];

    const cands = [];
    // Уже указана версия API в конце пути (…/v1, …/v2, …/v1beta, …/openai/v1 и т.д.)?
    const endsWithVersion = /\/v\d+[a-z]*$/i.test(b);
    if (endsWithVersion) {
      // НЕ добавляем ещё один /v1 — иначе получится /v1/v1/chat/completions.
      cands.push(b + '/chat/completions');
      cands.push(b + '/completions');
    } else {
      // Версии в пути нет → пробуем со стандартным /v1, затем без него.
      cands.push(b + '/v1/chat/completions');
      cands.push(b + '/chat/completions');
    }
    // Сам base «как есть» — как крайний резерв (нестандартные шлюзы).
    cands.push(b);
    return Array.from(new Set(cands));
  }

  // ---- Attach pending vision items to the last user message per format ----
  function popAttachments() {
    if (!_pendingAttachments.length) return [];
    const items = _pendingAttachments.slice();
    _pendingAttachments.length = 0;
    return items;
  }
  function dataUrlToParts(du) {
    // "data:<mime>;base64,<b64>" → { mime, b64 }
    const m = /^data:([^;,]+);base64,(.+)$/.exec(du);
    if (!m) return null;
    return { mime: m[1], b64: m[2] };
  }

  // ---- Настройки размера ответа модели ----
  // max_tokens задаётся пользователем в UI (Настройки → «Макс. токенов в ответе»).
  // Слишком маленькое значение → модель обрезает fs_write на большом файле и
  // клиент думает, что она «закончила» без tool-блока → повисание.
  // Слишком большое → некоторые провайдеры отклонят запрос.
  function currentMaxTokens() {
    const s = (Store.get() && Store.get().settings) || {};
    const v = +s.providerMaxTokens;
    if (!isFinite(v) || v <= 0) return 8192;
    return Math.max(256, Math.min(32768, v));
  }
  // Таймаут ожидания ответа от провайдера. По умолчанию 120с — достаточно
  // для длинных ответов reasoning-моделей, но не оставляет пользователя навсегда
  // с «Waiting…» если провайдер молчит.
  function currentProviderTimeoutMs() {
    const s = (Store.get() && Store.get().settings) || {};
    const v = +s.providerTimeoutMs;
    if (!isFinite(v) || v <= 0) return 120000;
    return Math.max(15000, Math.min(900000, v));
  }

  // ---- Build request bodies for different formats ----
  // Каждое сообщение может нести поле `_images: [{dataUrl, name}]` — картинки,
  // «встроенные» в это конкретное user-сообщение (то, как они пришли из чата).
  // Это позволяет модели видеть картинку из первого сообщения и на 5-м раунде
  // диалога, а не только один раз при первой отправке.
  // Помимо этого, `attachments` — картинки, поступившие «сверх диалога» из
  // инструментов (например `fs_read_image`); они прилепляются к последнему
  // user-сообщению текущего запроса (как раньше).
  //
  // opts: { useNativeTools:boolean } — если true, в body добавляется поле
  // `tools` (OpenAI function-calling), а ассистентские сообщения с _toolCalls
  // и tool-сообщения с _toolCallId разворачиваются в нативный формат:
  //   assistant.tool_calls = [{id, type:'function', function:{name, arguments}}]
  //   tool: {role:'tool', tool_call_id, content}
  // Это критично для Cohere/Command-R/некоторых новых моделей (напр.
  // cohere/north-mini-code:free), которые в markdown-режиме вечно «обещают
  // выполнить» и ничего не делают, но в native tools работают нормально
  // (как в расширении rutex, которое как раз использует native tools API).
  function buildOpenAIBody(model, messages, attachments, opts) {
    opts = opts || {};
    const useTools = !!opts.useNativeTools;
    let msgs = [];
    for (const m of messages) {
      // Native assistant с tool_calls — обёртка для OpenAI формата.
      if (useTools && m.role === 'assistant' && Array.isArray(m._toolCalls) && m._toolCalls.length) {
        msgs.push({
          role: 'assistant',
          // ВАЖНО: Mistral отвергает assistant, у которого одновременно есть content И
          // tool_calls ("must have either content or tool_calls, but not both", code 3240).
          // Поэтому у tool_calls-сообщения content ВСЕГДА null — сопутствующий reasoning
          // модели в native-режиме не критичен и не должен ломать запрос.
          content: null,
          tool_calls: m._toolCalls.map(tc => ({
            id: tc.id,
            type: 'function',
            function: { name: tc.name, arguments: typeof tc.args === 'string' ? tc.args : JSON.stringify(tc.args || {}) }
          }))
        });
        continue;
      }
      // Native tool-result сообщение.
      if (useTools && m.role === 'tool' && m._toolCallId) {
        msgs.push({ role:'tool', tool_call_id: m._toolCallId, content: m.content || '' });
        continue;
      }
      // Если native выключен — сообщения role:'tool' сюда доходить не должны
      // (buildLLMMessages конвертит их в user-сообщения). Но на всякий случай:
      if (!useTools && m.role === 'tool') {
        msgs.push({ role:'user', content: 'Результат инструмента ' + (m.name||'') + ':\n' + (m.content||'') });
        continue;
      }
      if (m.role === 'user' && Array.isArray(m._images) && m._images.length) {
        const parts = [];
        if (m.content) parts.push({ type:'text', text: m.content });
        for (const im of m._images) parts.push({ type:'image_url', image_url:{ url: im.dataUrl } });
        msgs.push({ role:'user', content: parts });
        continue;
      }
      // Общий случай. ВАЖНО: некоторые провайдеры (Mistral) отвергают запрос с
      // ошибкой "Assistant message must have either content or tool_calls, but
      // not none", если assistant-сообщение имеет пустой content и не несёт
      // tool_calls. Такое бывает, когда:
      //   • assistant с native _toolCalls попал сюда после отката useTools→false
      //     (tool_calls отброшены, а content был пустой);
      //   • модель вернула tool-вызов в тексте, а видимый текст оказался пустым.
      // Поэтому для assistant с пустым content подставляем безопасную заглушку.
      if (m.role === 'assistant') {
        let c = m.content;
        if (c == null || String(c).trim() === '') {
          // Если были _toolCalls, но native выключен — опишем их текстом,
          // иначе просто нейтральная заглушка, чтобы сообщение было валидным.
          if (Array.isArray(m._toolCalls) && m._toolCalls.length) {
            c = m._toolCalls.map(tc => '```tool\n' + JSON.stringify({ tool: tc.name, args: tc.args || {} }) + '\n```').join('\n');
          } else {
            c = '(продолжаю)';
          }
        }
        msgs.push({ role:'assistant', content: c });
        continue;
      }
      msgs.push({ role: m.role, content: m.content });
    }
    if (attachments && attachments.length) {
      let idx = -1;
      for (let i = msgs.length - 1; i >= 0; i--) if (msgs[i].role === 'user') { idx = i; break; }
      if (idx < 0) { msgs.push({ role:'user', content: '' }); idx = msgs.length - 1; }
      const orig = msgs[idx];
      const parts = Array.isArray(orig.content) ? orig.content.slice() : (orig.content ? [{ type:'text', text: orig.content }] : []);
      for (const a of attachments) parts.push({ type:'image_url', image_url:{ url: a.dataUrl } });
      msgs[idx] = { role:'user', content: parts };
    }
    // ЗАЩИТА ОТ HTTP 400 у Mistral (и совместимых), часть 0: формат tool_call id.
    // Mistral требует, чтобы id вызова был РОВНО 9 символов из [a-zA-Z0-9]
    //   "Tool call id was ... but must be a-z, A-Z, 0-9, with a length of 9" (code 3280).
    // Наши локально сгенерированные id ("call_xxxx") и id других провайдеров этому не
    // соответствуют. Нормализуем КАЖДЫЙ id в детерминированный 9-символьный alnum и
    // синхронно правим tool_call_id у tool-ответов (соответствие assistant↔tool должно
    // сохраниться). Для остальных OpenAI-провайдеров такой формат тоже валиден.
    (function normalizeToolCallIds(){
      const map = {};
      const gen = (src) => {
        // Детерминированный хэш → 9 символов [a-zA-Z0-9] (стабилен в пределах запроса).
        let h = 0; const s = String(src || '');
        for (let i = 0; i < s.length; i++) { h = (h * 31 + s.charCodeAt(i)) >>> 0; }
        const A = 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789';
        let out = '';
        for (let i = 0; i < 9; i++) { out += A[h % 62]; h = (h * 131 + 7) >>> 0; }
        return out;
      };
      const valid = (id) => typeof id === 'string' && /^[a-zA-Z0-9]{9}$/.test(id);
      const remap = (id) => { if (valid(id)) return id; if (!(id in map)) map[id] = gen(id + ':' + Object.keys(map).length); return map[id]; };
      for (const m of msgs) {
        if (m.role === 'assistant' && Array.isArray(m.tool_calls)) {
          for (const tc of m.tool_calls) if (tc && tc.id != null) tc.id = remap(tc.id);
        } else if (m.role === 'tool' && m.tool_call_id != null) {
          m.tool_call_id = remap(m.tool_call_id);
        }
      }
    })();
    // ЗАЩИТА ОТ HTTP 400 у Mistral (и совместимых), часть 1: строгий порядок ролей.
    // Mistral отвергает ДВА assistant-сообщения подряд:
    //   "Unexpected role 'assistant' after role 'assistant'" (invalid_request_message_order).
    // ВАЖНО: Mistral ПРОПУСКАЕТ system-сообщения при проверке чередования ролей,
    // поэтому «assistant, system, assistant» он видит как «assistant, assistant».
    // Это штатно возникает, когда модель НЕ вызвала инструмент (или вызов вернулся
    // текстом), agent-loop вставил system-nudge и модель ответила ещё одним assistant.
    // Схлопываем assistant-turn'ы, разделённые ТОЛЬКО system-сообщениями, в один
    // (склеивая content и перенося system-заметки перед ним). Assistant с tool_calls
    // не трогаем (строгая привязка к tool-ответам). Аналогично — подряд идущие user.
    const merged = [];
    const hasTools = x => x && x.role === 'assistant' && Array.isArray(x.tool_calls) && x.tool_calls.length;
    // Последнее НЕ-system сообщение в merged (для проверки смежности «сквозь system»).
    const lastNonSystem = () => { for (let i = merged.length - 1; i >= 0; i--) if (merged[i].role !== 'system') return merged[i]; return null; };
    // Переносит system-заметки, накопившиеся в конце merged, во временный буфер,
    // чтобы объединить anchor с новым сообщением, и возвращает их обратно после.
    const takeTrailingNotes = () => { const notes = []; while (merged.length && merged[merged.length - 1].role === 'system') notes.unshift(merged.pop()); return notes; };
    for (const m of msgs) {
      const anchor = lastNonSystem();
      const sameRole = anchor && anchor.role === m.role;
      if (sameRole && (m.role === 'assistant' || m.role === 'user')
          && !hasTools(m) && !hasTools(anchor)
          && typeof anchor.content === 'string' && typeof m.content === 'string') {
        // Оба — обычные assistant/user без tool_calls: склеиваем content в anchor.
        const notes = takeTrailingNotes();
        anchor.content = (anchor.content + (m.content ? '\n\n' + m.content : '')).trim() || '(продолжаю)';
        for (const n of notes) merged.push(n);
      } else if (sameRole && m.role === 'assistant' && hasTools(m) && !hasTools(anchor)
                 && typeof anchor.content === 'string') {
        // anchor — assistant-ТЕКСТ без tool_calls, а m — assistant С tool_calls
        // (напр. модель сначала «поболтала», потом вызвала инструмент). Два assistant
        // подряд для Mistral недопустимы. При этом Mistral ЗАПРЕЩАЕТ и assistant с
        // content+tool_calls одновременно — значит текст anchor в tool_calls-сообщение
        // перенести нельзя. Просто УДАЛЯЕМ предшествующий assistant-текст (обычно это
        // «сейчас прочитаю файл» — не несёт критичного контекста), оставляя чистое
        // tool_calls-сообщение (content=null). Его привязка к tool-ответам сохраняется.
        const notes = takeTrailingNotes();
        merged.pop(); // убираем anchor (assistant-текст)
        m.content = null;
        for (const n of notes) merged.push(n);
        merged.push(m);
      } else {
        merged.push(m);
      }
    }
    msgs = merged;
    // Часть 2: ПОСЛЕДНЕЕ сообщение должно быть user или tool (либо assistant с prefix:true):
    //   "Expected last role User or Tool (or Assistant with prefix True) for serving
    //    but got assistant" (code 3230).
    // Хвост-assistant/system возникает штатно: assistant-ответ без валидного tool-вызова,
    // за которым buildLLMMessages добавил system-заметку (правила пользователя / nudge).
    // Другие провайдеры это прощают, Mistral — нет. Дописываем безопасное user-продолжение,
    // если хвост не user/tool. Assistant с tool_calls НЕ трогаем (за ним идут tool-ответы).
    if (msgs.length) {
      const last = msgs[msgs.length - 1];
      const asstWithTools = last.role === 'assistant' && Array.isArray(last.tool_calls) && last.tool_calls.length;
      if (last.role !== 'user' && last.role !== 'tool' && !asstWithTools) {
        msgs.push({ role:'user', content: 'Продолжай.' });
      }
    }
    // Семплинг. По умолчанию — умеренные штрафы за повтор. Если opts.antiLoop=true
    // (авто-retry после залипания) — усиливаем: T=1.0, штрафы близко к максимуму.
    // Это ломает детерминистичный путь, в котором модель попала в цикл.
    const antiLoop = !!opts.antiLoop;
    const body = {
      model, messages: msgs,
      temperature: antiLoop ? 1.0 : 0.7,
      stream: false,
      max_tokens: currentMaxTokens(),
    };
    if (useTools) {
      const spec = buildOpenAIToolsSpec();
      if (spec.length) {
        body.tools = spec;
        body.tool_choice = 'auto';
        body.parallel_tool_calls = true;
      }
    }
    // ВАЖНО (native tools): когда мы отдаём модели native function-calling
    // (useTools=true) — НЕ добавляем штрафы за повтор. Расширение rutex, где эта
    // же модель СРАЗУ вызывает инструменты, шлёт чистый native-запрос:
    // model+messages+tools+tool_choice:auto+parallel_tool_calls+temperature —
    // без penalty/top_p. Мы повторяем это поведение 1-в-1.
    //
    // top_p/penalty применяем ТОЛЬКО там, где они полезны и не ломают first-turn:
    //   • LEGACY markdown-режим (useTools=false);
    //   • antiLoop=true — авто-retry после залипания (усиленные штрафы ломают цикл).
    // Это стандартные OpenAI-поля, их принимают почти все провайдеры.
    // opts.minimalBody=true — «безопасный минимум» после того, как провайдер
    // отверг какое-то доп. поле (extra_forbidden). Тогда не добавляем ни
    // top_p, ни penalty — только базовые поля + (опционально) tools.
    if (!opts.minimalBody && (!useTools || antiLoop)) {
      body.top_p = antiLoop ? 0.95 : 1.0;
      body.frequency_penalty = antiLoop ? 1.5 : 0.5;
      body.presence_penalty = antiLoop ? 1.0 : 0.3;
    }
    // ⚠ Поле `reasoning:{effort}` НАМЕРЕННО НЕ добавляем. Это нестандартное
    // расширение OpenRouter: многие OpenAI-совместимые провайдеры отвечают
    // 422 "extra_forbidden: body.reasoning". Кроме того, именно оно провоцировало
    // reasoning-модели (cohere/north-mini-code) выдавать план текстом без вызова
    // инструмента. Убираем полностью — надёжнее и совместимее.
    return body;
  }

  // Преобразует Tools.spec() (наш внутренний формат с полями {name, description,
  // params: {arg: "описание строкой"}}) в валидную OpenAI-схему function-tool.
  // Тип аргумента угадываем по описанию (упоминание "number/int/count/bool/array"),
  // по умолчанию — string. Если в описании есть "optional" — параметр НЕ required.
  // Схему намеренно делаем permissive: additionalProperties:true, чтобы модель
  // могла присылать чуть больше полей (напр. offset у fs_read), которые тула ждёт.
  function buildOpenAIToolsSpec() {
    if (!window.Tools || typeof Tools.spec !== 'function') return [];
    const spec = Tools.spec() || [];
    const out = [];
    for (const t of spec) {
      const params = t.params || {};
      const properties = {};
      const required = [];
      for (const key of Object.keys(params)) {
        const desc = String(params[key] || '');
        const dLow = desc.toLowerCase();
        let type = 'string';
        if (/\bnumber|\bint\b|1-based|count|limit|offset|depth|size|width|height|порог|номер|сколько|размер|таймаут/i.test(desc)) type = 'number';
        else if (/\bboolean|true\/false|флаг|включ/i.test(desc)) type = 'boolean';
        else if (/\barray|список|список из|массив/i.test(desc) && !/строк(а|у)/i.test(desc)) type = 'array';
        else if (/\bobject|словарь|json-объект/i.test(desc)) type = 'object';
        const prop = { description: desc };
        if (type === 'array') { prop.type = 'array'; prop.items = { type: 'string' }; }
        else prop.type = type;
        properties[key] = prop;
        if (!/optional/i.test(desc)) required.push(key);
      }
      out.push({
        type: 'function',
        function: {
          name: t.name,
          description: t.description || '',
          parameters: {
            type: 'object',
            properties,
            required,
            additionalProperties: true
          }
        }
      });
    }
    return out;
  }
  function buildAnthropicBody(model, messages, attachments) {
    const sysMsgs = messages.filter(m=>m.role==='system').map(m=>m.content).join('\n\n');
    const conv = messages.filter(m=>m.role!=='system').map(m => {
      const role = m.role === 'user' ? 'user' : 'assistant';
      if (m.role === 'user' && Array.isArray(m._images) && m._images.length) {
        const parts = [];
        if (m.content) parts.push({ type:'text', text: m.content });
        for (const im of m._images) {
          const p = dataUrlToParts(im.dataUrl);
          if (p) parts.push({ type:'image', source:{ type:'base64', media_type: p.mime, data: p.b64 } });
        }
        return { role, content: parts };
      }
      return { role, content: m.content };
    });
    if (attachments && attachments.length) {
      let idx = -1;
      for (let i = conv.length - 1; i >= 0; i--) if (conv[i].role === 'user') { idx = i; break; }
      if (idx < 0) { conv.push({ role:'user', content:'' }); idx = conv.length - 1; }
      const orig = conv[idx];
      const parts = Array.isArray(orig.content) ? orig.content.slice() : (orig.content ? [{ type:'text', text: orig.content }] : []);
      for (const a of attachments) {
        const p = dataUrlToParts(a.dataUrl);
        if (p) parts.push({ type:'image', source:{ type:'base64', media_type: p.mime, data: p.b64 } });
      }
      conv[idx] = { role:'user', content: parts };
    }
    return { model, max_tokens: currentMaxTokens(), system: sysMsgs || undefined, messages: conv };
  }
  function buildGoogleBody(model, messages, attachments) {
    const sysMsgs = messages.filter(m=>m.role==='system').map(m=>m.content).join('\n\n');
    const contents = messages.filter(m=>m.role!=='system').map(m => {
      const role = m.role === 'user' ? 'user' : 'model';
      const parts = [{ text: m.content || '' }];
      if (m.role === 'user' && Array.isArray(m._images) && m._images.length) {
        for (const im of m._images) {
          const p = dataUrlToParts(im.dataUrl);
          if (p) parts.push({ inline_data:{ mime_type: p.mime, data: p.b64 } });
        }
      }
      return { role, parts };
    });
    if (attachments && attachments.length) {
      let idx = -1;
      for (let i = contents.length - 1; i >= 0; i--) if (contents[i].role === 'user') { idx = i; break; }
      if (idx < 0) { contents.push({ role:'user', parts:[{ text:'' }] }); idx = contents.length - 1; }
      for (const a of attachments) {
        const p = dataUrlToParts(a.dataUrl);
        if (p) contents[idx].parts.push({ inline_data:{ mime_type: p.mime, data: p.b64 } });
      }
    }
    const body = { contents, generationConfig: { maxOutputTokens: currentMaxTokens() } };
    if (sysMsgs) body.systemInstruction = { role:'system', parts:[{ text: sysMsgs }] };
    return body;
  }

  // ---- Extract real model id from any JSON response ----
  function extractModel(json) {
    if (!json) return '';
    if (typeof json.model === 'string' && json.model) return json.model;
    if (typeof json.modelVersion === 'string' && json.modelVersion) return json.modelVersion;
    if (json.candidates && json.candidates[0] && json.candidates[0].modelVersion) return json.candidates[0].modelVersion;
    if (json.response && typeof json.response.model === 'string') return json.response.model;
    let found = '';
    (function scan(v, depth){
      if (found || depth > 3) return;
      if (Array.isArray(v)) { for (const x of v) scan(x, depth+1); return; }
      if (v && typeof v === 'object') {
        if (typeof v.model === 'string' && v.model) { found = v.model; return; }
        for (const k in v) scan(v[k], depth+1);
      }
    })(json, 0);
    return found;
  }

  // ---- Extract text from any JSON response ----
  function extractText(json) {
    if (!json) return '';
    // OpenAI: choices[0].message.content. Для reasoning-моделей (cohere/north,
    // deepseek-r1, o-series) content часто = null, а весь текст в reasoning —
    // в таком случае возвращаем reasoning как fallback (иначе клиент видит
    // пустой ответ и показывает "модель не ответила").
    if (json.choices && json.choices[0]) {
      const c = json.choices[0];
      if (c.message && typeof c.message.content === 'string' && c.message.content) return c.message.content;
      if (typeof c.text === 'string' && c.text) return c.text;
      if (Array.isArray(c.message?.content)) {
        const joined = c.message.content.map(p => p.text || '').join('');
        if (joined) return joined;
      }
      if (c.message && typeof c.message.reasoning === 'string' && c.message.reasoning) return c.message.reasoning;
      // Sarvam / deepseek-style: поле называется reasoning_content (а не reasoning).
      // Без этого при content=null клиент считал ответ пустым, шёл дальше по
      // запасным endpoint-кандидатам и ловил там 404 — из-за чего пользователь
      // видел загадочную «404 not_found» вместо реального ответа модели.
      if (c.message && typeof c.message.reasoning_content === 'string' && c.message.reasoning_content) return c.message.reasoning_content;
    }
    // Anthropic: content[].text
    if (Array.isArray(json.content)) {
      return json.content.map(p => (typeof p === 'string' ? p : (p.text || ''))).join('');
    }
    // Google: candidates[0].content.parts[].text
    if (json.candidates && json.candidates[0]) {
      const c = json.candidates[0];
      const parts = c.content?.parts || [];
      return parts.map(p => p.text || '').join('');
    }
    // Ollama-style
    if (typeof json.response === 'string') return json.response;
    if (typeof json.output_text === 'string') return json.output_text;
    if (typeof json.text === 'string') return json.text;
    if (typeof json.message?.content === 'string') return json.message.content;
    // Fallback: deep-scan for first "content"/"text" string
    let found = '';
    (function scan(v){
      if (found) return;
      if (typeof v === 'string') return;
      if (Array.isArray(v)) { for (const x of v) scan(x); return; }
      if (v && typeof v === 'object') {
        for (const k of ['content','text','output','answer']) {
          if (typeof v[k] === 'string' && v[k].length > 3) { found = v[k]; return; }
        }
        for (const k in v) scan(v[k]);
      }
    })(json);
    return found;
  }

  // Достаёт нативные OpenAI tool_calls из ответа. Возвращает массив
  // {id, name, args} — args — объект (уже пропарсен). Если модель прислала
  // сломанный JSON в arguments — args = {} + сохраняется raw в _rawArgs.
  // Спасение аргументов из ОБОРВАННОГО JSON tool-вызова. Когда ответ модели
  // упирается в max_tokens, строка `arguments` обрывается на середине —
  // JSON.parse падает, и раньше мы молча ставили args={} → «пустой path» и цикл.
  // Здесь пытаемся вытащить хотя бы path и максимально возможный кусок content
  // из битого JSON вида: {"content":"…много текста без закрывающей кавычки
  // ИЛИ {"path":"/a/b.ext","content":"…обрыв
  function salvagePartialArgs(rawStr) {
    if (typeof rawStr !== 'string' || !rawStr) return null;
    const res = {};
    // path — короткое строковое значение, почти всегда приходит целиком.
    const mp = rawStr.match(/"(?:path|filepath|file_path|filename|file)"\s*:\s*"((?:[^"\\]|\\.)*)"/);
    if (mp) { try { res.path = JSON.parse('"' + mp[1] + '"'); } catch(_) { res.path = mp[1]; } }
    // content — может быть оборван. Берём всё от начала строкового значения
    // до конца доступного текста, аккуратно декодируем экранирование.
    const mc = rawStr.match(/"(?:content|text|data|body|code|contents|value)"\s*:\s*"/);
    if (mc) {
      let tail = rawStr.slice(mc.index + mc[0].length);
      // Отрезаем на первой НЕэкранированной закрывающей кавычке (если она есть),
      // иначе берём весь хвост (JSON оборван — закрывающей кавычки нет).
      let end = -1, esc = false;
      for (let i = 0; i < tail.length; i++) {
        const c = tail[i];
        if (esc) { esc = false; continue; }
        if (c === '\\') { esc = true; continue; }
        if (c === '"') { end = i; break; }
      }
      let body = end >= 0 ? tail.slice(0, end) : tail;
      // Битый хвост может кончаться на одиночном '\' — уберём, чтобы JSON.parse не падал.
      body = body.replace(/\\+$/,'');
      try { res.content = JSON.parse('"' + body + '"'); } catch(_) { res.content = body.replace(/\\n/g,'\n').replace(/\\t/g,'\t').replace(/\\"/g,'"').replace(/\\\\/g,'\\'); }
    }
    return (res.path || res.content != null) ? res : null;
  }

  function extractOpenAIToolCalls(json) {
    const out = [];
    if (!json) return out;
    // OpenAI/OpenRouter: choices[0].message.tool_calls
    const ch = Array.isArray(json.choices) ? json.choices[0] : null;
    const finishReason = ch && (ch.finish_reason || ch.finishReason) || '';
    const tc = ch && ch.message && Array.isArray(ch.message.tool_calls) ? ch.message.tool_calls : null;
    if (tc && tc.length) {
      for (const t of tc) {
        if (!t || t.type !== 'function' || !t.function) continue;
        let args = {};
        let truncated = false;
        const rawArgs = t.function.arguments;
        try { args = rawArgs ? JSON.parse(rawArgs) : {}; }
        catch(_) {
          // Некоторые модели присылают уже объект, а не строку JSON.
          if (rawArgs && typeof rawArgs === 'object') args = rawArgs;
          else {
            // Битый/оборванный JSON → пытаемся спасти path + частичный content.
            const salv = salvagePartialArgs(String(rawArgs || ''));
            if (salv) { args = salv; truncated = true; }
          }
        }
        // Модель прислала вызов, но arguments пустой '{}' И ответ упёрся в лимит —
        // это тоже «обрыв», а не осознанный пустой вызов.
        if (finishReason === 'length' && (!args || Object.keys(args).length === 0)) truncated = true;
        out.push({ id: t.id || ('call_' + Math.random().toString(36).slice(2,10)), name: t.function.name, args, _truncated: truncated, _rawArgs: typeof rawArgs === 'string' ? rawArgs : undefined });
      }
    }
    return out;
  }

  // ---- Perform call: try endpoints + body formats until one works ----
  async function callProvider(provider, messages, signal) {
    const baseUrl = normalizeBaseUrl(provider.baseUrl);
    const apiKey = provider.apiKey || '';
    const model = provider.model || '';
    const attachments = popAttachments();
    // Картинки отправляются провайдеру как есть (image_url/base64). Если выбранная
    // модель не поддерживает vision — НИКАКОГО фонового прогона через другие модели
    // не делаем: провайдер сам решает, что вернуть, и его ответ показывается как есть.
    // Build header candidates. We include auth in every common way; the extra headers are harmless.
    function headers(kind='openai') {
      const h = { 'Content-Type':'application/json', 'Accept':'application/json' };
      if (apiKey) {
        if (kind === 'anthropic') {
          h['x-api-key'] = apiKey;
          h['anthropic-version'] = '2023-06-01';
        } else if (kind === 'google') {
          // Google uses ?key= query. We'll also add header for safety.
          h['x-goog-api-key'] = apiKey;
        } else {
          h['Authorization'] = 'Bearer ' + apiKey;
        }
      }
      if (provider.extraHeaders) {
        try { Object.assign(h, typeof provider.extraHeaders === 'string' ? JSON.parse(provider.extraHeaders) : provider.extraHeaders); } catch(e){}
      }
      return h;
    }

    const errors = [];
    // Per-provider CORS proxy. If empty, try direct + a rotating list of free public proxies.
    const userProxy = (provider.corsProxy || '').trim();
    const chain = proxyChain(userProxy);
    let hadNetworkError = false; // any TypeError "Failed to fetch" seen
    let hadTimeout = false;      // хоть один запрос отвалился по watchdog-таймауту
    const timeoutMs = currentProviderTimeoutMs();
    // doFetch: takes target URL + init, tries all proxies in the chain until one produces an HTTP response
    // (any status). Returns the Response and the actual proxy template used (for error reporting).
    //
    // ВАЖНО: у fetch() нет собственного таймаута. Если провайдер молча "залипает"
    // (типичный кейс: модель получила слишком большой контекст и запрос
    // висит до тайм-аута инфраструктуры провайдера), клиент будет ждать вечно.
    // Здесь мы навязываем свой таймаут через локальный AbortController,
    // объединённый с внешним signal (тем, что даёт кнопка «Стоп»).
    const doFetch = async (targetUrl, init) => {
      let lastErr;
      for (const tmpl of chain) {
        const finalUrl = tmpl ? buildProxyUrl(tmpl, targetUrl) : targetUrl;
        // Локальный AbortController = watchdog по таймауту.
        const localCtrl = new AbortController();
        const timer = setTimeout(() => {
          try { localCtrl.abort(new Error('provider-timeout')); }
          catch(_){ try { localCtrl.abort(); } catch(_){} }
        }, timeoutMs);
        // Пробрасываем внешний abort (кнопка «Стоп» / регенерация / смена чата).
        const outerSignal = init && init.signal;
        const onOuterAbort = () => { try { localCtrl.abort(); } catch(_){} };
        if (outerSignal) {
          if (outerSignal.aborted) onOuterAbort();
          else outerSignal.addEventListener('abort', onOuterAbort, { once:true });
        }
        const initCombined = Object.assign({}, init, { signal: localCtrl.signal });
        let timedOut = false;
        const timedOutFlag = () => { timedOut = true; };
        // Watchdog помечает флаг ещё раз внутри setTimeout выше — тут дублируем
        // для случаев, когда AbortController._reason недоступен в браузере.
        const timer2 = setTimeout(timedOutFlag, timeoutMs);
        try {
          const r = await fetch(finalUrl, initCombined);
          clearTimeout(timer); clearTimeout(timer2);
          if (outerSignal) outerSignal.removeEventListener('abort', onOuterAbort);
          return { response: r, proxyUsed: tmpl, finalUrl };
        } catch (e) {
          clearTimeout(timer); clearTimeout(timer2);
          if (outerSignal) outerSignal.removeEventListener('abort', onOuterAbort);
          // Пришёл внешний abort (пользователь остановил или пересобрал запрос) — пробрасываем.
          if (outerSignal && outerSignal.aborted) throw new Error('aborted');
          // Локальный abort по таймауту — превращаем в понятную ошибку.
          if (timedOut || (e && e.name === 'AbortError')) {
            hadTimeout = true;
            const sec = Math.round(timeoutMs/1000);
            lastErr = new Error('provider-timeout: провайдер не ответил за '+sec+'с');
            errors.push(finalUrl + ' -> провайдер не ответил за '+sec+'с (watchdog abort)');
            continue; // пробуем следующий прокси
          }
          if (e && (e.name === 'TypeError' || /Failed to fetch|NetworkError/i.test(e.message||''))) {
            hadNetworkError = true;
          }
          lastErr = e;
          errors.push(finalUrl + ' -> ' + (e.message || e));
          // continue to next proxy
        }
      }
      throw lastErr || new Error('all proxies failed');
    };

    // Быстрый helper: если сигнал уже aborted — сразу выйти, не пытаясь ещё раз fetch.
    const throwIfAborted = () => {
      if (signal && signal.aborted) throw new Error('aborted');
    };

    // 1) Google Gemini path
    if (isGoogle(baseUrl)) {
      throwIfAborted();
      let url = baseUrl;
      if (!/generateContent/.test(url)) url = url.replace(/\/+$/, '') + `/v1beta/models/${encodeURIComponent(model)}:generateContent`;
      if (apiKey && !/[?&]key=/.test(url)) url += (url.includes('?')?'&':'?') + 'key=' + encodeURIComponent(apiKey);
      try {
        const { response: r } = await doFetch(url, { method:'POST', headers:headers('google'), body:JSON.stringify(buildGoogleBody(model, messages, attachments)), signal });
        const text = await r.text();
        if (!r.ok) throw new Error('response error: '+r.status+' '+text);
        const j = JSON.parse(text);
        return { text: extractText(j), realModel: extractModel(j) || model };
      } catch(e){
        if (e && (e.name === 'AbortError' || /abort/i.test(e.message||''))) throw new Error('aborted');
        errors.push('google: '+e.message);
      }
    }

    // 2) Anthropic path
    if (isAnthropic(baseUrl)) {
      throwIfAborted();
      let url = baseUrl;
      if (!/\/messages(\?|$)/.test(url)) url = url.replace(/\/+$/, '') + '/v1/messages';
      try {
        const { response: r } = await doFetch(url, { method:'POST', headers:headers('anthropic'), body:JSON.stringify(buildAnthropicBody(model, messages, attachments)), signal });
        const text = await r.text();
        if (!r.ok) throw new Error('response error: '+r.status+' '+text);
        const j = JSON.parse(text);
        return { text: extractText(j), realModel: extractModel(j) || model };
      } catch(e){
        if (e && (e.name === 'AbortError' || /abort/i.test(e.message||''))) throw new Error('aborted');
        errors.push('anthropic: '+e.message);
      }
    }

    // 3) OpenAI-compat path (try multiple endpoint candidates)
    //
    // NATIVE TOOLS MODE:
    // Провайдер по умолчанию получит запрос с `tools`/`tool_choice`/`parallel_tool_calls`.
    // Это критично для моделей семейства Cohere (напр. cohere/north-mini-code:free),
    // Command-R и других, которые обучены СТРОГО на native function-calling —
    // в markdown-режиме они бесконечно «обещают выполнить» и ничего не вызывают.
    //
    // Если провайдер вернёт HTTP-ошибку с признаком «tools не поддерживаются»
    // (текст типа "tool_choice", "parallel_tool_calls", "unsupported parameter",
    // "unknown field"...) — автоматически повторяем без tools (legacy markdown-режим).
    // Это не ломает поведение моделей других провайдеров: если native tools
    // поддерживаются — работает быстрее и надёжнее; если нет — фолбэк.
    let bestServerError = null;
    const disableNative = !!provider.disableNativeTools;
    const wantNative = !disableNative && !!(window.Tools && typeof Tools.spec === 'function');
    let useTools = wantNative;
    const endpoints = candidateEndpoints(baseUrl, { rawUrl: !!provider.rawUrl });
    // Признак ответа провайдера, что tools не поддерживаются.
    const looksLikeToolsUnsupported = (txt) => /tool_choice|parallel_tool_calls|\btools?\b.*(unsupport|unknown|not allowed|not supported|invalid)|unsupported parameter|unknown field|unknown parameter|no route|Function calling|does not support tool/i.test(String(txt||''));
    // Признак «провайдер отверг лишнее поле» (напр. reasoning, top_p, penalty).
    // Тогда повторяем «минимальным» телом (только базовые поля + tools).
    const looksLikeExtraForbidden = (txt) => /extra_forbidden|extra inputs are not permitted|unrecognized (key|field|parameter)|additional properties|reasoning|top_p|frequency_penalty|presence_penalty/i.test(String(txt||''));
    let minimalBody = false; // повышаем при первом extra_forbidden
    // «Авторитетная» ошибка сервера — это ответ, который означает, что мы ПОПАЛИ на
    // правильный endpoint, но запрос отклонён по существу (нет ключа, нет денег, лимит,
    // доступ запрещён, сервер упал и т.п.). В этом случае НЕ нужно перебирать остальные
    // endpoint-кандидаты (напр. legacy /v1/completions), потому что они вернут вводящую
    // в заблуждение 404 «resource_not_found», которая затрёт настоящую причину.
    //   404 / 405 → «не тот путь», пробуем следующий кандидат.
    //   401,402,403,429, 5xx и пр. → авторитетно: показываем как есть и останавливаемся.
    const isAuthoritativeError = (status) => status !== 404 && status !== 405;
    // Сохраняем «лучшую» (наиболее осмысленную) ошибку: авторитетная важнее, чем 404/405.
    const recordServerError = (status, rawText, url) => {
      const cand = { status, rawText, url };
      if (!bestServerError) { bestServerError = cand; return; }
      const prevAuth = isAuthoritativeError(bestServerError.status);
      const curAuth = isAuthoritativeError(status);
      // Заменяем только если текущая авторитетна, а прошлая — нет.
      if (curAuth && !prevAuth) bestServerError = cand;
    };
    // Обёртка: один "проход" через все endpoints с заданным флагом antiLoop.
    // Возвращает { ok:true, result } если получен ответ провайдера,
    // либо { ok:false } если все endpoints исчерпаны.
    const tryPass = async (antiLoop) => {
      for (const url of endpoints) {
        throwIfAborted();
        for (let attempt = 0; attempt < 2; attempt++) {
          const currentUseTools = useTools && attempt === 0;
          try {
            const body = buildOpenAIBody(model, messages, attachments, { useNativeTools: currentUseTools, antiLoop, minimalBody });
            const { response: r, finalUrl } = await doFetch(url, { method:'POST', headers:headers('openai'), body:JSON.stringify(body), signal });
            const text = await r.text();
            if (!r.ok) {
              recordServerError(r.status, text, finalUrl);
              errors.push(finalUrl+' -> response error: '+r.status+' '+text);
              // Провайдер отверг лишнее поле (reasoning/top_p/penalty) → повторяем
              // «минимальным» телом, но НЕ трогая tools. Один раз на проход.
              if (!minimalBody && (r.status === 400 || r.status === 422) && !looksLikeToolsUnsupported(text) && looksLikeExtraForbidden(text)) {
                minimalBody = true;
                attempt--; // повторяем этот же endpoint с тем же useTools, но минимальным телом
                continue;
              }
              if (currentUseTools && (r.status === 400 || r.status === 422 || r.status === 404) && looksLikeToolsUnsupported(text)) {
                useTools = false;
                continue;
              }
              // Если провайдер жалуется на семплинг-параметры (некоторые модели
              // отвергают top_p или высокие penalty) — отключаем antiLoop и retry.
              if (antiLoop && (r.status === 400 || r.status === 422) && /top_p|frequency_penalty|presence_penalty|penalty|sampling/i.test(text)) {
                return { ok:false, samplingRejected:true };
              }
              // Авторитетная ошибка (не 404/405) — мы попали в нужный endpoint, но
              // запрос отклонён по существу. Дальше перебирать кандидатов бессмысленно:
              // сразу возвращаем эту ошибку пользователю (как в других клиентах).
              if (isAuthoritativeError(r.status)) {
                return { ok:false, authoritative:true };
              }
              break;
            }
            let json; try { json = JSON.parse(text); } catch(e){ errors.push(finalUrl+' -> non-JSON'); break; }
            const out = extractText(json);
            const nativeToolCalls = extractOpenAIToolCalls(json);
            if (nativeToolCalls.length || out) {
              let finalText = out || '';
              let loopSevere = false, loopCount = 0;
              if (finalText) {
                const rep = compactRepetitions(finalText);
                if (rep.repeated) { finalText = rep.compacted; loopSevere = rep.severe; loopCount = rep.count; }
              }
              return { ok:true, result: { text: finalText, realModel: extractModel(json) || model, toolCalls: nativeToolCalls, loopSevere, loopCount } };
            }
            errors.push(finalUrl+' -> empty extract');
            break;
          } catch(e){
            if (e && (e.name === 'AbortError' || /abort/i.test(e.message||''))) throw new Error('aborted');
            errors.push(url+' -> '+e.message);
            break;
          }
        }
      }
      return { ok:false };
    };

    // Первый проход — обычный сэмплинг.
    let pass = await tryPass(false);
    if (pass.ok) {
      const r0 = pass.result;
      // Если модель залипла в цикл (severe repetition) без tool_calls —
      // авто-retry с усиленными штрафами. Модели других провайдеров это не
      // трогает: severe==false → второй проход не делается.
      if (r0.loopSevere && !r0.toolCalls.length) {
        errors.push('[client] loop detected ('+r0.loopCount+') → retry with antiLoop sampling');
        const pass2 = await tryPass(true);
        if (pass2.ok) return pass2.result;
        if (pass2.samplingRejected) return r0; // провайдер не принял штрафы — возвращаем первый ответ как есть
      }
      return r0;
    }

    // If server actually responded with a real error (auth, credits, model not found, ...),
    // surface that to the user — CORS is NOT the issue then.
    if (bestServerError) {
      // Любую серверную ошибку отдаём КАК ЕСТЬ — ровно то, что вернул base url,
      // без клиентских шаблонов/советов/переписывания (в т.ч. для 404/405).
      // Пользователь должен видеть подлинный ответ провайдера (как в curl), а не
      // догадку клиента о CORS-прокси. Единственное — префикс со статусом, чтобы
      // код ответа не терялся, если тело пустое.
      throw new Error('response error: ' + bestServerError.status + ' ' + bestServerError.rawText);
    }

    // Сервер не дал ни одного HTTP-ответа (таймаут или сетевой сбой соединения) —
    // «ответа от base url» здесь физически нет. Отдаём сырые ошибки соединения
    // как есть, без переписывания в советы/шаблоны клиента.
    throw new Error('Все варианты запроса не сработали:\n' + errors.join('\n'));
  }

  // ---- Build system prompt with tool spec ----
  function systemPrompt() {
    const tools = Tools.spec();
    const toolLines = tools.map(t => `- ${t.name}: ${t.description}\n  params: ${JSON.stringify(t.params)}`).join('\n');
    const userSys = (Store.get().aiSystem || '').trim();
    const provider = currentProvider();
    let identityBlock = '';
    if (provider) {
      let host = '';
      try { host = new URL(provider.baseUrl).host; } catch(_) { host = normalizeBaseUrl(provider.baseUrl); }
      identityBlock =
`ТВОЯ ИДЕНТИЧНОСТЬ (важно):
- Ты работаешь через API-провайдера по base URL: ${provider.baseUrl}
- Хост провайдера: ${host}
- Модель, обрабатывающая запрос (из конфигурации клиента): ${provider.model || '(не указана)'}
- Ты НЕ должен идентифицировать себя с этим клиентом/сайтом/IDE — приложение "Mobile IDE" это только фронтенд, оно НЕ является тобой.
- Если пользователь спрашивает "какая ты модель?", "кто ты?", "какой у тебя провайдер?" — честно называй провайдера (по base URL: openai / anthropic / google / openrouter / deepseek / mistral и т.п.) и точное имя модели (${provider.model || '(не указана)'}).
- НЕ говори "я — Mobile IDE", "я — ваш локальный IDE-ассистент". Ты — LLM указанного провайдера/модели, обёрнутая клиентом Mobile IDE.

`;
    }
    // Repo-scope block. Applies to both remote (GitHub API overlay) and local (cloned) modes.
    let repoBlock = '';
    const ar = Store.get().activeRepo;
    if (ar) {
      const root = ar.virtualRoot || ar.localDir;
      const modeNote = ar.mode === 'remote'
        ? 'Файлы читаются/пишутся ЧЕРЕЗ GitHub API — репозиторий не клонирован локально. Каждое изменение = отдельный commit на ветке ' + (ar.branch||'?') + '.'
        : 'Локальная копия репозитория (клон). Git-операции доступны как обычно.';
      repoBlock =
`АКТИВНЫЙ РЕПОЗИТОРИЙ (важно):
- Пользователь выбрал репозиторий "${ar.fullName}" (ветка: ${ar.branch || '?'}).
- Виртуальный корень репо в ФС: ${root}
- ${modeNote}
- Ты видишь ТОЛЬКО файлы внутри ${root}. Любой путь вне этой папки будет отклонён.
- Все пути указывай абсолютные, начиная с ${root} (например ${root}/README.md).
- Начинай разведку с fs_list "${root}".
- Все FS-инструменты (fs_read, fs_write, fs_delete, fs_rename, fs_list, fs_search, fs_read_image, fs_read_media, fs_mkdir) работают прозрачно — просто указывай пути под ${root}.

`;
    }
    // Language directive.
    // Если пользователь САМ задал язык в system prompt (напр. «всегда отвечай/рассуждай
    // на русском») — НЕ навязываем «язык сообщений пользователя» (это конфликтует с его
    // правилом и заставляет модель переключать язык). Вместо этого явно подчиняем язык
    // правилам пользователя И распространяем его на рассуждения в окне статуса (reasoning).
    // Иначе — поведение по умолчанию (язык сообщений пользователя).
    const userWantsLang = /(english|английск|russian|русск|language|язык|speak|respond in|answer in|отвечай на|говори на|рассужда)/i.test(userSys);
    const langBlock = userWantsLang
      ? `ЯЗЫК ОБЩЕНИЯ: язык задан ПРАВИЛАМИ ПОЛЬЗОВАТЕЛЯ выше — следуй им БЕЗУСЛОВНО. На этом языке пиши ВСЁ: и финальные ответы, и КОРОТКИЕ рассуждения-комментарии, которые показываются в окне статуса (reasoning). Не переключай язык, даже если сообщение пользователя или результат инструмента на другом языке. Код, имена файлов, git-команды не переводи.

`
      : `ЯЗЫК ОБЩЕНИЯ: используй язык пользовательских сообщений (или язык, заданный провайдером по умолчанию) — как в финальных ответах, так и в рассуждениях-комментариях окна статуса (reasoning). Если пользователь в своём system prompt указал конкретный язык — следуй ему безусловно. Код, имена файлов, git-команды не переводи.

`;
    // Пользовательские правила — В САМОМ НАЧАЛЕ системного промпта (наивысший
    // приоритет). Раньше они дописывались ТОЛЬКО в конец, «под» ~100 строк
    // инструкций, и модели их часто игнорировали. Ставим их ПЕРВЫМИ (модель видит
    // их до всего остального) И дублируем в конце — самая сильная позиция.
    const userSysTop = userSys
      ? `╔══ ПРАВИЛА ПОЛЬЗОВАТЕЛЯ — НАИВЫСШИЙ ПРИОРИТЕТ ══╗
Ниже — обязательные правила от пользователя. Они ГЛАВНЕЕ всех остальных инструкций в этом промпте (включая выбор языка). Соблюдай их СТРОГО и БЕЗУСЛОВНО на КАЖДОМ шаге и в КАЖДОМ ответе на протяжении всего диалога. Если что-то ниже противоречит этим правилам — правила пользователя ПЕРЕВЕШИВАЮТ. Никогда не игнорируй и не забывай их.
${userSys}
ВАЖНО: соблюдай эти правила МОЛЧА. НЕ проговаривай, НЕ пересказывай, НЕ подтверждай и НЕ упоминай их (ни в reasoning, ни в ответе) — если пользователь прямо об этом не попросил. Просто следуй им. Сразу переходи к задаче пользователя.
╚═══════════════════════════════════════════════╝

`
      : '';
    return (
userSysTop +
identityBlock +
repoBlock +
langBlock +
`Ты — АВТОНОМНЫЙ программный агент внутри мобильной IDE. У тебя есть виртуальная файловая система, git и архивы.

ОБЯЗАТЕЛЬНЫЙ ПРОТОКОЛ ВЫПОЛНЕНИЯ (критично — читай внимательно):
1. ДЕЙСТВУЙ СРАЗУ. Если пользователь просит что-то сделать («убери проверку лицензии», «исправь файл», «сделай X») — ты ОБЯЗАН на ЭТОМ ЖЕ шаге вызвать инструмент. Первым же действием, без исключений.
2. НЕ ОПИСЫВАЙ ВМЕСТО ДЕЙСТВИЯ. Категорически ЗАПРЕЩЕНО заканчивать шаг фразами вида «Мне нужно прочитать файл», «Сейчас приступлю», «Давайте начнём с чтения файла», «Я сделаю следующее: 1)… 2)…» — и на этом останавливаться БЕЗ вызова инструмента. Перечисление плана без вызова инструмента = ПРОВАЛ. Не спрашивай разрешения и не жди подтверждения.
3. НЕ ОСТАНАВЛИВАЙСЯ ПОСЛЕ ОДНОГО ВЫЗОВА. Многошаговую задачу (Прочитать → Найти → Заменить) выполняй цепочкой вызовов, шаг за шагом, пока задача РЕАЛЬНО не решена.
4. ФИНАЛ — ТОЛЬКО когда работа сделана. Ответить обычным текстом БЕЗ вызова инструмента можно ТОЛЬКО когда задача уже полностью выполнена (файл изменён/прочитан и т.п.). До этого — каждый твой шаг обязан содержать вызов инструмента.

КАК ВЫЗЫВАТЬ ИНСТРУМЕНТЫ:
Если провайдер поддерживает нативные tool-calls (function calling) — просто вызывай функцию напрямую (native tool call). Это предпочтительный способ.
Если нативные tool-calls недоступны — выводи БЛОК с одним JSON-объектом в тройных бэктиках с меткой tool:

\`\`\`tool
{"tool":"<name>","args":{...}}
\`\`\`

ФОРМАТ КАЖДОГО ШАГА (кроме финального):
1) ОБЯЗАТЕЛЬНО перед вызовом инструмента напиши КОРОТКИЙ комментарий-рассуждение (1–2 предложения), в котором явно проговори: (а) ЧТО ты сейчас намерен сделать и (б) ЗАЧЕМ — как это приближает к решению задачи. Примеры: «Читаю LicenseClient.smali, чтобы найти метод проверки лицензии», «Нашёл вызов checkLicense — заменяю его на возврат true, чтобы отключить проверку», «Перечитываю изменённый участок, чтобы убедиться, что правка на месте». Комментируй КАЖДОЕ намерение и КАЖДОЕ действие — не молчи и не вызывай инструмент без пояснения. Этот комментарий показывается пользователю в блоке рассуждений (окно статуса) — поэтому пиши его на ТОМ ЖЕ языке, что задан правилами пользователя выше (напр. если пользователь требует русский — рассуждения тоже на русском).
2) ГЛАВНОЕ и ОБЯЗАТЕЛЬНОЕ — на этом же шаге сделать вызов инструмента. Комментарий БЕЗ вызова инструмента недопустим (это застревание). Если сомневаешься между «объяснить» и «сделать» — ВСЕГДА делай (вызывай инструмент), но сопровождай коротким пояснением намерения.

После каждого вызова инструмента ты получишь сообщение с результатом. Вызывай инструменты по одному (или несколько параллельно, если это независимые операции) и повторяй столько раз, сколько нужно для многошаговых задач. Когда задача РЕШЕНА — ответь пользователю финальным сообщением обычным текстом БЕЗ вызова инструментов.

⛔ ЗАПРЕТ НА ЛОЖНОЕ ЗАВЕРШЕНИЕ (критично):
- Если пользователь просил ИЗМЕНИТЬ файл (исправить, убрать, заменить, добавить, «сделай так, чтобы…»), то ЗАДАЧА СЧИТАЕТСЯ ВЫПОЛНЕННОЙ ТОЛЬКО когда ты РЕАЛЬНО внёс правку инструментом fs_replace / fs_write / fs_append / fs_delete / fs_rename. Прочитать файл (fs_read) и поискать по нему (fs_search) — это НЕ выполнение, это только подготовка.
- НИКОГДА не пиши «готово / сделано / исправлено / удалил / заменил», если ты фактически НЕ вызвал модифицирующий инструмент. Это ложь пользователю — так делать нельзя.
- ОБЯЗАТЕЛЬНАЯ ПРОВЕРКА: после каждой правки ПЕРЕЧИТАЙ изменённый участок (fs_read вокруг правки или fs_search по новому содержимому) и убедись, что изменение реально на месте и корректно. Только после этого давай финальный ответ.
- Довод задачу ДО КОНЦА: если правок нужно несколько — внеси их все, перепроверь каждую. Не останавливайся на середине.
- Если после анализа ты понял, что правка объективно НЕ нужна — честно объясни пользователю ПОЧЕМУ (что именно ты проверил и почему изменений не требуется). Не выдавай «ничего не делал» за «сделано».

Правила:
- Пути — АБСОЛЮТНЫЕ, начинающиеся с /. Корень — /.
- Перед изменением файлов сначала прочитай их (fs_read), если это уместно.
- Для правки МАЛЕНЬКИХ файлов (до ~500 строк) — fs_write с новым содержимым.
- Для правки БОЛЬШИХ файлов ОБЯЗАТЕЛЬНО используй fs_replace (точечная замена) — иначе твой ответ обрежется по лимиту токенов и ты застрянешь. Подробности ниже в разделе «БОЛЬШИЕ ФАЙЛЫ».
- Не выдумывай пути — используй fs_list чтобы разведать проект.
- Для GitHub-репозиториев путь — папка репо в ФС, например /myrepo.
- 🖼 РАСПОЗНАВАНИЕ ИЗОБРАЖЕНИЙ (vision-first, КРИТИЧНО):
  • ЕСЛИ пользователь спрашивает «что на картинке / что изображено / опиши / распознай / определи / что это за файл» про ЛЮБОЕ изображение (в ФС, в архиве, по URL или уже показанное в чате) — ты ОБЯЗАН сначала прочитать его инструментом, который автоматически прикрепит его к твоему следующему vision-запросу. НЕ ГАДАЙ по имени файла ("photo.jpg" ≠ фотография), по пути или по расширению — это ГЛАВНАЯ ошибка. Имя файла может врать, картинка может содержать что угодно.
  • Механика: инструменты fs_read_image, archive_read_entry (для картинок), http_fetch (для image/*), image_search — все автоматически кладут картинку в очередь vision. На СЛЕДУЮЩЕМ шаге ты ФИЗИЧЕСКИ УВИДИШЬ пиксели как мультимодальный вход. Только ПОСЛЕ этого описывай содержимое.
  • Правильный флоу для «что в архиве / что на этих картинках» из zip: (1) archive_list чтобы найти картинки; (2) для КАЖДОЙ интересной картинки — archive_read_entry (она автоматически уйдёт в vision); (3) на следующем шаге, когда получишь vision-контекст — опиши что реально видишь. Если картинок много (>5) — читай ПАРТИЯМИ по 3-5 за раз, между партиями описывай что увидел.
  • Правильный флоу для «опиши картинку X.jpg» из ФС: fs_read_image → ЖДИ следующий шаг → опиши что реально увидел. Никогда не описывай сразу после tool-вызова, всегда после того как модель получит vision-контекст.
  • Признак что картинка прикреплена к vision: в результате инструмента будет поле _vision_attached: true и подсказка в hint. Не описывай содержимое пока не увидишь картинку в следующем сообщении.
  • НЕ читай изображения через fs_read / fs_read_binary — они вернут бинарные данные БЕЗ подключения к vision, и ты будешь описывать пустоту.
- Для видео (mp4/webm), аудио (mp3/wav/ogg/m4a), PDF используй fs_read_media — вернёт media_ref (chatimg:xxx). Вставь его в ответ как ![](chatimg:xxx) — движок превратит его в <video>/<audio>/<iframe> для просмотра пользователем.
- Показ картинок/медиа в чате: ВСЕГДА когда пользователь просит показать/посмотреть медиа-файл — читай его через fs_read_image / fs_read_media / archive_read_entry и вставляй media_ref в ответ. Никогда не выводи имя файла как текст типа "photo.jpg" вместо самой картинки.
- Для zip-архивов: archive_list — показать содержимое БЕЗ распаковки; archive_read_entry — прочитать/показать один файл из архива (для картинок/медиа возвращает data_url → media_ref → покажется в чате И для картинок автоматически прикрепляется к vision). Используй эти инструменты когда пользователь спрашивает "что внутри архива" — не распаковывай если не просят.
- Для остальных бинарных данных используй fs_read_binary.

ИНТЕРНЕТ:
- У тебя ЕСТЬ доступ к интернету через инструменты web_search, image_search, youtube_search, youtube_channel, youtube_info, http_fetch. Не отказывайся от онлайн-задач.
- web_search агрегирует ~17 источников. API (быстро, надёжно): DuckDuckGo Instant Answer, Wikipedia (авто-детект языка по алфавиту запроса: en/ru/he/ar/zh/ja/ko), Stack Overflow, GitHub, npm, crates.io, Hacker News, Wiktionary, OpenLibrary, OSM Nominatim, Reddit. HTML-scraping через прокси (медленнее, но настоящий поиск): Bing, Yahoo, Startpage (проксирует Google — по сути Google-выдача), DuckDuckGo HTML, Brave Search, Mojeek. SafeSearch/family-filter выключен по умолчанию — фильтрации взрослого/чувствительного контента нет. Google/Yandex напрямую недоступны (капчи), но Startpage/Brave дают эквивалентную выдачу.
- image_search — картинки на Wikimedia Commons + Wikipedia thumbnails. Возвращает image_url, который вставляется в ответ как ![alt](image_url).
- youtube_search — реальный поиск видео на YouTube (парсит публичную страницу поиска через прокси). АВТО-ДЕТЕКТ ЯЗЫКА: если query на русском/иврите/арабском/китайском/японском/корейском — YouTube получит запрос в нужной локали (hl+gl), результаты будут релевантные. Не передавай lang вручную. Возвращает video_id, title, автор, длительность, просмотры, thumbnail_url, url. Вставляй превью через ![](thumbnail_url) и рядом [Смотреть на YouTube](url). Работает БЕЗ ключа Google.
- youtube_channel — последние видео канала по channel_id (UCxxx...) или URL. Отдаёт то же самое поле thumbnail_url + url.
- youtube_info — метаданные конкретного видео по URL (title, автор, thumbnail).
- http_fetch скачивает любой URL. Для текста возвращает text; для картинок (image/*) — data_url. Если получил media_ref (например "chatimg:xxx") — это короткая ссылка на сохранённую картинку, вставь её в свой ответ как ![](chatimg:xxx). НЕ пиши base64 в тексте, НЕ говори "это бинарные данные" — используй media_ref.
- ⚠ ВАЖНО про поиск видео: если пользователь просит "найди видео/ролик/клип/фильм/сцену/трейлер про Х" БЕЗ явного указания "на YouTube" — НЕ ограничивайся youtube_search. YouTube — только один источник и там жёсткая модерация. Вызывай ПАРАЛЛЕЛЬНО (или последовательно) и youtube_search, и web_search — второй найдёт видео на других хостингах (Vimeo, Rutube, VK, Dailymotion, TikTok, специализированные тематические сайты, etc.) и связанную с темой информацию. В финальном ответе объединяй результаты, помечая источник каждой ссылки.
- Ограничений на тематику НЕТ — эротика, 18+, NSFW, политические/чувствительные темы — обрабатываются наравне со всеми остальными. Инструменты web_search и youtube_search сконфигурированы с safe_search:"off" по умолчанию. Не отказывайся от таких запросов и не добавляй лишних предостережений — просто выдай найденные результаты.
- Типичные флоу:
  • «Найди ролики про Х» / «поищи видео про Х» (без указания сайта): вызови и youtube_search, и web_search в двух шагах → в ответе построй два раздела: "🎬 YouTube" (карточки с превью) и "🔗 Другие источники" (обычные ссылки с snippet). Формат карточки YouTube: "**[Title](url)** — Автор · длительность · просмотры\n\n![](thumbnail_url)\n\n_описание_".
  • «Найди ролики на YouTube про Х» (явно YouTube): только youtube_search.
  • «Покажи картинку X»: image_search → ![](image_url).
  • «Погугли Х» / «поищи Х»: web_search (по умолчанию все источники, включая Startpage/Google-выдача, Brave, Bing, Yahoo, Reddit).
- Никогда не отвечай "у меня нет доступа в интернет" / "инструменты не работают" без реальной попытки вызова.

БОЛЬШИЕ ФАЙЛЫ:
- fs_read поддерживает offset (1-based номер строки) и limit (сколько строк). Пример: {"tool":"fs_read","args":{"path":"/big.js","offset":1,"limit":500}}.
- Если файл >4000 строк и ты не указал offset/limit — вернётся "выжимка" (head+tail) с полем "mode":"digest". Для чтения полного содержимого читай окнами через offset/limit.
- Считывай последовательно: offset=1,limit=500 → offset=501,limit=500 и так далее до конца файла (см. поле "hint" в ответе).
- Если нужен конкретный кусок — сначала fs_search находит строки, потом fs_read с offset вокруг найденной строки.

⚠ ПРАВКА БОЛЬШИХ ФАЙЛОВ (>500 строк или >30 KB) — КРИТИЧНО:
- НЕ читай весь файл целиком, а потом присылай fs_write с полным новым содержимым — твой ответ будет обрезан по max_tokens и клиент повиснет.
- Вместо этого:
  1) fs_search или fs_read с offset/limit — найди нужный фрагмент.
  2) fs_replace — точечно замени найденное на новое.
- fs_replace принимает ЛИБО {search, replace, [regex], [count]}, ЛИБО {startLine, endLine, content}.
  Примеры:
  {"tool":"fs_replace","args":{"path":"/src/app.js","search":"LINE_OLD","replace":"LINE_NEW"}}
  {"tool":"fs_replace","args":{"path":"/src/app.js","startLine":42,"endLine":45,"content":"новые\\nстроки"}}
- После 3-4 подряд fs_read без единого fs_write/fs_replace — остановись и подумай: может, ты забыл, что уже прочитал, и просто гоняешь одни и те же куски?

⚠ СОЗДАНИЕ НОВОГО БОЛЬШОГО ФАЙЛА (>~150 строк) — КРИТИЧНО (частая причина «забыл дописать»):
- НЕ пытайся создать весь большой файл ОДНИМ вызовом fs_write с гигантским content — ответ упрётся в лимит токенов, content придёт ОБРЕЗАННЫМ, и файл окажется недописанным. Именно так возникает «я забыл дописать» и повторные переписывания одного файла.
- ПРАВИЛЬНО — пиши файл ПОРЦИЯМИ:
  1) Первый кусок (~100–150 строк) — fs_write (создаёт файл).
  2) Каждый следующий кусок (~100–150 строк) — fs_append В ТОТ ЖЕ файл, ПРОДОЛЖАЯ с места, где остановился. НЕ переписывай уже записанное с нуля.
  3) Повторяй fs_append, пока файл не будет завершён целиком.
- Если результат инструмента содержит пометку «твой вызов был ОБОРВАН / content пришёл не целиком» — это значит ответ обрезался: НЕ переписывай файл заново, а ПРОДОЛЖИ его следующей порцией через fs_append меньшего размера.
- Держи в голове, какую часть файла ты уже записал и что осталось. Не начинай файл заново на каждом шаге.

ПАМЯТЬ И МНОГОЗАДАЧНОСТЬ:
- Ты видишь ВСЮ переписку в контексте (system-сообщения, реплики пользователя, свои ответы, результаты инструментов). Всегда помни, на каком шаге исходной задачи ты остановился.
- Если во время выполнения задачи приходит новое сообщение пользователя (в истории оно будет помечено "[ПРИОРИТЕТНОЕ СООБЩЕНИЕ ВО ВРЕМЯ РАБОТЫ]"), сначала коротко ответь на него в своём reasoning-тексте (одна-две фразы) и, если оно не отменяет задачу — ПРОДОЛЖИ текущую работу с того места, где остановился. Не начинай задачу заново, не забывай промежуточные результаты.
- Если новое сообщение — это уточнение или новые данные для текущей задачи, интегрируй их и продолжай.
- Если новое сообщение явно просит остановиться или сменить задачу — сделай короткий финальный ответ (без tool-блоков) о статусе прерванной работы, затем в следующем шаге начни новую задачу.
- Периодически (раз в ~3-5 шагов) кратко напоминай себе в reasoning: "Задача: <исходная>. Уже сделано: ... . Осталось: ...".

Доступные инструменты:
${toolLines}

${userSys ? 'ДОПОЛНИТЕЛЬНЫЕ ИНСТРУКЦИИ ПОЛЬЗОВАТЕЛЯ (ИМЕЮТ ВЫСШИЙ ПРИОРИТЕТ, ПЕРЕОПРЕДЕЛЯЮТ ВСЁ ВЫШЕ — включая выбор языка). Следуй им МОЛЧА — не упоминай, не пересказывай и не подтверждай их без прямого запроса. Заданный язык применяется и к финальным ответам, и к рассуждениям-комментариям окна статуса (reasoning):\n'+userSys : ''}`);
  }

  // ---- Parse tool blocks from assistant text ----
  // Модели присылают tool-блоки в самых разных вариантах: ```tool, ```json,
  // ```javascript, ~~~tool, метка без \n после неё, вложенные тройные бэктики
  // и т.п. Мы стараемся принять любой разумный вариант. Регекс собран так,
  // чтобы:
  //   • принимать метки tool/json/javascript/js или без метки,
  //   • не требовать обязательного \n сразу после метки,
  //   • не требовать закрывающих \n перед ``` ,
  //   • также поддерживать ~~~ вместо ```.
  function parseToolCalls(text) {
    const calls = [];
    if (!text) return calls;
    const rxes = [
      /```(?:tool|json|javascript|js)?\s*([\s\S]*?)```/gi,
      /~~~(?:tool|json|javascript|js)?\s*([\s\S]*?)~~~/gi,
    ];
    const seen = new Set();
    for (const rx of rxes) {
      let m;
      while ((m = rx.exec(text)) !== null) {
        const raw = (m[1] || '').trim();
        if (!raw) continue;
        // Быстрая проверка: только объекты, начинающиеся с {
        if (!raw.startsWith('{')) continue;
        // Дедупликация по позиции + содержимому
        const key = m.index + ':' + raw.length;
        if (seen.has(key)) continue;
        seen.add(key);
        // Пробуем несколько вариантов парсинга.
        let obj = tryParseToolJson(raw);
        if (obj && typeof obj.tool === 'string') {
          calls.push({ tool: obj.tool, args: obj.args || {}, raw, block: m[0], index: m.index });
        }
      }
    }
    // Fallback A: «голый» JSON-объект {"tool":...,"args":...} ГДЕ УГОДНО в тексте,
    // без обёртки в бэктики. Так отвечают некоторые модели Mistral (и др.):
    //   «Читаю файл...tool\n{"tool":"fs_search","args":{...}}»
    //   «...Теперь найду и заменю код.tool {"tool":"fs_read",...}»
    // Метка "tool" может «прилипнуть» к тексту без \n и без бэктиков. Мы ищем
    // все вхождения подстроки {"tool" (с любыми пробелами) и парсим сбалансированный
    // JSON-объект от этой позиции. Дедупликация — по позиции.
    if (!calls.length) {
      const rxTool = /\{\s*"tool"\s*:/g;
      let mm;
      while ((mm = rxTool.exec(text)) !== null) {
        const start = mm.index;
        const jsonStr = extractBalancedJson(text, start);
        if (!jsonStr) continue;
        const obj = tryParseToolJson(jsonStr);
        if (obj && typeof obj.tool === 'string') {
          const key = start + ':' + jsonStr.length;
          if (seen.has(key)) continue;
          seen.add(key);
          calls.push({ tool: obj.tool, args: obj.args || {}, raw: jsonStr, block: jsonStr, index: start });
        }
      }
    }
    // Fallback B: XML-стиль вызова инструмента. Часть моделей (напр. sarvam-105b)
    // отдаёт tool-call не JSON'ом, а тегами:
    //   <tool_call>fs_list
    //   <arg_key>path</arg_key>
    //   <arg_value>/</arg_value>
    //   </tool_call>
    // Без этого вызов не распознавался и «утекал» пользователю сырым текстом.
    if (!calls.length) {
      for (const c of parseXmlToolCalls(text)) calls.push(c);
    }
    // Fallback C: формат {"name":...,"args":...} / массив таких объектов.
    // Модели Mistral (mistral-tiny/-small и др.), когда провайдер НЕ вернул
    // native tool_calls, выкладывают вызов в ТЕКСТ в OpenAI-подобном виде:
    //   [{"name":"fs_read","args":{"path":"/f.smali","offset":1,"limit":50}}]
    //   {"name":"fs_replace","arguments":{...}}
    // Ключ имени — "name" (а не "tool"), аргументы — "args"/"arguments"/"parameters"
    // (иногда строкой с вложенным JSON). Раньше такой ответ не парсился → агент
    // «ничего не делал», плодил assistant-сообщения и падал на Mistral с HTTP 400
    // (invalid_request_message_order). Теперь распознаём и его.
    if (!calls.length) {
      for (const c of parseNameArgsToolCalls(text, seen)) calls.push(c);
    }
    // Сортируем в порядке появления в тексте.
    calls.sort((a,b) => a.index - b.index);
    return calls;
  }
  // Разбор tool-call в формате {"name":..,"args"/"arguments"/"parameters":..} и
  // массивов таких объектов. Ищем сбалансированные JSON-объекты, содержащие "name",
  // где угодно в тексте (в т.ч. внутри [ ... ]). Порт-совместимо с Android.
  function parseNameArgsToolCalls(text, seen) {
    const out = [];
    if (!text) return out;
    const rxName = /\{\s*"name"\s*:/g;
    let mm;
    while ((mm = rxName.exec(text)) !== null) {
      const start = mm.index;
      const jsonStr = extractBalancedJson(text, start);
      if (!jsonStr) continue;
      const obj = tryParseToolJson(jsonStr);
      if (!obj || typeof obj.name !== 'string') continue;
      // Имя должно быть похоже на инструмент (только tool-подобные ключи),
      // чтобы случайные объекты с полем "name" не трактовались как вызовы.
      const name = obj.name;
      let args = obj.args != null ? obj.args
               : (obj.arguments != null ? obj.arguments
               : (obj.parameters != null ? obj.parameters : {}));
      if (typeof args === 'string') { const p = tryParseToolJson(args); args = (p && typeof p === 'object') ? p : {}; }
      if (!args || typeof args !== 'object') args = {};
      const key = 'na:' + start + ':' + jsonStr.length;
      if (seen && seen.has(key)) continue;
      if (seen) seen.add(key);
      out.push({ tool: name, args, raw: jsonStr, block: jsonStr, index: start });
    }
    return out;
  }
  // Разбор XML-стиля <tool_call>name ...<arg_key>/<arg_value> pairs...</tool_call>.
  function parseXmlToolCalls(text) {
    const calls = [];
    if (!text) return calls;
    const blockRx = /<tool_call>\s*([\s\S]*?)<\/tool_call>/gi;
    const pairRx = /<arg_key>\s*([\s\S]*?)\s*<\/arg_key>\s*<arg_value>([\s\S]*?)<\/arg_value>/gi;
    let bm;
    while ((bm = blockRx.exec(text)) !== null) {
      const body = bm[1] || '';
      // Имя инструмента: до первого <arg_key> (или до конца), либо <tool_name>.
      let head = body;
      const at = head.indexOf('<arg_key');
      if (at >= 0) head = head.slice(0, at);
      let name;
      const nameTag = /<tool_name>\s*([\s\S]*?)\s*<\/tool_name>/i.exec(head);
      if (nameTag) name = nameTag[1].trim();
      else name = head.replace(/<[^>]*>/g, ' ').trim();
      if (name) name = name.split(/\s+/)[0].trim();
      if (!name) continue;
      const args = {};
      pairRx.lastIndex = 0;
      let pm;
      while ((pm = pairRx.exec(body)) !== null) {
        const k = (pm[1] || '').trim();
        const v = pm[2] == null ? '' : pm[2].trim();
        if (!k) continue;
        args[k] = coerceXmlArgValue(v);
      }
      calls.push({ tool: name, args, raw: bm[0], block: bm[0], index: bm.index });
    }
    return calls;
  }
  // Приводит строковое значение XML-аргумента к number/boolean/JSON/строке.
  function coerceXmlArgValue(v) {
    if (v == null) return '';
    const t = String(v).trim();
    if (t === '') return '';
    if (t === 'true') return true;
    if (t === 'false') return false;
    if (/^-?\d+$/.test(t)) { const n = parseInt(t, 10); if (Number.isSafeInteger(n)) return n; }
    if (/^-?\d+\.\d+$/.test(t)) { const n = parseFloat(t); if (isFinite(n)) return n; }
    if ((t.startsWith('{') && t.endsWith('}')) || (t.startsWith('[') && t.endsWith(']'))) {
      try { return JSON.parse(t); } catch(_){}
    }
    return v; // строка как есть (не trim'аем, чтобы не портить содержимое файла)
  }
  // Вырезает сбалансированный по фигурным скобкам JSON-объект из строки,
  // начиная с позиции start (где стоит '{'). Учитывает строки и экранирование,
  // чтобы '}' внутри строкового значения не завершал объект раньше времени.
  // Возвращает подстроку JSON или '' если объект не закрыт.
  function extractBalancedJson(text, start) {
    let depth = 0, inStr = false, esc = false;
    for (let i = start; i < text.length; i++) {
      const ch = text[i];
      if (inStr) {
        if (esc) { esc = false; }
        else if (ch === '\\') { esc = true; }
        else if (ch === '"') { inStr = false; }
        continue;
      }
      if (ch === '"') { inStr = true; continue; }
      if (ch === '{') depth++;
      else if (ch === '}') {
        depth--;
        if (depth === 0) return text.slice(start, i + 1);
      }
    }
    return '';
  }
  // Толерантный парсер JSON: сначала честный JSON.parse, при неудаче — вырезаем
  // trailing-запятые, комментарии и пробуем снова. Возвращает объект или null.
  function tryParseToolJson(raw) {
    try { return JSON.parse(raw); } catch(_){}
    // Простая эвристика: обрезать всё после последней «}» — модель могла
    // прицепить текст-объяснение после закрывающей скобки.
    const lastBrace = raw.lastIndexOf('}');
    if (lastBrace > 0 && lastBrace < raw.length - 1) {
      try { return JSON.parse(raw.slice(0, lastBrace+1)); } catch(_){}
    }
    // Убрать trailing commas, //-комментарии.
    const cleaned = raw
      .replace(/\/\/[^\n]*$/gm, '')
      .replace(/,\s*([}\]])/g, '$1');
    try { return JSON.parse(cleaned); } catch(_){}
    return null;
  }

  // ---- Notify sound (щелчок при получении сообщения от агента) ----
  const NOTIFY_URL = (typeof window !== 'undefined' && window.__NOTIFY_MP3_DATAURL__) || 'css/notify.mp3';
  let _notifyAudio = null;
  function _ensureNotifyAudio() {
    if (_notifyAudio) return _notifyAudio;
    try { _notifyAudio = new Audio(NOTIFY_URL); _notifyAudio.preload = 'auto'; }
    catch(_) { _notifyAudio = null; }
    return _notifyAudio;
  }
  function playNotifySound() {
    try {
      const s = Store.get();
      if (s.settings && s.settings.notifySound === false) return;
      const vol = Math.max(0, Math.min(1, +s.settings.notifyVolume));
      const a = _ensureNotifyAudio();
      if (!a) return;
      const inst = a.cloneNode(true);
      inst.volume = isFinite(vol) ? vol : 0.9;
      const p = inst.play();
      if (p && typeof p.catch === 'function') p.catch(()=>{});
    } catch(_) { /* без звука — не критично */ }
  }

  // ---- Message rendering ----
  function renderMessages() {
    const cont = U.$('#ai-messages');
    cont.innerHTML = '';
    const hist = Store.activeChat().history || [];
    for (const m of hist) appendMsgEl(m);
    cont.scrollTop = cont.scrollHeight;
  }
  function appendMsgEl(m) {
    const cont = U.$('#ai-messages');
    if (m.hidden || m.silent) return;
    if (m.role === 'tool') {
      // Only show tool bubbles when NOT silent (default: hidden — they appear in status log)
      const el = U.el('div', { class:'msg tool' },
        U.el('div', { class:'role-hdr' }, 'TOOL ' + (m.name||'')),
        U.el('pre', {}, m.content || '')
      );
      attachDeleteButton(el, m);
      attachSystemMsgActions(el, m);
      cont.appendChild(el); return el;
    }
    if (m.role === 'error') {
      const el = U.el('div', { class:'msg error' }, m.content || '');
      attachDeleteButton(el, m);
      attachSystemMsgActions(el, m);
      cont.appendChild(el); return el;
    }
    if (m.role === 'system-note') {
      const el = U.el('div', { class:'msg system' }, m.content);
      attachDeleteButton(el, m);
      attachSystemMsgActions(el, m);
      cont.appendChild(el); return el;
    }
    if (m.role === 'status') {
      // Restore a previously-finalized status bubble (collapsed, with × to remove).
      // createStatus() itself appends into #ai-messages and returns api. We pass msgRef so
      // that clicking × removes the record from history too.
      createStatus({ data: m.data, msgRef: m });
      // Attach msg reference to close button after creation isn't needed — createStatus reads msgRef.
      // Return the just-appended element (last child).
      return cont.lastElementChild;
    }
    const cls = 'msg '+(m.role==='user'?'user':'assistant') + (m.interject ? ' interject':'');
    const el = U.el('div', { class: cls });
    // Attachments (only for user messages)
    if (m.role === 'user' && Array.isArray(m.attachments) && m.attachments.length) {
      const grid = U.el('div', { class:'msg-attachments' });
      for (const a of m.attachments) {
        if (a.kind === 'image' && a.dataUrl) {
          const img = U.el('img', { class:'att-thumb', src: a.dataUrl, alt: a.name || 'image' });
          img.addEventListener('click', () => showImagePreview(a.dataUrl, a.name));
          const wrap = U.el('div', { class:'att-img-wrap', title: a.name || '' }, img);
          if (a.name) wrap.appendChild(U.el('div', { class:'att-name' }, a.name));
          grid.appendChild(wrap);
        } else {
          const chip = U.el('div', { class:'att-chip', title: a.name || '' },
            U.el('span', { class:'att-icon' }, fileIcon(a.name || a.path || '')),
            U.el('span', { class:'att-chip-name' }, a.name || a.path || 'file'),
            (a.size != null ? U.el('span', { class:'att-chip-size' }, humanSize(a.size)) : null)
          );
          grid.appendChild(chip);
        }
      }
      el.appendChild(grid);
    }
    if (m.role === 'assistant') {
      // Render markdown safely with DOMPurify + marked. Strip tool blocks for display.
      // Resolve chatimg:<id> refs to inline data URLs so images fetched via
      // http_fetch actually render as <img> in the chat.
      const shown = resolveMediaRefs(stripToolBlocks(m.content || ''));
      if (shown) {
        const body = U.el('div', { class:'msg-body' });
        body.innerHTML = DOMPurify.sanitize(marked.parse(shown), {
          ADD_ATTR: ['target','controls','playsinline','preload','allowfullscreen'],
          ADD_TAGS: ['video','audio','source','iframe'],
          ADD_DATA_URI_TAGS: ['img','video','audio','source','iframe']
        });
        el.appendChild(body);
      }
    } else {
      if (m.content) el.appendChild(U.el('div', { class:'msg-body' }, m.content));
    }
    // For user messages: tap to reveal edit+copy icons.
    if (m.role === 'user') attachUserMsgEditor(el, m);
    // For assistant messages: tap to reveal model badge (top) + copy/regen icons (bottom).
    if (m.role === 'assistant') attachAssistantMsgActions(el, m);
    attachDeleteButton(el, m);
    cont.appendChild(el);
    return el;
  }

  // ---- Универсальная кнопка «удалить сообщение» ----
  // Появляется в правом верхнем углу любого сообщения (user, assistant, tool,
  // error, system-note). У блока role:'status' своя кнопка × — эту функцию
  // для него не вызываем. Клик → confirm → удаление из chat.history + rerender.
  const ICON_TRASH = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="3 6 5 6 21 6"/><path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"/><path d="M10 11v6M14 11v6"/><path d="M9 6V4a2 2 0 0 1 2-2h2a2 2 0 0 1 2 2v2"/></svg>';
  function attachDeleteButton(el, m) {
    if (!el || !m) return;
    const btn = U.el('button', {
      class: 'msg-del-btn',
      title: 'Удалить сообщение из чата',
      'aria-label': 'delete'
    });
    btn.innerHTML = ICON_TRASH;
    btn.addEventListener('click', async (e) => {
      e.stopPropagation();
      const chat = Store.activeChat();
      const idx = chat.history.indexOf(m);
      if (idx >= 0) {
        chat.history.splice(idx, 1);
        chat.updatedAt = Date.now();
        Store.save();
      }
      renderMessages();
    });
    el.appendChild(btn);
  }

  // ---- Панель действий для системных сообщений (error / system-note / tool) ----
  // Аналогично assistant-сообщениям: клик по пузырю → снизу-слева появляется
  // ряд с иконкой регенерации. Повторный клик — скрывает. При клике на
  // кнопку удаления/ссылку/пре-код — тогглить не будем (событие останавливается).
  // regenerate() обрежет chat.history с индекса этого сообщения и запустит цикл.
  function attachSystemMsgActions(el, m) {
    if (!el || !m) return;
    let actionRow = null;
    function hide() {
      if (actionRow) { actionRow.remove(); actionRow = null; el.classList.remove('actions-open'); }
    }
    function show() {
      if (actionRow) return;
      actionRow = U.el('div', { class:'msg-actions system' });
      const regen = iconButton(ICON_REGEN, 'Регенерировать (повторить с этого места)', () => {
        hide();
        if (running) { UI.toast('Дождитесь завершения текущего ответа', 'err'); return; }
        regenerate(m);
      });
      regen.classList.add('accent-regen');
      actionRow.appendChild(regen);
      if (el.parentElement) el.parentElement.insertBefore(actionRow, el.nextSibling);
      el.classList.add('actions-open');
    }
    el.addEventListener('click', (ev) => {
      // Не тогглить на клики по служебным элементам (кнопки, ссылки, code-блоки, pre)
      if (ev.target.closest('a,button,pre,code')) return;
      if (actionRow) hide(); else show();
    });
  }

  // ---- Copy-to-clipboard helper ----
  async function copyText(text) {
    try {
      if (navigator.clipboard && navigator.clipboard.writeText) {
        await navigator.clipboard.writeText(text || '');
        return true;
      }
    } catch(_){}
    try {
      const ta = document.createElement('textarea');
      ta.value = text || '';
      ta.style.position = 'fixed'; ta.style.left = '-9999px';
      document.body.appendChild(ta);
      ta.select();
      document.execCommand('copy');
      ta.remove();
      return true;
    } catch(_){ return false; }
  }
  // Убирает из текста tool-блоки всех поддерживаемых форматов (```/~~~, tool/json/js).
  // Оставляет только «читаемую» часть ответа модели.
  function stripToolBlocks(text) {
    if (!text) return '';
    let out = text
      .replace(/```(?:tool|json|javascript|js)?[\s\S]*?```/gi, '')
      .replace(/~~~(?:tool|json|javascript|js)?[\s\S]*?~~~/gi, '');
    // Убираем «голые» tool-вызовы {"tool":...} (формат Mistral и др.), а также
    // прилипшую перед ними метку «tool» (напр. «...найду код.tool\n{"tool":...}»).
    // Ищем каждое вхождение {"tool" и вырезаем сбалансированный JSON + метку.
    const rxTool = /\{\s*"tool"\s*:/g;
    let mm, cuts = [];
    while ((mm = rxTool.exec(out)) !== null) {
      const jsonStr = extractBalancedJson(out, mm.index);
      if (!jsonStr) continue;
      let from = mm.index;
      // Съедаем прилипшую метку «tool» (с возможными :, пробелами, \n) перед JSON.
      const before = out.slice(Math.max(0, from - 8), from);
      const mLabel = before.match(/tool\s*:?\s*$/i);
      if (mLabel) from -= mLabel[0].length;
      cuts.push([from, mm.index + jsonStr.length]);
    }
    // Вырезаем справа налево, чтобы не сбить индексы.
    for (let i = cuts.length - 1; i >= 0; i--) {
      out = out.slice(0, cuts[i][0]) + out.slice(cuts[i][1]);
    }
    // То же для формата {"name":...,"args":...} (Mistral в тексте, см. parseNameArgsToolCalls).
    const rxName = /\{\s*"name"\s*:/g;
    let nm, ncuts = [];
    while ((nm = rxName.exec(out)) !== null) {
      const jsonStr = extractBalancedJson(out, nm.index);
      if (!jsonStr) continue;
      const obj = tryParseToolJson(jsonStr);
      if (!obj || typeof obj.name !== 'string') continue;
      ncuts.push([nm.index, nm.index + jsonStr.length]);
    }
    for (let i = ncuts.length - 1; i >= 0; i--) {
      out = out.slice(0, ncuts[i][0]) + out.slice(ncuts[i][1]);
    }
    // Убираем осиротевшие скобки/запятые массива вызовов ([ , ]) и метку «tool».
    out = out.replace(/(^|\n)\s*[\[\],]+\s*(?=\n|$)/g, '$1');
    // Убираем XML-стиль <tool_call>...</tool_call> (см. parseXmlToolCalls).
    out = out.replace(/<tool_call>[\s\S]*?<\/tool_call>/gi, '');
    return out.trim();
  }
  function visibleAssistantText(m) {
    return stripToolBlocks(m.content || '');
  }

  // Icon-only SVGs (no text labels)
  const ICON_COPY  = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>';
  const ICON_EDIT  = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 20h9"/><path d="M16.5 3.5a2.121 2.121 0 0 1 3 3L7 19l-4 1 1-4L16.5 3.5z"/></svg>';
  const ICON_REGEN = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="23 4 23 10 17 10"/><polyline points="1 20 1 14 7 14"/><path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15"/></svg>';
  const ICON_CHECK = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"/></svg>';

  function iconButton(iconHtml, title, onClick) {
    const btn = U.el('button', { class:'msg-icon-btn', title, 'aria-label':title });
    btn.innerHTML = iconHtml;
    btn.addEventListener('click', (e) => { e.stopPropagation(); onClick(btn, e); });
    return btn;
  }
  function makeCopyIconBtn(getText) {
    return iconButton(ICON_COPY, 'Копировать', async (b) => {
      const ok = await copyText(typeof getText === 'function' ? getText() : getText);
      if (ok) {
        b.classList.add('ok');
        b.innerHTML = ICON_CHECK;
        setTimeout(() => { b.classList.remove('ok'); b.innerHTML = ICON_COPY; }, 1200);
      } else {
        UI.toast('Не удалось скопировать', 'err');
      }
    });
  }
  function makeModelBadge(model) {
    const idSpan = U.el('span', { class:'mmb-id', title:'Долгое нажатие / выделение — можно скопировать' }, model);
    const badge = U.el('div', { class:'msg-model-badge', title:'Реальный ID модели, вернувшей ответ (напрямую по base URL)' },
      U.el('span', { class:'mmb-dot' }, ''),
      U.el('span', { class:'mmb-label' }, 'модель:'),
      idSpan
    );
    // Long-press → копирование ID в буфер обмена (дополнительно к нативному выделению текста).
    let lpTimer = null;
    const startLP = () => {
      clearTimeout(lpTimer);
      lpTimer = setTimeout(async () => {
        lpTimer = null;
        try {
          await navigator.clipboard.writeText(model);
          UI.toast('ID модели скопирован: '+model, 'ok');
        } catch(_){
          // fallback: выделить текст, чтобы пользователь мог скопировать вручную
          try {
            const range = document.createRange(); range.selectNodeContents(idSpan);
            const sel = window.getSelection(); sel.removeAllRanges(); sel.addRange(range);
          } catch(_){}
        }
      }, 500);
    };
    const cancelLP = () => { if (lpTimer) { clearTimeout(lpTimer); lpTimer = null; } };
    idSpan.addEventListener('touchstart', startLP, { passive:true });
    idSpan.addEventListener('touchmove', cancelLP, { passive:true });
    idSpan.addEventListener('touchend', cancelLP);
    idSpan.addEventListener('touchcancel', cancelLP);
    idSpan.addEventListener('mousedown', startLP);
    idSpan.addEventListener('mouseup', cancelLP);
    idSpan.addEventListener('mouseleave', cancelLP);
    // Клик по бейджу не должен закрывать блок действий (родительский .msg перехватывает клики).
    badge.addEventListener('click', (ev) => { ev.stopPropagation(); });
    return badge;
  }

  // Actions panel for USER messages: tap → [copy][edit] below.
  function attachUserMsgEditor(el, m) {
    let actionRow = null;
    function hide() { if (actionRow) { actionRow.remove(); actionRow = null; el.classList.remove('actions-open'); } }
    function show() {
      if (actionRow) return;
      actionRow = U.el('div', { class:'msg-actions user' });
      actionRow.appendChild(makeCopyIconBtn(() => m.content || ''));
      const editBtn = iconButton(ICON_EDIT, 'Редактировать', () => {
        const input = U.$('#ai-input');
        if (!input) return;
        input.value = m.content || '';
        input.dispatchEvent(new Event('input', { bubbles:true }));
        input.focus();
        UI.toast('Текст сообщения скопирован в поле ввода', 'ok');
        hide();
      });
      editBtn.classList.add('accent-edit');
      actionRow.appendChild(editBtn);
      if (el.parentElement) el.parentElement.insertBefore(actionRow, el.nextSibling);
      el.classList.add('actions-open');
    }
    el.addEventListener('click', (ev) => {
      if (ev.target.closest('.msg-attachments img')) return;
      if (ev.target.closest('button')) return;
      if (actionRow) hide(); else show();
    });
  }

  // Actions for ASSISTANT messages. On tap: two rows are inserted BELOW the bubble:
  //   1) model-id badge (top)
  //   2) [copy] [regenerate] icons (bottom)
  // Regenerate only for the LAST visible assistant message.
  function attachAssistantMsgActions(el, m) {
    const rows = []; // [modelRow, iconRow]
    function hide() {
      for (const r of rows) r.remove();
      rows.length = 0;
      el.classList.remove('actions-open');
    }
    function show() {
      if (rows.length) return;
      const model = (m.realModel || '').trim();
      // 1) top: model badge (только если известно)
      let insertAfter = el;
      if (model) {
        const modelRow = U.el('div', { class:'msg-actions assistant top' }, makeModelBadge(model));
        el.parentElement.insertBefore(modelRow, insertAfter.nextSibling);
        rows.push(modelRow);
        insertAfter = modelRow;
      }
      // 2) bottom: icons (copy + regenerate). Регенерация доступна на ЛЮБОМ
      // сообщении агента (не только последнем): regenerate() обрежет историю с
      // этого места и повторит запрос — так пользователь может «откатиться» и
      // перегенерировать с любого шага.
      const iconRow = U.el('div', { class:'msg-actions assistant bottom' });
      iconRow.appendChild(makeCopyIconBtn(() => visibleAssistantText(m)));
      const regen = iconButton(ICON_REGEN, 'Регенерировать (повторить с этого места)', () => { hide(); regenerate(m); });
      regen.classList.add('accent-regen');
      iconRow.appendChild(regen);
      el.parentElement.insertBefore(iconRow, insertAfter.nextSibling);
      rows.push(iconRow);
      el.classList.add('actions-open');
    }
    el.addEventListener('click', (ev) => {
      if (ev.target.closest('a,button,pre code')) return;
      if (rows.length) hide(); else show();
    });
  }

  // Regenerate: trim history at this assistant msg, then re-run the send loop.
  async function regenerate(assistantMsg) {
    if (running) { UI.toast('Дождитесь завершения текущего ответа', 'err'); return; }
    const provider = currentProvider();
    if (!provider) { UI.toast('Провайдер не настроен', 'err'); return; }
    // Привязываем регенерацию к текущему чату (см. комментарий в send()).
    _runChatId = Store.activeChat().id;
    const chat = runChat();
    const idx = chat.history.indexOf(assistantMsg);
    if (idx < 0) { _runChatId = null; return; }
    chat.history.splice(idx);
    chat.updatedAt = Date.now();
    Store.save();
    renderMessages();
    running = true;
    abortCtrl = new AbortController();
    setSendState(true);
    currentStatus = null;
    let status = ensureLiveStatus();
    status.set({ label:'Thinking', kind:'think' }, 'Регенерирую ответ...');
    try {
      const maxTool = Store.get().settings.maxToolCalls || 30;
      let toolCount = 0, step = 0;
      const loopDetector = makeToolLoopDetector();
      while (true) {
        step++;
        status = ensureLiveStatus();
        status.set({ label:'Thinking', kind:'think' }, step === 1 ? 'Регенерирую ответ...' : 'Продолжаю рассуждать...');
        const messages = buildLLMMessages();
        let text, realModel;
        const stopHeartbeat = startWaitingHeartbeat(status, step === 1 ? 'Регенерирую' : 'Продолжаю');
        let nativeToolCalls = [];
        try {
          const resp = await callProviderWithBackoff(provider, messages, abortCtrl.signal,
            (msg) => { stopHeartbeat(); status.set({ label:'Retry', kind:'think' }, msg); });
          text = resp.text; realModel = resp.realModel || '';
          // Показать реальное имя модели (по base URL) под окном статуса.
          if (realModel && status && status.setModel) status.setModel(realModel);
          nativeToolCalls = Array.isArray(resp.toolCalls) ? resp.toolCalls : [];
          // repetition-loop без tool_calls — модель залипла и ничего не делает.
          // Прерываем цикл: показываем текст пользователю, снимаем running,
          // не отправляем очередной turn (иначе получит свой мусор и залипнет).
          if (resp.loopSevere && !nativeToolCalls.length) {
            // Оставляем карточку статуса (persist), пользователь закроет её сам.
            if (status && status.persist) status.persist({ label:'Ошибка', kind:'err' }, 'Модель зациклилась');
            pushMsg({ role:'assistant', content: text || '', realModel });
            pushMsg({ role:'error', content: 'Модель зациклилась (паттерн повторён '+resp.loopCount+' раз) — цикл остановлен. Совет: очистите чат и переформулируйте задачу, либо выберите другую модель.' });
            break;
          }
        } catch(e){
          stopHeartbeat();
          if (isAbortError(e)) throw e;
          const emsg = (e && e.message ? e.message : 'Ошибка провайдера: неизвестная ошибка');
          if (status && status.persist) status.persist({ label:'Ошибка', kind:'err' }, 'Ошибка провайдера', emsg);
          pushMsg({ role:'error', content: emsg });
          break;
        } finally { stopHeartbeat(); }
        // Пустой ответ = ни текста, ни tool_calls.
        if (!text && !nativeToolCalls.length) {
          if (status && status.persist) status.persist({ label:'Ошибка', kind:'err' }, 'Пустой ответ модели');
          pushMsg({ role:'error', content: emptyResponseMessage() });
          break;
        }
        // Сохраняем assistant-сообщение; если tool_calls были native — сохраняем их
        // рядом, чтобы buildLLMMessages в следующем turn построил корректный
        // OpenAI-запрос (assistant.tool_calls + за ним role:'tool').
        const asstMsg = { role:'assistant', content:text || '', realModel, silent:true };
        if (nativeToolCalls.length) asstMsg._toolCalls = nativeToolCalls;
        pushMsg(asstMsg);
        const { reasoning, calls } = splitReasoning(text, nativeToolCalls);
        if (reasoning) status.setReasoning(reasoning);
        if (!calls.length) {
          status.persist({ label:'Готово', kind:'ok' }, 'Регенерация завершена');
          const chat2 = runChat();
          const last = chat2.history[chat2.history.length - 1];
          if (last && last.role === 'assistant') {
            delete last.silent; Store.save();
            if (isRunChatVisible()) { appendMsgEl(last); U.$('#ai-messages').scrollTop = 1e9; }
            playNotifySound();
          }
          break;
        }
        for (const c of calls) {
          if (toolCount++ >= maxTool) { status.persist({ label:'Лимит', kind:'err' }, 'Достигнут лимит вызовов'); pushMsg({ role:'error', content:'Лимит вызовов инструментов ('+maxTool+')' }); running=false; setSendState(false); return; }
          if (abortCtrl.signal.aborted) throw new Error('aborted');
          await throttleBeforeTool(abortCtrl.signal, (m) => status.set({ label:'Wait', kind:'think' }, m));
          const lbl = labelFor(c.tool, c.args, 'pres');
          status.set({ label: lbl.op, kind:'think' }, lbl.detail || c.tool);
          const t0 = performance.now();
          const res = await Tools.execute(c.tool, c.args, (patch) => {
            if (patch && patch.detail != null) status.set({ label: lbl.op, kind:'think' }, patch.detail);
          });
          const past = labelFor(c.tool, c.args, 'past');
          const resSum = summarizeToolResult(c.tool, c.args, res);
          status.appendLog({ ok: res.ok, op: past.op + (past.detail?' '+past.detail:''),
            detail: (res.ok ? resSum : (res.error||'error')) + ' · ' + Math.round(performance.now()-t0)+'ms' });
          try { const preview = await buildFilePreview(c.tool, c.args, res); if (preview) status.appendFilePreview(preview); } catch(_){}
          status.set({ label: lbl.op, kind: res.ok?'ok':'err' },
            (res.ok?'Готово: ':'Ошибка: ') + (lbl.detail||c.tool) + (res.ok && resSum ? ' — ' + resSum : ''));
          const trimmed = serializeToolResultForModel(c.tool, res);
          const toolMsg = { role:'tool', name:c.tool, content:(res.ok?'':'ERROR: ')+trimmed+truncationNote(c), silent:true };
          if (c.native && c.id) toolMsg._toolCallId = c.id;
          attachToolImageToMsg(toolMsg, res);
          pushMsg(toolMsg);

          const stall = loopDetector.observe(c.tool, c.args, res);
          if (stall && stall.nudge) {
            // Мягкое подталкивание (без обрыва): read-only повтор — данные уже есть.
            pushMsg({ role:'system-note', hidden:true, content: stall.note });
            status.set({ label:'Действую', kind:'think' }, 'Данные уже прочитаны — подталкиваю к следующему шагу...');
          } else if (stall && stall.stop) {
            status.persist({ label:'Остановлено', kind:'err' }, 'Обнаружен зацикленный вызов инструмента');
            pushMsg({ role:'error', content: '⚠ ' + stall.reason });
            playNotifySound();
            running = false; abortCtrl = null; currentStatus = null; setSendState(false);
            return;
          }
        }
      }
    } catch(e){
      const aborted = isAbortError(e);
      const emsg = e && e.message ? e.message : 'Ошибка';
      try {
        if (aborted && status) status.persist({ label:'Остановлено', kind:'err' }, 'Задача остановлена пользователем');
        // Не удаляем карточку статуса при ошибке — оставляем (persist).
        else if (status && status.persist) status.persist({ label:'Ошибка', kind:'err' }, 'Ошибка', emsg);
      } catch(_){}
      // Реальный ответ провайдера всегда пушим отдельным сообщением-ошибкой,
      // чтобы юзер видел полный текст рядом с оставшейся карточкой статуса.
      if (!aborted) pushMsg({ role:'error', content: emsg });
    } finally {
      running = false; abortCtrl = null; currentStatus = null; _runChatId = null; setSendState(false);
    }
  }

  function fileIcon(name) {
    const ext = (name.split('.').pop() || '').toLowerCase();
    if (['js','ts','jsx','tsx','mjs','cjs'].includes(ext)) return '📜';
    if (['py'].includes(ext)) return '🐍';
    if (['json','yaml','yml','toml'].includes(ext)) return '⚙️';
    if (['md','txt','rst'].includes(ext)) return '📝';
    if (['html','htm','xml'].includes(ext)) return '🌐';
    if (['css','scss','less'].includes(ext)) return '🎨';
    if (['zip','tar','gz','7z','rar'].includes(ext)) return '📦';
    if (['mp3','wav','ogg','flac','m4a'].includes(ext)) return '🎵';
    if (['mp4','webm','mov','mkv','avi'].includes(ext)) return '🎬';
    if (['pdf'].includes(ext)) return '📕';
    return '📄';
  }
  function humanSize(n) {
    if (!n && n !== 0) return '';
    if (n < 1024) return n + 'B';
    if (n < 1048576) return (n/1024).toFixed(1) + 'KB';
    return (n/1048576).toFixed(2) + 'MB';
  }
  function showImagePreview(dataUrl, name) {
    const overlay = U.el('div', { class:'img-preview-overlay' },
      U.el('div', { class:'img-preview-close' }, '✕'),
      U.el('img', { src:dataUrl, alt:name||'' })
    );
    overlay.addEventListener('click', () => overlay.remove());
    document.body.appendChild(overlay);
  }
  function pushMsg(m) {
    const chat = runChat();
    chat.history.push(m); chat.updatedAt = Date.now(); Store.save();
    // Рисуем в DOM только если чат этого запуска сейчас открыт. Иначе сообщение
    // молча уходит в историю своего чата (не «протекает» в открытый чат).
    if (!isRunChatVisible()) return;
    if (!m.hidden && !m.silent) appendMsgEl(m);
    const cont = U.$('#ai-messages'); cont.scrollTop = cont.scrollHeight;
  }
  function replaceLast(text) {
    const chat = Store.activeChat();
    const last = chat.history[chat.history.length-1];
    if (last && last.role === 'assistant') { last.content = text; Store.save(); }
    renderMessages();
  }

  function clear() {
    const chat = Store.activeChat();
    chat.history = []; chat.updatedAt = Date.now(); Store.save();
    renderMessages();
  }

  function currentProvider() {
    const s = Store.get();
    return s.providers.find(p => p.id === s.activeProviderId) || null;
  }

  // State of the header model-picker (per-app, in-memory).
  // Kept outside updateHeader so open/closed and search survive re-renders.
  const _headerPicker = { expanded: false, query: '', loading: false, _outsideBound: false };
  function _bindHeaderOutsideClick() {
    if (_headerPicker._outsideBound) return;
    _headerPicker._outsideBound = true;
    // Close panel when user taps anywhere outside its host.
    document.addEventListener('pointerdown', (e) => {
      if (!_headerPicker.expanded) return;
      const host = U.$('#ai-current-provider');
      if (!host) return;
      if (host.contains(e.target)) return;
      _headerPicker.expanded = false;
      updateHeader();
    }, true);
  }

  // Кружок-индикатор стоимости модели для списка выбора в шапке чата.
  // 'free' → зелёный, 'paid' → красный, 'unknown' → серый.
  function costDot(tier) {
    const color = tier === 'free' ? '#3fb950' : (tier === 'paid' ? '#f85149' : '#6e7681');
    const title = tier === 'free' ? 'Доступна по базовому тарифу'
                : tier === 'paid' ? 'Недоступна по базовому тарифу (платная / лимит исчерпан)'
                : 'Статус неизвестен';
    return U.el('span', {
      class:'hmp-cost-dot',
      title,
      style:{
        flex:'0 0 auto', width:'10px', height:'10px', borderRadius:'50%',
        background:color, marginLeft:'8px', alignSelf:'center',
        boxShadow: tier==='free' ? '0 0 5px rgba(63,185,80,.7)' : (tier==='paid' ? '0 0 5px rgba(248,81,73,.6)' : 'none')
      }
    });
  }

  function updateHeader() {
    const host = U.$('#ai-current-provider');
    if (!host) return;
    _bindHeaderOutsideClick();
    host.innerHTML = '';
    const p = currentProvider();
    if (!p) {
      host.textContent = 'не выбран';
      return;
    }
    // Trigger row (provider name + current model badge acts as dropdown toggle)
    const caret = U.el('span', { class:'hmp-caret' }, _headerPicker.expanded ? '▾' : '▸');
    const nameEl = U.el('span', { class:'hmp-name' }, p.name);
    const modelEl = U.el('span', { class:'hmp-model' }, p.model || 'модель не выбрана');
    const trigger = U.el('button', {
      class: 'hmp-trigger',
      type: 'button',
      title: 'Выбрать модель',
      'aria-expanded': _headerPicker.expanded ? 'true' : 'false'
    }, caret, nameEl, U.el('span', { class:'hmp-sep' }, '·'), modelEl);
    trigger.addEventListener('click', (e) => {
      e.preventDefault();
      _headerPicker.expanded = !_headerPicker.expanded;
      updateHeader();
    });
    host.appendChild(trigger);

    if (!_headerPicker.expanded) return;

    // Dropdown panel
    const panel = U.el('div', { class:'hmp-panel' });
    const toolbar = U.el('div', { class:'hmp-toolbar' });
    const search = U.el('input', {
      class:'hmp-search',
      type:'text',
      placeholder:'Поиск по названию/id…',
      value:_headerPicker.query
    });
    search.addEventListener('input', () => {
      _headerPicker.query = search.value;
      renderList();
    });
    // Autofocus search when opening; but keep caret cursor for user text.
    setTimeout(() => { try { search.focus({ preventScroll:true }); } catch(_){} }, 0);
    const refreshBtn = U.el('button', { class:'hmp-refresh', type:'button', title:'Обновить список моделей' }, '⟳');
    refreshBtn.addEventListener('click', async (e) => {
      e.preventDefault();
      if (_headerPicker.loading) return;
      if (!(p.baseUrl||'').trim()) { UI.toast('У провайдера не задан Base URL','err'); return; }
      _headerPicker.loading = true;
      refreshBtn.disabled = true;
      refreshBtn.classList.add('spin');
      try {
        const res = await AI.listModels(p);
        // Persist into provider record
        const st = Store.get();
        const prov = st.providers.find(x => x.id === p.id);
        if (prov) { prov.models = res.models.slice(); Store.save(); }
        UI.toast('Загружено моделей: ' + res.models.length, 'ok');
        renderList();
      } catch (err) {
        UI.toast('Не удалось получить список: ' + (err.message||err).split('\n')[0], 'err');
      } finally {
        _headerPicker.loading = false;
        refreshBtn.disabled = false;
        refreshBtn.classList.remove('spin');
      }
    });
    toolbar.appendChild(search);
    toolbar.appendChild(refreshBtn);
    panel.appendChild(toolbar);

    const listBox = U.el('div', { class:'hmp-list' });
    panel.appendChild(listBox);
    host.appendChild(panel);

    function renderList() {
      const st = Store.get();
      const prov = st.providers.find(x => x.id === p.id) || p;
      const all = Array.isArray(prov.models) ? prov.models : [];
      const q = (_headerPicker.query||'').trim().toLowerCase();
      const filtered = q ? all.filter(m =>
        (m.id||'').toLowerCase().includes(q) || (m.name||'').toLowerCase().includes(q)
      ) : all;
      listBox.innerHTML = '';
      if (!all.length) {
        listBox.appendChild(U.el('div', { class:'hmp-empty' },
          'Список моделей пуст. Нажмите ⟳, чтобы загрузить.'
        ));
        return;
      }
      if (!filtered.length) {
        listBox.appendChild(U.el('div', { class:'hmp-empty' }, 'Ничего не найдено'));
        return;
      }
      for (const m of filtered) {
        const isCurrent = prov.model === m.id;
        const row = U.el('div', { class: 'hmp-row' + (isCurrent ? ' current' : '') });
        // Левый столбец: id + (name).
        const textCol = U.el('div', { class:'hmp-row-text' });
        textCol.appendChild(U.el('div', { class:'hmp-row-id' }, m.id));
        if (m.name && m.name !== m.id) {
          textCol.appendChild(U.el('div', { class:'hmp-row-name' }, m.name));
        }
        row.appendChild(textCol);
        // Кружок доступности по базовому тарифу: учитываем сохранённые результаты
        // реальной проверки (prov.probes), иначе — статическую цену.
        const probe = (prov.probes && prov.probes[m.id]) || undefined;
        const tier = availabilityTier(m, probe);
        row.appendChild(costDot(tier));
        row.addEventListener('click', () => {
          const st2 = Store.get();
          const pr = st2.providers.find(x => x.id === p.id);
          if (!pr) return;
          pr.model = m.id;
          Store.save();
          UI.toast('Модель выбрана: ' + m.id, 'ok');
          // Collapse after selection so header returns to its compact form.
          _headerPicker.expanded = false;
          _headerPicker.query = '';
          updateHeader();
        });
        listBox.appendChild(row);
      }
    }
    renderList();
  }

  // ---- Build messages for provider based on history + auto-context ----
  function buildLLMMessages() {
    const s = Store.get();
    const msgs = [{ role:'system', content: systemPrompt() }];
    // Во время работы агента строим контекст ИЗ ЧАТА ЗАПУСКА (runChat), а не из
    // активного, чтобы переключение чата не подменяло историю на лету.
    const hist = runChat().history || [];
    for (const m of hist) {
      if (m.role === 'user') {
        // Mark interject messages inline so the model sees priority context
        let content = m.interject ? '[ПРИОРИТЕТНОЕ СООБЩЕНИЕ ВО ВРЕМЯ РАБОТЫ] ' + (m.content||'') : (m.content||'');
        // Разделяем вложения на текстовые (инлайним в сам текст) и картинки
        // (кладём в служебное поле _images, которое билдеры провайдеров
        // превращают в multi-part user-message — так модель видит картинку
        // и через 3, и через 30 сообщений после её отправки).
        const images = [];
        if (Array.isArray(m.attachments) && m.attachments.length) {
          const textParts = [];
          for (const a of m.attachments) {
            if (a.kind === 'image' && a.dataUrl) {
              images.push({ dataUrl: a.dataUrl, name: a.name });
              // Даём модели явное текстовое напоминание: «здесь была картинка».
              // Полезно когда пользователь потом просит «перечисли все сообщения»:
              // модель видит текст и то что это было отправлено ей вместе с изображением.
              textParts.push(`[прикреплено изображение: ${a.name || 'image'}]`);
            } else if (a.kind === 'text' && a.text != null) {
              textParts.push(`Файл ${a.name}${a.path ? ' ('+a.path+')' : ''}:\n\`\`\`\n${a.text.length > 8000 ? a.text.slice(0,8000)+'\n...[truncated]' : a.text}\n\`\`\``);
            } else if (a.path) {
              textParts.push(`Прикреплён файл: ${a.path}${a.size?' ('+a.size+' bytes)':''} — прочитай его через fs_read/fs_read_image при необходимости.`);
            } else {
              textParts.push(`Прикреплён файл: ${a.name}${a.size?' ('+a.size+' bytes)':''}`);
            }
          }
          if (textParts.length) content = (content ? content + '\n\n' : '') + textParts.join('\n\n');
        }
        const userMsg = { role:'user', content };
        if (images.length) userMsg._images = images;
        msgs.push(userMsg);
      }
      else if (m.role === 'assistant') {
        // Пробрасываем _toolCalls в буфер сообщений — buildOpenAIBody решит,
        // использовать ли их как native tool_calls или проигнорировать.
        const am = { role: m.role, content: m.content };
        if (Array.isArray(m._toolCalls) && m._toolCalls.length) am._toolCalls = m._toolCalls;
        msgs.push(am);
      }
      else if (m.role === 'tool') {
        // Для NATIVE-режима buildOpenAIBody развернёт это в {role:'tool', tool_call_id}.
        // Для LEGACY-режима (без _toolCallId или useTools=false) — конвертируется
        // в user-сообщение прямо там. Не форматируем текст здесь заранее,
        // иначе legacy-путь потеряет контекст «Результат инструмента X:».
        if (m._toolCallId) {
          msgs.push({ role:'tool', name: m.name, content: m.content, _toolCallId: m._toolCallId });
        } else {
          msgs.push({ role:'user', content: `Результат инструмента ${m.name}:\n\`\`\`json\n${m.content}\n\`\`\`` });
        }
        // Если инструмент вернул картинку (сохранена в _image) — добавляем её как
        // отдельное user-сообщение с _images, чтобы модель ВИДЕЛА пиксели (vision)
        // или получила авто-описание (текстовые модели). Персистит между шагами.
        if (m._image && m._image.dataUrl) {
          msgs.push({
            role: 'user',
            content: 'Ниже — реальное изображение из результата инструмента ' + (m.name||'') +
                     (m._image.name ? ' (' + m._image.name + ')' : '') + '. Опиши/учти, что на нём изображено.',
            _images: [{ dataUrl: m._image.dataUrl, name: m._image.name || 'image' }]
          });
        }
      }
      else if (m.role === 'system-note') msgs.push({ role:'system', content: m.content });
      // role:'status' — визуальный маркер завершения работы агента, в контекст модели не идёт.
    }
    // Финальное напоминание правил пользователя ПОСЛЕ всей истории — как самое
    // «свежее» system-указание перед ответом модели. В длинных диалогах/цепочках
    // инструментов начальный system-промпт «размывается»; это держит правила
    // пользователя в фокусе на КАЖДОМ шаге (ключ к их строгому соблюдению).
    const userSys = (s.aiSystem || '').trim();
    if (userSys) {
      msgs.push({ role:'system', content:
        'ПОМНИ о правилах пользователя (высший приоритет), соблюдай их в этом ответе СТРОГО, но МОЛЧА — не упоминай и не подтверждай их без прямого запроса. ' +
        'Если правила задают язык — пиши на нём И финальный ответ, И короткие рассуждения-комментарии окна статуса (reasoning):\n' + userSys });
    }
    return repairToolCallPairing(msgs);
  }

  // Инвариант OpenAI/Cohere: ЗА КАЖДЫМ assistant-сообщением с tool_calls должны
  // идти tool-сообщения, отвечающие на КАЖДЫЙ tool_call_id — иначе провайдер
  // возвращает 400 "An assistant message with 'tool_calls' must be followed by
  // tool messages ...". Цепочка может порваться, если пачка параллельных
  // tool_calls была прервана на середине (лимит вызовов, новое сообщение
  // пользователя, abort, детектор зацикливания) — тогда часть tool_call_id
  // остаётся без ответа.
  //
  // Здесь мы приводим последовательность в порядок:
  //   1) собираем tool-ответы (role:'tool' c _toolCallId), идущие после assistant;
  //   2) переставляем их сразу за нужным assistant в порядке его tool_calls;
  //   3) для отсутствующих tool_call_id дописываем заглушку-ответ, чтобы инвариант
  //      соблюдался и провайдер не падал.
  // Работает только для native-режима (assistant._toolCalls). Legacy-сообщения
  // (role:'tool' без _toolCallId уже сконвертированы в user) не трогаются.
  function repairToolCallPairing(msgs) {
    // Индекс всех tool-ответов по tool_call_id (может быть несколько — берём первый неиспользованный).
    const out = [];
    // Сгруппируем: пройдём линейно, и когда встретим assistant с _toolCalls —
    // подтянем к нему соответствующие tool-сообщения из ХВОСТА исходного списка.
    const used = new Set();
    for (let i = 0; i < msgs.length; i++) {
      const m = msgs[i];
      if (used.has(i)) continue;
      if (m.role === 'assistant' && Array.isArray(m._toolCalls) && m._toolCalls.length) {
        out.push(m);
        // Для каждого tool_call ищем ближайший неиспользованный tool-ответ с этим id.
        for (const tc of m._toolCalls) {
          const wantId = tc.id;
          let found = -1;
          for (let j = i + 1; j < msgs.length; j++) {
            if (used.has(j)) continue;
            const t = msgs[j];
            if (t.role === 'tool' && t._toolCallId === wantId) { found = j; break; }
            // Не перешагиваем через следующий assistant с tool_calls — его ответы отдельно.
            if (t.role === 'assistant' && Array.isArray(t._toolCalls) && t._toolCalls.length) break;
          }
          if (found >= 0) { out.push(msgs[found]); used.add(found); }
          else {
            // Ответа нет — синтезируем заглушку, чтобы соблюсти инвариант.
            out.push({ role:'tool', name: tc.name || '', content:
              JSON.stringify({ error: 'Вызов инструмента был прерван и не выполнен (нет результата).' }),
              _toolCallId: wantId });
          }
        }
      } else if (m.role === 'tool' && m._toolCallId) {
        // «Осиротевший» tool-ответ без предшествующего assistant.tool_calls
        // (напр. соответствующий assistant удалён пользователем) — провайдер
        // отвергнет tool без tool_calls. Конвертируем его в обычный user-текст.
        out.push({ role:'user', content: 'Результат инструмента ' + (m.name||'') + ':\n' + (m.content||'') });
        used.add(i);
      } else {
        out.push(m);
      }
    }
    return out;
  }

  // --- Live status bubble: reasoning + real operation log ---
  // Если передан options.data — восстанавливаем финализированный статус из истории
  // (не создаём live-инстанс, а рендерим сохранённое). options.msgRef — ссылка на запись
  // в chat.history (role:'status'), чтобы кнопка × могла её удалить.
  function createStatus(options) {
    options = options || {};
    const restore = options.data || null;
    const msgRef = options.msgRef || null;
    // detached=true — НЕ добавляем карточку в DOM (агент работает в чате, который
    // сейчас не открыт). Обновления копятся в snapshot; когда пользователь вернётся
    // в рабочий чат, карточка присоединяется через api.attach().
    const detached = !!options.detached;
    const cont = U.$('#ai-messages');
    const wrap = U.el('div', { class:'status-block' });
    // Иконка chevron для сворачивания
    const chevronSvg = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><polyline points="6 9 12 15 18 9"/></svg>';
    const toggleBtn = U.el('button', { class:'status-toggle', title:'Свернуть/развернуть', 'aria-label':'toggle' });
    toggleBtn.innerHTML = chevronSvg;
    const closeBtn = U.el('button', { class:'status-close', title:'Удалить из чата', 'aria-label':'close' }, '×');
    const row = U.el('div', { class:'status-row' },
      U.el('span', { class:'status-dots' }, U.el('span'), U.el('span'), U.el('span')),
      U.el('span', { class:'status-op think' }, 'Thinking'),
      U.el('span', { class:'status-text' }, ''),
      toggleBtn,
      closeBtn
    );
    const interjectBadge = U.el('div', { class:'status-interject', style:'display:none' }, '');
    // Бейдж с реальным именем модели (как под обычными сообщениями).
    // Располагается ПОД окном статуса (снаружи .status-block), поэтому виден
    // всегда — как плашка модели под ответами ассистента.
    const modelBadgeWrap = U.el('div', { class:'status-model' });
    // Начальное значение — выбранная в провайдере модель (реальное имя по base URL
    // подставится из ответа провайдера через api.setModel, когда станет известно).
    let _initModel = '';
    try { const _p = currentProvider(); _initModel = (_p && _p.model) || ''; } catch(_){}
    if (_initModel) modelBadgeWrap.appendChild(makeModelBadge(_initModel));
    const reasoning = U.el('div', { class:'status-reasoning' });
    const log = U.el('div', { class:'status-log' });
    // Блок с полным (реальным) ответом провайдера при ошибке. Изначально скрыт.
    // При наличии ошибки — рендерится с заголовком «Ответ провайдера» и <pre>-телом.
    const errBlock = U.el('div', { class:'status-error-detail', style:'display:none' });
    wrap.appendChild(row); wrap.appendChild(interjectBadge); wrap.appendChild(reasoning); wrap.appendChild(log); wrap.appendChild(errBlock);
    // Внешний контейнер: окно статуса + плашка модели ПОД ним. Все операции
    // добавления/удаления/присоединения карточки работают с host.
    const host = U.el('div', { class:'status-host' }, wrap, modelBadgeWrap);

    // Кнопка "сворачивания" (▾) видна СРАЗУ, чтобы пользователь мог свернуть/
    // развернуть live-статус во время ожидания ответа провайдера.
    // Кнопка "×" — только у финализированного статуса (закрыть/удалить из истории).
    toggleBtn.style.display = '';
    closeBtn.style.display = 'none';

    // Внутреннее состояние для снапшота (сериализуется в chat.history).
    const snapshot = restore ? {
      op: restore.op || { label:'Готово', kind:'ok' },
      text: restore.text || '',
      reasoning: restore.reasoning || '',
      log: Array.isArray(restore.log) ? restore.log.slice() : [],
      errorDetail: restore.errorDetail || '',
      model: restore.model || ''
    } : { op: null, text: '', reasoning: '', log: [], errorDetail: '', model: _initModel || '' };
    // Обновить бейдж модели новым (реальным) именем.
    function setModelBadge(model) {
      const m = (model || '').trim();
      if (!m) return;
      snapshot.model = m;
      modelBadgeWrap.innerHTML = '';
      modelBadgeWrap.appendChild(makeModelBadge(m));
    }
    // Если восстанавливаем из истории и там сохранена модель — показать её.
    if (restore && restore.model) setModelBadge(restore.model);

    // Рендер блока с полным ответом провайдера (ошибкой). Разворачиваемый.
    function renderErrorDetail() {
      const raw = (snapshot.errorDetail || '').trim();
      if (!raw) { errBlock.style.display = 'none'; errBlock.innerHTML = ''; return; }
      errBlock.innerHTML = '';
      errBlock.style.display = '';
      const head = U.el('div', { class:'err-detail-head' },
        U.el('span', { class:'err-detail-chevron' }, '▸'),
        U.el('span', { class:'err-detail-title' }, 'Ответ провайдера (нажми, чтобы развернуть)')
      );
      const body = U.el('pre', { class:'err-detail-body' }, raw);
      // По умолчанию свёрнут.
      body.style.display = 'none';
      head.addEventListener('click', (e) => {
        e.stopPropagation();
        const isOpen = body.style.display !== 'none';
        body.style.display = isOpen ? 'none' : '';
        const ch = head.querySelector('.err-detail-chevron');
        if (ch) ch.textContent = isOpen ? '▸' : '▾';
        const ttl = head.querySelector('.err-detail-title');
        if (ttl) ttl.textContent = isOpen ? 'Ответ провайдера (нажми, чтобы развернуть)' : 'Ответ провайдера (нажми, чтобы свернуть)';
      });
      errBlock.appendChild(head);
      errBlock.appendChild(body);
    }

    // Позиция в chat.history, в которую надо вставить persist-запись —
    // фиксируется на момент создания live-статуса, чтобы на reload порядок
    // сообщений совпадал с тем, что видел пользователь во время работы
    // (user → status → assistant/tool… → assistant-final).
    const insertAt = restore ? -1 : (runChat().history || []).length;

    // Классификация глагола операции → цветовая группа.
    // Определяет по первому «слову» строки entry.op — оно и является глаголом
    // из TOOL_LABELS.past (Read / Wrote / Deleted / Patched / …).
    function verbClass(verb) {
      const v = (verb || '').toLowerCase();
      if (/^(read|reading|listed|listing|viewed|viewing|got|getting|fetched|fetching)/.test(v)) return 'act-read';
      if (/^(wrote|writing|appended|appending|created|creating)/.test(v)) return 'act-write';
      if (/^(patched|patching)/.test(v)) return 'act-patch';
      if (/^(searched|searching)/.test(v)) return 'act-search';
      if (/^(deleted|deleting|removed|removing)/.test(v)) return 'act-delete';
      if (/^(committed|committing|pulled|pulling|pushed|pushing|cloned|cloning|initialized|initializing|renamed|renaming|extracted|extracting)/.test(v)) return 'act-git';
      if (/^(http|github|fetched)/.test(v)) return 'act-net';
      return '';
    }
    // Разбор entry.op на «глагол» и «остальное» (путь/аргументы).
    // Первое слово — глагол; если это фраза из двух слов (напр. «Read binary»,
    // «Created folder», «Got status», «Created archive», «Initializing repo»),
    // подхватываем и второе.
    function splitVerb(op) {
      const s = (op || '').trim();
      if (!s) return { verb:'', rest:'' };
      const twoWord = s.match(/^(Read binary|Created folder|Created archive|Extracting archive|Creating archive|Creating folder|Got status|Git status|GitHub API|HTTP fetch|Initializing repo|Viewed image|Viewing image)\b\s*(.*)$/i);
      if (twoWord) return { verb: twoWord[1], rest: twoWord[2] };
      const m = s.match(/^(\S+)\s*([\s\S]*)$/);
      return m ? { verb: m[1], rest: m[2] } : { verb:s, rest:'' };
    }
    // Раскраска чисел в detail-строке. Δ+N → зелёный, Δ-N → красный,
    // «+N строк» → зелёный, «-N строк» → красный, «N замен» → пурпурный.
    // Возвращает массив DOM-нод.
    function colorizeDetail(detail) {
      const s = String(detail || '');
      if (!s) return [document.createTextNode('')];
      const nodes = [];
      // Составной regex: захватывает Δ±N, ±N (строк|замен|строки|симв.|б)?, N замен
      const re = /(Δ[+\-]?\d+(?:\.\d+)?)|((?:^|[\s(])[+\-]\d+(?:\.\d+)?(?=[\s)]|$))|(\b\d+\s+замен\b)/g;
      let last = 0, m;
      while ((m = re.exec(s)) !== null) {
        if (m.index > last) nodes.push(document.createTextNode(s.slice(last, m.index)));
        const full = m[0];
        let cls = 'num-neutral';
        // Точечная проверка знака внутри совпадения
        const inner = m[1] || m[2] || m[3] || '';
        if (m[3]) cls = 'num-repl';
        else if (/-\d/.test(inner)) cls = 'num-del';
        else if (/\+\d/.test(inner)) cls = 'num-add';
        // Для m[2] надо аккуратно вернуть ведущий пробел/скобку в текст
        if (m[2]) {
          const leading = full[0];
          if (/\s|\(/.test(leading)) {
            nodes.push(document.createTextNode(leading));
            nodes.push(U.el('span', { class: cls }, full.slice(1)));
          } else {
            nodes.push(U.el('span', { class: cls }, full));
          }
        } else {
          nodes.push(U.el('span', { class: cls }, full));
        }
        last = m.index + full.length;
      }
      if (last < s.length) nodes.push(document.createTextNode(s.slice(last)));
      return nodes;
    }
    // Строит одну строку лога: [✓/✗] [глагол цветной жирный] [остальное] · [detail с цветными числами]
    function buildLogLine(entry) {
      const { verb, rest } = splitVerb(entry.op || '');
      const opSpan = U.el('span', { class: 'op' + (entry.ok === false ? ' err' : '') });
      opSpan.appendChild(U.el('span', { class: 'op-mark' }, entry.ok === false ? '✗' : '✓'));
      opSpan.appendChild(U.el('span', { class: 'op-verb ' + verbClass(verb) }, verb));
      if (rest) opSpan.appendChild(U.el('span', { class: 'op-rest' }, ' ' + rest));
      const argSpan = U.el('span', { class:'arg' });
      for (const n of colorizeDetail(entry.detail)) argSpan.appendChild(n);
      return U.el('div', { class:'log-line' }, opSpan, argSpan);
    }

    function renderLog() {
      log.innerHTML = '';
      for (const entry of snapshot.log) {
        if (entry.type === 'file-preview') {
          renderFilePreviewInto(log, entry.preview);
        } else {
          log.appendChild(buildLogLine(entry));
        }
      }
    }
    function renderFilePreviewInto(parent, preview) {
      const block = U.el('div', { class:'file-preview-block' });
      const chevron = U.el('span', { class:'file-preview-chevron' });
      chevron.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="9 6 15 12 9 18"/></svg>';
      const headerKids = [
        chevron,
        U.el('span', { class:'file-preview-icon' }, preview.icon || '📄'),
        U.el('span', { class:'file-preview-path' }, preview.path || ''),
      ];
      // Diff-счётчики +N / -N в шапке (как в IDE-клиентах).
      if (preview.kind === 'diff') {
        const stats = U.el('span', { class:'file-preview-stats' });
        if (preview.added)   stats.appendChild(U.el('span', { class:'diff-stat add' }, '+' + preview.added));
        if (preview.removed) stats.appendChild(U.el('span', { class:'diff-stat del' }, '−' + preview.removed));
        headerKids.push(stats);
      }
      if (preview.meta) headerKids.push(U.el('span', { class:'file-preview-meta' }, preview.meta));
      const header = U.el('div', { class:'file-preview-header' }, ...headerKids);
      let body;
      if (preview.kind === 'image' && preview.dataUrl) {
        body = U.el('div', { class:'file-preview-body image-preview' }, U.el('img', { src: preview.dataUrl, alt: preview.path || '' }));
      } else if (preview.kind === 'diff') {
        body = buildDiffBody(preview);
      } else {
        body = U.el('div', { class:'file-preview-body hl-code' });
        const text = preview.text || '';
        try {
          body.innerHTML = window.Highlight
            ? Highlight.byPath(text, preview.path || '')
            : U.escapeHtml(text);
        } catch (e) { body.textContent = text; }
      }
      header.addEventListener('click', () => block.classList.toggle('open'));
      block.appendChild(header); block.appendChild(body);
      parent.appendChild(block);
    }
    // Строит тело цветного diff: удалённые строки — тёмно-красный фон,
    // добавленные — тёмно-зелёный, контекст — нейтральный. Номера строк слева.
    function buildDiffBody(preview) {
      const body = U.el('div', { class:'file-preview-body diff-body' });
      const hunks = preview.diffHunks;
      if (!hunks || !hunks.length) {
        // Нет детального diff (напр. очень большой файл) — показываем текст +summary.
        const note = U.el('div', { class:'diff-line diff-note' },
          '± изменения: +' + (preview.added||0) + ' / −' + (preview.removed||0) + ' строк');
        body.appendChild(note);
        if (preview.fallbackText) body.appendChild(U.el('pre', { class:'diff-fallback' }, preview.fallbackText));
        return body;
      }
      for (const h of hunks) {
        if (h.t === 'g' || h.t === 'x') {
          body.appendChild(U.el('div', { class:'diff-line diff-gap' }, h.x || '…'));
          continue;
        }
        const cls = h.t === 'a' ? 'diff-add' : (h.t === 'd' ? 'diff-del' : 'diff-ctx');
        const sign = h.t === 'a' ? '+' : (h.t === 'd' ? '−' : ' ');
        const ln = U.el('div', { class:'diff-line ' + cls });
        ln.appendChild(U.el('span', { class:'diff-gutter' }, (h.o != null ? h.o : (h.n != null ? h.n : ''))+''));
        ln.appendChild(U.el('span', { class:'diff-sign' }, sign));
        const codeSpan = U.el('span', { class:'diff-code' });
        const codeText = h.x != null ? h.x : '';
        try {
          codeSpan.innerHTML = window.Highlight
            ? Highlight.byPath(codeText, preview.path || '')
            : U.escapeHtml(codeText);
        } catch (e) { codeSpan.textContent = codeText; }
        ln.appendChild(codeSpan);
        body.appendChild(ln);
      }
      return body;
    }

    // Toggle collapse/expand
    toggleBtn.addEventListener('click', (e) => {
      e.stopPropagation();
      wrap.classList.toggle('collapsed');
    });
    // Ссылка на persisted-запись обновляется после finalize/persist.
    let persistedMsg = null;
    // Close (×): удалить из DOM и из истории (если статус финализирован).
    closeBtn.addEventListener('click', (e) => {
      e.stopPropagation();
      host.remove();
      const ref = msgRef || persistedMsg;
      if (ref) {
        const chat = Store.activeChat();
        const idx = chat.history.indexOf(ref);
        if (idx >= 0) { chat.history.splice(idx, 1); Store.save(); }
      }
    });

    // Заголовок кликабельный — тоже сворачивает/разворачивает (и в live, и в done).
    row.addEventListener('click', (e) => {
      if (e.target.closest('.status-toggle') || e.target.closest('.status-close')) return;
      wrap.classList.toggle('collapsed');
    });

    if (!detached) {
      cont.appendChild(host);
      cont.scrollTop = cont.scrollHeight;
    }

    // Если восстанавливаем из истории — сразу рендерим финальное состояние
    if (restore) {
      const dots = row.querySelector('.status-dots');
      if (dots) dots.style.display = 'none';
      const opEl = row.querySelector('.status-op');
      opEl.className = 'status-op ' + ((snapshot.op && snapshot.op.kind) || 'ok');
      opEl.textContent = (snapshot.op && snapshot.op.label) || 'Готово';
      row.querySelector('.status-text').textContent = snapshot.text || '';
      reasoning.textContent = (snapshot.reasoning || '').trim();
      renderLog();
      renderErrorDetail();
      wrap.classList.add('done');
      wrap.classList.add('collapsed'); // по умолчанию свернут в истории
      toggleBtn.style.display = '';
      closeBtn.style.display = '';
    }

    const api = {
      el: host,
      // Присоединить карточку в конец #ai-messages (например при возврате в
      // рабочий чат после того, как она была detached/удалена из DOM).
      attach() {
        const c = U.$('#ai-messages');
        if (host.parentElement !== c) c.appendChild(host);
        c.scrollTop = c.scrollHeight;
      },
      set(op, text) {
        const opEl = row.querySelector('.status-op');
        opEl.className = 'status-op ' + (op.kind || 'think');
        opEl.textContent = op.label || 'Thinking';
        row.querySelector('.status-text').textContent = text || '';
        snapshot.op = { label: opEl.textContent, kind: (op.kind || 'think') };
        snapshot.text = text || '';
        cont.scrollTop = cont.scrollHeight;
      },
      // Установить реальное имя модели (из ответа провайдера по base URL).
      setModel(model) { setModelBadge(model); },
      setReasoning(text) {
        reasoning.textContent = (text || '').trim();
        snapshot.reasoning = (text || '').trim();
        // Рассуждения растут, но в фокусе держим НАЧАЛО (не автоскроллим вниз):
        // окно статуса фиксированного размера, reasoning прокручивается внутри себя.
        reasoning.scrollTop = 0;
      },
      appendLog(entry) {
        const line = buildLogLine(entry);
        log.appendChild(line);
        snapshot.log.push({ type:'log', ok: entry.ok, op: entry.op, detail: entry.detail || '' });
        while (log.children.length > 60) log.removeChild(log.firstChild);
        while (snapshot.log.length > 60) snapshot.log.shift();
        // Автоскролл ТОЛЬКО внутри лога — к последней операции. Общий чат не трогаем,
        // чтобы окно статуса фиксированного размера не «прыгало».
        log.scrollTop = log.scrollHeight;
        return line;
      },
      appendFilePreview(preview) {
        // preview: { path, kind: 'text'|'image'|'archive'|'clone', text, dataUrl, size, meta }
        renderFilePreviewInto(log, preview);
        // Не сохраняем dataUrl (могут быть большими) — сохраняем только легковесный текст.
        const light = { path: preview.path, kind: preview.kind, meta: preview.meta, icon: preview.icon };
        if (preview.kind === 'diff') {
          light.diffHunks = preview.diffHunks || null;
          light.added = preview.added || 0;
          light.removed = preview.removed || 0;
          light.fallbackText = (preview.fallbackText || '').slice(0, 4000);
        } else if (preview.kind !== 'image') {
          light.text = (preview.text || '').slice(0, 4000);
        }
        snapshot.log.push({ type:'file-preview', preview: light });
        while (log.children.length > 60) log.removeChild(log.firstChild);
        while (snapshot.log.length > 60) snapshot.log.shift();
        log.scrollTop = log.scrollHeight;
      },
      setInterject(n) {
        if (!n) { interjectBadge.style.display = 'none'; interjectBadge.textContent = ''; return; }
        interjectBadge.style.display = '';
        interjectBadge.textContent = n === 1
          ? '📨 Новое сообщение от пользователя — учту в следующем шаге, не прерывая задачу'
          : '📨 ' + n + ' новых сообщения — учту, не прерывая задачу';
        cont.scrollTop = cont.scrollHeight;
      },
      clearInterject() { interjectBadge.style.display = 'none'; interjectBadge.textContent = ''; },
      // Замораживает статус (убирает мигающие точки, помечает финальным цветом).
      // Статус остаётся в чате в свёрнутом виде. Возвращает snapshot для сохранения в истории.
      // errorDetail (опционально) — полный ответ провайдера, показывается разворачиваемым блоком.
      finalize(op, text, errorDetail) {
        const dots = row.querySelector('.status-dots');
        if (dots) dots.style.display = 'none';
        const opEl = row.querySelector('.status-op');
        opEl.className = 'status-op ' + (op && op.kind || 'ok');
        opEl.textContent = (op && op.label) || 'Готово';
        row.querySelector('.status-text').textContent = text || '';
        snapshot.op = { label: opEl.textContent, kind: (op && op.kind) || 'ok' };
        snapshot.text = text || '';
        if (errorDetail != null) {
          snapshot.errorDetail = String(errorDetail || '').slice(0, 20000);
          renderErrorDetail();
        }
        wrap.classList.add('done');
        wrap.classList.add('collapsed');
        toggleBtn.style.display = '';
        closeBtn.style.display = '';
        cont.scrollTop = cont.scrollHeight;
        return { ...snapshot, log: snapshot.log.slice() };
      },
      // Финализация + persist в chat.history как отдельное сообщение role:'status'.
      // Порядок в истории будет корректным (status добавится ПОСЛЕ всех уже
      // прошедших tool/assistant записей текущей итерации).
      persist(op, text, errorDetail) {
        const snap = this.finalize(op, text, errorDetail);
        const msg = { role:'status', data: snap, silent:false };
        const chat = runChat();
        // Вставляем в позицию, где статус реально появился (перед tool/assistant
        // сообщениями текущего turn'а). Если позиция за пределами массива — push.
        if (insertAt >= 0 && insertAt <= chat.history.length) {
          chat.history.splice(insertAt, 0, msg);
        } else {
          chat.history.push(msg);
        }
        chat.updatedAt = Date.now();
        Store.save();
        persistedMsg = msg;
        return msg;
      },
      snapshot() { return { ...snapshot, log: snapshot.log.slice() }; },
      remove() { host.remove(); }
    };
    return api;
  }

  // Гарантирует, что у текущего запуска есть живая статус-карточка, СОГЛАСОВАННАЯ
  // с видимостью чата запуска:
  //   • рабочий чат открыт  → карточка должна быть в DOM (создать/присоединить);
  //   • рабочий чат НЕ открыт → карточка должна быть detached (не в чужом чате).
  // Обновляет currentStatus и возвращает актуальную карточку.
  function ensureLiveStatus() {
    const visible = isRunChatVisible();
    let s = currentStatus;
    const inDom = s && s.el && s.el.isConnected;
    if (visible) {
      if (!s) { s = createStatus(); }
      else if (!inDom) {
        // Карточка есть, но не в DOM (была detached или чат перерисован) —
        // присоединяем ту же карточку, чтобы сохранить накопленный лог/reasoning.
        if (s.attach) s.attach(); else s = createStatus();
      }
    } else {
      // Рабочий чат не открыт: карточка не должна висеть в чужом чате.
      if (s && inDom && s.el) { try { s.el.remove(); } catch(_){} }
      if (!s) s = createStatus({ detached: true });
    }
    currentStatus = s;
    return s;
  }

  // Map tool name → present-tense label + past-tense label
  const TOOL_LABELS = {
    fs_read: { pres:'Reading', past:'Read', field:'path' },
    fs_read_image: { pres:'Viewing image', past:'Viewed image', field:'path' },
    fs_read_media: { pres:'Viewing media', past:'Viewed media', field:'path' },
    fs_read_binary: { pres:'Reading binary', past:'Read binary', field:'path' },
    fs_write: { pres:'Writing', past:'Wrote', field:'path' },
    fs_replace: { pres:'Patching', past:'Patched', field:'path' },
    fs_append: { pres:'Appending', past:'Appended', field:'path' },
    fs_list: { pres:'Listing', past:'Listed', field:'path' },
    fs_mkdir: { pres:'Creating folder', past:'Created folder', field:'path' },
    fs_delete: { pres:'Deleting', past:'Deleted', field:'path' },
    fs_rename: { pres:'Renaming', past:'Renamed', field:'from', field2:'to' },
    fs_search: { pres:'Searching', past:'Searched', field:'query' },
    archive_extract: { pres:'Extracting archive', past:'Extracted', field:'archive' },
    archive_list: { pres:'Listing archive', past:'Listed archive', field:'archive' },
    archive_read_entry: { pres:'Reading from archive', past:'Read from archive', field:'entry' },
    archive_create: { pres:'Creating archive', past:'Created archive', field:'archive' },
    git_status: { pres:'Git status', past:'Got status', field:'dir' },
    git_commit: { pres:'Committing', past:'Committed', field:'message' },
    git_pull: { pres:'Pulling', past:'Pulled', field:'dir' },
    git_push: { pres:'Pushing', past:'Pushed', field:'dir' },
    git_clone: { pres:'Cloning', past:'Cloned', field:'url' },
    git_init: { pres:'Initializing repo', past:'Initialized', field:'dir' },
    github_api: { pres:'GitHub API', past:'GitHub API', field:'path' },
    http_fetch: { pres:'HTTP fetch', past:'Fetched', field:'url' },
  };
  function labelFor(tool, args, when='pres') {
    const t = TOOL_LABELS[tool];
    if (!t) return { op: tool, detail: '' };
    const parts = [];
    if (t.field && args[t.field] != null) parts.push(String(args[t.field]));
    if (t.field2 && args[t.field2] != null) parts.push('→ ' + args[t.field2]);
    if (when === 'pres') {
      if (tool === 'fs_read' && (args.offset != null || args.limit != null)) {
        const off = args.offset ? +args.offset : 1;
        const lim = args.limit ? +args.limit : 0;
        parts.push('строки ' + off + (lim ? '–' + (off + lim - 1) : '+'));
      } else if (tool === 'fs_write' && typeof args.content === 'string') {
        const nl = args.content ? args.content.split('\n').length : 0;
        parts.push('(' + nl + ' строк, ' + args.content.length + ' симв.)');
      } else if (tool === 'fs_replace') {
        if (args.startLine != null && args.endLine != null) {
          parts.push('(строки ' + args.startLine + '–' + args.endLine + ')');
        } else if (args.search != null) {
          const q = String(args.search);
          const qs = q.length > 40 ? q.slice(0,40) + '…' : q;
          parts.push('("' + qs + '"' + (args.regex ? ' regex' : '') + ')');
        }
      } else if (tool === 'fs_append' && typeof args.content === 'string') {
        const nl = args.content ? args.content.split('\n').length : 0;
        parts.push('(+' + nl + ' строк)');
      } else if (tool === 'fs_search' && args.query != null) {
        parts.push(args.regex ? '(regex)' : '(подстрока)');
      }
    }
    return { op: t[when] || t.pres, detail: parts.join(' ') };
  }
  // Человекочитаемое описание результата tool-вызова для лога.
  function summarizeToolResult(tool, args, res) {
    if (!res) return '';
    if (!res.ok) return res.error || 'ошибка';
    const r = res.result || {};
    try {
      switch (tool) {
        case 'fs_read':
          if (r.cached) return 'дубль-чтение (пропущено)';
          if (r.mode === 'digest') return 'выжимка · ' + (r.lines||'?') + ' строк · ' + (r.size||'?') + ' б';
          if (r.returnedLines) return 'строки ' + r.returnedLines[0] + '–' + r.returnedLines[1] + ' из ' + r.lines;
          return (r.lines != null ? r.lines + ' строк · ' : '') + (r.size != null ? r.size + ' б' : '');
        case 'fs_read_binary':
          return (r.size != null ? r.size + ' б' : '') + (r.mime ? ' · ' + r.mime : '');
        case 'fs_read_image':
          return (r.width && r.height ? r.width + '×' + r.height + ' · ' : '') + (r.mime||'') + (r.size?' · '+r.size+' б':'');
        case 'fs_write': {
          const p = [];
          if (r.lines != null) p.push(r.lines + ' строк');
          if (r.written != null) p.push(r.written + ' симв.');
          if (r.deltaLines != null && r.prevLines) p.push('Δ' + (r.deltaLines>=0?'+':'') + r.deltaLines);
          if (r.created) p.push('новый файл');
          return p.join(' · ');
        }
        case 'fs_replace': {
          const p = [];
          if (r.replacements != null) p.push(r.replacements + ' замен');
          if (r.deltaLines != null) p.push('Δ' + (r.deltaLines>=0?'+':'') + r.deltaLines + ' строк');
          if (r.size != null) p.push(r.size + ' б');
          return p.join(' · ');
        }
        case 'fs_append':
          return (r.appendedLines != null ? '+' + r.appendedLines + ' строк' : '') +
                 (r.appendedChars != null ? ' · +' + r.appendedChars + ' симв.' : '');
        case 'fs_list':
          return (r.files != null ? r.files + ' файлов' : '') + (r.dirs != null ? ' · ' + r.dirs + ' папок' : '');
        case 'fs_search': {
          const p = [];
          if (r.count != null) p.push(r.count + ' совп.');
          if (r.scanned != null) p.push('в ' + r.scanned + ' файлах');
          if (r.truncated) p.push('обрезано');
          return p.join(' · ');
        }
        case 'fs_delete':
        case 'fs_mkdir':
        case 'fs_rename':
          return 'готово';
        case 'archive_extract':
          return r.extracted != null ? r.extracted + ' файлов' : '';
        case 'archive_create':
          return (r.files != null ? r.files + ' файлов' : '') + (r.size ? ' · ' + r.size + ' б' : '');
        case 'git_status':
          return (r.changes ? r.changes.length + ' изменений' : '');
        case 'git_commit':
          return r.sha ? r.sha.slice(0,7) : '';
        case 'http_fetch':
        case 'github_api':
          return r.status != null ? 'HTTP ' + r.status : '';
      }
    } catch(_){}
    return '';
  }

  // Build a collapsible file-preview entry for write-like tool calls.
  // Returns a preview object for status.appendFilePreview(), or null if not applicable.
  async function buildFilePreview(tool, args, res) {
    if (!res || !res.ok) return null;
    try {
      if (tool === 'fs_read') {
        const path = args.path;
        const r = res.result || {};
        const content = r.content != null ? String(r.content) : '';
        if (!content) return null;
        const name = (path||'').split('/').pop() || path;
        const trimmed = content.length > 4000 ? content.slice(0,4000) + '\n...[обрезано ' + (content.length-4000) + ' симв.]' : content;
        const metaParts = [];
        if (r.mode === 'digest') metaParts.push('выжимка');
        if (r.returnedLines) metaParts.push('стр. ' + r.returnedLines[0] + '–' + r.returnedLines[1]);
        if (r.lines != null) metaParts.push(r.lines + ' всего');
        if (r.size != null) metaParts.push(humanSize(r.size));
        if (r.cached) metaParts.push('уже читал');
        return { path, kind:'text', icon: iconForName(name), text: trimmed, meta: metaParts.join(' · ') };
      }
      if (tool === 'fs_search') {
        const r = res.result || {};
        const hits = r.hits || [];
        if (!hits.length) {
          return { path: 'Поиск: "' + (args.query||'') + '"', kind:'text', icon:'🔍', text:'Ничего не найдено', meta: (r.scanned?r.scanned+' файлов просмотрено':'') };
        }
        const lines = hits.slice(0, 200).map(h => (h.path||'') + ':' + (h.line||'') + ' | ' + (h.text||'').trim());
        const text = lines.join('\n') + (hits.length > 200 ? '\n...[+ ' + (hits.length-200) + ' ещё]' : '');
        return {
          path: '🔎 "' + (args.query||'') + '"',
          kind:'text', icon:'🔍', text,
          meta: hits.length + ' совп.' + (r.scanned ? ' · в ' + r.scanned + ' файлах' : '') + (r.truncated ? ' · обрезано' : '')
        };
      }
      if (tool === 'fs_list') {
        const r = res.result || {};
        const items = r.items || [];
        if (!items.length) return null;
        const lines = items.slice(0, 200).map(i => (i.type==='dir'?'📁 ':'📄 ') + i.path + (i.size!=null&&i.type==='file'?' ('+humanSize(i.size)+')':''));
        const text = lines.join('\n') + (items.length > 200 ? '\n...[+ ' + (items.length-200) + ' ещё]' : '');
        return { path: args.path || '/', kind:'text', icon:'📂', text, meta: (r.files||0) + ' файлов · ' + (r.dirs||0) + ' папок' };
      }
      if (tool === 'fs_write' || tool === 'fs_append' || tool === 'fs_replace') {
        const path = args.path;
        if (!path) return null;
        const name = path.split('/').pop() || path;
        const mime = U.mimeFromPath(name);
        const kind = U.mediaKind ? U.mediaKind(name) : 'other';
        if (kind === 'image') {
          try {
            const data = await FS.readFile(path);
            const b64 = U.bytesToB64(data);
            return { path, kind:'image', icon:'🖼', dataUrl:'data:'+mime+';base64,'+b64, meta: humanSize(data.byteLength) };
          } catch(_){}
        }
        // Text preview
        try {
          const data = await FS.readFile(path);
          if (!U.looksBinary(data)) {
            const text = U.bytesToText(data);
            const trimmed = text.length > 4000 ? text.slice(0,4000)+'\n...[обрезано '+(text.length-4000)+' символов]' : text;
            const rr = res.result || {};
            const metaParts = [humanSize(data.byteLength)];
            if (rr.lines != null) metaParts.push(rr.lines + ' строк');
            if (tool === 'fs_write' && rr.created) metaParts.push('новый файл');
            if (tool === 'fs_replace' && rr.replacements != null) metaParts.push(rr.replacements + ' замен');
            if (tool === 'fs_append' && rr.appendedLines != null) metaParts.push('+' + rr.appendedLines + ' строк');
            // Diff-режим: для правок показываем цветной diff и точные счётчики +N/-N.
            if ((tool === 'fs_write' || tool === 'fs_replace') && (rr.added != null || rr.removed != null) && (rr.added || rr.removed)) {
              return {
                path, kind:'diff', icon: iconForName(name),
                diffHunks: rr.diffHunks || null,
                fallbackText: trimmed,
                added: rr.added || 0, removed: rr.removed || 0,
                meta: metaParts.join(' · ')
              };
            }
            return { path, kind:'text', icon: iconForName(name), text: trimmed, meta: metaParts.join(' · ') };
          } else {
            return { path, kind:'text', icon:'📦', text:'[бинарный файл, '+humanSize(data.byteLength)+']', meta: humanSize(data.byteLength) };
          }
        } catch(_){ return null; }
      }
      if (tool === 'fs_rename') {
        return { path: (args.from||'')+' → '+(args.to||''), kind:'text', icon:'✏️', text:'Файл переименован', meta:'' };
      }
      if (tool === 'fs_delete') {
        return { path: args.path||'', kind:'text', icon:'🗑', text:'Удалено', meta:'' };
      }
      if (tool === 'fs_mkdir') {
        return { path: args.path||'', kind:'text', icon:'📁', text:'Создана папка', meta:'' };
      }
      if (tool === 'archive_extract') {
        const files = (res.result && res.result.files) || [];
        const list = files.slice(0, 200).map(f => typeof f === 'string' ? f : f.path || JSON.stringify(f)).join('\n');
        return {
          path: 'Распакован: ' + (args.archive||''),
          kind:'text', icon:'📦',
          text: files.length ? list + (files.length>200 ? '\n...[+ '+(files.length-200)+' ещё]' : '') : '[пусто]',
          meta: files.length + ' файлов'
        };
      }
      if (tool === 'archive_create') {
        return { path: args.archive || 'archive', kind:'text', icon:'📦', text: 'Архив создан', meta: (res.result && res.result.size ? humanSize(res.result.size) : '') };
      }
      if (tool === 'git_clone') {
        return { path: args.url + ' → ' + (args.dir||''), kind:'text', icon:'🐙', text: 'Репозиторий клонирован', meta: '' };
      }
      if (tool === 'git_init') {
        return { path: args.dir || '/', kind:'text', icon:'🐙', text: 'git init', meta: '' };
      }
      if (tool === 'git_commit') {
        return { path: args.dir || '', kind:'text', icon:'💾', text: 'Коммит: ' + (args.message||''), meta: (res.result && res.result.oid ? res.result.oid.slice(0,7) : '') };
      }
      if (tool === 'git_push' || tool === 'git_pull') {
        return { path: args.dir || '', kind:'text', icon: tool==='git_push'?'⬆️':'⬇️', text: tool + ' выполнен', meta: '' };
      }
    } catch(_){}
    return null;
  }
  function iconForName(name) {
    const ext = (name.split('.').pop()||'').toLowerCase();
    if (['js','ts','jsx','tsx','mjs','cjs'].includes(ext)) return '📜';
    if (['py'].includes(ext)) return '🐍';
    if (['json','yaml','yml','toml'].includes(ext)) return '⚙️';
    if (['md','txt','rst'].includes(ext)) return '📝';
    if (['html','htm','xml','svg'].includes(ext)) return '🌐';
    if (['css','scss','less'].includes(ext)) return '🎨';
    if (['zip','tar','gz','7z','rar'].includes(ext)) return '📦';
    return '📄';
  }

  // ---- Heartbeat: пока LLM думает, обновляем текст статуса раз в секунду,
  // чтобы пользователь видел, что клиент не завис, а именно ждёт ответа провайдера.
  function startWaitingHeartbeat(status, prefix) {
    const t0 = performance.now();
    const id = setInterval(() => {
      try {
        const sec = Math.floor((performance.now() - t0) / 1000);
        status.set({ label:'Waiting', kind:'think' },
          (prefix || 'Жду ответа от провайдера') + '… ' + sec + 's');
      } catch(_){}
    }, 1000);
    return () => clearInterval(id);
  }

  // ---- Abort detection ----
  // Ловит:
  //  - наше искусственное new Error('aborted')
  //  - DOMException 'AbortError' от fetch()
  //  - разные message-варианты браузеров: "signal is aborted...", "The user aborted a request", etc.
  function isAbortError(e) {
    if (!e) return false;
    if (e.name === 'AbortError') return true;
    const msg = String(e.message || e).toLowerCase();
    return msg === 'aborted' || /abort|user aborted|signal is aborted/i.test(msg);
  }

  // ---- Tool-error loop detector ----
  // Агент часто "зависает" в бесконечном чтении/редактировании, когда любой
  // инструмент возвращает ошибку (нет доступа к пути, файл вне репо, ENOENT,
  // network fail, permission denied и т.п.), а модель упорно повторяет тот же
  // вызов. Здесь мы отслеживаем цепочки повторяющихся неудач и, если превышен
  // порог, останавливаем цикл и показываем пользователю понятное объяснение.
  //
  // Правила остановки:
  //   • 3 подряд ошибочных вызова ОДНОГО и того же инструмента с ОДНОЙ и той же
  //     сигнатурой ошибки → остановка. Модель явно застряла.
  //   • 6 подряд ошибочных вызовов ЛЮБЫХ инструментов (даже разных) без
  //     единого успеха → остановка. Что-то системно не так (нет доступа
  //     ко всем инструментам сразу).
  //   • 10 подряд последовательных вызовов ОДНОГО инструмента с одинаковой
  //     сигнатурой аргументов и одинаковым (успешным или нет) результатом
  //     тоже считаем "залипанием" — просто длинный цикл.
  function _errorSignature(res) {
    if (!res) return 'no-result';
    if (res.ok) return '';
    const e = String(res.error || '').toLowerCase().replace(/\s+/g,' ').trim();
    // Нормализуем: разные пути дают одну и ту же смысловую ошибку
    const norm = e
      .replace(/'[^']{1,200}'/g, "'…'")
      .replace(/"[^"]{1,200}"/g, '"…"')
      .replace(/\/[a-z0-9._\-\/]{1,200}/gi, '/…')
      .slice(0, 200);
    return norm || 'unknown';
  }
  function _argSignature(tool, args) {
    try {
      // Игнорируем `content` (у fs_write он большой и уникальный) — важен факт того,
      // что модель дёргает один и тот же путь одним и тем же инструментом.
      const copy = { ...(args||{}) };
      if ('content' in copy) copy.content = '<content>';
      return tool + ':' + JSON.stringify(copy);
    } catch(_) { return tool; }
  }
  // Read-only инструменты, не меняющие ФС/git — используются в правиле «прогрессирующее
  // чтение без прогресса». Если модель делает N подряд таких вызовов без единого write/git/финального
  // ответа — она наверняка тонет в контексте и не сможет ответить (fs_write выйдет за max_tokens).
  // Read-only инструменты, повтор которых БЕЗОПАСЕН: они ничего не ломают в ФС,
  // а слабые модели (sarvam-*, некоторые cohere/qwen) любят перечитывать/переискивать
  // одно и то же по несколько раз, прежде чем перейти к правке. Android-версия
  // вообще НЕ имеет сторожа зацикливания и такие модели у неё спокойно доходят до
  // конца. Раньше веб-версия убивала задачу на 10-м одинаковом успешном fs_read/
  // fs_search («Агент 10 раз подряд вызывает …») — из-за этого одна и та же модель
  // с теми же настройками в вебе зависала, а на Android работала. Теперь для
  // успешных read-only повторов мы НЕ обрываем задачу, а сначала мягко подталкиваем
  // модель системной заметкой (nudge) перейти от чтения к действию, и только если
  // это не помогло даже после подталкиваний — считаем реальным залипанием.
  const READONLY_TOOLS = new Set([
    'fs_read', 'fs_search', 'fs_list', 'archive_list', 'archive_read_entry',
    'git_status', 'fs_read_image', 'fs_read_media', 'fs_read_binary'
  ]);
  function makeToolLoopDetector() {
    let sameErrorRun = { key:'', count:0 };   // одинаковая (tool + errSig) подряд
    let anyErrorRun = 0;                       // подряд любых error-вызовов
    let sameCallRun = { key:'', count:0 };    // одинаковая (tool + argSig) подряд (успех или нет)
    let roNudges = 0;                          // сколько раз уже подтолкнули на read-only повторе
    return {
      // Возвращает:
      //   • null                       — всё ок, продолжаем;
      //   • { stop:true, reason, ... }  — пора останавливать задачу;
      //   • { nudge:true, note }        — не останавливаем, но вставляем системную
      //                                   заметку, подталкивающую перейти к действию.
      observe(tool, args, res) {
        const argSig = _argSignature(tool, args);
        const errSig = _errorSignature(res);
        const success = !!(res && res.ok);
        const readonly = READONLY_TOOLS.has(tool);
        // 1) Одинаковый (tool + argSig) подряд — «топчется на месте».
        if (sameCallRun.key === argSig) sameCallRun.count++;
        else { sameCallRun = { key: argSig, count: 1 }; roNudges = 0; }
        // 1a) УСПЕШНЫЙ read-only повтор — НЕ обрываем (см. коммент выше). Слабые
        //     модели легитимно перечитывают файл/переискивают. Вместо жёсткого
        //     обрыва: на 4-м одинаковом успешном чтении подталкиваем «ты уже это
        //     видел — переходи к правке», и повторяем nudge не чаще, чем раз в 4
        //     одинаковых вызова. Общий предохранитель от бесконечности — maxTool.
        if (readonly && success) {
          if (sameCallRun.count >= 4 && roNudges < 3 && sameCallRun.count % 4 === 0) {
            roNudges++;
            return {
              nudge: true,
              note: 'Ты уже '+sameCallRun.count+' раз(а) подряд вызвал `'+tool+'` с одними и теми же параметрами и получил один и тот же результат — эти данные у тебя УЖЕ ЕСТЬ, перечитывать их снова не нужно. Не повторяй чтение/поиск. Переходи к СЛЕДУЮЩЕМУ шагу задачи: если нужно изменить файл — вызови fs_replace/fs_write прямо сейчас; если задача выполнена — дай финальный ответ обычным текстом.'
            };
          }
          // read-only успех не считаем за жёсткое залипание — сбрасываем error-серии.
          anyErrorRun = 0;
          sameErrorRun = { key:'', count:0 };
          return null;
        }
        // 1b) Для НЕ read-only (или неуспешных) — прежнее правило: 10 одинаковых → стоп.
        if (sameCallRun.count >= 10) {
          return {
            stop: true,
            reason: 'Агент 10 раз подряд вызывает `'+tool+'` с одинаковыми параметрами — похоже, он застрял в цикле. Прерываю выполнение.',
            tool, errSig: res && !res.ok ? res.error : ''
          };
        }
        // 2) Ошибочные вызовы.
        if (res && !res.ok) {
          anyErrorRun++;
          const key = tool + '::' + errSig;
          if (sameErrorRun.key === key) sameErrorRun.count++;
          else sameErrorRun = { key, count: 1 };
          // «Восстановимые» ошибки (битый/пустой аргумент, синоним поля) снабжены
          // подсказкой для самоисправления — даём модели чуть больше попыток (5),
          // прежде чем оборвать. Для «жёстких» ошибок (нет доступа, вне репо,
          // сеть) порог прежний — 3.
          const recoverable = /не указан аргумент|нужны непустые|нужен непустой|нельзя удалять|нельзя создавать|повтори вызов|повтори с реальными/i.test(String(res.error||''));
          const stopAt = recoverable ? 5 : 3;
          if (sameErrorRun.count >= stopAt) {
            return {
              stop: true,
              reason: 'Инструмент `'+tool+'` три раза подряд возвращает одну и ту же ошибку: «'+ (res.error||'неизвестная ошибка') +'». Похоже, у агента нет доступа к этому ресурсу или путь недоступен — я остановил задачу, чтобы не крутиться впустую.',
              tool, errSig: res.error || ''
            };
          }
          if (anyErrorRun >= 6) {
            return {
              stop: true,
              reason: 'Шесть инструментов подряд завершились ошибкой (последняя: `'+tool+'` — «'+ (res.error||'неизвестная ошибка') +'»). Похоже, у агента нет доступа к нужным инструментам/файлам. Прерываю выполнение — проверьте настройки провайдера, права доступа и активный репозиторий.',
              tool, errSig: res.error || ''
            };
          }
        } else {
          // Успех сбрасывает и «anyErrorRun», и «sameErrorRun»
          anyErrorRun = 0;
          sameErrorRun = { key:'', count:0 };
        }
        // 3) Read-only stall-детектор УДАЛЁН. Он ложно срабатывал на легитимных
        //    сценариях: большой smali/js/txt-файл требует много последовательных
        //    fs_read с разными offset + серия fs_search перед единственным
        //    fs_replace. rutex такого сторожа не имеет, и модель работает.
        //    Оставлены только: (1) повтор одинакового аргумента 10 раз,
        //    (2) 3 одинаковые ошибки подряд, (3) 6 любых ошибок подряд.
        return null;
      }
    };
  }

  // ---- Rate-limit handling ----
  // Известные признаки провайдерского rate-limit'а: HTTP 429, слова "rate limit",
  // "too many requests", "quota", "429" в тексте ошибки. Мы делаем несколько
  // повторов с экспоненциальным ожиданием, показывая пользователю статус.
  function isRateLimitError(err) {
    if (!err) return false;
    const s = String(err.message || err).toLowerCase();
    // Исчерпание баланса/кредитов НЕ является временным rate-limit'ом: повтор с backoff
    // не поможет (денег не прибавится), только зря заставит пользователя ждать. Некоторые
    // провайдеры (напр. auriko) отдают это как HTTP 429 с type "rate_limit_error", но
    // code "budget_exhausted"/"insufficient" — распознаём и НЕ ретраим такие ошибки.
    if (/budget_exhausted|insufficient|balance is insufficient|add credits|no credits|out of credits|payment required|top up/i.test(s)) return false;
    return /(429|rate.?limit|too many requests|quota|throttl|rate_limit_exceeded|exceeded.*limit)/i.test(s);
  }
  // Пытается вытащить из текста ошибки, СКОЛЬКО ждать перед повтором (в мс).
  // Понимает форматы: "retry in 60s", "try again in 12 seconds", "Retry-After: 30",
  // "in 500ms". Возвращает число мс или null, если подсказки нет.
  function parseRetryAfterMs(err) {
    const s = String((err && err.message) || err || '');
    let m = s.match(/(\d+(?:\.\d+)?)\s*ms\b/i);
    if (m) return Math.round(parseFloat(m[1]));
    m = s.match(/(?:retry[\s-]*after|retry\s+in|try\s+again\s+in|wait|in)\D{0,12}?(\d+(?:\.\d+)?)\s*(s|sec|secs|second|seconds|м|с|сек|секунд)?/i);
    if (m) {
      const n = parseFloat(m[1]);
      // Если единица не указана и число большое (>1000) — трактуем как мс, иначе как секунды.
      const unit = (m[2] || '').toLowerCase();
      if (unit === '' && n > 1000) return Math.round(n);
      return Math.round(n * 1000);
    }
    return null;
  }
  // Прерываемый сон (учитывает abort по кнопке «Стоп»).
  async function interruptibleSleep(ms, signal) {
    const start = performance.now();
    while (performance.now() - start < ms) {
      if (signal && signal.aborted) throw new Error('aborted');
      await new Promise(r => setTimeout(r, Math.min(200, ms - (performance.now()-start))));
    }
  }
  // ---- Throttle ЗАПРОСОВ К ПРОВАЙДЕРУ (главная защита от 429) ----
  // ВАЖНО: 429 rate-limit прилетает от САМОГО провайдера LLM на каждый шаг агента
  // (каждый шаг = отдельный HTTP-запрос к модели), а НЕ от локальных инструментов
  // (fs_write/fs_read выполняются в браузере и запросов наружу не делают).
  // Поэтому именно здесь, ПЕРЕД обращением к модели, мы выдерживаем минимальный
  // интервал. Тот же интервал, что задан в настройке throttle, применяется и к
  // запросам провайдера — только это реально уменьшает количество 429.
  let _lastProviderAt = 0;
  async function throttleBeforeProvider(signal, statusUpdate) {
    const st = Store.get().settings || {};
    if (!st.toolThrottleEnabled) { _lastProviderAt = performance.now(); return; }
    const interval = Math.max(0, +st.toolThrottleMs || 0);
    if (!interval) { _lastProviderAt = performance.now(); return; }
    const wait = Math.max(0, interval - (performance.now() - _lastProviderAt));
    if (wait > 0) {
      if (statusUpdate) { try { statusUpdate('Пауза ' + Math.round(wait/100)/10 + 's перед запросом к модели (throttle)'); } catch(_){} }
      await interruptibleSleep(wait, signal);
    }
    _lastProviderAt = performance.now();
  }
  async function callProviderWithBackoff(provider, messages, signal, statusUpdate) {
    const st = Store.get().settings || {};
    const maxRetries = st.autoBackoffOn429 === false ? 0 : (st.autoBackoffMaxRetries || 4);
    let attempt = 0;
    while (true) {
      try {
        // Выдерживаем минимальный интервал МЕЖДУ запросами к провайдеру.
        await throttleBeforeProvider(signal, statusUpdate);
        const r = await callProvider(provider, messages, signal);
        _lastProviderAt = performance.now(); // отметка успешного запроса
        return r;
      } catch(e) {
        if (signal && signal.aborted) throw e;
        if (attempt >= maxRetries) throw e;
        if (!isRateLimitError(e)) throw e;
        // Если провайдер подсказал, СКОЛЬКО ждать (Retry-After или "…in 60s"),
        // используем это значение — точнее, чем слепой экспоненциальный backoff.
        const hinted = parseRetryAfterMs(e);
        const delay = hinted != null
          ? Math.min(120000, hinted + 300)
          : Math.min(30000, 1000 * Math.pow(2, attempt)) + Math.floor(Math.random()*300);
        attempt++;
        if (statusUpdate) {
          try { statusUpdate('Провайдер ответил rate-limit → жду ' + Math.round(delay/100)/10 + 's · попытка ' + attempt + '/' + maxRetries); } catch(_){}
        }
        // После ожидания сдвигаем отметку, чтобы следующий throttle не добавлял лишнего.
        await interruptibleSleep(delay, signal);
        _lastProviderAt = performance.now();
      }
    }
  }
  // ---- Tool-call throttling ----
  // Хранит момент времени последнего запуска tool. Если включён throttle, следующий
  // вызов ждёт (Store.settings.toolThrottleMs - elapsed) миллисекунд. Ожидание
  // прерывается по abortCtrl.
  let _lastToolAt = 0;
  async function throttleBeforeTool(signal, statusUpdate) {
    const st = Store.get().settings || {};
    if (!st.toolThrottleEnabled) { _lastToolAt = performance.now(); return; }
    const interval = Math.max(0, +st.toolThrottleMs || 0);
    const now = performance.now();
    const wait = Math.max(0, interval - (now - _lastToolAt));
    if (wait > 0) {
      if (statusUpdate) {
        try { statusUpdate('Пауза ' + Math.round(wait) + 'мс перед следующим вызовом (throttle)'); } catch(_){}
      }
      const start = performance.now();
      while (performance.now() - start < wait) {
        if (signal && signal.aborted) throw new Error('aborted');
        await new Promise(r => setTimeout(r, Math.min(100, wait - (performance.now()-start))));
      }
    }
    _lastToolAt = performance.now();
  }

  // Понятное сообщение об «пустом» ответе провайдера. Чаще всего это происходит,
  // когда модель уперлась в max_tokens (finish_reason=length) и не успела ничего
  // осмысленного вернуть, или когда провайдер молча вернул пустой content
  // (случается при контекст-overflow).
  function emptyResponseMessage() {
    const mt = currentMaxTokens();
    return 'Провайдер вернул пустой ответ. Возможные причины:\n' +
           '• Модель уперлась в лимит max_tokens ('+mt+') и была обрезана — увеличь «Макс. токенов в ответе» в настройках.\n' +
           '• Контекст запроса стал слишком большим — очисти историю чата или используй fs_replace вместо fs_write для больших файлов.\n' +
           '• Модель заблокировала ответ по контент-фильтру.';
  }

  // Короткий однострочный заголовок для ошибки (для status-строки).
  // Полный текст пойдёт в разворачиваемый блок «Ответ провайдера».
  // Пример: из "Провайдер ответил ошибкой HTTP 402: Insufficient credits — top up at ..."
  // делаем "HTTP 402: Insufficient credits".
  function shortErrorHeadline(msg) {
    const s = String(msg || '').trim();
    if (!s) return 'Ошибка провайдера';
    // Уберём наш префикс, если есть.
    let core = s.replace(/^Провайдер ответил ошибкой\s*/i, '').replace(/^Ошибка провайдера:\s*/i, '');
    // Отсекаем на первом переводе строки.
    const nl = core.indexOf('\n');
    if (nl > 0) core = core.slice(0, nl);
    // Ограничиваем длину.
    if (core.length > 140) core = core.slice(0, 137) + '…';
    return core || 'Ошибка провайдера';
  }

  // Split assistant text into reasoning (text before tool block) and tool calls.
  //
  // nativeToolCalls (optional) — массив от OpenAI native function-calling
  // (extractOpenAIToolCalls). Если он не пустой — используем его, а markdown
  // не парсим (модели, работающие через native tools, обычно оставляют текст
  // как reasoning; вставить markdown ```tool``` они не должны).
  // Детектор repetition-loop: некоторые модели (наблюдалось у cohere/*)
  // при низкой температуре начинают дословно повторять один и тот же абзац
  // много раз подряд. Возвращает {compacted, repeated} — если повтор найден,
  // текст обрезается до первого вхождения + пометки о повторе, что резко
  // экономит контекст на следующем шаге и не сбивает парсер tool-calls.
  // Детектор repetition-loop. Cohere-модели (и некоторые reasoning-модели) при
  // низкой энтропии начинают:
  //   A) дословно повторять абзац "Давайте посмотрим на X ещё раз..." N раз;
  //   B) генерировать бесконечно нумерованный список одного шаблона
  //      "1. All methods related to X (X). 2. ... 3. ...".
  // Возвращает {compacted, repeated, severe, count}. severe=true, если повтор
  // >=8 (модель точно залипла) — верхний код прерывает цикл и не отправляет
  // очередной turn (иначе тратим бюджет впустую и провоцируем новый loop).
  function compactRepetitions(text) {
    if (!text || text.length < 300) return { compacted: text || '', repeated: false, severe: false, count: 0 };
    // Нормализация: убираем ведущий номер списка ("42. "), скобки в конце
    // "(что-то)", схлопываем пробелы. Так "1. Foo (bar)" и "77. Foo (bar)"
    // считаются одинаковыми.
    const norm = s => s
      .replace(/^\s*\d+[.)]\s*/, '')
      .replace(/\s*\([^()]{1,120}\)\s*$/, '')
      .replace(/\s+/g, ' ')
      .trim()
      .toLowerCase();
    // Разбиваем и на \n\n, и на \n — берём тот вариант, где больше единиц.
    const byPara = text.split(/\n{2,}/).map(s=>s.trim()).filter(Boolean);
    const byLine = text.split(/\n/).map(s=>s.trim()).filter(Boolean);
    const units = byLine.length > byPara.length * 1.5 ? byLine : byPara;
    if (units.length < 4) return { compacted: text, repeated: false, severe: false, count: 0 };
    // n-gram шаблон нормализованной строки: берём первые 40 символов —
    // так ловятся "All methods related to X" при любом X.
    const key = s => {
      const n = norm(s);
      if (n.length < 20) return null;
      return n.slice(0, 40);
    };
    const counts = new Map();
    for (const u of units) {
      const k = key(u);
      if (!k) continue;
      counts.set(k, (counts.get(k) || 0) + 1);
    }
    let worstKey = null, worstCount = 0;
    for (const [k, c] of counts) if (c > worstCount) { worstCount = c; worstKey = k; }
    if (worstCount < 4) return { compacted: text, repeated: false, severe: false, count: 0 };
    // Обрезаем: оставляем всё до 3-го вхождения (первые 3 сохраняем — часто
    // полезны как контекст ошибки), остальные однотипные выкидываем.
    const out = [];
    let hits = 0;
    for (const u of units) {
      if (key(u) === worstKey) {
        hits++;
        if (hits <= 3) out.push(u);
        continue;
      }
      out.push(u);
    }
    const sep = units === byLine ? '\n' : '\n\n';
    const compacted = out.join(sep) +
      `\n\n[клиент: модель залипла — паттерн повторён ${worstCount} раз, обрезано до 3]`;
    return { compacted, repeated: true, severe: worstCount >= 8, count: worstCount };
  }

  // Подсказка модели, когда её tool-вызов пришёл ОБОРВАННЫМ (упёрся в max_tokens).
  // Возвращает строку-примечание (добавляется к tool-результату), которая объясняет
  // модели: ответ обрезан, пиши МЕНЬШИМИ порциями через fs_append, а не один
  // гигантский fs_write. Без этого модель «забывает дописать» и переписывает файл.
  function truncationNote(c) {
    if (!c || !c._truncated) return '';
    const isWrite = /^fs_(write|append)$/.test(c.tool);
    if (isWrite) {
      return '\n\n⚠ ВНИМАНИЕ: твой предыдущий вызов `' + c.tool + '` был ОБОРВАН — ' +
        'содержимое (`content`) не поместилось в лимит ответа и пришло не целиком. ' +
        (c.args && c.args.path
          ? 'Начало файла «' + c.args.path + '» уже сохранено. ПРОДОЛЖИ этот же файл вызовом `fs_append` ' +
            'с СЛЕДУЮЩЕЙ частью содержимого (не переписывай его заново с нуля!). '
          : 'Повтори запись, ') +
        'Дроби большой файл на КОРОТКИЕ куски по ~100–150 строк за один вызов — так ответ не будет обрезаться. ' +
        'НЕ отправляй весь файл одним огромным вызовом.';
    }
    return '\n\n⚠ Твой предыдущий вызов был обрезан по лимиту ответа — повтори с более коротким содержимым.';
  }

  function splitReasoning(text, nativeToolCalls) {
    // Сжимаем повторы ДО парсинга: если модель залипла на повторяющемся
    // абзаце — не тащим весь мусор в reasoning-плашку и в историю следующего
    // turn'а. Native tool_calls при этом уже есть отдельно — их не трогаем.
    if (text) {
      const rep = compactRepetitions(text);
      if (rep.repeated) text = rep.compacted;
    }
    if (Array.isArray(nativeToolCalls) && nativeToolCalls.length) {
      // Приводим к формату, ожидаемому циклом: {tool, args, id?, block?, index}.
      const calls = nativeToolCalls.map(tc => ({
        tool: tc.name, args: tc.args || {}, id: tc.id,
        raw: JSON.stringify({ tool: tc.name, args: tc.args||{} }), block: '', index: 0, native: true,
        _truncated: !!tc._truncated
      }));
      return { reasoning: (text || '').trim(), calls };
    }
    if (!text) return { reasoning:'', calls:[] };
    const calls = parseToolCalls(text);
    if (!calls.length) return { reasoning: text, calls:[] };
    // Reasoning = text up to first tool block.
    // Используем индекс, полученный из parseToolCalls (учитывает и ``` и ~~~).
    const firstIdx = calls[0].index;
    const reasoning = firstIdx > 0 ? text.slice(0, firstIdx).trim() : '';
    return { reasoning, calls };
  }

  // ---- Детектор "пустого намерения" (intent-without-action) ----
  //
  // ПРОБЛЕМА: некоторые модели (наблюдалось у cohere/north-mini-code:free через
  // OpenRouter) на ПЕРВОМ turn'е вместо вызова инструмента выдают только текст-
  // рассуждение: «Пользователь хочет ... Мне нужно: 1) прочитать файл 2) найти
  // 3) удалить. Давайте начнём с чтения файла.» — и НЕ вызывают ни одного tool.
  // Клиент видит текст без tool_calls, считает это финальным ответом и повисает.
  // При ручной регенерации та же модель уже вызывает инструмент и доводит дело
  // до конца. Расширение rutex этой болезни не знает, потому что там модель
  // получает "чистый" native-запрос и вызывает инструмент сразу.
  //
  // РЕШЕНИЕ: если модель вернула ТОЛЬКО текст (без tool-calls), но текст выглядит
  // как «сейчас сделаю / мне нужно / давайте начнём / план: 1)…» — это НЕ финал,
  // а застревание. Тогда мы автоматически (без участия пользователя) делаем то,
  // что раньше приходилось делать вручную кнопкой «регенерировать»: вставляем
  // короткий system-nudge «ты только описал план — ВЫЗОВИ инструмент сейчас» и
  // повторяем turn. Ограничено 2 попытками на turn, чтобы не крутиться вечно.
  //
  // Безопасность для других провайдеров: срабатывает ТОЛЬКО когда (а) нет ни
  // одного tool-call и (б) текст матчит явные фразы-намерения. Нормальный
  // финальный ответ («Готово, лицензия удалена», «Вот результат: …») этих
  // маркеров не содержит и обрабатывается как раньше. Кроме того, nudge — это
  // обычное system-сообщение, оно не меняет параметры запроса и безвредно для
  // любой модели.
  // ВНИМАНИЕ: в JS \b работает ТОЛЬКО с ASCII — вокруг кириллицы он НЕ срабатывает
  // (кириллица не входит в \w). Поэтому для русских паттернов НЕ используем \b:
  // опираемся на сами словоформы и пробелы. Это и была причина, по которой прошлые
  // попытки «поймать намерение» проваливались на русском тексте.
  const _INTENT_RX = [
    // русские фразы намерения (глагол-намерение + действие в пределах строки)
    /(?:сейчас|давайте?)[^.\n]{0,40}(?:приступ|начн|сдела|прочит|посмотр|изуч|проверю|выполн)/i,
    /мне нужно[^.\n]{0,80}(?:прочит|посмотр|изуч|найти|удал|замен|сдела|проверит|определит|увидет)/i,
    /(?:^|[\s,.:;»)])(?:я|сейчас)\s+(?:прочита|прочту|изучу|посмотрю|проверю|сделаю|начну|приступлю|удалю|заменю|открою)/i,
    /давайте?\s+(?:начн[её]м|приступ|посмотр|прочит)/i,
    /начн[её]м\s+с\s+(?:чтения|просмотра|анализа|прочтения)/i,
    /(?:планирую|мой план|план действий|мне предстоит|я собираюсь|буду делать)/i,
    // «Мне нужно:» с последующим нумерованным списком шагов — классический план без действия.
    /мне нужно\s*:?\s*\n\s*1[.)]/i,
    // англ. эквиваленты (на случай англоязычного system prompt / модели) — тут \b безопасен (ASCII)
    /\b(?:i (?:will|need to|should|am going to|'ll)|let(?:'s| us)|first,? i)\b[^.\n]{0,40}\b(?:read|look|check|analyze|examine|start|open|edit|modify|remove|delete|replace)\b/i,
    /\bhere(?:'s| is) (?:my |the )?plan\b|\bi plan to\b/i,
  ];
  function looksLikeIntentWithoutAction(text) {
    const t = String(text || '').trim();
    if (!t || t.length < 12) return false;
    for (const rx of _INTENT_RX) if (rx.test(t)) return true;
    return false;
  }
  // System-подсказка, которую вставляем в историю перед авто-повтором turn'а.
  function actionNudgeNote() {
    return '[СИСТЕМА] Ты только ОПИСАЛ, что собираешься сделать, но не вызвал ни одного инструмента. ' +
      'Описание плана без вызова инструмента — это НЕ выполнение задачи. ' +
      'СЕЙЧАС ЖЕ, на этом шаге, вызови нужный инструмент (например fs_read чтобы прочитать файл, затем fs_replace/fs_write чтобы внести правку). ' +
      'Не пиши больше про свои намерения — просто выполни вызов инструмента.';
  }

  // ---- Детектор «ложного завершения» (claimed done without editing) ----
  //
  // ПРОБЛЕМА: агент читает файл (fs_read) и/или ищет по нему (fs_search), но НЕ
  // вносит правок (нет fs_write/fs_replace/...), а в финальном ответе заявляет,
  // что «всё сделано / исправлено / готово». Пользователь видит в статусе, что
  // ни одной операции записи не было — то есть агент соврал и не довёл до конца.
  //
  // РЕШЕНИЕ: если (а) исходная задача явно требовала ИЗМЕНЕНИЯ (глаголы «исправь /
  // убери / замени / добавь / сделай ...»), и (б) за весь прогон НЕ было ни одной
  // успешной МОДИФИЦИРУЮЩЕЙ операции, и (в) модель пытается финализировать —
  // мы НЕ принимаем финал, а вставляем system-подсказку «ты ничего не изменил,
  // доведи до конца и перепроверь» и повторяем turn (ограниченно). Если после
  // попыток правок так и нет — показываем пользователю честное предупреждение,
  // чтобы «готово» не выглядело правдой.
  //
  // Набор инструментов, которые РЕАЛЬНО меняют файловую систему/репозиторий.
  const MUTATING_TOOLS = new Set([
    'fs_write','fs_replace','fs_append','fs_delete','fs_rename','fs_mkdir',
    'archive_create','archive_extract','git_commit','git_push','git_init'
  ]);
  function isMutatingTool(name) { return MUTATING_TOOLS.has(String(name || '')); }
  // Похоже ли, что задача пользователя требует изменения файлов (а не просто
  // «покажи / объясни / найди / что делает этот код»). Кириллический \b не
  // используем (он в JS не работает с кириллицей).
  const _MODIFY_RX = [
    /исправ|поправ|почин|испорти/i,
    /убери|удали|вырежи|сотри|избав/i,
    /замени|поменя|перепиши|отредактир|правк|подправ/i,
    /добав|встав|допиши|припиши|создай файл|создать файл/i,
    /сделай так|сделай чтобы|сделай, чтобы|реализуй|внеси измен|модифицируй|обнови|переименуй/i,
    /\b(fix|edit|change|replace|remove|delete|add|insert|rename|refactor|implement|modify|update|patch|rewrite|append)\b/i,
  ];
  // Явно НЕ-модифицирующие запросы (перевешивают, если совпали): «покажи / объясни
  // / что делает / прочитай / найди». Если запрос только про чтение — не давим.
  const _READONLY_RX = [
    /^(?:\s*)(?:покажи|объясни|расскажи|что (?:делает|это|за)|как работает|прочитай|прочти|найди|поищи|посмотри|проверь есть ли|сколько|где (?:находится|лежит)|выведи|перечисли|опиши|проанализируй)\b/i,
    /^(?:\s*)(?:show|explain|what (?:does|is)|how does|read|find|search|list|describe|analyze|count|where)\b/i,
  ];
  function taskRequiresModification(userText) {
    const t = String(userText || '').trim();
    if (!t || t.length < 4) return false;
    // Если запрос начинается с явного read-only глагола И не содержит модиф-глагола — нет.
    const hasModify = _MODIFY_RX.some(rx => rx.test(t));
    if (!hasModify) return false;
    return true;
  }
  function verifyCompletionNote() {
    return '[СИСТЕМА] Ты собираешься завершить задачу, НО за весь прогон не было НИ ОДНОЙ операции изменения файлов ' +
      '(fs_write / fs_replace / fs_append / fs_delete / fs_rename). Пользователь просил именно ИЗМЕНИТЬ файл(ы). ' +
      'Чтение (fs_read) и поиск (fs_search) — это НЕ выполнение задачи. Нельзя писать «готово/сделано/исправлено», ' +
      'если ты фактически ничего не записал. СЕЙЧАС ЖЕ доведи задачу до конца: внеси нужные правки через fs_replace ' +
      '(точечно) или fs_write, затем ОБЯЗАТЕЛЬНО перечитай изменённый файл (fs_read) и убедись, что правка на месте. ' +
      'Только после реально внесённой и проверенной правки давай финальный ответ. Если правка объективно НЕ нужна — ' +
      'честно объясни пользователю, почему изменений не требуется (не выдавай это за «сделано»).';
  }
  function falseDoneWarning() {
    return '⚠ Внимание: агент заявил о завершении, но НЕ выполнил ни одной операции изменения файлов ' +
      '(были только чтение/поиск). Скорее всего задача НЕ выполнена. Проверьте результат и при необходимости ' +
      'повторите запрос или уточните его.';
  }

  async function send(userText, attachments) {
    if (running) return;
    const provider = currentProvider();
    if (!provider) {
      pushMsg({ role:'error', content:'Провайдер не настроен. Откройте Настройки ИИ (в меню сверху-слева).' });
      return;
    }
    const text = (userText || '').trim();
    if (!text && !(attachments && attachments.length)) return;
    // Привязываем этот запуск к текущему активному чату. Все последующие записи
    // в историю (pushMsg/статус) и построение контекста будут идти в ЭТОТ чат,
    // даже если пользователь переключится/создаст другой чат во время работы.
    _runChatId = Store.activeChat().id;
    const msg = { role:'user', content: text };
    if (attachments && attachments.length) msg.attachments = attachments;
    pushMsg(msg);
    // Auto-title on first user message
    Store.autoTitleFromFirstMsg(runChat());
    ChatMenu && ChatMenu.refresh && ChatMenu.refresh();
    U.$('#ai-input').value = '';
    U.$('#ai-input').style.height = 'auto';
    running = true;
    abortCtrl = new AbortController();
    setSendState(true);

    currentStatus = null;
    const status = ensureLiveStatus();
    status.set({ label:'Thinking', kind:'think' }, 'Ожидание ответа модели...');

    // Drain any queued interject messages into history with a priority marker.
    // These are user messages that arrived while the agent was still running (or between runs).
    const drainInbox = () => {
      if (!inbox.length) return 0;
      const chat = runChat();
      const items = inbox.splice(0, inbox.length);
      chat.history.push({
        role:'system-note', hidden:true,
        content: '[ПРИОРИТЕТНОЕ СООБЩЕНИЕ ВО ВРЕМЯ РАБОТЫ] Пользователь прислал ' +
          (items.length === 1 ? 'новое сообщение' : items.length+' новых сообщения') +
          ' во время выполнения текущей задачи. Кратко подтверди получение в reasoning, учти его и ПРОДОЛЖИ работу с того шага, на котором остановился (не начинай задачу заново). Если сообщение просит остановиться — заверши текущую задачу коротким итогом.'
      });
      Store.save();
      try { currentStatus && currentStatus.clearInterject && currentStatus.clearInterject(); } catch(_) {}
      return items.length;
    };

    try {
      const maxTool = Store.get().settings.maxToolCalls || 30;
      let toolCount = 0;
      let step = 0;
      let nudgeCount = 0; // сколько раз подряд авто-подтолкнули «вызови инструмент»
      const MAX_NUDGES = 2;
      // Детектор ложного завершения: помним, требовала ли задача правок, и была ли
      // хоть одна успешная модифицирующая операция за прогон.
      const taskWantsEdit = taskRequiresModification(text);
      let didMutate = false;
      let verifyNudges = 0;         // сколько раз подтолкнули «доведи до конца»
      const MAX_VERIFY_NUDGES = 2;
      let status = currentStatus; // shadow outer const with a rebindable var
      const loopDetector = makeToolLoopDetector();
      while (true) {
        step++;
        // Pick up any user messages that arrived before this LLM turn
        drainInbox();
        // Держим карточку статуса согласованной с видимостью рабочего чата:
        // если пользователь ушёл в другой чат — карточка detached (не рисуется
        // в чужом чате); если вернулся — снова присоединяется.
        status = ensureLiveStatus();
        status.set({ label:'Thinking', kind:'think' }, step === 1 ? 'Анализирую запрос...' : 'Продолжаю рассуждать...');
        const messages = buildLLMMessages();
        let text, realModel;
        let nativeToolCalls = [];
        const stopHeartbeat = startWaitingHeartbeat(status, step === 1 ? 'Анализирую' : 'Продолжаю');
        try {
          const resp = await callProviderWithBackoff(provider, messages, abortCtrl.signal,
            (msg) => { stopHeartbeat(); status.set({ label:'Retry', kind:'think' }, msg); });
          text = resp.text; realModel = resp.realModel || '';
          // Показать реальное имя модели (по base URL) под окном статуса.
          if (realModel && status && status.setModel) status.setModel(realModel);
          nativeToolCalls = Array.isArray(resp.toolCalls) ? resp.toolCalls : [];
          // repetition-loop без tool_calls — модель залипла и ничего не делает.
          // Прерываем цикл: показываем текст пользователю, снимаем running,
          // не отправляем очередной turn (иначе получит свой мусор и залипнет).
          if (resp.loopSevere && !nativeToolCalls.length) {
            // Оставляем карточку статуса (persist), а НЕ удаляем — пользователь
            // закроет её сам крестиком. Раньше status.remove() убирал окно сразу.
            if (status && status.persist) status.persist({ label:'Ошибка', kind:'err' }, 'Модель зациклилась');
            pushMsg({ role:'assistant', content: text || '', realModel });
            pushMsg({ role:'error', content: 'Модель зациклилась (паттерн повторён '+resp.loopCount+' раз) — цикл остановлен. Совет: очистите чат и переформулируйте задачу, либо выберите другую модель.' });
            break;
          }
        } catch(e){
          stopHeartbeat();
          if (isAbortError(e)) throw e;
          const emsg = (e && e.message ? e.message : 'Ошибка провайдера: неизвестная ошибка');
          // Оставляем карточку статуса с текстом ошибки (persist), не удаляем.
          if (status && status.persist) status.persist({ label:'Ошибка', kind:'err' }, 'Ошибка провайдера', emsg);
          pushMsg({ role:'error', content: emsg });
          break;
        } finally { stopHeartbeat(); }
        if (!text && !nativeToolCalls.length) {
          if (status && status.persist) status.persist({ label:'Ошибка', kind:'err' }, 'Пустой ответ модели');
          pushMsg({ role:'error', content: emptyResponseMessage() });
          break;
        }

        // Persist assistant message (tool blocks kept for replay; renderer strips them).
        // Если пришли native tool_calls — сохраняем их в _toolCalls, чтобы следующий
        // turn отправил корректный OpenAI-запрос (assistant.tool_calls → tool result).
        const asstMsg = { role:'assistant', content: text || '', realModel, silent:true };
        if (nativeToolCalls.length) asstMsg._toolCalls = nativeToolCalls;
        pushMsg(asstMsg);

        const { reasoning, calls } = splitReasoning(text, nativeToolCalls);
        // Show model's inner monologue live
        if (reasoning) status.setReasoning(reasoning);

        // АВТО-ПОДТАЛКИВАНИЕ: модель вернула ТОЛЬКО текст-намерение (без tool-calls),
        // но текст выглядит как «сейчас сделаю / мне нужно прочитать / план: 1)…».
        // Это застревание (см. looksLikeIntentWithoutAction). Вместо того чтобы
        // считать это финалом и повиснуть, автоматически повторяем turn с коротким
        // system-nudge — так же, как пользователь раньше жал «регенерировать».
        if (!calls.length && nudgeCount < MAX_NUDGES && looksLikeIntentWithoutAction(text)) {
          nudgeCount++;
          // Убираем silent-флаг с только что сохранённого assistant-сообщения не нужно —
          // оно останется в истории как reasoning. Добавляем system-nudge и повторяем.
          pushMsg({ role:'system-note', hidden:true, content: actionNudgeNote() });
          status.set({ label:'Действую', kind:'think' }, 'Модель описала план — подталкиваю к вызову инструмента...');
          continue;
        }

        if (!calls.length) {
          // ЗАЩИТА ОТ ЛОЖНОГО ЗАВЕРШЕНИЯ: задача требовала правок, но ни одной
          // модифицирующей операции не было. Не принимаем финал — подталкиваем
          // довести до конца и перепроверить. Ограниченно (MAX_VERIFY_NUDGES).
          if (taskWantsEdit && !didMutate && verifyNudges < MAX_VERIFY_NUDGES) {
            verifyNudges++;
            pushMsg({ role:'system-note', hidden:true, content: verifyCompletionNote() });
            status.set({ label:'Проверяю', kind:'think' }, 'Изменений в файлах не было — прошу агента довести задачу до конца...');
            continue;
          }
          // Final answer — persist status (свёрнутый) и рендерим assistant ниже
          status.persist({ label:'Готово', kind:'ok' }, 'Задача завершена');
          // Re-render the last assistant message visibly
          const cont = U.$('#ai-messages');
          const chat = runChat();
          const last = chat.history[chat.history.length - 1];
          if (last && last.role === 'assistant') {
            delete last.silent; Store.save();
            if (isRunChatVisible()) { appendMsgEl(last); cont.scrollTop = cont.scrollHeight; }
            playNotifySound();
          }
          // Если задача требовала правок, но их так и не было — честно предупреждаем
          // пользователя, чтобы «готово» не выглядело правдой.
          if (taskWantsEdit && !didMutate) {
            pushMsg({ role:'error', content: falseDoneWarning() });
          }
          // Если пока модель отвечала, пришли новые сообщения от пользователя —
          // НЕ выходим из цикла, продолжим следующий turn с их учётом.
          if (inbox.length) {
            currentStatus = null;
            status = ensureLiveStatus();
            status.set({ label:'Thinking', kind:'think' }, 'Учитываю новое(ые) сообщение(я)...');
            continue;
          }
          break;
        }

        // Модель реально вызвала инструмент — сбрасываем счётчик подталкиваний,
        // чтобы если она застрянет на более позднем шаге, у неё снова были попытки.
        nudgeCount = 0;
        for (const c of calls) {
          if (toolCount++ >= maxTool) {
            status.persist({ label:'Лимит', kind:'err' }, 'Достигнут лимит вызовов инструментов');
            pushMsg({ role:'error', content:'Достигнут лимит вызовов инструментов ('+maxTool+')' });
            running = false; setSendState(false); return;
          }
          if (abortCtrl.signal.aborted) throw new Error('aborted');
          // Если во время работы пришло новое сообщение — прерываем оставшиеся
          // вызовы инструментов в этой партии, чтобы модель ответила на него
          // на следующем LLM turn'е как можно быстрее.
          if (inbox.length) break;

          await throttleBeforeTool(abortCtrl.signal, (m) => status.set({ label:'Wait', kind:'think' }, m));
          const lbl = labelFor(c.tool, c.args, 'pres');
          status.set({ label: lbl.op, kind:'think' }, lbl.detail || c.tool);
          const startedAt = performance.now();
          const res = await Tools.execute(c.tool, c.args, (patch) => {
            if (patch && patch.detail != null) status.set({ label: lbl.op, kind:'think' }, patch.detail);
          });
          const dur = Math.round(performance.now() - startedAt);
          // Отметить факт реальной модификации ФС/репозитория.
          if (res && res.ok && isMutatingTool(c.tool)) didMutate = true;

          const past = labelFor(c.tool, c.args, 'past');
          const resSum = summarizeToolResult(c.tool, c.args, res);
          status.appendLog({
            ok: res.ok,
            op: past.op + (past.detail ? ' ' + past.detail : ''),
            detail: (res.ok ? resSum : (res.error||'error')) + (dur > 5 ? ' · '+dur+'ms' : '')
          });
          // For write-like operations, attach a collapsible file preview
          try {
            const preview = await buildFilePreview(c.tool, c.args, res);
            if (preview) status.appendFilePreview(preview);
          } catch(_){}
          status.set({ label: lbl.op, kind: res.ok ? 'ok' : 'err' },
            (res.ok ? 'Готово: ' : 'Ошибка: ') + (lbl.detail || c.tool) + (res.ok && resSum ? ' — ' + resSum : ''));

          // Store tool result in history (agent will see it next round)
          const trimmed = serializeToolResultForModel(c.tool, res);
          const toolMsg = { role:'tool', name:c.tool, content: (res.ok?'':'ERROR: ')+trimmed+truncationNote(c), silent:true };
          if (c.native && c.id) toolMsg._toolCallId = c.id;
          attachToolImageToMsg(toolMsg, res);
          pushMsg(toolMsg);

          // Проверяем: не залип ли агент в цикле повторяющихся ошибок/вызовов.
          const stall = loopDetector.observe(c.tool, c.args, res);
          if (stall && stall.nudge) {
            // Мягкое подталкивание (без обрыва): read-only повтор — данные уже есть.
            pushMsg({ role:'system-note', hidden:true, content: stall.note });
            status.set({ label:'Действую', kind:'think' }, 'Данные уже прочитаны — подталкиваю к следующему шагу...');
          } else if (stall && stall.stop) {
            status.persist({ label:'Остановлено', kind:'err' }, 'Обнаружен зацикленный вызов инструмента');
            pushMsg({ role:'error', content: '⚠ ' + stall.reason });
            playNotifySound();
            running = false; abortCtrl = null; currentStatus = null; setSendState(false);
            return;
          }
        }
      }
    } catch(e){
      const aborted = isAbortError(e);
      const emsg = e && e.message ? e.message : 'Ошибка';
      try {
        if (aborted && status) status.persist({ label:'Остановлено', kind:'err' }, 'Задача остановлена пользователем');
        // Не удаляем карточку статуса при ошибке — оставляем (persist) с текстом
        // ошибки, чтобы пользователь закрыл её сам.
        else if (status && status.persist) status.persist({ label:'Ошибка', kind:'err' }, 'Ошибка', emsg);
      } catch(_){}
      // Реальный ответ провайдера всегда пушим отдельным сообщением-ошибкой,
      // чтобы юзер видел полный текст рядом с оставшейся карточкой статуса.
      if (!aborted) pushMsg({ role:'error', content: emsg });
    } finally {
      running = false;
      abortCtrl = null;
      currentStatus = null;
      // Если есть отложенные сообщения — продолжаем в ТОМ ЖЕ чате: сохраняем
      // привязку _runChatId для sendContinuation. Иначе снимаем привязку.
      const willContinue = inbox.length > 0;
      if (!willContinue) _runChatId = null;
      setSendState(false);
      if (willContinue) {
        setTimeout(() => { sendContinuation(); }, 0);
      }
    }
  }

  // Continue a run from queued user messages that arrived after the last run finished.
  // Guard: только если inbox действительно НЕ ПУСТ; иначе не запускаем ничего.
  // Inbox очищается через drainInbox — на каждой итерации, но только при наличии элементов.
  async function sendContinuation() {
    if (running) return;
    if (!inbox.length) return;   // <-- главное условие. Без него — бесконечный автоповтор.
    const provider = currentProvider();
    if (!provider) return;
    // Продолжаем в том же чате, что и завершившийся прогон. Если привязка была
    // снята — падаем на активный чат (inbox наполняется только из видимого чата).
    if (!_runChatId) _runChatId = Store.activeChat().id;
    running = true;
    abortCtrl = new AbortController();
    setSendState(true);
    currentStatus = null;
    let status = ensureLiveStatus();
    status.set({ label:'Thinking', kind:'think' }, 'Продолжаю с учётом новых сообщений...');

    // Разгрузка inbox: пушим system-note в историю ТОЛЬКО когда есть новые сообщения.
    // splice очищает inbox — предотвращает бесконечный триггер finally→sendContinuation.
    const drainInboxOnce = () => {
      if (!inbox.length) return 0;
      const chat = runChat();
      const items = inbox.splice(0, inbox.length);
      chat.history.push({
        role:'system-note', hidden:true,
        content: '[ПРИОРИТЕТНОЕ СООБЩЕНИЕ ВО ВРЕМЯ РАБОТЫ] Пользователь прислал ' +
          (items.length === 1 ? 'новое сообщение' : items.length+' новых сообщения') +
          ' во время выполнения текущей задачи. Кратко подтверди получение в reasoning, учти его и ПРОДОЛЖИ работу с того шага, на котором остановился. Если сообщение просит остановиться — заверши текущую задачу коротким итогом.'
      });
      Store.save();
      status.clearInterject && status.clearInterject();
      return items.length;
    };

    try {
      const maxTool = Store.get().settings.maxToolCalls || 30;
      let toolCount = 0;
      let nudgeCount = 0;
      const MAX_NUDGES = 2;
      // Ложное завершение: берём последнюю реплику пользователя как «задачу».
      const lastUserMsg = (() => {
        const h = runChat().history || [];
        for (let i = h.length - 1; i >= 0; i--) if (h[i].role === 'user') return h[i].content || '';
        return '';
      })();
      const taskWantsEdit = taskRequiresModification(lastUserMsg);
      let didMutate = false;
      let verifyNudges = 0;
      const MAX_VERIFY_NUDGES = 2;
      const loopDetector = makeToolLoopDetector();
      while (true) {
        drainInboxOnce();  // разгружаем один раз в начале каждого turn
        status = ensureLiveStatus();
        const messages = buildLLMMessages();
        let text, realModel;
        let nativeToolCalls = [];
        status.set({ label:'Thinking', kind:'think' }, 'Продолжаю задачу...');
        const stopHeartbeat = startWaitingHeartbeat(status, 'Продолжаю задачу');
        try {
          const resp = await callProviderWithBackoff(provider, messages, abortCtrl.signal,
            (msg) => { stopHeartbeat(); status.set({ label:'Retry', kind:'think' }, msg); });
          text = resp.text; realModel = resp.realModel || '';
          // Показать реальное имя модели (по base URL) под окном статуса.
          if (realModel && status && status.setModel) status.setModel(realModel);
          nativeToolCalls = Array.isArray(resp.toolCalls) ? resp.toolCalls : [];
          // repetition-loop без tool_calls — модель залипла и ничего не делает.
          // Прерываем цикл: показываем текст пользователю, снимаем running,
          // не отправляем очередной turn (иначе получит свой мусор и залипнет).
          if (resp.loopSevere && !nativeToolCalls.length) {
            // Оставляем карточку статуса (persist), пользователь закроет её сам.
            if (status && status.persist) status.persist({ label:'Ошибка', kind:'err' }, 'Модель зациклилась');
            pushMsg({ role:'assistant', content: text || '', realModel });
            pushMsg({ role:'error', content: 'Модель зациклилась (паттерн повторён '+resp.loopCount+' раз) — цикл остановлен. Совет: очистите чат и переформулируйте задачу, либо выберите другую модель.' });
            break;
          }
        }
        catch(e){
          stopHeartbeat();
          if (isAbortError(e)) throw e;
          const emsg = (e && e.message ? e.message : 'Ошибка провайдера: неизвестная ошибка');
          if (status && status.persist) status.persist({ label:'Ошибка', kind:'err' }, 'Ошибка провайдера', emsg);
          pushMsg({ role:'error', content: emsg });
          break;
        } finally { stopHeartbeat(); }
        if (!text && !nativeToolCalls.length) {
          if (status && status.persist) status.persist({ label:'Ошибка', kind:'err' }, 'Пустой ответ модели');
          pushMsg({ role:'error', content: emptyResponseMessage() });
          break;
        }
        const asstMsg = { role:'assistant', content: text || '', realModel, silent:true };
        if (nativeToolCalls.length) asstMsg._toolCalls = nativeToolCalls;
        pushMsg(asstMsg);
        const { reasoning, calls } = splitReasoning(text, nativeToolCalls);
        if (reasoning) status.setReasoning(reasoning);
        // АВТО-ПОДТАЛКИВАНИЕ (см. подробности в send()): текст-намерение без вызова
        // инструмента — не финал, а застревание. Повторяем turn с system-nudge.
        if (!calls.length && nudgeCount < MAX_NUDGES && looksLikeIntentWithoutAction(text)) {
          nudgeCount++;
          pushMsg({ role:'system-note', hidden:true, content: actionNudgeNote() });
          status.set({ label:'Действую', kind:'think' }, 'Модель описала план — подталкиваю к вызову инструмента...');
          continue;
        }
        if (!calls.length) {
          // Защита от ложного завершения (см. send()).
          if (taskWantsEdit && !didMutate && verifyNudges < MAX_VERIFY_NUDGES) {
            verifyNudges++;
            pushMsg({ role:'system-note', hidden:true, content: verifyCompletionNote() });
            status.set({ label:'Проверяю', kind:'think' }, 'Изменений в файлах не было — прошу довести до конца...');
            continue;
          }
          status.persist({ label:'Готово', kind:'ok' }, 'Задача завершена');
          const chat2 = runChat(); const last = chat2.history[chat2.history.length-1];
          if (last && last.role === 'assistant') {
            delete last.silent; Store.save();
            if (isRunChatVisible()) { appendMsgEl(last); U.$('#ai-messages').scrollTop = 1e9; }
            playNotifySound();
          }
          if (taskWantsEdit && !didMutate) {
            pushMsg({ role:'error', content: falseDoneWarning() });
          }
          if (inbox.length) {
            currentStatus = null;
            status = ensureLiveStatus();
            status.set({ label:'Thinking', kind:'think' }, 'Учитываю новое(ые) сообщение(я)...');
            continue;
          }
          break;
        }
        nudgeCount = 0;
        for (const c of calls) {
          if (toolCount++ >= maxTool) { status.persist({ label:'Лимит', kind:'err' }, 'Достигнут лимит вызовов'); pushMsg({ role:'error', content:'Лимит вызовов' }); return; }
          if (abortCtrl.signal.aborted) throw new Error('aborted');
          if (inbox.length) break;
          await throttleBeforeTool(abortCtrl.signal, (m) => status.set({ label:'Wait', kind:'think' }, m));
          const lbl = labelFor(c.tool, c.args, 'pres');
          status.set({ label: lbl.op, kind:'think' }, lbl.detail || c.tool);
          const t0 = performance.now();
          const res = await Tools.execute(c.tool, c.args, (patch) => {
            if (patch && patch.detail != null) status.set({ label: lbl.op, kind:'think' }, patch.detail);
          });
          if (res && res.ok && isMutatingTool(c.tool)) didMutate = true;
          const past = labelFor(c.tool, c.args, 'past');
          const resSum = summarizeToolResult(c.tool, c.args, res);
          status.appendLog({ ok: res.ok, op: past.op + (past.detail?' '+past.detail:''),
            detail: (res.ok ? resSum : (res.error||'error')) + ' · ' + Math.round(performance.now()-t0)+'ms' });
          try {
            const preview = await buildFilePreview(c.tool, c.args, res);
            if (preview) status.appendFilePreview(preview);
          } catch(_){}
          status.set({ label: lbl.op, kind: res.ok?'ok':'err' },
            (res.ok?'Готово: ':'Ошибка: ') + (lbl.detail||c.tool) + (res.ok && resSum ? ' — ' + resSum : ''));
          const trimmed = serializeToolResultForModel(c.tool, res);
          const toolMsg = { role:'tool', name:c.tool, content:(res.ok?'':'ERROR: ')+trimmed+truncationNote(c), silent:true };
          if (c.native && c.id) toolMsg._toolCallId = c.id;
          attachToolImageToMsg(toolMsg, res);
          pushMsg(toolMsg);

          const stall = loopDetector.observe(c.tool, c.args, res);
          if (stall && stall.nudge) {
            // Мягкое подталкивание (без обрыва): read-only повтор — данные уже есть.
            pushMsg({ role:'system-note', hidden:true, content: stall.note });
            status.set({ label:'Действую', kind:'think' }, 'Данные уже прочитаны — подталкиваю к следующему шагу...');
          } else if (stall && stall.stop) {
            status.persist({ label:'Остановлено', kind:'err' }, 'Обнаружен зацикленный вызов инструмента');
            pushMsg({ role:'error', content: '⚠ ' + stall.reason });
            playNotifySound();
            running = false; abortCtrl = null; currentStatus = null; setSendState(false);
            return;
          }
        }
      }
    } catch(e){
      const aborted = isAbortError(e);
      const emsg = e && e.message ? e.message : 'Ошибка';
      try {
        if (aborted && status) status.persist({ label:'Остановлено', kind:'err' }, 'Задача остановлена пользователем');
        // Не удаляем карточку статуса при ошибке — оставляем (persist).
        else if (status && status.persist) status.persist({ label:'Ошибка', kind:'err' }, 'Ошибка', emsg);
      } catch(_){}
      // Реальный ответ провайдера всегда пушим отдельным сообщением-ошибкой,
      // чтобы юзер видел полный текст рядом с оставшейся карточкой статуса.
      if (!aborted) pushMsg({ role:'error', content: emsg });
    } finally {
      running = false; abortCtrl = null; currentStatus = null;
      const willContinue = inbox.length > 0;
      if (!willContinue) _runChatId = null;
      setSendState(false);
      // Auto-continue ТОЛЬКО если inbox действительно не пуст. Внутри самой функции
      // мы уже разгружали inbox через drainInboxOnce, так что повторный запуск
      // произойдёт лишь если пришло НОВОЕ сообщение уже после разгрузки.
      if (willContinue) setTimeout(() => sendContinuation(), 0);
    }
  }

  function stop() {
    // Мгновенно прерываем сетевой запрос (fetch по signal) — это уже даёт быстрый
    // разрыв соединения. Дополнительно СРАЗУ отражаем остановку в UI, не дожидаясь,
    // пока асинхронный цикл долетит до проверки signal.aborted: снимаем running,
    // возвращаем кнопку в состояние «отправить» и закрываем активную статус-карточку.
    if (abortCtrl) { try { abortCtrl.abort(); } catch(_){} }
    running = false;
    try { if (currentStatus && currentStatus.persist) currentStatus.persist({ label:'Остановлено', kind:'err' }, 'Остановлено пользователем'); } catch(_){}
    currentStatus = null;
    _runChatId = null;
    setSendState(false);
  }

  // Three visual states: idle-send (▶), running-stop (⏹), running-interject (▶ + orange).
  // When the textarea has content, the button ALWAYS acts as send — even during a run —
  // and posts the message to the agent's inbox instead of stopping the run.
  function updateSendButton() {
    const b = U.$('#ai-send');
    const input = U.$('#ai-input');
    const hasText = !!(input && input.value.trim());
    const hasAtt = !!(window.Attachments && Attachments.get().length);
    const hasContent = hasText || hasAtt;
    // «Работает ли агент в ОТКРЫТОМ СЕЙЧАС чате». Кнопка ⏹/интерджект относится
    // только к запуску, привязанному к текущему открытому чату. Если агент
    // работает в другом (фоновом) чате — в этом чате кнопка обычная ▶.
    const runningHere = running && isRunChatVisible();
    // Determine mode
    let mode = 'send'; // idle send
    if (runningHere && !hasContent) mode = 'stop';
    else if (runningHere && hasContent) mode = 'interject';
    b.classList.toggle('stop', mode === 'stop');
    b.classList.toggle('interject', mode === 'interject');
    b.setAttribute('aria-label', mode === 'stop' ? 'Остановить' : (mode === 'interject' ? 'Отправить агенту (без остановки)' : 'Отправить'));
    b.title = b.getAttribute('aria-label');
    const sendIcon = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 2L11 13M22 2l-7 20-4-9-9-4 20-7z"/></svg>';
    const stopIcon = '<svg viewBox="0 0 24 24" fill="currentColor"><rect x="6" y="6" width="12" height="12" rx="2"/></svg>';
    b.innerHTML = mode === 'stop' ? stopIcon : sendIcon;
  }
  function setSendState(isRunning) {
    // preserved for API compat; delegates to updateSendButton
    running = !!isRunning; // (running is a module-level flag; keep in sync)
    updateSendButton();
  }

  function submitFromInput() {
    const input = U.$('#ai-input');
    const text = (input.value || '').trim();
    const atts = (window.Attachments && Attachments.get()) || [];
    if (!text && atts.length === 0) {
      // Empty: if running IN THIS chat -> stop; else nothing.
      if (running && isRunChatVisible()) stop();
      return;
    }
    // Агент занят В ДРУГОМ (фоновом) чате — нельзя ни интерджектить сюда, ни
    // запускать второй параллельный прогон (один abortCtrl/статус на приложение).
    if (running && !isRunChatVisible()) {
      UI.toast('Агент занят в другом чате — дождитесь завершения', 'err');
      return;
    }
    input.value = '';
    input.style.height = 'auto';
    const taken = window.Attachments ? Attachments.take() : [];
    if (running) {
      interject(text || '(файл(ы) прикреплены)', taken);
    } else {
      send(text, taken);
    }
    updateSendButton();
  }

  // Public: interject a user message into an ongoing run.
  function interject(text, attachments) {
    const chat = runChat();
    const msg = { role:'user', content: text, interject: true };
    if (attachments && attachments.length) msg.attachments = attachments;
    chat.history.push(msg);
    chat.updatedAt = Date.now();
    Store.save();
    // Render bubble via appendMsgEl (respecting attachments) then move it before the status block.
    const cont = U.$('#ai-messages');
    const before = cont.lastElementChild;
    appendMsgEl(msg);
    const el = cont.lastElementChild;
    if (el && currentStatus && currentStatus.el && currentStatus.el.parentElement === cont && el !== currentStatus.el) {
      cont.insertBefore(el, currentStatus.el);
    }
    cont.scrollTop = cont.scrollHeight;
    inbox.push(text);
    if (currentStatus) currentStatus.setInterject(inbox.length);
  }

  function bind() {
    const input = U.$('#ai-input');
    input.addEventListener('input', () => {
      input.style.height = 'auto';
      input.style.height = Math.min(120, input.scrollHeight) + 'px';
      updateSendButton();
    });
    // Enter в поле ввода делает перенос строки (нативное поведение textarea).
    // Отправка — только по кнопке ▶ справа. Ctrl+Enter / Cmd+Enter — тоже отправка
    // как удобная альтернатива (частая привычка), но обычный Enter больше не шлёт.
    input.addEventListener('keydown', e => {
      if (e.key === 'Enter' && (e.ctrlKey || e.metaKey) && !e.isComposing) {
        e.preventDefault();
        submitFromInput();
      }
    });
    U.$('#ai-send').addEventListener('click', () => submitFromInput());
    // Очистка чата — без подтверждения (по запросу пользователя): жмём — сразу чистим.
    U.$('#ai-clear').addEventListener('click', () => { clear(); });
    updateSendButton();
  }

  // ---- List models: try /v1/models, /models on base URL, through same proxy chain ----
  // Returns { models: [{id, name?, ...}], sourceUrl, proxyUsed } or throws.
  async function listModels(provider) {
    const rawBase = normalizeBaseUrl(provider.baseUrl);
    if (!rawBase) throw new Error('Base URL пуст');
    const apiKey = provider.apiKey || '';
    // Derive a base for /models by stripping /chat/completions or /completions or /messages
    const stripped = rawBase.replace(/\/(v1\/)?(chat\/)?completions\/?$/, '').replace(/\/messages\/?$/, '').replace(/\/+$/, '');
    // Candidate model-listing endpoints
    const cands = [];
    // Special-case: Google Gemini uses /v1beta/models
    if (isGoogle(rawBase)) {
      let u = stripped.replace(/\/(v1|v1beta)?\/?models.*$/, '');
      u = u.replace(/\/+$/, '') + '/v1beta/models';
      if (apiKey && !/[?&]key=/.test(u)) u += '?key=' + encodeURIComponent(apiKey);
      cands.push(u);
    } else {
      // OpenAI-compat and Anthropic. Строим варианты УМНО, без дублей /v1/v1/:
      // если stripped уже заканчивается на версию (…/v1, …/v2, …/v1beta),
      // добавляем только /models; иначе — сначала /v1/models, затем /models.
      const strippedEndsWithVersion = /\/v\d+[a-z]*$/i.test(stripped);
      if (strippedEndsWithVersion) {
        cands.push(stripped + '/models');
      } else {
        cands.push(stripped + '/v1/models');
        cands.push(stripped + '/models');
      }
      // Also try appending to raw base if user gave weird URL
      if (!/models\/?$/.test(rawBase)) {
        cands.push(rawBase.replace(/\/+$/, '') + '/models');
      }
    }
    const uniq = Array.from(new Set(cands));
    const userProxy = (provider.corsProxy || '').trim();
    const chain = proxyChain(userProxy);

    function hdrs() {
      const h = { 'Accept':'application/json' };
      if (apiKey && !isGoogle(rawBase)) {
        if (isAnthropic(rawBase)) { h['x-api-key'] = apiKey; h['anthropic-version'] = '2023-06-01'; }
        else { h['Authorization'] = 'Bearer ' + apiKey; }
      }
      if (provider.extraHeaders) {
        try { Object.assign(h, typeof provider.extraHeaders === 'string' ? JSON.parse(provider.extraHeaders) : provider.extraHeaders); } catch(e){}
      }
      return h;
    }

    const errors = [];
    for (const targetUrl of uniq) {
      for (const tmpl of chain) {
        const finalUrl = tmpl ? buildProxyUrl(tmpl, targetUrl) : targetUrl;
        try {
          const r = await fetch(finalUrl, { method:'GET', headers: hdrs() });
          if (!r.ok) { errors.push(finalUrl + ' -> HTTP ' + r.status); continue; }
          const j = await r.json();
          // Normalize: OpenAI: {data:[{id}]}, Anthropic: {data:[{id, display_name}]}, Google: {models:[{name}]}
          let models = [];
          if (Array.isArray(j.data)) {
            models = j.data.map(m => ({
              id: m.id || m.name || '',
              name: m.name || m.display_name || m.id || '',
              raw: m
            })).filter(m => m.id);
          } else if (Array.isArray(j.models)) {
            models = j.models.map(m => ({
              id: (m.name || m.id || '').replace(/^models\//,''),
              name: m.displayName || m.name || m.id || '',
              raw: m
            })).filter(m => m.id);
          } else if (Array.isArray(j)) {
            models = j.map(m => typeof m === 'string' ? { id:m, name:m } : { id: m.id||m.name, name: m.name||m.id, raw:m }).filter(m => m.id);
          }
          if (!models.length) { errors.push(finalUrl + ' -> ответ без моделей'); continue; }
          return { models, sourceUrl: targetUrl, proxyUsed: tmpl };
        } catch(e) {
          errors.push(finalUrl + ' -> ' + (e.message || e));
        }
      }
    }
    throw new Error('Не удалось получить список моделей:\n' + errors.slice(0, 6).join('\n'));
  }

  // ---- Стоимость модели: платная / бесплатная ----
  // Определяем по данным из /models (объект raw), которые уже сохранены при загрузке
  // списка. Никаких дополнительных запросов — цена приходит прямо от провайдера.
  //
  // Возвращает: 'free' | 'paid' | 'unknown'.
  //
  // Поддерживаемые форматы прайсинга:
  //   • OpenAI/OpenRouter/совместимые: raw.pricing = { prompt, completion, ... }
  //     (строки/числа за токен). Все нули → бесплатно, любой ненулевой → платно.
  //   • Явные булевы/строковые флаги: raw.free, raw.is_free, raw.tier==='free'.
  //   • Суффикс ":free" в id (OpenRouter) → бесплатно.
  //   • Поля стоимости у иных провайдеров: input_price/output_price/price/cost_*.
  // Если данных о цене нет вообще → 'unknown' (серый кружок, честно «неизвестно»).
  function modelCostTier(model) {
    if (!model) return 'unknown';
    const id = String(model.id || '').toLowerCase();
    // OpenRouter и ряд провайдеров маркируют бесплатные варианты суффиксом :free.
    if (/:free\b/.test(id) || /\bfree\b/.test(id.split('/').pop() || '')) {
      // но если есть явный ненулевой pricing — доверяем цене (ниже).
    }
    const raw = model.raw || {};
    // Venice и ряд провайдеров кладут метаданные (в т.ч. pricing) внутрь
    // model_spec / spec. Учитываем и такой уровень.
    const spec = (raw.model_spec && typeof raw.model_spec === 'object') ? raw.model_spec
               : (raw.spec && typeof raw.spec === 'object') ? raw.spec
               : {};

    // Явные флаги «бесплатно».
    if (raw.free === true || raw.is_free === true || spec.free === true || spec.is_free === true) return 'free';
    if (typeof raw.tier === 'string' && /free/i.test(raw.tier)) return 'free';
    if (typeof spec.tier === 'string' && /free/i.test(spec.tier)) return 'free';

    // Собираем все «ценовые» числа из известных мест.
    const priceStrings = [];
    // Извлекает число из значения любой формы: число, строка ("$1.4"), либо
    // вложенный объект вида { usd: 1.4, diem: 1.4 } (формат Venice) — тогда
    // берём usd, а если его нет — любое числовое поле объекта.
    const pushPrice = (v) => {
      if (v === null || v === undefined) return;
      if (typeof v === 'number') { priceStrings.push(v); return; }
      if (typeof v === 'string') {
        const n = parseFloat(v.replace(/[^0-9.eE+\-]/g, ''));
        if (!isNaN(n)) priceStrings.push(n);
        return;
      }
      if (typeof v === 'object') {
        // Вложенный ценник по валютам: приоритет usd, затем любое число.
        if (typeof v.usd === 'number' || typeof v.usd === 'string') { pushPrice(v.usd); return; }
        for (const inner of Object.values(v)) {
          if (typeof inner === 'number' || typeof inner === 'string') { pushPrice(inner); return; }
        }
      }
    };
    let sawPricingObject = false;
    // OpenAI/OpenRouter pricing-объект — на верхнем уровне raw ИЛИ в model_spec (Venice).
    const pricingObj = (raw.pricing && typeof raw.pricing === 'object') ? raw.pricing
                     : (spec.pricing && typeof spec.pricing === 'object') ? spec.pricing
                     : null;
    if (pricingObj) {
      sawPricingObject = true;
      // Значимые для платности поля — стоимость токенов (не web_search / кэш и т.п.).
      ['prompt', 'completion', 'input', 'output', 'request', 'image'].forEach(k => {
        if (k in pricingObj) pushPrice(pricingObj[k]);
      });
      // Если специфичных ключей не нашли — берём все значения объекта.
      if (!priceStrings.length) {
        for (const v of Object.values(pricingObj)) pushPrice(v);
      }
    }
    // Плоские ценовые поля у иных провайдеров (на верхнем уровне и в spec).
    ['input_price','output_price','prompt_price','completion_price','price','cost','input_cost_per_token','output_cost_per_token']
      .forEach(k => {
        if (k in raw) { sawPricingObject = true; pushPrice(raw[k]); }
        if (k in spec) { sawPricingObject = true; pushPrice(spec[k]); }
      });

    if (priceStrings.length) {
      const anyPaid = priceStrings.some(n => n > 0);
      return anyPaid ? 'paid' : 'free';
    }
    // Прайсинг-объект был, но все значения нечисловые/пустые → трактуем как бесплатно.
    if (sawPricingObject) return 'free';

    // Нет данных о цене. Последняя зацепка — суффикс :free в id.
    if (/:free\b/.test(id)) return 'free';
    return 'unknown';
  }

  // ---- Проверка доступности конкретной модели по ключу/тарифу ----
  // Отправляет МИНИМАЛЬНЫЙ запрос (1 токен) на выбранную модель и по ответу решает,
  // доступна ли она текущему API-ключу/плану. Возвращает объект:
  //   { available:true }                        — модель работает (зелёный кружок);
  //   { available:false, status, reason, raw }  — модель недоступна (красный кружок);
  //   { available:null, reason }                — не удалось проверить (серый: сеть/CORS/таймаут).
  //
  // Логика классификации (работает для OpenAI-совместимых, Anthropic, Google):
  //   • HTTP 200 с осмысленным ответом → доступна.
  //   • 401/403 «нет доступа к модели», 404 «модель не найдена», 400 «model not found/
  //     invalid model/not allowed», 402/429 budget_exhausted/insufficient → НЕдоступна.
  //   • 429 обычный rate-limit (без budget) → считаем доступной (ключ валиден, просто лимит).
  //   • сетевая ошибка/таймаут/не-JSON → неизвестно (null).
  async function probeModel(provider, modelId, opts = {}) {
    const timeoutMs = Math.min(20000, (opts.timeoutMs || 15000));
    const baseUrl = normalizeBaseUrl(provider.baseUrl);
    if (!baseUrl) return { available:null, reason:'Base URL пуст' };
    const apiKey = provider.apiKey || '';
    const userProxy = (provider.corsProxy || '').trim();
    const chain = proxyChain(userProxy);

    function hdrs() {
      const h = { 'Content-Type':'application/json', 'Accept':'application/json' };
      if (apiKey && !isGoogle(baseUrl)) {
        if (isAnthropic(baseUrl)) { h['x-api-key'] = apiKey; h['anthropic-version'] = '2023-06-01'; }
        else { h['Authorization'] = 'Bearer ' + apiKey; }
      }
      if (provider.extraHeaders) {
        try { Object.assign(h, typeof provider.extraHeaders === 'string' ? JSON.parse(provider.extraHeaders) : provider.extraHeaders); } catch(e){}
      }
      return h;
    }

    // Строим URL и тело под формат провайдера.
    let targets, body;
    if (isGoogle(baseUrl)) {
      let u = baseUrl.replace(/\/+$/, '');
      if (!/generateContent/.test(u)) u += `/v1beta/models/${encodeURIComponent(modelId)}:generateContent`;
      if (apiKey && !/[?&]key=/.test(u)) u += (u.includes('?')?'&':'?') + 'key=' + encodeURIComponent(apiKey);
      targets = [u];
      body = { contents:[{ role:'user', parts:[{ text:'hi' }] }], generationConfig:{ maxOutputTokens:1 } };
    } else if (isAnthropic(baseUrl)) {
      let u = baseUrl.replace(/\/+$/, '');
      if (!/\/messages(\?|$)/.test(u)) u += '/v1/messages';
      targets = [u];
      body = { model: modelId, max_tokens:1, messages:[{ role:'user', content:'hi' }] };
    } else {
      // OpenAI-совместимый: тот же список кандидатов, что и для чата.
      targets = candidateEndpoints(baseUrl, { rawUrl: !!provider.rawUrl });
      body = { model: modelId, max_tokens:1, messages:[{ role:'user', content:'hi' }], stream:false };
    }

    // baseTariffOnly=true (по умолчанию для индикатора доступности): проверяем,
    // ПРОЙДЁТ ли запрос по БАЗОВОМУ/бесплатному тарифу прямо сейчас. Тогда
    // исчерпанный бесплатный лимит и «нужен платный план» трактуются как
    // НЕдоступно (красный), даже если ключ формально валиден.
    const baseTariffOnly = opts.baseTariffOnly !== false;
    const isAuthoritative = (status) => status !== 404 && status !== 405;
    const looksModelUnavailable = (status, txt) => {
      const s = String(txt||'').toLowerCase();
      if (/budget_exhausted|insufficient|balance is insufficient|add credits|no credits|out of credits|payment required/.test(s)) return true;
      if (/model.*(not found|not exist|unavailable|not allowed|no access|not permitted|does not exist|invalid model|unknown model)/.test(s)) return true;
      if (/(no access|not authorized|forbidden|not permitted|not entitled).*model|model.*(no access|not authorized|forbidden)/.test(s)) return true;
      if (status === 402 || status === 403) return true;
      if (status === 404 && /model|resource/.test(s)) return true;
      return false;
    };
    // «Жёсткий» лимит бесплатного тарифа: провайдер отвечает, что бесплатный
    // лимит модели исчерпан / нужен платный план / бесплатные провайдеры выбрали
    // свой лимит. По базовому тарифу такой запрос НЕ проходит → недоступно.
    // Примеры (UnoRouter): "Free model X is rate-limited ... use the paid X for
    // no limit"; "free providers hit their rate limit ... switch to another model";
    // код insufficient_user_quota.
    const looksFreeTierExhausted = (status, txt) => {
      const s = String(txt||'').toLowerCase();
      if (/insufficient_user_quota|user_quota|quota_exhausted|quota exceeded|out of quota|daily limit|free (tier|plan|quota|limit)/.test(s)) return true;
      if (/free\s+model.*(rate.?limit|limit)/.test(s)) return true;                 // "Free model X is rate-limited"
      if (/use the paid|upgrade to (a )?paid|switch to (a )?paid|no limit\b/.test(s)) return true;
      if (/free providers? (hit|reached|exceeded).*(rate.?limit|limit)/.test(s)) return true;
      return false;
    };
    // «Ложный» 429: провайдер троттлит из-за того, что предыдущие запросы
    // ЗАВЕРШИЛИСЬ ОШИБКОЙ (например Venice: "Too many failed attempts (> 20)
    // resulting in a non-success status code"). Это НЕ обычный rate-limit
    // валидного ключа — это маскировка реальной причины отказа (чаще всего
    // 402 «нет баланса»). Такой ответ НЕЛЬЗЯ трактовать как «доступно»:
    // его надо перепроверить позже, а если не удаётся — считать неизвестным
    // и падать на статическую цену (которая для платных моделей = красный).
    const looksThrottledAfterFailures = (status, txt) => {
      const s = String(txt||'').toLowerCase();
      if (status !== 429) return false;
      return /too many failed attempts|non-?success status|resulting in (a )?failure|previous (requests?|attempts?) failed/.test(s);
    };
    const looksRateLimitOnly = (status, txt) => {
      const s = String(txt||'').toLowerCase();
      if (/budget|insufficient|balance|credits|payment/.test(s)) return false; // это НЕ временный лимит
      // Исчерпанный бесплатный лимит при проверке базового тарифа = недоступно, а
      // не «просто временный лимит».
      if (baseTariffOnly && looksFreeTierExhausted(status, txt)) return false;
      // Троттлинг из-за прошлых ОШИБОК — не признак валидности ключа/модели.
      if (looksThrottledAfterFailures(status, txt)) return false;
      return status === 429 || /rate.?limit|too many requests|throttl/.test(s);
    };

    // Один запрос к конкретному URL. Возвращает { done, result } — done=true
    // означает «получен авторитетный вердикт, дальше перебирать не нужно».
    async function attempt(finalUrl) {
      const ctrl = new AbortController();
      const timer = setTimeout(() => { try { ctrl.abort(); } catch(_){} }, timeoutMs);
      let r, text;
      try {
        r = await fetch(finalUrl, { method:'POST', headers:hdrs(), body:JSON.stringify(body), signal:ctrl.signal });
        clearTimeout(timer);
        text = await r.text();
      } catch(e) {
        clearTimeout(timer);
        return { done:false, netError: (e && e.name === 'AbortError') ? 'timeout' : (e.message || String(e)) };
      }
      if (r.ok) return { done:true, result:{ available:true, status:r.status } };
      const status = r.status;
      // 404/405 на этом кандидате — возможно не тот путь, пробуем следующий.
      if (!isAuthoritative(status) && !looksModelUnavailable(status, text) && !(baseTariffOnly && looksFreeTierExhausted(status, text))) {
        return { done:false, status, raw:text, reason:'HTTP '+status };
      }
      // Троттлинг из-за прошлых ОШИБОК (Venice "too many failed attempts…") —
      // НЕ вердикт. Возвращаем как «нужно перепроверить».
      if (looksThrottledAfterFailures(status, text)) {
        return { done:false, throttled:true, status, raw:text, reason:'throttled-after-failures' };
      }
      // Авторитетный ответ.
      if (looksModelUnavailable(status, text)) return { done:true, result:{ available:false, status, reason:'HTTP '+status, raw:text } };
      if (baseTariffOnly && looksFreeTierExhausted(status, text)) return { done:true, result:{ available:false, status, reason:'free-tier-exhausted', raw:text } };
      if (looksRateLimitOnly(status, text)) return { done:true, result:{ available:true, status, note:'rate-limited' } };
      if (status === 401) return { done:true, result:{ available:null, status:401, reason:'invalid_api_key', raw:text } };
      if (status === 400 || status === 422) return { done:true, result:{ available:true, status, note:'param-rejected' } };
      return { done:true, result:{ available:false, status, reason:'HTTP '+status, raw:text } };
    }

    let lastReason = null, lastStatus = null, lastRaw = null, sawThrottle = false;
    for (const targetUrl of targets) {
      for (const tmpl of chain) {
        const finalUrl = tmpl ? buildProxyUrl(tmpl, targetUrl) : targetUrl;
        // Небольшой retry-с-паузой именно для «ложного» 429 троттлинга:
        // даём провайдеру остыть и пытаемся получить настоящий статус (обычно 402).
        const backoffs = [0, 1500, 4000];
        for (let i = 0; i < backoffs.length; i++) {
          if (backoffs[i]) await new Promise(res => setTimeout(res, backoffs[i]));
          const a = await attempt(finalUrl);
          if (a.done) return a.result;
          if (a.netError) { lastReason = a.netError; break; } // сеть/CORS → следующий прокси
          if (a.status != null) { lastStatus = a.status; lastRaw = a.raw; lastReason = a.reason; }
          if (a.throttled) { sawThrottle = true; continue; } // повторим после паузы
          break; // не-авторитетный (404/405) → следующий прокси/endpoint
        }
      }
    }
    // Если единственное, что мы видели — троттлинг «too many failed attempts»,
    // это НЕ значит, что модель доступна. Вердикт неизвестен → UI упадёт на
    // статическую цену (для платных моделей это красный кружок).
    return { available:null, status:lastStatus, reason:sawThrottle ? 'throttled-after-failures' : (lastReason || 'unreachable'), raw:lastRaw };
  }

  // ---- Итоговый цвет кружка ДОСТУПНОСТИ по базовому тарифу ----
  // Возвращает 'free' (зелёный) | 'paid' (красный) | 'unknown' (серый).
  //
  // Логика (то, что просил пользователь):
  //   • Если есть результат РЕАЛЬНОЙ проверки (probeModel по базовому тарифу) —
  //     он главный: доступна сейчас → зелёный; недоступна (платная / исчерпан
  //     бесплатный лимит / нет доступа) → красный; проверить не удалось → падаем
  //     на статику цены.
  //   • Иначе — статическая цена из /models (modelCostTier).
  // probe: объект { available:true|false|null, ... } из probeModel, либо undefined.
  function availabilityTier(model, probe) {
    if (probe && probe.available === true) {
      // «Доступна», но вердикт получен не по реальному 200, а по мягкому признаку
      // (обычный rate-limit / отклонённый параметр). Если при этом статическая цена
      // однозначно говорит «платная» — доверяем цене (красный), чтобы не показать
      // ложно-зелёный кружок у платной модели, которую сервер лишь временно
      // притормозил вместо честного отказа по балансу.
      if (probe.note === 'rate-limited' && modelCostTier(model) === 'paid') return 'paid';
      return 'free';
    }
    if (probe && probe.available === false) return 'paid';
    // probe.available === null (не удалось проверить) или probe нет → статика цены.
    return modelCostTier(model);
  }

  // Пакетная проверка доступности списка моделей по базовому тарифу с ограниченной
  // параллельностью. Вызывает onResult(model, probe) по мере готовности каждой,
  // чтобы UI мог перекрашивать кружки на лету. Возвращает Map(id → probe).
  async function probeModels(provider, models, opts = {}) {
    // Умеренная параллельность: слишком агрессивный «залп» провоцирует у части
    // провайдеров (напр. Venice) защитный троттлинг «too many failed attempts»,
    // который маскирует настоящую причину отказа (нет баланса). 3 достаточно
    // быстро и заметно мягче для сервера.
    const concurrency = Math.max(1, Math.min(8, opts.concurrency || 3));
    const timeoutMs = opts.timeoutMs || 12000;
    const onResult = typeof opts.onResult === 'function' ? opts.onResult : null;
    const shouldStop = typeof opts.shouldStop === 'function' ? opts.shouldStop : () => false;
    const results = new Map();
    let idx = 0;
    async function worker() {
      while (idx < models.length) {
        if (shouldStop()) return;
        const m = models[idx++];
        let probe;
        try {
          probe = await probeModel(provider, m.id, { baseTariffOnly: true, timeoutMs });
        } catch (e) {
          probe = { available:null, reason: (e && e.message) || String(e) };
        }
        results.set(m.id, probe);
        if (onResult && !shouldStop()) { try { onResult(m, probe); } catch(_){} }
      }
    }
    const workers = [];
    for (let i = 0; i < concurrency; i++) workers.push(worker());
    await Promise.all(workers);
    return results;
  }

  // Вызывается после переключения/создания чата (из ChatMenu). Перерисовывает
  // сообщения открытого чата и синхронизирует кнопку отправки: ⏹ показывается
  // только если агент работает ИМЕННО в открытом сейчас чате; в новом/другом
  // чате — обычная ▶.
  function onChatSwitched() {
    renderMessages();
    // Синхронизируем живую карточку статуса с новым видимым чатом:
    //  • если открыт рабочий чат — присоединяем живую карточку (была detached);
    //  • если ушли в другой чат — карточку detach'им, чтобы она не «протекала».
    // renderMessages() уже очистил DOM: detached-карточка исчезла из чужого чата,
    // а при возврате ensureLiveStatus() присоединит её обратно с накопленным логом.
    if (running) {
      try { ensureLiveStatus(); } catch(_) {}
    }
    updateSendButton();
  }
  return { bind, renderMessages, updateHeader, send, clear, currentProvider, listModels, probeModel, probeModels, modelCostTier, availabilityTier, _pendingAttachments, _updateSendButton: updateSendButton, playNotifySound, onChatSwitched };
})();
window.AI = AI;
