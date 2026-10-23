package com.sainaw.mm.board;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaFormat;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.RobolectricTestRunner;
import java.io.File;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

/** Fake codec/muxer tests: no microphone, native codec or network is used. */
@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
public class ShanStreamingOpusTest {
    @Test @Config(sdk=28) public void rejectsBeforeApi29() {
        Context c = RuntimeEnvironment.getApplication();
        assertThrows(UnsupportedOperationException.class, () -> ShanStreamingOpus.open(c, 16000));
    }
    @Test @Config(sdk=29) public void rejectsInvalidArgumentsBeforeCodecAccess() {
        Context c = RuntimeEnvironment.getApplication();
        assertThrows(IllegalArgumentException.class, () -> ShanStreamingOpus.open(c, 0));
        assertThrows(IllegalArgumentException.class, () -> ShanStreamingOpus.open(null, 16000));
    }
    @Test public void incrementalSamplesHaveExactTimestampsAndEos() throws Exception {
        Fake f = new Fake(4);
        try (ShanStreamingOpus s = encoder(f)) {
            s.write(new byte[]{1,2,3,4,5,6}, 0, 6);
            s.write(new byte[]{7,8}, 0, 2);
            assertArrayEquals(new byte[]{1,2,3,4,5,6,7,8}, f.pcm.toByteArray());
            assertEquals(3, f.sizes.size());
            assertEquals(0L, f.pts.get(0).longValue());
            assertEquals(125L, f.pts.get(1).longValue());
            assertEquals(187L, f.pts.get(2).longValue());
            byte[] result = s.finish();
            assertEquals('O', result[0]);
            assertTrue(f.inputEos);
            assertEquals(250L, f.eosPts);
            assertEquals(3, f.mux.samples);
            assertEquals(1, f.mux.starts);
            assertTrue(f.codecReleased);
            assertFalse(f.file.exists());
        }
    }
    @Test public void noInputIndexIsTakenWithoutSamples() throws Exception {
        Fake f = new Fake(8);
        try (ShanStreamingOpus s = encoder(f)) {
            s.write(new byte[0], 0, 0);
            assertEquals(0, f.dequeues);
            s.write(new byte[]{1,2}, 0, 2);
            assertEquals(1, f.dequeues);
            assertEquals(0, f.maxWriteWaitUs);
        }
    }
    @Test public void overloadRejectsInsteadOfBlockingCapture() throws Exception {
        Fake f = new Fake(8); f.noInput = true;
        try (ShanStreamingOpus s = encoder(f)) {
            s.write(new byte[6400], 0, 6400);
            assertThrows(IllegalStateException.class, () -> s.write(new byte[2], 0, 2));
            assertEquals(0, f.clock.now);
        }
    }
    @Test public void pendingTailDrainsBeforeEndOfStream() throws Exception {
        Fake f = new Fake(4); f.noInput = true;
        try (ShanStreamingOpus s = encoder(f)) {
            byte[] all = {1,2,3,4,5,6};
            s.write(all, 0, all.length);
            assertEquals(0, f.pcm.size());
            f.noInput = false;
            s.finish();
            assertArrayEquals(all, f.pcm.toByteArray());
            assertEquals(187L, f.eosPts);
        }
    }
    @Test public void zeroCapacityCannotSilentlyDiscardPendingTail() throws Exception {
        Fake f = new Fake(0); f.noInput = true;
        try (ShanStreamingOpus s = encoder(f)) {
            s.write(new byte[]{1,2}, 0, 2);
            f.noInput = false;
            assertThrows(IllegalStateException.class, s::finish);
            assertFalse(f.inputEos);
            assertEquals(0, f.sizes.get(0).intValue());
        }
    }
    @Test public void zeroCapacityWriteReturnsIndexAndFailsForWavFallback() throws Exception {
        Fake f = new Fake(1);
        try (ShanStreamingOpus s = encoder(f)) {
            assertThrows(IllegalStateException.class, () -> s.write(new byte[]{1,2}, 0, 2));
            assertEquals(1, f.sizes.size());
            assertEquals(0, f.sizes.get(0).intValue());
        }
    }
    @Test public void missingEosHitsDeadlineAndCleansUp() throws Exception {
        Fake f = new Fake(4); f.noInput = true;
        ShanStreamingOpus s = encoder(f);
        try {
            assertThrows(java.util.concurrent.TimeoutException.class, s::finish);
            assertTrue(f.clock.now >= 150);
            assertTrue(f.maxFinishWaitUs <= 1000);
        } finally { s.close(); }
        assertTrue(f.codecReleased); assertFalse(f.file.exists());
    }
    @Test public void sampleBeforeFormatFailsRatherThanReturningPartialOgg() throws Exception {
        Fake f = new Fake(4); f.formatSent = true;
        try (ShanStreamingOpus s = encoder(f)) {
            assertThrows(IllegalStateException.class, () -> s.write(new byte[]{1,2}, 0, 2));
            assertEquals(1, f.releasedOutputs);
        }
    }
    @Test public void closeIsIdempotentAndRejectsNewInput() throws Exception {
        Fake f = new Fake(4); ShanStreamingOpus s = encoder(f);
        s.write(new byte[]{1,2}, 0, 2);
        s.finish(); s.close(); s.close();
        assertEquals(1, f.mux.stops); assertEquals(1, f.releases);
        assertThrows(IllegalStateException.class, () -> s.write(new byte[]{1,2}, 0, 2));
    }
    @Test public void invalidSlicesNeverReachCodec() throws Exception {
        Fake f = new Fake(4);
        try (ShanStreamingOpus s = encoder(f)) {
            assertThrows(IllegalArgumentException.class, () -> s.write(new byte[3], 0, 3));
            assertThrows(IllegalArgumentException.class, () -> s.write(null, 0, 0));
            assertThrows(IllegalArgumentException.class, () -> s.write(new byte[2], 1, 2));
            assertEquals(0, f.dequeues);
        }
    }
    private ShanStreamingOpus encoder(Fake f) throws Exception {
        f.file = File.createTempFile("opus-test-", ".ogg");
        // This is a synthetic container signature for seam tests, not a playable OGG assertion.
        try (java.io.FileOutputStream o = new java.io.FileOutputStream(f.file)) {
            byte[] b = new byte[40]; b[0]='O'; b[1]='g'; b[2]='g'; b[3]='S'; o.write(b);
        }
        return new ShanStreamingOpus(f.file, 16000, f, f.mux, f.clock);
    }
    private static final class Fake implements ShanStreamingOpus.Codec {
        final int capacity;
        final List<Integer> sizes = new ArrayList<>();
        final List<Long> pts = new ArrayList<>();
        final ArrayDeque<Integer> outputs = new ArrayDeque<>();
        final ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        final Mux mux = new Mux(); final Clock clock = new Clock();
        ByteBuffer input; File file;
        int dequeues, releasedOutputs, releases, currentFlags;
        long eosPts, maxWriteWaitUs, maxFinishWaitUs;
        boolean inputEos, noInput, codecReleased, formatSent;
        Fake(int capacity) { this.capacity = capacity; }
        public int dequeueInputBuffer(long waitUs) {
            dequeues++; clock.now += (waitUs + 999) / 1000;
            if (waitUs == 0) maxWriteWaitUs = Math.max(maxWriteWaitUs, waitUs);
            else maxFinishWaitUs = Math.max(maxFinishWaitUs, waitUs);
            return noInput ? -1 : 0;
        }
        public ByteBuffer getInputBuffer(int index) { input = ByteBuffer.allocate(capacity); return input; }
        public void queueInputBuffer(int index, int offset, int size, long timestamp, int flags) {
            sizes.add(size); pts.add(timestamp);
            if (size > 0) { pcm.write(input.array(), offset, size); outputs.add(0); }
            if ((flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                inputEos = true; eosPts = timestamp; outputs.add(MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            }
        }
        public int dequeueOutputBuffer(MediaCodec.BufferInfo info, long waitUs) {
            clock.now += (waitUs + 999) / 1000;
            maxFinishWaitUs = Math.max(maxFinishWaitUs, waitUs);
            if (outputs.isEmpty()) return MediaCodec.INFO_TRY_AGAIN_LATER;
            if (!formatSent) { formatSent = true; return MediaCodec.INFO_OUTPUT_FORMAT_CHANGED; }
            currentFlags = outputs.remove();
            info.set(0, currentFlags == 0 ? 1 : 0, 0, currentFlags);
            return 0;
        }
        public ByteBuffer getOutputBuffer(int index) { return ByteBuffer.wrap(new byte[]{1}); }
        public MediaFormat getOutputFormat() { return new MediaFormat(); }
        public void releaseOutputBuffer(int index, boolean render) { releasedOutputs++; }
        public void stop() { }
        public void release() { codecReleased = true; releases++; }
    }
    private static final class Mux implements ShanStreamingOpus.Muxer {
        int starts, samples, stops;
        public int addTrack(MediaFormat format) { return 0; }
        public void start() { starts++; }
        public void writeSampleData(int track, ByteBuffer data, MediaCodec.BufferInfo info) { samples++; }
        public void stop() { stops++; }
        public void release() { }
    }
    private static final class Clock implements ShanStreamingOpus.Clock {
        long now; public long elapsedRealtime() { return now; }
    }
}
