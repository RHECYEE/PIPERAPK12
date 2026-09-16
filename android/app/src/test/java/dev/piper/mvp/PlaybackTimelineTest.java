package dev.piper.mvp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Covers the ±10 s seek rules, clamping, and pause/resume position accounting. */
public class PlaybackTimelineTest {

    private static final int RATE = 22050;
    private static final long TEN_SECONDS = 10L * RATE;

    /** 60 s of generated audio. */
    private static final long TOTAL = 60L * RATE;

    @Test
    public void positionIsBasePlusHeadNotWhatWeQueued() {
        PlaybackTimeline t = new PlaybackTimeline(RATE);
        t.onWritten(0, 5 * RATE, t.generation());   // queued 5 s into AudioTrack
        // Only 1 s has actually been rendered, so that is the reported position.
        assertEquals(RATE, t.positionFrames(RATE, TOTAL));
    }

    @Test
    public void positionNeverExceedsWhatWasWritten() {
        PlaybackTimeline t = new PlaybackTimeline(RATE);
        t.onWritten(0, RATE, t.generation());
        assertEquals(RATE, t.positionFrames(5 * RATE, TOTAL));
    }

    @Test
    public void forwardTenSecondsMovesTenSeconds() {
        PlaybackTimeline t = new PlaybackTimeline(RATE);
        t.onWritten(0, (int) (30L * RATE), t.generation());
        long head = 5L * RATE;                       // at 5 s
        long target = t.seekTarget(10.0, head, TOTAL);
        assertEquals(15L * RATE, target);
    }

    @Test
    public void backTenSecondsMovesTenSecondsBack() {
        PlaybackTimeline t = new PlaybackTimeline(RATE);
        t.onWritten(0, (int) (30L * RATE), t.generation());
        long head = 25L * RATE;
        assertEquals(15L * RATE, t.seekTarget(-10.0, head, TOTAL));
    }

    @Test
    public void backSeekClampsAtZero() {
        PlaybackTimeline t = new PlaybackTimeline(RATE);
        t.onWritten(0, (int) (30L * RATE), t.generation());
        assertEquals(0L, t.seekTarget(-10.0, 3L * RATE, TOTAL));
        assertEquals(0L, t.seekTarget(-10.0, 0L, TOTAL));
    }

    @Test
    public void forwardSeekClampsAtGeneratedDuration() {
        PlaybackTimeline t = new PlaybackTimeline(RATE);
        t.onWritten(0, (int) TOTAL, t.generation());
        assertEquals(TOTAL, t.seekTarget(10.0, 55L * RATE, TOTAL));
        assertEquals(TOTAL, t.seekTarget(10.0, TOTAL, TOTAL));
    }

    @Test
    public void seekClampsToWhatHasBeenGeneratedSoFarNotTheFinalLength() {
        PlaybackTimeline t = new PlaybackTimeline(RATE);
        long generatedSoFar = 8L * RATE;             // synthesis still running
        t.onWritten(0, (int) generatedSoFar, t.generation());
        assertEquals(generatedSoFar, t.seekTarget(10.0, 2L * RATE, generatedSoFar));
    }

    @Test
    public void applySeekReanchorsBothCursors() {
        PlaybackTimeline t = new PlaybackTimeline(RATE);
        t.onWritten(0, (int) (30L * RATE), t.generation());
        t.applySeek(15L * RATE);
        assertEquals(15L * RATE, t.baseFrame());
        assertEquals(15L * RATE, t.writeFrame());
        // AudioTrack.flush() zeroed the head, so position is exactly the seek target.
        assertEquals(15L * RATE, t.positionFrames(0L, TOTAL));
    }

    @Test
    public void seekIsExactlyTenSecondsAcrossTheRoundTrip() {
        PlaybackTimeline t = new PlaybackTimeline(RATE);
        t.onWritten(0, (int) TOTAL, t.generation());
        long head = 20L * RATE;
        long before = t.positionFrames(head, TOTAL);

        t.applySeek(t.seekTarget(10.0, head, TOTAL));
        long afterForward = t.positionFrames(0L, TOTAL);
        assertEquals(TEN_SECONDS, afterForward - before);

        t.onWritten(afterForward, (int) (20L * RATE), t.generation());
        t.applySeek(t.seekTarget(-10.0, 0L, TOTAL));
        assertEquals(before, t.positionFrames(0L, TOTAL));
    }

    @Test
    public void writesRacedBySeekAreDiscarded() {
        PlaybackTimeline t = new PlaybackTimeline(RATE);
        int staleGeneration = t.generation();
        long staleFrom = t.writeFrame();

        t.applySeek(40L * RATE);                     // seek lands mid-write
        t.onWritten(staleFrom, 4096, staleGeneration);

        assertEquals("stale write must not move the new cursor",
                40L * RATE, t.writeFrame());
    }

    @Test
    public void pauseAndResumeKeepTheExactPosition() {
        PlaybackTimeline t = new PlaybackTimeline(RATE);
        t.onWritten(0, (int) (30L * RATE), t.generation());
        long head = 12L * RATE + 511;                // arbitrary mid-frame pause point
        long paused = t.positionFrames(head, TOTAL);
        // Pause does not flush, so the head is unchanged when playback resumes.
        assertEquals(paused, t.positionFrames(head, TOTAL));
        assertEquals(12L * RATE + 511, paused);
    }

    @Test
    public void extremeSeekValuesSaturateInsteadOfOverflowing() {
        PlaybackTimeline t = new PlaybackTimeline(RATE);
        t.onWritten(0, (int) TOTAL, t.generation());
        assertEquals(0L, t.seekTarget(-Double.MAX_VALUE, 30L * RATE, TOTAL));
        assertEquals(TOTAL, t.seekTarget(Double.MAX_VALUE, 30L * RATE, TOTAL));
    }

    @Test
    public void secondsConversionMatchesSampleRate() {
        PlaybackTimeline t = new PlaybackTimeline(RATE);
        assertEquals(10.0, t.secondsOf(TEN_SECONDS), 1e-9);
        assertEquals(TEN_SECONDS, t.framesOf(10.0));
        assertTrue(t.secondsOf(0) == 0.0);
    }

    @Test
    public void emptyStreamSeeksAreNoOps() {
        PlaybackTimeline t = new PlaybackTimeline(RATE);
        assertEquals(0L, t.seekTarget(10.0, 0L, 0L));
        assertEquals(0L, t.seekTarget(-10.0, 0L, 0L));
        assertEquals(0L, t.positionFrames(0L, 0L));
    }
}
