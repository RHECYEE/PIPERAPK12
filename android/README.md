# Piper TTS — Android MVP

A minimal debug app around the Piper engine already in this repository. One playback
screen, one settings screen. No library, no playlists, no accounts, no network.

## What was already here (and what wasn't)

This fork is upstream `rhasspy/piper` 1.2.0: a C++ CLI plus Python training code.
**There was no Android code, no Gradle project and no JNI layer** — so there was no
existing "Android → Piper → audio" path to trace. What does exist, and what this app
reuses unchanged, is the engine in `src/cpp/`:

| Question | What `src/cpp/piper.cpp` actually does |
| --- | --- |
| How are models loaded? | `loadVoice()` parses `<model>.onnx.json` (espeak voice, `phoneme_type`, `phoneme_id_map`, `phoneme_map`, `audio.sample_rate`, `inference.*`, `num_speakers`, `speaker_id_map`) then opens the `.onnx` with `Ort::Session`. |
| What is the audio representation? | `std::vector<int16_t>` — mono 16-bit PCM at the model's sample rate. The ONNX float output is peak-normalized per phrase, then converted to int16. |
| Which synthesis parameters are exposed? | `SynthesisConfig`: `noiseScale`, `lengthScale`, `noiseW` (packed into the model's `scales` tensor), `speakerId` (the `sid` tensor), `sentenceSilenceSeconds` (zero samples appended per sentence) and `phonemeSilenceSeconds` (per-phoneme phrase splits, model-config driven). There is **no volume parameter**. |
| Streamed, buffered or written to file? | Both. `textToAudio()` accumulates into a caller-owned buffer; if an `audioCallback` is supplied it fires once per sentence and then **clears the buffer**. `textToWavFile()` writes a WAV. |

### The one conflict with the MVP requirements

Upstream's streaming mode clears its audio buffer after every sentence, so the stream is
gone as soon as it is handed over — nothing to seek through. The smallest fix, and the
only architectural change made, is in `app/src/main/cpp/piper_jni.cpp`: the audio callback
**copies each sentence's PCM out to Java before piper clears it**. Those chunks accumulate
in `PcmBuffer`, so playback keeps the entire generated stream and ±10 s is an exact cursor
move on real audio — never an estimate over characters or words.

`src/cpp/piper.cpp` itself is compiled **unmodified** and is the only inference path.

## Architecture

```
MainActivity ──► PiperEngine ──JNI──► piper_jni.cpp ──► piper.cpp (this repo, unmodified)
                     │                                      ├─► piper-phonemize ─► espeak-ng
                     │  short[] per sentence                └─► onnxruntime (VITS)
                     ▼
                 PcmBuffer  ◄── the full synthesized stream, kept for seeking
                     │
                     ▼
              PlaybackEngine ──► AudioTrack (MODE_STREAM)
                     │
                 PlaybackTimeline  ── the clock; pure arithmetic, unit tested
```

`PlaybackTimeline` tracks two cursors against the cached stream:

* `baseFrame` — the stream frame that AudioTrack's playback head `0` refers to. `flush()`
  zeroes the head, so every seek re-anchors this.
* `writeFrame` — the next frame handed to AudioTrack, running ahead of what is audible.

Audible position is `baseFrame + getPlaybackHeadPosition()`, i.e. what the listener has
actually heard, not what has been queued. That is what makes pause exact (`pause()` stops
the head without dropping the buffer) and resume continue from the same sample. A seek is
`pause()` → `flush()` → re-anchor → `play()`, with a generation counter so a write already
in flight cannot corrupt the new cursor.

## Settings

Every setting maps to something the engine genuinely reads. Nothing was invented.

| Setting | Where it lands |
| --- | --- |
| Voice / model | `loadVoice(modelPath, configPath)` |
| Speech rate (`length_scale`) | `scales[1]` |
| Noise scale (`noise_scale`) | `scales[0]` |
| Noise width (`noise_w`) | `scales[2]` |
| Sentence silence (seconds) | zero samples appended per sentence by `textToAudio()` |
| Speaker | the `sid` tensor; the selector is enabled only when `num_speakers > 1` |
| Playback volume | `AudioTrack.setVolume()` — Piper peak-normalizes and has no gain of its own |

Not exposed: `phoneme_silence` (a per-phoneme map that belongs in the model's JSON rather
than an MVP slider) and CUDA (`useCuda`), which is desktop-only.

Settings persist in `SharedPreferences`. On first run they are seeded from the loaded
model's own `inference` values, so the app starts out behaving exactly like the upstream
CLI for that voice. **Reset to Defaults** restores the current model's declared values.

## Building

```bash
cd android
ANDROID_HOME=/path/to/android-sdk ./gradlew assembleDebug
```

Outputs land in `app/build/outputs/apk/debug/`: per-ABI APKs plus `app-universal-debug.apk`.

Prerequisites: Android SDK with platform 35, build-tools 35, CMake 3.22.1 and NDK
26.3.11579264. Gradle downloads the `onnxruntime-android` AAR and unpacks its headers and
per-ABI `libonnxruntime.so` for the CMake build (`prepareOnnxRuntime`).

`libespeak-ng.so` is vendored prebuilt under `third_party/espeak-ng/jniLibs/` because
building it needs two stages (see `tools/build_espeak_ng.sh` to regenerate). The bundled
voice is the repository's own `etc/test_voice.onnx`, copied into assets at build time, so
no extra model binary is committed. Other Piper voices can be imported from Settings.

## Tests

```bash
./gradlew testDebugUnitTest                 # timeline + PCM cache (19 tests)
ESPEAK_BUILD=... ORT_LINUX=... tools/host_jni_check/run.sh   # the real JNI bridge on the host
```

`tools/host_jni_check` compiles the same `piper_jni.cpp` and `piper.cpp` for the desktop
and drives them from a JVM, so the native contract (chunked callbacks, cancellation,
parameter effects, model metadata) is verifiable without a device.

Note: Piper's VITS duration predictor is stochastic, so the same text yields slightly
different lengths on each run at the default `noise_w`. Checks needing an exact sample
count pin `noise_w=0`, which makes synthesis deterministic.

## Debugging

Everything is instrumented. `piper.cpp`'s own `spdlog` output is routed to logcat, so the
engine's existing tracing shows up alongside the app's.

```bash
adb logcat -s PiperNative PiperEngine PiperPlayback PiperMain PiperAssets PiperVoices PiperSettings
```

| Tag | Covers |
| --- | --- |
| `PiperNative` | espeak init, model load + metadata, settings passed into piper, per-chunk sample counts, synthesis timings, failures (plus all of piper.cpp's own logging) |
| `PiperEngine` | voice load timing, synthesis start/end, cancellation |
| `PiperPlayback` | AudioTrack setup, state changes, seek requests with position before/after and whether it clamped, end-of-stream |
| `PiperMain` | user actions, seek results |
| `PiperAssets` / `PiperVoices` | asset unpacking, voice discovery and import |
