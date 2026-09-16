#!/usr/bin/env bash
# Builds piper_jni.cpp + the fork's piper.cpp for the host and runs HostJniCheck against
# a real voice. Verifies the native contract the Android app relies on, no device needed.
#
# Required environment:
#   ESPEAK_BUILD  host espeak-ng build dir (contains src/libespeak-ng/libespeak-ng.so
#                 and espeak-ng-data/)
#   ORT_LINUX     unpacked onnxruntime-linux-x64-<ver> dir (include/ and lib/)
# Optional:
#   MODEL, MODEL_CONFIG  defaults to the fork's etc/test_voice.onnx
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
THIRD_PARTY="$ROOT/android/third_party"
OUT="${OUT:-$HERE/build}"

: "${ESPEAK_BUILD:?set ESPEAK_BUILD to a host espeak-ng build directory}"
: "${ORT_LINUX:?set ORT_LINUX to an unpacked onnxruntime-linux-x64 release}"
MODEL="${MODEL:-$ROOT/etc/test_voice.onnx}"
MODEL_CONFIG="${MODEL_CONFIG:-$ROOT/etc/test_voice.onnx.json}"
JAVA_HOME="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}"

mkdir -p "$OUT/classes"

echo "==> building libpiper_mvp.so for the host"
g++ -std=c++17 -O2 -fPIC -shared -o "$OUT/libpiper_mvp.so" \
  "$ROOT/android/app/src/main/cpp/piper_jni.cpp" \
  "$ROOT/src/cpp/piper.cpp" \
  "$THIRD_PARTY/piper-phonemize/phonemize.cpp" \
  "$THIRD_PARTY/piper-phonemize/phoneme_ids.cpp" \
  "$THIRD_PARTY/piper-phonemize/shared.cpp" \
  "$THIRD_PARTY/piper-phonemize/tashkeel.cpp" \
  -I"$ROOT/src/cpp" \
  -I"$THIRD_PARTY/piper-phonemize" \
  -I"$THIRD_PARTY/spdlog/include" \
  -I"$THIRD_PARTY/espeak-ng/include" \
  -I"$ORT_LINUX/include" \
  -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" \
  -D_PIPER_VERSION="$(cat "$ROOT/VERSION")" \
  -L"$ESPEAK_BUILD/src/libespeak-ng" -lespeak-ng \
  -L"$ORT_LINUX/lib" -lonnxruntime \
  -Wl,-rpath,"$ESPEAK_BUILD/src/libespeak-ng" -Wl,-rpath,"$ORT_LINUX/lib"

echo "==> compiling the harness"
APP_JAVA="$ROOT/android/app/src/main/java/dev/piper/mvp"
javac -d "$OUT/classes" \
  "$APP_JAVA/PiperNative.java" "$APP_JAVA/PcmBuffer.java" "$APP_JAVA/PlaybackTimeline.java" \
  "$HERE/HostJniCheck.java"

echo "==> running"
exec java -cp "$OUT/classes" -Djava.library.path="$OUT" \
  dev.piper.mvp.HostJniCheck "$MODEL" "$MODEL_CONFIG" "$ESPEAK_BUILD/espeak-ng-data"
