package dev.piper.mvp;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Owns the native Piper lifecycle and runs synthesis off the UI thread.
 *
 * <p>Synthesis streams: piper.cpp hands back one sentence at a time, and each chunk goes
 * straight into the playback cache, so audio can start before the whole text is done
 * while still leaving the full stream seekable.
 */
final class PiperEngine {

    static final String TAG = "PiperEngine";

    interface Listener {
        void onVoiceLoaded(VoiceRepository.Voice voice, int sampleRate, int numSpeakers,
                           float[] modelDefaults, String[] speakerNames);

        void onSynthesisChunk(int chunkSamples, long totalFrames);

        void onSynthesisFinished(int totalSamples, boolean cancelled);

        void onError(String message, Throwable cause);
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor(
            r -> new Thread(r, "piper-native"));
    private final Handler main = new Handler(Looper.getMainLooper());
    // Every synthesis run gets a token. Bumping it both cancels the run in flight and
    // makes its remaining chunks and callbacks no-ops, so a superseded run can never
    // append audio to, or finish, the run that replaced it.
    private final AtomicInteger currentRun = new AtomicInteger();

    private Listener listener;
    private volatile boolean initialized;
    private volatile long voiceHandle;
    private volatile VoiceRepository.Voice currentVoice;
    private volatile int sampleRate;
    private volatile int numSpeakers = 1;
    private volatile float[] modelDefaults;

    void setListener(Listener listener) {
        this.listener = listener;
    }

    boolean isReady() {
        return initialized && voiceHandle != 0L;
    }

    int sampleRate() {
        return sampleRate;
    }

    int numSpeakers() {
        return numSpeakers;
    }

    float[] modelDefaults() {
        return modelDefaults;
    }

    VoiceRepository.Voice currentVoice() {
        return currentVoice;
    }

    void initAndLoad(File espeakDataDir, VoiceRepository.Voice voice) {
        executor.execute(() -> {
            try {
                Throwable loadError = PiperNative.loadError();
                if (loadError != null) {
                    throw new IllegalStateException("native library not loaded", loadError);
                }
                if (!initialized) {
                    Log.i(TAG, "initializing piper, espeakData=" + espeakDataDir);
                    PiperNative.nativeInit(espeakDataDir.getAbsolutePath());
                    initialized = true;
                }
                loadVoiceLocked(voice);
            } catch (Throwable t) {
                Log.e(TAG, "init/load failed", t);
                postError("Could not start Piper: " + t.getMessage(), t);
            }
        });
    }

    void loadVoice(VoiceRepository.Voice voice) {
        executor.execute(() -> {
            try {
                loadVoiceLocked(voice);
            } catch (Throwable t) {
                Log.e(TAG, "load voice failed", t);
                postError("Could not load voice: " + t.getMessage(), t);
            }
        });
    }

    private void loadVoiceLocked(VoiceRepository.Voice voice) {
        if (voice == null) {
            postError("No Piper voice available", null);
            return;
        }
        long previous = voiceHandle;
        Log.i(TAG, "loading voice " + voice.id + " model=" + voice.model
                + " (" + voice.model.length() + " bytes)");
        long started = System.currentTimeMillis();
        long handle = PiperNative.nativeLoadVoice(voice.model.getAbsolutePath(),
                voice.config.getAbsolutePath());
        if (handle == 0L) {
            postError("Piper returned no voice handle for " + voice.id, null);
            return;
        }
        voiceHandle = handle;
        currentVoice = voice;
        sampleRate = PiperNative.nativeGetSampleRate(handle);
        numSpeakers = PiperNative.nativeGetNumSpeakers(handle);
        modelDefaults = PiperNative.nativeGetModelDefaults(handle);
        String[] speakerNames = parseSpeakers(PiperNative.nativeGetSpeakerNames(handle));
        if (previous != 0L) {
            PiperNative.nativeFreeVoice(previous);
        }
        Log.i(TAG, "voice loaded in " + (System.currentTimeMillis() - started) + " ms: "
                + voice.id + " sampleRate=" + sampleRate + " numSpeakers=" + numSpeakers);

        VoiceRepository.Voice loaded = voice;
        int rate = sampleRate;
        int speakers = numSpeakers;
        float[] defaults = modelDefaults;
        main.post(() -> {
            Listener l = listener;
            if (l != null) {
                l.onVoiceLoaded(loaded, rate, speakers, defaults, speakerNames);
            }
        });
    }

    /** Synthesizes {@code text}, appending each sentence to {@code playback} as it lands. */
    void synthesize(String text, AppSettings settings, PlaybackEngine playback) {
        final int run = currentRun.incrementAndGet();
        executor.execute(() -> {
            if (currentRun.get() != run) {
                Log.i(TAG, "synthesis run " + run + " superseded before it started");
                return;
            }
            long handle = voiceHandle;
            if (handle == 0L) {
                postError("No voice loaded", null);
                return;
            }
            Log.i(TAG, "synthesis start: chars=" + text.length() + " " + settings.describe());
            long started = System.currentTimeMillis();
            try {
                int speakerId = numSpeakers > 1 ? settings.speakerId() : 0;
                int total = PiperNative.nativeSynthesize(handle, text,
                        settings.noiseScale(), settings.lengthScale(), settings.noiseW(),
                        settings.sentenceSilence(), speakerId,
                        pcm -> {
                            if (currentRun.get() != run) {
                                return false;
                            }
                            playback.appendPcm(pcm);
                            int chunk = pcm.length;
                            long frames = playback.totalFrames();
                            main.post(() -> {
                                Listener l = listener;
                                if (l != null) {
                                    l.onSynthesisChunk(chunk, frames);
                                }
                            });
                            return true;
                        });
                boolean cancelled = currentRun.get() != run;
                Log.i(TAG, "synthesis end: run=" + run + " samples=" + total
                        + " wallMs=" + (System.currentTimeMillis() - started)
                        + " cancelled=" + cancelled);
                if (cancelled) {
                    return; // a newer run owns the playback engine now
                }
                main.post(() -> {
                    Listener l = listener;
                    if (l != null) {
                        l.onSynthesisFinished(total, false);
                    }
                });
            } catch (Throwable t) {
                Log.e(TAG, "synthesis failed", t);
                if (currentRun.get() != run) {
                    return;
                }
                postError("Synthesis failed: " + t.getMessage(), t);
                main.post(() -> {
                    Listener l = listener;
                    if (l != null) {
                        l.onSynthesisFinished(-1, false);
                    }
                });
            }
        });
    }

    void cancelSynthesis() {
        Log.i(TAG, "cancel requested (run " + currentRun.incrementAndGet() + ")");
    }

    void shutdown() {
        cancelSynthesis();
        executor.execute(() -> {
            if (voiceHandle != 0L) {
                PiperNative.nativeFreeVoice(voiceHandle);
                voiceHandle = 0L;
            }
            if (initialized) {
                PiperNative.nativeTerminate();
                initialized = false;
            }
        });
        executor.shutdown();
    }

    private static String[] parseSpeakers(String raw) {
        if (raw == null || raw.isEmpty()) {
            return new String[0];
        }
        String[] lines = raw.split("\n");
        java.util.TreeMap<Integer, String> byId = new java.util.TreeMap<>();
        for (String line : lines) {
            int tab = line.indexOf('\t');
            if (tab <= 0) {
                continue;
            }
            try {
                byId.put(Integer.parseInt(line.substring(0, tab)), line.substring(tab + 1));
            } catch (NumberFormatException ignored) {
                // Malformed entry in the model's speaker_id_map; skip it.
            }
        }
        return byId.values().toArray(new String[0]);
    }

    private void postError(String message, Throwable cause) {
        main.post(() -> {
            Listener l = listener;
            if (l != null) {
                l.onError(message, cause);
            }
        });
    }
}
