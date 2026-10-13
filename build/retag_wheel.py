#!/usr/bin/env python3
"""
retag_wheel.py КОЛЕСО НОВАЯ_ВЕРСИЯ — меняет версию пакета в колесе (имя файла, папка .dist-info, METADATA, RECORD).

Зачем: shazamio требует shazamio-core==1.1.2, а 1.1.2 написан на старом PyO3 и падает на Python 3.14.
Собираем свежий shazamio-core (тот же API) и выдаём его за «1.1.2+frx.<настоящая версия>» — локальная метка версии
(PEP 440) удовлетворяет «==1.1.2», и pip берёт нашу сборку.
"""
import base64
import hashlib
import shutil
import sys
import tempfile
import zipfile
from pathlib import Path


def main(path, new_ver):
    src = Path(path)
    name, old_ver, *tags = src.name[:-4].split('-')
    tmp = Path(tempfile.mkdtemp())
    with zipfile.ZipFile(src) as z:
        z.extractall(tmp)
    old_di, new_di = tmp / f'{name}-{old_ver}.dist-info', tmp / f'{name}-{new_ver}.dist-info'
    old_di.rename(new_di)
    meta = new_di / 'METADATA'
    meta.write_text('\n'.join(f'Version: {new_ver}' if ln.startswith('Version: ') else ln
                              for ln in meta.read_text(encoding='utf-8').split('\n')), encoding='utf-8')
    rows = []
    for f in sorted(p for p in tmp.rglob('*') if p.is_file()):
        rel = f.relative_to(tmp).as_posix()
        if rel == f'{new_di.name}/RECORD':
            continue
        data = f.read_bytes()
        digest = base64.urlsafe_b64encode(hashlib.sha256(data).digest()).rstrip(b'=').decode()
        rows.append(f'{rel},sha256={digest},{len(data)}')
    rows.append(f'{new_di.name}/RECORD,,')
    (new_di / 'RECORD').write_text('\n'.join(rows) + '\n', encoding='utf-8')
    out = src.with_name('-'.join([name, new_ver.replace('-', '_'), *tags]) + '.whl')
    with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as z:
        for f in sorted(p for p in tmp.rglob('*') if p.is_file()):
            z.write(f, f.relative_to(tmp).as_posix())
    shutil.rmtree(tmp)
    if out != src:
        src.unlink()
    print(out)


if __name__ == '__main__':
    main(*sys.argv[1:3])
