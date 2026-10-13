#!/usr/bin/env python3
"""
Подбор и сбор колёс под Android для APK.

    resolve.py resolve            → out/pins.json   {пакет: версия} — дерево зависимостей requirements-android.txt
    resolve.py sdist NAME VER DIR → скачивает исходники пакета (sdist) с PyPI, печатает путь
    resolve.py collect            → out/wheels/*.whl — каждое закреплённое колесо под Android:
                                    готовое android-колесо с PyPI, либо чистый Python (py3-none-any; если на PyPI
                                    только исходники — собираем такое колесо сами), либо собранное нами (NATIVE_BUILT).

Разрешение зависимостей делает pip хоста (Linux, тот же Python 3.X): набор пакетов у FRX от ОС не зависит.
"""
import json
import os
import subprocess
import sys
import tempfile
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = Path(os.environ['OUT']) if os.environ.get('OUT') else ROOT / 'out' / os.environ.get('ABI', 'arm64-v8a')
WHEELS = OUT / 'wheels'
PINS = OUT / 'pins.json'
REQS = ROOT / 'build' / 'requirements-android.txt'
NATIVE_BUILT = {'pydantic-core', 'pillow'}          # собираются скриптами build_*.sh

PY_MM = os.environ.get('PY_MM') or f'{sys.version_info.major}.{sys.version_info.minor}'
API = int(os.environ.get('WHEEL_API_LEVEL', '24'))
CP = 'cp' + PY_MM.replace('.', '')
WHEEL_ARCH = os.environ.get('WHEEL_ARCH') or {'x86_64': 'x86_64'}.get(os.environ.get('ABI', ''), 'arm64_v8a')


def norm(name: str) -> str:
    return name.lower().replace('_', '-').replace('.', '-')


def pip(*args, check=True, capture=False):
    cmd = [sys.executable, '-m', 'pip', '--disable-pip-version-check', *args]
    print('+', ' '.join(cmd), flush=True)
    return subprocess.run(cmd, check=check, capture_output=capture, text=True)


def resolve():
    if sys.version_info[:2] != tuple(map(int, PY_MM.split('.'))):
        sys.exit(f'нужен Python {PY_MM} на машине сборки, а это {sys.version.split()[0]}')
    with tempfile.TemporaryDirectory() as td:
        report = Path(td) / 'report.json'
        pip('install', '--dry-run', '--ignore-installed', '--quiet', '--report', str(report), '-r', str(REQS))
        data = json.loads(report.read_text())
    pins = {norm(i['metadata']['name']): i['metadata']['version'] for i in data['install']}
    OUT.mkdir(exist_ok=True)
    PINS.write_text(json.dumps(pins, indent=1, sort_keys=True))
    print(json.dumps(pins, indent=1, sort_keys=True))


def sdist(name: str, ver: str, dest: str):
    with urllib.request.urlopen(f'https://pypi.org/pypi/{name}/{ver}/json', timeout=60) as r:
        info = json.load(r)
    src = next((u for u in info['urls'] if u['packagetype'] == 'sdist'), None)
    if not src:
        sys.exit(f'{name} {ver}: на PyPI нет исходников (sdist)')
    Path(dest).mkdir(parents=True, exist_ok=True)
    path = Path(dest) / src['filename']
    if not path.is_file():
        urllib.request.urlretrieve(src['url'], path)
    print(path)


def android_platforms():
    out = []
    for api in range(API, 15, -1):
        out += ['--platform', f'android_{api}_{WHEEL_ARCH}']
    return out


def collect():
    pins = json.loads(PINS.read_text())
    WHEELS.mkdir(parents=True, exist_ok=True)
    for old in WHEELS.glob('*.whl'):                 # из кэша прошлой сборки: версия поменялась — убираем
        parts = old.name.split('-')
        if pins.get(norm(parts[0])) != parts[1]:
            print('  убираю устаревшее', old.name)
            old.unlink()
    have = {norm(p.name.split('-')[0]) for p in WHEELS.glob('*.whl')}
    problems = []
    for name, ver in sorted(pins.items()):
        if name in have:
            continue
        if name in NATIVE_BUILT:
            problems.append(f'{name} {ver}: колесо должно было собраться скриптом build_*.sh')
            continue
        r = pip('download', '--no-deps', '--only-binary', ':all:', *android_platforms(),
                '--python-version', PY_MM, '--implementation', 'cp', '--abi', CP, '--abi', 'abi3', '--abi', 'none',
                '-d', str(WHEELS), f'{name}=={ver}', check=False, capture=True)
        if r.returncode == 0:
            continue
        # на PyPI только исходники (например, pyaes) — собираем колесо сами; годится только чистый Python
        with tempfile.TemporaryDirectory() as td:
            r2 = pip('wheel', '--no-deps', '-w', td, f'{name}=={ver}', check=False, capture=True)
            built = list(Path(td).glob('*.whl'))
            if r2.returncode != 0 or not built:
                problems.append(f'{name} {ver}: нет колеса под Android и не собирается ({(r2.stderr or "").strip()[-300:]})')
                continue
            whl = built[0]
            if not whl.name.endswith('-none-any.whl'):
                problems.append(f'{name} {ver}: это не чистый Python ({whl.name}) — нужен свой скрипт сборки')
                continue
            whl.replace(WHEELS / whl.name)
    for p in sorted(WHEELS.glob('*.whl')):
        print('  ', p.name)
    if problems:
        print('\nПРОБЛЕМЫ:\n  ' + '\n  '.join(problems), file=sys.stderr)
        sys.exit(1)


if __name__ == '__main__':
    cmd = sys.argv[1:] or ['help']
    if cmd[0] == 'resolve':
        resolve()
    elif cmd[0] == 'sdist' and len(cmd) == 4:
        sdist(*cmd[1:])
    elif cmd[0] == 'collect':
        collect()
    else:
        sys.exit(__doc__)
