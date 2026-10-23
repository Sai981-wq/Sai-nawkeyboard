package com.sainaw.mm.board;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Codec selection and upload metadata only; fake encoder never records or uploads audio. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 34})
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
public class VoicePayloadTest {
    private byte[] pcm() {
        byte[] p = new byte[6400];
        p[0] = 1; p[p.length - 2] = 2;
        return p;
    }
    @Test public void olderAndroidUsesOriginalLosslessWavWithoutCallingEncoder() {
        byte[] original = pcm();
        ShanVoicePayload p = ShanVoicePayload.prepare(original, 16000, 28,
                (samples, rate) -> { throw new AssertionError("Opus requires API 29"); });
        assertEquals("speech.wav", p.filename);
        assertEquals("audio/wav", p.mime);
        assertArrayEquals(ShanWav.encode(original, 16000), p.bytes);
    }
    @Test public void supportedEncoderGetsAllSamplesAndCorrectRateAndOggMetadata() {
        byte[] original = pcm();
        byte[] fakeOgg = new byte[100];
        fakeOgg[0]='O'; fakeOgg[1]='g'; fakeOgg[2]='g'; fakeOgg[3]='S';
        ShanVoicePayload p = ShanVoicePayload.prepare(original, 16000, 29, (samples, rate) -> {
            assertArrayEquals(original, samples);
            assertEquals(16000, rate);
            return fakeOgg;
        });
        assertSame(fakeOgg, p.bytes);
        assertEquals("speech.ogg", p.filename);
        assertEquals("audio/ogg", p.mime);
        assertEquals("--test\r\nContent-Disposition: form-data; name=\"file\"; filename=\"speech.ogg\"\r\nContent-Type: audio/ogg\r\n\r\n",
                new String(p.multipartHead("test"), StandardCharsets.UTF_8));
    }
    @Test public void missingCodecFallsBackLocallyWithoutLosingBoundarySamples() {
        byte[] original = pcm();
        ShanVoicePayload p = ShanVoicePayload.prepare(original, 16000, 34,
                (samples, rate) -> { throw new IllegalStateException("No device encoder"); });
        assertArrayEquals(ShanWav.encode(original, 16000), p.bytes);
        assertEquals("audio/wav", p.mime);
    }
    @Test public void malformedOrEmptyEncoderOutputDoesNotGetMislabeledAsOgg() {
        for (byte[] bad : new byte[][]{null, new byte[0], new byte[100]}) {
            ShanVoicePayload p = ShanVoicePayload.prepare(pcm(), 16000, 34, (samples, rate) -> bad);
            assertEquals("speech.wav", p.filename);
            assertEquals("audio/wav", p.mime);
        }
    }
    @Test public void invalidPcmNeverInvokesEncoder() {
        try {
            ShanVoicePayload.prepare(new byte[1], 16000, 34,
                    (samples, rate) -> { throw new AssertionError("Invalid PCM"); });
            fail("Should reject partial sample");
        } catch (IllegalArgumentException expected) { }
    }
}
