"""
bootstrap.py — скачивает код FRX с GitHub в папку приложения (как install.sh на сервере).

    python bootstrap.py --dest <папка FRX> [--repo владелец/репозиторий]
    FRX_GITHUB_TOKEN — токен GitHub для приватного репозитория (необязательно)

Папку user/ (данные владельца) не трогает. Строки «@ключ значение» — данные для экрана приложения.
Только стандартная библиотека.
"""
import argparse
import os
import shutil
import sys
import tempfile
import time
import urllib.error
import urllib.request
import zipfile
from pathlib import Path


def say(text):
    print(text, flush=True)


def download(url, headers, dest: Path):
    req = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(req, timeout=60) as r, open(dest, 'wb') as f:
        total = int(r.headers.get('Content-Length') or 0)
        done, last = 0, 0.0
        while True:
            chunk = r.read(1 << 16)
            if not chunk:
                break
            f.write(chunk)
            done += len(chunk)
            if time.time() - last > 0.7:
                last = time.time()
                mb = done / (1 << 20)
                say(f"  скачано {mb:.1f} МБ" + (f" из {total / (1 << 20):.1f}" if total else ''))
                if total:
                    say(f"@progress {done * 100 // total}")
    say(f"  скачано {dest.stat().st_size / (1 << 20):.1f} МБ")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--dest', required=True)
    ap.add_argument('--repo', default='Shxdowrate/FRX-Userbot')
    a = ap.parse_args()
    token = os.environ.get('FRX_GITHUB_TOKEN', '').strip()
    dest = Path(a.dest)
    repo = a.repo.strip().removeprefix('https://github.com/').strip('/')
    headers = {'User-Agent': 'FRX-Android'}
    if token:
        headers['Authorization'] = f'token {token}'
        url = f'https://api.github.com/repos/{repo}/zipball'
    else:
        url = f'https://github.com/{repo}/archive/HEAD.zip'

    say(f"Скачиваю FRX: github.com/{repo}")
    tmp = Path(tempfile.mkdtemp(prefix='frx_dl_'))
    try:
        archive = tmp / 'frx.zip'
        try:
            download(url, headers, archive)
        except urllib.error.HTTPError as e:
            if e.code in (401, 403, 404):
                say("Репозиторий не найден или закрыт. Если он приватный — укажите токен GitHub (доступ на чтение).")
            else:
                say(f"GitHub ответил ошибкой {e.code}")
            return 2
        except Exception as e:
            say(f"Не скачалось: {e}. Проверьте интернет.")
            return 2
        say("Распаковываю…")
        with zipfile.ZipFile(archive) as z:
            z.extractall(tmp / 'x')
        roots = [p for p in (tmp / 'x').iterdir() if p.is_dir()]
        src = next((p for p in roots if (p / 'frx_systemkeeper.py').is_file()), None)
        if not src:
            say("В архиве нет frx_systemkeeper.py — это точно репозиторий FRX?")
            return 3
        dest.mkdir(parents=True, exist_ok=True)
        for item in src.iterdir():
            if item.name in ('user', '.git', '.github'):
                continue
            target = dest / item.name
            if target.is_dir() and not target.is_symlink():
                shutil.rmtree(target)
            elif target.exists():
                target.unlink()
            shutil.move(str(item), str(target))
        say("FRX установлен")
        say("@done 1")
        return 0
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == '__main__':
    sys.exit(main())
