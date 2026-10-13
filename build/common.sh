# shellcheck shell=bash
# Общие переменные для скриптов сборки. Подключается: . build/common.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# versions.env: KEY=VALUE  # комментарий
while IFS='=' read -r k v; do
  [[ -z "$k" || "$k" == \#* ]] && continue
  v="${v%%#*}"; v="${v%"${v##*[![:space:]]}"}"
  export "$k=${!k:-$v}"
done < "$ROOT/versions.env"

export PY_MM="${PYTHON_VERSION%.*}"               # 3.14
export OUT="$ROOT/out"                            # всё собранное (в .gitignore)
export PREFIX="$OUT/python-android/prefix"        # распакованный CPython для Android
export DEPS="$OUT/deps"                           # нативные библиотеки для Pillow (статические)
export WHEELS="$OUT/wheels"                       # колёса под Android, которые пойдут в APK
export APP="$ROOT/android/app/src/main"
export HOST_TRIPLE=aarch64-linux-android
export ABI=arm64-v8a

if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
  for c in "${ANDROID_NDK_LATEST_HOME:-}" "${ANDROID_NDK_ROOT:-}" "${ANDROID_HOME:-/nonexistent}"/ndk/*; do
    [[ -n "$c" && -d "$c/toolchains" ]] && ANDROID_NDK_HOME="$c"
  done
fi
: "${ANDROID_NDK_HOME:?нужен Android NDK (ANDROID_NDK_HOME)}"
export ANDROID_NDK_HOME
export TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64"
export CC_APP="$TOOLCHAIN/bin/${HOST_TRIPLE}${APP_MIN_SDK}-clang"
export CC_WHEEL="$TOOLCHAIN/bin/${HOST_TRIPLE}${WHEEL_API_LEVEL}-clang"
export CXX_WHEEL="$TOOLCHAIN/bin/${HOST_TRIPLE}${WHEEL_API_LEVEL}-clang++"
export AR="$TOOLCHAIN/bin/llvm-ar"
export STRIP="$TOOLCHAIN/bin/llvm-strip"

say() { printf '\n\033[1;36m== %s\033[0m\n' "$*"; }
mkdir -p "$OUT" "$WHEELS"
