package dev.piper.mvp;

/**
 * The playback clock. Pure frame arithmetic, no Android types, so the seek and clamp
 * rules can be unit tested directly.
 *
 * <p>Two cursors are tracked against the cached PCM stream:
 * <ul>
 *   <li>{@code baseFrame} - the stream frame that AudioTrack's playback head 0 refers to.
 *       AudioTrack resets its head on flush(), so every seek re-anchors this.</li>
 *   <li>{@code writeFrame} - the next stream frame to hand to AudioTrack. It runs ahead
 *       of the audible position by however much AudioTrack has buffered.</li>
 * </ul>
 * Audible position is therefore {@code baseFrame + head}, which is what the user hears,
 * not what we have queued.
 */
final class PlaybackTimeline {

    private final int sampleRate;
    private long baseFrame;
    private long writeFrame;
    private int generation;

    PlaybackTimeline(int sampleRate) {
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        this.sampleRate = sampleRate;
    }

    int sampleRate() {
        return sampleRate;
    }

    synchronized int generation() {
        return generation;
    }

    synchronized long writeFrame() {
        return writeFrame;
    }

    synchronized long baseFrame() {
        return baseFrame;
    }

    /** Frames the listener has actually heard, clamped to what exists and what we wrote. */
    synchronized long positionFrames(long headFrames, long totalFrames) {
        long position = baseFrame + Math.max(0L, headFrames);
        if (position > writeFrame) {
            position = writeFrame;
        }
        if (position > totalFrames) {
            position = totalFrames;
        }
        return Math.max(0L, position);
    }

    /** Target frame for a relative jump, clamped to [0, totalFrames]. */
    synchronized long seekTarget(double deltaSeconds, long headFrames, long totalFrames) {
        long position = positionFrames(headFrames, totalFrames);
        double targetExact = position + deltaSeconds * sampleRate;
        // Saturate rather than wrap: the clamp below is the only thing that should
        // decide the endpoints.
        long target = targetExact >= totalFrames ? totalFrames
                : targetExact <= 0d ? 0L
                : Math.round(targetExact);
        if (target < 0L) {
            target = 0L;
        }
        if (target > totalFrames) {
            target = totalFrames;
        }
        return target;
    }

    /** Re-anchors both cursors after AudioTrack.flush() has zeroed the playback head. */
    synchronized void applySeek(long targetFrame) {
        baseFrame = Math.max(0L, targetFrame);
        writeFrame = baseFrame;
        generation++;
    }

    /**
     * Records frames accepted by AudioTrack. Writes issued before a seek are discarded,
     * otherwise a write in flight during a seek would corrupt the new cursor.
     */
    synchronized void onWritten(long fromFrame, int written, int atGeneration) {
        if (atGeneration != generation || written <= 0) {
            return;
        }
        writeFrame = fromFrame + written;
    }

    synchronized void reset() {
        baseFrame = 0L;
        writeFrame = 0L;
        generation++;
    }

    double secondsOf(long frames) {
        return (double) frames / (double) sampleRate;
    }

    long framesOf(double seconds) {
        return Math.round(seconds * sampleRate);
    }
}
