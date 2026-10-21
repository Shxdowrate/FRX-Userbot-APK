#!/usr/bin/env python3
"""
Собирает файлы, которые кладутся в APK как assets:

    python.zip   lib/python3.X/… — стандартная библиотека CPython для Android + site-packages из out/wheels
                 (колёса распаковываются здесь же, без pip: теги платформы не проверяем — колёса подобраны resolve.py)
    python.json  {"python": "3.X.Y", "build": "<хеш>"} — приложение распаковывает python.zip заново, когда build меняется
Код FRX в APK не кладётся: приложение скачивает свежий с GitHub (или ставит из архива, выбранного вручную) —
вложенный код устаревал с каждым обновлением FRX, и с телефона приходила старая версия.

Запуск: python3.X build/assemble.py — тем же Python 3.X, что и в APK (байткод .pyc).
"""
import argparse
import compileall
import hashlib
import json
import os
import re
import shutil
import sys
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = Path(os.environ['OUT']) if os.environ.get('OUT') else ROOT / 'out' / os.environ.get('ABI', 'arm64-v8a')
PREFIX = OUT / 'python-android' / 'prefix'
WHEELS = OUT / 'wheels'
ASSETS = ROOT / 'android' / 'app' / 'src' / 'main' / 'assets'

# не нужно юзерботу — экономим место в APK
STDLIB_SKIP = {'test', 'idlelib', 'tkinter', 'turtledemo', 'turtle.py', '__phello__', '__pycache__'}


def install_wheel(whl: Path, site: Path) -> None:
    """Распаковка колеса в site-packages (то же, что делает pip, без проверки тегов и без скриптов)."""
    with zipfile.ZipFile(whl) as z:
        for info in z.infolist():
            name = info.filename
            parts = name.split('/')
            if parts[0].endswith('.data'):
                if len(parts) > 2 and parts[1] in ('purelib', 'platlib'):
                    name = '/'.join(parts[2:])
                else:
                    continue                      # scripts / headers / data — не нужны
            if name.endswith('/'):
                continue
            dest = site / name
            dest.parent.mkdir(parents=True, exist_ok=True)
            with z.open(info) as src, open(dest, 'wb') as out:
                shutil.copyfileobj(src, out)
    dist = next(iter(site.glob(whl.name.split('-')[0] + '-*.dist-info')), None)
    if dist:
        (dist / 'INSTALLER').write_text('frx-apk\n')


def build_python(stage: Path) -> str:
    mm = f'{sys.version_info.major}.{sys.version_info.minor}'
    src_lib = PREFIX / 'lib' / f'python{mm}'
    if not src_lib.is_dir():
        sys.exit(f'нет {src_lib} — запустите build/fetch_python.sh; и assemble.py нужен Python {mm}, как в APK')
    lib = stage / 'lib' / f'python{mm}'
    shutil.copytree(src_lib, lib, symlinks=False,
                    ignore=lambda d, names: [n for n in names if n in STDLIB_SKIP or n.startswith('config-')])
    site = lib / 'site-packages'
    site.mkdir(exist_ok=True)
    wheels = sorted(WHEELS.glob('*.whl'))
    if not wheels:
        sys.exit('нет колёс в out/wheels — запустите build/resolve.py collect')
    for whl in wheels:
        install_wheel(whl, site)
        print('  +', whl.name)
    # байткод заранее: первый запуск на телефоне быстрее. unchecked-hash — .pyc верен, пока APK не обновили
    compileall.compile_dir(str(lib), quiet=1, workers=0,
                           invalidation_mode=compileall.py_compile.PycInvalidationMode.UNCHECKED_HASH)
    try:
        hdr = (PREFIX / 'include' / f'python{mm}' / 'patchlevel.h').read_text()
        pyver = re.search(r'#define PY_VERSION\s+"([^"]+)"', hdr).group(1)
    except Exception:
        pyver = mm
    return pyver


def zip_dir(src: Path, dest: Path) -> str:
    h = hashlib.sha256()
    files = sorted(p for p in src.rglob('*') if p.is_file())
    dest.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(dest, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        for p in files:
            rel = p.relative_to(src).as_posix()
            data = p.read_bytes()
            h.update(rel.encode() + b'\0' + hashlib.sha256(data).digest())
            # .pyc и .so уже плотные или мелкие — всё равно жмём: APK скачивают целиком
            z.writestr(zipfile.ZipInfo(rel, date_time=(2020, 1, 1, 0, 0, 0)), data, zipfile.ZIP_DEFLATED)
    return h.hexdigest()[:16]


def main():
    argparse.ArgumentParser(description='assets для APK: python.zip + python.json').parse_args()
    ASSETS.mkdir(parents=True, exist_ok=True)
    stage = Path(tempfile.mkdtemp())
    try:
        pyver = build_python(stage)
        build = zip_dir(stage, ASSETS / 'python.zip')
        (ASSETS / 'python.json').write_text(json.dumps({'python': pyver, 'build': build}))
        print(f'python.zip: Python {pyver}, build {build} ({(ASSETS / "python.zip").stat().st_size // (1 << 20)} МБ)')
    finally:
        shutil.rmtree(stage, ignore_errors=True)
    for stale in ('frx.zip', 'frx.json'):                # от старых сборок, где код FRX клался в APK
        (ASSETS / stale).unlink(missing_ok=True)


if __name__ == '__main__':
    main()
