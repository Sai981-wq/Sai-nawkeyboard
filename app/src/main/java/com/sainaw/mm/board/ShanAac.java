package com.sainaw.mm.board;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;

/** 
 * Offline PCM16-mono to AAC (.m4a) encoder. 
 * Used as a lightweight fallback instead of bulky WAV files.
 */
public final class ShanAac {
    private static final String MIME = "audio/mp4a-latm";
    private static final int BIT_RATE = 32_000;
    private static final int CHUNK_MS = 20;
    private static final long CODEC_TIMEOUT_US = 10_000L;
    private static final long WALL_TIMEOUT_MS = 5_000L;

    private ShanAac() { }

    public static byte[] encode(Context context, byte[] pcm, int rate) throws Exception {
        if (context == null) throw new IllegalArgumentException("Context required");
        if (pcm == null || (pcm.length & 1) != 0 || rate <= 0)
            throw new IllegalArgumentException("Invalid PCM data");

        File temp = File.createTempFile("shan-aac-", ".m4a", context.getCacheDir());
        MediaCodec codec = null;
        MediaMuxer muxer = null;
        boolean muxerStarted = false;
        boolean codecStarted = false;
        
        try {
            MediaFormat format = MediaFormat.createAudioFormat(MIME, rate, 1);
            format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8192);
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);

            codec = MediaCodec.createEncoderByType(MIME);
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();
            codecStarted = true;

            muxer = new MediaMuxer(temp.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

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
                    throw new java.util.concurrent.TimeoutException("AAC encoding timeout");

                if (!inputEos) {
                    int inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US);
                    if (inputIndex >= 0) {
                        ByteBuffer input = codec.getInputBuffer(inputIndex);
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
                    if (muxerStarted) throw new IllegalStateException("Format changed twice");
                    track = muxer.addTrack(codec.getOutputFormat());
                    muxer.start();
                    muxerStarted = true;
                } else if (outputIndex >= 0) {
                    ByteBuffer output = codec.getOutputBuffer(outputIndex);
                    if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        output.position(info.offset);
                        output.limit(info.offset + info.size);
                        muxer.writeSampleData(track, output, info);
                    }
                    outputEos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    codec.releaseOutputBuffer(outputIndex, false);
                }
            }

            if (!muxerStarted) throw new IllegalStateException("No AAC track produced");
            
            muxer.stop(); muxerStarted = false;
            codec.stop(); codecStarted = false;

            try (FileInputStream in = new FileInputStream(temp); 
                 java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream((int) temp.length())) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                return out.toByteArray();
            }

        } finally {
            if (muxer != null) {
                if (muxerStarted) try { muxer.stop(); } catch (Exception e) {}
                try { muxer.release(); } catch (Exception e) {}
            }
            if (codec != null) {
                if (codecStarted) try { codec.stop(); } catch (Exception e) {}
                try { codec.release(); } catch (Exception e) {}
            }
            temp.delete();
        }
    }
}
