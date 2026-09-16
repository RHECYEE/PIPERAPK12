cmake_minimum_required(VERSION 3.22)
project(piper_mvp CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Repo root: android/app/src/main/cpp -> ../../../../..
get_filename_component(PIPER_REPO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../.." ABSOLUTE)
set(THIRD_PARTY "${PIPER_REPO_ROOT}/android/third_party")

if(NOT DEFINED ORT_ROOT)
  message(FATAL_ERROR "ORT_ROOT not set (Gradle's prepareOnnxRuntime task provides it)")
endif()

file(READ "${PIPER_REPO_ROOT}/VERSION" PIPER_VERSION)
string(STRIP "${PIPER_VERSION}" PIPER_VERSION)

# ---- prebuilt: onnxruntime (unpacked from the onnxruntime-android AAR) ----
add_library(onnxruntime SHARED IMPORTED)
set_target_properties(onnxruntime PROPERTIES
  IMPORTED_LOCATION "${ORT_ROOT}/jni/${ANDROID_ABI}/libonnxruntime.so")

# ---- prebuilt: espeak-ng (cross-compiled from rhasspy/espeak-ng) ----
add_library(espeak-ng SHARED IMPORTED)
set_target_properties(espeak-ng PROPERTIES
  IMPORTED_LOCATION "${THIRD_PARTY}/espeak-ng/jniLibs/${ANDROID_ABI}/libespeak-ng.so")

# ---- piper engine (the fork's own src/cpp/piper.cpp, unmodified) + phonemizer ----
add_library(piper_mvp SHARED
  piper_jni.cpp
  "${PIPER_REPO_ROOT}/src/cpp/piper.cpp"
  "${THIRD_PARTY}/piper-phonemize/phonemize.cpp"
  "${THIRD_PARTY}/piper-phonemize/phoneme_ids.cpp"
  "${THIRD_PARTY}/piper-phonemize/shared.cpp"
  "${THIRD_PARTY}/piper-phonemize/tashkeel.cpp"
)

target_include_directories(piper_mvp PRIVATE
  "${PIPER_REPO_ROOT}/src/cpp"
  "${THIRD_PARTY}/piper-phonemize"
  "${THIRD_PARTY}/spdlog/include"
  "${THIRD_PARTY}/espeak-ng/include"
  "${ORT_ROOT}/headers"
)

target_compile_definitions(piper_mvp PRIVATE _PIPER_VERSION=${PIPER_VERSION})
target_compile_options(piper_mvp PRIVATE -Wall -fexceptions)

target_link_libraries(piper_mvp
  espeak-ng
  onnxruntime
  log
)
