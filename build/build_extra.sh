#!/usr/bin/env bash
# Колёса под Android для пакетов, которые просят модули FRX (их нет на PyPI под Android).
#   build/build_extra.sh ПАКЕТ [ВЕРСИЯ]   → out/$ABI/extra/*.whl
# Колёса публикуются в релиз «wheels» репозитория APK; приложение ставит их по запросу модуля
# (PIP_FIND_LINKS в Runtime.kt), в сам APK они не входят.
. "$(dirname "$0")/common.sh"

PKG="${1:?пакет}"
VER="${2:-}"
EXTRA="$OUT/extra"; mkdir -p "$EXTRA"
SRC="$OUT/src-extra"; mkdir -p "$SRC"
TC="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake"

latest() { python3 -c "import json,urllib.request,sys;print(json.load(urllib.request.urlopen('https://pypi.org/pypi/$1/json'))['info']['version'])"; }
# пакет, который тянет другой пакет с точной версией, — собираем ровно ту версию, что он требует, иначе pip её не возьмёт
case "$PKG" in
  pydantic-core) PARENT=aiogram ;;           # aiogram → pydantic → pydantic-core==X
  shazamio-core) PARENT=shazamio ;;          # shazamio → shazamio-core==X
  *) PARENT="" ;;
esac
if [[ -z "$VER" && -n "$PARENT" ]]; then
  VER="$(python3 -m pip install -q --dry-run --ignore-installed --report - "$PARENT" 2>/dev/null \
         | python3 -c "import json,sys; print(next(i['metadata']['version'] for i in json.load(sys.stdin)['install'] if i['metadata']['name'].lower().replace('_','-')=='$PKG'))")"
fi
[[ -n "$VER" ]] || VER="$(latest "$PKG")"
say "$PKG $VER для $ABI"

unpack() {  # → путь к распакованным исходникам
  local f; f="$(python3 "$ROOT/build/resolve.py" sdist "$PKG" "$VER" "$SRC")"
  local d="$SRC/$PKG-$VER-src"; rm -rf "$d"; mkdir -p "$d"
  tar -xf "$f" -C "$d" --strip-components=1
  echo "$d"
}

cibw() {  # исходники → колесо через cibuildwheel (C-расширения)
  python3 -m pip install -q "cibuildwheel>=3.2"
  export CIBW_PLATFORM=android CIBW_ARCHS="$WHEEL_ARCH" CIBW_BUILD="cp${PY_MM/./}-*" CIBW_BUILD_FRONTEND=build
  export CIBW_TEST_SKIP='*' CIBW_TEST_COMMAND='' CIBW_TEST_REQUIRES='' CIBW_BEFORE_ALL=':' CIBW_BEFORE_BUILD=':'
  export CIBW_REPAIR_WHEEL_COMMAND='cp {wheel} {dest_dir}'
  export ANDROID_API_LEVEL="$WHEEL_API_LEVEL"
  python3 -m cibuildwheel --output-dir "$EXTRA" "$1"
}

maturin_build() {  # исходники → колесо через maturin (Rust)
  rustup target add "$HOST_TRIPLE"
  python3 -m pip install -q "maturin>=1.8"
  local T; T="$(echo "$HOST_TRIPLE" | tr 'a-z-' 'A-Z_')"
  export "CARGO_TARGET_${T}_LINKER=$CC_WHEEL" "CC_${HOST_TRIPLE//-/_}=$CC_WHEEL" "CXX_${HOST_TRIPLE//-/_}=$CXX_WHEEL" \
         "AR_${HOST_TRIPLE//-/_}=$AR"
  export PYO3_CROSS_LIB_DIR="$PREFIX/lib" PYO3_CROSS_PYTHON_VERSION="$PY_MM" ANDROID_API_LEVEL="$WHEEL_API_LEVEL"
  export ANDROID_NDK_ROOT="$ANDROID_NDK_HOME" ANDROID_NDK="$ANDROID_NDK_HOME"
  # крейты с cmake внутри (BoringSSL и т.п.) — тулчейн NDK
  export "CMAKE_TOOLCHAIN_FILE_${HOST_TRIPLE//-/_}=$TC" CMAKE_GENERATOR=Ninja
  export ANDROID_ABI="$ABI" ANDROID_PLATFORM="android-$WHEEL_API_LEVEL"
  export RUSTFLAGS="${RUSTFLAGS:-} -C link-arg=-L$PREFIX/lib -C link-arg=-lpython$PY_MM"
  (cd "$1" && python3 -m maturin build --release --target "$HOST_TRIPLE" -i "python$PY_MM" --out "$EXTRA")
}

static_cmake() {  # srcdir builddir prefix [опции] — статическая библиотека под NDK
  local s="$1" b="$2" p="$3"; shift 3
  cmake -S "$s" -B "$b" -G Ninja -DCMAKE_TOOLCHAIN_FILE="$TC" -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM="android-$WHEEL_API_LEVEL" -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$p" \
    -DCMAKE_INSTALL_LIBDIR=lib -DCMAKE_PREFIX_PATH="$p" -DCMAKE_FIND_ROOT_PATH="$p" \
    -DBUILD_SHARED_LIBS=OFF -DCMAKE_POSITION_INDEPENDENT_CODE=ON "$@"
  cmake --build "$b" -j"$(nproc)"
  cmake --install "$b"
}

python3 -m pip install -q ninja cmake
case "$PKG" in
  psutil)
    # psutil узнаёт Linux по sys.platform; у Python для Android это «android» — а ядро то же самое
    d="$(unpack)"
    sed -i 's/LINUX = sys.platform.startswith("linux")/LINUX = sys.platform.startswith(("linux", "android"))/' "$d/psutil/_common.py"
    grep -q '"android"' "$d/psutil/_common.py"
    cibw "$d" ;;
  brotli|Brotli|pycryptodomex|audioop-lts|numpy)
    d="$(unpack)"
    if [[ "$PKG" == numpy ]]; then
      # numpy при сборке запускает тестовую программу (формат long double) — при кросс-сборке нельзя, говорим сами:
      # на Android и arm64, и x86_64 long double — 128-битный IEEE quad
      # и линковка с libpython: meson, вшитый в numpy, на Android её не добавляет, а LDFLAGS при кросс-сборке не читает
      LA="['-L$PREFIX/lib', '-lpython$PY_MM', '-lm', '-Wl,-z,max-page-size=16384', '-Wl,--build-id=sha1']"
      printf "[properties]\nlongdouble_format = 'IEEE_QUAD_LE'\n\n[built-in options]\nc_link_args = %s\ncpp_link_args = %s\n" \
        "$LA" "$LA" > "$OUT/numpy-cross.ini"
      cat "$OUT/numpy-cross.ini"
      export CIBW_CONFIG_SETTINGS="setup-args=-Dallow-noblas=true setup-args=-Dblas=none setup-args=-Dlapack=none setup-args=--cross-file=$OUT/numpy-cross.ini"
    fi
    cibw "$d" ;;
  lxml)
    # libxml2 + libxslt статически внутрь колеса — lxml самодостаточен, в APK ничего добавлять не нужно
    XML2_VER=2.14.6; XSLT_VER=1.1.43     # как в сборках самого lxml (pyproject: LIBXML2_VERSION)
    D="$OUT/deps-lxml"
    if [[ ! -f "$D/.done" ]]; then
      curl -fL --retry 3 -o "$SRC/libxml2.tar.xz" "https://download.gnome.org/sources/libxml2/${XML2_VER%.*}/libxml2-$XML2_VER.tar.xz"
      curl -fL --retry 3 -o "$SRC/libxslt.tar.xz" "https://download.gnome.org/sources/libxslt/${XSLT_VER%.*}/libxslt-$XSLT_VER.tar.xz"
      rm -rf "$SRC/libxml2" "$SRC/libxslt"; mkdir -p "$SRC/libxml2" "$SRC/libxslt"
      tar -xf "$SRC/libxml2.tar.xz" -C "$SRC/libxml2" --strip-components=1
      tar -xf "$SRC/libxslt.tar.xz" -C "$SRC/libxslt" --strip-components=1
      static_cmake "$SRC/libxml2" "$SRC/libxml2/b" "$D" -DLIBXML2_WITH_PYTHON=OFF -DLIBXML2_WITH_ICONV=OFF \
        -DLIBXML2_WITH_LZMA=OFF -DLIBXML2_WITH_ZLIB=OFF -DLIBXML2_WITH_TESTS=OFF -DLIBXML2_WITH_PROGRAMS=OFF \
        -DLIBXML2_WITH_ICU=OFF -DLIBXML2_WITH_HTTP=OFF
      static_cmake "$SRC/libxslt" "$SRC/libxslt/b" "$D" -DLIBXSLT_WITH_PYTHON=OFF -DLIBXSLT_WITH_CRYPTO=OFF \
        -DLIBXSLT_WITH_TESTS=OFF -DLIBXSLT_WITH_PROGRAMS=OFF
      touch "$D/.done"
    fi
    ls -la "$D/lib"
    # lxml спрашивает флаги у xml2-config / xslt-config — отвечаем своими (статические библиотеки целиком)
    mkdir -p "$D/bin"
    cat > "$D/bin/xml2-config" <<EOF
#!/bin/sh
case "\$1" in
  --version) echo $XML2_VER ;;
  --cflags) echo "-I$D/include/libxml2" ;;
  --libs) echo "-L$D/lib -lxml2 -lm" ;;
  --prefix) echo "$D" ;;
esac
EOF
    cat > "$D/bin/xslt-config" <<EOF
#!/bin/sh
case "\$1" in
  --version) echo $XSLT_VER ;;
  --cflags) echo "-I$D/include -I$D/include/libxml2" ;;
  --libs) echo "-L$D/lib -lexslt -lxslt -lxml2 -lm" ;;
  --prefix) echo "$D" ;;
esac
EOF
    chmod +x "$D/bin/xml2-config" "$D/bin/xslt-config"
    d="$(unpack)"
    # setupinfo.py lxml зовёт xml2-config через shell; Python «под Android» в среде сборки ищет его в /system/bin/sh
    [[ -e /system/bin/sh ]] || { sudo mkdir -p /system/bin && sudo ln -sf /bin/sh /system/bin/sh; }
    export CIBW_ENVIRONMENT_ANDROID="PATH=$D/bin:\$PATH WITH_XML2_CONFIG=$D/bin/xml2-config WITH_XSLT_CONFIG=$D/bin/xslt-config"
    export PATH="$D/bin:$PATH"
    cibw "$d" ;;
  primp|shazamio-core|shazamio_core|pydantic-core)
    d="$(unpack)"
    maturin_build "$d" ;;
  *)
    echo "нет рецепта для $PKG"; exit 2 ;;
esac
ls -la "$EXTRA"
for w in "$EXTRA"/*.whl; do unzip -l "$w" | grep -E '\.so$' | head -5; done
