#!/usr/bin/env bash
# Pillow под Android: сначала библиотеки форматов (libjpeg-turbo, freetype, libwebp) под NDK, потом сам Pillow
# через cibuildwheel (у него есть кросс-сборка под Android). zlib берётся системная (она есть на любом Android).
#
# Библиотеки собираются разделяемыми (libjpeg.so, libfreetype.so, libwebp*.so, libsharpyuv.so — у тулчейна Android
# имена без номера версии) и кладутся в jniLibs приложения: оттуда их находит загрузчик (LD_LIBRARY_PATH).
. "$(dirname "$0")/common.sh"

VER="$(python3 -c "import json;print(json.load(open('$OUT/pins.json'))['pillow'])")"
copy_libs() {  # библиотеки форматов — в нативные библиотеки приложения
  local JNI="$APP/jniLibs/$ABI"; mkdir -p "$JNI"
  for so in "$DEPS"/lib/lib*.so; do cp -L "$so" "$JNI/"; done
  "$STRIP" --strip-unneeded "$JNI"/libjpeg.so "$JNI"/libfreetype.so "$JNI"/libwebp*.so "$JNI"/libsharpyuv.so 2>/dev/null || true
}
if ls "$WHEELS"/pillow-"$VER"-*.whl >/dev/null 2>&1 && [[ -f "$DEPS/.done" ]]; then
  say "Pillow $VER уже собран"; copy_libs; exit 0
fi

JPEG_VER=3.1.0
FREETYPE_VER=2.13.3
WEBP_VER=1.5.0
SRC="$OUT/src"; mkdir -p "$SRC" "$DEPS"
TC="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake"

fetch() {  # url file
  [[ -f "$SRC/$2" ]] || curl -fL --retry 3 -o "$SRC/$2" "$1"
}
cmake_build() {  # srcdir builddir [опции…]
  local s="$1" b="$2"; shift 2
  cmake -S "$s" -B "$b" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$TC" -DANDROID_ABI="$ABI" -DANDROID_PLATFORM="android-$WHEEL_API_LEVEL" \
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$DEPS" -DCMAKE_INSTALL_LIBDIR=lib \
    -DCMAKE_PREFIX_PATH="$DEPS" -DCMAKE_FIND_ROOT_PATH="$DEPS" "$@"
  cmake --build "$b" -j"$(nproc)"
  cmake --install "$b"
}

python3 -m pip install -q ninja cmake
if [[ ! -f "$DEPS/.done" ]]; then
  say "libjpeg-turbo $JPEG_VER"
  fetch "https://github.com/libjpeg-turbo/libjpeg-turbo/releases/download/$JPEG_VER/libjpeg-turbo-$JPEG_VER.tar.gz" jpeg.tgz
  rm -rf "$SRC/jpeg" && mkdir "$SRC/jpeg" && tar -xzf "$SRC/jpeg.tgz" -C "$SRC/jpeg" --strip-components=1
  cmake_build "$SRC/jpeg" "$SRC/jpeg/b" -DENABLE_SHARED=ON -DENABLE_STATIC=OFF -DWITH_TURBOJPEG=OFF -DWITH_TOOLS=OFF -DWITH_TESTS=OFF

  say "freetype $FREETYPE_VER"
  fetch "https://download.savannah.gnu.org/releases/freetype/freetype-$FREETYPE_VER.tar.xz" freetype.txz \
    || fetch "https://github.com/freetype/freetype/archive/refs/tags/VER-${FREETYPE_VER//./-}.tar.gz" freetype.txz
  rm -rf "$SRC/freetype" && mkdir "$SRC/freetype" && tar -xf "$SRC/freetype.txz" -C "$SRC/freetype" --strip-components=1
  cmake_build "$SRC/freetype" "$SRC/freetype/b" -DBUILD_SHARED_LIBS=ON \
    -DFT_DISABLE_ZLIB=ON -DFT_DISABLE_BZIP2=ON -DFT_DISABLE_PNG=ON -DFT_DISABLE_HARFBUZZ=ON -DFT_DISABLE_BROTLI=ON

  say "libwebp $WEBP_VER"
  fetch "https://github.com/webmproject/libwebp/archive/refs/tags/v$WEBP_VER.tar.gz" webp.tgz
  rm -rf "$SRC/webp" && mkdir "$SRC/webp" && tar -xzf "$SRC/webp.tgz" -C "$SRC/webp" --strip-components=1
  cmake_build "$SRC/webp" "$SRC/webp/b" -DBUILD_SHARED_LIBS=ON -DWEBP_BUILD_ANIM_UTILS=OFF -DWEBP_BUILD_CWEBP=OFF \
    -DWEBP_BUILD_DWEBP=OFF -DWEBP_BUILD_GIF2WEBP=OFF -DWEBP_BUILD_IMG2WEBP=OFF -DWEBP_BUILD_VWEBP=OFF \
    -DWEBP_BUILD_WEBPINFO=OFF -DWEBP_BUILD_WEBPMUX=OFF -DWEBP_BUILD_EXTRAS=OFF -DWEBP_BUILD_LIBWEBPMUX=ON

  # системная zlib: Pillow ищет include/zlib.h и lib/libz.so в ZLIB_ROOT
  SYSROOT="$TOOLCHAIN/sysroot"
  mkdir -p "$DEPS/zlib/include" "$DEPS/zlib/lib"
  ln -sf "$SYSROOT/usr/include/zlib.h" "$SYSROOT/usr/include/zconf.h" "$DEPS/zlib/include/"
  ln -sf "$SYSROOT/usr/lib/$HOST_TRIPLE/$WHEEL_API_LEVEL/libz.so" "$DEPS/zlib/lib/libz.so"
  touch "$DEPS/.done"
fi
ls -la "$DEPS/lib"

say "Pillow $VER (cibuildwheel)"
python3 "$ROOT/build/resolve.py" sdist pillow "$VER" "$SRC" >/dev/null
python3 -m pip install -q "cibuildwheel>=3.2"
export JPEG_ROOT="$DEPS" FREETYPE_ROOT="$DEPS" WEBP_ROOT="$DEPS" ZLIB_ROOT="$DEPS/zlib"
export CIBW_PLATFORM=android
export CIBW_ARCHS="$WHEEL_ARCH"
export CIBW_BUILD="cp${PY_MM/./}-*"
export CIBW_BUILD_FRONTEND=build
export CIBW_TEST_SKIP='*'
# у Pillow в pyproject свои настройки cibuildwheel (сборка зависимостей скриптом из их репозитория, тесты) — гасим
export CIBW_BEFORE_ALL=':'
export CIBW_TEST_COMMAND=''
export CIBW_TEST_REQUIRES=''
export CIBW_TEST_EXTRAS=''
export CIBW_ENVIRONMENT_ANDROID="JPEG_ROOT=$DEPS FREETYPE_ROOT=$DEPS WEBP_ROOT=$DEPS ZLIB_ROOT=$DEPS/zlib"
export CIBW_REPAIR_WHEEL_COMMAND='cp {wheel} {dest_dir}'                # библиотеки форматов лежат в jniLibs, не в колесе
export CIBW_ENVIRONMENT_PASS_LINUX="JPEG_ROOT FREETYPE_ROOT WEBP_ROOT ZLIB_ROOT"
export CIBW_CONFIG_SETTINGS="platform-guessing=disable zlib=enable jpeg=enable freetype=enable webp=enable tiff=disable lcms=disable jpeg2000=disable imagequant=disable xcb=disable avif=disable raqm=disable"
export ANDROID_API_LEVEL="$WHEEL_API_LEVEL"
python3 -m cibuildwheel --output-dir "$OUT/built-pillow" "$SRC/pillow-$VER.tar.gz"
cp "$OUT"/built-pillow/pillow-"$VER"-*.whl "$WHEELS/"

copy_libs
unzip -l "$WHEELS"/pillow-"$VER"-*.whl | grep '\.so'
