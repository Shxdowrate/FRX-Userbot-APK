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
| `nativeLibraryDir/` | `libfrxpython.so` (python), `libpython3.14.so`, OpenSSL/SQLite, libjpeg/freetype/libwebp для Pillow, `libffmpeg.so`/`libffprobe.so`, `libc++_shared.so` |
| `files/python/` | стандартная библиотека + пакеты ядра из APK (`assets/python.zip`), заменяется при обновлении APK |
| `files/pyuser/` | пакеты, которые FRX ставит сам (`pip --user`), переживают обновление APK |
| `files/frx/` | код FRX; `files/frx/user/` — ваши данные (config, сессия, модули, БД) |
| `files/bin/` | ссылки `ffmpeg`, `ffprobe` на программы из APK (в PATH) |
| `files/logs/frx.log` | журнал (на экране приложения — «Журнал», кнопка «Отправить») |

Интерфейс — веб-страница `assets/ui` в WebView + мост `FRX` в `MainActivity`. Первый вход в Telegram показывает
`assets/frx_android/login.py` (aiohttp + Telethon на 127.0.0.1): api_id/api_hash → номер → код → 2FA → токен бота.
Он пишет те же `user/config.json` и `user/data/frx_session.session`, что FRX создаёт на ПК.

Служба переднего плана (`FrxService`) держит FRX запущенным: перезапуск при падении (5 с … 5 мин), автозапуск
после включения телефона, частичная блокировка сна (можно выключить).

## Что внутри и чего нет

- **ffmpeg** (x264, VP9, Opus, MP3, AAC) — в APK: кружки, голосовые, видео-стикеры, музыка.
- **Пакеты модулей с кодом на C/Rust** — готовые сборки под Android в релизе
  [wheels](../../releases/tag/wheels) (`.github/workflows/wheels.yml`, раз в неделю свежие): psutil, lxml, primp, numpy,
  pydantic-core, pycryptodomex, brotli, audioop-lts, shazamio-core. Приложение ставит их само, когда модуль попросит
  (pip `--find-links`); пакеты на чистом Python — как обычно, с PyPI. Нужен пакет, которого нет, — см.
  [«Авторам модулей»](#авторам-модулей-пакет-с-crust-для-телефона).
- **Не работают:** голосовые чаты (py-tgcalls — под Android его нет вообще) и модули только для ПК.

Нужна полная свобода «любой пакет, как на ПК» — FRX в [Termux](https://termux.dev): `install.sh` из FRX-Userbot
ставит там всё сам и берёт те же готовые сборки (проверка — `.github/workflows/termux.yml`).

## Авторам модулей: пакет с C/Rust для телефона

Модуль объявляет пакеты как обычно (`REQUIREMENTS` в модуле) — ничего особого под Android писать не нужно.
Пакеты на чистом Python ставятся в приложении с PyPI сами. Если пакет с кодом на C/C++/Rust и под Android его
сборки нет, в журнале приложения будет `[модули] пакет … не поставился`. Тогда:

1. Добавьте имя пакета (как на PyPI) строкой в [`build/extra-packages.txt`](build/extra-packages.txt) — pull request
   в этот репозиторий; или откройте issue «Пакет для модуля не ставится» — добавим сами.
2. CI соберёт его под Android (`.github/workflows/wheels.yml`: Rust — maturin, C/C++ — cibuildwheel) и выложит в релиз
   wheels. Приложения подхватят сборку при следующей установке модуля — обновлять APK не нужно. Собранное
   пересобирается раз в неделю со свежими версиями.
3. Если пакет не собирается обычным способом (свои библиотеки, правки под Android), нужен рецепт в
   [`build/build_extra.sh`](build/build_extra.sh) — примеры там же: psutil, lxml, numpy, shazamio-core.

Свои сборки делать не нужно, да и негде: собирать для телефона приходится на компьютере с Android NDK — CI делает
это за всех. В Termux те же сборки подхватываются тоже, а чего нет — Termux соберёт на месте сам.

## Проверки в CI

- `build.yml` — сборка APK (arm64 для телефонов + x86_64) и самопроверка x86_64-сборки на эмуляторе Android 14:
  Python, подпроцессы, HTTPS, aiogram/Telethon/Pillow, ffmpeg, установка пакетов модулей
  (`assets/frx_android/selftest.py`).
- `wheels.yml` — сборки пакетов модулей под Android.
- `termux.yml` — установка в настоящем Termux (образ termux-docker): пакеты ядра и модулей.

## Сборка

Собирает GitHub Actions (`.github/workflows/build.yml`) на каждый push; тег `v*` — релиз с APK.

1. `build/fetch_python.sh` — CPython для Android с python.org
2. `build/build_launcher.sh` — запускатель + библиотеки в `jniLibs`
3. `build/resolve.py resolve` — версии пакетов ядра (`build/requirements-android.txt`)
4. `build/build_pydantic_core.sh` — pydantic-core (Rust, maturin) — для aiogram
5. `build/build_pillow.sh` — libjpeg-turbo, freetype, libwebp (NDK) + Pillow (cibuildwheel)
6. `build/resolve.py collect` — остальные колёса (android-колёса с PyPI или чистый Python)
7. `build/assemble.py [--frx папка]` — `assets/python.zip` (+ `frx.zip`, если код FRX доступен; это запасной
   вариант на случай, когда при первой установке нет интернета: обычно приложение скачивает свежий FRX с GitHub)
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
