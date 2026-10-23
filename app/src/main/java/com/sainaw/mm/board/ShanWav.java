package com.sainaw.mm.board;

/** PCM16 mono WAV encoder; no Android or network dependency. */
final class ShanWav {
    private ShanWav() { }
    static byte[] encode(byte[] pcm, int rate) {
        if (pcm == null || (pcm.length & 1) != 0 || rate <= 0)
            throw new IllegalArgumentException("PCM16 requires complete samples and a positive rate");
        if (rate > Integer.MAX_VALUE / 2 || pcm.length > Integer.MAX_VALUE - 44)
            throw new IllegalArgumentException("WAV header would overflow");
        java.nio.ByteBuffer out = java.nio.ByteBuffer.allocate(44 + pcm.length)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        out.put(new byte[]{'R','I','F','F'}).putInt(36 + pcm.length);
        out.put(new byte[]{'W','A','V','E','f','m','t',' '}).putInt(16);
        out.putShort((short) 1).putShort((short) 1).putInt(rate).putInt(rate * 2);
        out.putShort((short) 2).putShort((short) 16);
        out.put(new byte[]{'d','a','t','a'}).putInt(pcm.length).put(pcm);
        return out.array();
    }
}
