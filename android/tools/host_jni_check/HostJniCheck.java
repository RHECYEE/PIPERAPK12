package dev.piper.mvp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Runs the real JNI bridge (app/src/main/cpp/piper_jni.cpp) and the real timeline classes
 * on a desktop JVM, so the native contract the app depends on can be verified without a
 * device. See run.sh.
 *
 * <p>Note on determinism: Piper's VITS duration predictor is stochastic, so the same text
 * yields slightly different lengths on each run at the default noise_w. Checks that need
 * an exact sample count therefore pin noise_w to 0.
 */
public final class HostJniCheck {

    private static int failures = 0;

    private static void check(String what, boolean ok, String detail) {
        System.out.printf("%-58s %s   %s%n", what, ok ? "PASS" : "FAIL", detail);
        if (!ok) {
            failures++;
        }
    }

    private static final String TEXT =
            "Piper runs locally on the device. This is the second sentence. "
                    + "And here is a third one to make the stream longer.";
    private static final int SENTENCES = 3;

    public static void main(String[] args) {
        String model = args[0], config = args[1], espeakData = args[2];

        Throwable loadError = PiperNative.loadError();
        check("native library loads", loadError == null, String.valueOf(loadError));
        if (loadError != null) {
            System.exit(1);
        }

        check("nativeInit(espeakData)", PiperNative.nativeInit(espeakData), espeakData);

        long handle = PiperNative.nativeLoadVoice(model, config);
        check("nativeLoadVoice returns a handle", handle != 0L, "handle=" + handle);

        int sampleRate = PiperNative.nativeGetSampleRate(handle);
        int speakers = PiperNative.nativeGetNumSpeakers(handle);
        float[] defaults = PiperNative.nativeGetModelDefaults(handle);
        String speakerNames = PiperNative.nativeGetSpeakerNames(handle);
        check("nativeGetSampleRate", sampleRate > 0, "sampleRate=" + sampleRate);
        check("nativeGetNumSpeakers", speakers >= 1, "numSpeakers=" + speakers);
        check("nativeGetModelDefaults returns 4 floats",
                defaults != null && defaults.length == 4, Arrays.toString(defaults));
        check("nativeGetSpeakerNames callable", speakerNames != null,
                "\"" + speakerNames.replace("\n", "|") + "\"");

        // --- streaming chunks land in the cache; this is what makes seeking possible ---
        PcmBuffer buffer = new PcmBuffer();
        List<Integer> chunks = new ArrayList<>();
        int total = synth(handle, 1f, 0.8f, 0.2f, pcm -> {
            chunks.add(pcm.length);
            buffer.append(pcm);
            return true;
        });
        check("nativeSynthesize returns a sample count", total > 0, "samples=" + total);
        check("sink receives one chunk per sentence", chunks.size() == SENTENCES,
                "chunks=" + chunks);
        check("cache holds every synthesized sample", buffer.frames() == total,
                "cached=" + buffer.frames() + " reported=" + total);
        check("audio is a plausible length", total / (double) sampleRate > 2.0,
                String.format("%.3f s @ %d Hz", total / (double) sampleRate, sampleRate));

        short[] probe = new short[buffer.frames()];
        buffer.copyInto(0, probe, probe.length);
        int peak = 0, nonZero = 0;
        for (short s : probe) {
            peak = Math.max(peak, Math.abs(s));
            if (s != 0) {
                nonZero++;
            }
        }
        check("PCM is real audio, not silence", peak > 1000 && nonZero > probe.length / 2,
                "peak=" + peak + " nonZero=" + (100 * nonZero / probe.length) + "%");

        // --- settings genuinely reach the model ---
        int baseline = synth(handle, 1.0f, 0f, 0f, pcm -> true);
        int repeat = synth(handle, 1.0f, 0f, 0f, pcm -> true);
        check("noise_w=0 makes synthesis deterministic", baseline == repeat,
                "run1=" + baseline + " run2=" + repeat);

        int slow = synth(handle, 2.0f, 0f, 0f, pcm -> true);
        int fast = synth(handle, 0.5f, 0f, 0f, pcm -> true);
        check("length_scale 2.0 lengthens the audio", slow > baseline,
                "slow=" + slow + " baseline=" + baseline);
        check("length_scale 0.5 shortens the audio", fast < baseline,
                "fast=" + fast + " baseline=" + baseline);

        int withSilence = synth(handle, 1.0f, 0f, 1.0f, pcm -> true);
        int expectedExtra = SENTENCES * sampleRate; // 1.0 s of zeros per sentence
        check("sentence_silence adds exactly the expected zero samples",
                withSilence - baseline == expectedExtra,
                "delta=" + (withSilence - baseline) + " expected=" + expectedExtra);

        int stochastic1 = synth(handle, 1.0f, 0.8f, 0f, pcm -> true);
        int stochastic2 = synth(handle, 1.0f, 0.8f, 0f, pcm -> true);
        check("noise_w>0 varies duration (VITS stochastic predictor)",
                stochastic1 != stochastic2, "run1=" + stochastic1 + " run2=" + stochastic2);

        // --- cancellation unwinds cleanly out of textToAudio ---
        final int[] seen = {0};
        int cancelled = synth(handle, 1.0f, 0.8f, 0.2f, pcm -> {
            seen[0]++;
            return false;
        });
        check("sink can cancel synthesis mid-stream", seen[0] == 1 && cancelled > 0,
                "chunksBeforeCancel=" + seen[0] + " samples=" + cancelled);

        int afterCancel = synth(handle, 1.0f, 0f, 0f, pcm -> true);
        check("engine still usable after a cancel", afterCancel == baseline,
                "samples=" + afterCancel + " baseline=" + baseline);

        // --- seek rules against this real audio ---
        PlaybackTimeline timeline = new PlaybackTimeline(sampleRate);
        long totalFrames = buffer.frames();
        timeline.onWritten(0, (int) totalFrames, timeline.generation());
        long head = timeline.framesOf(1.0);
        long forward = timeline.seekTarget(10.0, head, totalFrames);
        check("forward 10 s clamps to the generated duration",
                forward == totalFrames, "target=" + forward + " total=" + totalFrames);
        timeline.applySeek(forward);
        check("back 10 s from the end clamps at zero",
                timeline.seekTarget(-10.0, 0L, totalFrames) == 0L, "target=0");

        PiperNative.nativeFreeVoice(handle);
        PiperNative.nativeTerminate();

        System.out.println();
        System.out.println(failures == 0
                ? "ALL HOST JNI CHECKS PASSED"
                : failures + " HOST JNI CHECK(S) FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static int synth(long handle, float lengthScale, float noiseW, float sentenceSilence,
                             PiperNative.PcmSink sink) {
        return PiperNative.nativeSynthesize(handle, TEXT, 0.667f, lengthScale, noiseW,
                sentenceSilence, 0, sink);
    }
}
