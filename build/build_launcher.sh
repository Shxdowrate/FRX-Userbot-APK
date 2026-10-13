#!/usr/bin/env bash
# Собирает launcher (libfrxpython.so) и кладёт его вместе с libpython и её библиотеками в jniLibs приложения.
. "$(dirname "$0")/common.sh"

JNI="$APP/jniLibs/$ABI"
rm -rf "$JNI"; mkdir -p "$JNI"
say "Собираю launcher"
"$CC_APP" -O2 -fPIE -pie \
  -I"$PREFIX/include/python${PY_MM}" \
  "$ROOT/build/launcher.c" \
  -L"$PREFIX/lib" -lpython"${PY_MM}" \
  -Wl,-rpath,'$ORIGIN' \
  -o "$JNI/libfrxpython.so"

# libpython + то, от чего зависят модули стандартной библиотеки (_ssl, _hashlib, _sqlite3 …)
for so in "$PREFIX"/lib/lib*.so; do
  cp -L "$so" "$JNI/"
done
"$STRIP" --strip-unneeded "$JNI"/*.so
ls -la "$JNI"
