'use strict';
const $ = (id) => document.getElementById(id);
const $$ = (sel) => Array.from(document.querySelectorAll(sel));
const A = window.FRX || mockBridge();          // мост приложения (MainActivity.Bridge); в обычном браузере — заглушка

let S = {};                 // state() — статус FRX, задачи, разрешения
let logSeq = -1, openedPort = null;
let tab = load('tab') || 'home';
let logOpen = false;
let modsSig = '', modsOpen = '', filter = 'all';
let M = { items: [], hidden: [] };

const TASKS = { prepare: 'Подготовка', download: 'Скачиваю FRX', login: 'Вход в Telegram', reinstall: 'Переустановка FRX',
  archive: 'Установка из архива', backup: 'Резервная копия', restore: 'Восстановление из копии' };
const STATUS = { running: 'Работает', starting: 'Запускается…', restarting: 'Перезапуск…', stopping: 'Останавливается…',
  stopped: 'Остановлен', error: 'Ошибка' };

// ------------------------------------------------------------------ мелочи

function call(name, ...args) {
  try { return A[name](...args); } catch (e) { toast('Ошибка: ' + e.message); return undefined; }
}
function json(name, ...args) {
  try { return JSON.parse(A[name](...args)); } catch (e) { return null; }
}
function load(k) { try { return sessionStorage.getItem('frx_' + k); } catch (e) { return null; } }
function save(k, v) { try { sessionStorage.setItem('frx_' + k, v); } catch (e) { /* без хранилища — не страшно */ } }

function toast(text) {
  const t = $('toast');
  t.textContent = text; t.classList.add('on');
  clearTimeout(toast.t); toast.t = setTimeout(() => t.classList.remove('on'), 2400);
}
window.toast = toast;

function plural(n, one, few, many) {
  const a = Math.abs(n) % 100, b = a % 10;
  return a > 10 && a < 20 ? many : b === 1 ? one : b > 1 && b < 5 ? few : many;
}

function ago(ms) {
  let s = Math.max(0, Math.floor((Date.now() - ms) / 1000));
  const d = Math.floor(s / 86400); s %= 86400;
  const h = Math.floor(s / 3600); s %= 3600;
  const m = Math.floor(s / 60);
  if (d) return `${d} д ${h} ч`;
  if (h) return `${h} ч ${m} мин`;
  if (m) return `${m} мин`;
  return 'меньше минуты';
}

function when(ms) {
  const d = new Date(ms), now = new Date();
  const hm = d.toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' });
  if (d.toDateString() === now.toDateString()) return 'сегодня в ' + hm;
  const y = new Date(now); y.setDate(now.getDate() - 1);
  if (d.toDateString() === y.toDateString()) return 'вчера в ' + hm;
  return d.toLocaleDateString('ru-RU', { day: 'numeric', month: 'long' }) + ' в ' + hm;
}

function size(b) {
  if (b < 1024 * 1024) return Math.max(0, Math.round(b / 1024)) + ' КБ';
  return (b / 1024 / 1024).toFixed(b < 10 * 1024 * 1024 ? 1 : 0).replace('.', ',') + ' МБ';
}

function esc(s) {
  return String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

function taskTitle(t) {
  return (TASKS[t.name] || t.name) + (t.state === 'failed' ? ' — не получилось' : t.state === 'done' ? ' — готово' : '');
}

// ------------------------------------------------------------------ общий цикл

function refresh() {
  const st = json('state');
  if (!st) return;
  S = st;
  const t = S.task || {};
  const loginOpen = t.name === 'login' && t.state === 'running';
  const setup = !(S.frxInstalled && S.loggedIn) || loginOpen;

  $('setup').hidden = !setup;
  $('tabs').hidden = setup || logOpen;
  document.body.classList.toggle('no-tabs', setup || logOpen);
  $('p-log').hidden = setup || !logOpen;
  for (const p of ['home', 'mods', 'set']) $('p-' + p).hidden = setup || logOpen || tab !== p;
  $$('[data-tab]').forEach((b) => b.classList.toggle('on', b.dataset.tab === tab));
  $$('[data-foot]').forEach((f) => (f.textContent = `APK ${S.app} · Python ${S.python}` + (S.frxVersion ? ` · FRX ${S.frxVersion}` : '')));
  $$('[data-opt]').forEach((i) => (i.checked = !!S[i.dataset.opt]));

  if (setup) return renderSetup(t);
  renderTasks(t);
  if (logOpen) return renderLog();
  if (tab === 'home') renderHome();
  else if (tab === 'mods') renderMods();
  else renderSettings();
}

function showTab(name) {
  if (name === tab && !logOpen) { window.scrollTo({ top: 0, behavior: 'smooth' }); return; }
  tab = name; logOpen = false; save('tab', name);
  if (name === 'mods') { modsSig = ''; call('modulesRefresh', false); }
  if (name === 'set') settingsSig = '';
  refresh();
  window.scrollTo(0, 0);
}

/** «Назад» Android: журнал → главная, другая вкладка → главная; на главной — свернуть (false). */
window.frxBack = () => {
  if (!$('setup').hidden) return false;
  if (logOpen) { logOpen = false; refresh(); return true; }
  if (tab !== 'home') { showTab('home'); return true; }
  return false;
};
window.frxResume = refresh;

// ------------------------------------------------------------------ установка

function stepState(id, done, locked) {
  const el = $(id);
  el.classList.toggle('done', !!done);
  el.classList.toggle('locked', !done && !!locked);
}

function renderSetup(t) {
  const busy = t.state === 'running';
  stepState('s-prepare', S.pythonReady, false);
  stepState('s-download', S.frxInstalled, !S.pythonReady);
  stepState('s-login', S.loggedIn && !(t.name === 'login' && busy), !S.frxInstalled);
  $$('#setup .btn').forEach((b) => (b.disabled = busy));

  const lb = $('b-login');
  if (t.name === 'login' && busy && t.data && t.data.port) {
    lb.textContent = 'Открыть экран входа';
    lb.disabled = false;
    $('s-login').classList.remove('done');
    if (openedPort !== t.data.port) { openedPort = t.data.port; A.openLogin(+t.data.port); }
  } else lb.textContent = 'Войти';

  const box = $('task');
  box.hidden = !t.name || t.name === 'backup' || t.name === 'restore';
  if (box.hidden) return;
  box.classList.toggle('idle', !busy);
  $('task-name').textContent = taskTitle(t);
  $('b-cancel').hidden = !busy || t.name === 'prepare';
  const pr = t.data && t.data.progress;
  $('task-meter').hidden = !(busy && pr);
  if (pr) $('task-meter').firstElementChild.style.width = pr + '%';
  const pre = $('task-log');
  pre.textContent = (t.lines || []).join('\n');
  pre.scrollTop = pre.scrollHeight;
}

/** Задачи на вкладках (переустановка, архив, копия): показываем там, где их запустили. */
function renderTasks(t) {
  const home = ['reinstall', 'archive'], set = ['reinstall', 'archive', 'backup', 'restore'];
  $$('[data-dtask]').forEach((box) => {
    const names = box.closest('#p-home') ? home : set;
    box.hidden = !names.includes(t.name);
    if (box.hidden) return;
    box.classList.toggle('idle', t.state !== 'running');
    box.querySelector('[data-dtask-name]').textContent = taskTitle(t);
    const pre = box.querySelector('[data-dtask-log]');
    pre.textContent = (t.lines || []).slice(-10).join('\n');
    pre.scrollTop = pre.scrollHeight;
  });
}

// ------------------------------------------------------------------ главная

function renderHome() {
  const st = S.status || 'stopped';
  const alive = ['running', 'starting', 'restarting'].includes(st);
  $('dot').className = 'dot ' + st;
  $('st-title').textContent = STATUS[st] || st;
  let sub = '';
  if (st === 'running' && S.startedAt) {
    sub = ago(S.startedAt);
    if (S.modules) sub += ` · ${S.modules} ${plural(S.modules, 'модуль', 'модуля', 'модулей')}`;
  } else if (S.detail) sub = S.detail;
  else if (st === 'stopped' && S.lastExit !== null && S.lastExit !== undefined) sub = 'Код выхода ' + S.lastExit;
  else if (st === 'stopped') sub = 'Нажмите «Запустить»';
  $('st-sub').textContent = sub;

  const main = $('b-main');
  main.textContent = alive ? 'Остановить' : st === 'stopping' ? 'Останавливается…' : 'Запустить';
  main.disabled = st === 'stopping';
  main.onclick = () => { alive ? call('stop') : call('start'); setTimeout(refresh, 150); };
  $('b-restart').disabled = !alive;

  $('care').hidden = S.notifOk && S.batteryOk;
  $('c-notif').hidden = S.notifOk;
  $('c-battery').hidden = S.batteryOk;

  if (S.logSeq !== homeSeq) {
    homeSeq = S.logSeq;
    const last = json('log', 1) || [];
    $('log-last').textContent = last.length ? last[0] : 'Пока пусто';
  }
}
let homeSeq = -1;

// ------------------------------------------------------------------ журнал

function cls(line) {
  if (/^\[app\]/.test(line)) return 'a';
  if (/Traceback|Error|Exception|Ошибка|ошибк|❌|×|не удалось|не встал/i.test(line)) return 'e';
  if (/⚠|Внимание|warning/i.test(line)) return 'w';
  if (/√|✓|запущен|готов/i.test(line)) return 'g';
  return '';
}

function renderLog(force) {
  if (S.logSeq === logSeq && !force) return;
  logSeq = S.logSeq;
  const lines = json('log', 400) || [];
  const pre = $('log');
  const atBottom = pre.scrollHeight - pre.scrollTop - pre.clientHeight < 40;
  pre.textContent = '';
  const frag = document.createDocumentFragment();
  for (const l of lines) {
    const c = cls(l);
    if (c) {
      const s = document.createElement('span'); s.className = c; s.textContent = l + '\n'; frag.appendChild(s);
    } else frag.appendChild(document.createTextNode(l + '\n'));
  }
  if (!lines.length) frag.appendChild(document.createTextNode('Пока пусто — запустите FRX.'));
  pre.appendChild(frag);
  if (atBottom || force) pre.scrollTop = pre.scrollHeight;
}

// ------------------------------------------------------------------ модули

function renderMods() {
  const m = json('modules');
  if (!m) return;
  M = m;
  const q = $('m-q').value.trim().toLowerCase();
  const sig = JSON.stringify([m.items, m.hidden, m.error, m.loading, m.busy, q, filter, modsOpen, S.status]);
  if (sig === modsSig) return;
  modsSig = sig;

  const items = m.items || [];
  const inst = items.filter((x) => x.installed);
  const upd = items.filter((x) => x.update);
  $('m-sub').textContent = items.length ? `из FRX-Modules · ${items.filter((x) => !x.local).length} для Android` : 'из FRX-Modules';
  $('m-ni').textContent = inst.length || '';
  $('m-nu').textContent = upd.length || '';
  $('tab-badge').hidden = !upd.length;
  $$('[data-filter]').forEach((c) => c.classList.toggle('on', c.dataset.filter === filter));
  $('b-mrefresh').disabled = m.loading;

  const note = $('m-note');
  const msg = m.error || (S.status !== 'running' && items.length ? 'FRX не запущен — модули заработают при следующем запуске.' : '');
  note.hidden = !msg;
  note.className = 'note' + (m.error ? ' err' : '');
  note.textContent = msg;

  const list = $('m-list');
  if (!items.length) {
    list.innerHTML = m.loading
      ? '<div class="card list" style="padding:12px 0"><div class="skel"></div><div class="skel"></div><div class="skel"></div></div>'
      : '<div class="card empty">Каталог пока пуст — проверьте интернет и нажмите «Обновить»</div>';
    $('m-hidden').hidden = true;
    return;
  }

  let shown = items.filter((x) => !q || (x.name + ' ' + x.module + ' ' + (x.desc || '')).toLowerCase().includes(q));
  if (filter === 'installed') shown = shown.filter((x) => x.installed);
  if (filter === 'updates') shown = shown.filter((x) => x.update);

  let html = '';
  const sect = (title, arr) => {
    if (!arr.length) return;
    html += `<section class="group"><h3>${title}</h3><div class="card list">${arr.map(modRow).join('')}</div></section>`;
  };
  if (filter === 'all' && !q) {
    sect('Есть обновление', shown.filter((x) => x.update));
    sect('Каталог', shown.filter((x) => !x.update));
  } else sect(filter === 'installed' ? 'Установлены' : filter === 'updates' ? 'Обновления' : 'Найдено', shown);
  if (!html) html = `<div class="card empty">${q ? 'Ничего не нашлось' : filter === 'updates' ? 'Все модули свежие' : 'Пока ничего не установлено'}</div>`;
  list.innerHTML = html;

  const hid = m.hidden || [];
  $('m-hidden').hidden = !hid.length || filter !== 'all';
  $('m-hidden').textContent = hid.length
    ? `${hid.length} ${plural(hid.length, 'модуль скрыт', 'модуля скрыты', 'модулей скрыто')}: ` +
      hid.map((h) => `${h.name} — ${h.reason}`).join('; ')
    : '';
}

function modRow(x) {
  const busy = M.busy === x.module;
  const other = M.busy && !busy;
  const open = modsOpen === x.module;
  const ver = x.update ? `${esc(x.installedVersion || '?')} → ${esc(x.version)}` : esc(x.installedVersion || x.version || '');
  let right;
  if (busy) right = '<span class="spin"></span>';
  else if (x.update) right = `<button class="btn sm pri" data-mod="install" data-m="${esc(x.module)}"${other ? ' disabled' : ''}>Обновить</button>`;
  else if (x.installed) right = '<span class="state">установлен</span>';
  else right = `<button class="btn sm" data-mod="install" data-m="${esc(x.module)}"${other ? ' disabled' : ''}>Установить</button>`;
  let more = '';
  if (open) {
    const bits = [];
    if (x.author) bits.push('Автор: ' + esc(x.author));
    if (x.needs) bits.push('Нужные пакеты FRX доставит сам при первом запуске модуля.');
    more = `<div class="mod-more">${bits.map((b) => `<p>${b}</p>`).join('')}` +
      (x.installed ? `<div class="btns"><button class="btn sm" data-mod="remove" data-m="${esc(x.module)}"${M.busy ? ' disabled' : ''}>Удалить</button></div>` : '') +
      '</div>';
  }
  return `<div class="mod${open ? ' open' : ''}" data-mod="toggle" data-m="${esc(x.module)}" role="button" tabindex="0">` +
    `<span class="tile">${esc((x.name || x.module).slice(0, 1).toUpperCase())}</span>` +
    `<span class="info"><b>${esc(x.name || x.module)}<small>${ver}</small></b><small>${esc(x.desc || '')}</small></span>` +
    right + '</div>' + more;
}

function modAction(kind, module, btn) {
  if (kind === 'toggle') { modsOpen = modsOpen === module ? '' : module; modsSig = ''; return renderMods(); }
  if (kind === 'install') {
    const ok = call('moduleInstall', module);
    if (!ok) return toast('Подождите — идёт другая установка');
    toast(S.status === 'running' ? 'Ставлю — FRX перезапустится' : 'Ставлю — заработает при запуске FRX');
  }
  if (kind === 'remove') {
    if (!arm(btn, 'Точно удалить?')) return;
    const ok = call('moduleRemove', module);
    toast(ok ? (S.status === 'running' ? 'Удалён — FRX перезапустится' : 'Удалён') : 'Не получилось удалить');
    modsOpen = '';
  }
  modsSig = '';
  setTimeout(refresh, 200);
}

// ------------------------------------------------------------------ настройки

let settingsSig = '';
let C = {};

function renderSettings() {
  const c = json('settings');
  if (!c) return;
  C = c;
  $('v-notif').hidden = !S.notifOk; $('b-notif').hidden = S.notifOk;
  $('v-battery').hidden = !S.batteryOk; $('b-battery').hidden = S.batteryOk;
  const sig = JSON.stringify(c);
  if (sig === settingsSig) return;
  settingsSig = sig;

  const a = c.account || {};
  const name = a.name || 'Аккаунт Telegram';
  $('a-name').textContent = name;
  $('a-ava').textContent = (a.name || 'F').trim().slice(0, 1).toUpperCase();
  const phone = a.phone ? (c.hidePhone ? maskPhone(a.phone) : a.phone) : '';
  $('a-sub').textContent = [phone, a.username ? '@' + a.username : ''].filter(Boolean).join(' · ') ||
    'Имя появится после следующего входа';
  $$('[data-set]').forEach((i) => (i.checked = !!c[i.dataset.set]));
  $('r-daily').hidden = !c.dailyRestart;
  const ti = $('daily-time');
  if (document.activeElement !== ti) {
    ti.value = String(Math.floor(c.dailyMinute / 60)).padStart(2, '0') + ':' + String(c.dailyMinute % 60).padStart(2, '0');
  }
  $('v-backup').textContent = c.lastBackup ? 'последняя ' + when(c.lastBackup) : 'ещё не делали';
  $('v-cache').textContent = size(c.cacheBytes || 0);
  $('v-repo').textContent = c.repo + (c.repo === c.defaultRepo ? ' · стандартный' : '');
  $('v-token').textContent = c.tokenSet ? 'задан' : 'не задан — нужен только для приватного';
  $('v-ver').textContent = `APK ${S.app} · Python ${S.python}`;

  const u = c.update || {};
  const up = $('v-upd');
  up.classList.remove('ok', 'warn');
  if (u.checking) up.textContent = 'Проверяю…';
  else if (u.error) up.textContent = u.error;
  else if (u.available) { up.textContent = `Есть новая: ${u.latest} — нажмите, чтобы открыть`; up.style.color = 'var(--warn)'; }
  else if (u.at) { up.textContent = 'У вас последняя версия'; up.style.color = 'var(--ok)'; }
  else { up.textContent = 'APK с GitHub'; up.style.color = ''; }
}

function maskPhone(p) {
  const d = p.replace(/\D/g, '');
  if (d.length < 5) return p;
  return '+' + d.slice(0, d.length - 10 > 0 ? d.length - 10 : 1) + ' ••• ••• •• ' + d.slice(-2);
}

// ------------------------------------------------------------------ подтверждение повторным нажатием

/** Первое нажатие «взводит» кнопку (текст меняется на 4 с), второе — выполняет. true — можно выполнять. */
function arm(btn, text) {
  const label = btn.querySelector('b') || btn;
  if (btn.dataset.armed) {
    clearTimeout(btn._t); delete btn.dataset.armed; btn.classList.remove('armed');
    label.textContent = btn._old;
    return true;
  }
  btn.dataset.armed = '1'; btn._old = label.textContent; label.textContent = text;
  btn.classList.add('armed');
  btn._t = setTimeout(() => { delete btn.dataset.armed; btn.classList.remove('armed'); label.textContent = btn._old; }, 4000);
  return false;
}

// ------------------------------------------------------------------ действия

const busyToast = () => toast('Уже идёт другая задача');

const ACTIONS = {
  prepare: () => call('prepare') || busyToast(),
  download: () => call('download', $('repo').value.trim(), $('token').value.trim()) || busyToast(),
  login: () => {
    const t = S.task || {};
    if (t.name === 'login' && t.state === 'running' && t.data && t.data.port) return A.openLogin(+t.data.port);
    openedPort = null;
    call('login') || busyToast();
  },
  archive: () => call('pickArchive') || busyToast(),
  cancelTask: () => call('cancelTask'),
  restart: () => { call('restart'); toast('Перезапускаю'); },
  notifications: () => call('notifications'),
  battery: () => call('battery'),
  panel: () => (S.status === 'running' ? call('panel') : toast('Сначала запустите FRX')),
  openLog: () => { logOpen = true; refresh(); renderLog(true); window.scrollTo(0, 0); },
  closeLog: () => { logOpen = false; refresh(); },
  copyLog: () => { call('copy', $('log').textContent); toast('Журнал скопирован'); },
  shareLog: () => call('shareLog'),
  reinstall: () => call('reinstall', '') || busyToast(),
  relogin: () => {
    toast('Останавливаю FRX…');
    setTimeout(() => { call('logout'); openedPort = null; call('login'); refresh(); }, 50);
  },
  modsRefresh: () => { call('modulesRefresh', true); modsSig = ''; toast('Обновляю каталог'); },
  backup: () => call('backup') || busyToast(),
  restore: () => call('restore') || busyToast(),
  clearCache: () => {
    const n = call('clearCache');
    if (n < 0) return busyToast();
    settingsSig = '';
    toast(n > 0 ? 'Освобождено ' + size(n) : 'Кэш уже пуст');
  },
  editRepo: () => { const e = $('e-repo'); e.hidden = !e.hidden; if (!e.hidden) { $('i-repo').value = C.repo || ''; $('i-repo').focus(); } },
  saveRepo: () => { call('setRepo', $('i-repo').value); $('e-repo').hidden = true; settingsSig = ''; toast('Сохранено'); },
  editToken: () => { const e = $('e-token'); e.hidden = !e.hidden; if (!e.hidden) { $('i-token').value = ''; $('i-token').focus(); } },
  saveToken: () => {
    const v = $('i-token').value.trim();
    call('setToken', v); $('e-token').hidden = true; settingsSig = '';
    toast(v ? 'Токен сохранён' : 'Токен убран');
  },
  checkUpdate: () => {
    const u = (C.update || {});
    if (u.available && u.url) return call('open', u.url);
    call('checkUpdate'); settingsSig = '';
  },
  github: () => call('open', 'https://github.com/Shxdowrate/FRX-Userbot-APK/releases'),
};

document.addEventListener('click', (e) => {
  const tb = e.target.closest('[data-tab]');
  if (tb) return showTab(tb.dataset.tab);
  const fc = e.target.closest('[data-filter]');
  if (fc) { filter = fc.dataset.filter; modsSig = ''; return renderMods(); }
  const mb = e.target.closest('[data-mod]');
  if (mb) {
    if (mb.disabled) return;
    // кнопка внутри строки модуля — не раскрывать строку
    if (mb.dataset.mod !== 'toggle') e.stopPropagation();
    return modAction(mb.dataset.mod, mb.dataset.m, mb);
  }
  const b = e.target.closest('[data-act]');
  if (!b || b.disabled) return;
  if (b.dataset.confirm && !arm(b, b.dataset.confirm)) return;
  const f = ACTIONS[b.dataset.act];
  if (f) { f(b); setTimeout(refresh, 150); }
});

$$('[data-opt]').forEach((i) => (i.onchange = () => { call('setOption', i.dataset.opt, i.checked); refresh(); }));
$$('[data-set]').forEach((i) => (i.onchange = () => { call('setOption', i.dataset.set, i.checked); settingsSig = ''; refresh(); }));
$('daily-time').onchange = (e) => {
  const [h, m] = e.target.value.split(':').map(Number);
  if (Number.isFinite(h) && Number.isFinite(m)) { call('setDailyTime', h * 60 + m); toast('Перезапуск каждый день в ' + e.target.value); }
  settingsSig = '';
};
$('m-q').oninput = () => { modsSig = ''; renderMods(); };
$('i-repo').onkeydown = (e) => { if (e.key === 'Enter') ACTIONS.saveRepo(); };
$('i-token').onkeydown = (e) => { if (e.key === 'Enter') ACTIONS.saveToken(); };

if (tab === 'mods') call('modulesRefresh', false);
refresh();
setInterval(refresh, 1000);

// ------------------------------------------------------------------ заглушка для просмотра в браузере

function mockBridge() {
  const q = new URLSearchParams(location.search);
  const setup = q.get('setup') !== null;
  const st = {
    app: '1.0.3', python: '3.14.8', pythonReady: true, frxInstalled: !setup, frxVersion: '1.0.3',
    loggedIn: !setup, status: q.get('status') || 'running', detail: '', modules: 71,
    startedAt: Date.now() - 8100e3, lastExit: null, autostart: true, wakelock: true, batteryOk: q.get('care') === null,
    notifOk: true, logSeq: 1,
    task: setup ? { name: 'prepare', state: 'running', data: { progress: 40 },
      lines: ['Распаковываю Python 3.14.8…', '  python.zip: 2500 файлов…'] } : { name: '', state: 'idle', data: {}, lines: [] },
  };
  const log = ['[FRX Keeper] SystemKeeper v2, Python 3.14.8', '[FRX deps] √ всё на месте',
    '[FRX] Юзербот запущен. Загружено модулей: 71', '[FRX Keeper] main.py запущен полностью',
    '[FRX] Ошибка загрузки модуля из user/modules/remote.py: No module named pycaw', '[app] ── запуск FRX ──'];
  const cat = [
    ['AutoAnon', 'AutoAnon', '2.1', 'Автопоиск собеседников в анонимном чате', '2.1'],
    ['qwerty', 'qwerty', '1.1', 'Исправляет текст, набранный не в той раскладке', '1.0'],
    ['Weather', 'weather', '1.0', 'Погода сейчас, по часам и на неделю (Open-Meteo, без ключей)'],
    ['Games', 'games', '1.2', '14 игр на двоих на инлайн-кнопках'],
    ['Translator', 'translator', '1.0', 'Переводчик Google без ключей'],
    ['Remind', 'remind', '1.0', 'Напоминания: через 2 часа, в 18:30, каждый день'],
    ['YouTube', 'youtube', '1.3', 'Скачать видео и аудио по ссылке', null, true],
  ].map(([name, module, version, desc, iv, needs]) => ({ name, module, version, desc, author: 'FRX', supported: true, needs: !!needs,
    installed: !!iv, installedVersion: iv || undefined, update: !!iv && iv !== version }));
  const mods = { items: cat, hidden: [{ name: 'VoiceJoin', reason: 'голосовые чаты на Android не работают' },
    { name: 'Remote', reason: 'управление компьютером, на телефоне не нужно' }], error: '', loading: false, busy: '', frxReady: true };
  const cfg = { account: { name: 'Shadow', username: 'shxdowrate', phone: '+79991234567' }, hidePhone: true, restartOnCrash: true,
    waitNetwork: true, dailyRestart: false, dailyMinute: 240, notifButtons: true, notifUptime: true,
    lastBackup: Date.now() - 864e5, cacheBytes: 48.2 * 1024 * 1024, repo: 'Shxdowrate/FRX-Userbot', defaultRepo: 'Shxdowrate/FRX-Userbot',
    tokenSet: false, update: {} };
  const noop = () => true;
  return {
    state: () => JSON.stringify(st), log: (n) => JSON.stringify(log.slice(-n)), modules: () => JSON.stringify(mods),
    settings: () => JSON.stringify(cfg),
    setOption: (k, v) => { if (k in st) st[k] = v; else cfg[k] = v; },
    setDailyTime: (m) => { cfg.dailyMinute = m; }, setRepo: (r) => { cfg.repo = r || cfg.defaultRepo; },
    setToken: (t) => { cfg.tokenSet = !!t; }, clearCache: () => { const n = cfg.cacheBytes; cfg.cacheBytes = 0; return n; },
    checkUpdate: () => { cfg.update = { checking: true }; setTimeout(() => (cfg.update = { at: Date.now(), latest: '1.0.3' }), 900); },
    moduleInstall: (m) => {
      mods.busy = m;
      setTimeout(() => { const x = cat.find((i) => i.module === m); x.installed = true; x.installedVersion = x.version; x.update = false; mods.busy = ''; }, 1200);
      return true;
    },
    moduleRemove: (m) => { const x = cat.find((i) => i.module === m); x.installed = false; x.update = false; delete x.installedVersion; return true; },
    modulesRefresh: noop, start: () => { st.status = 'running'; st.startedAt = Date.now(); }, stop: () => { st.status = 'stopped'; },
    prepare: noop, download: noop, login: noop, openLogin: noop, cancelTask: noop, restart: noop, battery: noop,
    notifications: noop, panel: noop, open: noop, shareLog: noop, copy: noop, logout: noop, pickArchive: noop,
    reinstall: noop, backup: noop, restore: noop,
  };
}
