// JNI bridge around the fork's existing engine (src/cpp/piper.cpp).
//
// Nothing in piper.cpp is modified. This file only:
//   * routes piper's existing spdlog instrumentation into logcat,
//   * exposes loadVoice/textToAudio to Java,
//   * copies each synthesized sentence chunk out of piper's audioBuffer
//     before textToAudio clears it, so the app can cache the whole PCM
//     stream and seek deterministically.

#include <jni.h>

#include <cstdio>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include <spdlog/spdlog.h>

#if defined(__ANDROID__)
#include <android/log.h>
#include <spdlog/sinks/android_sink.h>
#else
// Host builds: android/tools/host_jni_check runs this exact file on the desktop JVM
// so the native contract can be tested without a device.
#include <spdlog/sinks/stdout_sinks.h>
#endif

#include "piper.hpp"

namespace {

constexpr const char *kTag = "PiperNative";

#if defined(__ANDROID__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, kTag, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, kTag, __VA_ARGS__)
#else
#define LOGI(...)                                                                              \
  (std::fprintf(stderr, "[I/%s] ", kTag), std::fprintf(stderr, __VA_ARGS__),                    \
   std::fprintf(stderr, "\n"))
#define LOGE(...)                                                                              \
  (std::fprintf(stderr, "[E/%s] ", kTag), std::fprintf(stderr, __VA_ARGS__),                    \
   std::fprintf(stderr, "\n"))
#endif

// Thrown out of the audio callback to abort textToAudio early. piper.cpp is
// exception-safe (RAII only), so this unwinds cleanly without touching it.
struct SynthesisCancelled {};

std::mutex gMutex;
std::unique_ptr<piper::PiperConfig> gConfig;

std::string toStdString(JNIEnv *env, jstring s) {
  if (s == nullptr) return {};
  const char *chars = env->GetStringUTFChars(s, nullptr);
  std::string out(chars ? chars : "");
  if (chars) env->ReleaseStringUTFChars(s, chars);
  return out;
}

void throwJava(JNIEnv *env, const std::string &msg) {
  jclass cls = env->FindClass("java/lang/RuntimeException");
  if (cls != nullptr) env->ThrowNew(cls, msg.c_str());
}

} // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_dev_piper_mvp_PiperNative_nativeInit(JNIEnv *env, jclass, jstring jEspeakDataPath) {
  std::lock_guard<std::mutex> lock(gMutex);
  const std::string espeakDataPath = toStdString(env, jEspeakDataPath);

  // Send piper's own spdlog output to logcat so the engine's existing
  // instrumentation (model load, phonemes, sample counts, timings) is visible.
  static bool loggingReady = false;
  if (!loggingReady) {
#if defined(__ANDROID__)
    auto sink = std::make_shared<spdlog::sinks::android_sink_mt>(kTag);
#else
    auto sink = std::make_shared<spdlog::sinks::stderr_sink_mt>();
#endif
    auto logger = std::make_shared<spdlog::logger>("piper", sink);
    logger->set_level(spdlog::level::debug);
    spdlog::set_default_logger(logger);
    spdlog::set_level(spdlog::level::debug);
    loggingReady = true;
  }

  if (gConfig) {
    LOGI("nativeInit: already initialized");
    return JNI_TRUE;
  }

  LOGI("nativeInit: espeakDataPath=%s", espeakDataPath.c_str());
  try {
    auto config = std::make_unique<piper::PiperConfig>();
    config->eSpeakDataPath = espeakDataPath;
    config->useESpeak = true;
    config->useTashkeel = false; // Arabic diacritization model is not bundled
    piper::initialize(*config);
    gConfig = std::move(config);
    LOGI("nativeInit: piper %s initialized", piper::getVersion().c_str());
    return JNI_TRUE;
  } catch (const std::exception &e) {
    LOGE("nativeInit FAILED: %s", e.what());
    throwJava(env, std::string("piper initialize failed: ") + e.what());
    return JNI_FALSE;
  }
}

JNIEXPORT void JNICALL
Java_dev_piper_mvp_PiperNative_nativeTerminate(JNIEnv *, jclass) {
  std::lock_guard<std::mutex> lock(gMutex);
  if (!gConfig) return;
  LOGI("nativeTerminate");
  piper::terminate(*gConfig);
  gConfig.reset();
}

JNIEXPORT jlong JNICALL
Java_dev_piper_mvp_PiperNative_nativeLoadVoice(JNIEnv *env, jclass, jstring jModelPath,
                                               jstring jConfigPath) {
  std::lock_guard<std::mutex> lock(gMutex);
  if (!gConfig) {
    throwJava(env, "piper not initialized");
    return 0;
  }
  const std::string modelPath = toStdString(env, jModelPath);
  const std::string configPath = toStdString(env, jConfigPath);
  LOGI("nativeLoadVoice: model=%s config=%s", modelPath.c_str(), configPath.c_str());

  try {
    auto voice = std::make_unique<piper::Voice>();
    std::optional<piper::SpeakerId> speakerId;
    piper::loadVoice(*gConfig, modelPath, configPath, *voice, speakerId, /*useCuda=*/false);
    const auto &sc = voice->synthesisConfig;
    LOGI("nativeLoadVoice OK: sampleRate=%d channels=%d speakers=%d "
         "modelDefaults{noiseScale=%.4f lengthScale=%.4f noiseW=%.4f sentenceSilence=%.4f}",
         sc.sampleRate, sc.channels, voice->modelConfig.numSpeakers, sc.noiseScale,
         sc.lengthScale, sc.noiseW, sc.sentenceSilenceSeconds);
    return reinterpret_cast<jlong>(voice.release());
  } catch (const std::exception &e) {
    LOGE("nativeLoadVoice FAILED: %s", e.what());
    throwJava(env, std::string("load voice failed: ") + e.what());
    return 0;
  }
}

JNIEXPORT void JNICALL
Java_dev_piper_mvp_PiperNative_nativeFreeVoice(JNIEnv *, jclass, jlong handle) {
  if (handle == 0) return;
  LOGI("nativeFreeVoice: handle=%p", reinterpret_cast<void *>(handle));
  delete reinterpret_cast<piper::Voice *>(handle);
}

JNIEXPORT jint JNICALL
Java_dev_piper_mvp_PiperNative_nativeGetSampleRate(JNIEnv *, jclass, jlong handle) {
  if (handle == 0) return 0;
  return reinterpret_cast<piper::Voice *>(handle)->synthesisConfig.sampleRate;
}

JNIEXPORT jint JNICALL
Java_dev_piper_mvp_PiperNative_nativeGetNumSpeakers(JNIEnv *, jclass, jlong handle) {
  if (handle == 0) return 0;
  return reinterpret_cast<piper::Voice *>(handle)->modelConfig.numSpeakers;
}

// Model's own defaults, as parsed from <model>.onnx.json by piper.cpp:
// [noiseScale, lengthScale, noiseW, sentenceSilenceSeconds]
JNIEXPORT jfloatArray JNICALL
Java_dev_piper_mvp_PiperNative_nativeGetModelDefaults(JNIEnv *env, jclass, jlong handle) {
  if (handle == 0) return nullptr;
  const auto &sc = reinterpret_cast<piper::Voice *>(handle)->synthesisConfig;
  jfloat values[4] = {sc.noiseScale, sc.lengthScale, sc.noiseW, sc.sentenceSilenceSeconds};
  jfloatArray out = env->NewFloatArray(4);
  if (out != nullptr) env->SetFloatArrayRegion(out, 0, 4, values);
  return out;
}

// Speaker names for multi-speaker models, newline separated as "<id>\t<name>".
JNIEXPORT jstring JNICALL
Java_dev_piper_mvp_PiperNative_nativeGetSpeakerNames(JNIEnv *env, jclass, jlong handle) {
  if (handle == 0) return env->NewStringUTF("");
  auto *voice = reinterpret_cast<piper::Voice *>(handle);
  std::string out;
  if (voice->modelConfig.speakerIdMap) {
    for (const auto &entry : *voice->modelConfig.speakerIdMap) {
      out += std::to_string(entry.second) + "\t" + entry.first + "\n";
    }
  }
  return env->NewStringUTF(out.c_str());
}

// Synthesize `text`, handing each sentence's PCM to `sink.onPcmChunk(short[])`.
// The sink returns false to cancel. Returns the total number of samples produced.
JNIEXPORT jint JNICALL
Java_dev_piper_mvp_PiperNative_nativeSynthesize(JNIEnv *env, jclass, jlong handle, jstring jText,
                                                jfloat noiseScale, jfloat lengthScale,
                                                jfloat noiseW, jfloat sentenceSilence,
                                                jint speakerId, jobject sink) {
  if (handle == 0) {
    throwJava(env, "no voice loaded");
    return -1;
  }
  std::lock_guard<std::mutex> lock(gMutex);
  if (!gConfig) {
    throwJava(env, "piper not initialized");
    return -1;
  }

  auto *voice = reinterpret_cast<piper::Voice *>(handle);
  const std::string text = toStdString(env, jText);

  // Settings actually passed into piper for this run.
  LOGI("nativeSynthesize: chars=%zu noiseScale=%.4f lengthScale=%.4f noiseW=%.4f "
       "sentenceSilence=%.4f speakerId=%d",
       text.size(), noiseScale, lengthScale, noiseW, sentenceSilence, speakerId);

  voice->synthesisConfig.noiseScale = noiseScale;
  voice->synthesisConfig.lengthScale = lengthScale;
  voice->synthesisConfig.noiseW = noiseW;
  voice->synthesisConfig.sentenceSilenceSeconds = sentenceSilence;
  if (voice->modelConfig.numSpeakers > 1) {
    voice->synthesisConfig.speakerId = static_cast<piper::SpeakerId>(speakerId);
  }

  jclass sinkClass = env->GetObjectClass(sink);
  jmethodID onChunk = env->GetMethodID(sinkClass, "onPcmChunk", "([S)Z");
  if (onChunk == nullptr) {
    throwJava(env, "sink.onPcmChunk([S)Z not found");
    return -1;
  }

  std::vector<int16_t> audioBuffer;
  piper::SynthesisResult result{};
  jint totalSamples = 0;
  int chunkIndex = 0;

  // textToAudio clears audioBuffer after each callback, so copy it out here.
  // This is what makes the stream cacheable and therefore seekable.
  auto audioCallback = [&]() {
    const auto count = static_cast<jsize>(audioBuffer.size());
    chunkIndex++;
    if (count == 0) return;

    jshortArray chunk = env->NewShortArray(count);
    if (chunk == nullptr) throw std::runtime_error("out of memory allocating PCM chunk");
    env->SetShortArrayRegion(chunk, 0, count, audioBuffer.data());
    jboolean keepGoing = env->CallBooleanMethod(sink, onChunk, chunk);
    env->DeleteLocalRef(chunk);

    if (env->ExceptionCheck()) throw SynthesisCancelled{};
    totalSamples += count;
    LOGI("synthesis chunk %d: samples=%d total=%d", chunkIndex, count, totalSamples);
    if (!keepGoing) {
      LOGI("synthesis cancelled by sink after chunk %d", chunkIndex);
      throw SynthesisCancelled{};
    }
  };

  try {
    piper::textToAudio(*gConfig, *voice, text, audioBuffer, result, audioCallback);
  } catch (const SynthesisCancelled &) {
    LOGI("nativeSynthesize: cancelled, samples=%d", totalSamples);
    return totalSamples;
  } catch (const std::exception &e) {
    LOGE("nativeSynthesize FAILED: %s", e.what());
    if (!env->ExceptionCheck()) throwJava(env, std::string("synthesis failed: ") + e.what());
    return -1;
  }

  LOGI("nativeSynthesize done: samples=%d audioSeconds=%.3f inferSeconds=%.3f rtf=%.3f",
       totalSamples, result.audioSeconds, result.inferSeconds, result.realTimeFactor);
  return totalSamples;
}

} // extern "C"
