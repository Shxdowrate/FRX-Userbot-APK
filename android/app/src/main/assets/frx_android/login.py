"""
login.py — первый вход в Telegram для FRX на Android (на ПК это делается в консоли).

    python login.py --frx <папка FRX>

Поднимает страницу на 127.0.0.1 (порт печатает строкой «@port N» — приложение открывает её у себя),
по шагам: api_id/api_hash → номер → код → пароль 2FA (если есть) → токен бота (необязательно).
Пишет user/config.json и сессию user/data/frx_session.session — те же файлы, что создаёт FRX на ПК.
В конце страница переходит на frx://login-done, приложение запускает FRX, а этот процесс завершается.
"""
import argparse
import asyncio
import json
import os
import platform
import subprocess
from pathlib import Path

from aiohttp import web
from telethon import TelegramClient, errors
from telethon.tl.functions.account import GetPasswordRequest

ap = argparse.ArgumentParser()
ap.add_argument('--frx', required=True)
ARGS = ap.parse_args()
FRX = Path(ARGS.frx)
USER = FRX / 'user'
CONFIG = USER / 'config.json'
SESSION = USER / 'data' / 'frx_session'


def getprop(name):
    try:
        return subprocess.run(['getprop', name], capture_output=True, text=True, timeout=3).stdout.strip()
    except Exception:
        return ''


def frx_version():
    import re
    try:
        m = re.search(r"FRX_VERSION\s*=\s*['\"]([^'\"]+)", (FRX / 'core' / 'engine.py').read_text(encoding='utf-8'))
        return m.group(1) if m else ''
    except OSError:
        return ''


def read_config():
    try:
        return json.loads(CONFIG.read_text(encoding='utf-8'))
    except Exception:
        return {}


def write_config(cfg):
    base = {"api_id": 0, "api_hash": "", "aiogram_token": "", "prefix": "."}
    base.update(read_config())
    base.update(cfg)
    CONFIG.parent.mkdir(parents=True, exist_ok=True)
    tmp = CONFIG.with_suffix('.tmp')
    tmp.write_text(json.dumps(base, indent=2, ensure_ascii=False), encoding='utf-8')
    os.replace(tmp, CONFIG)


class Login:
    def __init__(self):
        cfg = read_config()
        self.api_id = int(cfg.get('api_id') or 0)
        self.api_hash = str(cfg.get('api_hash') or '')
        self.client = None
        self.phone = ''
        self.code_hash = ''
        self.step = 'api' if not (self.api_id and self.api_hash) else 'phone'
        self.me = None
        self.hint = ''
        self.code_type = ''

    async def make_client(self):
        if self.client:
            try:
                await self.client.disconnect()
            except Exception:
                pass
        SESSION.parent.mkdir(parents=True, exist_ok=True)
        model = getprop('ro.product.model') or platform.machine()
        self.client = TelegramClient(
            str(SESSION), self.api_id, self.api_hash,
            device_model=model, system_version=f"Android {getprop('ro.build.version.release')}".strip(),
            app_version=f"FRX {frx_version()}".strip(), lang_code='ru', system_lang_code='ru')
        await self.client.connect()
        if await self.client.is_user_authorized():
            self.me = await self.client.get_me()
            self.step = 'bot'

    def state(self):
        s = {'step': self.step, 'phone': self.phone, 'hint': self.hint, 'code_type': self.code_type,
             'api_set': bool(self.api_id and self.api_hash)}
        if self.me:
            s['me'] = {'name': ' '.join(filter(None, [self.me.first_name, self.me.last_name])),
                       'username': self.me.username or '', 'id': self.me.id}
        cfg = read_config()
        s['bot_set'] = bool(cfg.get('aiogram_token'))
        s['prefix'] = cfg.get('prefix') or '.'
        return s


L = Login()
ERRORS = {
    'ApiIdInvalidError': 'api_id / api_hash не подходят — скопируйте их с my.telegram.org ещё раз',
    'PhoneNumberInvalidError': 'Неверный номер. Пишите в международном формате: +7 999 123-45-67',
    'PhoneNumberBannedError': 'Этот номер заблокирован в Telegram',
    'PhoneNumberFloodError': 'Слишком много попыток входа с этого номера — подождите',
    'PhoneCodeInvalidError': 'Неверный код',
    'PhoneCodeExpiredError': 'Код устарел — запросите новый',
    'PhoneCodeEmptyError': 'Введите код',
    'PasswordHashInvalidError': 'Неверный пароль двухэтапной проверки',
    'AuthKeyUnregisteredError': 'Сессия недействительна — войдите заново',
}


def err_text(e):
    if isinstance(e, errors.FloodWaitError):
        return f'Telegram просит подождать {e.seconds} с перед следующей попыткой'
    return ERRORS.get(type(e).__name__) or f'{type(e).__name__}: {e}'


async def api_state(request):
    return web.json_response(L.state())


async def api_set_api(request):
    d = await request.json()
    raw_id = str(d.get('api_id', '')).strip()
    raw_hash = str(d.get('api_hash', '')).strip()
    if not raw_id.isdigit() or int(raw_id) <= 0:
        return web.json_response({'error': 'api_id — это число'}, status=400)
    if len(raw_hash) < 16 or not raw_hash.isalnum():
        return web.json_response({'error': 'api_hash — строка из 32 букв и цифр'}, status=400)
    L.api_id, L.api_hash = int(raw_id), raw_hash
    try:
        await L.make_client()
    except Exception as e:
        return web.json_response({'error': err_text(e)}, status=400)
    write_config({'api_id': L.api_id, 'api_hash': L.api_hash})
    if L.step == 'api':
        L.step = 'phone'
    return web.json_response(L.state())


async def ensure_client():
    if L.client is None or not L.client.is_connected():
        await L.make_client()


async def api_phone(request):
    d = await request.json()
    phone = ''.join(ch for ch in str(d.get('phone', '')) if ch.isdigit() or ch == '+')
    if len(phone.lstrip('+')) < 7:
        return web.json_response({'error': 'Введите номер полностью, с кодом страны'}, status=400)
    try:
        await ensure_client()
        if L.step == 'bot':
            return web.json_response(L.state())
        sent = await L.client.send_code_request(phone)
    except Exception as e:
        return web.json_response({'error': err_text(e)}, status=400)
    L.phone, L.code_hash = phone, sent.phone_code_hash
    t = type(sent.type).__name__
    L.code_type = {'SentCodeTypeApp': 'app', 'SentCodeTypeSms': 'sms', 'SentCodeTypeCall': 'call',
                   'SentCodeTypeFlashCall': 'call', 'SentCodeTypeFragmentSms': 'fragment',
                   'SentCodeTypeEmailCode': 'email'}.get(t, 'app')
    L.step = 'code'
    return web.json_response(L.state())


async def after_login():
    L.me = await L.client.get_me()
    L.step = 'bot'


async def api_code(request):
    d = await request.json()
    code = ''.join(ch for ch in str(d.get('code', '')) if ch.isdigit())
    try:
        await ensure_client()
        await L.client.sign_in(phone=L.phone, code=code, phone_code_hash=L.code_hash)
    except errors.SessionPasswordNeededError:
        try:
            pw = await L.client(GetPasswordRequest())
            L.hint = pw.hint or ''
        except Exception:
            L.hint = ''
        L.step = 'password'
        return web.json_response(L.state())
    except Exception as e:
        return web.json_response({'error': err_text(e)}, status=400)
    await after_login()
    return web.json_response(L.state())


async def api_password(request):
    d = await request.json()
    try:
        await ensure_client()
        await L.client.sign_in(password=str(d.get('password', '')))
    except Exception as e:
        return web.json_response({'error': err_text(e)}, status=400)
    await after_login()
    return web.json_response(L.state())


async def api_back(request):
    if L.step in ('code', 'password'):
        L.step = 'phone'
    elif L.step == 'phone':
        L.step = 'api'
    return web.json_response(L.state())


async def api_finish(request):
    d = await request.json()
    token = str(d.get('bot_token', '')).strip()
    prefix = str(d.get('prefix', '')).strip() or '.'
    if token and (':' not in token or not token.split(':', 1)[0].isdigit()):
        return web.json_response({'error': 'Это не похоже на токен бота (вида 123456789:AA…)'}, status=400)
    upd = {'prefix': prefix[:3]}
    if token:
        upd['aiogram_token'] = token
    write_config(upd)
    try:
        await L.client.disconnect()                 # сессию дальше открывает сам FRX
    except Exception:
        pass
    print("Вход выполнен, настройки сохранены", flush=True)
    print("@done 1", flush=True)
    asyncio.get_running_loop().call_later(1.5, lambda: os._exit(0))
    return web.json_response({'ok': True, 'redirect': 'frx://login-done'})


async def font(request):
    f = FRX / 'core' / 'web' / 'static' / 'font.woff2'          # фирменный шрифт сайта FRX
    if not f.is_file():
        raise web.HTTPNotFound()
    return web.FileResponse(f, headers={'Cache-Control': 'max-age=86400'})


async def page(request):
    return web.Response(text=PAGE, content_type='text/html', charset='utf-8')


async def main():
    if L.step != 'api':
        try:
            await L.make_client()
        except Exception as e:
            print(f"Не удалось подключиться к Telegram: {err_text(e)}", flush=True)
    app = web.Application()
    app.add_routes([web.get('/', page), web.get('/font.woff2', font), web.get('/api/state', api_state), web.post('/api/api', api_set_api),
                    web.post('/api/phone', api_phone), web.post('/api/code', api_code),
                    web.post('/api/password', api_password), web.post('/api/back', api_back),
                    web.post('/api/finish', api_finish)])
    runner = web.AppRunner(app, access_log=None)
    await runner.setup()
    site = web.TCPSite(runner, '127.0.0.1', 0)
    await site.start()
    port = site._server.sockets[0].getsockname()[1]
    print("Экран входа готов", flush=True)
    print(f"@port {port}", flush=True)
    await asyncio.Event().wait()


PAGE = r'''<!doctype html>
<html lang="ru"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<title>Вход в Telegram</title>
<style>
@font-face{font-family:'FRX Gothic';font-weight:700;src:url('/font.woff2') format('woff2')}
:root{--bg:#050506;--glass:rgba(17,17,20,.84);--line:rgba(255,255,255,.16);--text:rgba(255,255,255,.95);
--muted:rgba(255,255,255,.64);--dim:rgba(255,255,255,.4);--acc:#ff3d1f;--acc2:#ff8a00;
--grad:linear-gradient(90deg,#ff2a1c 0%,#ff5a14 55%,#ff8a00 100%);--err:#ff9b8f;
--font:'FRX Gothic','Century Gothic','Jost',system-ui,sans-serif;--mono:ui-monospace,'Roboto Mono',monospace}
*{box-sizing:border-box}html,body{margin:0;background:var(--bg);color:var(--text);
font:700 16px/1.45 var(--font);letter-spacing:.012em;-webkit-tap-highlight-color:transparent}
body::before{content:'';position:fixed;inset:0;z-index:-1;background:
repeating-linear-gradient(135deg,rgba(255,255,255,.018) 0 1px,transparent 1px 14px),
radial-gradient(ellipse 70% 40% at 100% 0%,rgba(255,110,20,.16),transparent 70%),
radial-gradient(ellipse 70% 45% at 0% 100%,rgba(255,30,20,.14),transparent 70%),
radial-gradient(ellipse 80% 60% at 50% 50%,#0d0d10,#050506 80%)}
main{max-width:520px;margin:0 auto;padding:16px 16px 40px}
.top{margin:4px 0 18px}.top a{color:var(--acc2);text-decoration:none;font-size:13px;letter-spacing:.06em;
text-transform:lowercase;padding:6px 0;display:inline-block}
.steps{display:flex;gap:6px;margin-bottom:22px}.steps i{flex:1;height:3px;background:rgba(255,255,255,.08)}
.steps i.on{background:var(--grad);box-shadow:0 0 12px rgba(255,80,20,.6)}
h1{font-size:24px;margin:0 0 8px;text-transform:uppercase;letter-spacing:.05em;position:relative;padding-left:18px}
h1::before{content:'';position:absolute;left:0;top:14%;bottom:14%;width:5px;background:var(--grad);transform:skewX(-14deg)}
p{margin:0 0 14px;color:var(--muted)}p b{color:var(--text)}
label{display:block;font-size:12px;letter-spacing:.06em;color:var(--dim);margin:16px 0 4px}
input{width:100%;padding:10px 2px;border:0;border-bottom:1px solid var(--line);background:transparent;color:var(--text);
font:500 18px var(--mono);outline:none;border-radius:0}input:focus{border-bottom-color:var(--acc2)}
input.code{font-size:30px;letter-spacing:.45em;text-align:center}
button{width:100%;margin-top:22px;padding:14px;border:1px solid transparent;border-radius:2px;background:var(--grad);color:#fff;
font:inherit;font-size:15px;letter-spacing:.08em;text-transform:uppercase;box-shadow:0 6px 22px rgba(255,60,20,.28)}
button:active{filter:brightness(1.12);transform:scale(.98)}button:disabled{opacity:.45}
.ghost{background:transparent;color:var(--muted);border-color:transparent;box-shadow:none;margin-top:8px;font-size:12px}
.err{color:var(--err);margin-top:12px;min-height:1.2em;font-size:14px}
.card{background:var(--glass);border:1px solid rgba(255,255,255,.07);border-radius:2px;padding:14px 16px;margin:14px 0;
box-shadow:inset 3px 0 0 var(--acc),0 18px 50px rgba(0,0,0,.45)}
.card ol{margin:0;padding-left:20px;color:var(--muted)}.card li{margin:4px 0}
a.ext{color:var(--acc2)}
.me{display:flex;gap:14px;align-items:center}.ava{width:44px;height:44px;border-radius:2px;background:var(--grad);
color:#fff;display:grid;place-items:center;font-size:20px;box-shadow:0 0 16px rgba(255,70,20,.45)}
</style></head><body><main>
<div class="top"><a href="frx://home">← в приложение</a></div>
<div class="steps"><i></i><i></i><i></i><i></i></div>
<div id="view"></div>
</main>
<script>
const V=document.getElementById('view'),steps=['api','phone','code','bot'];
let S={};
const esc=s=>String(s??'').replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));
async function call(path,body){
  const r=await fetch(path,{method:body?'POST':'GET',headers:{'content-type':'application/json'},body:body?JSON.stringify(body):undefined});
  const j=await r.json();if(!r.ok)throw new Error(j.error||'Ошибка');return j;}
function bar(){const i=Math.max(0,steps.indexOf(S.step==='password'?'code':S.step));
  document.querySelectorAll('.steps i').forEach((e,k)=>e.classList.toggle('on',k<=i));}
function form(html,submit){V.innerHTML=html+'<div class="err" id="err"></div>';bar();
  const f=V.querySelector('form');const inp=V.querySelector('input');if(inp)setTimeout(()=>inp.focus(),50);
  f.onsubmit=async e=>{e.preventDefault();const b=f.querySelector('button[type=submit]');b.disabled=true;
    document.getElementById('err').textContent='';
    try{S=await submit(new FormData(f));render();}catch(x){document.getElementById('err').textContent=x.message;b.disabled=false;}};
  const back=V.querySelector('[data-back]');if(back)back.onclick=async()=>{S=await call('/api/back',{});render();};}
function render(){
  if(S.step==='api')form(`<h1>Ключи Telegram API</h1>
    <p>Нужны один раз — так FRX работает от вашего имени.</p>
    <div class="card"><ol><li>Откройте <a class="ext" href="https://my.telegram.org/apps" target="_blank">my.telegram.org</a> и войдите по номеру</li>
    <li>«API development tools» → создайте приложение (название любое)</li><li>Скопируйте сюда <b>App api_id</b> и <b>App api_hash</b></li></ol></div>
    <form><label>api_id</label><input name="api_id" inputmode="numeric" autocomplete="off" required>
    <label>api_hash</label><input name="api_hash" autocomplete="off" autocapitalize="off" spellcheck="false" required>
    <button type="submit">Дальше</button></form>`,fd=>call('/api/api',{api_id:fd.get('api_id'),api_hash:fd.get('api_hash')}));
  else if(S.step==='phone')form(`<h1>Номер телефона</h1><p>Аккаунт Telegram, в котором будет работать FRX.</p>
    <form><label>Номер</label><input name="phone" type="tel" inputmode="tel" placeholder="+7 999 123-45-67" value="${esc(S.phone)}" required>
    <button type="submit">Получить код</button><button type="button" class="ghost" data-back>Изменить api_id / api_hash</button></form>`,
    fd=>call('/api/phone',{phone:fd.get('phone')}));
  else if(S.step==='code'){const where={app:'в Telegram — в чат «Telegram» на другом вашем устройстве',sms:'по SMS',
    call:'звонком',fragment:'на fragment.com',email:'на почту'}[S.code_type]||'в Telegram';
    form(`<h1>Код подтверждения</h1><p>Отправили ${where} на <b>${esc(S.phone)}</b>.</p>
    <form><input class="code" name="code" inputmode="numeric" autocomplete="one-time-code" maxlength="8" required>
    <button type="submit">Войти</button><button type="button" class="ghost" data-back>Другой номер</button></form>`,
    fd=>call('/api/code',{code:fd.get('code')}));}
  else if(S.step==='password')form(`<h1>Облачный пароль</h1><p>У аккаунта включена двухэтапная проверка${S.hint?` (подсказка: <b>${esc(S.hint)}</b>)`:''}.</p>
    <form><input name="password" type="password" autocomplete="current-password" required>
    <button type="submit">Войти</button><button type="button" class="ghost" data-back>Другой номер</button></form>`,
    fd=>call('/api/password',{password:fd.get('password')}));
  else if(S.step==='bot'){const me=S.me||{};
    form(`<h1>Готово</h1><div class="card me"><div class="ava">${esc((me.name||'?')[0])}</div>
    <div><b>${esc(me.name)}</b><div style="color:var(--muted)">${me.username?'@'+esc(me.username):'id '+esc(me.id)}</div></div></div>
    <p>Осталось по желанию — их можно поменять потом в FRX.</p>
    <form><label>Префикс команд</label><input name="prefix" value="${esc(S.prefix||'.')}" maxlength="3">
    <label>Токен стационарного бота${S.bot_set?' (уже задан)':''} — необязательно</label>
    <input name="bot_token" placeholder="123456789:AA…" autocomplete="off" autocapitalize="off" spellcheck="false">
    <button type="submit">Запустить FRX</button></form>`,
    async fd=>{const r=await call('/api/finish',{prefix:fd.get('prefix'),bot_token:fd.get('bot_token')});
      location.href=r.redirect;return S;});}
}
call('/api/state').then(s=>{S=s;render();}).catch(e=>V.textContent=e.message);
</script></body></html>'''

if __name__ == '__main__':
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
