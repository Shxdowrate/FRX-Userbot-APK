#!/usr/bin/env bash
# Скачивает официальный CPython для Android (aarch64) и распаковывает в out/python-android/.
# В пакете: prefix/lib/libpython3.X.so, стандартная библиотека prefix/lib/python3.X/ (с lib-dynload),
# libssl/libcrypto/libsqlite и заголовки prefix/include — исполняемого python там нет (его даёт launcher.c).
. "$(dirname "$0")/common.sh"

if [[ -f "$PREFIX/lib/libpython${PY_MM}.so" ]]; then
  say "CPython $PYTHON_VERSION для Android уже распакован"
  exit 0
fi
say "Скачиваю CPython $PYTHON_VERSION для Android"
url="https://www.python.org/ftp/python/${PYTHON_VERSION}/python-${PYTHON_VERSION}-${HOST_TRIPLE}.tar.gz"
mkdir -p "$OUT/python-android"
curl -fL --retry 3 -o "$OUT/python-android.tar.gz" "$url"
tar -xzf "$OUT/python-android.tar.gz" -C "$OUT/python-android"
ls "$PREFIX/lib"
test -f "$PREFIX/lib/libpython${PY_MM}.so"
