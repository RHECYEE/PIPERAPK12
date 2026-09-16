package dev.piper.mvp;

import java.util.Arrays;

/**
 * Append-only cache of every PCM sample Piper has produced for the current text.
 *
 * <p>piper.cpp's textToAudio() clears its audio buffer after each sentence callback, so
 * upstream streaming is not seekable. Keeping the full stream here is the one change the
 * MVP needs to make back/forward seeking deterministic rather than estimated.
 */
final class PcmBuffer {

    private static final int INITIAL_CAPACITY = 1 << 16;

    private short[] data = new short[INITIAL_CAPACITY];
    private int frames;

    synchronized void append(short[] chunk) {
        if (chunk == null || chunk.length == 0) {
            return;
        }
        ensureCapacity(frames + chunk.length);
        System.arraycopy(chunk, 0, data, frames, chunk.length);
        frames += chunk.length;
    }

    synchronized int frames() {
        return frames;
    }

    /** Copies up to {@code maxFrames} starting at {@code fromFrame}; returns frames copied. */
    synchronized int copyInto(long fromFrame, short[] dest, int maxFrames) {
        if (fromFrame < 0 || fromFrame >= frames) {
            return 0;
        }
        int available = frames - (int) fromFrame;
        int count = Math.min(Math.min(available, maxFrames), dest.length);
        System.arraycopy(data, (int) fromFrame, dest, 0, count);
        return count;
    }

    synchronized void clear() {
        frames = 0;
        if (data.length > INITIAL_CAPACITY) {
            data = new short[INITIAL_CAPACITY];
        }
    }

    synchronized int capacityBytes() {
        return data.length * 2;
    }

    private void ensureCapacity(int needed) {
        if (needed <= data.length) {
            return;
        }
        int capacity = data.length;
        while (capacity < needed) {
            capacity = capacity + (capacity >> 1);
            if (capacity < 0) {
                capacity = Integer.MAX_VALUE - 8;
                break;
            }
        }
        data = Arrays.copyOf(data, capacity);
    }
}
