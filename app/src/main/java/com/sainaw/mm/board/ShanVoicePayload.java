package com.sainaw.mm.board;

import android.content.Context;
import android.os.Build;
import java.nio.charset.StandardCharsets;

/** One encoding decision. Uses Opus for API 29+, fallback to AAC (.m4a) for older devices. */
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

    /** AAC Encoding အလုပ်မလုပ်မှသာ WAV ကို အသုံးပြုမည် */
    static ShanVoicePayload aacOrWav(Context context, byte[] pcm, int rate) {
        try {
            byte[] aac = ShanAac.encode(context, pcm, rate);
            if (aac != null && aac.length > 0) {
                return new ShanVoicePayload(aac, "speech.m4a", "audio/m4a");
            }
        } catch (Exception e) {
            // AAC Error
        }
        return wav(ShanWav.encode(pcm, rate));
    }

    static ShanVoicePayload prepare(Context context, byte[] pcm, int rate) {
        return prepare(context, pcm, rate, Build.VERSION.SDK_INT,
                (samples, sampleRate) -> ShanOpus.encode(context, samples, sampleRate));
    }
    
    static ShanVoicePayload prepare(Context context, byte[] pcm, int rate, int sdk, OpusEncoder encoder) {
        byte[] validated = ShanAudioProcessor.prepare(pcm, rate);
        if (sdk >= 29) {
            try {
                byte[] ogg = encoder.encode(validated, rate);
                if (ogg != null && ogg.length > 27 && ogg[0] == 'O' && ogg[1] == 'g'
                        && ogg[2] == 'g' && ogg[3] == 'S') {
                    return new ShanVoicePayload(ogg, "speech.ogg", "audio/ogg");
                }
            } catch (Exception | LinkageError unavailable) {
            }
        }
        return aacOrWav(context, validated, rate);
    }

    /** Context ကိုပါ ထည့်သွင်းပေးရန် ပြင်ဆင်ထားသည် */
    static ShanVoicePayload completed(Context context, byte[] pcm, int rate, byte[] ogg) {
        byte[] validated = ShanAudioProcessor.prepare(pcm, rate);
        if (ogg != null && ogg.length > 27 && ogg[0] == 'O' && ogg[1] == 'g'
                && ogg[2] == 'g' && ogg[3] == 'S')
            return new ShanVoicePayload(ogg, "speech.ogg", "audio/ogg");
            
        return aacOrWav(context, validated, rate);
    }

    byte[] multipartHead(String boundary) {
        return ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
                + filename + "\"\r\nContent-Type: " + mime + "\r\n\r\n").getBytes(StandardCharsets.UTF_8);
    }
}

