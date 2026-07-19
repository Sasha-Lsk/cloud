/* ===== UI: drawer, sheet, dialog, toast ===== */
const UI = (() => {
  // Toast
  function toast(msg, kind='') {
    const holder = U.$('#toast-holder');
    const t = U.el('div', { class:'toast '+(kind||'') }, msg);
    holder.appendChild(t);
    requestAnimationFrame(() => t.classList.add('show'));
    setTimeout(() => { t.classList.remove('show'); setTimeout(() => t.remove(), 300); }, 3200);
  }

  // Drawer
  const drawer = U.$('#drawer'), scrim = U.$('#drawer-scrim');
  function openDrawer(){ drawer.classList.add('show'); scrim.classList.add('show'); }
  function closeDrawer(){ drawer.classList.remove('show'); scrim.classList.remove('show'); }
  scrim.addEventListener('click', closeDrawer);

  // Bottom sheet
  const sheet = U.$('#sheet'), sheetScrim = U.$('#sheet-scrim');
  const sheetTitle = U.$('#sheet-title'), sheetBody = U.$('#sheet-body'), sheetFooter = U.$('#sheet-footer');
  U.$('#sheet-close').addEventListener('click', closeSheet);
  sheetScrim.addEventListener('click', closeSheet);
  function openSheet(title, contentNodeOrHtml, footerNodes=null) {
    sheetTitle.textContent = title;
    sheetBody.innerHTML = '';
    if (typeof contentNodeOrHtml === 'string') sheetBody.innerHTML = contentNodeOrHtml;
    else if (contentNodeOrHtml) sheetBody.appendChild(contentNodeOrHtml);
    sheetFooter.innerHTML = '';
    if (footerNodes && footerNodes.length) { sheetFooter.style.display='flex'; footerNodes.forEach(n => sheetFooter.appendChild(n)); }
    else sheetFooter.style.display='none';
    sheet.classList.add('show'); sheetScrim.classList.add('show');
  }
  function closeSheet(){ sheet.classList.remove('show'); sheetScrim.classList.remove('show'); }

  // Dialog
  const dlgScrim = U.$('#dialog-scrim'), dlgTitle = U.$('#dialog-title'), dlgBody = U.$('#dialog-body'), dlgFooter = U.$('#dialog-footer');
  function closeDialog(){ dlgScrim.classList.remove('show'); }
  dlgScrim.addEventListener('click', e => { if (e.target === dlgScrim) closeDialog(); });
  function dialog({title, body, buttons}) {
    return new Promise(resolve => {
      dlgTitle.textContent = title || '';
      dlgBody.innerHTML = ''; if (typeof body === 'string') dlgBody.innerHTML = body; else if (body) dlgBody.appendChild(body);
      dlgFooter.innerHTML = '';
      (buttons || [{text:'OK', primary:true, value:true}]).forEach(b => {
        const btn = U.el('button', { class:'btn '+(b.primary?'primary':(b.danger?'danger':'ghost')) }, b.text);
        btn.addEventListener('click', () => { closeDialog(); resolve(b.value); });
        dlgFooter.appendChild(btn);
      });
      dlgScrim.classList.add('show');
    });
  }
  function confirm(msg, {title='Подтверждение', okText='Да', cancelText='Отмена', danger=false}={}) {
    return dialog({ title, body:`<div style="font-size:14px;line-height:1.5">${U.escapeHtml(msg)}</div>`, buttons:[
      {text:cancelText, value:false}, {text:okText, primary:!danger, danger, value:true}
    ]});
  }
  function prompt(label, {title='Ввод', value='', placeholder='', okText='OK', multiline=false}={}) {
    return new Promise(resolve => {
      const field = U.el('div', { class:'field' },
        U.el('label', {}, label || ''),
        multiline
          ? U.el('textarea', { placeholder })
          : U.el('input', { type:'text', placeholder })
      );
      const input = field.querySelector('input,textarea');
      input.value = value;
      dlgTitle.textContent = title;
      dlgBody.innerHTML = ''; dlgBody.appendChild(field);
      dlgFooter.innerHTML = '';
      const cancel = U.el('button', { class:'btn ghost' }, 'Отмена');
      cancel.addEventListener('click', ()=>{ closeDialog(); resolve(null); });
      const ok = U.el('button', { class:'btn primary' }, okText);
      ok.addEventListener('click', ()=>{ closeDialog(); resolve(input.value); });
      dlgFooter.appendChild(cancel); dlgFooter.appendChild(ok);
      dlgScrim.classList.add('show');
      setTimeout(()=>input.focus(), 50);
      if (!multiline) input.addEventListener('keydown', e => { if (e.key==='Enter') ok.click(); });
    });
  }

  // Progress overlay — статусное окно распаковки/архивации с процентами.
  // Возвращает контроллер { set(percent, label), done(msg), close() }.
  // percent: 0..100. При каждом вызове обновляется полоса и подпись «NN%».
  function progress(title, subtitle='') {
    let scrim = U.$('#progress-scrim');
    if (!scrim) {
      scrim = U.el('div', { id:'progress-scrim', class:'progress-scrim' });
      const box = U.el('div', { class:'progress-box' },
        U.el('div', { class:'progress-head' },
          U.el('div', { class:'progress-title', id:'progress-title' }, ''),
          // Кнопка сворачивания в плавающую кнопку у правого края.
          U.el('button', { class:'progress-min', id:'progress-min', title:'Свернуть', 'aria-label':'Свернуть' }, '—')
        ),
        U.el('div', { class:'progress-sub', id:'progress-sub' }, ''),
        U.el('div', { class:'progress-bar' }, U.el('div', { class:'progress-fill', id:'progress-fill' })),
        U.el('div', { class:'progress-pct', id:'progress-pct' }, '0%'),
        // Кнопка «Отмена» — прерывает создание/распаковку архива.
        U.el('button', { class:'btn danger progress-cancel', id:'progress-cancel' }, 'Отмена')
      );
      scrim.appendChild(box);
      document.body.appendChild(scrim);
    }
    // Плавающая кнопка (FAB) — у правого края по центру, показывает проценты.
    let fab = U.$('#progress-fab');
    if (!fab) {
      fab = U.el('div', { id:'progress-fab', class:'progress-fab', title:'Развернуть', role:'button', tabindex:'0' });
      // Кольцевой индикатор (SVG) + подпись процентов.
      fab.innerHTML =
        '<svg viewBox="0 0 40 40" class="progress-fab-ring" aria-hidden="true">' +
          '<circle class="pf-track" cx="20" cy="20" r="16"></circle>' +
          '<circle class="pf-arc" id="progress-fab-arc" cx="20" cy="20" r="16"></circle>' +
        '</svg>' +
        '<span class="progress-fab-pct" id="progress-fab-pct">0%</span>';
      document.body.appendChild(fab);
    }
    const titleEl = U.$('#progress-title'), subEl = U.$('#progress-sub');
    const fill = U.$('#progress-fill'), pct = U.$('#progress-pct');
    const minBtn = U.$('#progress-min');
    const cancelBtn = U.$('#progress-cancel');
    const fabPct = U.$('#progress-fab-pct'), fabArc = U.$('#progress-fab-arc');
    // Окружность кольца для расчёта dash (r=16).
    const CIRC = 2 * Math.PI * 16;
    if (fabArc) { fabArc.style.strokeDasharray = CIRC.toFixed(2); fabArc.style.strokeDashoffset = CIRC.toFixed(2); }

    titleEl.textContent = title || '';
    subEl.textContent = subtitle || '';
    subEl.style.display = subtitle ? '' : 'none';
    fill.style.width = '0%';
    pct.textContent = '0%';
    pct.style.color = '';
    if (fabPct) { fabPct.textContent = '0%'; fabPct.style.color = ''; }

    let curPct = 0;
    let cancelled = false;
    function expand() { scrim.classList.add('show'); fab.classList.remove('show'); }
    function minimize() { scrim.classList.remove('show'); fab.classList.add('show'); }
    // Навешиваем обработчики (переопределяем каждый раз — безопасно, т.к. элементы одни и те же).
    minBtn.onclick = minimize;
    fab.onclick = expand;
    fab.onkeydown = (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); expand(); } };
    // Сброс состояния кнопки «Отмена» при каждом новом показе окна.
    if (cancelBtn) {
      cancelBtn.disabled = false;
      cancelBtn.textContent = 'Отмена';
      cancelBtn.onclick = () => {
        cancelled = true;
        cancelBtn.disabled = true;
        cancelBtn.textContent = 'Отмена…';
        subEl.textContent = 'Отмена операции…';
        subEl.style.display = '';
      };
    }

    // Стартуем развёрнутыми.
    expand();

    // Даём браузеру перерисоваться между обновлениями прогресса.
    const yieldFrame = () => new Promise(r => requestAnimationFrame(() => r()));
    function applyPct(p) {
      curPct = p;
      fill.style.width = p + '%';
      pct.textContent = p + '%';
      if (fabPct) fabPct.textContent = p + '%';
      if (fabArc) fabArc.style.strokeDashoffset = (CIRC * (1 - p / 100)).toFixed(2);
    }
    function close() { scrim.classList.remove('show'); fab.classList.remove('show'); }
    async function set(percent, label) {
      let p = Math.max(0, Math.min(100, Math.round(percent)));
      applyPct(p);
      if (label != null) subEl.textContent = label, subEl.style.display = '';
      await yieldFrame();
    }
    async function done(msg) {
      applyPct(100);
      pct.style.color = 'var(--ok, #4caf50)';
      if (fabPct) fabPct.style.color = 'var(--ok, #4caf50)';
      if (msg != null) subEl.textContent = msg, subEl.style.display = '';
      await yieldFrame();
      setTimeout(close, 650);
    }
    function isCancelled() { return cancelled; }
    return { set, done, close, minimize, expand, isCancelled };
  }

  return { toast, openDrawer, closeDrawer, openSheet, closeSheet, dialog, confirm, prompt, progress };
})();
window.UI = UI;
