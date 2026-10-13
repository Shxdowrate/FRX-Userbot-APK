#!/usr/bin/env bash
# pydantic-core (нужен aiogram через pydantic) — на Rust, готовых колёс под Android на PyPI нет.
# Собираем ту версию, которую выбрал resolve.py (out/pins.json), кросс-компиляцией maturin + PyO3.
. "$(dirname "$0")/common.sh"

VER="$(python3 -c "import json;print(json.load(open('$OUT/pins.json'))['pydantic-core'])")"
if ls "$WHEELS"/pydantic_core-"$VER"-*.whl >/dev/null 2>&1; then
  say "pydantic-core $VER уже собран"; exit 0
fi
say "Собираю pydantic-core $VER для Android"
SRC="$OUT/src"; mkdir -p "$SRC"
python3 "$ROOT/build/resolve.py" sdist pydantic-core "$VER" "$SRC"
tar -xzf "$SRC"/pydantic_core-"$VER".tar.gz -C "$SRC"
cd "$SRC/pydantic_core-$VER"

rustup target add aarch64-linux-android
python3 -m pip install -q "maturin>=1.8"

export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$CC_WHEEL"
export CC_aarch64_linux_android="$CC_WHEEL"
export CXX_aarch64_linux_android="$CXX_WHEEL"
export AR_aarch64_linux_android="$AR"
export PYO3_CROSS_LIB_DIR="$PREFIX/lib"          # там libpython и _sysconfigdata__android_*.py
export PYO3_CROSS_PYTHON_VERSION="$PY_MM"
export ANDROID_API_LEVEL="$WHEEL_API_LEVEL"
export RUSTFLAGS="${RUSTFLAGS:-} -C link-arg=-L$PREFIX/lib -C link-arg=-lpython$PY_MM"
python3 -m maturin build --release --target aarch64-linux-android -i "python$PY_MM" --out "$OUT/built-pc"

whl="$(ls "$OUT"/built-pc/pydantic_core-"$VER"-*.whl | head -n1)"
# тег платформы у maturin может быть любым — колесо всё равно распаковывает наш install_wheels.py;
# переименовываем в android-тег, чтобы было видно, что это за файл
cp "$whl" "$WHEELS/pydantic_core-$VER-cp${PY_MM/./}-cp${PY_MM/./}-android_${WHEEL_API_LEVEL}_arm64_v8a.whl"
unzip -l "$WHEELS"/pydantic_core-"$VER"-*.whl | grep '\.so'
