/* ===== Settings sheet: providers CRUD, github, options ===== */
const Settings = (() => {

  function open() {
    const body = U.el('div');
    body.appendChild(renderProvidersSection());
    body.appendChild(renderOptionsSection());
    body.appendChild(renderGithubSection());
    UI.openSheet('Настройки ИИ', body);
  }

  function renderProvidersSection() {
    const s = Store.get();
    const sect = U.el('div', { class:'settings-section' });
    sect.appendChild(U.el('h4', {}, 'ПРОВАЙДЕРЫ'));
    if (!s.providers.length) {
      sect.appendChild(U.el('div', { style:{color:'var(--fg-dim)', fontSize:'13px', margin:'8px 0'} }, 'Провайдеров нет. Добавьте свой.'));
    }
    for (const p of s.providers) sect.appendChild(providerItem(p));
    const addBtn = U.el('button', { class:'btn primary', style:{width:'100%', marginTop:'8px'} }, '+ Добавить провайдера');
    addBtn.addEventListener('click', () => editProvider(null));
    sect.appendChild(addBtn);
    return sect;
  }

  function providerItem(p) {
    const s = Store.get();
    const isActive = s.activeProviderId === p.id;
    const wrap = U.el('div', { class:'provider-item' },
      U.el('div', { class:'row' },
        U.el('div', { class:'name' }, p.name),
        isActive ? U.el('span', { class:'badge' }, 'АКТИВНЫЙ') : null
      ),
      U.el('div', { class:'info' },
        U.el('div', {}, 'URL: ' + (p.baseUrl || '—') + (p.rawUrl ? '  [как есть]' : '')),
        U.el('div', {}, 'Модель: ' + (p.model || '—')),
        U.el('div', {}, 'API key: ' + (p.apiKey ? maskKey(p.apiKey) : '—')),
        U.el('div', {}, 'CORS-прокси: ' + (p.corsProxy || 'авто (встроенные публичные)'))
      ),
      U.el('div', { class:'actions' },
        (!isActive ? btn('Сделать активным', () => { Store.set({ activeProviderId: p.id }); refresh(); AI.updateHeader(); }, 'small primary') : null),
        iconBtn(PROV_ICON_EDIT, 'Редактировать', () => editProvider(p), ''),
        iconBtn(PROV_ICON_TRASH, 'Удалить', async () => {
          if (!await UI.confirm('Удалить провайдера "'+p.name+'"?', {danger:true})) return;
          const st = Store.get(); st.providers = st.providers.filter(x => x.id !== p.id);
          if (st.activeProviderId === p.id) st.activeProviderId = null;
          Store.save(); refresh(); AI.updateHeader();
        }, 'danger')
      )
    );
    return wrap;
  }

  function editProvider(p) {
    const isNew = !p;
    const draft = p ? { ...p } : { id: 'p_'+U.uid(), name:'', baseUrl:'', apiKey:'', model:'', extraHeaders:'', rawUrl:false, corsProxy:'' };
    const dlgBody = U.el('div');
    const inputs = {}; // refs to each input by key
    // opts.hint — короткое описание, показывается ПОД полем в раскрывающемся
    // спойлере (заголовок спойлера = название поля). opts.spoilerTitle — если
    // хочется заголовок спойлера отличный от label.
    const mkField = (label, key, opts={}) => {
      const f = U.el('div', { class:'field' },
        U.el('label', {}, label),
        opts.multiline ? U.el('textarea', { placeholder: opts.placeholder||'' }) : U.el('input', { type:'text', placeholder: opts.placeholder||'' })
      );
      const inp = f.querySelector('input,textarea');
      inp.value = draft[key] || '';
      inp.addEventListener('input', () => draft[key] = inp.value);
      inputs[key] = inp;
      if (opts.hint) f.appendChild(spoiler(opts.spoilerTitle || label, opts.hint));
      return f;
    };
    dlgBody.appendChild(mkField('Название', 'name', { placeholder:'Мой провайдер' }));
    dlgBody.appendChild(mkField('Base URL', 'baseUrl', { placeholder:'https://api.example.com/v1',
      hint:'Адрес API провайдера. Можно указать базу (например https://api.auriko.ai/v1) или полный путь до endpoint — клиент сам достроит /chat/completions без дублирования. Если провайдер требует нестандартный путь — включите ниже «Использовать URL строго как есть».' }));
    // Раздельный чекбокс: использовать URL строго как есть
    const rawWrap = U.el('div', { class:'field' });
    const rawRow = U.el('div', { style:{display:'flex', alignItems:'center', gap:'10px'} });
    const rawCb = U.el('input', { type:'checkbox', id:'prov-raw-url-cb' });
    rawCb.checked = !!draft.rawUrl;
    rawCb.addEventListener('change', () => draft.rawUrl = rawCb.checked);
    const rawLbl = U.el('label', { for:'prov-raw-url-cb', style:{margin:0, color:'var(--fg)', cursor:'pointer'} }, 'Использовать URL строго как есть');
    rawRow.appendChild(rawCb); rawRow.appendChild(rawLbl);
    rawWrap.appendChild(rawRow);
    rawWrap.appendChild(spoiler('Использовать URL строго как есть',
      'Не достраивать /v1/chat/completions или /chat/completions к Base URL — использовать адрес точно как введён. Включайте, только если провайдер требует свой собственный путь (например https://api.kilo.ai/api/gateway). В обычных случаях оставьте выключенным.'));
    dlgBody.appendChild(rawWrap);
    dlgBody.appendChild(mkApiKeyField(draft, inputs));
    dlgBody.appendChild(mkField('ID модели', 'model', { placeholder:'gpt-4o-mini / claude-3-5-sonnet / gemini-1.5-flash / llama-3.1-8b',
      hint:'Точный идентификатор модели у провайдера (например gpt-4o-mini, claude-3-5-sonnet). Можно вписать вручную или выбрать из списка ниже, нажав «Обновить список моделей».' }));
    // Кнопка «Обновить список моделей» + свёрнутый блок с поиском и списком
    dlgBody.appendChild(renderModelPicker(draft, inputs));
    dlgBody.appendChild(mkField('CORS-прокси (опционально)', 'corsProxy', { placeholder:'(оставь пустым — авто-подбор бесплатного)',
      hint:'Оставьте ПУСТЫМ в большинстве случаев — клиент сам подберёт бесплатный публичный прокси. Прокси нужен потому, что многие провайдеры не разрешают прямые запросы из браузера (ограничение CORS). Свой прокси указывайте, только если знаете, что делаете: поддерживаются шаблоны {url_raw} и {url_enc}, например https://your-worker.example.com/?{url_raw}. Внимание: публичные прокси видят ваш API-ключ — для важных ключей поднимите свой Cloudflare Worker.' }));
    dlgBody.appendChild(mkField('Extra headers (JSON)', 'extraHeaders', { multiline:true, placeholder:'{"X-My-Header":"value"}',
      hint:'Необязательно. Дополнительные HTTP-заголовки в формате JSON, которые будут добавлены к каждому запросу к провайдеру. Нужно редко — например, если провайдер требует особый заголовок.' }));

    UI.dialog({
      title: isNew ? 'Новый провайдер' : 'Редактирование',
      body: dlgBody,
      buttons: [
        { text:'Отмена', value:false },
        { text: isNew ? 'Создать' : 'Сохранить', primary:true, value:true }
      ]
    }).then(async ok => {
      if (!ok) return;
      if (!draft.name.trim()) { UI.toast('Название обязательно','err'); return; }
      if (!draft.baseUrl.trim()) { UI.toast('Base URL обязателен','err'); return; }
      const st = Store.get();
      if (isNew) {
        st.providers.push(draft);
        if (!st.activeProviderId) st.activeProviderId = draft.id;
      } else {
        const i = st.providers.findIndex(x => x.id === draft.id);
        if (i >= 0) st.providers[i] = draft;
      }
      Store.save();
      refresh(); AI.updateHeader();
    });
  }

  // Model picker block: button + collapsible search + clickable list.
  // Uses AI.listModels(draft) which respects baseUrl / apiKey / corsProxy / rawUrl.
  function renderModelPicker(draft, inputs) {
    const wrap = U.el('div', { class:'field', style:{gap:'6px'} });
    // Row: [Обновить список моделей] ... [Загружено: N]
    const row = U.el('div', { style:{display:'flex', alignItems:'center', gap:'10px', flexWrap:'wrap'} });
    const btn = U.el('button', { class:'btn small' }, 'Обновить список моделей');
    // Кнопка ручного перезапуска реальной проверки доступности по базовому тарифу.
    // Скрыта, пока список не загружен.
    const checkBtn = U.el('button', { class:'btn small', style:{display:'none'} }, '↻ Перепроверить доступность');
    const info = U.el('div', { style:{fontSize:'12px', color:'var(--fg-dim)', marginLeft:'auto'} }, '');
    row.appendChild(btn); row.appendChild(checkBtn); row.appendChild(info);
    wrap.appendChild(row);
    // Легенда: что означают цветные кружки доступности по базовому тарифу.
    const legend = U.el('div', { style:{display:'none', fontSize:'11px', color:'var(--fg-dim)', display:'flex', gap:'14px', alignItems:'center', flexWrap:'wrap'} });
    const legItem = (color, text) => U.el('span', { style:{display:'inline-flex', alignItems:'center', gap:'5px'} },
      U.el('span', { style:{width:'9px', height:'9px', borderRadius:'50%', background:color, display:'inline-block'} }), U.el('span', {}, text));
    legend.appendChild(legItem('#3fb950', 'доступна'));
    legend.appendChild(legItem('#f85149', 'недоступна (платн./лимит исчерпан)'));
    legend.appendChild(legItem('#6e7681', 'неизвестно'));
    legend.style.display = 'none';
    wrap.appendChild(legend);

    // Collapsible block
    const collapse = U.el('div', { class:'model-picker-collapse', style:{display:'none', border:'1px solid var(--border)', borderRadius:'6px', padding:'8px', background:'var(--bg-2)'} });
    const header = U.el('div', { style:{display:'flex', alignItems:'center', gap:'8px', marginBottom:'6px'} });
    const toggleBtn = U.el('button', { class:'btn small', style:{padding:'4px 8px'} }, '▸ Свернуть');
    const search = U.el('input', { type:'text', placeholder:'Поиск по названию/id…', style:{flex:'1', padding:'6px 8px', borderRadius:'6px', border:'1px solid var(--border)', background:'var(--bg)', color:'var(--fg)'} });
    header.appendChild(toggleBtn); header.appendChild(search);
    collapse.appendChild(header);
    const listBox = U.el('div', { class:'model-list', style:{maxHeight:'260px', overflowY:'auto', display:'flex', flexDirection:'column', gap:'2px'} });
    collapse.appendChild(listBox);
    wrap.appendChild(collapse);

    // Восстанавливаем ранее загруженный список моделей из провайдера, если он есть.
    // Это позволяет пользователю видеть список без повторной загрузки после загрузки.
    let loadedModels = Array.isArray(draft.models) ? draft.models.slice() : [];
    // Результаты РЕАЛЬНОЙ проверки доступности по базовому тарифу: id → probe.
    // Восстанавливаем из сохранённого провайдера, если они там есть.
    const probeMap = new Map();
    if (draft.probes && typeof draft.probes === 'object') {
      for (const k of Object.keys(draft.probes)) probeMap.set(k, draft.probes[k]);
    }
    let checking = false;       // идёт ли сейчас проверка доступности
    let stopChecking = false;   // флаг остановки (при закрытии/повторном запуске)
    let expanded = true;
    if (loadedModels.length) {
      collapse.style.display = '';
      legend.style.display = 'flex';
      // Отрисуем после того как renderList/costStats/checkBtn определятся ниже.
      setTimeout(() => {
        checkBtn.style.display = '';
        const s = costStats();
        info.textContent = statLine(probeMap.size ? 'Проверено' : 'Сохранено', loadedModels.length, s);
        renderList();
      }, 0);
    }

    // Итоговый tier для кружка: если есть результат реальной проверки — он главный,
    // иначе — статическая цена. Инкапсулировано в AI.availabilityTier.
    function tierFor(m) {
      const probe = probeMap.get(m.id);
      if (window.AI && AI.availabilityTier) return AI.availabilityTier(m, probe);
      return (window.AI && AI.modelCostTier) ? AI.modelCostTier(m) : 'unknown';
    }

    function statLine(prefix, total, s) {
      return prefix + ': ' + total + ' · 🟢' + s.free + ' 🔴' + s.paid + (s.unk ? ' ⚪' + s.unk : '');
    }

    // Кружок-индикатор ДОСТУПНОСТИ модели по базовому тарифу:
    //   зелёный  — доступна сейчас (реальный запрос проходит / бесплатная);
    //   красный  — недоступна по базовому тарифу (платная ИЛИ бесплатный лимит
    //              исчерпан — реальный запрос не проходит);
    //   серый    — статус неизвестен (проверить не удалось и цена неизвестна);
    //   «пульс»  — идёт проверка доступности.
    function statusDot(m, opts = {}) {
      if (opts.pending) {
        const dot = U.el('span', {
          class:'model-dot model-dot-checking',
          title:'Проверка доступности…',
          style:{
            flex:'0 0 auto', width:'10px', height:'10px', borderRadius:'50%',
            background:'#d29922', marginLeft:'8px', transition:'background .2s'
          }
        });
        return dot;
      }
      const tier = tierFor(m);
      const probe = probeMap.get(m.id);
      const color = tier === 'free' ? '#3fb950'
                  : tier === 'paid' ? '#f85149'
                  : '#6e7681';
      let title;
      if (probe && probe.available === true) title = 'Доступна по базовому тарифу (запрос проходит)';
      else if (probe && probe.available === false) title = 'Недоступна по базовому тарифу' + (probe.reason === 'free-tier-exhausted' ? ' (бесплатный лимит исчерпан)' : (probe.status ? ' (HTTP ' + probe.status + ')' : ''));
      else title = tier === 'free' ? 'Вероятно бесплатная (по данным прайсинга)'
                 : tier === 'paid' ? 'Вероятно платная (по данным прайсинга)'
                 : 'Статус неизвестен';
      return U.el('span', {
        class:'model-dot',
        title,
        style:{
          flex:'0 0 auto', width:'10px', height:'10px', borderRadius:'50%',
          background:color, marginLeft:'8px',
          boxShadow: tier==='free' ? '0 0 5px rgba(63,185,80,.7)' : (tier==='paid' ? '0 0 5px rgba(248,81,73,.6)' : 'none'),
          transition:'background .2s'
        }
      });
    }
    function applyExpanded() {
      listBox.style.display = expanded ? '' : 'none';
      search.style.display = expanded ? '' : 'none';
      toggleBtn.textContent = expanded ? '▾ Свернуть' : '▸ Развернуть';
    }
    toggleBtn.addEventListener('click', (e) => { e.preventDefault(); expanded = !expanded; applyExpanded(); });

    function renderList() {
      const q = search.value.trim().toLowerCase();
      const filtered = q ? loadedModels.filter(m =>
        (m.id||'').toLowerCase().includes(q) || (m.name||'').toLowerCase().includes(q)
      ) : loadedModels;
      listBox.innerHTML = '';
      if (!filtered.length) {
        listBox.appendChild(U.el('div', { style:{color:'var(--fg-dim)', fontSize:'12px', padding:'6px'} }, loadedModels.length ? 'Ничего не найдено' : 'Список пуст — нажми «Обновить список моделей»'));
        return;
      }
      for (const m of filtered) {
        const row = U.el('div', { class:'model-row', style:{padding:'6px 8px', borderRadius:'4px', cursor:'pointer', display:'flex', alignItems:'center', gap:'8px', background: draft.model === m.id ? 'var(--accent-soft, rgba(88,166,255,0.15))' : 'transparent'} });
        // Левая часть: id + (name) в столбик.
        const textCol = U.el('div', { style:{display:'flex', flexDirection:'column', gap:'2px', flex:'1', minWidth:'0'} });
        textCol.appendChild(U.el('div', { style:{fontFamily:'monospace', fontSize:'13px', color:'var(--fg)', wordBreak:'break-all'} }, m.id));
        if (m.name && m.name !== m.id) {
          textCol.appendChild(U.el('div', { style:{fontSize:'11px', color:'var(--fg-dim)'} }, m.name));
        }
        row.appendChild(textCol);
        // Правая часть: кружок ДОСТУПНОСТИ по базовому тарифу.
        // Пока идёт проверка и результата для этой модели ещё нет — показываем «пульс».
        const pending = checking && !probeMap.has(m.id);
        row.appendChild(statusDot(m, { pending }));
        row.addEventListener('mouseenter', () => { if (draft.model !== m.id) row.style.background = 'var(--bg-3, rgba(255,255,255,0.04))'; });
        row.addEventListener('mouseleave', () => { if (draft.model !== m.id) row.style.background = 'transparent'; });
        row.addEventListener('click', () => {
          draft.model = m.id;
          if (inputs.model) {
            inputs.model.value = m.id;
            inputs.model.dispatchEvent(new Event('input', { bubbles:true }));
          }
          UI.toast('Модель выбрана: ' + m.id, 'ok');
          renderList(); // re-highlight
        });
        listBox.appendChild(row);
      }
    }

    // Подсчёт статистики для строки info (доступных/недоступных/неизвестно).
    // Использует итоговый tier (реальная проверка приоритетнее цены).
    function costStats() {
      let free = 0, paid = 0, unk = 0;
      for (const m of loadedModels) {
        const t = tierFor(m);
        if (t === 'free') free++; else if (t === 'paid') paid++; else unk++;
      }
      return { free, paid, unk };
    }

    // Запуск реальной проверки доступности всех загруженных моделей по базовому
    // тарифу. Перекрашивает кружки на лету по мере готовности каждого результата.
    async function runAvailabilityCheck() {
      if (!(window.AI && AI.probeModels) || !loadedModels.length) return;
      if (checking) { stopChecking = true; return; }
      checking = true; stopChecking = false;
      probeMap.clear();
      renderList();
      let done = 0;
      const total = loadedModels.length;
      try {
        await AI.probeModels(draft, loadedModels, {
          concurrency: 3,
          timeoutMs: 12000,
          shouldStop: () => stopChecking,
          onResult: (m, probe) => {
            probeMap.set(m.id, probe);
            done++;
            const s = costStats();
            info.textContent = 'Проверка ' + done + '/' + total + ' · 🟢' + s.free + ' 🔴' + s.paid + (s.unk ? ' ⚪' + s.unk : '');
            renderList();
          }
        });
      } catch (e) { /* игнор — частичные результаты уже показаны */ }
      checking = false;
      // Сохраняем результаты проверки в провайдер, чтобы после reopen кружки
      // отражали реальную доступность без повторной проверки.
      draft.probes = {};
      for (const [k, v] of probeMap) draft.probes[k] = v;
      const s = costStats();
      info.textContent = statLine('Проверено', total, s);
      renderList();
    }

    search.addEventListener('input', renderList);

    btn.addEventListener('click', async (e) => {
      e.preventDefault();
      if (!(draft.baseUrl||'').trim()) { UI.toast('Заполни Base URL', 'err'); return; }
      const origText = btn.textContent;
      btn.textContent = 'Загрузка…';
      btn.disabled = true;
      info.textContent = '';
      try {
        const res = await AI.listModels(draft);
        loadedModels = res.models;
        // Новый список — старые результаты проверки больше не актуальны.
        probeMap.clear();
        // Сохраняем список в draft провайдера, чтобы после сохранения настроек
        // и повторного открытия — не приходилось обновлять его заново.
        draft.models = loadedModels.slice();
        draft.probes = {};
        info.textContent = 'Загружено: ' + loadedModels.length + ' · доступность не проверена';
        collapse.style.display = '';
        legend.style.display = 'flex';
        checkBtn.style.display = '';
        expanded = true;
        applyExpanded();
        renderList();
        // Авто-проверку доступности НЕ запускаем — только по нажатию кнопки
        // «Перепроверить доступность» (как в Android-версии).
        UI.toast('Загружено моделей: ' + loadedModels.length + '. Нажмите «Перепроверить доступность» для проверки.', 'ok');
      } catch (err) {
        info.textContent = 'Ошибка';
        UI.toast('Не удалось получить список: ' + (err.message||err).split('\n')[0], 'err');
      } finally {
        btn.textContent = origText;
        btn.disabled = false;
      }
    });

    // Отдельная кнопка «Перепроверить доступность» (реальные тестовые запросы).
    checkBtn.addEventListener('click', async (e) => {
      e.preventDefault();
      if (!loadedModels.length) { UI.toast('Сначала загрузи список моделей', 'err'); return; }
      if (checking) { stopChecking = true; UI.toast('Останавливаю проверку…'); return; }
      checkBtn.textContent = '⏳ Проверка… (нажми, чтобы остановить)';
      await runAvailabilityCheck();
      checkBtn.textContent = '↻ Перепроверить доступность';
      const s = costStats();
      UI.toast('Проверка завершена: доступно ' + s.free + ', недоступно ' + s.paid + (s.unk ? ', неизв. ' + s.unk : ''), 'ok');
    });

    return wrap;
  }

  // Поле API key с кнопкой-«глазом» справа для показа/скрытия значения.
  function mkApiKeyField(draft, inputs) {
    const f = U.el('div', { class:'field' });
    f.appendChild(U.el('label', {}, 'API key'));
    const row = U.el('div', { style:{position:'relative', display:'flex', alignItems:'center'} });
    const inp = U.el('input', { type:'password', placeholder:'sk-...', style:{paddingRight:'40px'} });
    inp.value = draft.apiKey || '';
    inp.addEventListener('input', () => draft.apiKey = inp.value);
    const eyeBtn = U.el('button', {
      type:'button',
      title:'Показать/скрыть',
      'aria-label':'Показать/скрыть API key',
      style:{
        position:'absolute', right:'6px', top:'50%', transform:'translateY(-50%)',
        background:'transparent', border:'none', cursor:'pointer',
        padding:'4px 6px', color:'var(--fg-dim)', fontSize:'16px', lineHeight:'1',
        display:'flex', alignItems:'center', justifyContent:'center'
      }
    });
    const eyeOpen = '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></svg>';
    const eyeClosed = '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M17.94 17.94A10.94 10.94 0 0 1 12 20c-7 0-11-8-11-8a20.6 20.6 0 0 1 5.06-5.94"/><path d="M9.9 4.24A10.94 10.94 0 0 1 12 4c7 0 11 8 11 8a20.7 20.7 0 0 1-3.16 4.19"/><path d="M14.12 14.12a3 3 0 1 1-4.24-4.24"/><line x1="1" y1="1" x2="23" y2="23"/></svg>';
    eyeBtn.innerHTML = eyeClosed;
    eyeBtn.addEventListener('click', (e) => {
      e.preventDefault();
      const show = inp.type === 'password';
      inp.type = show ? 'text' : 'password';
      eyeBtn.innerHTML = show ? eyeOpen : eyeClosed;
      eyeBtn.title = show ? 'Скрыть' : 'Показать';
    });
    row.appendChild(inp); row.appendChild(eyeBtn);
    f.appendChild(row);
    f.appendChild(spoiler('API key',
      'Ключ доступа к провайдеру. Хранится только локально в вашем браузере и никуда не отправляется, кроме самого провайдера. Нажмите «глаз» справа, чтобы показать/скрыть значение.'));
    inputs.apiKey = inp;
    return f;
  }

  // Спойлер-подсказка под полем: заголовок = название функции, при клике
  // разворачивается краткое описание. Используем нативный <details>/<summary>.
  function spoiler(title, description) {
    const d = U.el('details', { class:'field-spoiler' });
    d.appendChild(U.el('summary', {}, title));
    d.appendChild(U.el('div', { class:'spoiler-body' }, description));
    return d;
  }

  function renderOptionsSection() {
    const s = Store.get();
    const sect = U.el('div', { class:'settings-section' });
    sect.appendChild(U.el('h4', {}, 'ПОВЕДЕНИЕ ИИ'));

    // --- Дополнительный system prompt ---
    const sysField = U.el('div', { class:'field' },
      U.el('label', {}, 'Дополнительный system prompt'),
      U.el('textarea', { placeholder:'Например: пиши на русском, будь кратким' })
    );
    const sysInp = sysField.querySelector('textarea'); sysInp.value = s.aiSystem || '';
    sysInp.addEventListener('input', () => { Store.set({ aiSystem: sysInp.value }); });
    sysField.appendChild(spoiler('Дополнительный system prompt',
      'Ваша постоянная инструкция агенту, добавляется к каждому запросу. Например: «отвечай на русском», «будь кратким», «комментируй код». Оставьте пустым, если не нужно.'));
    sect.appendChild(sysField);

    // --- Звук уведомления ---
    const optSound = toggle('Звуковой сигнал при получении ответа агента', s.settings.notifySound !== false, v => { s.settings.notifySound = v; Store.save(); });
    optSound.appendChild(spoiler('Звуковой сигнал',
      'Проигрывать звук, когда агент закончил и прислал ответ. Удобно, если вы переключаетесь в другое приложение во время работы агента. Включено по умолчанию.'));

    // --- Громкость уведомления ---
    const optVol = U.el('div', { class:'field' },
      U.el('label', {}, 'Громкость уведомления'),
      U.el('input', { type:'range', min:'0', max:'1', step:'0.05' })
    );
    const volInp = optVol.querySelector('input');
    volInp.value = (s.settings.notifyVolume != null ? s.settings.notifyVolume : 0.9);
    volInp.addEventListener('input', () => { s.settings.notifyVolume = +volInp.value; Store.save(); });
    const testBtn = U.el('button', { class:'btn small', style:{marginLeft:'8px'} }, '▶ Проверить');
    testBtn.addEventListener('click', () => { try { AI && AI.playNotifySound && AI.playNotifySound(); } catch(_){} });
    optVol.appendChild(testBtn);
    optVol.appendChild(spoiler('Громкость уведомления',
      'Насколько громким будет звук уведомления (0 — тихо, вправо — громче). Кнопка «Проверить» проигрывает пробный звук.'));

    // --- Макс. число вызовов инструментов ---
    const optMax = U.el('div', { class:'field' },
      U.el('label', {}, 'Макс. число вызовов инструментов за задачу'),
      U.el('input', { type:'number', min:'1', max:'200' })
    );
    const maxInp = optMax.querySelector('input'); maxInp.value = s.settings.maxToolCalls || 30;
    maxInp.addEventListener('input', () => { s.settings.maxToolCalls = Math.max(1, +maxInp.value || 30); Store.save(); });
    optMax.appendChild(spoiler('Макс. число вызовов инструментов за задачу',
      'Предохранитель от бесконечных циклов: сколько раз агент может вызвать инструменты (чтение/запись файлов и т.п.) за одну задачу, прежде чем остановиться. Для больших задач (создать проект) ставьте больше — 100–300; для мелких правок хватит 30–50.'));

    // --- Таймаут ответа провайдера ---
    const optTimeout = U.el('div', { class:'field' },
      U.el('label', {}, 'Таймаут ответа провайдера, сек'),
      U.el('input', { type:'number', min:'15', max:'900', step:'5' })
    );
    const timeoutInp = optTimeout.querySelector('input');
    timeoutInp.value = Math.round((s.settings.providerTimeoutMs || 120000)/1000);
    timeoutInp.addEventListener('input', () => {
      s.settings.providerTimeoutMs = Math.max(15, Math.min(900, +timeoutInp.value || 120)) * 1000;
      Store.save();
    });
    optTimeout.appendChild(spoiler('Таймаут ответа провайдера, сек',
      'Сколько ждать ответа модели, прежде чем оборвать запрос с ошибкой (чтобы не «висеть» вечно, если провайдер молчит). По умолчанию 120. Для больших контекстов/медленных моделей ставьте 300–600.'));

    // --- Макс. токенов в ответе ---
    const optMaxTok = U.el('div', { class:'field' },
      U.el('label', {}, 'Макс. токенов в ответе (max_tokens)'),
      U.el('input', { type:'number', min:'256', max:'32768', step:'256' })
    );
    const maxTokInp = optMaxTok.querySelector('input');
    maxTokInp.value = s.settings.providerMaxTokens || 8192;
    maxTokInp.addEventListener('input', () => {
      s.settings.providerMaxTokens = Math.max(256, Math.min(32768, +maxTokInp.value || 8192));
      Store.save();
    });
    optMaxTok.appendChild(spoiler('Макс. токенов в ответе (max_tokens)',
      'Максимальный размер одного ответа модели. Слишком мало — модель обрежет код при записи большого файла и «забудет дописать». Слишком много — некоторые провайдеры отклонят запрос. Оптимально 4096–8192.'));

    // --- Пауза между запросами к ИИ (throttle) ---
    const optThrottleWrap = U.el('div', { class:'field' });
    const throttleHeader = U.el('div', { style:{display:'flex',alignItems:'center',gap:'10px'} });
    const throttleCb = U.el('input', { type:'checkbox' });
    throttleCb.checked = !!s.settings.toolThrottleEnabled;
    const throttleLabel = U.el('label', { style:{margin:0} }, 'Пауза между запросами к ИИ (защита от «rate limit / 429»)');
    throttleHeader.appendChild(throttleCb); throttleHeader.appendChild(throttleLabel);
    const throttleInput = U.el('input', { type:'number', min:'0', max:'120000', step:'100', style:{marginTop:'6px'} });
    throttleInput.value = s.settings.toolThrottleMs != null ? s.settings.toolThrottleMs : 1000;
    throttleInput.disabled = !throttleCb.checked;
    throttleCb.addEventListener('change', () => {
      s.settings.toolThrottleEnabled = throttleCb.checked;
      throttleInput.disabled = !throttleCb.checked;
      Store.save();
    });
    throttleInput.addEventListener('input', () => {
      s.settings.toolThrottleMs = Math.max(0, Math.min(120000, +throttleInput.value || 0));
      Store.save();
    });
    optThrottleWrap.appendChild(throttleHeader);
    optThrottleWrap.appendChild(throttleInput);
    optThrottleWrap.appendChild(spoiler('Пауза между запросами к ИИ (защита от «429»)',
      'Задержка (в мс) перед каждым обращением к модели. Ошибка «429 rate limit» приходит от провайдера, если запросов слишком часто, поэтому маленькие значения (200–500) не помогают — ставьте БОЛЬШЕ. Формула: 60000 ÷ (лимит запросов в минуту). Примеры: 1 запрос/мин → 60000; 5/мин → 12000; 20/мин → 3000; 60/мин → 1000. Не знаете лимит — начните с 6000–12000.'));

    // --- Автоповтор при 429 ---
    const optBackoff = U.el('div', { class:'field' });
    const bkHeader = U.el('div', { style:{display:'flex',alignItems:'center',gap:'10px'} });
    const bkCb = U.el('input', { type:'checkbox' });
    bkCb.checked = s.settings.autoBackoffOn429 !== false;
    const bkLabel = U.el('label', { style:{margin:0} }, 'Автоповтор при ответе провайдера «rate limit / 429»');
    bkHeader.appendChild(bkCb); bkHeader.appendChild(bkLabel);
    bkCb.addEventListener('change', () => { s.settings.autoBackoffOn429 = bkCb.checked; Store.save(); });
    optBackoff.appendChild(bkHeader);
    optBackoff.appendChild(spoiler('Автоповтор при «429»',
      'Если провайдер вернул ошибку лимита — клиент сам подождёт (1→2→4→8 сек, или сколько попросит сервер) и повторит запрос, не теряя прогресс задачи. Без этой опции задача обрывается на первой такой ошибке. Рекомендуется держать включённым.'));

    sect.appendChild(optSound); sect.appendChild(optVol);
    sect.appendChild(optMax);
    sect.appendChild(optTimeout);
    sect.appendChild(optMaxTok);
    sect.appendChild(optThrottleWrap);
    sect.appendChild(optBackoff);

    return sect;
  }
  function toggle(label, val, on) {
    const wrap = U.el('div', { class:'field', style:{flexDirection:'column', gap:'6px'} });
    const row = U.el('div', { style:{display:'flex', alignItems:'center', gap:'12px'} });
    const cb = U.el('input', { type:'checkbox' });
    cb.checked = !!val;
    cb.addEventListener('change', () => on(cb.checked));
    row.appendChild(cb);
    row.appendChild(U.el('label', { style:{margin:0, color:'var(--fg)'} }, label));
    wrap.appendChild(row);
    return wrap;
  }

  function renderGithubSection() {
    const s = Store.get();
    const sect = U.el('div', { class:'settings-section' });
    sect.appendChild(U.el('h4', {}, 'GITHUB'));
    if (s.githubUser) {
      sect.appendChild(U.el('div', {}, 'Авторизован как: ', U.el('b', {}, s.githubUser)));
      sect.appendChild(U.el('div', { style:{marginTop:'8px'} },
        btn('Выйти', () => { Store.set({githubToken:null,githubUser:null}); refresh(); App.updateGithubHint(); Git.renderPanel(); }, 'small danger')
      ));
    } else {
      sect.appendChild(U.el('div', { style:{fontSize:'12px',color:'var(--fg-dim)',marginBottom:'10px',lineHeight:'1.5'} },
        'Войдите в GitHub, чтобы клонировать репозитории, коммитить и пушить изменения. Есть три способа — все открывают системный браузер, где вы уже авторизованы.'));
      sect.appendChild(U.el('div', { style:{display:'flex',flexDirection:'column',gap:'6px'} },
        btn('🌐 Войти в GitHub…', () => App.githubLogin(), 'small primary')
      ));
      // Дополнительное поле: свой OAuth Client ID (опционально)
      const cid = U.el('div', { class:'field', style:{marginTop:'12px'} },
        U.el('label', {}, 'Свой GitHub OAuth App Client ID (опционально)'),
        U.el('input', { type:'text', placeholder:'Iv1.abcdef... (по умолчанию используется публичный)' }),
        U.el('div', { class:'hint' }, 'Если пусто — используется встроенный публичный Client ID для Device Flow. Для приватности вы можете создать свой OAuth App на github.com/settings/developers.')
      );
      const inp = cid.querySelector('input'); inp.value = s.githubClientId || '';
      inp.addEventListener('input', () => Store.set({ githubClientId: inp.value.trim() }));
      sect.appendChild(cid);
    }
    const proxy = U.el('div', { class:'field', style:{marginTop:'12px'} },
      U.el('label', {}, 'CORS proxy (для git clone/push)'),
      U.el('input', { type:'text', placeholder:'https://cors.isomorphic-git.org' })
    );
    const pinp = proxy.querySelector('input'); pinp.value = s.corsProxy;
    pinp.addEventListener('input', () => Store.set({ corsProxy: pinp.value.trim() }));
    sect.appendChild(proxy);
    return sect;
  }

  function btn(text, fn, cls='') {
    const b = U.el('button', { class:'btn '+cls }, text);
    b.addEventListener('click', fn);
    return b;
  }

  // Значки для кнопок «Редактировать»/«Удалить» в карточке провайдера.
  const PROV_ICON_EDIT  = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 20h9"/><path d="M16.5 3.5a2.121 2.121 0 0 1 3 3L7 19l-4 1 1-4L16.5 3.5z"/></svg>';
  const PROV_ICON_TRASH = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="3 6 5 6 21 6"/><path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"/><path d="M10 11v6M14 11v6"/><path d="M9 6V4a2 2 0 0 1 2-2h2a2 2 0 0 1 2 2v2"/></svg>';

  // Кнопка-значок (компактная, квадратная). cls: '' | 'primary' | 'danger'.
  function iconBtn(svg, title, fn, cls='') {
    const b = U.el('button', { class:'btn prov-icon-btn '+cls, title, 'aria-label':title });
    b.innerHTML = svg;
    b.addEventListener('click', fn);
    return b;
  }

  function maskKey(k) {
    if (!k) return '—';
    if (k.length <= 8) return '••••';
    return k.slice(0, 4) + '••••' + k.slice(-4);
  }

  function refresh() { open(); }

  return { open };
})();
window.Settings = Settings;
