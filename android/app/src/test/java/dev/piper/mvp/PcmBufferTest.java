package dev.piper.mvp;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class PcmBufferTest {

    @Test
    public void appendsAcrossChunksAndGrows() {
        PcmBuffer buffer = new PcmBuffer();
        for (int i = 0; i < 100; i++) {
            buffer.append(new short[2000]);
        }
        assertEquals(200_000, buffer.frames());
    }

    @Test
    public void copiesFromAnOffset() {
        PcmBuffer buffer = new PcmBuffer();
        buffer.append(new short[]{1, 2, 3});
        buffer.append(new short[]{4, 5, 6});

        short[] dest = new short[4];
        int copied = buffer.copyInto(2, dest, 4);
        assertEquals(4, copied);
        assertArrayEquals(new short[]{3, 4, 5, 6}, dest);
    }

    @Test
    public void copyStopsAtTheEndOfGeneratedAudio() {
        PcmBuffer buffer = new PcmBuffer();
        buffer.append(new short[]{1, 2, 3});

        short[] dest = new short[8];
        assertEquals(1, buffer.copyInto(2, dest, 8));
        assertEquals(0, buffer.copyInto(3, dest, 8));
        assertEquals(0, buffer.copyInto(99, dest, 8));
        assertEquals(0, buffer.copyInto(-1, dest, 8));
    }

    @Test
    public void clearResetsLength() {
        PcmBuffer buffer = new PcmBuffer();
        buffer.append(new short[1000]);
        buffer.clear();
        assertEquals(0, buffer.frames());
        assertEquals(0, buffer.copyInto(0, new short[4], 4));
    }

    @Test
    public void emptyAppendsAreIgnored() {
        PcmBuffer buffer = new PcmBuffer();
        buffer.append(null);
        buffer.append(new short[0]);
        assertEquals(0, buffer.frames());
    }
}
