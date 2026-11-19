package com.sainaw.mm.board;

/** Conservative amplitude-only boundary detector for mono little-endian PCM16. */
public final class ShanPhraseDetector {
    private static final int SILENCE_RMS_MAX = 65;
    private static final int SILENCE_PEAK_MAX = 200;
    private final int sampleRate;
    private final long confirmSamples;
    private final long boundarySamples;
    private long candidateSamples;
    private long silenceSamples;
    private boolean confirmed;

    public ShanPhraseDetector(int rate) {
        if (rate <= 0) throw new IllegalArgumentException("rate must be positive");
        sampleRate = rate;
        confirmSamples = samplesForMillis(60);
        boundarySamples = samplesForMillis(500);
    }

    /** Accept caller-framed PCM16 samples; true means confirmed speech reached a silence boundary. */
    public boolean accept(byte[] frame, int n) {
        if (frame == null) throw new IllegalArgumentException("frame must not be null");
        if (n < 0 || n > frame.length) throw new IllegalArgumentException("invalid byte count");
        if ((n & 1) != 0) throw new IllegalArgumentException("PCM16 byte count must be even");
        int count = n / 2;
        if (count == 0) return false;
        long squares = 0;
        int peak = 0;
        for (int i = 0; i < n; i += 2) {
            int sample = (short) ((frame[i] & 0xff) | (frame[i + 1] << 8));
            int magnitude = Math.abs(sample);
            peak = Math.max(peak, magnitude);
            squares += (long) sample * sample;
        }
        int rms = (int) Math.sqrt((double) squares / count);
        if (rms <= SILENCE_RMS_MAX && peak <= SILENCE_PEAK_MAX) {
            if (!confirmed) candidateSamples = 0; // Require 60ms contiguous candidate audio, not accumulated clicks.
            if (confirmed && (silenceSamples += count) >= boundarySamples) {
                reset();
                return true;
            }
            return false;
        }
        // Uncertain/quiet non-silence resets the silence run: prefer deferring over cutting.
        silenceSamples = 0;
        candidateSamples += count;
        if (!confirmed && candidateSamples >= confirmSamples) confirmed = true;
        return false;
    }

    public boolean hasSpeech() { return confirmed; }

    public void reset() {
        candidateSamples = 0;
        silenceSamples = 0;
        confirmed = false;
    }

    private long samplesForMillis(long millis) {
        return ((long) sampleRate * millis + 999) / 1000;
    }
}
