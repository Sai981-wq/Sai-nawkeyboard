package com.sainaw.mm.board;

import android.content.Context;
import android.os.Build;
import java.nio.charset.StandardCharsets;

/** One encoding decision, made before any network connection. Never retries a failed upload. */
final class ShanVoicePayload {
    interface OpusEncoder { byte[] encode(byte[] pcm, int rate) throws Exception; }
    final byte[] bytes;
    final String filename, mime;
    private ShanVoicePayload(byte[] bytes, String filename, String mime) {
        this.bytes = bytes;
        this.filename = filename;
        this.mime = mime;
    }
    static ShanVoicePayload wav(byte[] wav) {
        return new ShanVoicePayload(wav, "speech.wav", "audio/wav");
    }
    static ShanVoicePayload prepare(Context context, byte[] pcm, int rate) {
        return prepare(pcm, rate, Build.VERSION.SDK_INT,
                (samples, sampleRate) -> ShanOpus.encode(context, samples, sampleRate));
    }
    static ShanVoicePayload prepare(byte[] pcm, int rate, int sdk, OpusEncoder encoder) {
        byte[] validated = ShanAudioProcessor.prepare(pcm, rate);
        if (sdk >= 29) {
            try {
                byte[] ogg = encoder.encode(validated, rate);
                if (ogg != null && ogg.length > 27 && ogg[0] == 'O' && ogg[1] == 'g'
                        && ogg[2] == 'g' && ogg[3] == 'S') {
                    return new ShanVoicePayload(ogg, "speech.ogg", "audio/ogg");
                }
            } catch (Exception | LinkageError unavailable) {
                // Missing codec/muxer, device limits or an encode error: preserve the PCM.
                // This is local fallback only. No audio has been sent, and no HTTP retry occurs.
            }
        }
        return wav(ShanWav.encode(validated, rate));
    }
    /** Production stop path: codec work was already done during capture. */
    static ShanVoicePayload completed(byte[] pcm, int rate, byte[] ogg) {
        byte[] validated = ShanAudioProcessor.prepare(pcm, rate);
        if (ogg != null && ogg.length > 27 && ogg[0] == 'O' && ogg[1] == 'g'
                && ogg[2] == 'g' && ogg[3] == 'S')
            return new ShanVoicePayload(ogg, "speech.ogg", "audio/ogg");
        return wav(ShanWav.encode(validated, rate));
    }
    byte[] multipartHead(String boundary) {
        return ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
                + filename + "\"\r\nContent-Type: " + mime + "\r\n\r\n").getBytes(StandardCharsets.UTF_8);
    }
}
