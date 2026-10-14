'use strict';
const $ = (id) => document.getElementById(id);
const A = window.FRX || mockBridge();          // мост приложения (MainActivity.Bridge); в обычном браузере — заглушка
let S = {}, logSeq = -1, openedPort = null;

const TASKS = { prepare: 'Подготовка', download: 'Скачиваю FRX', login: 'Вход в Telegram', reinstall: 'Переустановка FRX' };
const STATUS = {
  running: 'Работает', starting: 'Запускается…', restarting: 'Перезапуск…', stopping: 'Останавливается…',
  stopped: 'Остановлен', error: 'Ошибка',
};

function call(name, ...args) {
  try { return A[name](...args); } catch (e) { toast('Ошибка: ' + e.message); }
}

function toast(text) {
  const t = $('toast');
  t.textContent = text; t.classList.add('on');
  clearTimeout(toast.t); toast.t = setTimeout(() => t.classList.remove('on'), 2200);
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

function refresh() {
  try { S = JSON.parse(A.state()); } catch (e) { return; }
  const t = S.task || {};
  const loginOpen = t.name === 'login' && t.state === 'running';
  const setup = !(S.frxInstalled && S.loggedIn) || loginOpen;
  $('setup').hidden = !setup;
  $('dash').hidden = setup;
  $('ver').textContent = S.frxVersion ? 'v' + S.frxVersion : '';
  $('foot').textContent = `APK ${S.app} · Python ${S.python}` + (S.frxVersion ? ` · FRX ${S.frxVersion}` : '');
  setup ? renderSetup(t) : renderDash();
}

// ------------------------------------------------------------------ установка

function stepState(id, done, locked) {
  const el = $(id);
  el.classList.toggle('done', !!done);
  el.classList.toggle('locked', !done && !!locked);
}

function renderSetup(t) {
  const busy = t.state === 'running';
  const prepared = S.pythonReady && (S.frxInstalled || !S.bundledFrx);
  stepState('s-prepare', prepared, false);
  $('s-download').hidden = S.bundledFrx && !S.frxInstalled ? true : false;
  stepState('s-download', S.frxInstalled, !S.pythonReady);
  stepState('s-login', S.loggedIn, !S.frxInstalled);
  document.querySelectorAll('#setup .btn').forEach((b) => (b.disabled = busy));

  const loginBtn = document.querySelector('[data-act=login]');
  if (t.name === 'login' && busy && t.data && t.data.port) {
    loginBtn.textContent = 'Открыть экран входа';
    loginBtn.disabled = false;
    if (openedPort !== t.data.port) { openedPort = t.data.port; A.openLogin(+t.data.port); }
  } else loginBtn.textContent = S.loggedIn ? 'Войти' : 'Войти';

  const box = $('task');
  box.hidden = !t.name;
  if (!t.name) return;
  box.classList.toggle('idle', !busy);
  $('task-name').textContent = (TASKS[t.name] || t.name) +
    (t.state === 'failed' ? ' — не получилось' : t.state === 'done' ? ' — готово' : '');
  document.querySelector('[data-act=cancelTask]').hidden = !busy || t.name === 'prepare';
  const pr = t.data && t.data.progress;
  $('task-meter').hidden = !(busy && pr);
  if (pr) $('task-meter').firstElementChild.style.width = pr + '%';
  const pre = $('task-log');
  pre.textContent = (t.lines || []).join('\n');
  pre.scrollTop = pre.scrollHeight;
}

// ------------------------------------------------------------------ панель

function renderDash() {
  const st = S.status || 'stopped';
  const alive = ['running', 'starting', 'restarting'].includes(st);
  $('dot').className = 'dot ' + st;
  $('st-title').textContent = STATUS[st] || st;
  let sub = '';
  if (st === 'running' && S.startedAt) sub = 'без перерыва ' + ago(S.startedAt);
  else if (S.detail) sub = S.detail;
  else if (st === 'stopped' && S.lastExit !== null && S.lastExit !== undefined) sub = 'код выхода ' + S.lastExit;
  else if (st === 'stopped') sub = 'Нажмите «Запустить»';
  $('st-sub').textContent = sub;

  const main = $('b-main');
  main.textContent = alive ? 'Остановить' : st === 'stopping' ? 'Останавливается…' : 'Запустить';
  main.className = 'btn' + (alive ? ' danger' : '');
  main.disabled = st === 'stopping';
  main.onclick = () => (alive ? call('stop') : call('start'));
  $('b-restart').disabled = !alive;

  $('care').hidden = S.notifOk && S.batteryOk;
  $('c-notif').hidden = S.notifOk;
  $('c-battery').hidden = S.batteryOk;
  $('o-autostart').checked = S.autostart;
  $('o-wakelock').checked = S.wakelock;

  const t = S.task || {};
  const dt = $('dtask');
  dt.hidden = t.name !== 'reinstall';
  if (!dt.hidden) {
    const busy = t.state === 'running';
    dt.classList.toggle('idle', !busy);
    $('dtask-name').textContent = TASKS.reinstall + (t.state === 'failed' ? ' — не получилось' : t.state === 'done' ? ' — готово' : '');
    const pre = $('dtask-log'); pre.textContent = (t.lines || []).slice(-12).join('\n'); pre.scrollTop = pre.scrollHeight;
  }

  if (S.logSeq !== logSeq) {
    logSeq = S.logSeq;
    renderLog(JSON.parse(A.log(400)));
  }
}

function cls(line) {
  if (/^\[app\]/.test(line)) return 'a';
  if (/Traceback|Error|Exception|Ошибка|ошибк|❌|×|не удалось|не встал/i.test(line)) return 'e';
  if (/⚠|Внимание|warning/i.test(line)) return 'w';
  if (/√|✓|запущен|готов/i.test(line)) return 'g';
  return '';
}

function renderLog(lines) {
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
  if (atBottom || !renderLog.once) pre.scrollTop = pre.scrollHeight;
  renderLog.once = true;
}

// ------------------------------------------------------------------ действия

const ACTIONS = {
  prepare: () => call('prepare') || toast('Уже идёт другая задача'),
  download: () => call('download', $('repo').value.trim(), $('token').value.trim()) || toast('Уже идёт другая задача'),
  login: () => {
    const t = S.task || {};
    if (t.name === 'login' && t.state === 'running' && t.data && t.data.port) return A.openLogin(+t.data.port);
    openedPort = null;
    call('login') || toast('Уже идёт другая задача');
  },
  cancelTask: () => call('cancelTask'),
  restart: () => { call('restart'); toast('Перезапускаю'); },
  notifications: () => call('notifications'),
  battery: () => call('battery'),
  panel: () => (S.status === 'running' ? call('panel') : toast('Сначала запустите FRX')),
  copyLog: () => { call('copy', $('log').textContent); toast('Журнал скопирован'); },
  shareLog: () => call('shareLog'),
  reinstall: (btn) => {
    if (!btn.dataset.armed) {
      btn.dataset.armed = '1'; const old = btn.textContent; btn.textContent = 'Точно? Ещё раз';
      setTimeout(() => { delete btn.dataset.armed; btn.textContent = old; }, 4000);
      return;
    }
    delete btn.dataset.armed; btn.textContent = 'Переустановить FRX';
    call('reinstall') || toast('Уже идёт другая задача');
  },
  relogin: (btn) => {
    if (!btn.dataset.armed) {
      btn.dataset.armed = '1'; const old = btn.textContent; btn.textContent = 'Точно? Ещё раз';
      setTimeout(() => { delete btn.dataset.armed; btn.textContent = old; }, 4000);
      return;
    }
    delete btn.dataset.armed; btn.textContent = 'Войти заново';
    toast('Останавливаю FRX…');
    setTimeout(() => { call('logout'); openedPort = null; call('login'); refresh(); }, 50);
  },
};

document.addEventListener('click', (e) => {
  const b = e.target.closest('[data-act]');
  if (!b || b.disabled) return;
  const f = ACTIONS[b.dataset.act];
  if (f) { f(b); setTimeout(refresh, 150); }
});
$('o-autostart').onchange = (e) => call('setOption', 'autostart', e.target.checked);
$('o-wakelock').onchange = (e) => call('setOption', 'wakelock', e.target.checked);
window.frxResume = refresh;

refresh();
setInterval(refresh, 1000);

// ------------------------------------------------------------------ заглушка для просмотра в браузере

function mockBridge() {
  const q = new URLSearchParams(location.search);
  const st = {
    app: '1.0.0', python: '3.14.8', pythonReady: true, frxInstalled: q.get('setup') === null, frxVersion: '1.0.0',
    bundledFrx: false, loggedIn: q.get('setup') === null, status: q.get('status') || 'running', detail: '',
    startedAt: Date.now() - 8100e3, lastExit: null, autostart: true, wakelock: true, batteryOk: false, notifOk: true,
    task: q.get('setup') !== null ? { name: 'prepare', state: 'running', data: { progress: 40 },
      lines: ['Распаковываю Python 3.14.8…', '  python.zip: 2500 файлов…'] } : { name: '', state: 'idle', data: {}, lines: [] },
    logSeq: 1,
  };
  const log = ['[FRX Keeper] SystemKeeper v2, Python 3.14.8', '[FRX deps] √ всё на месте',
    '[FRX] Юзербот запущен. Загружено модулей: 71', '[FRX Keeper] main.py запущен полностью',
    '[FRX] Ошибка загрузки модуля из user/modules/remote.py: No module named pycaw', '[app] ── запуск FRX ──'];
  const noop = () => true;
  return { state: () => JSON.stringify(st), log: () => JSON.stringify(log), prepare: noop, download: noop, login: noop,
    openLogin: noop, cancelTask: noop, start: noop, stop: noop, restart: noop, setOption: noop, battery: noop,
    notifications: noop, panel: noop, open: noop, shareLog: noop, copy: noop, logout: noop };
}
