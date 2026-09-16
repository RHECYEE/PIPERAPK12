package dev.piper.mvp;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.util.Log;

/**
 * Plays the cached PCM stream through AudioTrack and keeps an exact playback timeline.
 *
 * <p>Seeking is done on the audio stream, never on the text: the whole synthesized PCM
 * lives in {@link PcmBuffer}, so a jump is just a cursor move plus an AudioTrack flush.
 */
final class PlaybackEngine {

    static final String TAG = "PiperPlayback";

    enum State { IDLE, PLAYING, PAUSED, ENDED }

    interface Listener {
        void onStateChanged(State state);
    }

    private static final int WRITE_CHUNK_FRAMES = 4096;
    private static final long IDLE_SLEEP_MS = 15L;

    private final PcmBuffer buffer = new PcmBuffer();
    private final PlaybackTimeline timeline;
    private final int sampleRate;
    private final Object lock = new Object();

    private AudioTrack track;
    private Thread writerThread;
    private volatile boolean writerRunning;
    private volatile State state = State.IDLE;
    private volatile boolean producerFinished;
    private volatile float volume = 1.0f;
    private Listener listener;

    PlaybackEngine(int sampleRate) {
        this.sampleRate = sampleRate;
        this.timeline = new PlaybackTimeline(sampleRate);
        Log.i(TAG, "PlaybackEngine created: sampleRate=" + sampleRate);
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    int sampleRate() {
        return sampleRate;
    }

    State state() {
        return state;
    }

    /** Called from the synthesis thread as each sentence completes. */
    void appendPcm(short[] pcm) {
        buffer.append(pcm);
    }

    void setProducerFinished(boolean finished) {
        producerFinished = finished;
        Log.i(TAG, "producerFinished=" + finished + " totalFrames=" + buffer.frames()
                + " duration=" + String.format("%.3f", durationSeconds()) + "s");
    }

    long totalFrames() {
        return buffer.frames();
    }

    double durationSeconds() {
        return timeline.secondsOf(buffer.frames());
    }

    double positionSeconds() {
        return timeline.secondsOf(positionFrames());
    }

    long positionFrames() {
        return timeline.positionFrames(headFrames(), buffer.frames());
    }

    void setVolume(float value) {
        volume = Math.max(0f, Math.min(1f, value));
        synchronized (lock) {
            if (track != null) {
                track.setVolume(volume);
            }
        }
        Log.i(TAG, "setVolume=" + volume);
    }

    /** Starts or resumes playback. From ENDED this rewinds to the beginning. */
    void play() {
        synchronized (lock) {
            ensureTrack();
            if (state == State.ENDED) {
                Log.i(TAG, "play from ENDED -> rewinding to 0");
                track.pause();
                track.flush();
                timeline.applySeek(0L);
            }
            track.play();
            startWriter();
        }
        setState(State.PLAYING);
        Log.i(TAG, "play: position=" + fmt(positionSeconds()) + "s duration="
                + fmt(durationSeconds()) + "s");
    }

    /** Pauses without losing the position; AudioTrack keeps its buffered audio. */
    void pause() {
        synchronized (lock) {
            if (track != null && state == State.PLAYING) {
                track.pause();
            }
        }
        if (state == State.PLAYING) {
            setState(State.PAUSED);
        }
        Log.i(TAG, "pause: position=" + fmt(positionSeconds()) + "s");
    }

    void togglePlayPause() {
        if (state == State.PLAYING) {
            pause();
        } else {
            play();
        }
    }

    /** Relative seek on the audio timeline, clamped to [0, generated duration]. */
    void seekBy(double deltaSeconds) {
        long total = buffer.frames();
        long head = headFrames();
        long before = timeline.positionFrames(head, total);
        long target = timeline.seekTarget(deltaSeconds, head, total);

        State resumeState = state;
        synchronized (lock) {
            if (track != null) {
                track.pause();
                track.flush();
            }
            timeline.applySeek(target);
            if (resumeState == State.PLAYING && track != null) {
                track.play();
            }
        }
        if (resumeState == State.ENDED && target < total) {
            setState(State.PAUSED);
        }

        Log.i(TAG, "seek " + fmt(deltaSeconds) + "s: before=" + fmt(timeline.secondsOf(before))
                + "s after=" + fmt(timeline.secondsOf(target)) + "s"
                + " (frames " + before + " -> " + target + " of " + total + ")"
                + " clamped=" + (target == 0L || target == total));
    }

    /** Drops the cached audio and resets the clock, ready for new text. */
    void reset() {
        // Stop the writer before taking `lock`: it takes `lock` itself, so joining it
        // from inside the monitor would deadlock.
        stopWriter();
        synchronized (lock) {
            if (track != null) {
                track.pause();
                track.flush();
                track.release();
                track = null;
            }
            buffer.clear();
            timeline.reset();
            producerFinished = false;
        }
        setState(State.IDLE);
        Log.i(TAG, "reset");
    }

    void release() {
        reset();
    }

    private void ensureTrack() {
        if (track != null) {
            return;
        }
        int minBytes = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minBytes <= 0) {
            minBytes = sampleRate; // fall back to ~0.5 s of 16-bit mono
        }
        // ~250 ms keeps pause and seek responsive without underrunning.
        int desiredBytes = (sampleRate / 4) * 2;
        int bufferBytes = Math.max(minBytes, desiredBytes);

        track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setBufferSizeInBytes(bufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        track.setVolume(volume);
        Log.i(TAG, "AudioTrack created: sampleRate=" + sampleRate + " bufferBytes=" + bufferBytes
                + " minBytes=" + minBytes);
    }

    private long headFrames() {
        AudioTrack current;
        synchronized (lock) {
            current = track;
        }
        if (current == null) {
            return 0L;
        }
        return current.getPlaybackHeadPosition() & 0xFFFFFFFFL;
    }

    private void startWriter() {
        if (writerRunning) {
            return;
        }
        writerRunning = true;
        writerThread = new Thread(this::writeLoop, "piper-audio-writer");
        writerThread.setPriority(Thread.MAX_PRIORITY - 1);
        writerThread.start();
    }

    private void stopWriter() {
        writerRunning = false;
        Thread thread = writerThread;
        writerThread = null;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(500L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void writeLoop() {
        final short[] scratch = new short[WRITE_CHUNK_FRAMES];
        while (writerRunning) {
            if (state != State.PLAYING) {
                sleep(IDLE_SLEEP_MS);
                continue;
            }

            long from;
            int generation;
            synchronized (lock) {
                from = timeline.writeFrame();
                generation = timeline.generation();
            }

            int count = buffer.copyInto(from, scratch, scratch.length);
            if (count == 0) {
                // Nothing new yet: either synthesis is still running, or we are done.
                if (producerFinished && positionFrames() >= buffer.frames()) {
                    Log.i(TAG, "playback reached end at " + fmt(positionSeconds()) + "s");
                    synchronized (lock) {
                        if (track != null) {
                            track.pause();
                        }
                    }
                    setState(State.ENDED);
                }
                sleep(IDLE_SLEEP_MS);
                continue;
            }

            AudioTrack current;
            synchronized (lock) {
                current = track;
            }
            if (current == null) {
                sleep(IDLE_SLEEP_MS);
                continue;
            }

            int written = current.write(scratch, 0, count, AudioTrack.WRITE_NON_BLOCKING);
            if (written > 0) {
                timeline.onWritten(from, written, generation);
            } else if (written == 0) {
                sleep(5L); // AudioTrack buffer full; let it drain
            } else {
                Log.e(TAG, "AudioTrack.write failed: " + written);
                sleep(IDLE_SLEEP_MS);
            }
        }
    }

    private void setState(State next) {
        if (state == next) {
            return;
        }
        state = next;
        Log.i(TAG, "state -> " + next + " position=" + fmt(positionSeconds())
                + "s duration=" + fmt(durationSeconds()) + "s");
        Listener current = listener;
        if (current != null) {
            current.onStateChanged(next);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String fmt(double value) {
        return String.format(java.util.Locale.US, "%.3f", value);
    }
}
