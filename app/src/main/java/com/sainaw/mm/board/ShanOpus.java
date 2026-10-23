package com.sainaw.mm.board;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;

/** Offline PCM16-mono to Ogg Opus encoder. Call on a worker thread, never the UI thread. */
final class ShanOpus {
    private static final String MIME = "audio/opus";
    private static final int BIT_RATE = 32_000;
    private static final int CHUNK_MS = 20;
    private static final int MAX_AUDIO_MS = 30_000;
    private static final int MAX_PCM_BYTES = 2 * 1024 * 1024;
    private static final long CODEC_TIMEOUT_US = 10_000L;
    private static final long WALL_TIMEOUT_MS = 5_000L;

    private ShanOpus() { }

    /**
     * Encodes complete PCM16 mono samples into a real Ogg container (audio/ogg).
     * API < 29, unsupported codecs, or codec failures throw for the caller to fall back to WAV.
     */
    static byte[] encode(Context context, byte[] pcm, int rate) throws Exception {
        if (Build.VERSION.SDK_INT < 29) throw new UnsupportedOperationException("Ogg muxing requires Android API 29+");
        if (context == null) throw new IllegalArgumentException("Context must not be null");
        if (pcm == null || pcm.length == 0 || (pcm.length & 1) != 0 || rate <= 0)
            throw new IllegalArgumentException("Expected non-empty PCM16 mono and a positive sample rate");
        long durationMs = (pcm.length / 2L) * 1000L / rate;
        if (durationMs == 0 || durationMs > MAX_AUDIO_MS || pcm.length > MAX_PCM_BYTES)
            throw new IllegalArgumentException("PCM duration/size exceeds the 30-second encoder limit");

        File temp = File.createTempFile("shan-opus-", ".ogg", context.getCacheDir());
        MediaCodec codec = null;
        MediaMuxer muxer = null;
        boolean muxerStarted = false;
        boolean codecStarted = false;
        try {
            MediaFormat format = MediaFormat.createAudioFormat(MIME, rate, 1);
            format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8192);
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT);
            codec = MediaCodec.createEncoderByType(MIME);
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();
            codecStarted = true;
            muxer = new MediaMuxer(temp.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG);

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int inputOffset = 0;
            long timestampUs = 0;
            boolean inputEos = false;
            boolean outputEos = false;
            int track = -1;
            long deadline = android.os.SystemClock.elapsedRealtime() + WALL_TIMEOUT_MS;
            int chunkBytes = Math.max(2, (rate * CHUNK_MS / 1000) * 2);
            chunkBytes -= chunkBytes & 1;

            while (!outputEos) {
                if (android.os.SystemClock.elapsedRealtime() > deadline)
                    throw new java.util.concurrent.TimeoutException("Opus encoding exceeded 5 seconds");
                if (!inputEos) {
                    int inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US);
                    if (inputIndex >= 0) {
                        ByteBuffer input = codec.getInputBuffer(inputIndex);
                        if (input == null) throw new IllegalStateException("Encoder returned no input buffer");
                        input.clear();
                        int count = Math.min(Math.min(chunkBytes, input.remaining()), pcm.length - inputOffset);
                        count &= ~1;
                        if (count > 0) {
                            input.put(pcm, inputOffset, count);
                            codec.queueInputBuffer(inputIndex, 0, count, timestampUs, 0);
                            timestampUs += (count / 2L) * 1_000_000L / rate;
                            inputOffset += count;
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, 0, timestampUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputEos = true;
                        }
                    }
                }

                int outputIndex = codec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US);
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (muxerStarted) throw new IllegalStateException("Encoder output format changed twice");
                    track = muxer.addTrack(codec.getOutputFormat()); // includes OpusHead/Tags codec-specific data
                    muxer.start();
                    muxerStarted = true;
                } else if (outputIndex >= 0) {
                    ByteBuffer output = codec.getOutputBuffer(outputIndex);
                    if (output == null) throw new IllegalStateException("Encoder returned no output buffer");
                    if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        if (!muxerStarted) throw new IllegalStateException("Encoded sample arrived before output format");
                        output.position(info.offset);
                        output.limit(info.offset + info.size);
                        muxer.writeSampleData(track, output, info);
                    }
                    outputEos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    codec.releaseOutputBuffer(outputIndex, false);
                }
            }
            if (!muxerStarted) throw new IllegalStateException("Encoder produced no Ogg track");
            muxer.stop();
            muxerStarted = false;
            codec.stop();
            codecStarted = false;
            if (temp.length() < 32 || temp.length() > MAX_PCM_BYTES)
                throw new IllegalStateException("Invalid Ogg output size");
            try (FileInputStream in = new FileInputStream(temp); java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream((int) temp.length())) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                byte[] result = out.toByteArray();
                if (result.length < 4 || result[0] != 'O' || result[1] != 'g' || result[2] != 'g' || result[3] != 'S')
                    throw new IllegalStateException("Muxer did not create an Ogg stream");
                return result;
            }
        } finally {
            if (muxer != null) {
                if (muxerStarted) try { muxer.stop(); } catch (RuntimeException ignored) { }
                try { muxer.release(); } catch (RuntimeException ignored) { }
            }
            if (codec != null) {
                if (codecStarted) try { codec.stop(); } catch (RuntimeException ignored) { }
                try { codec.release(); } catch (RuntimeException ignored) { }
            }
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
        }
    }
}
