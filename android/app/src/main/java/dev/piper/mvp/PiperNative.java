package dev.piper.mvp;

/** Thin binding to the fork's C++ engine (src/cpp/piper.cpp) via piper_jni.cpp. */
final class PiperNative {

    /** Receives one sentence of mono 16-bit PCM. Return false to cancel synthesis. */
    interface PcmSink {
        boolean onPcmChunk(short[] pcm);
    }

    private static Throwable loadError;

    static {
        try {
            System.loadLibrary("piper_mvp");
        } catch (Throwable t) {
            loadError = t;
        }
    }

    static Throwable loadError() {
        return loadError;
    }

    static native boolean nativeInit(String espeakDataPath);

    static native void nativeTerminate();

    static native long nativeLoadVoice(String modelPath, String configPath);

    static native void nativeFreeVoice(long handle);

    static native int nativeGetSampleRate(long handle);

    static native int nativeGetNumSpeakers(long handle);

    /** [noiseScale, lengthScale, noiseW, sentenceSilenceSeconds] as parsed from the model JSON. */
    static native float[] nativeGetModelDefaults(long handle);

    /** Lines of "<id>\t<name>" for multi-speaker models; empty otherwise. */
    static native String nativeGetSpeakerNames(long handle);

    static native int nativeSynthesize(long handle, String text, float noiseScale,
                                       float lengthScale, float noiseW, float sentenceSilence,
                                       int speakerId, PcmSink sink);

    private PiperNative() {
    }
}
