"""
bootstrap.py — ставит код FRX в папку приложения (как install.sh на сервере): скачивает с GitHub
или берёт архив, который пользователь выбрал вручную (кнопка «Загрузить архив»).

    python bootstrap.py --dest <папка FRX> [--repo владелец/репозиторий]
    python bootstrap.py --dest <папка FRX> --zip <архив.zip>
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
    ap.add_argument('--zip', help='архив с кодом FRX вместо скачивания (zip репозитория или .update-архив)')
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

    tmp = Path(tempfile.mkdtemp(prefix='frx_dl_'))
    try:
        if a.zip:
            archive = Path(a.zip)
            say(f"Архив: {archive.stat().st_size / (1 << 20):.1f} МБ")
        else:
            say(f"Скачиваю FRX: github.com/{repo}")
            archive = tmp / 'frx.zip'
            try:
                download(url, headers, archive)
            except urllib.error.HTTPError as e:
                if e.code in (401, 403, 404):
                    say("Репозиторий не найден или закрыт. Если он приватный — укажите токен GitHub (доступ на чтение).")
                else:
                    say(f"GitHub ответил ошибкой {e.code}")
                say("Можно поставить из архива: кнопка «Загрузить архив».")
                return 2
            except Exception as e:
                say(f"Не скачалось: {e}. Проверьте интернет — или поставьте из архива («Загрузить архив»).")
                return 2
        say("Распаковываю…")
        try:
            with zipfile.ZipFile(archive) as z:
                root = (tmp / 'x').resolve()
                for info in z.infolist():                  # без выхода за папку распаковки
                    if not (root / info.filename).resolve().is_relative_to(root):
                        raise zipfile.BadZipFile(f"подозрительный путь в архиве: {info.filename}")
                z.extractall(root)
        except zipfile.BadZipFile as e:
            say(f"Это не zip-архив или он повреждён: {e}")
            return 3
        x = tmp / 'x'
        # код в корне архива (.update-архив, zip папки) или в одной папке внутри (zip репозитория с GitHub)
        cands = [x] + [p for p in x.iterdir() if p.is_dir()]
        src = next((p for p in cands if (p / 'frx_systemkeeper.py').is_file() and (p / 'main.py').is_file()), None)
        if not src:
            say("В архиве нет frx_systemkeeper.py и main.py — это точно код FRX?")
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
