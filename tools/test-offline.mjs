// tools/test-offline.mjs — смоук-тест автономного бандла (android/assets/www).
// Запуск: node tools/test-offline.mjs
//
// Паттерн: jsdom runScripts:'outside-only' + последовательный window.eval
// скриптов страницы (239.js → answers.js → инлайн-скрипт index.html).
// Offline-режим жёсткий: fetch/XHR кидают исключение — тест падает,
// если страница пытается выйти в сеть.

import { JSDOM, VirtualConsole } from 'jsdom';
import { readFileSync, existsSync } from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const WWW = path.join(ROOT, 'android', 'assets', 'www');

let passed = 0, failed = 0;
function ok(cond, label) {
    if (cond) { passed++; console.log('  ✓ ' + label); }
    else { failed++; console.log('  ✗ FAIL: ' + label); }
}

if (!existsSync(path.join(WWW, 'index.html'))) {
    console.error('Нет android/assets/www — сначала tools/build-www.sh');
    process.exit(2);
}

// ---------- Сборка окружения ----------

const html = readFileSync(path.join(WWW, 'index.html'), 'utf8');

const vc = new VirtualConsole();
const jsErrors = [];
vc.on('jsdomError', (e) => jsErrors.push(String(e)));

const dom = new JSDOM(html, {
    url: 'https://t239.local/',
    runScripts: 'outside-only',
    pretendToBeVisual: true,
    virtualConsole: vc,
});

const { window } = dom;

// Никакой сети: fetch брошен — любое обращение уронит скрипт и тест
window.fetch = () => Promise.reject(new Error('сети нет: офлайн-бандл'));
const origXhr = window.XMLHttpRequest;
window.XMLHttpRequest = function () {
    throw new Error('сети нет: офлайн-бандл (XHR)');
};

// Стабы моста T239 (в APK его ставит MainActivity)
const bridgeCalls = { saveFile: [], setStatusBarTheme: [] };
window.T239 = {
    saveFile: (name, content) => { bridgeCalls.saveFile.push({ name, content }); return true; },
    setStatusBarTheme: (dark) => { bridgeCalls.setStatusBarTheme.push(dark); },
    toast: () => {},
    appVersion: () => 'test',
};

// ---------- Прогон бандла ----------

function evalScript(file, code, label) {
    try {
        window.eval(code);
        passed++; console.log('  ✓ ' + label);
    } catch (e) {
        failed++; console.log('  ✗ FAIL: ' + label + ' — ' + e.message);
    }
}

console.log('\n== Загрузка скриптов');
evalScript('239.js', readFileSync(path.join(WWW, '239.js'), 'utf8'), '239.js исполняется');
evalScript('answers.js', readFileSync(path.join(WWW, 'answers.js'), 'utf8'), 'answers.js исполняется');

// Инлайн-скрипт index.html (последний <script> без src)
const inline = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].map(m => m[1]).join('\n');
evalScript('inline', inline, 'инлайн-скрипт приложения исполняется');

// ---------- Проверки ----------

console.log('\n== Разметка и данные');
const doc = window.document;
ok(window.PDF239 && Array.isArray(window.PDF239.sections), 'данные PDF239 загружены');
ok(doc.querySelectorAll('#tabs [data-tab]').length === 4, 'четыре вкладки: Тренажёр/План/Цель/Задачи');
ok(doc.getElementById('p-total').textContent === '1036', 'счётчик «Решено … из 1036»');
ok(doc.getElementById('f-auto').textContent !== '…' && Number(doc.getElementById('f-auto').textContent) > 0,
    'футер: число автопроверяемых задач посчитано');
ok(doc.querySelector('#pane-train .cta-box') !== null, 'без цели тренажёр показывает карточку «Начнём подготовку»');

console.log('\n== Прогресс и localStorage');
const saved = window.localStorage.getItem('p239_state_v2');
ok(!!saved, 'прогресс сохранён в localStorage (p239_state_v2)');
ok(jsErrors.length === 0, 'ошибок jsdom нет' + (jsErrors.length ? ': ' + jsErrors[0] : ''));

console.log('\n== Переключение вкладок');
const tasksTab = doc.querySelector('[data-tab="tasks"]');
tasksTab.dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
ok(!doc.getElementById('pane-tasks').hidden, 'вкладка «Задачи» открывается');
ok(doc.querySelectorAll('#pane-tasks *').length > 10, 'каталог задач отрендерился');
const goalTab = doc.querySelector('[data-tab="goal"]');
goalTab.dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
ok(!doc.getElementById('pane-goal').hidden, 'вкладка «Цель» открывается');

console.log('\n== Тёмная тема');
const themeBtn = doc.getElementById('theme-btn');
themeBtn.dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
ok(doc.documentElement.dataset.theme === 'dark', 'кнопка «☾ Вечер» переключает data-theme');

console.log('\n== Картинки задач');
// Рисунки появляются в DOM только при показе задач с изображениями,
// поэтому проверяем ссылки 239.js → файлы бандла напрямую
const data239 = readFileSync(path.join(WWW, '239.js'), 'utf8');
const refs = [...new Set([...data239.matchAll(/"(img\/[^"]+)"/g)].map(m => m[1]))];
const missingImgs = refs.filter(s => !existsSync(path.join(WWW, s)));
ok(refs.length > 0, '239.js ссылается на картинки задач (' + refs.length + ' шт.)');
ok(missingImgs.length === 0, 'все картинки бандла на месте' + (missingImgs.length ? ': ' + missingImgs.join(', ') : ''));

// ---------- Итог ----------

console.log('\n' + (failed === 0
    ? `OK: ${passed} проверок пройдено, бандл автономен`
    : `FAIL: ${failed} из ${passed + failed}`));
process.exit(failed === 0 ? 0 : 1);
