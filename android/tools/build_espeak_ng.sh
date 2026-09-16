#!/usr/bin/env bash
# Reproduces android/third_party/espeak-ng/jniLibs/<abi>/libespeak-ng.so and the
# app/src/main/assets/espeak-ng-data payload.
#
# Two stages are needed because espeak-ng compiles its phoneme and dictionary data by
# running the espeak-ng binary it just built. That binary cannot run when cross-compiling,
# so we build for the host first to generate the data, then cross-compile the library only.
#
#   ANDROID_NDK   path to an Android NDK (tested with 26.3.11579264)
set -euo pipefail

: "${ANDROID_NDK:?set ANDROID_NDK to an Android NDK directory}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
WORK="${WORK:-$HERE/.espeak-build}"
ABIS=(arm64-v8a armeabi-v7a x86_64)

# Pinned to the same commit piper-phonemize uses; it carries
# espeak_TextToPhonemesWithTerminator, which upstream espeak-ng does not have.
ESPEAK_REPO=https://github.com/rhasspy/espeak-ng.git
ESPEAK_COMMIT=0f65aa301e0d6bae5e172cc74197d32a6182200f

COMMON_FLAGS=(
  -DCMAKE_BUILD_TYPE=Release
  -DBUILD_SHARED_LIBS=ON
  -DUSE_ASYNC=OFF -DUSE_MBROLA=OFF -DUSE_LIBSONIC=OFF
  -DUSE_LIBPCAUDIO=OFF -DUSE_KLATT=OFF -DUSE_SPEECHPLAYER=OFF
  -DEXTRA_cmn=ON -DEXTRA_ru=ON -DBUILD_TESTING=OFF
  -DCMAKE_C_FLAGS=-D_FILE_OFFSET_BITS=64
)

mkdir -p "$WORK"
if [ ! -d "$WORK/espeak-ng" ]; then
  git clone "$ESPEAK_REPO" "$WORK/espeak-ng"
fi
git -C "$WORK/espeak-ng" checkout -q "$ESPEAK_COMMIT"
SRC="$WORK/espeak-ng"

echo "==> host build (generates espeak-ng-data)"
cmake -S "$SRC" -B "$SRC/build-host" "${COMMON_FLAGS[@]}"
cmake --build "$SRC/build-host" -j"$(nproc)"

echo "==> installing espeak-ng-data into app assets"
rm -rf "$ROOT/android/app/src/main/assets/espeak-ng-data"
cp -r "$SRC/build-host/espeak-ng-data" "$ROOT/android/app/src/main/assets/espeak-ng-data"

for ABI in "${ABIS[@]}"; do
  echo "==> cross build $ABI (library target only; data is already generated)"
  BUILD="$SRC/build-android-$ABI"
  cmake -S "$SRC" -B "$BUILD" \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ABI" -DANDROID_PLATFORM=android-24 "${COMMON_FLAGS[@]}"
  cmake --build "$BUILD" --target espeak-ng -j"$(nproc)"
  DEST="$ROOT/android/third_party/espeak-ng/jniLibs/$ABI"
  mkdir -p "$DEST"
  find "$BUILD" -name 'libespeak-ng.so*' -exec cp -P {} "$DEST/" \;
done

echo "==> refreshing headers"
cp "$SRC/src/include/espeak-ng/"*.h "$ROOT/android/third_party/espeak-ng/include/espeak-ng/"
cp "$SRC/COPYING" "$ROOT/android/third_party/espeak-ng/COPYING"
echo "done"
