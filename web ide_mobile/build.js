// Build a single-file HTML by inlining the CSS and JS modules
const fs = require('fs');
const path = require('path');
const root = __dirname;
const html = fs.readFileSync(path.join(root, 'index.html'), 'utf8');
const css = fs.readFileSync(path.join(root, 'css/main.css'), 'utf8');
const files = [
  'js/core/util.js',
  'js/core/highlight.js',
  'js/core/store.js',
  'js/core/fs.js',
  'js/core/github-fs.js',
  'js/core/local-fs.js',
  'js/core/fs-router.js',
  'js/core/ui.js',
  'js/tabs/explorer.js',
  'js/tabs/editor.js',
  'js/tabs/git.js',
  'js/tabs/ai.js',
  'js/tabs/chats.js',
  'js/core/tools.js',
  'js/core/settings.js',
  'js/app.js',
];

let notifySnippet = '';
const mp3Path = path.join(root, 'css', 'notify.mp3');
if (fs.existsSync(mp3Path)) {
  const b64 = fs.readFileSync(mp3Path).toString('base64');
  notifySnippet =
    "/* ===== embedded css/notify.mp3 ===== */\n" +
    "window.__NOTIFY_MP3_DATAURL__ = 'data:audio/mpeg;base64," + b64 + "';\n";
}

let combined = notifySnippet;
for (const f of files) {
  combined += `\n/* ===== ${f} ===== */\n` + fs.readFileSync(path.join(root, f), 'utf8') + '\n';
}
let out = html.replace(
  /<link rel="stylesheet" href="css\/main\.css"\s*\/?>/,
  '<style>\n' + css + '\n</style>'
);
out = out.replace(/\n\s*<script src="js\/[^"]+"><\/script>/g, '');
out = out.replace(/<\/body>/, '<script>\n' + combined + '\n</script>\n</body>');

const outPath = path.join(root, '..', 'outputs', 'mobile-ide.html');
fs.mkdirSync(path.dirname(outPath), { recursive: true });
fs.writeFileSync(outPath, out);
console.log('Wrote', outPath, 'size', out.length);
