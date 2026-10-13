# FRX для Android

APK, в котором юзербот [FRX](https://github.com/Shxdowrate/FRX-Userbot) работает прямо на телефоне — без Termux, сервера и консоли.

**Скачать:** раздел [Releases](../../releases) → `FRX-x.y.z.apk`. Нужен Android 13 или новее (arm64).

## Как это устроено

Внутри APK — настоящий CPython (официальная сборка python.org для Android) и маленький запускатель
`libfrxpython.so` (`build/launcher.c`). Android 10+ разрешает приложению запускать программы только из папки
его нативных библиотек, поэтому python лежит там. Дальше всё как на ПК: служба приложения запускает
`frx_systemkeeper.py`, keeper — `main.py`; `.restart`, `.update`, установка пакетов модулей (`pip`) работают как обычно.

| Где | Что |
|---|---|
| `nativeLibraryDir/` | `libfrxpython.so` (python), `libpython3.14.so`, OpenSSL/SQLite, libjpeg/freetype/libwebp для Pillow |
| `files/python/` | стандартная библиотека + пакеты ядра из APK (`assets/python.zip`), заменяется при обновлении APK |
| `files/pyuser/` | пакеты, которые FRX ставит сам (`pip --user`), переживают обновление APK |
| `files/frx/` | код FRX; `files/frx/user/` — ваши данные (config, сессия, модули, БД) |
| `files/logs/frx.log` | журнал (на экране приложения — «Журнал», кнопка «Отправить») |

Интерфейс — веб-страница `assets/ui` в WebView + мост `FRX` в `MainActivity`. Первый вход в Telegram показывает
`assets/frx_android/login.py` (aiohttp + Telethon на 127.0.0.1): api_id/api_hash → номер → код → 2FA → токен бота.
Он пишет те же `user/config.json` и `user/data/frx_session.session`, что FRX создаёт на ПК.

Служба переднего плана (`FrxService`) держит FRX запущенным: перезапуск при падении (5 с … 5 мин), автозапуск
после включения телефона, частичная блокировка сна (можно выключить).

## Что не работает на телефоне

- голосовые чаты (py-tgcalls — нет сборки под Android);
- пакеты модулей с кодом на C/Rust, которых нет на PyPI под Android, — модуль скажет, чего ему не хватает;
- ffmpeg пока не входит в APK — модули, которым он нужен, сообщат об этом;
- модули только для ПК (управление Windows и т.п.).

## Сборка

Собирает GitHub Actions (`.github/workflows/build.yml`) на каждый push; тег `v*` — релиз с APK.

1. `build/fetch_python.sh` — CPython для Android с python.org
2. `build/build_launcher.sh` — запускатель + библиотеки в `jniLibs`
3. `build/resolve.py resolve` — версии пакетов ядра (`build/requirements-android.txt`)
4. `build/build_pydantic_core.sh` — pydantic-core (Rust, maturin) — для aiogram
5. `build/build_pillow.sh` — libjpeg-turbo, freetype, libwebp (NDK) + Pillow (cibuildwheel)
6. `build/resolve.py collect` — остальные колёса (android-колёса с PyPI или чистый Python)
7. `build/assemble.py [--frx папка]` — `assets/python.zip` (+ `frx.zip`, если код FRX доступен)
8. `gradle assembleRelease`

Версии — в `versions.env`.

### Секреты репозитория (Settings → Secrets and variables → Actions → New repository secret)

| Секрет | Зачем |
|---|---|
| `FRX_KEYSTORE_B64` | ключ подписи APK одной строкой (`base64 -w0 frx.jks`). Без постоянного ключа новая версия не ставится поверх старой |
| `FRX_KEYSTORE_PASSWORD`, `FRX_KEY_ALIAS` | необязательно: пароль и имя ключа, если они не `frxfrx` и `frx` |
| `FRX_USERBOT_TOKEN` | необязательно, пока FRX-Userbot приватный: токен с чтением этого репозитория, чтобы положить код FRX в APK |

Свой ключ (если нужен новый):

```
keytool -genkeypair -keystore frx.jks -alias frx -keyalg RSA -keysize 4096 -validity 36500 -storepass frxfrx -keypass frxfrx
```
