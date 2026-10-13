#!/usr/bin/env bash
# ffmpeg + ffprobe под Android для APK (кружки, голосовые, видео-стикеры, музыка — модули и ядро FRX зовут ffmpeg).
# Кодеки как на ПК: libx264, libvpx (VP9 для видео-стикеров), libmp3lame, libopus, встроенный AAC.
# Готовые программы кладутся в jniLibs как libffmpeg.so / libffprobe.so: Android 10+ запускает только оттуда;
# приложение делает на них ссылки files/bin/ffmpeg и files/bin/ffprobe (PATH), и FRX находит их как обычно.
#
# Лицензия: с libx264 сборка ffmpeg — GPL. Исходники всех частей — по ссылкам ниже (версии зафиксированы),
# список с лицензиями кладётся в APK: assets/licenses/ffmpeg.txt.
. "$(dirname "$0")/common.sh"

FFMPEG_VER=7.1.1
X264_REF=stable                # ветка x264 (у x264 нет релизов-архивов)
VPX_VER=1.14.1
LAME_VER=3.100
OPUS_VER=1.5.2
BUILD_REV=2                    # поменять, чтобы пересобрать (кэш CI)

P="$OUT/ffmpeg-prefix"
SRC="$OUT/src-ffmpeg"; mkdir -p "$SRC"
JNI="$APP/jniLibs/$ABI"; mkdir -p "$JNI"
LIC="$APP/assets/licenses"; mkdir -p "$LIC"

write_license() {
  cat > "$LIC/ffmpeg.txt" <<EOF
ffmpeg $FFMPEG_VER (GPL v2+, собран с --enable-gpl) — https://ffmpeg.org/releases/ffmpeg-$FFMPEG_VER.tar.xz
x264 ($X264_REF, GPL v2+) — https://code.videolan.org/videolan/x264/-/tree/$X264_REF
libvpx $VPX_VER (BSD) — https://github.com/webmproject/libvpx/archive/refs/tags/v$VPX_VER.tar.gz
LAME $LAME_VER (LGPL) — https://downloads.sourceforge.net/project/lame/lame/$LAME_VER/lame-$LAME_VER.tar.gz
Opus $OPUS_VER (BSD) — https://downloads.xiph.org/releases/opus/opus-$OPUS_VER.tar.gz
Сборка: build/build_ffmpeg.sh в https://github.com/Shxdowrate/FRX-Userbot-APK
EOF
}

copy_out() {
  cp "$P/bin/ffmpeg" "$JNI/libffmpeg.so"
  cp "$P/bin/ffprobe" "$JNI/libffprobe.so"
  "$STRIP" --strip-unneeded "$JNI/libffmpeg.so" "$JNI/libffprobe.so"
  write_license
  ls -la "$JNI/libffmpeg.so" "$JNI/libffprobe.so"
}

if [[ -x "$P/bin/ffmpeg" && -f "$P/.version" && "$(cat "$P/.version")" == "$FFMPEG_VER-$X264_REF-$VPX_VER-$LAME_VER-$OPUS_VER-$BUILD_REV" ]]; then
  say "ffmpeg уже собран"; copy_out; exit 0
fi
rm -rf "$P"; mkdir -p "$P"

command -v nasm >/dev/null || sudo apt-get install -y -q nasm >/dev/null
python3 -m pip install -q ninja cmake meson
case "$ABI" in
  arm64-v8a) FF_ARCH=aarch64; FF_CPU=armv8-a; VPX_TARGET=arm64-android-gcc ;;
  x86_64)    FF_ARCH=x86_64;  FF_CPU=x86-64;  VPX_TARGET=x86_64-android-gcc ;;
esac
SYSROOT="$TOOLCHAIN/sysroot"
export CC="$CC_WHEEL" CXX="$CXX_WHEEL" AR NM="$TOOLCHAIN/bin/llvm-nm" RANLIB="$TOOLCHAIN/bin/llvm-ranlib" STRIP
export PKG_CONFIG_PATH="$P/lib/pkgconfig" PKG_CONFIG_LIBDIR="$P/lib/pkgconfig"

fetch() {  # url dir
  local f="$SRC/$(basename "$1")"
  [[ -f "$f" ]] || curl -fL --retry 3 -o "$f" "$1"
  rm -rf "$SRC/$2"; mkdir -p "$SRC/$2"; tar -xf "$f" -C "$SRC/$2" --strip-components=1
}

say "x264"
rm -rf "$SRC/x264"
git clone -q --depth 1 -b "$X264_REF" https://code.videolan.org/videolan/x264.git "$SRC/x264"
(cd "$SRC/x264" && ./configure --prefix="$P" --host="$HOST_TRIPLE" --enable-static --enable-pic --disable-cli \
   --extra-cflags="-fPIC" && make -j"$(nproc)" && make install)

say "libvpx $VPX_VER"
fetch "https://github.com/webmproject/libvpx/archive/refs/tags/v$VPX_VER.tar.gz" vpx
(cd "$SRC/vpx" && CROSS="" AS="$([[ $ABI == x86_64 ]] && echo nasm || echo "$CC")" LD="$CC" \
   ./configure --prefix="$P" --target="$VPX_TARGET" --enable-pic --enable-static --disable-shared \
   --disable-examples --disable-tools --disable-docs --disable-unit-tests --enable-vp9 --enable-vp8 \
   --enable-runtime-cpu-detect --extra-cflags="-fPIC" && make -j"$(nproc)" && make install)

say "LAME $LAME_VER"
fetch "https://downloads.sourceforge.net/project/lame/lame/$LAME_VER/lame-$LAME_VER.tar.gz" lame
(cd "$SRC/lame" && ./configure --prefix="$P" --host="$HOST_TRIPLE" --enable-static --disable-shared \
   --disable-frontend --disable-decoder --with-pic && make -j"$(nproc)" && make install)

say "Opus $OPUS_VER"
fetch "https://downloads.xiph.org/releases/opus/opus-$OPUS_VER.tar.gz" opus
cmake -S "$SRC/opus" -B "$SRC/opus/b" -G Ninja -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI="$ABI" -DANDROID_PLATFORM="android-$WHEEL_API_LEVEL" -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_INSTALL_PREFIX="$P" -DCMAKE_INSTALL_LIBDIR=lib -DBUILD_SHARED_LIBS=OFF -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
  -DOPUS_BUILD_PROGRAMS=OFF -DOPUS_BUILD_TESTING=OFF
cmake --build "$SRC/opus/b" -j"$(nproc)" && cmake --install "$SRC/opus/b"

say "ffmpeg $FFMPEG_VER"
fetch "https://ffmpeg.org/releases/ffmpeg-$FFMPEG_VER.tar.xz" ffmpeg
(cd "$SRC/ffmpeg" && ./configure --prefix="$P" --target-os=android --arch="$FF_ARCH" --cpu="$FF_CPU" \
   --enable-cross-compile --cc="$CC" --cxx="$CXX" --nm="$NM" --ar="$AR" --ranlib="$RANLIB" --strip="$STRIP" \
   --sysroot="$SYSROOT" --pkg-config=pkg-config --pkg-config-flags=--static \
   --enable-pic --enable-gpl --disable-doc --disable-debug --disable-ffplay \
   --disable-autodetect --enable-zlib --enable-libx264 --enable-libvpx --enable-libmp3lame --enable-libopus \
   --enable-static --disable-shared \
   --extra-cflags="-I$P/include -fPIC" --extra-ldflags="-L$P/lib -pie" --extra-libs="-lm" \
   && make -j"$(nproc)" && make install)

echo "$FFMPEG_VER-$X264_REF-$VPX_VER-$LAME_VER-$OPUS_VER-$BUILD_REV" > "$P/.version"
copy_out
