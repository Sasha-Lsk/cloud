/* ===== Util ===== */
const U = (() => {
  const $ = (s, r=document) => r.querySelector(s);
  const $$ = (s, r=document) => Array.from(r.querySelectorAll(s));
  const el = (tag, props={}, ...children) => {
    const n = document.createElement(tag);
    for (const k in props) {
      const v = props[k];
      if (k === 'class') n.className = v;
      else if (k === 'style' && typeof v === 'object') Object.assign(n.style, v);
      else if (k.startsWith('on') && typeof v === 'function') n.addEventListener(k.slice(2), v);
      else if (k === 'html') n.innerHTML = v;
      else if (v !== undefined && v !== null) n.setAttribute(k, v);
    }
    for (const c of children.flat()) {
      if (c == null) continue;
      n.appendChild(typeof c === 'string' ? document.createTextNode(c) : c);
    }
    return n;
  };
  const debounce = (fn, ms=200) => { let t; return (...a) => { clearTimeout(t); t = setTimeout(() => fn(...a), ms); }; };
  const uid = () => Math.random().toString(36).slice(2, 10) + Date.now().toString(36);
  const dirname = (p) => { const i = p.lastIndexOf('/'); return i <= 0 ? '/' : p.slice(0, i); };
  const basename = (p) => { const i = p.lastIndexOf('/'); return i < 0 ? p : p.slice(i+1); };
  const extname = (p) => { const b = basename(p); const i = b.lastIndexOf('.'); return i <= 0 ? '' : b.slice(i+1).toLowerCase(); };
  const normPath = (p) => {
    if (!p) return '/';
    p = String(p).replace(/\\/g, '/');
    if (!p.startsWith('/')) p = '/' + p;
    const parts = []; for (const s of p.split('/')) { if (!s || s === '.') continue; if (s === '..') parts.pop(); else parts.push(s); }
    return '/' + parts.join('/');
  };
  const joinPath = (a, b) => normPath((a || '/') + '/' + (b || ''));
  const bytesToStr = (n) => { if (n<1024) return n+' B'; if (n<1024*1024) return (n/1024).toFixed(1)+' KB'; return (n/1024/1024).toFixed(1)+' MB'; };
  const enc = new TextEncoder(); const dec = new TextDecoder();
  const isTextExt = (ext) => ['js','jsx','ts','tsx','mjs','cjs','html','htm','css','scss','sass','less','json','xml','yml','yaml','md','markdown','txt','py','rb','go','rs','c','cc','cpp','h','hpp','java','kt','swift','php','sh','bash','zsh','sql','csv','tsv','ini','conf','toml','env','gitignore','gitattributes','dockerfile','makefile','vue','svelte','astro','graphql','proto','log','patch','diff','smali','cxx','cs','m','mm','pyw'].includes(String(ext||'').toLowerCase());
  const looksBinary = (u8) => { const L = Math.min(u8.length, 4096); for (let i=0;i<L;i++){ const b=u8[i]; if (b===0) return true; } return false; };
  const bytesToText = (u8) => dec.decode(u8);
  const textToBytes = (s) => enc.encode(s);
  const b64ToBytes = (b64) => { const bin = atob(b64.replace(/\s/g,'')); const u8 = new Uint8Array(bin.length); for (let i=0;i<bin.length;i++) u8[i]=bin.charCodeAt(i); return u8; };
  const bytesToB64 = (u8) => { let s=''; const CH=0x8000; for (let i=0;i<u8.length;i+=CH) s+=String.fromCharCode.apply(null, u8.subarray(i,i+CH)); return btoa(s); };
  const mediaKind = (name) => {
    const e = String(extname(name)||'').toLowerCase();
    if (['png','jpg','jpeg','gif','webp','bmp','ico','avif','apng'].includes(e)) return 'image';
    if (e === 'svg') return 'svg';
    if (['mp4','webm','ogv','mov','m4v'].includes(e)) return 'video';
    if (['mp3','wav','ogg','oga','m4a','flac','aac','opus'].includes(e)) return 'audio';
    if (['pdf'].includes(e)) return 'pdf';
    return null;
  };
  const mimeFromPath = (p) => {
    const e = String(extname(p)||'').toLowerCase();
    const map = {
      png:'image/png', jpg:'image/jpeg', jpeg:'image/jpeg', gif:'image/gif', webp:'image/webp',
      bmp:'image/bmp', ico:'image/x-icon', svg:'image/svg+xml', avif:'image/avif', apng:'image/apng',
      mp4:'video/mp4', webm:'video/webm', ogv:'video/ogg', mov:'video/quicktime', m4v:'video/x-m4v',
      mp3:'audio/mpeg', wav:'audio/wav', ogg:'audio/ogg', oga:'audio/ogg', m4a:'audio/mp4',
      flac:'audio/flac', aac:'audio/aac', opus:'audio/ogg',
      pdf:'application/pdf', json:'application/json', html:'text/html', css:'text/css',
      js:'application/javascript', md:'text/markdown', txt:'text/plain', xml:'application/xml',
      zip:'application/zip'
    };
    return map[e] || 'application/octet-stream';
  };
  const langFromPath = (p) => {
    const e = extname(p);
    const map = {js:'javascript',mjs:'javascript',cjs:'javascript',jsx:'javascript',ts:'typescript',tsx:'typescript',
      html:'html',htm:'html',css:'css',scss:'scss',less:'less',json:'json',md:'markdown',markdown:'markdown',
      xml:'xml',yaml:'yaml',yml:'yaml',py:'python',rb:'ruby',go:'go',rs:'rust',c:'c',h:'c',cpp:'cpp',cc:'cpp',hpp:'cpp',
      java:'java',kt:'kotlin',swift:'swift',php:'php',sh:'shell',bash:'shell',zsh:'shell',sql:'sql',vue:'html',svelte:'html',
      dockerfile:'dockerfile',makefile:'makefile',toml:'ini',ini:'ini',conf:'ini',smali:'smali',
      cxx:'cpp',cs:'cpp',m:'c',mm:'cpp',pyw:'python'};
    return map[e] || 'plaintext';
  };
  const fileIcon = (name, isDir) => {
    if (isDir) return {cls:'ft-folder', ch:'▸'};
    const e = extname(name);
    if (['js','mjs','cjs','jsx'].includes(e)) return {cls:'ft-js', ch:'JS'};
    if (['ts','tsx'].includes(e)) return {cls:'ft-ts', ch:'TS'};
    if (e==='py') return {cls:'ft-py', ch:'PY'};
    if (['html','htm'].includes(e)) return {cls:'ft-html', ch:'<>'};
    if (['css','scss','less'].includes(e)) return {cls:'ft-css', ch:'#'};
    if (e==='json') return {cls:'ft-json', ch:'{}'};
    if (['md','markdown'].includes(e)) return {cls:'ft-md', ch:'M↓'};
    if (['png','jpg','jpeg','gif','svg','webp','bmp','ico'].includes(e)) return {cls:'ft-img', ch:'🖼'};
    if (['zip','tar','gz','tgz','rar','7z'].includes(e)) return {cls:'ft-zip', ch:'📦'};
    return {cls:'ft-default', ch:'•'};
  };
  const escapeHtml = (s) => String(s).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const sleep = (ms) => new Promise(r => setTimeout(r, ms));
  return {$, $$, el, debounce, uid, dirname, basename, extname, normPath, joinPath, bytesToStr, isTextExt, looksBinary, bytesToText, textToBytes, b64ToBytes, bytesToB64, langFromPath, mediaKind, mimeFromPath, fileIcon, escapeHtml, sleep};
})();
window.U = U;
