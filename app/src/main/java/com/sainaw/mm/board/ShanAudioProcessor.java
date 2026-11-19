package com.sainaw.mm.board;

/** Lossless validation of PCM16 mono; no gain, denoising, silence threshold or trimming. */
public final class ShanAudioProcessor {
    private static final int MIN_DURATION_MS = 100;
    private ShanAudioProcessor() { }
    /** Preserve every sample, including quiet syllables and boundary pauses. */
    public static byte[] prepare(byte[] pcm, int rate) {
        if (pcm == null) throw new IllegalArgumentException("PCM must not be null");
        if (rate <= 0) throw new IllegalArgumentException("Sample rate must be positive");
        if ((pcm.length & 1) != 0) throw new IllegalArgumentException("PCM16 data must contain complete samples");
        if ((pcm.length / 2L) * 1000L < (long) rate * MIN_DURATION_MS)
            throw new IllegalArgumentException("Audio must be at least 100 ms");
        boolean nonzero = false;
        for (byte value : pcm) if (value != 0) { nonzero = true; break; }
        if (!nonzero) throw new IllegalArgumentException("Audio contains only digital zero");
        return pcm.clone();
    }
}
