package com.ianshkondin.mat239;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Единственная Activity: WebView с офлайн-копией тренажёра
 * (android/assets/www/, собирается tools/build-www.sh) и Java-мостом
 * «T239»: сохранение экспорта прогресса в Downloads и синхронизация
 * цвета строки состояния с темой вёрстки («Тёплая бумага» / «Вечер»).
 *
 * Сеть приложению не нужна вовсе — разрешений в манифесте нет.
 */
public class MainActivity extends Activity {

    private static final String START_URL = "file:///android_asset/www/index.html";
    private static final int REQ_FILE_CHOOSER = 42;
    private static final int REQ_NOTIFY_PERMISSION = 41;

    /** Строка состояния под фон вёрстки: светлая тема #F5F1E8, тёмная #211E18. */
    private static final int BAR_LIGHT = 0xFFF5F1E8;
    private static final int BAR_DARK = 0xFF211E18;

    private WebView web;
    private ValueCallback<Uri[]> filePathCallback;
    private long lastBackAt;
    // Вид, который нужно открыть после загрузки страницы: тап по пушу
    // напоминания. null — обычный запуск (вкладка восстановится из localStorage).
    private String pendingOpenView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setBarTheme(false);

        // Devtools-сокет WebView — только в debuggable-сборках (T239_DEBUG=1
        // в tools/build-apk.sh): удобно гонять проверки страницы через CDP
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);      // localStorage — хранилище прогресса
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);     // import прогресса через системный выбор файла
        // Без этого WebView игнорирует <meta viewport> и рендерит легаси-ширину
        // 980px, ужатую зумом, — «десктоп» на телефоне. Широкая вьюпа +
        // обзорный зум = мобильная вёрстка сайта как есть.
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                // Обвязка, не трогающая код сайта: перехват экспорта
                // (клик по <a download> с blob:-URL в WebView не работает)
                // и синхронизация темы строки состояния.
                view.evaluateJavascript(INJECT, null);
                // Тап по пушу напоминания несёт extra «view»: страница грузится
                // асинхронно, холодный старт живёт в onCreate дольше загрузки —
                // дожидаемся onPageFinished и зовём хук (INJECT уже определил
                // его выше по порядку выполнения).
                if (pendingOpenView != null) {
                    String v = pendingOpenView;
                    pendingOpenView = null;
                    view.evaluateJavascript(
                            "if (window.__t239OpenView) window.__t239OpenView('" + v + "');", null);
                }
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            // Импорт прогресса: <input type="file"> в WebView не работает
            // без хром-клиента — открываем системный выбор файла.
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = callback;
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                try {
                    startActivityForResult(Intent.createChooser(intent, "Выберите файл"), REQ_FILE_CHOOSER);
                } catch (android.content.ActivityNotFoundException e) {
                    filePathCallback = null;
                    return false;
                }
                return true;
            }

            // JS alert()/confirm(): показываем свои модальные окна — дефолтный
            // вид WebView («Подтвердите действие на file://») сжимает текст
            // и не выглядит частью приложения.
            @Override
            public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                showJsDialog(message, false, false, null, result);
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
                showJsDialog(message, true, false, null, result);
                return true;
            }

            @Override
            public boolean onJsPrompt(WebView view, String url, String message,
                                      String defaultValue, JsPromptResult result) {
                showJsDialog(message, true, true, defaultValue, result);
                return true;
            }
        });

        web.addJavascriptInterface(new Bridge(), "T239");
        setContentView(web);
        web.loadUrl(START_URL);

        // Тап по пушу напоминания: активность standard-launchMode, CLEAR_TOP
        // из PendingIntent её пересоздаёт — интент читаем в onCreate.
        if ("train".equals(getIntent().getStringExtra("view"))) {
            pendingOpenView = "train";
        }

        // Будильник мог слететь (перезагрузка, чистка) — восстановим из снапшота
        AlarmScheduler.rescheduleFromSnapshot(this);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQ_NOTIFY_PERMISSION) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (!granted) {
                Toast.makeText(this, "Уведомления запрещены — напоминания не покажутся. Включить можно в настройках системы.",
                        Toast.LENGTH_LONG).show();
            }
            return;
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE_CHOOSER) {
            if (filePathCallback != null) {
                Uri[] uris = null;
                if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                    uris = new Uri[]{ data.getData() };
                }
                filePathCallback.onReceiveValue(uris);
                filePathCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    // Тренажёр — SPA без истории навигации: «назад» вылетал бы из приложения
    // с первого случайного тапа. Двойное нажатие в течение 2 секунд — выход.
    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastBackAt < 2000) {
            super.onBackPressed();
        } else {
            lastBackAt = now;
            Toast.makeText(this, "Нажмите «назад» ещё раз, чтобы выйти", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Модальное окно для JS alert()/confirm()/prompt(): setCancelable(false) —
     * закрыть можно только кнопкой (тап мимо и «назад» не проходят), выбор
     * уходит в JsResult, JS-поток страницы ждёт его синхронно.
     */
    private void showJsDialog(String message, boolean cancellable, boolean withInput,
                              String preset, final JsResult result) {
        final EditText input = new EditText(this);
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.app_name))
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton("ОК", (dialog, which) -> {
                    if (withInput && result instanceof JsPromptResult) {
                        ((JsPromptResult) result).confirm(input.getText().toString());
                    } else {
                        result.confirm();
                    }
                });
        if (withInput) {
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            float dp = getResources().getDisplayMetrics().density;
            box.setPadding(Math.round(20 * dp), Math.round(4 * dp), Math.round(20 * dp), 0);
            input.setText(preset == null ? "" : preset);
            box.addView(input);
            b.setView(box);
        }
        if (cancellable) {
            b.setNegativeButton("Отмена", (dialog, which) -> result.cancel());
        }
        b.show();
    }

    private void setBarTheme(final boolean dark) {
        runOnUiThread(() -> {
            getWindow().setStatusBarColor(dark ? BAR_DARK : BAR_LIGHT);
            if (Build.VERSION.SDK_INT >= 23) {
                View v = getWindow().getDecorView();
                int flags = v.getSystemUiVisibility();
                if (dark) flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                else flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                v.setSystemUiVisibility(flags);
            }
        });
    }

    /**
     * Обвязка страницы. Сайт не меняем: экспорт ловим на захватывающей фазе
     * клика по ссылке с атрибутом download (в WebView скачивание blob:-URL
     * не происходит само), тему отдаём в Java через MutationObserver —
     * сайт переключает data-theme на <html> кнопкой «☾ Вечер»; в футер и
     * вкладку «Цель» добавляем свои блоки (кнопка PDF-пособия и настройки
     * напоминаний), а прогресс-состояние сбрасываем в Java-снапшот при
     * каждом сохранении (патч Storage.setItem — сайт пишет p239_state_v2).
     * Одинарные кавычки в HTML/JS — чтобы не экранировать двойные тут.
     */
    private static final String INJECT =
        "(function(){\n" +
        "  /* Полифилы для старых WebView (Android 5–6 с необновлённым Chromium 37–40):\n" +
        "     closest появился в Chrome 41 — его использует и сам сайт (клики по\n" +
        "     вкладкам), поэтому восстанавливаем до первого взаимодействия. */\n" +
        "  if (window.Element && !Element.prototype.closest) {\n" +
        "    Element.prototype.closest = function(s){\n" +
        "      var el = this, m = el.matches || el.webkitMatchesSelector ||\n" +
        "               el.msMatchesSelector || el.mozMatchesSelector;\n" +
        "      if (!m) return null;\n" +
        "      while (el && el.nodeType === 1) {\n" +
        "        if (m.call(el, s)) return el;\n" +
        "        el = el.parentElement;\n" +
        "      }\n" +
        "      return null;\n" +
        "    };\n" +
        "  }\n" +
        "\n" +
        "  /* Карточки занятий.\n" +
        "     «План занятий»: строки (дата + трек + счётчик + статус + кнопка)\n" +
        "     не влезают в телефон — перенос до 780px (max-width .quiz-shell,\n" +
        "     покрывает планшет/ландшафт и крупный системный шрифт), кнопка\n" +
        "     второй строкой в правый нижний угол.\n" +
        "     «Ближайшие занятия» (в тренажёре): карточка-сетка — дата и\n" +
        "     мета-строка (счётчик + статус) слева, кнопка «заниматься»\n" +
        "     (primary, как «тренировать» в плане) справа по центру.\n" +
        "     DOM строк слегка перестраивается: свободные SPAN'ы (счётчик,\n" +
        "     статус) собираются в один .t239-meta. */\n" +
        "  if (!document.getElementById('t239-style')) {\n" +
        "    var st = document.createElement('style');\n" +
        "    st.id = 't239-style';\n" +
        "    st.textContent =\n" +
        "      '@media (max-width: 780px) {' +\n" +
        "        '.plan-row { flex-wrap: wrap; row-gap: 6px; }' +\n" +
        "        '.plan-date { min-width: 0; }' +\n" +
        "        '.plan-row > .btn { margin-left: auto; align-self: flex-end; }' +\n" +
        "      '}' +\n" +
        "      /* карточка «Ближайшие занятия»: сетка вместо флекс-строки */\n" +
        "      '.sess-list .plan-row {' +\n" +
        "        'display: grid;' +\n" +
        "        'grid-template-columns: minmax(0, 1fr) auto;' +\n" +
        "        'grid-template-areas: \"date btn\" \"meta btn\";' +\n" +
        "        'align-items: center; column-gap: 12px; row-gap: 4px;' +\n" +
        "      '}' +\n" +
        "      '.sess-list .plan-date { grid-area: date; min-width: 0; }' +\n" +
        "      '.sess-list .plan-row > .btn {' +\n" +
        "        'grid-area: btn; align-self: center; justify-self: end;' +\n" +
        "        'margin-left: 0;' +\n" +
        "        'background: var(--accent); border-color: var(--accent);' +\n" +
        "        'color: #fdfcf8; font-weight: 600;' +\n" +
        "      '}' +\n" +
        "      '.t239-meta {' +\n" +
        "        'grid-area: meta; display: flex; align-items: center;' +\n" +
        "        'gap: 10px; min-width: 0; color: var(--muted); font-size: 13px;' +\n" +
        "      '}' +\n" +
        "      '.t239-meta .grow { display: none; }';\n" +
        "    document.head.appendChild(st);\n" +
        "  }\n" +
        "  function polishSessRows(){\n" +
        "    var rows = document.querySelectorAll('.sess-list .plan-row');\n" +
        "    for (var i = 0; i < rows.length; i++) {\n" +
        "      var r = rows[i];\n" +
        "      if (r.getAttribute('data-t239')) continue;\n" +
        "      r.setAttribute('data-t239', '1');\n" +
        "      var spans = [];\n" +
        "      for (var j = 0; j < r.children.length; j++) {\n" +
        "        if (r.children[j].tagName === 'SPAN') spans.push(r.children[j]);\n" +
        "      }\n" +
        "      if (!spans.length) continue;\n" +
        "      var meta = document.createElement('div');\n" +
        "      meta.className = 't239-meta';\n" +
        "      for (var k = 0; k < spans.length; k++) meta.appendChild(spans[k]);\n" +
        "      r.appendChild(meta);\n" +
        "    }\n" +
        "  }\n" +
        "  /* Сайт перерисовывает вкладку тренажёра — карточки собираем заново */\n" +
        "  var trainPane = document.getElementById('pane-train');\n" +
        "  if (trainPane) {\n" +
        "    new MutationObserver(function(){ setTimeout(polishSessRows, 0); })\n" +
        "        .observe(trainPane, { childList: true, subtree: true });\n" +
        "    polishSessRows();\n" +
        "  }\n" +
        "\n" +
        "  function reportTheme(){\n" +
        "    var dark = document.documentElement.dataset.theme === 'dark';\n" +
        "    if (window.T239) window.T239.setStatusBarTheme(dark);\n" +
        "  }\n" +
        "  new MutationObserver(reportTheme).observe(document.documentElement,\n" +
        "      { attributes: true, attributeFilter: ['data-theme'] });\n" +
        "  reportTheme();\n" +
        "\n" +
        "  document.addEventListener('click', function(e){\n" +
        "    var a = e.target && e.target.closest ? e.target.closest('a[download]') : null;\n" +
        "    if (!a) return;\n" +
        "    e.preventDefault(); e.stopPropagation();\n" +
        "    var name = a.getAttribute('download') || 'progress.json';\n" +
        "    /* blob:-URL читаем XHR'ом — fetch есть только с Chrome 42 */\n" +
        "    try {\n" +
        "      var xhr = new XMLHttpRequest();\n" +
        "      xhr.open('GET', a.href, true);\n" +
        "      xhr.onload = function(){\n" +
        "        if (window.T239) window.T239.saveFile(name, xhr.responseText);\n" +
        "      };\n" +
        "      xhr.onerror = function(){\n" +
        "        if (window.T239) window.T239.toast('Не удалось сохранить файл');\n" +
        "      };\n" +
        "      xhr.send();\n" +
        "    } catch (err) {\n" +
        "      if (window.T239) window.T239.toast('Не удалось сохранить файл');\n" +
        "    }\n" +
        "  }, true);\n" +
        "\n" +
        "  /* Подвал сайта: техническая справка о проверке ответов и данных —\n" +
        "     в приложении не нужна (сайт скрипты уже отработали, элементы\n" +
        "     статичные, ре-рендера нет) */\n" +
        "  var f = document.querySelector('.app-f');\n" +
        "  if (f) f.remove();\n" +
        "\n" +
        "  /* ---------- Напоминания о занятиях ---------- */\n" +
        "  function pad2(x){ return (x < 10 ? '0' : '') + x; }\n" +
        "  function readNotify(){\n" +
        "    var n = {};\n" +
        "    try { n = JSON.parse(localStorage.getItem('p239_notify')) || {}; } catch (e) {}\n" +
        "    return { enabled: !!n.enabled, hh: (n.hh >= 0 && n.hh <= 23) ? n.hh : 17,\n" +
        "             mm: (n.mm >= 0 && n.mm <= 59) ? n.mm : 0 };\n" +
        "  }\n" +
        "  function pushSnapshot(){\n" +
        "    try {\n" +
        "      var S = JSON.parse(localStorage.getItem('p239_state_v2') || 'null') || {};\n" +
        "      var n = readNotify();\n" +
        "      var sessions = {};\n" +
        "      (S.plan || []).forEach(function(ses){\n" +
        "        var left = 0;\n" +
        "        (ses.ids || []).forEach(function(id){ if (!S.solved || !S.solved[id]) left++; });\n" +
        "        sessions[ses.d] = { left: left, total: (ses.ids || []).length };\n" +
        "      });\n" +
        "      var snap = {\n" +
        "        notify: n,\n" +
        "        sessions: sessions,\n" +
        "        solved: S.solved ? Object.keys(S.solved).length : 0,\n" +
        "        allTotal: window.PDF239 ? window.PDF239.problems.length : 1036,\n" +
        "        goal: S.goal || ''\n" +
        "      };\n" +
        "      if (window.T239 && window.T239.persistSnapshot) window.T239.persistSnapshot(JSON.stringify(snap));\n" +
        "    } catch (e) {}\n" +
        "  }\n" +
        "  var snapT = null;\n" +
        "  function scheduleSnap(){\n" +
        "    clearTimeout(snapT);\n" +
        "    snapT = setTimeout(pushSnapshot, 250);\n" +
        "  }\n" +
        "  // Сайт пишет состояние через localStorage.setItem — ловим запись\n" +
        "  // p239_state_v2 и обновляем снапшот (для NotifyReceiver/BootReceiver)\n" +
        "  if (window.Storage && Storage.prototype && Storage.prototype.setItem) {\n" +
        "    var origSetItem = Storage.prototype.setItem;\n" +
        "    Storage.prototype.setItem = function(k, v){\n" +
        "      origSetItem.call(this, k, v);\n" +
        "      if (k === 'p239_state_v2') scheduleSnap();\n" +
        "    };\n" +
        "  }\n" +
        "  function updateNotifyHint(){\n" +
        "    var h = document.getElementById('notify-hint');\n" +
        "    if (!h) return;\n" +
        "    var n = readNotify();\n" +
        "    var S = {};\n" +
        "    try { S = JSON.parse(localStorage.getItem('p239_state_v2') || 'null') || {}; } catch (e) {}\n" +
        "    if (!n.enabled) h.textContent = 'Напоминания выключены.';\n" +
        "    else if (!(S.plan || []).length) h.textContent = 'План ещё не составлен — напоминания придут, когда появятся занятия.';\n" +
        "    else h.textContent = 'Напомним в ' + pad2(n.hh) + ':' + pad2(n.mm) + ' по дням занятий (занятие решено — не побеспокоим).';\n" +
        "  }\n" +
        "  function onNotifyChange(){\n" +
        "    var t = document.getElementById('notify-time');\n" +
        "    var c = document.getElementById('notify-on');\n" +
        "    var p = (t.value || '17:00').split(':');\n" +
        "    var n = { enabled: c.checked, hh: Math.max(0, Math.min(23, +p[0] || 17)),\n" +
        "              mm: Math.max(0, Math.min(59, +p[1] || 0)) };\n" +
        "    var orig = readNotify();\n" +
        "    localStorage.setItem('p239_notify', JSON.stringify(n));\n" +
        "    if (window.T239) {\n" +
        "      if (n.enabled && !orig.enabled) window.T239.requestNotificationsPermission();\n" +
        "      window.T239.setNotifications(n.enabled, n.hh, n.mm);\n" +
        "    }\n" +
        "    pushSnapshot();\n" +
        "    updateNotifyHint();\n" +
        "  }\n" +
        "  function injectNotifyCard(){\n" +
        "    var pane = document.getElementById('pane-goal');\n" +
        "    if (!pane || document.getElementById('notify-card')) return;\n" +
        "    if (!pane.querySelector('.card')) return; // вкладка ещё не отрендерена\n" +
        "    var n = readNotify();\n" +
        "    var card = document.createElement('div');\n" +
        "    card.className = 'card';\n" +
        "    card.id = 'notify-card';\n" +
        "    card.innerHTML =\n" +
        "      '<h2 class=card-t>Напоминания</h2>' +\n" +
        "      '<div class=card-s>Раз в день в выбранное время придёт уведомление — если на этот день есть занятие по плану с нерешёнными задачами.</div>' +\n" +
        "      '<label class=fld>Время напоминания</label>' +\n" +
        "      '<div style=display:flex;align-items:center;gap:14px;flex-wrap:wrap>' +\n" +
        "        '<input type=time id=notify-time value=' + pad2(n.hh) + ':' + pad2(n.mm) +\n" +
        "          ' style=font-size:16px;padding:8px 10px;border:1px solid var(--line);border-radius:8px;background:var(--card);color:var(--ink)>' +\n" +
        "        '<label style=display:flex;align-items:center;gap:6px;cursor:pointer>' +\n" +
        "          '<input type=checkbox id=notify-on' + (n.enabled ? ' checked' : '') + '> напоминать</label>' +\n" +
        "      '</div>' +\n" +
        "      '<div class=card-s style=margin-top:10px id=notify-hint></div>';\n" +
        "    pane.appendChild(card);\n" +
        "    card.querySelector('#notify-time').addEventListener('change', onNotifyChange);\n" +
        "    card.querySelector('#notify-on').addEventListener('change', onNotifyChange);\n" +
        "    updateNotifyHint();\n" +
        "  }\n" +
        "  // Сайт перерисовывает вкладку «Цель» (innerHTML) — карточку возвращаем\n" +
        "  var goalPane = document.getElementById('pane-goal');\n" +
        "  if (goalPane) {\n" +
        "    new MutationObserver(function(){ setTimeout(injectNotifyCard, 0); })\n" +
        "        .observe(goalPane, { childList: true });\n" +
        "    injectNotifyCard();\n" +
        "  }\n" +
        "\n" +
        "  /* Тап по пушу напоминания (extra «view» из NotifyReceiver → Java →\n" +
        "     сюда после загрузки страницы). Вкладка тренажёра + клик по первой\n" +
        "     карточке занятия: сайт рендерит «Сегодняшнее занятие» первым,\n" +
        "     за неимением — «Следующее»; идём по DOM, не трогая внутренности\n" +
        "     сайта (trainStart живёт в его IIFE). */\n" +
        "  window.__t239OpenView = function(view){\n" +
        "    try {\n" +
        "      var tab = document.querySelector('[data-tab=' + view + ']');\n" +
        "      if (tab) tab.click();\n" +
        "      if (view === 'train') {\n" +
        "        var btn = document.querySelector('#pane-train [data-act=open-sess]');\n" +
        "        if (btn) btn.click();\n" +
        "      }\n" +
        "    } catch (e) {}\n" +
        "  };\n" +
        "\n" +
        "  pushSnapshot();\n" +
        "})();";

    // ==================== Мост T239 ====================

    private class Bridge {

        /**
         * Экспорт прогресса (a[download] с blob:-URL). Файл уходит в Downloads
         * через MediaStore (API 29+) или в каталог приложения (24–28).
         */
        @JavascriptInterface
        public boolean saveFile(String name, String content) {
            try {
                byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
                    cv.put(MediaStore.Downloads.MIME_TYPE, "application/json");
                    Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                    if (uri == null) return false;
                    try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                        if (os == null) return false;
                        os.write(bytes);
                    }
                    toast("Сохранено в Downloads/" + name);
                } else {
                    File dir = getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS);
                    if (dir == null) dir = getFilesDir();
                    //noinspection ResultOfMethodCallIgnored
                    dir.mkdirs();
                    File file = new File(dir, name);
                    try (FileOutputStream fo = new FileOutputStream(file)) {
                        fo.write(bytes);
                    }
                    toast("Сохранено: " + file.getAbsolutePath());
                }
                return true;
            } catch (Exception e) {
                toast("Не удалось сохранить файл: " + e.getMessage());
                return false;
            }
        }

        /**
         * Снапшот плана и настроек: localStorage из Java не читается,
         * поэтому JS сам сбрасывает сюда всё, что нужно NotifyReceiver
         * (напоминание о занятии) и BootReceiver. Запись атомарная
         * (tmp + rename). Вызывается при загрузке страницы, при каждом
         * сохранении прогресса и при смене настроек напоминаний.
         */
        @JavascriptInterface
        public boolean persistSnapshot(String json) {
            try {
                File tmp = new File(getFilesDir(), AlarmScheduler.SNAPSHOT_FILE + ".tmp");
                try (FileOutputStream fo = new FileOutputStream(tmp)) {
                    fo.write(json.getBytes(StandardCharsets.UTF_8));
                }
                //noinspection ResultOfMethodCallIgnored
                tmp.renameTo(new File(getFilesDir(), AlarmScheduler.SNAPSHOT_FILE));
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        /** Включить/выключить напоминание и переставить будильник на новое время. */
        @JavascriptInterface
        public void setNotifications(boolean enabled, int hh, int mm) {
            if (enabled) AlarmScheduler.schedule(MainActivity.this, hh, mm);
            else AlarmScheduler.cancel(MainActivity.this);
        }

        /** Разрешение на уведомления (Android 13+) — запрашивается в момент включения. */
        @JavascriptInterface
        public void requestNotificationsPermission() {
            runOnUiThread(() -> {
                if (Build.VERSION.SDK_INT >= 33
                        && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{ Manifest.permission.POST_NOTIFICATIONS }, REQ_NOTIFY_PERMISSION);
                }
            });
        }

        /** Тема строки состояния синхронно с кнопкой «☾ Вечер» на странице. */
        @JavascriptInterface
        public void setStatusBarTheme(final boolean dark) {
            setBarTheme(dark);
        }

        @JavascriptInterface
        public void toast(String msg) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show());
        }

        @JavascriptInterface
        public String appVersion() {
            return getString(R.string.app_version);
        }
    }
}
