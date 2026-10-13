// tools/cdp-eval.mjs — выполнить JS в живом WebView приложения через CDP.
// Использование: node tools/cdp-eval.mjs 'выражение'
// (сам пробрасывает сокет: adb forward tcp:9333 localabstract:webview_devtools_remote_<pid>)
const expr = process.argv[2];
if (!expr) { console.error('укажите выражение'); process.exit(1); }

import { spawnSync } from 'child_process';

const ADB = process.env.ADB || 'tools/android-sdk/platform-tools/adb.exe';
const run = (...args) => {
    const r = spawnSync(ADB, args, { encoding: 'utf8' });
    if (r.status !== 0) throw new Error('adb ' + args.join(' ') + ' → ' + r.stderr);
    return r.stdout.trim();
};

// pid процесса приложения → имя devtools-сокета
// (на старых Android нет pidof — ошибка уходит в stdout, фолбэк на ps)
const pidofRaw = run('shell', 'pidof', 'com.ianshkondin.mat239');
const pid = /^\d+$/.test(pidofRaw) ? pidofRaw :
    run('shell', 'ps').split('\n').map(l => l.trim()).filter(l => l.endsWith('com.ianshkondin.mat239'))
        .map(l => l.split(/\s+/)[1]).find(p => /^\d+$/.test(p));
if (!pid) { console.error('приложение не запущено'); process.exit(1); }
run('forward', 'tcp:9333', `localabstract:webview_devtools_remote_${pid}`);

// Список таргетов берём curl'ом: встроенный fetch (undici) старый
// devtools-сервер Chromium 37 (Android 5) молча рвёт соединение
const CURL = process.env.CURL || 'C:/Windows/System32/curl.exe';
const listJson = spawnSync(CURL, ['-s', '--max-time', '5', 'http://127.0.0.1:9333/json/list'],
    { encoding: 'utf8' });
const list = JSON.parse(listJson.stdout);
const page = list.find(p => p.type === 'page');
if (!page) { console.error('страница не найдена'); process.exit(1); }

const ws = new WebSocket(page.webSocketDebuggerUrl);
const timeout = setTimeout(() => { console.error('timeout'); process.exit(2); }, 20000);

ws.onopen = () => {
    ws.send(JSON.stringify({
        id: 1, method: 'Runtime.evaluate',
        params: { expression: expr, returnByValue: true, awaitPromise: true }
    }));
};
ws.onmessage = (ev) => {
    const m = JSON.parse(ev.data);
    if (m.id === 1) {
        clearTimeout(timeout);
        if (m.result && m.result.exceptionDetails) {
            const d = m.result.exceptionDetails;
            console.log('EXCEPTION:', d.text, d.exception && d.exception.description ? d.exception.description.slice(0, 800) : '');
        } else if (m.result && m.result.result) {
            const r = m.result.result;
            console.log(r.type === 'string' ? r.value : JSON.stringify(r.value, null, 1));
        } else {
            console.log(JSON.stringify(m.result));
        }
        ws.close();
        process.exit(0);
    }
};
ws.onerror = () => { console.error('ws error'); process.exit(3); };
