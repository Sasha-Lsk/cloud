/* ===== AI Tools: FS, Git, Archive =====
 * Each tool: { name, description, params, run(args) -> {ok, result?, error?} }
 * All operations expressed as JSON. Agent invokes tools by emitting JSON blocks.
 */
const Tools = (() => {
  const registry = new Map();
  function reg(t){ registry.set(t.name, t); }
  function list(){ return Array.from(registry.values()); }
  function get(name){ return registry.get(name); }

  function ok(v){ return { ok:true, result:v }; }
  // Понятные, самоисправляющие сообщения вместо сырых кодов ФС. Модель по такому
  // тексту чинит вызов на следующем шаге, а не долбит один и тот же битый.
  function humanizeFsError(e) {
    const code = e && e.code;
    const path = (e && e.path) || '';
    if (code === 'EISDIR') {
      return 'Путь «'+path+'» — это ПАПКА, в неё нельзя записать файл. Укажи путь к конкретному ФАЙЛУ внутри неё, например «'+ (path.replace(/\/+$/,'') || '') +'/File.ext».';
    }
    if (code === 'ENOENT') {
      return 'Путь «'+path+'» не найден. Проверь правильность пути; папки создаются автоматически при fs_write, но для fs_read/fs_replace файл должен уже существовать.';
    }
    if (code === 'ENOTDIR') {
      return 'В пути «'+path+'» один из промежуточных элементов — файл, а не папка. Исправь путь.';
    }
    return (e && e.message) || String(e);
  }
  function err(e){ return { ok:false, error: humanizeFsError(e) }; }

  // ---- Line diff (LCS) ----
  // Возвращает { added, removed, hunks } где hunks — массив групп изменений для
  // отрисовки цветного превью: [{ type:'ctx'|'add'|'del', text, oldNo, newNo }].
  // Используется в fs_write / fs_replace, чтобы клиент мог показать точные
  // счётчики +N / -N и подсветку удалённого (тёмно-красный) / добавленного
  // (тёмно-зелёный), как в «настоящих» IDE-клиентах.
  function lineDiff(oldText, newText) {
    const a = String(oldText == null ? '' : oldText).split('\n');
    const b = String(newText == null ? '' : newText).split('\n');
    const n = a.length, m = b.length;
    // LCS ограничим по размеру, чтобы не съесть память на огромных файлах:
    // если суммарно > 6000 строк — считаем по нетто (быстрый путь).
    if (n + m > 6000) {
      const added = Math.max(0, m - n);
      const removed = Math.max(0, n - m);
      return { added, removed, hunks: null, approx: true };
    }
    // DP-таблица LCS.
    const dp = new Array(n + 1);
    for (let i = 0; i <= n; i++) dp[i] = new Int32Array(m + 1);
    for (let i = n - 1; i >= 0; i--) {
      for (let j = m - 1; j >= 0; j--) {
        dp[i][j] = a[i] === b[j] ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1]);
      }
    }
    const ops = [];
    let i = 0, j = 0, added = 0, removed = 0;
    while (i < n && j < m) {
      if (a[i] === b[j]) { ops.push({ type:'ctx', text:a[i], oldNo:i+1, newNo:j+1 }); i++; j++; }
      else if (dp[i + 1][j] >= dp[i][j + 1]) { ops.push({ type:'del', text:a[i], oldNo:i+1 }); removed++; i++; }
      else { ops.push({ type:'add', text:b[j], newNo:j+1 }); added++; j++; }
    }
    while (i < n) { ops.push({ type:'del', text:a[i], oldNo:i+1 }); removed++; i++; }
    while (j < m) { ops.push({ type:'add', text:b[j], newNo:j+1 }); added++; j++; }
    return { added, removed, hunks: ops };
  }
  // Сжимает список операций diff для показа: оставляет 2 строки контекста вокруг
  // изменений, длинные неизменные участки схлопывает в маркер «…». Ограничивает
  // общий объём (макс. ~400 строк), чтобы не раздувать сообщение инструмента.
  function compactHunks(ops) {
    if (!ops || !ops.length) return null;
    const CTX = 2, MAX = 400;
    const keep = new Array(ops.length).fill(false);
    for (let k = 0; k < ops.length; k++) {
      if (ops[k].type !== 'ctx') {
        for (let d = -CTX; d <= CTX; d++) { const idx = k + d; if (idx >= 0 && idx < ops.length) keep[idx] = true; }
      }
    }
    const out = [];
    let gap = false;
    for (let k = 0; k < ops.length; k++) {
      if (keep[k]) {
        out.push({ t: ops[k].type[0], x: ops[k].text, o: ops[k].oldNo || null, n: ops[k].newNo || null });
        gap = false;
        if (out.length >= MAX) { out.push({ t:'x', x:'…[превью обрезано]' }); break; }
      } else if (!gap) {
        out.push({ t:'g', x:'…' });
        gap = true;
      }
    }
    return out;
  }

  // Порог для режима "digest" — если файл больше и агент не задал offset/limit,
  // отдаём выжимку (head + tail). Никакого счётчика повторных чтений: агент —
  // единственный, кто решает, читать ли ему тот же диапазон снова. Мы не блокируем.
  const MAX_INLINE_LINES = 4000;
  const MAX_INLINE_BYTES = 320 * 1024;
  // Backward-compat заглушка — read-cache удалён, но старые вызовы .delete/.clear
  // не должны падать.
  const readCache = { delete(){}, clear(){}, get(){return null;}, set(){} };

  // ---- Filesystem ----
  reg({
    name:'fs_read',
    description:'Прочитать содержимое файла как текст. Поддерживает пагинацию: offset (1-based номер строки) и limit (сколько строк). Без offset/limit возвращает файл целиком, но если файл больше 4000 строк или 320KB — вернёт «выжимку» (начало+конец) с подсказкой читать по offset/limit.',
    params:{
      path:'string (абсолютный путь, напр. /src/app.js)',
      offset:'number (optional, 1-based номер строки; по умолчанию 1)',
      limit:'number (optional, сколько строк вернуть; по умолчанию — весь файл или максимум 4000 строк / 320KB)'
    },
    async run({path, offset, limit}, ctx) {
      try {
        ctx && ctx.progress && ctx.progress({ detail: path });
        const data = await FS.readFile(path);
        const text = U.bytesToText(data);
        const allLines = text.split('\n');
        const totalLines = allLines.length;
        const size = data.byteLength;

        const off1 = Math.max(1, offset ? (+offset|0) : 1);
        const wantSpec = (offset != null || limit != null);

        // Big file без offset/limit: выжимка.
        if (!wantSpec && (totalLines > MAX_INLINE_LINES || size > MAX_INLINE_BYTES)) {
          const HEAD = Math.min(400, Math.floor(MAX_INLINE_LINES * 0.5));
          const TAIL = Math.min(200, Math.floor(MAX_INLINE_LINES * 0.2));
          const head = allLines.slice(0, HEAD).map((ln,i) => (i+1)+'\t'+ln).join('\n');
          const tail = allLines.slice(Math.max(0, totalLines - TAIL)).map((ln,i) => (totalLines - TAIL + i + 1)+'\t'+ln).join('\n');
          const preview = head + '\n\n[... пропущено ' + (totalLines - HEAD - TAIL) + ' строк — файл слишком большой; читай нужные части через fs_read с offset и limit ...]\n\n' + tail;
          ctx && ctx.progress && ctx.progress({ detail: path + ' · выжимка (файл ' + totalLines + ' строк)' });
          return ok({
            path, size, lines: totalLines, mode:'digest',
            head_lines: [1, HEAD], tail_lines: [Math.max(1,totalLines-TAIL+1), totalLines],
            content: preview,
            hint:'Файл слишком большой ('+totalLines+' строк). Показано только начало и конец. Для чтения любой части: fs_read с offset и limit. Пример: {"path":"'+path+'","offset":1,"limit":500}, затем {"offset":501,"limit":500} и т.д.'
          });
        }

        // Обычное чтение или слайс.
        let startIdx = off1 - 1;
        if (startIdx < 0) startIdx = 0;
        if (startIdx > totalLines) startIdx = totalLines;
        let endIdx = totalLines;
        if (limit && +limit > 0) endIdx = Math.min(totalLines, startIdx + (+limit|0));
        // Жёсткий cap — на случай, если модель попросила огромный limit.
        if (endIdx - startIdx > MAX_INLINE_LINES) endIdx = startIdx + MAX_INLINE_LINES;

        const slice = allLines.slice(startIdx, endIdx).join('\n');
        const outBytes = new TextEncoder().encode(slice).byteLength;
        let finalText = slice, finalEnd = endIdx;
        if (outBytes > MAX_INLINE_BYTES) {
          const ratio = MAX_INLINE_BYTES / outBytes;
          const keepLines = Math.max(50, Math.floor((endIdx - startIdx) * ratio));
          finalEnd = startIdx + keepLines;
          finalText = allLines.slice(startIdx, finalEnd).join('\n');
        }

        ctx && ctx.progress && ctx.progress({ detail: path + ' · строки ' + (startIdx+1) + '–' + finalEnd + ' из ' + totalLines });
        return ok({
          path, size, lines: totalLines,
          offset: startIdx + 1, limit: finalEnd - startIdx,
          returnedLines: [startIdx + 1, finalEnd],
          content: finalText,
          truncated: finalEnd < totalLines,
          hint: (finalEnd < totalLines)
            ? 'Показаны строки ' + (startIdx+1) + '–' + finalEnd + ' из ' + totalLines + '. Для следующего фрагмента: fs_read с offset=' + (finalEnd + 1) + '.'
            : 'Прочитан файл до конца (' + totalLines + ' строк).'
        });
      } catch(e){ return err(e); }
    }
  });

  reg({
    name:'fs_write', description:'Записать текст в файл (создать/перезаписать). Автоматически создаёт папки.',
    params:{ path:'string', content:'string' },
    async run({path, content}, ctx) {
      try {
        const newText = String(content||'');
        // Prev content for diff-metrics (best-effort)
        let prevText = '';
        try { if (await FS.exists(path)) prevText = U.bytesToText(await FS.readFile(path)); } catch(_){}
        const prevLines = prevText ? prevText.split('\n').length : 0;
        const newLines = newText ? newText.split('\n').length : 0;
        const delta = newLines - prevLines;
        ctx && ctx.progress && ctx.progress({
          detail: path + ' · ' + newLines + ' строк' + (prevText ? ' (Δ' + (delta>=0?'+':'') + delta + ')' : ' (новый файл)')
        });
        await FS.writeFile(path, newText);
        // Invalidate read cache — файл изменился.
        readCache.delete(path);
        await Editor.onExternalWrite(path);
        await Explorer.render();
        const diff = lineDiff(prevText, newText);
        return ok({ path, written: newText.length, lines: newLines, prevLines, deltaLines: delta, created: !prevText,
          added: diff.added, removed: diff.removed, diffHunks: compactHunks(diff.hunks) });
      } catch(e){ return err(e); }
    }
  });
  // fs_replace — точечная правка большого файла БЕЗ повторной отправки всего
  // содержимого. Ключевая проблема: fs_write требует, чтобы модель прислала
  // ВЕСЬ новый текст файла. Для файла на 5000+ строк это 30–60k токенов исходящего
  // трафика на КАЖДУЮ мелкую правку — модель либо режется по max_tokens и не
  // возвращает валидный tool-блок, либо запрос висит.
  //
  // fs_replace принимает ЛИБО:
  //   • search + replace (+ count, +regex) — заменяет подстроку или regex-совпадение;
  //   • startLine + endLine + content — заменяет строки [startLine..endLine] (1-based, включительно).
  //
  // Пути обязательны, файл должен существовать. Возвращает число замен и Δ-строки.
  reg({
    name:'fs_replace',
    description:'Точечная правка файла БЕЗ отправки всего содержимого. Два режима: (1) search/replace по подстроке или regex; (2) замена диапазона строк startLine..endLine. Используй для правок в больших файлах вместо fs_write (fs_write вынуждает переслать весь файл целиком — это тратит токены и приводит к обрывам).',
    params:{
      path:'string (абсолютный путь к существующему файлу)',
      // Режим 1: подстрока/regex
      search:'string (что искать; опционально)',
      replace:'string (на что заменить; используется вместе с search)',
      regex:'boolean (optional; если true — search интерпретируется как regex, флаги по умолчанию: g, при отсутствии — global)',
      flags:'string (optional; флаги regex, например "gi"; действует только при regex=true)',
      count:'number (optional; максимум замен; по умолчанию — все совпадения)',
      // Режим 2: диапазон строк
      startLine:'number (optional; 1-based; строка начала замены — включительно)',
      endLine:'number (optional; 1-based; строка конца замены — включительно)',
      content:'string (optional; новый текст для диапазона строк; используется вместе со startLine/endLine)'
    },
    async run({path, search, replace, regex, flags, count, startLine, endLine, content}, ctx) {
      try {
        if (!path) return err('Требуется path');
        if (!(await FS.exists(path))) return err('Файл не существует: ' + path);
        const data = await FS.readFile(path);
        if (U.looksBinary(data)) return err('fs_replace работает только с текстовыми файлами');
        const original = U.bytesToText(data);
        const prevLines = original ? original.split('\n').length : 0;
        let result = original;
        let replacements = 0;
        // Режим 2: диапазон строк
        const lineMode = (startLine != null || endLine != null || (content != null && !search));
        if (lineMode) {
          if (startLine == null || endLine == null) {
            return err('Для режима диапазона строк нужны startLine И endLine');
          }
          const s = Math.max(1, +startLine|0);
          const e = Math.max(s, +endLine|0);
          const lines = original.split('\n');
          if (s > lines.length) return err('startLine ('+s+') за пределами файла ('+lines.length+' строк)');
          const eClamped = Math.min(e, lines.length);
          const newBlock = String(content == null ? '' : content);
          const newLinesArr = newBlock.split('\n');
          const merged = lines.slice(0, s-1).concat(newLinesArr, lines.slice(eClamped));
          result = merged.join('\n');
          replacements = 1;
          ctx && ctx.progress && ctx.progress({ detail: path + ' · строки ' + s + '–' + eClamped + ' → ' + newLinesArr.length + ' строк' });
        }
        // Режим 1: search/replace
        else if (search != null) {
          const repl = String(replace == null ? '' : replace);
          if (regex) {
            let f = String(flags || 'g');
            if (!f.includes('g') && (count == null || +count > 1)) f += 'g';
            let rx;
            try { rx = new RegExp(String(search), f); }
            catch(e){ return err('Некорректный regex: ' + (e.message||e)); }
            if (count != null && +count > 0) {
              // Ограниченное число замен: заменяем вручную, чтобы уважать count.
              const limit = +count|0;
              const parts = [];
              let last = 0, m;
              const rx2 = new RegExp(rx.source, rx.flags.includes('g') ? rx.flags : rx.flags + 'g');
              while ((m = rx2.exec(result)) !== null && replacements < limit) {
                parts.push(result.slice(last, m.index));
                parts.push(repl.replace(/\$(\d+)/g, (_,n) => m[+n] || ''));
                last = m.index + m[0].length;
                if (m[0].length === 0) rx2.lastIndex++; // защита от zero-length
                replacements++;
              }
              parts.push(result.slice(last));
              result = parts.join('');
            } else {
              const before = result;
              result = result.replace(rx, (...args) => { replacements++; return repl.replace(/\$(\d+)/g, (_,n) => args[+n] || ''); });
              if (result === before && replacements === 0) {
                return err('Регулярное выражение не нашло совпадений: ' + String(search));
              }
            }
          } else {
            const needle = String(search);
            if (!needle) return err('search не может быть пустой строкой');
            const limit = (count != null && +count > 0) ? (+count|0) : Infinity;
            const parts = [];
            let idx = 0;
            while (replacements < limit) {
              const found = result.indexOf(needle, idx);
              if (found < 0) break;
              parts.push(result.slice(idx, found));
              parts.push(repl);
              idx = found + needle.length;
              replacements++;
            }
            if (replacements === 0) {
              return err('Подстрока не найдена: ' + JSON.stringify(needle.length > 80 ? needle.slice(0,80)+'…' : needle));
            }
            parts.push(result.slice(idx));
            result = parts.join('');
          }
          ctx && ctx.progress && ctx.progress({ detail: path + ' · ' + replacements + ' замен' });
        }
        else {
          return err('Нужен либо search (+replace), либо startLine+endLine+content');
        }
        const newLines = result ? result.split('\n').length : 0;
        await FS.writeFile(path, result);
        readCache.delete(path);
        await Editor.onExternalWrite(path);
        await Explorer.render();
        const diff = lineDiff(original, result);
        return ok({
          path,
          replacements,
          prevLines,
          lines: newLines,
          deltaLines: newLines - prevLines,
          added: diff.added,
          removed: diff.removed,
          diffHunks: compactHunks(diff.hunks),
          prevSize: data.byteLength,
          size: new TextEncoder().encode(result).byteLength
        });
      } catch(e){ return err(e); }
    }
  });

  reg({
    name:'fs_append', description:'Дописать текст в конец файла',
    params:{ path:'string', content:'string' },
    async run({path, content}, ctx) {
      try {
        const addText = String(content||'');
        const addedLines = addText ? addText.split('\n').length : 0;
        let cur = '';
        if (await FS.exists(path)) cur = U.bytesToText(await FS.readFile(path));
        ctx && ctx.progress && ctx.progress({ detail: path + ' · +' + addedLines + ' строк' });
        await FS.writeFile(path, cur + addText);
        readCache.delete(path);
        await Editor.onExternalWrite(path);
        await Explorer.render();
        return ok({ path, appendedChars: addText.length, appendedLines: addedLines });
      } catch(e){ return err(e); }
    }
  });
  reg({
    name:'fs_list', description:'Список файлов и папок в директории',
    params:{ path:'string (папка, по умолчанию /)' },
    async run({path='/'}, ctx) {
      try {
        ctx && ctx.progress && ctx.progress({ detail: 'Сканирую ' + path + '…' });
        const rows = await FS.listTree(path);
        const items = rows.filter(r => r.path !== path).map(r => ({ path:r.path, type:r.type, size:r.size }));
        const files = items.filter(i => i.type === 'file').length;
        const dirs = items.length - files;
        ctx && ctx.progress && ctx.progress({ detail: path + ' · ' + files + ' файлов, ' + dirs + ' папок' });
        return ok({ path, count:items.length, files, dirs, items });
      } catch(e){ return err(e); }
    }
  });
  reg({
    name:'fs_mkdir', description:'Создать папку (рекурсивно)',
    params:{ path:'string' },
    async run({path}) {
      try { await FS.ensureDirRecursive(path); await Explorer.render(); return ok({path}); } catch(e){ return err(e); }
    }
  });
  reg({
    name:'fs_delete', description:'Удалить файл или папку (рекурсивно для папок)',
    params:{ path:'string' },
    async run({path}) {
      try {
        const st = await FS.stat(path);
        if (st.type === 'dir') await FS.rmrf(path); else await FS.unlink(path);
        readCache.delete(path);
        Editor.onPathDeleted(path); await Explorer.render();
        return ok({path});
      } catch(e){ return err(e); }
    }
  });
  reg({
    name:'fs_rename', description:'Переименовать/переместить файл или папку',
    params:{ from:'string', to:'string' },
    async run({from, to}) {
      try {
        await FS.rename(from, to);
        readCache.delete(from);
        Editor.onPathRenamed(from, to);
        await Explorer.render();
        return ok({from,to});
      } catch(e){ return err(e); }
    }
  });
  reg({
    name:'fs_search', description:'Поиск подстроки/regex по файлам проекта',
    params:{ query:'string', regex:'boolean (optional)', path:'string (optional, root)' },
    async run({query, regex=false, path='/'}, ctx) {
      try {
        // Авто-regex: слабые модели часто пишут альтернацию через «|»
        // ("license|check|licensed") БЕЗ regex:true, ожидая regex-семантику.
        // При подстроковом поиске это даёт 0 совпадений → модель повторяет поиск
        // и «зависает». Если query похож на regex-альтернацию — трактуем как regex.
        if (!regex && /^[^\s|]+(\|[^\s|]+)+$/.test(String(query||''))) regex = true;
        ctx && ctx.progress && ctx.progress({ detail: '"' + query + '" — индексирую…' });
        const rows = (await FS.listTree(path)).filter(r=>r.type==='file');
        const rx = regex ? new RegExp(query, 'i') : null;
        const hits = [];
        const total = rows.length;
        let processed = 0, lastTick = 0;
        for (const r of rows) {
          processed++;
          const now = performance.now();
          if (ctx && ctx.progress && (now - lastTick > 80 || processed % 25 === 0 || processed === total)) {
            lastTick = now;
            const short = r.path.length > 60 ? '…' + r.path.slice(-58) : r.path;
            ctx.progress({ detail: '"' + query + '" · ' + processed + '/' + total + ' · ' + hits.length + ' найдено · ' + short });
            await new Promise(res => setTimeout(res, 0));
          }
          if (r.size > 500*1024) continue;
          try {
            const data = await FS.readFile(r.path);
            if (U.looksBinary(data)) continue;
            const txt = U.bytesToText(data);
            const lines = txt.split('\n');
            lines.forEach((ln, i) => {
              const m = regex ? rx.test(ln) : ln.toLowerCase().includes(String(query).toLowerCase());
              if (m) hits.push({ path:r.path, line:i+1, text:ln.slice(0,240) });
            });
            if (hits.length > 200) {
              ctx && ctx.progress && ctx.progress({ detail: '"' + query + '" · >200 найдено (обрезано)' });
              return ok({query, truncated:true, count:hits.length, hits});
            }
          } catch(e){}
        }
        ctx && ctx.progress && ctx.progress({ detail: '"' + query + '" · ' + hits.length + ' найдено в ' + total + ' файлах' });
        return ok({ query, count:hits.length, scanned: total, hits });
      } catch(e){ return err(e); }
    }
  });

  // ---- Images / media ----
  // Attachments queue is provided by AI module: images the agent has "seen" via fs_read_image
  // are appended as vision content on the NEXT LLM call.

  // Render any image (including ICO, SVG) to a canvas via <img>, then re-encode as PNG.
  // Vision models don't accept image/x-icon; we normalize to PNG/JPEG.
  async function renderImageToPng(blob, mime, path, maxSide) {
    const url = URL.createObjectURL(blob);
    try {
      const img = new Image();
      img.decoding = 'async';
      // For SVG, ensure browser treats it correctly
      img.src = url;
      await new Promise((resolve, reject) => {
        img.onload = resolve;
        img.onerror = () => reject(new Error('Не удалось декодировать изображение: '+mime));
      });
      let w = img.naturalWidth || img.width || 0;
      let h = img.naturalHeight || img.height || 0;
      if (!w || !h) throw new Error('Нулевые размеры изображения');
      // ICO files often have small native size (16/32/48px). Upscale small icons to at least 128px for vision clarity.
      let minTarget = 128;
      if (mime === 'image/svg+xml') minTarget = 512; // vector — render at higher default
      const scaleUp = Math.max(1, minTarget / Math.max(w, h));
      const scaleDown = Math.min(1, maxSide / Math.max(w, h));
      const scale = scaleUp > 1 ? scaleUp : scaleDown;
      const outW = Math.max(1, Math.round(w * scale));
      const outH = Math.max(1, Math.round(h * scale));
      const c = document.createElement('canvas');
      c.width = outW; c.height = outH;
      const ctx = c.getContext('2d');
      // Preserve transparency by leaving canvas transparent, encode as PNG
      ctx.imageSmoothingEnabled = true;
      ctx.imageSmoothingQuality = 'high';
      ctx.drawImage(img, 0, 0, outW, outH);
      const outBlob = await new Promise(res => c.toBlob(res, 'image/png'));
      const buf = new Uint8Array(await outBlob.arrayBuffer());
      return { buf, mime: 'image/png', width: outW, height: outH };
    } finally {
      URL.revokeObjectURL(url);
    }
  }

  async function readImageAsDataUrl(path, maxSide=1600) {
    const data = await FS.readFile(path);
    const srcMime = U.mimeFromPath(path);
    const blob = new Blob([data], { type: srcMime });
    // Formats not accepted by most vision models: ICO, BMP (partial), TIFF, SVG.
    // We rasterize them to PNG. Additionally, downscale big rasters to save tokens.
    const NEEDS_CONVERT = new Set(['image/x-icon','image/vnd.microsoft.icon','image/bmp','image/svg+xml','image/tiff']);
    const needsConvert = NEEDS_CONVERT.has(srcMime) || /\.(ico|bmp|svg|tif|tiff)$/i.test(path);
    if (needsConvert) {
      try {
        const out = await renderImageToPng(blob, srcMime, path, maxSide);
        return {
          dataUrl: 'data:image/png;base64,' + U.bytesToB64(out.buf),
          width: out.width, height: out.height, mime: 'image/png',
          size: out.buf.byteLength, originalSize: data.byteLength, converted: true, originalMime: srcMime
        };
      } catch(e){
        // fall through to raw base64 attempt below
      }
    }
    // For standard raster formats, try to downscale if too big
    if (['image/png','image/jpeg','image/webp','image/gif','image/apng','image/avif'].includes(srcMime)) {
      try {
        const bitmap = await createImageBitmap(blob).catch(()=>null);
        if (bitmap) {
          const scale = Math.min(1, maxSide / Math.max(bitmap.width, bitmap.height));
          const w = Math.max(1, Math.round(bitmap.width * scale));
          const h = Math.max(1, Math.round(bitmap.height * scale));
          if (scale < 1) {
            const c = document.createElement('canvas');
            c.width = w; c.height = h;
            c.getContext('2d').drawImage(bitmap, 0, 0, w, h);
            const outBlob = await new Promise(res => c.toBlob(res, 'image/jpeg', 0.85));
            const buf = new Uint8Array(await outBlob.arrayBuffer());
            return { dataUrl: 'data:image/jpeg;base64,' + U.bytesToB64(buf), width:w, height:h, mime:'image/jpeg', size: buf.byteLength, originalSize: data.byteLength, converted: true, originalMime: srcMime };
          }
          return { dataUrl: 'data:'+srcMime+';base64,' + U.bytesToB64(data), width: bitmap.width, height: bitmap.height, mime: srcMime, size: data.byteLength, originalSize: data.byteLength };
        }
      } catch(e){}
    }
    // Fallback — send raw
    return { dataUrl: 'data:'+srcMime+';base64,' + U.bytesToB64(data), mime: srcMime, size: data.byteLength, originalSize: data.byteLength };
  }

  reg({
    name:'fs_read_image', description:'Прочитать изображение (png/jpg/gif/webp/svg/bmp) для мультимодального просмотра. Прикрепляет к следующему LLM-запросу (vision) И возвращает data_url — движок чата автоматически превратит его в короткий media_ref, который ты можешь показать пользователю через ![](chatimg:...).',
    params:{ path:'string (путь к файлу изображения в ФС)', maxSide:'number (optional, max side px, default 1600)' },
    async run({path, maxSide=1600}) {
      try {
        const mk = U.mediaKind(path);
        if (mk !== 'image' && mk !== 'svg') return err('Не изображение: '+path);
        const info = await readImageAsDataUrl(path, +maxSide||1600);
        // Картинку сохраняет движок чата (attachToolImageToMsg) прямо в tool-сообщение
        // по полю data_url ниже — она персистит в истории и уходит модели на каждом шаге.
        // Возвращаем data_url — processToolResultForModel заменит его на media_ref (chatimg:xxx)
        return ok({
          path,
          name: U.basename(path),
          mime: info.mime,
          width: info.width,
          height: info.height,
          size: info.size,
          originalSize: info.originalSize,
          data_url: info.dataUrl,
          note: 'Изображение прикреплено к следующему vision-запросу И доступно для показа в чате.'
        });
      } catch(e){ return err(e); }
    }
  });

  reg({
    name:'fs_read_media',
    description:'Прочитать медиа-файл из ФС (видео mp4/webm, аудио mp3/wav/ogg, PDF) и вернуть его как data_url — движок чата автоматически превратит его в media_ref (chatimg:xxx), который ты можешь встроить в ответ как ![](chatimg:xxx), и он превратится в <video>/<audio>/<iframe> для просмотра пользователем.',
    params:{ path:'string (путь к медиа-файлу)' },
    async run({path}) {
      try {
        const mk = U.mediaKind(path);
        if (mk === 'image' || mk === 'svg') return err('Для изображений используй fs_read_image');
        if (!mk) return err('Не медиа-файл (поддерживаются: video, audio, pdf): '+path);
        const data = await FS.readFile(path);
        const mime = U.mimeFromPath(path);
        const dataUrl = 'data:' + mime + ';base64,' + U.bytesToB64(data);
        return ok({
          path,
          name: U.basename(path),
          mime,
          kind: mk,
          size: data.byteLength,
          data_url: dataUrl
        });
      } catch(e){ return err(e); }
    }
  });

  reg({
    name:'fs_read_binary', description:'Прочитать любой файл как base64 (для бинарных данных). Возвращает base64-строку. НЕ прикрепляет к vision — для описания/распознавания картинок НЕ ГОДИТСЯ, используй fs_read_image (или archive_read_entry для картинок из zip). Для видео/аудио/PDF — fs_read_media.',
    params:{ path:'string' },
    async run({path}) {
      try {
        // Guard: если это картинка — молча переадресуем на fs_read_image, чтобы
        // модель не читала бесполезный base64 и всё-таки увидела пиксели в vision.
        const mk = U.mediaKind(path);
        if (mk === 'image' || mk === 'svg') {
          const info = await readImageAsDataUrl(path, 1600);
          // Картинку сохраняет движок чата (attachToolImageToMsg) из data_url ниже.
          return ok({
            path,
            name: U.basename(path),
            mime: info.mime,
            width: info.width,
            height: info.height,
            size: info.size,
            data_url: info.dataUrl,
            note: 'fs_read_binary был вызван на картинке — автоматически переключено на vision-режим (изображение прикреплено к следующему vision-запросу И доступно как media_ref для показа в чате).'
          });
        }
        const data = await FS.readFile(path);
        return ok({ path, size: data.byteLength, mime: U.mimeFromPath(path), base64: U.bytesToB64(data) });
      } catch(e){ return err(e); }
    }
  });

  // ---- Archives ----
  reg({
    name:'archive_extract', description:'Распаковать zip-архив из ФС в указанную папку',
    params:{ archive:'string (путь к .zip)', to:'string (папка назначения)' },
    async run({archive, to}, ctx) {
      try {
        ctx && ctx.progress && ctx.progress({ detail: 'Читаю ' + archive + '…' });
        const data = await FS.readFile(archive);
        ctx && ctx.progress && ctx.progress({ detail: 'Разбираю ' + data.byteLength + ' байт…' });
        const files = fflate.unzipSync(data);
        const keys = Object.keys(files);
        let n = 0, i = 0, lastTick = 0;
        for (const rel of keys) {
          i++;
          const now = performance.now();
          if (ctx && ctx.progress && (now - lastTick > 80 || i % 20 === 0 || i === keys.length)) {
            lastTick = now;
            ctx.progress({ detail: 'Извлекаю ' + i + '/' + keys.length + ' · ' + rel.slice(-50) });
            await new Promise(res => setTimeout(res, 0));
          }
          const dst = U.joinPath(to, rel);
          if (rel.endsWith('/')) await FS.ensureDirRecursive(dst);
          else { await FS.writeFile(dst, files[rel]); n++; }
        }
        await Explorer.render();
        return ok({ extracted:n, to });
      } catch(e){ return err(e); }
    }
  });
  reg({
    name:'archive_list',
    description:'Просмотреть содержимое zip-архива БЕЗ распаковки. Возвращает список записей с именем, размером и признаком папки. Используй ПЕРЕД распаковкой, чтобы узнать что внутри и запросить у пользователя нужный файл.',
    params:{ archive:'string (путь к .zip)' },
    async run({archive}, ctx) {
      try {
        ctx && ctx.progress && ctx.progress({ detail: 'Читаю ' + archive + '…' });
        const data = await FS.readFile(archive);
        ctx && ctx.progress && ctx.progress({ detail: 'Разбираю ' + data.byteLength + ' байт…' });
        const files = fflate.unzipSync(data);
        const entries = [];
        let totalBytes = 0;
        for (const rel in files) {
          const bytes = files[rel];
          const isDir = rel.endsWith('/');
          const size = isDir ? 0 : (bytes.length || bytes.byteLength || 0);
          if (!isDir) totalBytes += size;
          entries.push({ path: rel, is_dir: isDir, size });
        }
        // Сортируем: сначала папки, потом файлы, внутри — по алфавиту
        entries.sort((a,b) => {
          if (a.is_dir !== b.is_dir) return a.is_dir ? -1 : 1;
          return a.path.localeCompare(b.path);
        });
        return ok({
          archive,
          archive_size: data.byteLength,
          total_uncompressed: totalBytes,
          file_count: entries.filter(e => !e.is_dir).length,
          dir_count: entries.filter(e => e.is_dir).length,
          entries,
        });
      } catch(e){ return err(e); }
    }
  });
  reg({
    name:'archive_read_entry',
    description:'Прочитать один файл ВНУТРИ zip-архива без полной распаковки. Для текстовых — возвращает text; для картинок/видео/аудио/PDF — data_url (движок превратит его в media_ref для показа в чате); для остальных бинарных — base64. Для КАРТИНОК (image/*) — АВТОМАТИЧЕСКИ прикрепляет изображение к твоему следующему vision-запросу (мультимодальный анализ) — на следующем шаге ты увидишь пиксели глазами и сможешь корректно распознать что изображено. Используй когда нужно посмотреть/описать конкретный файл из архива не распаковывая весь.',
    params:{
      archive:'string (путь к .zip)',
      entry:'string (путь внутри архива, например "src/main.js")',
      as:'string, необязательно ("text" | "binary" | "media", по умолчанию auto — text для текстовых, data_url для картинок/видео/аудио/PDF, base64 для прочих бинарных)',
      max_bytes:'number, необязательно (лимит для текстового вывода, по умолчанию 200000)'
    },
    async run({archive, entry, as, max_bytes}, ctx) {
      try {
        const limit = Number.isFinite(max_bytes) ? Math.max(0, max_bytes) : 200000;
        ctx && ctx.progress && ctx.progress({ detail: 'Читаю ' + archive + '…' });
        const data = await FS.readFile(archive);
        ctx && ctx.progress && ctx.progress({ detail: 'Извлекаю запись ' + entry + '…' });
        const files = fflate.unzipSync(data);
        const bytes = files[entry];
        if (bytes == null) {
          const available = Object.keys(files).filter(k => !k.endsWith('/')).slice(0, 30);
          return err('В архиве нет записи "' + entry + '". Доступны: ' + available.join(', ') + (available.length === 30 ? ' …' : ''));
        }
        if (entry.endsWith('/')) return err('"' + entry + '" — это папка, не файл');
        const size = bytes.length || bytes.byteLength || 0;
        const mk = U.mediaKind(entry);
        const isMedia = !!mk;
        let mode = as;
        if (mode !== 'text' && mode !== 'binary' && mode !== 'media') {
          // auto
          if (isMedia) mode = 'media';
          else mode = U.looksBinary(bytes.slice(0, Math.min(4096, size))) ? 'binary' : 'text';
        }
        if (mode === 'text') {
          let text = U.bytesToText(bytes);
          const truncated = text.length > limit;
          if (truncated) text = text.slice(0, limit);
          return ok({ archive, entry, size, encoding:'text', text, truncated });
        }
        if (mode === 'media') {
          const srcMime = U.mimeFromPath(entry);
          // Для КАРТИНОК из архива — нормализуем как в fs_read_image:
          // рестерируем ICO/SVG/BMP/TIFF в PNG, а большие raster'ы даунскейлим
          // в JPEG (иначе vision-модели их не примут или потратят кучу токенов).
          // Это гарантирует, что archive_read_entry на картинке даст такой же
          // качественный vision-инпут, как fs_read_image.
          if (mk === 'image' || mk === 'svg') {
            try {
              const blob = new Blob([bytes], { type: srcMime });
              const NEEDS_CONVERT = new Set(['image/x-icon','image/vnd.microsoft.icon','image/bmp','image/svg+xml','image/tiff']);
              const needsConvert = NEEDS_CONVERT.has(srcMime) || /\.(ico|bmp|svg|tif|tiff)$/i.test(entry);
              if (needsConvert) {
                const out = await renderImageToPng(blob, srcMime, entry, 1600);
                const dataUrl = 'data:image/png;base64,' + U.bytesToB64(out.buf);
                return ok({
                  archive, entry, size, encoding:'data_url',
                  name: U.basename(entry),
                  mime: 'image/png', kind: 'image',
                  width: out.width, height: out.height,
                  originalMime: srcMime, converted: true,
                  data_url: dataUrl
                });
              }
              // Downscale large rasters (JPEG, PNG, WebP, ...)
              const bitmap = await createImageBitmap(blob).catch(()=>null);
              if (bitmap) {
                const maxSide = 1600;
                const scale = Math.min(1, maxSide / Math.max(bitmap.width, bitmap.height));
                if (scale < 1) {
                  const w = Math.max(1, Math.round(bitmap.width * scale));
                  const h = Math.max(1, Math.round(bitmap.height * scale));
                  const c = document.createElement('canvas');
                  c.width = w; c.height = h;
                  c.getContext('2d').drawImage(bitmap, 0, 0, w, h);
                  const outBlob = await new Promise(res => c.toBlob(res, 'image/jpeg', 0.85));
                  const buf = new Uint8Array(await outBlob.arrayBuffer());
                  const dataUrl = 'data:image/jpeg;base64,' + U.bytesToB64(buf);
                  return ok({
                    archive, entry, size, encoding:'data_url',
                    name: U.basename(entry),
                    mime: 'image/jpeg', kind: 'image',
                    width: w, height: h,
                    originalMime: srcMime, converted: true,
                    data_url: dataUrl
                  });
                }
                // Native size is fine — return raw
                const dataUrl = 'data:' + srcMime + ';base64,' + U.bytesToB64(bytes);
                return ok({
                  archive, entry, size, encoding:'data_url',
                  name: U.basename(entry),
                  mime: srcMime, kind: 'image',
                  width: bitmap.width, height: bitmap.height,
                  data_url: dataUrl
                });
              }
            } catch(e) {
              // Fallback to raw below
            }
          }
          const mime = srcMime;
          const dataUrl = 'data:' + mime + ';base64,' + U.bytesToB64(bytes);
          return ok({
            archive, entry, size, encoding:'data_url',
            name: U.basename(entry),
            mime, kind: mk,
            data_url: dataUrl
          });
        }
        // binary → base64
        return ok({ archive, entry, size, encoding:'base64', base64: U.bytesToB64(bytes) });
      } catch(e){ return err(e); }
    }
  });
  reg({
    name:'archive_create', description:'Создать zip-архив из папки',
    params:{ source:'string (папка)', archive:'string (путь к .zip)' },
    async run({source, archive}, ctx) {
      try {
        ctx && ctx.progress && ctx.progress({ detail: 'Собираю файлы из ' + source + '…' });
        const rows = (await FS.listTree(source)).filter(r => r.type==='file');
        const bag = {};
        let i = 0, lastTick = 0;
        for (const r of rows) {
          i++;
          const now = performance.now();
          if (ctx && ctx.progress && (now - lastTick > 80 || i % 20 === 0 || i === rows.length)) {
            lastTick = now;
            ctx.progress({ detail: 'Читаю ' + i + '/' + rows.length + ' · ' + r.path.slice(-50) });
            await new Promise(res => setTimeout(res, 0));
          }
          const rel = r.path.slice(source.length).replace(/^\//,'');
          bag[rel] = await FS.readFile(r.path);
        }
        ctx && ctx.progress && ctx.progress({ detail: 'Сжимаю ' + rows.length + ' файлов…' });
        const zipped = fflate.zipSync(bag);
        await FS.writeFile(archive, zipped);
        await Explorer.render();
        return ok({ archive, files: rows.length, size: zipped.byteLength });
      } catch(e){ return err(e); }
    }
  });

  // ---- Git ----
  reg({
    name:'git_status', description:'Показать git status в репозитории',
    params:{ dir:'string' },
    async run({dir}) { try { const st = await Git.status(dir); return ok({dir, changes:st}); } catch(e){ return err(e); } }
  });
  reg({
    name:'git_commit', description:'Стейджит все изменения и коммитит',
    params:{ dir:'string', message:'string' },
    async run({dir, message}) {
      try { await Editor.saveAll(); await Git.addAll(dir); const sha = await Git.commit(dir, message); return ok({sha}); } catch(e){ return err(e); }
    }
  });
  reg({
    name:'git_pull', description:'Pull изменений',
    params:{ dir:'string' },
    async run({dir}) { try { await Git.pull(dir); return ok({dir}); } catch(e){ return err(e); } }
  });
  reg({
    name:'git_push', description:'Push изменений',
    params:{ dir:'string' },
    async run({dir}) { try { await Git.push(dir); return ok({dir}); } catch(e){ return err(e); } }
  });
  reg({
    name:'git_clone', description:'Клонировать репозиторий по URL',
    params:{ url:'string', dir:'string' },
    async run({url, dir}) { try { await Git.clone(url, dir); await Explorer.render(); await Git.renderPanel(); return ok({dir,url}); } catch(e){ return err(e); } }
  });
  reg({
    name:'git_init', description:'Инициализировать git репозиторий',
    params:{ dir:'string' },
    async run({dir}) { try { await Git.init(dir); await Git.renderPanel(); return ok({dir}); } catch(e){ return err(e); } }
  });

  // ---- GitHub API (uses stored token) ----
  reg({
    name:'github_api', description:'Выполнить произвольный GitHub REST API запрос (уже с токеном). Возвращает JSON.',
    params:{ method:'string (GET/POST/PATCH/PUT/DELETE)', path:'string (например /user/repos)', body:'object (optional)' },
    async run({method='GET', path, body}) {
      try {
        const s = Store.get();
        if (!s.githubToken) return err('Не авторизован в GitHub');
        const r = await fetch('https://api.github.com'+path, {
          method,
          headers: { 'Authorization':'token '+s.githubToken, 'Accept':'application/vnd.github+json', 'Content-Type':'application/json' },
          body: body ? JSON.stringify(body) : undefined
        });
        const text = await r.text();
        let data; try { data = JSON.parse(text); } catch(e){ data = text; }
        return ok({ status:r.status, data });
      } catch(e){ return err(e); }
    }
  });

  // ---- HTTP fetch (generic) ----
  // Пробуем несколько CORS-прокси подряд (тот же список, что использует
  // клиент провайдера). Без этого браузер режет большинство запросов из-за
  // отсутствия CORS-заголовков на целевом сервере — из-за чего агент часто
  // отвечает пользователю "HTTP-запросы через инструменты не катят".
  const CORS_PROXIES = [
    '', // direct first
    'https://corsproxy.io/?{url_enc}',
    'https://api.allorigins.win/raw?url={url_enc}',
    'https://proxy.cors.sh/{url_raw}',
    'https://test.cors.workers.dev/?{url_raw}',
    'https://api.codetabs.com/v1/proxy/?quest={url_raw}',
  ];
  function buildProxied(tmpl, targetUrl) {
    if (!tmpl) return targetUrl;
    if (tmpl.includes('{url_raw}')) return tmpl.replace('{url_raw}', targetUrl);
    if (tmpl.includes('{url_enc}')) return tmpl.replace('{url_enc}', encodeURIComponent(targetUrl));
    return tmpl + encodeURIComponent(targetUrl);
  }
  async function fetchViaProxies(targetUrl, init, opts={}) {
    const errors = [];
    // GET через no-cors нельзя (пустой ответ), поэтому явно используем прокси.
    for (const tmpl of CORS_PROXIES) {
      const finalUrl = buildProxied(tmpl, targetUrl);
      try {
        const r = await fetch(finalUrl, init);
        // Прокси могут вернуть 5xx, если целевой сервер отверг — попробуем следующий
        if (!r.ok && r.status >= 500 && tmpl) { errors.push(tmpl + ' → HTTP ' + r.status); continue; }
        return { response: r, proxyUsed: tmpl || '(прямой запрос)' };
      } catch(e) {
        errors.push((tmpl || 'direct') + ' → ' + ((e && e.message) || e));
      }
    }
    const e = new Error('Все попытки (прямой + CORS-прокси) провалились:\n' + errors.slice(0, 6).join('\n'));
    e._probes = errors;
    throw e;
  }
  // Определяет, является ли Content-Type бинарным медиа. Для таких ответов
  // r.text() возвращает мусор — сохраняем как base64 data URL, чтобы модель
  // могла вставить `![](data_url)` и картинка отобразилась прямо в чате.
  function isBinaryMediaCT(ct) {
    if (!ct) return false;
    ct = ct.toLowerCase();
    return ct.startsWith('image/') || ct.startsWith('audio/') || ct.startsWith('video/') ||
           ct === 'application/pdf' || ct === 'application/octet-stream' ||
           ct.startsWith('application/zip') || ct.startsWith('application/x-') && !/json|xml|www-form/.test(ct);
  }
  // Blob → base64 (без "data:...;base64," префикса)
  function blobToBase64(blob) {
    return new Promise((resolve, reject) => {
      const fr = new FileReader();
      fr.onload = () => {
        const s = String(fr.result || '');
        const i = s.indexOf(',');
        resolve(i >= 0 ? s.slice(i+1) : s);
      };
      fr.onerror = () => reject(fr.error);
      fr.readAsDataURL(blob);
    });
  }
  reg({
    name:'http_fetch',
    description:'Скачать содержимое любого URL. Автоматически определяет тип ответа: текст (HTML/JSON/etc) → возвращает {status, text, contentType, proxyUsed}; картинки/бинарные (image/*, audio/*, video/*, application/pdf) → возвращает {status, data_url:"data:<mime>;base64,...", contentType, size, proxyUsed}. Для КАРТИНОК (image/*) движок АВТОМАТИЧЕСКИ прикрепит изображение к твоему следующему vision-запросу (мультимодальный анализ) — на следующем шаге увидишь его пиксели и сможешь корректно описать. Пробует прямой запрос, потом цепочку публичных CORS-прокси.',
    params:{ url:'string', method:'string (optional, default GET)', headers:'object (optional)', body:'string (optional)', force_text:'boolean (optional, если true — не превращать бинарник в data_url, вернуть как есть текстом)' },
    async run({url, method='GET', headers={}, body, force_text=false}) {
      if (!url || typeof url !== 'string') return err('url обязателен');
      try {
        const { response: r, proxyUsed } = await fetchViaProxies(url, { method, headers, body });
        const ct = r.headers.get('content-type') || '';
        const finalUrl = r.url || url;
        // По расширению URL или по Content-Type
        const looksImage = /\.(png|jpe?g|gif|webp|svg|bmp|ico|avif)(\?|$)/i.test(finalUrl);
        const binary = !force_text && (isBinaryMediaCT(ct) || (looksImage && (!ct || ct.startsWith('text/'))));
        if (binary) {
          const blob = await r.blob();
          // Ограничение размера — не пихаем в контекст модели файлы > 4MB.
          // Модели тоже имеют лимит; лучше сразу вернуть ошибку.
          const MAX = 4 * 1024 * 1024;
          if (blob.size > MAX) {
            return err('Файл слишком большой ('+blob.size+' байт) для встраивания в чат. Лимит 4MB. Попробуй скачать другой размер.');
          }
          const b64 = await blobToBase64(blob);
          let mime = (ct && ct.split(';')[0].trim()) || '';
          if (!mime && looksImage) {
            const ext = (finalUrl.match(/\.(png|jpe?g|gif|webp|svg|bmp|ico|avif)(\?|$)/i) || [])[1];
            const map = { png:'image/png', jpg:'image/jpeg', jpeg:'image/jpeg', gif:'image/gif', webp:'image/webp', svg:'image/svg+xml', bmp:'image/bmp', ico:'image/x-icon', avif:'image/avif' };
            mime = map[String(ext).toLowerCase()] || 'application/octet-stream';
          }
          const dataUrl = 'data:' + (mime || 'application/octet-stream') + ';base64,' + b64;
          return ok({
            status: r.status, url: finalUrl, proxyUsed,
            contentType: ct, size: blob.size, kind: mime.startsWith('image/') ? 'image' : 'binary',
            data_url: dataUrl,
            hint: mime.startsWith('image/') ? 'Чтобы показать картинку пользователю — вставь в свой ответ строку ![alt](data_url). Не пиши base64 текстом.' : 'Бинарный файл. Для отображения в чате не подходит.'
          });
        }
        const text = await r.text();
        return ok({ status: r.status, url: finalUrl, proxyUsed, contentType: ct, text: text.slice(0, 200000) });
      } catch(e) { return err(e); }
    }
  });

  // ---- Web search (CORS-friendly aggregation) ----
  // Реальные полноценные поисковики (Google/Bing/DDG HTML) блокируют браузерные
  // запросы через CORS. Мы агрегируем несколько публичных JSON API, каждое из
  // которых отдаёт правильные CORS-заголовки без ключа:
  //   • DuckDuckGo Instant Answer — сводка + связанные темы.
  //   • Wikipedia OpenSearch (en / ru) — энциклопедические статьи.
  //   • Stack Exchange — вопросы Stack Overflow и др. сайтов.
  //   • GitHub — репозитории.
  //   • npm registry — JavaScript-пакеты.
  //   • crates.io — Rust-пакеты.
  // Все запросы идут параллельно, и результаты приходят из тех источников,
  // которые ответили. По умолчанию пробуем всё, но можно ограничить `sources`.
  // Отдельный список CORS-прокси для HTML-скрапинга (не бинарники). Проверено:
  // proxy.cors.sh пропускает YouTube/Bing/Yahoo/Startpage; test.cors.workers.dev
  // пропускает Bing и меньшие сайты. allorigins глотает YouTube (страница-заглушка).
  // Явно НЕ Yandex/Google — они блокируют IP большинства прокси капчами.
  const HTML_PROXIES = [
    (u) => 'https://proxy.cors.sh/' + u,
    (u) => 'https://test.cors.workers.dev/?' + u,
    (u) => 'https://api.allorigins.win/raw?url=' + encodeURIComponent(u),
    (u) => 'https://api.codetabs.com/v1/proxy/?quest=' + u,
  ];
  async function fetchHtmlViaProxy(targetUrl, { timeoutMs=9000, minBytes=3000 } = {}) {
    const errs = [];
    for (const mk of HTML_PROXIES) {
      const u = mk(targetUrl);
      try {
        const ctl = new AbortController();
        const tid = setTimeout(() => ctl.abort(), timeoutMs);
        // Просим локаль запроса, чтобы YouTube не давал consent-редирект (в EU он часто перекидывает
        // на consent.youtube.com для en-US). Это заголовки прокси, обычно они прокидываются.
        const r = await fetch(u, { signal: ctl.signal, headers:{ 'Accept-Language':'*' } });
        clearTimeout(tid);
        if (!r.ok) { errs.push(u.split('?')[0] + ' → HTTP ' + r.status); continue; }
        const text = await r.text();
        // Некоторые прокси возвращают HTML-редирект YouTube "включите куки" (~1.6KB) или
        // consent-страницу (~6-8KB, RU: "Прежде чем перейти", EN: "Before you continue").
        // Настоящая страница поиска YouTube > 100KB и содержит ytInitialData.
        const head = text.slice(0, 2000);
        const isStub = text.length < minBytes ||
          (text.length < 40000 && /consent\.(youtube|google)\.com|Before you continue|Прежде чем перейти|включите cookies|Enable cookies|Пожалуйста, включите|Please enable JavaScript|robot check|unusual traffic|необычн\S* трафик/i.test(head));
        if (isStub) {
          errs.push(u.split('?')[0] + ' → страница-заглушка (' + text.length + ' байт)');
          continue;
        }
        return { text, proxyUsed: u.split('?')[0] };
      } catch(e) {
        errs.push(u.split('?')[0] + ' → ' + ((e && e.message) || e));
      }
    }
    const e = new Error('Все HTML-прокси провалились для ' + targetUrl + ':\n' + errs.slice(0, 4).join('\n'));
    e._probes = errs;
    throw e;
  }

  const ALL_SEARCH_SOURCES = ['ddg','ddg_html','wiki','stackoverflow','github','npm','crates','hackernews','wiktionary','openlibrary','nominatim','bing','yahoo','startpage','brave','reddit','mojeek'];
  reg({
    name:'web_search',
    description:'Поиск в интернете. Возвращает [{title, url, snippet, source}]. Агрегирует ~17 источников: API — DuckDuckGo Instant Answer, Wikipedia (en+ru+авто-детект по алфавиту), Stack Overflow, GitHub, npm, crates.io, Hacker News (Algolia), Wiktionary, OpenLibrary, OpenStreetMap Nominatim, Reddit (JSON); HTML-scraping через CORS-прокси — Bing, Yahoo, Startpage (проксирует Google-выдачу), DuckDuckGo HTML, Brave Search, Mojeek. Работает без API-ключей. SafeSearch/family filter ВЫКЛЮЧЕН у всех источников по умолчанию (safe_search:"on" включит фильтр). Для видео — youtube_search (даёт реальные результаты, но ТОЛЬКО с YouTube). Если пользователь просит "видео про Х", "фильм", "клип" и т.п. без явного указания сайта — вызывай ПАРАЛЛЕЛЬНО и web_search, и youtube_search, чтобы дать разнообразные результаты (не только с YouTube). Для картинок — image_search. Для загрузки страницы после — http_fetch.',
    params:{ query:'string', limit:'number (optional, default 10, max 25)', sources:'array of strings (optional, subset of ["ddg","ddg_html","wiki","stackoverflow","github","npm","crates","hackernews","wiktionary","openlibrary","nominatim","bing","yahoo","startpage","brave","reddit","mojeek"])', safe_search:'string (optional, "off" по умолчанию — не фильтровать взрослый контент; "on" — включить SafeSearch у поисковиков)' },
    async run({query, limit=10, sources, safe_search='off'}) {
      const safeOff = String(safe_search||'off').toLowerCase() !== 'on';
      if (!query || typeof query !== 'string') return err('query обязателен');
      const n = Math.max(1, Math.min(25, +limit || 10));
      const perSource = Math.max(3, Math.ceil(n / 2));
      const enabled = new Set(Array.isArray(sources) && sources.length ? sources : ALL_SEARCH_SOURCES);
      const results = [];
      const errors = [];

      async function ddg() {
        try {
          const u = 'https://api.duckduckgo.com/?q=' + encodeURIComponent(query) + '&format=json&no_html=1&skip_disambig=1';
          const r = await fetch(u);
          if (!r.ok) return errors.push('ddg → HTTP '+r.status);
          const j = await r.json();
          const out = [];
          if (j.AbstractText && j.AbstractURL) out.push({ title: j.Heading || query, url: j.AbstractURL, snippet: j.AbstractText.slice(0, 400), source: j.AbstractSource || 'DuckDuckGo' });
          if (j.Answer && j.AnswerType) out.push({ title:'['+j.AnswerType+'] '+query, url:'https://duckduckgo.com/?q='+encodeURIComponent(query), snippet: String(j.Answer).slice(0, 400), source:'DuckDuckGo instant' });
          for (const t of (j.RelatedTopics || [])) {
            if (t.Topics && Array.isArray(t.Topics)) {
              for (const s of t.Topics) { if (s.FirstURL && s.Text) out.push({ title: s.Text.split(' - ')[0], url: s.FirstURL, snippet: s.Text.slice(0, 400), source:'DuckDuckGo' }); }
            } else if (t.FirstURL && t.Text) out.push({ title: t.Text.split(' - ')[0], url: t.FirstURL, snippet: t.Text.slice(0, 400), source:'DuckDuckGo' });
          }
          for (const t of (j.Results || [])) if (t.FirstURL) out.push({ title: t.Text || t.FirstURL, url: t.FirstURL, snippet: (t.Text||'').slice(0, 400), source:'DuckDuckGo' });
          results.push(...out.slice(0, perSource));
        } catch(e) { errors.push('ddg → ' + (e.message||e)); }
      }

      async function wiki(lang) {
        try {
          const u = 'https://' + lang + '.wikipedia.org/w/api.php?action=opensearch&search=' + encodeURIComponent(query) + '&limit=' + perSource + '&format=json&origin=*';
          const r = await fetch(u);
          if (!r.ok) return errors.push('wiki-'+lang+' → HTTP '+r.status);
          const j = await r.json();
          const titles = j[1] || [], descs = j[2] || [], urls = j[3] || [];
          for (let i = 0; i < titles.length; i++) results.push({ title: titles[i], url: urls[i], snippet: (descs[i]||'').slice(0, 400), source:'Wikipedia ('+lang+')' });
        } catch(e) { errors.push('wiki-'+lang+' → ' + (e.message||e)); }
      }

      async function stackoverflow() {
        try {
          const u = 'https://api.stackexchange.com/2.3/search/advanced?order=desc&sort=relevance&q=' + encodeURIComponent(query) + '&site=stackoverflow&pagesize=' + perSource + '&filter=default';
          const r = await fetch(u);
          if (!r.ok) return errors.push('stackoverflow → HTTP '+r.status);
          const j = await r.json();
          for (const it of (j.items || [])) {
            const tags = it.tags && it.tags.length ? ' [' + it.tags.join(', ') + ']' : '';
            results.push({ title: it.title, url: it.link, snippet: 'Score: '+it.score+', Answers: '+it.answer_count+(it.is_answered?' ✓':'')+tags, source:'Stack Overflow' });
          }
        } catch(e) { errors.push('stackoverflow → ' + (e.message||e)); }
      }

      async function github() {
        try {
          const u = 'https://api.github.com/search/repositories?q=' + encodeURIComponent(query) + '&per_page=' + perSource;
          const r = await fetch(u, { headers:{ 'Accept':'application/vnd.github+json' } });
          if (!r.ok) return errors.push('github → HTTP '+r.status);
          const j = await r.json();
          for (const it of (j.items || [])) {
            results.push({ title: it.full_name + (it.language?' ['+it.language+']':''), url: it.html_url, snippet: (it.description||'')+' ⭐'+it.stargazers_count, source:'GitHub' });
          }
        } catch(e) { errors.push('github → ' + (e.message||e)); }
      }

      async function npm() {
        try {
          const u = 'https://registry.npmjs.org/-/v1/search?text=' + encodeURIComponent(query) + '&size=' + perSource;
          const r = await fetch(u);
          if (!r.ok) return errors.push('npm → HTTP '+r.status);
          const j = await r.json();
          for (const it of (j.objects || [])) {
            const p = it.package || {};
            results.push({ title: p.name + '@' + p.version, url: (p.links && (p.links.npm || p.links.homepage)) || 'https://www.npmjs.com/package/'+p.name, snippet: p.description || '', source:'npm' });
          }
        } catch(e) { errors.push('npm → ' + (e.message||e)); }
      }

      async function crates() {
        try {
          const u = 'https://crates.io/api/v1/crates?q=' + encodeURIComponent(query) + '&per_page=' + perSource;
          const r = await fetch(u);
          if (!r.ok) return errors.push('crates → HTTP '+r.status);
          const j = await r.json();
          for (const it of (j.crates || [])) {
            results.push({ title: it.name + '@' + (it.newest_version || it.max_version), url: 'https://crates.io/crates/'+it.name, snippet: it.description || '', source:'crates.io' });
          }
        } catch(e) { errors.push('crates → ' + (e.message||e)); }
      }

      async function hackernews() {
        try {
          const u = 'https://hn.algolia.com/api/v1/search?query=' + encodeURIComponent(query) + '&hitsPerPage=' + perSource;
          const r = await fetch(u);
          if (!r.ok) return errors.push('hackernews → HTTP '+r.status);
          const j = await r.json();
          for (const it of (j.hits || [])) {
            const title = it.title || it.story_title || it.comment_text?.slice(0,80) || '(no title)';
            const url = it.url || it.story_url || ('https://news.ycombinator.com/item?id=' + it.objectID);
            results.push({ title, url, snippet: 'HN score: '+(it.points||0)+', '+(it.num_comments||0)+' comments · '+(it.author||''), source:'Hacker News' });
          }
        } catch(e) { errors.push('hackernews → ' + (e.message||e)); }
      }

      async function wiktionary() {
        try {
          const u = 'https://en.wiktionary.org/w/api.php?action=opensearch&search=' + encodeURIComponent(query) + '&limit=' + perSource + '&format=json&origin=*';
          const r = await fetch(u);
          if (!r.ok) return errors.push('wiktionary → HTTP '+r.status);
          const j = await r.json();
          const titles = j[1] || [], urls = j[3] || [];
          for (let i = 0; i < titles.length; i++) results.push({ title: titles[i], url: urls[i], snippet: 'Словарная статья', source:'Wiktionary' });
        } catch(e) { errors.push('wiktionary → ' + (e.message||e)); }
      }

      async function openlibrary() {
        try {
          const u = 'https://openlibrary.org/search.json?q=' + encodeURIComponent(query) + '&limit=' + perSource;
          const r = await fetch(u);
          if (!r.ok) return errors.push('openlibrary → HTTP '+r.status);
          const j = await r.json();
          for (const it of (j.docs || []).slice(0, perSource)) {
            const authors = (it.author_name || []).slice(0,2).join(', ');
            const year = it.first_publish_year ? ' ('+it.first_publish_year+')' : '';
            results.push({ title: (it.title||'?') + year + (authors ? ' — '+authors : ''), url: 'https://openlibrary.org' + it.key, snippet: 'Editions: '+(it.edition_count||1), source:'OpenLibrary' });
          }
        } catch(e) { errors.push('openlibrary → ' + (e.message||e)); }
      }

      async function nominatim() {
        try {
          const u = 'https://nominatim.openstreetmap.org/search?q=' + encodeURIComponent(query) + '&format=json&limit=' + perSource;
          const r = await fetch(u);
          if (!r.ok) return errors.push('nominatim → HTTP '+r.status);
          const j = await r.json();
          for (const it of (j || [])) {
            results.push({ title: it.display_name, url: 'https://www.openstreetmap.org/'+it.osm_type+'/'+it.osm_id, snippet: 'Тип: '+it.type+' · lat='+it.lat+', lon='+it.lon, source:'OpenStreetMap' });
          }
        } catch(e) { errors.push('nominatim → ' + (e.message||e)); }
      }

      // ---- HTML-scraping engines (Bing, Yahoo, Startpage/Google) ----
      // Идут через HTML_PROXIES с fallback. Медленнее API-источников, но реальный
      // поиск по всему интернету.
      function unwrapBingUrl(u) {
        try {
          const url = new URL(u);
          if (url.hostname === 'www.bing.com' && url.pathname === '/ck/a') {
            const enc = url.searchParams.get('u');
            if (enc && enc.startsWith('a1')) {
              let b64 = enc.slice(2).replace(/-/g,'+').replace(/_/g,'/');
              while (b64.length % 4) b64 += '=';
              try { return atob(b64); } catch(_){}
            }
          }
        } catch(_){}
        return u;
      }
      async function bing() {
        try {
          // adlt=off отключает Bing SafeSearch (adlt=strict — включает).
          // Без этого Bing прячет любые NSFW/adult результаты и возвращает
          // "Некоторые результаты были удалены".
          const target = 'https://www.bing.com/search?q=' + encodeURIComponent(query) + (safeOff ? '&adlt=off' : '&adlt=strict');
          const { text: html } = await fetchHtmlViaProxy(target);
          const doc = new DOMParser().parseFromString(html, 'text/html');
          let n = 0;
          for (const li of doc.querySelectorAll('li.b_algo')) {
            if (n >= perSource) break;
            const a = li.querySelector('h2 a[href]');
            if (!a) continue;
            const title = a.textContent.trim();
            const url = unwrapBingUrl(a.href);
            const p = li.querySelector('div.b_caption p, .b_caption p, p');
            const snippet = p ? p.textContent.trim() : '';
            if (!/^https?:\/\//.test(url)) continue;
            results.push({ title, url, snippet: snippet.slice(0, 400), source: 'Bing' });
            n++;
          }
          if (!n) errors.push('bing → пустой парсинг (' + html.length + ' байт HTML)');
        } catch(e) { errors.push('bing → ' + ((e && e.message)||e)); }
      }
      async function yahoo() {
        try {
          // vm=r — Yahoo relaxed SafeSearch (r=off, i=moderate, p=strict)
          const target = 'https://search.yahoo.com/search?p=' + encodeURIComponent(query) + (safeOff ? '&vm=r' : '&vm=p');
          const { text: html } = await fetchHtmlViaProxy(target);
          const doc = new DOMParser().parseFromString(html, 'text/html');
          const seenUrls = new Set();
          let n = 0;
          for (const el of doc.querySelectorAll('div.algo, .algo, ol li')) {
            if (n >= perSource) break;
            const a = el.querySelector('h3 a[href], .compTitle a[href]');
            if (!a) continue;
            let url = a.href;
            const m = url.match(/\/RU=([^\/]+)\//);
            if (m) { try { url = decodeURIComponent(m[1]); } catch(_){} }
            if (!/^https?:\/\//.test(url) || seenUrls.has(url)) continue;
            seenUrls.add(url);
            // Title: Yahoo слепляет hostname + breadcrumbs + title; берём последнюю часть
            let title = a.textContent.trim();
            const dot = title.lastIndexOf('/');
            if (dot > 0 && dot < title.length - 5) title = title.slice(dot + 1).trim();
            const snip = el.querySelector('.compText, p, .fc-falcon')?.textContent?.trim() || '';
            results.push({ title: title.slice(0, 120), url, snippet: snip.slice(0, 400), source: 'Yahoo' });
            n++;
          }
          if (!n) errors.push('yahoo → пустой парсинг');
        } catch(e) { errors.push('yahoo → ' + ((e && e.message)||e)); }
      }
      async function startpage() {
        try {
          // Startpage анонимно проксирует Google-выдачу → реальные Google-результаты.
          // qadf=off — отключает Startpage family filter (adult content filter).
          const target = 'https://www.startpage.com/do/dsearch?query=' + encodeURIComponent(query) + (safeOff ? '&qadf=off' : '&qadf=heavy');
          const { text: html } = await fetchHtmlViaProxy(target);
          const doc = new DOMParser().parseFromString(html, 'text/html');
          // Startpage переехал на emotion CSS-in-JS + классы с суффиксами css-XXXX.
          // Стабильные якоря: .result — контейнер, a.result-link — заголовок-ссылка,
          // .description — сниппет. Fallback: старая вёрстка + просто первая внешняя ссылка.
          let n = 0;
          const seenUrls = new Set();
          const containers = doc.querySelectorAll('.result, .w-gl__result, article.result, section.w-gl article');
          for (const el of containers) {
            if (n >= perSource) break;
            // Убираем inline <style>/<noscript> из клона, чтобы .textContent не тянул CSS.
            const clone = el.cloneNode(true);
            clone.querySelectorAll('style, noscript, script').forEach(s => s.remove());
            const linkEl = clone.querySelector('a.result-link, a[data-testid="result-link"]');
            const externalLinks = [...clone.querySelectorAll('a[href^="http"]')].filter(a =>
              !a.href.includes('startpage.com') &&
              !a.classList.contains('favicon-link') &&
              !a.classList.contains('wgl-display-url')
            );
            const url = (linkEl && linkEl.href) || (externalLinks[0] && externalLinks[0].href);
            if (!url || !/^https?:\/\//.test(url) || seenUrls.has(url)) continue;
            seenUrls.add(url);
            const title = (linkEl?.textContent?.trim()) ||
              clone.querySelector('h1, h2, h3, .w-gl__result-title')?.textContent?.trim() || url;
            const snippet = (clone.querySelector('.description, .w-gl__description, [data-testid="description"], p.description')?.textContent?.trim()) || '';
            results.push({ title: title.slice(0, 160), url, snippet: snippet.slice(0, 400), source: 'Startpage (Google)' });
            n++;
          }
          if (!n) errors.push('startpage → пустой парсинг (' + html.length + ' байт HTML, контейнеров ' + containers.length + ')');
        } catch(e) { errors.push('startpage → ' + ((e && e.message)||e)); }
      }

      // DuckDuckGo HTML-версия — /html/?q= отдаёт реальные результаты (не instant answers).
      // kp=-2 — отключает SafeSearch; kp=1 — moderate; kp=2 — strict.
      async function ddg_html() {
        try {
          const target = 'https://html.duckduckgo.com/html/?q=' + encodeURIComponent(query) + (safeOff ? '&kp=-2' : '&kp=1');
          const { text: html } = await fetchHtmlViaProxy(target);
          const doc = new DOMParser().parseFromString(html, 'text/html');
          let n = 0;
          const seenUrls = new Set();
          for (const el of doc.querySelectorAll('.result, .web-result')) {
            if (n >= perSource) break;
            const a = el.querySelector('.result__a, a.result__a, h2 a');
            if (!a) continue;
            // DDG обёртывает результаты через /l/?uddg=...
            let url = a.getAttribute('href') || '';
            const m = url.match(/[?&]uddg=([^&]+)/);
            if (m) { try { url = decodeURIComponent(m[1]); } catch(_){} }
            if (url.startsWith('//')) url = 'https:' + url;
            if (!/^https?:\/\//.test(url) || seenUrls.has(url)) continue;
            seenUrls.add(url);
            const title = a.textContent.trim();
            const snippet = (el.querySelector('.result__snippet') || el.querySelector('.snippet') || {}).textContent?.trim() || '';
            results.push({ title: title.slice(0, 120), url, snippet: snippet.slice(0, 400), source: 'DuckDuckGo' });
            n++;
          }
          if (!n) errors.push('ddg_html → пустой парсинг');
        } catch(e) { errors.push('ddg_html → ' + ((e && e.message)||e)); }
      }

      // Brave Search — свой независимый индекс. safesearch=off/moderate/strict.
      async function brave() {
        try {
          const target = 'https://search.brave.com/search?q=' + encodeURIComponent(query) + '&safesearch=' + (safeOff ? 'off' : 'strict');
          const { text: html } = await fetchHtmlViaProxy(target);
          const doc = new DOMParser().parseFromString(html, 'text/html');
          let n = 0;
          const seenUrls = new Set();
          for (const el of doc.querySelectorAll('div.snippet, .snippet[data-type], #results .snippet')) {
            if (n >= perSource) break;
            const a = el.querySelector('a[href^="http"]');
            if (!a) continue;
            const url = a.href;
            if (seenUrls.has(url) || url.includes('search.brave.com')) continue;
            seenUrls.add(url);
            const title = (el.querySelector('.title, .snippet-title') || a).textContent?.trim() || '';
            const snippet = (el.querySelector('.snippet-description, .description') || {}).textContent?.trim() || '';
            results.push({ title: title.slice(0, 120), url, snippet: snippet.slice(0, 400), source: 'Brave' });
            n++;
          }
          if (!n) errors.push('brave → пустой парсинг');
        } catch(e) { errors.push('brave → ' + ((e && e.message)||e)); }
      }

      // Mojeek — независимый краулер. safe=0/1.
      async function mojeek() {
        try {
          const target = 'https://www.mojeek.com/search?q=' + encodeURIComponent(query) + '&safe=' + (safeOff ? '0' : '1');
          const { text: html } = await fetchHtmlViaProxy(target);
          const doc = new DOMParser().parseFromString(html, 'text/html');
          let n = 0;
          const seenUrls = new Set();
          for (const el of doc.querySelectorAll('ul.results-standard li, .results li, .results-standard li')) {
            if (n >= perSource) break;
            const a = el.querySelector('h2 a[href^="http"], a.title[href^="http"], a.ob[href^="http"]');
            if (!a) continue;
            const url = a.href;
            if (seenUrls.has(url) || url.includes('mojeek.com')) continue;
            seenUrls.add(url);
            const title = a.textContent.trim();
            const snippet = (el.querySelector('.s, .snippet, p.s') || {}).textContent?.trim() || '';
            results.push({ title: title.slice(0, 120), url, snippet: snippet.slice(0, 400), source: 'Mojeek' });
            n++;
          }
          if (!n) errors.push('mojeek → пустой парсинг');
        } catch(e) { errors.push('mojeek → ' + ((e && e.message)||e)); }
      }

      // Reddit — публичное JSON API /search.json, CORS-friendly, никаких ключей.
      // include_over_18=on — не отфильтровывать NSFW subreddits.
      async function reddit() {
        try {
          const u = 'https://www.reddit.com/search.json?q=' + encodeURIComponent(query) + '&limit=' + perSource + (safeOff ? '&include_over_18=on' : '');
          const r = await fetch(u, { headers:{ 'Accept':'application/json' } });
          if (!r.ok) return errors.push('reddit → HTTP '+r.status);
          const j = await r.json();
          for (const c of (j.data && j.data.children || [])) {
            const d = c.data || {};
            const title = d.title || '';
            const url = d.url_overridden_by_dest || ('https://www.reddit.com' + (d.permalink || ''));
            const snippet = (d.selftext || '').slice(0, 400) || ('r/' + (d.subreddit||'?') + ' · '+ (d.score||0) + ' points · ' + (d.num_comments||0) + ' comments');
            results.push({ title: title.slice(0, 120), url, snippet, source: 'Reddit' + (d.over_18 ? ' [NSFW]' : '') });
          }
        } catch(e) { errors.push('reddit → ' + ((e && e.message)||e)); }
      }

      // Запускаем параллельно все включённые источники
      const jobs = [];
      if (enabled.has('ddg')) jobs.push(ddg());
      if (enabled.has('ddg_html')) jobs.push(ddg_html());
      if (enabled.has('wiki')) {
        jobs.push(wiki('en'));
        if (/[а-яё]/i.test(query)) jobs.push(wiki('ru'));
        // Автоопределение вики по алфавиту запроса (иврит/арабский/китайский/…)
        if (/[\u0590-\u05FF]/.test(query)) jobs.push(wiki('he'));
        if (/[\u0600-\u06FF]/.test(query)) jobs.push(wiki('ar'));
        if (/[\u4E00-\u9FFF]/.test(query)) jobs.push(wiki('zh'));
        if (/[\u3040-\u30FF]/.test(query)) jobs.push(wiki('ja'));
        if (/[\uAC00-\uD7AF]/.test(query)) jobs.push(wiki('ko'));
      }
      if (enabled.has('stackoverflow')) jobs.push(stackoverflow());
      if (enabled.has('github')) jobs.push(github());
      if (enabled.has('npm')) jobs.push(npm());
      if (enabled.has('crates')) jobs.push(crates());
      if (enabled.has('hackernews')) jobs.push(hackernews());
      if (enabled.has('wiktionary')) jobs.push(wiktionary());
      if (enabled.has('openlibrary')) jobs.push(openlibrary());
      if (enabled.has('nominatim')) jobs.push(nominatim());
      if (enabled.has('bing')) jobs.push(bing());
      if (enabled.has('yahoo')) jobs.push(yahoo());
      if (enabled.has('startpage')) jobs.push(startpage());
      if (enabled.has('brave')) jobs.push(brave());
      if (enabled.has('mojeek')) jobs.push(mojeek());
      if (enabled.has('reddit')) jobs.push(reddit());
      await Promise.all(jobs);

      if (!results.length) {
        return err('Не удалось получить результаты поиска. Пробовал:\n' + errors.slice(0, 8).join('\n') +
          '\nСовет: попробуй http_fetch с конкретным URL или уточни запрос.');
      }

      // Дедуп по url, сортировка: результаты чередуются по источникам чтобы
      // никакой один источник не забил всю выдачу — берём по одному из каждого.
      const bySrc = new Map();
      for (const r of results) {
        if (!bySrc.has(r.source)) bySrc.set(r.source, []);
        bySrc.get(r.source).push(r);
      }
      const merged = [];
      const seen = new Set();
      let added = true;
      while (merged.length < n && added) {
        added = false;
        for (const arr of bySrc.values()) {
          if (merged.length >= n) break;
          while (arr.length) {
            const r = arr.shift();
            if (!seen.has(r.url)) { seen.add(r.url); merged.push(r); added = true; break; }
          }
        }
      }
      return ok({ query, count: merged.length, results: merged, sources_used: [...bySrc.keys()], errors: errors.length ? errors : undefined });
    }
  });

  // ---- Image search ----
  // Настоящий поиск картинок ПО ВСЕМУ ИНТЕРНЕТУ (не только wiki). Источники:
  //   • Bing Images — скрапинг www.bing.com/images/search через CORS-прокси;
  //     реальная веб-выдача, murl = полный URL картинки. adlt=off — без SafeSearch.
  //   • DuckDuckGo Images — двухшаговый flow (vqd-токен → i.js JSON) через прокси;
  //     реальная веб-выдача. p=-1 — без SafeSearch.
  //   • Wikimedia Commons и Wikipedia PageImages — резерв (CORS-friendly, без прокси).
  // Возвращает [{title, image_url, thumbnail_url, page_url, width, height, source}].
  // SafeSearch/adult-фильтр выключен по умолчанию (safe_search:"on" включит).
  // Модель может вставить ![](image_url); если хост не отдаёт CORS для <img> —
  // есть thumbnail_url или http_fetch(image_url) → data_url.
  reg({
    name:'image_search',
    description:'Поиск картинок ПО ВСЕМУ ИНТЕРНЕТУ. Возвращает [{title, image_url, thumbnail_url, page_url, width, height, source}]. Агрегирует реальные поисковики изображений через CORS-прокси (без API-ключей): Bing Images и DuckDuckGo Images (настоящая веб-выдача по всему интернету), плюс Wikimedia Commons и Wikipedia как резерв. SafeSearch/adult-фильтр ВЫКЛЮЧЕН по умолчанию (safe_search:"on" включит фильтр) — можно искать любые изображения, включая NSFW/эротику. Полученный image_url можно вставить в ответ как ![alt](image_url) — картинка отобразится в чате (если исходный хост не отдаёт CORS для <img>, используй thumbnail_url или сначала http_fetch по image_url — он вернёт data_url). Параметр sources ограничивает набор источников.',
    params:{ query:'string', limit:'number (optional, default 8, max 30)', safe_search:'string (optional, "off" по умолчанию — не фильтровать взрослый контент; "on" — включить SafeSearch)', sources:'array of strings (optional, subset of ["bing","ddg","commons","wikipedia"]; по умолчанию все)' },
    async run({query, limit=8, safe_search='off', sources}) {
      if (!query || typeof query !== 'string') return err('query обязателен');
      const n = Math.max(1, Math.min(30, +limit || 8));
      const safeOff = String(safe_search||'off').toLowerCase() !== 'on';
      const ALL = ['bing','ddg','commons','wikipedia'];
      const enabled = new Set(Array.isArray(sources) && sources.length ? sources : ALL);
      const out = [];
      const seen = new Set();
      const errors = [];
      const push = (item) => {
        if (!item || !item.image_url) return;
        const key = item.image_url.split('?')[0];
        if (seen.has(key)) return;
        seen.add(key);
        out.push(item);
      };

      // Хелпер: HTML/текст через цепочку CORS-прокси (тот же список, что и web_search).
      async function viaProxy(target, timeoutMs=12000) {
        try { return (await fetchHtmlViaProxy(target, { timeoutMs, minBytes: 400 })).text; }
        catch(e) { throw e; }
      }

      // 1) Bing Images — реальный поиск по всему интернету. Результаты лежат в
      // <a class="iusc" m='{...json...}'> где m.murl = полный URL картинки,
      // m.turl = миниатюра, m.t = заголовок. adlt=off отключает SafeSearch.
      async function bingImages() {
        try {
          const target = 'https://www.bing.com/images/search?q=' + encodeURIComponent(query) +
            '&count=' + Math.min(35, n * 2) + (safeOff ? '&adlt=off' : '&adlt=strict') + '&qft=+filterui:photo-photo';
          const html = await viaProxy(target);
          const doc = new DOMParser().parseFromString(html, 'text/html');
          let c = 0;
          for (const a of doc.querySelectorAll('a.iusc')) {
            if (c >= n * 2) break;
            const mattr = a.getAttribute('m');
            if (!mattr) continue;
            let m; try { m = JSON.parse(mattr); } catch(_) { continue; }
            if (!m.murl) continue;
            push({
              title: (m.t || '').replace(/<[^>]+>/g, '').slice(0, 160) || query,
              image_url: m.murl,
              thumbnail_url: m.turl || m.murl,
              page_url: m.purl || m.murl,
              width: m.mw || undefined, height: m.mh || undefined,
              source: 'Bing Images'
            });
            c++;
          }
          if (!c) errors.push('bing-images → пустой парсинг (' + html.length + ' байт)');
        } catch(e) { errors.push('bing-images → ' + ((e && e.message)||e)); }
      }

      // 2) DuckDuckGo Images — реальная веб-выдача. Двухшаговый flow: сначала берём
      // токен vqd со страницы поиска, затем JSON с i.js. p=-1 — SafeSearch off,
      // p=1 — strict.
      async function ddgImages() {
        try {
          const tokHtml = await viaProxy('https://duckduckgo.com/?q=' + encodeURIComponent(query) + '&iax=images&ia=images');
          const mvqd = tokHtml.match(/vqd=([\d-]+)/) || tokHtml.match(/vqd=["']([^"']+)["']/);
          if (!mvqd) { errors.push('ddg-images → не удалось получить токен vqd'); return; }
          const vqd = mvqd[1];
          const p = safeOff ? '-1' : '1';
          const iu = 'https://duckduckgo.com/i.js?l=us-en&o=json&q=' + encodeURIComponent(query) +
            '&vqd=' + encodeURIComponent(vqd) + '&f=,,,,,&p=' + p;
          const jsonTxt = await viaProxy(iu);
          let j; try { j = JSON.parse(jsonTxt); } catch(_) { errors.push('ddg-images → ответ не JSON'); return; }
          for (const r of (j.results || [])) {
            if (!r.image) continue;
            push({
              title: (r.title || query).slice(0, 160),
              image_url: r.image,
              thumbnail_url: r.thumbnail || r.image,
              page_url: r.url || r.image,
              width: r.width || undefined, height: r.height || undefined,
              source: 'DuckDuckGo Images'
            });
            if (out.length >= n * 2) break;
          }
          if (!(j.results||[]).length) errors.push('ddg-images → пустой список');
        } catch(e) { errors.push('ddg-images → ' + ((e && e.message)||e)); }
      }

      // 3) Wikimedia Commons — резерв (CORS-friendly, без прокси).
      async function commons() {
        try {
          const su = 'https://commons.wikimedia.org/w/api.php?action=query&list=search&srsearch=' + encodeURIComponent(query) + '&srnamespace=6&format=json&origin=*&srlimit=' + Math.min(20, n * 2);
          const sr = await fetch(su);
          if (!sr.ok) { errors.push('commons-search → HTTP ' + sr.status); return; }
          const sj = await sr.json();
          const titles = ((sj.query && sj.query.search) || []).map(h => h.title).filter(Boolean);
          if (!titles.length) return;
          const iu = 'https://commons.wikimedia.org/w/api.php?action=query&titles=' + encodeURIComponent(titles.slice(0, n * 2).join('|')) + '&prop=imageinfo&iiprop=url|size|mime&iiurlwidth=800&format=json&origin=*';
          const ir = await fetch(iu);
          if (!ir.ok) { errors.push('commons-imageinfo → HTTP ' + ir.status); return; }
          const ij = await ir.json();
          const pages = (ij.query && ij.query.pages) || {};
          for (const pid of Object.keys(pages)) {
            const p = pages[pid];
            const info = (p.imageinfo && p.imageinfo[0]) || null;
            if (!info) continue;
            const url = info.thumburl || info.url;
            if (!url) continue;
            push({ title: (p.title || '').replace(/^File:/, ''), image_url: info.url || url, thumbnail_url: info.thumburl || url, page_url: 'https://commons.wikimedia.org/wiki/' + encodeURIComponent(p.title || ''), width: info.thumbwidth || info.width, height: info.thumbheight || info.height, source: 'Wikimedia Commons' });
          }
        } catch(e) { errors.push('commons → ' + (e.message||e)); }
      }

      // 4) Wikipedia PageImages — резерв.
      async function wikipedia() {
        try {
          const lang = /[а-яё]/i.test(query) ? 'ru' : 'en';
          const ou = 'https://' + lang + '.wikipedia.org/w/api.php?action=opensearch&search=' + encodeURIComponent(query) + '&limit=' + Math.min(10, n * 2) + '&format=json&origin=*';
          const or = await fetch(ou);
          if (!or.ok) { errors.push('wiki-opensearch → HTTP ' + or.status); return; }
          const oj = await or.json();
          const titles = (oj[1] || []).slice(0, n * 2);
          if (!titles.length) return;
          const pu = 'https://' + lang + '.wikipedia.org/w/api.php?action=query&titles=' + encodeURIComponent(titles.join('|')) + '&prop=pageimages&format=json&pithumbsize=800&origin=*';
          const pr = await fetch(pu);
          if (!pr.ok) { errors.push('wiki-pageimages → HTTP ' + pr.status); return; }
          const pj = await pr.json();
          const pages = (pj.query && pj.query.pages) || {};
          for (const pid of Object.keys(pages)) {
            const p = pages[pid];
            if (!p.thumbnail) continue;
            push({ title: p.title, image_url: p.thumbnail.source, thumbnail_url: p.thumbnail.source, page_url: 'https://' + lang + '.wikipedia.org/wiki/' + encodeURIComponent(p.title.replace(/ /g,'_')), width: p.thumbnail.width, height: p.thumbnail.height, source: 'Wikipedia (' + lang + ')' });
          }
        } catch(e) { errors.push('wiki-images → ' + (e.message||e)); }
      }

      // Запускаем выбранные источники параллельно. Bing и DDG — главные (реальный веб),
      // Commons/Wikipedia — резерв на случай, когда прокси недоступны.
      const jobs = [];
      if (enabled.has('bing')) jobs.push(bingImages());
      if (enabled.has('ddg')) jobs.push(ddgImages());
      if (enabled.has('commons')) jobs.push(commons());
      if (enabled.has('wikipedia')) jobs.push(wikipedia());
      await Promise.allSettled(jobs);

      if (!out.length) {
        return err('Не удалось найти изображения ни в одном источнике. Пробовал:\n' + errors.slice(0, 8).join('\n'));
      }
      return ok({ query, count: Math.min(out.length, n), total_found: out.length, results: out.slice(0, n),
        hint: 'Чтобы показать картинку пользователю — вставь в ответ ![alt](image_url). Если <img> не грузится из-за CORS исходного сайта, используй thumbnail_url или сначала http_fetch по image_url (он вернёт data_url).' });
    }
  });

  // ---- YouTube search ----
  // YouTube официального CORS-friendly search API нет (нужен ключ Google Data API).
  // Но публичная страница https://www.youtube.com/results?search_query=... содержит
  // всё в виде большого JSON `ytInitialData` внутри <script>. Скрапим через
  // proxy.cors.sh (единственный проверенный прокси, который пропускает YouTube).
  // Возвращаем список видео с title, автором, длительностью, thumbnail и т.п.
  // Модель может сформировать в ответе:
  //   ![thumb](thumbnail_url)  — превью (i.ytimg.com отдаёт CORS)
  //   [Смотреть на YouTube](url)  — ссылка
  // Авто-детект локали YouTube-поиска по алфавиту запроса. Это критично: если
  // задан hl=en (или другой чужой язык), YouTube для запроса на русском/иврите/
  // арабском/китайском и т.п. может отдавать заметно хуже подобранные результаты
  // или вовсе пустую выдачу (плюс EU-consent-редирект на consent.youtube.com).
  function detectYtLocale(q) {
    // Возвращает {hl, gl} — язык интерфейса и регион.
    if (/[\u0400-\u04FF]/.test(q)) return { hl:'ru', gl:'RU' };      // кириллица
    if (/[\u0590-\u05FF]/.test(q)) return { hl:'iw', gl:'IL' };      // иврит
    if (/[\u0600-\u06FF]/.test(q)) return { hl:'ar', gl:'SA' };      // арабский
    if (/[\u4E00-\u9FFF]/.test(q)) return { hl:'zh', gl:'CN' };      // китайский (упрощённый)
    if (/[\u3040-\u30FF]/.test(q)) return { hl:'ja', gl:'JP' };      // японский
    if (/[\uAC00-\uD7AF]/.test(q)) return { hl:'ko', gl:'KR' };      // корейский
    if (/[àâçéèêëîïôûùüÿœæ]/i.test(q)) return { hl:'fr', gl:'FR' };
    if (/[äöüß]/i.test(q)) return { hl:'de', gl:'DE' };
    if (/[ñáéíóúü¿¡]/i.test(q)) return { hl:'es', gl:'ES' };
    if (/[ãõáéíóúâêôç]/i.test(q)) return { hl:'pt', gl:'BR' };
    return { hl:'en', gl:'US' };
  }
  reg({
    name:'youtube_search',
    description:'Поиск видео на YouTube. Возвращает [{video_id, title, author, url, duration, views, published, thumbnail_url, description}]. Работает через прокси-скрапинг публичной страницы поиска YouTube (без API-ключа). Автоматически определяет язык запроса (по алфавиту) — русский/иврит/арабский/китайский/японский/корейский и т.п. вернут результаты той же локали. Thumbnail с i.ytimg.com отдаёт CORS — можно вставлять в чат как ![](thumbnail_url). Ссылка вида https://www.youtube.com/watch?v=<id> открывается у пользователя. Для деталей конкретного видео используй youtube_info. Для последних видео канала — youtube_channel.',
    params:{ query:'string', limit:'number (optional, default 8, max 20)', lang:'string (optional, hl code — если не задан, определяется по алфавиту запроса)', region:'string (optional, gl code — регион, по умолчанию совпадает с lang)' },
    async run({query, limit=8, lang, region}) {
      if (!query || typeof query !== 'string') return err('query обязателен');
      const n = Math.max(1, Math.min(20, +limit || 8));
      const det = detectYtLocale(query);
      const hl = (lang || det.hl).trim();
      const gl = (region || det.gl).trim();
      // persist_gl=1 + gl — говорит YouTube не переопределять регион по IP прокси.
      // Cookies через URL передать нельзя, но hl/gl обходят большинство редиректов.
      const target = 'https://www.youtube.com/results?search_query=' + encodeURIComponent(query) +
        '&hl=' + encodeURIComponent(hl) + '&gl=' + encodeURIComponent(gl) + '&persist_gl=1';
      // Скачиваем страницу и, если ytInitialData не нашлось, пробуем ещё раз с другим
      // прокси/URL — до N попыток. Это критично для больших/чувствительных запросов
      // (порно, политика и т.п.), где какой-то прокси может отдать заглушку или
      // консент-страницу вместо реальной выдачи.
      const attempts = [
        { url: target, minBytes: 50000, note: 'www.youtube.com' },
        { url: target + '&pbj=1', minBytes: 20000, note: 'www.youtube.com&pbj=1' },
        // мобильная версия — некоторые прокси её пропускают
        { url: 'https://m.youtube.com/results?search_query=' + encodeURIComponent(query) +
               '&hl=' + encodeURIComponent(hl) + '&gl=' + encodeURIComponent(gl), minBytes: 20000, note: 'm.youtube.com' },
      ];
      let html = '', proxyUsed = '', fetchErrors = [];
      for (const at of attempts) {
        try {
          const r = await fetchHtmlViaProxy(at.url, { timeoutMs: 15000, minBytes: at.minBytes });
          html = r.text;
          proxyUsed = r.proxyUsed + ' (' + at.note + ')';
          // Быстрая проверка — есть ли якорь ytInitialData в HTML.
          // Если нет — переходим к следующей попытке.
          if (html.indexOf('ytInitialData') === -1) {
            fetchErrors.push(at.note + ' → нет ytInitialData в HTML (' + html.length + ' байт)');
            html = ''; continue;
          }
          break;
        } catch(e) {
          fetchErrors.push(at.note + ' → ' + ((e && e.message)||e));
        }
      }
      if (!html) {
        return err('Не удалось получить страницу YouTube с ytInitialData:\n' + fetchErrors.slice(0,6).join('\n'));
      }
      // Ищем `ytInitialData = {...}` в HTML. YouTube меняет формат: бывает
      // `var ytInitialData = ...`, `window["ytInitialData"] = ...`,
      // `window.ytInitialData = ...`, `ytInitialData = ...`. Non-greedy regex
      // на 500+ KB JSON нестабилен (может пропустить/уронить движок), поэтому
      // ищем стартовую позицию через indexOf + сканируем сбалансированные скобки.
      function extractYtInitialData(src) {
        const anchors = [
          'var ytInitialData =',
          'var ytInitialData=',
          'window["ytInitialData"] =',
          'window["ytInitialData"]=',
          "window['ytInitialData'] =",
          "window['ytInitialData']=",
          'window.ytInitialData =',
          'window.ytInitialData=',
          'ytInitialData =',
        ];
        for (const a of anchors) {
          const p = src.indexOf(a);
          if (p < 0) continue;
          // Найти первую `{` после anchor'а
          const braceStart = src.indexOf('{', p + a.length);
          if (braceStart < 0) continue;
          // Проходим по строке, считая скобки, уважая строки и экранирование.
          let depth = 0, i = braceStart, inStr = false, quote = null, esc = false;
          const N = src.length;
          for (; i < N; i++) {
            const ch = src[i];
            if (inStr) {
              if (esc) { esc = false; continue; }
              if (ch === '\\') { esc = true; continue; }
              if (ch === quote) { inStr = false; quote = null; }
              continue;
            }
            if (ch === '"' || ch === "'") { inStr = true; quote = ch; continue; }
            if (ch === '{') depth++;
            else if (ch === '}') { depth--; if (depth === 0) { i++; break; } }
          }
          if (depth === 0 && i > braceStart) {
            const json = src.slice(braceStart, i);
            try {
              const parsed = JSON.parse(json);
              return { data: parsed, anchor: a, size: json.length };
            } catch(_) {
              // если один якорь дал сбой парсинга — попробуем следующий
            }
          }
        }
        return null;
      }
      const extracted = extractYtInitialData(html);
      if (!extracted) {
        // Diagnostic hint: показываем, встречается ли слово ytInitialData вообще
        const hasWord = html.indexOf('ytInitialData') !== -1;
        return err('YouTube страница загрузилась (' + html.length + ' байт), но ytInitialData не найден или не распарсился. ' +
          (hasWord
            ? 'Слово "ytInitialData" присутствует, но не в ожидаемом формате (возможно, страница-заглушка/AMP/consent). '
            : 'Слова "ytInitialData" нет в HTML — прокси вернул заглушку. ') +
          'Пробуй youtube_channel по конкретному каналу или сначала http_fetch страницы канала.');
      }
      const data = extracted.data;
      // Рекурсивно обходим весь JSON и собираем любые videoRenderer / compactVideoRenderer
      // (мобильная страница) / reelItemRenderer (Shorts) — YouTube меняет схему,
      // но эти три ключа стабильны уже много лет.
      const items = [];
      const seenIds = new Set();
      function extractVideos(node) {
        if (!node || typeof node !== 'object') return;
        if (Array.isArray(node)) {
          for (const x of node) { if (items.length >= n) return; extractVideos(x); }
          return;
        }
        // Проверяем ключи типа-рендерера
        const v = node.videoRenderer || node.compactVideoRenderer || node.gridVideoRenderer;
        const reel = node.reelItemRenderer || node.shortsLockupViewModel;
        if (v && v.videoId) {
          const vid = v.videoId;
          if (!seenIds.has(vid)) {
            seenIds.add(vid);
            const title = v.title?.runs?.[0]?.text || v.title?.simpleText || v.headline?.simpleText || '';
            const thumbs = v.thumbnail?.thumbnails || [];
            const thumb = thumbs.length ? thumbs[thumbs.length - 1].url : ('https://i.ytimg.com/vi/' + vid + '/hqdefault.jpg');
            const author = v.ownerText?.runs?.[0]?.text || v.longBylineText?.runs?.[0]?.text || v.shortBylineText?.runs?.[0]?.text || '';
            const authorUrl = v.ownerText?.runs?.[0]?.navigationEndpoint?.commandMetadata?.webCommandMetadata?.url
                           || v.longBylineText?.runs?.[0]?.navigationEndpoint?.commandMetadata?.webCommandMetadata?.url;
            const duration = v.lengthText?.simpleText || v.thumbnailOverlays?.find?.(o => o.thumbnailOverlayTimeStatusRenderer)?.thumbnailOverlayTimeStatusRenderer?.text?.simpleText || '';
            const views = v.viewCountText?.simpleText || v.shortViewCountText?.simpleText || '';
            const published = v.publishedTimeText?.simpleText || '';
            const desc = (v.detailedMetadataSnippets?.[0]?.snippetText?.runs || v.descriptionSnippet?.runs || []).map(x=>x.text).join('').slice(0, 200);
            items.push({
              video_id: vid,
              title,
              author,
              author_url: authorUrl ? (authorUrl.startsWith('http') ? authorUrl : 'https://www.youtube.com' + authorUrl) : null,
              url: 'https://www.youtube.com/watch?v=' + vid,
              duration,
              views,
              published,
              thumbnail_url: thumb,
              description: desc
            });
          }
        } else if (reel) {
          // Shorts / reels — тоже возвращаем, но пометим kind='short'
          const vid = reel.videoId || reel.entityId || reel.onTap?.innertubeCommand?.reelWatchEndpoint?.videoId;
          if (vid && !seenIds.has(vid)) {
            seenIds.add(vid);
            const title = reel.headline?.simpleText || reel.overlayMetadata?.primaryText?.content || '';
            const thumbs = reel.thumbnail?.thumbnails || [];
            const thumb = thumbs.length ? thumbs[thumbs.length - 1].url : ('https://i.ytimg.com/vi/' + vid + '/hqdefault.jpg');
            const views = reel.viewCountText?.simpleText || reel.overlayMetadata?.secondaryText?.content || '';
            items.push({
              video_id: vid,
              title,
              author: '',
              url: 'https://www.youtube.com/shorts/' + vid,
              duration: '',
              views,
              published: '',
              thumbnail_url: thumb,
              description: '',
              kind: 'short'
            });
          }
        }
        // рекурсия по всем полям
        for (const k in node) {
          if (items.length >= n) return;
          const val = node[k];
          if (val && typeof val === 'object') extractVideos(val);
        }
      }
      extractVideos(data);
      if (items.length >= n) {
        // усечём до n на всякий
        items.length = n;
      }
      // Legacy branch retained for backwards-compat if outer loop below still references items.length
      if (items.length) {
        return ok({ query, count: items.length, results: items, proxyUsed,
          hint: 'Чтобы показать превью пользователю — вставь в ответ строку: ![](thumbnail_url) и рядом [Смотреть](url). i.ytimg.com отдаёт CORS-заголовки, поэтому img загрузится в чате.' });
      }
      // Дошло сюда — ничего не нашли. Продолжаем в старый цикл — там своя ошибка.
      const contents = data?.contents?.twoColumnSearchResultsRenderer?.primaryContents?.sectionListRenderer?.contents || [];
      for (const section of contents) {
        const arr = section.itemSectionRenderer?.contents || [];
        for (const it of arr) {
          const v = it.videoRenderer;
          if (!v || !v.videoId) continue;
          const vid = v.videoId;
          const title = v.title?.runs?.[0]?.text || v.title?.simpleText || '';
          const thumbs = v.thumbnail?.thumbnails || [];
          const thumb = thumbs.length ? thumbs[thumbs.length - 1].url : ('https://i.ytimg.com/vi/' + vid + '/hqdefault.jpg');
          const author = v.ownerText?.runs?.[0]?.text || v.longBylineText?.runs?.[0]?.text || '';
          const authorUrl = v.ownerText?.runs?.[0]?.navigationEndpoint?.commandMetadata?.webCommandMetadata?.url;
          const duration = v.lengthText?.simpleText || '';
          const views = v.viewCountText?.simpleText || v.shortViewCountText?.simpleText || '';
          const published = v.publishedTimeText?.simpleText || '';
          const desc = (v.detailedMetadataSnippets?.[0]?.snippetText?.runs || v.descriptionSnippet?.runs || []).map(x=>x.text).join('').slice(0, 200);
          items.push({
            video_id: vid,
            title,
            author,
            author_url: authorUrl ? 'https://www.youtube.com' + authorUrl : null,
            url: 'https://www.youtube.com/watch?v=' + vid,
            duration,
            views,
            published,
            thumbnail_url: thumb,
            description: desc
          });
          if (items.length >= n) break;
        }
        if (items.length >= n) break;
      }
      if (!items.length) return err('YouTube-страница распарсилась, но видео не найдено.');
      return ok({ query, count: items.length, results: items, proxyUsed,
        hint: 'Чтобы показать превью пользователю — вставь в ответ строку: ![](thumbnail_url) и рядом [Смотреть](url). i.ytimg.com отдаёт CORS-заголовки, поэтому img загрузится в чате.' });
    }
  });

  // ---- YouTube channel latest videos ----
  reg({
    name:'youtube_channel',
    description:'Последние видео с YouTube-канала. Работает через официальную RSS-ленту YouTube (никакого прокси не требуется, всё через rss2json). Принимает либо channel_id (UCxxx...), либо URL канала (https://www.youtube.com/channel/UCxxx, /@handle, /c/name — попробуем извлечь id).',
    params:{ channel_id:'string (UCxxx…) или URL канала', limit:'number (optional, default 10, max 15)' },
    async run({channel_id, limit=10}) {
      if (!channel_id || typeof channel_id !== 'string') return err('channel_id обязателен (UCxxx… или URL канала)');
      let cid = channel_id.trim();
      // Если это URL — извлекаем ID
      const m = cid.match(/channel\/(UC[A-Za-z0-9_-]{20,})/);
      if (m) cid = m[1];
      if (!/^UC[A-Za-z0-9_-]{20,}$/.test(cid)) {
        return err('Похоже, это не channel_id. Нужен UC... из URL канала. Для /@handle или /c/name — сначала получи UC-id (например через http_fetch страницы канала).');
      }
      const rss = 'https://www.youtube.com/feeds/videos.xml?channel_id=' + cid;
      try {
        // Параметр count требует API-ключ у rss2json, поэтому режем на клиенте.
        const r = await fetch('https://api.rss2json.com/v1/api.json?rss_url=' + encodeURIComponent(rss));
        if (!r.ok) return err('rss2json → HTTP ' + r.status);
        const j = await r.json();
        if (j.status !== 'ok') return err('rss2json: ' + (j.message || 'error'));
        const items = (j.items || []).slice(0, limit).map(it => ({
          video_id: (it.link.match(/v=([^&]+)/) || [])[1] || null,
          title: it.title,
          url: it.link,
          author: it.author,
          published: it.pubDate,
          description: (it.description || '').replace(/<[^>]+>/g, '').slice(0, 200),
          thumbnail_url: it.thumbnail || (it.enclosure && it.enclosure.link) || null
        }));
        return ok({
          channel_id: cid,
          channel_title: j.feed?.title || '',
          channel_url: j.feed?.link || ('https://www.youtube.com/channel/' + cid),
          count: items.length,
          results: items,
          hint: 'Превью thumbnail_url (i.ytimg.com) можно вставлять в ответ через ![](thumbnail_url).'
        });
      } catch(e) { return err(e); }
    }
  });

  // ---- YouTube video info (oEmbed) ----
  reg({
    name:'youtube_info',
    description:'Метаданные YouTube-видео по URL или ID: title, автор, thumbnail, HTML embed. Работает через официальный oEmbed endpoint YouTube (напрямую, CORS ok, без ключа).',
    params:{ url:'string (URL видео или его ID)' },
    async run({url}) {
      if (!url || typeof url !== 'string') return err('url обязателен');
      let full = url.trim();
      // Если передан только ID
      if (/^[A-Za-z0-9_-]{11}$/.test(full)) full = 'https://www.youtube.com/watch?v=' + full;
      if (!/youtube\.com|youtu\.be/.test(full)) return err('URL не похож на YouTube');
      try {
        const r = await fetch('https://www.youtube.com/oembed?url=' + encodeURIComponent(full) + '&format=json');
        if (!r.ok) return err('oembed HTTP ' + r.status + ' (видео недоступно или приватное)');
        const j = await r.json();
        const vidM = full.match(/[?&]v=([^&]+)/) || full.match(/youtu\.be\/([A-Za-z0-9_-]{11})/);
        const vid = vidM ? vidM[1] : null;
        return ok({
          video_id: vid,
          title: j.title,
          author: j.author_name,
          author_url: j.author_url,
          url: full,
          thumbnail_url: j.thumbnail_url,
          width: j.thumbnail_width, height: j.thumbnail_height,
          html_embed: j.html,
          hint: 'Чтобы вставить превью — ![](thumbnail_url). Для проигрывания нужно html_embed (iframe), в чат не встраивается — только даёшь ссылку [Смотреть](url).'
        });
      } catch(e) { return err(e); }
    }
  });

  function spec() {
    return list().map(t => ({ name:t.name, description:t.description, params:t.params }));
  }

  // Path-scoping: when Store.activeRepo is set, the agent can ONLY touch files
  // inside that repository's virtualRoot. We rewrite path-like args:
  //   - absolute paths inside repo — unchanged
  //   - absolute paths outside repo — blocked with a clear error
  //   - relative paths — resolved against repo root
  const PATH_FIELDS = ['path','from','to','dir','source','archive'];
  function scopePath(p, root) {
    if (typeof p !== 'string' || !p) return { ok:true, path:p };
    let np = p;
    if (!np.startsWith('/')) np = (root.replace(/\/+$/,'') + '/' + np).replace(/\/+/g,'/');
    const parts = [];
    for (const seg of np.split('/')) {
      if (seg === '' || seg === '.') continue;
      if (seg === '..') { parts.pop(); continue; }
      parts.push(seg);
    }
    np = '/' + parts.join('/');
    const rootN = root.replace(/\/+$/,'') || '/';
    if (np !== rootN && !np.startsWith(rootN + '/')) {
      return { ok:false, error:'Путь "'+p+'" вне выбранного репозитория ('+root+'). Агент видит только этот репозиторий.' };
    }
    return { ok:true, path:np };
  }

  // ---- Нормализация аргументов от модели (провайдеро-независимо) ----
  // ПРОБЛЕМА: модели нередко присылают tool-call, где обязательный аргумент назван
  // не так, как в схеме (path → filename/file/filepath/…, content → text/data/…),
  // либо путь пустой/«/». Раньше это приводило к загадочной ошибке «EISDIR: /» и
  // модель зацикливалась, повторяя тот же битый вызов, пока клиент не обрывал задачу.
  //
  // Здесь мы:
  //   1) восстанавливаем path/content/query/… из распространённых синонимов;
  //   2) если после восстановления обязательный путь всё равно пуст/«/» для
  //      файловой операции — возвращаем ПОНЯТНУЮ, самоисправляющую ошибку с
  //      примером, чтобы модель на следующем шаге прислала корректный вызов
  //      (а не долбила один и тот же битый).
  // Всё это НЕ меняет поведение для корректных вызовов — только чинит битые.
  const PATH_ALIASES  = ['path','filepath','file_path','filePath','file','filename','fileName','file_name','target','target_path','targetPath'];
  const DIR_ALIASES   = ['dir','directory','folder','path','dirpath','dir_path'];
  const CONTENT_ALIASES = ['content','text','data','body','code','contents','value','file_content','fileContent','new_content','newContent'];
  const QUERY_ALIASES = ['query','q','search','term','pattern','text'];
  const URL_ALIASES   = ['url','uri','link','href','address'];
  function firstAlias(obj, aliases, canonical) {
    if (obj[canonical] != null && obj[canonical] !== '') return; // уже есть — не трогаем
    for (const a of aliases) {
      if (a === canonical) continue;
      const v = obj[a];
      if (v != null && v !== '') { obj[canonical] = v; return; }
    }
  }
  function isBlankPath(p) {
    if (p == null) return true;
    const s = String(p).trim();
    return s === '' || s === '/' || s === '.' || s === './';
  }
  // Инструменты, которым обязателен непустой путь к КОНКРЕТНОМУ файлу.
  const NEEDS_FILE_PATH = new Set(['fs_write','fs_replace','fs_append','fs_read','fs_read_image','fs_read_media','fs_read_binary']);
  function normalizeAgentArgs(name, args) {
    if (!args || typeof args !== 'object') return args;
    const a = { ...args };
    // Восстанавливаем синонимы для соответствующих групп инструментов.
    if (/^fs_(write|replace|append|read|read_image|read_media|read_binary|delete|mkdir)$/.test(name)) {
      firstAlias(a, PATH_ALIASES, 'path');
    }
    if (name === 'fs_list' || name === 'fs_search' || name === 'git_status' || name === 'git_add' || name === 'git_log') {
      firstAlias(a, DIR_ALIASES, name === 'fs_list' || name === 'fs_search' ? 'path' : 'dir');
    }
    if (name === 'fs_rename') {
      // from/to могут прийти как source/dest, old_path/new_path, src/dst.
      if (isBlankPath(a.from)) { for (const k of ['from','source','src','old','old_path','oldPath','path']) if (a[k]!=null&&a[k]!==''){a.from=a[k];break;} }
      if (isBlankPath(a.to))   { for (const k of ['to','dest','dst','new','new_path','newPath','target','rename_to']) if (a[k]!=null&&a[k]!==''){a.to=a[k];break;} }
    }
    if (name === 'fs_write' || name === 'fs_append') firstAlias(a, CONTENT_ALIASES, 'content');
    if (name === 'fs_search') firstAlias(a, QUERY_ALIASES, 'query');
    if (name === 'http_fetch' || name === 'git_clone') firstAlias(a, URL_ALIASES, 'url');
    return a;
  }
  // Понятная самоисправляющая ошибка при пустом/битом обязательном пути.
  function blankPathError(name, args) {
    const examples = {
      fs_write: '{"tool":"fs_write","args":{"path":"<путь к файлу>","content":"…"}}',
      fs_append: '{"tool":"fs_append","args":{"path":"<путь к файлу>","content":"…"}}',
      fs_replace: '{"tool":"fs_replace","args":{"path":"<путь к файлу>","search":"…","replace":"…"}}',
      fs_read: '{"tool":"fs_read","args":{"path":"<путь к файлу>"}}',
    };
    const ex = examples[name] || '{"tool":"'+name+'","args":{"path":"<путь к файлу>"}}';
    const got = (() => { try { const c={...args}; if('content' in c) c.content='<'+String(c.content||'').length+' симв.>'; return JSON.stringify(c); } catch(_){ return '{}'; } })();
    return { ok:false, error:
      'Не указан аргумент `path` (путь к файлу) для `'+name+'` — получено: '+got+'. '+
      'Укажи корректный путь к конкретному файлу (например /'+ (Store.get().activeRepo ? 'проект' : 'game') +'/…/File.ext), а не пустую строку и не «/» (это папка). '+
      'Повтори вызов в формате: '+ex+' — с реальным путём и содержимым в правильных полях.' };
  }

  async function execute(name, args, onProgress) {
    const t = get(name);
    if (!t) return { ok:false, error:'Unknown tool: '+name };
    args = args || {};
    // Шаг 0: чиним синонимы полей (path/content/query/url/…) ДО скоупинга и запуска.
    args = normalizeAgentArgs(name, args);
    // Шаг 0.1: пустой обязательный путь у файловой операции → понятная ошибка вместо EISDIR:/.
    if (NEEDS_FILE_PATH.has(name) && isBlankPath(args.path)) {
      return blankPathError(name, args);
    }
    if (name === 'fs_rename' && (isBlankPath(args.from) || isBlankPath(args.to))) {
      return { ok:false, error:'Для `fs_rename` нужны непустые `from` и `to` (пути к файлам). Получено from="'+(args.from||'')+'", to="'+(args.to||'')+'". Повтори с реальными путями.' };
    }
    if ((name === 'fs_delete' || name === 'fs_mkdir') && isBlankPath(args.path)) {
      return { ok:false, error:'Для `'+name+'` нужен непустой `path`. Получено path="'+(args.path||'')+'". «/» — это корень, его нельзя '+(name==='fs_delete'?'удалять':'создавать')+'. Укажи конкретный путь.' };
    }
    try {
      const s = Store.get();
      const ar = s && s.activeRepo;
      const root = ar && (ar.virtualRoot || ar.localDir);
      if (root) {
        const BYPASS = new Set(['git_clone','github_api','http_fetch','web_search','image_search','youtube_search','youtube_channel','youtube_info']);
        if (!BYPASS.has(name)) {
          const patched = { ...args };
          for (const f of PATH_FIELDS) {
            if (patched[f] != null) {
              const r = scopePath(patched[f], root);
              if (!r.ok) return { ok:false, error:r.error };
              patched[f] = r.path;
            }
          }
          if (name === 'fs_search' && (!patched.path || patched.path === '/')) patched.path = root;
          if (name === 'fs_list' && (!patched.path || patched.path === '/')) patched.path = root;
          args = patched;
        }
      }
    } catch(_){}
    const ctx = {
      progress(patch) {
        try { if (typeof onProgress === 'function') onProgress(patch || {}); } catch(_){}
      }
    };
    try { return await t.run(args, ctx); }
    catch(e){ return { ok:false, error: (e && e.message) || String(e) }; }
  }

  // Expose cache clear so AI-module can reset между чатами.
  function clearReadCache(){ readCache.clear(); }

  return { list, get, spec, execute, clearReadCache };
})();
window.Tools = Tools;
