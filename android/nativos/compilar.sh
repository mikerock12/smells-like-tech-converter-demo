#!/usr/bin/env bash
# Compila o FFmpeg do app para Android, para uma ABI, a partir dos códigos-fonte oficiais.
#
#   bash android/nativos/compilar.sh arm64-v8a      (ou armeabi-v7a, x86_64)
#
# Sai um executável só, saida/<abi>/libffmpeg.so: o FFmpeg com os mesmos codecs do
# aplicativo para Windows e o filtro "whisper" (o whisper.cpp embutido), que transcreve
# como no desktop. O nome "lib*.so" é o que faz o Android extraí-lo, com permissão de
# execução, para a pasta das bibliotecas nativas do app.
#
# Licenças: tudo aqui é LGPL ou mais permissivo. Não há --enable-gpl (nada de x264/x265):
# o H.264 e o H.265 saem pelo codificador do próprio celular (MediaCodec).
#
# Roda num Linux x86_64 com o NDK (ANDROID_NDK_HOME), nasm, autoconf, automake, libtool,
# pkg-config, cmake e ninja. O GitHub Actions (.github/workflows/android-nativos.yml)
# roda uma ABI por máquina.
set -euo pipefail

ABI="${1:?informe a ABI: arm64-v8a, armeabi-v7a ou x86_64}"
API=29
NDK="${ANDROID_NDK_HOME:?defina ANDROID_NDK_HOME}"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
RAIZ="$(cd "$(dirname "$0")" && pwd)"
TRABALHO="${TRABALHO:-$RAIZ/.trabalho}"
FONTES="$TRABALHO/fontes"
OBRA="$TRABALHO/obra/$ABI"
PREFIXO="$TRABALHO/prefixo/$ABI"
SAIDA="$RAIZ/saida/$ABI"
TAREFAS="$(nproc)"

case "$ABI" in
  arm64-v8a)
    TRIPLA=aarch64-linux-android; HOST=aarch64-linux-android; ARCH=aarch64; CPU=armv8-a
    VPX=arm64-android-gcc; AOM=arm64; CFLAGS_ABI=""; FFMPEG_ABI=(--enable-neon)
    PAGINA="-Wl,-z,max-page-size=16384" ;;
  armeabi-v7a)
    TRIPLA=armv7a-linux-androideabi; HOST=arm-linux-androideabi; ARCH=arm; CPU=armv7-a
    VPX=armv7-android-gcc; AOM=armv7; CFLAGS_ABI="-march=armv7-a -mfpu=neon -mfloat-abi=softfp"
    FFMPEG_ABI=(--enable-neon --enable-thumb); PAGINA="" ;;
  x86_64)
    TRIPLA=x86_64-linux-android; HOST=x86_64-linux-android; ARCH=x86_64; CPU=x86-64
    VPX=x86_64-android-gcc; AOM=x86_64; CFLAGS_ABI=""; FFMPEG_ABI=(--x86asmexe=nasm)
    PAGINA="-Wl,-z,max-page-size=16384" ;;
  *) echo "ABI desconhecida: $ABI" >&2; exit 1 ;;
esac

export CC="$TOOLCHAIN/bin/${TRIPLA}${API}-clang"
export CXX="$TOOLCHAIN/bin/${TRIPLA}${API}-clang++"
export AR="$TOOLCHAIN/bin/llvm-ar"
export NM="$TOOLCHAIN/bin/llvm-nm"
export RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
export STRIP="$TOOLCHAIN/bin/llvm-strip"
export LD="$CC"
export CFLAGS="-O3 -fPIC $CFLAGS_ABI"
export CXXFLAGS="$CFLAGS"
export PKG_CONFIG_PATH="$PREFIXO/lib/pkgconfig"
export PKG_CONFIG_LIBDIR="$PREFIXO/lib/pkgconfig"

CMAKE_ANDROID=(
  -G Ninja
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake"
  -DANDROID_ABI="$ABI" -DANDROID_PLATFORM="android-$API" -DANDROID_ARM_NEON=ON
  -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$PREFIXO" -DCMAKE_INSTALL_LIBDIR=lib
  -DBUILD_SHARED_LIBS=OFF -DCMAKE_POSITION_INDEPENDENT_CODE=ON
)

mkdir -p "$FONTES" "$OBRA" "$PREFIXO" "$SAIDA"

# Baixa e confere cada código-fonte. O SHA-256 foi tirado dos arquivos oficiais; um
# arquivo diferente para a compilação.
baixar() {
  local nome="$1" url="$2" hash="$3" arquivo="$FONTES/$1"
  if [[ ! -f "$arquivo" ]]; then curl -fsSL --retry 5 -o "$arquivo" "$url"; fi
  echo "$hash  $arquivo" | sha256sum -c --quiet -
}

desempacotar() {
  local arquivo="$FONTES/$1" pasta="$OBRA/$2"
  rm -rf "$pasta" && mkdir -p "$pasta"
  tar -xf "$arquivo" -C "$pasta" --strip-components=1
}

baixar ffmpeg.tar.xz https://ffmpeg.org/releases/ffmpeg-8.1.3.tar.xz 7138d28c96d9d3e3af4ee3d8cad72741f8ffb40da90c1112235dea3ecd3178a3
baixar whisper.tar.gz https://github.com/ggml-org/whisper.cpp/archive/refs/tags/v1.9.4.tar.gz 57e280cee375ab02425b806ad5146b99f6eb9357e3c2b31357c8a6af2e2e44ae
baixar lame.tar.gz https://downloads.sourceforge.net/project/lame/lame/3.100/lame-3.100.tar.gz ddfe36cab873794038ae2c1210557ad34857a4b6bdc515785d1da9e175b1da1e
baixar opus.tar.gz https://downloads.xiph.org/releases/opus/opus-1.6.1.tar.gz 6ffcb593207be92584df15b32466ed64bbec99109f007c82205f0194572411a1
baixar ogg.tar.xz https://downloads.xiph.org/releases/ogg/libogg-1.3.6.tar.xz 5c8253428e181840cd20d41f3ca16557a9cc04bad4a3d04cce84808677fa1061
baixar vorbis.tar.xz https://downloads.xiph.org/releases/vorbis/libvorbis-1.3.7.tar.xz b33cc4934322bcbf6efcbacf49e3ca01aadbea4114ec9589d1b1e9d20f72954b
baixar vpx.tar.gz https://github.com/webmproject/libvpx/archive/refs/tags/v1.17.0.tar.gz 1020f184046187baa2985dbde38e0691f49c44088bca7a1842b0236c6081dc0a
baixar aom.tar.gz https://storage.googleapis.com/aom-releases/libaom-3.15.1.tar.gz 8ca0c52746174603500f0adb6f2a215d69c9ca2aab2acb3caa06fb791d8d01bf

echo "::group::LAME (MP3)"
desempacotar lame.tar.gz lame
(
  cd "$OBRA/lame"
  # O símbolo não existe mais e quebra o link com toolchains novas.
  sed -i '/lame_init_old/d' include/libmp3lame.sym
  ./configure --host="$HOST" --prefix="$PREFIXO" --enable-static --disable-shared --with-pic \
    --disable-frontend --disable-decoder --disable-gtktest
  make -j"$TAREFAS" && make install
)
echo "::endgroup::"

echo "::group::Ogg"
desempacotar ogg.tar.xz ogg
(
  cd "$OBRA/ogg"
  ./configure --host="$HOST" --prefix="$PREFIXO" --enable-static --disable-shared --with-pic
  make -j"$TAREFAS" && make install
)
echo "::endgroup::"

echo "::group::Vorbis (OGG)"
desempacotar vorbis.tar.xz vorbis
(
  cd "$OBRA/vorbis"
  # O clang não conhece esta opção que o configure põe para x86.
  sed -i 's/-mno-ieee-fp//g' configure
  ./configure --host="$HOST" --prefix="$PREFIXO" --enable-static --disable-shared --with-pic \
    --with-ogg="$PREFIXO" --disable-oggtest --disable-docs --disable-examples
  make -j"$TAREFAS" && make install
)
echo "::endgroup::"

echo "::group::Opus"
desempacotar opus.tar.gz opus
cmake -S "$OBRA/opus" -B "$OBRA/opus/obra" "${CMAKE_ANDROID[@]}" \
  -DOPUS_BUILD_PROGRAMS=OFF -DOPUS_BUILD_TESTING=OFF -DBUILD_TESTING=OFF
cmake --build "$OBRA/opus/obra" -j"$TAREFAS" && cmake --install "$OBRA/opus/obra"
echo "::endgroup::"

echo "::group::libvpx (WEBM: VP8 e VP9)"
desempacotar vpx.tar.gz vpx
(
  cd "$OBRA/vpx"
  EXTRA_VPX=()
  if [[ "$ABI" == armeabi-v7a ]]; then
    EXTRA_VPX=(--enable-neon --disable-runtime-cpu-detect)
    # O assembly NEON do libvpx vai para o "as" da plataforma; o NDK não tem mais o GNU as,
    # e sem isto o configure pegaria o do Linux (x86). O clang monta o .S.
    export AS="$CC -c"
  fi
  [[ "$ABI" == x86_64 ]] && EXTRA_VPX=(--as=nasm)
  ./configure --target="$VPX" --prefix="$PREFIXO" \
    --enable-pic --enable-static --disable-shared --disable-examples --disable-tools --disable-docs \
    --disable-unit-tests --disable-webm-io --disable-libyuv --enable-vp8 --enable-vp9 \
    "${EXTRA_VPX[@]}"
  make -j"$TAREFAS" && make install
)
echo "::endgroup::"

echo "::group::libaom (AVIF)"
desempacotar aom.tar.gz aom
AOM_EXTRA=()
[[ "$ABI" == x86_64 ]] && AOM_EXTRA=(-DENABLE_NASM=ON)
cmake -S "$OBRA/aom" -B "$OBRA/aom/obra" "${CMAKE_ANDROID[@]}" \
  -DAOM_TARGET_CPU="$AOM" -DCONFIG_AV1_DECODER=0 -DENABLE_DOCS=0 -DENABLE_EXAMPLES=0 \
  -DENABLE_TESTDATA=0 -DENABLE_TESTS=0 -DENABLE_TOOLS=0 "${AOM_EXTRA[@]}"
cmake --build "$OBRA/aom/obra" -j"$TAREFAS" && cmake --install "$OBRA/aom/obra"
echo "::endgroup::"

echo "::group::whisper.cpp"
desempacotar whisper.tar.gz whisper
# Sem otimização para um processador específico: o mesmo executável roda em qualquer
# celular da ABI (um Cortex-A53 antigo não tem as instruções dos novos).
cmake -S "$OBRA/whisper" -B "$OBRA/whisper/obra" "${CMAKE_ANDROID[@]}" \
  -DWHISPER_BUILD_EXAMPLES=OFF -DWHISPER_BUILD_TESTS=OFF -DWHISPER_BUILD_SERVER=OFF \
  -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF -DGGML_BACKEND_DL=OFF
cmake --build "$OBRA/whisper/obra" -j"$TAREFAS" && cmake --install "$OBRA/whisper/obra"
# O whisper.pc instalado não lista as bibliotecas do ggml na ordem que um link estático
# precisa. Esta versão lista tudo (duas vezes: as bibliotecas do ggml se chamam em ciclo).
BIBLIOTECAS_GGML="$(cd "$PREFIXO/lib" && ls libggml*.a | sed -E 's/^lib(.*)\.a$/-l\1/' | tr '\n' ' ')"
cat > "$PREFIXO/lib/pkgconfig/whisper.pc" <<PC
prefix=$PREFIXO
libdir=\${prefix}/lib
includedir=\${prefix}/include

Name: whisper
Description: whisper.cpp
Version: 1.9.4
Libs: -L\${libdir} -lwhisper $BIBLIOTECAS_GGML -lwhisper $BIBLIOTECAS_GGML
Libs.private: -lc++_static -lc++abi -lm -ldl
Cflags: -I\${includedir}
PC
echo "::endgroup::"

echo "::group::FFmpeg"
desempacotar ffmpeg.tar.xz ffmpeg
(
  cd "$OBRA/ffmpeg"
  ./configure \
    --prefix="$PREFIXO" --target-os=android --enable-cross-compile --arch="$ARCH" --cpu="$CPU" \
    --cc="$CC" --cxx="$CXX" --ar="$AR" --nm="$NM" --ranlib="$RANLIB" --strip="$STRIP" \
    --sysroot="$TOOLCHAIN/sysroot" --pkg-config=pkg-config --pkg-config-flags=--static \
    --extra-cflags="-I$PREFIXO/include $CFLAGS" \
    --extra-ldflags="-L$PREFIXO/lib -pie $PAGINA" \
    --extra-libs="-lc++_static -lc++abi -lm -ldl" \
    --enable-pic --enable-static --disable-shared \
    --disable-ffplay --disable-ffprobe --disable-doc --disable-debug \
    --disable-network --disable-indevs --disable-outdevs --disable-vulkan \
    --enable-libmp3lame --enable-libopus --enable-libvorbis --enable-libvpx --enable-libaom \
    --enable-whisper --enable-jni --enable-mediacodec     --disable-decoder=libaom_av1 \
    "${FFMPEG_ABI[@]}"
  make -j"$TAREFAS"
  "$STRIP" ffmpeg
  cp ffmpeg "$SAIDA/libffmpeg.so"
)
echo "::endgroup::"

# O que o executável pede ao sistema: só bibliotecas que todo Android tem.
"$TOOLCHAIN/bin/llvm-readelf" -d "$SAIDA/libffmpeg.so" | grep NEEDED
ls -l "$SAIDA/libffmpeg.so"
