"""
selftest.py — проверка Python внутри APK (CI запускает её на эмуляторе: am start … --ez selftest true).
Проверяет то, на чём держится FRX на Android: подпроцессы из nativeLibraryDir, расширения стандартной
библиотеки (_ssl, _sqlite3, …), HTTPS с проверкой сертификатов, pydantic-core, Pillow, aiogram, Telethon, pip.
Итог — строка «SELFTEST OK» или «SELFTEST FAIL: …».
"""
import asyncio
import io
import os
import subprocess
import sys
import time
import traceback

failed = []


def check(name):
    def deco(fn):
        t = time.time()
        try:
            r = fn()
            if asyncio.iscoroutine(r):
                r = asyncio.run(r)
            print(f"PASS {name}{(': ' + str(r)) if r else ''} ({time.time() - t:.1f} с)", flush=True)
        except BaseException:
            failed.append(name)
            print(f"FAIL {name}\n{traceback.format_exc()}", flush=True)
        return fn
    return deco


@check('python')
def _():
    import platform
    return f"{sys.version.split()[0]} {sys.platform} {platform.machine()} exe={sys.executable}"


@check('subprocess (sys.executable)')
def _():
    out = subprocess.run([sys.executable, '-c', 'import sys; print(sys.version_info[:2])'], capture_output=True,
                         text=True, timeout=60)
    assert out.returncode == 0, out.stderr
    return out.stdout.strip()


@check('stdlib extensions')
def _():
    import _ssl, _sqlite3, _ctypes, _hashlib, _socket, select, zlib, _decimal  # noqa: F401
    import importlib
    import sqlite3
    sqlite3.connect(':memory:').execute('select 1')
    missing = []
    for m in ('lzma', 'bz2', '_uuid', 'mmap', '_zstd', 'readline', '_curses'):
        try:
            importlib.import_module(m)
        except ImportError:
            missing.append(m)
    return f"sqlite {sqlite3.sqlite_version}, {__import__('ssl').OPENSSL_VERSION}; нет: {missing or '—'}"


@check('zoneinfo (tzdata)')
def _():
    from zoneinfo import ZoneInfo
    from datetime import datetime
    return datetime.now(ZoneInfo('Asia/Chita')).strftime('%H:%M %Z')


@check('https')
def _():
    import urllib.request
    with urllib.request.urlopen('https://api.telegram.org/', timeout=30) as r:
        return f"api.telegram.org → {r.status}"


@check('pydantic + pydantic-core')
def _():
    import pydantic
    class M(pydantic.BaseModel):
        x: int
    assert M(x='5').x == 5
    return pydantic.VERSION


@check('aiogram')
def _():
    import aiogram
    from aiogram import Bot, Dispatcher  # noqa
    return aiogram.__version__


@check('aiohttp')
async def _():
    import aiohttp
    async with aiohttp.ClientSession() as s:
        async with s.get('https://api.telegram.org/') as r:
            return f"{aiohttp.__version__} → {r.status}"


@check('Pillow')
def _():
    from PIL import Image, ImageDraw, ImageFont, features
    im = Image.new('RGB', (64, 64), 'teal')
    ImageDraw.Draw(im).text((4, 4), 'FRX', fill='white', font=ImageFont.load_default(size=20))
    for fmt in ('JPEG', 'PNG', 'WEBP'):
        b = io.BytesIO(); im.save(b, fmt); b.seek(0); Image.open(b).load()
    feats = [f for f in ('jpg', 'zlib', 'freetype2', 'webp') if features.check(f)]
    assert {'jpg', 'zlib', 'freetype2', 'webp'} <= set(feats), feats
    import PIL
    return f"{PIL.__version__} {feats}"


@check('telethon (подключение без входа)')
async def _():
    import telethon
    from telethon import TelegramClient
    from telethon.sessions import MemorySession
    c = TelegramClient(MemorySession(), 1, '0' * 32)
    await asyncio.wait_for(c.connect(), 60)
    ok = c.is_connected()
    await c.disconnect()
    assert ok
    return telethon.__version__


@check('pip')
def _():
    out = subprocess.run([sys.executable, '-m', 'pip', '--version'], capture_output=True, text=True, timeout=120)
    assert out.returncode == 0, out.stderr
    return out.stdout.split(' from ')[0]


@check('pip install (чистый Python, в pyuser)')
def _():
    out = subprocess.run([sys.executable, '-m', 'pip', 'install', '-q', 'six'], capture_output=True, text=True,
                         timeout=300)
    assert out.returncode == 0, out.stdout + out.stderr
    out = subprocess.run([sys.executable, '-c', 'import six; print(six.__file__)'], capture_output=True, text=True)
    assert out.returncode == 0 and os.environ.get('PYTHONUSERBASE', '?') in out.stdout, out.stdout + out.stderr
    return out.stdout.strip()


@check('ffmpeg (x264, vp9, opus, mp3)')
def _():
    import shutil
    import tempfile
    ff = shutil.which('ffmpeg')
    assert ff, 'ffmpeg нет в PATH'
    d = tempfile.mkdtemp()
    jobs = [
        ['-f', 'lavfi', '-i', 'testsrc=duration=1:size=160x160:rate=10', '-c:v', 'libx264', '-preset', 'veryfast',
         '-pix_fmt', 'yuv420p', d + '/a.mp4'],
        ['-f', 'lavfi', '-i', 'testsrc=duration=1:size=160x160:rate=10', '-c:v', 'libvpx-vp9', '-b:v', '200k', d + '/b.webm'],
        ['-f', 'lavfi', '-i', 'sine=duration=1', '-c:a', 'libopus', d + '/c.ogg'],
        ['-f', 'lavfi', '-i', 'sine=duration=1', '-c:a', 'libmp3lame', d + '/d.mp3'],
        ['-f', 'lavfi', '-i', 'sine=duration=1', '-c:a', 'aac', d + '/e.m4a'],
    ]
    for j in jobs:
        r = subprocess.run([ff, '-hide_banner', '-loglevel', 'error', '-y', *j], capture_output=True, text=True, timeout=120)
        assert r.returncode == 0 and os.path.getsize(j[-1]) > 0, (j[-1], r.stderr[-500:])
    v = subprocess.run([ff, '-version'], capture_output=True, text=True).stdout.split('\n')[0]
    probe = subprocess.run([shutil.which('ffprobe'), '-v', 'error', '-show_entries', 'stream=codec_name', '-of', 'csv=p=0',
                            d + '/a.mp4'], capture_output=True, text=True).stdout.strip()
    assert probe == 'h264', probe
    return v


@check('пакеты модулей из релиза wheels (psutil)')
def _():
    out = subprocess.run([sys.executable, '-m', 'pip', 'install', '-q', 'psutil'], capture_output=True, text=True, timeout=300)
    assert out.returncode == 0, (out.stdout + out.stderr)[-800:]
    out = subprocess.run([sys.executable, '-c', 'import psutil; print(psutil.__version__, psutil.virtual_memory().total // 2**20, "MB")'],
                         capture_output=True, text=True)
    assert out.returncode == 0, out.stderr[-800:]
    return out.stdout.strip()


def _module_pkg(spec, imp):
    @check(f'пакет модулей: {spec}')
    def _():
        out = subprocess.run([sys.executable, '-m', 'pip', 'install', '-q', spec], capture_output=True, text=True, timeout=600)
        assert out.returncode == 0, (out.stdout + out.stderr)[-800:]
        out = subprocess.run([sys.executable, '-c', f'import {imp}; print(getattr({imp}, "__version__", "ok"))'],
                             capture_output=True, text=True, timeout=120)
        assert out.returncode == 0, out.stderr[-800:]
        return out.stdout.strip()


for _spec, _imp in [('ddgs', 'ddgs'), ('yt-dlp[default]', 'yt_dlp'), ('shazamio', 'shazamio'), ('lxml', 'lxml.etree'),
                    ('numpy', 'numpy'), ('pycryptodomex', 'Cryptodome'), ('brotli', 'brotli')]:
    _module_pkg(_spec, _imp)


print("SELFTEST OK" if not failed else f"SELFTEST FAIL: {', '.join(failed)}", flush=True)
sys.exit(1 if failed else 0)
