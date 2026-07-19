/* ===== Highlight: лёгкая подсветка синтаксиса без внешних зависимостей =====
 * Работает офлайн, без CDN. Токенизирует исходный код в цветные <span> с
 * классами hl-*. Палитра — VS Code Dark+ (см. css/main.css, блок .hl-*).
 *
 * Поддерживаемые группы языков (через U.langFromPath):
 *   c-like     — js/ts/jsx/tsx/c/cpp/java/kotlin/swift/go/rust/php/c#/scss/less
 *   python     — python/ruby (# комментарии, def/class)
 *   shell      — sh/bash/zsh
 *   html/xml   — html/xml/vue/svelte (теги, атрибуты, встроенные <style>/<script>)
 *   css        — css/scss/less
 *   json       — json
 *   markdown   — md/markdown
 *   ini/yaml   — ini/toml/conf/yaml
 * Незнакомые языки подсвечиваются как обычный текст (без ошибок).
 */
const Highlight = (() => {
  const esc = (s) => String(s).replace(/[&<>]/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;'}[c]));

  // Наборы ключевых слов по языкам.
  const KW = {
    javascript: 'break case catch class const continue debugger default delete do else export extends finally for function if import in instanceof let new return super switch this throw try typeof var void while with yield async await static get set of as from',
    typescript: 'break case catch class const continue debugger default delete do else export extends finally for function if import in instanceof let new return super switch this throw try typeof var void while with yield async await static get set of as from interface type enum namespace declare implements public private protected readonly abstract keyof infer is satisfies',
    python: 'and as assert async await break class continue def del elif else except finally for from global if import in is lambda nonlocal not or pass raise return try while with yield None True False self match case',
    ruby: 'alias and begin break case class def defined do else elsif end ensure false for if in module next nil not or redo rescue retry return self super then true undef unless until when while yield require attr_accessor attr_reader attr_writer',
    go: 'break case chan const continue default defer else fallthrough for func go goto if import interface map package range return select struct switch type var nil true false iota make new len cap append',
    rust: 'as async await break const continue crate dyn else enum extern false fn for if impl in let loop match mod move mut pub ref return self Self static struct super trait true type unsafe use where while',
    c: 'auto break case char const continue default do double else enum extern float for goto if inline int long register restrict return short signed sizeof static struct switch typedef union unsigned void volatile while bool true false NULL',
    cpp: 'auto break case catch char class const constexpr continue default delete do double else enum explicit extern false float for friend goto if inline int long namespace new nullptr operator private protected public register return short signed sizeof static struct switch template this throw true try typedef typename union unsigned using virtual void volatile while',
    java: 'abstract assert boolean break byte case catch char class const continue default do double else enum extends final finally float for goto if implements import instanceof int interface long native new package private protected public return short static strictfp super switch synchronized this throw throws transient try void volatile while true false null var',
    kotlin: 'as break class continue do else false for fun if in interface is null object package return super this throw true try typealias val var when while by get set import abstract override open private protected public sealed suspend',
    swift: 'associatedtype class deinit enum extension func import init let protocol struct subscript typealias var break case continue default defer do else fallthrough for guard if in repeat return switch where while as catch throw throws try false true nil self Self super',
    php: 'abstract and array as break callable case catch class clone const continue declare default do echo else elseif empty enddeclare endfor endforeach endif endswitch endwhile extends final finally fn for foreach function global goto if implements include include_once instanceof insteadof interface isset list namespace new or print private protected public require require_once return static switch throw trait try unset use var while xor yield true false null self this',
    shell: 'if then else elif fi for while until do done case esac in function select time coproc echo cd export local return exit source alias unalias set unset read test true false',
    sql: 'select from where insert into values update set delete create table drop alter add column primary key foreign references index view join inner left right outer on group by order having limit offset union all as distinct and or not null is in like between count sum avg min max',
    smali: 'return return-void return-object return-wide move move-object move-result move-result-object move-result-wide invoke-virtual invoke-static invoke-direct invoke-super invoke-interface invoke-virtual/range invoke-static/range const const-string const-class const-wide const/4 const/16 const/high16 new-instance new-array iget iput iget-object iput-object sget sput sget-object sput-object goto if-eq if-ne if-lt if-ge if-gt if-le if-eqz if-nez if-ltz if-gez if-gtz if-lez check-cast instance-of throw nop aget aput array-length int-to-long long-to-int add-int sub-int mul-int div-int rem-int and-int or-int',
    css: '', json: '', html: '', markdown: ''
  };
  KW.jsx = KW.tsx = KW.javascript; // покрыто общей c-like логикой
  const cLike = new Set(['javascript','typescript','go','rust','c','cpp','java','kotlin','swift','php','sql','scss','less']);
  // Часто встречающиеся встроенные типы/константы для c-подобных — подсвечиваем как тип.
  const BUILTIN_TYPES = new Set(['int','long','short','byte','char','float','double','boolean','bool','void','unsigned','signed','string','String','var','let','const','auto','size_t','wchar_t','int8_t','int16_t','int32_t','int64_t','uint8_t','uint16_t','uint32_t','uint64_t','Object','Integer','Double','Float','Boolean','Long','List','Map','Set','Array']);
  const CONSTS = new Set(['true','false','null','nil','none','None','True','False','NULL','nullptr','undefined','this','self','super']);

  function kwSet(lang) {
    const s = KW[lang] || KW.javascript;
    return new Set(s.split(/\s+/).filter(Boolean));
  }

  // Раскраска пунктуации/операторов/скобок для c-подобных языков.
  const OP_CHARS = '+-*/%=<>!&|^~?:';
  function punc(ch) {
    if (ch === '(' || ch === ')' || ch === '[' || ch === ']' || ch === '{' || ch === '}')
      return '<span class="hl-punc">' + esc(ch) + '</span>';
    if (OP_CHARS.indexOf(ch) >= 0) return '<span class="hl-op">' + esc(ch) + '</span>';
    return esc(ch);
  }

  // Общий токенайзер c-подобных языков (и python/shell через флаги комментариев).
  function highlightGeneric(code, lang, opts) {
    opts = opts || {};
    const kw = kwSet(lang);
    const lineComment = opts.lineComment || '//';
    const blockComment = opts.blockComment !== false;   // /* */
    const hashComment = !!opts.hashComment;             // # ...
    const preproc = !!opts.preproc;                     // #include / #define (C/C++)
    const annotations = opts.annotations !== false;     // @Override / @decorator
    const templateStr = opts.templateStr !== false;     // `...`
    let out = '';
    const n = code.length;
    let i = 0;
    let atLineStart = true; // для препроцессора C
    while (i < n) {
      const c = code[i];
      const two = code.substr(i, 2);
      // Директива препроцессора: строка начинается с # (C/C++).
      if (preproc && atLineStart && c === '#') {
        let j = i; while (j < n && code[j] !== '\n') j++;
        out += '<span class="hl-preproc">' + esc(code.slice(i, j)) + '</span>';
        i = j; atLineStart = false; continue;
      }
      // Хэш-комментарий (python/shell)
      if (hashComment && c === '#') {
        let j = i; while (j < n && code[j] !== '\n') j++;
        out += '<span class="hl-com">' + esc(code.slice(i, j)) + '</span>';
        i = j; continue;
      }
      // Строчный комментарий //
      if (lineComment && two === lineComment) {
        let j = i; while (j < n && code[j] !== '\n') j++;
        out += '<span class="hl-com">' + esc(code.slice(i, j)) + '</span>';
        i = j; continue;
      }
      // Блочный комментарий /* */
      if (blockComment && two === '/*') {
        let j = code.indexOf('*/', i + 2); j = j < 0 ? n : j + 2;
        out += '<span class="hl-com">' + esc(code.slice(i, j)) + '</span>';
        i = j; continue;
      }
      // Строки: " ' ` (шаблонные — опционально)
      if (c === '"' || c === "'" || (templateStr && c === '`')) {
        let j = i + 1;
        while (j < n) { if (code[j] === '\\') { j += 2; continue; } if (code[j] === c) { j++; break; } j++; }
        out += '<span class="hl-str">' + esc(code.slice(i, j)) + '</span>';
        i = j; atLineStart = false; continue;
      }
      // Аннотации / декораторы: @Word
      if (annotations && c === '@' && /[A-Za-z_]/.test(code[i+1] || '')) {
        let j = i + 1; while (j < n && /[A-Za-z0-9_.]/.test(code[j])) j++;
        out += '<span class="hl-annotation">' + esc(code.slice(i, j)) + '</span>';
        i = j; atLineStart = false; continue;
      }
      // Числа (hex, bin, float, суффиксы L/f/u)
      if (/[0-9]/.test(c) || (c === '.' && /[0-9]/.test(code[i+1] || ''))) {
        let j = i;
        if (two === '0x' || two === '0X') { j = i + 2; while (j < n && /[0-9a-fA-F_]/.test(code[j])) j++; }
        else if (two === '0b' || two === '0B') { j = i + 2; while (j < n && /[01_]/.test(code[j])) j++; }
        else { while (j < n && /[0-9._]/.test(code[j])) j++; if (/[eE]/.test(code[j])) { j++; if (code[j]==='+'||code[j]==='-') j++; while (j<n&&/[0-9]/.test(code[j])) j++; } }
        while (j < n && /[uUlLfFdD]/.test(code[j])) j++; // числовые суффиксы
        out += '<span class="hl-num">' + esc(code.slice(i, j)) + '</span>';
        i = j; atLineStart = false; continue;
      }
      // Идентификаторы / ключевые слова / типы / функции / константы / свойства
      if (/[A-Za-z_$]/.test(c)) {
        let j = i; while (j < n && /[A-Za-z0-9_$]/.test(code[j])) j++;
        const word = code.slice(i, j);
        let k = j; while (k < n && (code[k] === ' ' || code[k] === '\t')) k++;
        // Был ли перед словом '.' (обращение к свойству/методу)?
        let p = i - 1; while (p >= 0 && (code[p] === ' ' || code[p] === '\t')) p--;
        const afterDot = code[p] === '.';
        if (kw.has(word)) out += '<span class="hl-kw">' + esc(word) + '</span>';
        else if (BUILTIN_TYPES.has(word)) out += '<span class="hl-type">' + esc(word) + '</span>';
        else if (CONSTS.has(word)) out += '<span class="hl-const">' + esc(word) + '</span>';
        else if (code[k] === '(') out += '<span class="hl-fn">' + esc(word) + '</span>';
        else if (/^[A-Z][A-Za-z0-9_]*$/.test(word) && !afterDot) out += '<span class="hl-type">' + esc(word) + '</span>';
        else if (/^[A-Z][A-Z0-9_]+$/.test(word)) out += '<span class="hl-const">' + esc(word) + '</span>';
        else if (afterDot) out += '<span class="hl-prop">' + esc(word) + '</span>';
        else out += esc(word);
        i = j; atLineStart = false; continue;
      }
      // Пунктуация, операторы, скобки
      if (c === '\n') { out += '\n'; i++; atLineStart = true; continue; }
      if (c === ' ' || c === '\t') { out += c; i++; continue; }
      out += punc(c);
      i++; atLineStart = false;
    }
    return out;
  }

  // Токенайзер Smali (байткод Dalvik): директивы .method, регистры v0/p1, типы Lcom/...;
  function highlightSmali(code) {
    const kw = kwSet('smali');
    return code.split('\n').map(line => {
      // Комментарий #
      const ci = line.indexOf('#');
      let com = '';
      if (ci >= 0) { com = '<span class="hl-com">' + esc(line.slice(ci)) + '</span>'; line = line.slice(0, ci); }
      // Токенизируем по пробелам/знакам, сохраняя разделители.
      const html = line.replace(/(\.[a-z][a-z0-9-]*)|([vp]\d+)|(L[\w$\/]+;)|("(?:[^"\\]|\\.)*")|(->)|(:[a-zA-Z_][\w]*)|(\{|\}|,|\(|\))|([a-zA-Z][\w\-\/]*)|(0x[0-9a-fA-F]+|-?\d+)/g,
        (m, dir, reg, type, str, arrow, label, punc, word, num) => {
          if (dir) return '<span class="hl-kw">' + esc(dir) + '</span>';           // .method .field .line
          if (reg) return '<span class="hl-reg">' + esc(reg) + '</span>';          // v0 p1
          if (type) return '<span class="hl-type">' + esc(type) + '</span>';       // Ljava/lang/String;
          if (str) return '<span class="hl-str">' + esc(str) + '</span>';
          if (arrow) return '<span class="hl-op">-&gt;</span>';
          if (label) return '<span class="hl-annotation">' + esc(label) + '</span>'; // :goto_0
          if (punc) return '<span class="hl-punc">' + esc(punc) + '</span>';
          if (num) return '<span class="hl-num">' + esc(num) + '</span>';
          if (word) return kw.has(word) ? '<span class="hl-fn">' + esc(word) + '</span>' : esc(word);
          return esc(m);
        });
      return html + com;
    }).join('\n');
  }

  function highlightJSON(code) {
    let out = '', i = 0; const n = code.length;
    while (i < n) {
      const c = code[i];
      if (c === '"') {
        let j = i + 1; while (j < n) { if (code[j] === '\\') { j += 2; continue; } if (code[j] === '"') { j++; break; } j++; }
        let k = j; while (k < n && (code[k] === ' ' || code[k] === '\t')) k++;
        const cls = code[k] === ':' ? 'hl-key' : 'hl-str';
        out += '<span class="' + cls + '">' + esc(code.slice(i, j)) + '</span>'; i = j; continue;
      }
      if (/[0-9-]/.test(c) && /[0-9]/.test(code[i+1] || c)) {
        let j = i + 1; while (j < n && /[0-9.eE+-]/.test(code[j])) j++;
        out += '<span class="hl-num">' + esc(code.slice(i, j)) + '</span>'; i = j; continue;
      }
      if (/[a-z]/.test(c)) {
        let j = i; while (j < n && /[a-z]/.test(code[j])) j++;
        const w = code.slice(i, j);
        if (w === 'true' || w === 'false' || w === 'null') out += '<span class="hl-const">' + w + '</span>';
        else out += esc(w);
        i = j; continue;
      }
      if (c === '{' || c === '}' || c === '[' || c === ']') { out += '<span class="hl-punc">' + c + '</span>'; i++; continue; }
      if (c === ':' || c === ',') { out += '<span class="hl-op">' + c + '</span>'; i++; continue; }
      out += esc(c); i++;
    }
    return out;
  }

  function highlightCSS(code) {
    let out = '', i = 0; const n = code.length; let inBlock = false;
    while (i < n) {
      const start = i; // страховка от зависания: гарантируем продвижение
      const c = code[i], two = code.substr(i, 2);
      if (two === '/*') { let j = code.indexOf('*/', i + 2); j = j < 0 ? n : j + 2; out += '<span class="hl-com">' + esc(code.slice(i, j)) + '</span>'; i = j; continue; }
      if (c === '"' || c === "'") { let j = i + 1; while (j < n && code[j] !== c) { if (code[j] === '\\') j++; j++; } j++; out += '<span class="hl-str">' + esc(code.slice(i, j)) + '</span>'; i = j; continue; }
      if (c === '{') { inBlock = true; out += '<span class="hl-punc">{</span>'; i++; continue; }
      if (c === '}') { inBlock = false; out += '<span class="hl-punc">}</span>'; i++; continue; }
      // HEX-цвет: #fff / #0d1117
      if (c === '#' && /[0-9a-fA-F]/.test(code[i+1] || '') && (inBlock || true)) {
        let j = i + 1; while (j < n && /[0-9a-fA-F]/.test(code[j])) j++;
        if (j - i >= 4) { out += '<span class="hl-num">' + esc(code.slice(i, j)) + '</span>'; i = j; continue; }
      }
      // Внутри блока: имя свойства перед ':' — hl-key; иначе — значение/функция.
      if (inBlock && /[a-zA-Z-]/.test(c)) {
        let j = i; while (j < n && /[a-zA-Z-]/.test(code[j])) j++;
        const word = code.slice(i, j);
        let k = j; while (k < n && /\s/.test(code[k])) k++;
        if (code[k] === ':') out += '<span class="hl-key">' + esc(word) + '</span>';
        else if (code[j] === '(') out += '<span class="hl-fn">' + esc(word) + '</span>'; // rgba(), calc()
        else out += '<span class="hl-const">' + esc(word) + '</span>';                    // red, flex, auto
        i = j;
        if (i === start) { out += esc(code[i]); i++; }
        continue;
      }
      // Числа/размеры (и в блоке, и вне) — до общих буквенных веток.
      if (/[0-9]/.test(c) || (c === '-' && /[0-9]/.test(code[i+1] || ''))) {
        let j = i + 1; while (j < n && /[0-9.a-z%]/.test(code[j])) j++;
        out += '<span class="hl-num">' + esc(code.slice(i, j)) + '</span>'; i = j; continue;
      }
      // Пунктуация значений
      if (c === ':' || c === ';' || c === ',') { out += '<span class="hl-op">' + esc(c) + '</span>'; i++; continue; }
      if (c === '(' || c === ')') { out += '<span class="hl-punc">' + esc(c) + '</span>'; i++; continue; }
      // Вне блока: селектор — последовательность из имён, . # : > + ~ [] и т.п.
      if (!inBlock && /[.#:@a-zA-Z*\[]/.test(c)) {
        let j = i;
        while (j < n && /[.#:@a-zA-Z0-9_\-\[\]="'()*>~+ ]/.test(code[j]) && code[j] !== '{') j++;
        // Обрежем хвостовые пробелы из селектора (оставим их сырыми).
        let sel = code.slice(i, j);
        const trimmed = sel.replace(/\s+$/, '');
        out += '<span class="hl-sel">' + esc(trimmed) + '</span>' + esc(sel.slice(trimmed.length));
        i = j;
        if (i === start) { out += esc(code[i]); i++; }
        continue;
      }
      out += esc(c); i++;
      if (i <= start) i = start + 1; // на всякий случай
    }
    return out;
  }

  function highlightHTML(code) {
    let out = '', i = 0; const n = code.length;
    while (i < n) {
      const c = code[i];
      // Комментарии <!-- -->
      if (code.substr(i, 4) === '<!--') { let j = code.indexOf('-->', i); j = j < 0 ? n : j + 3; out += '<span class="hl-com">' + esc(code.slice(i, j)) + '</span>'; i = j; continue; }
      // DOCTYPE: <!doctype html>
      if (/^<!doctype/i.test(code.slice(i, i + 9))) {
        let j = code.indexOf('>', i); if (j < 0) j = n - 1; j++;
        out += highlightDoctype(code.slice(i, j)); i = j; continue;
      }
      if (c === '<') {
        let j = code.indexOf('>', i); if (j < 0) j = n - 1; j++;
        const tag = code.slice(i, j);
        // Встроенные блоки style и script подсвечиваем как CSS/JS.
        // ВАЖНО: не пишем закрывающие теги буквально даже в комментариях/строках —
        // иначе HTML-парсер закроет наш внешний <scr'+'ipt> раньше времени.
        const em = tag.match(/^<\s*(style|script)\b/i);
        if (em && !tag.match(/\/\s*>$/)) {
          out += highlightTag(tag);
          i = j;
          const kind = em[1].toLowerCase();
          // Регэкспы закрывающих тегов собираем из частей, чтобы в исходнике не было
          // подстроки «</scr…» / «</sty…», которую HTML-парсер примет за конец скрипта.
          const closeRe = kind === 'style'
            ? new RegExp('<\\/\\s*' + 'style' + '\\s*>', 'i')
            : new RegExp('<\\/\\s*' + 'script' + '\\s*>', 'i');
          const rest = code.slice(i);
          const cm = rest.match(closeRe);
          const end = cm ? i + cm.index : n;
          const inner = code.slice(i, end);
          out += (kind === 'style' ? highlightCSS(inner) : highlightGeneric(inner, 'javascript', { lineComment: '//', blockComment: true }));
          i = end; continue;
        }
        out += highlightTag(tag);
        i = j; continue;
      }
      // Текстовое содержимое до следующего тега
      let j = code.indexOf('<', i); if (j < 0) j = n;
      out += esc(code.slice(i, j)); i = j;
    }
    return out;
  }
  // <!doctype html> — «<!doctype» пунктуация, «html» — как имя типа (оранжевый).
  function highlightDoctype(tag) {
    return esc(tag).replace(/^(&lt;!)([a-zA-Z]+)(\s+)([a-zA-Z0-9]+)?(.*)$/,
      (a, br, dt, sp, val, rest) =>
        '<span class="hl-punc">' + br + '</span><span class="hl-kw">' + dt + '</span>' + sp +
        (val ? '<span class="hl-doctype">' + val + '</span>' : '') + rest);
  }
  function highlightTag(tag) {
    // tag — сырой фрагмент <tagname attr="val" ...> / </tagname> / <tag/>.
    // Раскрашиваем скобки < > /, имя тега (жирно), атрибуты (оранжевые),
    // = (пунктуация), значения в кавычках (зелёные).
    const m = tag.match(/^(<\/?)([a-zA-Z][a-zA-Z0-9-]*)([\s\S]*?)(\/?>)?$/);
    if (!m) return esc(tag);
    const open = '<span class="hl-punc">' + esc(m[1]) + '</span>';
    const name = '<span class="hl-tag">' + esc(m[2]) + '</span>';
    const close = m[4] ? '<span class="hl-punc">' + esc(m[4]) + '</span>' : '';
    // Экранируем строку атрибутов, затем детально раскрашиваем.
    let attrs = esc(m[3] || '');
    // Пары name="value"
    attrs = attrs.replace(/([a-zA-Z_:][a-zA-Z0-9_:.-]*)(\s*)(=)(\s*)(&quot;[\s\S]*?&quot;|&#39;[\s\S]*?&#39;|[^\s&]+)/g,
      (a, at, s1, eq, s2, val) =>
        '<span class="hl-attr">' + at + '</span>' + s1 +
        '<span class="hl-punc">' + eq + '</span>' + s2 +
        '<span class="hl-str">' + val + '</span>');
    return open + name + attrs + close;
  }

  function highlightMarkdown(code) {
    const lines = code.split('\n');
    return lines.map(line => {
      if (/^\s{0,3}#{1,6}\s/.test(line)) return '<span class="hl-md-h">' + esc(line) + '</span>';
      if (/^\s{0,3}(```|~~~)/.test(line)) return '<span class="hl-md-code">' + esc(line) + '</span>';
      if (/^\s{0,3}&gt;/.test(esc(line))) return '<span class="hl-com">' + esc(line) + '</span>';
      // Инлайн-форматирование (жирный / код / ссылки) — общее для обычных строк и списков.
      const inline = (s) => s
        .replace(/(\*\*|__)([^*_]+?)\1/g, '<span class="hl-md-b">$1$2$1</span>')
        .replace(/(`)([^`]+?)(`)/g, '<span class="hl-md-code">$1$2$3</span>')
        .replace(/(\[[^\]]+\])(\([^)]+\))/g, '<span class="hl-md-link">$1</span><span class="hl-str">$2</span>');
      let m = line.match(/^(\s*)([-*+]|\d+\.)(\s)([\s\S]*)$/);
      if (m) return esc(m[1]) + '<span class="hl-md-list">' + esc(m[2]) + '</span>' + esc(m[3]) + inline(esc(m[4]));
      return inline(esc(line));
    }).join('\n');
  }

  function highlightIni(code) {
    return code.split('\n').map(line => {
      if (/^\s*[#;]/.test(line)) return '<span class="hl-com">' + esc(line) + '</span>';
      let m = line.match(/^(\s*\[)([^\]]*)(\].*)$/);
      if (m) return esc(m[1]) + '<span class="hl-sel">' + esc(m[2]) + '</span>' + esc(m[3]);
      m = line.match(/^(\s*[\w.-]+\s*)([:=])(.*)$/);
      if (m) return '<span class="hl-key">' + esc(m[1]) + '</span>' + esc(m[2]) + '<span class="hl-str">' + esc(m[3]) + '</span>';
      return esc(line);
    }).join('\n');
  }

  // Порог, выше которого подсветка не строится: токенизация огромного файла и
  // генерация мегабайтов <span> подвешивает вкладку и часто вообще не
  // прорисовывается. Возвращаем экранированный текст — он рисуется мгновенно.
  const MAX_HL_CHARS = 200 * 1024; // ~200 КБ

  // Главная точка входа. Возвращает HTML-строку с <span class="hl-*">.
  function toHTML(code, lang) {
    if (code == null) return '';
    code = String(code);
    if (code.length > MAX_HL_CHARS) return esc(code);
    try {
      switch (lang) {
        case 'json': return highlightJSON(code);
        case 'css': case 'scss': case 'less': return highlightCSS(code);
        case 'html': case 'xml': return highlightHTML(code);
        case 'markdown': return highlightMarkdown(code);
        case 'ini': case 'yaml': case 'toml': return highlightIni(code);
        case 'smali': return highlightSmali(code);
        case 'python': case 'ruby':
          return highlightGeneric(code, lang, { lineComment: '#', blockComment: false, hashComment: true, templateStr: false });
        case 'shell':
          return highlightGeneric(code, 'shell', { lineComment: '#', blockComment: false, hashComment: true, annotations: false, templateStr: false });
        case 'dockerfile': case 'makefile':
          return highlightGeneric(code, 'shell', { lineComment: '#', blockComment: false, hashComment: true, annotations: false, templateStr: false });
        case 'c': case 'cpp':
          return highlightGeneric(code, lang, { lineComment: '//', blockComment: true, preproc: true, annotations: false, templateStr: false });
        case 'java': case 'kotlin':
          return highlightGeneric(code, lang, { lineComment: '//', blockComment: true, annotations: true });
        case 'plaintext': case '': case undefined: case null:
          return esc(code);
        default:
          return highlightGeneric(code, lang, { lineComment: '//', blockComment: true });
      }
    } catch (e) {
      return esc(code);
    }
  }

  // Подсветка по пути файла (использует U.langFromPath).
  function byPath(code, path) {
    const lang = (window.U && U.langFromPath) ? U.langFromPath(path) : 'plaintext';
    return toHTML(code, lang);
  }

  return { toHTML, byPath, esc };
})();
window.Highlight = Highlight;
