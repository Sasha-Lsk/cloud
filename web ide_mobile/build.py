#!/usr/bin/env python3
"""Bundle mobile-ide into a single HTML file in outputs/mobile-ide.html"""
import os, re, base64

root = os.path.dirname(os.path.abspath(__file__))

def read(p): return open(os.path.join(root, p), 'r', encoding='utf-8').read()
def read_bin(p): return open(os.path.join(root, p), 'rb').read()

html = read('index.html')
css = read('css/main.css')
files = [
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
]

notify_mp3_path = os.path.join(root, 'css', 'notify.mp3')
notify_snippet = ''
if os.path.exists(notify_mp3_path):
    b64 = base64.b64encode(read_bin('css/notify.mp3')).decode('ascii')
    notify_snippet = (
        "/* ===== embedded css/notify.mp3 ===== */\n"
        "window.__NOTIFY_MP3_DATAURL__ = 'data:audio/mpeg;base64," + b64 + "';\n"
    )

combined = notify_snippet
for f in files:
    combined += f"\n/* ===== {f} ===== */\n" + read(f) + '\n'

out = re.sub(r'<link rel="stylesheet" href="css/main\.css"\s*/?>', '<style>\n' + css + '\n</style>', html)
out = re.sub(r'\n\s*<script src="js/[^"]+"></script>', '', out)
out = out.replace('</body>', '<script>\n' + combined + '\n</script>\n</body>')

outpath = os.path.abspath(os.path.join(root, '..', 'outputs', 'mobile-ide.html'))
os.makedirs(os.path.dirname(outpath), exist_ok=True)
with open(outpath, 'w', encoding='utf-8') as f:
    f.write(out)
print('Wrote', outpath, 'size', len(out))
