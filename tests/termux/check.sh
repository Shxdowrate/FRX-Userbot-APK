#!/data/data/com.termux/files/usr/bin/bash
# Проверка установки FRX в Termux (CI: образ termux/termux-docker). Те же системные пакеты, что ставит install.sh
# FRX-Userbot в Termux (TERMUX_PKGS / TERMUX_PY_PKGS — держите в синхроне), затем пакеты ядра FRX и модулей по одному.
# Итог — таблица PASS/FAIL и строка «TERMUX OK» / «TERMUX FAIL».
set -u
TERMUX_PKGS="python git clang make pkg-config binutils rust libjpeg-turbo libpng freetype libwebp libxml2 libxslt libffi openssl ffmpeg"
TERMUX_PY_PKGS="python-pillow python-lxml python-numpy python-cryptography python-psutil"

echo "== pkg"
yes | pkg update >/dev/null 2>&1
pkg install -y $TERMUX_PKGS || { echo "TERMUX FAIL: pkg install"; exit 1; }
for p in $TERMUX_PY_PKGS; do
  if pkg install -y "$p" >/dev/null 2>&1; then echo "  pkg $p: есть"; else echo "  pkg $p: НЕТ в репозитории"; fi
done
python -c 'import sys, platform; print("python", sys.version.split()[0], sys.platform, platform.machine())'
ffmpeg -hide_banner -version | head -1

echo "== venv"
python -m venv --system-site-packages "$HOME/v" && . "$HOME/v/bin/activate"
pip install -q --disable-pip-version-check --upgrade pip
export ANDROID_API_LEVEL=24

# ядро FRX (requirements.txt) и пакеты модулей библиотеки (REQUIREMENTS) — по одному, со временем
SPECS=(
  "telethon>=1.36|telethon" "aiogram>=3.25,<4.0|aiogram" "aiohttp>=3.9|aiohttp" "Pillow>=10.0|PIL"
  "psutil|psutil" "ddgs|ddgs" "yt-dlp[default]|yt_dlp" "pygments|pygments" "ytmusicapi|ytmusicapi"
  "yandex-music|yandex_music" "zvuk-music|zvuk_music" "shazamio|shazamio" "tzdata|tzdata"
)
fail=0
results=()
for item in "${SPECS[@]}"; do
  spec="${item%%|*}"; mod="${item##*|}"
  t0=$SECONDS
  if pip install -q --disable-pip-version-check "$spec" >"$HOME/pip.log" 2>&1 && python -c "import $mod" 2>>"$HOME/pip.log"; then
    results+=("PASS  $spec  ($((SECONDS - t0)) с)")
  else
    results+=("FAIL  $spec  ($((SECONDS - t0)) с): $(grep -iE 'error|failed' "$HOME/pip.log" | tail -3 | tr '\n' ' ' | cut -c1-400)")
    fail=1
  fi
done
# второй проход: те же пакеты, но со сборками под Android из релиза wheels APK (как будет делать install.sh в Termux)
echo "== с готовыми сборками (релиз wheels)"
deactivate
python -m venv --system-site-packages "$HOME/v2" && . "$HOME/v2/bin/activate"
pip install -q --disable-pip-version-check --upgrade pip
export PIP_FIND_LINKS="https://github.com/Shxdowrate/FRX-Userbot-APK/releases/expanded_assets/wheels" PIP_PREFER_BINARY=1
for item in "${SPECS[@]}"; do
  spec="${item%%|*}"; mod="${item##*|}"
  t0=$SECONDS
  if pip install -q --disable-pip-version-check "$spec" >"$HOME/pip.log" 2>&1 && python -c "import $mod" 2>>"$HOME/pip.log"; then
    results+=("PASS2 $spec  ($((SECONDS - t0)) с)")
  else
    results+=("FAIL2 $spec  ($((SECONDS - t0)) с): $(grep -iE 'error|failed' "$HOME/pip.log" | tail -3 | tr '\n' ' ' | cut -c1-400)")
  fi
done
pip list 2>/dev/null | grep -iE "pydantic.core|primp|shazamio|lxml|numpy|psutil" | sed 's/^/  /'
python -c "import pydantic_core, sys; print('pydantic_core из', pydantic_core.__file__)"
unset PIP_FIND_LINKS PIP_PREFER_BINARY

echo "== итог"
printf '%s\n' "${results[@]}"
python - <<'P'
import psutil, PIL.features as f
print("psutil", psutil.__version__, "cpu", psutil.cpu_count(), "mem", psutil.virtual_memory().total // 2**20, "MB")
print("Pillow", [x for x in ("jpg", "webp", "freetype2", "zlib") if f.check(x)])
P
[ "$fail" = 0 ] && echo "TERMUX OK" || echo "TERMUX FAIL"
exit "$fail"
